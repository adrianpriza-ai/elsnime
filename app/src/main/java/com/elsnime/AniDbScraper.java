package com.elsnime;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;

/** AniDB-backed port of ani-cli.1's search, episode, and HLS playback flow. */
public final class AniDbScraper {
    private static final String ANIDB = "https://anidb.app";
    private static final String JIKAN = "https://api.jikan.moe/v4";
    private static final String ANILIST = "https://graphql.anilist.co";
    // Package-visible so the WebView can present the same browser fingerprint
    // the scraper uses (CDN media requests are gated on it).
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final long TTL_DAY = 86400L, TTL_HOUR = 3600L;

    public interface CacheStore { String get(String key); void put(String key,String value,long ttlSeconds); void clear(); void clearPrefix(String prefix); }
    private volatile CacheStore cache;
    public void setCache(CacheStore store){cache=store;}
    public void clearCache(){CacheStore c=cache;if(c!=null)c.clear();}
    public void clearCachePrefix(String prefix){CacheStore c=cache;if(c!=null)c.clearPrefix(prefix);}

    /** Pluggable HTTP layer so the app can swap in Cronet (Chrome TLS fingerprint) while the default stays plain HttpURLConnection. */
    public interface HttpTransport { String request(String method,String url,String body,String referer,String origin)throws IOException; }
    private volatile HttpTransport transport=HttpUrlConnectionTransport.INSTANCE;
    public void setTransport(HttpTransport t){if(t!=null)transport=t;}

    public JSONArray search(String query,String ignoredMode) throws Exception {
        return cachedArray("anidb-search|"+query,TTL_DAY,()->enrich(aniDbSearch(query),12));
    }

    private JSONArray aniDbSearch(String query) throws Exception {
        String page=get(ANIDB+"/browse?q="+URLEncoder.encode(query,"UTF-8"),ANIDB,ANIDB);
        rejectCloudflare(page);
        // Some pages embed the markup JSON-escaped (\"); normalize so one pattern fits.
        page=page.replace("\\\"","\"");
        JSONArray out=new JSONArray(); Set<String> seen=new HashSet<>();
        Matcher m=Pattern.compile("anime/([a-z0-9-]+-\\d+)\"[^>]*title=\"([^\"]+)\"",Pattern.CASE_INSENSITIVE).matcher(page);
        while(m.find()&&out.length()<40){
            String id=m.group(1); if(!seen.add(id))continue;
            String title=html(m.group(2));
            out.put(new JSONObject().put("id",id).put("title",title).put("raw_title",title));
        }
        return out;
    }

    public JSONObject resolve(JSONObject body) throws Exception {
        return cachedObject("anidb-resolve|"+body.toString(),TTL_DAY,()->resolveUncached(body));
    }

    private JSONObject resolveUncached(JSONObject body) throws Exception {
        JSONArray titles=body.optJSONArray("titles");
        Map<String,JSONObject> matches=new LinkedHashMap<>();
        if(titles!=null) for(int i=0;i<titles.length()&&i<6;i++){
            String wanted=normalize(titles.optString(i)); if(wanted.length()<2)continue;
            JSONArray results=aniDbSearch(titles.optString(i));
            for(int j=0;j<results.length();j++){
                JSONObject r=results.optJSONObject(j); if(r==null)continue;
                int score=scoreName(r.optString("title"),wanted); if(score==0)continue;
                JSONObject old=matches.get(r.optString("id"));
                if(old==null||score>old.optInt("match_score"))r.put("match_score",score); else continue;
                matches.put(r.optString("id"),r);
            }
        }
        List<JSONObject> ranked=new ArrayList<>(matches.values());
        ranked.sort((a,b)->Integer.compare(b.optInt("match_score"),a.optInt("match_score")));
        JSONArray alternatives=new JSONArray();
        for(int i=0;i<ranked.size()&&i<8;i++){JSONObject r=ranked.get(i);try{r.put("anilist",aniList(r.optString("title")));}catch(Exception ignored){}alternatives.put(r);}
        Object best=ranked.isEmpty()||ranked.get(0).optInt("match_score")<500?JSONObject.NULL:ranked.get(0);
        return new JSONObject().put("best",best).put("alternatives",alternatives);
    }

    public JSONArray episodes(String animeId,String ignoredMode) throws Exception {
        return cachedArray("anidb-episodes|"+animeId,TTL_HOUR,()->{
            JSONObject root=json(get(ANIDB+"/api/frontend/anime/"+numericId(animeId)+"/episodes",ANIDB,ANIDB));
            List<String> list=new ArrayList<>(); collectEpisodeNumbers(root,list);
            Set<String> unique=new LinkedHashSet<>(list); list=new ArrayList<>(unique);
            list.sort(Comparator.comparingDouble(AniDbScraper::number));
            JSONArray out=new JSONArray();for(String episode:list)out.put(episode);return out;
        });
    }

    public JSONObject stream(String animeId,String episode,String mode) throws Exception {
        JSONObject episodes=json(get(ANIDB+"/api/frontend/anime/"+numericId(animeId)+"/episodes",ANIDB,ANIDB));
        String episodeId=findEpisodeId(episodes,episode);
        if(episodeId.isEmpty())return error("Episode not found");
        String languages=get(ANIDB+"/api/frontend/episode/"+episodeId+"/languages",ANIDB,ANIDB);
        rejectCloudflare(languages);
        String embed=findEmbed(json(languages),"dub".equalsIgnoreCase(mode)?"eng":"jpn",false);
        if(embed.isEmpty())return error("No "+mode+" source found");
        String page=get(embed,ANIDB,ANIDB); rejectCloudflare(page);
        Matcher file=Pattern.compile("file\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(page);
        if(!file.find())return error("HLS playlist not found");
        String master=absoluteUrl(embed,file.group(1));
        String manifest=get(master,embed,ANIDB);
        String url=bestVariant(master,manifest);
        if(url.isEmpty())return error("No video quality found");
        // "referer" is the embed page the manifest was fetched with; mpv needs
        // it (plus a browser UA) to load the CDN playlist outside the WebView.
        // "master" is the full multi-quality master playlist: the web player
        // hands it to hls.js so the settings > quality menu lists every
        // rendition (mpv keeps the pre-resolved best-variant "url").
        return new JSONObject().put("url",url).put("raw",url).put("master",master).put("type","hls").put("referer",embed);
    }

    public JSONArray trending() throws Exception { return cachedArray("trending",TTL_DAY,this::trendingUncached); }
    private JSONArray trendingUncached() throws Exception {
        String gql="query{Page(page:1,perPage:24){media(type:ANIME,sort:TRENDING_DESC,status_not:NOT_YET_RELEASED){id idMal format synonyms title{romaji english native} coverImage{large extraLarge} bannerImage averageScore episodes status seasonYear description(asHtml:false) genres}}}";
        JSONArray media=postJson(ANILIST,new JSONObject().put("query",gql)).optJSONObject("data").optJSONObject("Page").optJSONArray("media");
        JSONArray out=new JSONArray();if(media==null)return out;
        for(int i=0;i<media.length();i++){JSONObject m=media.optJSONObject(i);if(m==null)continue;JSONObject t=m.optJSONObject("title");String title=t==null?"":t.optString("english",t.optString("romaji"));out.put(new JSONObject().put("id",JSONObject.NULL).put("title",title).put("thumbnail",m.optJSONObject("coverImage").optString("large")).put("score",m.opt("averageScore")).put("anilist",m));}
        return out;
    }

    public JSONObject anime(String jikanId,String ignoredMode) throws Exception {
        return cachedObject("anime|"+jikanId,TTL_DAY,()->{
            JSONObject result=normalizeJikan(jikan("/anime/"+URLEncoder.encode(jikanId,"UTF-8")+"/full"));
            JSONArray matches=aniDbSearch(result.optString("title"));
            if(matches.length()>0){JSONObject first=matches.optJSONObject(0);result.put("id",first.optString("id"));}
            return result;
        });
    }

    public JSONArray searchTag(String tag) throws Exception {
        return cachedArray("tag|"+tag,TTL_DAY,()->{
            String gql="query($genre:String){Page(page:1,perPage:24){media(type:ANIME,genre:$genre,sort:POPULARITY_DESC){id idMal format synonyms title{romaji english native} coverImage{large extraLarge} bannerImage averageScore episodes status seasonYear description(asHtml:false) genres}}}";
            JSONArray media=postJson(ANILIST,new JSONObject().put("query",gql).put("variables",new JSONObject().put("genre",tag))).optJSONObject("data").optJSONObject("Page").optJSONArray("media");
            JSONArray out=new JSONArray();if(media!=null)for(int i=0;i<media.length();i++){JSONObject m=media.optJSONObject(i);if(m==null)continue;JSONObject t=m.optJSONObject("title");String title=t==null?"":t.optString("english",t.optString("romaji"));out.put(new JSONObject().put("id",JSONObject.NULL).put("title",title).put("thumbnail",m.optJSONObject("coverImage").optString("large")).put("score",m.opt("averageScore")).put("anilist",m));}return out;
        });
    }

    public JSONArray tags() throws Exception { return cachedArray("tags",TTL_DAY,()->{
        JSONArray data=jikan("/genres/anime").optJSONArray("data"),out=new JSONArray();
        if(data!=null)for(int i=0;i<data.length();i++){JSONObject x=data.optJSONObject(i);if(x!=null)out.put(new JSONObject().put("id",x.optInt("mal_id")).put("name",x.optString("name")).put("count",x.optInt("count")));}return out;
    }); }

    /** AniSkip: resolve the show's MAL id (via AniList, cached) and fetch the
     *  community skip times for an episode over the app's own transport, so
     *  the player never depends on a WebView fetch or on the search-time
     *  enrichment having attached anilist.idMal. Episodes with a resolved MAL
     *  id but no community data are cached so they aren't re-queried; a failed
     *  lookup (mal_id 0) is returned as an error so cachedObject never caches
     *  it — a transient AniList failure must not lock the episode into
     *  emptiness for an hour. */
    public JSONObject skipTimes(String title, String episode) throws Exception {
        return cachedObject("aniskip|"+title+"|"+episode,TTL_HOUR,()->{
            int malId=0;
            try{JSONObject media=aniList(title);malId=media==null?0:media.optInt("idMal",0);}catch(Exception ignored){}
            JSONObject out=new JSONObject().put("mal_id",malId);
            if(malId<=0)return out.put("error","could not resolve MAL id").put("results",new JSONArray());
            String url="https://api.aniskip.com/v2/skip-times/"+malId+"/"+URLEncoder.encode(episode,"UTF-8")
                +"?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=0";
            try{JSONObject data=json(get(url,"https://api.aniskip.com","https://api.aniskip.com"));
                out.put("results",data.optJSONArray("results")==null?new JSONArray():data.optJSONArray("results"));}
            catch(Exception ignored){out.put("results",new JSONArray());}
            return out;
        });
    }

    private JSONArray enrich(JSONArray input,int limit) throws Exception { JSONArray out=new JSONArray();for(int i=0;i<input.length()&&i<limit;i++){JSONObject item=input.optJSONObject(i);if(item==null)continue;try{item.put("anilist",aniList(item.optString("title")));}catch(Exception ignored){}out.put(item);}return out; }
    private JSONObject aniList(String title) throws Exception {String q="query($search:String){Media(search:$search,type:ANIME){id idMal format synonyms title{romaji english native} coverImage{large extraLarge} bannerImage averageScore episodes status seasonYear description(asHtml:false) genres}}";return postJson(ANILIST,new JSONObject().put("query",q).put("variables",new JSONObject().put("search",title))).optJSONObject("data").optJSONObject("Media");}
    private JSONObject jikan(String path) throws Exception{return json(get(JIKAN+path,"https://jikan.moe/","https://jikan.moe"));}
    private JSONObject normalizeJikan(JSONObject entry)throws Exception{JSONObject d=entry.optJSONObject("data");if(d==null)d=entry;JSONObject images=d.optJSONObject("images"),jpg=images==null?null:images.optJSONObject("jpg"),webp=images==null?null:images.optJSONObject("webp");String title=d.optString("title");if(title.isEmpty())title=d.optString("title_english",d.optString("title_japanese"));JSONObject titles=new JSONObject().put("english",d.optString("title_english",title)).put("romaji",title);JSONObject cover=new JSONObject().put("large",jpg==null?"":jpg.optString("large_image_url",jpg.optString("image_url"))).put("extraLarge",webp==null?"":webp.optString("large_image_url",webp.optString("image_url")));JSONArray genres=new JSONArray(),gs=d.optJSONArray("genres");if(gs!=null)for(int i=0;i<gs.length();i++)genres.put(gs.optJSONObject(i).optString("name"));JSONObject al=new JSONObject().put("title",titles).put("coverImage",cover).put("averageScore",d.optDouble("score")*10).put("episodes",d.opt("episodes")).put("status",d.optString("status")).put("seasonYear",d.opt("year")).put("description",d.optString("synopsis")).put("genres",genres);return new JSONObject().put("id",JSONObject.NULL).put("jikan_id",d.optInt("mal_id")).put("title",title).put("thumbnail",webp==null?"":webp.optString("image_url")).put("score",d.opt("score")).put("anilist",al);}

    private String findEpisodeId(Object value,String wanted){if(value instanceof JSONObject){JSONObject o=(JSONObject)value;if(o.has("id")&&wanted.equals(o.optString("number")))return o.optString("id");for(Iterator<String> it=o.keys();it.hasNext();){String found=findEpisodeId(o.opt(it.next()),wanted);if(!found.isEmpty())return found;}}else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){String found=findEpisodeId(a.opt(i),wanted);if(!found.isEmpty())return found;}}return "";}
    private void collectEpisodeNumbers(Object value,List<String> out){if(value instanceof JSONObject){JSONObject o=(JSONObject)value;if(o.has("id")&&o.has("number")){String n=o.optString("number");if(!n.isEmpty())out.add(n);}for(Iterator<String> it=o.keys();it.hasNext();)collectEpisodeNumbers(o.opt(it.next()),out);}else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)collectEpisodeNumbers(a.opt(i),out);}}
    private String findEmbed(Object value,String language,boolean inherited){if(value instanceof JSONObject){JSONObject o=(JSONObject)value;boolean matched=inherited||language.equalsIgnoreCase(o.optString("code"))||language.equalsIgnoreCase(o.optString("language"));String embed=o.optString("embed_url");if(matched&&!embed.isEmpty())return unescapeUrl(embed);for(Iterator<String> it=o.keys();it.hasNext();){String key=it.next();String found=findEmbed(o.opt(key),language,matched||language.equalsIgnoreCase(key));if(!found.isEmpty())return found;}}else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){String found=findEmbed(a.opt(i),language,inherited);if(!found.isEmpty())return found;}}return "";}
    private String bestVariant(String master,String manifest){return pickVariant(master,manifest,0);}
    /** Pick a variant playlist from a master manifest: the highest rendition when
     *  targetHeight<=0, otherwise the closest at-or-below the requested height
     *  (falling back to the smallest above it). Shared by the web player (best
     *  quality) and the native downloader (user-chosen Default Quality). */
    static String pickVariant(String master,String manifest,int targetHeight){
        List<String[]> links=new ArrayList<>();
        for(String part:manifest.split("#EXT-X-STREAM-INF")){
            part=part.split("#EXT-X-I-FRAME")[0];
            Matcher rm=Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(part);
            if(!rm.find())continue;
            Matcher um=Pattern.compile("https?://[^\\s#\\\"]+").matcher(part);
            String uri=um.find()?um.group():null;
            if(uri==null){String[] toks=part.trim().split("\\s+");if(toks.length>0)uri=toks[toks.length-1];}
            if(uri==null||uri.isEmpty())continue;
            links.add(new String[]{rm.group(1),absoluteUrl(master,uri)});
        }
        if(links.isEmpty())return "";
        int target=targetHeight>0?targetHeight:Integer.MAX_VALUE;
        String best=null,above=null;int bestH=-1,aboveH=Integer.MAX_VALUE;
        for(String[] l:links){
            int h=quality(l[0]);
            if(h<=target&&h>bestH){bestH=h;best=l[1];}
            else if(h>target&&h<aboveH){aboveH=h;above=l[1];}
        }
        return best!=null?best:(above!=null?above:links.get(0)[1]);
    }
    private static int scoreName(String title,String wanted){String n=normalize(title);if(n.equals(wanted))return 1000;if(n.startsWith(wanted))return 700;if(n.contains(wanted))return 500;return 0;}
    private static String normalize(String s){return java.text.Normalizer.normalize(s==null?"":s.toLowerCase(Locale.US),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private static String numericId(String slug){int p=slug.lastIndexOf('-');return p<0?slug:slug.substring(p+1);}
    private static double number(String s){try{return Double.parseDouble(s);}catch(Exception e){return 0;}}
    private static int quality(String s){Matcher m=Pattern.compile("^(\\d+)").matcher(s);return m.find()?Integer.parseInt(m.group(1)):0;}
    private static String html(String s){return s.replace("&#039;","'").replace("&quot;","\"").replace("&amp;","&");}
    private static String unescapeUrl(String s){return s.replace("\\/","/").replace("\\u0026","&");}
    private static String absoluteUrl(String base,String value){try{return new URL(new URL(base),unescapeUrl(value)).toString();}catch(Exception e){return unescapeUrl(value);}}
    private static JSONObject error(String message)throws Exception{return new JSONObject().put("error",message);}
    private static JSONObject json(String body)throws Exception{rejectCloudflare(body);return new JSONObject(body);}
    static void rejectCloudflare(String body)throws IOException{if(body!=null&&body.contains("Just a moment"))throw new IOException("AniDB blocked this request (Cloudflare challenge). Try again in a moment.");}
    private JSONArray cachedArray(String key,long ttl,Callable<JSONArray> load)throws Exception{
        CacheStore c=cache;
        if(c!=null){
            String hit=c.get(key);
            // Empty results are treated as a miss: a transient failure or a stale
            // "no results" page must not lock a query into emptiness for its TTL.
            if(hit!=null)try{JSONArray cached=new JSONArray(hit);if(cached.length()>0)return cached;}catch(Exception ignored){}
        }
        JSONArray value=load.call();
        if(c!=null&&value.length()>0)c.put(key,value.toString(),ttl);
        return value;
    }
    private JSONObject cachedObject(String key,long ttl,Callable<JSONObject> load)throws Exception{CacheStore c=cache;if(c!=null){String hit=c.get(key);if(hit!=null)try{return new JSONObject(hit);}catch(Exception ignored){}}JSONObject value=load.call();// Never cache error payloads — a transient failure shouldn't poison the cache.
        if(c!=null&&!value.has("error"))c.put(key,value.toString(),ttl);return value;}
    private JSONObject postJson(String url,JSONObject body)throws Exception{return json(request("POST",url,body.toString(),ANIDB,ANIDB));}
    private String get(String url,String referer,String origin)throws Exception{return request("GET",url,null,referer,origin);}
    private String request(String method,String url,String body,String referer,String origin)throws Exception{
        IOException lastChallenge=null;
        for(int attempt=0;attempt<3;attempt++){
            try{return transport.request(method,url,body,referer,origin);}
            catch(IOException e){
                if(e.getMessage()!=null&&e.getMessage().contains("Cloudflare")){
                    lastChallenge=e;
                    try{Thread.sleep(1200L*(attempt+1));}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                }else throw e;
            }
        }
        throw lastChallenge!=null?lastChallenge:new IOException("Request failed");
    }

    /** Default transport: plain HttpURLConnection (also the JVM/test fallback when Cronet is unavailable). */
    public static final class HttpUrlConnectionTransport implements HttpTransport {
        public static final HttpUrlConnectionTransport INSTANCE=new HttpUrlConnectionTransport();
        @Override
        public String request(String method,String url,String body,String referer,String origin)throws IOException{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(12000);c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent",UA);
            c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language","en-US,en;q=0.9");
            c.setRequestProperty("Referer",referer);
            if(origin!=null)c.setRequestProperty("Origin",origin);
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            if(in==null)throw new IOException("HTTP "+code);
            try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder out=new StringBuilder();String line;while((line=r.readLine())!=null)out.append(line);String result=out.toString();rejectCloudflare(result);if(code>=400)throw new IOException("HTTP "+code);return result;}
        }
    }
}
