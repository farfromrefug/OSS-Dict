package itkach.aard2.article;

import android.app.Application;
import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.lookup.LookupResult;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.utils.ThreadUtils;
import itkach.aard2.utils.Utils;
import itkach.slob.Slob;

public class ArticleCollectionViewModel extends AndroidViewModel {
    public static final String TAG = ArticleCollectionViewModel.class.getSimpleName();
    private final itkach.aard2.Application application;
    private final MutableLiveData<BlobListWrapper> blobListLiveData = new MutableLiveData<>();
    private final MutableLiveData<CharSequence> failureMessageLiveData = new MutableLiveData<>();

    public ArticleCollectionViewModel(@NonNull Application application) {
        super(application);
        this.application = (itkach.aard2.Application) application;
    }

    public LiveData<BlobListWrapper> getBlobListLiveData() {
        return blobListLiveData;
    }

    public LiveData<CharSequence> getFailureMessageLiveData() {
        return failureMessageLiveData;
    }
    
    public openUrlInBrowser(@NonNull Uri url) {
        Context context = getApplication().getApplicationContext();
        Intent intent = new Intent(Intent.ACTION_VIEW, url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        // Get all apps that can handle this intent
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        
        for (ResolveInfo resolveInfo : resolveInfos) {
            String packageName = resolveInfo.activityInfo.packageName;

            // Skip your own app's package
            if (!packageName.equals(context.getPackageName())) {
                intent.setPackage(packageName);
                context.startActivity(intent);
                break;
            }
        }

    }

    public void loadBlobList(@NonNull Intent intent) {
        ThreadUtils.postOnBackgroundThread(() -> {
            Uri articleUri = intent.getData();
            int currentPosition = intent.getIntExtra("position", 0);
            try {
                BlobListWrapper result;
                if (articleUri != null) {
                    result = createFromUri(articleUri, intent);
                } else {
                    String action = intent.getAction();
                    if (action == null) {
                        result = createFromLastResult();
                    } else if (action.equals(ArticleCollectionActivity.ACTION_BOOKMARKS)) {
                        result = createFromBookmarks();
                    } else if (action.equals(ArticleCollectionActivity.ACTION_HISTORY)) {
                        result = createFromHistory();
                    } else {
                        result = createFromIntent(intent);
                    }
                }
                // Deliver result
                if (result != null) {
                    int resultCount = result.size();
                    if (resultCount != 0) {
                        blobListLiveData.postValue(result);
                    } else if (currentPosition >= resultCount) {
                        failureMessageLiveData.postValue(application.getString(R.string.article_collection_selected_not_available));
                        if (AppPrefs.openMissingInBrowser()) {
                            openUrlInBrowser(articleUri);
                        }
                    } else {
                        failureMessageLiveData.postValue(application.getString(R.string.article_collection_nothing_found));
                        if (AppPrefs.openMissingInBrowser()) {
                            openUrlInBrowser(articleUri);
                        }
                    }
                } else {
                    failureMessageLiveData.postValue(application.getString(R.string.article_collection_invalid_link));
                }
            } catch (Exception e) {
                failureMessageLiveData.postValue(e.getLocalizedMessage());
            }
        });
    }

    @Nullable
    private BlobListWrapper createFromUri(@NonNull Uri articleUrl, @NonNull Intent intent) {
        String host = articleUrl.getHost();
        if (!(host.startsWith("localhost") || host.startsWith(SlobHelper.LOCALHOST))) {
            return createFromIntent(intent);
        }
        BlobDescriptor bd = BlobDescriptor.fromUri(articleUrl);
        if (bd == null) {
            return null;
        }
        Iterator<Slob.Blob> result = SlobHelper.getInstance().find(bd.key, bd.slobId);
        LookupResult lookupResult = new LookupResult(20, 1);
        lookupResult.setResult(result);
        boolean hasFragment = !TextUtils.isEmpty(bd.fragment);
        return new LookupResultWrapper(lookupResult, hasFragment ? new ToBlobWithFragment(bd.fragment) : item -> item);
    }

    @NonNull
    private BlobListWrapper createFromLastResult() {
        return new LookupResultWrapper(SlobHelper.getInstance().lastLookupResult, item -> item);
    }

    @NonNull
    private BlobListWrapper createFromBookmarks() {
        return new BlobDescriptorListWrapper(SlobHelper.getInstance().bookmarks);
    }

    @NonNull
    private BlobDescriptorListWrapper createFromHistory() {
        return new BlobDescriptorListWrapper(SlobHelper.getInstance().history);
    }

    @NonNull
    private BlobListWrapper createFromIntent(@NonNull Intent intent) {
        String lookupKey = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (Objects.equals(intent.getAction(), Intent.ACTION_PROCESS_TEXT)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                lookupKey = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString();
            }
        }
        if (lookupKey == null) {
            lookupKey = intent.getStringExtra(SearchManager.QUERY);
        }
        if (lookupKey == null) {
            lookupKey = intent.getStringExtra("EXTRA_QUERY");
        }
        String preferredSlobId = null;
        if (lookupKey == null) {
            Uri uri = intent.getData();
            if (uri != null) {
                List<String> segments = uri.getPathSegments();
                int length = segments.size();
                if (length > 0) {
                    lookupKey = segments.get(length - 1);
                }
                if (lookupKey.equals("Special:Search")){
                    lookupKey = uri.getQueryParameter("search");
                }
                String slobUri = Utils.wikipediaToSlobUri(uri);
                Log.d(TAG, String.format("Converted URI %s to slob URI %s", uri, slobUri));
                if (slobUri != null) {
                    Slob slob = SlobHelper.getInstance().findSlob(slobUri);
                    if (slob != null) {
                        preferredSlobId = slob.getId().toString();
                        Log.d(TAG, String.format("Found slob %s for slob URI %s", preferredSlobId, slobUri));
                    }
                }
            }
        }
        if (TextUtils.isEmpty(lookupKey)) {
            String msg = application.getString(R.string.article_collection_nothing_to_lookup);
            throw new RuntimeException(msg);
        }
        LookupResult lookupResult = new LookupResult(20, 1);
        Iterator<Slob.Blob> result = stemLookup(lookupKey, preferredSlobId);
        lookupResult.setResult(result);
        // Update the query for the main activity
        ((itkach.aard2.Application) getApplication()).lookupAsync(lookupKey);
        return new LookupResultWrapper(lookupResult, item -> item);
    }

    @NonNull
    private Iterator<Slob.Blob> stemLookup(@NonNull String lookupKey, @Nullable String preferredSlobId) {
        Slob.PeekableIterator<Slob.Blob> result;
        final int length = lookupKey.length();
        String currentLookupKey = lookupKey;
        int currentLength = currentLookupKey.length();
        do {
            result = SlobHelper.getInstance().find(currentLookupKey, preferredSlobId, true);
            if (result.hasNext()) {
                Slob.Blob b = result.peek();
                if (b.key.length() - length > 3) {
                    // We don't like this result
                } else {
                    break;
                }
            }
            currentLookupKey = currentLookupKey.substring(0, currentLength - 1);
            currentLength = currentLookupKey.length();
        } while (length - currentLength < 5 && currentLength > 0);
        return result;
    }

    private static class ToBlobWithFragment implements LookupResultWrapper.ToBlob<Slob.Blob> {
        @NonNull
        private final String fragment;

        ToBlobWithFragment(@NonNull String fragment) {
            this.fragment = fragment;
        }

        @Override
        @Nullable
        public Slob.Blob convert(@Nullable Slob.Blob item) {
            if (item == null) {
                return null;
            }
            return new Slob.Blob(item.owner, item.id, item.key, this.fragment);
        }
    }
}
