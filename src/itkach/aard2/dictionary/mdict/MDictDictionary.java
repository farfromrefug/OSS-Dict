package itkach.aard2.dictionary.mdict;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
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
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.dictionary.DictionaryContent;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.slob.Slob;

/**
 * Reads MDict MDX dictionary files.
 *
 * <p>Supported format versions: 2.0 (the most common modern version). Each
 * entry's HTML content can optionally reference resources (images, CSS, etc.)
 * that are stored in a companion {@code .mdd} file; those resources are served
 * via the same {@link Dictionary} interface using the resource path as the
 * key.</p>
 *
 * <p>File format reference:
 * https://github.com/zhansliu/writemdict/blob/master/fileformat.md</p>
 */
public final class MDictDictionary implements Dictionary {
    private static final String TAG = "MDictDictionary";

    private static final int COMP_NONE = 0;
    private static final int COMP_LZO  = 1;
    private static final int COMP_ZLIB = 2;

    // -----------------------------------------------------------------------
    // Index cache constants
    // -----------------------------------------------------------------------
    /** Magic header bytes for the binary index cache file. */
    private static final byte[] CACHE_MAGIC =
            new byte[]{'M', 'D', 'X', 'I', 'D', 'X', '\n', '\0'};
    /** Increment when the cache format changes to invalidate old caches. */
    private static final int CACHE_VERSION = 1;
    /** Buffer size for cache I/O (64 KiB). */
    private static final int CACHE_BUFFER_SIZE = 65536;

    // -----------------------------------------------------------------------
    // Persisted / derived state
    // -----------------------------------------------------------------------
    @NonNull private final String id;
    @NonNull private final String filePath;
    @NonNull private final Map<String, String> tags;

    // -----------------------------------------------------------------------
    // In-memory index
    // -----------------------------------------------------------------------
    /** Sorted list of headwords. Index into this == entry index. */
    @NonNull private final List<String> keys;
    /**
     * Offset of each entry's record in the decompressed record stream,
     * indexed by collation-sorted key position (parallel to {@link #keys}).
     */
    @NonNull private final long[] recordOffsets;
    /**
     * All record offsets sorted in ascending stream order.  Used by
     * {@link #readRecord} to determine the byte length of each record without
     * relying on {@code recordOffsets[i+1]}, which after the collation sort no
     * longer points to the consecutive stream entry.
     */
    @NonNull private final long[] sortedOffsets;

    // -----------------------------------------------------------------------
    // For decompressing records on demand
    // -----------------------------------------------------------------------
    @NonNull private final List<long[]> recordBlockInfo;  // each element: [compressedSize, decompressedSize, fileOffset]
    @NonNull private final FileChannel fileChannel;
    private final long recordBlocksStart;

    // -----------------------------------------------------------------------
    // For companion MDD resource dictionary (optional)
    // -----------------------------------------------------------------------
    @Nullable private MDictDictionary mddDictionary;

    // -----------------------------------------------------------------------
    // Resource lifetime management (fromUri path only)
    // -----------------------------------------------------------------------
    // These references prevent the underlying file descriptor from being closed
    // prematurely by the garbage collector.  ParcelFileDescriptor.finalize()
    // closes the FD it owns; FileInputStream.finalize() does the same for the
    // FD it wraps.  If either is collected while fileChannel is still in use,
    // subsequent channel.read() calls will throw ClosedChannelException.
    @SuppressWarnings("FieldCanBeLocal")
    @Nullable private ParcelFileDescriptor pfd;
    @SuppressWarnings("FieldCanBeLocal")
    @Nullable private FileInputStream fis;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private MDictDictionary(@NonNull String id,
                             @NonNull String filePath,
                             @NonNull Map<String, String> tags,
                             @NonNull List<String> keys,
                             @NonNull long[] recordOffsets,
                             @NonNull long[] sortedOffsets,
                             @NonNull List<long[]> recordBlockInfo,
                             @NonNull FileChannel fileChannel,
                             long recordBlocksStart) {
        this.id = id;
        this.filePath = filePath;
        this.tags = Collections.unmodifiableMap(tags);
        this.keys = keys;
        this.recordOffsets = recordOffsets;
        this.sortedOffsets = sortedOffsets;
        this.recordBlockInfo = recordBlockInfo;
        this.fileChannel = fileChannel;
        this.recordBlocksStart = recordBlocksStart;
    }

    /** Sets the companion MDD resource file dictionary. */
    public void setMdd(@Nullable MDictDictionary mdd) {
        this.mddDictionary = mdd;
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Opens an MDX file identified by a content {@link Uri} and parses its
     * key index into memory.  Record data is read lazily.
     *
     * <p>On the first load the parsed key index is written to a small binary
     * cache file in the app's files directory so that subsequent loads can
     * skip the CPU-intensive key-block decompression and parsing step.</p>
     *
     * @throws IOException if the file cannot be opened or is not a valid MDX.
     */
    @NonNull
    public static MDictDictionary fromUri(@NonNull Context context,
                                          @NonNull Uri uri,
                                          @NonNull String filePath) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Cannot open: " + filePath);
        FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
        FileChannel channel = fis.getChannel();

        // Try the index cache first to skip slow key-block decompression.
        File cache = cacheFile(context, filePath);
        MDictDictionary dict = tryLoadFromCache(cache, channel, filePath);
        if (dict == null) {
            dict = parse(channel, filePath);
            saveToCache(cache, dict);
        }
        // Keep pfd and fis alive for the lifetime of the dictionary so that
        // the underlying file descriptor is not closed by GC finalizers.
        dict.pfd = pfd;
        dict.fis = fis;
        return dict;
    }

    @NonNull
    static MDictDictionary parse(@NonNull FileChannel channel,
                                  @NonNull String filePath) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.BIG_ENDIAN);

        // ── Header ──────────────────────────────────────────────────────────
        // 4 bytes header length (big-endian)
        readFully(channel, buf, 0);
        int headerLen = buf.getInt();
        if (headerLen <= 0 || headerLen > 64 * 1024 * 1024) {
            throw new IOException("Invalid MDX header length: " + headerLen);
        }

        // The MDict format stores headerLen as the number of *bytes* in the UTF-16LE XML
        // string (including the 2-byte null terminator).  Some older tools mistakenly
        // store it as a character count; in that case headerLen*2 is the byte count.
        // We detect which convention is in use by checking whether decoding headerLen
        // bytes as UTF-16LE produces a valid XML fragment (ends with '>' before any
        // null padding).  If not, we try headerLen*2 bytes.
        ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
        readFully(channel, headerBuf, 4);
        // Header is a UTF-16LE XML string
        String headerXml = StandardCharsets.UTF_16LE.decode(headerBuf).toString();

        long actualHeaderBytes = headerLen;
        // Truncate at the closing '>' to strip null terminator and any padding bytes.
        int closeAngle = headerXml.lastIndexOf('>');
        if (closeAngle >= 0) {
            headerXml = headerXml.substring(0, closeAngle + 1);
        } else {
            // No '>' found: headerLen was likely in characters, not bytes.
            // Re-read using headerLen*2 bytes.
            actualHeaderBytes = (long) headerLen * 2;
            ByteBuffer fullBuf = ByteBuffer.allocate((int) actualHeaderBytes);
            readFully(channel, fullBuf, 4);
            headerXml = StandardCharsets.UTF_16LE.decode(fullBuf).toString();
            closeAngle = headerXml.lastIndexOf('>');
            if (closeAngle >= 0) headerXml = headerXml.substring(0, closeAngle + 1);
        }

        // 4 bytes Adler32 checksum – skip
        // total header block size = 4 (length) + actualHeaderBytes + 4 (checksum)
        long afterHeader = 4L + actualHeaderBytes + 4L;

        // Parse XML attributes from header
        Map<String, String> tags = parseHeaderXml(headerXml, filePath);

        // ── Encryption Handling ──────────────────────────────────────────────
        String encryptedStr = tags.getOrDefault("encrypted", "No");
        if ("1".equals(encryptedStr) || "Yes".equalsIgnoreCase(encryptedStr)) {
            throw new IOException("User identification is needed to read this encrypted MDict file (Encrypted=1). Not supported.");
        }
        final boolean isEncryptedType2 = "2".equals(encryptedStr) || encryptedStr.startsWith("2");

        // ── Format version ───────────────────────────────────────────────────
        // GeneratedByEngineVersion drives all number-width decisions:
        //   version >= 2.0 → numbers are uint64 (8 bytes); key block header has
        //                     5 uint64 fields + 4-byte Adler32 = 44 bytes total.
        //   version <  2.0 → numbers are uint32 (4 bytes); key block header has
        //                     4 uint32 fields, no Adler32 = 16 bytes total.
        String versionStr = tags.getOrDefault("generatedbyengineversion", "2.0");
        double version = 2.0;
        try { version = Double.parseDouble(versionStr); } catch (NumberFormatException ignored) {}
        final boolean isV2Plus = version >= 2.0;

        // Determine the key text encoding (default UTF-8 for MDX v2).
        // The format spec uses "UTF-16" (without the LE suffix) to mean UTF-16LE.
        String keyEncoding = tags.getOrDefault("encoding", "UTF-8");
        if (keyEncoding.isEmpty()) keyEncoding = "UTF-8";
        final boolean keyIsUtf16le = "UTF-16LE".equalsIgnoreCase(keyEncoding)
                || "UTF-16".equalsIgnoreCase(keyEncoding);
        final java.nio.charset.Charset keyCharset = keyIsUtf16le
                ? StandardCharsets.UTF_16LE
                : java.nio.charset.Charset.forName(keyEncoding);

        // Derive a stable UUID from the file path
        String id = deterministicUuid(filePath).toString();

        // ── Key Block Header ─────────────────────────────────────────────────
        // v2+: 5 big-endian uint64 fields + 4-byte Adler32 = 44 bytes
        // v1.x: 4 big-endian uint32 fields, no kbiDecompSize, no Adler32 = 16 bytes
        final long numKeyBlocks, numEntries, kbiSize, afterKbHeader, kbiDecompSize;
        if (isV2Plus) {
            ByteBuffer kbh = ByteBuffer.allocate(40);
            kbh.order(ByteOrder.BIG_ENDIAN);
            readFully(channel, kbh, afterHeader);
            numKeyBlocks = kbh.getLong();
            numEntries   = kbh.getLong();
            kbiDecompSize = kbh.getLong();
            kbiSize      = kbh.getLong();
            kbh.getLong(); // kbSize
            afterKbHeader = afterHeader + 40 + 4; // +4 for Adler32
        } else {
            ByteBuffer kbh = ByteBuffer.allocate(16);
            kbh.order(ByteOrder.BIG_ENDIAN);
            readFully(channel, kbh, afterHeader);
            numKeyBlocks = kbh.getInt() & 0xFFFFFFFFL;
            kbiDecompSize = 0;
            numEntries   = kbh.getInt() & 0xFFFFFFFFL;
            kbiSize      = kbh.getInt() & 0xFFFFFFFFL;
            kbh.getInt(); // kbSize
            afterKbHeader = afterHeader + 16; // no Adler32
        }

        // ── Key Block Info ───────────────────────────────────────────────────
        long[] kbSizes = readKeyBlockInfo(channel, afterKbHeader, (int) numKeyBlocks, kbiSize, kbiDecompSize,
                keyIsUtf16le, isV2Plus, isEncryptedType2);
        long afterKbi = afterKbHeader + kbiSize;

        // ── Key Blocks ───────────────────────────────────────────────────────
        // Each decompressed key block entry:
        //   v2+:  8-byte big-endian offset + null-terminated key string
        //   v1.x: 4-byte big-endian offset + null-terminated key string
        // Null terminator: 2 bytes (U+0000) for UTF-16LE, 1 byte for all others.
        List<String> keys = new ArrayList<>((int) numEntries);
        long[] recordOffsets = new long[(int) numEntries];

        final int keyIdWidth = isV2Plus ? 8 : 4;
        // minimum entry size = key_id + at least 1 (or 2) null-terminator byte(s)
        final int minEntry = keyIdWidth + (keyIsUtf16le ? 2 : 1);

        long kbPos = afterKbi;
        int entryIdx = 0;
        for (int i = 0; i < numKeyBlocks; i++) {
            long compSize = kbSizes[i * 2];
            long decompSize = kbSizes[i * 2 + 1];
            byte[] block = readBlock(channel, kbPos, compSize, decompSize);
            // compSize includes the 8-byte block header (comp_type + checksum).
            kbPos += compSize;
            ByteBuffer bb = ByteBuffer.wrap(block);
            bb.order(ByteOrder.BIG_ENDIAN);
            while (bb.remaining() >= minEntry) {
                long offset = isV2Plus
                        ? bb.getLong()
                        : (bb.getInt() & 0xFFFFFFFFL);
                int start = bb.position();
                String key;
                if (keyIsUtf16le) {
                    // find 2-byte null terminator (U+0000 in UTF-16LE)
                    while (bb.remaining() >= 2) {
                        byte lo = bb.get();
                        byte hi = bb.get();
                        if (lo == 0 && hi == 0) break;
                    }
                    int end = bb.position() - 2;
                    key = end > start
                            ? new String(block, start, end - start, StandardCharsets.UTF_16LE)
                            : "";
                } else {
                    // find single-byte null terminator
                    while (bb.hasRemaining()) {
                        if (bb.get() == 0) break;
                    }
                    int end = bb.position() - 1;
                    key = end > start ? new String(block, start, end - start, keyCharset) : "";
                }
                if (!key.isEmpty() && entryIdx < (int) numEntries) {
                    keys.add(key);
                    recordOffsets[entryIdx] = offset;
                    entryIdx++;
                }
            }
        }
        long afterKeyBlocks = kbPos;

        // Sort keys and their corresponding record-offsets by QUATERNARY Unicode
        // collation so that binary search with any Slob.Strength comparator works
        // correctly.  MDict files sort keys in the file's native encoding byte
        // order (e.g. UTF-16LE or GBK byte order), which can differ significantly
        // from Unicode collation order, causing lookups to fail for non-ASCII
        // dictionaries.
        if (entryIdx > 1) {
            final Slob.KeyComparator sortCmp = Slob.Strength.QUATERNARY.comparator;
            Integer[] sortedIdxs = new Integer[entryIdx];
            for (int i = 0; i < entryIdx; i++) sortedIdxs[i] = i;
            final String[] keyArr = keys.toArray(new String[0]);
            final long[] offsCopy = Arrays.copyOf(recordOffsets, entryIdx);
            Arrays.sort(sortedIdxs, (a, b) -> sortCmp.compare(
                    new Slob.Keyed(keyArr[a]), new Slob.Keyed(keyArr[b])));
            keys.clear();
            for (int j = 0; j < entryIdx; j++) {
                keys.add(keyArr[sortedIdxs[j]]);
                recordOffsets[j] = offsCopy[sortedIdxs[j]];
            }
        }

        // Build a sorted copy of the record offsets in ascending stream order.
        // This is used by readRecord() to find the correct end boundary of each
        // record: after the collation sort above, recordOffsets[i+1] is the
        // offset of the collation-next entry, NOT the stream-next entry.
        // Sorting once here lets readRecord() do a O(log n) lookup.
        final long[] sortedOffsets = Arrays.copyOf(recordOffsets, entryIdx);
        Arrays.sort(sortedOffsets);

        // ── Record Block Header ──────────────────────────────────────────────
        // v2+:  4 × uint64 = 32 bytes
        // v1.x: 4 × uint32 = 16 bytes
        final int rbhSize = isV2Plus ? 32 : 16;
        ByteBuffer rbh = ByteBuffer.allocate(rbhSize);
        rbh.order(ByteOrder.BIG_ENDIAN);
        readFully(channel, rbh, afterKeyBlocks);
        final long numRecordBlocks, rbiSize;
        if (isV2Plus) {
            numRecordBlocks = rbh.getLong();
            rbh.getLong(); // numRecEntries
            rbiSize         = rbh.getLong();
            rbh.getLong(); // rbSize
        } else {
            numRecordBlocks = rbh.getInt() & 0xFFFFFFFFL;
            rbh.getInt(); // numRecEntries
            rbiSize         = rbh.getInt() & 0xFFFFFFFFL;
            rbh.getInt(); // rbSize
        }
        long afterRbHeader = afterKeyBlocks + rbhSize;

        // ── Record Block Info ────────────────────────────────────────────────
        // Array of (compressed_size, decompressed_size) pairs.
        // Each pair is 2 × uint64 (v2+) or 2 × uint32 (v1.x).
        ByteBuffer rbi = ByteBuffer.allocate((int) rbiSize);
        rbi.order(ByteOrder.BIG_ENDIAN);
        readFully(channel, rbi, afterRbHeader);

        List<long[]> recordBlockInfo = new ArrayList<>((int) numRecordBlocks);
        long rbFileOffset = afterRbHeader + rbiSize; // file offset of first record block
        for (int i = 0; i < numRecordBlocks; i++) {
            long compSize, decompSize;
            if (isV2Plus) {
                compSize   = rbi.getLong();
                decompSize = rbi.getLong();
            } else {
                compSize   = rbi.getInt() & 0xFFFFFFFFL;
                decompSize = rbi.getInt() & 0xFFFFFFFFL;
            }
            recordBlockInfo.add(new long[]{compSize, decompSize, rbFileOffset});
            rbFileOffset += compSize;
        }

        return new MDictDictionary(id, filePath, tags, keys,
                Arrays.copyOf(recordOffsets, entryIdx), sortedOffsets,
                recordBlockInfo, channel, afterRbHeader + rbiSize);
    }

    // -----------------------------------------------------------------------
    // Dictionary interface
    // -----------------------------------------------------------------------

    @Override @NonNull public String getId()   { return id; }
    @Override @NonNull public String getLabel() {
        String label = tags.get("label");   // remapped from "title" by parseHeaderXml
        return (label != null && !label.isEmpty()) ? label : shortName(filePath);
    }
    @Override @NonNull public String getUri() {
        String uri = tags.get("uri");       // remapped from "website" by parseHeaderXml
        return (uri != null && !uri.isEmpty()) ? uri : ("mdict:" + id);
    }
    @Override @NonNull public Map<String, String> getTags() { return tags; }
    @Override public long getBlobCount() { return keys.size(); }
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
        // Binary search with collation matching
        final int startIdx = findStartIndex(key, strength);
        final Slob.KeyComparator stopCmp = strength.stopComparator;
        final Slob.Keyed lookupKeyed = new Slob.Keyed(key);

        return new Iterator<DictionaryEntry>() {
            int idx = startIdx;
            DictionaryEntry next = advance();

            private DictionaryEntry advance() {
                while (idx < keys.size()) {
                    String candidate = keys.get(idx);
                    if (stopCmp.compare(new Slob.Keyed(candidate), lookupKeyed) != 0) {
                        return null;
                    }
                    int currentIdx = idx++;
                    return new DictionaryEntry(MDictDictionary.this,
                            String.valueOf(currentIdx), candidate, null);
                }
                return null;
            }

            @Override public boolean hasNext() { return next != null; }
            @Override public DictionaryEntry next() {
                if (next == null) throw new NoSuchElementException();
                DictionaryEntry result = next;
                next = advance();
                return result;
            }
        };
    }

    @Override
    @Nullable
    public DictionaryContent getContent(@NonNull String blobId) {
        // blobId is either a decimal entry index (for MDX lookups) or a
        // resource path from the MDD file.
        try {
            int idx = Integer.parseInt(blobId);
            if (idx < 0 || idx >= keys.size()) return null;
            long offset = recordOffsets[idx];
            byte[] data = readRecord(offset);
            if (data == null) return null;
            // Trim null terminator if present
            int len = data.length;
            while (len > 0 && data[len - 1] == 0) len--;
            // Use the dictionary's encoding for content (same as for keys).
            String enc = tags.getOrDefault("encoding", "UTF-8");
            if (enc.isEmpty()) enc = "UTF-8";
            Charset cs;
            try { cs = Charset.forName(enc); } catch (Exception e) { cs = StandardCharsets.UTF_8; }
            String html = new String(data, 0, len, cs);
            return new DictionaryContent("text/html; charset=utf-8",
                    ByteBuffer.wrap(html.getBytes(StandardCharsets.UTF_8)));
        } catch (NumberFormatException e) {
            // It's a resource path – delegate to MDD
            if (mddDictionary != null) {
                return mddDictionary.getResourceContent(blobId);
            }
            return null;
        }
    }

    @Override
    @NonNull
    public String getContentType(@NonNull String blobId) {
        try {
            Integer.parseInt(blobId);
            return "text/html; charset=utf-8";
        } catch (NumberFormatException e) {
            // resource
            return guessMimeType(blobId);
        }
    }

    // -----------------------------------------------------------------------
    // MDD resource support
    // -----------------------------------------------------------------------

    /**
     * Looks up a resource (image, CSS, etc.) by its path. Used when this
     * MDictDictionary instance represents an MDD file.
     *
     * <p>MDD keys are stored with a leading backslash (e.g. {@code \image.png}).
     * HTML content may reference resources using forward slashes ({@code /image.png})
     * or backslashes ({@code \image.png}). This method normalises the path before
     * searching so both conventions work correctly.</p>
     */
    @Nullable
    DictionaryContent getResourceContent(@NonNull String resourcePath) {
        // Normalise to the MDD convention: replace forward slashes with backslashes
        // and ensure there is a leading backslash.
        String key = resourcePath.replace('/', '\\');
        if (key.isEmpty() || key.charAt(0) != '\\') {
            key = '\\' + key;
        }
        // Binary search using the same QUATERNARY comparator the keys are sorted by.
        int idx = findStartIndex(key, Slob.Strength.QUATERNARY);
        if (idx < 0 || idx >= keys.size()
                || !keys.get(idx).equalsIgnoreCase(key)) return null;
        long offset = recordOffsets[idx];
        byte[] data = readRecord(offset);
        if (data == null) return null;
        // Use the key without the leading backslash to guess the MIME type from
        // the file extension (the backslash is not part of the filename itself).
        String nameForMime = key.substring(1);
        return new DictionaryContent(guessMimeType(nameForMime),
                ByteBuffer.wrap(data));
    }

    // -----------------------------------------------------------------------
    // Internal parsing helpers
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

    /**
     * Reads the decoded record bytes for a given decompressed-stream offset.
     *
     * <p>The end boundary is determined by looking up the next offset in
     * {@link #sortedOffsets} (which holds all record offsets sorted in ascending
     * stream order).  This is correct regardless of the collation sort order of
     * {@link #keys}/{@link #recordOffsets}.</p>
     */
    @Nullable
    private byte[] readRecord(long offset) {
        // Determine the end of this record: the next larger offset in the stream.
        int pos = Arrays.binarySearch(sortedOffsets, offset);
        long nextOffset = (pos >= 0 && pos + 1 < sortedOffsets.length)
                ? sortedOffsets[pos + 1] : Long.MAX_VALUE;

        // Walk record blocks to find which block contains this offset.
        long decompStart = 0;
        for (long[] info : recordBlockInfo) {
            long compSize   = info[0];
            long decompSize = info[1];
            long fileOffset = info[2];

            if (decompStart + decompSize > offset) {
                // This block contains our record's start.
                try {
                    byte[] block = readBlock(fileChannel, fileOffset, compSize, decompSize);
                    int start = (int) (offset - decompStart);
                    int end;
                    if (nextOffset != Long.MAX_VALUE && nextOffset - decompStart <= decompSize) {
                        // Next record is also within this block.
                        end = (int) (nextOffset - decompStart);
                    } else {
                        // Next record is in a later block (or there is no next record).
                        // Scan for the single-byte null terminator that MDict uses to
                        // end each record in the decompressed stream.
                        end = block.length;
                        for (int i = start; i < block.length; i++) {
                            if (block[i] == 0) {
                                end = i;
                                break;
                            }
                        }
                    }
                    if (start >= end) return new byte[0];
                    byte[] result = new byte[end - start];
                    System.arraycopy(block, start, result, 0, result.length);
                    return result;
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read record at offset " + offset, e);
                    return null;
                }
            }
            decompStart += decompSize;
        }
        return null;
    }

    /** Reads and decompresses a single block from the file channel. */
    @NonNull
    private static byte[] readBlock(@NonNull FileChannel channel,
                                     long fileOffset,
                                     long compSize,
                                     long decompSize) throws IOException {
        // Block header: 4-byte compression type (little-endian u32) + 4-byte checksum
        // Only the lowest nibble of the first byte encodes the compression method:
        //   0x00 = none, 0x01 = LZO, 0x02 = ZLIB
        ByteBuffer header = ByteBuffer.allocate(8);
        readFully(channel, header, fileOffset);
        int compType = header.get(0) & 0x0f; // LE u32, lowest nibble

        long dataSize = compSize - 8;
        byte[] compressed = new byte[(int) dataSize];
        ByteBuffer dataBuf = ByteBuffer.wrap(compressed);
        readFully(channel, dataBuf, fileOffset + 8);

        switch (compType) {
            case COMP_NONE:
                return compressed;
            case COMP_ZLIB:
                return zlibDecompress(compressed, (int) decompSize);
            case COMP_LZO:
                // LZO is not commonly used; fall back to returning raw data
                Log.w(TAG, "LZO compression not supported; returning raw block");
                return compressed;
            default:
                throw new IOException("Unknown MDict compression type: " + compType);
        }
    }

    /** Reads the Key Block Info to get compressed/decompressed sizes per key block. */
    @NonNull
    private static long[] readKeyBlockInfo(@NonNull FileChannel channel,
                                            long offset,
                                            int numBlocks,
                                            long kbiSize,
                                            long kbiDecompSize,
                                            boolean keysAreUtf16le,
                                            boolean isV2Plus,
                                            boolean isEncryptedType2) throws IOException {
        ByteBuffer raw = ByteBuffer.allocate((int) kbiSize);
        readFully(channel, raw, offset);

        byte[] decompressed;
        if (isV2Plus) {
            // v2+: KBI has an 8-byte block header (4-byte LE compression type + 4-byte checksum)
            // followed by compressed (or raw) data.
            int compType = raw.get(0) & 0x0f; // lowest nibble of LE u32
            raw.position(8);
            byte[] data = new byte[raw.remaining()];
            raw.get(data);


            if (compType == COMP_ZLIB) {
                byte[] dataToDecompress = data;
                if (isEncryptedType2) {
                    // Reconstruct the full block (8-byte header + data) because
                    // mdxDecrypt requires bytes 4..7 to generate the decryption key.
                    byte[] fullBlock = new byte[8 + data.length];
                    raw.position(0);
                    raw.get(fullBlock, 0, 8);
                    System.arraycopy(data, 0, fullBlock, 8, data.length);

                    // Decrypts in place starting at byte 8
                    mdxDecrypt(fullBlock);

                    // Extract the now-decrypted payload
                    dataToDecompress = Arrays.copyOfRange(fullBlock, 8, fullBlock.length);
                }
                decompressed = zlibDecompress(dataToDecompress, (int) kbiDecompSize);
            } else {
                decompressed = data;
            }
        } else {
            // v1.x: KBI is always stored uncompressed (no block header).
            decompressed = raw.array();
        }

        // Decode entries.
        // v2+ entry:  num_entries(uint64) + first_key(uint16 len, data+null) +
        //             last_key(uint16 len, data+null) + comp_size(uint64) + decomp_size(uint64)
        // v1.x entry: num_entries(uint32) + first_key(uint8 len, data, no null) +
        //             last_key(uint8 len, data, no null) + comp_size(uint32) + decomp_size(uint32)
        long[] sizes = new long[numBlocks * 2];
        ByteBuffer bb = ByteBuffer.wrap(decompressed);
        bb.order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numBlocks; i++) {
            if (isV2Plus) {
                if (bb.remaining() < 8) break;
                bb.getLong(); // num_entries
            } else {
                if (bb.remaining() < 4) break;
                bb.getInt(); // num_entries
            }
            skipKeyString(bb, keysAreUtf16le, isV2Plus);
            skipKeyString(bb, keysAreUtf16le, isV2Plus);
            if (isV2Plus) {
                if (bb.remaining() < 16) break;
                sizes[i * 2]     = bb.getLong(); // comp_size
                sizes[i * 2 + 1] = bb.getLong(); // decomp_size
            } else {
                if (bb.remaining() < 8) break;
                sizes[i * 2]     = bb.getInt() & 0xFFFFFFFFL; // comp_size
                sizes[i * 2 + 1] = bb.getInt() & 0xFFFFFFFFL; // decomp_size
            }
        }
        return sizes;
    }

    /**
     * Skips a key string in the Key Block Info buffer.
     *
     * <p>v2+ format: 2-byte big-endian length (in encoding units) + data + 1 null unit.</p>
     * <p>v1.x format: 1-byte length (in encoding units) + data (no null terminator).</p>
     */
    private static void skipKeyString(@NonNull ByteBuffer bb, boolean utf16le, boolean isV2Plus) {
        if (isV2Plus) {
            if (bb.remaining() < 2) return;
            int len = bb.getShort() & 0xFFFF;
            // len is in encoding units; add 1 null unit; each unit is 1 byte (UTF-8/GBK) or 2 (UTF-16LE)
            int bytesToSkip = utf16le ? (len + 1) * 2 : (len + 1);
            if (bb.remaining() >= bytesToSkip) bb.position(bb.position() + bytesToSkip);
        } else {
            // v1.x: 1-byte length, no null terminator
            if (bb.remaining() < 1) return;
            int len = bb.get() & 0xFF;
            int bytesToSkip = utf16le ? len * 2 : len;
            if (bb.remaining() >= bytesToSkip) bb.position(bb.position() + bytesToSkip);
        }
    }

    // -----------------------------------------------------------------------
    // Index cache helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the path of the index cache file for the given MDX file path.
     * The cache is stored in a private sub-directory of
     * {@code context.getFilesDir()} so it persists between restarts.
     */
    @NonNull
    private static File cacheFile(@NonNull Context context, @NonNull String filePath) {
        File cacheDir = new File(context.getFilesDir(), "dicts/mdict");
        // Use a FNV-1a 64-bit hash for a stable, low-collision directory name.
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < filePath.length(); i++) {
            h ^= filePath.charAt(i);
            h *= 0x100000001b3L;
        }
        String name = Long.toHexString(h & Long.MAX_VALUE) + ".cache";
        return new File(cacheDir, name);
    }

    /**
     * Removes the persisted index cache that was created for this MDX file
     * when it was first loaded.
     *
     * <p>Call this when the user removes / "forgets" the dictionary so that
     * the app does not accumulate stale data in internal storage.</p>
     *
     * @param context  the application context
     * @param filePath the URI / path string of the MDX file (same value
     *                 that was passed to {@link #fromUri})
     */
    public static void cleanupPersistedData(@NonNull Context context,
                                             @NonNull String filePath) {
        File cache = cacheFile(context, filePath);
        if (!cache.delete() && cache.exists()) {
            Log.w(TAG, "Could not delete MDict index cache: " + cache);
        }
    }

    /**
     * Tries to construct an {@link MDictDictionary} from the on-disk index
     * cache.  Returns {@code null} if the cache does not exist, has a stale
     * fingerprint, or is corrupt in any way.
     */
    @Nullable
    private static MDictDictionary tryLoadFromCache(@NonNull File cacheFile,
                                                     @NonNull FileChannel channel,
                                                     @NonNull String filePath) {
        if (!cacheFile.exists()) return null;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cacheFile), CACHE_BUFFER_SIZE))) {

            // Magic
            byte[] magic = new byte[CACHE_MAGIC.length];
            dis.readFully(magic);
            if (!Arrays.equals(magic, CACHE_MAGIC)) return null;

            // Version
            if ((dis.readByte() & 0xFF) != CACHE_VERSION) return null;

            // Fingerprint: MDX file size
            long cachedSize = dis.readLong();
            if (cachedSize != channel.size()) {
                Log.d(TAG, "Index cache stale (size mismatch) for " + filePath);
                return null;
            }

            // Tags
            int tagCount = dis.readShort() & 0xFFFF;
            Map<String, String> tags = new HashMap<>(tagCount);
            for (int i = 0; i < tagCount; i++) {
                tags.put(dis.readUTF(), dis.readUTF());
            }

            // id
            String id = dis.readUTF();

            // Keys + record offsets
            int keyCount = dis.readInt();
            List<String> keys = new ArrayList<>(keyCount);
            long[] recordOffsets = new long[keyCount];
            long[] sortedOffsets = new long[keyCount];
            for (int i = 0; i < keyCount; i++) keys.add(dis.readUTF());
            for (int i = 0; i < keyCount; i++) recordOffsets[i] = dis.readLong();
            for (int i = 0; i < keyCount; i++) sortedOffsets[i] = dis.readLong();

            // Record block info
            int blockCount = dis.readInt();
            List<long[]> recordBlockInfo = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                recordBlockInfo.add(new long[]{
                        dis.readLong(), // compSize
                        dis.readLong(), // decompSize
                        dis.readLong()  // fileOffset
                });
            }

            // Start of record blocks in the file
            long recordBlocksStart = dis.readLong();

            Log.d(TAG, "Loaded index from cache for " + filePath
                    + " (" + keyCount + " keys)");
            return new MDictDictionary(id, filePath, tags, keys,
                    recordOffsets, sortedOffsets, recordBlockInfo,
                    channel, recordBlocksStart);

        } catch (Exception e) {
            Log.w(TAG, "Failed to load index cache for " + filePath, e);
            return null;
        }
    }

    /**
     * Persists the parsed key index of {@code dict} to {@code cacheFile} so
     * that {@link #tryLoadFromCache} can reconstruct it on the next load.
     *
     * <p>Writes to a sibling temp file first, then renames atomically to
     * prevent a partial write from corrupting the cache.</p>
     */
    private static void saveToCache(@NonNull File cacheFile,
                                     @NonNull MDictDictionary dict) {
        // Use getAbsoluteFile() so that getParentFile() is non-null in all
        // realistic cases (files under context.getFilesDir() are always absolute).
        File absCache = cacheFile.getAbsoluteFile();
        File parent = absCache.getParentFile();
        if (parent == null) {
            // Defensive: should never happen for a path under context.getFilesDir().
            Log.w(TAG, "Cannot determine parent directory for cache: " + cacheFile);
            return;
        }
        if (!parent.mkdirs() && !parent.isDirectory()) {
            Log.w(TAG, "Cannot create cache directory: " + parent);
            return;
        }
        File tmp = new File(parent, absCache.getName() + ".tmp");
        try {
            long fileSize = dict.fileChannel.size();
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp), CACHE_BUFFER_SIZE))) {
                dos.write(CACHE_MAGIC);
                dos.writeByte(CACHE_VERSION);
                dos.writeLong(fileSize);

                // Tags
                dos.writeShort(dict.tags.size());
                for (Map.Entry<String, String> e : dict.tags.entrySet()) {
                    dos.writeUTF(e.getKey());
                    dos.writeUTF(e.getValue());
                }

                // id
                dos.writeUTF(dict.id);

                // Keys + offsets
                dos.writeInt(dict.keys.size());
                for (String k : dict.keys) dos.writeUTF(k);
                for (long off : dict.recordOffsets) dos.writeLong(off);
                for (long off : dict.sortedOffsets) dos.writeLong(off);

                // Record block info
                dos.writeInt(dict.recordBlockInfo.size());
                for (long[] info : dict.recordBlockInfo) {
                    dos.writeLong(info[0]);
                    dos.writeLong(info[1]);
                    dos.writeLong(info[2]);
                }

                // Record blocks start
                dos.writeLong(dict.recordBlocksStart);
            }
            if (!tmp.renameTo(absCache)) {
                Log.w(TAG, "Failed to rename cache temp file to " + absCache);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } else {
                Log.d(TAG, "Saved index cache for " + dict.filePath
                        + " (" + dict.keys.size() + " keys)");
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save index cache for " + dict.filePath, e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** ZLIB decompression helper with raw deflate fallback. */
    @NonNull
    private static byte[] zlibDecompress(@NonNull byte[] data, int expectedSize) throws IOException {
        try {
            // Try standard ZLIB format (expects 2-byte header)
            return doInflate(data, expectedSize, false);
        } catch (IOException e) {
            // Some MDict tools incorrectly emit raw deflate but label it as zlib.
            // Fallback to raw deflate (nowrap) if we get a header check error.
            if (e.getMessage() != null && e.getMessage().contains("incorrect header check")) {
                return doInflate(data, expectedSize, true);
            }
            throw e;
        }
    }

    @NonNull
    private static byte[] doInflate(@NonNull byte[] data, int expectedSize, boolean nowrap) throws IOException {
        Inflater inf = new Inflater(nowrap);
        inf.setInput(data);
        byte[] out = new byte[Math.max(expectedSize, data.length * 4)];
        int totalRead = 0;
        try {
            while (!inf.finished()) {
                int need = out.length - totalRead;
                if (need == 0) {
                    byte[] larger = new byte[out.length * 2];
                    System.arraycopy(out, 0, larger, 0, out.length);
                    out = larger;
                    need = out.length - totalRead;
                }
                try {
                    int read = inf.inflate(out, totalRead, need);
                    if (read == 0 && inf.needsInput()) break;
                    totalRead += read;
                } catch (DataFormatException e) {
                    throw new IOException("ZLIB decompress error", e);
                }
            }
        } finally {
            inf.end();
        }
        if (totalRead == out.length) return out;
        byte[] result = new byte[totalRead];
        System.arraycopy(out, 0, result, 0, totalRead);
        return result;
    }

    /**
     * Decrypts an MDict block (Key Block Info) when Encrypted=2.
     * The first 8 bytes are untouched (comp_type + checksum).
     * Decryption starts at byte 8.
     */
    private static void mdxDecrypt(byte[] block) {
        byte[] keyBuffer = new byte[8];
        System.arraycopy(block, 4, keyBuffer, 0, 4); // Extract bytes 4..7
        keyBuffer[4] = (byte) 0x95;
        keyBuffer[5] = (byte) 0x36;
        // keyBuffer[6] and [7] remain 0x00

        byte[] key = ripemd128(keyBuffer);
        fastDecrypt(block, 8, block.length - 8, key);
    }

    /**
     * Fast MDict decryption algorithm.
     */
    private static void fastDecrypt(byte[] data, int startOffset, int length, byte[] key) {
        int previous = 0x36;
        for (int i = 0; i < length; i++) {
            int idx = startOffset + i;
            int original = data[idx] & 0xFF;
            int t = ((original >>> 4) | (original << 4)) & 0xFF;
            int decrypted = t ^ (previous & 0xFF) ^ (i & 0xFF) ^ (key[i % key.length] & 0xFF);
            previous = data[idx] & 0xFF;
            data[idx] = (byte) decrypted;
        }
    }

    /**
     * Exact port of the provided JS ripemd128 implementation.
     * NOTE: Intentionally contains the 0x00000000 constant bug from the JS source,
     * as the dictionary file requires this specific non-standard hash to decrypt correctly.
     */
    @NonNull
    private static byte[] ripemd128(@NonNull byte[] data) {
        int[] hash = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476};

        // Note the 8th element is 0x00000000 instead of the standard 0x7A6D76E9!
        int[] K = {
                0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC,
                0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x00000000
        };

        int[][] S = {
                {11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8},
                {7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12},
                {11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5},
                {11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12},
                {8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6},
                {9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11},
                {9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5},
                {15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8}
        };

        int[][] X = {
                {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                {7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8},
                {3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12},
                {1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2},
                {5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12},
                {6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2},
                {15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13},
                {8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14}
        };

        int bytes = data.length;
        int padLen = (bytes % 64 < 56 ? 56 : 120) - (bytes % 64);

        // FIX: Added +8 here! The JS code does a concat() which adds the 8 byte length to the end.
        byte[] padded = new byte[bytes + padLen + 8];
        System.arraycopy(data, 0, padded, 0, bytes);
        padded[bytes] = (byte) 0x80;

        // Match JS bitwise truncation behavior exactly
        bytes = bytes << 3;
        padded[padded.length - 8] = (byte) (bytes & 0xFF);
        padded[padded.length - 7] = (byte) ((bytes >>> 8) & 0xFF);
        padded[padded.length - 6] = (byte) ((bytes >>> 16) & 0xFF);
        padded[padded.length - 5] = (byte) ((bytes >>> 24) & 0xFF);
        padded[padded.length - 4] = (byte) ((bytes >>> 31) & 0xFF); // JS uses >>> 31 instead of >>> 32
        padded[padded.length - 3] = 0;
        padded[padded.length - 2] = 0;
        padded[padded.length - 1] = 0;

        int[] x = new int[padded.length / 4];
        for (int i = 0; i < x.length; i++) {
            x[i] = (padded[i * 4] & 0xFF) |
                    ((padded[i * 4 + 1] & 0xFF) << 8) |
                    ((padded[i * 4 + 2] & 0xFF) << 16) |
                    ((padded[i * 4 + 3] & 0xFF) << 24);
        }

        int aa, bb, cc, dd, aaa, bbb, ccc, ddd;
        for (int i = 0; i < x.length; i += 16) {
            aa = aaa = hash[0];
            bb = bbb = hash[1];
            cc = ccc = hash[2];
            dd = ddd = hash[3];

            int t = 0;
            while (t < 64) {
                int r = t / 16;
                aa = rotl(aa + f(r, bb, cc, dd) + x[i + X[r][t % 16]] + K[r], S[r][t % 16]);
                int tmp = dd; dd = cc; cc = bb; bb = aa; aa = tmp;
                t++;
            }

            while (t < 128) {
                int r = t / 16;
                int rr = (63 - (t % 64)) / 16;
                aaa = rotl(aaa + f(rr, bbb, ccc, ddd) + x[i + X[r][t % 16]] + K[r], S[r][t % 16]);
                int tmp = ddd; ddd = ccc; ccc = bbb; bbb = aaa; aaa = tmp;
                t++;
            }

            int tmpD = hash[1] + cc + ddd;
            hash[1] = hash[2] + dd + aaa;
            hash[2] = hash[3] + aa + bbb;
            hash[3] = hash[0] + bb + ccc;
            hash[0] = tmpD;
        }

        byte[] result = new byte[16];
        for (int j = 0; j < 4; j++) {
            result[j * 4] = (byte) (hash[j] & 0xFF);
            result[j * 4 + 1] = (byte) ((hash[j] >>> 8) & 0xFF);
            result[j * 4 + 2] = (byte) ((hash[j] >>> 16) & 0xFF);
            result[j * 4 + 3] = (byte) ((hash[j] >>> 24) & 0xFF);
        }
        return result;
    }

    private static int rotl(int x, int n) {
        return (x >>> (32 - n)) | (x << n);
    }

    private static int f(int r, int x, int y, int z) {
        switch (r) {
            case 0: return x ^ y ^ z;
            case 1: return (x & y) | (~x & z);
            case 2: return (x | ~y) ^ z;
            case 3: return (x & z) | (y & ~z);
            default: return x ^ y ^ z;
        }
    }

    private static void readFully(@NonNull FileChannel channel,
                                   @NonNull ByteBuffer buf,
                                   long position) throws IOException {
        buf.clear();
        long pos = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n < 0) throw new IOException("Unexpected EOF at position " + pos);
            pos += n;
        }
        buf.flip();
    }

    /** Parses the MDX header XML to extract metadata tags. */
    @NonNull
    private static Map<String, String> parseHeaderXml(@NonNull String xml,
                                                        @NonNull String filePath) {
        Map<String, String> tags = new HashMap<>();
        // Simple attribute extraction via regex-free scanning
        for (String attr : new String[]{"Title", "Encoding", "Description",
                "Format", "CreationDate", "Website", "Copyright", "Author",
                "GeneratedByEngineVersion",
                "Encrypted"}) {
            String val = extractAttr(xml, attr);
            if (val != null) {
                tags.put(attr.toLowerCase(), val);
            }
        }
        // Map to standard Slob tag names
        remapTag(tags, "title", "label");
        remapTag(tags, "website", "uri");
        remapTag(tags, "copyright", "copyright");
        remapTag(tags, "description", "description");
        return tags;
    }

    @Nullable
    private static String extractAttr(@NonNull String xml, @NonNull String attr) {
        String needle = attr + "=\"";
        int start = xml.indexOf(needle);
        if (start < 0) {
            needle = attr.toLowerCase() + "=\"";
            start = xml.toLowerCase().indexOf(needle);
        }
        if (start < 0) return null;
        start += needle.length();
        int end = xml.indexOf('"', start);
        if (end < 0) return null;
        return xml.substring(start, end);
    }

    private static void remapTag(@NonNull Map<String, String> tags,
                                   @NonNull String fromKey,
                                   @NonNull String toKey) {
        if (!fromKey.equals(toKey)) {
            String v = tags.remove(fromKey);
            if (v != null && !v.isEmpty()) tags.put(toKey, v);
        }
    }

    @NonNull
    private static String shortName(@NonNull String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @NonNull
    private static UUID deterministicUuid(@NonNull String filePath) {
        // Simplified deterministic UUID derived from the file path.
        // Note: this is NOT a standards-compliant version 5 UUID (which would
        // require SHA-1 over a namespace + name), but the version/variant bits
        // are set to the RFC 4122 values for version 5 (0x5000) and variant 2
        // (0x8000...) to produce a well-formed UUID string.
        byte[] bytes = filePath.getBytes(StandardCharsets.UTF_8);
        long most = 0, least = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (i < 8) most  = (most  << 8) | (bytes[i] & 0xFF);
            else       least = (least << 8) | (bytes[i % 8] & 0xFF);
        }
        most  ^= filePath.hashCode();
        least ^= filePath.hashCode() * 0x9e3779b97f4a7c15L;
        // Apply RFC 4122 version (5) and variant (10xx) bits
        most  = (most  & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000005000L;
        least = (least & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    @NonNull
    private static String guessMimeType(@NonNull String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".css"))  return "text/css";
        if (lower.endsWith(".js"))   return "application/javascript";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".ogg"))  return "audio/ogg";
        if (lower.endsWith(".wav"))  return "audio/wav";
        return "application/octet-stream";
    }
}
