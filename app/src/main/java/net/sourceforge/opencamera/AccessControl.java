package net.sourceforge.opencamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Access helper for features that require subscription/lifetime entitlement. */
public class AccessControl {
    private static final String PREF_SUBSCRIPTION_UNLOCKED = "pref_subscription_unlocked";

    private AccessControl() {
    }

    public static boolean hasSubscriptionAccess(Context context) {
        if( BuildConfig.HAS_SUBSCRIPTION_ACCESS ) {
            return true;
        }
        if( !BuildConfig.SHOW_SUBSCRIPTION_OFFER ) {
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        return sharedPreferences.getBoolean(PREF_SUBSCRIPTION_UNLOCKED, false);
    }

    public static void setSubscriptionAccess(Context context, boolean enabled) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        sharedPreferences.edit().putBoolean(PREF_SUBSCRIPTION_UNLOCKED, enabled).apply();
    }
}
