package itkach.aard2.article;

import android.database.DataSetObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import itkach.aard2.dictionary.DictionaryEntry;
import itkach.aard2.lookup.LookupResult;

class LookupResultWrapper implements BlobListWrapper {
    interface ToEntry<T> {
        @Nullable
        DictionaryEntry convert(T item);
    }

    private final LookupResult lookupResult;
    private final ToEntry<DictionaryEntry> toEntry;

    LookupResultWrapper(@NonNull LookupResult lookupResult, @NonNull ToEntry<DictionaryEntry> toEntry) {
        this.lookupResult = lookupResult;
        this.toEntry = toEntry;
    }

    @Override
    public void registerDataSetObserver(@NonNull DataSetObserver observer) {
        lookupResult.registerDataSetObserver(observer);
    }

    @Override
    public void unregisterDataSetObserver(@NonNull DataSetObserver observer) {
        lookupResult.unregisterDataSetObserver(observer);
    }

    @Nullable
    @Override
    public DictionaryEntry get(int index) {
        return toEntry.convert(lookupResult.getList().get(index));
    }

    @Nullable
    @Override
    public CharSequence getLabel(int index) {
        DictionaryEntry item = lookupResult.getList().get(index);
        return item != null ? item.key : null;
    }

    @Override
    public int size() {
        return lookupResult.getList().size();
    }
}
