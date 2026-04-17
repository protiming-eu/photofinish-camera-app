package net.sourceforge.opencamera;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class QuickStartDetailActivity extends Activity {
    private static final String STATE_TOPIC_INDEX = "state_topic_index";

    private int topicIndex;

    private TextView breadcrumbText;
    private TextView topicTitleText;
    private TextView topicSummaryText;
    private LinearLayout topicImagesContainer;
    private LinearLayout topicBulletsContainer;
    private Button previousButton;
    private Button nextButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_start_detail);

        breadcrumbText = findViewById(R.id.tv_breadcrumb);
        topicTitleText = findViewById(R.id.tv_topic_title);
        topicSummaryText = findViewById(R.id.tv_topic_summary);
        topicImagesContainer = findViewById(R.id.ll_topic_images);
        topicBulletsContainer = findViewById(R.id.ll_topic_bullets);
        previousButton = findViewById(R.id.btn_topic_back);
        nextButton = findViewById(R.id.btn_topic_next);

        if( savedInstanceState != null ) {
            topicIndex = savedInstanceState.getInt(STATE_TOPIC_INDEX, 0);
        }
        else {
            topicIndex = getIntent().getIntExtra(QuickStartData.EXTRA_TOPIC_INDEX, 0);
        }
        topicIndex = QuickStartData.sanitizeTopicIndex(topicIndex);

        previousButton.setOnClickListener(v -> showPreviousTopic());
        nextButton.setOnClickListener(v -> showNextTopic());

        bindTopic();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TOPIC_INDEX, topicIndex);
    }

    private void bindTopic() {
        QuickStartData.Topic topic = QuickStartData.getTopic(topicIndex);
        String title = getString(topic.titleResId);

        breadcrumbText.setText(getString(R.string.quick_start_breadcrumb_format, title));
        topicTitleText.setText(title);
        topicSummaryText.setText(topic.summaryResId);

        bindTopicImages(topic.imageResIds);
        bindTopicBullets(topic.bodyBulletsResId);

        previousButton.setEnabled(topicIndex > 0);
        nextButton.setText(topicIndex < QuickStartData.getTopicCount() - 1 ? R.string.quick_start_next : R.string.quick_start_done);
    }

    private void bindTopicImages(int[] imageResIds) {
        topicImagesContainer.removeAllViews();

        for(int imageResId : imageResIds) {
            ImageView imageView = new ImageView(this);
            imageView.setImageResource(imageResId);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setBackgroundResource(R.drawable.quick_start_image_frame);
            int padding = dpToPx(2);
            imageView.setPadding(padding, padding, padding, padding);
            imageView.setOnClickListener(v -> openImageFullscreen(imageResId));

            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            layoutParams.bottomMargin = dpToPx(12);
            imageView.setLayoutParams(layoutParams);

            topicImagesContainer.addView(imageView);
        }
    }

    private void bindTopicBullets(int bulletsArrayResId) {
        topicBulletsContainer.removeAllViews();

        String[] bullets = getResources().getStringArray(bulletsArrayResId);
        for(String bullet : bullets) {
            TextView bulletText = new TextView(this);
            bulletText.setText(makeBulletText(bullet));
            bulletText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            bulletText.setLineSpacing(0f, 1.18f);
            bulletText.setPadding(0, 0, 0, dpToPx(8));

            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            bulletText.setLayoutParams(layoutParams);

            topicBulletsContainer.addView(bulletText);
        }
    }

    private SpannableString makeBulletText(String bulletLine) {
        String composedText = "\u25A0  " + bulletLine;
        SpannableString spannable = new SpannableString(composedText);

        int bulletColor = Color.parseColor("#E53935");
        int textColor = Color.parseColor("#6A2E2E");

        spannable.setSpan(new ForegroundColorSpan(bulletColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(textColor), 3, composedText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    private void openImageFullscreen(int imageResId) {
        Intent intent = new Intent(this, QuickStartImageActivity.class);
        intent.putExtra(QuickStartData.EXTRA_IMAGE_RES_ID, imageResId);
        startActivity(intent);
    }

    private void showPreviousTopic() {
        if( topicIndex <= 0 ) {
            finish();
            return;
        }

        topicIndex--;
        bindTopic();
    }

    private void showNextTopic() {
        if( topicIndex >= QuickStartData.getTopicCount() - 1 ) {
            finish();
            return;
        }

        topicIndex++;
        bindTopic();
    }

    private int dpToPx(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
