package net.sourceforge.opencamera;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.Locale;

public class SimpleViewerActivity extends Activity {
    
    private static final int REQUEST_VIDEO_PICK = 1001;
    
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView tvFrameInfo;
    private TextView tvPlaybackPosition;
    private TextView tvPlaybackDuration;
    private ImageButton btnPrev, btnNext, btnPlay;
    private SeekBar seekBar;
    private LinearLayout controlsLayout;
    
    private Uri videoUri;
    private int totalFrames = 0;
    private int currentFrame = 0;
    private double fps = 30.0;
    private long durationUs = 0;
    private boolean userSeekingSlider = false;
    private boolean controlsVisible = true;
    private Handler updateHandler;
    private Runnable updateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_viewer);
        
        playerView = findViewById(R.id.playerView);
        tvFrameInfo = findViewById(R.id.tv_frame_info);
        tvPlaybackPosition = findViewById(R.id.playbackPositionTxt);
        tvPlaybackDuration = findViewById(R.id.playbackDurationTxt);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        btnPlay = findViewById(R.id.btn_play);
        seekBar = findViewById(R.id.seekBar);
        controlsLayout = findViewById(R.id.controls);
        
        updateHandler = new Handler(Looper.getMainLooper());
        
        // Initialize ExoPlayer
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setControllerAutoShow(false);
        playerView.setUseController(false);
        
        // Tap to toggle controls
        playerView.setOnClickListener(v -> toggleControlsVisibility());
        
        // Check if video URI was provided via Intent
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            videoUri = intent.getData();
            loadVideo(videoUri);
        } else {
            // No URI provided, open file picker
            openFilePicker();
        }
        
        btnPrev.setOnClickListener(v -> previousFrame());
        btnNext.setOnClickListener(v -> nextFrame());
        btnPlay.setOnClickListener(v -> togglePlayback());
        
        // SeekBar listener for slider-based frame navigation
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && totalFrames > 0) {
                    userSeekingSlider = true;
                    int targetFrame = (int) ((progress / 100.0) * totalFrames);
                    seekToFrame(targetFrame);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeekingSlider = true;
                // Pause playback while seeking
                if (player != null && player.isPlaying()) {
                    player.pause();
                    btnPlay.setImageResource(android.R.drawable.ic_media_play);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeekingSlider = false;
            }
        });
        
        // Start update loop for frame counter
        startUpdateLoop();
    }
    
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("video/*");
        startActivityForResult(intent, REQUEST_VIDEO_PICK);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_VIDEO_PICK && resultCode == RESULT_OK && data != null) {
            videoUri = data.getData();
            if (videoUri != null) {
                loadVideo(videoUri);
            }
        } else {
            // User cancelled picker - exit activity
            finish();
        }
    }
    
    private void loadVideo(Uri uri) {
        try {
            // Get video metadata using MediaMetadataRetriever
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            
            if (fpsStr != null && !fpsStr.isEmpty()) {
                fps = Double.parseDouble(fpsStr);
            } else {
                fps = 30.0; // default
            }
            
            long durationMs = Long.parseLong(durationStr);
            durationUs = durationMs * 1000;
            totalFrames = (int) ((durationMs / 1000.0) * fps);
            
            retriever.release();
            
            // Load video in ExoPlayer
            MediaItem mediaItem = MediaItem.fromUri(uri);
            player.setMediaItem(mediaItem);
            player.prepare();
            
            // Start paused at frame 0
            player.pause();
            player.seekTo(0);
            
            currentFrame = 0;
            updateUI();
            
        } catch (Exception e) {
            Toast.makeText(this, "Error loading video: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }
    
    private void previousFrame() {
        if (currentFrame > 0) {
            seekToFrame(currentFrame - 1);
        }
    }
    
    private void nextFrame() {
        if (currentFrame < totalFrames - 1) {
            seekToFrame(currentFrame + 1);
        }
    }
    
    private void seekToFrame(int frame) {
        if (player == null || totalFrames == 0) return;
        
        if (frame < 0) frame = 0;
        if (frame >= totalFrames) frame = totalFrames - 1;
        
        // Pause playback when manually seeking frame-by-frame
        if (player.isPlaying()) {
            player.pause();
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        }
        
        // Calculate time in microseconds for this frame
        long timeUs = (long) ((frame / fps) * 1_000_000);
        
        // ExoPlayer seekTo with precise time
        player.seekTo(timeUs / 1000); // seekTo takes milliseconds
        
        currentFrame = frame;
        updateUI();
    }
    
    private void togglePlayback() {
        if (player == null) return;
        
        if (player.isPlaying()) {
            player.pause();
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        } else {
            player.play();
            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        }
    }
    
    private void toggleControlsVisibility() {
        controlsVisible = !controlsVisible;
        
        if (controlsVisible) {
            controlsLayout.setVisibility(View.VISIBLE);
            tvFrameInfo.setVisibility(View.VISIBLE);
        } else {
            controlsLayout.setVisibility(View.GONE);
            tvFrameInfo.setVisibility(View.GONE);
        }
    }
    
    private void startUpdateLoop() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && totalFrames > 0) {
                    // Only update frame from player position if actively playing
                    // Otherwise keep manual frame position
                    if (player.isPlaying()) {
                        long positionMs = player.getCurrentPosition();
                        currentFrame = (int) ((positionMs / 1000.0) * fps);
                        
                        // Clamp to valid range
                        if (currentFrame < 0) currentFrame = 0;
                        if (currentFrame >= totalFrames) currentFrame = totalFrames - 1;
                        
                        updateUI();
                    }
                }
                
                // Schedule next update
                updateHandler.postDelayed(this, 33); // ~30fps update rate
            }
        };
        
        updateHandler.post(updateRunnable);
    }
    
    private void updateUI() {
        if (totalFrames > 0) {
            double timeSeconds = currentFrame / fps;
            
            // Update frame info (top left)
            String info = String.format(Locale.US, 
                // "Frame: %d / %d\nTime: %.3f s\nFPS: %.1f",
                // currentFrame, totalFrames, timeSeconds, fps);
                "Frame: %d / %d\nTime: %.3f s",
                currentFrame, totalFrames, timeSeconds);
            tvFrameInfo.setText(info);
            
            // Update playback position (bottom left)
            String position = formatTime((long)(timeSeconds * 1000));
            tvPlaybackPosition.setText(position);
            
            // Update playback duration (bottom right)
            if (player != null) {
                long durationMs = player.getDuration();
                String duration = formatTime(durationMs);
                tvPlaybackDuration.setText(duration);
            }
            
            // Update SeekBar (only if user is not actively dragging it)
            if (!userSeekingSlider) {
                int progress = (int) ((currentFrame / (double) totalFrames) * 100);
                seekBar.setProgress(progress);
            }
        }
    }
    
    private String formatTime(long milliseconds) {
        if (milliseconds < 0) milliseconds = 0;
        
        int totalSeconds = (int) (milliseconds / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.US, "%d:%02d", minutes, seconds);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updateHandler != null) {
            updateHandler.removeCallbacksAndMessages(null);
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
