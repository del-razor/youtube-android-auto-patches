package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Intent;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.HostException;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import java.util.Locale;
import java.util.Map;

/** One station: its address, every posted price, and a button to drive there. */
final class StationDetailScreen extends Screen {
    private static final String TAG = "DelRazorGasBuddyCar";

    private final Station station;
    private final FuelGrade grade;

    StationDetailScreen(@NonNull CarContext carContext, Station station, FuelGrade grade) {
        super(carContext);
        this.station = station;
        this.grade = grade;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Pane.Builder pane = new Pane.Builder();

        // The car shows only a few rows, so the chosen grade comes first.
        Station.Price chosen = station.price(grade);
        if (chosen != null) pane.addRow(priceRow(grade, chosen));

        StringBuilder where = new StringBuilder(" ");
        if (station.locality != null) where.append(" · ").append(station.locality);
        SpannableString whereText = new SpannableString(where);
        if (station.distanceMetres >= 0) {
            whereText.setSpan(DistanceSpan.create(StationListScreen.distance(station.distanceMetres)),
                    0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        pane.addRow(new Row.Builder()
                .setTitle(station.address != null ? station.address : station.name)
                .addText(whereText)
                .build());

        for (Map.Entry<FuelGrade, Station.Price> entry : station.prices().entrySet()) {
            if (entry.getKey() != grade) pane.addRow(priceRow(entry.getKey(), entry.getValue()));
        }

        pane.addAction(new Action.Builder()
                .setTitle("Navigate")
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener(this::navigate)
                .build());

        return new PaneTemplate.Builder(pane.build())
                .setTitle(station.name)
                .setHeaderAction(Action.BACK)
                .build();
    }

    private static Row priceRow(FuelGrade grade, Station.Price price) {
        StringBuilder text = new StringBuilder();
        if (price.cashValue > 0 && price.cashValue != price.value) {
            text.append("Cash ").append(Formats.price(price.cashValue));
        }
        String age = Formats.age(price.postedAtMillis);
        if (age != null) {
            if (text.length() > 0) text.append(" · ");
            text.append("Updated ").append(age);
            if (price.postedBy != null && !price.postedBy.isEmpty()) {
                text.append(" by ").append(price.postedBy);
            }
        }
        Row.Builder row = new Row.Builder().setTitle(grade.label + "  " + Formats.price(price.value));
        if (text.length() > 0) row.addText(text.toString());
        return row.build();
    }

    /** Hands the station to the car's navigation app (Google Maps or Waze). */
    private void navigate() {
        Uri destination = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f",
                station.latitude, station.longitude));
        try {
            getCarContext().startCarApp(new Intent(CarContext.ACTION_NAVIGATE, destination));
        } catch (HostException | SecurityException e) {
            Log.e(TAG, "Starting navigation failed", e);
            CarToast.makeText(getCarContext(), "Couldn't start navigation", CarToast.LENGTH_LONG)
                    .show();
        }
    }
}
