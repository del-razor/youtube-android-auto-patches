package app.delrazor.extension.gasbuddy.androidauto;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** One gas station, as the car screen shows it. Filled in by a {@link StationSource}. */
public final class Station {

    /** One posted price for one fuel grade. */
    public static final class Price {
        /** Dollars per gallon in the US, cents per litre in Canada, as GasBuddy shows it. */
        public final double value;
        /** Cash price, or 0 when the station has none. */
        public final double cashValue;
        /** When the price was posted, in milliseconds since 1970, or 0 when unknown. */
        public final long postedAtMillis;
        /** Who posted it, or null. */
        public final String postedBy;

        public Price(double value, double cashValue, long postedAtMillis, String postedBy) {
            this.value = value;
            this.cashValue = cashValue;
            this.postedAtMillis = postedAtMillis;
            this.postedBy = postedBy;
        }
    }

    public final String id;
    public final String name;
    /** Street address, or null. */
    public final String address;
    /** City and state, or null. */
    public final String locality;
    public final double latitude;
    public final double longitude;
    /** Distance from the phone in metres, filled in by the car screen. */
    double distanceMetres = -1;

    private final Map<FuelGrade, Price> prices;

    public Station(String id, String name, String address, String locality,
                   double latitude, double longitude, Map<FuelGrade, Price> prices) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.locality = locality;
        this.latitude = latitude;
        this.longitude = longitude;
        this.prices = prices.isEmpty()
                ? Collections.<FuelGrade, Price>emptyMap()
                : Collections.unmodifiableMap(new EnumMap<>(prices));
    }

    /** The price for this grade, or null when nobody has posted one. */
    public Price price(FuelGrade grade) {
        return prices.get(grade);
    }

    public Map<FuelGrade, Price> prices() {
        return prices;
    }
}
