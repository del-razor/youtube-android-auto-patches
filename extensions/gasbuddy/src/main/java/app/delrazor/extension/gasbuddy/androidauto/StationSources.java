package app.delrazor.extension.gasbuddy.androidauto;

/** Holds the {@link StationSource} the car screen uses. */
public final class StationSources {

    private static volatile StationSource source;

    private StationSources() {
    }

    /** Replaces where the car screen gets stations from, for example in a test. */
    public static void register(StationSource newSource) {
        source = newSource;
    }

    static StationSource get() {
        StationSource current = source;
        return current != null ? current : GASBUDDY;
    }

    /** GasBuddy's own server, asked the way the app asks it. */
    private static final StationSource GASBUDDY = new GasBuddyStationSource();
}
