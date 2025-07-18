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

package com.android.phone.satellite.entitlement;

import static com.android.phone.satellite.entitlement.SatelliteEntitlementResult.SATELLITE_ENTITLEMENT_STATUS_DISABLED;

import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.satellite.SatelliteNetworkInfo;
import com.android.libraries.entitlement.ServiceEntitlement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class parses whether the satellite service configuration.
 * @hide
 */
public class SatelliteEntitlementResponse {
    private static final String TAG = "SatelliteEntitlementResponse";

    /** Overall status of the SatMode entitlement, stating if the satellite service can be offered
     * on the device, and if it can be activated or not by the user. */
    private static final String ENTITLEMENT_STATUS_KEY = "EntitlementStatus";
    /** List of allowed PLMNs where the service can be used. */
    private static final String PLMN_ALLOWED_KEY = "PLMNAllowed";
    /** List of barred PLMNs where the service can’t be used. */
    private static final String PLMN_BARRED_KEY = "PLMNBarred";
    /** allowed PLMN-ID where the service can be used or is barred. */
    private static final String PLMN_KEY = "PLMN";
    /** The data plan is of the metered or un-metered type. This value is optional. */
    private static final String DATA_PLAN_TYPE_KEY = "DataPlanType";
    /** The allowed services info with array of allowed services */
    private static final String ALLOWED_SERVICES_INFO_TYPE_KEY = "AllowedServicesInfo";
    /** The allowed services with service type and service policy for the plmn*/
    private static final String ALLOWED_SERVICES_KEY = "AllowedServices";
    /** list of service type supported for the plmn*/
    private static final String SERVICE_TYPE_KEY = "ServiceType";
    /** list of service policy supported for the plmn*/
    private static final String SERVICE_POLICY_KEY = "ServicePolicy";

    @SatelliteEntitlementResult.SatelliteEntitlementStatus private int mEntitlementStatus;

    /**
     * <p> Available options are :
     * "PLMNAllowed":[{ "PLMN": "XXXXXX", “DataPlanType”: "unmetered"},
     * {"PLMN": "XXXXXX", “DataPlanType”: "metered"},
     * {"PLMN": "XXXXXX"}]
     */
    private List<SatelliteNetworkInfo> mPlmnAllowedList;
    /**
     * <p> Available option is :
     * "PLMNBarred":[{"PLMN": "XXXXXX"}, {"PLMN”:"XXXXXX"}]
     */
    private List<String> mPlmnBarredList;

    public SatelliteEntitlementResponse(String response) {
        mEntitlementStatus = SATELLITE_ENTITLEMENT_STATUS_DISABLED;
        mPlmnAllowedList = new ArrayList<>();
        mPlmnBarredList = new ArrayList<>();
        if (!TextUtils.isEmpty(response)) {
            parsingResponse(response);
        }
    }

    /**
     * Get the entitlement status for the satellite service
     * @return The satellite entitlement status
     */
    public int getEntitlementStatus() {
        return mEntitlementStatus;
    }

    /**
     * Get the PLMNAllowed from the response
     * @return The PLMNs Allowed list. PLMN and Data Plan Type(optional).
     */
    public List<SatelliteNetworkInfo> getPlmnAllowed() {
        return mPlmnAllowedList.stream().map((info) -> new SatelliteNetworkInfo(info.mPlmn,
                info.mDataPlanType, info.mAllowedServicesInfo)).collect(Collectors.toList());
    }

    /**
     * Get the PLMNBarredList from the response
     * @return The PLMNs Barred List
     */
    public List<String> getPlmnBarredList() {
        return mPlmnBarredList.stream().map(String::new).collect(Collectors.toList());
    }

    private void parsingResponse(String response) {
        JSONObject jsonAuthResponse = null;
        try {
            jsonAuthResponse = new JSONObject(response);
            if (!jsonAuthResponse.has(ServiceEntitlement.APP_SATELLITE_ENTITLEMENT)) {
                loge("parsingResponse failed with no app");
                return;
            }
            JSONObject jsonToken = jsonAuthResponse.getJSONObject(
                    ServiceEntitlement.APP_SATELLITE_ENTITLEMENT);
            if (jsonToken.has(ENTITLEMENT_STATUS_KEY)) {
                String entitlementStatus = jsonToken.getString(ENTITLEMENT_STATUS_KEY);
                if (entitlementStatus == null) {
                    loge("parsingResponse EntitlementStatus is null");
                    return;
                }
                mEntitlementStatus = Integer.valueOf(entitlementStatus);
            }
            if (jsonToken.has(PLMN_ALLOWED_KEY)) {
                JSONArray jsonArray = jsonToken.getJSONArray(PLMN_ALLOWED_KEY);
                mPlmnAllowedList = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    String dataPlanType = jsonArray.getJSONObject(i).has(DATA_PLAN_TYPE_KEY)
                            ? jsonArray.getJSONObject(i).getString(DATA_PLAN_TYPE_KEY) : "";
                    Map<String, String> allowedServicesInfo = null;
                    if (jsonArray.getJSONObject(i).has(ALLOWED_SERVICES_INFO_TYPE_KEY)) {
                        allowedServicesInfo = new HashMap<>();
                        JSONArray jsonArray1 = jsonArray.getJSONObject(i)
                                .getJSONArray(ALLOWED_SERVICES_INFO_TYPE_KEY);
                        for (int j = 0; j < jsonArray1.length(); j++) {
                            String serviceType =  jsonArray1.getJSONObject(j)
                                    .getJSONObject(ALLOWED_SERVICES_KEY)
                                    .has(SERVICE_TYPE_KEY) ? jsonArray1.getJSONObject(j)
                                    .getJSONObject(ALLOWED_SERVICES_KEY)
                                    .getString(SERVICE_TYPE_KEY): "";
                            String servicePolicy = jsonArray1.getJSONObject(j)
                                    .getJSONObject(ALLOWED_SERVICES_KEY)
                                    .has(SERVICE_POLICY_KEY) ? jsonArray1.getJSONObject(j)
                                    .getJSONObject(ALLOWED_SERVICES_KEY)
                                    .getString(SERVICE_POLICY_KEY) : "";
                            allowedServicesInfo.put(serviceType, servicePolicy);
                        }
                    }
                    String plmn = jsonArray.getJSONObject(i).getString(PLMN_KEY);
                    logd("parsingResponse: plmn=" + plmn + " dataplan=" + dataPlanType
                            + " allowedServices=" + allowedServicesInfo);
                    if (!TextUtils.isEmpty(plmn)) {
                        mPlmnAllowedList.add(new SatelliteNetworkInfo(
                                plmn, dataPlanType, allowedServicesInfo));
                    }
                }
            }
            if (jsonToken.has(PLMN_BARRED_KEY)) {
                mPlmnBarredList = new ArrayList<>();
                JSONArray jsonArray = jsonToken.getJSONArray(PLMN_BARRED_KEY);
                for (int i = 0; i < jsonArray.length(); i++) {
                    String plmn = jsonArray.getJSONObject(i).getString(PLMN_KEY);
                    if (!TextUtils.isEmpty(plmn)) {
                        mPlmnBarredList.add(plmn);
                    }
                }
            }
        } catch (JSONException e) {
            loge("parsingResponse: failed JSONException", e);
        } catch (NumberFormatException e) {
            loge("parsingResponse: failed NumberFormatException", e);
        }
    }

    private static void logd(String log) {
        Log.d(TAG, log);
    }

    private static void loge(String log) {
        Log.e(TAG, log);
    }

    private static void loge(String log, Exception e) {
        Log.e(TAG, log, e);
    }
}
