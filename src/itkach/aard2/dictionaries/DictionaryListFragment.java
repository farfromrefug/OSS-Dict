package itkach.aard2.dictionaries;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import itkach.aard2.BaseListFragment;
import itkach.aard2.MainActivity;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.SlobDescriptor;

public class DictionaryListFragment extends BaseListFragment {
    private final static String TAG = DictionaryListFragment.class.getSimpleName();

    private DictionaryListViewModel viewModel;
    private final ActivityResultLauncher<Intent> dictionarySelector = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result == null || result.getResultCode() != Activity.RESULT_OK) {
                    return;
                }
                Intent intent = result.getData();
                if (intent == null) {
                    return;
                }
                viewModel.addDictionaries(intent);
            });
    private final ActivityResultLauncher<Intent> dictionaryUpdater = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result == null || result.getResultCode() != Activity.RESULT_OK) {
                    return;
                }
                Intent intent = result.getData();
                Uri uri = intent != null ? intent.getData() : null;
                if (uri == null) {
                    return;
                }
                viewModel.updateDictionary(uri);
            });

    @DrawableRes
    @Override
    protected int getEmptyIcon() {
        return R.drawable.ic_library_books;
    }

    @NonNull
    @Override
    protected CharSequence getEmptyText() {
        return getString(R.string.main_empty_dictionaries);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(DictionaryListViewModel.class);
        DictionaryListAdapter listAdapter = new DictionaryListAdapter(SlobHelper.getInstance().dictionaries, this);
        recyclerView.setAdapter(listAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        FragmentActivity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).displayFab(R.drawable.ic_add, R.string.action_add_dictionaries,
                    v -> selectDictionaryFiles());
            ((MainActivity) activity).requireActionBar().setTitle(R.string.subtitle_dictionaries);
            ((MainActivity) activity).requireActionBar().setSubtitle(null);
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

    private void selectDictionaryFiles() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            dictionarySelector.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Not activity to get content", e);
            Toast.makeText(getContext(), R.string.msg_no_activity_to_get_content, Toast.LENGTH_LONG).show();
        }
    }

    public void updateDictionary(@NonNull SlobDescriptor sd) {
        viewModel.setDictionaryToBeReplaced(sd);
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            dictionaryUpdater.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Not activity to get content", e);
            Toast.makeText(getContext(), R.string.msg_no_activity_to_get_content, Toast.LENGTH_LONG).show();
        }
    }
}
