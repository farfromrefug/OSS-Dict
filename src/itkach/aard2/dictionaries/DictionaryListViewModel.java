package itkach.aard2.dictionaries;

import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.BlobDescriptorList;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.SlobDescriptorList;
import itkach.aard2.SlobHelper;
import itkach.aard2.utils.ThreadUtils;

public class DictionaryListViewModel extends AndroidViewModel {
    @Nullable
    private SlobDescriptor dictionaryToBeReplaced;

    /**
     * Emits {@code true} while one or more dictionaries are being loaded / extracted,
     * {@code false} once all pending loads have completed.  Observe this from the UI to
     * show/hide a progress SnackBar.
     */
    public final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);

    /** Counts in-flight dictionary loads so we only emit false once all complete. */
    private final AtomicInteger loadingCount = new AtomicInteger(0);

    public DictionaryListViewModel(@NonNull Application application) {
        super(application);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
    }

    public void addDictionaries(@NonNull Intent intent) {
        ThreadUtils.postOnBackgroundThread(() -> {
            List<Uri> selection = new ArrayList<>();
            Uri dataUri = intent.getData();
            if (dataUri != null) {
                selection.add(dataUri);
            }
            ClipData clipData = intent.getClipData();
            if (clipData != null) {
                int itemCount = clipData.getItemCount();
                for (int i = 0; i < itemCount; i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    selection.add(uri);
                }
            }
            if (selection.isEmpty()) return;

            // Signal that loading has started (before any blocking I/O begins).
            loadingCount.addAndGet(selection.size());
            isLoading.postValue(true);
            
            // Check if auto-move is enabled and folder is set
            boolean shouldShowMoveHint = itkach.aard2.prefs.AppPrefs.autoMoveToFolder() 
                    && !itkach.aard2.prefs.AppPrefs.getAutoLoadDictFolderUri().isEmpty();

            for (Uri uri : selection) {
                try {
                    getApplication().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    SlobDescriptor sd = SlobDescriptor.fromUri(getApplication(), uri);
                    SlobDescriptorList dictionaries = SlobHelper.getInstance().dictionaries;
                    if (!dictionaries.hasId(sd.id)) {
                        dictionaries.add(sd);
                    }
                } finally {
                    if (loadingCount.decrementAndGet() == 0) {
                        isLoading.postValue(false);
                    }
                }
            }
            
            // Show hint about moving files if enabled
            if (shouldShowMoveHint && selection.size() > 0) {
                itkach.aard2.utils.ThreadUtils.postOnMainThread(() -> {
                    android.widget.Toast.makeText(getApplication(), 
                            itkach.aard2.R.string.msg_move_to_auto_load_folder_hint,
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void setDictionaryToBeReplaced(@Nullable SlobDescriptor dictionaryToBeReplaced) {
        this.dictionaryToBeReplaced = dictionaryToBeReplaced;
    }

    public void updateDictionary(@NonNull Uri newUri) {
        ThreadUtils.postOnBackgroundThread(() -> {
            if (dictionaryToBeReplaced != null) {
                getApplication().getContentResolver().takePersistableUriPermission(newUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                SlobHelper slobHelper = SlobHelper.getInstance();
                SlobDescriptorList dictionaries = slobHelper.dictionaries;
                SlobDescriptor newSd = SlobDescriptor.fromUri(getApplication(), newUri);
                if (!dictionaries.hasId(dictionaryToBeReplaced.id)) {
                    // Dictionary to be replaced does not exist for some reason
                    if (!dictionaries.hasId(newSd.id)) {
                        dictionaries.add(newSd);
                    }
                    return;
                }
                // Replace dictionary
                dictionaries.remove(dictionaryToBeReplaced);
                if (!dictionaries.hasId(newSd.id)) {
                    dictionaries.add(newSd);
                }
                // Update history and bookmarks – use getDictionaryUri for format-agnostic lookup
                String oldId = dictionaryToBeReplaced.id;
                String newId = newSd.id;
                String newDictUri = slobHelper.getDictionaryUri(newId);
                if (newDictUri == null) {
                    // Fallback for Slob
                    newDictUri = slobHelper.getSlobUri(newId);
                }
                final String finalNewDictUri = newDictUri;
                BlobDescriptorList history = slobHelper.history;
                for (BlobDescriptor d : history.getList()) {
                    if (Objects.equals(d.slobId, oldId)) {
                        d.slobId = newId;
                        d.slobUri = finalNewDictUri;
                    }
                }
                BlobDescriptorList bookmarks = slobHelper.bookmarks;
                for (BlobDescriptor d : bookmarks.getList()) {
                    if (Objects.equals(d.slobId, oldId)) {
                        d.slobId = newId;
                        d.slobUri = finalNewDictUri;
                    }
                }
                ThreadUtils.postOnMainThread(() -> {
                    history.notifyDataSetChanged();
                    bookmarks.notifyDataSetChanged();
                });
            }
        });
    }

    /**
     * Sets the auto-load folder and triggers a scan with progress notifications.
     */
    public void setAutoLoadFolder(@NonNull Uri folderUri) {
        DictionaryScanNotification notification = new DictionaryScanNotification(getApplication());
        
        DictionaryFolderManager.getInstance(getApplication()).setAutoLoadFolder(
                folderUri,
                // Loading callback
                isLoading -> {
                    if (isLoading) {
                        loadingCount.incrementAndGet();
                        this.isLoading.postValue(true);
                    } else {
                        if (loadingCount.decrementAndGet() == 0) {
                            this.isLoading.postValue(false);
                        }
                    }
                },
                // Progress callback
                new DictionaryFolderManager.ProgressCallback() {
                    @Override
                    public void onScanStarted() {
                        notification.showScanStarted();
                    }

                    @Override
                    public void onDictionaryLoading(String dictionaryName, int current, int total) {
                        notification.updateProgress(dictionaryName, current, total);
                    }

                    @Override
                    public void onScanCompleted(int addedCount, int removedCount) {
                        notification.showCompleted(addedCount, removedCount);
                    }
                });
    }
    
    /**
     * Clears the auto-load folder and removes all auto-loaded dictionaries.
     */
    public void clearAutoLoadFolder() {
        DictionaryFolderManager.getInstance(getApplication()).clearAutoLoadFolder(isLoading -> {
            if (isLoading) {
                loadingCount.incrementAndGet();
                this.isLoading.postValue(true);
            } else {
                if (loadingCount.decrementAndGet() == 0) {
                    this.isLoading.postValue(false);
                }
            }
        });
    }
}
