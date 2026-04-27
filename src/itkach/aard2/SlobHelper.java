package itkach.aard2;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.DescriptorStore;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.dictionary.Dictionary;
import itkach.aard2.dictionary.DictionaryEntry;
import itkach.aard2.dictionary.SlobDictionary;
import itkach.aard2.lookup.LookupResult;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.slob.SlobServer;
import itkach.slob.Slob;

public final class SlobHelper {
    public static final String TAG = SlobHelper.class.getSimpleName();
    public static final String LOCALHOST = "127.0.0.1";
    public static final int PREFERRED_PORT = 8489;

    private static SlobHelper instance;

    public static SlobHelper getInstance() {
        if (instance == null) {
            instance = new SlobHelper(Application.get());
        }
        return instance;
    }

    @NonNull
    private final Application application;
    @NonNull
    private final ObjectMapper mapper;
    @NonNull
    private final DescriptorStore<BlobDescriptor> bookmarkStore;
    @NonNull
    private final DescriptorStore<BlobDescriptor> historyStore;
    @NonNull
    private final DescriptorStore<SlobDescriptor> dictStore;

    // -----------------------------------------------------------------------
    // Dictionary state (all access must hold dictsLock)
    // -----------------------------------------------------------------------
    private final Object dictsLock = new Object();
    /** All loaded Dictionary instances (Slob + non-Slob). */
    private final List<Dictionary> dictList = new ArrayList<>();
    /** dictId → Dictionary, for O(1) lookup by ID. */
    private final Map<String, Dictionary> dictMap = new HashMap<>();
    /** Kept separate so we can still call Slob.find() for Slob dictionaries. */
    private final List<Slob> slobs = new ArrayList<>();
    /** slobId → Slob, for backward-compatible URI resolution. */
    private final Map<String, Slob> slobMap = new HashMap<>();

    private final Random random;

    @NonNull
    public final BlobDescriptorList bookmarks;
    @NonNull
    public final BlobDescriptorList history;
    @NonNull
    public final SlobDescriptorList dictionaries;
    @NonNull
    public final LookupResult lastLookupResult;

    private int port = -1;
    private volatile boolean initialized;

    private SlobHelper(@NonNull Application application) {
        this.application = application;
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        dictStore = new DescriptorStore<>(mapper, application.getDir("dictionaries", Context.MODE_PRIVATE));
        bookmarkStore = new DescriptorStore<>(mapper, application.getDir("bookmarks", Context.MODE_PRIVATE));
        historyStore = new DescriptorStore<>(mapper, application.getDir("history", Context.MODE_PRIVATE));
        dictionaries = new SlobDescriptorList(dictStore);
        bookmarks = new BlobDescriptorList(bookmarkStore);
        history = new HistoryBlobDescriptorList(historyStore);
        lastLookupResult = new LookupResult();
        random = new Random();
    }

    @WorkerThread
    public void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        long t0 = System.currentTimeMillis();
        int portCandidate = PREFERRED_PORT;
        try {
            SlobServer.startServer(LOCALHOST, portCandidate);
            port = portCandidate;
        } catch (IOException e) {
            Log.w(TAG, String.format("Failed to start on preferred port %d", portCandidate), e);
            Set<Integer> seen = new HashSet<>();
            seen.add(PREFERRED_PORT);
            Random rand = new Random();
            int attemptCount = 0;
            Exception lastError = e;
            while (true) {
                int value = 1 + (int) Math.floor((65535 - 1025) * rand.nextDouble());
                portCandidate = 1024 + value;
                if (seen.contains(portCandidate)) {
                    continue;
                }
                attemptCount += 1;
                seen.add(portCandidate);
                try {
                    SlobServer.startServer(LOCALHOST, portCandidate);
                    port = portCandidate;
                    break;
                } catch (IOException e1) {
                    lastError = e1;
                    Log.w(TAG, String.format("Failed to start on port %d", portCandidate), e1);
                }
                if (attemptCount >= 20) {
                    throw new RuntimeException("Failed to start web server", lastError);
                }
            }
        }
        Log.d(TAG, String.format("Started web server on port %d in %d ms", port, (System.currentTimeMillis() - t0)));
        dictionaries.load();
        bookmarks.load();
        history.load();
    }

    /**
     * Rebuilds the internal dictionary maps from the current {@link #dictionaries} descriptor list.
     * Called whenever the list of dictionaries changes.
     */
    public void updateSlobs() {
        checkInitialized();
        synchronized (dictsLock) {
            dictList.clear();
            dictMap.clear();
            slobs.clear();
            slobMap.clear();

            for (SlobDescriptor sd : dictionaries) {
                String origId = sd.id;
                Dictionary dict = sd.loadDictionary(application);
                if (dict != null) {
                    if (!origId.equals(sd.id)) {
                        Log.d(TAG, String.format("%s replaced: updating store %s -> %s",
                                sd.path, origId, sd.id));
                        dictStore.delete(origId);
                        dictStore.save(sd);
                    }
                    dictList.add(dict);
                    dictMap.put(dict.getId(), dict);

                    // Keep parallel Slob tracking for backward-compatible search
                    if (dict instanceof SlobDictionary) {
                        Slob s = ((SlobDictionary) dict).getSlob();
                        slobs.add(s);
                        slobMap.put(s.getId().toString(), s);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Active / favourite subsets
    // -----------------------------------------------------------------------

    @NonNull
    public Dictionary[] getActiveDictionaries() {
        checkInitialized();
        List<Dictionary> result = new ArrayList<>();
        synchronized (dictsLock) {
            for (SlobDescriptor sd : dictionaries) {
                if (sd.active) {
                    Dictionary d = dictMap.get(sd.id);
                    if (d != null) result.add(d);
                }
            }
        }
        return result.toArray(new Dictionary[0]);
    }

    @NonNull
    public Dictionary[] getFavoriteDictionaries() {
        checkInitialized();
        List<Dictionary> result = new ArrayList<>();
        synchronized (dictsLock) {
            for (SlobDescriptor sd : dictionaries) {
                if (sd.active && sd.priority > 0) {
                    Dictionary d = dictMap.get(sd.id);
                    if (d != null) result.add(d);
                }
            }
        }
        return result.toArray(new Dictionary[0]);
    }

    /** @deprecated Use {@link #getActiveDictionaries()}. Kept for call-sites that still need Slob[]. */
    @NonNull
    public Slob[] getActiveSlobs() {
        checkInitialized();
        List<Slob> result = new ArrayList<>();
        synchronized (dictsLock) {
            for (SlobDescriptor sd : dictionaries) {
                if (sd.active) {
                    Slob s = slobMap.get(sd.id);
                    if (s != null) result.add(s);
                }
            }
        }
        return result.toArray(new Slob[0]);
    }

    @NonNull
    public Slob[] getFavoriteSlobs() {
        checkInitialized();
        List<Slob> result = new ArrayList<>();
        synchronized (dictsLock) {
            for (SlobDescriptor sd : dictionaries) {
                if (sd.active && sd.priority > 0) {
                    Slob s = slobMap.get(sd.id);
                    if (s != null) result.add(s);
                }
            }
        }
        return result.toArray(new Slob[0]);
    }

    // -----------------------------------------------------------------------
    // HTTP URI construction
    // -----------------------------------------------------------------------

    /**
     * Builds the HTTP URI used to serve a {@link DictionaryEntry} via the
     * embedded web server.
     *
     * URL format: {@code http://host:port/<auth>/<dictId>/<key>?blob=<blobId>#<fragment>}
     */
    @NonNull
    public Uri getHttpUri(@NonNull DictionaryEntry entry) {
        Uri.Builder builder = new Uri.Builder()
                .scheme("http")
                .encodedAuthority(LOCALHOST + ":" + port)
                .appendPath(SlobServer.getAuthKey())
                .appendPath(entry.owner.getId())
                .appendPath(entry.key)
                .appendQueryParameter("blob", entry.id);
        if (entry.fragment != null && !entry.fragment.isEmpty()) {
            builder.fragment(entry.fragment);
        }
        return builder.build();
    }

    /** Builds the HTTP URI for a legacy {@link Slob.Blob}. */
    @NonNull
    public Uri getHttpUri(@NonNull Slob.Blob blob) {
        return new Uri.Builder()
                .scheme("http")
                .encodedAuthority(LOCALHOST + ":" + port)
                .appendPath(SlobServer.getAuthKey())
                .appendPath(blob.owner.getId().toString())
                .appendPath(blob.key)
                .appendQueryParameter("blob", blob.id)
                .fragment(blob.fragment)
                .build();
    }

    // -----------------------------------------------------------------------
    // Dictionary lookup by ID / URI
    // -----------------------------------------------------------------------

    @Nullable
    public Dictionary getDictionary(@Nullable String dictId) {
        if (dictId == null) return null;
        checkInitialized();
        synchronized (dictsLock) {
            return dictMap.get(dictId);
        }
    }

    @Nullable
    public Dictionary findDictionary(@NonNull String dictIdOrUri) {
        checkInitialized();
        Dictionary d = getDictionary(dictIdOrUri);
        return d != null ? d : findDictionaryByUri(dictIdOrUri);
    }

    @Nullable
    public Dictionary findDictionaryByUri(@NonNull String uri) {
        checkInitialized();
        synchronized (dictsLock) {
            for (Dictionary d : dictList) {
                if (d.getUri().equals(uri)) return d;
            }
        }
        return null;
    }

    @NonNull
    public List<Dictionary> findDictionariesByUri(@NonNull String uri) {
        checkInitialized();
        List<Dictionary> result = new ArrayList<>();
        synchronized (dictsLock) {
            for (Dictionary d : dictList) {
                if (d.getUri().equals(uri)) result.add(d);
            }
        }
        return result;
    }

    @Nullable
    public String getDictionaryUri(@Nullable String dictId) {
        Dictionary d = getDictionary(dictId);
        return d != null ? d.getUri() : null;
    }

    // -----------------------------------------------------------------------
    // Backward-compatible Slob lookup (used by SlobServer and BlobDescriptorList)
    // -----------------------------------------------------------------------

    @Nullable
    public Slob getSlob(@Nullable String slobId) {
        if (slobId == null) return null;
        checkInitialized();
        synchronized (dictsLock) {
            return slobMap.get(slobId);
        }
    }

    @Nullable
    public Slob findSlob(@Nullable String slobIdOrUri) {
        if (slobIdOrUri == null) return null;
        checkInitialized();
        Slob s = getSlob(slobIdOrUri);
        return s != null ? s : findSlobByUri(slobIdOrUri);
    }

    @Nullable
    public Slob findSlobByUri(@NonNull String slobURI) {
        checkInitialized();
        synchronized (dictsLock) {
            for (Slob s : slobs) {
                if (s.getURI().equals(slobURI)) return s;
            }
        }
        return null;
    }

    @NonNull
    public List<Slob> findSlobsByUri(@NonNull String uri) {
        checkInitialized();
        List<Slob> result = new ArrayList<>();
        synchronized (dictsLock) {
            for (Slob s : slobs) {
                if (s.getURI().equals(uri)) result.add(s);
            }
        }
        return result;
    }

    @Nullable
    public String getSlobUri(@Nullable String slobId) {
        Slob slob = getSlob(slobId);
        return slob != null ? slob.getURI() : null;
    }

    // -----------------------------------------------------------------------
    // Random article
    // -----------------------------------------------------------------------

    @Nullable
    public DictionaryEntry findRandom() {
        checkInitialized();
        Dictionary[] dicts = AppPrefs.useOnlyFavoritesForRandomLookups()
                ? getFavoriteDictionaries() : getActiveDictionaries();
        Set<String> types = new HashSet<>(2);
        types.add("text/html");
        types.add("text/plain");
        return findRandom(types, dicts);
    }

    @Nullable
    private DictionaryEntry findRandom(@NonNull Set<String> allowedTypes,
                                        @NonNull Dictionary[] dicts) {
        if (dicts.length == 0) return null;
        for (int i = 0; i < 100; i++) {
            Dictionary dict = dicts[random.nextInt(dicts.length)];
            int sz = dict.size();
            if (sz == 0) continue;
            DictionaryEntry entry = dict.get(random.nextInt(sz));
            if (entry == null) continue;
            String ct = getMimeType(dict.getContentType(entry.id));
            if (allowedTypes.contains(ct)) return entry;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Searches all active dictionaries for {@code key}.
     * Slob dictionaries use the merge-sorted {@link Slob#find} algorithm;
     * non-Slob dictionaries are searched in parallel and appended.
     */
    @NonNull
    public Iterator<DictionaryEntry> find(@NonNull String key) {
        return findInternal(key, null, true, null);
    }

    @NonNull
    public Iterator<DictionaryEntry> find(@NonNull String key, @Nullable String preferredDictId) {
        return find(key, preferredDictId, false);
    }

    @NonNull
    public PeekableEntryIterator find(@NonNull String key,
                                       @Nullable String preferredDictId,
                                       boolean activeOnly) {
        return find(key, preferredDictId, activeOnly, null);
    }

    @NonNull
    public PeekableEntryIterator find(@NonNull String key,
                                       @Nullable String preferredDictId,
                                       boolean activeOnly,
                                       @Nullable Slob.Strength upToStrength) {
        return findInternal(key, preferredDictId, activeOnly, upToStrength);
    }

    @NonNull
    private PeekableEntryIterator findInternal(@NonNull String key,
                                                @Nullable String preferredDictId,
                                                boolean activeOnly,
                                                @Nullable Slob.Strength upToStrength) {
        checkInitialized();
        long t0 = System.currentTimeMillis();

        List<Slob> targetSlobs;
        List<Dictionary> nonSlobDicts;

        synchronized (dictsLock) {
            if (activeOnly) {
                targetSlobs = new ArrayList<>();
                nonSlobDicts = new ArrayList<>();
                for (SlobDescriptor sd : dictionaries) {
                    if (!sd.active) continue;
                    Dictionary d = dictMap.get(sd.id);
                    if (d == null) continue;
                    if (d instanceof SlobDictionary) {
                        targetSlobs.add(((SlobDictionary) d).getSlob());
                    } else {
                        nonSlobDicts.add(d);
                    }
                }
            } else {
                targetSlobs = new ArrayList<>(slobs);
                nonSlobDicts = new ArrayList<>();
                for (Dictionary d : dictList) {
                    if (!(d instanceof SlobDictionary)) nonSlobDicts.add(d);
                }
            }
        }

        Slob preferredSlob = findSlob(preferredDictId);
        Slob.PeekableIterator<Slob.Blob> slobResult = Slob.find(
                key, targetSlobs.toArray(new Slob[0]), preferredSlob, upToStrength);

        // Wrap the Slob result as DictionaryEntry stream
        Iterator<DictionaryEntry> slobEntries = new Iterator<DictionaryEntry>() {
            @Override public boolean hasNext() { return slobResult.hasNext(); }
            @Override public DictionaryEntry next() {
                Slob.Blob b = slobResult.next();
                String slobId = b.owner.getId().toString();
                synchronized (dictsLock) {
                    Dictionary d = dictMap.get(slobId);
                    if (d instanceof SlobDictionary) {
                        return ((SlobDictionary) d).fromBlob(b);
                    }
                }
                // fallback: create an ad-hoc wrapper
                SlobDictionary sd = new SlobDictionary(b.owner);
                return sd.fromBlob(b);
            }
        };

        // Chain non-Slob dictionaries
        // Use SECONDARY strength for case-insensitive search
        List<Iterator<DictionaryEntry>> allIters = new ArrayList<>();
        allIters.add(slobEntries);
        for (Dictionary d : nonSlobDicts) {
            // Preferred dictionary goes first among non-Slob dicts
            if (d.getId().equals(preferredDictId)) {
                allIters.add(0, d.find(key, Slob.Strength.SECONDARY));
            } else {
                allIters.add(d.find(key, Slob.Strength.SECONDARY));
            }
        }

        final Iterator<DictionaryEntry> chain = chainIterators(allIters);
        Log.d(TAG, String.format("find ran in %dms", System.currentTimeMillis() - t0));

        // Wrap in PeekableEntryIterator
        return new PeekableEntryIterator() {
            private DictionaryEntry peeked = null;
            private boolean hasPeeked = false;

            @Override
            public DictionaryEntry peek() {
                if (!hasPeeked) {
                    peeked = chain.hasNext() ? chain.next() : null;
                    hasPeeked = true;
                }
                return peeked;
            }

            @Override
            public boolean hasNext() {
                return hasPeeked ? peeked != null : chain.hasNext();
            }

            @Override
            public DictionaryEntry next() {
                if (hasPeeked) {
                    hasPeeked = false;
                    if (peeked == null) throw new NoSuchElementException();
                    return peeked;
                }
                return chain.next();
            }
        };
    }

    /** Simple chain of iterators: exhausts them in order. */
    @NonNull
    private static <T> Iterator<T> chainIterators(@NonNull List<Iterator<T>> iters) {
        return new Iterator<T>() {
            int i = 0;
            @Override public boolean hasNext() {
                while (i < iters.size() && !iters.get(i).hasNext()) i++;
                return i < iters.size();
            }
            @Override public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                return iters.get(i).next();
            }
        };
    }

    // -----------------------------------------------------------------------
    // PeekableEntryIterator
    // -----------------------------------------------------------------------

    public interface PeekableEntryIterator extends Iterator<DictionaryEntry> {
        DictionaryEntry peek();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SlobHelper not initialized. Call init() first!");
        }
    }

    @NonNull
    public static String getMimeType(@NonNull String contentType) {
        int sc = contentType.indexOf(';');
        return (sc == -1 ? contentType : contentType.substring(0, sc)).trim();
    }
}
