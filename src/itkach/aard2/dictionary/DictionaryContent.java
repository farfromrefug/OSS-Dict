package itkach.aard2.dictionary;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

/**
 * Holds the content of a dictionary entry, including its MIME type and raw data.
 * This mirrors {@code Slob.Content} but is format-agnostic.
 */
public final class DictionaryContent {
    @NonNull
    public final String type;
    @NonNull
    public final ByteBuffer data;

    public DictionaryContent(@NonNull String type, @NonNull ByteBuffer data) {
        this.type = type;
        this.data = data;
    }
}
