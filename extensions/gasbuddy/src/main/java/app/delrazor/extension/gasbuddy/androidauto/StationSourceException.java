package app.delrazor.extension.gasbuddy.androidauto;

/** A problem the car screen shows to the driver. The message must be short and plain. */
public final class StationSourceException extends Exception {
    public StationSourceException(String message) {
        super(message);
    }

    public StationSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
