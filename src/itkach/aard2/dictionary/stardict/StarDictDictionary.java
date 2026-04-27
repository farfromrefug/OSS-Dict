package itkach.aard2.dictionary.stardict;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
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
     * Opens a StarDict dictionary from a compressed archive (ZIP or GZ).
     * The archive must contain .ifo, .idx (or .idx.gz), and .dict (or .dict.dz) files
     * with the same base name.
     *
     * @param context Android context for content resolver
     * @param archiveUri URI of the archive file (.zip, .gz, or .tgz)
     * @param archivePath Display path of the archive
     * @return StarDictDictionary loaded from the archive
     * @throws IOException if files cannot be read or required files are missing
     */
    @NonNull
    public static StarDictDictionary fromArchiveUri(@NonNull Context context,
                                                      @NonNull Uri archiveUri,
                                                      @NonNull String archivePath) throws IOException {
        Map<String, byte[]> archiveFiles = new HashMap<>();
        
        try (InputStream is = context.getContentResolver().openInputStream(archiveUri)) {
            if (is == null) {
                throw new IOException("Cannot open archive: " + archivePath);
            }
            
            // Assume ZIP format (.zip)
            try (ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        String name = entry.getName();
                        // Extract just the filename, not the full path
                        int lastSlash = name.lastIndexOf('/');
                        if (lastSlash >= 0) {
                            name = name.substring(lastSlash + 1);
                        }
                        
                        // Only store relevant StarDict files
                        if (name.endsWith(".ifo") || name.endsWith(".idx") || 
                            name.endsWith(".idx.gz") || name.endsWith(".dict") ||
                            name.endsWith(".dict.dz")) {
                            byte[] data = readAll(zis);
                            archiveFiles.put(name, data);
                            Log.d(TAG, "Extracted from archive: " + name + " (" + data.length + " bytes)");
                        }
                    }
                    zis.closeEntry();
                }
            }
        }
        
        // Find the .ifo file
        String ifoFileName = null;
        byte[] ifoData = null;
        for (Map.Entry<String, byte[]> entry : archiveFiles.entrySet()) {
            if (entry.getKey().endsWith(".ifo")) {
                ifoFileName = entry.getKey();
                ifoData = entry.getValue();
                break;
            }
        }
        
        if (ifoData == null) {
            throw new IOException("No .ifo file found in archive: " + archivePath);
        }
        
        // Parse .ifo content
        Map<String, String> ifoTags = new HashMap<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(ifoData), StandardCharsets.UTF_8))) {
            String magic = br.readLine();
            if (!"StarDict's dict ifo file".equals(magic)) {
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
        
        // Find and decompress index file
        byte[] idxData = null;
        String idxGzName = baseName + ".idx.gz";
        String idxName = baseName + ".idx";
        
        if (archiveFiles.containsKey(idxGzName)) {
            byte[] compressedIdx = archiveFiles.get(idxGzName);
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new java.io.ByteArrayInputStream(compressedIdx))) {
                idxData = readAll(gzip);
            }
        } else if (archiveFiles.containsKey(idxName)) {
            idxData = archiveFiles.get(idxName);
        }
        
        if (idxData == null) {
            throw new IOException("No .idx or .idx.gz file found in archive for: " + baseName);
        }
        
        // Find and potentially decompress dict file
        byte[] dictData = null;
        String dictDzName = baseName + ".dict.dz";
        String dictName = baseName + ".dict";
        
        if (archiveFiles.containsKey(dictDzName)) {
            byte[] compressedDict = archiveFiles.get(dictDzName);
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new java.io.ByteArrayInputStream(compressedDict))) {
                dictData = readAll(gzip);
            }
        } else if (archiveFiles.containsKey(dictName)) {
            dictData = archiveFiles.get(dictName);
        }
        
        if (dictData == null) {
            throw new IOException("No .dict or .dict.dz file found in archive for: " + baseName);
        }
        
        // Create dictionary from in-memory data
        // Since we have everything in memory, pass null for dictChannel
        return parse(ifoTags, idxData, null, dictData, archivePath);
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
        // Try .dict.dz first (gzip-compressed), then fall back to plain .dict
        Uri dictUri = findCompanionFile(context, ifoUri, ifoPath, new String[]{".dict.dz", ".dict"});
        FileChannel dictChannel = null;
        byte[] dictDzData = null;

        if (dictUri != null) {
            try {
                InputStream is = context.getContentResolver().openInputStream(dictUri);
                if (dictUri.toString().endsWith(".dz") || dictUri.toString().endsWith(".gz")) {
                    // Compressed dict file - decompress into memory
                    try (GZIPInputStream gzip = new GZIPInputStream(is)) {
                        dictDzData = readAll(gzip);
                    }
                } else {
                    // Uncompressed dict file - use FileChannel for efficiency
                    if (is instanceof FileInputStream) {
                        dictChannel = ((FileInputStream) is).getChannel();
                    } else {
                        // For SAF URIs, we might not get a FileInputStream
                        // Fall back to reading into memory
                        dictDzData = readAll(is);
                        is.close();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not open .dict file for " + basePath, e);
            }
        }

        return parse(ifoTags, idxData, dictChannel, dictDzData, basePath);
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
