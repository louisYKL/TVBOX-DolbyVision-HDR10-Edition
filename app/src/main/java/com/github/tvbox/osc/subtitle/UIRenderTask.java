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

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;

/**
 * @author AveryZhong.
 */

public class UIRenderTask {

    private final SubtitleEngine.OnSubtitleChangeListener mOnSubtitleChangeListener;
    /**
     * A subtitle selection can be replaced while callbacks are queued on the main looper.
     * Cancelling the task prevents an old cue from being rendered into the new selection.
     */
    private volatile boolean mCancelled;

    public UIRenderTask(final SubtitleEngine.OnSubtitleChangeListener l) {
        mOnSubtitleChangeListener = l;
    }

    public void cancel() {
        mCancelled = true;
    }

    public void execute(final Subtitle subtitle) {
        execute(subtitle, null);
    }

    public void execute(final Subtitle subtitle, final GenerationGuard generationGuard) {
        if (mCancelled) {
            return;
        }
        AppTaskExecutor.mainThread().execute(new Runnable() {
            @Override
            public void run() {
                if (!mCancelled
                        && (generationGuard == null || generationGuard.isCurrent())
                        && mOnSubtitleChangeListener != null) {
                    mOnSubtitleChangeListener.onSubtitleChanged(subtitle);
                }
            }
        });
    }

    public interface GenerationGuard {
        boolean isCurrent();
    }
}
