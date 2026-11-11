package com.github.tvbox.osc.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.receiver.SearchReceiver;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
/**
 * @author pj567
 * @date :2021/1/4
 * @description:
 */
public class ControlManager {
    private static ControlManager instance;
    private RemoteServer mServer = null;
    public static Context mContext;

    private ControlManager() {

    }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) {
                    instance = new ControlManager();
                }
            }
        }
        return instance;
    }

    public static void init(Context context) {
        mContext = context.getApplicationContext();
    }

    public String getAddress(boolean local) {
        if (mServer == null) {
            return "";
        }
        return local ? mServer.getLoadAddress() : mServer.getServerAddress();
    }

    public synchronized void startServer() {
        if (mServer != null) {
            return;
        }
        while (RemoteServer.serverPort < 9999) {
            RemoteServer candidate = new RemoteServer(RemoteServer.serverPort, mContext);
            candidate.setDataReceiver(new DataReceiver() {
                @Override
                public void onTextReceived(String text) {
                    if (!TextUtils.isEmpty(text)) {
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putString("title", text);
                        intent.setAction(SearchReceiver.action);
                        intent.setPackage(mContext.getPackageName());
                        intent.setComponent(new ComponentName(mContext, SearchReceiver.class));
                        intent.putExtras(bundle);
                        mContext.sendBroadcast(intent);
                    }
                }

                @Override
                public void onApiReceived(String url) {
                    onApiReceived(url, "", true, false);
                }

                @Override
                public void onApiReceived(String url, String liveUrl) {
                    onApiReceived(url, liveUrl, true, true);
                }

                @Override
                public void onApiReceived(String url, String liveUrl, boolean hasVodChange, boolean hasLiveChange) {
                    if (hasVodChange) {
                        String newApi = url == null ? "" : url.trim();
                        String oldApi = Hawk.get(HawkConfig.API_URL, "");
                        if (!TextUtils.equals(oldApi, newApi)) {
                            Hawk.put(HawkConfig.API_URL, newApi);
                            if (!TextUtils.isEmpty(newApi)) {
                                HistoryHelper.setApiHistory(newApi);
                            }
                        }
                    }
                    if (hasLiveChange) {
                        String newLiveApi = liveUrl == null ? "" : liveUrl.trim();
                        String oldLiveApi = Hawk.get(HawkConfig.LIVE_API_URL, "");
                        if (!TextUtils.equals(oldLiveApi, newLiveApi)) {
                            Hawk.put(HawkConfig.LIVE_API_URL, newLiveApi);
                            if (!TextUtils.isEmpty(newLiveApi)) {
                                HistoryHelper.setLiveApiHistory(newLiveApi);
                            }
                        }
                    }
                }

                @Override
                public void onPushReceived(String url) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_URL, url));
                }
            });
            try {
                candidate.start();
                mServer = candidate;
                Log.i("TVBox-RemoteServer", "application startup server ready at port "
                        + RemoteServer.serverPort);
                return;
            } catch (IOException ex) {
                Log.w("TVBox-RemoteServer", "port " + RemoteServer.serverPort
                        + " unavailable; trying next port", ex);
                candidate.stop();
                RemoteServer.serverPort++;
            }
        }
        Log.e("TVBox-RemoteServer", "no available port in range 9978-9998");
    }

    public synchronized void stopServer() {
        if (mServer != null) {
            if (mServer.isStarting()) {
                mServer.stop();
            }
            mServer = null;
        }
    }
}
