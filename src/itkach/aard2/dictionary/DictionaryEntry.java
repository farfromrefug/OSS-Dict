package itkach.aard2.dictionary;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A single entry / blob inside a {@link Dictionary}.
 *
 * <p>This class replaces {@code Slob.Blob} in the higher-level app code so that
 * non-Slob formats can be represented uniformly.</p>
 */
public final class DictionaryEntry {

    /** The dictionary that owns this entry. */
    @NonNull
    public final Dictionary owner;

    /**
     * An opaque identifier that the owning dictionary understands and can pass
     * to {@link Dictionary#getContent(String)}.  For Slob dictionaries this is
     * "{@code binIndex}-{@code itemIndex}"; for other formats it can be an
     * integer index or an offset encoded as a decimal string.
     */
    @NonNull
    public final String id;

    /** The lookup key (headword) for this entry. */
    @NonNull
    public final String key;

    /**
     * An optional URL fragment that should be appended when navigating to this
     * entry.  May be {@code null} or empty.
     */
    @Nullable
    public final String fragment;

    public DictionaryEntry(@NonNull Dictionary owner,
                           @NonNull String id,
                           @NonNull String key,
                           @Nullable String fragment) {
        this.owner = owner;
        this.id = id;
        this.key = key;
        this.fragment = fragment;
    }

    /** Returns the content of this entry. */
    @Nullable
    public DictionaryContent getContent() {
        return owner.getContent(id);
    }

    /** Returns the MIME type of this entry's content. */
    @NonNull
    public String getContentType() {
        return owner.getContentType(id);
    }

    @Override
    public int hashCode() {
        int result = owner.getId().hashCode();
        result = 31 * result + id.hashCode();
        result = 31 * result + key.hashCode();
        result = 31 * result + (fragment != null ? fragment.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DictionaryEntry)) return false;
        DictionaryEntry other = (DictionaryEntry) obj;
        if (!owner.getId().equals(other.owner.getId())) return false;
        if (!id.equals(other.id)) return false;
        if (!key.equals(other.key)) return false;
        if (fragment == null) return other.fragment == null;
        return fragment.equals(other.fragment);
    }

    @Override
    public String toString() {
        return "DictionaryEntry{dict=" + owner.getId() + ", id=" + id
                + ", key=" + key + ", fragment=" + fragment + "}";
    }
}
