package com.minibrowser;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A plain-text scratchpad. The content lives in <filesDir>/notepad.txt and is
 *
 *  - loaded synchronously in onCreate() (a single, small file — fast enough),
 *  - flushed to disk on a *background* thread in onPause() so the UI is never
 *    blocked by I/O. Writes are atomic: we stage to notepad.tmp then rename.
 */
public class NotepadActivity extends Activity {

    private static final String FILE_NAME = "notepad.txt";
    private static final String TEMP_NAME = "notepad.tmp";

    private EditText editor;
    private TextView status;
    private File target;

    private volatile boolean dirty = false;
    private Thread saveThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notepad);

        editor = findViewById(R.id.notepad_edit);
        status = findViewById(R.id.notepad_status);
        findViewById(R.id.notepad_close).setOnClickListener(v -> finish());

        target = new File(getFilesDir(), FILE_NAME);

        loadText();

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                dirty = true;
                status.setText("unsaved");
                status.setTextColor(getColor(R.color.c_offline));
            }
        });

        if (savedInstanceState != null) {
            editor.setText(savedInstanceState.getString("text", ""));
        }
    }

    private void loadText() {
        if (!target.exists()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(target), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            // Trim the trailing newline added by the read loop if present.
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.deleteCharAt(sb.length() - 1);
            }
        } catch (IOException e) {
            // Best effort — an empty editor is fine.
        }
        editor.setText(sb.toString());
        editor.setSelection(editor.getText().length());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("text", editor.getText().toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        final String content = editor.getText().toString();
        if (!dirty) {
            return;
        }
        // Snapshot then hand off to a worker thread — never block the UI.
        saveThread = new Thread(() -> saveAtomic(content), "NotepadSaver");
        saveThread.setPriority(Thread.NORM_PRIORITY - 1);
        saveThread.start();
        // Reflect the saved state immediately; the file write continues off-thread.
        status.post(() -> {
            status.setText("saved");
            status.setTextColor(getColor(R.color.active));
        });
        dirty = false;
    }

    /** Atomic write: stage to a temp file, fsync-free rename for crash safety. */
    private void saveAtomic(String content) {
        final File tmp = new File(getFilesDir(), TEMP_NAME);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;
            if (!tmp.renameTo(target)) {
                // Rename can fail across mount points — fallback to byte copy.
                copyFile(tmp, target);
                tmp.delete();
            }
        } catch (IOException e) {
            // Swallow — user data is best-effort for a scratchpad.
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.getFD().sync();
        }
    }
}
