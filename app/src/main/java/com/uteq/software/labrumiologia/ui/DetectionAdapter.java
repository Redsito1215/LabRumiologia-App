package com.uteq.software.labrumiologia.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.uteq.software.labrumiologia.R;
import com.uteq.software.labrumiologia.model.Detection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DetectionAdapter extends RecyclerView.Adapter<DetectionAdapter.Holder> {
    public interface Listener {
        void onClick(Detection detection, int index);
    }

    private final List<Detection> items = new ArrayList<>();
    private int selected = -1;
    private final Listener listener;

    public DetectionAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Detection> detections, int selectedIndex) {
        items.clear();
        if (detections != null) items.addAll(detections);
        selected = selectedIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_detection, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Detection d = items.get(position);
        holder.label.setText(d.label);
        holder.confidence.setText(String.format(Locale.getDefault(), "Confianza: %.0f%%", d.confidence * 100f));
        holder.code.setText(String.format("Código: %s", d.classId));
        
        boolean on = position == selected;
        holder.card.setStrokeWidth(on ? 4 : 1);
        holder.card.setStrokeColor(ContextCompat.getColor(holder.itemView.getContext(), 
                on ? R.color.primary : android.R.color.transparent));
        holder.card.setCardBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(),
                on ? R.color.primary_soft : R.color.white));
        holder.card.setCardElevation(on ? 8f : 0f);
        holder.selector.setImageResource(on ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        bindThumbnail(holder.image, d.classId);
        holder.itemView.setOnClickListener(v -> listener.onClick(d, holder.getBindingAdapterPosition()));
    }

    private void bindThumbnail(ImageView imageView, String classId) {
        if (classId == null) {
            imageView.setImageDrawable(null);
            return;
        }
        String assetPath = "equipment_photos/" + classId + ".jpg";
        try (InputStream in = imageView.getContext().getAssets().open(assetPath)) {
            Bitmap ref = BitmapFactory.decodeStream(in);
            imageView.setImageBitmap(ref);
        } catch (IOException e) {
            imageView.setImageDrawable(null);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView image;
        final TextView label;
        final TextView confidence;
        final TextView code;
        final ImageView selector;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            image = itemView.findViewById(R.id.itemImage);
            label = itemView.findViewById(R.id.itemLabel);
            confidence = itemView.findViewById(R.id.itemConfidenceLabel);
            code = itemView.findViewById(R.id.itemCodeLabel);
            selector = itemView.findViewById(R.id.itemSelector);
        }
    }
}
