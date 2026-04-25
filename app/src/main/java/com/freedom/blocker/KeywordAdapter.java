package com.freedom.blocker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** RecyclerView adapter for the blocked keyword list. */
public class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.ViewHolder> {

    public interface OnDeleteListener {
        void onDelete(String keyword);
    }

    private List<String> keywords;
    private final OnDeleteListener listener;
    private boolean editable = true;

    public KeywordAdapter(List<String> keywords, OnDeleteListener listener) {
        this.keywords = keywords;
        this.listener = listener;
    }

    /** Call to lock/unlock delete buttons based on session state. */
    public void setEditable(boolean editable) {
        this.editable = editable;
        notifyDataSetChanged();
    }

    public void updateList(List<String> newList) {
        this.keywords = newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                               .inflate(R.layout.item_keyword, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String kw = keywords.get(position);
        holder.textKeyword.setText(kw);
        holder.btnDelete.setVisibility(editable ? View.VISIBLE : View.INVISIBLE);
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(kw));
    }

    @Override
    public int getItemCount() { return keywords.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView    textKeyword;
        final ImageButton btnDelete;

        ViewHolder(View v) {
            super(v);
            textKeyword = v.findViewById(R.id.text_keyword);
            btnDelete   = v.findViewById(R.id.btn_delete);
        }
    }
}
