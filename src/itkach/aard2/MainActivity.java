package itkach.aard2;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.EdgeToEdge;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import itkach.aard2.article.ArticleCollectionActivity;
import itkach.aard2.dictionaries.DictionaryListFragment;
import itkach.aard2.lookup.LookupFragment;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.prefs.SettingsFragment;
import itkach.aard2.utils.ClipboardUtils;
import itkach.aard2.utils.Utils;

public class MainActivity extends AppCompatActivity implements NavigationBarView.OnItemSelectedListener,
        ViewPager.OnPageChangeListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private AppSectionsPagerAdapter appSectionsPagerAdapter;
    private AppBarLayout appBarLayout;
    private CoordinatorLayout layout;
    private ViewPager viewPager;
    private BottomNavigationView bottomNavigationView;
    private FloatingActionButton fab;
    private int oldPosition = -1;

    @NonNull
    public ActionBar requireActionBar() {
        return Objects.requireNonNull(getSupportActionBar());
    }

    public void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        Utils.updateNightMode();
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        appSectionsPagerAdapter = new AppSectionsPagerAdapter(getSupportFragmentManager(), AppPrefs.disableHistory());

        layout = findViewById(R.id.layout);
        appBarLayout = findViewById(R.id.appBar);
        viewPager = findViewById(R.id.pager);
        viewPager.setOffscreenPageLimit(appSectionsPagerAdapter.getCount());
        viewPager.setAdapter(appSectionsPagerAdapter);
        viewPager.setOnPageChangeListener(this);

        bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(this);
        updateHistoryTabState();

        fab = findViewById(R.id.fab);

        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState);
        } else if (SlobHelper.getInstance().dictionaries.isEmpty()) {
            viewPager.setCurrentItem(3);
        }
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            getWindow().setNavigationBarContrastEnforced(false);
//        }

//        ViewCompat.setWindowInsetsAnimationCallback(
//                findViewById(R.id.layout),
//                new ImeProgressWindowInsetAnimation(progress -> {
//                    layout.setProgress(progress);
//                    return null;
//                }, (view, insets) ->
//                {
//                    spacesWindowInsetListener(insets);
//                    return null;
//                }));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layout), (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = bars.left;
            mlp.bottomMargin = bars.bottom;
            mlp.topMargin = bars.top;
            mlp.rightMargin = bars.right;
            v.setLayoutParams(mlp);

            Insets ime  = insets.getInsets(
                    WindowInsetsCompat.Type.ime()
            );
            mlp = (CoordinatorLayout.LayoutParams) fab.getLayoutParams();
            mlp.bottomMargin = ime.bottom - bars.bottom;
            fab.setLayoutParams(mlp);

            View recyclerView = findViewById(R.id.recycler_view);
            if (recyclerView != null) {
                recyclerView.setPadding(0,0,0,ime.bottom > 0 ? ime.bottom - bars.bottom - bottomNavigationView.getHeight() : 0);
            }
            return WindowInsetsCompat.CONSUMED;
        });

    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        int currentSection = savedInstanceState.getInt("currentSection");
        viewPager.setCurrentItem(currentSection);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("currentSection", viewPager.getCurrentItem());
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        int offset = appSectionsPagerAdapter.tabHistoryDisabled ? -1 : 0;
        if (itemId == R.id.action_lookup) {
            viewPager.setCurrentItem(0);
        } else if (itemId == R.id.action_bookmarks) {
            viewPager.setCurrentItem(1);
        } else if (itemId == R.id.action_history) {
            viewPager.setCurrentItem(2);
        } else if (itemId == R.id.action_dictionaries) {
            viewPager.setCurrentItem(3 + offset);
        } else if (itemId == R.id.action_settings) {
            viewPager.setCurrentItem(4 + offset);
        } else return false;
        return true;
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
//        appBarLayout.setExpanded(true, true);
    }
    private String getFragmentTag(int fragmentPosition)
    {
        return "android:switcher:" + viewPager.getId() + ":" + fragmentPosition;
    }
    @Override
    public void onPageSelected(int position) {
//        ((HideBottomViewOnScrollBehavior)((CoordinatorLayout.LayoutParams)bottomNavigationView.getLayoutParams()).getBehavior()).slideUp(bottomNavigationView);
        if (oldPosition >= 0 && oldPosition < appSectionsPagerAdapter.getCount()) {
            bottomNavigationView.getMenu().getItem(oldPosition).setChecked(false);
            Fragment frag = getSupportFragmentManager().findFragmentByTag(getFragmentTag(oldPosition));
            if (frag instanceof BlobDescriptorListFragment) {
                ((BlobDescriptorListFragment) frag).finishActionMode();
            }
        } else {
            bottomNavigationView.getMenu().getItem(0).setChecked(false);
        }
        if (position > 0) {
            View v = this.getCurrentFocus();
            if (v != null) {
                InputMethodManager mgr = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                mgr.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            }
        }
        int offset = AppPrefs.disableHistory() && position>=2 ? 1 : 0;
        bottomNavigationView.getMenu().getItem(position + offset).setChecked(true);
        oldPosition = position;

    }

    @Override
    public void onPageScrollStateChanged(int state) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppPrefs.getPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        AppPrefs.getPreferences().unregisterOnSharedPreferenceChangeListener(this);
        //Looks like shown soft input sometimes causes a system ui visibility
        //change event that breaks article activity launched from here out of full screen mode.
        //Hiding it appears to reduce that.
        View focusedView = getCurrentFocus();
        if (focusedView != null) {
            InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            inputMethodManager.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        int currentItem = viewPager.getCurrentItem();
        Fragment frag = appSectionsPagerAdapter.getItem(currentItem);
        if (frag instanceof BlobDescriptorListFragment) {
            BlobDescriptorListFragment bdFrag = (BlobDescriptorListFragment) frag;
            if (bdFrag.isFilterExpanded()) {
                bdFrag.collapseFilter();
                return;
            }
        }
        super.onBackPressed();
    }

    public void displayFab(@DrawableRes int icon, @StringRes int description, View.OnClickListener listener) {
        fab.setImageResource(icon);
        fab.setContentDescription(getString(description));
        fab.setOnClickListener(listener);
        fab.show();
    }

    public void hideFab() {
        fab.hide();
    }

    private void updateHistoryTabState() {
        Boolean hidden = AppPrefs.disableHistory();
//        if (hidden) {
//            bottomNavigationView.getMenu().removeItem(0);
//            bottomNavigationView.inflateMenu(R.menu.activity_main_navigation_actions_without_history);
//        } else {
//            bottomNavigationView.removeAllViews();
//            bottomNavigationView.inflateMenu(R.menu.activity_main_navigation_actions);
//
//        }
        int selectedId = bottomNavigationView.getSelectedItemId();
        bottomNavigationView.getMenu().getItem(2).setVisible(!hidden);
        appSectionsPagerAdapter.setTabHistoryDisabled(hidden);
//        if (viewPager.getCurrentItem() >= 2) {
            int currentItem = viewPager.getCurrentItem();

                viewPager.setCurrentItem(currentItem);
        bottomNavigationView.setSelectedItemId(selectedId);
//            } else {
//                viewPager.setCurrentItem(Math.min (currentItem + 1, appSectionsPagerAdapter.getCount() -1));
//
//            }
//        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (Objects.equals(key, AppPrefs.PREF_DISABLE_HISTORY)) {
            updateHistoryTabState();
        }
    }

    public static final class BookmarksFragment extends BlobDescriptorListFragment {
        @Override
        String getItemClickAction() {
            return ArticleCollectionActivity.ACTION_BOOKMARKS;
        }

        @Override
        BlobDescriptorList getDescriptorList() {
            return SlobHelper.getInstance().bookmarks;
        }

        @Override
        protected int getEmptyIcon() {
            return R.drawable.ic_bookmarks;
        }

        @Override
        protected String getEmptyText() {
            return getString(R.string.main_empty_bookmarks);
        }

        @Override
        int getDeleteConfirmationItemCountResId() {
            return R.plurals.confirm_delete_bookmark_count;
        }

        @Override
        String getPreferencesNS() {
            return "bookmarks";
        }


        @Override
        public void onResume() {
            super.onResume();
            FragmentActivity activity = requireActivity();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).requireActionBar().setTitle(R.string.subtitle_bookmark);
                ((MainActivity) activity).requireActionBar().setSubtitle(null);
            }
        }
    }

    public static class HistoryFragment extends BlobDescriptorListFragment {
        @Override
        String getItemClickAction() {
            return ArticleCollectionActivity.ACTION_HISTORY;
        }

        @Override
        BlobDescriptorList getDescriptorList() {
            return SlobHelper.getInstance().history;
        }

        @Override
        protected int getEmptyIcon() {
            return R.drawable.ic_history;
        }

        @Override
        protected String getEmptyText() {
            return getString(R.string.main_empty_history);
        }

        @Override
        int getDeleteConfirmationItemCountResId() {
            return R.plurals.confirm_delete_history_count;
        }

        @Override
        String getPreferencesNS() {
            return "history";
        }


        @Override
        public void onResume() {
            super.onResume();
            FragmentActivity activity = requireActivity();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).requireActionBar().setTitle(R.string.subtitle_history);
                ((MainActivity) activity).requireActionBar().setSubtitle(null);
            }
        }

    }


    public static class AppSectionsPagerAdapter extends FragmentPagerAdapter {
        private ArrayList<Class> fragments;
        Boolean tabHistoryDisabled = false;

        public AppSectionsPagerAdapter(FragmentManager fm, Boolean tabHistoryDisabled) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.tabHistoryDisabled = tabHistoryDisabled;
            if (tabHistoryDisabled) {
                fragments = new ArrayList<>(Arrays.asList(LookupFragment.class, BookmarksFragment.class, DictionaryListFragment.class, SettingsFragment.class));
            } else {
                fragments = new ArrayList<>(Arrays.asList(LookupFragment.class, BookmarksFragment.class, HistoryFragment.class, DictionaryListFragment.class, SettingsFragment.class));
            }
        }
        public void setTabHistoryDisabled(Boolean hidden) {
            if (tabHistoryDisabled != hidden) {
                tabHistoryDisabled = hidden;
                if (hidden) {
                    fragments.remove(HistoryFragment.class);
                } else {
                    fragments.add(2, HistoryFragment.class);
                }
                notifyDataSetChanged();
            }
        }
        @NonNull
        @Override
        public Object instantiateItem(@NonNull View container, int position) {
            return super.instantiateItem(container, position);
        }

        @NonNull
        @Override
        public Fragment getItem(int i) {
            try {
                Fragment result = (Fragment) fragments.get(i).getConstructor().newInstance();
                return result;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public long getItemId(int position) {
            return fragments.get(position).toString().hashCode();
        }

        @Override
        public int getCount() {
            return fragments.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return "";
        }

        @Override
        public int getItemPosition(Object object) {
//            int index = -1;
//            for (int i = 0; i < fragments.size(); i++) {
//                if (fragments.get(i) == object.getClass()) {
//                    index = i;
//                    break;
//                }
//            }
//            if (index == -1)
                return POSITION_NONE;
//            else
//                return index;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (!AppPrefs.autoPasteInLookup()) {
            Log.d(TAG, "Auto-paste is off");
            return;
        }
        if (!hasFocus) {
            Log.d(TAG, "has no focus");
            return;
        }
        CharSequence text = ClipboardUtils.peek(this);
        if (text != null) {
            viewPager.setCurrentItem(0);
            invalidateOptionsMenu();
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event.isCanceled()) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!AppPrefs.useVolumeKeysForNavigation()) {
                return false;
            }
            int current = viewPager.getCurrentItem();
            if (current > 0) {
                viewPager.setCurrentItem(current - 1);
            } else {
                viewPager.setCurrentItem(appSectionsPagerAdapter.getCount() - 1);
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!AppPrefs.useVolumeKeysForNavigation()) {
                return false;
            }
            int current = viewPager.getCurrentItem();
            if (current < appSectionsPagerAdapter.getCount() - 1) {
                viewPager.setCurrentItem(current + 1);
            } else {
                viewPager.setCurrentItem(0);
            }
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return AppPrefs.useVolumeKeysForNavigation();
        }
        return super.onKeyDown(keyCode, event);
    }

}
