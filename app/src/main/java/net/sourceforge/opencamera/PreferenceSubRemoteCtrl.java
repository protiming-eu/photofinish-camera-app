package net.sourceforge.opencamera;

import android.os.Bundle;
import android.preference.Preference;
import android.util.Log;

import net.sourceforge.opencamera.remotecontrol.WebRemoteControl;

public class PreferenceSubRemoteCtrl extends PreferenceSubScreen {
    private static final String TAG = "PreferenceSubRemoteCtrl";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_sub_remote_ctrl);
        updateWebRemoteStatus();
        if( MyDebug.LOG )
            Log.d(TAG, "onCreate done");
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWebRemoteStatus();
    }

    @Override
    public void onSharedPreferenceChanged(android.content.SharedPreferences prefs, String key) {
        super.onSharedPreferenceChanged(prefs, key);
        if( PreferenceKeys.EnableWebRemote.equals(key) || PreferenceKeys.WebRemotePin.equals(key) ) {
            updateWebRemoteStatus();
        }
    }

    private void updateWebRemoteStatus() {
        Preference webRemoteStatus = findPreference(PreferenceKeys.WebRemoteStatus);
        if( webRemoteStatus != null && getActivity() != null ) {
            webRemoteStatus.setSummary(WebRemoteControl.getAccessSummary(getActivity()));
        }
    }
}
