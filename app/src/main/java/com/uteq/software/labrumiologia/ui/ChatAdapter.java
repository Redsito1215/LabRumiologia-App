package com.uteq.software.labrumiologia.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.uteq.software.labrumiologia.R;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {
    public static class Message {
        public final String role;
        public final String body;
        public final String sources;
        public final boolean placeholder;

        public Message(String role, String body, String sources) {
            this(role, body, sources, false);
        }

        public Message(String role, String body, String sources, boolean placeholder) {
            this.role = role;
            this.body = body;
            this.sources = sources;
            this.placeholder = placeholder;
        }
    }

    private final List<Message> items = new ArrayList<>();

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
        holder.role.setText(m.role);
        holder.body.setText(m.body);
        boolean user = "Usted".equals(m.role);
        holder.card.setBackgroundResource(user ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_assistant);
        holder.sources.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View card;
        final TextView role;
        final TextView body;
        final TextView sources;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView;
            role = itemView.findViewById(R.id.messageRole);
            body = itemView.findViewById(R.id.messageBody);
            sources = itemView.findViewById(R.id.messageSources);
        }
    }
}
