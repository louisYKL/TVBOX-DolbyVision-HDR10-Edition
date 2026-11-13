/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.subtitle.model.Subtitle;

import java.util.List;

/**
 * @author AveryZhong.
 */

public class SubtitleFinder {
    private SubtitleFinder() {
        throw new AssertionError("No instance for you");
    }

    @Nullable
    public static Subtitle find(long position, List<Subtitle> subtitles) {
        if (subtitles == null || subtitles.isEmpty()) {
            return null;
        }
        // Find the last cue whose start is at or before the playback clock. A plain interval
        // binary search can skip a valid cue when ASS contains overlapping dialogue events.
        int low = 0;
        int high = subtitles.size() - 1;
        int candidate = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Subtitle cue = subtitles.get(middle);
            if (cue == null || cue.start == null || cue.start.mseconds > position) {
                high = middle - 1;
            } else {
                candidate = middle;
                low = middle + 1;
            }
        }
        // Walk backwards through overlapping cues so the renderer never loses a valid event
        // merely because a later-starting line was stored in the middle of the search range.
        for (int index = candidate; index >= 0; index--) {
            Subtitle cue = subtitles.get(index);
            if (cue == null || cue.start == null || cue.end == null) {
                continue;
            }
            if (cue.start.mseconds <= position && position <= cue.end.mseconds) {
                return cue;
            }
        }
        return null;
    }
}
