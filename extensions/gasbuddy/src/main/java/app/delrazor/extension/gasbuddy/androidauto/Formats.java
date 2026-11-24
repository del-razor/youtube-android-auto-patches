package app.delrazor.extension.gasbuddy.androidauto;

import java.util.Locale;

/** Text the car screen shows for prices, times and distances. */
final class Formats {
    private Formats() {
    }

    /**
     * GasBuddy shows US prices in dollars per gallon ($3.19) and Canadian prices in cents per
     * litre (145.9¢). No US price is above $20 and no Canadian one below 20¢, so the value says
     * which it is.
     */
    static String price(double value) {
        if (value >= 20) return String.format(Locale.US, "%.1f¢", value);
        return String.format(Locale.US, "$%.2f", value);
    }

    /** "just now", "25 min ago", "3 h ago", "2 days ago", or null when unknown. */
    static String age(long postedAtMillis) {
        if (postedAtMillis <= 0) return null;
        long minutes = (System.currentTimeMillis() - postedAtMillis) / 60_000;
        if (minutes < 2) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " h ago";
        long days = hours / 24;
        return days == 1 ? "1 day ago" : days + " days ago";
    }

    /** Miles in the US, UK and Liberia and Myanmar; kilometres elsewhere. */
    static boolean usesMiles() {
        String country = Locale.getDefault().getCountry();
        return "US".equals(country) || "GB".equals(country)
                || "LR".equals(country) || "MM".equals(country);
    }
}
