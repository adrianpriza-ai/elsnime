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
    private WebView web; private Backend backend; private static volatile boolean immersive=false;
    // Storage permission (API 23-28 only; API 29+ uses MediaStore and needs
    // nothing): the bridge thread parks on this until onRequestPermissionsResult
    // resolves it, then proceeds (or fails cleanly when the user denies).
    private static volatile java.util.concurrent.CompletableFuture<Boolean> storagePermissionFuture;
    private static final Object storagePermissionLock = new Object();
    private static final int STORAGE_PERMISSION_REQUEST = 7001;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 7002;

    // Ensure WRITE_EXTERNAL_STORAGE before the first download on legacy Android.
    // Blocks up to 30s waiting for the user's answer; returns true on API 29+
    // where MediaStore needs no permission. A full-series queue fires several
    // startDownload calls concurrently (each on its own executor thread), so
    // concurrent callers share the in-flight request instead of racing on it.
    static boolean ensureStoragePermission(WebView view){
        if(android.os.Build.VERSION.SDK_INT>=29)return true;
        final android.content.Context c=view.getContext();
        java.util.concurrent.CompletableFuture<Boolean> f;
        boolean requester=false;
        synchronized(storagePermissionLock){
            if(c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED)return true;
            if(storagePermissionFuture==null){storagePermissionFuture=new java.util.concurrent.CompletableFuture<>();requester=true;}
            f=storagePermissionFuture;
        }
        if(requester){
            view.post(()->{
                try{((Activity)c).requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},STORAGE_PERMISSION_REQUEST);}
                catch(Exception e){synchronized(storagePermissionLock){if(storagePermissionFuture!=null){storagePermissionFuture.complete(false);storagePermissionFuture=null;}}}
            });
        }
        try{return f.get(30,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){return false;}
    }
    // immersive tracks Plyr's fallback fullscreen state (set via the
    // AndroidApi.setFullscreen bridge) so onWindowFocusChanged can re-hide the
    // system bars after a focus loss while fullscreen is active.
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state) { super.onCreate(state); backend=new Backend(this); web=new WebView(this); web.setBackgroundColor(0xff0f0f0f); WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false); s.setAllowFileAccess(true); s.setAllowUniversalAccessFromFileURLs(true);
        // Present the scraper's browser UA to the CDN: hls.js fetches the
        // manifest/segments via XHR, and JS cannot override the User-Agent
        // header (it's a forbidden header), so the WebView's own UA is what
        // the CDN sees. "Elsnime Android" was getting those requests blocked;
        // the Chrome fingerprint matches the one the scraper resolved the
        // stream with (the Referer is stamped per-request in player.js).
        s.setUserAgentString(AniDbScraper.UA);
        // The page declares color-scheme (meta + CSS) and themes itself; the
        // real system theme is read natively via AndroidApi.systemTheme(),
        // because WebView's prefers-color-scheme is unreliable across devices.
        web.setWebViewClient(new WebViewClient());
        // Fullscreen is handled by Plyr inside the page (CSS fallback mode
        // fills the WebView viewport with the control bar overlaid), so there
        // is no native custom view. This client exists to keep
        // window.confirm() a silent no-op (the UI uses its own in-app dialog
        // for destructive actions).
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView v, String url, String message, JsResult result) { result.cancel(); return true; }
        });
        web.addJavascriptInterface(new AndroidApi(web,backend),"AndroidApi"); setContentView(web); web.loadUrl("file:///android_asset/ui.html");
        // Download notifications need POST_NOTIFICATIONS on Android 13+. Ask at
        // launch (a no-op below 33); denying only hides the notification — the
        // download itself still works.
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION_REQUEST);
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==STORAGE_PERMISSION_REQUEST&&storagePermissionFuture!=null){
            boolean ok=grants!=null&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;
            storagePermissionFuture.complete(ok);
            storagePermissionFuture=null;
        }
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed(){
        // The UI is a single-page app with its own view stack, so the Android
        // back button must go through the web layer: exit Plyr fullscreen,
        // close the picker, pop the player/detail/history views, clear an
        // active search — and only exit the app when a root tab is showing.
        // The callback runs on the UI thread.
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
    // Some devices re-show the system bars when the window regains focus (app
    // switcher, notification shade); re-hide them while in fullscreen (the
    // immersive flag is only set by AndroidApi.setFullscreen while Plyr's
    // fallback fullscreen is active).
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus&&immersive)applyImmersive(web,true);
    }

    // Called from the AndroidApi.setFullscreen bridge (and onWindowFocusChanged
    // while fullscreen). API 30+ uses
    // WindowInsetsController; older devices get the legacy
    // SYSTEM_UI_FLAG_IMMERSIVE_STICKY combo. The transient behavior is reset
    // to BEHAVIOR_DEFAULT when restoring, so swiping on normal screens shows
    // the bars persistently again.
    static void applyImmersive(WebView view, boolean on){
        try {
            Activity a = (Activity) view.getContext();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = a.getWindow().getInsetsController();
                if (c == null) return;
                int bars = android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars();
                if (on) {
                    c.hide(bars);
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
                    c.show(bars);
                }
            } else {
                android.view.View decor = a.getWindow().getDecorView();
                int flags = on ? (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) : 0;
                decor.setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) {}
    }

    static final class AndroidApi {
        private final WebView view; private final Backend backend; AndroidApi(WebView v,Backend b){view=v;backend=b;
            // Downloader progress events (background thread) → WebView main thread.
            backend.setDownloadListener(ev->view.post(()->view.evaluateJavascript("window.__downloadEvent("+ev.toString()+")",null)));
        }
        // True system theme regardless of WebView support — works on every API level.
        @JavascriptInterface public String systemTheme(){
            int mode = view.getContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "dark" : "light";
        }
        // ui-loader.js: the UI is split into views/*.html partials, and fetch()
        // to file:// URLs is unreliable in WebView (CORS on file origins), so
        // the page reads each partial through the bridge instead. Runs on the
        // JS bridge thread (a background thread), so the blocking asset read is
        // fine. Returns null when the file is missing (e.g. stale APK).
        @JavascriptInterface public String readAsset(final String path){
            try{
                java.io.InputStream in = view.getContext().getAssets().open(path);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                in.close();
                return out.toString("UTF-8");
            }catch(Exception e){return null;}
        }
        @JavascriptInterface public void request(final String id,final String method,final String path,final String body){backend.executor.execute(()->{String result=backend.handle(method,path,body); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        @JavascriptInterface public void playInMpv(final String id,final String animeId,final String animeTitle,final String episode,final String type,final String referer,final String userAgent){backend.executor.execute(()->{String result=backend.playInMpv(animeId,animeTitle,episode,type,referer,userAgent); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // MPV for a downloaded file (playing from Library > Downloads): the file
        // is already served by the loopback LocalFileServer, so mpv just opens
        // that URL — no stream scraping. mime is the container type mpv-android
        // needs to accept the extension-less loopback URL.
        @JavascriptInterface public void playInMpvUrl(final String id,final String url,final String title,final String mime){backend.executor.execute(()->{String result=backend.playInMpvUrl(url,title,mime); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // Plyr fallback fullscreen state (called from player.js on
        // enterfullscreen/exitfullscreen): hide the system bars + rotate to
        // landscape for the duration, restore on exit. The last requested
        // state is remembered so onWindowFocusChanged can re-apply it after a
        // focus loss (some devices re-show the bars when the notification
        // shade closes).
        @JavascriptInterface public void setFullscreen(final boolean on){
            immersive = on;
            view.post(() -> {
                applyImmersive(view, on);
                try {
                    Activity a = (Activity) view.getContext();
                    if (on) a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    else a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                } catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public void mpvStatus(final String id){backend.executor.execute(()->view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(backend.mpvStatus())+")",null)));}
        @JavascriptInterface public void saveProgress(final String id,final String animeId,final String animeTitle,final String episode,final double progress,final double duration,final String thumbnail,final boolean force){backend.executor.execute(()->{String result=saveProgressResult(animeId,animeTitle,episode,progress,duration,thumbnail,force); view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // Pull-to-refresh: drop only the cache entries the active view needs, so
        // unrelated data stays warm and API rate limits aren't hammered. prefixes
        // is a comma-joined list ("trending,tags"); empty = no-op, "all" = wipe.
        // Runs synchronously on the JS bridge thread (a background thread) so the
        // clear is guaranteed to finish before the reload requests are dispatched.
        @JavascriptInterface public void refreshCache(final String prefixes){backend.refreshCache(prefixes);}
        // Downloads: start resolves the stream + enqueues on the native worker;
        // progress/state is pushed back through window.__downloadEvent. The
        // storage permission prompt (legacy Android) happens on the bridge thread
        // before anything is enqueued.
        @JavascriptInterface public void startDownload(final String id,final String uid,final String animeId,final String animeTitle,final String episode,final String type,final String quality){
            backend.executor.execute(()->{
                if(!MainActivity.ensureStoragePermission(view)){view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote("{\"error\":\"Storage permission denied\"}")+")",null));return;}
                String result=backend.startDownload(uid,animeId,animeTitle,episode,type,quality);
                view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));
            });
        }
        @JavascriptInterface public void cancelDownload(final String id,final String animeId,final String episode){backend.executor.execute(()->{backend.cancelDownload(animeId,episode);view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote("{\"ok\":true}")+")",null));});}
        @JavascriptInterface public void removeDownload(final String id,final String animeId,final String animeTitle,final String episode){backend.executor.execute(()->{String result=backend.removeDownload(animeId,animeTitle,episode);view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        @JavascriptInterface public void listDownloads(final String id){backend.executor.execute(()->{String result=backend.downloadsList();view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // Active (possibly resumed) downloads with their last-known state — the
        // Downloads tab reconciles against this at boot, because download
        // events emitted before the page loaded are lost.
        @JavascriptInterface public void activeDownloads(final String id){backend.executor.execute(()->{String result=backend.activeDownloads();view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // In-app playback of a downloaded file (Library > Downloads > Play):
        // resolves the file and registers it with the loopback file server,
        // returning the http URL + type the player can load.
        @JavascriptInterface public void playDownload(final String id,final String animeTitle,final String episode){backend.executor.execute(()->{String result=backend.playDownload(animeTitle,episode);view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        // Settings > Remove all downloads: cancel active tasks and delete every
        // file in Movies/Elsnime.
        @JavascriptInterface public void clearDownloads(final String id){backend.executor.execute(()->{String result=backend.clearDownloads();view.post(()->view.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+JSONObject.quote(result)+")",null));});}
        private String saveProgressResult(final String animeId,final String animeTitle,final String episode,final double progress,final double duration,final String thumbnail,final boolean force){try{return backend.saveProgress(new JSONObject().put("anime_id",animeId).put("anime_title",animeTitle).put("episode",episode).put("progress",progress).put("duration",duration).put("thumbnail",thumbnail),force);}catch(Exception e){return "{\"error\":\"save failed\"}";}}
    }

    static final class Backend {
        final ExecutorService executor=Executors.newCachedThreadPool(); final AniDbScraper scraper=new AniDbScraper(); final HistoryDb db;
        // The Downloader is process-wide (static): it survives an activity
        // destroy mid-download (e.g. swipe-away + reopen), and DownloadService
        // keeps referencing the same instance to mirror progress in the
        // notification. Same for the event listener: the current AndroidApi's
        // WebView must receive events even when the downloader was built by an
        // earlier Backend.
        private static volatile Downloader sharedDownloader;
        private static volatile java.util.function.Consumer<JSONObject> downloadListener;
        final Downloader downloader;
        private int downloadToken=0;
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
        });scraper.setTransport(CronetTransport.create(appContext));
        // Downloader pushes raw JSON events; AndroidApi forwards them to the page.
        if(sharedDownloader==null)sharedDownloader=new Downloader(appContext,scraper,ev->{java.util.function.Consumer<JSONObject> l=downloadListener;if(l!=null)l.accept(ev);});
        downloader=sharedDownloader;
        DownloadService.attach(downloader);
        // Resumed downloads (survived a process death) also need the foreground
        // service, or the process could be killed again mid-resume.
        if(downloader.activeCount()>0)DownloadService.start(appContext);
        }
        void setDownloadListener(java.util.function.Consumer<JSONObject> l){downloadListener=l;}
        String startDownload(String uid,String animeId,String animeTitle,String episode,String type,String quality){
            try{
                String key=animeId+"|"+episode;
                if(downloader.isActive(key))return "{\"active\":true}";
                JSONObject f=downloader.findFile(animeTitle,episode);
                if(f!=null)return new JSONObject().put("exists",true).put("fileName",f.optString("fileName")).toString();
                // Enqueue first, then bring up the foreground service: it keeps
                // the process alive (and shows the progress notification) while
                // this download runs, even when the app is backgrounded or
                // swiped away. The service checks activeCount() on start, so
                // the task must already be visible to it.
                downloader.start(new JSONObject().put("uid",uid).put("anime_id",animeId).put("anime_title",animeTitle).put("episode",episode).put("type",type).put("quality",quality));
                DownloadService.start(appContext);
                return ok();
            }catch(Exception e){try{return new JSONObject().put("error",e.getMessage()==null?"Download failed":e.getMessage()).toString();}catch(Exception ignored){return "{\"error\":\"Download failed\"}";}}
        }
        void cancelDownload(String animeId,String episode){downloader.cancel(animeId+"|"+episode);}
        String removeDownload(String animeId,String animeTitle,String episode){
            downloader.cancel(animeId+"|"+episode);
            try{return downloader.deleteFile(animeTitle,episode)?ok():new JSONObject().put("error","File not found").toString();}catch(Exception e){return "{\"error\":\"Delete failed\"}";}
        }
        String downloadsList(){try{return new JSONObject().put("files",downloader.listFiles()).toString();}catch(Exception e){return "{\"files\":[]}";}}
        String activeDownloads(){try{return new JSONObject().put("downloads",downloader.activeDownloads()).toString();}catch(Exception e){return "{\"downloads\":[]}";}}
        // Serve a downloaded file over the loopback HTTP server so the WebView
        // player (and any external player) can fetch it. Returns the base URL
        // plus the container type; the player appends .m3u8 for MPEG-TS so
        // hls.js gets a playlist to load.
        String playDownload(String animeTitle,String episode){
            try{
                JSONObject f=downloader.findFile(animeTitle,episode);
                if(f==null)return new JSONObject().put("error","Download not found").toString();
                String fileName=f.optString("fileName");
                String dir=f.optString("dirName");
                long size=f.optLong("size");
                String lower=fileName.toLowerCase(java.util.Locale.ROOT);
                String ext=lower.endsWith(".mp4")?"mp4":(lower.endsWith(".ts")?"ts":"");
                if(ext.isEmpty())return new JSONObject().put("error","Unsupported file type").toString();
                String token="dl"+(++downloadToken);
                String url=LocalFileServer.get().register(token,size,ext.equals("mp4")?"video/mp4":"video/mp2t",offset->{
                    try{return downloader.openFile(dir,fileName,offset);}catch(Exception e){return null;}
                });
                return new JSONObject().put("url",url).put("ext",ext).put("fileName",fileName).toString();
            }catch(Exception e){try{return new JSONObject().put("error",e.getMessage()==null?"Could not open download":e.getMessage()).toString();}catch(Exception ignored){return "{\"error\":\"Could not open download\"}";}}
        }
        String clearDownloads(){try{return new JSONObject().put("deleted",downloader.deleteAll()).toString();}catch(Exception e){return "{\"error\":\"Could not clear downloads\"}";}}
        String mpvStatus(){
            boolean cli=mpvCliAvailable(), app=mpvAppAvailable();
            try{return new JSONObject().put("cli",cli).put("app",app).put("available",cli||app).toString();}catch(Exception ignored){return "{\"available\":false}";}
        }
        // Resolve the full mpv binary path. The app process PATH rarely includes
        // Termux's bin dir, so launching by bare name would fail even though the
        // detection above found the binary — always exec the absolute path.
        private String mpvCliPath(){
            String path=System.getenv("PATH");
            String dirs=(path==null?"":path)+":/data/data/com.termux/files/usr/bin:/usr/bin";
            for(String d:dirs.split(":")){if(!d.isEmpty()){java.io.File f=new java.io.File(d,"mpv");if(f.canExecute())return f.getAbsolutePath();}}
            return null;
        }
        private boolean mpvCliAvailable(){return mpvCliPath()!=null;}
        // mpv-android renamed from is.mpv.android to is.xyz.mpv in v1.4.0 — check
        // both so the MPV button shows for every install (Play/F-Droid/GitHub).
        private String mpvAppPackage(){
            for(String pkg:new String[]{"is.xyz.mpv","is.mpv.android"}){
                try{appContext.getPackageManager().getPackageInfo(pkg,0);return pkg;}catch(Exception ignored){}
            }
            return null;
        }
        private boolean mpvAppAvailable(){return mpvAppPackage()!=null;}
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
        String playInMpv(String animeId,String animeTitle,String episode,String type,String referer,String userAgent){
            JSONObject out=new JSONObject();
            try{
                JSONObject stream=scraper.stream(animeId,episode,type);
                String url=stream.optString("url");
                // The stream result carries the embed page the manifest was fetched
                // from — mpv must present it (plus the browser UA) to load the CDN.
                String streamReferer=stream.optString("referer",referer);
                String title=((animeTitle==null||animeTitle.isEmpty())?"Anime":animeTitle)+" - Episode "+episode;
                out.put("url",url).put("raw",stream.optString("raw",url)).put("type",stream.opt("type"));
                if(url.isEmpty())return out.put("error","No stream available").toString();
                // ani-cli's Termux flow: launch mpv-android via an ACTION_VIEW
                // intent (URL + title extra). Referer/UA can't ride on the intent,
                // so the per-playback flags are written to the same include file
                // ani-cli uses. The CLI binary path is kept as a fallback for
                // rooted setups where exec works.
                String pkg=mpvAppPackage();
                if(pkg!=null)launchMpvApp(url,title,streamReferer,userAgent,pkg,null);
                else if(mpvCliAvailable())launchMpv(url,title,streamReferer,userAgent);
                else return out.put("error","MPV is not installed. Install mpv-android (is.xyz.mpv) from the Play Store or F-Droid.").toString();
                return out.put("ok",true).put("app",pkg!=null).toString();
            }catch(Exception e){try{return out.put("error",e.getMessage()==null?"Failed to launch MPV":e.getMessage()).toString();}catch(Exception ignored){return "{\"error\":\"Failed to launch MPV\"}";}}
        }
        // MPV for a downloaded file: the file is already served by the loopback
        // LocalFileServer, so mpv (app or CLI) just opens that URL directly. The
        // mime type is required by mpv-android's intent filter (extension-less
        // loopback URL has no file extension to match on).
        String playInMpvUrl(String url,String title,String mime){
            JSONObject out=new JSONObject();
            try{
                if(url==null||url.isEmpty())return out.put("error","No file open").toString();
                String pkg=mpvAppPackage();
                if(pkg!=null)launchMpvApp(url,title,"","",pkg,(mime==null||mime.isEmpty())?"video/mp4":mime);
                else if(mpvCliAvailable())launchMpv(url,title,"","");
                else return out.put("error","MPV is not installed. Install mpv-android (is.xyz.mpv) from the Play Store or F-Droid.").toString();
                return out.put("ok",true).put("app",pkg!=null).toString();
            }catch(Exception e){try{return out.put("error",e.getMessage()==null?"Failed to launch MPV":e.getMessage()).toString();}catch(Exception ignored){return "{\"error\":\"Failed to launch MPV\"}";}}
        }
        private void launchMpv(String url,String title,String referer,String userAgent)throws Exception{
            String binary=mpvCliPath();
            if(binary==null)throw new Exception("Could not launch MPV. Check if mpv is installed.");
            Process p;
            try{
                p=new ProcessBuilder(binary,"--no-stdin","--tls-verify=no","--force-window=yes",
                    "--force-media-title="+title,"--referrer="+referer,"--user-agent="+userAgent,url)
                    .redirectErrorStream(true).start();
            }
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
        private void launchMpvApp(String url,String title,String referer,String userAgent,String pkg,String mime)throws Exception{
            // ani-cli's android_mpv: am start -a VIEW -d <url> -e title <title>.
            // mpv-android's intent extras are title/subs/position/decode_mode — no
            // referer or UA — so those are pushed through the config include below
            // (and mpv-android also has a global User-Agent in its own settings).
            android.content.Intent i=new android.content.Intent(android.content.Intent.ACTION_VIEW);
            // mpv-android only accepts http(s) URLs that carry a video/audio MIME
            // type (or a recognized file extension). Downloaded files are served
            // from the extension-less loopback token URL, so the MIME must be set
            // explicitly or the intent resolves to nothing.
            if(mime!=null&&!mime.isEmpty())i.setDataAndType(android.net.Uri.parse(url),mime);
            else i.setData(android.net.Uri.parse(url));
            i.setPackage(pkg);
            i.putExtra("title",title);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if(i.resolveActivity(appContext.getPackageManager())==null)throw new Exception("MPV app could not open this stream.");
            writeMpvFlags(referer,userAgent);
            appContext.startActivity(i);
        }
        // Best-effort mirror of ani-cli's android_mpv: write the per-playback flags
        // (tls, referer, UA) to /storage/emulated/0/mpv/mpv.config.mp4. Users who
        // add include='/storage/emulated/0/mpv/mpv.config.mp4' to MPV > Settings >
        // Advanced > mpv.conf get them automatically, so the CDN sees the same
        // referer/UA the WebView sends. Silently skipped when scoped storage (or a
        // locked-down device) blocks the write.
        // NOTE: unlike ani-cli (which clears the file on exit) this is overwritten
        // on every launch but never cleared — a stale referer/UA may linger for
        // manually-launched mpv sessions until the next Elsnime play.
        private void writeMpvFlags(String referer,String userAgent){
            try{
                java.io.File dir=new java.io.File("/storage/emulated/0/mpv");
                if(!dir.exists()&&!dir.mkdirs())return;
                StringBuilder sb=new StringBuilder();
                sb.append("tls-verify=no\n");
                String ua=sanitizeConfigValue(userAgent);
                String ref=sanitizeConfigValue(referer);
                if(!ua.isEmpty())sb.append("user-agent=").append(ua).append('\n');
                if(!ref.isEmpty())sb.append("http-header-fields=Referer: ").append(ref).append('\n');
                java.io.File f=new java.io.File(dir,"mpv.config.mp4");
                try(java.io.FileOutputStream os=new java.io.FileOutputStream(f)){
                    os.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }catch(Exception ignored){}
        }
        // Config values become one mpv.conf line; strip anything that could break
        // out of the option=value line (newlines, the list separator, comments).
        private static String sanitizeConfigValue(String s){
            return s==null?"":s.replace('\r',' ').replace('\n',' ').replace(';',' ').replace('#',' ').trim();
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
            if(method.equals("GET")&&path.equals("/api/aniskip"))return scraper.skipTimes(p.q("title"), p.q("episode")).toString();
            if(method.equals("GET")&&path.equals("/api/home"))return new JSONObject().put("trending",scraper.trending()).put("history",db.history()).toString();
            if(path.equals("/api/history")&&method.equals("GET"))return db.history(p.q("anime_id")).toString();
            if(path.equals("/api/history")&&method.equals("POST")){db.save(new JSONObject(body));return ok();}
            // Clear-all (Settings > Clear history): one DELETE, no fetch needed.
            if(path.equals("/api/history")&&method.equals("DELETE")){db.clearHistory();return ok();}
            if(path.startsWith("/api/history/anime/")&&method.equals("DELETE")){db.deleteByAnime(path.substring(19));return ok();}
            if(path.startsWith("/api/history/")&&method.equals("DELETE")){db.delete(Integer.parseInt(path.substring(13)));return ok();}
            if(path.equals("/api/watched")&&method.equals("POST")){db.markWatchedUpto(new JSONObject(body));return ok();}
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
        JSONArray history() throws Exception { return history(null); }
        JSONArray history(String animeId) throws Exception {
            JSONArray result = new JSONArray();
            boolean filter = animeId != null && !animeId.isEmpty();
            // Per-series lists need every episode row of the series' histories, so
            // the cap is generous (a marked-watched series stores one row per
            // episode; 5000 covers even the longest-running series).
            try (Cursor c = getReadableDatabase().query(
                    "history", null, filter ? "anime_id=?" : null, filter ? new String[]{animeId} : null, null, null, "last_watched DESC", "5000")) {
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
        void deleteByAnime(String animeId){getWritableDatabase().delete("history","anime_id=?",new String[]{animeId});}
        // Wipe the whole table in one statement — Settings' "Clear history"
        // calls this directly, so it never depends on a prior fetch.
        void clearHistory(){getWritableDatabase().delete("history",null,null);}
        // "Mark as watched" sets a watched-through boundary: episodes up to the
        // given index become watched, and episodes past it that were marked by a
        // previous press (progress=1, duration=1) revert to unwatched. Runs in
        // one transaction so a long episode list can't leave the table half-updated.
        void markWatchedUpto(JSONObject x){
            String animeId=x.optString("anime_id");
            String animeTitle=x.optString("anime_title");
            String thumbnail=x.optString("thumbnail");
            JSONArray episodes=x.optJSONArray("episodes");
            int upto=x.optInt("upto_index",-1);
            if(animeId.isEmpty()||episodes==null||upto<0||upto>=episodes.length())return;
            SQLiteDatabase d=getWritableDatabase();
            d.beginTransaction();
            try{
                long ts=System.currentTimeMillis()/1000;
                for(int i=0;i<=upto;i++){
                    ContentValues v=new ContentValues();
                    v.put("anime_id",animeId);
                    v.put("anime_title",animeTitle);
                    v.put("episode",episodes.optString(i));
                    v.put("progress",1.0);
                    v.put("duration",1.0);
                    v.put("thumbnail",thumbnail);
                    v.put("last_watched",ts);
                    d.insertWithOnConflict("history",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                }
                // Revert the watched-through boundary: only rows written by the
                // button itself (progress=1, duration=1) are removed, so episodes
                // the user actually watched to completion keep their state.
                for(int i=upto+1;i<episodes.length();i++){
                    d.delete("history","anime_id=? AND episode=? AND progress=1 AND duration=1",new String[]{animeId,episodes.optString(i)});
                }
                d.setTransactionSuccessful();
            }finally{d.endTransaction();}
        }
        JSONObject settings() throws Exception{JSONObject o=new JSONObject().put("hw_accel",false).put("sub_lang","sub").put("player","web").put("theme","auto").put("aniskip","on").put("performance_mode","auto").put("accent_h",239).put("quality","480").put("separate_quality","off").put("stream_quality","480").put("download_quality","480");try(Cursor c=getReadableDatabase().query("settings",new String[]{"key","value"},null,null,null,null,null)){while(c.moveToNext()){String k=c.getString(0),v=c.getString(1);try{o.put(k,new JSONTokener(v).nextValue());}catch(Exception ignored){}}}return o;}
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
