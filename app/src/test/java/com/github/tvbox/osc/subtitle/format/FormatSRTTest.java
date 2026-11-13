package com.github.tvbox.osc.subtitle.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FormatSRTTest {
    @Test
    public void acceptsCueNumbersThatStartAfterOne() throws Exception {
        TimedTextObject parsed = parse("7\n00:00:01,000 --> 00:00:05,000\nHello\n\n");

        assertEquals(1, parsed.captions.size());
        assertCue(parsed.captions.firstEntry().getValue(), 1000, 5000, "Hello<br />");
    }

    @Test
    public void acceptsNonContiguousAndRepeatedCueNumbers() throws Exception {
        TimedTextObject parsed = parse("1\n00:00:01,000 --> 00:00:02,000\nOne\n\n"
                + "8\n00:00:03,000 --> 00:00:04,000\nEight\n\n"
                + "8\n00:00:05,000 --> 00:00:06,000\nRepeated\n\n");

        List<Subtitle> cues = new ArrayList<>(parsed.captions.values());
        assertEquals(3, cues.size());
        assertCue(cues.get(0), 1000, 2000, "One<br />");
        assertCue(cues.get(1), 3000, 4000, "Eight<br />");
        assertCue(cues.get(2), 5000, 6000, "Repeated<br />");
    }

    @Test
    public void commitsFinalCueAtEndOfFileWithoutBlankLine() throws Exception {
        TimedTextObject parsed = parse("1\n00:00:01,000 --> 00:00:05,000\nLast line");

        assertEquals(1, parsed.captions.size());
        assertCue(parsed.captions.firstEntry().getValue(), 1000, 5000, "Last line<br />");
    }

    @Test
    public void commitsFinalCueWithOnlyTrailingNewline() throws Exception {
        TimedTextObject parsed = parse("7\n00:00:01,000 --> 00:00:05,000\nHello\n");

        assertEquals(1, parsed.captions.size());
        assertCue(parsed.captions.firstEntry().getValue(), 1000, 5000, "Hello<br />");
    }

    @Test
    public void preservesNormalMultilineCuesAndTimes() throws Exception {
        TimedTextObject parsed = parse("1\r\n00:00:01,250 --> 00:00:02,500\r\n"
                + "First line\r\nSecond line\r\n\r\n"
                + "2\r\n00:00:03,000 --> 00:00:04,000\r\nNext cue\r\n\r\n");

        List<Subtitle> cues = new ArrayList<>(parsed.captions.values());
        assertTrue(parsed.built);
        assertEquals(2, cues.size());
        assertCue(cues.get(0), 1250, 2500, "First line<br />Second line<br />");
        assertCue(cues.get(1), 3000, 4000, "Next cue<br />");
    }

    private static TimedTextObject parse(String text) throws Exception {
        return new FormatSRT().parseFile("sample.srt",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertCue(Subtitle cue, int start, int end, String content) {
        assertEquals(start, cue.start.mseconds);
        assertEquals(end, cue.end.mseconds);
        assertEquals(content, cue.content);
    }
}
