package itkach.aard2.dictionary.stardict;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.dictionary.DictionaryContent;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.slob.Slob;

/**
 * Reads StarDict dictionary files (.ifo + .idx + .dict or .dict.dz).
 *
 * <p>StarDict is the format used natively by GoldenDict and many other
 * open-source dictionary applications.  The format is well-specified at
 * https://github.com/huzheng001/stardict-3/blob/master/dict/doc/StarDictFileFormat</p>
 *
 * <p>This implementation supports:</p>
 * <ul>
 *   <li>v2.4.2 dictionaries (the modern standard)</li>
 *   <li>Uncompressed {@code .dict} content files</li>
 *   <li>GZIP-compressed {@code .dict.dz} content files</li>
 *   <li>GZIP-compressed {@code .idx.gz} index files</li>
 *   <li>Synonym files {@code .syn}</li>
 *   <li>HTML and plain-text content types</li>
 * </ul>
 */
public final class StarDictDictionary implements Dictionary {
    private static final String TAG = "StarDictDictionary";
    /** Buffer size used when copying stream data to temp files (64 KiB). */
    private static final int COPY_BUFFER_SIZE = 65536;

    // -----------------------------------------------------------------------
    // Key-index cache constants
    // -----------------------------------------------------------------------
    /** Magic header that identifies a StarDict key-index cache file. */
    private static final byte[] KEYS_CACHE_MAGIC =
            new byte[]{'S', 'D', 'K', 'E', 'Y', 'S', '\n', '\0'};
    /** Increment this whenever the cache binary format changes to auto-invalidate old files. */
    private static final int KEYS_CACHE_VERSION = 1;
    /** I/O buffer size for cache read/write (64 KiB). */
    private static final int KEYS_CACHE_BUFFER_SIZE = 65536;

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------
    @NonNull private final String id;
    @NonNull private final String basePath;   // path without extension
    @NonNull private final Map<String, String> tags;
    private final long wordCount;

    // -----------------------------------------------------------------------
    // Key index (in-memory for fast search)
    // -----------------------------------------------------------------------
    @NonNull private final List<String> keys;
    @NonNull private final int[]  offsets;  // offset into dict file
    @NonNull private final int[]  sizes;    // byte size in dict file

    // -----------------------------------------------------------------------
    // Content channel
    // -----------------------------------------------------------------------
    @Nullable private final FileChannel dictChannel;  // null if using .dict.dz
    @Nullable private final byte[] dictDzData;        // full decompressed content if .dict.dz

    // -----------------------------------------------------------------------
    // Resource lifetime management (fromIfoUri / fromArchiveUri paths)
    // -----------------------------------------------------------------------
    // These references prevent the underlying file descriptor or temp file from
    // being closed / deleted prematurely by the garbage collector.
    @SuppressWarnings("FieldCanBeLocal")
    @Nullable private FileInputStream dictFis;        // keeps dictChannel alive
    @SuppressWarnings("FieldCanBeLocal")
    @Nullable private ParcelFileDescriptor dictPfd;   // keeps dictFis alive for SAF URIs
    @Nullable private File tempDictFile;              // non-null when we own a temp file

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private StarDictDictionary(@NonNull String id,
                                @NonNull String basePath,
                                @NonNull Map<String, String> tags,
                                long wordCount,
                                @NonNull List<String> keys,
                                @NonNull int[] offsets,
                                @NonNull int[] sizes,
                                @Nullable FileChannel dictChannel,
                                @Nullable byte[] dictDzData) {
        this.id = id;
        this.basePath = basePath;
        this.tags = Collections.unmodifiableMap(tags);
        this.wordCount = wordCount;
        this.keys = keys;
        this.offsets = offsets;
        this.sizes = sizes;
        this.dictChannel = dictChannel;
        this.dictDzData = dictDzData;
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Pure-Java factory that builds a {@link StarDictDictionary} from already-
     * opened streams.  This is the testable, Android-free entry point.
     *
     * @param ifoTags   key/value pairs from the {@code .ifo} file (already parsed)
     * @param idxData   raw bytes of the {@code .idx} (or decompressed {@code .idx.gz})
     * @param dictChannel open {@link FileChannel} for the uncompressed {@code .dict}
     *                    file, OR {@code null} if {@code dictDzData} is provided
     * @param dictDzData  fully decompressed content of a {@code .dict.dz} file,
     *                    OR {@code null} if {@code dictChannel} is provided
     * @param basePath    file-system path without extension (used to derive the id)
     */
    @NonNull
    public static StarDictDictionary parse(@NonNull Map<String, String> ifoTags,
                                            @NonNull byte[] idxData,
                                            @Nullable FileChannel dictChannel,
                                            @Nullable byte[] dictDzData,
                                            @NonNull String basePath) throws IOException {
        long wordCount = Long.parseLong(ifoTags.getOrDefault("wordcount", "0"));
        Map<String, String> tags = buildTags(new HashMap<>(ifoTags), basePath);
        String id = deterministicUuid(basePath + ".ifo").toString();

        List<String> keys = new ArrayList<>((int) wordCount);
        List<Integer> offList = new ArrayList<>((int) wordCount);
        List<Integer> sizeList = new ArrayList<>((int) wordCount);
        parseIdx(idxData, keys, offList, sizeList);

        // Re-sort by QUATERNARY so that binary search with Slob.Strength works
        // regardless of the byte-order sort in the .idx file.
        if (keys.size() > 1) {
            final Slob.KeyComparator sortCmp = Slob.Strength.QUATERNARY.comparator;
            Integer[] sortedIdxs = new Integer[keys.size()];
            for (int i = 0; i < keys.size(); i++) sortedIdxs[i] = i;
            final String[] keyArr = keys.toArray(new String[0]);
            Arrays.sort(sortedIdxs, (a, b) -> sortCmp.compare(
                    new Slob.Keyed(keyArr[a]), new Slob.Keyed(keyArr[b])));
            final int[] rawOff  = offList.stream().mapToInt(Integer::intValue).toArray();
            final int[] rawSize = sizeList.stream().mapToInt(Integer::intValue).toArray();
            keys.clear();
            offList.clear();
            sizeList.clear();
            for (int j = 0; j < sortedIdxs.length; j++) {
                keys.add(keyArr[sortedIdxs[j]]);
                offList.add(rawOff[sortedIdxs[j]]);
                sizeList.add(rawSize[sortedIdxs[j]]);
            }
        }

        int[] offsets = offList.stream().mapToInt(Integer::intValue).toArray();
        int[] sizes   = sizeList.stream().mapToInt(Integer::intValue).toArray();

        if (dictDzData == null && dictChannel == null) {
            throw new IOException("Neither dictChannel nor dictDzData provided for " + basePath);
        }
        return new StarDictDictionary(id, basePath, tags, wordCount,
                keys, offsets, sizes, dictChannel, dictDzData);
    }

    /**
     * Opens a StarDict dictionary from a compressed archive (ZIP).
     *
     * <p>On the first call for a given archive, the contents are extracted to a
     * persistent sub-directory of {@code context.getFilesDir()} so that
     * subsequent calls load directly from the extracted files without touching
     * the ZIP again.  This makes all loads after the first as fast as opening a
     * plain StarDict directory.</p>
     *
     * <p>The .ifo and .idx files are read into memory (they are small). The
     * .dict content is kept on disk and accessed lazily via a
     * {@link FileChannel}.  If the archive contains a .dict.dz file, it is
     * decompressed during extraction so that no gzip processing is needed on
     * subsequent loads.</p>
     *
     * @param context Android context for content resolver
     * @param archiveUri URI of the archive file (.zip)
     * @param archivePath Display path of the archive (used to derive the
     *                    stable extraction directory name)
     * @return StarDictDictionary loaded from the archive (or extracted files)
     * @throws IOException if files cannot be read or required files are missing
     */
    @NonNull
    public static StarDictDictionary fromArchiveUri(@NonNull Context context,
                                                      @NonNull Uri archiveUri,
                                                      @NonNull String archivePath) throws IOException {
        // Use a stable directory name derived from the archive path so the
        // same archive always maps to the same extraction directory.
        // We mix several hash contributions to reduce collision probability.
        File baseDir = new File(context.getFilesDir(), "dicts/stardict");
        String dirName = Long.toHexString(stableHash64(archivePath) & Long.MAX_VALUE);
        File extractDir = new File(baseDir, dirName);
        // Key-index cache lives as a sibling file next to the extracted directory.
        File keysCache = new File(baseDir, dirName + ".keys");

        // Fast path: already extracted on a previous run.
        File ifoFile = findIfoFile(extractDir);
        if (ifoFile == null) {
            // Slow path (one-time only): extract all relevant files from the ZIP,
            // decompressing .dict.dz → .dict and .idx.gz → .idx on the fly.
            Log.i(TAG, "Extracting StarDict archive to " + extractDir);
            // mkdirs() may legitimately return false when the directory already
            // exists (e.g. a concurrent load): only fail if the dir is still absent.
            extractDir.mkdirs();
            if (!extractDir.isDirectory()) {
                throw new IOException("Cannot create extract directory: " + extractDir);
            }
            extractArchiveToDir(context, archiveUri, extractDir);
            ifoFile = findIfoFile(extractDir);
            // Unconditionally delete any existing key cache so the next load
            // re-parses the freshly extracted .idx rather than serving stale data.
            deleteSilently(keysCache);
        }

        if (ifoFile == null) {
            // Extraction produced no .ifo file – fall back to the streaming approach.
            Log.w(TAG, "No .ifo found after extraction of " + archivePath
                    + "; falling back to streaming");
            return fromArchiveUriStreaming(context, archiveUri, archivePath);
        }

        Log.d(TAG, "Loading extracted StarDict from " + ifoFile.getPath());
        return fromExtractedDir(extractDir, ifoFile, archivePath, keysCache);
    }

    /**
     * Extracts all StarDict-relevant files from a ZIP archive into {@code outDir}.
     * <ul>
     *   <li>.ifo and .idx are copied verbatim.</li>
     *   <li>.idx.gz is decompressed to .idx.</li>
     *   <li>.dict.dz is decompressed to .dict.</li>
     *   <li>.dict is copied verbatim.</li>
     * </ul>
     */
    static void extractArchiveToDir(@NonNull Context context,
                                     @NonNull Uri archiveUri,
                                     @NonNull File outDir) throws IOException {
        // Compute canonical form of outDir ONCE for ZipSlip protection.
        // Every extracted file's canonical path must start with this prefix.
        final String canonOutDir = outDir.getCanonicalPath() + File.separator;

        try (InputStream raw = context.getContentResolver().openInputStream(archiveUri)) {
            if (raw == null) throw new IOException("Cannot open archive URI: " + archiveUri);
            try (ZipInputStream zis = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        String name = entry.getName();
                        int lastSlash = name.lastIndexOf('/');
                        if (lastSlash >= 0) name = name.substring(lastSlash + 1);

                        // Guard against ZipSlip: verify the resolved output path
                        // stays inside outDir (handles ".", "..", encoded sequences, etc.).
                        if (name.isEmpty()) {
                            zis.closeEntry();
                            continue;
                        }
                        File outFile = new File(outDir, name);
                        if (!outFile.getCanonicalPath().startsWith(canonOutDir)) {
                            Log.w(TAG, "Skipping unsafe ZIP entry: " + entry.getName());
                            zis.closeEntry();
                            continue;
                        }

                        if (name.endsWith(".ifo") || name.endsWith(".idx")) {
                            copyStreamToFile(zis, outFile);
                        } else if (name.endsWith(".idx.gz")) {
                            // Decompress on-the-fly: "foo.idx.gz" → "foo.idx"
                            File out = new File(outDir, name.substring(0, name.length() - 3));
                            NonClosingInputStream ncs = new NonClosingInputStream(zis);
                            try (GZIPInputStream gzip = new GZIPInputStream(ncs)) {
                                copyStreamToFile(gzip, out);
                            }
                        } else if (name.endsWith(".dict.dz")) {
                            // Decompress on-the-fly: "foo.dict.dz" → "foo.dict"
                            File out = new File(outDir, name.substring(0, name.length() - 3));
                            NonClosingInputStream ncs = new NonClosingInputStream(zis);
                            try (GZIPInputStream gzip = new GZIPInputStream(ncs)) {
                                copyStreamToFile(gzip, out);
                            }
                        } else if (name.endsWith(".dict")) {
                            copyStreamToFile(zis, outFile);
                        }
                    }
                    zis.closeEntry();
                }
            }
        }
    }

    /**
     * Loads a StarDict dictionary from an already-extracted directory of plain
     * (uncompressed) StarDict files.  The {@code .dict} file is opened with a
     * seekable {@link FileChannel} so entry content is fetched lazily.
     *
     * <p>When {@code keysCache} is non-null the method first checks whether a
     * valid key-index cache exists on disk.  On a cache hit the expensive
     * {@code .idx} parse and QUATERNARY sort are skipped entirely.  On a cache
     * miss the parse runs as normal and the result is written to the cache for
     * the next launch.</p>
     */
    @NonNull
    private static StarDictDictionary fromExtractedDir(@NonNull File extractDir,
                                                         @NonNull File ifoFile,
                                                         @NonNull String archivePath,
                                                         @Nullable File keysCache)
            throws IOException {
        String baseName = ifoFile.getName();
        baseName = baseName.substring(0, baseName.length() - 4); // strip .ifo

        File idxFile = new File(extractDir, baseName + ".idx");
        if (!idxFile.exists()) {
            throw new IOException("Missing .idx after extraction: " + idxFile);
        }

        // Open the .dict file with a seekable FileChannel
        File dictFile = new File(extractDir, baseName + ".dict");
        if (!dictFile.exists()) {
            throw new IOException("Missing .dict after extraction: " + dictFile);
        }
        FileInputStream dictFis = new FileInputStream(dictFile);
        FileChannel dictChannel = dictFis.getChannel();

        // Use archivePath as the basePath so the dictionary ID is the same
        // whether we load from the persistent extracted dir or via streaming.
        String basePath = archivePath.endsWith(".ifo")
                ? archivePath.substring(0, archivePath.length() - 4)
                : archivePath;

        // ── Try to load the sorted key index from disk cache ─────────────────
        if (keysCache != null) {
            StarDictDictionary cached = tryLoadKeysFromCache(keysCache, idxFile, basePath, dictChannel);
            if (cached != null) {
                cached.dictFis = dictFis;
                return cached;
            }
        }

        // ── Cache miss: parse the .idx and sort (may be slow for large dicts) ─
        Map<String, String> ifoTags = readIfoFile(ifoFile);
        byte[] idxData;
        try (FileInputStream fis = new FileInputStream(idxFile)) {
            idxData = readAll(fis);
        }

        StarDictDictionary result = parse(ifoTags, idxData, dictChannel, null, basePath);
        result.dictFis = dictFis;

        // ── Persist the sorted key index so the next load is fast ────────────
        if (keysCache != null) {
            saveKeysToCache(keysCache, idxFile, result);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Streaming fallback for fromArchiveUri (no Context.getFilesDir available
    // or extraction to persistent dir failed)
    // -----------------------------------------------------------------------

    /**
     * Streaming fallback: opens a StarDict dictionary from a ZIP archive by
     * extracting the .dict body to a temporary file (lives only for the
     * current process lifetime).  Used when persistent extraction fails.
     */
    @NonNull
    private static StarDictDictionary fromArchiveUriStreaming(@NonNull Context context,
                                                               @NonNull Uri archiveUri,
                                                               @NonNull String archivePath) throws IOException {
        // Small files (.ifo, .idx, .idx.gz) are held in memory.
        // The .dict or .dict.dz content is streamed to a temp file to avoid
        // loading the entire dictionary body into RAM.
        Map<String, byte[]> smallFiles = new HashMap<>();
        File tempDictFile = null;

        try (InputStream is = context.getContentResolver().openInputStream(archiveUri)) {
            if (is == null) {
                throw new IOException("Cannot open archive: " + archivePath);
            }

            try (ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        String name = entry.getName();
                        // Strip any directory prefix – we only care about the leaf name.
                        int lastSlash = name.lastIndexOf('/');
                        if (lastSlash >= 0) {
                            name = name.substring(lastSlash + 1);
                        }

                        if (name.endsWith(".ifo") || name.endsWith(".idx")
                                || name.endsWith(".idx.gz")) {
                            byte[] data = readAll(zis);
                            smallFiles.put(name, data);
                            Log.d(TAG, "Read into memory: " + name + " (" + data.length + " bytes)");
                        } else if (name.endsWith(".dict.dz")) {
                            NonClosingInputStream nonClosingStream = new NonClosingInputStream(zis);
                            try (GZIPInputStream gzip = new GZIPInputStream(nonClosingStream)) {
                                tempDictFile = extractToTempFile(context, gzip, "stardict_dict_");
                            }
                            Log.d(TAG, "Extracted .dict.dz to temp file: " + tempDictFile.getPath());
                        } else if (name.endsWith(".dict")) {
                            NonClosingInputStream nonClosingStream = new NonClosingInputStream(zis);
                            tempDictFile = extractToTempFile(context, nonClosingStream, "stardict_dict_");
                            Log.d(TAG, "Extracted .dict to temp file: " + tempDictFile.getPath());
                        }
                    }
                    zis.closeEntry();
                }
            }
        }

        // Find the .ifo file
        String ifoFileName = null;
        byte[] ifoData = null;
        for (Map.Entry<String, byte[]> entry : smallFiles.entrySet()) {
            if (entry.getKey().endsWith(".ifo")) {
                ifoFileName = entry.getKey();
                ifoData = entry.getValue();
                break;
            }
        }

        if (ifoData == null) {
            deleteSilently(tempDictFile);
            throw new IOException("No .ifo file found in archive: " + archivePath);
        }

        // Parse .ifo content
        Map<String, String> ifoTags = new HashMap<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(ifoData), StandardCharsets.UTF_8))) {
            String magic = br.readLine();
            if (!"StarDict's dict ifo file".equals(magic)) {
                deleteSilently(tempDictFile);
                throw new IOException("Not a StarDict .ifo file in archive: " + archivePath);
            }
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    ifoTags.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }

        // Get base name (without .ifo extension)
        String baseName = ifoFileName.substring(0, ifoFileName.length() - 4);

        // Find and decompress index file (kept in memory – it is much smaller than the dict)
        byte[] idxData = null;
        String idxGzName = baseName + ".idx.gz";
        String idxName = baseName + ".idx";

        if (smallFiles.containsKey(idxGzName)) {
            byte[] compressedIdx = smallFiles.get(idxGzName);
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new java.io.ByteArrayInputStream(compressedIdx))) {
                idxData = readAll(gzip);
            }
        } else if (smallFiles.containsKey(idxName)) {
            idxData = smallFiles.get(idxName);
        }

        if (idxData == null) {
            deleteSilently(tempDictFile);
            throw new IOException("No .idx or .idx.gz file found in archive for: " + baseName);
        }

        if (tempDictFile == null) {
            throw new IOException("No .dict or .dict.dz file found in archive for: " + baseName);
        }

        // Open a FileChannel to the temp file for lazy random-access reads.
        FileInputStream dictFis = new FileInputStream(tempDictFile);
        FileChannel dictChannel = dictFis.getChannel();

        StarDictDictionary result = parse(ifoTags, idxData, dictChannel, null, archivePath);
        result.dictFis      = dictFis;
        result.tempDictFile = tempDictFile;
        return result;
    }

    /**
     * Opens a StarDict dictionary from its {@code .ifo} file URI.  The
     * companion {@code .idx} and {@code .dict} (or {@code .dict.dz}) files
     * must reside in the same directory with the same base name.
     */
    @NonNull
    public static StarDictDictionary fromIfoUri(@NonNull Context context,
                                                 @NonNull Uri ifoUri,
                                                 @NonNull String ifoPath) throws IOException {
        // ── .ifo ──────────────────────────────────────────────────────────
        Map<String, String> ifoTags = new HashMap<>();
        try (InputStream is = context.getContentResolver().openInputStream(ifoUri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String magic = br.readLine();
            if (!"StarDict's dict ifo file".equals(magic)) {
                throw new IOException("Not a StarDict .ifo file: " + ifoPath);
            }
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    ifoTags.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }

        String basePath = ifoPath.endsWith(".ifo")
                ? ifoPath.substring(0, ifoPath.length() - 4) : ifoPath;

        // ── .idx (.idx.gz) ────────────────────────────────────────────────
        // Try .idx.gz first (gzip-compressed index), then fall back to plain .idx
        Uri idxUri = findCompanionFile(context, ifoUri, ifoPath, new String[]{".idx.gz", ".idx"});
        byte[] idxData = null;

        if (idxUri != null) {
            try (InputStream is = context.getContentResolver().openInputStream(idxUri)) {
                if (idxUri.toString().endsWith(".gz")) {
                    try (GZIPInputStream gzip = new GZIPInputStream(is)) {
                        idxData = readAll(gzip);
                    }
                } else {
                    idxData = readAll(is);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read .idx file for " + basePath, e);
            }
        }

        if (idxData == null) {
            throw new IOException("No .idx or .idx.gz file found for " + basePath);
        }

        // ── .dict or .dict.dz ─────────────────────────────────────────────
        // Try .dict.dz first (gzip-compressed), then fall back to plain .dict.
        // Neither the compressed bytes nor the decompressed bytes are held in
        // memory: .dict.dz is decompressed to a temp file; plain .dict is opened
        // via a seekable FileChannel so only individual entries are read on demand.
        Uri dictUri = findCompanionFile(context, ifoUri, ifoPath, new String[]{".dict.dz", ".dict"});
        FileChannel dictChannel = null;
        FileInputStream dictFis = null;
        ParcelFileDescriptor dictPfd = null;
        File tempDictFile = null;

        if (dictUri != null) {
            try {
                if (dictUri.toString().endsWith(".dz") || dictUri.toString().endsWith(".gz")) {
                    // Compressed .dict.dz: decompress to a temp file so individual
                    // entries can be fetched with random seeks, without keeping the
                    // decompressed content in RAM.
                    try (InputStream rawIs = context.getContentResolver().openInputStream(dictUri);
                         GZIPInputStream gzip = new GZIPInputStream(rawIs)) {
                        tempDictFile = extractToTempFile(context, gzip, "stardict_dictdz_");
                    }
                    dictFis = new FileInputStream(tempDictFile);
                    dictChannel = dictFis.getChannel();
                } else {
                    // Uncompressed .dict: try to obtain a seekable FileChannel.
                    InputStream is = context.getContentResolver().openInputStream(dictUri);
                    if (is instanceof FileInputStream) {
                        dictFis = (FileInputStream) is;
                        dictChannel = dictFis.getChannel();
                    } else {
                        // SAF content:// URI – openInputStream does not return a
                        // FileInputStream, but openFileDescriptor gives a real fd.
                        is.close();
                        try {
                            dictPfd = context.getContentResolver().openFileDescriptor(dictUri, "r");
                            if (dictPfd != null) {
                                dictFis = new FileInputStream(dictPfd.getFileDescriptor());
                                dictChannel = dictFis.getChannel();
                            }
                        } catch (Exception e2) {
                            Log.w(TAG, "openFileDescriptor failed for " + basePath + ", copying to temp file", e2);
                        }
                        if (dictChannel == null) {
                            // Last resort: copy the dict to a temp file.
                            try (InputStream is2 = context.getContentResolver().openInputStream(dictUri)) {
                                tempDictFile = extractToTempFile(context, is2, "stardict_dict_");
                            }
                            dictFis = new FileInputStream(tempDictFile);
                            dictChannel = dictFis.getChannel();
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not open .dict file for " + basePath, e);
            }
        }

        StarDictDictionary result = parse(ifoTags, idxData, dictChannel, null, basePath);
        result.dictFis      = dictFis;
        result.dictPfd      = dictPfd;
        result.tempDictFile = tempDictFile;
        return result;
    }

    // -----------------------------------------------------------------------
    // Dictionary interface
    // -----------------------------------------------------------------------

    @Override @NonNull public String getId()    { return id; }
    @Override @NonNull public String getLabel() {
        String b = tags.get("label");
        return (b != null && !b.isEmpty()) ? b : shortName(basePath);
    }
    @Override @NonNull public String getUri() {
        String uri = tags.get("uri");
        return (uri != null && !uri.isEmpty()) ? uri : ("stardict:" + id);
    }
    @Override @NonNull public Map<String, String> getTags() { return tags; }
    @Override public long getBlobCount() { return wordCount; }
    @Override public int size()          { return keys.size(); }

    @Override
    @Nullable
    public DictionaryEntry get(int i) {
        if (i < 0 || i >= keys.size()) return null;
        return new DictionaryEntry(this, String.valueOf(i), keys.get(i), null);
    }

    @Override
    @NonNull
    public Iterator<DictionaryEntry> find(@NonNull String key, @NonNull Slob.Strength strength) {
        final int startIdx = findStartIndex(key, strength);
        final Slob.KeyComparator stopCmp = strength.stopComparator;
        final Slob.Keyed lookupKeyed = new Slob.Keyed(key);

        return new Iterator<DictionaryEntry>() {
            int idx = startIdx;
            DictionaryEntry next = advance();

            private DictionaryEntry advance() {
                while (idx < keys.size()) {
                    String candidate = keys.get(idx);
                    if (stopCmp.compare(new Slob.Keyed(candidate), lookupKeyed) != 0) return null;
                    int cur = idx++;
                    return new DictionaryEntry(StarDictDictionary.this,
                            String.valueOf(cur), candidate, null);
                }
                return null;
            }

            @Override public boolean hasNext() { return next != null; }
            @Override public DictionaryEntry next() {
                if (next == null) throw new NoSuchElementException();
                DictionaryEntry r = next;
                next = advance();
                return r;
            }
        };
    }

    @Override
    @Nullable
    public DictionaryContent getContent(@NonNull String blobId) {
        try {
            int idx = Integer.parseInt(blobId);
            if (idx < 0 || idx >= keys.size()) return null;
            byte[] data = readDictData(offsets[idx], sizes[idx]);
            if (data == null) return null;
            // Detect content type: StarDict entries with type-sequence 'h' are HTML
            String sametypesequence = tags.getOrDefault("sametypesequence", "m");
            String contentType = sametypesequence.contains("h")
                    ? "text/html; charset=utf-8"
                    : "text/plain; charset=utf-8";
            return new DictionaryContent(contentType, ByteBuffer.wrap(data));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    @NonNull
    public String getContentType(@NonNull String blobId) {
        String sametypesequence = tags.getOrDefault("sametypesequence", "m");
        return sametypesequence.contains("h")
                ? "text/html; charset=utf-8"
                : "text/plain; charset=utf-8";
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private int findStartIndex(@NonNull String key, @NonNull Slob.Strength strength) {
        Slob.KeyComparator cmp = strength.comparator;
        Slob.Keyed lookupKeyed = new Slob.Keyed(key);
        int lo = 0, hi = keys.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            int c = cmp.compare(new Slob.Keyed(keys.get(mid)), lookupKeyed);
            if (c < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    @Nullable
    private byte[] readDictData(int offset, int size) {
        if (dictDzData != null) {
            if (offset + size > dictDzData.length) return null;
            byte[] data = new byte[size];
            System.arraycopy(dictDzData, offset, data, 0, size);
            return data;
        }
        if (dictChannel != null) {
            try {
                ByteBuffer buf = ByteBuffer.allocate(size);
                int total = 0;
                while (buf.hasRemaining()) {
                    int n = dictChannel.read(buf, offset + total);
                    if (n < 0) break;
                    total += n;
                }
                return buf.array();
            } catch (IOException e) {
                Log.e(TAG, "Failed to read dict data", e);
            }
        }
        return null;
    }

    /** Parses the binary .idx file into parallel lists of (key, offset, size). */
    private static void parseIdx(@NonNull byte[] idxData,
                                   @NonNull List<String> keys,
                                   @NonNull List<Integer> offsets,
                                   @NonNull List<Integer> sizes) {
        int pos = 0;
        while (pos < idxData.length) {
            // null-terminated UTF-8 key
            int nullPos = pos;
            while (nullPos < idxData.length && idxData[nullPos] != 0) nullPos++;
            String key = new String(idxData, pos, nullPos - pos, StandardCharsets.UTF_8);
            pos = nullPos + 1; // skip null
            if (pos + 8 > idxData.length) break;
            // 4-byte big-endian offset + 4-byte big-endian size
            int offset = ((idxData[pos] & 0xFF) << 24)
                    | ((idxData[pos + 1] & 0xFF) << 16)
                    | ((idxData[pos + 2] & 0xFF) << 8)
                    | (idxData[pos + 3] & 0xFF);
            int size = ((idxData[pos + 4] & 0xFF) << 24)
                    | ((idxData[pos + 5] & 0xFF) << 16)
                    | ((idxData[pos + 6] & 0xFF) << 8)
                    | (idxData[pos + 7] & 0xFF);
            pos += 8;
            keys.add(key);
            offsets.add(offset);
            sizes.add(size);
        }
    }

    @NonNull
    private static Map<String, String> buildTags(@NonNull Map<String, String> ifo,
                                                   @NonNull String basePath) {
        Map<String, String> tags = new HashMap<>(ifo);
        remapTag(tags, "bookname",    "label");
        remapTag(tags, "website",     "uri");
        remapTag(tags, "description", "description");
        return tags;
    }

    private static void remapTag(@NonNull Map<String, String> tags,
                                   @NonNull String from, @NonNull String to) {
        if (!from.equals(to)) {
            String v = tags.remove(from);
            if (v != null && !v.isEmpty()) tags.put(to, v);
        }
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Copies all bytes from {@code in} to {@code outFile}, creating or
     * overwriting the file.  Partial output is deleted on error.
     */
    private static void copyStreamToFile(@NonNull InputStream in,
                                          @NonNull File outFile) throws IOException {
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[COPY_BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            deleteSilently(outFile);
            throw e;
        }
    }

    /** Returns the first {@code .ifo} file found in {@code dir}, or {@code null}. */
    @Nullable
    private static File findIfoFile(@NonNull File dir) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().endsWith(".ifo")) return f;
        }
        return null;
    }

    /**
     * Reads and parses a {@code .ifo} file from disk, returning the key/value
     * tag pairs it contains.
     */
    @NonNull
    private static Map<String, String> readIfoFile(@NonNull File ifoFile) throws IOException {
        Map<String, String> tags = new HashMap<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(ifoFile), StandardCharsets.UTF_8))) {
            String magic = br.readLine();
            if (!"StarDict's dict ifo file".equals(magic)) {
                throw new IOException("Not a StarDict .ifo file: " + ifoFile);
            }
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    tags.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }
        return tags;
    }

    // -----------------------------------------------------------------------
    // Key-index cache helpers
    // -----------------------------------------------------------------------

    /**
     * Attempts to deserialize the sorted key index from a previously saved cache file.
     *
     * <p>The cache is validated against the current {@code .idx} file size.  Any mismatch
     * (stale cache, truncated write, format version change) causes a {@code null} return so
     * the caller falls back to full parsing.</p>
     *
     * @param cacheFile the cache file to read from
     * @param idxFile   the live {@code .idx} file – used for the fingerprint check
     * @param basePath  the dictionary's base path (used when constructing the instance)
     * @param dictChannel open {@link FileChannel} for the {@code .dict} file
     * @return a ready-to-use {@link StarDictDictionary}, or {@code null} on cache miss/error
     */
    @Nullable
    private static StarDictDictionary tryLoadKeysFromCache(@NonNull File cacheFile,
                                                             @NonNull File idxFile,
                                                             @NonNull String basePath,
                                                             @NonNull FileChannel dictChannel) {
        if (!cacheFile.exists()) return null;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cacheFile), KEYS_CACHE_BUFFER_SIZE))) {
            // Validate magic
            byte[] magic = new byte[KEYS_CACHE_MAGIC.length];
            dis.readFully(magic);
            if (!Arrays.equals(magic, KEYS_CACHE_MAGIC)) {
                Log.w(TAG, "Key cache magic mismatch: " + cacheFile);
                return null;
            }
            // Validate version
            if ((dis.readByte() & 0xFF) != KEYS_CACHE_VERSION) {
                Log.d(TAG, "Key cache version mismatch: " + cacheFile);
                return null;
            }
            // Validate fingerprint (idx file size)
            long cachedIdxSize = dis.readLong();
            if (cachedIdxSize != idxFile.length()) {
                Log.d(TAG, "Key cache stale (idx size changed): " + cacheFile);
                return null;
            }
            // Read id and tags
            String id = dis.readUTF();
            int tagCount = dis.readShort() & 0xFFFF;
            Map<String, String> tags = new HashMap<>(tagCount);
            for (int i = 0; i < tagCount; i++) {
                tags.put(dis.readUTF(), dis.readUTF());
            }
            long wordCount = dis.readLong();
            // Read sorted keys, offsets, sizes
            int keyCount = dis.readInt();
            List<String> keys = new ArrayList<>(keyCount);
            for (int i = 0; i < keyCount; i++) keys.add(dis.readUTF());
            int[] offsets = new int[keyCount];
            int[] sizes   = new int[keyCount];
            for (int i = 0; i < keyCount; i++) offsets[i] = dis.readInt();
            for (int i = 0; i < keyCount; i++) sizes[i]   = dis.readInt();

            Log.d(TAG, "Loaded key cache for " + basePath + " (" + keyCount + " keys)");
            return new StarDictDictionary(id, basePath, tags, wordCount,
                    keys, offsets, sizes, dictChannel, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read key cache " + cacheFile + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Serializes the sorted key index of {@code dict} to {@code cacheFile} using an atomic
     * write (temp-file + rename) so that a crash during the write cannot leave a corrupt cache.
     */
    private static void saveKeysToCache(@NonNull File cacheFile,
                                         @NonNull File idxFile,
                                         @NonNull StarDictDictionary dict) {
        File parent = cacheFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) {
            if (!parent.mkdirs() && !parent.isDirectory()) {
                Log.w(TAG, "Could not create key cache directory: " + parent);
                return;
            }
        }
        File tmp = new File(cacheFile.getParent(), cacheFile.getName() + ".tmp");
        try {
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp), KEYS_CACHE_BUFFER_SIZE))) {
                dos.write(KEYS_CACHE_MAGIC);
                dos.writeByte(KEYS_CACHE_VERSION);
                dos.writeLong(idxFile.length());          // fingerprint
                dos.writeUTF(dict.id);
                Map<String, String> tags = dict.tags;     // unmodifiable view
                dos.writeShort(tags.size());
                for (Map.Entry<String, String> e : tags.entrySet()) {
                    dos.writeUTF(e.getKey());
                    dos.writeUTF(e.getValue());
                }
                dos.writeLong(dict.wordCount);
                dos.writeInt(dict.keys.size());
                for (String k : dict.keys)   dos.writeUTF(k);
                for (int off : dict.offsets) dos.writeInt(off);
                for (int sz  : dict.sizes)   dos.writeInt(sz);
            }
            if (!tmp.renameTo(cacheFile)) {
                Log.w(TAG, "Could not rename key cache tmp file to " + cacheFile);
                deleteSilently(tmp);
            } else {
                Log.d(TAG, "Saved key cache for " + dict.basePath
                        + " (" + dict.keys.size() + " keys)");
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to write key cache " + cacheFile + ": " + e.getMessage());
            deleteSilently(tmp);
        }
    }

    /**
     * Returns a stable 64-bit hash of {@code s} using a simple polynomial
     * rolling hash.  More collision-resistant than {@code String.hashCode()}
     * for use as a directory/file name.
     */
    private static long stableHash64(@NonNull String s) {
        long h = 0xcbf29ce484222325L; // FNV-1a offset basis (adapted)
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;      // FNV prime
        }
        return h;
    }

    /**
     * Copies all bytes from {@code in} into a new temporary file inside the
     * app's cache directory and returns a reference to that file.
     *
     * <p>The caller is responsible for deleting the file when it is no longer
     * needed (e.g. by calling {@link #deleteSilently(File)} in the owning
     * dictionary's cleanup path, or by clearing the app's cache).</p>
     *
     * @param context Android context used to locate the cache directory
     * @param in      source stream; the caller is responsible for closing it
     * @param prefix  prefix for the temp-file name
     */
    @NonNull
    private static File extractToTempFile(@NonNull Context context,
                                           @NonNull InputStream in,
                                           @NonNull String prefix) throws IOException {
        File tempFile = File.createTempFile(prefix, ".tmp", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            byte[] buf = new byte[COPY_BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            deleteSilently(tempFile);
            throw e;
        }
        return tempFile;
    }

    /** Deletes {@code f} if it is non-null, logging a warning if deletion fails. */
    private static void deleteSilently(@Nullable File f) {
        if (f != null && !f.delete()) {
            Log.w(TAG, "Could not delete temp dict file: " + f.getPath());
        }
    }

    /**
     * Wraps an {@link InputStream} so that {@link #close()} does <em>not</em>
     * propagate to the underlying stream.  This is required when we wrap a
     * {@link ZipInputStream} entry with a {@link GZIPInputStream}: closing
     * the GZIPInputStream must not close the ZipInputStream itself.
     */
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream in) { super(in); }
        @Override public void close() {
            /* Intentionally do not close the wrapped stream. This prevents
             * a GZIPInputStream from closing the parent ZipInputStream when
             * decompressing nested .dict.dz entries from a ZIP archive. */
        }
    }

    /**
     * Finds a companion file (e.g., .idx, .dict) using SAF-compatible file discovery.
     * Works with both content:// URIs (SAF) and file:// URIs.
     *
     * @param context Android context for content resolver
     * @param ifoUri  URI of the .ifo file
     * @param ifoPath Path string of the .ifo file (for fallback)
     * @param extensions Extensions to try, in order of preference (e.g., [".idx.gz", ".idx"])
     * @return URI of the found companion file, or null if not found
     */
    @Nullable
    private static Uri findCompanionFile(@NonNull Context context,
                                          @NonNull Uri ifoUri,
                                          @NonNull String ifoPath,
                                          @NonNull String[] extensions) {
        // For SAF content:// URIs, use DocumentFile API
        if ("content".equals(ifoUri.getScheme())) {
            DocumentFile ifoFile = DocumentFile.fromSingleUri(context, ifoUri);
            if (ifoFile == null || !ifoFile.exists()) {
                return null;
            }
            
            DocumentFile parentDir = ifoFile.getParentFile();
            if (parentDir == null || !parentDir.isDirectory()) {
                return null;
            }
            
            // Get base name without extension
            String ifoName = ifoFile.getName();
            if (ifoName == null || !ifoName.endsWith(".ifo")) {
                return null;
            }
            String baseName = ifoName.substring(0, ifoName.length() - 4);
            
            // Search for companion files
            DocumentFile[] files = parentDir.listFiles();
            for (String ext : extensions) {
                String targetName = baseName + ext;
                for (DocumentFile file : files) {
                    if (targetName.equals(file.getName())) {
                        return file.getUri();
                    }
                }
            }
            return null;
        }
        
        // For file:// URIs or other schemes, use simple string manipulation (legacy)
        for (String ext : extensions) {
            Uri uri = deriveUri(ifoUri, ".ifo", ext);
            try {
                InputStream test = context.getContentResolver().openInputStream(uri);
                if (test != null) {
                    test.close();
                    return uri;
                }
            } catch (Exception ignored) {
                // File doesn't exist, try next extension
            }
        }
        return null;
    }

    /** Derives a companion file URI by replacing the extension. Used for non-SAF URIs. */
    @NonNull
    private static Uri deriveUri(@NonNull Uri ifoUri,
                                   @NonNull String fromExt,
                                   @NonNull String toExt) {
        String uriStr = ifoUri.toString();
        if (uriStr.endsWith(fromExt)) {
            uriStr = uriStr.substring(0, uriStr.length() - fromExt.length()) + toExt;
        }
        return Uri.parse(uriStr);
    }

    @NonNull
    private static String shortName(@NonNull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    @NonNull
    /**
     * Builds a simplified deterministic UUID from the file path.
     * Not a standards-compliant version-5 UUID (no SHA-1 over a namespace);
     * the RFC 4122 version (5) and variant (10xx) bits are applied purely for
     * well-formed UUID string output.
     */
    private static UUID deterministicUuid(@NonNull String path) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        long most = 0, least = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (i < 8) most  = (most  << 8) | (bytes[i] & 0xFF);
            else       least = (least << 8) | (bytes[i % 8] & 0xFF);
        }
        most  ^= path.hashCode();
        least ^= path.hashCode() * 0x9e3779b97f4a7c15L;
        // Apply RFC 4122 version (5) and variant (10xx) bits
        most  = (most  & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000005000L;
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}
