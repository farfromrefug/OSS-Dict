package itkach.aard2;

import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.text.StringSearch;

import java.text.StringCharacterIterator;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.descriptor.DescriptorStore;
import itkach.aard2.utils.ThreadUtils;
import itkach.aard2.utils.Utils;
import itkach.slob.Slob;

public class BlobDescriptorList extends AbstractList<BlobDescriptor> {
    private static final String TAG = BlobDescriptorList.class.getSimpleName();

    enum SortOrder {
        TIME, NAME
    }

    protected final DescriptorStore<BlobDescriptor> store;
    protected final List<BlobDescriptor> list;
    private final List<BlobDescriptor> filteredList;
    private final DataSetObservable dataSetObservable;
    private final Comparator<BlobDescriptor> nameComparatorAsc;
    private final Comparator<BlobDescriptor> nameComparatorDesc;
    private final Comparator<BlobDescriptor> timeComparatorAsc;
    private final Comparator<BlobDescriptor> timeComparatorDesc;
    protected final Comparator<BlobDescriptor> lastAccessComparator;
    private final Slob.KeyComparator keyComparator;
    protected final int maxSize;
    private final RuleBasedCollator  filterCollator;

    private String filter;
    private SortOrder order;
    private boolean ascending;
    private Comparator<BlobDescriptor> comparator;

    BlobDescriptorList(DescriptorStore<BlobDescriptor> store) {
        this(store, 100);
    }

    BlobDescriptorList(DescriptorStore<BlobDescriptor> store, int maxSize) {
        this.store = store;
        this.maxSize = maxSize;
        this.list = new ArrayList<>();
        this.filteredList = new ArrayList<>();
        this.dataSetObservable = new DataSetObservable();
        this.filter = "";
        keyComparator = Slob.Strength.QUATERNARY.comparator;

        nameComparatorAsc = (b1, b2) -> keyComparator.compare(b1.key, b2.key);

        nameComparatorDesc = Collections.reverseOrder(nameComparatorAsc);

        timeComparatorAsc = (b1, b2) -> Long.compare(b1.createdAt, b2.createdAt);

        timeComparatorDesc = Collections.reverseOrder(timeComparatorAsc);

        lastAccessComparator = (b1, b2) -> Long.compare(b2.lastAccess, b1.lastAccess);

        order = SortOrder.TIME;
        ascending = false;
        setSort(order, ascending);

        try {
            filterCollator = (RuleBasedCollator) Collator.getInstance(Locale.ROOT).clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
        filterCollator.setStrength(Collator.PRIMARY);
        filterCollator.setAlternateHandlingShifted(false);
    }

    public void registerDataSetObserver(DataSetObserver observer) {
        this.dataSetObservable.registerObserver(observer);
    }

    public void unregisterDataSetObserver(DataSetObserver observer) {
        this.dataSetObservable.unregisterObserver(observer);
    }

    /**
     * Notifies the attached observers that the underlying data has been changed
     * and any View reflecting the data set should refresh itself.
     */
    @MainThread
    public void notifyDataSetChanged() {
        this.filteredList.clear();
        if (TextUtils.isEmpty(filter)) {
            this.filteredList.addAll(this.list);
        } else {
            for (BlobDescriptor bd : this.list) {
                StringSearch stringSearch = new StringSearch(
                        filter, new StringCharacterIterator(bd.key), filterCollator);
                int matchPos = stringSearch.first();
                if (matchPos != StringSearch.DONE) {
                    this.filteredList.add(bd);
                }
            }
        }
        sortOrderChanged();
    }

    private void sortOrderChanged() {
        Utils.sort(this.filteredList, comparator);
        this.dataSetObservable.notifyChanged();
    }

    /**
     * Notifies the attached observers that the underlying data is no longer
     * valid or available. Once invoked this adapter is no longer valid and
     * should not report further data set changes.
     */
    @MainThread
    public void notifyDataSetInvalidated() {
        this.dataSetObservable.notifyInvalidated();
    }

    void load() {
        this.list.addAll(this.store.load(BlobDescriptor.class));
        notifyDataSetChanged();
    }

    private void doUpdateLastAccess(BlobDescriptor bd) {
        long t = System.currentTimeMillis();
        long dt = t - bd.lastAccess;
        if (dt < 2000) {
            return;
        }
        bd.lastAccess = t;
        store.save(bd);
    }

    void updateLastAccess(final BlobDescriptor bd) {
        ThreadUtils.postOnMainThread(() -> doUpdateLastAccess(bd));
    }

    Slob resolveOwner(BlobDescriptor bd) {
        Slob slob = SlobHelper.getInstance().getSlob(bd.slobId);
//        if (slob == null || !slob.file.exists()) {
        if (slob == null) {
            slob = SlobHelper.getInstance().findSlob(bd.slobUri);
        }
        return slob;
    }

    public Slob.Blob resolve(BlobDescriptor bd) {
        Slob slob = resolveOwner(bd);
        Slob.Blob blob = null;
        if (slob == null) {
            return null;
        }
        String slobId = slob.getId().toString();
        if (slobId.equals(bd.slobId)) {
            blob = new Slob.Blob(slob, bd.blobId, bd.key, bd.fragment);
        } else {
            try {
                Iterator<Slob.Blob> result = slob.find(bd.key, Slob.Strength.QUATERNARY);
                if (result.hasNext()) {
                    blob = result.next();
                    bd.slobId = slobId;
                    bd.blobId = blob.id;
                }
            } catch (Exception ex) {
                Log.w(TAG, String.format("Failed to resolve descriptor %s (%s) in %s (%s)",
                                bd.blobId, bd.key, slob.getId(), slob.fileURI), ex);
            }
        }
        if (blob != null) {
            updateLastAccess(bd);
        }
        return blob;
    }

    protected BlobDescriptor createDescriptor(Uri contentUri) {
        Log.d(TAG, "Create descriptor from content url: " + contentUri);
        BlobDescriptor bd = BlobDescriptor.fromUri(contentUri);
        if (bd != null) {
            String slobUri = SlobHelper.getInstance().getSlobUri(bd.slobId);
            Log.d(TAG, "Found slob uri for: " + bd.slobId + " " + slobUri);
            bd.slobUri = slobUri;
        }
        return bd;
    }

    public BlobDescriptor add(Uri contentUrl) {
        BlobDescriptor bd = createDescriptor(contentUrl);
        int index = this.list.indexOf(bd);
        if (index > -1) {
            return this.list.get(index);
        }
        this.list.add(bd);
        store.save(bd);
        if (this.list.size() > this.maxSize) {
            Utils.sort(this.list, lastAccessComparator);
            BlobDescriptor lru = this.list.remove(this.list.size() - 1);
            store.delete(lru.id);
        }
        notifyDataSetChanged();
        return bd;
    }

    public BlobDescriptor remove(Uri contentUrl) {
        int index = this.list.indexOf(createDescriptor(contentUrl));
        if (index > -1) {
            return removeByIndex(index);
        }
        return null;
    }

    public BlobDescriptor remove(int index) {
        //FIXME find exact item by uuid or using sorted<->unsorted mapping
        BlobDescriptor bd = this.filteredList.get(index);
        int realIndex = this.list.indexOf(bd);
        if (realIndex > -1) {
            return removeByIndex(realIndex);
        }
        return null;
    }

    private BlobDescriptor removeByIndex(int index) {
        BlobDescriptor bd = this.list.remove(index);
        if (bd != null) {
            boolean removed = store.delete(bd.id);
            Log.d(TAG, String.format("Item (%s) %s removed? %s", bd.key, bd.id, removed));
            if (removed) {
                notifyDataSetChanged();
            }
        }
        return bd;
    }

    public boolean contains(Uri contentUrl) {
        BlobDescriptor toFind = createDescriptor(contentUrl);
        for (BlobDescriptor bd : this.list) {
            if (bd.equals(toFind)) {
                Log.d(TAG, "Found exact match, bookmarked");
                return true;
            }
            if (bd.key.equals(toFind.key) && bd.slobUri.equals(toFind.slobUri)) {
                Log.d(TAG, "Found approximate match, bookmarked");
                return true;
            }
        }
        Log.d(TAG, "not bookmarked");
        return false;
    }

    public void setFilter(String filter) {
        this.filter = filter;
        notifyDataSetChanged();
    }

    public String getFilter() {
        return this.filter;
    }

    @Override
    public BlobDescriptor get(int location) {
        return this.filteredList.get(location);
    }

    public List<BlobDescriptor> getList() {
        return list;
    }

    @Override
    public int size() {
        return this.filteredList.size();
    }

    public void setSort(boolean ascending) {
        setSort(this.order, ascending);
    }

    public void setSort(SortOrder order) {
        setSort(order, this.ascending);
    }

    public SortOrder getSortOrder() {
        return this.order;
    }

    public boolean isAscending() {
        return this.ascending;
    }

    public void setSort(SortOrder order, boolean ascending) {
        this.order = order;
        this.ascending = ascending;
        Comparator<BlobDescriptor> c = null;
        if (order == SortOrder.NAME) {
            c = ascending ? nameComparatorAsc : nameComparatorDesc;
        }
        if (order == SortOrder.TIME) {
            c = ascending ? timeComparatorAsc : timeComparatorDesc;
        }
        if (c != comparator) {
            comparator = c;
            sortOrderChanged();
        }
    }

}
