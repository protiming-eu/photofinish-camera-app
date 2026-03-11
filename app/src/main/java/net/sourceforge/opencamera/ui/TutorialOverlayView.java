package net.sourceforge.opencamera.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/** Overlay used for interactive tutorial coach marks.
 *  Draws a dimmed background, a highlighted target circle and a pointer arrow.
 */
public class TutorialOverlayView extends View {
    private final Paint dimPaint = new Paint();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect targetRect = new Rect();
    private int stepIndex = 1;
    private int stepCount = 1;
    private String title = "";
    private String body = "";

    public TutorialOverlayView(Context context) {
        super(context);
        init();
    }

    public TutorialOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TutorialOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(false);

        dimPaint.setColor(0xAA000000);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3.0f));
        ringPaint.setColor(Color.WHITE);

        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(dp(1.5f));
        pulsePaint.setColor(0x88FFFFFF);

        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(dp(3.0f));
        arrowPaint.setColor(0xFFE6B84D);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);

        textBoxPaint.setStyle(Paint.Style.FILL);
        textBoxPaint.setColor(0xEE121212);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(sp(18.0f));
        titlePaint.setFakeBoldText(true);

        bodyPaint.setColor(0xFFE8E8E8);
        bodyPaint.setTextSize(sp(14.0f));

        stepPaint.setColor(0xFFFFD580);
        stepPaint.setTextSize(sp(12.0f));
        stepPaint.setFakeBoldText(true);
    }

    public void setStepData(Rect rect, int currentStep, int totalSteps, String stepTitle, String stepBody) {
        this.targetRect.set(rect);
        this.stepIndex = currentStep;
        this.stepCount = totalSteps;
        this.title = stepTitle != null ? stepTitle : "";
        this.body = stepBody != null ? stepBody : "";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

        float cx = targetRect.centerX();
        float cy = targetRect.centerY();
        float r = Math.max(targetRect.width(), targetRect.height()) * 0.68f;
        canvas.drawCircle(cx, cy, r + dp(8.0f), pulsePaint);
        canvas.drawCircle(cx, cy, r, ringPaint);

        float boxMargin = dp(16.0f);
        float boxLeft = boxMargin;
        float boxTop = boxMargin;
        float boxRight = getWidth() - boxMargin;
        float boxBottom = dp(180.0f);
        if( cy < getHeight() * 0.45f ) {
            boxTop = getHeight() - dp(196.0f);
            boxBottom = getHeight() - boxMargin;
        }

        canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, dp(14.0f), dp(14.0f), textBoxPaint);

        float tx = boxLeft + dp(14.0f);
        float ty = boxTop + dp(22.0f);
        canvas.drawText("STEP " + stepIndex + " / " + stepCount, tx, ty, stepPaint);

        ty += dp(24.0f);
        canvas.drawText(title, tx, ty, titlePaint);

        ty += dp(22.0f);
        drawMultiline(canvas, body, tx, ty, boxRight - boxLeft - dp(28.0f), bodyPaint, dp(20.0f));

        float arrowStartX = (boxLeft + boxRight) * 0.5f;
        float arrowStartY = (cy < getHeight() * 0.45f) ? boxTop : boxBottom;
        float arrowEndY = (cy < getHeight() * 0.45f) ? cy + r + dp(10.0f) : cy - r - dp(10.0f);
        canvas.drawLine(arrowStartX, arrowStartY, cx, arrowEndY, arrowPaint);

        float headSize = dp(8.0f);
        if( cy < getHeight() * 0.45f ) {
            canvas.drawLine(cx, arrowEndY, cx - headSize, arrowEndY + headSize, arrowPaint);
            canvas.drawLine(cx, arrowEndY, cx + headSize, arrowEndY + headSize, arrowPaint);
        }
        else {
            canvas.drawLine(cx, arrowEndY, cx - headSize, arrowEndY - headSize, arrowPaint);
            canvas.drawLine(cx, arrowEndY, cx + headSize, arrowEndY - headSize, arrowPaint);
        }
    }

    private void drawMultiline(Canvas canvas, String text, float startX, float startY, float maxWidth, Paint paint, float lineHeight) {
        if( text == null || text.isEmpty() ) {
            return;
        }
        String[] paragraphs = text.split("\\n");
        float y = startY;
        for(String paragraph : paragraphs) {
            if( paragraph.isEmpty() ) {
                y += lineHeight * 0.8f;
                continue;
            }
            String[] words = paragraph.split(" ");
            StringBuilder line = new StringBuilder();
            for(String word : words) {
                String candidate = line.length() == 0 ? word : (line + " " + word);
                if( paint.measureText(candidate) > maxWidth && line.length() > 0 ) {
                    canvas.drawText(line.toString(), startX, y, paint);
                    y += lineHeight;
                    line = new StringBuilder(word);
                }
                else {
                    line = new StringBuilder(candidate);
                }
            }
            if( line.length() > 0 ) {
                canvas.drawText(line.toString(), startX, y, paint);
                y += lineHeight;
            }
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
