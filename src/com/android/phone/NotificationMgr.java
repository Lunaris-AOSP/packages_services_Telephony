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

import static android.Manifest.permission.READ_PHONE_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.RadioAccessFamily;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.FeatureFlagsImpl;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.phone.settings.VoicemailSettingsActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * NotificationManager-related utility code for the Phone app.
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneGlobals.notificationMgr
 */
public class NotificationMgr {
    private static final String LOG_TAG = NotificationMgr.class.getSimpleName();
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    // Do not check in with VDBG = true, since that may write PII to the system log.
    private static final boolean VDBG = false;

    private static final String MWI_SHOULD_CHECK_VVM_CONFIGURATION_KEY_PREFIX =
            "mwi_should_check_vvm_configuration_state_";

    // notification types
    static final int MMI_NOTIFICATION = 1;
    static final int NETWORK_SELECTION_NOTIFICATION = 2;
    static final int VOICEMAIL_NOTIFICATION = 3;
    static final int CALL_FORWARD_NOTIFICATION = 4;
    static final int DATA_ROAMING_NOTIFICATION = 5;
    static final int SELECTED_OPERATOR_FAIL_NOTIFICATION = 6;
    static final int LIMITED_SIM_FUNCTION_NOTIFICATION = 7;

    // Event for network selection notification.
    private static final int EVENT_PENDING_NETWORK_SELECTION_NOTIFICATION = 1;

    private static final long NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIME_IN_MS = 10000L;
    private static final int NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIMES = 10;

    private static final int STATE_UNKNOWN_SERVICE = -1;

    private static final String ACTION_MOBILE_NETWORK_LIST = "android.settings.MOBILE_NETWORK_LIST";

    /**
     * Grant recipients of new voicemail broadcasts a 10sec allowlist so they can start a background
     * service to do VVM processing.
     */
    private final long VOICEMAIL_ALLOW_LIST_DURATION_MILLIS = 10000L;

    /** The singleton NotificationMgr instance. */
    private static NotificationMgr sInstance;

    private PhoneGlobals mApp;

    private Context mContext;
    private StatusBarManager mStatusBarManager;
    private UserManager mUserManager;
    private Toast mToast;
    private SubscriptionManager mSubscriptionManager;
    private TelecomManager mTelecomManager;
    private TelephonyManager mTelephonyManager;

    // used to track the notification of selected network unavailable, per subscription id.
    private SparseArray<Boolean> mSelectedUnavailableNotify = new SparseArray<>();

    // used to track the notification of limited sim function under dual sim, per subscription id.
    private Set<Integer> mLimitedSimFunctionNotify = new HashSet<>();

    // used to track whether the message waiting indicator is visible, per subscription id.
    private ArrayMap<Integer, Boolean> mMwiVisible = new ArrayMap<Integer, Boolean>();
    // used to track the last broadcast sent to the dialer about the MWI, per sub id.
    private ArrayMap<Integer, Integer> mLastMwiCountSent = new ArrayMap<Integer, Integer>();

    // those flags are used to track whether to show network selection notification or not.
    private SparseArray<Integer> mPreviousServiceState = new SparseArray<>();
    private SparseArray<Long> mOOSTimestamp = new SparseArray<>();
    private SparseArray<Integer> mPendingEventCounter = new SparseArray<>();
    // maps each subId to selected network operator name.
    private SparseArray<String> mSelectedNetworkOperatorName = new SparseArray<>();

    // feature flags
    private final FeatureFlags mFeatureFlags;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_PENDING_NETWORK_SELECTION_NOTIFICATION:
                    int subId = (int) msg.obj;
                    TelephonyManager telephonyManager =
                            mTelephonyManager.createForSubscriptionId(subId);
                    if (telephonyManager.getServiceState() != null) {
                        shouldShowNotification(telephonyManager.getServiceState().getState(),
                                subId);
                    }
                    break;
            }
        }
    };

    /**
     * Private constructor (this is a singleton).
     * @see #init(PhoneGlobals)
     */
    @VisibleForTesting
    /* package */ NotificationMgr(PhoneGlobals app) {
        mApp = app;
        mContext = app;
        mStatusBarManager =
                (StatusBarManager) app.getSystemService(Context.STATUS_BAR_SERVICE);
        mUserManager = (UserManager) app.getSystemService(Context.USER_SERVICE);
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mTelecomManager = app.getSystemService(TelecomManager.class);
        mTelephonyManager = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
        mFeatureFlags = new FeatureFlagsImpl();
    }

    /**
     * Initialize the singleton NotificationMgr instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static NotificationMgr init(PhoneGlobals app) {
        synchronized (NotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new NotificationMgr(app);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** The projection to use when querying the phones table */
    static final String[] PHONES_PROJECTION = new String[] {
        PhoneLookup.NUMBER,
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup._ID
    };

    /**
     * Re-creates the message waiting indicator (voicemail) notification if it is showing.  Used to
     * refresh the voicemail intent on the indicator when the user changes it via the voicemail
     * settings screen.  The voicemail notification sound is suppressed.
     *
     * @param subId The subscription Id.
     */
    /* package */ void refreshMwi(int subId) {
        // In a single-sim device, subId can be -1 which means "no sub id".  In this case we will
        // reference the single subid stored in the mMwiVisible map.
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (mMwiVisible.keySet().size() == 1) {
                Set<Integer> keySet = mMwiVisible.keySet();
                Iterator<Integer> keyIt = keySet.iterator();
                if (!keyIt.hasNext()) {
                    return;
                }
                subId = keyIt.next();
            }
        }
        if (mMwiVisible.containsKey(subId)) {
            boolean mwiVisible = mMwiVisible.get(subId);
            if (mwiVisible) {
                mApp.notifier.updatePhoneStateListeners(true);
            }
        }
    }

    public void setShouldCheckVisualVoicemailConfigurationForMwi(int subId, boolean enabled) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.e(LOG_TAG, "setShouldCheckVisualVoicemailConfigurationForMwi: invalid subId"
                    + subId);
            return;
        }

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putBoolean(MWI_SHOULD_CHECK_VVM_CONFIGURATION_KEY_PREFIX + subId, enabled)
                .apply();
    }

    private boolean shouldCheckVisualVoicemailConfigurationForMwi(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.e(LOG_TAG, "shouldCheckVisualVoicemailConfigurationForMwi: invalid subId" + subId);
            return true;
        }
        return PreferenceManager
                .getDefaultSharedPreferences(mContext)
                .getBoolean(MWI_SHOULD_CHECK_VVM_CONFIGURATION_KEY_PREFIX + subId, true);
    }
    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateMwi(int subId, boolean visible) {
        updateMwi(subId, visible, false /* isRefresh */);
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     * See also {@link CallNotifier#updatePhoneStateListeners(boolean)} for more background.
     * Okay, lets get started; time for a code adventure.
     * This method (unfortunately) serves double-duty for updating the message waiting indicator
     * when either: the "subscription info" changes (could be due to the carrier name loading, the
     * current phase of the moon, etc) -- this is considered a "refresh"; or due to a direct signal
     * from the network that indeed the voicemail indicator has changed state.  Note that although
     * {@link android.telephony.ims.feature.MmTelFeature.Listener#onVoiceMessageCountUpdate(int)}
     * gives us an actual COUNT of the messages, that information is unfortunately lost in the
     * current design -- that's a design issue left for another day.  Note; it is ALSO possible to
     * get updates through SMS via {@code GsmInboundSmsHandler#updateMessageWaitingIndicator(int)},
     * and that seems to generally not include a count.
     * <p>
     * There are two ways the message waiting indicator can be notified to the user; either directly
     * here by posting a notification, or via the default dialer.
     * <p>
     * The {@code isRefresh} == false case is pretty intuitive.  The network told us something
     * changed, so we should notify the user.
     * <p>
     * The {@code isRefresh} == true case is unfortunately not very intuitive.  While the device is
     * booting up, we'll actually get a callback from the ImsService telling us the state of the
     * MWI indication, BUT at that point in time it's possible the sim records won't have loaded so
     * code below will return early -- the voicemail notification is supposed to include the user's
     * voicemail number, so we have to defer posting the notification or notifying the dialer until
     * later on.  That's where the refreshes are handy; once the voicemail number is known, we'll
     * get a refresh and have an opportunity to notify the user at this point.
     * @param subId the subId to update where {@code isRefresh} is false,
     *              or {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if {@code isRefresh} is
     *              true.
     * @param visible {@code true} if there are messages waiting, or {@code false} if there are not.
     * @param isRefresh {@code true} if this is a refresh triggered by a subscription change,
     *                              {@code false} if this is an update based on actual network
     *                              signalling.
     */
    void updateMwi(int subId, boolean visible, boolean isRefresh) {
        if (!PhoneGlobals.sVoiceCapable) {
            // Do not show the message waiting indicator on devices which are not voice capable.
            // These events *should* be blocked at the telephony layer for such devices.
            Log.w(LOG_TAG, "Called updateMwi() on non-voice-capable device! Ignoring...");
            return;
        }

        Phone phone = PhoneGlobals.getPhone(subId);
        // --
        // Note: This is all done just so that we can have a better log of what is going on here.
        // The state changes are unfortunately not so intuitive.
        boolean wasPrevStateKnown = mMwiVisible.containsKey(subId);
        boolean wasVisible = wasPrevStateKnown && mMwiVisible.get(subId);
        mMwiVisible.put(subId, visible);
        boolean mwiStateChanged = !wasPrevStateKnown || wasVisible != visible;
        // --

        Log.i(LOG_TAG, "updateMwi(): subId:" + subId + " isRefresh:" + isRefresh + " state:"
                + (wasPrevStateKnown ? (wasVisible ? "Y" : "N") : "unset") + "->"
                + (visible ? "Y" : "N")
                + " (changed:" + (mwiStateChanged ? "Y" : "N") + ")");

        if (visible) {
            if (phone == null) {
                Log.w(LOG_TAG, "Found null phone for: " + subId);
                return;
            }

            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                Log.w(LOG_TAG, "Found null subscription info for: " + subId);
                return;
            }

            int resId = android.R.drawable.stat_notify_voicemail;
            if (mTelephonyManager.getPhoneCount() > 1) {
                resId = (phone.getPhoneId() == 0) ? R.drawable.stat_notify_voicemail_sub1
                        : R.drawable.stat_notify_voicemail_sub2;
            }

            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = phone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'");

            // The voicemail number may be null because:
            //   (1) This phone has no voicemail number.
            //   (2) This phone has a voicemail number, but the SIM isn't ready yet. This may
            //       happen when the device first boots if we get a MWI notification when we
            //       register on the network before the SIM has loaded. In this case, the
            //       SubscriptionListener in CallNotifier will update this once the SIM is loaded.
            if ((vmNumber == null) && !phone.getIccRecordsLoaded()) {
                Log.i(LOG_TAG, "updateMwi - Null vm number: SIM records not loaded (yet)...");
                return;
            }

            // Pay attention here; vmCount is an Integer, not an int.  This is because:
            // vmCount == null - means there are voicemail messages waiting.
            // vmCount == 0 - means there are no voicemail messages waiting.
            // vmCount > 0 - means there are a specific number of voicemail messages waiting.
            // Awesome.
            Integer vmCount = null;

            // TODO: This should be revisited; in the IMS case, the network tells us a count, so
            // it is strange to stash it and then retrieve it here instead of just passing it.
            if (TelephonyCapabilities.supportsVoiceMessageCount(phone)) {
                vmCount = phone.getVoiceMessageCount();
                String titleFormat = mContext.getString(R.string.notification_voicemail_title_count);
                notificationTitle = String.format(titleFormat, vmCount);
            }

            // This pathway only applies to PSTN accounts; only SIMS have subscription ids.
            PhoneAccountHandle phoneAccountHandle = PhoneUtils.makePstnPhoneAccountHandle(phone);

            Intent intent;
            String notificationText;
            boolean isSettingsIntent = TextUtils.isEmpty(vmNumber);

            if (isSettingsIntent) {
                // If the voicemail number if unknown, instead of calling voicemail, take the user
                // to the voicemail settings.
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);
                intent = new Intent(VoicemailSettingsActivity.ACTION_ADD_VOICEMAIL);
                intent.putExtra(SubscriptionInfoHelper.SUB_ID_EXTRA, subId);
                intent.setClass(mContext, VoicemailSettingsActivity.class);
            } else {
                if (mTelephonyManager.getPhoneCount() > 1) {
                    notificationText = subInfo.getDisplayName().toString();
                } else {
                    notificationText = String.format(
                            mContext.getString(R.string.notification_voicemail_text_format),
                            PhoneNumberUtils.formatNumber(vmNumber));
                }
                intent = new Intent(
                        Intent.ACTION_CALL, Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "",
                                null));
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
            }
            PendingIntent pendingIntent;
            UserHandle subAssociatedUserHandle =
                    mSubscriptionManager.getSubscriptionUserHandle(subId);
            if (subAssociatedUserHandle == null) {
                pendingIntent = PendingIntent.getActivity(mContext, subId /* requestCode */, intent,
                        PendingIntent.FLAG_IMMUTABLE);
            } else {
                pendingIntent = PendingIntent.getActivityAsUser(mContext, subId /* requestCode */,
                        intent, PendingIntent.FLAG_IMMUTABLE, null, subAssociatedUserHandle);
            }

            Resources res = mContext.getResources();
            PersistableBundle carrierConfig = PhoneGlobals.getInstance().getCarrierConfigForSubId(
                    subId);
            Notification.Builder builder = new Notification.Builder(mContext);
            builder.setSmallIcon(resId)
                    .setWhen(System.currentTimeMillis())
                    .setColor(subInfo.getIconTint())
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setContentIntent(pendingIntent)
                    .setColor(res.getColor(R.color.dialer_theme_color))
                    .setOngoing(carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_VOICEMAIL_NOTIFICATION_PERSISTENT_BOOL))
                    .setChannelId(NotificationChannelController.CHANNEL_ID_VOICE_MAIL)
                    .setOnlyAlertOnce(isRefresh);

            final Notification notification = builder.build();
            List<UserHandle> users = getUsersExcludeDying();
            for (UserHandle userHandle : users) {
                boolean isProfile = mUserManager.isProfile(userHandle.getIdentifier());
                if (!hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS, userHandle)
                        && (userHandle.equals(subAssociatedUserHandle)
                            || (subAssociatedUserHandle == null && !isProfile))
                        && !maybeSendVoicemailNotificationUsingDefaultDialer(phone, vmCount,
                        vmNumber, pendingIntent, isSettingsIntent, userHandle, isRefresh)) {
                    Log.i(LOG_TAG, "updateMwi: notify userHandle=" + userHandle);
                    notifyAsUser(
                            Integer.toString(subId) /* tag */,
                            VOICEMAIL_NOTIFICATION,
                            notification,
                            userHandle);
                }
            }
        } else {
            UserHandle subAssociatedUserHandle =
                    mSubscriptionManager.getSubscriptionUserHandle(subId);
            List<UserHandle> users = getUsersExcludeDying();
            for (UserHandle userHandle : users) {
                boolean isProfile = mUserManager.isProfile(userHandle.getIdentifier());
                if (!hasUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS, userHandle)
                        && (userHandle.equals(subAssociatedUserHandle)
                            || (subAssociatedUserHandle == null && !isProfile))
                        && !maybeSendVoicemailNotificationUsingDefaultDialer(phone, 0, null, null,
                        false, userHandle, isRefresh)) {
                    Log.i(LOG_TAG, "notifyMwi: cancel userHandle=" + userHandle);
                    cancelAsUser(
                            Integer.toString(subId) /* tag */,
                            VOICEMAIL_NOTIFICATION,
                            userHandle);
                }
            }
        }
    }

    private List<UserHandle> getUsersExcludeDying() {
        long[] serialNumbersOfUsers =
                mUserManager.getSerialNumbersOfUsers(/* excludeDying= */ true);
        List<UserHandle> users = new ArrayList<>(serialNumbersOfUsers.length);
        for (long serialNumber : serialNumbersOfUsers) {
            UserHandle userHandle = mUserManager.getUserForSerialNumber(serialNumber);
            if (userHandle != null) {
                users.add(userHandle);
            }
        }
        return users;
    }

    private boolean hasUserRestriction(String restrictionKey, UserHandle userHandle) {
        final List<UserManager.EnforcingUser> sources = mUserManager
                .getUserRestrictionSources(restrictionKey, userHandle);
        return (sources != null && !sources.isEmpty());
    }

    /**
     * Sends a broadcast with the voicemail notification information to the default dialer. This
     * method is also used to indicate to the default dialer when to clear the
     * notification. A pending intent can be passed to the default dialer to indicate an action to
     * be taken as it would by a notification produced in this class.
     * @param phone The phone the notification is sent from
     * @param count The number of pending voicemail messages to indicate on the notification. A
     *              Value of 0 is passed here to indicate that the notification should be cleared.
     * @param number The voicemail phone number if specified.
     * @param pendingIntent The intent that should be passed as the action to be taken.
     * @param isSettingsIntent {@code true} to indicate the pending intent is to launch settings.
     *                         otherwise, {@code false} to indicate the intent launches voicemail.
     * @param userHandle The user to receive the notification. Each user can have their own default
     *                   dialer.
     * @return {@code true} if the default was notified of the notification.
     */
    private boolean maybeSendVoicemailNotificationUsingDefaultDialer(Phone phone, Integer count,
            String number, PendingIntent pendingIntent, boolean isSettingsIntent,
            UserHandle userHandle, boolean isRefresh) {

        if (shouldManageNotificationThroughDefaultDialer(userHandle)) {
            int subId = phone.getSubId();
            // We want to determine if the count of voicemails that we notified to the dialer app
            // has changed or not.  mLastMwiCountSent will initially contain no entry for a subId
            // meaning no count was ever sent to dialer.  The previous count is an Integer (not int)
            // because the caller of maybeSendVoicemailNotificationUsingDefaultDialer will either
            // send an instance of Integer with an actual number or "null" if the count isn't known.
            // See the docs on updateMwi to get more flavor on this lovely logic.
            // The end result here is we want to know if the "count" we last sent to the dialer for
            // a sub has changed or not; this will play into whether we want to actually send the
            // broadcast or not.
            boolean wasCountSentYet = mLastMwiCountSent.containsKey(subId);
            Integer previousCount = wasCountSentYet ? mLastMwiCountSent.get(subId) : null;
            boolean didCountChange = !wasCountSentYet || !Objects.equals(previousCount, count);
            mLastMwiCountSent.put(subId, count);

            Log.i(LOG_TAG,
                    "maybeSendVoicemailNotificationUsingDefaultDialer: count: " + (wasCountSentYet
                            ? previousCount : "undef") + "->" + count + " (changed="
                            + didCountChange + ")");

            Intent intent = getShowVoicemailIntentForDefaultDialer(userHandle);

            /**
             * isRefresh == true means that we're rebroadcasting because of an
             * onSubscriptionsChanged callback -- that happens a LOT at boot up.  isRefresh == false
             * happens when TelephonyCallback#onMessageWaitingIndicatorChanged is triggered in
             * CallNotifier.  It's important to note that that count may either be an actual number,
             * or "we don't know" because the modem doesn't know the actual count.  Hence anytime
             * TelephonyCallback#onMessageWaitingIndicatorChanged occurs, we have to sent the
             * broadcast even if the count didn't actually change.
             */
            if (!didCountChange && isRefresh) {
                Log.i(LOG_TAG, "maybeSendVoicemailNotificationUsingDefaultDialer: skip bcast to:"
                        + intent.getPackage() + ", user:" + userHandle);
                // It's "technically" being sent through the dialer, but we just skipped that so
                // still return true so we don't post a notification.
                return true;
            }

            intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.setAction(TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION);
            intent.putExtra(TelephonyManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                    PhoneUtils.makePstnPhoneAccountHandle(phone));
            intent.putExtra(TelephonyManager.EXTRA_IS_REFRESH, isRefresh);
            if (count != null) {
                intent.putExtra(TelephonyManager.EXTRA_NOTIFICATION_COUNT, count);
            }

            // Additional information about the voicemail notification beyond the count is only
            // present when the count not specified or greater than 0. The value of 0 represents
            // clearing the notification, which does not require additional information.
            if (count == null || count > 0) {
                if (!TextUtils.isEmpty(number)) {
                    intent.putExtra(TelephonyManager.EXTRA_VOICEMAIL_NUMBER, number);
                }

                if (pendingIntent != null) {
                    intent.putExtra(isSettingsIntent
                            ? TelephonyManager.EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT
                            : TelephonyManager.EXTRA_CALL_VOICEMAIL_INTENT,
                            pendingIntent);
                }
            }

            BroadcastOptions bopts = BroadcastOptions.makeBasic();
            bopts.setTemporaryAppWhitelistDuration(VOICEMAIL_ALLOW_LIST_DURATION_MILLIS);

            Log.i(LOG_TAG, "maybeSendVoicemailNotificationUsingDefaultDialer: send via Dialer:"
                    + intent.getPackage() + ", user:" + userHandle);
            mContext.sendBroadcastAsUser(intent, userHandle, READ_PHONE_STATE,
                    bopts.toBundle());
            return true;
        }
        Log.i(LOG_TAG, "maybeSendVoicemailNotificationUsingDefaultDialer: not using dialer ; user:"
                + userHandle);

        return false;
    }

    private Intent getShowVoicemailIntentForDefaultDialer(UserHandle userHandle) {
        String dialerPackage = mContext.getSystemService(TelecomManager.class)
                .getDefaultDialerPackage(userHandle);
        return new Intent(TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION)
                .setPackage(dialerPackage);
    }

    private boolean shouldManageNotificationThroughDefaultDialer(UserHandle userHandle) {
        Intent intent = getShowVoicemailIntentForDefaultDialer(userHandle);
        if (intent == null) {
            return false;
        }

        List<ResolveInfo> receivers;
        if (mFeatureFlags.hsumPackageManager()) {
            receivers = mContext.createContextAsUser(userHandle, 0)
                    .getPackageManager().queryBroadcastReceivers(intent, 0);
        } else {
            receivers = mContext.getPackageManager()
                    .queryBroadcastReceivers(intent, 0);
        }
        return receivers.size() > 0;
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if call forwarding enabled
     */

     /* package */ void updateCfi(int subId, boolean visible) {
        updateCfi(subId, visible, false /* isRefresh */);
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if call forwarding enabled
     */
    /* package */ void updateCfi(int subId, boolean visible, boolean isRefresh) {
        logi("updateCfi: subId= " + subId + ", visible=" + (visible ? "Y" : "N"));
        if (visible) {
            // If Unconditional Call Forwarding (forward all calls) for VOICE
            // is enabled, just show a notification.  We'll default to expanded
            // view for now, so the there is less confusion about the icon.  If
            // it is deemed too weird to have CF indications as expanded views,
            // then we'll flip the flag back.

            // TODO: We may want to take a look to see if the notification can
            // display the target to forward calls to.  This will require some
            // effort though, since there are multiple layers of messages that
            // will need to propagate that information.

            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                Log.w(LOG_TAG, "Found null subscription info for: " + subId);
                return;
            }

            String notificationTitle;
            int resId = R.drawable.stat_sys_phone_call_forward;
            if (mTelephonyManager.getPhoneCount() > 1) {
                int slotId = SubscriptionManager.getSlotIndex(subId);
                resId = (slotId == 0) ? R.drawable.stat_sys_phone_call_forward_sub1
                        : R.drawable.stat_sys_phone_call_forward_sub2;
                if (subInfo.getDisplayName() != null) {
                    notificationTitle = subInfo.getDisplayName().toString();
                } else {
                    notificationTitle = mContext.getString(R.string.labelCF);
                }
            } else {
                notificationTitle = mContext.getString(R.string.labelCF);
            }

            Notification.Builder builder = new Notification.Builder(mContext)
                    .setSmallIcon(resId)
                    .setColor(subInfo.getIconTint())
                    .setContentTitle(notificationTitle)
                    .setContentText(mContext.getString(R.string.sum_cfu_enabled_indicator))
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setChannelId(NotificationChannelController.CHANNEL_ID_CALL_FORWARD)
                    .setOnlyAlertOnce(isRefresh);

            Intent intent = new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            SubscriptionInfoHelper.addExtrasToIntent(
                    intent, mSubscriptionManager.getActiveSubscriptionInfo(subId));
            builder.setContentIntent(PendingIntent.getActivity(mContext, subId /* requestCode */,
                    intent, PendingIntent.FLAG_IMMUTABLE));
            notifyAsUser(
                    Integer.toString(subId) /* tag */,
                    CALL_FORWARD_NOTIFICATION,
                    builder.build(),
                    UserHandle.ALL);
        } else {
            List<UserHandle> users = getUsersExcludeDying();
            for (UserHandle user : users) {
                if (mUserManager.isManagedProfile(user.getIdentifier())) {
                    continue;
                }
                cancelAsUser(
                        Integer.toString(subId) /* tag */,
                        CALL_FORWARD_NOTIFICATION,
                        user);
            }
        }
    }

    /**
     * Shows either:
     * 1) the "Data roaming is on" notification, which
     * appears when you're roaming and you have the "data roaming" feature turned on for the
     * given {@code subId}.
     * or
     * 2) the "data disconnected due to roaming" notification, which
     * appears when you lose data connectivity because you're roaming and
     * you have the "data roaming" feature turned off for the given {@code subId}.
     * @param subId which subscription it's notifying about.
     * @param roamingOn whether currently roaming is on or off. If true, we show notification
     *                  1) above; else we show notification 2).
     */
    /* package */ void showDataRoamingNotification(int subId, boolean roamingOn) {
        if (DBG) {
            log("showDataRoamingNotification() roaming " + (roamingOn ? "on" : "off")
                    + " on subId " + subId);
        }

        // "Mobile network settings" screen / dialog
        Intent intent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
        intent.putExtra(Settings.EXTRA_SUB_ID, subId);
        PendingIntent contentIntent = PendingIntent.getActivity(
                mContext, subId, intent, PendingIntent.FLAG_IMMUTABLE);

        CharSequence contentTitle = mContext.getText(roamingOn
                ? R.string.roaming_on_notification_title
                : R.string.roaming_notification_title);
        CharSequence contentText = mContext.getText(roamingOn
                ? R.string.roaming_enabled_message
                : R.string.roaming_reenable_message);

        final Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(contentTitle)
                .setColor(mContext.getResources().getColor(R.color.dialer_theme_color))
                .setContentText(contentText)
                .setChannelId(NotificationChannelController.CHANNEL_ID_MOBILE_DATA_STATUS)
                .setContentIntent(contentIntent);
        final Notification notif =
                new Notification.BigTextStyle(builder).bigText(contentText).build();
        notifyAsUser(null /* tag */, DATA_ROAMING_NOTIFICATION, notif, UserHandle.ALL);
    }

    private void notifyAsUser(String tag, int id, Notification notification, UserHandle user) {
        try {
            Context contextForUser =
                    mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
            NotificationManager notificationManager =
                    (NotificationManager) contextForUser.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            notificationManager.notify(tag, id, notification);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "unable to notify for user " + user);
            e.printStackTrace();
        }
    }

    private void cancelAsUser(String tag, int id, UserHandle user) {
        try {
            Context contextForUser =
                    mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
            NotificationManager notificationManager =
                    (NotificationManager) contextForUser.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(tag, id);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "unable to cancel for user " + user);
            e.printStackTrace();
        }
    }

    /**
     * Turns off the "data disconnected due to roaming" or "Data roaming is on" notification.
     */
    /* package */ void hideDataRoamingNotification() {
        if (DBG) log("hideDataRoamingNotification()...");
        cancelAsUser(null, DATA_ROAMING_NOTIFICATION, UserHandle.ALL);
    }

    /**
     * Shows the "Limited SIM functionality" warning notification, which appears when using a
     * special carrier under dual sim. limited function applies for DSDS in general when two SIM
     * cards share a single radio, thus the voice & data maybe impaired under certain scenarios.
     */
    public void showLimitedSimFunctionWarningNotification(int subId, @Nullable String carrierName) {
        if (DBG) log("showLimitedSimFunctionWarningNotification carrier: " + carrierName
                + " subId: " + subId);
        if (mLimitedSimFunctionNotify.contains(subId)) {
            // handle the case that user swipe the notification but condition triggers
            // frequently which cause the same notification consistently displayed.
            if (DBG) log("showLimitedSimFunctionWarningNotification, "
                    + "not display again if already displayed");
            return;
        }
        // Navigate to "Network Selection Settings" which list all subscriptions.
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(ACTION_MOBILE_NETWORK_LIST), PendingIntent.FLAG_IMMUTABLE);
        // Display phone number from the other sub
        String line1Num = null;
        SubscriptionManager subMgr = (SubscriptionManager) mContext.getSystemService(
            Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        List<SubscriptionInfo> subList = subMgr.getActiveSubscriptionInfoList(false);
        for (SubscriptionInfo sub : subList) {
            if (sub.getSubscriptionId() != subId) {
                line1Num = mTelephonyManager.getLine1Number(sub.getSubscriptionId());
            }
        }
        final CharSequence contentText = TextUtils.isEmpty(line1Num) ?
            String.format(mContext.getText(
                R.string.limited_sim_function_notification_message).toString(), carrierName) :
            String.format(mContext.getText(
                R.string.limited_sim_function_with_phone_num_notification_message).toString(),
                carrierName, line1Num);
        final Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_sim_card)
                .setContentTitle(mContext.getText(
                        R.string.limited_sim_function_notification_title))
                .setContentText(contentText)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setChannelId(NotificationChannelController.CHANNEL_ID_SIM_HIGH_PRIORITY)
                .setContentIntent(contentIntent);
        final Notification notification = new Notification.BigTextStyle(builder).bigText(
                contentText).build();

        notifyAsUser(Integer.toString(subId),
                LIMITED_SIM_FUNCTION_NOTIFICATION,
                notification, UserHandle.ALL);
        mLimitedSimFunctionNotify.add(subId);
    }

    /**
     * Dismiss the "Limited SIM functionality" warning notification for the given subId.
     */
    public void dismissLimitedSimFunctionWarningNotification(int subId) {
        if (DBG) log("dismissLimitedSimFunctionWarningNotification subId: " + subId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // dismiss all notifications
            for (int id : mLimitedSimFunctionNotify) {
                cancelAsUser(Integer.toString(id),
                        LIMITED_SIM_FUNCTION_NOTIFICATION, UserHandle.ALL);
            }
            mLimitedSimFunctionNotify.clear();
        } else if (mLimitedSimFunctionNotify.contains(subId)) {
            cancelAsUser(Integer.toString(subId),
                    LIMITED_SIM_FUNCTION_NOTIFICATION, UserHandle.ALL);
            mLimitedSimFunctionNotify.remove(subId);
        }
    }

    /**
     * Dismiss the "Limited SIM functionality" warning notification for all inactive subscriptions.
     */
    public void dismissLimitedSimFunctionWarningNotificationForInactiveSubs() {
        if (DBG) log("dismissLimitedSimFunctionWarningNotificationForInactiveSubs");
        // dismiss notification for inactive subscriptions.
        // handle the corner case that SIM change by SIM refresh doesn't clear the notification
        // from the old SIM if both old & new SIM configured to display the notification.
        mLimitedSimFunctionNotify.removeIf(id -> {
            if (!mSubscriptionManager.isActiveSubId(id)) {
                cancelAsUser(Integer.toString(id),
                        LIMITED_SIM_FUNCTION_NOTIFICATION, UserHandle.ALL);
                return true;
            }
            return false;
        });
    }

    /**
     * Display the network selection "no service" notification
     * @param operator is the numeric operator number
     * @param subId is the subscription ID
     */
    private void showNetworkSelection(String operator, int subId) {
        if (DBG) log("showNetworkSelection(" + operator + ")...");

        if (!TextUtils.isEmpty(operator)) {
            operator = String.format(" (%s)", operator);
        }
        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(mContext.getString(R.string.notification_network_selection_title))
                .setContentText(
                        mContext.getString(R.string.notification_network_selection_text, operator))
                .setShowWhen(false)
                .setOngoing(true)
                .setChannelId(NotificationChannelController.CHANNEL_ID_ALERT);

        // create the target network operators settings intent
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // Use MobileNetworkSettings to handle the selection intent
        intent.setComponent(new ComponentName(
                mContext.getString(R.string.mobile_network_settings_package),
                mContext.getString(R.string.mobile_network_settings_class)));
        intent.putExtra(Settings.EXTRA_SUB_ID, subId);
        builder.setContentIntent(
                PendingIntent.getActivity(mContext, subId, intent, PendingIntent.FLAG_IMMUTABLE));
        notifyAsUser(
                Integer.toString(subId) /* tag */,
                SELECTED_OPERATOR_FAIL_NOTIFICATION,
                builder.build(),
                UserHandle.ALL);
        mSelectedUnavailableNotify.put(subId, true);
    }

    /**
     * Turn off the network selection "no service" notification
     */
    private void cancelNetworkSelection(int subId) {
        if (DBG) log("cancelNetworkSelection()...");
        cancelAsUser(
                Integer.toString(subId) /* tag */, SELECTED_OPERATOR_FAIL_NOTIFICATION,
                UserHandle.ALL);
    }

    /**
     * Update notification about no service of user selected operator
     *
     * @param serviceState Phone service state
     * @param subId The subscription ID
     */
    void updateNetworkSelection(int serviceState, int subId) {
        // for dismissNetworkSelectionNotificationOnSimDisable feature enabled.
        int phoneId = SubscriptionManager.getPhoneId(subId);
        Phone phone = SubscriptionManager.isValidPhoneId(phoneId) ?
                PhoneFactory.getPhone(phoneId) : PhoneFactory.getDefaultPhone();
        if (TelephonyCapabilities.supportsNetworkSelection(phone)) {
            if (SubscriptionManager.isValidSubscriptionId(subId)
                    && mSubscriptionManager.isActiveSubId(subId)) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
                String selectedNetworkOperatorName =
                        sp.getString(Phone.NETWORK_SELECTION_NAME_KEY + subId, "");
                // get the shared preference of network_selection.
                // empty is auto mode, otherwise it is the operator alpha name
                // in case there is no operator name, check the operator numeric
                if (TextUtils.isEmpty(selectedNetworkOperatorName)) {
                    selectedNetworkOperatorName =
                            sp.getString(Phone.NETWORK_SELECTION_KEY + subId, "");
                }
                boolean isManualSelection;
                // if restoring manual selection is controlled by framework, then get network
                // selection from shared preference, otherwise get from real network indicators.
                boolean restoreSelection = !mContext.getResources().getBoolean(
                        com.android.internal.R.bool.skip_restoring_network_selection);
                if (restoreSelection) {
                    isManualSelection = !TextUtils.isEmpty(selectedNetworkOperatorName);
                } else {
                    isManualSelection = phone.getServiceStateTracker().mSS.getIsManualSelection();
                }

                if (DBG) {
                    log("updateNetworkSelection()..." + "state = " + serviceState + " new network "
                            + (isManualSelection ? selectedNetworkOperatorName : ""));
                }

                if (isManualSelection
                        && isSubscriptionVisibleToUser(
                              mSubscriptionManager.getActiveSubscriptionInfo(subId))) {
                    mSelectedNetworkOperatorName.put(subId, selectedNetworkOperatorName);
                    shouldShowNotification(serviceState, subId);
                } else {
                    dismissNetworkSelectionNotification(subId);
                    clearUpNetworkSelectionNotificationParam(subId);
                }
            } else {
                if (DBG) {
                    log("updateNetworkSelection()... state = " + serviceState
                            + " not updating network due to invalid subId " + subId);
                }
                dismissNetworkSelectionNotificationForInactiveSubId();
            }
        }
    }

    /**
     * Update notification about no service of user selected operator.
     * For dismissNetworkSelectionNotificationOnSimDisable feature disabled.
     *
     * @param serviceState Phone service state
     * @param subId The subscription ID
     */
    private void updateNetworkSelectionForFeatureDisabled(int serviceState, int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        Phone phone = SubscriptionManager.isValidPhoneId(phoneId)
                ? PhoneFactory.getPhone(phoneId) : PhoneFactory.getDefaultPhone();
        if (TelephonyCapabilities.supportsNetworkSelection(phone)) {
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
                String selectedNetworkOperatorName =
                        sp.getString(Phone.NETWORK_SELECTION_NAME_KEY + subId, "");
                // get the shared preference of network_selection.
                // empty is auto mode, otherwise it is the operator alpha name
                // in case there is no operator name, check the operator numeric
                if (TextUtils.isEmpty(selectedNetworkOperatorName)) {
                    selectedNetworkOperatorName =
                            sp.getString(Phone.NETWORK_SELECTION_KEY + subId, "");
                }
                boolean isManualSelection;
                // if restoring manual selection is controlled by framework, then get network
                // selection from shared preference, otherwise get from real network indicators.
                boolean restoreSelection = !mContext.getResources().getBoolean(
                        com.android.internal.R.bool.skip_restoring_network_selection);
                if (restoreSelection) {
                    isManualSelection = !TextUtils.isEmpty(selectedNetworkOperatorName);
                } else {
                    isManualSelection = phone.getServiceStateTracker().mSS.getIsManualSelection();
                }

                if (DBG) {
                    log("updateNetworkSelection()..." + "state = " + serviceState + " new network "
                            + (isManualSelection ? selectedNetworkOperatorName : ""));
                }

                if (isManualSelection
                        && isSubscriptionVisibleToUser(
                              mSubscriptionManager.getActiveSubscriptionInfo(subId))) {
                    mSelectedNetworkOperatorName.put(subId, selectedNetworkOperatorName);
                    shouldShowNotification(serviceState, subId);
                } else {
                    dismissNetworkSelectionNotification(subId);
                    clearUpNetworkSelectionNotificationParam(subId);
                }
            } else {
                if (DBG) log("updateNetworkSelection()..." + "state = " +
                        serviceState + " not updating network due to invalid subId " + subId);
                dismissNetworkSelectionNotificationForInactiveSubId();
            }
        }
    }

    // TODO(b/261916533) This should be handled by SubscriptionManager#isSubscriptionVisible(),
    // but that method doesn't support system callers, so here we are.
    private boolean isSubscriptionVisibleToUser(SubscriptionInfo subInfo) {
        return subInfo != null && (!subInfo.isOpportunistic() || subInfo.getGroupUuid() == null);
    }

    private void dismissNetworkSelectionNotification(int subId) {
        if (mSelectedUnavailableNotify.get(subId, false)) {
            cancelNetworkSelection(subId);
            mSelectedUnavailableNotify.remove(subId);
        }
    }

    /**
     * Dismiss the network selection "no service" notification for all inactive subscriptions.
     */
    public void dismissNetworkSelectionNotificationForInactiveSubId() {
        for (int i = 0; i < mSelectedUnavailableNotify.size(); i++) {
            int subId = mSelectedUnavailableNotify.keyAt(i);
            if (!mSubscriptionManager.isActiveSubId(subId)) {
                dismissNetworkSelectionNotification(subId);
                clearUpNetworkSelectionNotificationParam(subId);
            }
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private void logi(String msg) {
        Log.i(LOG_TAG, msg);
    }

    private void shouldShowNotification(int serviceState, int subId) {
        // "Network selection unavailable" notification should only show when network selection is
        // visible to the end user. Some CC items e.g. KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL
        // can be overridden to hide the network selection to the end user. In this case, the
        // notification is not shown to avoid confusion to the end user.
        if (!shouldDisplayNetworkSelectOptions(subId)) {
            logi("Carrier configs refuse to show network selection not available notification");
            return;
        }

        // In case network selection notification shows up repeatedly under
        // unstable network condition. The logic is to check whether or not
        // the service state keeps in no service condition for at least
        // {@link #NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIME_IN_MS}.
        // And checking {@link #NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIMES} times.
        // To avoid the notification showing up for the momentary state.
        if (serviceState == ServiceState.STATE_OUT_OF_SERVICE) {
            if (mPreviousServiceState.get(subId, STATE_UNKNOWN_SERVICE)
                    != ServiceState.STATE_OUT_OF_SERVICE) {
                mOOSTimestamp.put(subId, getTimeStamp());
            }
            if ((getTimeStamp() - mOOSTimestamp.get(subId, 0L)
                    >= NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIME_IN_MS)
                    || mPendingEventCounter.get(subId, 0)
                    > NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIMES) {
                showNetworkSelection(mSelectedNetworkOperatorName.get(subId), subId);
                clearUpNetworkSelectionNotificationParam(subId);
            } else {
                startPendingNetworkSelectionNotification(subId);
            }
        } else {
            dismissNetworkSelectionNotification(subId);
        }
        mPreviousServiceState.put(subId, serviceState);
        if (DBG) {
            log("shouldShowNotification()..." + " subId = " + subId
                    + " serviceState = " + serviceState
                    + " mOOSTimestamp = " + mOOSTimestamp
                    + " mPendingEventCounter = " + mPendingEventCounter);
        }
    }

    // TODO(b/243010310): merge methods below with Settings#MobileNetworkUtils and optimize them.
    // The methods below are copied from com.android.settings.network.telephony.MobileNetworkUtils
    // to make sure the network selection unavailable notification should not show when Network
    // Selection menu is not present in Settings app.
    private boolean shouldDisplayNetworkSelectOptions(int subId) {
        final TelephonyManager telephonyManager = mTelephonyManager.createForSubscriptionId(subId);
        final CarrierConfigManager carrierConfigManager = mContext.getSystemService(
                CarrierConfigManager.class);
        final PersistableBundle carrierConfig = carrierConfigManager.getConfigForSubId(subId);

        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                || carrierConfig == null
                || !carrierConfig.getBoolean(
                CarrierConfigManager.KEY_OPERATOR_SELECTION_EXPAND_BOOL)
                || carrierConfig.getBoolean(
                CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)
                || (carrierConfig.getBoolean(CarrierConfigManager.KEY_CSP_ENABLED_BOOL)
                && !telephonyManager.isManualNetworkSelectionAllowed())) {
            return false;
        }

        if (isWorldMode(carrierConfig)) {
            final int networkMode = RadioAccessFamily.getNetworkTypeFromRaf(
                    (int) telephonyManager.getAllowedNetworkTypesForReason(
                            TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER));
            if (networkMode == RILConstants.NETWORK_MODE_LTE_CDMA_EVDO) {
                return false;
            }
            if (shouldSpeciallyUpdateGsmCdma(telephonyManager, carrierConfig)) {
                return false;
            }
            if (networkMode == RILConstants.NETWORK_MODE_LTE_GSM_WCDMA) {
                return true;
            }
        }

        return isGsmBasicOptions(telephonyManager, carrierConfig);
    }

    private static boolean isWorldMode(@NonNull PersistableBundle carrierConfig) {
        return carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_MODE_ENABLED_BOOL);
    }

    private static boolean shouldSpeciallyUpdateGsmCdma(@NonNull TelephonyManager telephonyManager,
            @NonNull PersistableBundle carrierConfig) {
        if (!isWorldMode(carrierConfig)) {
            return false;
        }

        final int networkMode = RadioAccessFamily.getNetworkTypeFromRaf(
                (int) telephonyManager.getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER));
        if (networkMode == RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM
                || networkMode == RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA
                || networkMode == RILConstants.NETWORK_MODE_LTE_TDSCDMA
                || networkMode == RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA
                || networkMode
                == RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA
                || networkMode == RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA) {
            if (!isTdscdmaSupported(telephonyManager, carrierConfig)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTdscdmaSupported(@NonNull TelephonyManager telephonyManager,
            @NonNull PersistableBundle carrierConfig) {
        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_SUPPORT_TDSCDMA_BOOL)) {
            return true;
        }
        final String[] numericArray = carrierConfig.getStringArray(
                CarrierConfigManager.KEY_SUPPORT_TDSCDMA_ROAMING_NETWORKS_STRING_ARRAY);
        if (numericArray == null) {
            return false;
        }
        final ServiceState serviceState = telephonyManager.getServiceState();
        final String operatorNumeric =
                (serviceState != null) ? serviceState.getOperatorNumeric() : null;
        if (operatorNumeric == null) {
            return false;
        }
        for (String numeric : numericArray) {
            if (operatorNumeric.equals(numeric)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGsmBasicOptions(@NonNull TelephonyManager telephonyManager,
            @NonNull PersistableBundle carrierConfig) {
        if (!carrierConfig.getBoolean(
                CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)
                && carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_PHONE_BOOL)) {
            return true;
        }

        if (telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
            return true;
        }

        return false;
    }
    // END of TODO:(b/243010310): merge methods above with Settings#MobileNetworkUtils and optimize.

    private void startPendingNetworkSelectionNotification(int subId) {
        if (!mHandler.hasMessages(EVENT_PENDING_NETWORK_SELECTION_NOTIFICATION, subId)) {
            if (DBG) {
                log("startPendingNetworkSelectionNotification: subId = " + subId);
            }
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(EVENT_PENDING_NETWORK_SELECTION_NOTIFICATION, subId),
                    NETWORK_SELECTION_NOTIFICATION_MAX_PENDING_TIME_IN_MS);
            mPendingEventCounter.put(subId, mPendingEventCounter.get(subId, 0) + 1);
        }
    }

    private void clearUpNetworkSelectionNotificationParam(int subId) {
        if (mHandler.hasMessages(EVENT_PENDING_NETWORK_SELECTION_NOTIFICATION, subId)) {
            mHandler.removeMessages(EVENT_PENDING_NETWORK_SELECTION_NOTIFICATION, subId);
        }
        mPreviousServiceState.remove(subId);
        mOOSTimestamp.remove(subId);
        mPendingEventCounter.remove(subId);
        mSelectedNetworkOperatorName.remove(subId);
    }

    @VisibleForTesting
    public long getTimeStamp() {
        return SystemClock.elapsedRealtime();
    }
}
