/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone.security;

import static android.safetycenter.SafetyCenterManager.ACTION_REFRESH_SAFETY_SOURCES;
import static android.safetycenter.SafetyCenterManager.EXTRA_REFRESH_SAFETY_SOURCES_BROADCAST_ID;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.phone.PhoneGlobals;
import com.android.telephony.Rlog;

public class SafetySourceReceiver extends BroadcastReceiver {
    private static final String TAG = "TelephonySafetySourceReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_REFRESH_SAFETY_SOURCES.equals(action)) {
            return;
        }

        String refreshBroadcastId =
                intent.getStringExtra(EXTRA_REFRESH_SAFETY_SOURCES_BROADCAST_ID);
        if (refreshBroadcastId == null) {
            return;
        }

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            refreshSafetySources(refreshBroadcastId);
        }
    }

    private void refreshSafetySources(String refreshBroadcastId) {
        Phone phone = getDefaultPhone();
        // It's possible that phones have not been created yet. Safety center may send a refresh
        // broadcast very early on.
        if (phone != null) {
            phone.refreshSafetySources(refreshBroadcastId);
        }

    }

    @VisibleForTesting
    public Phone getDefaultPhone() {
        try {
            return PhoneGlobals.getPhone();
        } catch (IllegalStateException e) {
            Rlog.i(TAG, "Unable to get phone. Skipping safety source refresh: " + e.getMessage());
        }
        return null;
    }
}
