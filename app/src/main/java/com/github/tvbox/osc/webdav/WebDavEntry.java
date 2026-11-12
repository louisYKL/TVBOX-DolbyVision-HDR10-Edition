package com.github.tvbox.osc.webdav;

public final class WebDavEntry {
    public final String name;
    public final String url;
    public final boolean directory;
    public final long size;

    WebDavEntry(String name, String url, boolean directory, long size) {
        this.name = name;
        this.url = url;
        this.directory = directory;
        this.size = size;
    }
}
