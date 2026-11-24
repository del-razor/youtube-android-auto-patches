package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Context;

import java.util.List;

/**
 * Where the car screen gets stations and prices from. The GasBuddy patch registers one that uses
 * GasBuddy's own data with {@link StationSources#register}.
 */
public interface StationSource {

    /**
     * Returns stations near the given point that sell the grade. Called on a background thread,
     * so it may block. Throws {@link StationSourceException} with a message for the driver.
     */
    List<Station> findNearby(Context context, double latitude, double longitude, FuelGrade grade)
            throws StationSourceException;
}
