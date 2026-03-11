package net.sourceforge.opencamera;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class FrameByFrameVideoActivity extends Activity {
    private static final String TAG = "FrameByFrameVideo";
    
    private ImageView frameImageView;
    private SeekBar frameSeekBar;
    private TextView frameInfoTextView;
    private ImageButton btnPrevFrame;
    private ImageButton btnNextFrame;
    private ImageButton btnPlay;
    
    private MediaMetadataRetriever retriever;
    private Uri videoUri;
    private long videoDurationMs;
    private List<Long> frameTimestamps; // Actual available frame timestamps
    private int currentFrameIndex = 0;
    private int fps = 30; // default FPS
    
    private boolean isPlaying = false;
    private Thread playbackThread;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_frame_by_frame_video);
        
        // Get video URI from intent
        videoUri = getIntent().getData();
        if (videoUri == null) {
            Log.e(TAG, "No video URI provided");
            finish();
            return;
        }
        
        // Initialize views
        frameImageView = findViewById(R.id.frame_image_view);
        frameSeekBar = findViewById(R.id.frame_seekbar);
        frameInfoTextView = findViewById(R.id.frame_info_text);
        btnPrevFrame = findViewById(R.id.btn_prev_frame);
        btnNextFrame = findViewById(R.id.btn_next_frame);
        btnPlay = findViewById(R.id.btn_play);
        
        // Initialize MediaMetadataRetriever
        retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, videoUri);
            
            // Get video duration
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            videoDurationMs = Long.parseLong(durationStr);
            
            // Try to get FPS
            String frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRateStr != null) {
                fps = (int) Float.parseFloat(frameRateStr);
            }
            
            if (MyDebug.LOG) {
                Log.d(TAG, "Video duration: " + videoDurationMs + "ms");
                Log.d(TAG, "FPS: " + fps);
            }
            
            // Build list of frame timestamps based on actual video FPS
            // For high frame rate videos (120fps, 240fps), we need smaller steps
            frameTimestamps = new ArrayList<>();
            frameInfoTextView.setText("Loading frames...");
            
            // Calculate step size based on actual FPS
            // e.g., for 240fps: 1000000 / 240 = 4166.67 microseconds per frame
            // e.g., for 120fps: 1000000 / 120 = 8333.33 microseconds per frame
            // e.g., for 30fps:  1000000 / 30  = 33333.33 microseconds per frame
            long stepUs = (long)(1000000.0 / fps); // microseconds per frame based on actual FPS
            
            if (MyDebug.LOG) {
                Log.d(TAG, "Step size (microseconds): " + stepUs + " for FPS: " + fps);
            }
            
            for (long timeUs = 0; timeUs < videoDurationMs * 1000; timeUs += stepUs) {
                frameTimestamps.add(timeUs);
            }
            
            // If no frames found, add at least one at the start
            if (frameTimestamps.isEmpty()) {
                frameTimestamps.add(0L);
            }
            
            if (MyDebug.LOG) {
                Log.d(TAG, "Created " + frameTimestamps.size() + " frame timestamps");
            }
            
            // Setup seekbar
            frameSeekBar.setMax(frameTimestamps.size() - 1);
            frameSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        currentFrameIndex = progress;
                        displayFrame(currentFrameIndex);
                    }
                }
                
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    stopPlayback();
                }
                
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            
            // Setup buttons
            btnPrevFrame.setOnClickListener(v -> {
                stopPlayback();
                if (currentFrameIndex > 0) {
                    currentFrameIndex--;
                    displayFrame(currentFrameIndex);
                }
            });
            
            btnNextFrame.setOnClickListener(v -> {
                stopPlayback();
                if (currentFrameIndex < frameTimestamps.size() - 1) {
                    currentFrameIndex++;
                    displayFrame(currentFrameIndex);
                }
            });
            
            btnPlay.setOnClickListener(v -> {
                if (isPlaying) {
                    stopPlayback();
                } else {
                    startPlayback();
                }
            });
            
            // Display first frame
            displayFrame(0);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing video: " + e.getMessage());
            e.printStackTrace();
            finish();
        }
    }
    
    private void displayFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= frameTimestamps.size()) {
            return;
        }
        
        try {
            long timeUs = frameTimestamps.get(frameIndex);
            // Use OPTION_CLOSEST to get the exact frame, not just keyframes
            // This is important for slow motion videos where you want every frame
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            
            if (frame != null) {
                frameImageView.setImageBitmap(frame);
                frameSeekBar.setProgress(frameIndex);
                
                // Update info text - show frame number, total frames, time, and video FPS
                float timeSec = timeUs / 1000000.0f;
                String info = String.format("Frame: %d / %d  |  Time: %.3fs / %.2fs  |  Video: %dfps", 
                    frameIndex + 1, frameTimestamps.size(), timeSec, videoDurationMs / 1000.0, fps);
                frameInfoTextView.setText(info);
                
                currentFrameIndex = frameIndex;
            } else {
                Log.e(TAG, "Failed to get frame at index " + frameIndex);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error displaying frame " + frameIndex + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void startPlayback() {
        isPlaying = true;
        btnPlay.setImageResource(R.drawable.ic_pause_white_48dp);
        
        playbackThread = new Thread(() -> {
            final int PLAYBACK_FPS = 30; // Always play at 30 fps regardless of video's actual fps
            
            while (isPlaying && currentFrameIndex < frameTimestamps.size() - 1) {
                currentFrameIndex++;
                
                runOnUiThread(() -> displayFrame(currentFrameIndex));
                
                try {
                    Thread.sleep(1000 / PLAYBACK_FPS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            if (currentFrameIndex >= frameTimestamps.size() - 1) {
                runOnUiThread(this::stopPlayback);
            }
        });
        playbackThread.start();
    }
    
    private void stopPlayback() {
        isPlaying = false;
        btnPlay.setImageResource(R.drawable.ic_play_arrow_white_48dp);
        
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing retriever: " + e.getMessage());
            }
        }
    }
}
