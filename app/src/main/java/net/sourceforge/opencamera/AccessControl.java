package net.sourceforge.opencamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Access helper for features that require subscription/lifetime entitlement. */
public class AccessControl {
    private static final String PREF_SUBSCRIPTION_UNLOCKED = "pref_subscription_unlocked";

    private AccessControl() {
    }

    /** Returns true for an active Play Billing entitlement (subscription, free trial, or lifetime). */
    public static boolean hasPaidSubscriptionAccess(Context context) {
        if( BuildConfig.HAS_SUBSCRIPTION_ACCESS ) {
            return true;
        }
        if( !BuildConfig.SHOW_SUBSCRIPTION_OFFER ) {
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return sharedPreferences.getBoolean(PREF_SUBSCRIPTION_UNLOCKED, false);
    }

    /** Returns true when Pro features should be available. */
    public static boolean hasSubscriptionAccess(Context context) {
        return hasPaidSubscriptionAccess(context);
    }

    public static void setSubscriptionAccess(Context context, boolean enabled) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        sharedPreferences.edit().putBoolean(PREF_SUBSCRIPTION_UNLOCKED, enabled).apply();
    }
}
