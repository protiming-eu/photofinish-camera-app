package net.sourceforge.opencamera;

final class QuickStartData {
    static final String EXTRA_TOPIC_INDEX = "quick_start_topic_index";
    static final String EXTRA_IMAGE_RES_ID = "quick_start_image_res_id";

    static final class Topic {
        final int titleResId;
        final int summaryResId;
        final int bodyBulletsResId;
        final int[] imageResIds;

        Topic(int titleResId, int summaryResId, int bodyBulletsResId, int[] imageResIds) {
            this.titleResId = titleResId;
            this.summaryResId = summaryResId;
            this.bodyBulletsResId = bodyBulletsResId;
            this.imageResIds = imageResIds;
        }
    }

    private static final Topic[] TOPICS = new Topic[] {
            new Topic(
                    R.string.quick_start_topic_controls_title,
                    R.string.quick_start_topic_controls_summary,
                    R.array.quick_start_topic_controls_bullets,
                    new int[] {
                            R.drawable.topic_controls_annotated,
                            R.drawable.topic_standby_annotated
                    }
            ),
            new Topic(
                    R.string.quick_start_topic_finishline_title,
                    R.string.quick_start_topic_finishline_summary,
                    R.array.quick_start_topic_finishline_bullets,
                    new int[] {
                            R.drawable.topic_finishline_annotated
                    }
            ),
            new Topic(
                    R.string.quick_start_topic_exposure_title,
                    R.string.quick_start_topic_exposure_summary,
                    R.array.quick_start_topic_exposure_bullets,
                    new int[] {
                            R.drawable.topic_exposure_annotated
                    }
            ),
            new Topic(
                    R.string.quick_start_topic_review_title,
                    R.string.quick_start_topic_review_summary,
                    R.array.quick_start_topic_review_bullets,
                    new int[] {
                            R.drawable.topic_review_overview,
                            R.drawable.topic_review_zoom_finish,
                        //     R.drawable.topic_review_zoom_bibs
                    }
            )
    };

    private QuickStartData() {
    }

    static int getTopicCount() {
        return TOPICS.length;
    }

    static int sanitizeTopicIndex(int topicIndex) {
        if( topicIndex < 0 || topicIndex >= TOPICS.length ) {
            return 0;
        }
        return topicIndex;
    }

    static Topic getTopic(int topicIndex) {
        return TOPICS[sanitizeTopicIndex(topicIndex)];
    }
}
