package itkach.aard2.dictionary;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;

import itkach.slob.Slob;

/**
 * Adapts a {@link Slob} instance to the {@link Dictionary} interface, allowing
 * the rest of the application to treat Slob files and newer formats uniformly.
 */
public final class SlobDictionary implements Dictionary {

    @NonNull
    private final Slob slob;

    public SlobDictionary(@NonNull Slob slob) {
        this.slob = slob;
    }

    /** Returns the underlying {@link Slob}. */
    @NonNull
    public Slob getSlob() {
        return slob;
    }

    @Override
    @NonNull
    public String getId() {
        return slob.getId().toString();
    }

    @Override
    @NonNull
    public String getLabel() {
        String label = slob.getTags().get("label");
        return (label != null && !label.isEmpty()) ? label : "???";
    }

    @Override
    @NonNull
    public String getUri() {
        return slob.getURI();
    }

    @Override
    @NonNull
    public Map<String, String> getTags() {
        return slob.getTags();
    }

    @Override
    public long getBlobCount() {
        return slob.getBlobCount();
    }

    @Override
    public int size() {
        return slob.size();
    }

    @Override
    @Nullable
    public DictionaryEntry get(int i) {
        Slob.Blob blob = slob.get(i);
        return blob != null ? fromBlob(blob) : null;
    }

    @Override
    @NonNull
    public Iterator<DictionaryEntry> find(@NonNull String key, @NonNull Slob.Strength strength) {
        Iterator<Slob.Blob> blobIterator = slob.find(key, strength);
        return new Iterator<DictionaryEntry>() {
            @Override
            public boolean hasNext() {
                return blobIterator.hasNext();
            }

            @Override
            public DictionaryEntry next() {
                return fromBlob(blobIterator.next());
            }
        };
    }

    @Override
    @Nullable
    public DictionaryContent getContent(@NonNull String blobId) {
        try {
            Slob.Content content = slob.getContent(blobId);
            return new DictionaryContent(content.type, content.data);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    @NonNull
    public String getContentType(@NonNull String blobId) {
        return slob.getContentType(blobId);
    }

    /**
     * Creates a {@link DictionaryEntry} from a {@link Slob.Blob}, keeping the
     * blob ID so the HTTP server can fetch content directly.
     */
    @NonNull
    public DictionaryEntry fromBlob(@NonNull Slob.Blob blob) {
        return new DictionaryEntry(this, blob.id, blob.key, blob.fragment);
    }

    /**
     * Reconstructs a {@link Slob.Blob} from a {@link DictionaryEntry} so that
     * Slob-specific code paths (e.g. the search merge) continue to work.
     */
    @NonNull
    public Slob.Blob toBlob(@NonNull DictionaryEntry entry) {
        return new Slob.Blob(slob, entry.id, entry.key, entry.fragment);
    }
}
