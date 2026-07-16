package com.minibrowser.media;

import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.widget.ImageButton;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.minibrowser.R;

public class MediaPlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    private float aspect = 16f / 9f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().setBackgroundDrawableResource(android.R.color.black);

        playerView = findViewById(R.id.player_view);
        ImageButton pip = findViewById(R.id.btn_pip);
        ImageButton close = findViewById(R.id.btn_player_close);

        pip.setOnClickListener(v -> enterPip());
        close.setOnClickListener(v -> finish());

        initPlayer();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void initPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        Uri uri = getIntent().getData();
        String mime = getIntent().getType();
        if (uri == null) {
            finish();
            return;
        }
        androidx.media3.common.MediaItem item = (mime != null)
                ? new androidx.media3.common.MediaItem.Builder().setUri(uri).setMimeType(mime).build()
                : androidx.media3.common.MediaItem.fromUri(uri);
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) aspect = (float) vs.width / vs.height;
            }
        });
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                int w = (int) (aspect * 100);
                int h = 100;
                if (w <= 0 || h <= 0) { w = 100; h = 100; }
                PictureInPictureParams p = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(w, h))
                        .build();
                enterPictureInPictureMode(p);
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && player != null && player.getPlayWhenReady()) {
            enterPip();
        }
    }

    @Override
    @OptIn(markerClass = UnstableApi.class)
    public void onPictureInPictureModeChanged(boolean isInPiP, Configuration config) {
        super.onPictureInPictureModeChanged(isInPiP, config);
        if (playerView != null) playerView.setUseController(!isInPiP);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isInPictureInPictureMode() && player != null) {
                player.setPlayWhenReady(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode()) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }
}
