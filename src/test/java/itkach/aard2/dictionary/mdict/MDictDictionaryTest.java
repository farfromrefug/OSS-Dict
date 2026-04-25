package itkach.aard2.dictionary.mdict;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import itkach.aard2.dictionary.DictionaryContent;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.slob.Slob;

/**
 * Unit tests for {@link MDictDictionary} parsing and lookup.
 *
 * <p>Uses a hand-crafted minimal MDX v2 file stored under
 * {@code src/test/resources/itkach/aard2/dictionary/mdict/test-en-it.mdx}.
 * The file contains three entries: "ciao" → "goodbye", "hello" → "hello -> ciao",
 * "world" → "mondo".</p>
 */
public class MDictDictionaryTest {

    private static final String RES_PATH =
            "/itkach/aard2/dictionary/mdict/test-en-it.mdx";

    private MDictDictionary dict;

    @Before
    public void setUp() throws Exception {
        byte[] mdxBytes = readResource(RES_PATH);
        // Wrap the byte array in a SeekableByteChannel so we can pass it as a FileChannel
        SeekableByteChannel channel = new ByteArraySeekableChannel(mdxBytes);
        // Use the package-private parse() method directly (same package)
        dict = MDictDictionary.parse(new WrappedFileChannel(channel), "test-en-it.mdx");
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    public void keysAreLoaded() {
        assertTrue("keys must be non-empty after parsing", dict.size() > 0);
        assertEquals("expect 3 entries", 3, dict.size());
    }

    @Test
    public void blobCountMatchesKeys() {
        assertEquals(dict.size(), (int) dict.getBlobCount());
    }

    @Test
    public void labelFromHeader() {
        // The header XML has Title="Test EN-IT"
        assertFalse("label must not be empty", dict.getLabel().isEmpty());
    }

    // ── find() ───────────────────────────────────────────────────────────────

    @Test
    public void findHelloExactMatch() {
        Iterator<DictionaryEntry> it = dict.find("hello", Slob.Strength.QUATERNARY);
        assertTrue("find('hello') must return at least one result", it.hasNext());
        DictionaryEntry entry = it.next();
        assertEquals("hello", entry.key);
    }

    @Test
    public void findHelloPrefix() {
        Iterator<DictionaryEntry> it = dict.find("hel", Slob.Strength.QUATERNARY_PREFIX);
        assertTrue("prefix 'hel' should match 'hello'", it.hasNext());
    }

    @Test
    public void findNonExistent() {
        Iterator<DictionaryEntry> it = dict.find("zzz", Slob.Strength.QUATERNARY);
        assertFalse("'zzz' should not be found", it.hasNext());
    }

    @Test
    public void findCiao() {
        Iterator<DictionaryEntry> it = dict.find("ciao", Slob.Strength.QUATERNARY);
        assertTrue("'ciao' must be found", it.hasNext());
        assertEquals("ciao", it.next().key);
    }

    @Test
    public void findWorld() {
        Iterator<DictionaryEntry> it = dict.find("world", Slob.Strength.QUATERNARY);
        assertTrue("'world' must be found", it.hasNext());
        assertEquals("world", it.next().key);
    }

    // ── getContent() ─────────────────────────────────────────────────────────

    @Test
    public void getContentForHello() {
        Iterator<DictionaryEntry> it = dict.find("hello", Slob.Strength.QUATERNARY);
        assertTrue(it.hasNext());
        DictionaryContent content = it.next().getContent();
        assertNotNull("content must not be null", content);
        String text = StandardCharsets.UTF_8.decode(content.data).toString().trim()
                .replaceAll("\0+$", "");
        assertEquals("hello -> ciao", text);
    }

    @Test
    public void getContentForCiao() {
        Iterator<DictionaryEntry> it = dict.find("ciao", Slob.Strength.QUATERNARY);
        assertTrue(it.hasNext());
        DictionaryContent content = it.next().getContent();
        assertNotNull(content);
        String text = StandardCharsets.UTF_8.decode(content.data).toString().trim()
                .replaceAll("\0+$", "");
        assertEquals("goodbye", text);
    }

    @Test
    public void getContentForWorld() {
        Iterator<DictionaryEntry> it = dict.find("world", Slob.Strength.QUATERNARY);
        assertTrue(it.hasNext());
        DictionaryContent content = it.next().getContent();
        assertNotNull(content);
        String text = StandardCharsets.UTF_8.decode(content.data).toString().trim()
                .replaceAll("\0+$", "");
        assertEquals("mondo", text);
    }

    // ── get(int) random access ────────────────────────────────────────────────

    @Test
    public void randomAccessReturnsEntry() {
        DictionaryEntry e = dict.get(0);
        assertNotNull(e);
        assertFalse(e.key.isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] readResource(String path) throws IOException {
        try (InputStream is = MDictDictionaryTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * In-memory {@link SeekableByteChannel} backed by a byte array.
     * Used to wrap the MDX test data so it can be passed to
     * {@link MDictDictionary#parse} as a {@code FileChannel}.
     */
    private static final class ByteArraySeekableChannel implements SeekableByteChannel {
        private final byte[] data;
        private int pos = 0;
        private boolean open = true;

        ByteArraySeekableChannel(byte[] data) { this.data = data; }

        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }

        @Override
        public int read(ByteBuffer dst) {
            if (pos >= data.length) return -1;
            int count = Math.min(dst.remaining(), data.length - pos);
            dst.put(data, pos, count);
            pos += count;
            return count;
        }

        @Override
        public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }

        @Override public long position() { return pos; }

        @Override
        public SeekableByteChannel position(long newPosition) {
            pos = (int) newPosition;
            return this;
        }

        @Override public long size() { return data.length; }

        @Override
        public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }
    }

    /**
     * Adapts a {@link SeekableByteChannel} to look like a
     * {@code java.nio.channels.FileChannel} for use with
     * {@link MDictDictionary#parse}.
     *
     * <p>Only the {@code read(ByteBuffer, long)} and {@code isOpen()} methods
     * are implemented, since those are the only ones called by the parser.</p>
     */
    static final class WrappedFileChannel extends java.nio.channels.FileChannel {
        private final SeekableByteChannel delegate;

        WrappedFileChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            delegate.position(position);
            return delegate.read(dst);
        }

        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void implCloseChannel() { /* no-op */ }

        // ── Unused FileChannel abstract methods ──────────────────────────────
        @Override public int read(ByteBuffer dst)  { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] dsts, int off, int len) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] srcs, int off, int len) { throw new UnsupportedOperationException(); }
        @Override public long position() { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileChannel position(long pos) { throw new UnsupportedOperationException(); }
        @Override public long size() { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean metaData) { }
        @Override public long transferTo(long pos, long count, java.nio.channels.WritableByteChannel target) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(java.nio.channels.ReadableByteChannel src, long pos, long count) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src, long pos) { throw new UnsupportedOperationException(); }
        @Override public java.nio.MappedByteBuffer map(MapMode mode, long pos, long size) { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileLock lock(long pos, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public java.nio.channels.FileLock tryLock(long pos, long size, boolean shared) { throw new UnsupportedOperationException(); }
    }
}
