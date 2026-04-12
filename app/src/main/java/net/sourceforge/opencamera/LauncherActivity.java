package net.sourceforge.opencamera;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

public class LauncherActivity extends Activity {
    private static final String PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions?package=";

    private Button btnViewer;
    private Button btnSubscription;
    private TextView subscriptionOffer;
    private AdView adView;
    private InterstitialAd interstitialAd;
    private boolean hasSubscriptionAccess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        Button btnCamera = findViewById(R.id.btn_camera);
        btnViewer = findViewById(R.id.btn_viewer);
        btnSubscription = findViewById(R.id.btn_subscription);
        subscriptionOffer = findViewById(R.id.tv_subscription_offer);
        adView = findViewById(R.id.ad_view);

        btnCamera.setOnClickListener(v -> {
            showInterstitialThen(() -> {
                Intent intent = new Intent(LauncherActivity.this, MainActivity.class);
                startActivity(intent);
            });
        });

        btnViewer.setOnClickListener(v -> {
            Intent intent = new Intent(LauncherActivity.this, SimpleViewerActivity.class);
            startActivity(intent);
        });

        btnSubscription.setOnClickListener(v -> {
            if( hasSubscriptionAccess ) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_SUBSCRIPTIONS_URL + getPackageName()));
                startActivity(intent);
            }
            else {
                Intent intent = new Intent(LauncherActivity.this, SubscriptionActivity.class);
                startActivity(intent);
            }
        });

        updateSubscriptionUi(AccessControl.hasSubscriptionAccess(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if( BuildConfig.SHOW_SUBSCRIPTION_OFFER ) {
            SubscriptionBillingSync.syncEntitlement(this, this::updateSubscriptionUi);
        }
        else {
            updateSubscriptionUi(AccessControl.hasSubscriptionAccess(this));
        }
        if( adView != null ) adView.resume();
    }

    @Override
    protected void onPause() {
        if( adView != null ) adView.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if( adView != null ) adView.destroy();
        super.onDestroy();
    }

    private void updateSubscriptionUi(boolean hasSubscriptionAccess) {
        this.hasSubscriptionAccess = hasSubscriptionAccess;

        if( hasSubscriptionAccess ) {
            btnViewer.setEnabled(true);
            btnViewer.setText(getString(R.string.launcher_viewer_button));
        }
        else {
            btnViewer.setEnabled(false);
            btnViewer.setText(getString(R.string.launcher_viewer_button_locked));
        }

        if( BuildConfig.SHOW_SUBSCRIPTION_OFFER ) {
            String offerText = getString(R.string.subscription_offer_launch);
            if( offerText.trim().isEmpty() ) {
                subscriptionOffer.setVisibility(View.GONE);
            }
            else {
                subscriptionOffer.setText(offerText);
                subscriptionOffer.setVisibility(View.VISIBLE);
            }
            btnSubscription.setVisibility(View.VISIBLE);
            btnSubscription.setText(hasSubscriptionAccess ? R.string.launcher_subscription_manage_button : R.string.launcher_subscription_button);
        }
        else {
            subscriptionOffer.setVisibility(View.GONE);
            btnSubscription.setVisibility(View.GONE);
        }

        if( BuildConfig.SHOW_ADS && !hasSubscriptionAccess ) {
            if( adView != null && adView.getVisibility() != View.VISIBLE ) {
                adView.setVisibility(View.VISIBLE);
                adView.loadAd(new AdRequest.Builder().build());
            }
            maybeLoadInterstitial();
        } else {
            if( adView != null ) {
                adView.setVisibility(View.GONE);
            }
            interstitialAd = null;
        }
    }

    private void maybeLoadInterstitial() {
        if( interstitialAd != null ) {
            return;
        }

        AdRequest adRequest = new AdRequest.Builder().build();
        InterstitialAd.load(
                this,
                getString(R.string.admob_interstitial_ad_unit_id),
                adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        interstitialAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError loadAdError) {
                        interstitialAd = null;
                    }
                }
        );
    }

    private void showInterstitialThen(Runnable action) {
        if( !(BuildConfig.SHOW_ADS && !hasSubscriptionAccess) || interstitialAd == null ) {
            action.run();
            return;
        }

        InterstitialAd adToShow = interstitialAd;
        interstitialAd = null;

        adToShow.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                action.run();
                maybeLoadInterstitial();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(AdError adError) {
                action.run();
                maybeLoadInterstitial();
            }

            @Override
            public void onAdShowedFullScreenContent() {
                // no-op
            }
        });

        adToShow.show(this);
    }

    @Override
    public void onBackPressed() {
        // Avoid showing interstitial while activity is leaving via back.
        interstitialAd = null;
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if( isFinishing() ) {
            interstitialAd = null;
        }
    }
}
