package com.github.tvbox.osc.bean;

public class Subtitle {

    private String name;

    private String url;

    private boolean isZip;

    private boolean selected;

    public boolean getIsZip() {
        return isZip;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setIsZip(boolean zip) {
        isZip = zip;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public String toString() {
        return "Subtitle{" +
                "name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", isZip=" + isZip +
                ", selected=" + selected +
                '}';
    }
}
