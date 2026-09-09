package com.uteq.software.labrumiologia.ui;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.uteq.software.labrumiologia.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {
    public static class Message {
        public final String role;
        public final String body;
        public final String sources;
        public final boolean placeholder;
        public final long timestamp;

        public Message(String role, String body, String sources) {
            this(role, body, sources, false);
        }

        public Message(String role, String body, String sources, boolean placeholder) {
            this.role = role;
            this.body = body;
            this.sources = sources;
            this.placeholder = placeholder;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final List<Message> items = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public void add(Message message) {
        items.add(message);
        notifyItemInserted(items.size() - 1);
    }

    public void removeLastIfPlaceholder() {
        if (items.isEmpty() || !items.get(items.size() - 1).placeholder) return;
        int idx = items.size() - 1;
        items.remove(idx);
        notifyItemRemoved(idx);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_message, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Message m = items.get(position);
        holder.body.setText(m.body);
        holder.time.setText(timeFormat.format(new Date(m.timestamp)));

        boolean user = "Usted".equals(m.role);
        
        if (user) {
            holder.root.setGravity(Gravity.END);
            holder.avatar.setVisibility(View.GONE);
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_user);
            holder.body.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.white));
            holder.spacer.setVisibility(View.VISIBLE);
            holder.sourceLayout.setVisibility(View.GONE);
            holder.container.setGravity(Gravity.END);
        } else {
            holder.root.setGravity(Gravity.START);
            holder.avatar.setVisibility(View.VISIBLE);
            holder.bubble.setBackgroundResource(R.drawable.bg_bubble_assistant);
            holder.body.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.on_surface));
            holder.spacer.setVisibility(View.GONE);
            holder.container.setGravity(Gravity.START);
            
            if (m.sources != null && !m.sources.isEmpty()) {
                holder.sourceLayout.setVisibility(View.VISIBLE);
                holder.sources.setText(m.sources);
            } else {
                holder.sourceLayout.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final LinearLayout container;
        final View bubble;
        final ImageView avatar;
        final TextView body;
        final TextView time;
        final View sourceLayout;
        final TextView sources;
        final View spacer;

        Holder(@NonNull View itemView) {
            super(itemView);
            root = (LinearLayout) itemView;
            container = itemView.findViewById(R.id.messageContainer);
            bubble = itemView.findViewById(R.id.messageBubble);
            avatar = itemView.findViewById(R.id.messageAvatar);
            body = itemView.findViewById(R.id.messageBody);
            time = itemView.findViewById(R.id.messageTime);
            sourceLayout = itemView.findViewById(R.id.sourceLayout);
            sources = itemView.findViewById(R.id.messageSources);
            spacer = itemView.findViewById(R.id.messageSpacer);
        }
    }
}
