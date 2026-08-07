package com.elsnime;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.Bundle;
import android.webkit.*;
import org.json.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private WebView web; private Backend backend;
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) { super.onCreate(state); backend=new Backend(this); web=new WebView(this); web.setBackgroundColor(0xff0f0f0f); WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false); s.setAllowFileAccess(true); s.setAllowUniversalAccessFromFileURLs(true); s.setUserAgentString("Elsnime Android");
        // The page declares color-scheme (meta + CSS) and themes itself; the
        // real system theme is read natively via AndroidApi.systemTheme(),
        // because WebView's prefers-color-scheme is unreliable across devices.
        web.setWebViewClient(new WebViewClient()); web.addJavascriptInterface(new AndroidApi(web,backend),"AndroidApi"); setContentView(web); web.loadUrl("file:///android_asset/ui.html"); }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed(){
        // The UI is a single-page app with its own view stack, so the Android
        // back button must go through the web layer: close the picker, pop the
        // player/detail/history views, clear an active search — and only exit
        // the app when a root tab is showing. The callback runs on the UI thread.
        web.evaluateJavascript("(window.handleAppBack ? window.handleAppBack() : false)", result -> {
            // handleAppBack returns a boolean; the callback gets its JSON encoding ("true"/"false").
            if (result == null || !result.trim().equals("true")) MainActivity.super.onBackPressed();
        });
    }
    // Flush any pending progress on the way out — the JS bridge can't be relied
    // on to complete during beforeunload/teardown. Also pause the web player so
    // background playback doesn't keep burning the user's data.
    @Override protected void onPause(){
        super.onPause();
        if(backend!=null)backend.flushProgress();
        web.evaluateJavascript("(function(){var v=document.getElementById('video');if(v&&!v.paused)v.pause();})()", null);
    }
    // uiMode/orientation changes arrive here instead of recreating the activity
    // (see configChanges in the manifest), so the WebView can re-apply the theme
    // and survive rotation without losing playback.
    @Override public void onConfigurationChanged(android.content.res.Configuration c){
        super.onConfigurationChanged(c);
        if(web!=null)web.evaluateJavascript("window.__systemThemeChanged && window.__systemThemeChanged()", null);
    }

    static final class AndroidApi {
        private final WebView view; private final Backend backend; AndroidApi(WebView v,Backend b){view=v;backend=b;}
        // True system theme regardless of WebView support — works on every API level.
        @JavascriptInterface public String systemTheme(){
            int mode = view.getContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        }
        // Drive the activity orientation from JS: "landscape" (force), "sensor"
        // (both, so rotating back to portrait can exit), "auto" (restore).
        @JavascriptInterface public void setOrientation(final String mode){
            view.post(() -> {
                try {
                    Activity a = (Activity) view.getContext();
                    if ("landscape".equals(mode)) a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    else if ("sensor".equals(mode)) a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                    else a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                } catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public void request(final String id,final String method,final String path,final String body){backend.executor.execute(()->{String result=backend.handle(method,path,body); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        @JavascriptInterface public void playInMpv(final String id,final String animeId,final String episode,final String type,final String referer,final String userAgent){backend.executor.execute(()->{String result=backend.playInMpv(animeId,episode,type,referer,userAgent); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        @JavascriptInterface public void mpvStatus(final String id){backend.executor.execute(()->view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(backend.mpvStatus())+")",null)));}
        @JavascriptInterface public void saveProgress(final String id,final String animeId,final String animeTitle,final String episode,final double progress,final double duration,final String thumbnail,final boolean force){backend.executor.execute(()->{String result=saveProgressResult(animeId,animeTitle,episode,progress,duration,thumbnail,force); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // Pull-to-refresh: drop only the cache entries the active view needs, so
        // unrelated data stays warm and API rate limits aren't hammered. prefixes
        // is a comma-joined list ("trending,tags"); empty = no-op, "all" = wipe.
        // Runs synchronously on the JS bridge thread (a background thread) so the
        // clear is guaranteed to finish before the reload requests are dispatched.
        @JavascriptInterface public void refreshCache(final String prefixes){backend.refreshCache(prefixes);}
        private String saveProgressResult(final String animeId,final String animeTitle,final String episode,final double progress,final double duration,final String thumbnail,final boolean force){try{return backend.saveProgress(new JSONObject().put("anime_id",animeId).put("anime_title",animeTitle).put("episode",episode).put("progress",progress).put("duration",duration).put("thumbnail",thumbnail),force);}catch(Exception e){return "{\"error\":\"save failed\"}";}}
    }

    static final class Backend {
        final ExecutorService executor=Executors.newCachedThreadPool(); final AniDbScraper scraper=new AniDbScraper(); final HistoryDb db;
        private final java.util.concurrent.CopyOnWriteArrayList<Process> mpvProcesses=new java.util.concurrent.CopyOnWriteArrayList<>();
        // Progress batcher: keep only the latest entry per anime/episode in memory,
        // flush to SQLite in one transaction every 4s (or immediately when forced).
        private final java.util.Map<String,JSONObject> pendingProgress=new java.util.LinkedHashMap<>();
        private final java.util.concurrent.ScheduledExecutorService progressTimer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        private volatile boolean progressFlushScheduled=false;
        private final android.content.Context appContext;
        Backend(Context c){appContext=c.getApplicationContext();db=new HistoryDb(appContext);scraper.setCache(new AniDbScraper.CacheStore(){
            public String get(String key){return db.cacheGet(key);}
            public void put(String key,String value,long ttl){db.cachePut(key,value,ttl);}
            public void clear(){db.cacheClear();}
            public void clearPrefix(String prefix){db.cacheClearPrefix(prefix);}
        });scraper.setTransport(CronetTransport.create(appContext));}
        String mpvStatus(){
            boolean cli=mpvCliAvailable(), app=mpvAppAvailable();
            try{return new JSONObject().put("cli",cli).put("app",app).put("available",cli||app).toString();}catch(Exception ignored){return "{\"available\":false}";}
        }
        private boolean mpvCliAvailable(){
            String path=System.getenv("PATH");
            String dirs=(path==null?"":path)+":/data/data/com.termux/files/usr/bin:/usr/bin";
            for(String d:dirs.split(":")){if(!d.isEmpty()&&new java.io.File(d,"mpv").canExecute())return true;}
            return false;
        }
        private boolean mpvAppAvailable(){
            try{appContext.getPackageManager().getPackageInfo("is.mpv.android",0);return true;}catch(Exception ignored){return false;}
        }
        String saveProgress(JSONObject entry,boolean force){
            String key=entry.optString("anime_id")+"|"+entry.optString("episode");
            synchronized(pendingProgress){pendingProgress.put(key,entry);}
            if(force){progressTimer.schedule(this::flushProgress,0,java.util.concurrent.TimeUnit.MILLISECONDS);}
            else if(!progressFlushScheduled){progressFlushScheduled=true;progressTimer.schedule(this::flushProgress,4000,java.util.concurrent.TimeUnit.MILLISECONDS);}
            return ok();
        }
        private void flushProgress(){
            progressFlushScheduled=false;
            java.util.List<JSONObject> batch;
            synchronized(pendingProgress){
                if(pendingProgress.isEmpty())return;
                batch=new java.util.ArrayList<>(pendingProgress.values());
                pendingProgress.clear();
            }
            db.saveBatch(batch);
        }
        String playInMpv(String animeId,String episode,String type,String referer,String userAgent){
            JSONObject out=new JSONObject();
            try{
                JSONObject stream=scraper.stream(animeId,episode,type);
                String url=stream.optString("url");
                out.put("url",url).put("raw",stream.optString("raw",url)).put("type",stream.opt("type"));
                if(url.isEmpty())return out.put("error","No stream available").toString();
                if(mpvCliAvailable())launchMpv(url,referer,userAgent);
                else if(mpvAppAvailable())launchMpvApp(url);
                else return out.put("error","MPV is not installed on this device").toString();
                return out.put("ok",true).toString();
            }catch(Exception e){try{return out.put("error",e.getMessage()==null?"Failed to launch MPV":e.getMessage()).toString();}catch(Exception ignored){return "{\"error\":\"Failed to launch MPV\"}";}}
        }
        private void launchMpv(String url,String referer,String userAgent)throws Exception{
            Process p;
            try{p=new ProcessBuilder("mpv","--referrer="+referer,"--user-agent="+userAgent,"--force-window=yes",url).redirectErrorStream(true).start();}
            catch(java.io.IOException e){throw new Exception("Could not launch MPV. Check if mpv is installed.");}
            // Health check: on stock Android the binary can be world-executable yet
            // still die instantly (SELinux blocks cross-app exec, missing libs), so
            // confirm the process actually stays alive before reporting success.
            if(p.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)){
                mpvProcesses.remove(p);
                throw new Exception("MPV exited immediately. Check if it is installed correctly.");
            }
            mpvProcesses.add(p);
            new Thread(()->{try{java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));while(br.readLine()!=null){}}catch(Exception ignored){}try{p.waitFor();}catch(Exception ignored){}mpvProcesses.remove(p);},"mpv-pump").start();
        }
        private void launchMpvApp(String url)throws Exception{
            // NOTE: the mpv-android intent spec only supports decode_mode/subs/
            // position/title extras — referer/user-agent can't be passed, so the
            // stream must play without them (or the user sets a global referer in
            // mpv-android's advanced settings). Playback errors show in the app.
            android.content.Intent i=new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setData(android.net.Uri.parse(url));
            i.setPackage("is.mpv.android");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if(i.resolveActivity(appContext.getPackageManager())==null)throw new Exception("MPV app could not open this stream.");
            appContext.startActivity(i);
        }
        void refreshCache(String prefixes){
            if(prefixes==null||prefixes.trim().isEmpty())return;
            if("all".equals(prefixes.trim())){scraper.clearCache();return;}
            for(String p:prefixes.split(",")){p=p.trim();if(!p.isEmpty())scraper.clearCachePrefix(p);}
        }
        String handle(String method,String rawPath,String body){try{UriParts p=new UriParts(rawPath); String path=p.path;
            if(method.equals("GET")&&path.equals("/api/search"))return p.q("tag").isEmpty()?scraper.search(p.q("q"),p.q("type","sub")).toString():scraper.searchTag(p.q("tag")).toString();
            if(method.equals("GET")&&path.equals("/api/episodes"))return new JSONObject().put("episodes",scraper.episodes(p.q("id"),p.q("type","sub"))).toString();
            if(method.equals("GET")&&path.equals("/api/stream"))return scraper.stream(p.q("id"),p.q("episode"),p.q("type","sub")).toString();
            if(method.equals("GET")&&path.equals("/api/trending"))return scraper.trending().toString();
            if(method.equals("GET")&&path.equals("/api/popular"))return scraper.trending().toString();
            if(method.equals("GET")&&path.equals("/api/anime"))return scraper.anime(p.q("id"),p.q("type","sub")).toString();
            if(method.equals("POST")&&path.equals("/api/resolve"))return scraper.resolve(new JSONObject(body)).toString();
            if(method.equals("GET")&&path.equals("/api/tags"))return scraper.tags().toString();
            if(method.equals("GET")&&path.equals("/api/home"))return new JSONObject().put("trending",scraper.trending()).put("history",db.history()).toString();
            if(path.equals("/api/history")&&method.equals("GET"))return db.history().toString();
            if(path.equals("/api/history")&&method.equals("POST")){db.save(new JSONObject(body));return ok();}
            if(path.startsWith("/api/history/")&&method.equals("DELETE")){db.delete(Integer.parseInt(path.substring(14)));return ok();}
            if(path.equals("/api/settings")&&method.equals("GET"))return db.settings().toString();
            if(path.equals("/api/settings")&&method.equals("POST")){db.settings(new JSONObject(body));return ok();}
            return new JSONObject().put("error","Not found").toString();
        }catch(Exception e){try{return new JSONObject().put("error",e.getMessage()==null?"Request failed":e.getMessage()).toString();}catch(Exception ignored){return "{}";}}}
        private String ok(){return "{\"ok\":true}";}
    }

    static final class UriParts { final String path; final String query; UriParts(String raw){int i=raw.indexOf('?');path=i<0?raw:raw.substring(0,i);query=i<0?"":raw.substring(i+1);} String q(String key){return q(key,"");} String q(String key,String fallback){for(String pair:query.split("&")){String[] x=pair.split("=",2);if(x.length==2&&x[0].equals(key))try{return java.net.URLDecoder.decode(x[1],"UTF-8");}catch(Exception ignored){}}return fallback;} }

    static final class HistoryDb extends SQLiteOpenHelper {
        private static final int MAX_CACHE_ENTRIES = 250;
        private volatile long lastSweep = 0;
        HistoryDb(Context c){super(c,"elsnime.db",null,3);}
        public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,anime_id TEXT NOT NULL,anime_title TEXT NOT NULL,episode TEXT NOT NULL,progress REAL DEFAULT 0,duration REAL DEFAULT 0,thumbnail TEXT DEFAULT '',last_watched INTEGER DEFAULT (strftime('%s','now')),UNIQUE(anime_id,episode))");d.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");d.execSQL("CREATE TABLE cache(key TEXT PRIMARY KEY,value TEXT NOT NULL,expires_at INTEGER NOT NULL,last_updated INTEGER NOT NULL DEFAULT 0)");}
        public void onUpgrade(SQLiteDatabase d,int a,int b){d.execSQL("CREATE TABLE IF NOT EXISTS cache(key TEXT PRIMARY KEY,value TEXT NOT NULL,expires_at INTEGER NOT NULL,last_updated INTEGER NOT NULL DEFAULT 0)");if(a<3){try{d.execSQL("ALTER TABLE cache ADD COLUMN last_updated INTEGER NOT NULL DEFAULT 0");}catch(Exception ignored){}}}
        String cacheGet(String key){
            maybeSweep();
            try(Cursor c=getReadableDatabase().query("cache",new String[]{"value","expires_at"},"key=?",new String[]{key},null,null,null)){
                if(c.moveToNext()&&c.getLong(1)>System.currentTimeMillis())return c.getString(0);
            }
            return null;
        }
        // Overwrites any existing row for the key (CONFLICT_REPLACE), refreshes its
        // freshness timestamp, then trims expired/oldest rows so stale data never
        // lingers and the table stays bounded.
        void cachePut(String key,String value,long ttlSeconds){
            maybeSweep();
            ContentValues v=new ContentValues();
            v.put("key",key);
            v.put("value",value);
            v.put("expires_at",System.currentTimeMillis()+ttlSeconds*1000);
            v.put("last_updated",System.currentTimeMillis());
            getWritableDatabase().insertWithOnConflict("cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            enforceCacheLimit();
        }
        void cacheClear(){getWritableDatabase().delete("cache",null,null);}
        void cacheClearPrefix(String prefix){getWritableDatabase().delete("cache","key GLOB ?",new String[]{prefix+"*"});}
        // Opportunistic cleanup (throttled to once per 5 minutes): physically
        // remove expired rows so old data doesn't pile up past its TTL.
        private synchronized void maybeSweep(){
            long now=System.currentTimeMillis();
            if(now-lastSweep<300000L)return;
            lastSweep=now;
            try{getWritableDatabase().delete("cache","expires_at <= ?",new String[]{String.valueOf(now)});}catch(Exception ignored){}
        }
        // Cap total entries by evicting the least-recently-written rows.
        private synchronized void enforceCacheLimit(){
            try{
                SQLiteDatabase d=getWritableDatabase();
                long count;
                try(Cursor c=d.rawQuery("SELECT COUNT(*) FROM cache",null)){c.moveToFirst();count=c.getLong(0);}
                long excess=count-MAX_CACHE_ENTRIES;
                if(excess<=0)return;
                try(Cursor c=d.query("cache",new String[]{"key"},null,null,null,null,"last_updated ASC",String.valueOf(excess))){
                    while(c.moveToNext())d.delete("cache","key=?",new String[]{c.getString(0)});
                }
            }catch(Exception ignored){}
        }
        JSONArray history() throws Exception {
            JSONArray result = new JSONArray();
            try (Cursor c = getReadableDatabase().query(
                    "history", null, null, null, null, null, "last_watched DESC", "100")) {
                while (c.moveToNext()) {
                    JSONObject row = new JSONObject();
                    row.put("id", c.getInt(c.getColumnIndexOrThrow("id")));
                    row.put("anime_id", c.getString(c.getColumnIndexOrThrow("anime_id")));
                    row.put("anime_title", c.getString(c.getColumnIndexOrThrow("anime_title")));
                    row.put("episode", c.getString(c.getColumnIndexOrThrow("episode")));
                    row.put("progress", c.getDouble(c.getColumnIndexOrThrow("progress")));
                    row.put("duration", c.getDouble(c.getColumnIndexOrThrow("duration")));
                    row.put("thumbnail", c.getString(c.getColumnIndexOrThrow("thumbnail")));
                    row.put("last_watched", c.getLong(c.getColumnIndexOrThrow("last_watched")));
                    result.put(row);
                }
            }
            return result;
        }
        ContentValues values(JSONObject x){ContentValues v=new ContentValues();v.put("anime_id",x.optString("anime_id"));v.put("anime_title",x.optString("anime_title"));v.put("episode",x.optString("episode"));v.put("progress",x.optDouble("progress"));v.put("duration",x.optDouble("duration"));v.put("thumbnail",x.optString("thumbnail"));v.put("last_watched",System.currentTimeMillis()/1000);return v;}
        void save(JSONObject x){getWritableDatabase().insertWithOnConflict("history",null,values(x),SQLiteDatabase.CONFLICT_REPLACE);}
        void saveBatch(java.util.List<JSONObject> rows){SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try{for(JSONObject x:rows)d.insertWithOnConflict("history",null,values(x),SQLiteDatabase.CONFLICT_REPLACE);d.setTransactionSuccessful();}finally{d.endTransaction();}}
        void delete(int id){getWritableDatabase().delete("history","id=?",new String[]{String.valueOf(id)});}
        JSONObject settings() throws Exception{JSONObject o=new JSONObject().put("hw_accel",false).put("sub_lang","sub").put("player","web").put("theme","auto").put("performance_mode","auto").put("accent_h",239);try(Cursor c=getReadableDatabase().query("settings",new String[]{"key","value"},null,null,null,null,null)){while(c.moveToNext()){String k=c.getString(0),v=c.getString(1);try{o.put(k,new JSONTokener(v).nextValue());}catch(Exception ignored){}}}return o;}
        void settings(JSONObject x){
            SQLiteDatabase d=getWritableDatabase();
            JSONArray names=x.names();
            if(names==null)return;
            for(int i=0;i<names.length();i++){
                String k=names.optString(i);
                ContentValues v=new ContentValues();
                v.put("key",k);
                v.put("value",String.valueOf(x.opt(k)));
                d.insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
    }
}
