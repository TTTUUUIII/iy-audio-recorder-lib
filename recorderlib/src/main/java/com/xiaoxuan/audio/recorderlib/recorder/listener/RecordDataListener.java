package com.xiaoxuan.audio.recorderlib.recorder.listener;

public interface RecordDataListener
{
    
    /**
     * 当前的录音状态发生变化
     *
     * @param data 当前音频数据
     */
    void onData(byte[] data);
    
}
