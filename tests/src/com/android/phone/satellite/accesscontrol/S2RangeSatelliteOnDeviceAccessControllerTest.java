/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.phone.satellite.accesscontrol;

import static com.android.phone.satellite.accesscontrol.SatelliteAccessController.DEFAULT_REGIONAL_SATELLITE_CONFIG_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.telephony.flags.FeatureFlags;
import com.android.storage.s2.S2LevelRange;

import com.android.telephony.sats2range.read.SatS2RangeFileFormat;
import com.android.telephony.sats2range.read.SuffixTableRange;
import com.android.telephony.sats2range.utils.TestUtils;
import com.android.telephony.sats2range.write.SatS2RangeFileWriter;

import com.google.common.geometry.S2CellId;
import com.google.common.geometry.S2LatLng;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class S2RangeSatelliteOnDeviceAccessControllerTest {
    private File mFile;

    @Mock
    private FeatureFlags mMockFeatureFlags;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFile = File.createTempFile("test", ".data");
        assertTrue(mFile.exists());
    }

    @After
    public void tearDown() throws IOException {
        if (mFile != null && mFile.exists()) {
            assertTrue(mFile.delete());
        }
    }

    @Test
    public void testSatelliteAccessControl_AllowedList() throws Exception {
        testSatelliteAccessControl(true);
    }

    @Test
    public void testSatelliteAccessControl_DisallowedList() throws Exception {
        testSatelliteAccessControl(false);
    }

    private void testSatelliteAccessControl(boolean isAllowedList) throws Exception {
        SatS2RangeFileFormat fileFormat = null;
        try {
            fileFormat = createSatS2File(mFile, isAllowedList);
        } catch (Exception ex) {
            fail("Got unexpected exception in createSatS2File, ex=" + ex);
        }

        // Validate the output block file
        SatelliteOnDeviceAccessController accessController = null;
        try {
            accessController = SatelliteOnDeviceAccessController.create(mFile, mMockFeatureFlags);
            int s2Level = accessController.getS2Level();

            // Verify an edge cell of range 1 not in the output file
            S2CellId s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1000, 999));
            S2LatLng s2LatLng = s2CellId.toLatLng();
            SatelliteOnDeviceAccessController.LocationToken locationToken =
                    SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                            s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);

            // Verify if the return value is null, when the carrierRoamingNbIotNtn is disabled.
            doReturn(false).when(mMockFeatureFlags).carrierRoamingNbIotNtn();
            assertNull(accessController.getRegionalConfigIdForLocation(locationToken));

            doReturn(true).when(mMockFeatureFlags).carrierRoamingNbIotNtn();
            boolean isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
            assertTrue(isAllowed != isAllowedList);

            Integer configId = accessController.getRegionalConfigIdForLocation(locationToken);
            if (isAllowedList) {
                assertNull(configId);
            } else {
                assertNotNull(configId);
                assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
            }

            // Verify cells in range1 present in the output file
            for (int suffix = 1000; suffix < 2000; suffix++) {
                s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1000, suffix));
                s2LatLng = s2CellId.toLatLng();

                // Lookup using location token
                locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                        s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
                isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
                assertTrue(isAllowed == isAllowedList);

                configId = accessController.getRegionalConfigIdForLocation(locationToken);
                if (isAllowedList) {
                    assertNotNull(configId);
                    assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
                } else {
                    assertNull(configId);
                }
            }

            // Verify the middle cell not in the output file
            s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1000, 2000));
            s2LatLng = s2CellId.toLatLng();
            locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                    s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
            isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
            assertTrue(isAllowed != isAllowedList);

            configId = accessController.getRegionalConfigIdForLocation(locationToken);
            if (isAllowedList) {
                assertNull(configId);
            } else {
                assertNotNull(configId);
                assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
            }

            // Verify cells in range2 present in the output file
            for (int suffix = 2001; suffix < 3000; suffix++) {
                s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1000, suffix));
                s2LatLng = s2CellId.toLatLng();
                locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                        s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
                isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
                assertTrue(isAllowed == isAllowedList);

                configId = accessController.getRegionalConfigIdForLocation(locationToken);
                if (isAllowedList) {
                    assertNotNull(configId);
                    assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
                } else {
                    assertNull(configId);
                }
            }

            // Verify an edge cell of range 2 not in the output file
            s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1000, 3000));
            s2LatLng = s2CellId.toLatLng();
            locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                    s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
            isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
            assertTrue(isAllowed != isAllowedList);

            configId = accessController.getRegionalConfigIdForLocation(locationToken);
            if (isAllowedList) {
                assertNull(configId);
            } else {
                assertNotNull(configId);
                assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
            }

            // Verify an edge cell of range 3 not in the output file
            s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1001, 999));
            s2LatLng = s2CellId.toLatLng();
            locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                    s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
            isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
            assertTrue(isAllowed != isAllowedList);

            configId = accessController.getRegionalConfigIdForLocation(locationToken);
            if (isAllowedList) {
                assertNull(configId);
            } else {
                assertNotNull(configId);
                assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
            }

            // Verify cells in range1 present in the output file
            for (int suffix = 1000; suffix < 2000; suffix++) {
                s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1001, suffix));
                s2LatLng = s2CellId.toLatLng();
                locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                        s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
                isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
                assertTrue(isAllowed == isAllowedList);

                configId = accessController.getRegionalConfigIdForLocation(locationToken);
                if (isAllowedList) {
                    assertNotNull(configId);
                    assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
                } else {
                    assertNull(configId);
                }
            }

            // Verify an edge cell of range 3 not in the output file
            s2CellId = new S2CellId(TestUtils.createCellId(fileFormat, 1, 1001, 2000));
            s2LatLng = s2CellId.toLatLng();
            locationToken = SatelliteOnDeviceAccessController.createLocationTokenForLatLng(
                    s2LatLng.latDegrees(), s2LatLng.lngDegrees(), s2Level);
            isAllowed = accessController.isSatCommunicationAllowedAtLocation(locationToken);
            assertTrue(isAllowed != isAllowedList);

            configId = accessController.getRegionalConfigIdForLocation(locationToken);
            if (isAllowedList) {
                assertNull(configId);
            } else {
                assertNotNull(configId);
                assertEquals(DEFAULT_REGIONAL_SATELLITE_CONFIG_ID, (int) configId);
            }

        } catch (Exception ex) {
            fail("Unexpected exception when validating the output ex=" + ex);
        } finally {
            if (accessController != null) {
                accessController.close();
            }
        }
    }

    private SatS2RangeFileFormat createSatS2File(
            File file, boolean isAllowedList) throws Exception {
        SatS2RangeFileFormat fileFormat;
        SuffixTableRange range1, range2, range3;
        try (SatS2RangeFileWriter satS2RangeFileWriter = SatS2RangeFileWriter.open(
                file, TestUtils.createS2RangeFileFormat(isAllowedList))) {
            fileFormat = satS2RangeFileWriter.getFileFormat();

            // Two ranges that share a prefix.
            range1 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1000, 1000),
                    TestUtils.createCellId(fileFormat, 1, 1000, 2000));
            range2 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1000, 2001),
                    TestUtils.createCellId(fileFormat, 1, 1000, 3000));
            // This range has a different prefix, so will be in a different suffix table.
            range3 = new SuffixTableRange(
                    TestUtils.createCellId(fileFormat, 1, 1001, 1000),
                    TestUtils.createCellId(fileFormat, 1, 1001, 2000));

            List<SuffixTableRange> ranges = new ArrayList<>();
            ranges.add(range1);
            ranges.add(range2);
            ranges.add(range3);
            satS2RangeFileWriter.createSortedSuffixBlocks(ranges.iterator());
        }
        assertTrue(file.length() > 0);
        return fileFormat;
    }
}
