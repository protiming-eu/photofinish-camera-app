package net.sourceforge.opencamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Access helper for features that require subscription/lifetime entitlement. */
public class AccessControl {
    private static final String PREF_SUBSCRIPTION_UNLOCKED = "pref_subscription_unlocked";
    private static final String PREF_TRIAL_STARTED_AT_MS = "pref_trial_started_at_ms";
    private static final long TRIAL_DURATION_MS = 7L * 24L * 60L * 60L * 1000L;

    private AccessControl() {
    }

    /** Returns true only for paid entitlement (subscription or lifetime). */
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

    /** Returns true if user is still inside the 7-day trial window and has not purchased yet. */
    public static boolean hasTrialAccess(Context context) {
        return getTrialRemainingMs(context) > 0L;
    }

    /** Returns remaining milliseconds in 7-day trial window (0 when expired/not applicable). */
    public static long getTrialRemainingMs(Context context) {
        if( BuildConfig.HAS_SUBSCRIPTION_ACCESS || !BuildConfig.SHOW_SUBSCRIPTION_OFFER || hasPaidSubscriptionAccess(context) ) {
            return 0L;
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        long trialStartedAtMs = sharedPreferences.getLong(PREF_TRIAL_STARTED_AT_MS, 0L);
        if( trialStartedAtMs <= 0L ) {
            trialStartedAtMs = System.currentTimeMillis();
            sharedPreferences.edit().putLong(PREF_TRIAL_STARTED_AT_MS, trialStartedAtMs).apply();
        }

        long elapsedMs = System.currentTimeMillis() - trialStartedAtMs;
        return Math.max(0L, TRIAL_DURATION_MS - elapsedMs);
    }

    /** Returns true for paid users or active trial users. */
    public static boolean hasSubscriptionAccess(Context context) {
        return hasPaidSubscriptionAccess(context) || hasTrialAccess(context);
    }

    public static void setSubscriptionAccess(Context context, boolean enabled) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        sharedPreferences.edit().putBoolean(PREF_SUBSCRIPTION_UNLOCKED, enabled).apply();
    }
}
