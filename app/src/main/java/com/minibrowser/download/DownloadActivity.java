package com.minibrowser.download;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.minibrowser.MainActivity;
import com.minibrowser.R;

import java.io.File;

/**
 * Full download-manager UI: a live queue (RecyclerView), add-URL dialog,
 * pause/resume/open/share/delete, and a share/open via FileProvider.
 *
 * AppCompatActivity + AndroidX + Material — the showcase of the migration.
 */
public class DownloadActivity extends AppCompatActivity
        implements DownloadAdapter.Host, DownloadManager.Listener {

    private DownloadAdapter adapter;
    private TextView emptyView;
    private DownloadManager mgr;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);

        mgr = DownloadManager.get();

        emptyView = findViewById(R.id.dl_empty);
        RecyclerView list = findViewById(R.id.dl_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadAdapter(this);
        list.setAdapter(adapter);

        findViewById(R.id.dl_add).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.dl_close).setOnClickListener(v -> finish());

        // If launched with a URL to enqueue (from the browser's "Download media").
        Intent intent = getIntent();
        if (intent != null) {
            String url = intent.getStringExtra("url");
            String page = intent.getStringExtra("pageUrl");
            if (url != null && !url.isEmpty()) {
                mgr.enqueue(url, page, null);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mgr.register(this);
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mgr.unregister(this);
    }

    @Override
    public void onChanged() {
        if (isFinishing()) return;
        runOnUiThread(this::refresh);
    }

    private void refresh() {
        adapter.submit(mgr.snapshot());
        emptyView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    // --------------------------- add dialog ------------------------------

    private void showAddDialog() {
        final EditText et = new EditText(this);
        et.setHint("https://example.com/stream.m3u8");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_add)
                .setView(et)
                .setPositiveButton("Add", (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (!u.isEmpty()) mgr.enqueue(u, null, null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------- adapter callbacks -----------------------

    @Override
    public void onPauseResume(DownloadTask t) {
        if (t.status == DownloadTask.Status.DOWNLOADING
                || t.status == DownloadTask.Status.PENDING
                || t.status == DownloadTask.Status.MERGING) {
            mgr.pause(t);
        } else {
            mgr.resume(t);
        }
    }

    @Override
    public void onOpen(DownloadTask t) {
        if (t.outputPath == null) return;
        File f = new File(t.outputPath);
        if (!f.exists()) { toast("File missing"); return; }
        String mime = guessMime(t.filename);
        if (isPlayable(mime)) {
            Intent i = new Intent(this, com.minibrowser.media.MediaPlayerActivity.class);
            i.setDataAndType(Uri.fromFile(f), mime);
            startActivity(i);
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (Exception e) {
            toast("No app to open this file");
        }
    }

    private boolean isPlayable(String mime) {
        return mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"));
    }

    @Override
    public void onShare(DownloadTask t) {
        if (t.outputPath == null) return;
        File f = new File(t.outputPath);
        if (!f.exists()) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(guessMime(t.filename));
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share via"));
    }

    @Override
    public void onDelete(DownloadTask t) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_delete)
                .setMessage("Delete \"" + t.filename + "\" and its file?")
                .setPositiveButton("Delete", (d, w) -> mgr.delete(t, true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String guessMime(String name) {
        if (name == null) return "*/*";
        String l = name.toLowerCase();
        if (l.endsWith(".ts")) return "video/mp2t";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".m4a") || l.endsWith(".aac")) return "audio/mp4";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".mkv")) return "video/x-matroska";
        if (l.endsWith(".webm")) return "video/webm";
        return "*/*";
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
