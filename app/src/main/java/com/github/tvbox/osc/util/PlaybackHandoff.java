package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Cross-process playback handoff which avoids Android's ~1 MiB Binder limit.
 * The UI writes an atomic file and passes only its opaque key to :player.
 */
public final class PlaybackHandoff {
    private static final String DIRECTORY = "playback-handoff";

    private PlaybackHandoff() {
    }

    public static String write(VodInfo vodInfo, SourceBean sourceBean) {
        return write(vodInfo, sourceBean, null);
    }

    public static String write(VodInfo vodInfo, SourceBean sourceBean, String resolvedPlayResult) {
        if (vodInfo == null) {
            return null;
        }
        File directory = getDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            LOG.e("echo-play-handoff mkdir failed");
            return null;
        }
        String key = UUID.randomUUID().toString();
        File target = new File(directory, key + ".bin");
        File temp = new File(directory, key + ".tmp");
        try (ObjectOutputStream output = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(temp)))) {
            output.writeObject(new Payload(vodInfo, sourceBean, resolvedPlayResult));
            output.flush();
        } catch (Throwable error) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            LOG.e("echo-play-handoff write failed: " + error.getClass().getSimpleName());
            return null;
        }
        // Rename only after the stream is closed. This is atomic on the app cache
        // filesystem and prevents :player from observing a partially written object.
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            LOG.e("echo-play-handoff atomic rename failed");
            return null;
        }
        cleanupExpired(directory);
        return key;
    }

    public static Payload read(String key) {
        if (TextUtils.isEmpty(key) || key.indexOf('/') >= 0 || key.indexOf('\\') >= 0) {
            return null;
        }
        File file = new File(getDirectory(), key + ".bin");
        try (ObjectInputStream input = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            Object value = input.readObject();
            return value instanceof Payload ? (Payload) value : null;
        } catch (Throwable error) {
            LOG.e("echo-play-handoff read failed: " + error.getClass().getSimpleName());
            return null;
        }
    }

    public static void delete(String key) {
        if (TextUtils.isEmpty(key) || key.indexOf('/') >= 0 || key.indexOf('\\') >= 0) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(getDirectory(), key + ".bin").delete();
    }

    private static void cleanupExpired(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        for (File file : files) {
            if (file != null && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static File getDirectory() {
        return new File(App.getInstance().getCacheDir(), DIRECTORY);
    }

    public static final class Payload implements Serializable {
        private static final long serialVersionUID = 1L;
        public final VodInfo vodInfo;
        public final SourceBean sourceBean;
        public final String resolvedPlayResult;

        Payload(VodInfo vodInfo, SourceBean sourceBean, String resolvedPlayResult) {
            this.vodInfo = vodInfo;
            this.sourceBean = sourceBean;
            this.resolvedPlayResult = resolvedPlayResult;
        }
    }
}
