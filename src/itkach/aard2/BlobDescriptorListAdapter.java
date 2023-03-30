package itkach.aard2;

import android.content.Context;
import android.database.DataSetObserver;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.elevation.SurfaceColors;

import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.slob.SlobTags;
import itkach.slob.Slob;

public class BlobDescriptorListAdapter extends BaseAdapter {
    public final BlobDescriptorList list;
    private boolean selectionMode;

    public BlobDescriptorListAdapter(BlobDescriptorList list) {
        this.list = list;
        this.list.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                notifyDataSetChanged();
            }

            @Override
            public void onInvalidated() {
                notifyDataSetInvalidated();
            }
        });
    }

    @Override
    public int getCount() {
        synchronized (list) {
            return list.size();
        }
    }

    @Override
    public Object getItem(int position) {
        synchronized (list) {
            return list.get(position);
        }
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        BlobDescriptor item = list.get(position);
        CharSequence timestamp = DateUtils.getRelativeTimeSpanString(item.createdAt);
        View view;
        MaterialCardView cardView;
        if (convertView != null) {
            view = convertView;
            cardView = view.findViewById(R.id.card_view);
        } else {
            LayoutInflater inflater = (LayoutInflater) parent.getContext()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            view = inflater.inflate(R.layout.blob_descriptor_list_item, parent, false);
            cardView = view.findViewById(R.id.card_view);
            cardView.setCardBackgroundColor(SurfaceColors.SURFACE_1.getColor(view.getContext()));
        }
        TextView titleView = view.findViewById(R.id.blob_descriptor_key);
        titleView.setText(item.key);
        TextView sourceView = view.findViewById(R.id.blob_descriptor_source);
        Slob slob = list.resolveOwner(item);
        sourceView.setText(slob == null ? "???" : slob.getTags().get(SlobTags.TAG_LABEL));
        TextView timestampView = view.findViewById(R.id.blob_descriptor_timestamp);
        timestampView.setText(timestamp);
        cardView.setCheckable(isSelectionMode());
        return view;
    }

}
