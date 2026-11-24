package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Context;

import java.util.List;

/** Holds the {@link StationSource} the car screen uses. */
public final class StationSources {

    private static volatile StationSource source;

    private StationSources() {
    }

    /** Called by patched GasBuddy code to hand its station data to the car screen. */
    public static void register(StationSource newSource) {
        source = newSource;
    }

    static StationSource get() {
        StationSource current = source;
        return current != null ? current : NOT_CONNECTED;
    }

    /** Used until GasBuddy's own station data is wired in. */
    private static final StationSource NOT_CONNECTED = new StationSource() {
        @Override
        public List<Station> findNearby(Context context, double latitude, double longitude,
                                        FuelGrade grade) throws StationSourceException {
            throw new StationSourceException(
                    "Station prices aren't connected yet. This test build only checks the car screen.");
        }
    };
}
