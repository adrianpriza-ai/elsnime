package com.elsnime;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;

/** Java port of ani-cli-rs's anikoto.rs (Anikoto API + AniList + MegaPlay),
 *  wired in as a fallback provider next to {@link AniDbScraper}: when anidb.app
 *  is unreachable (Cloudflare/outage) the app searches AniList / anikotoapi.site
 *  and resolves streams through MegaPlay embeds instead.
 *
 *  <p>Search results carry the same shape the UI expects from AniDbScraper
 *  ({@code id}, {@code title}/{@code raw_title}, {@code thumbnail}, embedded
 *  {@code anilist} media object) so cards open and render identically. Show ids
 *  are the ani-cli "anikoto:&lt;base64url JSON&gt;" envelope (anilist/mal/anikoto
 *  ids + title + episode count); {@link #owns} routes those back here for
 *  episodes/streams without ever touching anidb.app.
 *
 *  <p>Reuses AniDbScraper's pluggable {@link AniDbScraper.CacheStore} (SQLite)
 *  and {@link AniDbScraper.HttpTransport} (Cronet) so it shares the app's
 *  fingerprint and cache namespace. Net semantics mirror the Rust client:
 *  series metadata is cached per series, resolved source URLs never are.
 */
public final class AnikotoScraper {
    private static final String API = "https://anikotoapi.site";
    private static final String ANILIST = "https://graphql.anilist.co";
    private static final String MEGAPLAY = "https://megaplay.buzz";
    static final String PREFIX = "anikoto:";
    private static final long TTL_DAY = 86400L, TTL_HOUR = 3600L;
    // AniList media fields mirroring AniDbScraper's search enrichment, so the
    // fallback cards carry the same metadata the primary ones do.
    private static final String MEDIA_FIELDS = "id idMal format isAdult synonyms title{romaji english native} coverImage{large extraLarge} bannerImage averageScore episodes status seasonYear description(asHtml:false) genres";
    private static final Pattern DATA_ID = Pattern.compile("(?i)\\bdata-id=[\"'](\\d+)[\"']");
    // Newer embed shells drop data-id and only carry the real file id.
    private static final Pattern DATA_REAL_ID = Pattern.compile("(?i)\\bdata-realid=[\"'](\\d+)[\"']");
    private static final int MAX_RESULTS = 40;

    private volatile AniDbScraper.CacheStore cache;
    private volatile AniDbScraper.HttpTransport transport = AniDbScraper.HttpUrlConnectionTransport.INSTANCE;
    public void setCache(AniDbScraper.CacheStore store){cache=store;}
    public void setTransport(AniDbScraper.HttpTransport t){if(t!=null)transport=t;}
    public void clearCache(){AniDbScraper.CacheStore c=cache;if(c!=null)c.clear();}
    public void clearCachePrefix(String prefix){AniDbScraper.CacheStore c=cache;if(c!=null)c.clearPrefix(prefix);}

    /** True when a show id came from this provider and must be served by it
     *  (anidb.app can't decode these ids). Pure-numeric ids are the ani-cli
     *  convention for a bare anikoto series id. */
    static boolean owns(String showId){return showId!=null&&showId.startsWith(PREFIX);}
    static boolean ownsNumeric(String showId){return showId!=null&&!showId.isEmpty()&&showId.chars().allMatch(Character::isDigit);}

    // -search

    public JSONArray search(String query,String ignoredMode) throws Exception {
        String q=query==null?"":query.trim();
        if(q.isEmpty())throw new IOException("Empty search query");
        return cachedArray("ak-search|"+q.toLowerCase(Locale.US),TTL_DAY,()->searchUncached(q));
    }

    private JSONArray searchUncached(String query) throws Exception {
        // Both sources are tried (like the Rust client's join): the Anikoto
        // recent-anime catalog maps titles to anikoto series ids (AniList
        // results alone would fall back to the ani/mal embed routes), and the
        // AniList page provides the full metadata each card needs.
        JSONArray anilistMedia=null;
        try{anilistMedia=anilistSearch(query);}catch(Exception ignored){}
        if(anilistMedia==null)anilistMedia=new JSONArray();
        JSONArray recent=new JSONArray();
        try{recent=anikotoRecent(query);}catch(Exception ignored){}
        if(anilistMedia.length()==0&&recent.length()==0)
            throw new IOException("Anikoto search failed (AniList and recent-anime both unreachable)");

        List<JSONObject> results=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        // recent first, then AniList, deduped by anilist id (fallback: title),
        // matching the Rust merge order so known anikoto series rank above the
        // plain AniList hits.
        for(int i=0;i<recent.length()&&results.size()<MAX_RESULTS;i++){
            JSONObject c=recent.optJSONObject(i);if(c==null)continue;
            if(seen.add(dedupeKey(c)))results.add(c);
        }
        for(int i=0;i<anilistMedia.length()&&results.size()<MAX_RESULTS;i++){
            JSONObject m=anilistMedia.optJSONObject(i);if(m==null)continue;
            JSONObject c=fromMedia(m);if(c==null)continue;
            if(seen.add(dedupeKey(c)))results.add(c);
        }
        // Cards that came only from the recent catalog have no cover/metadata;
        // fetch it for the first few via AniList Media(id).
        int enriched=0;
        for(JSONObject c:results){
            if(c.has("anilist")||enriched>=8)continue;
            String anilistId=decode(c.optString("id")).optString("anilistId","");
            if(anilistId.isEmpty())continue;
            try{
                JSONObject m=anilistMediaById(anilistId);
                if(m!=null&&!JSONObject.NULL.equals(m)){c.put("anilist",m);enriched++;}
            }catch(Exception ignored){}
        }
        return new JSONArray(results);
    }

    private String dedupeKey(JSONObject c){
        try{String anilistId=decode(c.optString("id")).optString("anilistId","");if(!anilistId.isEmpty())return "ani:"+anilistId;}catch(Exception ignored){}
        return "title:"+normalize(c.optString("title"));
    }

    /** AniList title page (up to 40 media) — the metadata backbone of search. */
    private JSONArray anilistSearch(String query) throws Exception {
        String gql="query($search:String){Page(page:1,perPage:40){media(type:ANIME,search:$search,sort:SEARCH_MATCH){"+MEDIA_FIELDS+"}}}";
        JSONObject root=postAnilist(gql,new JSONObject().put("search",query));
        JSONObject data=root.optJSONObject("data");
        if(data==null)return new JSONArray(); // GraphQL errors come back without "data"
        JSONObject page=data.optJSONObject("Page");
        JSONArray media=page==null?null:page.optJSONArray("media");
        return media==null?new JSONArray():media;
    }

    /** Single media lookup used to enrich recent-catalog cards. */
    private JSONObject anilistMediaById(String id) throws Exception {
        String gql="query($id:Int){Media(id:$id,type:ANIME){"+MEDIA_FIELDS+"}}";
        JSONObject root=postAnilist(gql,new JSONObject().put("id",Integer.parseInt(id)));
        JSONObject data=root.optJSONObject("data");
        return data==null?null:data.opt("Media")==JSONObject.NULL?null:data.optJSONObject("Media");
    }

    private JSONObject postAnilist(String gql,JSONObject variables) throws Exception {
        JSONObject payload=new JSONObject().put("query",gql).put("variables",variables);
        return json(request("POST",ANILIST,payload.toString(),"https://anilist.co/","https://anilist.co"));
    }

    /** The Anikoto catalog's latest entries, filtered client-side by title
     *  containment (the Rust client's approach — the api has no text search).
     *  Items carry {id, ani_id, title, ...} so matches map to anikoto ids. */
    private JSONArray anikotoRecent(String query) throws Exception {
        JSONObject root=json(request("GET",API+"/recent-anime?page=1&per_page=40",null,API,API));
        JSONArray items=findArray(root);
        String needle=normalize(query);
        JSONArray out=new JSONArray();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;
            if(!normalize(titleOf(item)).contains(needle))continue;
            JSONObject c=fromRecent(item);
            if(c!=null)out.put(c);
        }
        return out;
    }

    /** Turn one recent-anime payload item into a UI search result; searchUncached
     *  enriches the first few with full AniList metadata after merging. */
    private JSONObject fromRecent(JSONObject item) throws Exception {
        String idRaw=valueString(item.opt("id"));
        String aniRaw=valueString(item.opt("ani_id"));
        String anilistId=aniRaw!=null?aniRaw:idRaw;
        if(anilistId==null)return null;
        String anikotoId=(aniRaw!=null&&!aniRaw.equals(idRaw))?idRaw:null;
        String title=titleOf(item);
        if(title.isEmpty())title="AniList "+anilistId;
        double episodes=numberValue(item.opt("episodes"));
        String malId=firstOf(item,"idMal","mal_id");
        JSONObject id=newId(anilistId,malId,anikotoId,title,episodes);
        JSONObject out=new JSONObject().put("id",encodeId(id)).put("title",title).put("raw_title",title).put("episodes",episodes);
        if(item.optBoolean("isAdult",item.optBoolean("is_adult",false)))
            out.put("anilist",new JSONObject().put("isAdult",true));
        return out;
    }

    /** Wrap a full AniList Media object as a search result with metadata. */
    private JSONObject fromMedia(JSONObject m) throws Exception {
        JSONObject t=m.optJSONObject("title");
        String title=firstNonEmpty(t==null?"":t.optString("english"),t==null?"":t.optString("romaji"),t==null?"":t.optString("native"));
        String anilistId=valueString(m.opt("id"));
        if(title.isEmpty())title="AniList "+anilistId;
        String malId=firstOf(m,"idMal","mal_id");
        JSONObject id=newId(anilistId,malId,null,title,numberValue(m.opt("episodes")));
        JSONObject cover=m.optJSONObject("coverImage");
        JSONObject out=new JSONObject().put("id",encodeId(id)).put("title",title).put("raw_title",title).put("anilist",m);
        if(cover!=null)out.put("thumbnail",firstNonEmpty(cover.optString("extraLarge"),cover.optString("large")));
        double episodes=numberValue(m.opt("episodes"));
        if(episodes>0)out.put("episodes",episodes);
        return out;
    }

    // -episodes

    public JSONArray episodes(String showId,String mode) throws Exception {
        JSONObject id=decode(showId);
        JSONArray out=new JSONArray();
        String seriesId=id.optString("anikotoId","");
        if(!seriesId.isEmpty()){
            try{
                JSONArray series=loadSeries(seriesId);
                for(int i=0;i<series.length();i++){
                    JSONObject ep=series.optJSONObject(i);if(ep!=null)out.put(ep.optString("number"));
                }
            }catch(Exception e){
                // Catalog unreachable or unparseable but we know the episode
                // count — degrade to 1..n like the Rust client instead of failing.
                if(id.optInt("episodes",0)<=0)throw e;
            }
        }
        if(out.length()==0){
            int count=id.optInt("episodes",0);
            if(count<=0)throw new IOException("No episodes available");
            for(int i=1;i<=count;i++)out.put(String.valueOf(i));
        }
        return out;
    }

    /** Episode metadata for a series (numbers + megaplay embed ids/urls),
     *  cached per anikoto series id. Parsed from /series/{id}'s episode list. */
    private JSONArray loadSeries(String seriesId) throws Exception {
        AniDbScraper.CacheStore c=cache;
        if(c!=null){String hit=c.get("ak-series|"+seriesId);if(hit!=null)try{return new JSONArray(hit);}catch(Exception ignored){}}
        JSONObject root=json(request("GET",API+"/series/"+seriesId,null,API,API));
        JSONArray items=findArray(root);
        List<JSONObject> list=new ArrayList<>();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);if(item==null)continue;
            String number=valueString(item.opt("number"));
            if(number==null)number=valueString(item.opt("episode"));
            if(number==null)number=valueString(item.opt("episode_number"));
            if(number==null)continue;
            JSONObject ep=new JSONObject().put("number",number);
            String embedId=valueString(item.opt("episode_embed_id"));
            if(embedId!=null)ep.put("embedId",embedId);
            JSONObject urls=item.optJSONObject("embed_url");
            if(urls!=null){
                String sub=valueString(urls.opt("sub")),dub=valueString(urls.opt("dub"));
                if(sub!=null)ep.put("subUrl",sub);
                if(dub!=null)ep.put("dubUrl",dub);
            }
            list.add(ep);
        }
        list.sort(Comparator.comparingDouble((JSONObject e)->episodeNumber(e.optString("number"))).thenComparing(e->e.optString("number")));
        JSONArray out=new JSONArray(list);
        if(c!=null&&out.length()>0)c.put("ak-series|"+seriesId,out.toString(),TTL_HOUR);
        return out;
    }

    // -stream

    /** Resolve one episode to a playable stream, mirroring the Rust client's
     *  candidate chain: explicit sub/dub embed url → s-2 embed → ani → mal. */
    public JSONObject stream(String showId,String episode,String mode) throws Exception {
        JSONObject id=decode(showId);
        String lang="dub".equalsIgnoreCase(mode)?"dub":"sub";
        JSONArray series=new JSONArray();
        String seriesId=id.optString("anikotoId","");
        if(!seriesId.isEmpty()){
            try{series=loadSeries(seriesId);}
            catch(Exception e){
                // Without series data the ani/mal routes below may still work.
                if(id.optString("anilistId","").isEmpty()&&id.optString("malId","").isEmpty())throw e;
            }
        }
        JSONObject selected=null;
        for(int i=0;i<series.length();i++){
            JSONObject ep=series.optJSONObject(i);
            if(ep!=null&&episode!=null&&episode.equals(ep.optString("number"))){selected=ep;break;}
        }
        List<String> candidates=embedCandidates(selected,id,episode,lang);
        if(candidates.isEmpty())throw new IOException("No episodes available");
        List<String> failures=new ArrayList<>();
        for(String candidate:candidates){
            String label=candidateLabel(candidate);
            try{
                JSONObject r=resolveEmbed(candidate);
                if(!r.optString("url").isEmpty())return r;
                failures.add(label+" returned no playable URL");
            }catch(Exception e){failures.add(label+": "+(e.getMessage()==null?candidate:e.getMessage()));}
        }
        String detail=failures.isEmpty()?"No stream sources found":"No playable source: "+String.join("; ",failures);
        throw new IOException(detail);
    }

    /** Short diagnostic tag for a MegaPlay embed candidate so stream()
     *  failures name the route (s-2/ani/mal/explicit) instead of echoing the
     *  full embed URL into the user-facing error. */
    private static String candidateLabel(String url){
        if(url==null)return "embed";
        if(url.contains("/stream/s-2/"))return "s-2";
        if(url.contains("/stream/ani/"))return "ani";
        if(url.contains("/stream/mal/"))return "mal";
        return "embed";
    }

    private List<String> embedCandidates(JSONObject selected,JSONObject id,String episode,String lang){
        List<String> out=new ArrayList<>();
        if(selected!=null){
            String explicit=selected.optString(lang.equals("dub")?"dubUrl":"subUrl","");
            if(!explicit.isEmpty())out.add(explicit);
            String embedId=selected.optString("embedId","");
            if(!embedId.isEmpty())out.add(MEGAPLAY+"/stream/s-2/"+embedId+"/"+lang);
        }
        String anilistId=id.optString("anilistId","");
        if(!anilistId.isEmpty())out.add(MEGAPLAY+"/stream/ani/"+anilistId+"/"+episode+"/"+lang);
        String malId=id.optString("malId","");
        if(!malId.isEmpty())out.add(MEGAPLAY+"/stream/mal/"+malId+"/"+episode+"/"+lang);
        Set<String> seen=new LinkedHashSet<>();
        List<String> unique=new ArrayList<>();
        for(String u:out)if(seen.add(u))unique.add(u);
        return unique;
    }

    /** MegaPlay embed → getSources → the stream the WebView/mpv/downloader
     *  can load. Returns {url, raw, master, type, referer} like
     *  AniDbScraper.stream(): "url" is the best-rendition media playlist (or
     *  direct file), "master" the full multi-quality playlist hls.js loads so
     *  the quality menu lists every rendition, "referer" the page the CDN
     *  expects (player.js stamps it on every hls.js request). */
    private JSONObject resolveEmbed(String embedUrl) throws Exception {
        String html=request("GET",embedUrl,null,MEGAPLAY+"/",null);
        Matcher m=DATA_ID.matcher(html);
        if(!m.find()){
            // Newer embed shells drop data-id and only carry the real file id.
            m=DATA_REAL_ID.matcher(html);
            if(!m.find())throw new IOException("embed did not expose a playable source id");
        }
        // getSources is an AJAX-only endpoint: the site's player always calls it
        // with X-Requested-With: XMLHttpRequest and 403s plain requests. The
        // player has since moved to /stream/getSourcesNew (the embed's JS
        // rewrites every getSources request to it), and the legacy endpoint now
        // answers with only an encrypted payload. Try the new endpoint first,
        // falling back to the legacy one in case either serves the sources.
        List<String> sources=null;String lastFail=null;boolean anyPayload=false;
        for(String endpoint:new String[]{"/stream/getSourcesNew","/stream/getSources"}){
            try{
                JSONObject payload=json(request("GET",MEGAPLAY+endpoint+"?id="+m.group(1),null,embedUrl,MEGAPLAY,Collections.singletonMap("X-Requested-With","XMLHttpRequest")));
                anyPayload=true;
                List<String> found=collectSources(payload);
                if(!found.isEmpty()){sources=found;break;}
            }catch(Exception e){lastFail=e.getMessage()==null?endpoint:e.getMessage();}
        }
        if(sources==null)throw new IOException("embed returned no sources: "+(anyPayload?"payload contained no playable URLs":"request failed ("+lastFail+")"));
        // Prefer an HLS source (quality switching) over a direct file.
        String chosen=sources.get(0);
        for(String s:sources)if(isHlsUrl(s)){chosen=s;break;}
        String master=chosen,url=chosen;
        boolean hls=isHlsUrl(chosen);
        if(hls){
            String manifest=request("GET",chosen,null,MEGAPLAY+"/",MEGAPLAY);
            if(manifest.trim().startsWith("#EXTM3U")&&manifest.contains("#EXT-X-STREAM-INF")){
                String v=AniDbScraper.pickVariant(chosen,manifest,0);
                if(!v.isEmpty())url=v;
            }
        }
        JSONObject out=new JSONObject().put("url",url).put("raw",url).put("master",master).put("type",hls?"hls":"video/mp4").put("referer",MEGAPLAY+"/");
        return out;
    }

    // -ids / helpers

    private static JSONObject newId(String anilistId,String malId,String anikotoId,String title,double episodes) throws Exception {
        JSONObject id=new JSONObject();
        if(anilistId!=null&&!anilistId.isEmpty())id.put("anilistId",anilistId);
        if(malId!=null&&!malId.isEmpty())id.put("malId",malId);
        if(anikotoId!=null&&!anikotoId.isEmpty())id.put("anikotoId",anikotoId);
        if(title!=null&&!title.isEmpty())id.put("title",title);
        if(episodes>0)id.put("episodes",(int)episodes);
        return id;
    }

    private static String encodeId(JSONObject id) throws Exception {
        return PREFIX+base64UrlEncode(id.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject decode(String showId) throws Exception {
        if(ownsNumeric(showId))return new JSONObject().put("anikotoId",showId);
        if(showId==null||!showId.startsWith(PREFIX))throw new IOException("invalid Anikoto show ID");
        try{return new JSONObject(new String(base64UrlDecode(showId.substring(PREFIX.length())),StandardCharsets.UTF_8));}
        catch(Exception e){throw new IOException("invalid Anikoto show ID encoding");}
    }

    /** Locate the item array in a JSON payload: /data/Page/media (AniList),
     *  /data (Anikoto catalog), bare arrays, or /data/episodes (series). */
    private static JSONArray findArray(Object value){
        if(value instanceof JSONArray)return (JSONArray)value;
        if(!(value instanceof JSONObject))return new JSONArray();
        JSONObject o=(JSONObject)value;
        JSONArray a=o.optJSONArray("media");if(a!=null)return a;
        a=o.optJSONArray("episodes");if(a!=null)return a;
        Object data=o.opt("data");
        if(data instanceof JSONArray)return (JSONArray)data;
        if(data instanceof JSONObject){
            JSONObject d=(JSONObject)data;
            JSONArray da=d.optJSONArray("media");if(da!=null)return da;
            da=d.optJSONArray("episodes");if(da!=null)return da;
            JSONObject page=d.optJSONObject("Page");
            if(page!=null){da=page.optJSONArray("media");if(da!=null)return da;}
            if(d.opt("data") instanceof JSONArray)return (JSONArray)d.opt("data");
        }
        return new JSONArray();
    }

    private static String titleOf(JSONObject item){
        Object raw=item.opt("title");
        if(raw instanceof String)return ((String)raw).trim();
        if(raw instanceof JSONObject){
            JSONObject t=(JSONObject)raw;
            String title=firstNonEmpty(t.optString("english"),t.optString("romaji"),t.optString("native"));
            if(!title.isEmpty())return title;
        }
        String title=valueString(item.opt("name"));
        if(title!=null)return title;
        return "";
    }

    /** Collect every playable URL from a getSources payload, recursing through
     *  source-bearing keys (sources/source/links) and common response wrappers
     *  (data/result/files), plus objects holding file|url|src. Only HTTP(S)
     *  URLs are accepted — error messages, ids, and image URLs must never be
     *  mistaken for playable media. */
    private static List<String> collectSources(Object value){
        List<String> out=new ArrayList<>();
        collectSource(value,out,new HashSet<>());
        return out;
    }
    private static void collectSource(Object value,List<String> out,Set<String> seen){
        if(value instanceof String){String u=((String)value).trim();if(isMediaUrl(u)&&seen.add(u))out.add(u);}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)collectSource(a.opt(i),out,seen);}
        else if(value instanceof JSONObject){
            JSONObject o=(JSONObject)value;
            String u=firstOf(o,"file","url","src");
            if(u!=null&&isMediaUrl(u)&&seen.add(u))out.add(u);
            for(String key:new String[]{"sources","source","links","data","result","files"}){
                Object child=o.opt(key);
                if(child!=null)collectSource(child,out,seen);
            }
        }
    }

    private static boolean isMediaUrl(String url){
        if(url==null)return false;
        String lower=url.toLowerCase(Locale.US);
        return (lower.startsWith("https://")||lower.startsWith("http://"))&&!lower.contains(" ");
    }

    private static boolean isHlsUrl(String url){
        String lower=url==null?"":url.toLowerCase(Locale.US);
        int q=lower.indexOf('?');
        String path=q<0?lower:lower.substring(0,q);
        return path.contains(".m3u8")||lower.contains(".m3u8");
    }

    // -http

    private String request(String method,String url,String body,String referer,String origin) throws Exception {
        return request(method,url,body,referer,origin,null);
    }
    private String request(String method,String url,String body,String referer,String origin,Map<String,String> extra) throws Exception {
        try{return transport.request(method,url,body,referer,origin,extra);}
        catch(IOException e){throw new IOException("Anikoto request failed: "+(e.getMessage()==null?url:e.getMessage()),e);}
    }
    private JSONObject json(String text)throws Exception{return new JSONObject(text);}

    private JSONArray cachedArray(String key,long ttl,Callable<JSONArray> load)throws Exception{
        AniDbScraper.CacheStore c=cache;
        if(c!=null){
            String hit=c.get(key);
            // Empty results are a miss: a transient failure must not lock a
            // query into emptiness for its TTL (same rule as AniDbScraper).
            if(hit!=null)try{JSONArray cached=new JSONArray(hit);if(cached.length()>0)return cached;}catch(Exception ignored){}
        }
        JSONArray value=load.call();
        if(c!=null&&value.length()>0)c.put(key,value.toString(),ttl);
        return value;
    }

    // -string utils

    private static String firstNonEmpty(String... values){for(String v:values)if(v!=null&&!v.isEmpty())return v;return "";}
    private static String firstOf(JSONObject o,String... keys){for(String k:keys){String v=valueString(o.opt(k));if(v!=null)return v;}return null;}
    private static String valueString(Object value){
        if(value==null||JSONObject.NULL.equals(value))return null;
        if(value instanceof String){String s=((String)value).trim();return s.isEmpty()?null:s;}
        if(value instanceof Number)return String.valueOf(value);
        return null;
    }
    private static double numberValue(Object value){
        if(value instanceof Number)return ((Number)value).doubleValue();
        if(value instanceof String)try{return Double.parseDouble((String)value);}catch(Exception ignored){}
        return 0;
    }
    private static double episodeNumber(String s){try{return Double.parseDouble(s);}catch(Exception e){return Double.MAX_VALUE;}}
    private static String normalize(String s){return java.text.Normalizer.normalize(s==null?"":s.toLowerCase(Locale.US),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").replaceAll("[^\\p{L}\\p{N}]+","").trim();}

    // -base64url (no-pad, no-wrap) without android.util, keeping this scraper
    // plain-Java like AniDbScraper (the URL_SAFE_NO_PAD encoding ani-cli uses).

    private static final char[] B64="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static String base64UrlEncode(byte[] data){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<data.length;i+=3){
            int b0=data[i]&0xFF,b1=i+1<data.length?data[i+1]&0xFF:0,b2=i+2<data.length?data[i+2]&0xFF:0;
            out.append(B64[b0>>2]);
            out.append(B64[((b0&0x3)<<4)|(b1>>4)]);
            if(i+1<data.length)out.append(B64[((b1&0xF)<<2)|(b2>>6)]);
            if(i+2<data.length)out.append(B64[b2&0x3F]);
        }
        return out.toString();
    }
    private static byte[] base64UrlDecode(String s){
        int len=s.length();
        if((len&3)==1)throw new IllegalArgumentException("invalid base64url length");
        ByteArrayOutputStream out=new ByteArrayOutputStream((len*3)/4+1);
        int buffer=0,bits=0;
        for(int i=0;i<len;i++){
            char c=s.charAt(i);
            int v=valueOfB64(c);
            if(v<0)throw new IllegalArgumentException("invalid base64url character");
            buffer=(buffer<<6)|v;bits+=6;
            if(bits>=8){bits-=8;out.write((buffer>>bits)&0xFF);}
        }
        return out.toByteArray();
    }
    private static int valueOfB64(char c){
        if(c>='A'&&c<='Z')return c-'A';
        if(c>='a'&&c<='z')return c-'a'+26;
        if(c>='0'&&c<='9')return c-'0'+52;
        if(c=='-')return 62;
        if(c=='_')return 63;
        return -1;
    }
}
