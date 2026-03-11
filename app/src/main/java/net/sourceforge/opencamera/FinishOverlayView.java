package net.sourceforge.opencamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Overlay view for PhotoFinish activity.
 * Draws a draggable vertical line and frame information.
 */
public class FinishOverlayView extends View {
    private static final String TAG = "FinishOverlayView";

    private Paint linePaint;
    private Paint textPaint;
    private Paint textBackgroundPaint;

    private float linePositionPercent = 0.5f; // 0.0 to 1.0 (center by default)
    private boolean isDragging = false;

    private int currentFrame = 0;
    private int nullFrame = -1;
    private int fps = 30;

    private boolean showLine = true;
    private boolean showInfo = false; // Info is shown in separate TextViews

    public FinishOverlayView(Context context) {
        super(context);
        init();
    }

    public FinishOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FinishOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Red vertical line
        linePaint = new Paint();
        linePaint.setColor(Color.RED);
        linePaint.setStrokeWidth(4);
        linePaint.setAntiAlias(true);

        // Text paint for on-screen info
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);
        textPaint.setAntiAlias(true);
        textPaint.setShadowLayer(2, 1, 1, Color.BLACK);

        // Semi-transparent background for text
        textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(Color.argb(128, 0, 0, 0));
    }

    public void setLinePositionPercent(float percent) {
        this.linePositionPercent = Math.max(0f, Math.min(1f, percent));
        invalidate();
    }

    public float getLinePositionPercent() {
        return linePositionPercent;
    }

    public void setFrameInfo(int currentFrame, int nullFrame, int fps) {
        this.currentFrame = currentFrame;
        this.nullFrame = nullFrame;
        this.fps = fps;
        invalidate();
    }

    public void setShowLine(boolean show) {
        this.showLine = show;
        invalidate();
    }

    public void setShowInfo(boolean show) {
        this.showInfo = show;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) return;

        // Draw vertical line (draggable)
        if (showLine) {
            float lineX = linePositionPercent * width;
            canvas.drawLine(lineX, 0, lineX, height, linePaint);

            // Draw small handle at top and bottom
            float handleRadius = 20;
            canvas.drawCircle(lineX, handleRadius, handleRadius, linePaint);
            canvas.drawCircle(lineX, height - handleRadius, handleRadius, linePaint);
        }

        // Draw frame info on overlay (optional, usually shown in TextViews)
        if (showInfo) {
            float padding = 10;
            float textY = 50;

            // Frame number
            String frameText = "Frame: " + (currentFrame + 1);
            canvas.drawText(frameText, padding, textY, textPaint);
            textY += 50;

            // Time
            double currentTimeSec = (currentFrame) / (double) fps;
            String timeText = formatTime(currentTimeSec);
            canvas.drawText(timeText, padding, textY, textPaint);
            textY += 50;

            // Null time
            if (nullFrame >= 0) {
                int deltaFrames = currentFrame - nullFrame;
                double deltaSeconds = deltaFrames / (double) fps;
                String sign = deltaSeconds >= 0 ? "+" : "";
                String nullText = "From null: " + sign + formatTime(deltaSeconds);
                canvas.drawText(nullText, padding, textY, textPaint);
            }
        }
    }

    private String formatTime(double seconds) {
        boolean negative = seconds < 0;
        seconds = Math.abs(seconds);

        int mins = (int) (seconds / 60);
        double secs = seconds % 60;

        String timeStr = String.format(Locale.US, "%02d:%06.3f", mins, secs);
        return negative ? "-" + timeStr : timeStr;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!showLine) return super.onTouchEvent(event);

        float x = event.getX();
        float width = getWidth();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touch is near the line
                float lineX = linePositionPercent * width;
                if (Math.abs(x - lineX) < 50) { // 50px touch tolerance
                    isDragging = true;
                    return true;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    linePositionPercent = x / width;
                    linePositionPercent = Math.max(0f, Math.min(1f, linePositionPercent));
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    isDragging = false;
                    return true;
                }
                break;
        }

        return super.onTouchEvent(event);
    }
}
