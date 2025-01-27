package itkach.aard2.lookup;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import java.util.Timer;
import java.util.TimerTask;

import itkach.aard2.Application;
import itkach.aard2.BaseListFragment;
import itkach.aard2.MainActivity;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.article.ArticleCollectionActivity;
import itkach.aard2.prefs.AppPrefs;
import itkach.aard2.utils.ClipboardUtils;
import itkach.slob.Slob;

public class LookupFragment extends BaseListFragment implements LookupListener, SearchView.OnQueryTextListener {
    private final static String TAG = LookupFragment.class.getSimpleName();

    private Timer timer;
    private SearchView searchView;
    private LookupResultAdapter listAdapter;
    private LookupViewModel viewModel;

    @Override
    protected int getEmptyIcon() {
        return R.drawable.ic_search;
    }

    @Override
    protected CharSequence getEmptyText() {
        return "";
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        viewModel = new ViewModelProvider(this).get(LookupViewModel.class);
        Application app = (Application) requireActivity().getApplication();
        app.addLookupListener(this);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setBusy(false);
        listAdapter = new LookupResultAdapter(SlobHelper.getInstance().lastLookupResult);
        recyclerView.setAdapter(listAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity)activity).requireActionBar().setTitle(null);
            ((MainActivity)activity).requireActionBar().setSubtitle(R.string.subtitle_lookup);
            if (AppPrefs.disableRandomLookup()) {
                ((MainActivity) activity).hideFab();
            } else {
                ((MainActivity) activity).displayFab(R.drawable.ic_auto_awesome, R.string.action_open_random_article, v -> {
                    Slob.Blob blob = SlobHelper.getInstance().findRandom();
                    if (blob == null) {
                        Toast.makeText(activity, R.string.article_collection_nothing_found, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent intent = new Intent(activity, ArticleCollectionActivity.class);
                    intent.setData(SlobHelper.getInstance().getHttpUri(blob));
                    startActivity(intent);
                });
            }
        }
    }

    @Override
    public void onPause() {
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).hideFab();
        }
        super.onPause();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        timer = new Timer();
        inflater.inflate(R.menu.lookup, menu);
        MenuItem lookupMenu = menu.findItem(R.id.action_lookup);
        View filterActionView = lookupMenu.getActionView();
        searchView = filterActionView.findViewById(R.id.search);
        searchView.setQueryHint(lookupMenu.getTitle());
        searchView.setIconified(false);
        searchView.setOnQueryTextListener(this);
        searchView.setOnCloseListener(() -> true);
        searchView.setSubmitButtonEnabled(false);
        if (!AppPrefs.showKeyboarOnLookup()) {
            searchView.clearFocus();
        }
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (AppPrefs.autoPasteInLookup()) {
            CharSequence clipboard = ClipboardUtils.take(requireContext());
            if (clipboard != null && viewModel != null) {
                searchView.setQuery(clipboard.toString(), false);
                viewModel.lookup(clipboard.toString());
                return;
            }
        }
        String query = AppPrefs.getLastQuery();
        searchView.setQuery(query, false);
        if (viewModel != null) {
            viewModel.lookupLastQuery();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (searchView != null) {
            String query = searchView.getQuery().toString();
            outState.putString("lookupQuery", query);
        }
    }

    private void setBusy(boolean busy) {
        if (!busy) {
            TextView emptyText = emptyView.findViewById(R.id.empty_text);
            String msg = "";
            String query = AppPrefs.getLastQuery();
            if (!query.isEmpty()) {
                msg = getString(R.string.lookup_nothing_found);
            }
            emptyText.setText(msg);
        }
    }

    @Override
    public void onDestroy() {
        listAdapter = null;
        Application app = (Application) requireActivity().getApplication();
        app.removeLookupListener(this);
        super.onDestroy();
    }

    @Override
    public void onLookupStarted(String query) {
        setBusy(true);
    }

    @Override
    public void onLookupFinished(String query) {
        setBusy(false);
    }

    @Override
    public void onLookupCanceled(String query) {
        setBusy(false);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    TimerTask scheduledLookup = null;
    @Override
    public boolean onQueryTextChange(String newText) {
//        Log.d(TAG, "new query text: " + newText);
        TimerTask doLookup = new TimerTask() {
            @Override
            public void run() {
                final String query = searchView.getQuery().toString();
                if (viewModel != null) {
                    viewModel.lookup(query);
                }
                scheduledLookup = null;
            }
        };
        final String query = searchView.getQuery().toString();
        if (!AppPrefs.getLastQuery().equals(query)) {
            if (scheduledLookup != null) {
                scheduledLookup.cancel();
            }
            scheduledLookup = doLookup;
            timer.schedule(doLookup, 500);
        }
        return true;
    }
}
