package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarLocation;
import androidx.car.app.model.Distance;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Metadata;
import androidx.car.app.model.Place;
import androidx.car.app.model.PlaceListMapTemplate;
import androidx.car.app.model.PlaceMarker;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.versioning.CarAppApiLevels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first car screen: a map with nearby stations and a list sorted by price or distance, for
 * the chosen fuel grade. Tapping a station opens {@link StationDetailScreen}.
 */
final class StationListScreen extends Screen {
    private static final String TAG = "DelRazorGasBuddyCar";

    /** Rows shown when the car does not say how many it allows. */
    private static final int DEFAULT_ROW_LIMIT = 6;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Null while the first load runs. */
    private List<Station> stations;
    private String message;
    private boolean loading;
    /** Ignores results of a load that a newer one replaced. */
    private int loadGeneration;

    StationListScreen(@NonNull CarContext carContext) {
        super(carContext);
        // The screen isn't showing yet, so there is nothing to redraw.
        load(false);
    }

    private void load() {
        load(true);
    }

    private void load(boolean redraw) {
        final int generation = ++loadGeneration;
        final Context context = getCarContext().getApplicationContext();
        final FuelGrade grade = CarPrefs.grade(context);
        loading = true;
        if (redraw) invalidate();

        WORKER.execute(() -> {
            List<Station> found = null;
            String problem = null;
            try {
                Location here = PhoneLocation.await(context);
                found = new ArrayList<>(StationSources.get().findNearby(
                        context, here.getLatitude(), here.getLongitude(), grade));
                float[] result = new float[1];
                for (Station station : found) {
                    Location.distanceBetween(here.getLatitude(), here.getLongitude(),
                            station.latitude, station.longitude, result);
                    station.distanceMetres = result[0];
                }
            } catch (StationSourceException e) {
                problem = e.getMessage();
                Log.w(TAG, "Loading stations failed", e);
            } catch (RuntimeException e) {
                problem = "Couldn't load stations. Try again in a moment.";
                Log.e(TAG, "Loading stations failed", e);
            }

            final List<Station> loaded = found;
            final String shown = problem;
            main.post(() -> {
                if (generation != loadGeneration) return;
                loading = false;
                stations = loaded != null ? loaded : Collections.<Station>emptyList();
                message = shown;
                invalidate();
            });
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Context context = getCarContext();
        FuelGrade grade = CarPrefs.grade(context);
        boolean byDistance = CarPrefs.sortByDistance(context);

        PlaceListMapTemplate.Builder template = new PlaceListMapTemplate.Builder()
                .setTitle(grade.label + " gas nearby")
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(actionStrip(grade, byDistance))
                .setCurrentLocationEnabled(PhoneLocation.hasPermission(context));

        if (stations == null) {
            return template.setLoading(true).build();
        }

        ItemList.Builder list = new ItemList.Builder();
        List<Station> sorted = sorted(stations, grade, byDistance);
        int limit = Math.min(sorted.size(), rowLimit());
        for (int i = 0; i < limit; i++) {
            list.addItem(row(sorted.get(i), grade, i + 1));
        }
        if (limit == 0) {
            list.setNoItemsMessage(message != null ? message
                    : loading ? "Loading stations…" : "No " + grade.label.toLowerCase()
                    + " prices posted nearby.");
        }
        return template.setItemList(list.build()).build();
    }

    private ActionStrip actionStrip(FuelGrade grade, boolean byDistance) {
        Action gradeAction = new Action.Builder()
                .setTitle(grade.label)
                .setOnClickListener(() -> getScreenManager().pushForResult(
                        new FuelGradeScreen(getCarContext()),
                        result -> {
                            if (result != null) load();
                        }))
                .build();
        Action sortAction = new Action.Builder()
                // Names the order the list switches to.
                .setTitle(byDistance ? "Cheapest" : "Closest")
                .setOnClickListener(() -> {
                    CarPrefs.setSortByDistance(getCarContext(), !byDistance);
                    // Fresh prices and position whenever the driver asks for a new order.
                    load();
                })
                .build();
        return new ActionStrip.Builder().addAction(gradeAction).addAction(sortAction).build();
    }

    private Row row(Station station, FuelGrade grade, int number) {
        Station.Price price = station.price(grade);
        String title = (price != null ? Formats.price(price.value) + "  " : "") + station.name;

        // The car fills in the distance in its own units where the span is.
        StringBuilder details = new StringBuilder(" ");
        if (station.address != null) details.append(" · ").append(station.address);
        String age = price != null ? Formats.age(price.postedAtMillis) : null;
        if (age != null) details.append(" · ").append(age);
        SpannableString text = new SpannableString(details);
        if (station.distanceMetres >= 0) {
            text.setSpan(DistanceSpan.create(distance(station.distanceMetres)), 0, 1,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        Place place = new Place.Builder(CarLocation.create(station.latitude, station.longitude))
                .setMarker(new PlaceMarker.Builder().setLabel(String.valueOf(number)).build())
                .build();

        return new Row.Builder()
                .setTitle(title)
                .addText(text)
                .setMetadata(new Metadata.Builder().setPlace(place).build())
                .setBrowsable(true)
                .setOnClickListener(() -> getScreenManager().push(
                        new StationDetailScreen(getCarContext(), station, grade)))
                .build();
    }

    static Distance distance(double metres) {
        if (Formats.usesMiles()) {
            double miles = metres / 1609.344;
            return Distance.create(miles, Distance.UNIT_MILES);
        }
        return Distance.create(metres / 1000, Distance.UNIT_KILOMETERS);
    }

    private int rowLimit() {
        try {
            if (getCarContext().getCarAppApiLevel() >= CarAppApiLevels.LEVEL_2) {
                return getCarContext().getCarService(ConstraintManager.class)
                        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "No content limit from the car", e);
        }
        return DEFAULT_ROW_LIMIT;
    }

    /** Stations with a price for the grade first, then by price or distance. */
    private static List<Station> sorted(List<Station> stations, final FuelGrade grade,
                                        final boolean byDistance) {
        List<Station> copy = new ArrayList<>(stations);
        Collections.sort(copy, new Comparator<Station>() {
            @Override
            public int compare(Station a, Station b) {
                Station.Price pa = a.price(grade);
                Station.Price pb = b.price(grade);
                if ((pa == null) != (pb == null)) return pa == null ? 1 : -1;
                if (!byDistance && pa != null && pa.value != pb.value) {
                    return Double.compare(pa.value, pb.value);
                }
                return Double.compare(a.distanceMetres, b.distanceMetres);
            }
        });
        return copy;
    }
}
