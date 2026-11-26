package app.delrazor.extension.gasbuddy.androidauto;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remembers how GasBuddy's own code asks its server for nearby stations, so the car screen can
 * ask the same way.
 *
 * GasBuddy is a React Native app: its screens and server calls are JavaScript, and every request
 * goes through React Native's NetworkingModule. The patch calls {@link #onRequest} there before
 * each request is sent. The last station search (its address and headers, which hold GasBuddy's
 * sign-in) is kept in a file in GasBuddy's private storage, so it survives the app being closed
 * before the car connects. The sign-in is refreshed from every later GasBuddy request.
 *
 * The car screen runs in its own process ({@link CarProcess}), so it reads the file again
 * whenever GasBuddy's own process has written a newer one.
 */
public final class GasBuddyRequestTap {
    private static final String TAG = "DelRazorGasBuddyCar";
    /** Where dev.14 and dev.15 kept it. Read once if the file isn't there yet. */
    private static final String OLD_PREFS = "delrazor_android_auto_search";
    private static final String OLD_KEY_SEARCH = "station_search";
    private static final String FILE_NAME = "delrazor_android_auto_station_search.json";

    /** A station search the app made: its address and headers. */
    static final class CapturedSearch {
        final String url;
        final Map<String, String> headers;

        CapturedSearch(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private static final Object lock = new Object();
    private static CapturedSearch latest;
    /** When the file behind {@link #latest} was written, to notice newer ones. */
    private static long latestModified = -1;

    private GasBuddyRequestTap() {
    }

    /**
     * Called by patched React Native code before every request.
     *
     * @param headers a com.facebook.react.bridge.ReadableArray of [name, value] pairs. It is
     *                read with reflection, so the extension doesn't depend on React Native.
     */
    public static void onRequest(String method, String url, int requestId, Object headers) {
        try {
            if (url == null || !"GET".equalsIgnoreCase(method)) return;
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !host.toLowerCase(Locale.US).contains("gasbuddy")) return;

            Map<String, String> headerMap = readHeaders(headers);
            if (isStationSearch(uri)) {
                save(new CapturedSearch(url, headerMap));
                Log.i(TAG, "Station search seen: " + uri.getPath());
                return;
            }

            // Any later request to the same server carries a fresher sign-in.
            CapturedSearch current = get();
            String auth = header(headerMap, "authorization");
            if (current != null && auth != null && host.equalsIgnoreCase(Uri.parse(current.url).getHost())
                    && !auth.equals(header(current.headers, "authorization"))) {
                Map<String, String> updated = new LinkedHashMap<>(current.headers);
                putHeader(updated, "authorization", auth);
                save(new CapturedSearch(current.url, updated));
            }
        } catch (Throwable t) {
            // Never break GasBuddy's own requests.
            Log.w(TAG, "Request tap failed", t);
        }
    }

    /**
     * GasBuddy 702.x searches with GET .../stations?location_specification=lat,lng&... (its
     * "mobile orchestration" service) and has GET /v1/station/search/{location} as well.
     */
    static boolean isStationSearch(Uri uri) {
        String path = uri.getPath();
        if (path == null) return false;
        List<String> segments = uri.getPathSegments();
        if (path.endsWith("/stations")) {
            String location = uri.getQueryParameter("location_specification");
            return (location != null && location.contains(","))
                    || (uri.getQueryParameter("lat") != null && uri.getQueryParameter("lng") != null);
        }
        int n = segments.size();
        return n >= 4 && "station".equals(segments.get(n - 3)) && "search".equals(segments.get(n - 2))
                && segments.get(n - 1).contains(",");
    }

    static CapturedSearch get() {
        Context context = application();
        synchronized (lock) {
            if (context == null) return latest;
            File file = file(context);
            long modified = file.exists() ? file.lastModified() : 0;
            if (modified != latestModified) {
                CapturedSearch read = modified != 0 ? read(file) : readOldPrefs(context);
                if (read != null || modified != 0) latest = read;
                latestModified = modified;
            }
            return latest;
        }
    }

    private static Map<String, String> readHeaders(Object array) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (array == null) return result;
        Method size = array.getClass().getMethod("size");
        Method getArray = array.getClass().getMethod("getArray", int.class);
        int count = (Integer) size.invoke(array);
        for (int i = 0; i < count; i++) {
            Object pair = getArray.invoke(array, i);
            if (pair == null) continue;
            Method getString = pair.getClass().getMethod("getString", int.class);
            String name = (String) getString.invoke(pair, 0);
            String value = (String) getString.invoke(pair, 1);
            if (name != null && value != null) result.put(name, value);
        }
        return result;
    }

    static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        Iterator<String> keys = headers.keySet().iterator();
        while (keys.hasNext()) {
            if (keys.next().equalsIgnoreCase(name)) keys.remove();
        }
        headers.put(name, value);
    }

    private static void save(CapturedSearch search) {
        Context context = application();
        synchronized (lock) {
            latest = search;
            if (context == null) return;
            try {
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, String> entry : search.headers.entrySet()) {
                    headers.put(entry.getKey(), entry.getValue());
                }
                JSONObject json = new JSONObject().put("url", search.url).put("headers", headers);
                File file = file(context);
                AtomicFile atomic = new AtomicFile(file);
                FileOutputStream out = atomic.startWrite();
                try {
                    out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                    atomic.finishWrite(out);
                } catch (Exception e) {
                    atomic.failWrite(out);
                    throw e;
                }
                latestModified = file.lastModified();
            } catch (Exception e) {
                Log.w(TAG, "Saving the station search failed", e);
            }
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static CapturedSearch read(File file) {
        try {
            byte[] data = new AtomicFile(file).readFully();
            return parse(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Reading the saved station search failed", e);
            return null;
        }
    }

    private static CapturedSearch readOldPrefs(Context context) {
        try {
            String saved = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
                    .getString(OLD_KEY_SEARCH, null);
            return saved != null ? parse(saved) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static CapturedSearch parse(String saved) throws Exception {
        JSONObject json = new JSONObject(saved);
        JSONObject headersJson = json.getJSONObject("headers");
        Map<String, String> headers = new LinkedHashMap<>();
        Iterator<String> names = headersJson.keys();
        while (names.hasNext()) {
            String name = names.next();
            headers.put(name, headersJson.getString(name));
        }
        return new CapturedSearch(json.getString("url"), headers);
    }

    private static Context application() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return (Application) activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
