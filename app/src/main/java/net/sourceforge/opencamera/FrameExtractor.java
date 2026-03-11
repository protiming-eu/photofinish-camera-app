package net.sourceforge.opencamera;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class FrameExtractor {
    private static final String TAG = "FrameExtractor";

    private MediaExtractor extractor;
    private MediaCodec decoder;
    private MediaFormat format;

    private int videoTrackIndex = -1;
    private int fps = 30;
    private long frameDurationUs;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private int videoRotation = 0;

    private int currentFrame = 0;

    public FrameExtractor(String path) throws IOException {
        Log.d(TAG, "FrameExtractor constructor: path=" + path);
        
        extractor = new MediaExtractor();
        extractor.setDataSource(path);

        // Find video track
        int trackCount = extractor.getTrackCount();
        Log.d(TAG, "Total tracks: " + trackCount);
        
        for (int i = 0; i < trackCount; i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            Log.d(TAG, "Track " + i + ": mime=" + mime);
            
            if (mime != null && mime.startsWith("video/")) {
                videoTrackIndex = i;
                format = f;
                Log.d(TAG, "Selected video track: " + i);
                break;
            }
        }

        if (videoTrackIndex < 0) {
            Log.e(TAG, "No video track found!");
            throw new IOException("No video track found");
        }

        extractor.selectTrack(videoTrackIndex);

        // Get video properties
        fps = format.containsKey(MediaFormat.KEY_FRAME_RATE)
                ? format.getInteger(MediaFormat.KEY_FRAME_RATE)
                : 30;

        frameDurationUs = 1_000_000L / fps;

        if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        }
        if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        }
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            videoRotation = format.getInteger(MediaFormat.KEY_ROTATION);
        }

        Log.d(TAG, "Video properties: " + videoWidth + "x" + videoHeight + 
              " @ " + fps + "fps, rotation=" + videoRotation);

        // Create decoder (no surface, we want Image objects)
        String mime = format.getString(MediaFormat.KEY_MIME);
        Log.d(TAG, "Creating decoder for: " + mime);
        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
        decoder.start();

        Log.d(TAG, "FrameExtractor initialized successfully");
    }

    public int getFps() {
        return fps;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public int getVideoRotation() {
        return videoRotation;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public Image getFrame(int targetFrame) {
        Log.d(TAG, "getFrame: targetFrame=" + targetFrame);
        long targetTimeUs = targetFrame * frameDurationUs;

        extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        decoder.flush();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean outputDone = false;

        int decodedFrame = (int)(extractor.getSampleTime() / frameDurationUs);
        Log.d(TAG, "Seeked to frame: " + decodedFrame + ", target: " + targetFrame);

        while (!outputDone) {
            // Feed input
            int inputIndex = decoder.dequeueInputBuffer(10_000);
            if (inputIndex >= 0) {
                ByteBuffer input = decoder.getInputBuffer(inputIndex);
                int size = extractor.readSampleData(input, 0);
                long pts = extractor.getSampleTime();

                if (size < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    outputDone = true;
                } else {
                    decoder.queueInputBuffer(inputIndex, 0, size, pts, 0);
                    extractor.advance();
                }
            }

            // Get output
            int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
            if (outputIndex >= 0) {
                int frameNum = (int)(info.presentationTimeUs / frameDurationUs);
                Log.d(TAG, "Decoded frame: " + frameNum + ", target: " + targetFrame);

                if (frameNum >= targetFrame) {
                    Image image = decoder.getOutputImage(outputIndex);
                    currentFrame = frameNum;
                    
                    if (image != null) {
                        Log.d(TAG, "Returning image: format=" + image.getFormat() + 
                              ", size=" + image.getWidth() + "x" + image.getHeight());
                    } else {
                        Log.e(TAG, "getOutputImage returned null!");
                    }
                    
                    // Return image directly (caller must close it)
                    decoder.releaseOutputBuffer(outputIndex, false);
                    return image;
                }

                decoder.releaseOutputBuffer(outputIndex, false);
                decodedFrame = frameNum;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Output format changed");
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Timeout, continue
            }

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.d(TAG, "End of stream reached");
                outputDone = true;
            }
        }
        
        Log.w(TAG, "getFrame: returning null (end of stream or error)");
        return null;
    }

    public void release() {
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing decoder: " + e.getMessage());
            }
            decoder = null;
        }
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing extractor: " + e.getMessage());
            }
            extractor = null;
        }
    }
}
