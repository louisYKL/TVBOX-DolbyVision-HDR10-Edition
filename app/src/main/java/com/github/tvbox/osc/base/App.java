package com.github.tvbox.osc.base;

import android.app.Activity;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.StorageBudgetManager;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
        StorageBudgetManager.enforceAllBudgets();
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 0);
        }
        if (!Hawk.contains(HawkConfig.PLAY_RENDER)) {
            Hawk.put(HawkConfig.PLAY_RENDER, 1);
        }
        if (!Hawk.contains(HawkConfig.PLAYER_AUDIO_PASSTHROUGH)) {
            Hawk.put(HawkConfig.PLAYER_AUDIO_PASSTHROUGH, false);
        }
        String apiUrl = Hawk.get(HawkConfig.API_URL, "");
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            Hawk.put(HawkConfig.API_URL, HawkConfig.DEFAULT_API_URL);
        }
        migratePlayerRouteDefaults();
    }

    private void migratePlayerRouteDefaults() {
        int migrationVersion = Hawk.get(HawkConfig.PLAYER_ROUTE_MIGRATION_VERSION, 0);
        if (migrationVersion >= 6) {
            return;
        }

        int playType = Hawk.get(HawkConfig.PLAY_TYPE, 0);
        if (playType == 0 || playType == 1 || playType == 2 || playType == 3 || playType == 14) {
            Hawk.put(HawkConfig.PLAY_TYPE, 0);
        }

        int livePlayType = Hawk.get(HawkConfig.LIVE_PLAY_TYPE, Hawk.get(HawkConfig.PLAY_TYPE, 2));
        if (livePlayType == 0 || livePlayType == 1 || livePlayType == 2 || livePlayType == 3 || livePlayType == 14) {
            Hawk.put(HawkConfig.LIVE_PLAY_TYPE, 0);
        }

        Hawk.put(HawkConfig.PLAY_RENDER, 1);

        Hawk.put(HawkConfig.PLAYER_ROUTE_MIGRATION_VERSION, 6);
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public static boolean isJava64Build() {
        return false;
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
}
