package itkach.aard2.dictionaries;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import itkach.aard2.SlobDescriptorList;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.utils.ThreadUtils;

/**
 * Manages auto-loading of dictionaries from a persistent folder.
 * Handles detection of added, removed, and broken dictionaries.
 */
public class DictionaryFolderManager {
    private static final String TAG = "DictFolderManager";

    private final Context context;
    private final SlobDescriptorList dictionaries;
    
    // Track which dictionaries came from auto-load folder (id -> file URIs mapping)
    private final Map<String, String> autoLoadedDictionaries = new HashMap<>();
    
    // Track incomplete/broken dictionaries (id -> missing file count)
    private final Map<String, Integer> brokenDictionaries = new HashMap<>();

    public DictionaryFolderManager(@NonNull Context context, @NonNull SlobDescriptorList dictionaries) {
        this.context = context;
        this.dictionaries = dictionaries;
    }

    /**
     * Scans the configured auto-load folder and synchronizes dictionaries.
     * Should be called on a background thread.
     * 
     * @param loadingCallback Called with true when loading starts, false when it ends
     * @return true if any changes were made
     */
    @WorkerThread
    public boolean scanAndSync(@Nullable LoadingCallback loadingCallback) {
        String folderUriStr = AppPrefs.getAutoLoadDictFolderUri();
        if (folderUriStr.isEmpty()) {
            return false;
        }

        Uri folderUri = Uri.parse(folderUriStr);
        Log.d(TAG, "Scanning folder: " + folderUri);

        if (loadingCallback != null) {
            ThreadUtils.postOnMainThread(() -> loadingCallback.onLoadingChanged(true));
        }

        try {
            DictionaryScanner.ScanResult scanResult = DictionaryScanner.scanFolder(context, folderUri);
            boolean changed = syncDictionaries(scanResult);
            return changed;
        } finally {
            if (loadingCallback != null) {
                ThreadUtils.postOnMainThread(() -> loadingCallback.onLoadingChanged(false));
            }
        }
    }

    /**
     * Synchronizes the dictionary list based on scan results.
     * Handles additions, removals, and broken dictionary detection.
     */
    @WorkerThread
    private boolean syncDictionaries(@NonNull DictionaryScanner.ScanResult scanResult) {
        boolean changed = false;
        Set<String> currentAutoLoadIds = new HashSet<>(autoLoadedDictionaries.keySet());

        // Process each scanned dictionary
        for (DictionaryScanner.DictionaryFileSet fileSet : scanResult.dictionaries) {
            String fileUri = fileSet.mainFile.getUri().toString();
            
            // Check if this file URI is already tracked
            boolean alreadyLoaded = autoLoadedDictionaries.containsValue(fileUri);
            
            if (fileSet.isComplete) {
                // Dictionary has all required files
                if (!alreadyLoaded) {
                    // New dictionary - add it
                    String dictId = addDictionary(fileSet);
                    if (dictId != null) {
                        autoLoadedDictionaries.put(dictId, fileUri);
                        changed = true;
                    }
                } else {
                    // Find the dictionary ID for this file URI
                    String dictId = findDictIdByFileUri(fileUri);
                    if (dictId != null) {
                        if (brokenDictionaries.containsKey(dictId)) {
                            // Previously broken dictionary is now fixed - update it
                            brokenDictionaries.remove(dictId);
                            if (updateDictionary(fileSet)) {
                                changed = true;
                            }
                        }
                        currentAutoLoadIds.remove(dictId);
                    }
                }
            } else {
                // Dictionary is missing required files (broken)
                String dictId = findDictIdByFileUri(fileUri);
                if (dictId != null && alreadyLoaded && !brokenDictionaries.containsKey(dictId)) {
                    // Dictionary was complete, now broken - mark as broken
                    markDictionaryAsBroken(dictId, fileSet);
                    brokenDictionaries.put(dictId, fileSet.files.size());
                    changed = true;
                }
                if (dictId != null) {
                    currentAutoLoadIds.remove(dictId);
                }
            }
        }

        // Remove dictionaries that are no longer in the folder
        for (String removedId : currentAutoLoadIds) {
            if (removeDictionary(removedId)) {
                autoLoadedDictionaries.remove(removedId);
                brokenDictionaries.remove(removedId);
                changed = true;
            }
        }

        return changed;
    }

    /**
     * Adds a new dictionary from the scanned file set.
     * @return The dictionary ID if successfully added, null otherwise
     */
    @WorkerThread
    @Nullable
    private String addDictionary(@NonNull DictionaryScanner.DictionaryFileSet fileSet) {
        try {
            Uri uri = fileSet.mainFile.getUri();
            
            // Take persistable URI permission
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            // Create descriptor and load dictionary
            SlobDescriptor descriptor = new SlobDescriptor();
            descriptor.path = uri.toString();
            descriptor.format = fileSet.format;
            descriptor.loadDictionary(context);
            
            // Check if dictionary with this ID already exists
            if (descriptor.id != null && !dictionaries.hasId(descriptor.id)) {
                dictionaries.add(descriptor);
                Log.d(TAG, "Added dictionary: " + descriptor.getLabel() + " (format: " + fileSet.format + ", id: " + descriptor.id + ")");
                return descriptor.id;
            } else {
                Log.d(TAG, "Dictionary already exists or failed to load: " + descriptor.id);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to add dictionary: " + fileSet.mainFile.getUri(), e);
            return null;
        }
    }

    /**
     * Updates an existing dictionary (e.g., when it's been repaired).
     */
    @WorkerThread
    private boolean updateDictionary(@NonNull DictionaryScanner.DictionaryFileSet fileSet) {
        try {
            String fileUri = fileSet.mainFile.getUri().toString();
            String dictId = findDictIdByFileUri(fileUri);
            
            if (dictId == null) {
                return false;
            }
            
            // Find existing descriptor
            SlobDescriptor existing = findDescriptorById(dictId);
            if (existing == null) {
                return false;
            }

            // Reload the dictionary
            existing.error = null;
            existing.active = true;
            existing.expandDetail = false;
            existing.loadDictionary(context);
            
            // Notify changes
            dictionaries.notifyChanged();
            Log.d(TAG, "Updated dictionary: " + existing.getLabel());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to update dictionary: " + fileSet.mainFile.getUri(), e);
            return false;
        }
    }

    /**
     * Marks a dictionary as broken (missing files).
     */
    @WorkerThread
    private void markDictionaryAsBroken(@NonNull String dictId, @NonNull DictionaryScanner.DictionaryFileSet fileSet) {
        SlobDescriptor descriptor = findDescriptorById(dictId);
        if (descriptor != null) {
            descriptor.error = "Dictionary files are incomplete or missing";
            descriptor.active = false;
            descriptor.expandDetail = true;
            dictionaries.notifyChanged();
            Log.w(TAG, "Marked dictionary as broken: " + descriptor.getLabel());
        }
    }

    /**
     * Removes a dictionary that's no longer in the folder.
     */
    @WorkerThread
    private boolean removeDictionary(@NonNull String dictId) {
        SlobDescriptor descriptor = findDescriptorById(dictId);
        if (descriptor != null) {
            int index = dictionaries.indexOf(descriptor);
            if (index >= 0) {
                dictionaries.remove(index);
                
                // Clean up persisted data
                try {
                    descriptor.cleanupPersistedData(context);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to clean up persisted data for " + descriptor.path, e);
                }
                
                Log.d(TAG, "Removed dictionary: " + descriptor.getLabel());
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a descriptor by its dictionary ID.
     */
    @Nullable
    private SlobDescriptor findDescriptorById(@NonNull String dictId) {
        for (SlobDescriptor desc : dictionaries.getList()) {
            if (dictId.equals(desc.id)) {
                return desc;
            }
        }
        return null;
    }

    /**
     * Finds the dictionary ID for a given file URI.
     */
    @Nullable
    private String findDictIdByFileUri(@NonNull String fileUri) {
        for (Map.Entry<String, String> entry : autoLoadedDictionaries.entrySet()) {
            if (fileUri.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Sets the auto-load folder URI.
     * Triggers an immediate scan if the URI is valid.
     */
    public void setAutoLoadFolder(@NonNull Uri folderUri, @Nullable LoadingCallback loadingCallback) {
        try {
            // Take persistable URI permission for the folder
            context.getContentResolver().takePersistableUriPermission(folderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            // Save the folder URI
            AppPrefs.setAutoLoadDictFolderUri(folderUri.toString());
            
            // Trigger scan on background thread
            ThreadUtils.postOnBackgroundThread(() -> scanAndSync(loadingCallback));
        } catch (Exception e) {
            Log.e(TAG, "Failed to set auto-load folder: " + folderUri, e);
        }
    }

    /**
     * Callback interface for loading state changes.
     */
    public interface LoadingCallback {
        void onLoadingChanged(boolean isLoading);
    }
}
