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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.DescriptorStore;
import itkach.aard2.descriptor.SlobDescriptor;
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
    private final Object slobsLock = new Object();
    private final Map<String, Slob> slobMap = new HashMap<>();
    private final List<Slob> slobs = new ArrayList<>();
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
            while (true) {
                int value = 1 + (int) Math.floor((65535 - 1025) * rand.nextDouble());
                portCandidate = 1024 + value;
                if (seen.contains(portCandidate)) {
                    continue;
                }
                attemptCount += 1;
                seen.add(portCandidate);
                Exception lastError;
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
        // Load dictionaries, bookmarks and history
        dictionaries.load();
        bookmarks.load();
        history.load();
    }

    public void updateSlobs() {
        checkInitialized();
        synchronized (slobsLock) {
            slobs.clear();
            slobMap.clear();
            for (SlobDescriptor sd : dictionaries) {
                String origSlobId = sd.id;
                Slob s = sd.load(application);
                if (s != null) {
                    if (dictStore != null && !origSlobId.equals(sd.id)) {
                        Log.d(TAG, String.format("%s has been replaced, updating dict store %s -> %s", sd.path, origSlobId, sd.id));
                        //dictionary file has been replaced
                        //(same file name, different slob uuid)
                        //need to update store accordingly
                        dictStore.delete(origSlobId);
                        dictStore.save(sd);
                    }
                    slobs.add(s);
                }
            }
            for (Slob s : slobs) {
                slobMap.put(s.getId().toString(), s);
            }
        }
    }

    @NonNull
    public Slob[] getActiveSlobs() {
        checkInitialized();
        List<Slob> result = new ArrayList<>(dictionaries.size());
        synchronized (slobsLock) {
            for (SlobDescriptor sd : dictionaries) {
                if (sd.active) {
                    Slob s = slobMap.get(sd.id);
                    if (s != null) {
                        result.add(s);
                    }
                }
            }
        }
        return result.toArray(new Slob[0]);
    }

    @NonNull
    public Slob[] getFavoriteSlobs() {
        checkInitialized();
        List<Slob> result = new ArrayList<>(dictionaries.size());
        synchronized (slobsLock) {
            for (SlobDescriptor sd : dictionaries) {
                if (sd.active && sd.priority > 0) {
                    Slob s = slobMap.get(sd.id);
                    if (s != null) {
                        result.add(s);
                    }
                }
            }
        }
        return result.toArray(new Slob[0]);
    }

    @NonNull
    public Uri getHttpUri(@NonNull Slob.Blob blob) {
        // http://host:port/<auth>/<slob-id>/<key>?blob=<blob-id>#<fragment>
        return new Uri.Builder()
                .scheme("http")
                .authority(LOCALHOST + ":" + port)
                .appendPath(SlobServer.getAuthKey())
                .appendPath(blob.owner.getId().toString())
                .appendPath(blob.key)
                .appendQueryParameter("blob", blob.id)
                .fragment(blob.fragment)
                .build();
    }

    @Nullable
    public Slob getSlob(String slobId) {
        checkInitialized();
        synchronized (slobsLock) {
            return slobMap.get(slobId);
        }
    }

    @Nullable
    public Slob findSlob(String slobIdOrUri) {
        checkInitialized();
        Slob slob = getSlob(slobIdOrUri);
        return slob != null ? slob : findSlobByUri(slobIdOrUri);
    }

    @Nullable
    public Slob findSlobByUri(String slobURI) {
        checkInitialized();
        synchronized (slobsLock) {
            for (Slob s : slobs) {
                if (s.getURI().equals(slobURI)) {
                    return s;
                }
            }
        }
        return null;
    }

    @NonNull
    public List<Slob> findSlobsByUri(String uri) {
        checkInitialized();
        List<Slob> result = new ArrayList<>();
        synchronized (slobsLock) {
            for (Slob s : slobs) {
                if (s.getURI().equals(uri)) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    @Nullable
    public String getSlobUri(String slobId) {
        checkInitialized();
        Slob slob = getSlob(slobId);
        return slob != null ? slob.getURI() : null;
    }

    @Nullable
    public Slob.Blob findRandom() {
        checkInitialized();
        Slob[] slobs = AppPrefs.useOnlyFavoritesForRandomLookups() ? getFavoriteSlobs() : getActiveSlobs();
        Set<String> types = new HashSet<>(2);
        types.add("text/html");
        types.add("text/plain");
        return findRandom(types, slobs);
    }

    @Nullable
    private Slob.Blob findRandom(@NonNull Set<String> allowedContentTypes, @NonNull Slob[] slobs) {
        if (slobs.length > 0) {
            for (int i = 0; i < 100; i++) {
                Slob slob = slobs[random.nextInt(slobs.length)];
                int size = slob.size();
                Slob.Blob blob = slob.get(random.nextInt(size));
                String contentType = getMimeType(blob.getContentType());
                if (allowedContentTypes.contains(contentType)) {
                    return blob;
                }
            }
        }
        return null;
    }

    @NonNull
    public Iterator<Slob.Blob> find(@NonNull String key) {
        return Slob.find(key, getActiveSlobs());
    }

    @NonNull
    public Iterator<Slob.Blob> find(@NonNull String key, String preferredSlobId) {
        // When following links we want to consider all dictionaries
        // including the ones user turned off
        return find(key, preferredSlobId, false);
    }

    @NonNull
    public Slob.PeekableIterator<Slob.Blob> find(@NonNull String key, String preferredSlobId, boolean activeOnly) {
        return find(key, preferredSlobId, activeOnly, null);
    }

    @NonNull
    private Slob.PeekableIterator<Slob.Blob> find(@NonNull String key, String preferredSlobId, boolean activeOnly,
                                                  @Nullable Slob.Strength upToStrength) {
        checkInitialized();
        long t0 = System.currentTimeMillis();
        Slob[] targetSlobs;
        synchronized (slobsLock) {
            targetSlobs = activeOnly ? getActiveSlobs() : slobs.toArray(new Slob[0]);
        }
        Slob.PeekableIterator<Slob.Blob> result = Slob.find(key, targetSlobs, findSlob(preferredSlobId), upToStrength);
        Log.d(TAG, String.format("find ran in %dms", System.currentTimeMillis() - t0));
        return result;
    }

    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SlobHelper not initialized. Make sure to call init() first!");
        }
    }

    @NonNull
    private static String getMimeType(@NonNull String contentType) {
        int semiColon = contentType.indexOf(';');
        return (semiColon == -1 ? contentType : contentType.substring(0, semiColon)).trim();
    }
}
