package itkach.aard2;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.view.WindowCompat;

import com.google.android.material.color.DynamicColors;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Future;

import itkach.aard2.article.ArticleCollectionActivity;
import itkach.aard2.lookup.LookupListener;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.slob.SlobTags;
import itkach.aard2.utils.ThreadUtils;
import itkach.slob.Slob;
import itkach.slob.Slob.Blob;

public class Application extends android.app.Application {
    private static final String TAG = Application.class.getSimpleName();
    private static Application instance;

    public static Application get() {
        return instance;
    }

    private SlobHelper slobHelper;

    @Override
    public void onCreate() {
        instance = this;
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(new ArticleCollectionActivityController());
        try {
            Method setWebContentsDebuggingEnabledMethod = WebView.class.getMethod(
                    "setWebContentsDebuggingEnabled", boolean.class);
            setWebContentsDebuggingEnabledMethod.invoke(null, true);
        } catch (NoSuchMethodException e1) {
            Log.d(TAG, "setWebContentsDebuggingEnabledMethod method not found");
        } catch (InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        }
        slobHelper = SlobHelper.getInstance();

        slobHelper.dictionaries.registerDataSetObserver(new DataSetObserver() {
            @Override
            synchronized public void onChanged() {
                slobHelper.lastLookupResult.setResult(Collections.emptyIterator());
                slobHelper.updateSlobs();
                ThreadUtils.postOnMainThread(() -> {
                   // enableLinkHandling(slobHelper.getActiveSlobs());
                    slobHelper.bookmarks.notifyDataSetChanged();
                    slobHelper.history.notifyDataSetChanged();
                    lookupAsync(AppPrefs.getLastQuery());
                });
            }
        });

        ThreadUtils.postOnBackgroundThread(() -> slobHelper.init());
    }

    private void setLookupResult(@NonNull String query, Iterator<Slob.Blob> data) {
        slobHelper.lastLookupResult.setResult(data);
        AppPrefs.setLastQuery(query);
    }

    private Future<?> currentLookupTask;

    public void lookupAsync(@NonNull String query) {
        if (currentLookupTask != null) {
            currentLookupTask.cancel(true);
            ThreadUtils.postOnMainThread(() -> {
                notifyLookupCanceled(query);
            });
            currentLookupTask = null;
        }
        notifyLookupStarted(query);
        if (query.isEmpty()) {
            setLookupResult("", Collections.emptyIterator());
            notifyLookupFinished(query);
            return;
        }

        currentLookupTask = ThreadUtils.postOnBackgroundThread(() -> {
            Iterator<Blob> result = SlobHelper.getInstance().find(query);
            if (Thread.currentThread().isInterrupted()) return;
            ThreadUtils.postOnMainThread(() -> {
                setLookupResult(query, result);
                notifyLookupFinished(query);
            });
        });
    }

    private void notifyLookupStarted(String query) {
        ThreadUtils.postOnMainThread(() -> {
            for (LookupListener l : lookupListeners) {
                l.onLookupStarted(query);
            }
        });

    }

    private void notifyLookupFinished(String query) {
        ThreadUtils.postOnMainThread(() -> {
            for (LookupListener l : lookupListeners) {
                l.onLookupFinished(query);
            }
        });
    }

    private void notifyLookupCanceled(String query) {
        ThreadUtils.postOnMainThread(() -> {
            for (LookupListener l : lookupListeners) {
                l.onLookupCanceled(query);
            }
        });
    }

    private final List<LookupListener> lookupListeners = new ArrayList<>();

    public void addLookupListener(LookupListener listener) {
        lookupListeners.add(listener);
    }

    public void removeLookupListener(LookupListener listener) {
        lookupListeners.remove(listener);
    }

    @WorkerThread
    protected void enableLinkHandling(Slob[] slobs) {
        Set<String> hosts = new HashSet<>();
        for (Slob slob : slobs) {
            try {
                String uriValue = slob.getTags().get(SlobTags.TAG_URI);
                Uri uri = Uri.parse(uriValue);
                String host = uri.getHost();
                if (host != null) {
                    hosts.add(host.toLowerCase(Locale.ROOT));
                }
            } catch (Exception ex) {
                Log.w(TAG, String.format("Dictionary %s (%s) has no uri tag", slob.getId(), slob.getTags()), ex);
            }
        }

        long t0 = System.currentTimeMillis();
        try {
            PackageManager pm = getPackageManager();
            PackageInfo p = pm.getPackageInfo(BuildConfig.APPLICATION_ID,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_DISABLED_COMPONENTS);
            Log.d(TAG, "Done getting available activities in " + (System.currentTimeMillis() - t0));
            t0 = System.currentTimeMillis();
            if (p.activities != null) {
                for (ActivityInfo activityInfo : p.activities) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (activityInfo.targetActivity != null) {
                        boolean enabled = hosts.contains(activityInfo.name);
                        if (enabled) {
                            Log.d(TAG, "Enabling links handling for " + activityInfo.name);
                        }
                        int setting = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                        pm.setComponentEnabledSetting(new ComponentName(this, activityInfo.name), setting,
                                PackageManager.DONT_KILL_APP);
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, e);
        }
        Log.d(TAG, "Done enabling activities in " + (System.currentTimeMillis() - t0));
    }

    public static class ArticleCollectionActivityController implements ActivityLifecycleCallbacks {
        List<ArticleCollectionActivity> activeActivities = new ArrayList<>();

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            if (activity instanceof ArticleCollectionActivity) {
                activeActivities.add((ArticleCollectionActivity) activity);
                Log.d(TAG, "Activity added, stack size " + activeActivities.size());
                if (activeActivities.size() > 7) {
                    Log.d(TAG, "Max stack size exceeded, finishing oldest activity");
                    activeActivities.get(0).finish();
                }
            }
            Window window = activity.getWindow();
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            if (activity instanceof ArticleCollectionActivity) {
                activeActivities.remove(activity);
            }
        }
    }
}
