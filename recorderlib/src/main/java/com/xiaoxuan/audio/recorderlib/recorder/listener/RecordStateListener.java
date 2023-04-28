package com.xiaoxuan.audio.recorderlib.recorder.listener;

import com.xiaoxuan.audio.recorderlib.recorder.RecordHelper;

public interface RecordStateListener
{
    /**
     * 当前的录音状态发生变化
     *
     * @param state 当前状态
     */
    void onStateChange(RecordHelper.RecordState state);
    
    /**
     * 录音错误
     *
     * @param error 错误
     */
    void onError(String error);
    
}
