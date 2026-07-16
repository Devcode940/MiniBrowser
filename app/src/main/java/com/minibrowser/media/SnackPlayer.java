package com.minibrowser.media;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.minibrowser.R;

/**
 * SnackPlayer — a small, draggable floating mini-player that keeps audio/video
 * playing while you browse. It pins to the bottom-start corner and can be
 * dragged anywhere; tap close to stop and dismiss.
 *
 * Holds a single ExoPlayer; calling play() again replaces the current item.
 */
public class SnackPlayer {

    public interface OnCloseListener { void onClose(); }

    private final Context ctx;
    private final ViewGroup parent;
    private final LinearLayout card;
    private final PlayerView playerView;
    private final TextView title;
    private final ImageButton playPause;
    private ExoPlayer player;
    private OnCloseListener closeListener;

    private float dX, dY;
    private int lastAction;

    @OptIn(markerClass = UnstableApi.class)
    public SnackPlayer(Context context, ViewGroup parentView) {
        this.ctx = context;
        this.parent = parentView;

        card = new LinearLayout(ctx);
        int w = (int) dp(200);
        int h = (int) dp(124);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.bottomMargin = (int) dp(72);
        lp.leftMargin = (int) dp(12);
        card.setLayoutParams(lp);
        card.setElevation(dp(16));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF181C24);
        bg.setCornerRadius(dp(16));
        bg.setStroke((int) dp(1), 0xFF2A2F3A);
        card.setBackground(bg);
        card.setOrientation(LinearLayout.VERTICAL);

        // Top row: title + close
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding((int) dp(8), (int) dp(6), (int) dp(4), (int) dp(4));

        title = new TextView(ctx);
        title.setText("Snack player");
        title.setTextColor(0xFFECEFF4);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setSingleLine(true);
        title.setMarqueeRepeatLimit(-1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setSelected(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton close = new ImageButton(ctx);
        close.setImageResource(R.drawable.ic_close);
        close.setBackground(null);
        close.getLayoutParams();
        close.setOnClickListener(v -> close());
        top.addView(title);
        top.addView(close);
        card.addView(top);

        // Video surface (small). For audio-only it stays blank.
        playerView = new PlayerView(ctx);
        playerView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        playerView.setUseController(false);
        card.addView(playerView);

        // Bottom control row: play/pause
        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, (int) dp(4), 0, (int) dp(4));
        playPause = new ImageButton(ctx);
        playPause.setImageResource(R.drawable.ic_pause);
        playPause.setBackground(null);
        playPause.setOnClickListener(v -> togglePlay());
        bottom.addView(playPause);
        card.addView(bottom);

        // Drag handling on the whole card (except where child buttons consume).
        card.setOnTouchListener(this::onTouch);

        card.setVisibility(View.GONE);
        parent.addView(card);
    }

    public boolean isVisible() { return card.getVisibility() == View.VISIBLE; }

    @OptIn(markerClass = UnstableApi.class)
    public void play(String url, String label) {
        if (player == null) {
            player = new ExoPlayer.Builder(ctx).build();
            playerView.setPlayer(player);
        }
        title.setText(label != null && !label.isEmpty() ? label : "Snack player");
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.parse(url)));
        player.setPlayWhenReady(true);
        player.prepare();
        playPause.setImageResource(R.drawable.ic_pause);
        show();
    }

    private void togglePlay() {
        if (player == null) return;
        boolean playing = player.getPlayWhenReady();
        player.setPlayWhenReady(!playing);
        playPause.setImageResource(playing ? R.drawable.ic_play : R.drawable.ic_pause);
    }

    public void show() {
        if (card.getVisibility() != View.VISIBLE) {
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f);
            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        }
    }

    public void close() {
        if (player != null) { player.release(); player = null; }
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(160)
                .withEndAction(() -> card.setVisibility(View.GONE)).start();
        if (closeListener != null) closeListener.onClose();
    }

    public void setOnCloseListener(OnCloseListener l) { this.closeListener = l; }

    private boolean onTouch(View v, MotionEvent e) {
        // Let buttons handle their own taps; only drag from non-button areas.
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dX = card.getX() - e.getRawX();
                dY = card.getY() - e.getRawY();
                lastAction = MotionEvent.ACTION_DOWN;
                return false; // don't consume — children may still get it
            case MotionEvent.ACTION_MOVE:
                card.setX(Math.max(0, Math.min(parent.getWidth() - card.getWidth(),
                        e.getRawX() + dX)));
                card.setY(Math.max(0, Math.min(parent.getHeight() - card.getHeight(),
                        e.getRawY() + dY)));
                lastAction = MotionEvent.ACTION_MOVE;
                return true;
            default:
                return false;
        }
    }

    private float dp(int v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
