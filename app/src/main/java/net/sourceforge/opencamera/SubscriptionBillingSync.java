package net.sourceforge.opencamera;

import android.content.Context;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.List;

/** Queries Play Billing purchases and updates local entitlement state. */
public class SubscriptionBillingSync {
    private static final String TAG = "SubscriptionBillingSync";

    public interface SyncCallback {
        void onCompleted(boolean hasAccess);
    }

    private SubscriptionBillingSync() {
    }

    public static void syncEntitlement(Context context, SyncCallback callback) {
        if( !BuildConfig.SHOW_SUBSCRIPTION_OFFER ) {
            if( callback != null ) {
                callback.onCompleted(AccessControl.hasSubscriptionAccess(context));
            }
            return;
        }

        final Context appContext = context.getApplicationContext();
        final BillingClient billingClient = BillingClient.newBuilder(appContext)
                .enablePendingPurchases()
                .setListener((billingResult, purchases) -> {
                    // No-op for sync-only client.
                })
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            boolean subsDone = false;
            boolean inAppDone = false;
            boolean hasEntitlement = false;

            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if( billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK ) {
                    if( MyDebug.LOG ) {
                        Log.e(TAG, "Billing setup failed: " + billingResult.getDebugMessage());
                    }
                    billingClient.endConnection();
                    if( callback != null ) {
                        callback.onCompleted(AccessControl.hasSubscriptionAccess(appContext));
                    }
                    return;
                }
                querySubscriptions();
                queryInApp();
            }

            @Override
            public void onBillingServiceDisconnected() {
                if( MyDebug.LOG ) {
                    Log.d(TAG, "Billing service disconnected");
                }
            }

            private void querySubscriptions() {
                billingClient.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                        new PurchasesResponseListener() {
                            @Override
                            public void onQueryPurchasesResponse(BillingResult result, List<Purchase> purchases) {
                                if( result.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                                    hasEntitlement = hasEntitlement || containsEntitlement(purchases);
                                }
                                subsDone = true;
                                maybeFinish();
                            }
                        }
                );
            }

            private void queryInApp() {
                billingClient.queryPurchasesAsync(
                        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                        new PurchasesResponseListener() {
                            @Override
                            public void onQueryPurchasesResponse(BillingResult result, List<Purchase> purchases) {
                                if( result.getResponseCode() == BillingClient.BillingResponseCode.OK ) {
                                    hasEntitlement = hasEntitlement || containsEntitlement(purchases);
                                }
                                inAppDone = true;
                                maybeFinish();
                            }
                        }
                );
            }

            private boolean containsEntitlement(List<Purchase> purchases) {
                if( purchases == null ) {
                    return false;
                }
                for(Purchase purchase : purchases) {
                    if( purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED ) {
                        continue;
                    }
                    for(String productId : purchase.getProducts()) {
                        if( isEntitlementProduct(appContext, productId) ) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean isEntitlementProduct(Context context, String productId) {
                return productId.equals(context.getString(R.string.billing_product_monthly))
                        || productId.equals(context.getString(R.string.billing_product_yearly))
                        || productId.equals(context.getString(R.string.billing_product_lifetime));
            }

            private void maybeFinish() {
                if( !subsDone || !inAppDone ) {
                    return;
                }
                AccessControl.setSubscriptionAccess(appContext, hasEntitlement);
                billingClient.endConnection();
                if( callback != null ) {
                    callback.onCompleted(hasEntitlement);
                }
            }
        });
    }
}
