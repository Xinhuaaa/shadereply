package io.shadereply;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class GestureConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] arguments,
            String sortOrder) {
        if (!GestureConfig.URI.equals(uri)) throw new IllegalArgumentException("Unknown URI");
        MatrixCursor result = new MatrixCursor(GestureConfig.COLUMNS, 1);
        result.addRow(GestureConfig.readLocal(requireContext()).toRow());
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + GestureConfig.AUTHORITY;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }
}
