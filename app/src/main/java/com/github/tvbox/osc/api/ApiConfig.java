package com.github.tvbox.osc.api;

import static com.github.tvbox.osc.util.RegexUtils.getPattern;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.pyLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3u8;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.StorageBudgetManager;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    private static ApiConfig instance;
    private final LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private final List<LiveChannelGroup> liveChannelGroupList;
    private final List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private Map<String,String> myHosts;
    private String spider = null;
    public String wallpaper = "";

    private final SourceBean emptyHome = new SourceBean();

    private final JarLoader jarLoader = new JarLoader();
    private final JsLoader jsLoader = new JsLoader();
    private final IPyLoader pyLoader =  new pyLoader();
    private final Gson gson;

    private final String userAgent = "okhttp/3.15";

    private final String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private static final String DEFAULT_LIVE_OBJ_TEMPLATE = "{\"lives\":[{\"name\":\"txt_m3u\",\"type\":0,\"url\":\"txt_m3u_url\"}]}";

    /**
     * Keep network/configuration artifacts in the app cache tree so the shared
     * storage budget can rotate them. Older releases wrote these files directly
     * under filesDir; those paths remain read-compatible for one-way migration.
     */
    private static File apiCacheFile(String name) {
        File dir = new File(App.getInstance().getCacheDir(), "api");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, name);
    }

    private static File legacyFilesCacheFile(String name) {
        return new File(App.getInstance().getFilesDir(), name);
    }

    private static File readableCacheFile(String name) {
        File current = apiCacheFile(name);
        if (current.exists()) {
            return current;
        }
        File legacy = legacyFilesCacheFile(name);
        return legacy.exists() ? legacy : current;
    }
    private ApiConfig() {
        clearLoader();
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
        searchSourceBeanList = new ArrayList<>();
        gson = new Gson();
        Hawk.put(HawkConfig.LIVE_GROUP_LIST,new JsonArray());
        loadDefaultConfig();
        // The player runs in :player and owns a separate ApiConfig singleton. Build the
        // settings model there as well so LivePlayActivity remains usable when no remote
        // configuration or live source has been loaded yet.
        initLiveSettings();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = getPattern("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    private static byte[] getImgJar(String body){
        Pattern pattern = getPattern("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if(matcher.find()){
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    private String TempKey = null;
    private String configUrl(String apiUrl){
        String configUrl = "", pk = ";pk;";
        apiUrl=apiUrl.replace("file://", "clan://localhost/");
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            TempKey = a[1];
            if (apiUrl.startsWith("clan")){
                configUrl = clanToAddress(a[0]);
            }else if (apiUrl.startsWith("http")){
                configUrl = a[0];
            }else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + apiUrl;
        } else {
            configUrl = apiUrl;
        }
        return configUrl;
    }
    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        String resolvedApiUrl = Hawk.get(HawkConfig.API_URL, "");
        if (resolvedApiUrl == null || resolvedApiUrl.trim().isEmpty()) {
            resolvedApiUrl = HawkConfig.DEFAULT_API_URL;
            Hawk.put(HawkConfig.API_URL, resolvedApiUrl);
        }
        final String apiUrl = resolvedApiUrl;
        String liveApiUrl = Hawk.get(HawkConfig.LIVE_API_URL, "");
        resetLiveConfig();
        if (!liveApiUrl.isEmpty() && !liveApiUrl.equals(apiUrl)) {
            loadIndependentLiveConfig(liveApiUrl, useCache, callback,
                    () -> loadMainConfig(apiUrl, useCache, callback));
            return;
        }
        loadMainConfig(apiUrl, useCache, callback);
    }

    private void resetLiveConfig() {
        liveChannelGroupList.clear();
        Hawk.put(HawkConfig.LIVE_GROUP_LIST, new JsonArray());
        initLiveSettings();
    }

    private void loadIndependentLiveConfig(String liveApiUrl, boolean useCache,
                                           LoadConfigCallback callback, Runnable onComplete) {
        final String liveApiConfigUrl = configUrl(liveApiUrl);
        if (isDirectLiveUrl(liveApiUrl)) {
            try {
                initLiveSettings();
                parseLiveJson(liveApiUrl, wrapDirectLiveConfig(liveApiConfigUrl));
            } catch (Throwable th) {
                th.printStackTrace();
                callback.notice("解析直播配置失败");
            }
            onComplete.run();
            return;
        }

        final File liveCache = apiCacheFile(MD5.encode(liveApiUrl));
        final File liveReadableCache = readableCacheFile(MD5.encode(liveApiUrl));
        if (useCache && liveReadableCache.exists()) {
            try {
                parseLiveJson(liveApiUrl, liveReadableCache);
                onComplete.run();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }

        LOG.i("echo-加载独立直播");
        OkGo.<String>get(liveApiConfigUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String normalized = normalizeLiveConfigContent(liveApiUrl,
                                    liveApiConfigUrl, response.body());
                            parseLiveJson(liveApiUrl, normalized);
                            FileUtils.saveCache(liveCache, normalized);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            callback.notice("解析直播配置失败");
                            loadCachedLiveConfig(liveApiUrl, liveReadableCache);
                        } finally {
                            onComplete.run();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        if (!loadCachedLiveConfig(liveApiUrl, liveReadableCache)) {
                            callback.notice("直播配置拉取失败");
                        }
                        onComplete.run();
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() == null) {
                            return "";
                        }
                        String result = FindResult(response.body().string(), TempKey);
                        if (liveApiUrl.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(liveApiUrl), result);
                        }
                        return fixContentPath(liveApiUrl, result);
                    }
                });
    }

    private boolean loadCachedLiveConfig(String liveApiUrl, File liveReadableCache) {
        if (liveReadableCache == null || !liveReadableCache.exists()) {
            return false;
        }
        try {
            parseLiveJson(liveApiUrl, liveReadableCache);
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    private void loadMainConfig(String apiUrl, boolean useCache, LoadConfigCallback callback) {
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        final File cache = apiCacheFile(MD5.encode(apiUrl));
        final File readableCache = readableCacheFile(MD5.encode(apiUrl));
        if (useCache && readableCache.exists()) {
            try {
                parseJson(apiUrl, readableCache);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        String configUrl=configUrl(apiUrl);
        LOG.i("config-request host=" + configHost(configUrl) + " cache=" + useCache);
        // 使用内部存储，将当前配置地址写入到应用的私有目录中
        File configUrlFile = apiCacheFile("config_url");
        FileUtils.saveCache(configUrlFile,configUrl);

        OkGo.<String>get(configUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String json = response.body();
//                            LOG.i("echo-ConfigJson"+json);
                            parseJson(apiUrl, json);
                            FileUtils.saveCache(cache,json);
                            callback.success();
                        } catch (Throwable th) {
                            String contentType = response.headers() == null ? "unknown"
                                    : String.valueOf(response.headers().get("Content-Type"));
                            String diagnostic = "parse host=" + configHost(configUrl)
                                    + " status=" + response.code()
                                    + " contentType=" + contentType
                                    + " error=" + th.getClass().getSimpleName();
                            LOG.e("config-" + diagnostic);
                            Log.e("TVBox-Config", "config-" + diagnostic);
                            if (!loadCachedMainConfig(apiUrl, readableCache, callback)) {
                                callback.error("解析配置失败");
                            }
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        String diagnostic = "fetch-failed host=" + configHost(configUrl)
                                + " status=" + response.code()
                                + " error=" + (response.getException() == null ? "unknown"
                                : response.getException().getClass().getSimpleName());
                        LOG.e("config-" + diagnostic);
                        Log.e("TVBox-Config", "config-" + diagnostic);
                        if (loadCachedMainConfig(apiUrl, readableCache, callback)) {
                            return;
                        }
                        callback.error("拉取配置失败\n" + (response.getException() != null ? response.getException().getMessage() : ""));
                    }

                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        String result = "";
                        if (response.body() == null) {
                            result = "";
                        } else {
                            result = FindResult(response.body().string(), TempKey);
                        }

                        if (apiUrl.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(apiUrl), result);
                        }
                        //假相對路徑
                        result = fixContentPath(apiUrl,result);
                        return result;
                    }
                });
    }

    private boolean loadCachedMainConfig(String apiUrl, File readableCache,
                                         LoadConfigCallback callback) {
        if (readableCache == null || !readableCache.exists()) {
            return false;
        }
        try {
            parseJson(apiUrl, readableCache);
            LOG.i("config-cache-fallback success");
            callback.success();
            return true;
        } catch (Throwable th) {
            String diagnostic = "config-cache-fallback failed error="
                    + th.getClass().getSimpleName();
            LOG.e(diagnostic);
            Log.e("TVBox-Config", diagnostic);
            return false;
        }
    }

    private static String configHost(String configUrl) {
        try {
            String host = Uri.parse(configUrl).getHost();
            return TextUtils.isEmpty(host) ? "local-or-invalid" : host;
        } catch (Throwable ignored) {
            return "local-or-invalid";
        }
    }

    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/"+MD5.string2MD5(jarUrl)+".jar");

        if (!md5.isEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.getFileMd5(cache).equalsIgnoreCase(md5))) {
                prepareDexJarForLoad(cache);
                if (jarLoader.load(cache.getAbsolutePath())) {
                    callback.success();
                } else {
                    callback.error("md5缓存失效");
                }
                return;
            }
        }else {
            if (Boolean.parseBoolean(jarCache) && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                LOG.i("echo-load jar jarCache:"+jarUrl);
                prepareDexJarForLoad(cache);
                if (jarLoader.load(cache.getAbsolutePath())) {
                    callback.success();
                    return;
                }
            }
        }

        boolean isJarInImg = jarUrl.startsWith("img+");
        jarUrl = jarUrl.replace("img+", "");
        LOG.i("echo-load jar start:"+jarUrl);
        OkGo.<File>get(jarUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<File>() {

                    @Override
                    public File convertResponse(okhttp3.Response response){
                        File cacheDir = cache.getParentFile();
                        assert cacheDir != null;
                        if (!cacheDir.exists()) cacheDir.mkdirs();
                        if (cache.exists()) cache.delete();
                        // 3. 使用 try-with-resources 确保流关闭
                        assert response.body() != null;
                        try (FileOutputStream fos = new FileOutputStream(cache)) {
                            if (isJarInImg) {
                                String respData = response.body().string();
                                LOG.i("echo---jar Response: " + respData);
                                byte[] imgJar = getImgJar(respData);
                                if (imgJar == null || imgJar.length == 0) {
                                    LOG.e("echo---Generated JAR data is empty");
                                    callback.error("JAR 是空的");
                                }
                                fos.write(imgJar);
                            } else {
                                // 使用流式传输避免内存溢出
                                InputStream inputStream = response.body().byteStream();
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                            fos.flush();
                            prepareDexJarForLoad(cache);
                        } catch (IOException e) {
                            return null;
                        }
                        return cache;
                    }

                    @Override
                    public void onSuccess(Response<File> response) {
                        File file = response.body();
                        if (file != null && file.exists()) {
                            try {
                                if (jarLoader.load(file.getAbsolutePath())) {
                                    StorageBudgetManager.trimCaches();
                                    LOG.i("echo---load-jar-success");
                                    callback.success();
                                } else {
                                    LOG.e("echo---jar Loader returned false");
                                    callback.error("JAR加载失败");
                                }
                            } catch (Exception e) {
                                LOG.e("echo---jar Loader threw exception: " + e.getMessage());
                                callback.error("JAR加载异常: ");
                            }
                        } else {
                            LOG.e("echo---jar File not found");
                            callback.error("JAR文件不存在");
                        }
                    }

                    @Override
                    public void onError(Response<File> response) {
                        Throwable ex = response.getException();
                        if (ex != null) {
                            LOG.i("echo---jar Request failed: " + ex.getMessage());
                        }
                        if(cache.exists()){
                            prepareDexJarForLoad(cache);
                            jarLoader.load(cache.getAbsolutePath());
                        }
                        callback.error("网络错误");
                    }
                });
    }

    private void prepareDexJarForLoad(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.setWritable(false, false);
            file.setReadable(true, false);
            file.setExecutable(false, false);
        } catch (Throwable ignored) {
        }
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private static  String jarCache ="true";
    private void parseJson(String apiUrl, String jsonStr) {
//        pyLoader.setConfig(jsonStr);
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        sourceBeanList.clear();
        searchSourceBeanList.clear();
        mHomeSource = null;
        mDefaultParse = null;
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 远端站点源
        SourceBean firstSite = null;
        for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
            JsonObject obj = (JsonObject) opt;
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.has("name")?obj.get("name").getAsString().trim():siteKey);
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            if(siteKey.startsWith("py_")){
                sb.setFilterable(1);
            }else {
                sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            }
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            sb.setStyle(DefaultConfig.safeJsonString(obj, "style", ""));
            if (firstSite == null && isSelectableHomeSource(sb))
                firstSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = Hawk.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null || !isSelectableHomeSource(sh)) {
                SourceBean fallback = firstSite != null ? firstSite : findFallbackHomeSource();
                if (fallback != null) {
                    if (sh != null && !isSelectableHomeSource(sh)) {
                        LOG.e("echo-home-source-disabled: " + sh.getKey() + " / " + sh.getApi());
                    }
                    setSourceBean(fallback);
                }
            }
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if(infoJson.has("parses")){
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = DefaultConfig.safeJsonString(obj, "ext", "");
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
            if(!parseBeanList.isEmpty())addSuperParse();
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }

        // 直播源
        String live_api_url=Hawk.get(HawkConfig.LIVE_API_URL,"");
        if(live_api_url.isEmpty() || apiUrl.equals(live_api_url)){
            LOG.i("echo-load-config_live");
            initLiveSettings();
            if(infoJson.has("lives")){
                JsonArray lives_groups=infoJson.get("lives").getAsJsonArray();
                Hawk.put(HawkConfig.LIVE_GROUP_LIST,lives_groups);
                int live_group_index=resolveLiveGroupIndex(lives_groups);
                //加载多源配置
                try {
                    ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
                    for (int i=0; i< lives_groups.size();i++) {
                        JsonObject jsonObject = lives_groups.get(i).getAsJsonObject();
                        String name = jsonObject.has("name")?jsonObject.get("name").getAsString():"线路"+(i+1);
                        LiveSettingItem liveSettingItem = new LiveSettingItem();
                        liveSettingItem.setItemIndex(i);
                        liveSettingItem.setItemName(name);
                        liveSettingItemList.add(liveSettingItem);
                    }
                    liveSettingGroupList.get(5).setLiveSettingItems(liveSettingItemList);
                } catch (Exception e) {
                    // 捕获任何可能发生的异常
                    e.printStackTrace();
                }

                if (live_group_index >= 0) {
                    JsonObject livesOBJ = lives_groups.get(live_group_index).getAsJsonObject();
                    loadLiveApi(livesOBJ);
                }
            }
        }

        myHosts = new HashMap<>();
        if (infoJson.has("hosts")) {
            JsonArray hostsArray = infoJson.getAsJsonArray("hosts");
            for (int i = 0; i < hostsArray.size(); i++) {
                String entry = hostsArray.get(i).getAsString();
                String[] parts = entry.split("=", 2); // 只分割一次，防止 value 里有 =
                if (parts.length == 2) {
                    myHosts.put(parts[0], parts[1]);
                }
            }
        }

        //video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                //嗅探过滤规则
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                //广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    ArrayList<String> ads = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        String regex = one.getAsString();
                        if (M3u8.isAd(regex)) ads.add(regex);
                        else rule.add(regex);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                        VideoParseRuler.addHostRegex(host, ads);
                    }
                }
                //嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    ArrayList<String> scripts = new ArrayList<>();
                    JsonArray scriptArray = obj.getAsJsonArray("script");
                    for (JsonElement one : scriptArray) {
                        String script = one.getAsString();
                        scripts.add(script);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostScript(host, scripts);
                    }
                }
            }
        }

        if (infoJson.has("doh")) {
            String doh_json = infoJson.getAsJsonArray("doh").toString();
            if(!Hawk.get(HawkConfig.DOH_JSON, "").equals(doh_json)){
                Hawk.put(HawkConfig.DOH_URL, 0);
                Hawk.put(HawkConfig.DOH_JSON,doh_json);
            }
        }else {
            Hawk.put(HawkConfig.DOH_JSON,"");
        }
        OkGoHelper.setDnsList();
        LOG.i("echo-api-config-----------load");
        //追加的广告拦截
        if(infoJson.has("ads")){
            for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                if(!AdBlocker.hasHost(host.getAsString())){
                    AdBlocker.addAdHost(host.getAsString());
                }
            }
        }
    }

    private void loadDefaultConfig() {
        String defaultAdsJson="{\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson=gson.fromJson(defaultAdsJson, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
        }
        LOG.i("echo-default-config-----------load");
    }
    private void parseLiveJson(String apiUrl, File f) throws Throwable {
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseLiveJson(apiUrl, sb.toString());
    }

    private String liveSpider="";
    private void parseLiveJson(String apiUrl, String jsonStr) {
        JsonObject infoJson = gson.fromJson(jsonStr, JsonObject.class);
        liveChannelGroupList.clear();
        // spider
        liveSpider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // 直播源
        initLiveSettings();
        if(infoJson.has("lives")){
            JsonArray lives_groups=infoJson.get("lives").getAsJsonArray();
            Hawk.put(HawkConfig.LIVE_GROUP_LIST,lives_groups);
            int live_group_index=resolveLiveGroupIndex(lives_groups);
            //加载多源配置
            try {
                ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
                for (int i=0; i< lives_groups.size();i++) {
                    JsonObject jsonObject = lives_groups.get(i).getAsJsonObject();
                    String name = jsonObject.has("name")?jsonObject.get("name").getAsString():"线路"+(i+1);
                    LiveSettingItem liveSettingItem = new LiveSettingItem();
                    liveSettingItem.setItemIndex(i);
                    liveSettingItem.setItemName(name);
                    liveSettingItemList.add(liveSettingItem);
                }
                liveSettingGroupList.get(5).setLiveSettingItems(liveSettingItemList);
            } catch (Exception e) {
                // 捕获任何可能发生的异常
                e.printStackTrace();
            }

            if (live_group_index >= 0) {
                JsonObject livesOBJ = lives_groups.get(live_group_index).getAsJsonObject();
                loadLiveApi(livesOBJ);
            } else {
                LOG.i("echo-api-live-config has no live groups");
            }
        }

        myHosts = new HashMap<>();
        if (infoJson.has("hosts")) {
            JsonArray hostsArray = infoJson.getAsJsonArray("hosts");
            for (int i = 0; i < hostsArray.size(); i++) {
                String entry = hostsArray.get(i).getAsString();
                String[] parts = entry.split("=", 2); // 只分割一次，防止 value 里有 =
                if (parts.length == 2) {
                    myHosts.put(parts[0], parts[1]);
                }
            }
        }
        LOG.i("echo-api-live-config-----------load");
    }

    private int resolveLiveGroupIndex(JsonArray livesGroups) {
        if (livesGroups == null || livesGroups.size() == 0) {
            Hawk.put(HawkConfig.LIVE_GROUP_INDEX, 0);
            return -1;
        }
        int index = Hawk.get(HawkConfig.LIVE_GROUP_INDEX, 0);
        if (index < 0 || index >= livesGroups.size()) {
            index = 0;
            Hawk.put(HawkConfig.LIVE_GROUP_INDEX, index);
        }
        return index;
    }

    private final List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();
    private void initLiveSettings() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList("线路选择", "画面比例", "播放解码", "超时换源", "偏好设置", "多源切换"));
        ArrayList<ArrayList<String>> itemsArrayList = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        ArrayList<String> scaleItems = new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪"));
        ArrayList<String> playerDecoderItems = new ArrayList<>(Arrays.asList("系统"));
        ArrayList<String> timeoutItems = new ArrayList<>(Arrays.asList("5s", "10s", "15s", "20s", "25s", "30s"));
        ArrayList<String> personalSettingItems = new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类"));
        ArrayList<String> yumItems = new ArrayList<>();

        itemsArrayList.add(sourceItems);
        itemsArrayList.add(scaleItems);
        itemsArrayList.add(playerDecoderItems);
        itemsArrayList.add(timeoutItems);
        itemsArrayList.add(personalSettingItems);
        itemsArrayList.add(yumItems);

        liveSettingGroupList.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup liveSettingGroup = new LiveSettingGroup();
            ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
            liveSettingGroup.setGroupIndex(i);
            liveSettingGroup.setGroupName(groupNames.get(i));
            for (int j = 0; j < itemsArrayList.get(i).size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(itemsArrayList.get(i).get(j));
                liveSettingItemList.add(liveSettingItem);
            }
            liveSettingGroup.setLiveSettingItems(liveSettingItemList);
            liveSettingGroupList.add(liveSettingGroup);
        }
    }

    public List<LiveSettingGroup> getLiveSettingGroupList() {
        return liveSettingGroupList;
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + Integer.toString(sourceIndex));
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    public void loadLiveApi(JsonObject livesOBJ) {
        try {
            LOG.i("echo-loadLiveApi");
            String lives = livesOBJ.toString();
            int index = lives.indexOf("proxy://");
            String url;
            if (index != -1) {
                int endIndex = lives.lastIndexOf("\"");
                url = lives.substring(index, endIndex);
                url = DefaultConfig.checkReplaceProxy(url);
                String extUrl = Uri.parse(url).getQueryParameter("ext");
                if (extUrl != null && !extUrl.isEmpty()) {
                    String extUrlFix;
                    if(extUrl.startsWith("http") || extUrl.startsWith("clan://")){
                        extUrlFix = extUrl;
                    }else {
                        extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                    }
                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                    url = url.replace(extUrl, extUrlFix);
                }
            } else {
                String type= livesOBJ.get("type").getAsString();
                if(type.equals("0") || type.equals("3")){
                    url = livesOBJ.has("url")?livesOBJ.get("url").getAsString():"";
                    if(url.isEmpty())url=livesOBJ.has("api")?livesOBJ.get("api").getAsString():"";
                    LOG.i("echo-liveurl"+url);
                    if(!url.startsWith("http://127.0.0.1")){
                        if(url.startsWith("http")){
                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                        }
                        url = ControlManager.get().getAddress(true) + "proxy?do=live&type=txt&ext=" + url;
                    }
                    if(type.equals("3")){
                        String jarUrl = livesOBJ.has("jar")?livesOBJ.get("jar").getAsString().trim():"";
                        String pyApi = livesOBJ.has("api")?livesOBJ.get("api").getAsString().trim():"";
                        LOG.i("echo-pyApi1"+pyApi);
                        if(pyApi.contains(".py")){
                            LOG.i("echo-pyLoader.getSpider");
                            String ext="";
                            if(livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject() || livesOBJ.get("ext").isJsonArray())){
                                ext=livesOBJ.get("ext").toString();
                            }else {
                                ext=DefaultConfig.safeJsonString(livesOBJ, "ext", "");
                            }

                            pyLoader.getSpider(MD5.string2MD5(pyApi),pyApi,ext);
                        }
                        if(!jarUrl.isEmpty()){
                            jarLoader.loadLiveJar(jarUrl);
                        }else if(!liveSpider.isEmpty()){
                            jarLoader.loadLiveJar(liveSpider);
                        }
                    }
                }else {
                    liveChannelGroupList.clear();
                    return;
                }
            }
            //设置epg
            if(livesOBJ.has("epg")){
                String epg =livesOBJ.get("epg").getAsString();
                Hawk.put(HawkConfig.EPG_URL,epg);
            }else {
                Hawk.put(HawkConfig.EPG_URL,"");
            }
            //直播播放器类型
            if(livesOBJ.has("playerType")){
                String livePlayType =livesOBJ.get("playerType").getAsString();
                Hawk.put(HawkConfig.LIVE_PLAY_TYPE,livePlayType);
            }else {
                Hawk.put(HawkConfig.LIVE_PLAY_TYPE,Hawk.get(HawkConfig.PLAY_TYPE, 0));
            }
            //设置UA
            if(livesOBJ.has("header")) {
                JsonObject headerObj = livesOBJ.getAsJsonObject("header");
                HashMap<String, String> liveHeader = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : headerObj.entrySet()) {
                    liveHeader.put(entry.getKey(), entry.getValue().getAsString());
                }
                Hawk.put(HawkConfig.LIVE_WEB_HEADER, liveHeader);
            } else if(livesOBJ.has("ua")) {
                String ua = livesOBJ.get("ua").getAsString();
                HashMap<String,String> liveHeader = new HashMap<>();
                liveHeader.put("User-Agent", ua);
                Hawk.put(HawkConfig.LIVE_WEB_HEADER, liveHeader);
            }else {
                Hawk.put(HawkConfig.LIVE_WEB_HEADER,null);
            }
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setGroupName(url);
            liveChannelGroupList.clear();
            liveChannelGroupList.add(liveChannelGroup);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private boolean isDirectLiveUrl(String liveApiUrl) {
        String url = liveApiUrl == null ? "" : liveApiUrl.toLowerCase().trim();
        return url.contains(".txt")
                || url.contains(".m3u")
                || url.contains("=txt")
                || url.contains("=m3u")
                || url.contains("type=txt")
                || url.contains("type=m3u")
                || url.contains("format=txt")
                || url.contains("format=m3u")
                || url.contains("download")
                || url.contains("live");
    }

    private String wrapDirectLiveConfig(String liveApiConfigUrl) {
        return DEFAULT_LIVE_OBJ_TEMPLATE.replace("txt_m3u_url", liveApiConfigUrl);
    }

    private String normalizeLiveConfigContent(String liveApiUrl, String liveApiConfigUrl, String body) {
        if (TextUtils.isEmpty(body)) {
            return body;
        }
        String trimmed = body.replace("\ufeff", "").trim();
        if (looksLikeLiveText(trimmed) && !looksLikeLiveJson(trimmed)) {
            return wrapDirectLiveConfig(liveApiConfigUrl);
        }
        return trimmed;
    }

    private boolean looksLikeLiveJson(String body) {
        if (TextUtils.isEmpty(body)) {
            return false;
        }
        try {
            JsonObject infoJson = gson.fromJson(body, JsonObject.class);
            return infoJson != null && infoJson.has("lives");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean looksLikeLiveText(String body) {
        if (TextUtils.isEmpty(body)) {
            return false;
        }
        if (body.regionMatches(true, 0, "#EXTM3U", 0, 7)) {
            return true;
        }
        LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap = new LinkedHashMap<>();
        try {
            TxtSubscribe.parse(linkedHashMap, body);
            return !linkedHashMap.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String currentLiveSpider;
    public void setLiveJar(String liveJar)
    {
        if(liveJar.contains(".py")){
           pyLoader.setRecentPyKey(liveJar);
        }else {
            String jarUrl=!liveJar.isEmpty()?liveJar:liveSpider;
            jarLoader.setRecentJarKey(MD5.string2MD5(jarUrl));
        }
        currentLiveSpider=liveJar;
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        if (isKnownCrashSpider(sourceBean)) {
            LOG.e("echo-skip-known-crash-spider: " + sourceBean.getKey() + " / " + sourceBean.getApi());
            return new com.github.catvod.crawler.SpiderNull();
        }
        if (sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?")){
            return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
        else if (sourceBean.getApi().contains(".py")) {
            return pyLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
        }
        else return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
    }

    public Spider getPyCSP(String url) {
        return pyLoader.getSpider(MD5.string2MD5(url), url, "");
    }

    public Object[] proxyLocal(Map<String, String> param) {
        if ("js".equals(param.get("do"))) {
            return jsLoader.proxyInvoke(param);
        }
        String apiString;
        if (Hawk.get(HawkConfig.PLAYER_IS_LIVE, false)) {
            apiString = currentLiveSpider!=null?currentLiveSpider:"";
        } else {
            SourceBean sourceBean = ApiConfig.get().getHomeSourceBean();
            apiString = sourceBean.getApi();
        }
        return apiString.contains(".py") ? pyLoader.proxyInvoke(param) : jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void error(String msg);
        void notice(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key))
            return null;
        return sourceBeanList.get(key);
    }

    /**
     * Register a source handed over by the UI process.  The full-screen player
     * has its own ApiConfig singleton, so a source loaded from a remote config
     * is otherwise invisible there and getPlay() would dereference null.
     */
    public void registerSource(SourceBean sourceBean) {
        if (sourceBean == null || TextUtils.isEmpty(sourceBean.getKey())) {
            return;
        }
        sourceBeanList.put(sourceBean.getKey(), sourceBean);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        Hawk.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }
    public List<SourceBean> getSwitchSourceBeanList() {
        List<SourceBean> filteredList = new ArrayList<>();
        for (SourceBean bean : sourceBeanList.values()) {
            if (isSelectableHomeSource(bean)) {
                filteredList.add(bean);
            }
        }
        return filteredList;
    }

    private List<SourceBean> searchSourceBeanList;
    public List<SourceBean> getSearchSourceBeanList() {
        if(searchSourceBeanList.isEmpty()){
            LOG.i("echo-第一次getSearchSourceBeanList");
            searchSourceBeanList = new ArrayList<>();
            for (SourceBean bean : sourceBeanList.values()) {
                if (bean.isSearchable()) {
                    searchSourceBeanList.add(bean);
                }
            }
        }
        return searchSourceBeanList;
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://localhost/", fix).replace("file://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            url=url.replace("file://","clan://localhost/");
            if(!url.startsWith("http") && !url.startsWith("clan://")){
                url = "http://" + url;
            }
            if(url.startsWith("clan://"))url=clanToAddress(url);
            content = content.replace("./", url.substring(0,url.lastIndexOf("/") + 1));
        }
        return content;
    }

    public Map<String,String> getMyHost() {
        return myHosts;
    }

    public void clearJarLoader()
    {
        jarLoader.clear();
    }

    private void addSuperParse()
    {
        ParseBean superPb = new ParseBean();
        superPb.setName("超级解析");
        superPb.setUrl("SuperParse");
        superPb.setExt("");
        superPb.setType(4);
        parseBeanList.add(0, superPb);
    }

    public void clearLoader(){
        jarLoader.clear();
        pyLoader.clear();
        jsLoader.clear();
    }

    private boolean isSelectableHomeSource(SourceBean sourceBean) {
        return sourceBean != null && sourceBean.getFilterable() == 1 && !isKnownCrashSpider(sourceBean);
    }

    private SourceBean findFallbackHomeSource() {
        SourceBean firstFilterable = null;
        for (SourceBean bean : sourceBeanList.values()) {
            if (firstFilterable == null && bean.getFilterable() == 1) {
                firstFilterable = bean;
            }
            if (isSelectableHomeSource(bean)) {
                return bean;
            }
        }
        return firstFilterable;
    }

    private boolean isKnownCrashSpider(SourceBean sourceBean) {
        if (sourceBean == null || sourceBean.getType() != 3) {
            return false;
        }
        String api = sourceBean.getApi();
        if (TextUtils.isEmpty(api)) {
            return false;
        }
        String normalizedApi = api.trim().toLowerCase();
        return normalizedApi.equals("csp_allliveguard") || normalizedApi.contains("csp_allliveguard");
    }
}
