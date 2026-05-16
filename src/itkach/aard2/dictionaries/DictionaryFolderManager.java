package itkach.aard2.dictionaries;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import itkach.aard2.SlobDescriptorList;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.utils.ThreadUtils;

/**
 * Manages auto-loading of dictionaries from a persistent folder.
 * Handles detection of added, removed, and broken dictionaries.
 */
public class DictionaryFolderManager {
    private static final String TAG = "DictFolderManager";
    
    private static DictionaryFolderManager instance;

    private final Context context;
    private final SlobDescriptorList dictionaries;
    
    // Track which dictionaries came from auto-load folder (id -> file URIs mapping)
    private final Map<String, String> autoLoadedDictionaries = new HashMap<>();
    
    // Track incomplete/broken dictionaries (id -> missing file count)
    private final Map<String, Integer> brokenDictionaries = new HashMap<>();

    private DictionaryFolderManager(@NonNull Context context, @NonNull SlobDescriptorList dictionaries) {
        this.context = context;
        this.dictionaries = dictionaries;
    }
    
    /**
     * Gets the singleton instance of DictionaryFolderManager.
     */
    @NonNull
    public static synchronized DictionaryFolderManager getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new DictionaryFolderManager(context.getApplicationContext(), 
                    SlobHelper.getInstance().dictionaries);
        }
        return instance;
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
        return scanAndSync(loadingCallback, null);
    }
    
    /**
     * Scans the configured auto-load folder and synchronizes dictionaries with progress updates.
     * Should be called on a background thread.
     * 
     * @param loadingCallback Called with true when loading starts, false when it ends
     * @param progressCallback Called with progress updates during scanning
     * @return true if any changes were made
     */
    @WorkerThread
    public boolean scanAndSync(@Nullable LoadingCallback loadingCallback, @Nullable ProgressCallback progressCallback) {
        String folderUriStr = AppPrefs.getAutoLoadDictFolderUri();
        if (folderUriStr.isEmpty()) {
            return false;
        }

        Uri folderUri = Uri.parse(folderUriStr);
        Log.d(TAG, "Scanning folder: " + folderUri);

        if (loadingCallback != null) {
            ThreadUtils.postOnMainThread(() -> loadingCallback.onLoadingChanged(true));
        }
        
        if (progressCallback != null) {
            ThreadUtils.postOnMainThread(progressCallback::onScanStarted);
        }

        try {
            DictionaryScanner.ScanResult scanResult = DictionaryScanner.scanFolder(context, folderUri);
            boolean changed = syncDictionaries(scanResult, progressCallback);
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
        return syncDictionaries(scanResult, null);
    }
    
    /**
     * Synchronizes the dictionary list based on scan results with progress updates.
     * Handles additions, removals, and broken dictionary detection.
     */
    @WorkerThread
    private boolean syncDictionaries(@NonNull DictionaryScanner.ScanResult scanResult, 
                                     @Nullable ProgressCallback progressCallback) {
        boolean changed = false;
        int addedCount = 0;
        int removedCount = 0;
        Set<String> currentAutoLoadIds = new HashSet<>(autoLoadedDictionaries.keySet());
        
        int totalDicts = scanResult.dictionaries.size();
        int currentIndex = 0;

        // Process each scanned dictionary
        for (DictionaryScanner.DictionaryFileSet fileSet : scanResult.dictionaries) {
            currentIndex++;
            String fileUri = fileSet.mainFile.getUri().toString();
            
            // Notify progress
            if (progressCallback != null) {
                String dictName = fileSet.mainFile.getName();
                if (dictName != null) {
                    final int current = currentIndex;
                    final int total = totalDicts;
                    final String name = dictName;
                    ThreadUtils.postOnMainThread(() -> 
                        progressCallback.onDictionaryLoading(name, current, total));
                }
            }
            
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
                        addedCount++;
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
                removedCount++;
            }
        }
        
        // Notify completion
        if (progressCallback != null) {
            final int added = addedCount;
            final int removed = removedCount;
            ThreadUtils.postOnMainThread(() -> 
                progressCallback.onScanCompleted(added, removed));
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
//            context.getContentResolver().takePersistableUriPermission(uri,
//                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
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
     * Clears any existing auto-loaded dictionaries first, then triggers a scan.
     */
    public void setAutoLoadFolder(@NonNull Uri folderUri, @Nullable LoadingCallback loadingCallback) {
        setAutoLoadFolder(folderUri, loadingCallback, null);
    }
    
    /**
     * Sets the auto-load folder URI with progress updates.
     * Clears any existing auto-loaded dictionaries first, then triggers a scan.
     */
    public void setAutoLoadFolder(@NonNull Uri folderUri, @Nullable LoadingCallback loadingCallback,
                                  @Nullable ProgressCallback progressCallback) {
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                // Clear existing auto-loaded dictionaries first
                clearAutoLoadedDictionaries();
                
                // Release old folder permission if exists
                String oldFolderUri = AppPrefs.getAutoLoadDictFolderUri();
                if (!oldFolderUri.isEmpty()) {
                    try {
                        context.getContentResolver().releasePersistableUriPermission(
                                Uri.parse(oldFolderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to release old folder permission: " + oldFolderUri, e);
                    }
                }
                
                // Take persistable URI permission for the new folder
                context.getContentResolver().takePersistableUriPermission(folderUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                
                // Save the folder URI
                AppPrefs.setAutoLoadDictFolderUri(folderUri.toString());
                
                // Trigger scan
                scanAndSync(loadingCallback, progressCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to set auto-load folder: " + folderUri, e);
            }
        });
    }
    
    /**
     * Clears the auto-load folder setting and removes all auto-loaded dictionaries.
     */
    public void clearAutoLoadFolder(@Nullable LoadingCallback loadingCallback) {
        ThreadUtils.postOnBackgroundThread(() -> {
            try {
                if (loadingCallback != null) {
                    ThreadUtils.postOnMainThread(() -> loadingCallback.onLoadingChanged(true));
                }
                
                // Remove all auto-loaded dictionaries
                clearAutoLoadedDictionaries();
                
                // Release folder permission
                String folderUri = AppPrefs.getAutoLoadDictFolderUri();
                if (!folderUri.isEmpty()) {
                    try {
                        context.getContentResolver().releasePersistableUriPermission(
                                Uri.parse(folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to release folder permission: " + folderUri, e);
                    }
                }
                
                // Clear the preference
                AppPrefs.setAutoLoadDictFolderUri("");
                
                Log.d(TAG, "Auto-load folder cleared");
            } finally {
                if (loadingCallback != null) {
                    ThreadUtils.postOnMainThread(() -> loadingCallback.onLoadingChanged(false));
                }
            }
        });
    }
    
    /**
     * Removes all dictionaries that were auto-loaded from the folder.
     * This method checks each dictionary's path to determine if it's within the library folder,
     * making it work correctly even after app restarts when the in-memory map is empty.
     */
    @WorkerThread
    private void clearAutoLoadedDictionaries() {
        // Get the library folder URI
        String folderUriStr = AppPrefs.getAutoLoadDictFolderUri();
        if (folderUriStr.isEmpty()) {
            // No folder set, but clear the maps anyway
            autoLoadedDictionaries.clear();
            brokenDictionaries.clear();
            return;
        }
        
        // Find all dictionaries within the library folder
        List<SlobDescriptor> descriptorsToRemove = new ArrayList<>();
        for (int i = 0; i < dictionaries.size(); i++) {
            SlobDescriptor descriptor = dictionaries.get(i);
            if (descriptor != null && descriptor.path != null) {
                // Check if this dictionary's path is within the library folder
                if (isPathWithinFolder(descriptor.path, folderUriStr)) {
                    descriptorsToRemove.add(descriptor);
                }
            }
        }
        
        if (descriptorsToRemove.isEmpty()) {
            Log.d(TAG, "No dictionaries to remove from library folder");
            autoLoadedDictionaries.clear();
            brokenDictionaries.clear();
            return;
        }
        
        Log.d(TAG, "Found " + descriptorsToRemove.size() + " dictionaries to remove from library folder");
        
        // Batch removal to avoid RecyclerView inconsistency
        dictionaries.beginUpdate();
        try {
            for (SlobDescriptor descriptor : descriptorsToRemove) {
                int index = dictionaries.indexOf(descriptor);
                if (index >= 0) {
                    dictionaries.remove(index);
                    
                    // Clean up persisted data (StarDict extracted files, MDict cache, etc.)
                    final SlobDescriptor descriptorCopy = descriptor;
                    ThreadUtils.postOnBackgroundThread(() -> {
                        try {
                            descriptorCopy.cleanupPersistedData(context);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to clean up persisted data for " + descriptorCopy.path, e);
                        }
                    });
                    
                    Log.d(TAG, "Removed dictionary: " + descriptor.getLabel() + " (" + descriptor.path + ")");
                }
            }
        } finally {
            dictionaries.endUpdate(true);
        }
        
        autoLoadedDictionaries.clear();
        brokenDictionaries.clear();
        Log.d(TAG, "Cleared " + descriptorsToRemove.size() + " dictionaries from library folder");
    }
    
    /**
     * Checks if a dictionary path (URI) is within the given folder URI.
     * This handles both direct children and nested files within the folder.
     */
    private boolean isPathWithinFolder(@NonNull String dictPath, @NonNull String folderUriStr) {
        try {
            Uri dictUri = Uri.parse(dictPath);
            Uri folderUri = Uri.parse(folderUriStr);
            
            // For content:// URIs (SAF), check if the dictionary URI starts with the folder URI
            // This works because SAF URIs have a hierarchical structure
            String dictUriStr = dictUri.toString();
            
            // The folder URI typically looks like: content://com.android.externalstorage.documents/tree/primary%3ADownload%2FDictionaries
            // Dictionary URIs within it look like: content://com.android.externalstorage.documents/tree/primary%3ADownload%2FDictionaries/document/primary%3ADownload%2FDictionaries%2Fdict.mdx
            
            // Check if the dictionary URI contains the folder's tree ID
            if (folderUriStr.contains("/tree/")) {
                String treeId = folderUriStr.substring(folderUriStr.indexOf("/tree/") + 6);
                // Remove any trailing slashes or document segments
                if (treeId.contains("/document/")) {
                    treeId = treeId.substring(0, treeId.indexOf("/document/"));
                }
                
                // Check if the dictionary URI contains this tree ID
                return dictUriStr.contains(treeId);
            }
            
            // Fallback: simple prefix check
            return dictUriStr.startsWith(folderUriStr);
        } catch (Exception e) {
            Log.w(TAG, "Error checking if path is within folder: " + dictPath, e);
            return false;
        }
    }

    /**
     * Callback interface for loading state changes.
     */
    public interface LoadingCallback {
        void onLoadingChanged(boolean isLoading);
    }
    
    /**
     * Callback interface for detailed progress updates during scanning.
     */
    public interface ProgressCallback {
        /**
         * Called when scanning starts.
         */
        void onScanStarted();
        
        /**
         * Called when a dictionary is being loaded.
         * @param dictionaryName The name of the dictionary being loaded
         * @param current Current dictionary index (1-based)
         * @param total Total number of dictionaries to load
         */
        void onDictionaryLoading(String dictionaryName, int current, int total);
        
        /**
         * Called when scanning completes.
         * @param addedCount Number of dictionaries added
         * @param removedCount Number of dictionaries removed
         */
        void onScanCompleted(int addedCount, int removedCount);
    }
}
