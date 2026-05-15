package itkach.aard2.dictionaries;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import android.text.Spanned;
import androidx.core.text.HtmlCompat;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import itkach.aard2.BaseListFragment;
import itkach.aard2.MainActivity;
import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.descriptor.SlobDescriptor;
import itkach.aard2.prefs.AppPrefs;

public class DictionaryListFragment extends BaseListFragment {
    private final static String TAG = DictionaryListFragment.class.getSimpleName();
    private TextView formatsHeader;
    @Nullable private Snackbar loadingSnackbar;

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
    private final ActivityResultLauncher<Intent> folderSelector = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK) {
                    return;
                }
                android.content.Intent intent = result.getData();
                if (intent == null) {
                    return;
                }
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                viewModel.setAutoLoadFolder(uri);
                try {
                    // Take persistable URI permission
                    requireActivity().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    // Save the folder URI
                    AppPrefs.setAutoLoadDictFolderUri(uri.toString());

                    Toast.makeText(requireActivity(), R.string.msg_folder_selected, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set auto-load folder", e);
                    Toast.makeText(requireActivity(), R.string.msg_failed_to_select_folder, Toast.LENGTH_LONG).show();
                }
            });

    @DrawableRes
    @Override
    protected int getEmptyIcon() {
        return -1;
    }

    @NonNull
    @Override
    protected CharSequence getEmptyText() {
        // return a Spanned so links and formatting work
        String html = getString(R.string.main_empty_dictionaries_template,
                getString(R.string.main_empty_dictionaries_title),
                getString(R.string.main_empty_dictionaries_subtitle),
                getString(R.string.main_empty_dictionaries_formats),
                getString(R.string.main_empty_dictionaries_download_text),
                getString(R.string.main_empty_dictionaries_download_url),
                getString(R.string.here)
        );

        Spanned sp = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY);
        return sp;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(DictionaryListViewModel.class);
        DictionaryListAdapter listAdapter = new DictionaryListAdapter(SlobHelper.getInstance().dictionaries, this);
        recyclerView.setAdapter(listAdapter);

        // Add a button to select auto-load folder in empty view
        View container = emptyView.findViewById(R.id.container);
        if (container instanceof ViewGroup) {
            ViewGroup containerGroup = (ViewGroup) container;
            
            // Check if button doesn't already exist
            View existingButton = emptyView.findViewWithTag("select_folder_button");
            if (existingButton == null) {
                // Create button programmatically
                MaterialButton selectFolderButton = new MaterialButton(requireContext());
                selectFolderButton.setText(R.string.action_select_dictionary_folder);
                selectFolderButton.setTag("select_folder_button");
                selectFolderButton.setOnClickListener(v -> selectDictionaryFolder());
                
                // Add some margin
                ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                params.topMargin = (int) (24 * getResources().getDisplayMetrics().density);
                selectFolderButton.setLayoutParams(params);
                
                containerGroup.addView(selectFolderButton);
            }
        }


        // find header (in the fragment_list layout)
        formatsHeader = view.findViewById(R.id.formats_header);
        if (formatsHeader != null) {
            formatsHeader.setText(getString(R.string.formats_supported_header));
        }

        // update header visibility based on adapter contents
        updateFormatsHeaderVisibility(listAdapter);

        // observe adapter changes to update header
        listAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateFormatsHeaderVisibility(listAdapter);
            }
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updateFormatsHeaderVisibility(listAdapter);
            }
            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updateFormatsHeaderVisibility(listAdapter);
            }
        });

        // Show a SnackBar while dictionaries are being loaded / extracted so the
        // user knows background work is in progress.
        viewModel.isLoading.observe(getViewLifecycleOwner(), loading -> {
            if (Boolean.TRUE.equals(loading)) {
                if (loadingSnackbar == null || !loadingSnackbar.isShown()) {
                    loadingSnackbar = Snackbar.make(view,
                            R.string.msg_loading_dictionary,
                            Snackbar.LENGTH_INDEFINITE);
                    loadingSnackbar.show();
                }
            } else {
                if (loadingSnackbar != null) {
                    loadingSnackbar.dismiss();
                    loadingSnackbar = null;
                }
            }
        });
    }

    private void updateFormatsHeaderVisibility(DictionaryListAdapter adapter) {
        if (formatsHeader == null || adapter == null) return;
        boolean hasItems = adapter.getItemCount() > 0;
        formatsHeader.setVisibility(hasItems ? View.VISIBLE : View.GONE);
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
        // Accept all file types including:
        // - .slob (Aard 2 format)
        // - .mdx (MDict format)
        // - .ifo (StarDict format)
        // - .zip (StarDict archive containing .ifo, .idx, .dict files)
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

    public void selectDictionaryFolder() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            folderSelector.launch(intent);
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
