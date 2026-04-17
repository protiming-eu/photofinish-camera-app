package net.sourceforge.opencamera;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

public class QuickStartMenuActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_start_menu);

        bindTopicCard(R.id.card_topic_controls, 0);
        bindTopicCard(R.id.card_topic_finishline, 1);
        bindTopicCard(R.id.card_topic_exposure, 2);
        bindTopicCard(R.id.card_topic_review, 3);
    }

    private void bindTopicCard(int cardViewId, int topicIndex) {
        View card = findViewById(cardViewId);
        if( card != null ) {
            card.setOnClickListener(v -> openTopic(topicIndex));
        }
    }

    private void openTopic(int topicIndex) {
        Intent intent = new Intent(this, QuickStartDetailActivity.class);
        intent.putExtra(QuickStartData.EXTRA_TOPIC_INDEX, topicIndex);
        startActivity(intent);
    }
}
