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

import java.util.function.LongSupplier;

/**
 * @author AveryZhong.
 */

public class UIRenderTask {

    private final SubtitleEngine.OnSubtitleChangeListener mOnSubtitleChangeListener;
    private final LongSupplier mCurrentEpoch;

    public UIRenderTask(final SubtitleEngine.OnSubtitleChangeListener l, LongSupplier currentEpoch) {
        mOnSubtitleChangeListener = l;
        mCurrentEpoch = currentEpoch;
    }

    public void execute(final Subtitle subtitle) {
        final long epoch = mCurrentEpoch.getAsLong();
        AppTaskExecutor.mainThread().execute(() -> {
            if (epoch == mCurrentEpoch.getAsLong() && mOnSubtitleChangeListener != null) {
                mOnSubtitleChangeListener.onSubtitleChanged(subtitle);
            }
        });
    }
}
