package xyz.doikki.videoplayer.player;

import android.media.AudioDeviceInfo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioOutputRoutePolicyTest {
    @Test
    public void onlyEncodedTransportsAdvertisePassthroughCapability() {
        assertTrue(AudioOutputRoutePolicy.isEncodedPassthroughTransport(
                AudioDeviceInfo.TYPE_HDMI_EARC));
        assertTrue(AudioOutputRoutePolicy.isEncodedPassthroughTransport(
                AudioDeviceInfo.TYPE_HDMI_ARC));
        assertTrue(AudioOutputRoutePolicy.isEncodedPassthroughTransport(
                AudioDeviceInfo.TYPE_LINE_DIGITAL));
        assertFalse(AudioOutputRoutePolicy.isEncodedPassthroughTransport(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertFalse(AudioOutputRoutePolicy.isEncodedPassthroughTransport(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
    }
}
