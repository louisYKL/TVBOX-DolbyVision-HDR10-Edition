package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;

import org.junit.Test;

import java.util.Arrays;

public class SubtitleFinderTest {
    @Test
    public void findsCueAtBoundary() {
        Subtitle cue = cue(1000, 2000);
        assertSame(cue, SubtitleFinder.find(1000, Arrays.asList(cue)));
        assertSame(cue, SubtitleFinder.find(2000, Arrays.asList(cue)));
    }

    @Test
    public void findsOverlappingCueAfterBinarySearch() {
        Subtitle longCue = cue(0, 5000);
        Subtitle shortCue = cue(1000, 1500);
        assertSame(shortCue, SubtitleFinder.find(1200, Arrays.asList(longCue, shortCue)));
    }

    @Test
    public void returnsNullOutsideCue() {
        Subtitle cue = cue(1000, 2000);
        assertNull(SubtitleFinder.find(2500, Arrays.asList(cue)));
    }

    private static Subtitle cue(int startMs, int endMs) {
        Subtitle result = new Subtitle();
        result.start = time(startMs);
        result.end = time(endMs);
        result.content = "cue";
        return result;
    }

    private static Time time(int milliseconds) {
        Time result = new Time("hh:mm:ss,ms", "00:00:00,000");
        result.mseconds = milliseconds;
        return result;
    }
}
