package app.delrazor.extension.gasbuddy.androidauto;

/** The fuel grades GasBuddy lists. */
public enum FuelGrade {
    REGULAR("Regular"),
    MIDGRADE("Midgrade"),
    PREMIUM("Premium"),
    DIESEL("Diesel");

    public final String label;

    FuelGrade(String label) {
        this.label = label;
    }

    static FuelGrade fromName(String name) {
        for (FuelGrade grade : values()) {
            if (grade.name().equals(name)) return grade;
        }
        return REGULAR;
    }
}
