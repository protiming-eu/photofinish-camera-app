package net.sourceforge.opencamera;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

public class QuickStartImageActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_start_image);

        int imageResId = getIntent().getIntExtra(QuickStartData.EXTRA_IMAGE_RES_ID, 0);
        if( imageResId == 0 ) {
            finish();
            return;
        }

        ImageView fullScreenImage = findViewById(R.id.iv_quick_start_fullscreen);
        fullScreenImage.setImageResource(imageResId);

        View rootView = findViewById(android.R.id.content);
        rootView.setOnClickListener(v -> finish());
        fullScreenImage.setOnClickListener(v -> finish());
    }
}
