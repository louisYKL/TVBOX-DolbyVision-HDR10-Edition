package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackPrebufferPolicyTest {
    private static final long MB = 1024L * 1024L;

    @Test
    public void fillAheadUsesTheConfiguredLargeBuffer() {
        assertEquals(24L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                24L * MB, 20_327_632_348L, 10L * MB));
    }

    @Test
    public void endOfMediaOnlyRequiresTheRemainingBytes() {
        assertEquals(3L * MB, PlaybackPrebufferPolicy.resolveRequiredBytes(
                24L * MB, 100L * MB, 97L * MB));
    }

    @Test
    public void hundredThousandSeekTargetsNeverOverflowOrExceedRemainingMedia() {
        long total = 20_327_632_348L;
        long configured = 24L * MB;
        for (int seek = 0; seek < 100_000; seek++) {
            long anchor = ((long) seek * 13_771_337L) % total;
            long required = PlaybackPrebufferPolicy.resolveRequiredBytes(configured, total, anchor);
            assertTrue(required >= 0L);
            assertTrue(required <= configured);
            assertTrue(required <= total - anchor);
        }
    }

    @Test
    public void activePrebufferKeepsItsAnchorWhileDecoderReadHeadAdvances() {
        long fixedAnchor = 1_797_259_264L;
        for (int read = 0; read < 100_000; read++) {
            long liveReadHead = fixedAnchor + (long) read * 128L * 1024L;
            assertEquals(fixedAnchor, PlaybackPrebufferPolicy.resolveAnchor(
                    true, fixedAnchor, liveReadHead, 0L));
        }
    }

    @Test
    public void completedPrebufferFollowsTheLiveReadHeadAgain() {
        long liveReadHead = 1_830_813_696L;
        assertEquals(liveReadHead, PlaybackPrebufferPolicy.resolveAnchor(
                false, 1_797_259_264L, liveReadHead, 0L));
    }
}
