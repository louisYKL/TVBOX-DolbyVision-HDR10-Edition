package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.webdav.WebDavClient;
import com.github.tvbox.osc.webdav.WebDavConfig;
import com.github.tvbox.osc.webdav.WebDavEntry;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExternalSubtitlePickerDialog extends BaseDialog {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String initialWebDavDirectory;
    private final boolean preferredWebDavDirectoryProvided;
    private final Listener listener;
    private final EntryAdapter adapter = new EntryAdapter();
    private TextView pathView;
    private TextView statusView;
    private TextView localButton;
    private TextView webDavButton;
    private ProgressBar progress;
    private File localDirectory;
    private String webDavDirectory;
    private WebDavConfig webDavConfig;
    private boolean webDavMode;
    private boolean loading;

    public ExternalSubtitlePickerDialog(@NonNull @NotNull Context context,
                                        String preferredWebDavDirectory,
                                        Listener listener) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        this.listener = listener;
        webDavConfig = WebDavConfig.load();
        preferredWebDavDirectoryProvided = !TextUtils.isEmpty(preferredWebDavDirectory);
        initialWebDavDirectory = preferredWebDavDirectoryProvided
                ? preferredWebDavDirectory : webDavConfig.baseUrl;
        webDavDirectory = initialWebDavDirectory;
        localDirectory = new File("/");
        setContentView(R.layout.dialog_external_subtitle_picker);
        initializeViews();
    }

    private void initializeViews() {
        pathView = findViewById(R.id.tvExternalSubtitlePath);
        statusView = findViewById(R.id.tvExternalSubtitleStatus);
        localButton = findViewById(R.id.btnExternalSubtitleLocal);
        webDavButton = findViewById(R.id.btnExternalSubtitleWebDav);
        progress = findViewById(R.id.progressExternalSubtitle);
        com.owen.tvrecyclerview.widget.TvRecyclerView list =
                findViewById(R.id.rvExternalSubtitleFiles);
        list.setLayoutManager(new V7LinearLayoutManager(getContext(), V7LinearLayoutManager.VERTICAL, false));
        list.setAdapter(adapter);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter itemAdapter, View view, int position) {
                Entry entry = adapter.getItem(position);
                if (entry != null) {
                    openEntry(entry);
                }
            }
        });
        localButton.setOnClickListener(view -> showLocal());
        webDavButton.setOnClickListener(view -> showWebDav());
        webDavButton.setVisibility(webDavConfig != null && webDavConfig.isConfigured()
                ? View.VISIBLE : View.GONE);
    }

    @Override
    public void show() {
        super.show();
        if (getWindow() != null) {
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        if (preferredWebDavDirectoryProvided && !TextUtils.isEmpty(initialWebDavDirectory)
                && webDavConfig != null && webDavConfig.isConfigured()) {
            showWebDav();
        } else {
            showLocal();
        }
    }

    private void showLocal() {
        webDavMode = false;
        localButton.setSelected(true);
        webDavButton.setSelected(false);
        loadLocal(localDirectory);
    }

    private void showWebDav() {
        if (webDavConfig == null || !webDavConfig.isConfigured()) {
            showStatus("请先在设置中配置 WebDAV");
            return;
        }
        webDavMode = true;
        webDavDirectory = initialWebDavDirectory;
        localButton.setSelected(false);
        webDavButton.setSelected(true);
        loadWebDav(webDavDirectory);
    }

    private void loadLocal(final File directory) {
        if (loading || directory == null) {
            return;
        }
        loading = true;
        showLoading();
        worker.execute(() -> {
            File[] files = directory.listFiles();
            ArrayList<Entry> result = new ArrayList<>();
            if (files != null) {
                File parent = directory.getParentFile();
                if (parent != null && parent.canRead()) {
                    result.add(Entry.localParent(parent));
                }
                for (File file : files) {
                    if (file.isDirectory() || isSubtitle(file.getName())) {
                        result.add(Entry.local(file));
                    }
                }
                Collections.sort(result, (left, right) -> {
                    if (left.directory != right.directory) {
                        return left.directory ? -1 : 1;
                    }
                    return left.name.compareToIgnoreCase(right.name);
                });
            }
            postLoaded(result, directory.getAbsolutePath(), files == null
                    ? "目录不可读取，请确认应用已获得本地文件读取权限"
                    : result.isEmpty() ? "此目录没有外挂字幕" : null);
        });
    }

    private void loadWebDav(final String directory) {
        if (loading || TextUtils.isEmpty(directory)) {
            return;
        }
        loading = true;
        showLoading();
        worker.execute(() -> {
            try {
                List<WebDavEntry> files = new WebDavClient(webDavConfig).list(directory);
                ArrayList<Entry> result = new ArrayList<>();
                String parent = parentWebDavDirectory(directory);
                if (!TextUtils.isEmpty(parent)) {
                    result.add(Entry.remoteParent(parent));
                }
                for (WebDavEntry file : files) {
                    if (file.directory || isSubtitle(file.name)) {
                        result.add(Entry.remote(file));
                    }
                }
                postLoaded(result, directory, result.isEmpty() ? "此目录没有外挂字幕" : null);
            } catch (IOException error) {
                postLoaded(new ArrayList<>(), directory, "无法读取目录：" + error.getMessage());
            }
        });
    }

    private void postLoaded(ArrayList<Entry> entries, String path, String status) {
        Activity activity = getOwnerActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (!isShowing()) {
                return;
            }
            loading = false;
            progress.setVisibility(View.GONE);
            pathView.setText(path);
            adapter.setNewData(entries);
            if (TextUtils.isEmpty(status)) {
                statusView.setVisibility(View.GONE);
            } else {
                showStatus(status);
            }
        });
    }

    private void openEntry(Entry entry) {
        if (loading) {
            return;
        }
        if (entry.directory) {
            if (webDavMode) {
                webDavDirectory = entry.remote.url;
                loadWebDav(webDavDirectory);
            } else {
                localDirectory = new File(entry.localPath);
                loadLocal(localDirectory);
            }
            return;
        }
        if (!webDavMode) {
            dismiss();
            if (listener != null) {
                listener.onSubtitleSelected(entry.localPath);
            }
            return;
        }
        loading = true;
        showLoading();
        worker.execute(() -> {
            try {
                File cached = new WebDavClient(webDavConfig).downloadSubtitle(
                        getContext().getApplicationContext(), entry.remote);
                Activity activity = getOwnerActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.runOnUiThread(() -> {
                        if (isShowing()) {
                            dismiss();
                            if (listener != null) {
                                listener.onSubtitleSelected(cached.getAbsolutePath());
                            }
                        }
                    });
                }
            } catch (IOException error) {
                Activity activity = getOwnerActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.runOnUiThread(() -> {
                        loading = false;
                        progress.setVisibility(View.GONE);
                        showStatus("读取字幕失败：" + error.getMessage());
                    });
                }
            }
        });
    }

    private void showLoading() {
        progress.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
    }

    private void showStatus(String message) {
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (loading) {
            return;
        }
        if (webDavMode) {
            String parent = parentWebDavDirectory(webDavDirectory);
            if (!TextUtils.isEmpty(parent)) {
                webDavDirectory = parent;
                loadWebDav(webDavDirectory);
                return;
            }
        }
        if (!webDavMode && localDirectory != null) {
            File parent = localDirectory.getParentFile();
            if (parent != null && parent.canRead()) {
                localDirectory = parent;
                loadLocal(localDirectory);
                return;
            }
        }
        super.onBackPressed();
    }

    @Override
    public void dismiss() {
        worker.shutdownNow();
        super.dismiss();
    }

    private static boolean isSubtitle(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        String[] extensions = {".srt", ".ass", ".ssa", ".vtt", ".ttml", ".scc", ".stl"};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private String parentWebDavDirectory(String directory) {
        if (TextUtils.isEmpty(directory) || webDavConfig == null) {
            return null;
        }
        okhttp3.HttpUrl current = okhttp3.HttpUrl.parse(directory);
        okhttp3.HttpUrl root = okhttp3.HttpUrl.parse(webDavConfig.baseUrl);
        if (current == null || root == null
                || !current.scheme().equals(root.scheme())
                || !current.host().equalsIgnoreCase(root.host())
                || current.port() != root.port()
                || !current.encodedPath().startsWith(root.encodedPath())) {
            return null;
        }
        List<String> currentSegments = current.pathSegments();
        List<String> rootSegments = root.pathSegments();
        int currentCount = nonEmptySegmentCount(currentSegments);
        int rootCount = nonEmptySegmentCount(rootSegments);
        if (currentCount <= rootCount) {
            return null;
        }
        int removeIndex = currentSegments.size() - 1;
        if (removeIndex >= 0 && TextUtils.isEmpty(currentSegments.get(removeIndex))) {
            removeIndex--;
        }
        if (removeIndex < 0) {
            return null;
        }
        return current.newBuilder().removePathSegment(removeIndex).build().toString();
    }

    private static int nonEmptySegmentCount(List<String> segments) {
        int count = 0;
        for (String segment : segments) {
            if (!TextUtils.isEmpty(segment)) {
                count++;
            }
        }
        return count;
    }

    public interface Listener {
        void onSubtitleSelected(String path);
    }

    private static final class Entry {
        final String name;
        final String localPath;
        final boolean directory;
        final boolean parent;
        final long size;
        final WebDavEntry remote;

        private Entry(String name, String localPath, boolean directory, boolean parent,
                      long size, WebDavEntry remote) {
            this.name = name;
            this.localPath = localPath;
            this.directory = directory;
            this.parent = parent;
            this.size = size;
            this.remote = remote;
        }

        static Entry local(File file) {
            return new Entry(file.getName(), file.getAbsolutePath(), file.isDirectory(), false,
                    file.isFile() ? file.length() : 0L, null);
        }

        static Entry localParent(File parent) {
            return new Entry("..", parent.getAbsolutePath(), true, true, 0L, null);
        }

        static Entry remote(WebDavEntry file) {
            return new Entry(file.name, null, file.directory, false, file.size, file);
        }

        static Entry remoteParent(String parentUrl) {
            return new Entry("..", null, true, true, 0L,
                    new WebDavEntry("..", parentUrl, true, 0L));
        }
    }

    private static final class EntryAdapter extends BaseQuickAdapter<Entry, BaseViewHolder> {
        EntryAdapter() {
            super(R.layout.item_external_subtitle_file, new ArrayList<Entry>());
        }

        @Override
        protected void convert(BaseViewHolder helper, Entry item) {
            helper.setText(R.id.tvExternalSubtitleFile, item.name);
            helper.setText(R.id.tvExternalSubtitleFileDetail, item.parent ? "上一级" : item.directory ? "文件夹"
                    : android.text.format.Formatter.formatFileSize(helper.itemView.getContext(), item.size));
            helper.setImageResource(R.id.ivExternalSubtitleFile,
                    item.directory ? R.drawable.icon_folder : R.drawable.icon_video);
        }
    }
}
