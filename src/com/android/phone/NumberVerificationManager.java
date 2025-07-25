/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.NumberVerificationCallback;
import android.telephony.PhoneNumberRange;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.INumberVerificationCallback;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.flags.Flags;
import com.android.telephony.Rlog;

/**
 * Singleton for managing the call based number verification requests.
 */
public class NumberVerificationManager {
    private static final String TAG = "NumberVerification";

    interface PhoneListSupplier {
        Phone[] getPhones();
    }

    private static NumberVerificationManager sInstance;
    private static String sAuthorizedPackageOverride;

    private PhoneNumberRange mCurrentRange;
    private INumberVerificationCallback mCallback;
    private final PhoneListSupplier mPhoneListSupplier;

    // We don't really care what thread this runs on, since it's only used for a non-blocking
    // timeout.
    private Handler mHandler;

    NumberVerificationManager(PhoneListSupplier phoneListSupplier) {
        mPhoneListSupplier = phoneListSupplier;
        mHandler = new Handler(Looper.getMainLooper());
    }

    private NumberVerificationManager() {
        this(PhoneFactory::getPhones);
    }

    /**
     * Check whether the incoming call matches one of the active filters. If so, call the callback
     * that says that the number has been successfully verified.
     * @param number A phone number
     * @param networkCountryISO the network country ISO for the number
     * @return true if the number matches, false otherwise
     */
    public synchronized boolean checkIncomingCall(String number, String networkCountryISO) {
        if (mCurrentRange == null || mCallback == null) {
            return false;
        }

        Log.i(TAG, "checkIncomingCall: number=" + Rlog.piiHandle(number) + ", country="
                + networkCountryISO);

        String numberInE164Format;
        if (Flags.robustNumberVerification()) {
            // Reformat the number in E.164 format prior to performing matching.
            numberInE164Format = PhoneNumberUtils.formatNumberToE164(number,
                    networkCountryISO);
            if (TextUtils.isEmpty(numberInE164Format)) {
                // Parsing failed, so we will fall back to just passing the number as-is and hope
                // for the best.  Chances are this number is an unknown number (ie no caller id),
                // so it is most likely empty.
                numberInE164Format = number;
            }
        } else {
            // Default behavior.
            numberInE164Format = number;
        }

        if (mCurrentRange.matches(numberInE164Format)) {
            mCurrentRange = null;
            try {
                // Pass back the network-matched number as-is to the caller of
                // TelephonyManager#requestNumberVerification -- do not send them the E.164 format
                // number as that changes the format of the number from what the API consumer may
                // be expecting.
                mCallback.onCallReceived(number);
                return true;
            } catch (RemoteException e) {
                Log.w(NumberVerificationManager.class.getSimpleName(),
                        "Remote exception calling verification complete callback");
                // Intercept the call even if there was a remote exception -- it's still going to be
                // a strange call from a robot number
                return true;
            } finally {
                mCallback = null;
            }
        }
        return false;
    }

    synchronized void requestVerification(PhoneNumberRange numberRange,
            INumberVerificationCallback callback, long timeoutMillis) {
        if (!checkNumberVerificationFeasibility(callback)) {
            return;
        }

        mCallback = callback;
        mCurrentRange = numberRange;

        mHandler.postDelayed(() -> {
            synchronized (NumberVerificationManager.this) {
                // Check whether the verification finished already -- if so, don't call anything.
                if (mCallback != null && mCurrentRange != null) {
                    try {
                        mCallback.onVerificationFailed(NumberVerificationCallback.REASON_TIMED_OUT);
                    } catch (RemoteException e) {
                        Log.w(NumberVerificationManager.class.getSimpleName(),
                                "Remote exception calling verification error callback");
                    }
                    mCallback = null;
                    mCurrentRange = null;
                }
            }
        }, timeoutMillis);
    }

    private boolean checkNumberVerificationFeasibility(INumberVerificationCallback callback) {
        int reason = -1;
        try {
            if (mCurrentRange != null || mCallback != null) {
                reason = NumberVerificationCallback.REASON_CONCURRENT_REQUESTS;
                return false;
            }
            boolean doesAnyPhoneHaveRoomForIncomingCall = false;
            boolean isAnyPhoneVoiceRegistered = false;
            for (Phone phone : mPhoneListSupplier.getPhones()) {
                // abort if any phone is in an emergency call or ecbm
                if (phone.isInEmergencyCall()) {
                    reason = NumberVerificationCallback.REASON_IN_EMERGENCY_CALL;
                    return false;
                }
                if (phone.isInEcm()) {
                    reason = NumberVerificationCallback.REASON_IN_ECBM;
                    return false;
                }

                // make sure at least one phone is registered for voice
                if (phone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE) {
                    isAnyPhoneVoiceRegistered = true;
                }
                // make sure at least one phone has room for an incoming call.
                if (phone.getRingingCall().getState() == Call.State.IDLE
                        && (phone.getForegroundCall().getState() == Call.State.IDLE
                        || phone.getBackgroundCall().getState() == Call.State.IDLE)) {
                    doesAnyPhoneHaveRoomForIncomingCall = true;
                }
            }
            if (!isAnyPhoneVoiceRegistered) {
                reason = NumberVerificationCallback.REASON_NETWORK_NOT_AVAILABLE;
                return false;
            }
            if (!doesAnyPhoneHaveRoomForIncomingCall) {
                reason = NumberVerificationCallback.REASON_TOO_MANY_CALLS;
                return false;
            }
        } finally {
            if (reason >= 0) {
                try {
                    callback.onVerificationFailed(reason);
                } catch (RemoteException e) {
                    Log.w(NumberVerificationManager.class.getSimpleName(),
                            "Remote exception calling verification error callback");
                }
            }
        }
        return true;
    }

    /**
     * Get the singleton instance of NumberVerificationManager.
     * @return
     */
    public static NumberVerificationManager getInstance() {
        if (sInstance == null) {
            sInstance = new NumberVerificationManager();
        }
        return sInstance;
    }

    static String getAuthorizedPackage(Context context) {
        return !TextUtils.isEmpty(sAuthorizedPackageOverride) ? sAuthorizedPackageOverride :
                context.getResources().getString(R.string.platform_number_verification_package);
    }

    /**
     * Used by shell commands to override the authorized package name for number verification.
     * @param pkgName
     */
    static void overrideAuthorizedPackage(String pkgName) {
        // Make sure we don't have any lingering callbacks kicking around as this could crash other
        // test runs that follow; also old invocations from previous test invocations could also
        // mess up things here too.
        getInstance().mCallback = null;
        getInstance().mCurrentRange = null;
        getInstance().mHandler.removeMessages(0);
        sAuthorizedPackageOverride = pkgName;
    }
}
