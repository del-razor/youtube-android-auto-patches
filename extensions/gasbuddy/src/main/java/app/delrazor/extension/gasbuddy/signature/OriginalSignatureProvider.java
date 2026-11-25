package app.delrazor.extension.gasbuddy.signature;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Puts {@link OriginalSignature} in place when GasBuddy's process starts. Android starts content
 * providers before Application.onCreate, and the patch gives this one the highest start order,
 * so it runs before GasBuddy sets up Google services. It serves no data.
 */
public final class OriginalSignatureProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        if (getContext() != null) OriginalSignature.install(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
