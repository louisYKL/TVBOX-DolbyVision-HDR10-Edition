package com.github.tvbox.osc.subtitle;

import android.net.Uri;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;
import android.provider.OpenableColumns;

import com.github.tvbox.osc.subtitle.exception.FatalParsingException;
import com.github.tvbox.osc.subtitle.format.FormatASS;
import com.github.tvbox.osc.subtitle.format.FormatSCC;
import com.github.tvbox.osc.subtitle.format.FormatSRT;
import com.github.tvbox.osc.subtitle.format.FormatSTL;
import com.github.tvbox.osc.subtitle.format.FormatTTML;
import com.github.tvbox.osc.subtitle.format.FormatVTT;
import com.github.tvbox.osc.subtitle.format.TimedTextFileFormat;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.UnicodeReader;
import com.lzy.okgo.OkGo;

import org.apache.commons.io.input.ReaderInputStream;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URLDecoder;
import java.nio.charset.Charset;

import okhttp3.Response;

/**
 * @author AveryZhong.
 */

public class SubtitleLoader {
    private static final String TAG = SubtitleLoader.class.getSimpleName();
    private static final int MAX_SUBTITLE_BYTES = 8 * 1024 * 1024;

    private SubtitleLoader() {
        throw new AssertionError("No instance for you.");
    }

    public static void loadSubtitle(final String path, final Callback callback) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (path.startsWith("http://")
                || path.startsWith("https://")) {
            loadFromRemoteAsync(path, callback);
        } else {
            loadFromLocalAsync(path, callback);
        }
    }

    private static void loadFromRemoteAsync(final String remoteSubtitlePath,
                                            final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromRemote(remoteSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    private static void loadFromLocalAsync(final String localSubtitlePath,
                                           final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromLocal(localSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    public SubtitleLoadSuccessResult loadSubtitle(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            if (path.startsWith("http://")
                    || path.startsWith("https://")) {
                return loadFromRemote(path);
            } else {
                return loadFromLocal(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static SubtitleLoadSuccessResult loadFromRemote(final String remoteSubtitlePath)
            throws IOException, FatalParsingException, Exception {
        Log.d(TAG, "parseRemote: remoteSubtitlePath = " + remoteSubtitlePath);
        String referer = "";
        if (remoteSubtitlePath.contains("alicloud") || remoteSubtitlePath.contains("aliyundrive")) {
            referer = "https://www.aliyundrive.com/";
        } else if (remoteSubtitlePath.contains("assrt.net")) {
            referer = "https://secure.assrt.net/";
        }
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36";
        try (Response response = OkGo.<String>get(remoteSubtitlePath.split("#")[0])
                .headers("Referer", referer)
                .headers("User-Agent", ua)
                .execute()) {
            if (response.body() == null) {
                throw new IOException("Subtitle response is empty");
            }
            // A remote subtitle has the same memory budget as a local subtitle, including
            // responses without Content-Length. Never materialize an unbounded response body.
            byte[] bytes = readLimited(response.body().byteStream(), MAX_SUBTITLE_BYTES);
            UniversalDetector detector = new UniversalDetector(null);
            detector.handleData(bytes, 0, bytes.length);
            detector.dataEnd();
            String encoding = detector.getDetectedCharset();
            if (TextUtils.isEmpty(encoding)) encoding = "UTF-8";
            String content = new String(bytes, encoding);
            InputStream is = new ByteArrayInputStream(content.getBytes());
            String filename = "";
            String contentDispostion = response.header("content-disposition", "");
            String[] cd = contentDispostion.split(";");
            if (cd.length > 1) {
                String filenameInfo = cd[1];
                filenameInfo = filenameInfo.trim();
                if (filenameInfo.startsWith("filename=")) {
                    filename = filenameInfo.replace("filename=", "");
                    filename = filename.replace("\"", "");
                } else if (filenameInfo.startsWith("filename*=")) {
                    filename = filenameInfo.substring(filenameInfo.lastIndexOf("''")+2);
                }
                filename = filename.trim();
                filename = URLDecoder.decode(filename);
            }
            String filePath = filename;
            if (filename == null || filename.length() < 1) {
                Uri uri = Uri.parse(remoteSubtitlePath);
                filePath = uri.getPath();
            }
            if (!filePath.contains(".") && remoteSubtitlePath.contains("#")) {
                filePath = remoteSubtitlePath.split("#")[1];
                filePath = URLDecoder.decode(filePath);
            }
            SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
            subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filePath);
            subtitleLoadSuccessResult.fileName = filePath;
            subtitleLoadSuccessResult.content = content;
            subtitleLoadSuccessResult.subtitlePath = remoteSubtitlePath;
            return subtitleLoadSuccessResult;
        }
    }

    private static SubtitleLoadSuccessResult loadFromLocal(final String localSubtitlePath)
            throws IOException, FatalParsingException {
        Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath);
        if (localSubtitlePath.startsWith("content://")) {
            return loadFromContentUri(localSubtitlePath);
        }
        File file = new File(localSubtitlePath);
        if (!file.exists()) {
            Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath + " file not exsits");
            return null;
        }
        byte[] bytes;
        try (InputStream stream = new BufferedInputStream(new FileInputStream(file))) {
            bytes = readLimited(stream, MAX_SUBTITLE_BYTES);
        }
        return createLocalResult(bytes, localSubtitlePath, file.getName());
    }

    private static SubtitleLoadSuccessResult loadFromContentUri(String value)
            throws IOException, FatalParsingException {
        Uri uri = Uri.parse(value);
        String displayName = queryDisplayName(uri);
        try (InputStream stream = App.getInstance().getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Unable to open selected subtitle");
            }
            return createLocalResult(readLimited(stream, MAX_SUBTITLE_BYTES), value, displayName);
        }
    }

    private static SubtitleLoadSuccessResult createLocalResult(byte[] bytes,
                                                                String sourcePath,
                                                                String fileName) throws IOException, FatalParsingException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Selected subtitle is empty");
        }
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        String encoding = detector.getDetectedCharset();
        if (TextUtils.isEmpty(encoding)) encoding = "UTF-8";
        String content = new String(bytes, encoding);
        InputStream is = new ByteArrayInputStream(content.getBytes());
        SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, fileName);
        subtitleLoadSuccessResult.fileName = fileName;
        // Only remote subtitles need their source text retained for the cache-file write.
        subtitleLoadSuccessResult.subtitlePath = sourcePath;
        return subtitleLoadSuccessResult;
    }

    private static String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = App.getInstance().getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? "subtitle.srt" : fallback;
    }

    static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(64 * 1024, maxBytes));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() > maxBytes - read) {
                throw new IOException("Subtitle file exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static TimedTextObject loadAndParse(final InputStream is, final String filePath)
            throws IOException, FatalParsingException {
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        String ext = "";
        if (fileName.lastIndexOf(".") > 0) {
            ext = fileName.substring(fileName.lastIndexOf("."));
        }
        Log.d(TAG, "parse: name = " + fileName + ", ext = " + ext);
        Reader reader = new UnicodeReader(is); //处理有BOM头的utf8
        byte[] subtitleBytes = readAllBytes(new ReaderInputStream(reader, Charset.defaultCharset()));
        if (".srt".equalsIgnoreCase(ext)) {
            return new FormatSRT().parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
        } else if (".ass".equalsIgnoreCase(ext) || ".ssa".equalsIgnoreCase(ext)) {
            return new FormatASS().parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
        } else if (".stl".equalsIgnoreCase(ext)) {
            return new FormatSTL().parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
        } else if (".scc".equalsIgnoreCase(ext)) {
            return new FormatSCC().parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
        } else if (".ttml".equalsIgnoreCase(ext)) {
            return new FormatTTML().parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
        } else if (".vtt".equalsIgnoreCase(ext)) {
            return new FormatVTT().parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
        }
        TimedTextFileFormat[] arr = {new FormatSRT(), new FormatASS(), new FormatSTL(), new FormatSCC(), new FormatTTML(), new FormatVTT()};
        for(TimedTextFileFormat oneFormat : arr) {
            try {
                TimedTextObject obj = oneFormat.parseFile(fileName, new ByteArrayInputStream(subtitleBytes));
                return obj;
            } catch (Exception e) {
                continue;
            }
        }
        throw new FatalParsingException("Unsupported or unreadable subtitle format: " + fileName);
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    public interface Callback {
        void onSuccess(SubtitleLoadSuccessResult SubtitleLoadSuccessResult);

        void onError(Exception exception);
    }
}
