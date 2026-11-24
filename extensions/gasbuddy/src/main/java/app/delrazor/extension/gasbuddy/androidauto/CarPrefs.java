package app.delrazor.extension.gasbuddy.androidauto;

import android.content.Context;
import android.content.SharedPreferences;

/** The car screen's own settings, kept apart from GasBuddy's. */
final class CarPrefs {
    private static final String FILE = "delrazor_android_auto";
    private static final String KEY_GRADE = "fuel_grade";
    private static final String KEY_SORT_BY_DISTANCE = "sort_by_distance";

    private CarPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static FuelGrade grade(Context context) {
        return FuelGrade.fromName(prefs(context).getString(KEY_GRADE, FuelGrade.REGULAR.name()));
    }

    static void setGrade(Context context, FuelGrade grade) {
        prefs(context).edit().putString(KEY_GRADE, grade.name()).apply();
    }

    static boolean sortByDistance(Context context) {
        return prefs(context).getBoolean(KEY_SORT_BY_DISTANCE, false);
    }

    static void setSortByDistance(Context context, boolean byDistance) {
        prefs(context).edit().putBoolean(KEY_SORT_BY_DISTANCE, byDistance).apply();
    }
}
