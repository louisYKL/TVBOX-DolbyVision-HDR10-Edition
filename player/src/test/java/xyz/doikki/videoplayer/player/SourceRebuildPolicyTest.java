package xyz.doikki.videoplayer.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceRebuildPolicyTest {
    @Test
    public void invalidExtractorCanBeReplacedOnceAtTheResumeTarget() {
        assertTrue(SourceRebuildPolicy.shouldRebuild(true, true, 0, 717_016L));
        assertFalse(SourceRebuildPolicy.shouldRebuild(true, true, 1, 717_016L));
    }

    @Test
    public void missingSourceOrPlayerCannotStartARebuildLoop() {
        assertFalse(SourceRebuildPolicy.shouldRebuild(false, true, 0, 717_016L));
        assertFalse(SourceRebuildPolicy.shouldRebuild(true, false, 0, 717_016L));
    }

    @Test
    public void timedOutSeekToStartCanRebuildOnce() {
        assertTrue(SourceRebuildPolicy.shouldRebuild(true, true, 0, 0L));
        assertFalse(SourceRebuildPolicy.shouldRebuild(true, true, 1, 0L));
    }

    @Test
    public void rebuildBudgetReturnsOnlyAfterRealPlaybackAdvances() {
        assertFalse(SourceRebuildPolicy.hasAdvancedBeyondResume(717_016L, 0L));
        assertFalse(SourceRebuildPolicy.hasAdvancedBeyondResume(717_016L, 717_016L));
        assertFalse(SourceRebuildPolicy.hasAdvancedBeyondResume(717_016L, 718_016L));
        assertTrue(SourceRebuildPolicy.hasAdvancedBeyondResume(717_016L, 718_017L));
    }
}
