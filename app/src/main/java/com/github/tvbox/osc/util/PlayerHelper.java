package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import com.github.tvbox.osc.player.MPVCompatManager;
import com.github.tvbox.osc.player.MPVCompatPlayerFactory;
import com.github.tvbox.osc.player.Java64CodecPlayerFactory;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.JustPlayer;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import xyz.doikki.videoplayer.player.AndroidMediaPlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    private static final int[] ORDERED_PLAYER_TYPES = new int[]{0, 6, 10, 11, 12, 13};
    public static final int PLAYER_TYPE_SYSTEM = 0;
    public static final int PLAYER_TYPE_DOLBY_VISION_COMPAT = 6;
    public static final int[] JAVA64_TOUCH_PHONE_SCALE_CYCLE = new int[]{
            VideoView.SCREEN_SCALE_DEFAULT,
            VideoView.SCREEN_SCALE_MATCH_PARENT,
            VideoView.SCREEN_SCALE_CENTER_CROP
    };

    private static boolean isJava64TouchPhone(@androidx.annotation.Nullable Context context) {
        if (!com.github.tvbox.osc.base.App.isJava64Build()) {
            return false;
        }
        if (context == null) {
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                    return false;
                }
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    private static boolean shouldUseTextureRenderForSystemPlayer(@androidx.annotation.Nullable Context context,
                                                                 @androidx.annotation.Nullable JSONObject playerCfg) {
        if (!isJava64TouchPhone(context)) {
            return false;
        }
        if (playerCfg != null && playerCfg.optBoolean(HawkConfig.PLAYER_IS_LIVE, false)) {
            // 直播页已经通过 LivePlayerManager 带下来了用户的渲染配置。
            // 这里不要再强行改成 TextureView，否则会把用户选中的 SurfaceView 覆盖掉，
            // 64 位直播就会退回到有声无画的错误路径。
            return false;
        }
        String outputMode = playerCfg == null ? "" : playerCfg.optString("dvm", "");
        boolean hdrOutputRequested = (playerCfg != null && playerCfg.optInt("hro", 0) == 1)
                || (!TextUtils.isEmpty(outputMode) && !"sdr".equalsIgnoreCase(outputMode));
        return !hdrOutputRequested;
    }

    private static boolean isMappingOutputMode(@androidx.annotation.Nullable String outputMode) {
        if (TextUtils.isEmpty(outputMode)) {
            return false;
        }
        String lower = outputMode.trim().toLowerCase(Locale.US);
        return lower.startsWith("map-");
    }

    private static boolean shouldUseJava64CodecCompatPlayer(@androidx.annotation.Nullable Context context,
                                                            @androidx.annotation.Nullable JSONObject playerCfg) {
        if (!isJava64TouchPhone(context)) {
            return false;
        }
        String outputMode = playerCfg == null ? "" : playerCfg.optString("dvm", "");
        boolean hdrOutputRequested = (playerCfg != null && playerCfg.optInt("hro", 0) == 1)
                || (!TextUtils.isEmpty(outputMode) && !"sdr".equalsIgnoreCase(outputMode));
        if (!hdrOutputRequested || isMappingOutputMode(outputMode)) {
            return false;
        }
        HdrDeviceSupport.Capabilities caps = HdrDeviceSupport.query(context);
        if ("dv-base-hdr".equalsIgnoreCase(outputMode)) {
            return caps.supportsNativeDolbyVisionRoute(true);
        }
        return caps.hevcMain10Decoder;
    }

    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        updateCfg(videoView,playerCfg,-1);
    }
    public static void updateCfg(VideoView videoView, JSONObject playerCfg,int forcePlayerType) {
        int playerType = Hawk.get(HawkConfig.PLAY_TYPE, 0);
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 1);
        int scale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
        boolean preferHdrOutput = true;
        Context context = videoView == null ? null : videoView.getContext();
        boolean preferTextureSystemRender = shouldUseTextureRenderForSystemPlayer(context, playerCfg);
        try {
            playerType = playerCfg.getInt("pl");
            renderType = playerCfg.getInt("pr");
            scale = playerCfg.getInt("sc");
            preferHdrOutput = !"sdr".equalsIgnoreCase(playerCfg.optString("dvm", "hdr"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if(forcePlayerType>=0)playerType = forcePlayerType;
        if (playerType != PLAYER_TYPE_SYSTEM && playerType != PLAYER_TYPE_DOLBY_VISION_COMPAT) {
            playerType = PLAYER_TYPE_SYSTEM;
        }
        if (playerType == PLAYER_TYPE_SYSTEM) {
            renderType = preferTextureSystemRender ? 0 : 1;
        }
        if (playerType == PLAYER_TYPE_DOLBY_VISION_COMPAT) {
            renderType = 1;
            scale = VideoView.SCREEN_SCALE_DEFAULT;
        }
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        if(videoView!=null){
            if (playerType == PLAYER_TYPE_DOLBY_VISION_COMPAT) {
                String outputMode = playerCfg == null ? "base-hdr" : playerCfg.optString("dvm", preferHdrOutput ? "base-hdr" : "sdr");
                boolean useJava64CodecPlayer = shouldUseJava64CodecCompatPlayer(context, playerCfg);
                if (useJava64CodecPlayer) {
                    videoView.setPlayerFactory(Java64CodecPlayerFactory.create());
                } else {
                    MPVCompatManager.setOutputMode(outputMode);
                    videoView.setPlayerFactory(MPVCompatPlayerFactory.create());
                }
                renderViewFactory = SurfaceRenderViewFactory.create();
                LOG.i("echo-player-backend compat="
                        + (useJava64CodecPlayer ? "java64-codec" : "mpv")
                        + " output=" + outputMode
                        + " java64TouchPhone=" + isJava64TouchPhone(context));
            } else {
                boolean useJava64LiveCodecPlayer = isJava64TouchPhone(context)
                        && playerCfg != null
                        && playerCfg.optBoolean(HawkConfig.PLAYER_IS_LIVE, false);
                if (useJava64LiveCodecPlayer) {
                    videoView.setPlayerFactory(Java64CodecPlayerFactory.create());
                    LOG.i("echo-player-backend live=java64-codec java64TouchPhone=true");
                } else {
                    videoView.setPlayerFactory(AndroidMediaPlayerFactory.create());
                }
                renderViewFactory = preferTextureSystemRender
                        ? TextureRenderViewFactory.create()
                        : SurfaceRenderViewFactory.create();
            }
            String routeMode = "";
            int hdrOut = 0;
            if (playerCfg != null) {
                hdrOut = playerCfg.optInt("hro", 0);
                if (playerType == PLAYER_TYPE_DOLBY_VISION_COMPAT) {
                    routeMode = playerCfg.optString("dvm", "");
                }
            }
            LOG.i("echo-player-cfg player=" + playerType
                    + " render=" + getRenderName(renderType)
                    + " java64TouchPhone=" + isJava64TouchPhone(context)
                    + " hdrOut=" + hdrOut
                    + " dvm=" + routeMode
                    + " scale=" + scale);
            videoView.setRenderViewFactory(renderViewFactory);
            videoView.setScreenScaleType(sanitizeScaleForPlayer(playerType, scale));
        }
    }

    public static void updateCfg(VideoView videoView) {
        boolean preferTextureSystemRender = shouldUseTextureRenderForSystemPlayer(videoView == null ? null : videoView.getContext(), null);
        int renderType = preferTextureSystemRender ? 0 : 1;
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        videoView.setPlayerFactory(AndroidMediaPlayerFactory.create());
        videoView.setRenderViewFactory(renderViewFactory);
    }


    public static void init() {
    }

    public static String getPlayerName(int playType) {
        HashMap<Integer, String> playersInfo = getPlayersInfo();
        if (playersInfo.containsKey(playType)) {
            return playersInfo.get(playType);
        } else {
            return "系统播放器";
        }
    }

    private static HashMap<Integer, String> mPlayersInfo = null;
    public static HashMap<Integer, String> getPlayersInfo() {
        if (mPlayersInfo == null) {
            HashMap<Integer, String> playersInfo = new HashMap<>();
            playersInfo.put(PLAYER_TYPE_SYSTEM, "系统硬解播放器");
            playersInfo.put(PLAYER_TYPE_DOLBY_VISION_COMPAT, "杜比视界兼容播放器");
            playersInfo.put(10, "MX播放器");
            playersInfo.put(11, "Reex播放器");
            playersInfo.put(12, "Just Player");
            playersInfo.put(13, "附近TVBox");
            mPlayersInfo = playersInfo;
        }
        return mPlayersInfo;
    }

    private static HashMap<Integer, Boolean> mPlayersExistInfo = null;
    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        if (mPlayersExistInfo == null) {
            HashMap<Integer, Boolean> playersExist = new HashMap<>();
            playersExist.put(PLAYER_TYPE_SYSTEM, true);
            playersExist.put(PLAYER_TYPE_DOLBY_VISION_COMPAT, true);
            playersExist.put(10, MXPlayer.getPackageInfo() != null);
            playersExist.put(11, ReexPlayer.getPackageInfo() != null);
            playersExist.put(12, JustPlayer.getPackageInfo() != null);
            playersExist.put(13, RemoteTVBox.getAvalible() != null);
            mPlayersExistInfo = playersExist;
        }
        return mPlayersExistInfo;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    public static ArrayList<Integer> getExistPlayerTypes() {
        ArrayList<Integer> existPlayers = new ArrayList<>();
        for (int playerType : ORDERED_PLAYER_TYPES) {
            if (Boolean.TRUE.equals(getPlayerExist(playerType))) {
                existPlayers.add(playerType);
            }
        }
        if (!existPlayers.contains(PLAYER_TYPE_SYSTEM)) {
            existPlayers.add(0, PLAYER_TYPE_SYSTEM);
        }
        return existPlayers;
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers, 0L);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = JustPlayer.run(activity, url, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, url, title, subtitle, headers);
                break;
            }
        }
        return callResult;
    }

    public static boolean isSystemPlayerType(int playerType) {
        return playerType == PLAYER_TYPE_SYSTEM;
    }

    public static boolean isBuiltInCompatPlayerType(int playerType) {
        return playerType == PLAYER_TYPE_DOLBY_VISION_COMPAT;
    }

    public static boolean isInternalPlayerType(int playerType) {
        return playerType == PLAYER_TYPE_SYSTEM || playerType == PLAYER_TYPE_DOLBY_VISION_COMPAT;
    }

    public static boolean isExternalPlayerType(int playerType) {
        return playerType == 10 || playerType == 11 || playerType == 12 || playerType == 13;
    }

    public static int getNextCompatiblePlayerType(int playerType) {
        return PLAYER_TYPE_SYSTEM;
    }

    public static int getHdrCompatiblePlayerType() {
        return PLAYER_TYPE_DOLBY_VISION_COMPAT;
    }

    private static boolean runSystemPlayer(Activity activity, String url, String title, HashMap<String, String> headers) {
        if (activity == null || TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.parse(url), guessMimeType(url));
            intent.putExtra(Intent.EXTRA_TITLE, title);
            intent.putExtra("title", title);
            intent.putExtra("name", title);
            PackageManager packageManager = activity.getPackageManager();
            if (packageManager == null || intent.resolveActivity(packageManager) == null) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    private static String guessMimeType(String url) {
        if (TextUtils.isEmpty(url)) {
            return "video/*";
        }
        String lower = url.toLowerCase(Locale.US);
        if (PlaybackUrlNormalizer.isHlsLike(lower)) {
            return "application/vnd.apple.mpegurl";
        }
        if (lower.contains(".mpd") || lower.contains("type=mpd")) {
            return "application/dash+xml";
        }
        if (lower.contains(".mp4")) {
            return "video/mp4";
        }
        if (lower.contains(".mkv") || lower.contains(".webm")) {
            return "video/*";
        }
        return "video/*";
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    public static String getScaleName(int screenScaleType) {
        String scaleText = "默认";
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
                scaleText = "默认";
                break;
            case VideoView.SCREEN_SCALE_16_9:
                scaleText = "16:9";
                break;
            case VideoView.SCREEN_SCALE_4_3:
                scaleText = "4:3";
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                scaleText = "拉伸";
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                scaleText = "填充";
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                scaleText = "原始";
                break;
        }
        return scaleText;
    }

    public static int sanitizeScaleForPlayer(int playerType, int scaleType) {
        if (playerType != PLAYER_TYPE_SYSTEM && playerType != PLAYER_TYPE_DOLBY_VISION_COMPAT) {
            return scaleType;
        }
        switch (scaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                return scaleType;
            default:
                return VideoView.SCREEN_SCALE_DEFAULT;
        }
    }

    public static int nextJava64TouchPhoneScale(int currentScaleType) {
        int current = sanitizeScaleForPlayer(PLAYER_TYPE_SYSTEM, currentScaleType);
        for (int i = 0; i < JAVA64_TOUCH_PHONE_SCALE_CYCLE.length; i++) {
            if (JAVA64_TOUCH_PHONE_SCALE_CYCLE[i] == current) {
                return JAVA64_TOUCH_PHONE_SCALE_CYCLE[(i + 1) % JAVA64_TOUCH_PHONE_SCALE_CYCLE.length];
            }
        }
        return JAVA64_TOUCH_PHONE_SCALE_CYCLE[0];
    }

    public static String getDisplaySpeed(long speed,boolean show) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "Mb/s";
        else if(speed > 1024)
            return (speed / 1024) + "Kb/s";
        else
            return speed > 0?speed + "B/s":(show?"0B/s":"");
    }
    public static String getDisplaySpeedBps(long speed, boolean show) {
        long bitSpeed = speed * 8; // 字节转比特
        if (bitSpeed >= 1_000_000_000) {
            return new DecimalFormat("0.00").format(bitSpeed / 1_000_000_000d) + "Gbps";
        } else if (bitSpeed >= 1_000) {
            double mbps = bitSpeed / 1_000_000d;
            DecimalFormat df = mbps < 0.1 ? new DecimalFormat("0.00") : new DecimalFormat("0.0");
            return df.format(mbps) + "Mbps";
        }else {
            return show ? "0bps" : "";
        }
    }
}
