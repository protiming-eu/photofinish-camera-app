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
    private TextView subtitleText;
    private TextView statusText;

    private ProductDetails subscriptionProductDetails;
    private ProductDetails lifetimeProductDetails;
    private final Map<String, ProductDetails.SubscriptionOfferDetails> subscriptionOfferByBasePlanId = new HashMap<>();
    private final List<ProductDetails.SubscriptionOfferDetails> subscriptionOffers = new ArrayList<>();
    private ProductDetails.SubscriptionOfferDetails monthlyOfferDetails;
    private ProductDetails.SubscriptionOfferDetails yearlyOfferDetails;
    private boolean lifetimeManagePromptShown;
    private boolean hasProductAvailabilityWarning;

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
        subtitleText = findViewById(R.id.tv_subscription_subtitle);
        statusText = findViewById(R.id.tv_billing_status);

        monthlyButton.setEnabled(false);
        yearlyButton.setEnabled(false);
        lifetimeButton.setEnabled(false);
        subtitleText.setVisibility(View.GONE);

        monthlyButton.setOnClickListener(v -> launchSubscriptionPurchase(monthlyOfferDetails, getString(R.string.billing_subscription_base_plan_monthly)));
        yearlyButton.setOnClickListener(v -> launchSubscriptionPurchase(yearlyOfferDetails, getString(R.string.billing_subscription_base_plan_yearly)));
        lifetimeButton.setOnClickListener(v -> launchLifetimePurchase());

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
                runOnUiThread(() -> {
                    if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                        statusText.setText(R.string.subscription_status_ready);
                        queryProductDetails();
                        refreshPurchases(false);
                    }
                    else {
                        statusText.setText(R.string.subscription_status_unavailable);
                    }
                });
            }

            @Override
            public void onBillingServiceDisconnected() {
                runOnUiThread(() -> statusText.setText(R.string.subscription_status_reconnecting));
            }
        });
    }

    private void queryProductDetails() {
        querySubscriptionProductDetails();
        queryLifetimeProductDetails();
    }

    private void querySubscriptionProductDetails() {
        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(getString(R.string.billing_product_subscription))
                .setProductType(BillingClient.ProductType.SUBS)
                .build());

        QueryProductDetailsParams query = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        billingClient.queryProductDetailsAsync(query, (billingResult, productDetailsList) -> runOnUiThread(() -> {
            if( billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK ) {
                hasProductAvailabilityWarning = true;
                statusText.setText(R.string.subscription_status_products_failed);
                return;
            }

            subscriptionProductDetails = null;
            subscriptionOfferByBasePlanId.clear();
            subscriptionOffers.clear();
            monthlyOfferDetails = null;
            yearlyOfferDetails = null;

            for(ProductDetails details : productDetailsList) {
                if( details.getProductType().equals(BillingClient.ProductType.SUBS)
                        && details.getProductId().equals(getString(R.string.billing_product_subscription)) ) {
                    subscriptionProductDetails = details;
                    cacheSubscriptionOffers(details);
                    break;
                }
            }

            bindPriceToButtons();
        }));
    }

    private void queryLifetimeProductDetails() {
        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(getString(R.string.billing_product_lifetime))
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        QueryProductDetailsParams query = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        billingClient.queryProductDetailsAsync(query, (billingResult, productDetailsList) -> runOnUiThread(() -> {
            if( billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK ) {
                lifetimeProductDetails = null;
                bindPriceToButtons();
                return;
            }

            lifetimeProductDetails = null;
            for(ProductDetails details : productDetailsList) {
                if( details.getProductType().equals(BillingClient.ProductType.INAPP)
                        && details.getProductId().equals(getString(R.string.billing_product_lifetime))
                        && details.getOneTimePurchaseOfferDetails() != null ) {
                    lifetimeProductDetails = details;
                    break;
                }
            }

            bindPriceToButtons();
        }));
    }

    private void cacheSubscriptionOffers(ProductDetails details) {
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if( offers == null ) {
            return;
        }
        subscriptionOffers.clear();
        for(ProductDetails.SubscriptionOfferDetails offer : offers) {
            subscriptionOffers.add(offer);
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
        monthlyButton.setEnabled(false);
        yearlyButton.setEnabled(false);
        lifetimeButton.setEnabled(false);
        monthlyButton.setText(R.string.subscription_buy_monthly);
        yearlyButton.setText(R.string.subscription_buy_yearly);
        lifetimeButton.setText(R.string.subscription_buy_lifetime);

        monthlyOfferDetails = resolveSubscriptionOffer(
                getString(R.string.billing_subscription_base_plan_monthly),
                "P1M"
        );
        yearlyOfferDetails = resolveSubscriptionOffer(
                getString(R.string.billing_subscription_base_plan_yearly),
                "P1Y"
        );
        ProductDetails lifetime = lifetimeProductDetails;
        String monthlyPrice = null;
        String yearlyPrice = null;
        String lifetimePrice = null;

        if( monthlyOfferDetails != null ) {
            monthlyPrice = getSubscriptionDisplayPrice(monthlyOfferDetails);
            monthlyButton.setText(getString(R.string.subscription_buy_monthly_with_price, monthlyPrice));
            monthlyButton.setEnabled(true);
        }
        if( yearlyOfferDetails != null ) {
            yearlyPrice = getSubscriptionDisplayPrice(yearlyOfferDetails);
            yearlyButton.setText(getString(R.string.subscription_buy_yearly_with_price, yearlyPrice));
            yearlyButton.setEnabled(true);
        }
        if( lifetime != null && lifetime.getOneTimePurchaseOfferDetails() != null ) {
            lifetimePrice = lifetime.getOneTimePurchaseOfferDetails().getFormattedPrice();
            lifetimeButton.setText(getString(R.string.subscription_buy_lifetime_with_price, lifetimePrice));
            lifetimeButton.setEnabled(true);
        }

        if( monthlyOfferDetails == null && yearlyOfferDetails == null ) {
            hasProductAvailabilityWarning = true;
            statusText.setText(R.string.subscription_status_plans_unavailable);
        }
        else if( monthlyOfferDetails == null ) {
            hasProductAvailabilityWarning = true;
            statusText.setText(getString(
                    R.string.subscription_status_missing_base_plan,
                    getString(R.string.billing_subscription_base_plan_monthly)
            ));
        }
        else if( yearlyOfferDetails == null ) {
            hasProductAvailabilityWarning = true;
            statusText.setText(getString(
                    R.string.subscription_status_missing_base_plan,
                    getString(R.string.billing_subscription_base_plan_yearly)
            ));
        }
        else {
            hasProductAvailabilityWarning = false;
            statusText.setText(R.string.subscription_status_ready);
        }

        bindLocalizedOfferText(monthlyPrice, yearlyPrice, lifetimePrice);
    }

    private void bindLocalizedOfferText(String monthlyPrice, String yearlyPrice, String lifetimePrice) {
        List<String> parts = new ArrayList<>();
        if( monthlyPrice != null && !monthlyPrice.isEmpty() ) {
            parts.add(getString(R.string.subscription_offer_price_monthly, monthlyPrice));
        }
        if( yearlyPrice != null && !yearlyPrice.isEmpty() ) {
            parts.add(getString(R.string.subscription_offer_price_yearly, yearlyPrice));
        }
        if( lifetimePrice != null && !lifetimePrice.isEmpty() ) {
            parts.add(getString(R.string.subscription_offer_price_lifetime, lifetimePrice));
        }

        if( parts.isEmpty() ) {
            subtitleText.setVisibility(View.GONE);
            return;
        }

        String separator = getString(R.string.subscription_offer_price_separator);
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < parts.size(); i++) {
            if( i > 0 ) {
                builder.append(separator);
            }
            builder.append(parts.get(i));
        }

        subtitleText.setText(builder.toString());
        subtitleText.setVisibility(View.VISIBLE);
    }

    private String getSubscriptionDisplayPrice(ProductDetails.SubscriptionOfferDetails offer) {
        ProductDetails.PricingPhase lastPhase = getLastPricingPhase(offer);
        if( lastPhase == null ) {
            return "";
        }
        return lastPhase.getFormattedPrice();
    }

    private ProductDetails.PricingPhase getLastPricingPhase(ProductDetails.SubscriptionOfferDetails offer) {
        if( offer == null || offer.getPricingPhases() == null ) {
            return null;
        }
        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        if( phases == null || phases.isEmpty() ) {
            return null;
        }
        return phases.get(phases.size() - 1);
    }

    private ProductDetails.SubscriptionOfferDetails resolveSubscriptionOffer(String basePlanId, String billingPeriod) {
        ProductDetails.SubscriptionOfferDetails byBasePlanId = subscriptionOfferByBasePlanId.get(basePlanId);
        if( byBasePlanId != null ) {
            return byBasePlanId;
        }

        for(ProductDetails.SubscriptionOfferDetails offer : subscriptionOffers) {
            ProductDetails.PricingPhase lastPhase = getLastPricingPhase(offer);
            if( lastPhase != null && billingPeriod.equals(lastPhase.getBillingPeriod()) ) {
                return offer;
            }
        }

        return null;
    }

    private void launchSubscriptionPurchase(ProductDetails.SubscriptionOfferDetails offerDetails, String expectedBasePlanId) {
        if( subscriptionProductDetails == null ) {
            statusText.setText(R.string.subscription_status_products_failed);
            return;
        }

        if( offerDetails == null ) {
            statusText.setText(getString(R.string.subscription_status_missing_base_plan, expectedBasePlanId));
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
        runOnUiThread(() -> {
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
        });
    }

    private void refreshPurchases(boolean fromRestoreButton) {
        final boolean[] subsDone = {false};
        final boolean[] inAppDone = {false};
        final boolean[] hasAccess = {false};

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (result, purchases) -> runOnUiThread(() -> {
                    if( result.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                        hasAccess[0] = hasAccess[0] || hasEntitlingPurchase(purchases);
                        handlePurchases(purchases, false);
                    }
                    subsDone[0] = true;
                    if( subsDone[0] && inAppDone[0] ) {
                        AccessControl.setSubscriptionAccess(this, hasAccess[0]);
                        if( !hasProductAvailabilityWarning ) {
                            statusText.setText(fromRestoreButton ? R.string.subscription_status_restored : R.string.subscription_status_ready);
                        }
                    }
                })
        );

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> runOnUiThread(() -> {
                    if( result.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                        hasAccess[0] = hasAccess[0] || hasEntitlingPurchase(purchases);
                        handlePurchases(purchases, false);
                    }
                    inAppDone[0] = true;
                    if( subsDone[0] && inAppDone[0] ) {
                        AccessControl.setSubscriptionAccess(this, hasAccess[0]);
                        if( !hasProductAvailabilityWarning ) {
                            statusText.setText(fromRestoreButton ? R.string.subscription_status_restored : R.string.subscription_status_ready);
                        }
                    }
                })
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
