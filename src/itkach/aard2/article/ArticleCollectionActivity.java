package itkach.aard2.article;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Toast;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NavUtils;
import androidx.core.app.TaskStackBuilder;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerTitleStrip;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.List;
import java.util.Objects;

import itkach.aard2.BuildConfig;
import itkach.aard2.MainActivity;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.prefs.ArticleCollectionPrefs;
import itkach.aard2.slob.SlobTags;
import itkach.aard2.utils.ThreadUtils;
import itkach.aard2.utils.Utils;
import itkach.aard2.widget.ArticleWebView;
import itkach.slob.Slob;

public class ArticleCollectionActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String ACTION_BOOKMARKS = BuildConfig.APPLICATION_ID + ".action.BOOKMARKS";
    public static final String ACTION_HISTORY = BuildConfig.APPLICATION_ID + ".action.HISTORY";

    private static final String TAG = ArticleCollectionActivity.class.getSimpleName();

    private ArticleCollectionPagerAdapter pagerAdapter;
    private ViewPager2 viewPager;
    private ArticleCollectionViewModel viewModel;
    private boolean isHistory;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        Utils.updateNightMode();
        setContentView(R.layout.activity_article_collection_loading);
        setSupportActionBar(findViewById(R.id.toolbar));
        viewModel = new ViewModelProvider(this).get(ArticleCollectionViewModel.class);

        final ActionBar actionBar = requireActionBar();
        actionBar.hide();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setSubtitle("...");

        final Intent intent = getIntent();
        final int position = intent.getIntExtra("position", 0);
        isHistory = ArticleCollectionActivity.ACTION_HISTORY.equals(intent.getAction());

        viewModel.getBlobListLiveData().observe(this, blobListWrapper -> {
            if (blobListWrapper == null) {
                // Adapter is never null, empty or less than |position|
                return;
            }

            setContentView(R.layout.activity_article_collection);
            setSupportActionBar(findViewById(R.id.toolbar));
            requireActionBar().setDisplayHomeAsUpEnabled(true);

            TabLayout tabs = findViewById(R.id.tabs);
            tabs.setVisibility(
                    blobListWrapper.size() == 1 ? ViewGroup.GONE : ViewGroup.VISIBLE);

            viewPager = findViewById(R.id.pager);
            viewPager.setNestedScrollingEnabled(true);
            pagerAdapter = new ArticleCollectionPagerAdapter(blobListWrapper, this);
            viewPager.setAdapter(pagerAdapter);
            TabLayoutMediator tabLayoutMediator = new TabLayoutMediator(tabs, viewPager, true, (tab, position1) -> {
                if (!ArticleCollectionPrefs.disableTabLabels()) {
                    tab.setText(pagerAdapter.getPageTitle(position1));
                }
            });
            tabLayoutMediator.attach();
            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageScrollStateChanged(int arg0) {
                }

                @Override
                public void onPageScrolled(int arg0, float arg1, int arg2) {
                }

                @Override
                public void onPageSelected(final int position) {
                    updateTitle(position);
                    ArticleFragment fragment = pagerAdapter.getPageFragment(position);
                    if (fragment != null) {
                        pagerAdapter.setPrimaryItem(fragment);
                        runOnUiThread(() -> {
                            fragment.applyTextZoomPref();
                        });
                    }

                }
            });
//            viewPager.setCurrentItem(position);
//            updateTitle(position);
            pagerAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onChanged() {
                    if (pagerAdapter.getItemCount() == 0) {
                        finish();
                    }
                }
            });
            viewPager.setCurrentItem(position, false);
            updateTitle(position);

            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout), (v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars()
                                | WindowInsetsCompat.Type.displayCutout()
                );
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                mlp.leftMargin = bars.left;
                mlp.bottomMargin = (ArticleCollectionPrefs.isFullscreen()) ? 0 : bars.bottom;
                mlp.topMargin = (ArticleCollectionPrefs.isFullscreen()) ? 0 : bars.top;
                mlp.rightMargin = bars.right;
                v.setLayoutParams(mlp);
                return WindowInsetsCompat.CONSUMED;
            });
        });
        viewModel.getFailureMessageLiveData().observe(this, message -> {
            if (message != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (AppPrefs.openMissingInBrowser() && viewModel.articleUri != null) {
                        openUrlInBrowser(viewModel.articleUri);
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    }
                    finish();
                });
            }
        });
        // Load adapter
        viewModel.loadBlobList(intent);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = bars.left;
            mlp.bottomMargin = (ArticleCollectionPrefs.isFullscreen()) ? 0 : bars.bottom;
            mlp.topMargin = bars.top;
            mlp.rightMargin = bars.right;
            v.setLayoutParams(mlp);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onBackPressed() {
        if (ArticleCollectionPrefs.isFullscreen()) {
            // Exit fullscreen
            toggleFullScreen();
            return;
        }
        super.onBackPressed();
    }

    @NonNull
    public ActionBar requireActionBar() {
        return Objects.requireNonNull(getSupportActionBar());
    }

    private void updateTitle(int position) {
        Slob.Blob blob = pagerAdapter.get(position);
        CharSequence pageTitle = pagerAdapter.getPageTitle(position);
        ActionBar actionBar = requireActionBar();
        if (blob != null) {
            String dictLabel = blob.owner.getTags().get(SlobTags.TAG_LABEL);
            actionBar.setTitle(dictLabel);
            if (!AppPrefs.disableHistory() && !isHistory) {
                SlobHelper slobHelper = SlobHelper.getInstance();
                slobHelper.history.add(slobHelper.getHttpUri(blob));
            }
        } else {
            actionBar.setTitle("???");
        }
        actionBar.setSubtitle(pageTitle);
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(ArticleCollectionPrefs.PREF_FULLSCREEN)) {
            applyFullScreenPref();
        }
    }

    private void applyFullScreenPref() {
        if (ArticleCollectionPrefs.isFullscreen()) {
            WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(),
                    getWindow().getDecorView());
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            requireActionBar().hide();
        } else {
            WindowInsetsControllerCompat windowInsetsController = WindowCompat.getInsetsController(getWindow(),
                    getWindow().getDecorView());
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars());
            requireActionBar().show();
        }
    }

    SharedPreferences prefs() {
        return getSharedPreferences("articleCollection", Activity.MODE_PRIVATE);
    }

    void toggleFullScreen() {
        ArticleCollectionPrefs.setFullscreen(!ArticleCollectionPrefs.isFullscreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "[F] Resume");
        applyFullScreenPref();
        prefs().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "[F] Pause");
        prefs().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDestroy() {
        if (viewPager != null) {
            viewPager.setAdapter(null);
        }
        if (pagerAdapter != null) {
            pagerAdapter.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            Intent upIntent = Intent.makeMainActivity(new ComponentName(this, MainActivity.class));
            if (NavUtils.shouldUpRecreateTask(this, upIntent)) {
                TaskStackBuilder.create(this)
                        .addNextIntent(upIntent).startActivities();
                finish();
            } else {
                // This activity is part of the application's task, so simply
                // navigate up to the hierarchical parent activity.
                upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(upIntent);
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (event.isCanceled()) {
            return true;
        }
        if (pagerAdapter == null) {
            return false;
        }
        ArticleFragment af = pagerAdapter.getPrimaryItem();
        if (af != null) {
            ArticleWebView webView = af.getWebView();
            if (webView != null) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (webView.canGoBack()) {
                        webView.goBack();
                        return true;
                    }
                }

                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    if (!AppPrefs.useVolumeKeysForNavigation()) {
                        return false;
                    }
                    boolean scrolled = webView.pageUp(false);
                    if (!scrolled) {
                        int current = viewPager.getCurrentItem();
                        if (current > 0) {
                            viewPager.setCurrentItem(current - 1);
                        } else {
                            finish();
                        }
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (!AppPrefs.useVolumeKeysForNavigation()) {
                        return false;
                    }
                    boolean scrolled = webView.pageDown(false);
                    if (!scrolled) {
                        int current = viewPager.getCurrentItem();
                        if (current < pagerAdapter.getItemCount() - 1) {
                            viewPager.setCurrentItem(current + 1);
                        }
                    }
                    return true;
                }
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!AppPrefs.useVolumeKeysForNavigation()) {
                return false;
            }
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (!AppPrefs.useVolumeKeysForNavigation()) {
            return false;
        }
        ArticleFragment af = pagerAdapter.getPrimaryItem();
        if (af != null) {
            ArticleWebView webView = af.getWebView();

            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                webView.pageUp(true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                webView.pageDown(true);
                return true;
            }
        }
        return super.onKeyLongPress(keyCode, event);
    }

    public static class ArticleCollectionPagerAdapter extends FragmentStateAdapter {
        private final BlobListWrapper blobListWrapper;
        private final FragmentActivity activity;
        private final DataSetObserver observer = new DataSetObserver() {
            @Override
            public void onChanged() {
                ThreadUtils.postOnMainThread(() -> notifyDataSetChanged());
            }
        };

        private ArticleFragment primaryItem;

        public ArticleCollectionPagerAdapter(@NonNull BlobListWrapper blobListWrapper, FragmentActivity activity) {
            super(activity);
            this.blobListWrapper = blobListWrapper;
            this.activity = activity;
            this.blobListWrapper.registerDataSetObserver(observer);
        }

        public void destroy() {
            blobListWrapper.unregisterDataSetObserver(observer);
        }

        public void setPrimaryItem(@NonNull ArticleFragment object) {
            this.primaryItem = (ArticleFragment) object;
        }

        public ArticleFragment getPrimaryItem() {
            return this.primaryItem;
        }

        @Override
        @NonNull
        public ArticleFragment createFragment(int i) {
            ArticleFragment fragment = new ArticleFragment();

            Slob.Blob blob = get(i);
            if (blob != null) {
                Uri articleUri = SlobHelper.getInstance().getHttpUri(blob);
                Bundle args = new Bundle();
                args.putParcelable(ArticleFragment.ARG_URI, articleUri);
                fragment.setArguments(args);
            }
            return fragment;
        }

        @Override
        public int getItemCount() {
            return blobListWrapper.size();
        }

        public Slob.Blob get(int position) {
            return blobListWrapper.get(position);
        }

        @Override
        public long getItemId(int position) {
            // we override getItemId to ensure position is the id
            // so that we can find a fragment based on a position
            return position;
        }

        public ArticleFragment getPageFragment(long id) {
            return (ArticleFragment) this.activity.getSupportFragmentManager().findFragmentByTag("f" + Long.toString(id));
        }

        public CharSequence getPageTitle(int position) {
            CharSequence label = blobListWrapper.getLabel(position);
            return label != null ? label : "???";
        }

    }
    
    public void openUrlInBrowser(@NonNull Uri url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, url);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        // Get all apps that can handle this intent
        PackageManager pm = getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        
        for (ResolveInfo resolveInfo : resolveInfos) {
            String packageName = resolveInfo.activityInfo.packageName;

            // Skip your own app's package
            if (!packageName.equals(getPackageName())) {
                intent.setPackage(packageName);
                startActivity(intent);
                break;
            }
        }

    }
}
