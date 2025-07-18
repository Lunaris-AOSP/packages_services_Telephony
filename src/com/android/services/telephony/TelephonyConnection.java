/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import static android.telephony.ims.ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED;
import static android.telephony.ims.ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL;
import static android.telephony.ims.ImsReasonInfo.EXTRA_CODE_CALL_RETRY_EMERGENCY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PersistableBundle;
import android.telecom.CallAudioState;
import android.telecom.CallDiagnostics;
import android.telecom.CallScreeningService;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.ServiceState.RilRadioTechnology;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.RtpHeaderExtension;
import android.telephony.ims.RtpHeaderExtensionType;
import android.telephony.ims.feature.MmTelFeature;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;

import com.android.ims.ImsCall;
import com.android.ims.ImsException;
import com.android.ims.internal.ConferenceParticipant;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallFailCause;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection.Capability;
import com.android.internal.telephony.Connection.PostDialListener;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.d2d.Communicator;
import com.android.internal.telephony.d2d.DtmfAdapter;
import com.android.internal.telephony.d2d.DtmfTransport;
import com.android.internal.telephony.d2d.MessageTypeAndValueHelper;
import com.android.internal.telephony.d2d.RtpAdapter;
import com.android.internal.telephony.d2d.RtpTransport;
import com.android.internal.telephony.d2d.Timeouts;
import com.android.internal.telephony.d2d.TransportProtocol;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCall;
import com.android.internal.telephony.imsphone.ImsPhoneCallTracker;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.phone.ImsUtil;
import com.android.phone.PhoneGlobals;
import com.android.phone.PhoneUtils;
import com.android.phone.R;
import com.android.phone.callcomposer.CallComposerPictureManager;
import com.android.phone.callcomposer.CallComposerPictureTransfer;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Base class for CDMA and GSM connections.
 */
abstract class TelephonyConnection extends Connection implements Holdable, Communicator.Callback {
    private static final String LOG_TAG = "TelephonyConnection";

    private static final int MSG_PRECISE_CALL_STATE_CHANGED = 1;
    private static final int MSG_RINGBACK_TONE = 2;
    private static final int MSG_HANDOVER_STATE_CHANGED = 3;
    private static final int MSG_DISCONNECT = 4;
    private static final int MSG_MULTIPARTY_STATE_CHANGED = 5;
    private static final int MSG_CONFERENCE_MERGE_FAILED = 6;
    private static final int MSG_SUPP_SERVICE_NOTIFY = 7;

    // the threshold used to compare mAudioCodecBitrateKbps and mAudioCodecBandwidth.
    private static final float THRESHOLD = 0.01f;

    /**
     * Mappings from {@link com.android.internal.telephony.Connection} extras keys to their
     * equivalents defined in {@link android.telecom.Connection}.
     */
    private static final Map<String, String> sExtrasMap = createExtrasMap();

    private static final int MSG_SET_VIDEO_STATE = 8;
    private static final int MSG_SET_VIDEO_PROVIDER = 9;
    private static final int MSG_SET_AUDIO_QUALITY = 10;
    private static final int MSG_SET_CONFERENCE_PARTICIPANTS = 11;
    private static final int MSG_CONNECTION_EXTRAS_CHANGED = 12;
    private static final int MSG_SET_ORIGNAL_CONNECTION_CAPABILITIES = 13;
    private static final int MSG_ON_HOLD_TONE = 14;
    private static final int MSG_CDMA_VOICE_PRIVACY_ON = 15;
    private static final int MSG_CDMA_VOICE_PRIVACY_OFF = 16;
    private static final int MSG_HANGUP = 17;
    private static final int MSG_SET_CALL_RADIO_TECH = 18;
    private static final int MSG_ON_CONNECTION_EVENT = 19;
    private static final int MSG_REDIAL_CONNECTION_CHANGED = 20;
    private static final int MSG_REJECT = 21;
    private static final int MSG_DTMF_DONE = 22;
    private static final int MSG_MEDIA_ATTRIBUTES_CHANGED = 23;
    private static final int MSG_ON_RTT_INITIATED = 24;
    private static final int MSG_HOLD = 25;
    private static final int MSG_UNHOLD = 26;

    private static final String JAPAN_COUNTRY_CODE_WITH_PLUS_SIGN = "+81";
    private static final String JAPAN_ISO_COUNTRY_CODE = "JP";

    private List<Uri> mParticipants;
    private boolean mIsAdhocConferenceCall;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PRECISE_CALL_STATE_CHANGED:
                    Log.v(TelephonyConnection.this, "MSG_PRECISE_CALL_STATE_CHANGED");
                    updateState();
                    break;
                case MSG_HANDOVER_STATE_CHANGED:
                    // fall through
                case MSG_REDIAL_CONNECTION_CHANGED:
                    String what = (msg.what == MSG_HANDOVER_STATE_CHANGED)
                            ? "MSG_HANDOVER_STATE_CHANGED" : "MSG_REDIAL_CONNECTION_CHANGED";
                    Log.i(TelephonyConnection.this, "Connection changed due to: %s", what);
                    AsyncResult ar = (AsyncResult) msg.obj;
                    com.android.internal.telephony.Connection connection =
                         (com.android.internal.telephony.Connection) ar.result;
                    onOriginalConnectionRedialed(connection);
                    break;
                case MSG_RINGBACK_TONE:
                    Log.v(TelephonyConnection.this, "MSG_RINGBACK_TONE");
                    // TODO: This code assumes that there is only one connection in the foreground
                    // call, in other words, it punts on network-mediated conference calling.
                    if (getOriginalConnection() != getForegroundConnection()) {
                        Log.v(TelephonyConnection.this, "handleMessage, original connection is " +
                                "not foreground connection, skipping");
                        return;
                    }
                    boolean ringback = (Boolean) ((AsyncResult) msg.obj).result;
                    setRingbackRequested(ringback);
                    notifyRingbackRequested(ringback);
                    break;
                case MSG_DISCONNECT:
                    updateState();
                    break;
                case MSG_MULTIPARTY_STATE_CHANGED:
                    boolean isMultiParty = (Boolean) msg.obj;
                    Log.i(this, "Update multiparty state to %s", isMultiParty ? "Y" : "N");
                    mIsMultiParty = isMultiParty;
                    if (isMultiParty) {
                        notifyConferenceStarted();
                    }
                    break;
                case MSG_CONFERENCE_MERGE_FAILED:
                    notifyConferenceMergeFailed();
                    break;
                case MSG_SUPP_SERVICE_NOTIFY:
                    Phone phone = getPhone();
                    Log.v(TelephonyConnection.this, "MSG_SUPP_SERVICE_NOTIFY on phoneId : "
                            + (phone != null ? Integer.toString(phone.getPhoneId())
                            : "null"));
                    SuppServiceNotification mSsNotification = null;
                    if (msg.obj != null && ((AsyncResult) msg.obj).result != null) {
                        mSsNotification =
                                (SuppServiceNotification)((AsyncResult) msg.obj).result;
                        if (mOriginalConnection != null) {
                            handleSuppServiceNotification(mSsNotification);
                        }
                    }
                    break;

                case MSG_SET_VIDEO_STATE:
                    int videoState = (int) msg.obj;
                    setTelephonyVideoState(videoState);

                    // A change to the video state of the call can influence whether or not it
                    // can be part of a conference, whether another call can be added, and
                    // whether the call should have the HD audio property set.
                    refreshConferenceSupported();
                    refreshDisableAddCall();
                    refreshHoldSupported();
                    updateConnectionProperties();
                    break;

                case MSG_SET_VIDEO_PROVIDER:
                    VideoProvider videoProvider = (VideoProvider) msg.obj;
                    setTelephonyVideoProvider(videoProvider);
                    break;

                case MSG_SET_AUDIO_QUALITY:
                    int audioQuality = (int) msg.obj;
                    setAudioQuality(audioQuality);
                    break;

                case MSG_MEDIA_ATTRIBUTES_CHANGED:
                    refreshCodec();
                    break;

                case MSG_SET_CONFERENCE_PARTICIPANTS:
                    List<ConferenceParticipant> participants = (List<ConferenceParticipant>) msg.obj;
                    updateConferenceParticipants(participants);
                    break;

                case MSG_CONNECTION_EXTRAS_CHANGED:
                    final Bundle extras = (Bundle) msg.obj;
                    updateExtras(extras);
                    break;

                case MSG_SET_ORIGNAL_CONNECTION_CAPABILITIES:
                    setOriginalConnectionCapabilities(msg.arg1);
                    break;

                case MSG_ON_HOLD_TONE:
                    AsyncResult asyncResult = (AsyncResult) msg.obj;
                    Pair<com.android.internal.telephony.Connection, Boolean> heldInfo =
                            (Pair<com.android.internal.telephony.Connection, Boolean>)
                                    asyncResult.result;

                    // Determines if the hold tone is starting or stopping.
                    boolean playTone = ((Boolean) (heldInfo.second)).booleanValue();

                    // Determine which connection the hold tone is stopping or starting for
                    com.android.internal.telephony.Connection heldConnection = heldInfo.first;

                    // Only start or stop the hold tone if this is the connection which is starting
                    // or stopping the hold tone.
                    if (heldConnection == mOriginalConnection) {
                        // If starting the hold tone, send a connection event to Telecom which will
                        // cause it to play the on hold tone.
                        if (playTone) {
                            sendTelephonyConnectionEvent(EVENT_ON_HOLD_TONE_START, null);
                        } else {
                            sendTelephonyConnectionEvent(EVENT_ON_HOLD_TONE_END, null);
                        }
                    }
                    break;

                case MSG_CDMA_VOICE_PRIVACY_ON:
                    Log.d(this, "MSG_CDMA_VOICE_PRIVACY_ON received");
                    setCdmaVoicePrivacy(true);
                    break;
                case MSG_CDMA_VOICE_PRIVACY_OFF:
                    Log.d(this, "MSG_CDMA_VOICE_PRIVACY_OFF received");
                    setCdmaVoicePrivacy(false);
                    break;
                case MSG_HANGUP:
                    int cause = (int) msg.obj;
                    hangup(cause);
                    break;
                case MSG_REJECT:
                    int rejectReason = (int) msg.obj;
                    reject(rejectReason);
                    break;
                case MSG_DTMF_DONE:
                    Log.i(this, "MSG_DTMF_DONE");
                    break;

                case MSG_SET_CALL_RADIO_TECH:
                    int vrat = (int) msg.obj;
                    // Check whether Wi-Fi call tech is changed, it means call radio tech is:
                    //  a) changed from IWLAN to other value, or
                    //  b) changed from other value to IWLAN.
                    //
                    // In other word, below conditions are all met:
                    // 1) {@link #getCallRadioTech} is different from new vrat
                    // 2) Current call radio technology indicates Wi-Fi call, i.e. {@link #isWifi}
                    //    is true, or new vrat indicates Wi-Fi call.
                    boolean isWifiTechChange = getCallRadioTech() != vrat
                            && (isWifi() || vrat == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN);

                    // Step 1) Updates call radio tech firstly, so that afterwards Wi-Fi related
                    // update actions are taken correctly.
                    setCallRadioTech(vrat);

                    // Step 2) Handles Wi-Fi call tech change.
                    if (isWifiTechChange) {
                        updateConnectionProperties();
                        updateStatusHints();
                        refreshDisableAddCall();
                    }
                    break;
                case MSG_ON_CONNECTION_EVENT:
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        sendTelephonyConnectionEvent((String) args.arg1, (Bundle) args.arg2);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_ON_RTT_INITIATED:
                    if (mOriginalConnection != null) {
                        // if mOriginalConnection is null, the properties will get set when
                        // mOriginalConnection gets set.
                        updateConnectionProperties();
                        refreshConferenceSupported();
                    }
                    sendRttInitiationSuccess();
                    break;
                case MSG_HOLD:
                    performHold();
                    break;
                case MSG_UNHOLD:
                    performUnhold();
                    break;
            }
        }
    };

    private final Messenger mHandlerMessenger = new Messenger(mHandler);

    /**
     * The underlying telephony Connection has been redialed on a different domain (CS or IMS).
     * Track the new telephony Connection and set back up appropriate callbacks.
     * @param connection The new telephony Connection associated with this TelephonyConnection.
     */
    @VisibleForTesting
    public void onOriginalConnectionRedialed(
            com.android.internal.telephony.Connection connection) {
        if (connection == null) {
            setDisconnected(DisconnectCauseUtil
                    .toTelecomDisconnectCause(DisconnectCause.OUT_OF_NETWORK,
                            "handover failure, no connection"));
            close();
            return;
        }
        if (mOriginalConnection != null) {
            if ((connection.getAddress() != null
                    && mOriginalConnection.getAddress() != null
                    && mOriginalConnection.getAddress().equals(connection.getAddress()))
                    || connection.getState() == mOriginalConnection.getStateBeforeHandover()) {
                Log.i(TelephonyConnection.this, "Setting original connection after"
                        + " handover or redial, current original connection="
                        + mOriginalConnection.toString()
                        + ", new original connection="
                        + connection.toString());
                setOriginalConnection(connection);
                mWasImsConnection = false;
                if (mHangupDisconnectCause != DisconnectCause.NOT_VALID) {
                    // A hangup request was initiated during the handover process, so
                    // go ahead and initiate the hangup on the new connection.
                    try {
                        Log.i(TelephonyConnection.this, "user has tried to hangup "
                                + "during handover, retrying hangup.");
                        connection.hangup();
                    } catch (CallStateException e) {
                        // Call state exception may be thrown if the connection was
                        // already disconnected, so just log this case.
                        Log.w(TelephonyConnection.this, "hangup during "
                                + "handover or redial resulted in an exception:" + e);
                    }
                }
            }
        } else {
            Log.w(TelephonyConnection.this, " mOriginalConnection==null --"
                    + " invalid state (not cleaned up)");
        }
    }

    /**
     * Handles {@link SuppServiceNotification}s pertinent to Telephony.
     * @param ssn the notification.
     */
    private void handleSuppServiceNotification(SuppServiceNotification ssn) {
        Log.i(this, "handleSuppServiceNotification: type=%d, code=%d", ssn.notificationType,
                ssn.code);
        if (ssn.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_CODE_1
                && ssn.code == SuppServiceNotification.CODE_1_CALL_FORWARDED) {
            sendTelephonyConnectionEvent(TelephonyManager.EVENT_CALL_FORWARDED, null);
        }
        sendSuppServiceNotificationEvent(ssn.notificationType, ssn.code);
    }

    /**
     * Sends a supplementary service notification connection event.
     * This connection event includes the type and code, as well as a human readable message which
     * is suitable for display to the user if the UI chooses to do so.
     * @param type the {@link SuppServiceNotification#type}.
     * @param code the {@link SuppServiceNotification#code}.
     */
    private void sendSuppServiceNotificationEvent(int type, int code) {
        Bundle extras = new Bundle();
        extras.putInt(TelephonyManager.EXTRA_NOTIFICATION_TYPE, type);
        extras.putInt(TelephonyManager.EXTRA_NOTIFICATION_CODE, code);
        extras.putCharSequence(TelephonyManager.EXTRA_NOTIFICATION_MESSAGE,
                getSuppServiceMessage(type, code));
        sendTelephonyConnectionEvent(TelephonyManager.EVENT_SUPPLEMENTARY_SERVICE_NOTIFICATION,
                extras);
    }

    /**
     * Retrieves a human-readable message for a supplementary service notification.
     * This message is suitable for display to the user.
     * @param type the code group.
     * @param code the code.
     * @return A {@link CharSequence} containing the message, or {@code null} if none defined.
     */
    private CharSequence getSuppServiceMessage(int type, int code) {
        int messageId = -1;
        if (type == SuppServiceNotification.NOTIFICATION_TYPE_CODE_1) {
            switch (code) {
                case SuppServiceNotification.CODE_1_CALL_DEFLECTED:
                    messageId = R.string.supp_service_notification_call_deflected;
                    break;
                case SuppServiceNotification.CODE_1_CALL_FORWARDED:
                    messageId = R.string.supp_service_notification_call_forwarded;
                    break;
                case SuppServiceNotification.CODE_1_CALL_IS_WAITING:
                    messageId = R.string.supp_service_notification_call_waiting;
                    break;
                case SuppServiceNotification.CODE_1_CLIR_SUPPRESSION_REJECTED:
                    messageId = R.string.supp_service_clir_suppression_rejected;
                    break;
                case SuppServiceNotification.CODE_1_CUG_CALL:
                    messageId = R.string.supp_service_closed_user_group_call;
                    break;
                case SuppServiceNotification.CODE_1_INCOMING_CALLS_BARRED:
                    messageId = R.string.supp_service_incoming_calls_barred;
                    break;
                case SuppServiceNotification.CODE_1_OUTGOING_CALLS_BARRED:
                    messageId = R.string.supp_service_outgoing_calls_barred;
                    break;
                case SuppServiceNotification.CODE_1_SOME_CF_ACTIVE:
                    // Intentional fall through.
                case SuppServiceNotification.CODE_1_UNCONDITIONAL_CF_ACTIVE:
                    messageId = R.string.supp_service_call_forwarding_active;
                    break;
            }
        } else if (type == SuppServiceNotification.NOTIFICATION_TYPE_CODE_2) {
            switch (code) {
                case SuppServiceNotification.CODE_2_ADDITIONAL_CALL_FORWARDED:
                    messageId = R.string.supp_service_additional_call_forwarded;
                    break;
                case SuppServiceNotification.CODE_2_CALL_CONNECTED_ECT:
                    messageId = R.string.supp_service_additional_ect_connected;
                    break;
                case SuppServiceNotification.CODE_2_CALL_CONNECTING_ECT:
                    messageId = R.string.supp_service_additional_ect_connecting;
                    break;
                case SuppServiceNotification.CODE_2_CALL_ON_HOLD:
                    messageId = R.string.supp_service_call_on_hold;
                    break;
                case SuppServiceNotification.CODE_2_CALL_RETRIEVED:
                    messageId = R.string.supp_service_call_resumed;
                    break;
                case SuppServiceNotification.CODE_2_CUG_CALL:
                    messageId = R.string.supp_service_closed_user_group_call;
                    break;
                case SuppServiceNotification.CODE_2_DEFLECTED_CALL:
                    messageId = R.string.supp_service_deflected_call;
                    break;
                case SuppServiceNotification.CODE_2_FORWARDED_CALL:
                    messageId = R.string.supp_service_forwarded_call;
                    break;
                case SuppServiceNotification.CODE_2_MULTI_PARTY_CALL:
                    messageId = R.string.supp_service_conference_call;
                    break;
                case SuppServiceNotification.CODE_2_ON_HOLD_CALL_RELEASED:
                    messageId = R.string.supp_service_held_call_released;
                    break;
            }
        }
        if (messageId != -1 && getPhone() != null && getPhone().getContext() != null) {
            return getResourceText(messageId);
        } else {
            return null;
        }
    }

    @VisibleForTesting
    public CharSequence getResourceText(int id) {
        Resources resources = SubscriptionManager.getResourcesForSubId(getPhone().getContext(),
                getPhone().getSubId());
        return resources.getText(id);
    }

    @VisibleForTesting
    public String getResourceString(int id) {
        Resources resources = SubscriptionManager.getResourcesForSubId(getPhone().getContext(),
                getPhone().getSubId());
        return resources.getString(id);
    }

    /**
     * @return {@code true} if carrier video conferencing is supported, {@code false} otherwise.
     */
    public boolean isCarrierVideoConferencingSupported() {
        return mIsCarrierVideoConferencingSupported;
    }

    /**
     * A listener/callback mechanism that is specific communication from TelephonyConnections
     * to TelephonyConnectionService (for now). It is more specific that Connection.Listener
     * because it is only exposed in Telephony.
     */
    public abstract static class TelephonyConnectionListener {
        public void onOriginalConnectionConfigured(TelephonyConnection c) {}
        public void onOriginalConnectionRetry(TelephonyConnection c, boolean isPermanentFailure) {}
        public void onConferenceParticipantsChanged(Connection c,
                List<ConferenceParticipant> participants) {}
        public void onConferenceStarted() {}
        public void onConferenceSupportedChanged(Connection c, boolean isConferenceSupported) {}

        public void onConnectionCapabilitiesChanged(Connection c, int connectionCapabilities) {}
        public void onConnectionEvent(Connection c, String event, Bundle extras) {}
        public void onConnectionPropertiesChanged(Connection c, int connectionProperties) {}
        public void onExtrasChanged(Connection c, Bundle extras) {}
        public void onExtrasRemoved(Connection c, List<String> keys) {}
        public void onStateChanged(android.telecom.Connection c, int state) {}
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {}
        public void onDestroyed(Connection c) {}
        public void onDisconnected(android.telecom.Connection c,
                android.telecom.DisconnectCause disconnectCause) {}
        public void onVideoProviderChanged(android.telecom.Connection c,
                Connection.VideoProvider videoProvider) {}
        public void onVideoStateChanged(android.telecom.Connection c, int videoState) {}
        public void onRingbackRequested(Connection c, boolean ringback) {}
    }

    public static class D2DCallStateAdapter extends TelephonyConnectionListener {
        private Communicator mCommunicator;

        D2DCallStateAdapter(Communicator communicator) {
            mCommunicator = communicator;
        }

        @Override
        public void onStateChanged(android.telecom.Connection c, int state) {
            mCommunicator.onStateChanged(c.getTelecomCallId(), state);
        }
    }

    private final PostDialListener mPostDialListener = new PostDialListener() {
        @Override
        public void onPostDialWait() {
            Log.v(TelephonyConnection.this, "onPostDialWait");
            if (mOriginalConnection != null) {
                setPostDialWait(mOriginalConnection.getRemainingPostDialString());
            }
        }

        @Override
        public void onPostDialChar(char c) {
            Log.v(TelephonyConnection.this, "onPostDialChar: %s", c);
            if (mOriginalConnection != null) {
                setNextPostDialChar(c);
            }
        }
    };

    /**
     * Listener for listening to events in the {@link com.android.internal.telephony.Connection}.
     */
    private final com.android.internal.telephony.Connection.Listener mOriginalConnectionListener =
            new com.android.internal.telephony.Connection.ListenerBase() {
        @Override
        public void onVideoStateChanged(int videoState) {
            mHandler.obtainMessage(MSG_SET_VIDEO_STATE, videoState).sendToTarget();
        }

        /*
         * The {@link com.android.internal.telephony.Connection} has reported a change in
         * connection capability.
         * @param capabilities bit mask containing voice or video or both capabilities.
         */
        @Override
        public void onConnectionCapabilitiesChanged(int capabilities) {
            mHandler.obtainMessage(MSG_SET_ORIGNAL_CONNECTION_CAPABILITIES,
                    capabilities, 0).sendToTarget();
        }

        /**
         * The {@link com.android.internal.telephony.Connection} has reported a change in the
         * video call provider.
         *
         * @param videoProvider The video call provider.
         */
        @Override
        public void onVideoProviderChanged(VideoProvider videoProvider) {
            mHandler.obtainMessage(MSG_SET_VIDEO_PROVIDER, videoProvider).sendToTarget();
        }

        /**
         * Used by {@link com.android.internal.telephony.Connection} to report a change for
         * the call radio technology.
         *
         * @param vrat the RIL Voice Radio Technology used for current connection.
         */
        @Override
        public void onCallRadioTechChanged(@RilRadioTechnology int vrat) {
            mHandler.obtainMessage(MSG_SET_CALL_RADIO_TECH, vrat).sendToTarget();
        }

        /**
         * Used by the {@link com.android.internal.telephony.Connection} to report a change in the
         * audio quality for the current call.
         *
         * @param audioQuality The audio quality.
         */
        @Override
        public void onAudioQualityChanged(int audioQuality) {
            mHandler.obtainMessage(MSG_SET_AUDIO_QUALITY, audioQuality).sendToTarget();
        }

        @Override
        public void onMediaAttributesChanged() {
            mHandler.obtainMessage(MSG_MEDIA_ATTRIBUTES_CHANGED).sendToTarget();
        }

        /**
         * Handles a change in the state of conference participant(s), as reported by the
         * {@link com.android.internal.telephony.Connection}.
         *
         * @param participants The participant(s) which changed.
         */
        @Override
        public void onConferenceParticipantsChanged(List<ConferenceParticipant> participants) {
            mHandler.obtainMessage(MSG_SET_CONFERENCE_PARTICIPANTS, participants).sendToTarget();
        }

        /*
         * Handles a change to the multiparty state for this connection.
         *
         * @param isMultiParty {@code true} if the call became multiparty, {@code false}
         *      otherwise.
         */
        @Override
        public void onMultipartyStateChanged(boolean isMultiParty) {
            handleMultipartyStateChange(isMultiParty);
        }

        /**
         * Handles the event that the request to merge calls failed.
         */
        @Override
        public void onConferenceMergedFailed() {
            handleConferenceMergeFailed();
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            mHandler.obtainMessage(MSG_CONNECTION_EXTRAS_CHANGED, extras).sendToTarget();
        }

        /**
         * Handles the phone exiting ECM mode by updating the connection capabilities.  During an
         * ongoing call, if ECM mode is exited, we will re-enable mute for CDMA calls.
         */
        @Override
        public void onExitedEcmMode() {
            handleExitedEcmMode();
        }

        /**
         * Called from {@link ImsPhoneCallTracker} when a request to pull an external call has
         * failed.
         * @param externalConnection
         */
        @Override
        public void onCallPullFailed(com.android.internal.telephony.Connection externalConnection) {
            if (externalConnection == null) {
                return;
            }

            Log.i(this, "onCallPullFailed - pull failed; swapping back to call: %s",
                    externalConnection);

            // Inform the InCallService of the fact that the call pull failed (it may choose to
            // display a message informing the user of the pull failure).
            sendTelephonyConnectionEvent(Connection.EVENT_CALL_PULL_FAILED, null);

            // Swap the ImsPhoneConnection we used to do the pull for the ImsExternalConnection
            // which originally represented the call.
            setOriginalConnection(externalConnection);

            // Set our state to active again since we're no longer pulling.
            setActiveInternal();
        }

        /**
         * Called from {@link ImsPhoneCallTracker} when a handover to WIFI has failed.
         */
        @Override
        public void onHandoverToWifiFailed() {
            sendTelephonyConnectionEvent(TelephonyManager.EVENT_HANDOVER_TO_WIFI_FAILED, null);
        }

        /**
         * Informs the {@link android.telecom.ConnectionService} of a connection event raised by the
         * original connection.
         * @param event The connection event.
         * @param extras The extras.
         */
        @Override
        public void onConnectionEvent(String event, Bundle extras) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = event;
            args.arg2 = extras;
            if (EVENT_MERGE_COMPLETE.equals(event)){
                // To ensure the MERGE_COMPLETE event logs before the listeners are removed,
                // circumvent the handler by sending the connection event directly:
                sendTelephonyConnectionEvent(event, extras);
            } else {
                mHandler.obtainMessage(MSG_ON_CONNECTION_EVENT, args).sendToTarget();
            }
        }

        @Override
        public void onRttModifyRequestReceived() {
            sendRemoteRttRequest();
        }

        @Override
        public void onRttModifyResponseReceived(int status) {
            updateConnectionProperties();
            refreshConferenceSupported();
            if (status == RttModifyStatus.SESSION_MODIFY_REQUEST_SUCCESS) {
                sendRttInitiationSuccess();
            } else {
                sendRttInitiationFailure(status);
            }
        }

        @Override
        public void onDisconnect(int cause) {
            Log.i(this, "onDisconnect: callId=%s, cause=%s", getTelecomCallId(),
                    DisconnectCause.toString(cause));
            mHandler.obtainMessage(MSG_DISCONNECT).sendToTarget();
        }

        @Override
        public void onRttInitiated() {
            Log.i(TelephonyConnection.this, "onRttInitiated: callId=%s", getTelecomCallId());
            // Post RTT initiation to the Handler associated with this TelephonyConnection.
            // This avoids a race condition where a call starts as RTT but ConnectionService call to
            // handleCreateConnectionComplete happens AFTER the RTT status is reported to Telecom.
            mHandler.obtainMessage(MSG_ON_RTT_INITIATED).sendToTarget();
        }

        @Override
        public void onRttTerminated() {
            updateConnectionProperties();
            refreshConferenceSupported();
            sendRttSessionRemotelyTerminated();
        }

        @Override
        public void onOriginalConnectionReplaced(
                com.android.internal.telephony.Connection newConnection) {
            Log.i(TelephonyConnection.this, "onOriginalConnectionReplaced; newConn=%s",
                    newConnection);
            setOriginalConnection(newConnection);
        }

        @Override
        public void onIsNetworkEmergencyCallChanged(boolean isEmergencyCall) {
            setIsNetworkIdentifiedEmergencyCall(isEmergencyCall);
        }

        /**
         * Indicates data from an RTP header extension has been received from the network.
         * @param extensionData The extension data.
         */
        @Override
        public void onReceivedRtpHeaderExtensions(@NonNull Set<RtpHeaderExtension> extensionData) {
            if (mRtpTransport == null) {
                return;
            }
            Log.i(this, "onReceivedRtpHeaderExtensions: received %d extensions",
                    extensionData.size());
            mRtpTransport.onRtpHeaderExtensionsReceived(extensionData);
        }

        @Override
        public void onReceivedDtmfDigit(char digit) {
            if (mDtmfTransport == null) {
                return;
            }
            Log.i(this, "onReceivedDtmfDigit: digit=%c", digit);
            mDtmfTransport.onDtmfReceived(digit);
        }

        @Override
        public void onAudioModeIsVoipChanged(int imsAudioHandler) {
            boolean isVoip = imsAudioHandler == MmTelFeature.AUDIO_HANDLER_ANDROID;
            Log.i(this, "onAudioModeIsVoipChanged isVoip =" + isVoip);
            setAudioModeIsVoip(isVoip);
        }
    };

    private TelephonyConnectionService mTelephonyConnectionService;
    protected com.android.internal.telephony.Connection mOriginalConnection;
    private Phone mPhoneForEvents;
    private Call.State mConnectionState = Call.State.IDLE;
    private Bundle mOriginalConnectionExtras = new Bundle();
    private boolean mIsStateOverridden = false;
    private Call.State mOriginalConnectionState = Call.State.IDLE;
    private Call.State mConnectionOverriddenState = Call.State.IDLE;
    private RttTextStream mRttTextStream = null;

    private boolean mWasImsConnection;
    private boolean mWasCrossSim;

    /**
     * Tracks the multiparty state of the ImsCall so that changes in the bit state can be detected.
     */
    private boolean mIsMultiParty = false;

    /**
     * The {@link com.android.internal.telephony.Connection} capabilities associated with the
     * current {@link #mOriginalConnection}.
     */
    private int mOriginalConnectionCapabilities;

    /**
     * Determines the audio quality is high for the {@link TelephonyConnection}.
     * This is used when {@link TelephonyConnection#updateConnectionProperties}} is called to
     * indicate whether a call has the {@link Connection#PROPERTY_HIGH_DEF_AUDIO} property.
     */
    private boolean mHasHighDefAudio;

    /**
     * Indicates that the connection should be treated as an emergency call because the
     * number dialed matches an internal list of emergency numbers. Does not guarantee whether
     * the network will treat the call as an emergency call.
     */
    private boolean mTreatAsEmergencyCall;

    /**
     * Indicates whether the network has identified this call as an emergency call.  Where
     * {@link #mTreatAsEmergencyCall} is based on comparing dialed numbers to a list of known
     * emergency numbers, this property is based on whether the network itself has identified the
     * call as an emergency call (which can be the case for an incoming call from emergency
     * services).
     */
    private boolean mIsNetworkIdentifiedEmergencyCall;

    /**
     * For video calls, indicates whether the outgoing video for the call can be paused using
     * the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     */
    private boolean mIsVideoPauseSupported;

    /**
     * Indicates whether this connection supports being a part of a conference..
     */
    private boolean mIsConferenceSupported;

    /**
     * Indicates whether managing conference call is supported after this connection being
     * a part of a IMS conference.
     */
    private boolean mIsManageImsConferenceCallSupported;

    /**
     * Indicates whether the carrier supports video conferencing; captures the current state of the
     * carrier config
     * {@link android.telephony.CarrierConfigManager#KEY_SUPPORT_VIDEO_CONFERENCE_CALL_BOOL}.
     */
    private boolean mIsCarrierVideoConferencingSupported;

    /**
     * Indicates whether or not this connection has CDMA Enhanced Voice Privacy enabled.
     */
    private boolean mIsCdmaVoicePrivacyEnabled;

    /**
     * Indicates whether the connection can be held. This filed combined with the state of the
     * connection can determine whether {@link Connection#CAPABILITY_HOLD} should be added to the
     * connection.
     */
    private boolean mIsHoldable;

    /**
     * Indicates whether TTY is enabled; used to determine whether a call is VT capable.
     */
    private boolean mIsTtyEnabled;

    /**
     * Indicates whether this call is using assisted dialing.
     */
    private boolean mIsUsingAssistedDialing;

    /**
     * Indicates whether this connection supports showing preciese call failed cause.
     */
    private boolean mShowPreciseFailedCause;

    /**
     * Provides a DisconnectCause associated with a hang up request.
     */
    private int mHangupDisconnectCause = DisconnectCause.NOT_VALID;

    /**
     * Provides a means for a {@link Communicator} to be informed of call state changes.
     */
    private D2DCallStateAdapter mD2DCallStateAdapter;

    private RtpTransport mRtpTransport;

    private DtmfTransport mDtmfTransport;

    /**
     * Facilitates device to device communication.
     */
    private Communicator mCommunicator;

    /**
     * Listeners to our TelephonyConnection specific callbacks
     */
    private final Set<TelephonyConnectionListener> mTelephonyListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<TelephonyConnectionListener, Boolean>(8, 0.9f, 1));

    private Integer mEmergencyServiceCategory = null;
    private List<String> mEmergencyUrns = null;

    protected TelephonyConnection(com.android.internal.telephony.Connection originalConnection,
            String callId, int callDirection) {
        setCallDirection(callDirection);
        setTelecomCallId(callId);
        if (originalConnection != null) {
            setOriginalConnection(originalConnection);
        }
    }

    @VisibleForTesting
    protected TelephonyConnection() {
        // Do nothing
    }

    @Override
    public void onCallEvent(String event, Bundle extras) {
        switch (event) {
            case Connection.EVENT_DEVICE_TO_DEVICE_MESSAGE:
                // A Device to device message is being sent by a CallDiagnosticService.
                handleOutgoingDeviceToDeviceMessage(extras);
                break;
            default:
                break;
        }

    }
    /**
     * Creates a clone of the current {@link TelephonyConnection}.
     *
     * @return The clone.
     */
    public abstract TelephonyConnection cloneConnection();

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        // TODO: update TTY mode.
        if (getPhone() != null) {
            getPhone().setEchoSuppressionEnabled();
        }
    }

    @Override
    public void onStateChanged(int state) {
        Log.v(this, "onStateChanged, state: " + Connection.stateToString(state));
        updateStatusHints();
    }

    @Override
    public void onDisconnect() {
        Log.v(this, "onDisconnect");
        mHandler.obtainMessage(MSG_HANGUP, android.telephony.DisconnectCause.LOCAL).sendToTarget();
    }

    @Override
    public void onSeparate() {
        Log.v(this, "onSeparate");
        if (mOriginalConnection != null) {
            try {
                mOriginalConnection.separate();
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.separate failed with exception");
            }
        }
    }

    @Override
    public void onAddConferenceParticipants(List<Uri> participants) {
        performAddConferenceParticipants(participants);
    }

    @Override
    public void onAbort() {
        Log.v(this, "onAbort");
        mHandler.obtainMessage(MSG_HANGUP, android.telephony.DisconnectCause.LOCAL).sendToTarget();
    }

    @Override
    public void onHold() {
        mHandler.obtainMessage(MSG_HOLD).sendToTarget();
    }

    @Override
    public void onUnhold() {
        mHandler.obtainMessage(MSG_UNHOLD).sendToTarget();
    }

    @Override
    public void onAnswer(int videoState) {
        performAnswer(videoState);
    }

    @Override
    public void onDeflect(Uri address) {
        Log.v(this, "onDeflect");
        if (mOriginalConnection != null && isValidRingingCall()) {
            if (address == null) {
                Log.w(this, "call deflect address uri is null");
                return;
            }
            String scheme = address.getScheme();
            String deflectNumber = "";
            String uriString = address.getSchemeSpecificPart();
            if (!PhoneAccount.SCHEME_VOICEMAIL.equals(scheme)) {
                if (!PhoneAccount.SCHEME_TEL.equals(scheme)) {
                    Log.w(this, "onDeflect, address scheme is not of type tel instead: " +
                            scheme);
                    return;
                }
                if (PhoneNumberUtils.isUriNumber(uriString)) {
                    Log.w(this, "Invalid deflect address. Not a legal PSTN number.");
                    return;
                }
                deflectNumber = PhoneNumberUtils.convertAndStrip(uriString);
                if (TextUtils.isEmpty(deflectNumber)) {
                    Log.w(this, "Empty deflect number obtained from address uri");
                    return;
                }
            } else {
                Log.w(this, "Cannot deflect to voicemail uri");
                return;
            }

            try {
                mOriginalConnection.deflect(deflectNumber);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to deflect call.");
            }
        }
    }

    @Override
    public void onReject() {
        performReject(android.telecom.Call.REJECT_REASON_DECLINED);
    }

    @Override
    public void onReject(@android.telecom.Call.RejectReason int rejectReason) {
        performReject(rejectReason);
    }

    public void performReject(int rejectReason) {
        Log.v(this, "performReject");
        if (isValidRingingCall()) {
            mHandler.obtainMessage(MSG_REJECT, rejectReason)
                    .sendToTarget();
        }
        super.onReject();
    }

    @Override
    public void onTransfer(Uri number, boolean isConfirmationRequired) {
        Log.v(this, "onTransfer");
        if (mOriginalConnection != null) {
            if (number == null) {
                Log.w(this, "call transfer uri is null");
                return;
            }
            String scheme = number.getScheme();
            String transferNumber = "";
            String uriString = number.getSchemeSpecificPart();
            if (!PhoneAccount.SCHEME_VOICEMAIL.equals(scheme)) {
                if (!PhoneAccount.SCHEME_TEL.equals(scheme)) {
                    Log.w(this, "onTransfer, number scheme is not of type tel instead: "
                            + scheme);
                    return;
                }
                if (PhoneNumberUtils.isUriNumber(uriString)) {
                    Log.w(this, "Invalid transfer address. Not a legal PSTN number.");
                    return;
                }
                transferNumber = PhoneNumberUtils.convertAndStrip(uriString);
                if (TextUtils.isEmpty(transferNumber)) {
                    Log.w(this, "Empty transfer number obtained from uri");
                    return;
                }
            } else {
                Log.w(this, "Cannot transfer to voicemail uri");
                return;
            }

            try {
                mOriginalConnection.transfer(transferNumber, isConfirmationRequired);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to transfer call.");
            }
        }
    }

    @Override
    public void onTransfer(Connection otherConnection) {
        Log.v(this, "onConsultativeTransfer");
        if (mOriginalConnection != null && (otherConnection instanceof TelephonyConnection)) {
            try {
                mOriginalConnection.consultativeTransfer(
                        ((TelephonyConnection) otherConnection).getOriginalConnection());
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to transfer call.");
            }
        }
    }

    @Override
    public void onPostDialContinue(boolean proceed) {
        Log.v(this, "onPostDialContinue, proceed: " + proceed);
        if (mOriginalConnection != null) {
            if (proceed) {
                mOriginalConnection.proceedAfterWaitChar();
            } else {
                mOriginalConnection.cancelPostDial();
            }
        }
    }

    /**
     * Handles requests to pull an external call.
     */
    @Override
    public void onPullExternalCall() {
        if ((getConnectionProperties() & Connection.PROPERTY_IS_EXTERNAL_CALL) !=
                Connection.PROPERTY_IS_EXTERNAL_CALL) {
            Log.w(this, "onPullExternalCall - cannot pull non-external call");
            return;
        }

        if (mOriginalConnection != null) {
            mOriginalConnection.pullExternalCall();
        }
    }

    @Override
    public void onStartRtt(RttTextStream textStream) {
        if (isImsConnection()) {
            ImsPhoneConnection originalConnection = (ImsPhoneConnection) mOriginalConnection;
            if (originalConnection.isRttEnabledForCall()) {
                originalConnection.setCurrentRttTextStream(textStream);
            } else {
                originalConnection.startRtt(textStream);
            }
        } else {
            Log.w(this, "onStartRtt - not in IMS, so RTT cannot be enabled.");
        }
    }

    @Override
    public void onStopRtt() {
        if (isImsConnection()) {
            ImsPhoneConnection originalConnection = (ImsPhoneConnection) mOriginalConnection;
            if (originalConnection.isRttEnabledForCall()) {
                originalConnection.stopRtt();
            } else {
                Log.w(this, "onStopRtt - not in RTT call, ignoring");
            }
        } else {
            Log.w(this, "onStopRtt - not in IMS, ignoring");
        }
    }

    @Override
    public void onCallFilteringCompleted(CallFilteringCompletionInfo callFilteringCompletionInfo) {
        // Check what the call screening service has to say, if it's a system dialer.
        boolean isAllowedToDisplayPicture;
        String callScreeningPackage =
                callFilteringCompletionInfo.getCallScreeningComponent() == null
                        ? null
                        : callFilteringCompletionInfo.getCallScreeningComponent().getPackageName();
        boolean isResponseFromSystemDialer =
                Objects.equals(getPhone().getContext()
                        .getSystemService(TelecomManager.class).getSystemDialerPackage(),
                        callScreeningPackage);
        CallScreeningService.CallResponse callScreeningResponse =
                callFilteringCompletionInfo.getCallResponse();

        if (isResponseFromSystemDialer && callScreeningResponse != null
                && callScreeningResponse.getCallComposerAttachmentsToShow() >= 0) {
            isAllowedToDisplayPicture = (callScreeningResponse.getCallComposerAttachmentsToShow()
                    & CallScreeningService.CallResponse.CALL_COMPOSER_ATTACHMENT_PICTURE) != 0;
        } else {
            isAllowedToDisplayPicture = callFilteringCompletionInfo.isInContacts();
        }

        if (isImsConnection()) {
            ImsPhone imsPhone = (getPhone() instanceof ImsPhone) ? (ImsPhone) getPhone() : null;
            if (imsPhone != null
                    && imsPhone.getCallComposerStatus() == TelephonyManager.CALL_COMPOSER_STATUS_ON
                    && !callFilteringCompletionInfo.isBlocked() && isAllowedToDisplayPicture) {
                ImsPhoneConnection originalConnection = (ImsPhoneConnection) mOriginalConnection;
                ImsCallProfile profile = originalConnection.getImsCall().getCallProfile();
                String serverUrl = CallComposerPictureManager.sTestMode
                        ? CallComposerPictureManager.FAKE_SERVER_URL
                        : profile.getCallExtra(ImsCallProfile.EXTRA_PICTURE_URL);
                if (profile != null
                        && !TextUtils.isEmpty(serverUrl)) {
                    CallComposerPictureManager manager = CallComposerPictureManager
                            .getInstance(getPhone().getContext(), getPhone().getSubId());
                    manager.handleDownloadFromServer(new CallComposerPictureTransfer.Factory() {},
                            serverUrl,
                            (result) -> {
                                if (result.first != null) {
                                    Bundle newExtras = new Bundle();
                                    newExtras.putParcelable(TelecomManager.EXTRA_PICTURE_URI,
                                            result.first);
                                    putTelephonyExtras(newExtras);
                                } else {
                                    Log.i(this, "Call composer picture download:"
                                            + " error=" + result.second);
                                    Bundle newExtras = new Bundle();
                                    newExtras.putBoolean(TelecomManager.EXTRA_HAS_PICTURE, false);
                                    putTelephonyExtras(newExtras);
                                }
                            });
                }
            }
        }
    }

    @Override
    public void handleRttUpgradeResponse(RttTextStream textStream) {
        if (!isImsConnection()) {
            Log.w(this, "handleRttUpgradeResponse - not in IMS, so RTT cannot be enabled.");
            return;
        }
        ImsPhoneConnection originalConnection = (ImsPhoneConnection) mOriginalConnection;
        originalConnection.sendRttModifyResponse(textStream);
    }

    private boolean answeringDropsFgCalls() {
        if (Flags.callExtraForNonHoldSupportedCarriers()) {
            Bundle extras = getExtras();
            if (extras != null) {
                return extras.getBoolean(Connection.EXTRA_ANSWERING_DROPS_FG_CALL);
            }
        }
        return false;
    }

    public void performAnswer(int videoState) {
        Log.v(this, "performAnswer");
        if (isValidRingingCall() && getPhone() != null) {
            try {
                mTelephonyConnectionService.maybeDisconnectCallsOnOtherSubs(
                        getPhoneAccountHandle(), answeringDropsFgCalls());
                getPhone().acceptCall(videoState);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to accept call.");
            }
        }
    }

    public void performHold() {
        Log.v(this, "performHold");
        // TODO: Can dialing calls be put on hold as well since they take up the
        // foreground call slot?
        if (Call.State.ACTIVE == mConnectionState) {
            Log.v(this, "Holding active call");
            try {
                Phone phone = mOriginalConnection.getCall().getPhone();

                Call ringingCall = phone.getRingingCall();

                // Although the method says switchHoldingAndActive, it eventually calls a RIL method
                // called switchWaitingOrHoldingAndActive. What this means is that if we try to put
                // a call on hold while a call-waiting call exists, it'll end up accepting the
                // call-waiting call, which is bad if that was not the user's intention. We are
                // cheating here and simply skipping it because we know any attempt to hold a call
                // while a call-waiting call is happening is likely a request from Telecom prior to
                // accepting the call-waiting call.
                // TODO: Investigate a better solution. It would be great here if we
                // could "fake" hold by silencing the audio and microphone streams for this call
                // instead of actually putting it on hold.
                if (ringingCall.getState() != Call.State.WAITING) {
                    // New behavior for IMS -- don't use the clunky switchHoldingAndActive logic.
                    if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
                        ImsPhone imsPhone = (ImsPhone) phone;
                        imsPhone.holdActiveCall();
                        if (!com.android.server.telecom.flags.Flags.enableCallSequencing()) {
                            mTelephonyConnectionService.maybeUnholdCallsOnOtherSubs(
                                    getPhoneAccountHandle());
                        }
                        return;
                    }
                    phone.switchHoldingAndActive();
                }

                // TODO: Cdma calls are slightly different.
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to put call on hold.");
            }
        } else {
            Log.w(this, "Cannot put a call that is not currently active on hold.");
        }
    }

    public void performUnhold() {
        Log.v(this, "performUnhold");
        if (Call.State.HOLDING == mConnectionState) {
            try {
                Phone phone = mOriginalConnection.getCall().getPhone();
                // New behavior for IMS -- don't use the clunky switchHoldingAndActive logic.
                if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
                    ImsPhone imsPhone = (ImsPhone) phone;
                    imsPhone.unholdHeldCall();
                    return;
                }
                // Here's the deal--Telephony hold/unhold is weird because whenever there exists
                // more than one call, one of them must always be active. In other words, if you
                // have an active call and holding call, and you put the active call on hold, it
                // will automatically activate the holding call. This is weird with how Telecom
                // sends its commands. When a user opts to "unhold" a background call, telecom
                // issues hold commands to all active calls, and then the unhold command to the
                // background call. This means that we get two commands...each of which reduces to
                // switchHoldingAndActive(). The result is that they simply cancel each other out.
                // To fix this so that it works well with telecom we add a minor hack. If we
                // have one telephony call, everything works as normally expected. But if we have
                // two or more calls, we will ignore all requests to "unhold" knowing that the hold
                // requests already do what we want. If you've read up to this point, I'm very sorry
                // that we are doing this. I didn't think of a better solution that wouldn't also
                // make the Telecom APIs very ugly.

                if (!hasMultipleTopLevelCalls()) {
                    mOriginalConnection.getCall().getPhone().switchHoldingAndActive();
                } else {
                    Log.i(this, "Skipping unhold command for %s", this);
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to release call from hold.");
            }
        } else {
            Log.w(this, "Cannot release a call that is not already on hold from hold.");
        }
    }

    public void performConference(Connection otherConnection) {
        Log.d(this, "performConference - %s", this);
        if (getPhone() != null) {
            try {
                // We dont use the "other" connection because there is no concept of that in the
                // implementation of calls inside telephony. Basically, you can "conference" and it
                // will conference with the background call.  We know that otherConnection is the
                // background call because it would never have called setConferenceableConnections()
                // otherwise.
                getPhone().conference();
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to conference call.");
            }
        }
    }

    private String[] getAddConferenceParticipants(List<Uri> participants) {
        String[] addConfParticipants = new String[participants.size()];
        int i = 0;
        for (Uri participant : participants) {
           addConfParticipants[i] = participant.getSchemeSpecificPart();
           i++;
        }
        return addConfParticipants;
    }

    public void performAddConferenceParticipants(List<Uri> participants) {
        Log.v(this, "performAddConferenceParticipants");
        if (mOriginalConnection.getCall() instanceof ImsPhoneCall) {
            ImsPhoneCall imsPhoneCall = (ImsPhoneCall)mOriginalConnection.getCall();
            try {
                imsPhoneCall.getImsCall().inviteParticipants(
                        getAddConferenceParticipants(participants));
            } catch(ImsException e) {
                Log.e(this, e, "failed to add conference participants");
            }
        }
    }

    /**
     * Builds connection capabilities common to all TelephonyConnections. Namely, apply IMS-based
     * capabilities.
     */
    protected int buildConnectionCapabilities() {
        int callCapabilities = 0;
        if (mOriginalConnection != null && mOriginalConnection.isIncoming()) {
            callCapabilities |= CAPABILITY_SPEED_UP_MT_AUDIO;
        }
        if (!shouldTreatAsEmergencyCall() && isImsConnection() && canHoldImsCalls()) {
            callCapabilities |= CAPABILITY_SUPPORT_HOLD;
            if (mIsHoldable && (getState() == STATE_ACTIVE || getState() == STATE_HOLDING)) {
                callCapabilities |= CAPABILITY_HOLD;
            }
        }

        Log.d(this, "buildConnectionCapabilities: isHoldable = "
                + mIsHoldable + " State = " + getState() + " capabilities = " + callCapabilities);

        return callCapabilities;
    }

    protected final void updateConnectionCapabilities() {
        int newCapabilities = buildConnectionCapabilities();

        newCapabilities = applyOriginalConnectionCapabilities(newCapabilities);
        newCapabilities = changeBitmask(newCapabilities, CAPABILITY_CAN_PAUSE_VIDEO,
                mIsVideoPauseSupported && isVideoCapable());
        newCapabilities = changeBitmask(newCapabilities, CAPABILITY_CAN_PULL_CALL,
                isExternalConnection() && isPullable());
        newCapabilities = applyConferenceTerminationCapabilities(newCapabilities);
        newCapabilities = changeBitmask(newCapabilities, CAPABILITY_SUPPORT_DEFLECT,
                isImsConnection() && canDeflectImsCalls());

        newCapabilities = applyAddParticipantCapabilities(newCapabilities);
        newCapabilities = changeBitmask(newCapabilities, CAPABILITY_TRANSFER_CONSULTATIVE,
                isImsConnection() && canConsultativeTransfer());
        newCapabilities = changeBitmask(newCapabilities, CAPABILITY_TRANSFER,
                isImsConnection() && canTransferToNumber());

        if (getConnectionCapabilities() != newCapabilities) {
            setConnectionCapabilities(newCapabilities);
            notifyConnectionCapabilitiesChanged(newCapabilities);
        }
    }

    protected int buildConnectionProperties() {
        int connectionProperties = 0;

        // If the phone is in ECM mode, mark the call to indicate that the callback number should be
        // shown.
        Phone phone = getPhone();
        if (phone != null && phone.isInEcm()) {
            connectionProperties |= PROPERTY_EMERGENCY_CALLBACK_MODE;
        }

        return connectionProperties;
    }

    /**
     * Updates the properties of the connection.
     */
    protected final void updateConnectionProperties() {
        int newProperties = buildConnectionProperties();

        newProperties = changeBitmask(newProperties, PROPERTY_HIGH_DEF_AUDIO,
                hasHighDefAudioProperty());
        newProperties = changeBitmask(newProperties, PROPERTY_WIFI, isWifi() && !isCrossSimCall());
        newProperties = changeBitmask(newProperties, PROPERTY_IS_EXTERNAL_CALL,
                isExternalConnection());
        newProperties = changeBitmask(newProperties, PROPERTY_HAS_CDMA_VOICE_PRIVACY,
                mIsCdmaVoicePrivacyEnabled);
        newProperties = changeBitmask(newProperties, PROPERTY_ASSISTED_DIALING,
                mIsUsingAssistedDialing);
        newProperties = changeBitmask(newProperties, PROPERTY_IS_RTT, isRtt());
        newProperties = changeBitmask(newProperties, PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL,
                isNetworkIdentifiedEmergencyCall());
        newProperties = changeBitmask(newProperties, PROPERTY_IS_ADHOC_CONFERENCE,
                isAdhocConferenceCall());
        newProperties = changeBitmask(newProperties, PROPERTY_CROSS_SIM,
                isCrossSimCall());

        if (getConnectionProperties() != newProperties) {
            setTelephonyConnectionProperties(newProperties);
        }
    }

    public void setTelephonyConnectionProperties(int newProperties) {
        setConnectionProperties(newProperties);
        notifyConnectionPropertiesChanged(newProperties);
    }

    protected final void updateAddress() {
        updateConnectionCapabilities();
        updateConnectionProperties();
        if (mOriginalConnection != null) {
            Uri address;
            if (isShowingOriginalDialString()
                    && mOriginalConnection.getOrigDialString() != null) {
                address = getAddressFromNumber(mOriginalConnection.getOrigDialString());
            } else if (isNeededToFormatIncomingNumberForJp()) {
                address = getAddressFromNumber(
                        formatIncomingNumberForJp(mOriginalConnection.getAddress()));
            } else {
                address = getAddressFromNumber(mOriginalConnection.getAddress());
            }
            int presentation = mOriginalConnection.getNumberPresentation();
            if (!Objects.equals(address, getAddress()) ||
                    presentation != getAddressPresentation()) {
                Log.v(this, "updateAddress, address changed");
                if ((getConnectionProperties() & PROPERTY_IS_DOWNGRADED_CONFERENCE) != 0) {
                    address = null;
                }
                setAddress(address, presentation);
            }

            String name = filterCnapName(mOriginalConnection.getCnapName());
            int namePresentation = mOriginalConnection.getCnapNamePresentation();
            if (!Objects.equals(name, getCallerDisplayName()) ||
                    namePresentation != getCallerDisplayNamePresentation()) {
                Log.v(this, "updateAddress, caller display name changed");
                setCallerDisplayName(name, namePresentation);
            }

            TelephonyManager tm = (TelephonyManager) getPhone().getContext()
                    .getSystemService(Context.TELEPHONY_SERVICE);
            if (tm.isEmergencyNumber(mOriginalConnection.getAddress())) {
                mTreatAsEmergencyCall = true;
            }

            // Changing the address of the connection can change whether it is an emergency call or
            // not, which can impact whether it can be part of a conference.
            refreshConferenceSupported();
        }
    }

    void onRemovedFromCallService() {
        // Subclass can override this to do cleanup.
    }

    public void registerForCallEvents(Phone phone) {
        if (mPhoneForEvents == phone) {
            Log.i(this, "registerForCallEvents - same phone requested for"
                    + "registration, ignoring.");
            return;
        }
        Log.i(this, "registerForCallEvents; phone=%s", phone);
        // Only one Phone should be registered for events at a time.
        unregisterForCallEvents();
        phone.registerForPreciseCallStateChanged(mHandler, MSG_PRECISE_CALL_STATE_CHANGED, null);
        phone.registerForHandoverStateChanged(mHandler, MSG_HANDOVER_STATE_CHANGED, null);
        phone.registerForRedialConnectionChanged(mHandler, MSG_REDIAL_CONNECTION_CHANGED, null);
        phone.registerForRingbackTone(mHandler, MSG_RINGBACK_TONE, null);
        phone.registerForSuppServiceNotification(mHandler, MSG_SUPP_SERVICE_NOTIFY, null);
        phone.registerForOnHoldTone(mHandler, MSG_ON_HOLD_TONE, null);
        phone.registerForInCallVoicePrivacyOn(mHandler, MSG_CDMA_VOICE_PRIVACY_ON, null);
        phone.registerForInCallVoicePrivacyOff(mHandler, MSG_CDMA_VOICE_PRIVACY_OFF, null);
        mPhoneForEvents = phone;
    }

    void setOriginalConnection(com.android.internal.telephony.Connection originalConnection) {
        Log.i(this, "setOriginalConnection: TelephonyConnection, originalConnection: "
                + originalConnection);
        if (mOriginalConnection != null && originalConnection != null
               && !originalConnection.isIncoming()
               && originalConnection.getOrigDialString() == null
               && isShowingOriginalDialString()) {
            Log.i(this, "new original dial string is null, convert to: "
                   +  mOriginalConnection.getOrigDialString());
            originalConnection.restoreDialedNumberAfterConversion(
                    mOriginalConnection.getOrigDialString());
        }

        clearOriginalConnection();
        mOriginalConnectionExtras.clear();
        mOriginalConnection = originalConnection;
        mOriginalConnection.setTelecomCallId(getTelecomCallId());
        registerForCallEvents(getPhone());

        mOriginalConnection.addPostDialListener(mPostDialListener);
        mOriginalConnection.addListener(mOriginalConnectionListener);

        // Set video state and capabilities
        setTelephonyVideoState(mOriginalConnection.getVideoState());
        setOriginalConnectionCapabilities(mOriginalConnection.getConnectionCapabilities());
        setIsNetworkIdentifiedEmergencyCall(mOriginalConnection.isNetworkIdentifiedEmergencyCall());
        setIsAdhocConferenceCall(mOriginalConnection.isAdhocConference());
        setAudioModeIsVoip(mOriginalConnection.getAudioModeIsVoip());
        setTelephonyVideoProvider(mOriginalConnection.getVideoProvider());
        setAudioQuality(mOriginalConnection.getAudioQuality());
        setTechnologyTypeExtra();

        setCallRadioTech(mOriginalConnection.getCallRadioTech());

        // Post update of extras to the handler; extras are updated via the handler to ensure thread
        // safety. The Extras Bundle is cloned in case the original extras are modified while they
        // are being added to mOriginalConnectionExtras in updateExtras.
        Bundle connExtras = mOriginalConnection.getConnectionExtras();
            mHandler.obtainMessage(MSG_CONNECTION_EXTRAS_CHANGED, connExtras == null ? null :
                    new Bundle(connExtras)).sendToTarget();

        TelephonyManager tm = (TelephonyManager) getPhone().getContext()
                .getSystemService(Context.TELEPHONY_SERVICE);
        if (tm.isEmergencyNumber(mOriginalConnection.getAddress())) {
            mTreatAsEmergencyCall = true;
        }
        // Propagate VERSTAT for IMS calls.
        setCallerNumberVerificationStatus(mOriginalConnection.getNumberVerificationStatus());

        if (isImsConnection()) {
            mWasImsConnection = true;
        }
        if (originalConnection instanceof ImsPhoneConnection) {
            maybeConfigureDeviceToDeviceCommunication();
        }
        mIsMultiParty = mOriginalConnection.isMultiparty();

        Bundle extrasToPut = new Bundle();
        // Also stash the number verification status in a hidden extra key in the connection.
        // We do this because a RemoteConnection DOES NOT include a getNumberVerificationStatus
        // method and we need to be able to pass the number verification status up to Telecom
        // despite the missing pathway in the RemoteConnectionService API surface.
        extrasToPut.putInt(Connection.EXTRA_CALLER_NUMBER_VERIFICATION_STATUS,
                mOriginalConnection.getNumberVerificationStatus());
        List<String> extrasToRemove = new ArrayList<>();
        if (mOriginalConnection.isActiveCallDisconnectedOnAnswer()) {
            extrasToPut.putBoolean(Connection.EXTRA_ANSWERING_DROPS_FG_CALL, true);
        } else {
            extrasToRemove.add(Connection.EXTRA_ANSWERING_DROPS_FG_CALL);
        }

        if (shouldSetDisableAddCallExtra()) {
            extrasToPut.putBoolean(Connection.EXTRA_DISABLE_ADD_CALL, true);
        } else {
            extrasToRemove.add(Connection.EXTRA_DISABLE_ADD_CALL);
        }

        if (mOriginalConnection != null) {
            ArrayList<String> forwardedNumber = mOriginalConnection.getForwardedNumber();
            if (forwardedNumber != null) {
                extrasToPut.putStringArrayList(Connection.EXTRA_LAST_FORWARDED_NUMBER,
                        forwardedNumber);
            }
        }

        putTelephonyExtras(extrasToPut);
        removeTelephonyExtras(extrasToRemove);

        // updateState can set mOriginalConnection to null if its state is DISCONNECTED, so this
        // should be executed *after* the above setters have run.
        updateState();
        if (mOriginalConnection == null) {
            Log.w(this, "original Connection was nulled out as part of setOriginalConnection. " +
                    originalConnection);
        }

        fireOnOriginalConnectionConfigured();
    }

    /**
     * Filters the CNAP name to not include a list of names that are unhelpful to the user for
     * Caller ID purposes.
     */
    private String filterCnapName(final String cnapName) {
        if (cnapName == null) {
            return null;
        }
        PersistableBundle carrierConfig = getCarrierConfig();
        String[] filteredCnapNames = null;
        if (carrierConfig != null) {
            filteredCnapNames = carrierConfig.getStringArray(
                    CarrierConfigManager.KEY_FILTERED_CNAP_NAMES_STRING_ARRAY);
        }
        if (filteredCnapNames != null) {
            long cnapNameMatches = Arrays.asList(filteredCnapNames)
                    .stream()
                    .filter(filteredCnapName -> filteredCnapName.equals(
                            cnapName.toUpperCase(Locale.ROOT)))
                    .count();
            if (cnapNameMatches > 0) {
                Log.i(this, "filterCnapName: Filtered CNAP Name: " + cnapName);
                return "";
            }
        }
        return cnapName;
    }

    /**
     * Sets the EXTRA_CALL_TECHNOLOGY_TYPE extra on the connection to report back to Telecom.
     */
    private void setTechnologyTypeExtra() {
        if (getPhone() != null) {
            Bundle newExtras = getExtras();
            if (newExtras == null) {
                newExtras = new Bundle();
            }
            newExtras.putInt(TelecomManager.EXTRA_CALL_TECHNOLOGY_TYPE, getPhone().getPhoneType());
            putTelephonyExtras(newExtras);
        }
    }

    private void refreshHoldSupported() {
       if (mOriginalConnection == null) {
           Log.w(this, "refreshHoldSupported org conn is null");
           return;
       }

       if (!mOriginalConnection.shouldAllowHoldingVideoCall() && canHoldImsCalls() !=
               ((getConnectionCapabilities() & (CAPABILITY_HOLD | CAPABILITY_SUPPORT_HOLD)) != 0)) {
           updateConnectionCapabilities();
       }
    }

    private void refreshDisableAddCall() {
        if (shouldSetDisableAddCallExtra()) {
            Bundle newExtras = getExtras();
            if (newExtras == null) {
                newExtras = new Bundle();
            }
            newExtras.putBoolean(Connection.EXTRA_DISABLE_ADD_CALL, true);
            putTelephonyExtras(newExtras);
        } else {
            removeExtras(Connection.EXTRA_DISABLE_ADD_CALL);
        }
    }

    private void refreshCodec() {
        boolean changed = false;
        Bundle newExtras = getExtras();
        if (newExtras == null) {
            newExtras = new Bundle();
        }
        int newCodecType;
        if (isImsConnection()) {
            newCodecType = transformCodec(getOriginalConnection().getAudioCodec());
        } else {
            // For SRVCC, report AUDIO_CODEC_NONE.
            newCodecType = Connection.AUDIO_CODEC_NONE;
        }
        int oldCodecType = newExtras.getInt(Connection.EXTRA_AUDIO_CODEC,
                Connection.AUDIO_CODEC_NONE);
        if (newCodecType != oldCodecType) {
            newExtras.putInt(Connection.EXTRA_AUDIO_CODEC, newCodecType);
            Log.i(this, "refreshCodec: codec changed; old=%d, new=%d", oldCodecType, newCodecType);
            changed = true;
        }
        if (isImsConnection()) {
            float newBitrate = getOriginalConnection().getAudioCodecBitrateKbps();
            float oldBitrate = newExtras.getFloat(Connection.EXTRA_AUDIO_CODEC_BITRATE_KBPS, 0.0f);
            if (Math.abs(newBitrate - oldBitrate) > THRESHOLD) {
                newExtras.putFloat(Connection.EXTRA_AUDIO_CODEC_BITRATE_KBPS, newBitrate);
                Log.i(this, "refreshCodec: bitrate changed; old=%f, new=%f", oldBitrate,
                        newBitrate);
                changed = true;
            }

            float newBandwidth = getOriginalConnection().getAudioCodecBandwidthKhz();
            float oldBandwidth = newExtras.getFloat(Connection.EXTRA_AUDIO_CODEC_BANDWIDTH_KHZ,
                    0.0f);
            if (Math.abs(newBandwidth - oldBandwidth) > THRESHOLD) {
                newExtras.putFloat(Connection.EXTRA_AUDIO_CODEC_BANDWIDTH_KHZ, newBandwidth);
                Log.i(this, "refreshCodec: bandwidth changed; old=%f, new=%f", oldBandwidth,
                        newBandwidth);
                changed = true;
            }
        } else {
            ArrayList<String> toRemove = new ArrayList<>();
            toRemove.add(Connection.EXTRA_AUDIO_CODEC_BITRATE_KBPS);
            toRemove.add(Connection.EXTRA_AUDIO_CODEC_BANDWIDTH_KHZ);
            removeTelephonyExtras(toRemove);
        }

        if (changed) {
            Log.i(this, "refreshCodec: Codec:"
                    + newExtras.getInt(Connection.EXTRA_AUDIO_CODEC, Connection.AUDIO_CODEC_NONE)
                    + ", Bitrate:"
                    + newExtras.getFloat(Connection.EXTRA_AUDIO_CODEC_BITRATE_KBPS, 0.0f)
                    + ", Bandwidth:"
                    + newExtras.getFloat(Connection.EXTRA_AUDIO_CODEC_BANDWIDTH_KHZ, 0.0f));
            putTelephonyExtras(newExtras);
        }
    }

    private int transformCodec(int codec) {
        switch (codec) {
            case ImsStreamMediaProfile.AUDIO_QUALITY_NONE:
                return Connection.AUDIO_CODEC_NONE;
            case ImsStreamMediaProfile.AUDIO_QUALITY_AMR:
                return Connection.AUDIO_CODEC_AMR;
            case ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB:
                return Connection.AUDIO_CODEC_AMR_WB;
            case ImsStreamMediaProfile.AUDIO_QUALITY_QCELP13K:
                return Connection.AUDIO_CODEC_QCELP13K;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVRC:
                return Connection.AUDIO_CODEC_EVRC;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_B:
                return Connection.AUDIO_CODEC_EVRC_B;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_WB:
                return Connection.AUDIO_CODEC_EVRC_WB;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_NW:
                return Connection.AUDIO_CODEC_EVRC_NW;
            case ImsStreamMediaProfile.AUDIO_QUALITY_GSM_EFR:
                return Connection.AUDIO_CODEC_GSM_EFR;
            case ImsStreamMediaProfile.AUDIO_QUALITY_GSM_FR:
                return Connection.AUDIO_CODEC_GSM_FR;
            case ImsStreamMediaProfile.AUDIO_QUALITY_GSM_HR:
                return Connection.AUDIO_CODEC_GSM_HR;
            case ImsStreamMediaProfile.AUDIO_QUALITY_G711U:
                return Connection.AUDIO_CODEC_G711U;
            case ImsStreamMediaProfile.AUDIO_QUALITY_G723:
                return Connection.AUDIO_CODEC_G723;
            case ImsStreamMediaProfile.AUDIO_QUALITY_G711A:
                return Connection.AUDIO_CODEC_G711A;
            case ImsStreamMediaProfile.AUDIO_QUALITY_G722:
                return Connection.AUDIO_CODEC_G722;
            case ImsStreamMediaProfile.AUDIO_QUALITY_G711AB:
                return Connection.AUDIO_CODEC_G711AB;
            case ImsStreamMediaProfile.AUDIO_QUALITY_G729:
                return Connection.AUDIO_CODEC_G729;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVS_NB:
                return Connection.AUDIO_CODEC_EVS_NB;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVS_WB:
                return Connection.AUDIO_CODEC_EVS_WB;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVS_SWB:
                return Connection.AUDIO_CODEC_EVS_SWB;
            case ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB:
                return Connection.AUDIO_CODEC_EVS_FB;
            default:
                return Connection.AUDIO_CODEC_NONE;
        }
    }

    private boolean shouldSetDisableAddCallExtra() {
        if (mOriginalConnection == null) {
            return false;
        }
        boolean carrierShouldAllowAddCall = mOriginalConnection.shouldAllowAddCallDuringVideoCall();
        if (carrierShouldAllowAddCall) {
            return false;
        }
        Phone phone = getPhone();
        if (phone == null) {
            return false;
        }
        boolean isCurrentVideoCall = false;
        boolean wasVideoCall = false;
        boolean isVowifiEnabled = false;
        if (phone instanceof ImsPhone) {
            ImsPhoneCall foregroundCall = ((ImsPhone) phone).getForegroundCall();
            if (foregroundCall != null) {
                ImsCall call = foregroundCall.getImsCall();
                if (call != null) {
                    isCurrentVideoCall = call.isVideoCall();
                    wasVideoCall = call.wasVideoCall();
                }
            }

            isVowifiEnabled = isWfcEnabled(phone);
        }

        if (isCurrentVideoCall) {
            return true;
        } else if (wasVideoCall && isWifi() && !isVowifiEnabled) {
            return true;
        }
        return false;
    }

    private boolean hasHighDefAudioProperty() {
        if (!mHasHighDefAudio) {
            return false;
        }

        boolean isVideoCall = VideoProfile.isVideo(getVideoState());

        PersistableBundle b = getCarrierConfig();
        boolean canWifiCallsBeHdAudio =
                b != null && b.getBoolean(CarrierConfigManager.KEY_WIFI_CALLS_CAN_BE_HD_AUDIO);
        boolean canVideoCallsBeHdAudio =
                b != null && b.getBoolean(CarrierConfigManager.KEY_VIDEO_CALLS_CAN_BE_HD_AUDIO);
        boolean canGsmCdmaCallsBeHdAudio =
                b != null && b.getBoolean(CarrierConfigManager.KEY_GSM_CDMA_CALLS_CAN_BE_HD_AUDIO);
        boolean shouldDisplayHdAudio =
                b != null && b.getBoolean(CarrierConfigManager.KEY_DISPLAY_HD_AUDIO_PROPERTY_BOOL);

        if (!shouldDisplayHdAudio) {
            return false;
        }

        if (isGsmCdmaConnection() && !canGsmCdmaCallsBeHdAudio) {
            return false;
        }

        if (isVideoCall && !canVideoCallsBeHdAudio) {
            return false;
        }

        if (isWifi() && !canWifiCallsBeHdAudio) {
            return false;
        }

        return true;
    }

    /**
     * @return The address's to which this Connection is currently communicating.
     */
    public final @Nullable List<Uri> getParticipants() {
        return mParticipants;
    }

    /**
     * Sets the value of the {@link #getParticipants()} property.
     *
     * @param address The participant address's.
     */
    public final void setParticipants(@Nullable List<Uri> address) {
        mParticipants = address;
    }

    /**
     * @return true if connection is adhocConference call else false.
     */
    public final boolean isAdhocConferenceCall() {
        return mIsAdhocConferenceCall;
    }

    /**
     * Sets the value of the {@link #isAdhocConferenceCall()} property.
     *
     * @param isAdhocConferenceCall represents if the call is adhoc conference call or not.
     */
    public void setIsAdhocConferenceCall(boolean isAdhocConferenceCall) {
        mIsAdhocConferenceCall = isAdhocConferenceCall;
        updateConnectionProperties();
    }

    private boolean canHoldImsCalls() {
        PersistableBundle b = getCarrierConfig();
        // Return true if the CarrierConfig is unavailable
        return (!doesDeviceRespectHoldCarrierConfig() || b == null ||
                b.getBoolean(CarrierConfigManager.KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL)) &&
                ((mOriginalConnection != null && mOriginalConnection.shouldAllowHoldingVideoCall())
                || !VideoProfile.isVideo(getVideoState()));
    }

    private boolean isConferenceHosted() {
        boolean isHosted = false;
        if (getTelephonyConnectionService() != null) {
            for (Conference current : getTelephonyConnectionService().getAllConferences()) {
                if (current instanceof ImsConference) {
                    ImsConference other = (ImsConference) current;
                    if (getState() == current.getState()) {
                        continue;
                    }
                    if (other.isConferenceHost()) {
                        isHosted = true;
                        break;
                    }
                }
            }
        }
        return isHosted;
    }

    private boolean isAddParticipantCapable() {
        // not add participant capable for non ims phones
        if (getPhone() == null || getPhone().getPhoneType() != PhoneConstants.PHONE_TYPE_IMS) {
            return false;
        }

        if (!getCarrierConfig()
                .getBoolean(CarrierConfigManager.KEY_SUPPORT_ADD_CONFERENCE_PARTICIPANTS_BOOL)) {
            return false;
        }

        boolean isCapable = !mTreatAsEmergencyCall && (mConnectionState == Call.State.ACTIVE ||
                mConnectionState == Call.State.HOLDING);

        // add participant capable if current connection is a host connection or
        // if conference is not hosted on the device
        isCapable = isCapable && ((mOriginalConnection != null &&
                mOriginalConnection.isConferenceHost()) ||
                !isConferenceHosted());

        /**
          * For individual IMS calls, if the extra for remote conference support is
          *     - indicated, then consider the same for add participant capability
          *     - not indicated, then the add participant capability is same as before.
          */
        if (isCapable && (mOriginalConnection != null) && !mIsMultiParty) {
            // In case OEMs are still using deprecated value, read it and use it as default value.
            boolean isCapableFromDeprecatedExtra = mOriginalConnectionExtras.getBoolean(
                    ImsCallProfile.EXTRA_CONFERENCE_AVAIL, isCapable);
            isCapable = mOriginalConnectionExtras.getBoolean(
                    ImsCallProfile.EXTRA_EXTENDING_TO_CONFERENCE_SUPPORTED,
                    isCapableFromDeprecatedExtra);
        }
        return isCapable;
    }

    /**
     * Applies the add participant capabilities to the {@code CallCapabilities} bit-mask.
     *
     * @param callCapabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the add participant capabilities applied.
     */
    private int applyAddParticipantCapabilities(int callCapabilities) {
        int currentCapabilities = callCapabilities;
        if (isAddParticipantCapable()) {
            currentCapabilities = changeBitmask(currentCapabilities,
                    Connection.CAPABILITY_ADD_PARTICIPANT, true);
        } else {
            currentCapabilities = changeBitmask(currentCapabilities,
                    Connection.CAPABILITY_ADD_PARTICIPANT, false);
        }
        return currentCapabilities;
    }

    @VisibleForTesting
    public @NonNull PersistableBundle getCarrierConfig() {
        Phone phone = getPhone();
        if (phone == null) {
            Log.w(this,
                    "getCarrierConfig: phone is null. Returning CarrierConfigManager"
                            + ".getDefaultConfig()");
            return CarrierConfigManager.getDefaultConfig();
        }

        // potential null returned from .getCarrierConfigForSubId() and method guarantees non-null.
        // hence, need for try/finally block
        PersistableBundle pb = null;
        try {
            pb = PhoneGlobals.getInstance().getCarrierConfigForSubId(phone.getSubId());
        } catch (Exception e) {
            Log.e(this, e,
                    "getCarrierConfig: caught Exception when calling "
                            + "PhoneGlobals.getCarrierConfigForSubId(phone.getSubId()). Returning "
                            + "CarrierConfigManager.getDefaultConfig()");
        } finally {
            if (pb == null) {
                pb = CarrierConfigManager.getDefaultConfig();
            }
        }
        return pb;
    }

    @VisibleForTesting
    public boolean isRttMergeSupported(@NonNull PersistableBundle pb) {
        return pb.getBoolean(CarrierConfigManager.KEY_ALLOW_MERGING_RTT_CALLS_BOOL);
    }

    private boolean canDeflectImsCalls() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager.KEY_CARRIER_ALLOW_DEFLECT_IMS_CALL_BOOL)
                && isValidRingingCall();
    }

    private boolean isCallTransferSupported() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager.KEY_CARRIER_ALLOW_TRANSFER_IMS_CALL_BOOL);
    }

    private boolean canTransfer(TelephonyConnection c) {
        com.android.internal.telephony.Connection connection = c.getOriginalConnection();
        return (connection != null && !connection.isMultiparty()
                && (c.getState() == STATE_ACTIVE || c.getState() == STATE_HOLDING));
    }

    private boolean canTransferToNumber() {
        if (!isCallTransferSupported()) {
            return false;
        }
        return canTransfer(this);
    }

    private boolean canConsultativeTransfer() {
        if (!isCallTransferSupported()) {
            return false;
        }
        if (!canTransfer(this)) {
            return false;
        }
        boolean canConsultativeTransfer = false;
        if (getTelephonyConnectionService() != null) {
            for (Connection current : getTelephonyConnectionService().getAllConnections()) {
                if (current != this && current instanceof TelephonyConnection) {
                    TelephonyConnection other = (TelephonyConnection) current;
                    if (canTransfer(other)) {
                        canConsultativeTransfer = true;
                        break;
                    }
                }
            }
        }
        return canConsultativeTransfer;
    }

    /**
     * Determines if the device will respect the value of the
     * {@link CarrierConfigManager#KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL} configuration option.
     *
     * @return {@code false} if the device always supports holding IMS calls, {@code true} if it
     *      will use {@link CarrierConfigManager#KEY_ALLOW_HOLD_IN_IMS_CALL_BOOL} to determine if
     *      hold is supported.
     */
    private boolean doesDeviceRespectHoldCarrierConfig() {
        Phone phone = getPhone();
        if (phone == null) {
            return true;
        }
        return phone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_device_respects_hold_carrier_config);
    }

    /**
     * Whether the connection should be treated as an emergency.
     * @return {@code true} if the connection should be treated as an emergency call based
     * on the number dialed, {@code false} otherwise.
     */
    protected boolean shouldTreatAsEmergencyCall() {
        return mTreatAsEmergencyCall;
    }

    /**
     * Sets whether to treat this call as an emergency call or not.
     * @param shouldTreatAsEmergencyCall
     */
    @VisibleForTesting
    public void setShouldTreatAsEmergencyCall(boolean shouldTreatAsEmergencyCall) {
        mTreatAsEmergencyCall = shouldTreatAsEmergencyCall;
    }

    /**
     * Un-sets the underlying radio connection.
     */
    void clearOriginalConnection() {
        if (mOriginalConnection != null) {
            Log.i(this, "clearOriginalConnection; clearing=%s", mOriginalConnection);
            unregisterForCallEvents();
            mOriginalConnection.removePostDialListener(mPostDialListener);
            mOriginalConnection.removeListener(mOriginalConnectionListener);
            mOriginalConnection = null;
        }
    }

    public void unregisterForCallEvents() {
        if (mPhoneForEvents == null) return;
        mPhoneForEvents.unregisterForPreciseCallStateChanged(mHandler);
        mPhoneForEvents.unregisterForRingbackTone(mHandler);
        mPhoneForEvents.unregisterForHandoverStateChanged(mHandler);
        mPhoneForEvents.unregisterForRedialConnectionChanged(mHandler);
        mPhoneForEvents.unregisterForDisconnect(mHandler);
        mPhoneForEvents.unregisterForSuppServiceNotification(mHandler);
        mPhoneForEvents.unregisterForOnHoldTone(mHandler);
        mPhoneForEvents.unregisterForInCallVoicePrivacyOn(mHandler);
        mPhoneForEvents.unregisterForInCallVoicePrivacyOff(mHandler);
        mPhoneForEvents = null;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    public void hangup(int telephonyDisconnectCode) {
        if (mOriginalConnection != null) {
            mHangupDisconnectCause = telephonyDisconnectCode;
            try {
                // Hanging up a ringing call requires that we invoke call.hangup() as opposed to
                // connection.hangup(). Without this change, the party originating the call
                // will not get sent to voicemail if the user opts to reject the call.
                if (isValidRingingCall()) {
                    Call call = getCall();
                    if (call != null) {
                        call.hangup();
                    } else {
                        Log.w(this, "Attempting to hangup a connection without backing call.");
                    }
                } else {
                    // We still prefer to call connection.hangup() for non-ringing calls
                    // in order to support hanging-up specific calls within a conference call.
                    // If we invoked call.hangup() while in a conference, we would end up
                    // hanging up the entire conference call instead of the specific connection.
                    mOriginalConnection.hangup();
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.hangup failed with exception");
            }
        } else {
            mTelephonyConnectionService.onLocalHangup(this);
            if (getState() == STATE_DISCONNECTED) {
                Log.i(this, "hangup called on an already disconnected call!");
                close();
            } else {
                // There are a few cases where mOriginalConnection has not been set yet. For
                // example, when the radio has to be turned on to make an emergency call,
                // mOriginalConnection could not be set for many seconds.
                setTelephonyConnectionDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                        android.telephony.DisconnectCause.LOCAL,
                        "Local Disconnect before connection established."));
                close();
            }
        }
    }

    protected void reject(@android.telecom.Call.RejectReason int rejectReason) {
        if (mOriginalConnection != null) {
            mHangupDisconnectCause = android.telephony.DisconnectCause.INCOMING_REJECTED;
            try {
                // Hanging up a ringing call requires that we invoke call.hangup() as opposed to
                // connection.hangup(). Without this change, the party originating the call
                // will not get sent to voicemail if the user opts to reject the call.
                if (isValidRingingCall()) {
                    Call call = getCall();
                    if (call != null) {
                        call.hangup(rejectReason);
                    } else {
                        Log.w(this, "Attempting to hangup a connection without backing call.");
                    }
                } else {
                    // We still prefer to call connection.hangup() for non-ringing calls
                    // in order to support hanging-up specific calls within a conference call.
                    // If we invoked call.hangup() while in a conference, we would end up
                    // hanging up the entire conference call instead of the specific connection.
                    mOriginalConnection.hangup();
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.hangup failed with exception");
            }
        } else {
            if (getState() == STATE_DISCONNECTED) {
                Log.i(this, "hangup called on an already disconnected call!");
                close();
            } else {
                // There are a few cases where mOriginalConnection has not been set yet. For
                // example, when the radio has to be turned on to make an emergency call,
                // mOriginalConnection could not be set for many seconds.
                setTelephonyConnectionDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                        android.telephony.DisconnectCause.LOCAL,
                        "Local Disconnect before connection established."));
                close();
            }
        }
    }

    com.android.internal.telephony.Connection getOriginalConnection() {
        return mOriginalConnection;
    }

    protected Call getCall() {
        if (mOriginalConnection != null) {
            return mOriginalConnection.getCall();
        }
        return null;
    }

    Phone getPhone() {
        Call call = getCall();
        if (call != null) {
            return call.getPhone();
        }
        return null;
    }

    private boolean hasMultipleTopLevelCalls() {
        int numCalls = 0;
        Phone phone = getPhone();
        if (phone != null) {
            if (!phone.getRingingCall().isIdle()) {
                numCalls++;
            }
            if (!phone.getForegroundCall().isIdle()) {
                numCalls++;
            }
            if (!phone.getBackgroundCall().isIdle()) {
                numCalls++;
            }
        }
        return numCalls > 1;
    }

    private com.android.internal.telephony.Connection getForegroundConnection() {
        if (getPhone() != null) {
            return getPhone().getForegroundCall().getEarliestConnection();
        }
        return null;
    }

     /**
     * Checks for and returns the list of conference participants
     * associated with this connection.
     */
    public List<ConferenceParticipant> getConferenceParticipants() {
        if (mOriginalConnection == null) {
            Log.w(this, "Null mOriginalConnection, cannot get conf participants.");
            return null;
        }
        return mOriginalConnection.getConferenceParticipants();
    }

    /**
     * Checks to see the original connection corresponds to an active incoming call. Returns false
     * if there is no such actual call, or if the associated call is not incoming (See
     * {@link Call.State#isRinging}).
     */
    private boolean isValidRingingCall() {
        if (getPhone() == null) {
            Log.v(this, "isValidRingingCall, phone is null");
            return false;
        }

        Call ringingCall = getPhone().getRingingCall();
        if (!ringingCall.getState().isRinging()) {
            Log.v(this, "isValidRingingCall, ringing call is not in ringing state");
            return false;
        }

        if (ringingCall.getEarliestConnection() != mOriginalConnection) {
            Log.v(this, "isValidRingingCall, ringing call connection does not match");
            return false;
        }

        Log.v(this, "isValidRingingCall, returning true");
        return true;
    }

    // Make sure the extras being passed into this method is a COPY of the original extras Bundle.
    // We do not want the extras to be cleared or modified during mOriginalConnectionExtras.putAll
    // below.
    protected void updateExtras(Bundle extras) {
        if (mOriginalConnection != null) {
            if (extras != null) {
                // Check if extras have changed and need updating.
                if (!areBundlesEqual(mOriginalConnectionExtras, extras)) {
                    if (Log.DEBUG) {
                        Log.d(TelephonyConnection.this, "Updating extras:");
                        for (String key : extras.keySet()) {
                            Object value = extras.get(key);
                            if (value instanceof String) {
                                Log.d(this, "updateExtras Key=" + Rlog.pii(LOG_TAG, key)
                                        + " value=" + Rlog.pii(LOG_TAG, value));
                            }
                        }
                    }
                    mOriginalConnectionExtras.clear();

                    mOriginalConnectionExtras.putAll(extras);

                    // Remap any string extras that have a remapping defined.
                    for (String key : mOriginalConnectionExtras.keySet()) {
                        if (sExtrasMap.containsKey(key)) {
                            String newKey = sExtrasMap.get(key);
                            mOriginalConnectionExtras.putString(newKey, extras.getString(key));
                            mOriginalConnectionExtras.remove(key);
                        }
                    }

                    // Ensure extras are propagated to Telecom.
                    putTelephonyExtras(mOriginalConnectionExtras);
                    // If extras contain Conference support information,
                    // then ensure capabilities are updated.
                    if (mOriginalConnectionExtras.containsKey(
                            ImsCallProfile.EXTRA_EXTENDING_TO_CONFERENCE_SUPPORTED)
                            || mOriginalConnectionExtras.containsKey(
                                ImsCallProfile.EXTRA_CONFERENCE_AVAIL)) {
                        updateConnectionCapabilities();
                    }
                    // If extras contain or contained Cross Sim information,
                    // then ensure connection properties are updated and propagated to Telecom.
                    // Also, update the status hints in the case the call has
                    // has moved from cross sim call back to wifi
                    mWasCrossSim |= mOriginalConnectionExtras.containsKey(
                                ImsCallProfile.EXTRA_IS_CROSS_SIM_CALL);
                    if (mWasCrossSim) {
                        updateStatusHints();
                        updateConnectionProperties();
                    }
                } else {
                    Log.d(this, "Extras update not required");
                }
            } else {
                Log.d(this, "updateExtras extras: " + Rlog.pii(LOG_TAG, extras));
            }
        }
    }

    private static boolean areBundlesEqual(Bundle extras, Bundle newExtras) {
        if (extras == null || newExtras == null) {
            return extras == newExtras;
        }

        if (extras.size() != newExtras.size()) {
            return false;
        }

        for(String key : extras.keySet()) {
            if (key != null) {
                final Object value = extras.get(key);
                final Object newValue = newExtras.get(key);
                if (!Objects.equals(value, newValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    void setStateOverride(Call.State state) {
        mIsStateOverridden = true;
        mConnectionOverriddenState = state;
        // Need to keep track of the original connection's state before override.
        mOriginalConnectionState = mOriginalConnection.getState();
        updateStateInternal();
    }

    void resetStateOverride() {
        mIsStateOverridden = false;
        updateStateInternal();
    }

    void updateStateInternal() {
        if (mOriginalConnection == null) {
            return;
        }
        Call.State newState;
        // If the state is overridden and the state of the original connection hasn't changed since,
        // then we continue in the overridden state, else we go to the original connection's state.
        if (mIsStateOverridden && mOriginalConnectionState == mOriginalConnection.getState()) {
            newState = mConnectionOverriddenState;
        } else {
            newState = mOriginalConnection.getState();
        }
        int cause = mOriginalConnection.getDisconnectCause();
        Log.v(this, "Update state from %s to %s for %s", mConnectionState, newState,
                getTelecomCallId());

        if (mConnectionState != newState) {
            mConnectionState = newState;
            switch (newState) {
                case IDLE:
                    break;
                case ACTIVE:
                    setActiveInternal();
                    break;
                case HOLDING:
                    setTelephonyConnectionOnHold();
                    break;
                case DIALING:
                case ALERTING:
                    if (mOriginalConnection != null && mOriginalConnection.isPulledCall()) {
                        setTelephonyConnectionPulling();
                    } else {
                        setTelephonyConnectionDialing();
                    }
                    break;
                case INCOMING:
                case WAITING:
                    setTelephonyConnectionRinging();
                    break;
                case DISCONNECTED:
                    if (mTelephonyConnectionService != null) {
                        ImsReasonInfo reasonInfo = null;
                        if (isImsConnection()) {
                            ImsPhoneConnection imsPhoneConnection =
                                    (ImsPhoneConnection) mOriginalConnection;
                            reasonInfo = imsPhoneConnection.getImsReasonInfo();
                            if (reasonInfo != null) {
                                int reasonCode = reasonInfo.getCode();
                                int extraCode = reasonInfo.getExtraCode();
                                if ((reasonCode == CODE_SIP_ALTERNATE_EMERGENCY_CALL)
                                        || (reasonCode == CODE_LOCAL_CALL_CS_RETRY_REQUIRED
                                                && extraCode == EXTRA_CODE_CALL_RETRY_EMERGENCY)) {
                                    EmergencyNumber numberInfo =
                                            imsPhoneConnection.getEmergencyNumberInfo();
                                    if (numberInfo != null) {
                                        mEmergencyServiceCategory =
                                                numberInfo.getEmergencyServiceCategoryBitmask();
                                        mEmergencyUrns = numberInfo.getEmergencyUrns();
                                    } else {
                                        Log.i(this, "mEmergencyServiceCategory no EmergencyNumber");
                                    }

                                    if (mEmergencyServiceCategory != null) {
                                        Log.i(this, "mEmergencyServiceCategory="
                                                + mEmergencyServiceCategory);
                                    }
                                    if (mEmergencyUrns != null) {
                                        Log.i(this, "mEmergencyUrns=" + mEmergencyUrns);
                                    }
                                }
                            }
                        }

                        if (mTelephonyConnectionService.maybeReselectDomain(this, reasonInfo,
                                mShowPreciseFailedCause, mHangupDisconnectCause)) {
                            clearOriginalConnection();
                            break;
                        }
                    }

                    if (shouldTreatAsEmergencyCall()
                            && (cause
                            == android.telephony.DisconnectCause.EMERGENCY_TEMP_FAILURE
                            || cause
                            == android.telephony.DisconnectCause.EMERGENCY_PERM_FAILURE)) {
                        // We can get into a situation where the radio wants us to redial the
                        // same emergency call on the other available slot. This will not set
                        // the state to disconnected and will instead tell the
                        // TelephonyConnectionService to
                        // create a new originalConnection using the new Slot.
                        fireOnOriginalConnectionRetryDial(cause
                                == android.telephony.DisconnectCause.EMERGENCY_PERM_FAILURE);
                    } else {
                        int preciseDisconnectCause = CallFailCause.NOT_VALID;
                        if (mShowPreciseFailedCause) {
                            preciseDisconnectCause =
                                    mOriginalConnection.getPreciseDisconnectCause();
                        }
                        int disconnectCause = mOriginalConnection.getDisconnectCause();
                        if ((mHangupDisconnectCause != DisconnectCause.NOT_VALID)
                                && (mHangupDisconnectCause != disconnectCause)) {
                            Log.i(LOG_TAG, "setDisconnected: override cause: " + disconnectCause
                                    + " -> " + mHangupDisconnectCause);
                            disconnectCause = mHangupDisconnectCause;
                        }
                        ImsReasonInfo imsReasonInfo = null;
                        if (isImsConnection()) {
                            ImsPhoneConnection imsPhoneConnection =
                                    (ImsPhoneConnection) mOriginalConnection;
                            imsReasonInfo = imsPhoneConnection.getImsReasonInfo();
                        }
                        setTelephonyConnectionDisconnected(
                                DisconnectCauseUtil.toTelecomDisconnectCause(
                                        disconnectCause,
                                        preciseDisconnectCause,
                                        mOriginalConnection.getVendorDisconnectCause(),
                                        getPhone().getPhoneId(), imsReasonInfo,
                                        new FlagsAdapterImpl(),
                                        shouldTreatAsEmergencyCall()));
                        close();
                    }
                    break;
                case DISCONNECTING:
                    break;
            }

            if (mCommunicator != null) {
                mCommunicator.onStateChanged(getTelecomCallId(), getState());
            }
        }
    }

    void updateState() {
        if (mOriginalConnection == null) {
            return;
        }

        updateStateInternal();
        updateStatusHints();
        updateConnectionCapabilities();
        updateConnectionProperties();
        updateAddress();
        updateMultiparty();
        refreshDisableAddCall();
        refreshCodec();
    }

    /**
     * Checks for changes to the multiparty bit.  If a conference has started, informs listeners.
     */
    private void updateMultiparty() {
        if (mOriginalConnection == null) {
            return;
        }

        if (mIsMultiParty != mOriginalConnection.isMultiparty()) {
            mIsMultiParty = mOriginalConnection.isMultiparty();

            if (mIsMultiParty) {
                notifyConferenceStarted();
            }
        }
    }

    /**
     * Handles a failure when merging calls into a conference.
     * {@link com.android.internal.telephony.Connection.Listener#onConferenceMergedFailed()}
     * listener.
     */
    private void handleConferenceMergeFailed(){
        mHandler.obtainMessage(MSG_CONFERENCE_MERGE_FAILED).sendToTarget();
    }

    /**
     * Handles requests to update the multiparty state received via the
     * {@link com.android.internal.telephony.Connection.Listener#onMultipartyStateChanged(boolean)}
     * listener.
     * <p>
     * Note: We post this to the mHandler to ensure that if a conference must be created as a
     * result of the multiparty state change, the conference creation happens on the correct
     * thread.  This ensures that the thread check in
     * {@link com.android.internal.telephony.Phone#checkCorrectThread(android.os.Handler)}
     * does not fire.
     *
     * @param isMultiParty {@code true} if this connection is multiparty, {@code false} otherwise.
     */
    private void handleMultipartyStateChange(boolean isMultiParty) {
        Log.i(this, "Update multiparty state to %s", isMultiParty ? "Y" : "N");
        mHandler.obtainMessage(MSG_MULTIPARTY_STATE_CHANGED, isMultiParty).sendToTarget();
    }

    private void setActiveInternal() {
        if (getState() == STATE_ACTIVE) {
            Log.w(this, "Should not be called if this is already ACTIVE");
            return;
        }

        // When we set a call to active, we need to make sure that there are no other active
        // calls. However, the ordering of state updates to connections can be non-deterministic
        // since all connections register for state changes on the phone independently.
        // To "optimize", we check here to see if there already exists any active calls.  If so,
        // we issue an update for those calls first to make sure we only have one top-level
        // active call.
        if (getTelephonyConnectionService() != null) {
            for (Connection current : getTelephonyConnectionService().getAllConnections()) {
                if (current != this && current instanceof TelephonyConnection) {
                    TelephonyConnection other = (TelephonyConnection) current;
                    if (other.getState() == STATE_ACTIVE) {
                        other.updateState();
                    }
                }
            }
        }
        setTelephonyConnectionActive();
    }

    public void close() {
        Log.v(this, "close");
        clearOriginalConnection();
        destroy();
        if (mTelephonyConnectionService != null) {
            removeTelephonyConnectionListener(
                    mTelephonyConnectionService.getTelephonyConnectionListener());
        }
        notifyDestroyed();
    }

    /**
     * Determines if the current connection is video capable.
     *
     * A connection is deemed to be video capable if the original connection capabilities state that
     * both local and remote video is supported.
     *
     * @return {@code true} if the connection is video capable, {@code false} otherwise.
     */
    private boolean isVideoCapable() {
        return (mOriginalConnectionCapabilities & Capability.SUPPORTS_VT_LOCAL_BIDIRECTIONAL)
                == Capability.SUPPORTS_VT_LOCAL_BIDIRECTIONAL
                && (mOriginalConnectionCapabilities & Capability.SUPPORTS_VT_REMOTE_BIDIRECTIONAL)
                == Capability.SUPPORTS_VT_REMOTE_BIDIRECTIONAL;
    }

    /**
     * Determines if the current connection is an external connection.
     *
     * A connection is deemed to be external if the original connection capabilities state that it
     * is.
     *
     * @return {@code true} if the connection is external, {@code false} otherwise.
     */
    private boolean isExternalConnection() {
        return (mOriginalConnectionCapabilities
                & Capability.IS_EXTERNAL_CONNECTION) == Capability.IS_EXTERNAL_CONNECTION;
    }

    /**
     * Determines if the current connection has RTT enabled.
     */
    private boolean isRtt() {
        return mOriginalConnection != null
                && mOriginalConnection.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS
                && mOriginalConnection instanceof ImsPhoneConnection
                && ((ImsPhoneConnection) mOriginalConnection).isRttEnabledForCall();
    }

    /**
     * Determines if the current connection is cross sim calling
     */
    private boolean isCrossSimCall() {
        return mOriginalConnection != null
                && mOriginalConnection.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS
                && mOriginalConnection instanceof ImsPhoneConnection
                && ((ImsPhoneConnection) mOriginalConnection).isCrossSimCall();
    }

    /**
     * Determines if the current connection is pullable.
     *
     * A connection is deemed to be pullable if the original connection capabilities state that it
     * is.
     *
     * @return {@code true} if the connection is pullable, {@code false} otherwise.
     */
    private boolean isPullable() {
        return (mOriginalConnectionCapabilities & Capability.IS_EXTERNAL_CONNECTION)
                == Capability.IS_EXTERNAL_CONNECTION
                && (mOriginalConnectionCapabilities & Capability.IS_PULLABLE)
                == Capability.IS_PULLABLE;
    }

    /**
     * Sets whether or not CDMA enhanced call privacy is enabled for this connection.
     */
    private void setCdmaVoicePrivacy(boolean isEnabled) {
        if(mIsCdmaVoicePrivacyEnabled != isEnabled) {
            mIsCdmaVoicePrivacyEnabled = isEnabled;
            updateConnectionProperties();
        }
    }

    /**
     * Applies capabilities specific to conferences termination to the
     * {@code ConnectionCapabilities} bit-mask.
     *
     * @param capabilities The {@code ConnectionCapabilities} bit-mask.
     * @return The capabilities with the IMS conference capabilities applied.
     */
    private int applyConferenceTerminationCapabilities(int capabilities) {
        int currentCapabilities = capabilities;

        // An IMS call cannot be individually disconnected or separated from its parent conference.
        // If the call was IMS, even if it hands over to GMS, these capabilities are not supported.
        if (!mWasImsConnection) {
            currentCapabilities |= CAPABILITY_DISCONNECT_FROM_CONFERENCE;
            currentCapabilities |= CAPABILITY_SEPARATE_FROM_CONFERENCE;
        }

        return currentCapabilities;
    }

    /**
     * Stores the new original connection capabilities, and applies them to the current connection,
     * notifying any listeners as necessary.
     *
     * @param connectionCapabilities The original connection capabilties.
     */
    public void setOriginalConnectionCapabilities(int connectionCapabilities) {
        mOriginalConnectionCapabilities = connectionCapabilities;
        updateConnectionCapabilities();
        updateConnectionProperties();
    }

    /**
     * Called to apply the capabilities present in the {@link #mOriginalConnection} to this
     * {@link Connection}.  Provides a mapping between the capabilities present in the original
     * connection (see {@link com.android.internal.telephony.Connection.Capability}) and those in
     * this {@link Connection}.
     *
     * @param capabilities The capabilities bitmask from the {@link Connection}.
     * @return the capabilities bitmask with the original connection capabilities remapped and
     *      applied.
     */
    public int applyOriginalConnectionCapabilities(int capabilities) {
        // We only support downgrading to audio if both the remote and local side support
        // downgrading to audio.
        int supportsDowngrade = Capability.SUPPORTS_DOWNGRADE_TO_VOICE_LOCAL
                | Capability.SUPPORTS_DOWNGRADE_TO_VOICE_REMOTE;
        boolean supportsDowngradeToAudio =
                (mOriginalConnectionCapabilities & supportsDowngrade) == supportsDowngrade;
        capabilities = changeBitmask(capabilities,
                CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO, !supportsDowngradeToAudio);

        capabilities = changeBitmask(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL,
                (mOriginalConnectionCapabilities & Capability.SUPPORTS_VT_REMOTE_BIDIRECTIONAL)
                        == Capability.SUPPORTS_VT_REMOTE_BIDIRECTIONAL);

        boolean isLocalVideoSupported = (mOriginalConnectionCapabilities
                & Capability.SUPPORTS_VT_LOCAL_BIDIRECTIONAL)
                == Capability.SUPPORTS_VT_LOCAL_BIDIRECTIONAL && !mIsTtyEnabled;
        capabilities = changeBitmask(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL,
                isLocalVideoSupported);

        capabilities = changeBitmask(capabilities, CAPABILITY_REMOTE_PARTY_SUPPORTS_RTT,
                (mOriginalConnectionCapabilities & Capability.SUPPORTS_RTT_REMOTE)
                == Capability.SUPPORTS_RTT_REMOTE);

        return capabilities;
    }

    /**
     * Whether the call is using wifi.
     */
    boolean isWifi() {
        return getCallRadioTech() == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
    }

    /**
     * Sets whether this call has been identified by the network as an emergency call.
     * @param isNetworkIdentifiedEmergencyCall {@code true} if the network has identified this call
     * as an emergency call, {@code false} otherwise.
     */
    public void setIsNetworkIdentifiedEmergencyCall(boolean isNetworkIdentifiedEmergencyCall) {
        Log.d(this, "setIsNetworkIdentifiedEmergencyCall; callId=%s, "
                + "isNetworkIdentifiedEmergencyCall=%b", getTelecomCallId(),
                isNetworkIdentifiedEmergencyCall);
        mIsNetworkIdentifiedEmergencyCall = isNetworkIdentifiedEmergencyCall;
        updateConnectionProperties();
    }

    /**
     * @return {@code true} if the network has identified this call as an emergency call,
     * {@code false} otherwise.
     */
    public boolean isNetworkIdentifiedEmergencyCall() {
        return mIsNetworkIdentifiedEmergencyCall;
    }

    /**
     * @return {@code true} if this is an outgoing call, {@code false} otherwise.
     */
    public boolean isOutgoingCall() {
        return getCallDirection() == android.telecom.Call.Details.DIRECTION_OUTGOING;
    }

    /**
     * Sets the current call audio quality. Used during rebuild of the properties
     * to set or unset the {@link Connection#PROPERTY_HIGH_DEF_AUDIO} property.
     *
     * @param audioQuality The audio quality.
     */
    public void setAudioQuality(int audioQuality) {
        mHasHighDefAudio = audioQuality ==
                com.android.internal.telephony.Connection.AUDIO_QUALITY_HIGH_DEFINITION;
        updateConnectionProperties();
    }

    void resetStateForConference() {
        if (getState() == Connection.STATE_HOLDING) {
            resetStateOverride();
        }
    }

    boolean setHoldingForConference() {
        if (getState() == Connection.STATE_ACTIVE) {
            setStateOverride(Call.State.HOLDING);
            return true;
        }
        return false;
    }

    public void setRttTextStream(RttTextStream s) {
        mRttTextStream = s;
    }

    public RttTextStream getRttTextStream() {
        return mRttTextStream;
    }

    /**
     * For video calls, sets whether this connection supports pausing the outgoing video for the
     * call using the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     *
     * @param isVideoPauseSupported {@code true} if pause state supported, {@code false} otherwise.
     */
    public void setVideoPauseSupported(boolean isVideoPauseSupported) {
        mIsVideoPauseSupported = isVideoPauseSupported;
    }

    /**
     * @return {@code true} if this connection supports pausing the outgoing video using the
     * {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     */
    public boolean getVideoPauseSupported() {
        return mIsVideoPauseSupported;
    }

    /**
     * Sets whether this connection supports conference calling.
     * @param isConferenceSupported {@code true} if conference calling is supported by this
     *                                         connection, {@code false} otherwise.
     */
    public void setConferenceSupported(boolean isConferenceSupported) {
        mIsConferenceSupported = isConferenceSupported;
    }

    /**
     * @return {@code true} if this connection supports merging calls into a conference.
     */
    public boolean isConferenceSupported() {
        return mIsConferenceSupported;
    }

    /**
     * Sets whether managing conference call is supported after this connection being a part of a
     * Ims conference.
     *
     * @param isManageImsConferenceCallSupported {@code true} if manage conference calling is
     *        supported after this connection being a part of a IMS conference,
     *        {@code false} otherwise.
     */
    public void setManageImsConferenceCallSupported(boolean isManageImsConferenceCallSupported) {
        mIsManageImsConferenceCallSupported = isManageImsConferenceCallSupported;
    }

    /**
     * @return {@code true} if manage conference calling is supported after this connection being a
     * part of a IMS conference.
     */
    public boolean isManageImsConferenceCallSupported() {
        return mIsManageImsConferenceCallSupported;
    }

    /**
     * Sets whether this connection supports showing precise call disconnect cause.
     * @param showPreciseFailedCause  {@code true} if showing precise call
     * disconnect cause is supported by this connection, {@code false} otherwise.
     */
    public void setShowPreciseFailedCause(boolean showPreciseFailedCause) {
        mShowPreciseFailedCause = showPreciseFailedCause;
    }

    /**
     * Sets whether TTY is enabled or not.
     * @param isTtyEnabled
     */
    public void setTtyEnabled(boolean isTtyEnabled) {
        mIsTtyEnabled = isTtyEnabled;
        updateConnectionCapabilities();
    }

    /**
     * Whether the original connection is an IMS connection.
     * @return {@code True} if the original connection is an IMS connection, {@code false}
     *     otherwise.
     */
    protected boolean isImsConnection() {
        com.android.internal.telephony.Connection originalConnection = getOriginalConnection();

        return originalConnection != null
                && originalConnection.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS
                && originalConnection instanceof ImsPhoneConnection;
    }

    /**
     * Whether the original connection is an GSM/CDMA connection.
     * @return {@code True} if the original connection is an GSM/CDMA connection, {@code false}
     *     otherwise.
     */
    protected boolean isGsmCdmaConnection() {
        Phone phone = getPhone();
        if (phone != null) {
            switch (phone.getPhoneType()) {
                case PhoneConstants.PHONE_TYPE_GSM:
                case PhoneConstants.PHONE_TYPE_CDMA:
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }

    /**
     * Whether the original connection was ever an IMS connection, either before or now.
     * @return {@code True} if the original connection was ever an IMS connection, {@code false}
     *     otherwise.
     */
    public boolean wasImsConnection() {
        return mWasImsConnection;
    }

    boolean getIsUsingAssistedDialing() {
        return mIsUsingAssistedDialing;
    }

    void setIsUsingAssistedDialing(Boolean isUsingAssistedDialing) {
        mIsUsingAssistedDialing = isUsingAssistedDialing;
        updateConnectionProperties();
    }

    private static Uri getAddressFromNumber(String number) {
        // Address can be null for blocked calls.
        if (number == null) {
            number = "";
        }
        return Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    }

    /**
     * Changes a capabilities bit-mask to add or remove a capability.
     *
     * @param bitmask The bit-mask.
     * @param bitfield The bit-field to change.
     * @param enabled Whether the bit-field should be set or removed.
     * @return The bit-mask with the bit-field changed.
     */
    private int changeBitmask(int bitmask, int bitfield, boolean enabled) {
        if (enabled) {
            return bitmask | bitfield;
        } else {
            return bitmask & ~bitfield;
        }
    }

    private void updateStatusHints() {
        if (isWifi() && !isCrossSimCall() && getPhone() != null) {
            int labelId = isValidRingingCall()
                    ? R.string.status_hint_label_incoming_wifi_call
                    : R.string.status_hint_label_wifi_call;

            Context context = getPhone().getContext();
            setTelephonyStatusHints(new StatusHints(
                    getResourceString(labelId),
                    Icon.createWithResource(
                            context, R.drawable.ic_signal_wifi_4_bar_24dp),
                    null /* extras */));
        } else {
            setTelephonyStatusHints(null);
        }
    }

    /**
     * Register a listener for {@link TelephonyConnection} specific triggers.
     * @param l The instance of the listener to add
     * @return The connection being listened to
     */
    public final TelephonyConnection addTelephonyConnectionListener(TelephonyConnectionListener l) {
        mTelephonyListeners.add(l);
        // If we already have an original connection, let's call back immediately.
        // This would be the case for incoming calls.
        if (mOriginalConnection != null) {
            fireOnOriginalConnectionConfigured();
        }
        return this;
    }

    /**
     * Remove a listener for {@link TelephonyConnection} specific triggers.
     * @param l The instance of the listener to remove
     * @return The connection being listened to
     */
    public final TelephonyConnection removeTelephonyConnectionListener(
            TelephonyConnectionListener l) {
        if (l != null) {
            mTelephonyListeners.remove(l);
        }
        return this;
    }

    @Override
    public void setHoldable(boolean isHoldable) {
        mIsHoldable = isHoldable;
        updateConnectionCapabilities();
    }

    @Override
    public boolean isChildHoldable() {
        return getConference() != null;
    }

    public boolean isHoldable() {
        return mIsHoldable;
    }

    /**
     * Fire a callback to the various listeners for when the original connection is
     * set in this {@link TelephonyConnection}
     */
    private final void fireOnOriginalConnectionConfigured() {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onOriginalConnectionConfigured(this);
        }
    }

    private final void fireOnOriginalConnectionRetryDial(boolean isPermanentFailure) {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onOriginalConnectionRetry(this, isPermanentFailure);
        }
    }

    /**
     * Handles exiting ECM mode.
     */
    protected void handleExitedEcmMode() {
        updateConnectionProperties();
    }

    /**
     * Determines whether the connection supports conference calling.  A connection supports
     * conference calling if it:
     * 1. Is not an emergency call.
     * 2. Carrier supports conference calls.
     * 3. If call is a video call, carrier supports video conference calls.
     * 4. If call is a wifi call and VoWIFI is disabled and carrier supports merging these calls.
     */
    @VisibleForTesting
    void refreshConferenceSupported() {
        boolean isVideoCall = VideoProfile.isVideo(getVideoState());
        Phone phone = getPhone();
        if (phone == null) {
            Log.w(this, "refreshConferenceSupported = false; phone is null");
            if (isConferenceSupported()) {
                setConferenceSupported(false);
                notifyConferenceSupportedChanged(false);
            }
            return;
        }

        boolean isIms = phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS;
        boolean isVoWifiEnabled = false;
        if (isIms) {
            isVoWifiEnabled = isWfcEnabled(phone);
        }
        PhoneAccountHandle phoneAccountHandle = isIms ? PhoneUtils
                .makePstnPhoneAccountHandle(phone.getDefaultPhone())
                : PhoneUtils.makePstnPhoneAccountHandle(phone);
        TelecomAccountRegistry telecomAccountRegistry = getTelecomAccountRegistry(
                getPhone().getContext());
        boolean isConferencingSupported = telecomAccountRegistry
                .isMergeCallSupported(phoneAccountHandle);
        boolean isImsConferencingSupported = telecomAccountRegistry
                .isMergeImsCallSupported(phoneAccountHandle);
        mIsCarrierVideoConferencingSupported = telecomAccountRegistry
                .isVideoConferencingSupported(phoneAccountHandle);
        boolean isMergeOfWifiCallsAllowedWhenVoWifiOff = telecomAccountRegistry
                .isMergeOfWifiCallsAllowedWhenVoWifiOff(phoneAccountHandle);
        ImsCall imsCall = isImsConnection()
                ? ((ImsPhoneConnection) getOriginalConnection()).getImsCall()
                : null;
        CarrierConfigManager configManager = (CarrierConfigManager) phone.getContext()
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        boolean downGradedVideoCall = false;
        if (configManager != null) {
            PersistableBundle config = configManager.getConfigForSubId(phone.getSubId());
            if (config != null) {
                downGradedVideoCall = config.getBoolean(
                        CarrierConfigManager.KEY_TREAT_DOWNGRADED_VIDEO_CALLS_AS_VIDEO_CALLS_BOOL);
            }
        }

        Log.v(this, "refreshConferenceSupported : isConfSupp=%b, isImsConfSupp=%b, " +
                "isVidConfSupp=%b, isMergeOfWifiAllowed=%b, " +
                "isWifi=%b, isVoWifiEnabled=%b",
                isConferencingSupported, isImsConferencingSupported,
                mIsCarrierVideoConferencingSupported, isMergeOfWifiCallsAllowedWhenVoWifiOff,
                isWifi(), isVoWifiEnabled);
        boolean isConferenceSupported = true;
        if (mTreatAsEmergencyCall) {
            isConferenceSupported = false;
            Log.d(this, "refreshConferenceSupported = false; emergency call");
        } else if (isRtt() && !isRttMergeSupported(getCarrierConfig())) {
            isConferenceSupported = false;
            Log.d(this, "refreshConferenceSupported = false; rtt call");
        } else if (!isConferencingSupported || isIms && !isImsConferencingSupported) {
            isConferenceSupported = false;
            Log.d(this, "refreshConferenceSupported = false; carrier doesn't support conf.");
        } else if (isVideoCall && !mIsCarrierVideoConferencingSupported) {
            isConferenceSupported = false;
            Log.d(this, "refreshConferenceSupported = false; video conf not supported.");
        } else if ((imsCall != null) && (imsCall.wasVideoCall() && downGradedVideoCall)
                && !mIsCarrierVideoConferencingSupported) {
            isConferenceSupported = false;
            Log.d(this,
                    "refreshConferenceSupported = false;"
                            + " video conf not supported for downgraded audio call.");
        } else if (!isMergeOfWifiCallsAllowedWhenVoWifiOff && isWifi() && !isVoWifiEnabled) {
            isConferenceSupported = false;
            Log.d(this,
                    "refreshConferenceSupported = false; can't merge wifi calls when voWifi off.");
        } else {
            Log.d(this, "refreshConferenceSupported = true.");
        }

        if (isConferenceSupported != isConferenceSupported()) {
            setConferenceSupported(isConferenceSupported);
            notifyConferenceSupportedChanged(isConferenceSupported);
        }
    }

    @VisibleForTesting
    boolean isWfcEnabled(Phone phone) {
        return ImsUtil.isWfcEnabled(phone.getContext(), phone.getPhoneId());
    }

    /**
     * Provides a mapping from extras keys which may be found in the
     * {@link com.android.internal.telephony.Connection} to their equivalents defined in
     * {@link android.telecom.Connection}.
     *
     * @return Map containing key mappings.
     */
    private static Map<String, String> createExtrasMap() {
        Map<String, String> result = new HashMap<String, String>();
        result.put(ImsCallProfile.EXTRA_CHILD_NUMBER,
                android.telecom.Connection.EXTRA_CHILD_ADDRESS);
        result.put(ImsCallProfile.EXTRA_DISPLAY_TEXT,
                android.telecom.Connection.EXTRA_CALL_SUBJECT);
        result.put(ImsCallProfile.EXTRA_ADDITIONAL_SIP_INVITE_FIELDS,
                android.telecom.Connection.EXTRA_SIP_INVITE);
        return Collections.unmodifiableMap(result);
    }

    private boolean isShowingOriginalDialString() {
        boolean showOrigDialString = false;
        Phone phone = getPhone();
        if (phone != null && (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA)
                && !mOriginalConnection.isIncoming()) {
            showOrigDialString = getCarrierConfig().getBoolean(CarrierConfigManager
                    .KEY_CONFIG_SHOW_ORIG_DIAL_STRING_FOR_CDMA_BOOL);
            Log.d(this, "showOrigDialString: " + showOrigDialString);
        }
        return showOrigDialString;
    }

    /**
     * Creates a string representation of this {@link TelephonyConnection}.  Primarily intended for
     * use in log statements.
     *
     * @return String representation of the connection.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[TelephonyConnection objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" telecomCallID:");
        sb.append(getTelecomCallId());
        sb.append(" type:");
        if (isImsConnection()) {
            sb.append("ims");
        } else if (this instanceof com.android.services.telephony.GsmConnection) {
            sb.append("gsm");
        } else if (this instanceof CdmaConnection) {
            sb.append("cdma");
        }
        sb.append(" state:");
        sb.append(Connection.stateToString(getState()));
        sb.append(" capabilities:");
        sb.append(capabilitiesToString(getConnectionCapabilities()));
        sb.append(" properties:");
        sb.append(propertiesToString(getConnectionProperties()));
        sb.append(" address:");
        sb.append(Rlog.pii(LOG_TAG, getAddress()));
        sb.append(" originalConnection:");
        sb.append(mOriginalConnection);
        sb.append(" partOfConf:");
        if (getConference() == null) {
            sb.append("N");
        } else {
            sb.append("Y");
        }
        sb.append(" confSupported:");
        sb.append(mIsConferenceSupported ? "Y" : "N");
        sb.append(" isAdhocConf:");
        sb.append(isAdhocConferenceCall() ? "Y" : "N");
        sb.append("]");
        return sb.toString();
    }

    public final void setTelephonyConnectionService(TelephonyConnectionService connectionService) {
        mTelephonyConnectionService = connectionService;
    }

    public final TelephonyConnectionService getTelephonyConnectionService() {
        return mTelephonyConnectionService;
    }

    /**
     * Set this {@link TelephonyConnection} to an active state.
     * <p>
     * Note: This should be used instead of {@link #setActive()} to ensure listeners are notified.
     */
    public void setTelephonyConnectionActive() {
        setActive();
        notifyStateChanged(getState());
    }

    /**
     * Set this {@link TelephonyConnection} to a ringing state.
     * <p>
     * Note: This should be used instead of {@link #setRinging()} to ensure listeners are notified.
     */
    public void setTelephonyConnectionRinging() {
        setRinging();
        notifyStateChanged(getState());
    }

    /**
     * Set this {@link TelephonyConnection} to an initializing state.
     * <p>
     * Note: This should be used instead of {@link #setInitializing()} to ensure listeners are
     * notified.
     */
    public void setTelephonyConnectionInitializing() {
        setInitializing();
        notifyStateChanged(getState());
    }

    /**
     * Set this {@link TelephonyConnection} to a dialing state.
     * <p>
     * Note: This should be used instead of {@link #setDialing()} to ensure listeners are notified.
     */
    public void setTelephonyConnectionDialing() {
        setDialing();
        notifyStateChanged(getState());
    }

    /**
     * Set this {@link TelephonyConnection} to a pulling state.
     * <p>
     * Note: This should be used instead of {@link #setPulling()} to ensure listeners are notified.
     */
    public void setTelephonyConnectionPulling() {
        setPulling();
        notifyStateChanged(getState());
    }

    /**
     * Set this {@link TelephonyConnection} to a held state.
     * <p>
     * Note: This should be used instead of {@link #setOnHold()} to ensure listeners are notified.
     */
    public void setTelephonyConnectionOnHold() {
        setOnHold();
        notifyStateChanged(getState());
    }

    /**
     * Set this {@link TelephonyConnection} to a disconnected state.
     * <p>
     * Note: This should be used instead of
     * {@link #setDisconnected(android.telecom.DisconnectCause)} to ensure listeners are notified.
     */
    public void setTelephonyConnectionDisconnected(@NonNull
            android.telecom.DisconnectCause disconnectCause) {
        setDisconnected(disconnectCause);
        notifyDisconnected(disconnectCause);
        notifyStateChanged(getState());
    }

    /**
     * Sends a connection event for this {@link TelephonyConnection}.
     * <p>
     * Note: This should be used instead of {@link #sendConnectionEvent(String, Bundle)} to ensure
     * listeners are notified.
     */
    public void sendTelephonyConnectionEvent(@NonNull String event, @Nullable Bundle extras) {
        sendConnectionEvent(event, extras);
        notifyTelephonyConnectionEvent(event, extras);
    }

    /**
     * Sets the extras associated with this {@link TelephonyConnection}.
     * <p>
     * Note: This should be used instead of {@link #putExtras(Bundle)} to ensure listeners are
     * notified.
     */
    public void putTelephonyExtras(@NonNull Bundle extras) {
        putExtras(extras);
        notifyPutExtras(extras);
    }

    /**
     * Removes the specified extras associated with this {@link TelephonyConnection}.
     * <p>
     * Note: This should be used instead of {@link #removeExtras(String...)} to ensure listeners are
     * notified.
     */
    public void removeTelephonyExtras(@NonNull List<String> keys) {
        removeExtras(keys);
        notifyRemoveExtras(keys);
    }

    /**
     * Sets the video state associated with this {@link TelephonyConnection}.
     * <p>
     * Note: This should be used instead of {@link #setVideoState(int)} to ensure listeners are
     * notified.
     * @param videoState The new video state. Valid values:
     *                   {@link VideoProfile#STATE_AUDIO_ONLY},
     *                   {@link VideoProfile#STATE_BIDIRECTIONAL},
     *                   {@link VideoProfile#STATE_TX_ENABLED},
     *                   {@link VideoProfile#STATE_RX_ENABLED}.
     */
    public void setTelephonyVideoState(int videoState) {
        setVideoState(videoState);
        notifyVideoStateChanged(videoState);
    }

    /**
     * Sets the video provider associated with this {@link TelephonyConnection}.
     * <p>
     * Note: This should be used instead of {@link #setVideoProvider(VideoProvider)} to ensure
     * listeners are notified.
     */
    public void setTelephonyVideoProvider(@Nullable VideoProvider videoProvider) {
        setVideoProvider(videoProvider);
        notifyVideoProviderChanged(videoProvider);
    }

    /**
     * Sets the status hints associated with this {@link TelephonyConnection}.
     * <p>
     * Note: This should be used instead of {@link #setStatusHints(StatusHints)} to ensure listeners
     * are notified.
     */
    public void setTelephonyStatusHints(@Nullable StatusHints statusHints) {
        setStatusHints(statusHints);
        notifyStatusHintsChanged(statusHints);
    }

    /**
     * Sets RIL voice radio technology used for current connection.
     * <p>
     * This property is set by the Telephony {@link ConnectionService}.
     *
     * @param vrat the RIL Voice Radio Technology used for current connection,
     *             see {@code RIL_RADIO_TECHNOLOGY_*} in {@link android.telephony.ServiceState}.
     */
    public final void setCallRadioTech(@RilRadioTechnology int vrat) {
        Bundle extras = getExtras();
        if (extras == null) {
            extras = new Bundle();
        }
        extras.putInt(TelecomManager.EXTRA_CALL_NETWORK_TYPE,
                ServiceState.rilRadioTechnologyToNetworkType(vrat));
        putExtras(extras);
        // Propagates the call radio technology to its parent {@link android.telecom.Conference}
        // This action only covers non-IMS CS conference calls.
        // For IMS PS call conference call, it can be updated via its host connection
        // {@link #Listener.onExtrasChanged} event.
        if (getConference() != null) {
            Bundle newExtras = new Bundle();
            newExtras.putInt(
                    TelecomManager.EXTRA_CALL_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToNetworkType(vrat));
            getConference().putExtras(newExtras);
        }
    }

    /**
     * Returns RIL voice radio technology used for current connection.
     * <p>
     * Used by the Telephony {@link ConnectionService}.
     *
     * @return the RIL voice radio technology used for current connection,
     *         see {@code RIL_RADIO_TECHNOLOGY_*} in {@link android.telephony.ServiceState}.
     */
    public final @RilRadioTechnology int getCallRadioTech() {
        int voiceNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        Bundle extras = getExtras();
        if (extras != null) {
            voiceNetworkType = extras.getInt(TelecomManager.EXTRA_CALL_NETWORK_TYPE,
                    TelephonyManager.NETWORK_TYPE_UNKNOWN);
        }
        return ServiceState.networkTypeToRilRadioTechnology(voiceNetworkType);
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of a change to conference participant data
     * received via the {@link ImsConference} (i.e. conference event package).
     *
     * @param conferenceParticipants The participants.
     */
    private void updateConferenceParticipants(
            @NonNull List<ConferenceParticipant> conferenceParticipants) {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onConferenceParticipantsChanged(this, conferenceParticipants);
        }
    }

    /**
     * Where device to device communication is available and this is an IMS call, configures the
     * D2D communication infrastructure for operation.
     */
    private void maybeConfigureDeviceToDeviceCommunication() {
        if (!getPhone().getContext().getResources().getBoolean(
                R.bool.config_use_device_to_device_communication)) {
            Log.i(this, "maybeConfigureDeviceToDeviceCommunication: not using D2D.");
            notifyD2DAvailabilityChanged(false);
            return;
        }
        if (!isImsConnection()) {
            Log.i(this, "maybeConfigureDeviceToDeviceCommunication: not an IMS connection.");
            if (mCommunicator != null) {
                mCommunicator = null;
            }
            notifyD2DAvailabilityChanged(false);
            return;
        }
        if (mTreatAsEmergencyCall || mIsNetworkIdentifiedEmergencyCall) {
            Log.i(this, "maybeConfigureDeviceToDeviceCommunication: emergency call; no D2D");
            notifyD2DAvailabilityChanged(false);
            return;
        }

        ArrayList<TransportProtocol> supportedTransports = new ArrayList<>(2);

        if (supportsD2DUsingRtp()) {
            Log.i(this, "maybeConfigureDeviceToDeviceCommunication: carrier supports RTP.");
            // Implement abstracted out RTP functionality the RTP transport depends on.
            RtpAdapter rtpAdapter = new RtpAdapter() {
                @Override
                public Set<RtpHeaderExtensionType> getAcceptedRtpHeaderExtensions() {
                    ImsPhoneConnection originalConnection =
                            (ImsPhoneConnection) mOriginalConnection;
                    return originalConnection.getAcceptedRtpHeaderExtensions();
                }

                @Override
                public void sendRtpHeaderExtensions(
                        @NonNull Set<RtpHeaderExtension> rtpHeaderExtensions) {
                    Log.i(TelephonyConnection.this, "sendRtpHeaderExtensions: sending: %s",
                            rtpHeaderExtensions.stream()
                                    .map(r -> r.toString())
                                    .collect(Collectors.joining(",")));
                    ImsPhoneConnection originalConnection =
                            (ImsPhoneConnection) mOriginalConnection;
                    originalConnection.sendRtpHeaderExtensions(rtpHeaderExtensions);
                }
            };
            mRtpTransport = new RtpTransport(rtpAdapter, null /* TODO: not needed yet */, mHandler,
                    supportsSdpNegotiationOfRtpHeaderExtensions());
            supportedTransports.add(mRtpTransport);
        }
        if (supportsD2DUsingDtmf()) {
            Log.i(this, "maybeConfigureDeviceToDeviceCommunication: carrier supports DTMF.");
            DtmfAdapter dtmfAdapter = digit -> {
                Log.i(TelephonyConnection.this, "sendDtmf: send digit %c", digit);
                ImsPhoneConnection originalConnection =
                        (ImsPhoneConnection) mOriginalConnection;
                Message dtmfComplete = mHandler.obtainMessage(MSG_DTMF_DONE);
                dtmfComplete.replyTo = mHandlerMessenger;
                originalConnection.getImsCall().sendDtmf(digit, dtmfComplete);
            };
            ContentResolver cr = getPhone().getContext().getContentResolver();
            mDtmfTransport = new DtmfTransport(dtmfAdapter, new Timeouts.Adapter(cr),
                    Executors.newSingleThreadScheduledExecutor());
            supportedTransports.add(mDtmfTransport);
        }
        if (supportedTransports.size() > 0) {
            mCommunicator = new Communicator(supportedTransports, this);
            mD2DCallStateAdapter = new D2DCallStateAdapter(mCommunicator);
            addTelephonyConnectionListener(mD2DCallStateAdapter);
        } else {
            Log.i(this, "maybeConfigureDeviceToDeviceCommunication: no transports; disabled.");
            notifyD2DAvailabilityChanged(false);
        }
    }

    /**
     * Notifies upper layers of the availability of D2D communication.
     * @param isAvailable {@code true} if D2D is available, {@code false} otherwise.
     */
    private void notifyD2DAvailabilityChanged(boolean isAvailable) {
        Bundle extras = new Bundle();
        extras.putBoolean(Connection.EXTRA_IS_DEVICE_TO_DEVICE_COMMUNICATION_AVAILABLE,
                isAvailable);
        putTelephonyExtras(extras);
    }

    /**
     * @return The D2D communication class, or {@code null} if not set up.
     */
    public @Nullable Communicator getCommunicator() {
        return mCommunicator;
    }

    /**
     * Called by {@link Communicator} associated with this {@link TelephonyConnection} when there
     * are incoming device-to-device messages received.
     * @param messages the incoming messages.
     */
    @Override
    public void onMessagesReceived(@NonNull Set<Communicator.Message> messages) {
        Log.i(this, "onMessagesReceived: got d2d messages: %s", messages);
        // Send connection events up to Telecom so that we can relay the messages to a valid
        // CallDiagnosticService which is bound.
        for (Communicator.Message msg : messages) {
            Integer dcMsgType = MessageTypeAndValueHelper.MSG_TYPE_TO_DC_MSG_TYPE.getValue(
                    msg.getType());
            if (dcMsgType == null) {
                // Invalid msg type, skip.
                continue;
            }

            Integer dcMsgValue;
            switch (msg.getType()) {
                case CallDiagnostics.MESSAGE_CALL_AUDIO_CODEC:
                    dcMsgValue = MessageTypeAndValueHelper.CODEC_TO_DC_CODEC.getValue(
                            msg.getValue());
                    break;
                case CallDiagnostics.MESSAGE_CALL_NETWORK_TYPE:
                    dcMsgValue = MessageTypeAndValueHelper.RAT_TYPE_TO_DC_NETWORK_TYPE.getValue(
                            msg.getValue());
                    break;
                case CallDiagnostics.MESSAGE_DEVICE_BATTERY_STATE:
                    dcMsgValue = MessageTypeAndValueHelper.BATTERY_STATE_TO_DC_BATTERY_STATE
                            .getValue(msg.getValue());
                    break;
                case CallDiagnostics.MESSAGE_DEVICE_NETWORK_COVERAGE:
                    dcMsgValue = MessageTypeAndValueHelper.COVERAGE_TO_DC_COVERAGE
                            .getValue(msg.getValue());
                    break;
                default:
                    Log.w(this, "onMessagesReceived: msg=%d - invalid msg", msg.getValue());
                    continue;
            }
            if (dcMsgValue == null) {
                Log.w(this, "onMessagesReceived: msg=%d/%d - invalid msg value", msg.getType(),
                        msg.getValue());
                continue;
            }
            Bundle extras = new Bundle();
            extras.putInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_TYPE, dcMsgType);
            extras.putInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_VALUE, dcMsgValue);
            sendConnectionEvent(Connection.EVENT_DEVICE_TO_DEVICE_MESSAGE, extras);
        }
    }

    /**
     * Handles report from {@link Communicator} when the availability of D2D changes.
     * @param isAvailable {@code true} if D2D is available, {@code false} if unavailable.
     */
    @Override
    public void onD2DAvailabilitychanged(boolean isAvailable) {
        notifyD2DAvailabilityChanged(isAvailable);
    }

    /**
     * Called by a {@link ConnectionService} to notify Telecom that a {@link Conference#onMerge()}
     * operation has started.
     */
    protected void notifyConferenceStarted() {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onConferenceStarted();
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s when a change has occurred to the Connection
     * which impacts its ability to be a part of a conference call.
     * @param isConferenceSupported {@code true} if the connection supports being part of a
     *      conference call, {@code false} otherwise.
     */
    private void notifyConferenceSupportedChanged(boolean isConferenceSupported) {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onConferenceSupportedChanged(this, isConferenceSupported);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of changes to the connection capabilities.
     * @param newCapabilities the new capabilities.
     */
    private void notifyConnectionCapabilitiesChanged(int newCapabilities) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onConnectionCapabilitiesChanged(this, newCapabilities);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of changes to the connection properties.
     * @param newProperties the new properties.
     */
    private void notifyConnectionPropertiesChanged(int newProperties) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onConnectionPropertiesChanged(this, newProperties);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s when a connection is destroyed.
     */
    private void notifyDestroyed() {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onDestroyed(this);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s when a connection disconnects.
     * @param cause The disconnect cause.
     */
    private void notifyDisconnected(android.telecom.DisconnectCause cause) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onDisconnected(this, cause);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of connection state changes.
     * @param newState The new state.
     */
    private void notifyStateChanged(int newState) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onStateChanged(this, newState);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of telephony connection events.
     * @param event The event.
     * @param extras Any extras.
     */
    private void notifyTelephonyConnectionEvent(String event, Bundle extras) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onConnectionEvent(this, event, extras);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s when extras are added to the connection.
     * @param extras The new extras.
     */
    private void notifyPutExtras(Bundle extras) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onExtrasChanged(this, extras);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s when extra keys are removed from a connection.
     * @param keys The removed keys.
     */
    private void notifyRemoveExtras(List<String> keys) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onExtrasRemoved(this, keys);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of a change to the video state of a connection.
     * @param videoState The new video state. Valid values:
     *                   {@link VideoProfile#STATE_AUDIO_ONLY},
     *                   {@link VideoProfile#STATE_BIDIRECTIONAL},
     *                   {@link VideoProfile#STATE_TX_ENABLED},
     *                   {@link VideoProfile#STATE_RX_ENABLED}.
     */
    private void notifyVideoStateChanged(int videoState) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onVideoStateChanged(this, videoState);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of a whether to play Ringback Tone or not.
     * @param ringback Whether the ringback tone is to be played
     */
    private void notifyRingbackRequested(boolean ringback) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onRingbackRequested(this, ringback);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of changes to the video provider for a
     * connection.
     * @param videoProvider The new video provider.
     */
    private void notifyVideoProviderChanged(VideoProvider videoProvider) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onVideoProviderChanged(this, videoProvider);
        }
    }

    /**
     * Notifies {@link TelephonyConnectionListener}s of changes to the status hints for a
     * connection.
     * @param statusHints The new status hints.
     */
    private void notifyStatusHintsChanged(StatusHints statusHints) {
        for (TelephonyConnectionListener listener : mTelephonyListeners) {
            listener.onStatusHintsChanged(this, statusHints);
        }
    }

    /**
     * Whether the incoming call number should be formatted to national number for Japan.
     * @return {@code true} should be convert to the national format, {@code false} otherwise.
     */
    private boolean isNeededToFormatIncomingNumberForJp() {
        if (mOriginalConnection.isIncoming()
                && !TextUtils.isEmpty(mOriginalConnection.getAddress())
                && mOriginalConnection.getAddress().startsWith(JAPAN_COUNTRY_CODE_WITH_PLUS_SIGN)) {
            return getCarrierConfig().getBoolean(
                    CarrierConfigManager.KEY_FORMAT_INCOMING_NUMBER_TO_NATIONAL_FOR_JP_BOOL);
        }
        return false;
    }

    /**
     * Format the incoming call number to national number for Japan.
     * @param number
     * @return the formatted phone number (e.g, "+819012345678" -> "09012345678")
     */
    private String formatIncomingNumberForJp(String number) {
        return PhoneNumberUtils.stripSeparators(
                PhoneNumberUtils.formatNumber(number, JAPAN_ISO_COUNTRY_CODE));
    }

    public TelecomAccountRegistry getTelecomAccountRegistry(Context context) {
        return TelecomAccountRegistry.getInstance(context);
    }

    /**
     * @return {@code true} if the carrier supports D2D using RTP header extensions, {@code false}
     * otherwise.
     */
    private boolean supportsD2DUsingRtp() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_RTP_BOOL);
    }

    /**
     * @return {@code true} if the carrier supports D2D using DTMF digits, {@code false} otherwise.
     */
    private boolean supportsD2DUsingDtmf() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager.KEY_SUPPORTS_DEVICE_TO_DEVICE_COMMUNICATION_USING_DTMF_BOOL);
    }

    /**
     * @return {@code true} if the carrier supports using SDP negotiation for the RTP header
     * extensions used in D2D comms, {@code false} otherwise.
     */
    private boolean supportsSdpNegotiationOfRtpHeaderExtensions() {
        return getCarrierConfig().getBoolean(
                CarrierConfigManager
                        .KEY_SUPPORTS_SDP_NEGOTIATION_OF_D2D_RTP_HEADER_EXTENSIONS_BOOL);
    }

    /**
     * Handles a device to device message which a {@link CallDiagnostics} wishes to send.
     * @param extras the call event extras bundle.
     */
    private void handleOutgoingDeviceToDeviceMessage(Bundle extras) {
        int messageType = extras.getInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_TYPE);
        int messageValue = extras.getInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_VALUE);

        Integer internalMessageValue;
        switch (messageType) {
            case CallDiagnostics.MESSAGE_CALL_AUDIO_CODEC:
                internalMessageValue = MessageTypeAndValueHelper.CODEC_TO_DC_CODEC.getKey(
                        messageValue);
                break;
            case CallDiagnostics.MESSAGE_CALL_NETWORK_TYPE:
                internalMessageValue = MessageTypeAndValueHelper.RAT_TYPE_TO_DC_NETWORK_TYPE.getKey(
                        messageValue);
                break;
            case CallDiagnostics.MESSAGE_DEVICE_BATTERY_STATE:
                internalMessageValue = MessageTypeAndValueHelper.BATTERY_STATE_TO_DC_BATTERY_STATE
                        .getKey(messageValue);
                break;
            case CallDiagnostics.MESSAGE_DEVICE_NETWORK_COVERAGE:
                internalMessageValue = MessageTypeAndValueHelper.COVERAGE_TO_DC_COVERAGE
                        .getKey(messageValue);
                break;
            default:
                Log.w(this, "handleOutgoingDeviceToDeviceMessage: msg=%d - invalid msg",
                        messageType);
                return;
        }
        Integer internalMessageType = MessageTypeAndValueHelper.MSG_TYPE_TO_DC_MSG_TYPE.getKey(
                messageType);
        if (internalMessageValue == null) {
            Log.w(this, "handleOutgoingDeviceToDeviceMessage: msg=%d/%d - invalid value",
                    messageType, messageValue);
            return;
        }

        if (mCommunicator != null) {
            Log.w(this, "handleOutgoingDeviceToDeviceMessage: msg=%d/%d - sending",
                    internalMessageType, internalMessageValue);
            Set<Communicator.Message> set = new ArraySet<>();
            set.add(new Communicator.Message(internalMessageType, internalMessageValue));
            mCommunicator.sendMessages(set);
        }
    }

    /**
     * Returns the current telephony connection listeners for test purposes.
     * @return list of telephony connection listeners.
     */
    @VisibleForTesting
    public List<TelephonyConnectionListener> getTelephonyConnectionListeners() {
        return new ArrayList<>(mTelephonyListeners);
    }

    /**
     * @return An {@link Integer} instance of the emergency service category.
     */
    public @Nullable Integer getEmergencyServiceCategory() {
        return mEmergencyServiceCategory;
    }

    /**
     * Sets the emergency service category.
     *
     * @param eccCategory The emergency service category.
     */
    @VisibleForTesting
    public void setEmergencyServiceCategory(int eccCategory) {
        mEmergencyServiceCategory = eccCategory;
    }

    /**
     * @return a {@link List} of {@link String}s that are the emrgency URNs.
     */
    public @Nullable List<String> getEmergencyUrns() {
        return mEmergencyUrns;
    }

    /**
     * Set the emergency URNs.
     *
     * @param emergencyUrns The emergency URNs.
     */
    @VisibleForTesting
    public void setEmergencyUrns(@Nullable List<String> emergencyUrns) {
        mEmergencyUrns = emergencyUrns;
    }
}
