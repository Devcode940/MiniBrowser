package com.minibrowser.download;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.minibrowser.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the download queue. Rows show filename, status,
 * progress and contextual controls (pause/resume, open/share, delete).
 */
public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.VH> {

    public interface Host {
        void onPauseResume(DownloadTask t);
        void onOpen(DownloadTask t);
        void onShare(DownloadTask t);
        void onDelete(DownloadTask t);
    }

    private final Host host;
    private final List<DownloadTask> items = new ArrayList<>();

    DownloadAdapter(Host host) { this.host = host; }

    void submit(List<DownloadTask> next) {
        final List<DownloadTask> old = new ArrayList<>(items);
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return old.get(o).id.equals(next.get(n).id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                DownloadTask a = old.get(o), b = next.get(n);
                return a.status == b.status && a.progress == b.progress
                        && a.downloadedBytes == b.downloadedBytes
                        && eq(a.error, b.error);
            }
            private boolean eq(String x, String y) { return x == null ? y == null : x.equals(y); }
        }).dispatchUpdatesTo(this);
        items.clear();
        items.addAll(next);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DownloadTask t = items.get(position);
        Context c = h.itemView.getContext();

        h.title.setText(t.filename != null ? t.filename : t.url);
        h.subtitle.setText(subtitleFor(c, t));

        boolean running = t.status == DownloadTask.Status.DOWNLOADING
                || t.status == DownloadTask.Status.PENDING
                || t.status == DownloadTask.Status.MERGING;
        h.progress.setIndeterminate(t.totalBytes < 0 && running);
        h.progress.setProgress(t.progress);

        // Pause/Resume toggle
        h.pauseResume.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        h.pauseResume.setOnClickListener(v -> host.onPauseResume(t));

        boolean done = t.status == DownloadTask.Status.DONE;
        h.open.setVisibility(done ? View.VISIBLE : View.GONE);
        h.open.setOnClickListener(v -> host.onOpen(t));
        h.share.setVisibility(done ? View.VISIBLE : View.GONE);
        h.share.setOnClickListener(v -> host.onShare(t));

        h.delete.setOnClickListener(v -> host.onDelete(t));
        h.itemView.setOnClickListener(v -> { if (done) host.onOpen(t); });
    }

    private String subtitleFor(Context c, DownloadTask t) {
        switch (t.status) {
            case DONE: return "Saved · " + humanSize(t.downloadedBytes);
            case DOWNLOADING: case PENDING: case MERGING:
                return humanSize(t.downloadedBytes)
                        + (t.totalBytes > 0 ? " / " + humanSize(t.totalBytes) : "");
            case PAUSED: return "Paused · " + t.progress + "%";
            case FAILED: return "Failed: " + (t.error != null ? t.error : "error");
            default: return "";
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, subtitle;
        final ProgressBar progress;
        final ImageButton pauseResume, open, share, delete;
        VH(View v) {
            super(v);
            title = v.findViewById(R.id.dl_title);
            subtitle = v.findViewById(R.id.dl_subtitle);
            progress = v.findViewById(R.id.dl_progress);
            pauseResume = v.findViewById(R.id.dl_pause);
            open = v.findViewById(R.id.dl_open);
            share = v.findViewById(R.id.dl_share);
            delete = v.findViewById(R.id.dl_delete);
        }
    }
}
