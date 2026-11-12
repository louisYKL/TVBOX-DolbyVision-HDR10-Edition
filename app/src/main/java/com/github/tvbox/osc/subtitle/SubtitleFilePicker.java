package com.github.tvbox.osc.subtitle;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Opens Android's document providers so subtitles can be selected from any mounted storage. */
public final class SubtitleFilePicker {
    public static final int REQUEST_CODE = 0x5A31;

    private SubtitleFilePicker() {
    }

    public static Intent createIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/*",
                "application/x-subrip",
                "application/ttml+xml",
                "application/xml",
                "application/x-ass",
                "application/x-ssa",
                "text/xml",
                "application/octet-stream"
        });
        return intent;
    }

    public static String keepReadAccess(Context context, Intent result) {
        if (context == null || result == null || result.getData() == null) {
            return null;
        }
        Uri uri = result.getData();
        int grantedFlags = result.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if ((result.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                && grantedFlags != 0) {
            try {
                context.getContentResolver().takePersistableUriPermission(uri, grantedFlags);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // The selected file remains readable for the current grant even if it cannot persist.
            }
        }
        return uri.toString();
    }
}
