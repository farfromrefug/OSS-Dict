package itkach.aard2.dictionary.stardict;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import itkach.aard2.dictionary.DictionaryContent;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.slob.Slob;

/**
 * Unit tests for {@link StarDictDictionary} parsing and lookup.
 *
 * <p>Uses hand-crafted binary test files stored under
 * {@code src/test/resources/itkach/aard2/dictionary/stardict/}.  The test
 * dictionary contains three entries: "bonjour", "hello", "world".</p>
 */
public class StarDictDictionaryTest {

    private static final String RES_DIR =
            "/itkach/aard2/dictionary/stardict/test-eng-fra";

    private StarDictDictionary dict;

    @Before
    public void setUp() throws Exception {
        Map<String, String> ifoTags = readIfo(RES_DIR + ".ifo");
        byte[] idxData   = readResource(RES_DIR + ".idx");
        byte[] dictData  = readResource(RES_DIR + ".dict");

        dict = StarDictDictionary.parse(ifoTags, idxData, null, dictData, "test-eng-fra");
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    @Test
    public void keysAreNotEmpty() {
        assertTrue("keys must be loaded from .idx", dict.size() > 0);
        assertEquals("expect 3 entries", 3, dict.size());
    }

    @Test
    public void labelFromIfo() {
        assertEquals("Test English-French", dict.getLabel());
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
        // Use SECONDARY_PREFIX for case-insensitive prefix search
        Iterator<DictionaryEntry> it = dict.find("hello", Slob.Strength.SECONDARY_PREFIX);
        assertTrue("prefix 'hel' should match 'hello'", it.hasNext());
        DictionaryEntry entry = it.next();
        assertEquals("hello", entry.key);
    }

    @Test
    public void findNonExistent() {
        Iterator<DictionaryEntry> it = dict.find("zzz", Slob.Strength.QUATERNARY);
        assertFalse("'zzz' should not be found", it.hasNext());
    }

    @Test
    public void findBonjour() {
        Iterator<DictionaryEntry> it = dict.find("bonjour", Slob.Strength.QUATERNARY);
        assertTrue("find('bonjour') must return a result", it.hasNext());
        assertEquals("bonjour", it.next().key);
    }

    @Test
    public void findWorld() {
        Iterator<DictionaryEntry> it = dict.find("world", Slob.Strength.QUATERNARY);
        assertTrue("find('world') must return a result", it.hasNext());
        assertEquals("world", it.next().key);
    }

    // ── getContent() ─────────────────────────────────────────────────────────

    @Test
    public void getContentForHello() {
        Iterator<DictionaryEntry> it = dict.find("hello", Slob.Strength.QUATERNARY);
        assertTrue(it.hasNext());
        DictionaryEntry entry = it.next();
        DictionaryContent content = entry.getContent();
        assertNotNull("content must not be null", content);
        String text = StandardCharsets.UTF_8.decode(content.data).toString();
        assertEquals("hello -> bonjour", text);
    }

    @Test
    public void getContentForBonjour() {
        Iterator<DictionaryEntry> it = dict.find("bonjour", Slob.Strength.QUATERNARY);
        assertTrue(it.hasNext());
        DictionaryContent content = it.next().getContent();
        assertNotNull(content);
        String text = StandardCharsets.UTF_8.decode(content.data).toString();
        assertEquals("Au revoir", text);
    }

    @Test
    public void getContentForWorld() {
        Iterator<DictionaryEntry> it = dict.find("world", Slob.Strength.QUATERNARY);
        assertTrue(it.hasNext());
        DictionaryContent content = it.next().getContent();
        assertNotNull(content);
        String text = StandardCharsets.UTF_8.decode(content.data).toString();
        assertEquals("monde", text);
    }

    // ── get(int) random access ────────────────────────────────────────────────

    @Test
    public void randomAccessReturnsEntry() {
        DictionaryEntry e = dict.get(0);
        assertNotNull(e);
        assertFalse(e.key.isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, String> readIfo(String resourcePath) throws IOException {
        Map<String, String> tags = new HashMap<>();
        try (InputStream is = StarDictDictionaryTest.class.getResourceAsStream(resourcePath);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String magic = br.readLine();
            if (!"StarDict's dict ifo file".equals(magic)) {
                throw new IOException("Not a .ifo file: " + resourcePath);
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

    private static byte[] readResource(String path) throws IOException {
        try (InputStream is = StarDictDictionaryTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
