package itkach.aard2.dictionary;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.Map;

import itkach.slob.Slob;

/**
 * Abstraction over a dictionary in any format (Slob, MDict, StarDict, …).
 *
 * <p>Implementations must be thread-safe for concurrent read access.</p>
 */
public interface Dictionary {

    /**
     * Returns a stable, unique identifier for this dictionary instance.
     * For Slob files this is the UUID embedded in the file; for other
     * formats a deterministic UUID is derived from the file path.
     */
    @NonNull
    String getId();

    /**
     * Returns the human-readable name of the dictionary (e.g. the "label" tag
     * from a Slob header, or the "bookname" field from a StarDict .ifo file).
     */
    @NonNull
    String getLabel();

    /**
     * Returns the canonical URI that identifies the content of this dictionary
     * (used to match dictionaries across versions / replacements).
     */
    @NonNull
    String getUri();

    /**
     * Returns metadata tags for this dictionary. Keys are consistent with the
     * Slob tag names defined in {@link itkach.aard2.slob.SlobTags}.
     */
    @NonNull
    Map<String, String> getTags();

    /**
     * Returns the total number of content blobs / articles (may differ from
     * {@link #size()} when one article has multiple index keys).
     */
    long getBlobCount();

    /**
     * Returns the total number of index entries (key → blob mappings). This is
     * the value used for random-article selection.
     */
    int size();

    /**
     * Returns the entry at position {@code i} in the sorted key index.
     * Used for random article selection.
     */
    @Nullable
    DictionaryEntry get(int i);

    /**
     * Searches the dictionary for entries matching {@code key} at the given
     * collation {@code strength}.
     */
    @NonNull
    Iterator<DictionaryEntry> find(@NonNull String key, @NonNull Slob.Strength strength);

    /**
     * Returns the content of the blob identified by {@code blobId}. The format
     * of {@code blobId} is implementation-specific.
     */
    @Nullable
    DictionaryContent getContent(@NonNull String blobId);

    /**
     * Returns only the MIME type of the blob identified by {@code blobId},
     * without decoding the full content. Falls back to returning the full
     * content type from {@link #getContent(String)} if a cheaper path is
     * unavailable.
     */
    @NonNull
    String getContentType(@NonNull String blobId);
}
