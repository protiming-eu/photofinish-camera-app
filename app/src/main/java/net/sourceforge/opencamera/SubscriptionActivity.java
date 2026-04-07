package net.sourceforge.opencamera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Purchase screen for subscription and lifetime unlock using Google Play Billing. */
public class SubscriptionActivity extends Activity implements PurchasesUpdatedListener {
    private static final String PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions?package=";

    private BillingClient billingClient;

    private Button monthlyButton;
    private Button yearlyButton;
    private Button lifetimeButton;
    private Button restoreButton;
    private TextView statusText;

    private ProductDetails subscriptionProductDetails;
    private ProductDetails lifetimeProductDetails;
    private final Map<String, ProductDetails.SubscriptionOfferDetails> subscriptionOfferByBasePlanId = new HashMap<>();
    private boolean lifetimeManagePromptShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if( !BuildConfig.SHOW_SUBSCRIPTION_OFFER ) {
            finish();
            return;
        }

        setContentView(R.layout.activity_subscription);

        monthlyButton = findViewById(R.id.btn_buy_monthly);
        yearlyButton = findViewById(R.id.btn_buy_yearly);
        lifetimeButton = findViewById(R.id.btn_buy_lifetime);
        restoreButton = findViewById(R.id.btn_restore_purchases);
        statusText = findViewById(R.id.tv_billing_status);

        monthlyButton.setEnabled(false);
        yearlyButton.setEnabled(false);
        lifetimeButton.setEnabled(false);

        monthlyButton.setOnClickListener(v -> launchSubscriptionPurchase(getString(R.string.billing_subscription_base_plan_monthly)));
        yearlyButton.setOnClickListener(v -> launchSubscriptionPurchase(getString(R.string.billing_subscription_base_plan_yearly)));
        lifetimeButton.setOnClickListener(v -> launchLifetimePurchase());
        restoreButton.setOnClickListener(v -> refreshPurchases(true));

        connectBilling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if( billingClient != null ) {
            billingClient.endConnection();
        }
    }

    private void connectBilling() {
        billingClient = BillingClient.newBuilder(this)
                .enablePendingPurchases()
                .setListener(this)
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                    statusText.setText(R.string.subscription_status_ready);
                    queryProductDetails();
                    refreshPurchases(false);
                }
                else {
                    statusText.setText(R.string.subscription_status_unavailable);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                statusText.setText(R.string.subscription_status_reconnecting);
            }
        });
    }

    private void queryProductDetails() {
        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(getString(R.string.billing_product_subscription))
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(getString(R.string.billing_product_lifetime))
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        QueryProductDetailsParams query = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        billingClient.queryProductDetailsAsync(query, (billingResult, productDetailsList) -> {
            if( billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK ) {
                statusText.setText(R.string.subscription_status_products_failed);
                return;
            }

            subscriptionProductDetails = null;
            lifetimeProductDetails = null;
            subscriptionOfferByBasePlanId.clear();

            for(ProductDetails details : productDetailsList) {
                if( details.getProductType().equals(BillingClient.ProductType.SUBS)
                        && details.getProductId().equals(getString(R.string.billing_product_subscription)) ) {
                    subscriptionProductDetails = details;
                    cacheSubscriptionOffers(details);
                }
                else if( details.getProductType().equals(BillingClient.ProductType.INAPP)
                        && details.getProductId().equals(getString(R.string.billing_product_lifetime)) ) {
                    lifetimeProductDetails = details;
                }
            }

            bindPriceToButtons();
        });
    }

    private void cacheSubscriptionOffers(ProductDetails details) {
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if( offers == null ) {
            return;
        }
        for(ProductDetails.SubscriptionOfferDetails offer : offers) {
            String basePlanId = offer.getBasePlanId();
            if( basePlanId == null || basePlanId.isEmpty() ) {
                continue;
            }
            if( !subscriptionOfferByBasePlanId.containsKey(basePlanId) ) {
                subscriptionOfferByBasePlanId.put(basePlanId, offer);
            }
        }
    }

    private void bindPriceToButtons() {
        ProductDetails.SubscriptionOfferDetails monthlyOffer =
                subscriptionOfferByBasePlanId.get(getString(R.string.billing_subscription_base_plan_monthly));
        ProductDetails.SubscriptionOfferDetails yearlyOffer =
                subscriptionOfferByBasePlanId.get(getString(R.string.billing_subscription_base_plan_yearly));
        ProductDetails lifetime = lifetimeProductDetails;

        if( monthlyOffer != null ) {
            String price = getSubscriptionDisplayPrice(monthlyOffer);
            monthlyButton.setText(getString(R.string.subscription_buy_monthly_with_price, price));
            monthlyButton.setEnabled(true);
        }
        if( yearlyOffer != null ) {
            String price = getSubscriptionDisplayPrice(yearlyOffer);
            yearlyButton.setText(getString(R.string.subscription_buy_yearly_with_price, price));
            yearlyButton.setEnabled(true);
        }
        if( lifetime != null && lifetime.getOneTimePurchaseOfferDetails() != null ) {
            String price = lifetime.getOneTimePurchaseOfferDetails().getFormattedPrice();
            lifetimeButton.setText(getString(R.string.subscription_buy_lifetime_with_price, price));
            lifetimeButton.setEnabled(true);
        }
    }

    private String getSubscriptionDisplayPrice(ProductDetails.SubscriptionOfferDetails offer) {
        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        if( phases == null || phases.isEmpty() ) {
            return "";
        }

        ProductDetails.PricingPhase lastPhase = phases.get(phases.size() - 1);
        return lastPhase.getFormattedPrice();
    }

    private void launchSubscriptionPurchase(String basePlanId) {
        if( subscriptionProductDetails == null ) {
            statusText.setText(R.string.subscription_status_products_failed);
            return;
        }

        ProductDetails.SubscriptionOfferDetails offerDetails = subscriptionOfferByBasePlanId.get(basePlanId);
        if( offerDetails == null ) {
            statusText.setText(R.string.subscription_status_products_failed);
            return;
        }

        BillingFlowParams.ProductDetailsParams.Builder paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(subscriptionProductDetails)
                .setOfferToken(offerDetails.getOfferToken());

        launchBilling(paramsBuilder);
    }

    private void launchLifetimePurchase() {
        if( lifetimeProductDetails == null ) {
            statusText.setText(R.string.subscription_status_products_failed);
            return;
        }

        BillingFlowParams.ProductDetailsParams.Builder paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(lifetimeProductDetails);

        launchBilling(paramsBuilder);
    }

    private void launchBilling(BillingFlowParams.ProductDetailsParams.Builder paramsBuilder) {
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(java.util.Collections.singletonList(paramsBuilder.build()))
                .build();

        BillingResult launchResult = billingClient.launchBillingFlow(this, flowParams);
        if( launchResult.getResponseCode() != BillingClient.BillingResponseCode.OK ) {
            statusText.setText(getString(
                    R.string.subscription_status_error_with_code,
                    launchResult.getResponseCode(),
                    launchResult.getDebugMessage()
            ));
        }
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null ) {
            handlePurchases(purchases, true);
            return;
        }

        if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED ) {
            statusText.setText(R.string.subscription_status_canceled);
            return;
        }

        statusText.setText(getString(
                R.string.subscription_status_error_with_code,
                billingResult.getResponseCode(),
                billingResult.getDebugMessage()
        ));
    }

    private void refreshPurchases(boolean fromRestoreButton) {
        final boolean[] subsDone = {false};
        final boolean[] inAppDone = {false};
        final boolean[] hasAccess = {false};

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (result, purchases) -> {
                    if( result.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                        hasAccess[0] = hasAccess[0] || hasEntitlingPurchase(purchases);
                        handlePurchases(purchases, false);
                    }
                    subsDone[0] = true;
                    if( subsDone[0] && inAppDone[0] ) {
                        AccessControl.setSubscriptionAccess(this, hasAccess[0]);
                        statusText.setText(fromRestoreButton ? R.string.subscription_status_restored : R.string.subscription_status_ready);
                    }
                }
        );

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if( result.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                        hasAccess[0] = hasAccess[0] || hasEntitlingPurchase(purchases);
                        handlePurchases(purchases, false);
                    }
                    inAppDone[0] = true;
                    if( subsDone[0] && inAppDone[0] ) {
                        AccessControl.setSubscriptionAccess(this, hasAccess[0]);
                        statusText.setText(fromRestoreButton ? R.string.subscription_status_restored : R.string.subscription_status_ready);
                    }
                }
        );
    }

    private boolean hasEntitlingPurchase(List<Purchase> purchases) {
        if( purchases == null ) {
            return false;
        }
        for(Purchase purchase : purchases) {
            if( purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED ) {
                continue;
            }
            for(String productId : purchase.getProducts()) {
                if( isEntitlementProduct(productId) ) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isEntitlementProduct(String productId) {
        return productId.equals(getString(R.string.billing_product_subscription))
                || productId.equals(getString(R.string.billing_product_monthly_legacy))
                || productId.equals(getString(R.string.billing_product_yearly_legacy))
                || productId.equals(getString(R.string.billing_product_lifetime));
    }

    private void handlePurchases(List<Purchase> purchases, boolean updateStatusText) {
        if( purchases == null ) {
            return;
        }
        for(Purchase purchase : purchases) {
            if( purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED ) {
                boolean entitling = false;
                for(String productId : purchase.getProducts()) {
                    if( isEntitlementProduct(productId) ) {
                        entitling = true;
                        break;
                    }
                }
                if( entitling ) {
                    boolean hadSubscriptionAccess = AccessControl.hasSubscriptionAccess(this);
                    AccessControl.setSubscriptionAccess(this, true);
                    if( !hadSubscriptionAccess ) {
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                        sharedPreferences.edit().putBoolean("preference_frame_by_frame_viewer", true).apply();
                    }
                    if( updateStatusText ) {
                        statusText.setText(R.string.subscription_status_active);
                    }
                    if( updateStatusText && containsProductId(purchase, getString(R.string.billing_product_lifetime)) ) {
                        maybePromptManageSubscriptionAfterLifetimePurchase();
                    }
                }

                if( !purchase.isAcknowledged() ) {
                    AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build();
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams, new AcknowledgePurchaseResponseListener() {
                        @Override
                        public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                            // no-op
                        }
                    });
                }
            }
            else if( purchase.getPurchaseState() == Purchase.PurchaseState.PENDING ) {
                statusText.setText(R.string.subscription_status_pending);
            }
        }
    }

    private boolean containsProductId(Purchase purchase, String productId) {
        for(String purchasedProductId : purchase.getProducts()) {
            if( purchasedProductId.equals(productId) ) {
                return true;
            }
        }
        return false;
    }

    private void maybePromptManageSubscriptionAfterLifetimePurchase() {
        if( lifetimeManagePromptShown || billingClient == null ) {
            return;
        }

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (result, purchases) -> {
                    if( result.getResponseCode() != BillingClient.BillingResponseCode.OK ) {
                        return;
                    }
                    if( !hasEntitlingPurchase(purchases) ) {
                        return;
                    }
                    runOnUiThread(this::showManageSubscriptionDialog);
                }
        );
    }

    private void showManageSubscriptionDialog() {
        if( lifetimeManagePromptShown || isFinishing() ) {
            return;
        }
        lifetimeManagePromptShown = true;

        new AlertDialog.Builder(this)
                .setTitle(R.string.subscription_lifetime_manage_title)
                .setMessage(R.string.subscription_lifetime_manage_message)
                .setNegativeButton(R.string.subscription_lifetime_manage_later, (dialog, which) -> {
                    // no-op
                })
                .setPositiveButton(R.string.subscription_lifetime_manage_action, (dialog, which) -> openPlaySubscriptionManagement())
                .show();
    }

    private void openPlaySubscriptionManagement() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_SUBSCRIPTIONS_URL + getPackageName()));
        startActivity(intent);
    }
}
