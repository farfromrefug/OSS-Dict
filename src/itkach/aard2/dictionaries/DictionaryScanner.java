package itkach.aard2.dictionaries;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import itkach.aard2.descriptor.SlobDescriptor;

/**
 * Scans a folder for dictionary files and detects changes (additions, removals, breakage).
 * Designed to be extensible for supporting multiple dictionary formats.
 */
public class DictionaryScanner {
    private static final String TAG = "DictionaryScanner";

    /**
     * Represents a dictionary that may consist of multiple files.
     */
    public static class DictionaryFileSet {
        public final String id;  // Unique identifier based on main file
        public final String format;  // FORMAT_SLOB, FORMAT_MDICT, FORMAT_STARDICT, etc.
        public final List<DocumentFile> files;  // All files that make up this dictionary
        public final DocumentFile mainFile;  // The primary file to load (e.g., .ifo for StarDict, .mdx for MDict)
        public final boolean isComplete;  // True if all required files are present

        public DictionaryFileSet(String id, String format, DocumentFile mainFile, List<DocumentFile> files, boolean isComplete) {
            this.id = id;
            this.format = format;
            this.mainFile = mainFile;
            this.files = files;
            this.isComplete = isComplete;
        }
    }

    /**
     * Result of scanning a folder.
     */
    public static class ScanResult {
        public final List<DictionaryFileSet> dictionaries;
        public final Set<String> foundIds;  // IDs of all dictionaries found in the folder

        public ScanResult(List<DictionaryFileSet> dictionaries, Set<String> foundIds) {
            this.dictionaries = dictionaries;
            this.foundIds = foundIds;
        }
    }

    /**
     * Interface for dictionary format detectors. Allows easy extension for new formats.
     */
    public interface FormatDetector {
        /**
         * Returns true if this detector can handle the given file.
         */
        boolean canHandle(@NonNull DocumentFile file);

        /**
         * Attempts to build a complete dictionary file set from the given file.
         * Returns null if the file is part of a multi-file dictionary that's already been processed.
         */
        @Nullable
        DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile folder,
                                        @NonNull DocumentFile file, @NonNull Set<String> processedNames);

        /**
         * Returns the format identifier (e.g., FORMAT_SLOB, FORMAT_MDICT).
         */
        @NonNull
        String getFormat();
    }

    // List of all format detectors (extensible for future formats)
    private static final List<FormatDetector> DETECTORS = new ArrayList<>();

    static {
        DETECTORS.add(new SlobFormatDetector());
        DETECTORS.add(new MDictFormatDetector());
        DETECTORS.add(new StarDictFormatDetector());
        DETECTORS.add(new StarDictArchiveFormatDetector());
    }

    /**
     * Scans the given folder for dictionaries.
     */
    @WorkerThread
    @NonNull
    public static ScanResult scanFolder(@NonNull Context context, @NonNull Uri folderUri) {
        List<DictionaryFileSet> dictionaries = new ArrayList<>();
        Set<String> foundIds = new HashSet<>();
        Set<String> processedFileNames = new HashSet<>();

        DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
        if (folder == null || !folder.isDirectory()) {
            Log.w(TAG, "Invalid folder URI: " + folderUri);
            return new ScanResult(dictionaries, foundIds);
        }

        // Get all files in the folder
        DocumentFile[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            return new ScanResult(dictionaries, foundIds);
        }

        // Process each file with appropriate detector
        for (DocumentFile file : files) {
            if (file == null || !file.isFile()) continue;

            String name = file.getName();
            if (name == null || processedFileNames.contains(name)) continue;

            // Try each format detector
            for (FormatDetector detector : DETECTORS) {
                if (detector.canHandle(file)) {
                    DictionaryFileSet fileSet = detector.buildFileSet(context, folder, file, processedFileNames);
                    if (fileSet != null) {
                        dictionaries.add(fileSet);
                        foundIds.add(fileSet.id);
                        // Mark all files in this set as processed
                        for (DocumentFile f : fileSet.files) {
                            String fn = f.getName();
                            if (fn != null) processedFileNames.add(fn);
                        }
                        break;  // File handled, move to next
                    }
                }
            }
        }

        return new ScanResult(dictionaries, foundIds);
    }

    // -----------------------------------------------------------------------
    // Format Detectors
    // -----------------------------------------------------------------------

    /**
     * Detector for .slob files (single-file format).
     */
    private static class SlobFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".slob");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile folder,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_SLOB, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_SLOB;
        }
    }

    /**
     * Detector for .mdx files (may have companion .mdd resource files).
     */
    private static class MDictFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".mdx");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile folder,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);

            // Look for companion .mdd files (optional)
            String mdxName = file.getName();
            if (mdxName != null) {
                String baseName = mdxName.substring(0, mdxName.length() - 4);  // Remove .mdx
                DocumentFile[] folderFiles = folder.listFiles();
                if (folderFiles != null) {
                    for (DocumentFile f : folderFiles) {
                        if (f == null || !f.isFile()) continue;
                        String fn = f.getName();
                        if (fn != null && fn.toLowerCase().startsWith(baseName.toLowerCase()) &&
                                fn.toLowerCase().endsWith(".mdd")) {
                            files.add(f);
                        }
                    }
                }
            }

            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_MDICT, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_MDICT;
        }
    }

    /**
     * Detector for uncompressed StarDict files (.ifo + .idx + .dict or .dict.dz).
     */
    private static class StarDictFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".ifo");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile folder,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            String ifoName = file.getName();
            if (ifoName == null) return null;

            // Extract base name (without .ifo extension)
            String baseName = ifoName.substring(0, ifoName.length() - 4);
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);  // .ifo file

            // Look for required companion files
            DocumentFile[] folderFiles = folder.listFiles();
            boolean hasIdx = false;
            boolean hasDict = false;

            if (folderFiles != null) {
                for (DocumentFile f : folderFiles) {
                    if (f == null || !f.isFile()) continue;
                    String fn = f.getName();
                    if (fn == null) continue;

                    String fnLower = fn.toLowerCase();
                    // Check for .idx or .idx.gz
                    if ((fnLower.equals(baseName.toLowerCase() + ".idx") ||
                            fnLower.equals(baseName.toLowerCase() + ".idx.gz"))) {
                        files.add(f);
                        hasIdx = true;
                    }
                    // Check for .dict or .dict.dz
                    else if ((fnLower.equals(baseName.toLowerCase() + ".dict") ||
                            fnLower.equals(baseName.toLowerCase() + ".dict.dz"))) {
                        files.add(f);
                        hasDict = true;
                    }
                    // Optional .syn file
                    else if (fnLower.equals(baseName.toLowerCase() + ".syn")) {
                        files.add(f);
                    }
                }
            }

            boolean isComplete = hasIdx && hasDict;
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_STARDICT, file, files, isComplete);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_STARDICT;
        }
    }

    /**
     * Detector for StarDict .zip archives.
     */
    private static class StarDictArchiveFormatDetector implements FormatDetector {
        @Override
        public boolean canHandle(@NonNull DocumentFile file) {
            String name = file.getName();
            return name != null && name.toLowerCase().endsWith(".zip");
        }

        @Override
        @Nullable
        public DictionaryFileSet buildFileSet(@NonNull Context context, @NonNull DocumentFile folder,
                                               @NonNull DocumentFile file, @NonNull Set<String> processedNames) {
            // For .zip files, we can't easily inspect contents without extracting,
            // so we assume it's a valid StarDict archive if it's a .zip file
            List<DocumentFile> files = new ArrayList<>();
            files.add(file);
            String id = file.getUri().toString();
            return new DictionaryFileSet(id, SlobDescriptor.FORMAT_STARDICT_ARCHIVE, file, files, true);
        }

        @Override
        @NonNull
        public String getFormat() {
            return SlobDescriptor.FORMAT_STARDICT_ARCHIVE;
        }
    }

    /**
     * Generates a unique ID for a dictionary based on its file URIs.
     * This is stable across scans as long as the files don't move.
     */
    @NonNull
    public static String generateDictionaryId(@NonNull DictionaryFileSet fileSet) {
        return fileSet.mainFile.getUri().toString();
    }

    /**
     * Creates a mapping from dictionary IDs to their file URIs for tracking.
     */
    @NonNull
    public static Map<String, Uri> buildIdToUriMap(@NonNull List<DictionaryFileSet> fileSets) {
        Map<String, Uri> map = new HashMap<>();
        for (DictionaryFileSet fileSet : fileSets) {
            map.put(fileSet.id, fileSet.mainFile.getUri());
        }
        return map;
    }
}
