package com.github.tvbox.osc.server;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public interface DataReceiver {

    /**
     * @param text
     */
    void onTextReceived(String text);


    void onApiReceived(String url);
    void onApiReceived(String url, String liveUrl);
    void onApiReceived(String url, String liveUrl, boolean hasVodChange, boolean hasLiveChange);

    /** Returns true only after the targeted native input was filled, without saving it. */
    boolean onInputFieldReceived(RemoteInputRequest request);

    void onPushReceived(String url);
}
