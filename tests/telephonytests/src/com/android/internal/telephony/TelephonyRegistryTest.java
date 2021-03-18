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
package com.android.internal.telephony;

import static android.telephony.SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED;
import static android.telephony.TelephonyManager.RADIO_POWER_OFF;
import static android.telephony.TelephonyManager.RADIO_POWER_ON;
import static android.telephony.TelephonyManager.RADIO_POWER_UNAVAILABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.LinkProperties;
import android.os.ServiceManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation;
import android.telephony.LinkCapacityEstimate;
import android.telephony.PhoneCapability;
import android.telephony.PreciseDataConnectionState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.server.TelephonyRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class TelephonyRegistryTest extends TelephonyTest {
    @Mock
    private SubscriptionInfo mMockSubInfo;
    private TelephonyCallbackWrapper mTelephonyCallback;
    private List<LinkCapacityEstimate> mLinkCapacityEstimateList;
    private TelephonyRegistry mTelephonyRegistry;
    private PhoneCapability mPhoneCapability;
    private int mActiveSubId;
    private TelephonyDisplayInfo mTelephonyDisplayInfo;
    private int mSrvccState = -1;
    private int mRadioPowerState = RADIO_POWER_UNAVAILABLE;

    // All events contribute to TelephonyRegistry#isPhoneStatePermissionRequired
    private static final Set<Integer> READ_PHONE_STATE_EVENTS;
    static {
        READ_PHONE_STATE_EVENTS = new HashSet<>();
        READ_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED);
        READ_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED);
        READ_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED);
        READ_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED);
    }

    // All events contribute to TelephonyRegistry#isPrecisePhoneStatePermissionRequired
    private static final Set<Integer> READ_PRECISE_PHONE_STATE_EVENTS;
    static {
        READ_PRECISE_PHONE_STATE_EVENTS = new HashSet<>();
        READ_PRECISE_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(TelephonyCallback.EVENT_REGISTRATION_FAILURE);
        READ_PRECISE_PHONE_STATE_EVENTS.add(TelephonyCallback.EVENT_BARRING_INFO_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED);
        READ_PRECISE_PHONE_STATE_EVENTS.add(TelephonyCallback.EVENT_DATA_ENABLED_CHANGED);
    }

    // All events contribute to TelephonyRegistry#isPrivilegedPhoneStatePermissionRequired
    // TODO: b/148021947 will create the permission group with PREVILIGED_STATE_PERMISSION_MASK
    private static final Set<Integer> READ_PRIVILEGED_PHONE_STATE_EVENTS;
    static {
        READ_PRIVILEGED_PHONE_STATE_EVENTS = new HashSet<>();
        READ_PRIVILEGED_PHONE_STATE_EVENTS.add( TelephonyCallback.EVENT_SRVCC_STATE_CHANGED);
        READ_PRIVILEGED_PHONE_STATE_EVENTS.add( TelephonyCallback.EVENT_OEM_HOOK_RAW);
        READ_PRIVILEGED_PHONE_STATE_EVENTS.add( TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED);
        READ_PRIVILEGED_PHONE_STATE_EVENTS.add(
                TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED);
    }

    // All events contribute to TelephonyRegistry#isActiveEmergencySessionPermissionRequired
    private static final Set<Integer> READ_ACTIVE_EMERGENCY_SESSION_EVENTS;
    static {
        READ_ACTIVE_EMERGENCY_SESSION_EVENTS = new HashSet<>();
        READ_ACTIVE_EMERGENCY_SESSION_EVENTS.add(
                TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL);
        READ_ACTIVE_EMERGENCY_SESSION_EVENTS.add(
                TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS);
    }

    public class TelephonyCallbackWrapper extends TelephonyCallback implements
            TelephonyCallback.SrvccStateListener,
            TelephonyCallback.PhoneCapabilityListener,
            TelephonyCallback.ActiveDataSubscriptionIdListener,
            TelephonyCallback.RadioPowerStateListener,
            TelephonyCallback.PreciseDataConnectionStateListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.LinkCapacityEstimateChangedListener {
        // This class isn't mockable to get invocation counts because the IBinder is null and
        // crashes the TelephonyRegistry. Make a cheesy verify(times()) alternative.
        public AtomicInteger invocationCount = new AtomicInteger(0);

        @Override
        public void onSrvccStateChanged(int srvccState) {
            invocationCount.incrementAndGet();
            mSrvccState = srvccState;
        }

        @Override
        public void onPhoneCapabilityChanged(PhoneCapability capability) {
            invocationCount.incrementAndGet();
            mPhoneCapability = capability;
        }
        @Override
        public void onActiveDataSubscriptionIdChanged(int activeSubId) {
            invocationCount.incrementAndGet();
            mActiveSubId = activeSubId;
        }
        @Override
        public void onRadioPowerStateChanged(@Annotation.RadioPowerState int state) {
            invocationCount.incrementAndGet();
            mRadioPowerState = state;
        }
        @Override
        public void onPreciseDataConnectionStateChanged(PreciseDataConnectionState preciseState) {
            invocationCount.incrementAndGet();
        }
        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
            mTelephonyDisplayInfo = displayInfo;
        }

        @Override
        public void onLinkCapacityEstimateChanged(
                List<LinkCapacityEstimate> linkCapacityEstimateList) {
            mLinkCapacityEstimateList =  linkCapacityEstimateList;
        }
    }

    private void addTelephonyRegistryService() {
        mServiceManagerMockedServices.put("telephony.registry", mTelephonyRegistry.asBinder());
        mTelephonyRegistry.systemRunning();
    }

    private Executor mSimpleExecutor = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };

    @Before
    public void setUp() throws Exception {
        super.setUp("TelephonyRegistryTest");
        TelephonyRegistry.ConfigurationProvider mockConfigurationProvider =
                mock(TelephonyRegistry.ConfigurationProvider.class);
        when(mockConfigurationProvider.getRegistrationLimit()).thenReturn(-1);
        when(mockConfigurationProvider.isRegistrationLimitEnabledInPlatformCompat(anyInt()))
                .thenReturn(false);
        mTelephonyRegistry = new TelephonyRegistry(mContext, mockConfigurationProvider);
        addTelephonyRegistryService();
        mTelephonyCallback = new TelephonyCallbackWrapper();
        mTelephonyCallback.init(mSimpleExecutor);
        processAllMessages();
        assertEquals(mTelephonyRegistry.asBinder(),
                ServiceManager.getService("telephony.registry"));
    }

    @After
    public void tearDown() throws Exception {
        mTelephonyRegistry = null;
        super.tearDown();
    }

    @Test @SmallTest
    public void testPhoneCapabilityChanged() {
        doReturn(mMockSubInfo).when(mSubscriptionManager).getActiveSubscriptionInfo(anyInt());
        doReturn(0/*slotIndex*/).when(mMockSubInfo).getSimSlotIndex();
        // mTelephonyRegistry.listen with notifyNow = true should trigger callback immediately.
        PhoneCapability phoneCapability = new PhoneCapability(1, 2, 3, null, false);
        int[] events = {TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED};
        mTelephonyRegistry.notifyPhoneCapabilityChanged(phoneCapability);
        mTelephonyRegistry.listenWithEventList(0, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
        processAllMessages();
        assertEquals(phoneCapability, mPhoneCapability);

        // notifyPhoneCapabilityChanged with a new capability. Callback should be triggered.
        phoneCapability = new PhoneCapability(3, 2, 2, null, false);
        mTelephonyRegistry.notifyPhoneCapabilityChanged(phoneCapability);
        processAllMessages();
        assertEquals(phoneCapability, mPhoneCapability);
    }


    @Test @SmallTest
    public void testActiveDataSubChanged() {
        // mTelephonyRegistry.listen with notifyNow = true should trigger callback immediately.
        int[] activeSubs = {0, 1, 2};
        int[] events = {TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED};
        when(mSubscriptionManager.getActiveSubscriptionIdList()).thenReturn(activeSubs);
        int activeSubId = 0;
        mTelephonyRegistry.notifyActiveDataSubIdChanged(activeSubId);
        mTelephonyRegistry.listenWithEventList(activeSubId, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
        processAllMessages();
        assertEquals(activeSubId, mActiveSubId);

        // notifyPhoneCapabilityChanged with a new capability. Callback should be triggered.
        mActiveSubId = 1;
        mTelephonyRegistry.notifyActiveDataSubIdChanged(activeSubId);
        processAllMessages();
        assertEquals(activeSubId, mActiveSubId);
    }

    /**
     * Test that we first receive a callback when listen(...) is called that contains the latest
     * notify(...) response and then that the callback is called correctly when notify(...) is
     * called.
     */
    @Test
    @SmallTest
    public void testSrvccStateChanged() throws Exception {
        // Return a slotIndex / phoneId of 0 for all sub ids given.
        doReturn(mMockSubInfo).when(mSubscriptionManager).getActiveSubscriptionInfo(anyInt());
        doReturn(0/*slotIndex*/).when(mMockSubInfo).getSimSlotIndex();
        int srvccState = TelephonyManager.SRVCC_STATE_HANDOVER_STARTED;
        int[] events = {TelephonyCallback.EVENT_SRVCC_STATE_CHANGED};
        mTelephonyRegistry.notifySrvccStateChanged(1 /*subId*/, srvccState);
        // Should receive callback when listen is called that contains the latest notify result.
        mTelephonyRegistry.listenWithEventList(1 /*subId*/, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
        processAllMessages();
        assertEquals(srvccState, mSrvccState);

        // trigger callback
        srvccState = TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED;
        mTelephonyRegistry.notifySrvccStateChanged(1 /*subId*/, srvccState);
        processAllMessages();
        assertEquals(srvccState, mSrvccState);
    }

    /**
     * Test that a SecurityException is thrown when we try to listen to a SRVCC state change without
     * READ_PRIVILEGED_PHONE_STATE.
     */
    @Test
    @SmallTest
    public void testSrvccStateChangedNoPermission() {
        // Clear all permission grants for test.
        mContextFixture.addCallingOrSelfPermission("");
        int srvccState = TelephonyManager.SRVCC_STATE_HANDOVER_STARTED;
        int[] events = {TelephonyCallback.EVENT_SRVCC_STATE_CHANGED};
        mTelephonyRegistry.notifySrvccStateChanged(0 /*subId*/, srvccState);
        try {
            mTelephonyRegistry.listenWithEventList(0 /*subId*/, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
            fail();
        } catch (SecurityException e) {
            // pass test!
        }
    }

    /**
     * Test multi sim config change.
     */
    @Test
    public void testMultiSimConfigChange() {
        int[] events = {TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED};
        mTelephonyRegistry.listenWithEventList(1, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
        processAllMessages();
        assertEquals(RADIO_POWER_UNAVAILABLE, mRadioPowerState);

        // Notify RADIO_POWER_ON on invalid phoneId. Shouldn't go through.
        mTelephonyRegistry.notifyRadioPowerStateChanged(1, 1, RADIO_POWER_ON);
        processAllMessages();
        assertEquals(RADIO_POWER_UNAVAILABLE, mRadioPowerState);

        // Switch to DSDS and re-send RADIO_POWER_ON on phone 1. This time it should be notified.
        doReturn(2).when(mTelephonyManager).getActiveModemCount();
        mContext.sendBroadcast(new Intent(ACTION_MULTI_SIM_CONFIG_CHANGED));
        mTelephonyRegistry.notifyRadioPowerStateChanged(1, 1, RADIO_POWER_ON);
        processAllMessages();
        assertEquals(RADIO_POWER_ON, mRadioPowerState);

        // Switch back to single SIM mode and re-send on phone 0. This time it should be notified.
        doReturn(2).when(mTelephonyManager).getActiveModemCount();
        mContext.sendBroadcast(new Intent(ACTION_MULTI_SIM_CONFIG_CHANGED));
        mTelephonyRegistry.notifyRadioPowerStateChanged(0, 1, RADIO_POWER_OFF);
        processAllMessages();
        assertEquals(RADIO_POWER_OFF, mRadioPowerState);
    }

    /**
     * Test multi sim config change.
     */
    @Test
    public void testPreciseDataConnectionStateChanged() {
        final int subId = 1;
        int[] events = {TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED};
        doReturn(mMockSubInfo).when(mSubscriptionManager).getActiveSubscriptionInfo(anyInt());
        doReturn(0/*slotIndex*/).when(mMockSubInfo).getSimSlotIndex();
        // Initialize the PSL with a PreciseDataConnection
        mTelephonyRegistry.notifyDataConnectionForSubscriber(
                /*phoneId*/ 0, subId,
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(1)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(new ApnSetting.Builder()
                                .setApnTypeBitmask(ApnSetting.TYPE_DEFAULT)
                                .setApnName("default")
                                .setEntryName("default")
                                .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build());
        mTelephonyRegistry.listenWithEventList(subId, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
        processAllMessages();
        // Verify that the PDCS is reported for the only APN
        assertEquals(1, mTelephonyCallback.invocationCount.get());

        // Add IMS APN and verify that the listener is invoked for the IMS APN
        mTelephonyRegistry.notifyDataConnectionForSubscriber(
                /*phoneId*/ 0, subId,
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(2)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(new ApnSetting.Builder()
                                .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                .setApnName("ims")
                                .setEntryName("ims")
                                .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build());
        processAllMessages();

        assertEquals(mTelephonyCallback.invocationCount.get(), 2);

        // Unregister the listener
        mTelephonyRegistry.listenWithEventList(subId, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, new int[0], true);
        processAllMessages();

        // Re-register the listener and ensure that both APN types are reported
        mTelephonyRegistry.listenWithEventList(subId, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, true);
        processAllMessages();
        assertEquals(4, mTelephonyCallback.invocationCount.get());

        // Send a duplicate event to the TelephonyRegistry and verify that the listener isn't
        // invoked.
        mTelephonyRegistry.notifyDataConnectionForSubscriber(
                /*phoneId*/ 0, subId,
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(2)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(new ApnSetting.Builder()
                                .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                .setApnName("ims")
                                .setEntryName("ims")
                                .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build());
        processAllMessages();
        assertEquals(4, mTelephonyCallback.invocationCount.get());
    }

    /**
     * Test listen to events that require READ_PHONE_STATE permission.
     */
    @Test
    public void testReadPhoneStatePermission() {
        // Clear all permission grants for test.
        mContextFixture.addCallingOrSelfPermission("");
        assertSecurityExceptionThrown(
                READ_PHONE_STATE_EVENTS.stream().mapToInt(i -> i).toArray());

        // Grant permssion
        mContextFixture.addCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE);
        assertSecurityExceptionNotThrown(
                READ_PHONE_STATE_EVENTS.stream().mapToInt(i -> i).toArray());
    }

    /**
     * Test listen to events that require READ_PRECISE_PHONE_STATE permission.
     */
    // FIXME(b/159082270) - Simply not granting location permission doesn't fix the test because
    // Location is soft-denied to apps that aren't in the foreground, and soft-denial currently
    // short-circuits the test.
    @Ignore("Skip due to b/159082270")
    @Test
    public void testReadPrecisePhoneStatePermission() {
        // Clear all permission grants for test.
        mContextFixture.addCallingOrSelfPermission("");
        // Many of the events require LOCATION permission, but without READ_PHONE_STATE, they will
        // still throw exceptions. Since this test is testing READ_PRECISE_PHONE_STATE, all other
        // permissions should be granted up-front.
        mContextFixture.addCallingOrSelfPermission(
                android.Manifest.permission.READ_PHONE_STATE);
        mContextFixture.addCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        assertSecurityExceptionThrown(
                READ_PRECISE_PHONE_STATE_EVENTS.stream().mapToInt(i -> i).toArray());

        // Grant permssion
        mContextFixture.addCallingOrSelfPermission(
                android.Manifest.permission.READ_PRECISE_PHONE_STATE);
        assertSecurityExceptionNotThrown(
                READ_PRECISE_PHONE_STATE_EVENTS.stream().mapToInt(i -> i).toArray());

    }

    /**
     * Test a bit-fiddling method in TelephonyRegistry
     */
    @Test
    public void testGetApnTypesStringFromBitmask() {
        {
            int mask = 0;
            assertEquals("", TelephonyRegistry.getApnTypesStringFromBitmask(mask));
        }

        {
            int mask = ApnSetting.TYPE_DEFAULT | ApnSetting.TYPE_MMS;
            assertEquals(String.join(
                    ",", ApnSetting.TYPE_DEFAULT_STRING, ApnSetting.TYPE_MMS_STRING),
                    TelephonyRegistry.getApnTypesStringFromBitmask(mask));
        }

        {
            int mask = 1 << 31;
            assertEquals("", TelephonyRegistry.getApnTypesStringFromBitmask(mask));
        }
    }

    /**
     * Test listen to events that require READ_PRIVILEGED_PHONE_STATE permission.
     */
    @Test
    public void testReadPrivilegedPhoneStatePermission() {
        // Clear all permission grants for test.
        mContextFixture.addCallingOrSelfPermission("");
        assertSecurityExceptionThrown(
                READ_PRIVILEGED_PHONE_STATE_EVENTS.stream().mapToInt(i -> i).toArray());

        // Grant permssion
        mContextFixture.addCallingOrSelfPermission(
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        assertSecurityExceptionNotThrown(
                READ_PRIVILEGED_PHONE_STATE_EVENTS.stream().mapToInt(i -> i).toArray());
    }

    /**
     * Test listen to events that require READ_ACTIVE_EMERGENCY_SESSION permission.
     */
    @Test
    public void testReadActiveEmergencySessionPermission() {
        // Clear all permission grants for test.
        mContextFixture.addCallingOrSelfPermission("");
        assertSecurityExceptionThrown(
                READ_ACTIVE_EMERGENCY_SESSION_EVENTS.stream().mapToInt(i -> i).toArray());

        // Grant permssion
        mContextFixture.addCallingOrSelfPermission(
                android.Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION);
        assertSecurityExceptionNotThrown(
                READ_ACTIVE_EMERGENCY_SESSION_EVENTS.stream().mapToInt(i -> i).toArray());
    }

    @Test
    public void testNotifyDisplayInfoChanged() {
        mContext.sendBroadcast(new Intent(ACTION_DEFAULT_SUBSCRIPTION_CHANGED)
                .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 12)
                .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 0));
        processAllMessages();
        int[] events = {TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED};
        mTelephonyRegistry.listenWithEventList(2, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback, events, false);

        // Notify with invalid subId on default phone. Should NOT trigger callback.
        TelephonyDisplayInfo displayInfo = new TelephonyDisplayInfo(0, 0);
        mTelephonyRegistry.notifyDisplayInfoChanged(0, INVALID_SUBSCRIPTION_ID, displayInfo);
        processAllMessages();
        assertEquals(null, mTelephonyDisplayInfo);

        // Notify with the matching subId on default phone. Should trigger callback.
        mTelephonyRegistry.notifyDisplayInfoChanged(0, 2, displayInfo);
        processAllMessages();
        assertEquals(displayInfo, mTelephonyDisplayInfo);
    }

    private void assertSecurityExceptionThrown(int[] event) {
        try {
            mTelephonyRegistry.listenWithEventList(
                    SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), mTelephonyCallback.callback, event, true);
            fail("SecurityException should throw without permission");
        } catch (SecurityException expected) {
        }
    }

    private void assertSecurityExceptionNotThrown(int[] event) {
        try {
            mTelephonyRegistry.listenWithEventList(
                    SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), mTelephonyCallback.callback, event, true);
        } catch (SecurityException unexpected) {
            fail("SecurityException thrown with permission");
        }
    }

    @Test
    public void testNotifyLinkCapacityEstimateChanged() {
        mContext.sendBroadcast(new Intent(ACTION_DEFAULT_SUBSCRIPTION_CHANGED)
                .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 2)
                .putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 0));
        processAllMessages();
        int[] events = {TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED};
        mTelephonyRegistry.listenWithEventList(2, mContext.getOpPackageName(),
                mContext.getAttributionTag(), mTelephonyCallback.callback,
                events, false);

        // Notify with invalid subId / phoneId on default phone. Should NOT trigger callback.
        List<LinkCapacityEstimate> lceList = new ArrayList<>();
        lceList.add(new LinkCapacityEstimate(LinkCapacityEstimate.LCE_TYPE_COMBINED, 4000,
                LinkCapacityEstimate.INVALID));
        mTelephonyRegistry.notifyLinkCapacityEstimateChanged(1, INVALID_SUBSCRIPTION_ID, lceList);
        processAllMessages();
        assertEquals(null, mLinkCapacityEstimateList);

        // Notify with invalid phoneId. Should NOT trigger callback.
        mTelephonyRegistry.notifyLinkCapacityEstimateChanged(2, 2, lceList);
        processAllMessages();
        assertEquals(null, mLinkCapacityEstimateList);

        // Notify with the matching subId on default phone. Should trigger callback.
        mTelephonyRegistry.notifyLinkCapacityEstimateChanged(0, 2, lceList);
        processAllMessages();
        assertEquals(lceList, mLinkCapacityEstimateList);
    }
}
