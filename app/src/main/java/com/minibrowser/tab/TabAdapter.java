package com.minibrowser.tab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.minibrowser.R;
import java.util.List;

public class TabAdapter extends RecyclerView.Adapter<TabAdapter.ViewHolder> {
    private final List<Tab> tabs;
    private final OnTabClickListener listener;

    public interface OnTabClickListener {
        void onTabClick(int position);
        void onTabClose(int position);
    }

    public TabAdapter(List<Tab> tabs, OnTabClickListener listener) {
        this.tabs = tabs;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.tab_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Tab tab = tabs.get(position);
        holder.title.setText(tab.title);
        holder.itemView.setOnClickListener(v -> listener.onTabClick(position));
        holder.close.setOnClickListener(v -> listener.onTabClose(position));
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView title;
        public final TextView close;
        public ViewHolder(View v) {
            super(v);
            title = v.findViewById(R.id.tab_title);
            close = v.findViewById(R.id.tab_close);
        }
    }
}
