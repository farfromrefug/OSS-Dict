package itkach.aard2.descriptor;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.dictionary.SlobDictionary;
import itkach.aard2.dictionary.mdict.MDictDictionary;
import itkach.aard2.dictionary.stardict.StarDictDictionary;
import itkach.aard2.slob.SlobTags;
import itkach.slob.Slob;


public class SlobDescriptor extends BaseDescriptor {
    private final static String TAG = SlobDescriptor.class.getSimpleName();

    /**
     * Dictionary format identifier. One of: {@code "slob"}, {@code "mdict"},
     * {@code "stardict"}.  Defaults to {@code "slob"} so that existing
     * persisted descriptors continue to work without migration.
     */
    public String format = FORMAT_SLOB;

    public static final String FORMAT_SLOB     = "slob";
    public static final String FORMAT_MDICT    = "mdict";
    public static final String FORMAT_STARDICT = "stardict";
    public static final String FORMAT_STARDICT_ARCHIVE = "stardict-archive";

    public String path;
    /**
     * Optional URI of a companion {@code .mdd} resource file for MDict dictionaries.
     * {@code null} when there is no companion MDD file.
     */
    public String mddPath;
    public Map<String, String> tags = new HashMap<>();
    public boolean active = true;
    public long priority;
    public long blobCount;
    public String error;
    public boolean expandDetail = false;
    @SuppressWarnings("FieldCanBeLocal")
    private transient ParcelFileDescriptor fileDescriptor;

    public SlobDescriptor() {
    }

    // -----------------------------------------------------------------------
    // Slob-specific loading (kept for backward compatibility)
    // -----------------------------------------------------------------------

    private void updateFromSlob(@NonNull Slob s) {
        this.id = s.getId().toString();
        this.path = s.fileURI;
        this.tags = s.getTags();
        this.blobCount = s.getBlobCount();
        this.error = null;
    }

    /** @deprecated Use {@link #loadDictionary(Context)} instead. */
    @Deprecated
    @Nullable
    public Slob load(@NonNull Context context) {
        Slob slob = null;
        try {
            final Uri uri = Uri.parse(path);
            fileDescriptor = context.getContentResolver().openFileDescriptor(uri, "r");
            FileInputStream fileInputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            slob = new Slob(fileInputStream.getChannel(), path);
            updateFromSlob(slob);
        } catch (Exception e) {
            Log.e(TAG, "Error while opening " + this.path, e);
            error = e.getMessage();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Error while opening " + this.path, e);
            }
            expandDetail = true;
            active = false;
        }
        return slob;
    }

    // -----------------------------------------------------------------------
    // Format-agnostic loading
    // -----------------------------------------------------------------------

    /**
     * Opens the dictionary file and returns an appropriate {@link Dictionary}
     * implementation based on {@link #format}.
     *
     * <p>On success the descriptor's {@link #id}, {@link #tags}, and
     * {@link #blobCount} are updated from the loaded dictionary.  On failure
     * {@link #error} is set and {@link #active} is set to {@code false}.</p>
     */
    @Nullable
    public Dictionary loadDictionary(@NonNull Context context) {
        try {
            Uri uri = Uri.parse(path);
            Dictionary dict;
            switch (format) {
                case FORMAT_MDICT:
                    dict = MDictDictionary.fromUri(context, uri, path);
                    if (mddPath != null && !mddPath.isEmpty()) {
                        try {
                            Uri mddUri = Uri.parse(mddPath);
                            MDictDictionary mdd = MDictDictionary.fromUri(context, mddUri, mddPath);
                            ((MDictDictionary) dict).setMdd(mdd);
                            Log.d(TAG, "Attached MDD resource file: " + mddPath);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to load MDD companion file: " + mddPath, e);
                        }
                    }
                    break;
                case FORMAT_STARDICT:
                    dict = StarDictDictionary.fromIfoUri(context, uri, path);
                    break;
                case FORMAT_STARDICT_ARCHIVE:
                    dict = StarDictDictionary.fromArchiveUri(context, uri, path);
                    break;
                case FORMAT_SLOB:
                default:
                    fileDescriptor = context.getContentResolver().openFileDescriptor(uri, "r");
                    FileInputStream fis = new FileInputStream(fileDescriptor.getFileDescriptor());
                    Slob slob = new Slob(fis.getChannel(), path);
                    updateFromSlob(slob);
                    return new SlobDictionary(slob);
            }
            // Update descriptor metadata from the loaded dictionary
            this.id = dict.getId();
            this.tags = new HashMap<>(dict.getTags());
            this.blobCount = dict.getBlobCount();
            this.error = null;
            return dict;
        } catch (Exception e) {
            Log.e(TAG, "Error opening " + this.path + " (format=" + format + ")", e);
            error = e.getMessage();
            expandDetail = true;
            active = false;
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Persistent data cleanup
    // -----------------------------------------------------------------------

    /**
     * Deletes any persisted data (extracted files, index caches) that was
     * created for this dictionary.  Safe to call on any format; formats that
     * do not produce persistent data are silently skipped.
     *
     * <p>Should be called on a background thread when the user removes
     * ("forgets") the dictionary.</p>
     */
    public void cleanupPersistedData(@NonNull Context context) {
        if (path == null) return;
        switch (format) {
            case FORMAT_STARDICT_ARCHIVE:
                StarDictDictionary.cleanupPersistedData(context, path);
                break;
            case FORMAT_MDICT:
                MDictDictionary.cleanupPersistedData(context, path);
                if (mddPath != null && !mddPath.isEmpty()) {
                    MDictDictionary.cleanupPersistedData(context, mddPath);
                }
                break;
            default:
                // FORMAT_SLOB and FORMAT_STARDICT produce no persistent local data.
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------------

    @NonNull
    public String getLabel() {
        String label = tags.get(SlobTags.TAG_LABEL);
        if (TextUtils.isEmpty(label)) {
            return "???";
        }
        return label;
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Creates a descriptor from a URI, auto-detecting the dictionary format
     * from the file extension.
     */
    @NonNull
    public static SlobDescriptor fromUri(@NonNull Context context, @NonNull Uri uri) {
        SlobDescriptor s = new SlobDescriptor();
        s.path = uri.toString();
        s.format = detectFormat(context, uri);
        s.loadDictionary(context);
        return s;
    }

    /**
     * Detects the dictionary format for a content URI by querying the display
     * name (actual filename) from the content resolver.  Falls back to the URI
     * string itself when the display name cannot be obtained.
     */
    @NonNull
    public static String detectFormat(@NonNull Context context, @NonNull Uri uri) {
        String displayName = null;
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                displayName = cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not query display name for " + uri, e);
        }
        // Fall back to the URI string itself (e.g. file:// URIs that carry the name)
        if (displayName == null) {
            displayName = uri.toString();
        }
        return detectFormat(displayName);
    }

    /**
     * Detects the dictionary format from the file path / URI string based on
     * the file extension.
     */
    @NonNull
    public static String detectFormat(@NonNull String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".mdx")) return FORMAT_MDICT;
        if (lower.endsWith(".ifo")) return FORMAT_STARDICT;
        if (lower.endsWith(".zip")) {
            // Could be StarDict archive - we'll try to detect when loading
            return FORMAT_STARDICT_ARCHIVE;
        }
        return FORMAT_SLOB;
    }
}
