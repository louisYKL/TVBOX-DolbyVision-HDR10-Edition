package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePlayerCallbackPolicyTest {
    @Test
    public void onlyTheCurrentNativePlayerCanMutatePlaybackState() {
        Object current = new Object();
        Object retired = new Object();

        assertTrue(NativePlayerCallbackPolicy.isCurrent(current, current));
        assertFalse(NativePlayerCallbackPolicy.isCurrent(retired, current));
        assertFalse(NativePlayerCallbackPolicy.isCurrent(null, current));
        assertFalse(NativePlayerCallbackPolicy.isCurrent(current, null));
    }

    @Test
    public void replacingThePlayerImmediatelyInvalidatesOldCallbacks() {
        Object first = new Object();
        Object replacement = new Object();
        Object current = first;

        assertTrue(NativePlayerCallbackPolicy.isCurrent(first, current));
        current = replacement;
        assertFalse(NativePlayerCallbackPolicy.isCurrent(first, current));
        assertTrue(NativePlayerCallbackPolicy.isCurrent(replacement, current));
    }
}
