package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.ui.adapter.WebDavEntryAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.webdav.WebDavClient;
import com.github.tvbox.osc.webdav.WebDavConfig;
import com.github.tvbox.osc.webdav.WebDavEntry;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;

public class WebDavBrowserActivity extends BaseActivity {
    public static final int SUBTITLE_REQUEST_CODE = 0x5A32;
    public static final String EXTRA_PICK_SUBTITLE = "webdav_pick_subtitle";
    public static final String EXTRA_START_URL = "webdav_start_url";
    public static final String EXTRA_SELECTED_SUBTITLE_PATH = "webdav_selected_subtitle_path";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<String> parents = new ArrayList<>();
    private WebDavEntryAdapter adapter;
    private TextView pathView;
    private TextView statusView;
    private ProgressBar progress;
    private WebDavConfig config;
    private String currentUrl;
    private boolean needsReload;
    private boolean loading;
    private boolean subtitlePickerMode;

    public static Intent createSubtitlePickerIntent(Context context, String directoryUrl) {
        return new Intent(context, WebDavBrowserActivity.class)
                .putExtra(EXTRA_PICK_SUBTITLE, true)
                .putExtra(EXTRA_START_URL, directoryUrl);
    }

    public static String parentDirectoryUrl(String fileUrl) {
        HttpUrl url = HttpUrl.parse(fileUrl);
        if (url == null) {
            return null;
        }
        HttpUrl.Builder builder = url.newBuilder().query(null).fragment(null);
        int lastSegment = url.pathSegments().size() - 1;
        if (lastSegment >= 0) {
            builder.removePathSegment(lastSegment);
        }
        HttpUrl parent = builder.build();
        if (!parent.encodedPath().endsWith("/")) {
            parent = parent.newBuilder().addPathSegment("").build();
        }
        return parent.toString();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_webdav_browser;
    }

    @Override
    protected void init() {
        pathView = findViewById(R.id.tvWebDavPath);
        statusView = findViewById(R.id.tvWebDavStatus);
        progress = findViewById(R.id.progressWebDav);
        adapter = new WebDavEntryAdapter();
        subtitlePickerMode = getIntent().getBooleanExtra(EXTRA_PICK_SUBTITLE, false);
        if (subtitlePickerMode) {
            ((TextView) findViewById(R.id.tvWebDavTitle)).setText("选择外挂字幕");
            findViewById(R.id.btnWebDavSettings).setVisibility(View.GONE);
        }
        com.owen.tvrecyclerview.widget.TvRecyclerView list = findViewById(R.id.rvWebDavEntries);
        list.setLayoutManager(new V7LinearLayoutManager(this, V7LinearLayoutManager.VERTICAL, false));
        list.setAdapter(adapter);
        adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter itemAdapter, View view, int position) {
                WebDavEntry entry = adapter.getItem(position);
                if (entry == null) {
                    return;
                }
                if (subtitlePickerMode) {
                    selectSubtitle(entry);
                    return;
                }
                if (entry.directory) {
                    parents.add(currentUrl);
                    loadDirectory(entry.url);
                } else if (isVideo(entry.name)) {
                    openVideo(entry);
                } else {
                    Toast.makeText(WebDavBrowserActivity.this, "此文件不是可播放的视频", Toast.LENGTH_SHORT).show();
                }
            }
        });
        findViewById(R.id.btnWebDavBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                goParent();
            }
        });
        findViewById(R.id.btnWebDavSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                needsReload = true;
                startActivity(new Intent(WebDavBrowserActivity.this, SettingActivity.class));
            }
        });
        findViewById(R.id.btnWebDavRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                if (currentUrl != null) {
                    loadDirectory(currentUrl);
                }
            }
        });
        loadConfigAndRoot();
    }

    private void loadConfigAndRoot() {
        config = WebDavConfig.load();
        if (!config.isConfigured()) {
            currentUrl = null;
            pathView.setText("尚未配置 WebDAV");
            showStatus("请先在设置中填写 AList WebDAV 地址和账号");
            return;
        }
        parents.clear();
        String requestedStart = subtitlePickerMode
                ? getIntent().getStringExtra(EXTRA_START_URL) : null;
        currentUrl = requestedStart == null || requestedStart.isEmpty()
                ? config.baseUrl : requestedStart;
        loadDirectory(currentUrl);
    }

    private void loadDirectory(final String targetUrl) {
        if (loading || config == null || !config.isConfigured()) {
            return;
        }
        loading = true;
        progress.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
        pathView.setText(targetUrl);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final List<WebDavEntry> entries;
                try {
                    entries = new WebDavClient(config).list(targetUrl);
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            progress.setVisibility(View.GONE);
                            showStatus("无法读取目录：" + e.getMessage());
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        loading = false;
                        currentUrl = targetUrl;
                        pathView.setText(targetUrl);
                        progress.setVisibility(View.GONE);
                        List<WebDavEntry> visibleEntries = subtitlePickerMode
                                ? subtitleEntries(entries) : entries;
                        statusView.setVisibility(visibleEntries.isEmpty() ? View.VISIBLE : View.GONE);
                        statusView.setText(visibleEntries.isEmpty()
                                ? (subtitlePickerMode ? "此目录没有支持的字幕文件" : "此目录为空") : "");
                        adapter.setNewData(visibleEntries);
                        if (!visibleEntries.isEmpty()) {
                            findViewById(R.id.rvWebDavEntries).requestFocus();
                        }
                    }
                });
            }
        });
    }

    private void goParent() {
        if (loading) {
            return;
        }
        if (parents.isEmpty()) {
            finish();
            return;
        }
        String parent = parents.remove(parents.size() - 1);
        loadDirectory(parent);
    }

    private void openVideo(final WebDavEntry video) {
        if (loading) {
            return;
        }
        loading = true;
        progress.setVisibility(View.VISIBLE);
        showStatus("正在查找同名字幕");
        final WebDavEntry subtitle = findSiblingSubtitle(video);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                File localSubtitle = null;
                if (subtitle != null) {
                    try {
                        localSubtitle = new WebDavClient(config).downloadSubtitle(
                                WebDavBrowserActivity.this, subtitle);
                    } catch (IOException ignored) {
                        // Keep video playback available when a sidecar subtitle cannot be fetched.
                    }
                }
                final File result = localSubtitle;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        loading = false;
                        progress.setVisibility(View.GONE);
                        statusView.setVisibility(View.GONE);
                        Intent intent = new Intent(WebDavBrowserActivity.this, PlayActivity.class);
                        intent.putExtra(PlayActivity.EXTRA_WEBDAV_URL, video.url);
                        intent.putExtra(PlayActivity.EXTRA_WEBDAV_TITLE, video.name);
                        if (result != null && result.isFile()) {
                            intent.putExtra(PlayActivity.EXTRA_WEBDAV_SUBTITLE, result.getAbsolutePath());
                        } else if (subtitle != null) {
                            Toast.makeText(WebDavBrowserActivity.this,
                                    "同名字幕读取失败，继续播放视频", Toast.LENGTH_SHORT).show();
                        }
                        startActivity(intent);
                    }
                });
            }
        });
    }

    private void selectSubtitle(final WebDavEntry subtitle) {
        if (subtitle.directory || !isSubtitle(subtitle.name) || loading) {
            return;
        }
        loading = true;
        progress.setVisibility(View.VISIBLE);
        showStatus("正在读取字幕");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File result;
                try {
                    result = new WebDavClient(config).downloadSubtitle(
                            WebDavBrowserActivity.this, subtitle);
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            progress.setVisibility(View.GONE);
                            showStatus("读取字幕失败：" + e.getMessage());
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        Intent resultIntent = new Intent()
                                .putExtra(EXTRA_SELECTED_SUBTITLE_PATH, result.getAbsolutePath());
                        setResult(RESULT_OK, resultIntent);
                        finish();
                    }
                });
            }
        });
    }

    private WebDavEntry findSiblingSubtitle(WebDavEntry video) {
        String videoStem = stem(video.name);
        String[] preferred = {"srt", "ass", "ssa", "vtt", "ttml", "scc", "stl"};
        List<WebDavEntry> entries = adapter.getData();
        for (String extension : preferred) {
            String expected = videoStem + "." + extension;
            for (WebDavEntry entry : entries) {
                if (!entry.directory && entry.name.equalsIgnoreCase(expected)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isVideo(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        String[] extensions = {".mkv", ".mp4", ".m4v", ".mov", ".avi", ".wmv", ".flv",
                ".ts", ".m2ts", ".mpg", ".mpeg", ".webm", ".m3u8", ".mpd"};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static List<WebDavEntry> subtitleEntries(List<WebDavEntry> entries) {
        ArrayList<WebDavEntry> result = new ArrayList<>();
        for (WebDavEntry entry : entries) {
            if (!entry.directory && isSubtitle(entry.name)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean isSubtitle(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        String[] extensions = {".srt", ".ass", ".ssa", ".scc", ".stl", ".ttml", ".vtt"};
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private void showStatus(String message) {
        statusView.setText(message);
        statusView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needsReload) {
            needsReload = false;
            loadConfigAndRoot();
        }
    }

    @Override
    public void onBackPressed() {
        if (!parents.isEmpty()) {
            goParent();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
