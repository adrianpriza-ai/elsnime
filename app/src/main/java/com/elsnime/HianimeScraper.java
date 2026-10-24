package com.elsnime;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java port of the hianime provider used by ani-cli.6. */
public final class HianimeScraper {
    private static final String BASE = "https://hianime.at";
    private static final String EMBED_KEY = "otaku-embed-v1";
    private static final long TTL_DAY = 86400L, TTL_HOUR = 3600L;
    private static final Pattern SEARCH_ITEM = Pattern.compile("<div class=\\\"film-detail\\\">.*?<a href=\\\"[^\\\"]*/([^/\\\"]+)\\\"[^>]*title=\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_ITEM = Pattern.compile("data-number=\\\"([^\\\"]+)\\\".*?data-id=\\\"(\\d+)\\\".*?/watch/([^?\\\"]+)[?]ep=", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_ITEM = Pattern.compile("data-type=\\\"([^\\\"]+)\\\".*?data-server-name=\\\"ZokoAnime\\\".*?data-hash=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYLOAD = Pattern.compile("window\\.__P=\\\"([^\\\"]+)\\\"");
    private static final Pattern SOURCE = Pattern.compile("\\\"src\\\":\\\"([^\\\"]+\\.m3u8[^\\\"]*)\\\"");
    private static final Pattern SUBTITLES = Pattern.compile("\\\"subtitles\\\":\\[([^\\]]*)\\]");
    private static final Pattern SUBTITLE_OBJECT = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern SUBTITLE_SOURCE = Pattern.compile("\\\"src\\\":\\\"([^\\\"]+)\\\"");

    private volatile AniDbScraper.CacheStore cache;
    private volatile AniDbScraper.HttpTransport transport = AniDbScraper.HttpUrlConnectionTransport.INSTANCE;

    public void setCache(AniDbScraper.CacheStore value) { cache = value; }
    public void setTransport(AniDbScraper.HttpTransport value) { if (value != null) transport = value; }
    public void clearCache() { AniDbScraper.CacheStore c = cache; if (c != null) c.clear(); }
    public void clearCachePrefix(String prefix) { AniDbScraper.CacheStore c = cache; if (c != null) c.clearPrefix(prefix); }

    public JSONArray search(String query, String ignoredMode) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) throw new IOException("Empty search query");
        return cachedArray("hianime-search|" + q.toLowerCase(Locale.US), TTL_DAY, () -> {
            String page = request("GET", BASE + "/search?keyword=" + URLEncoder.encode(q, "UTF-8"), BASE + "/");
            page = page.replace("\\\\\"", "\"");
            JSONArray out = new JSONArray();
            Set<String> seen = new HashSet<>();
            int sidebar = page.indexOf("id=\"main-sidebar\"");
            Matcher m = SEARCH_ITEM.matcher(sidebar < 0 ? page : page.substring(0, sidebar));
            while (m.find() && out.length() < 40) {
                String id = html(m.group(1));
                if (!seen.add(id)) continue;
                String title = html(m.group(2));
                out.put(new JSONObject().put("id", id).put("title", title).put("raw_title", title));
            }
            return out;
        });
    }

    public JSONArray episodes(String animeId, String ignoredMode) throws Exception {
        return cachedArray("hianime-episodes|" + animeId, TTL_HOUR, () -> {
            String slug = animeId == null ? "" : animeId;
            String page = request("GET", BASE + "/api/theme/episode/list/" + slug.replaceFirst("^.*-", ""), BASE + "/");
            page = page.replace("\\\\", "").replace("ep-item", "\nep-item");
            Matcher m = EPISODE_ITEM.matcher(page);
            List<String> values = new ArrayList<>();
            while (m.find()) {
                if (!slug.equals(m.group(3)) && !slug.endsWith(m.group(3))) continue;
                values.add(m.group(1));
            }
            values.sort(Comparator.comparingDouble(HianimeScraper::episodeNumber));
            JSONArray out = new JSONArray();
            for (String value : new LinkedHashSet<>(values)) out.put(value);
            return out;
        });
    }

    public JSONObject stream(String animeId, String episode, String mode) throws Exception {
        String slug = animeId == null ? "" : animeId;
        String page = request("GET", BASE + "/api/theme/episode/list/" + slug.replaceFirst("^.*-", ""), BASE + "/");
        page = page.replace("\\\\", "").replace("ep-item", "\nep-item");
        Matcher ep = EPISODE_ITEM.matcher(page);
        String episodeId = "";
        while (ep.find()) {
            if (slug.equals(ep.group(3)) && episode.equals(ep.group(1))) { episodeId = ep.group(2); break; }
        }
        if (episodeId.isEmpty()) return error("Episode not found");

        String servers = request("GET", BASE + "/api/theme/episode/servers?episodeId=" + episodeId, BASE + "/");
        Matcher server = SERVER_ITEM.matcher(servers.replace("\\\\\"", "\""));
        String embed = "";
        while (server.find()) {
            if (mode == null || mode.equalsIgnoreCase(server.group(1))) {
                embed = decodeBase64(server.group(2));
                break;
            }
        }
        if (embed.isEmpty()) return error("No " + mode + " source found");
        String referer = origin(embed);
        String embedPage = request("GET", embed, referer);
        Matcher payload = PAYLOAD.matcher(embedPage);
        if (!payload.find()) return error("Embed payload not found");
        String config = xor(Base64.decode(payload.group(1), Base64.DEFAULT));
        Matcher source = SOURCE.matcher(config);
        if (!source.find()) return error("HLS playlist not found");
        String master = unescape(source.group(1));
        String manifest = request("GET", master, referer);
        String url = bestVariant(master, manifest);
        if (url.isEmpty()) return error("No video quality found");
        JSONObject out = new JSONObject().put("url", url).put("raw", url).put("master", master)
            .put("type", "hls").put("referer", referer);
        Matcher subtitles = SUBTITLES.matcher(config);
        if (subtitles.find()) {
            Matcher subtitleObject = SUBTITLE_OBJECT.matcher(subtitles.group(1));
            while (subtitleObject.find()) {
                String item = subtitleObject.group(1);
                if (!item.contains("\"default\":true")) continue;
                Matcher subtitleSource = SUBTITLE_SOURCE.matcher(item);
                if (subtitleSource.find()) out.put("subtitle", unescape(subtitleSource.group(1)));
                break;
            }
        }
        return out;
    }

    private String bestVariant(String master, String manifest) {
        String[] lines = manifest.split("\\r?\\n");
        String best = ""; int bestHeight = -1;
        for (int i = 0; i + 1 < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            Matcher r = Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(lines[i]);
            int height = r.find() ? Integer.parseInt(r.group(1)) : 0;
            String candidate = lines[i + 1].trim();
            if (candidate.isEmpty() || candidate.startsWith("#")) continue;
            if (height >= bestHeight) { bestHeight = height; best = absolute(master, candidate); }
        }
        return best.isEmpty() && !manifest.contains("#EXT-X-STREAM-INF") ? master : best;
    }

    private String request(String method, String url, String referer) throws Exception {
        String body;
        try {
            body = transport.request(method, url, null, referer, BASE);
        } catch (IOException e) {
            String message = e.getMessage();
            String detail = message != null && message.matches(".*HTTP [1-5][0-9][0-9].*")
                ? message.replaceFirst(".*(HTTP [1-5][0-9][0-9]).*", "$1")
                : "no HTTP response";
            throw new IOException("Connection error: could not fetch " + url + " (" + detail + ")", e);
        }
        if (body == null || body.isEmpty()) throw new IOException("Connection error: no response from " + BASE);
        if (body.contains("Just a moment")) throw new IOException("Cloudflare challenge from " + BASE);
        return body;
    }

    private JSONArray cachedArray(String key, long ttl, java.util.concurrent.Callable<JSONArray> load) throws Exception {
        AniDbScraper.CacheStore c = cache;
        if (c != null) { String hit = c.get(key); if (hit != null) try { return new JSONArray(hit); } catch (Exception ignored) {} }
        JSONArray value = load.call();
        if (c != null && value.length() > 0) c.put(key, value.toString(), ttl);
        return value;
    }

    private static String decodeBase64(String value) {
        try { return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
    private static String xor(byte[] bytes) {
        byte[] key = EMBED_KEY.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (bytes[i] ^ key[i % key.length]);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static String absolute(String base, String value) {
        try { return new java.net.URL(new java.net.URL(base), value).toString(); } catch (Exception e) { return value; }
    }
    private static String origin(String url) { try { java.net.URL u = new java.net.URL(url); return u.getProtocol() + "://" + u.getHost() + "/"; } catch (Exception e) { return BASE + "/"; } }
    private static String html(String value) { return value.replace("&#039;", "'").replace("&quot;", "\"").replace("&amp;", "&"); }
    private static String unescape(String value) { return value.replace("\\/", "/").replace("\\u0026", "&"); }
    private static double episodeNumber(String value) { try { return Double.parseDouble(value); } catch (Exception e) { return Double.MAX_VALUE; } }
    private static JSONObject error(String message) throws Exception { return new JSONObject().put("error", message); }
}
