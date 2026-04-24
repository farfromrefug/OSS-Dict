package itkach.aard2.dictionary.mdict;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    /** Offset of each entry's record in the *decompressed* record stream. */
    @NonNull private final long[] recordOffsets;

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
    // Construction
    // -----------------------------------------------------------------------

    private MDictDictionary(@NonNull String id,
                             @NonNull String filePath,
                             @NonNull Map<String, String> tags,
                             @NonNull List<String> keys,
                             @NonNull long[] recordOffsets,
                             @NonNull List<long[]> recordBlockInfo,
                             @NonNull FileChannel fileChannel,
                             long recordBlocksStart) {
        this.id = id;
        this.filePath = filePath;
        this.tags = Collections.unmodifiableMap(tags);
        this.keys = keys;
        this.recordOffsets = recordOffsets;
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
        return parse(channel, filePath);
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

        // Determine the key text encoding (default UTF-8 for MDX v2).
        String keyEncoding = tags.getOrDefault("encoding", "UTF-8");
        if (keyEncoding.isEmpty()) keyEncoding = "UTF-8";
        final boolean keyIsUtf16le = "UTF-16LE".equalsIgnoreCase(keyEncoding);
        final java.nio.charset.Charset keyCharset = keyIsUtf16le
                ? StandardCharsets.UTF_16LE
                : java.nio.charset.Charset.forName(keyEncoding);

        // Derive a stable UUID from the file path
        String id = deterministicUuid(filePath).toString();

        // ── Key Block Header ─────────────────────────────────────────────────
        // (5 big-endian u64 fields + 4-byte Adler32 checksum = 44 bytes total)
        ByteBuffer kbh = ByteBuffer.allocate(40);
        kbh.order(ByteOrder.BIG_ENDIAN);
        readFully(channel, kbh, afterHeader);
        long numKeyBlocks    = kbh.getLong();
        long numEntries      = kbh.getLong();
        /* long kbiDecompSize = */ kbh.getLong();
        long kbiSize         = kbh.getLong();
        /* long kbSize        = */ kbh.getLong();
        // 4 bytes Adler32 checksum – skip
        long afterKbHeader   = afterHeader + 40 + 4;

        // ── Key Block Info ───────────────────────────────────────────────────
        long[] kbSizes = readKeyBlockInfo(channel, afterKbHeader, (int) numKeyBlocks, kbiSize,
                keyIsUtf16le);
        long afterKbi = afterKbHeader + kbiSize;

        // ── Key Blocks ───────────────────────────────────────────────────────
        List<String> keys = new ArrayList<>((int) numEntries);
        long[] recordOffsets = new long[(int) numEntries]; // offset in decompressed record stream

        long kbPos = afterKbi;
        int entryIdx = 0;
        for (int i = 0; i < numKeyBlocks; i++) {
            long compSize = kbSizes[i * 2];
            long decompSize = kbSizes[i * 2 + 1];
            byte[] block = readBlock(channel, kbPos, compSize, decompSize);
            // compSize includes the 8-byte header (comp_type + checksum).
            kbPos += compSize;
            // Parse entries: 8-byte big-endian offset + null-terminated key string.
            // The null terminator is 1 byte for single-byte encodings (UTF-8, GBK…)
            // or 2 bytes (U+0000) for UTF-16LE.
            ByteBuffer bb = ByteBuffer.wrap(block);
            bb.order(ByteOrder.BIG_ENDIAN);
            while (bb.remaining() >= (keyIsUtf16le ? 10 : 9)) {
                long offset = bb.getLong();
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
                    if (end > start) {
                        key = new String(block, start, end - start, StandardCharsets.UTF_16LE);
                    } else {
                        key = "";
                    }
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

        // ── Record Block Header ──────────────────────────────────────────────
        ByteBuffer rbh = ByteBuffer.allocate(32);
        rbh.order(ByteOrder.BIG_ENDIAN);
        readFully(channel, rbh, afterKeyBlocks);
        long numRecordBlocks = rbh.getLong();
        /* long numRecEntries = */ rbh.getLong();
        long rbiSize         = rbh.getLong();
        /* long rbSize        = */ rbh.getLong();
        long afterRbHeader   = afterKeyBlocks + 32;

        // ── Record Block Info ────────────────────────────────────────────────
        // Array of (compressed_size, decompressed_size) pairs, each 8 bytes
        ByteBuffer rbi = ByteBuffer.allocate((int) rbiSize);
        rbi.order(ByteOrder.BIG_ENDIAN);
        readFully(channel, rbi, afterRbHeader);

        List<long[]> recordBlockInfo = new ArrayList<>((int) numRecordBlocks);
        long rbFileOffset = afterRbHeader + rbiSize; // file offset of first record block
        for (int i = 0; i < numRecordBlocks; i++) {
            long compSize   = rbi.getLong();
            long decompSize = rbi.getLong();
            recordBlockInfo.add(new long[]{compSize, decompSize, rbFileOffset});
            rbFileOffset += compSize;
        }

        return new MDictDictionary(id, filePath, tags, keys,
                recordOffsets, recordBlockInfo, channel, afterRbHeader + rbiSize);
    }

    // -----------------------------------------------------------------------
    // Dictionary interface
    // -----------------------------------------------------------------------

    @Override @NonNull public String getId()   { return id; }
    @Override @NonNull public String getLabel() {
        String label = tags.get("title");
        return (label != null && !label.isEmpty()) ? label : shortName(filePath);
    }
    @Override @NonNull public String getUri() {
        String uri = tags.get("website");
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
            long nextOffset = (idx + 1 < keys.size()) ? recordOffsets[idx + 1] : Long.MAX_VALUE;
            byte[] data = readRecord(offset, nextOffset);
            if (data == null) return null;
            // Trim null terminator if present
            int len = data.length;
            while (len > 0 && data[len - 1] == 0) len--;
            String html = new String(data, 0, len, StandardCharsets.UTF_8);
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
     */
    @Nullable
    DictionaryContent getResourceContent(@NonNull String resourcePath) {
        // Normalise: strip leading backslash used by MDict
        String key = resourcePath.startsWith("\\") ? resourcePath.substring(1) : resourcePath;
        int idx = Collections.binarySearch(keys, key, (a, b) -> a.compareToIgnoreCase(b));
        if (idx < 0) return null;
        long offset = recordOffsets[idx];
        long nextOffset = (idx + 1 < keys.size()) ? recordOffsets[idx + 1] : Long.MAX_VALUE;
        byte[] data = readRecord(offset, nextOffset);
        if (data == null) return null;
        return new DictionaryContent(guessMimeType(key),
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

    /** Reads a decoded record for a given decompressed stream offset. */
    @Nullable
    private byte[] readRecord(long offset, long nextOffset) {
        // Walk record blocks to find which block contains this offset
        long decompStart = 0;
        for (long[] info : recordBlockInfo) {
            long compSize   = info[0];
            long decompSize = info[1];
            long fileOffset = info[2];

            if (decompStart + decompSize > offset) {
                // This block contains our record
                try {
                    byte[] block = readBlock(fileChannel, fileOffset, compSize, decompSize);
                    int start = (int) (offset - decompStart);
                    int end;
                    if (nextOffset != Long.MAX_VALUE && nextOffset - decompStart <= decompSize) {
                        end = (int) (nextOffset - decompStart);
                    } else {
                        // find null terminator
                        end = block.length;
                        for (int i = start; i < block.length - 1; i++) {
                            if (block[i] == 0 && block[i + 1] == 0) {
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
                                            boolean keysAreUtf16le) throws IOException {
        // KBI block header: 4-byte compression type (LE u32) + 4-byte checksum + data
        ByteBuffer raw = ByteBuffer.allocate((int) kbiSize);
        readFully(channel, raw, offset);
        // Compression method is in the lowest nibble of the first byte (LE u32).
        int compType = raw.get(0) & 0x0f;
        raw.position(8); // skip 4-byte enc field + 4-byte checksum
        byte[] data = new byte[raw.remaining()];
        raw.get(data);

        byte[] decompressed;
        if (compType == COMP_ZLIB) {
            decompressed = zlibDecompress(data, numBlocks * 40);
        } else {
            decompressed = data;
        }

        // Each entry: num_entries(8) + first_key(2+len+1) + last_key(2+len+1) + comp_size(8) + decomp_size(8)
        long[] sizes = new long[numBlocks * 2];
        ByteBuffer bb = ByteBuffer.wrap(decompressed);
        bb.order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numBlocks && bb.remaining() >= 8; i++) {
            /* long numEntries = */ bb.getLong();
            skipKeyString(bb, keysAreUtf16le);
            skipKeyString(bb, keysAreUtf16le);
            long cs = bb.getLong();
            long ds = bb.getLong();
            sizes[i * 2]     = cs;
            sizes[i * 2 + 1] = ds;
        }
        return sizes;
    }

    private static void skipKeyString(@NonNull ByteBuffer bb, boolean utf16le) {
        if (bb.remaining() < 2) return;
        int len = bb.getShort() & 0xFFFF; // number of encoding units (chars for UTF-16LE, bytes for others)
        // For single-byte encodings: len bytes + 1 null byte = len+1 bytes total
        // For UTF-16LE: (len+1) code units * 2 bytes each = (len+1)*2 bytes total
        int bytesToSkip = utf16le ? (len + 1) * 2 : (len + 1);
        if (bb.remaining() >= bytesToSkip) {
            bb.position(bb.position() + bytesToSkip);
        }
    }

    @NonNull
    private static byte[] zlibDecompress(@NonNull byte[] data, int expectedSize) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        byte[] out = new byte[Math.max(expectedSize, data.length * 4)];
        int totalRead = 0;
        try {
            while (!inf.finished()) {
                int need = out.length - totalRead;
                if (need == 0) {
                    // grow buffer
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
                "Format", "CreationDate", "Website", "Copyright", "Author"}) {
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
