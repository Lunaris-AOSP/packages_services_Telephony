/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.ToneGenerator;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Message;
import android.os.SystemProperties;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.ims.feature.MmTelFeature;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaDisplayInfoRec;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.imsphone.ImsPhoneCallTracker;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Phone app module that listens for phone state changes and various other
 * events from the telephony layer, and triggers any resulting UI behavior
 * (like starting the Incoming Call UI, playing in-call tones,
 * updating notifications, writing call log entries, etc.)
 */
public class CallNotifier extends Handler {
    private static final String LOG_TAG = "CallNotifier";
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // Time to display the message from the underlying phone layers.
    private static final int SHOW_MESSAGE_NOTIFICATION_TIME = 3000; // msec

    /** The singleton instance. */
    private static CallNotifier sInstance;

    private Map<Integer, CallNotifierTelephonyCallback> mTelephonyCallback =
            new ArrayMap<Integer, CallNotifierTelephonyCallback>();
    private Map<Integer, Boolean> mCFIStatus = new ArrayMap<Integer, Boolean>();
    private Map<Integer, Boolean> mMWIStatus = new ArrayMap<Integer, Boolean>();
    private PhoneGlobals mApplication;
    private CallManager mCM;
    private BluetoothHeadset mBluetoothHeadset;

    // ToneGenerator instance for playing SignalInfo tones
    private ToneGenerator mSignalInfoToneGenerator;

    // The tone volume relative to other sounds in the stream SignalInfo
    private static final int TONE_RELATIVE_VOLUME_SIGNALINFO = 80;

    private boolean mVoicePrivacyState = false;

    // Cached AudioManager
    private AudioManager mAudioManager;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;

    // Events from the Phone object:
    public static final int PHONE_DISCONNECT = 3;
    public static final int PHONE_STATE_DISPLAYINFO = 6;
    public static final int PHONE_STATE_SIGNALINFO = 7;
    public static final int PHONE_ENHANCED_VP_ON = 9;
    public static final int PHONE_ENHANCED_VP_OFF = 10;
    public static final int PHONE_SUPP_SERVICE_FAILED = 14;
    public static final int PHONE_TTY_MODE_RECEIVED = 15;
    // Events generated internally.
    // We should store all the possible event type values in one place to make sure that
    // they don't step on each others' toes.
    public static final int INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE = 22;

    public static final int UPDATE_TYPE_MWI = 0;
    public static final int UPDATE_TYPE_CFI = 1;
    public static final int UPDATE_TYPE_MWI_CFI = 2;

    /**
     * Initialize the singleton CallNotifier instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static CallNotifier init(
            PhoneGlobals app) {
        synchronized (CallNotifier.class) {
            if (sInstance == null) {
                sInstance = new CallNotifier(app);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private CallNotifier(
            PhoneGlobals app) {
        mApplication = app;
        mCM = app.mCM;

        mAudioManager = (AudioManager) mApplication.getSystemService(Context.AUDIO_SERVICE);
        mTelephonyManager =
                (TelephonyManager) mApplication.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = (SubscriptionManager) mApplication.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);

        registerForNotifications();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(mApplication.getApplicationContext(),
                    mBluetoothProfileServiceListener,
                    BluetoothProfile.HEADSET);
        }

        mSubscriptionManager.addOnSubscriptionsChangedListener(
                new OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        Log.i(LOG_TAG, "onSubscriptionsChanged");
                        updatePhoneStateListeners(true);
                    }
                });
    }

    private void createSignalInfoToneGenerator() {
        // Instantiate the ToneGenerator for SignalInfo and CallWaiting
        // TODO: We probably don't need the mSignalInfoToneGenerator instance
        // around forever. Need to change it so as to create a ToneGenerator instance only
        // when a tone is being played and releases it after its done playing.
        if (mSignalInfoToneGenerator == null) {
            try {
                mSignalInfoToneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL,
                        TONE_RELATIVE_VOLUME_SIGNALINFO);
                Log.d(LOG_TAG, "CallNotifier: mSignalInfoToneGenerator created when toneplay");
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "CallNotifier: Exception caught while creating " +
                        "mSignalInfoToneGenerator: " + e);
                mSignalInfoToneGenerator = null;
            }
        } else {
            Log.d(LOG_TAG, "mSignalInfoToneGenerator created already, hence skipping");
        }
    }

    /**
     * Register for call state notifications with the CallManager.
     */
    private void registerForNotifications() {
        mCM.registerForDisconnect(this, PHONE_DISCONNECT, null);
        mCM.registerForDisplayInfo(this, PHONE_STATE_DISPLAYINFO, null);
        mCM.registerForSignalInfo(this, PHONE_STATE_SIGNALINFO, null);
        mCM.registerForInCallVoicePrivacyOn(this, PHONE_ENHANCED_VP_ON, null);
        mCM.registerForInCallVoicePrivacyOff(this, PHONE_ENHANCED_VP_OFF, null);
        mCM.registerForSuppServiceFailed(this, PHONE_SUPP_SERVICE_FAILED, null);
        mCM.registerForTtyModeReceived(this, PHONE_TTY_MODE_RECEIVED, null);
    }

    @Override
    public void handleMessage(Message msg) {
        if (DBG) {
            Log.d(LOG_TAG, "handleMessage(" + msg.what + ")");
        }
        switch (msg.what) {
            case PHONE_DISCONNECT:
                if (DBG) log("DISCONNECT");
                // Stop any signalInfo tone being played when a call gets ended, the rest of the
                // disconnect functionality in onDisconnect() is handled in ConnectionService.
                stopSignalInfoTone();
                break;

            case PHONE_STATE_DISPLAYINFO:
                if (DBG) log("Received PHONE_STATE_DISPLAYINFO event");
                onDisplayInfo((AsyncResult) msg.obj);
                break;

            case PHONE_STATE_SIGNALINFO:
                if (DBG) log("Received PHONE_STATE_SIGNALINFO event");
                onSignalInfo((AsyncResult) msg.obj);
                break;

            case INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE:
                if (DBG) log("Received Display Info notification done event ...");
                PhoneDisplayMessage.dismissMessage();
                break;

            case PHONE_ENHANCED_VP_ON:
                if (DBG) log("PHONE_ENHANCED_VP_ON...");
                if (!mVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mVoicePrivacyState = true;
                }
                break;

            case PHONE_ENHANCED_VP_OFF:
                if (DBG) log("PHONE_ENHANCED_VP_OFF...");
                if (mVoicePrivacyState) {
                    int toneToPlay = InCallTonePlayer.TONE_VOICE_PRIVACY;
                    new InCallTonePlayer(toneToPlay).start();
                    mVoicePrivacyState = false;
                }
                break;

            case PHONE_SUPP_SERVICE_FAILED:
                if (DBG) log("PHONE_SUPP_SERVICE_FAILED...");
                onSuppServiceFailed((AsyncResult) msg.obj);
                break;

            case PHONE_TTY_MODE_RECEIVED:
                if (DBG) log("Received PHONE_TTY_MODE_RECEIVED event");
                onTtyModeReceived((AsyncResult) msg.obj);
                break;

            default:
                // super.handleMessage(msg);
        }
    }

    void updateCallNotifierRegistrationsAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateCallNotifierRegistrationsAfterRadioTechnologyChange...");

        // Instantiate mSignalInfoToneGenerator
        createSignalInfoToneGenerator();
    }

    /**
     * Helper class to play tones through the earpiece (or speaker / BT)
     * during a call, using the ToneGenerator.
     *
     * To use, just instantiate a new InCallTonePlayer
     * (passing in the TONE_* constant for the tone you want)
     * and start() it.
     *
     * When we're done playing the tone, if the phone is idle at that
     * point, we'll reset the audio routing and speaker state.
     * (That means that for tones that get played *after* a call
     * disconnects, like "busy" or "congestion" or "call ended", you
     * should NOT call resetAudioStateAfterDisconnect() yourself.
     * Instead, just start the InCallTonePlayer, which will automatically
     * defer the resetAudioStateAfterDisconnect() call until the tone
     * finishes playing.)
     */
    private class InCallTonePlayer extends Thread {
        private int mToneId;
        private int mState;
        // The possible tones we can play.
        public static final int TONE_NONE = 0;
        public static final int TONE_VOICE_PRIVACY = 5;

        // The tone volume relative to other sounds in the stream
        static final int TONE_RELATIVE_VOLUME_HIPRI = 80;

        // Buffer time (in msec) to add on to tone timeout value.
        // Needed mainly when the timeout value for a tone is the
        // exact duration of the tone itself.
        static final int TONE_TIMEOUT_BUFFER = 20;

        // The tone state
        static final int TONE_OFF = 0;
        static final int TONE_ON = 1;
        static final int TONE_STOPPED = 2;

        InCallTonePlayer(int toneId) {
            super();
            mToneId = toneId;
            mState = TONE_OFF;
        }

        @Override
        public void run() {
            log("InCallTonePlayer.run(toneId = " + mToneId + ")...");

            int toneType = 0;  // passed to ToneGenerator.startTone()
            int toneVolume;  // passed to the ToneGenerator constructor
            int toneLengthMillis;
            int phoneType = mCM.getFgPhone().getPhoneType();

            switch (mToneId) {
                case TONE_VOICE_PRIVACY:
                    toneType = ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE;
                    toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
                    toneLengthMillis = 5000;
                    break;
                default:
                    throw new IllegalArgumentException("Bad toneId: " + mToneId);
            }

            // If the mToneGenerator creation fails, just continue without it.  It is
            // a local audio signal, and is not as important.
            ToneGenerator toneGenerator;
            try {
                int stream;
                if (mBluetoothHeadset != null) {
                    stream = isBluetoothAudioOn() ? AudioSystem.STREAM_BLUETOOTH_SCO :
                            AudioSystem.STREAM_VOICE_CALL;
                } else {
                    stream = AudioSystem.STREAM_VOICE_CALL;
                }
                toneGenerator = new ToneGenerator(stream, toneVolume);
                // if (DBG) log("- created toneGenerator: " + toneGenerator);
            } catch (RuntimeException e) {
                Log.w(LOG_TAG,
                      "InCallTonePlayer: Exception caught while creating ToneGenerator: " + e);
                toneGenerator = null;
            }

            // Using the ToneGenerator (with the CALL_WAITING / BUSY /
            // CONGESTION tones at least), the ToneGenerator itself knows
            // the right pattern of tones to play; we do NOT need to
            // manually start/stop each individual tone, or manually
            // insert the correct delay between tones.  (We just start it
            // and let it run for however long we want the tone pattern to
            // continue.)
            //
            // TODO: When we stop the ToneGenerator in the middle of a
            // "tone pattern", it sounds bad if we cut if off while the
            // tone is actually playing.  Consider adding API to the
            // ToneGenerator to say "stop at the next silent part of the
            // pattern", or simply "play the pattern N times and then
            // stop."
            boolean needToStopTone = true;
            boolean okToPlayTone = false;

            if (toneGenerator != null) {
                int ringerMode = mAudioManager.getRingerMode();
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (toneType == ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("- InCallTonePlayer: start playing call tone=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT) ||
                            (toneType == ToneGenerator.TONE_CDMA_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_REORDER) ||
                            (toneType == ToneGenerator.TONE_CDMA_ABBR_INTERCEPT) ||
                            (toneType == ToneGenerator.TONE_CDMA_CALLDROP_LITE)) {
                        if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                            if (DBG) log("InCallTonePlayer:playing call fail tone:" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else if ((toneType == ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE) ||
                               (toneType == ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE)) {
                        if ((ringerMode != AudioManager.RINGER_MODE_SILENT) &&
                                (ringerMode != AudioManager.RINGER_MODE_VIBRATE)) {
                            if (DBG) log("InCallTonePlayer:playing tone for toneType=" + toneType);
                            okToPlayTone = true;
                            needToStopTone = false;
                        }
                    } else { // For the rest of the tones, always OK to play.
                        okToPlayTone = true;
                    }
                } else {  // Not "CDMA"
                    okToPlayTone = true;
                }

                synchronized (this) {
                    if (okToPlayTone && mState != TONE_STOPPED) {
                        mState = TONE_ON;
                        toneGenerator.startTone(toneType);
                        try {
                            wait(toneLengthMillis + TONE_TIMEOUT_BUFFER);
                        } catch  (InterruptedException e) {
                            Log.w(LOG_TAG,
                                  "InCallTonePlayer stopped: " + e);
                        }
                        if (needToStopTone) {
                            toneGenerator.stopTone();
                        }
                    }
                    // if (DBG) log("- InCallTonePlayer: done playing.");
                    toneGenerator.release();
                    mState = TONE_OFF;
                }
            }
        }
    }

    // Returns whether there are any connected Bluetooth audio devices
    private boolean isBluetoothAudioOn() {
        return mBluetoothHeadset.getConnectedDevices().size() > 0;
    }

    /**
     * Displays a notification when the phone receives a DisplayInfo record.
     */
    private void onDisplayInfo(AsyncResult r) {
        // Extract the DisplayInfo String from the message
        CdmaDisplayInfoRec displayInfoRec = (CdmaDisplayInfoRec)(r.result);

        if (displayInfoRec != null) {
            String displayInfo = displayInfoRec.alpha;
            if (DBG) log("onDisplayInfo: displayInfo=" + displayInfo);
            PhoneDisplayMessage.displayNetworkMessage(mApplication, displayInfo);

            // start a timer that kills the dialog
            sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                    SHOW_MESSAGE_NOTIFICATION_TIME);
        }
    }

    /**
     * Displays a notification when the phone receives a notice that a supplemental
     * service has failed.
     */
    private void onSuppServiceFailed(AsyncResult r) {
        String mergeFailedString = "";
        if (r.result == Phone.SuppService.CONFERENCE) {
            if (DBG) log("onSuppServiceFailed: displaying merge failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_conference);
        } else if (r.result == Phone.SuppService.RESUME) {
            if (DBG) log("onSuppServiceFailed: displaying resume failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_resume);
        } else if (r.result == Phone.SuppService.HOLD) {
            if (DBG) log("onSuppServiceFailed: displaying hold failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_hold);
        } else if (r.result == Phone.SuppService.TRANSFER) {
            if (DBG) log("onSuppServiceFailed: displaying transfer failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_transfer);
        } else if (r.result == Phone.SuppService.SEPARATE) {
            if (DBG) log("onSuppServiceFailed: displaying separate failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_separate);
        } else if (r.result == Phone.SuppService.SWITCH) {
            if (DBG) log("onSuppServiceFailed: displaying switch failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_switch);
        } else if (r.result == Phone.SuppService.REJECT) {
            if (DBG) log("onSuppServiceFailed: displaying reject failure message");
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_reject);
        } else if (r.result == Phone.SuppService.HANGUP) {
            mergeFailedString = mApplication.getResources().getString(
                    R.string.incall_error_supp_service_hangup);
        }  else {
            if (DBG) log("onSuppServiceFailed: unknown failure");
            return;
        }

        PhoneDisplayMessage.displayErrorMessage(mApplication, mergeFailedString);

        // start a timer that kills the dialog
        sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                SHOW_MESSAGE_NOTIFICATION_TIME);
    }

    public void updatePhoneStateListeners(boolean isRefresh) {
        updatePhoneStateListeners(isRefresh, UPDATE_TYPE_MWI_CFI,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    /**
     * Update listeners of various "phone state" things; in particular message waiting indicators
     * and call forwarding indicators.  The updates can either be due to network signals or due to
     * "refreshes".  See below for more; I'm not saying this is a good design, I'm just helping set
     * the context for how this works.
     * @param isRefresh {@code true} if this is a refresh triggered by
     * {@link OnSubscriptionsChangedListener}, which ultimately fires way more than it should, or
     * {@code false} if this update is as a direct result of the network telling us something
     * changed.
     * @param updateType {@link #UPDATE_TYPE_MWI} for message waiting indication changes by the
     * network, {@link #UPDATE_TYPE_CFI} for call forwarding changes by the network, or
     * {@link #UPDATE_TYPE_MWI_CFI} when {@code isRefresh} is {@code true}.
     * @param subIdToUpdate The sub ID the update applies to for updates from the network, or
     * {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} refreshes due to
     * {@link OnSubscriptionsChangedListener}.
     */
    public void updatePhoneStateListeners(boolean isRefresh, int updateType, int subIdToUpdate) {
        List<SubscriptionInfo> subInfos = SubscriptionManagerService.getInstance()
                .getActiveSubscriptionInfoList(mApplication.getOpPackageName(),
                        mApplication.getAttributionTag(), true/*isForAllProfile*/);

        // Sort sub id list based on slot id, so that CFI/MWI notifications will be updated for
        // slot 0 first then slot 1. This is needed to ensure that when CFI or MWI is enabled for
        // both slots, user always sees icon related to slot 0 on left side followed by that of
        // slot 1.
        List<Integer> subIdList = new ArrayList<Integer>(mTelephonyCallback.keySet());
        Collections.sort(subIdList, new Comparator<Integer>() {
            public int compare(Integer sub1, Integer sub2) {
                int slotId1 = SubscriptionManager.getSlotIndex(sub1);
                int slotId2 = SubscriptionManager.getSlotIndex(sub2);
                return slotId1 > slotId2 ? 0 : -1;
            }
        });

        for (int subIdCounter = (subIdList.size() - 1); subIdCounter >= 0; subIdCounter--) {
            int subId = subIdList.get(subIdCounter);
            if (subInfos == null || !containsSubId(subInfos, subId)) {
                Log.i(LOG_TAG, "updatePhoneStateListeners: Hide the outstanding notifications.");
                // Hide the outstanding notifications.
                mApplication.notificationMgr.updateMwi(subId, false);
                mApplication.notificationMgr.updateCfi(subId, false);

                // Unregister the listener.
                mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback.get(subId));
                mTelephonyCallback.remove(subId);
            } else {
                Log.i(LOG_TAG, "updatePhoneStateListeners: update CF/MWI for subId=" + subId);

                if (mCFIStatus.containsKey(subId)) {
                    if ((updateType == UPDATE_TYPE_CFI) && (subId == subIdToUpdate)) {
                        mApplication.notificationMgr.updateCfi(subId, mCFIStatus.get(subId),
                                isRefresh);
                    } else {
                        mApplication.notificationMgr.updateCfi(subId, mCFIStatus.get(subId), true);
                    }
                }
                // Note: This logic is needlessly convoluted.  updatePhoneStateListeners is called
                // with either:
                // 1. isRefresh && updateType == UPDATE_TYPE_MWI_CFI
                //      via updatePhoneStateListeners(bool)
                //      (ie due to onSubscriptionsChanged)
                //      This is the "refresh" case.
                // 2. !isRefresh && updateType != UPDATE_TYPE_MWI_CFI
                //      via TelephonyCallback MWI or CF changed event.
                //      This is the "non-refresh" case.
                // The same "logic" applies for call forwarding indications above.
                if (mMWIStatus.containsKey(subId)) {
                    if ((updateType == UPDATE_TYPE_MWI) && (subId == subIdToUpdate)) {
                        mApplication.notificationMgr.updateMwi(subId, mMWIStatus.get(subId),
                            isRefresh);
                    } else {
                        mApplication.notificationMgr.updateMwi(subId, mMWIStatus.get(subId), true);
                    }
                }
            }
        }

        if (subInfos == null) {
            return;
        }

        // Register new phone listeners for active subscriptions.
        for (int i = 0; i < subInfos.size(); i++) {
            int subId = subInfos.get(i).getSubscriptionId();
            if (!mTelephonyCallback.containsKey(subId)) {
                CallNotifierTelephonyCallback listener = new CallNotifierTelephonyCallback(subId);
                mTelephonyManager.createForSubscriptionId(subId).registerTelephonyCallback(
                        new HandlerExecutor(this), listener);
                mTelephonyCallback.put(subId, listener);
            }
        }
    }

    /**
     * @return {@code true} if the list contains SubscriptionInfo with the given subscription id.
     */
    private boolean containsSubId(List<SubscriptionInfo> subInfos, int subId) {
        if (subInfos == null) {
            return false;
        }

        for (int i = 0; i < subInfos.size(); i++) {
            if (subInfos.get(i).getSubscriptionId() == subId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Displays a notification when the phone receives a notice that TTY mode
     * has changed on remote end.
     */
    private void onTtyModeReceived(AsyncResult r) {
        if (DBG) log("TtyModeReceived: displaying notification message");

        int resId = 0;
        switch (((Integer)r.result).intValue()) {
            case TelecomManager.TTY_MODE_FULL:
                resId = com.android.internal.R.string.peerTtyModeFull;
                break;
            case TelecomManager.TTY_MODE_HCO:
                resId = com.android.internal.R.string.peerTtyModeHco;
                break;
            case TelecomManager.TTY_MODE_VCO:
                resId = com.android.internal.R.string.peerTtyModeVco;
                break;
            case TelecomManager.TTY_MODE_OFF:
                resId = com.android.internal.R.string.peerTtyModeOff;
                break;
            default:
                Log.e(LOG_TAG, "Unsupported TTY mode: " + r.result);
                break;
        }
        if (resId != 0) {
            PhoneDisplayMessage.displayNetworkMessage(mApplication,
                    mApplication.getResources().getString(resId));

            // start a timer that kills the dialog
            sendEmptyMessageDelayed(INTERNAL_SHOW_MESSAGE_NOTIFICATION_DONE,
                    SHOW_MESSAGE_NOTIFICATION_TIME);
        }
    }

    /**
     * Helper class to play SignalInfo tones using the ToneGenerator.
     *
     * To use, just instantiate a new SignalInfoTonePlayer
     * (passing in the ToneID constant for the tone you want)
     * and start() it.
     */
    private class SignalInfoTonePlayer extends Thread {
        private int mToneId;

        SignalInfoTonePlayer(int toneId) {
            super();
            mToneId = toneId;
        }

        @Override
        public void run() {
            log("SignalInfoTonePlayer.run(toneId = " + mToneId + ")...");
            createSignalInfoToneGenerator();
            if (mSignalInfoToneGenerator != null) {
                //First stop any ongoing SignalInfo tone
                mSignalInfoToneGenerator.stopTone();

                //Start playing the new tone if its a valid tone
                mSignalInfoToneGenerator.startTone(mToneId);
            }
        }
    }

    /**
     * Plays a tone when the phone receives a SignalInfo record.
     */
    private void onSignalInfo(AsyncResult r) {
        // Signal Info are totally ignored on non-voice-capable devices.
        if (!PhoneGlobals.sVoiceCapable) {
            Log.w(LOG_TAG, "Got onSignalInfo() on non-voice-capable device! Ignoring...");
            return;
        }

        if (PhoneUtils.isRealIncomingCall(mCM.getFirstActiveRingingCall().getState())) {
            // Do not start any new SignalInfo tone when Call state is INCOMING
            // and stop any previous SignalInfo tone which is being played
            stopSignalInfoTone();
        } else {
            // Extract the SignalInfo String from the message
            CdmaSignalInfoRec signalInfoRec = (CdmaSignalInfoRec)(r.result);
            // Only proceed if a Signal info is present.
            if (signalInfoRec != null) {
                boolean isPresent = signalInfoRec.isPresent;
                if (DBG) log("onSignalInfo: isPresent=" + isPresent);
                if (isPresent) {// if tone is valid
                    int uSignalType = signalInfoRec.signalType;
                    int uAlertPitch = signalInfoRec.alertPitch;
                    int uSignal = signalInfoRec.signal;

                    if (DBG) log("onSignalInfo: uSignalType=" + uSignalType + ", uAlertPitch=" +
                            uAlertPitch + ", uSignal=" + uSignal);
                    //Map the Signal to a ToneGenerator ToneID only if Signal info is present
                    int toneID = SignalToneUtil.getAudioToneFromSignalInfo
                            (uSignalType, uAlertPitch, uSignal);

                    //Create the SignalInfo tone player and pass the ToneID
                    new SignalInfoTonePlayer(toneID).start();
                }
            }
        }
    }

    /**
     * Stops a SignalInfo tone in the following condition
     * 1 - On receiving a New Ringing Call
     * 2 - On disconnecting a call
     * 3 - On answering a Call Waiting Call
     */
    /* package */ void stopSignalInfoTone() {
        if (DBG) log("stopSignalInfoTone: Stopping SignalInfo tone player");
        new SignalInfoTonePlayer(ToneGenerator.TONE_CDMA_SIGNAL_OFF).start();
    }

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
           new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mBluetoothHeadset = (BluetoothHeadset) proxy;
                    if (VDBG) log("- Got BluetoothHeadset: " + mBluetoothHeadset);
                }

                public void onServiceDisconnected(int profile) {
                    mBluetoothHeadset = null;
                }
            };

    private class CallNotifierTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.MessageWaitingIndicatorListener,
            TelephonyCallback.CallForwardingIndicatorListener {

        private final int mSubId;

        CallNotifierTelephonyCallback(int subId) {
            super();
            this.mSubId = subId;
        }

        /**
         * Handle changes to the message waiting indicator.
         * This originates from {@link ImsPhoneCallTracker} via the
         * {@link MmTelFeature.Listener#onVoiceMessageCountUpdate(int)} callback from the IMS
         * implementation (there is something similar for GSM/CDMA, but that is old news).
         * @param visible Whether the message waiting indicator has changed or not.
         */
        @Override
        public void onMessageWaitingIndicatorChanged(boolean visible) {
            Log.i(LOG_TAG, "onMessageWaitingIndicatorChanged(): subId=" + this.mSubId
                    + ", visible=" + (visible ? "Y" : "N"));
            mMWIStatus.put(this.mSubId, visible);
            // Trigger a "non-refresh" update to the MWI indicator.
            updatePhoneStateListeners(false /* isRefresh */, UPDATE_TYPE_MWI, this.mSubId);
        }

        @Override
        public void onCallForwardingIndicatorChanged(boolean visible) {
            Log.i(LOG_TAG, "onCallForwardingIndicatorChanged(): subId=" + this.mSubId
                    + ", visible=" + (visible ? "Y" : "N"));
            mCFIStatus.put(this.mSubId, visible);
            updatePhoneStateListeners(false, UPDATE_TYPE_CFI, this.mSubId);
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
