package com.github.tvbox.osc.player;

public class TrackInfoBean {
    public int trackId;
    public int renderId;
    public int trackGroupId;
    public int extractorTrackIndex = -1;
    public String name;
    public String language;
    public String rawLanguage;
    public String rawTitle;
    public String rawCodec;
    public String rawMimeType;
    public String mappedSubtitlePath;
    public int groupIndex;
    public int index;
    public boolean selected;
    public boolean unreliableMetadata;
    public boolean autoSelectBlocked;
    public boolean metadataOnly;
}
