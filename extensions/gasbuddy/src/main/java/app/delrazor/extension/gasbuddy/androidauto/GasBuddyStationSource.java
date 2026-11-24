package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Asks GasBuddy's server for nearby stations the same way the app does: it repeats the last
 * station search the app made (see {@link GasBuddyRequestTap}), with the car's position and fuel
 * grade, and reads the stations and prices from the reply.
 *
 * Field names come from GasBuddy 702.10's own station model: each station has "id", "info"
 * (name, address) and "fuel_products", a list of {fuel_product, credit, cash}, where credit and
 * cash are {price, posted_time, nickname}. Coordinates are looked for under the usual names.
 */
final class GasBuddyStationSource implements StationSource {
    private static final String TAG = "DelRazorGasBuddyCar";
    private static final int TIMEOUT_MS = 15_000;

    /** Search settings that would narrow the repeated search to the app's last one. */
    private static final Set<String> DROPPED_PARAMETERS = new HashSet<>(Arrays.asList(
            "clip_box", "cursor", "text", "favorites_list_id", "default_favorites",
            "end_location_specification", "include_routes", "locality", "county",
            "postal_code", "metro", "region", "brand_id"));

    /** Headers HttpURLConnection sets itself. */
    private static final Set<String> SKIPPED_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "accept-encoding", "connection"));

    /** GasBuddy's fuel names (its FuelType values) and older numeric ids. */
    private static String fuelName(FuelGrade grade) {
        switch (grade) {
            case MIDGRADE: return "midgrade_gas";
            case PREMIUM: return "premium_gas";
            case DIESEL: return "diesel";
            default: return "regular_gas";
        }
    }

    private static FuelGrade gradeOf(String fuelProduct) {
        if (fuelProduct == null) return null;
        switch (fuelProduct.toLowerCase(Locale.US)) {
            case "regular_gas": case "regular": case "1": return FuelGrade.REGULAR;
            case "midgrade_gas": case "midgrade": case "2": return FuelGrade.MIDGRADE;
            case "premium_gas": case "premium": case "3": return FuelGrade.PREMIUM;
            case "diesel": case "4": return FuelGrade.DIESEL;
            default: return null;
        }
    }

    @Override
    public List<Station> findNearby(Context context, double latitude, double longitude,
                                    FuelGrade grade) throws StationSourceException {
        GasBuddyRequestTap.CapturedSearch search = GasBuddyRequestTap.get();
        if (search == null) {
            throw new StationSourceException("Open GasBuddy on your phone once and let it show "
                    + "nearby stations. The car screen then uses the same sign-in.");
        }

        String url = searchUrl(search.url, latitude, longitude, grade);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            for (Map.Entry<String, String> header : search.headers.entrySet()) {
                if (!SKIPPED_HEADERS.contains(header.getKey().toLowerCase(Locale.US))) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) {
                throw new StationSourceException("Your GasBuddy sign-in needs a refresh. "
                        + "Open GasBuddy on your phone, then try again.");
            }
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Station search answered " + code + ": " + readAll(connection.getErrorStream(), 2000));
                throw new StationSourceException("GasBuddy didn't answer (error " + code
                        + "). Try again in a moment.");
            }
            String body = readAll(connection.getInputStream(), Integer.MAX_VALUE);
            List<Station> stations = parse(body);
            if (stations.isEmpty()) {
                Log.w(TAG, "No stations read from: " + body.substring(0, Math.min(2000, body.length())));
            }
            return stations;
        } catch (IOException e) {
            Log.w(TAG, "Station search failed", e);
            throw new StationSourceException("Couldn't reach GasBuddy. Check the phone's connection.", e);
        } catch (JSONException e) {
            Log.w(TAG, "Station search reply unreadable", e);
            throw new StationSourceException("GasBuddy's reply couldn't be read. The patch may "
                    + "need an update for this GasBuddy version.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The app's last search, moved to this position and grade. */
    static String searchUrl(String captured, double latitude, double longitude, FuelGrade grade) {
        Uri uri = Uri.parse(captured);
        String location = String.format(Locale.US, "%.6f,%.6f", latitude, longitude);
        Uri.Builder builder = uri.buildUpon().clearQuery();

        List<String> segments = uri.getPathSegments();
        int n = segments.size();
        if (n >= 4 && "search".equals(segments.get(n - 2)) && segments.get(n - 1).contains(",")) {
            String path = uri.getEncodedPath();
            builder.encodedPath(path.substring(0, path.lastIndexOf('/') + 1) + location);
        }

        for (String name : uri.getQueryParameterNames()) {
            if (DROPPED_PARAMETERS.contains(name)) continue;
            for (String value : uri.getQueryParameters(name)) {
                switch (name) {
                    case "location_specification": value = location; break;
                    case "lat": value = String.format(Locale.US, "%.6f", latitude); break;
                    case "lng": value = String.format(Locale.US, "%.6f", longitude); break;
                    case "fuels": value = swapFuel(value, grade); break;
                    default: break;
                }
                builder.appendQueryParameter(name, value);
            }
        }
        return builder.build().toString();
    }

    /** Replaces a single fuel the app asked for with the car's grade, in the same format. */
    private static String swapFuel(String value, FuelGrade grade) {
        if (value == null || value.contains(",")) return value;
        if (value.matches("\\d+")) {
            return value.equals("1") || value.equals("2") || value.equals("3") || value.equals("4")
                    ? String.valueOf(grade.ordinal() + 1) : value;
        }
        return gradeOf(value) != null ? fuelName(grade) : value;
    }

    static List<Station> parse(String body) throws JSONException {
        Object root = new JSONObject(body);
        JSONArray array = findStationArray(root, 0);
        List<Station> stations = new ArrayList<>();
        if (array == null) return stations;
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) continue;
            Station station = parseStation(json);
            if (station != null) stations.add(station);
        }
        return stations;
    }

    /** The "stations" list, at the top or under "data" or "results". */
    private static JSONArray findStationArray(Object node, int depth) {
        if (!(node instanceof JSONObject) || depth > 3) return null;
        JSONObject object = (JSONObject) node;
        for (String key : new String[]{"stations", "results"}) {
            JSONArray array = object.optJSONArray(key);
            if (array != null) return array;
        }
        for (String key : new String[]{"data", "results", "response"}) {
            JSONArray found = findStationArray(object.opt(key), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static Station parseStation(JSONObject json) {
        // Some lists wrap each entry as {station: {...}}.
        if (!json.has("fuel_products") && json.optJSONObject("station") != null) {
            json = json.optJSONObject("station");
        }
        JSONObject info = json.optJSONObject("info");
        if (info == null) info = json;

        double[] point = findLatLng(json, 0);
        if (point == null) return null;

        String name = string(info, "name");
        if (name == null) name = string(json, "name");
        if (name == null) name = "Gas station";

        String address = null;
        String locality = null;
        JSONObject addressJson = info.optJSONObject("address");
        if (addressJson == null) addressJson = json.optJSONObject("address");
        if (addressJson != null) {
            address = string(addressJson, "line_1");
            if (address == null) address = string(addressJson, "line1");
            String cross = string(addressJson, "cross_street");
            if (address != null && cross != null) address = address + " & " + cross;
            String city = string(addressJson, "locality");
            String region = string(addressJson, "region");
            locality = city != null && region != null ? city + ", " + region : city != null ? city : region;
        } else {
            address = string(info, "address");
        }

        Map<FuelGrade, Station.Price> prices = new EnumMap<>(FuelGrade.class);
        JSONArray products = json.optJSONArray("fuel_products");
        if (products == null) products = json.optJSONArray("prices");
        if (products != null) {
            for (int i = 0; i < products.length(); i++) {
                JSONObject product = products.optJSONObject(i);
                if (product == null) continue;
                FuelGrade grade = gradeOf(string(product, "fuel_product"));
                if (grade == null) continue;
                Station.Price price = parsePrice(product);
                if (price != null) prices.put(grade, price);
            }
        }

        String id = json.has("id") ? String.valueOf(json.opt("id")) : name + point[0] + point[1];
        return new Station(id, name, address, locality, point[0], point[1], prices);
    }

    private static Station.Price parsePrice(JSONObject product) {
        JSONObject credit = product.optJSONObject("credit");
        JSONObject cash = product.optJSONObject("cash");
        double creditValue = credit != null ? number(credit, "price") : 0;
        double cashValue = cash != null ? number(cash, "price") : 0;
        JSONObject posted = creditValue > 0 ? credit : cash;
        double value = creditValue > 0 ? creditValue : cashValue;
        if (value <= 0 || posted == null) return null;
        return new Station.Price(value, cashValue, time(string(posted, "posted_time")),
                string(posted, "nickname"));
    }

    /** Looks for latitude and longitude in the station and the objects under it. */
    private static double[] findLatLng(JSONObject json, int depth) {
        String[][] names = {{"latitude", "longitude"}, {"lat", "lng"}, {"lat", "lon"}};
        for (String[] pair : names) {
            double lat = number(json, pair[0]);
            double lng = number(json, pair[1]);
            if (lat != 0 && lng != 0 && Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
                return new double[]{lat, lng};
            }
        }
        if (depth >= 3) return null;
        for (String key : new String[]{"info", "location", "coordinates", "coordinate", "geo", "address"}) {
            JSONObject child = json.optJSONObject(key);
            if (child != null) {
                double[] found = findLatLng(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String string(JSONObject json, String key) {
        if (json == null || json.isNull(key)) return null;
        String value = json.optString(key, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static double number(JSONObject json, String key) {
        if (json == null || json.isNull(key)) return 0;
        Object value = json.opt(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).replace("$", "").trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static long time(String iso) {
        if (iso == null) return 0;
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(iso + "Z").toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < limit) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
