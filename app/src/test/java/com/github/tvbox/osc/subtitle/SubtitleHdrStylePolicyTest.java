package com.github.tvbox.osc.subtitle;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SubtitleHdrStylePolicyTest {
    @Test
    public void plainTextCuesReceiveHdrSubtitleStyle() {
        assertTrue(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "A plain English subtitle line."));
        assertTrue(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "第一行普通字幕\n第二行普通字幕"));
        assertTrue(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "Line with punctuation: < 5 and 8 > 3"));
    }

    @Test
    public void emptyAndSimpleSubtitleCuesInitializeAllRules() {
        assertTrue(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false, ""));
        assertTrue(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false, "plain"));
    }

    @Test
    public void knownTrackStyleMetadataPreservesTheSourceStyle() {
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(true, "plain cue text"));
    }

    @Test
    public void htmlSubtitleStylingIsNotOverridden() {
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "<font color=\"#f0d040\">gold text</font>"));
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "<span style=\"color: yellow; text-shadow: 1px 1px black\">styled</span>"));
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "<i>italic</i> and <b>bold</b>"));
    }

    @Test
    public void assAndSsaOverridesAndEffectsAreNotOverridden() {
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "{\\c&H00D0FF&}colored{\\r}"));
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "{\\an8}{\\blur2\\t(0,500,\\alpha&H80&)}effect"));
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "{\\move(20,30,400,300)}positioned"));
    }

    @Test
    public void webVttCueMarkupIsNotOverridden() {
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "<v Narrator>spoken text</v>"));
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "<c.yellow>yellow text</c>"));
        assertFalse(SubtitleHdrStylePolicy.shouldApplyHdrStyle(false,
                "<ruby>字幕<rt>じまく</rt></ruby>"));
    }
}
