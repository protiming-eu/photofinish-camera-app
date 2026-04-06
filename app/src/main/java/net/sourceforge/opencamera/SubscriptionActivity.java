package net.sourceforge.opencamera;

import android.app.Activity;
import android.content.SharedPreferences;
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
    private BillingClient billingClient;

    private Button monthlyButton;
    private Button yearlyButton;
    private Button lifetimeButton;
    private Button restoreButton;
    private TextView statusText;

    private final Map<String, ProductDetails> productDetailsById = new HashMap<>();

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

        monthlyButton.setOnClickListener(v -> launchPurchase(getString(R.string.billing_product_monthly), BillingClient.ProductType.SUBS));
        yearlyButton.setOnClickListener(v -> launchPurchase(getString(R.string.billing_product_yearly), BillingClient.ProductType.SUBS));
        lifetimeButton.setOnClickListener(v -> launchPurchase(getString(R.string.billing_product_lifetime), BillingClient.ProductType.INAPP));
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
                .setProductId(getString(R.string.billing_product_monthly))
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(getString(R.string.billing_product_yearly))
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

            productDetailsById.clear();
            for(ProductDetails details : productDetailsList) {
                productDetailsById.put(details.getProductId(), details);
            }

            bindPriceToButtons();
        });
    }

    private void bindPriceToButtons() {
        ProductDetails monthly = productDetailsById.get(getString(R.string.billing_product_monthly));
        ProductDetails yearly = productDetailsById.get(getString(R.string.billing_product_yearly));
        ProductDetails lifetime = productDetailsById.get(getString(R.string.billing_product_lifetime));

        if( monthly != null ) {
            String price = getSubscriptionDisplayPrice(monthly);
            monthlyButton.setText(getString(R.string.subscription_buy_monthly_with_price, price));
            monthlyButton.setEnabled(true);
        }
        if( yearly != null ) {
            String price = getSubscriptionDisplayPrice(yearly);
            yearlyButton.setText(getString(R.string.subscription_buy_yearly_with_price, price));
            yearlyButton.setEnabled(true);
        }
        if( lifetime != null && lifetime.getOneTimePurchaseOfferDetails() != null ) {
            String price = lifetime.getOneTimePurchaseOfferDetails().getFormattedPrice();
            lifetimeButton.setText(getString(R.string.subscription_buy_lifetime_with_price, price));
            lifetimeButton.setEnabled(true);
        }
    }

    private String getSubscriptionDisplayPrice(ProductDetails details) {
        List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
        if( offers == null || offers.isEmpty() ) {
            return "";
        }

        ProductDetails.SubscriptionOfferDetails offer = offers.get(0);
        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        if( phases == null || phases.isEmpty() ) {
            return "";
        }

        ProductDetails.PricingPhase lastPhase = phases.get(phases.size() - 1);
        return lastPhase.getFormattedPrice();
    }

    private void launchPurchase(String productId, String productType) {
        ProductDetails details = productDetailsById.get(productId);
        if( details == null ) {
            statusText.setText(R.string.subscription_status_products_failed);
            return;
        }

        BillingFlowParams.ProductDetailsParams.Builder paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details);

        if( BillingClient.ProductType.SUBS.equals(productType) ) {
            List<ProductDetails.SubscriptionOfferDetails> offers = details.getSubscriptionOfferDetails();
            if( offers == null || offers.isEmpty() ) {
                statusText.setText(R.string.subscription_status_products_failed);
                return;
            }
            paramsBuilder.setOfferToken(offers.get(0).getOfferToken());
        }

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
        return productId.equals(getString(R.string.billing_product_monthly))
                || productId.equals(getString(R.string.billing_product_yearly))
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
}
