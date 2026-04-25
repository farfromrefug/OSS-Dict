package itkach.aard2.article;

import android.database.DataSetObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import itkach.aard2.dictionary.DictionaryEntry;

interface BlobListWrapper {
    void registerDataSetObserver(@NonNull DataSetObserver observer);

    void unregisterDataSetObserver(@NonNull DataSetObserver observer);

    @Nullable
    DictionaryEntry get(int index);

    @Nullable
    CharSequence getLabel(int index);

    int size();
}
