package com.android.phone.settings;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.flags.Flags;
import com.android.phone.PhoneUtils;
import com.android.phone.R;
import com.android.phone.SubscriptionInfoHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class PhoneAccountSettingsFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener,
                AccountSelectionPreference.AccountSelectionListener {

    private static final String ACCOUNTS_LIST_CATEGORY_KEY =
            "phone_accounts_accounts_list_category_key";

    private static final String ALL_CALLING_ACCOUNTS_KEY = "phone_accounts_all_calling_accounts";

    private static final String MAKE_AND_RECEIVE_CALLS_CATEGORY_KEY =
            "make_and_receive_calls_settings_category_key";
    private static final String DEFAULT_OUTGOING_ACCOUNT_KEY = "default_outgoing_account";
    private static final String SMART_FORWARDING_CONFIGURATION_PREF_KEY =
            "smart_forwarding_configuration_key";

    private static final String LEGACY_ACTION_CONFIGURE_PHONE_ACCOUNT =
            "android.telecom.action.CONNECTION_SERVICE_CONFIGURE";

    /**
     * Value to start ordering of phone accounts relative to other preferences. By setting this
     * value on the phone account listings, we ensure that anything that is ordered before
     * {value} in the preference XML comes before the phone account list and anything with
     * a value significantly larger will list after.
     */
    private static final int ACCOUNT_ORDERING_START_VALUE = 100;

    private static final String LOG_TAG = PhoneAccountSettingsFragment.class.getSimpleName();

    private TelecomManager mTelecomManager;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;

    private PreferenceCategory mAccountList;

    private AccountSelectionPreference mDefaultOutgoingAccount;
    private Preference mAllCallingAccounts;

    private PreferenceCategory mMakeAndReceiveCallsCategory;
    private boolean mMakeAndReceiveCallsCategoryPresent;

    private final SubscriptionManager.OnSubscriptionsChangedListener
            mOnSubscriptionsChangeListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            if (getActivity() == null) {
                return;
            }
            updateAccounts();
        }
    };

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mTelecomManager = getActivity().getSystemService(TelecomManager.class);
        mTelephonyManager = TelephonyManager.from(getActivity());
        mSubscriptionManager = SubscriptionManager.from(getActivity());
        if (Flags.workProfileApiSplit()) {
            mSubscriptionManager = mSubscriptionManager.createForAllUserProfiles();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (getPreferenceScreen() != null) {
            getPreferenceScreen().removeAll();
        }

        addPreferencesFromResource(R.xml.phone_account_settings);
        getView().setFitsSystemWindows(true);

        /**
         * Here we make decisions about what we will and will not display with regards to phone-
         * account settings.  The basic settings structure is this:
         * (1) <Make Calls With...>  // Lets user pick a default account for outgoing calls
         * (2) <Account List>
         *       <Account>
         *       ...
         *       <Account>
         *     </Account List>
         * (3) <All Accounts>  // Lets user enable/disable third-party accounts. SIM-based accounts
         *                     // are always enabled and so aren't relevant here.
         *
         * Here are the rules that we follow:
         * - (1) is only shown if there are multiple enabled accounts, including SIM accounts.
         *   This can be 2+ SIM accounts, 2+ third party accounts or any combination.
         * - (2) The account list only lists (a) enabled third party accounts and (b) SIM-based
         *   accounts. However, for single-SIM devices, if the only account to show is the
         *   SIM-based account, we don't show the list at all under the assumption that the user
         *   already knows about the account.
         * - (3) Is only shown if there exist any third party accounts.  If none exist, then the
         *   option is hidden since there is nothing that can be done in it.
         *
         * By far, the most common case for users will be the single-SIM device without any
         * third party accounts. IOW, the great majority of users won't see any of these options.
         */
        mAccountList = (PreferenceCategory) getPreferenceScreen().findPreference(
                ACCOUNTS_LIST_CATEGORY_KEY);
        mDefaultOutgoingAccount = (AccountSelectionPreference)
                getPreferenceScreen().findPreference(DEFAULT_OUTGOING_ACCOUNT_KEY);
        mAllCallingAccounts = getPreferenceScreen().findPreference(ALL_CALLING_ACCOUNTS_KEY);

        mMakeAndReceiveCallsCategory = (PreferenceCategory) getPreferenceScreen().findPreference(
                MAKE_AND_RECEIVE_CALLS_CATEGORY_KEY);
        mMakeAndReceiveCallsCategoryPresent = false;

        updateAccounts();
        updateMakeCallsOptions();

        SubscriptionManager.from(getActivity()).addOnSubscriptionsChangedListener(
                mOnSubscriptionsChangeListener);
    }

    @Override
    public void onPause() {
        SubscriptionManager.from(getActivity()).removeOnSubscriptionsChangedListener(
                mOnSubscriptionsChangeListener);
        super.onPause();
    }

    /**
     * Handles changes to the preferences.
     *
     * @param pref The preference changed.
     * @param objValue The changed value.
     * @return True if the preference change has been handled, and false otherwise.
     */
    @Override
    public boolean onPreferenceChange(Preference pref, Object objValue) {
        return false;
    }

    /**
     * Handles a phone account selection for the default outgoing phone account.
     *
     * @param pref The account selection preference which triggered the account selected event.
     * @param account The account selected.
     * @return True if the account selection has been handled, and false otherwise.
     */
    @Override
    public boolean onAccountSelected(AccountSelectionPreference pref, PhoneAccountHandle account) {
        Log.d(LOG_TAG, String.format("onAccountSelected:  pref=[%s], account=[%s]", pref, account));
        if (pref == mDefaultOutgoingAccount) {
            mTelecomManager.setUserSelectedOutgoingPhoneAccount(account);
            return true;
        }
        return false;
    }

    /**
     * Repopulate the dialog to pick up changes before showing.
     *
     * @param pref The account selection preference dialog being shown.
     */
    @Override
    public void onAccountSelectionDialogShow(AccountSelectionPreference pref) {
        if (pref == mDefaultOutgoingAccount) {
            updateDefaultOutgoingAccountsModel();
        }
    }

    @Override
    public void onAccountChanged(AccountSelectionPreference pref) {}

    /**
     * Queries the telcomm manager to update the default outgoing account selection preference
     * with the list of outgoing accounts and the current default outgoing account.
     */
    private void updateDefaultOutgoingAccountsModel() {
        mDefaultOutgoingAccount.setModel(
                mTelecomManager,
                getCallingAccounts(true /* includeSims */, false /* includeDisabled */),
                mTelecomManager.getUserSelectedOutgoingPhoneAccount(),
                getString(R.string.phone_accounts_ask_every_time));
    }

    private void initAccountList(List<PhoneAccountHandle> enabledAccounts) {

        boolean isMultiSimDevice = mTelephonyManager.isMultiSimEnabled();

        // On a single-SIM device, do not list any accounts if the only account is the SIM-based
        // one. This is because on single-SIM devices, we do not expose SIM settings through the
        // account listing entry so showing it does nothing to help the user. Nor does the lack of
        // action match the "Settings" header above the listing.
        if (!isMultiSimDevice && getCallingAccounts(
                false /* includeSims */, false /* includeDisabled */).isEmpty()){
            return;
        }

        // Obtain the list of phone accounts.
        List<PhoneAccount> accounts = new ArrayList<>();
        for (PhoneAccountHandle handle : enabledAccounts) {
            PhoneAccount account = mTelecomManager.getPhoneAccount(handle);
            if (account != null) {
                accounts.add(account);
            }
        }

        // Sort the accounts according to how we want to display them.
        Collections.sort(accounts, new Comparator<PhoneAccount>() {
            @Override
            public int compare(PhoneAccount account1, PhoneAccount account2) {
                int retval = 0;

                // SIM accounts go first
                boolean isSim1 = account1.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
                boolean isSim2 = account2.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
                if (isSim1 != isSim2) {
                    retval = isSim1 ? -1 : 1;
                }

                int subId1 = mTelephonyManager.getSubIdForPhoneAccount(account1);
                int subId2 = mTelephonyManager.getSubIdForPhoneAccount(account2);
                if (subId1 != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                        subId2 != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    retval = (mSubscriptionManager.getSlotIndex(subId1) <
                        mSubscriptionManager.getSlotIndex(subId2)) ? -1 : 1;
                }

                // Then order by package
                if (retval == 0) {
                    String pkg1 = account1.getAccountHandle().getComponentName().getPackageName();
                    String pkg2 = account2.getAccountHandle().getComponentName().getPackageName();
                    retval = pkg1.compareTo(pkg2);
                }

                // Finally, order by label
                if (retval == 0) {
                    String label1 = nullToEmpty(account1.getLabel().toString());
                    String label2 = nullToEmpty(account2.getLabel().toString());
                    retval = label1.compareTo(label2);
                }

                // Then by hashcode
                if (retval == 0) {
                    retval = account1.hashCode() - account2.hashCode();
                }
                return retval;
            }
        });

        int order = ACCOUNT_ORDERING_START_VALUE;

        // Add an entry for each account.
        for (PhoneAccount account : accounts) {
            PhoneAccountHandle handle = account.getAccountHandle();
            Intent intent = null;

            // SIM phone accounts use a different setting intent and are thus handled differently.
            if (account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {

                // For SIM-based accounts, we only expose the settings through the account list
                // if we are on a multi-SIM device. For single-SIM devices, the settings are
                // more spread out so there is no good single place to take the user, so we don't.
                if (isMultiSimDevice) {
                    SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(
                            mTelephonyManager.getSubIdForPhoneAccount(account));

                    if (subInfo != null) {
                        intent = new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        SubscriptionInfoHelper.addExtrasToIntent(intent, subInfo);
                    }
                }
            } else {
                intent = buildPhoneAccountConfigureIntent(getActivity(), handle);
            }

            // Create the preference & add the label
            Preference accountPreference = new Preference(getActivity());
            CharSequence accountLabel = account.getLabel();
            boolean isSimAccount =
                    account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION);
            accountPreference.setTitle((TextUtils.isEmpty(accountLabel) && isSimAccount)
                    ? getString(R.string.phone_accounts_default_account_label) : accountLabel);

            // Add an icon.
            Icon icon = account.getIcon();
            if (icon != null) {
                accountPreference.setIcon(icon.loadDrawable(getActivity()));
            }

            // Add an intent to send the user to the account's settings.
            if (intent != null) {
                accountPreference.setIntent(intent);
            }

            accountPreference.setOrder(order++);
            mAccountList.addPreference(accountPreference);
        }
    }

    private boolean shouldShowConnectionServiceList(List<PhoneAccountHandle> allNonSimAccounts) {
        return mTelephonyManager.isMultiSimEnabled() || allNonSimAccounts.size() > 0;
    }

    private void updateAccounts() {
        if (mAccountList != null) {
            mAccountList.removeAll();
            List<PhoneAccountHandle> allNonSimAccounts =
                    getCallingAccounts(false /* includeSims */, true /* includeDisabled */);

            List<PhoneAccountHandle> enabledAccounts =
                    getCallingAccounts(true /* includeSims */, false /* includeDisabled */);
            // Initialize the account list with the set of enabled & SIM accounts.
            initAccountList(enabledAccounts);

            // Always show the 'Make Calls With..." option
            mMakeAndReceiveCallsCategory.addPreference(mDefaultOutgoingAccount);
            mMakeAndReceiveCallsCategoryPresent = true;
            mDefaultOutgoingAccount.setListener(this);
            updateDefaultOutgoingAccountsModel();

            // If there are no third party (nonSim) accounts,
            // then don't show enable/disable dialog.
            if (!allNonSimAccounts.isEmpty()) {
                mAccountList.addPreference(mAllCallingAccounts);
            } else {
                mAccountList.removePreference(mAllCallingAccounts);
            }
        }
    }

    private List<PhoneAccountHandle> getCallingAccounts(
            boolean includeSims, boolean includeDisabledAccounts) {
        PhoneAccountHandle emergencyAccountHandle = getEmergencyPhoneAccount();

        List<PhoneAccountHandle> accountHandles =
                mTelecomManager.getCallCapablePhoneAccounts(includeDisabledAccounts);
        for (Iterator<PhoneAccountHandle> i = accountHandles.iterator(); i.hasNext();) {
            PhoneAccountHandle handle = i.next();
            UserHandle userHandle = handle.getUserHandle();
            if (handle.equals(emergencyAccountHandle)) {
                // never include emergency call accounts in this piece of code.
                i.remove();
                continue;
            }

            PhoneAccount account = mTelecomManager.getPhoneAccount(handle);
            if (account == null) {
                i.remove();
            } else if (!includeSims &&
                    account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                i.remove();
            } else if (!userHandle.equals(Binder.getCallingUserHandle())
                    && !account.hasCapabilities(PhoneAccount.CAPABILITY_MULTI_USER)) {
                // Only show accounts for the current user (unless account has
                // CAPABILITY_MULTI_USER).
                i.remove();
            }
        }
        return accountHandles;
    }

    private String nullToEmpty(String str) {
        return str == null ? "" : str;
    }

    private PhoneAccountHandle getEmergencyPhoneAccount() {
        return PhoneUtils.makePstnPhoneAccountHandleWithPrefix(
                (Phone) null, "" /* prefix */, true /* isEmergency */, null /* userHandle */);
    }

    public static Intent buildPhoneAccountConfigureIntent(
            Context context, PhoneAccountHandle accountHandle) {
        Intent intent = buildConfigureIntent(
                context, accountHandle, TelecomManager.ACTION_CONFIGURE_PHONE_ACCOUNT);

        if (intent == null) {
            // If the new configuration didn't work, try the old configuration intent.
            intent = buildConfigureIntent(
                    context, accountHandle, LEGACY_ACTION_CONFIGURE_PHONE_ACCOUNT);
            if (intent != null) {
                Log.w(LOG_TAG, "Phone account using old configuration intent: " + accountHandle);
            }
        }
        return intent;
    }

    private static Intent buildConfigureIntent(
            Context context, PhoneAccountHandle accountHandle, String actionStr) {
        if (accountHandle == null || accountHandle.getComponentName() == null ||
                TextUtils.isEmpty(accountHandle.getComponentName().getPackageName())) {
            return null;
        }

        // Build the settings intent.
        Intent intent = new Intent(actionStr);
        intent.setPackage(accountHandle.getComponentName().getPackageName());
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle);

        // Check to see that the phone account package can handle the setting intent.
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> resolutions = pm.queryIntentActivities(intent, 0);
        if (resolutions.size() == 0) {
            intent = null;  // set no intent if the package cannot handle it.
        }

        return intent;
    }

    /**
     * @return Whether the current user is the primary user.
     */
    private boolean isPrimaryUser() {
        final UserManager userManager = (UserManager) getActivity()
                .getSystemService(Context.USER_SERVICE);
        return userManager.isPrimaryUser();
    }

    private void updateMakeCallsOptions() {
        if (mMakeAndReceiveCallsCategory == null) {
            return;
        }

        Intent smartForwardingUiIntent = getLaunchSmartForwardingMenuIntent();
        if (smartForwardingUiIntent != null) {
            mMakeAndReceiveCallsCategory.findPreference(SMART_FORWARDING_CONFIGURATION_PREF_KEY)
                    .setIntent(smartForwardingUiIntent);
            mMakeAndReceiveCallsCategoryPresent = true;
        } else {
            mMakeAndReceiveCallsCategory.removePreference(
                    getPreferenceScreen().findPreference(SMART_FORWARDING_CONFIGURATION_PREF_KEY));
        }

        if (!mMakeAndReceiveCallsCategoryPresent) {
            getPreferenceScreen().removePreference(mMakeAndReceiveCallsCategory);
        }
    }

    /**
     * @return Smart forwarding configuration UI Intent when supported
     */
    private Intent getLaunchSmartForwardingMenuIntent() {
        if (mTelephonyManager.getPhoneCount() <= 1) {
            return null;
        }

        final CarrierConfigManager configManager = (CarrierConfigManager)
                getActivity().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            return null;
        }

        List<SubscriptionInfo> subscriptions =
                mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subscriptions == null) {
            return null;
        }

        List<SubscriptionInfo> effectiveSubscriptions = subscriptions.stream()
                .filter(subInfo -> !subInfo.isOpportunistic())
                .collect(Collectors.toList());
        if (effectiveSubscriptions.size() < 2) {
            return null;
        }

        List<String> componentNames = effectiveSubscriptions.stream()
                .map(subInfo -> configManager.getConfigForSubId(subInfo.getSubscriptionId()))
                .filter(bundle -> (bundle != null))
                .map(bundle -> bundle.getString(
                        CarrierConfigManager.KEY_SMART_FORWARDING_CONFIG_COMPONENT_NAME_STRING))
                .filter(componentName -> !TextUtils.isEmpty(componentName))
                .collect(Collectors.toList());

        String componentNameOfMenu = null;
        for (String componentName : componentNames) {
            if (componentNameOfMenu == null) {
                componentNameOfMenu = componentName;
            }
            else if (!componentNameOfMenu.equals(componentName)) {
                Log.w(LOG_TAG, "ignore smart forward component: " + componentName);
            }
        }

        if (TextUtils.isEmpty(componentNameOfMenu)) {
            return null;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(ComponentName.unflattenFromString(componentNameOfMenu));

        PackageManager pm = getActivity().getPackageManager();
        List<ResolveInfo> resolutions = pm.queryIntentActivities(intent, 0);
        if (resolutions.size() == 0) {
            intent = null;  // set no intent if no package can handle it.
        }

        return intent;
    }
}
