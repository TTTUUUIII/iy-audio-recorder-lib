package com.xiaoxuan.audio.recorderlib.recorder;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import com.xiaoxuan.audio.PCMGainer;
import com.xiaoxuan.audio.recorderlib.recorder.listener.RecordDataListener;
import com.xiaoxuan.audio.recorderlib.recorder.listener.RecordFftDataListener;
import com.xiaoxuan.audio.recorderlib.recorder.listener.RecordResultListener;
import com.xiaoxuan.audio.recorderlib.recorder.listener.RecordSoundSizeListener;
import com.xiaoxuan.audio.recorderlib.recorder.listener.RecordStateListener;
import com.xiaoxuan.audio.recorderlib.recorder.mp3.Mp3EncodeThread;
import com.xiaoxuan.audio.recorderlib.recorder.wav.WavUtils;
import com.xiaoxuan.audio.recorderlib.utils.ByteUtils;
import com.xiaoxuan.audio.recorderlib.utils.FileUtils;
import com.xiaoxuan.audio.recorderlib.utils.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fftlib.FftFactory;

public class RecordHelper
{
    private static final String TAG = RecordHelper.class.getSimpleName();
    
    private volatile static RecordHelper instance;
    
    private volatile RecordState state = RecordState.IDLE;
    
    private static final int RECORD_AUDIO_BUFFER_TIMES = 1;
    
    private RecordStateListener recordStateListener;
    
    private RecordDataListener recordDataListener;
    
    private RecordSoundSizeListener recordSoundSizeListener;
    
    private RecordResultListener recordResultListener;
    
    private RecordFftDataListener recordFftDataListener;
    
    private RecordConfig currentConfig;
    
    private AudioRecordThread audioRecordThread;
    
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private File resultFile = null;

    private Path mTmpFilesDir;
    private File tmpFile = null;
    
    private List<File> files = new ArrayList<>();
    
    private Mp3EncodeThread mp3EncodeThread;

    private float recordVolume = 1f;

    private float mMaxGain = 1.0f;
    
    private RecordHelper()
    {
        mTmpFilesDir = getDefaultTmpFilesDir();
    }
    
    static RecordHelper getInstance()
    {
        if (instance == null)
        {
            synchronized (RecordHelper.class)
            {
                if (instance == null)
                {
                    instance = new RecordHelper();
                }
            }
        }
        return instance;
    }
    
    RecordState getState()
    {
        return state;
    }
    
    void setRecordStateListener(RecordStateListener recordStateListener)
    {
        this.recordStateListener = recordStateListener;
    }
    
    void setRecordDataListener(RecordDataListener recordDataListener)
    {
        this.recordDataListener = recordDataListener;
    }
    
    void setRecordSoundSizeListener(RecordSoundSizeListener recordSoundSizeListener)
    {
        this.recordSoundSizeListener = recordSoundSizeListener;
    }
    
    void setRecordResultListener(RecordResultListener recordResultListener)
    {
        this.recordResultListener = recordResultListener;
    }
    
    public void setRecordFftDataListener(RecordFftDataListener recordFftDataListener)
    {
        this.recordFftDataListener = recordFftDataListener;
    }
    
    public void start(String filePath, RecordConfig config)
    {
        this.currentConfig = config;
        if (state != RecordState.IDLE && state != RecordState.STOP)
        {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        resultFile = new File(filePath);
        tmpFile = mTmpFilesDir.resolve(newTmpFileName()).toFile();
        
        Logger.d(TAG, "----------------开始录制 %s------------------------", currentConfig.getFormat().name());
        Logger.d(TAG, "参数： %s", currentConfig.toString());
        Logger.i(TAG, "pcm缓存 tmpFile: %s", tmpFile.getAbsolutePath());
        Logger.i(TAG, "录音文件 resultFile: %s", filePath);

        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }
    
    public void stop()
    {
        if (state == RecordState.IDLE)
        {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        
        if (state == RecordState.PAUSE)
        {
            makeFile();
            state = RecordState.IDLE;
            notifyState();
            stopMp3Encoded();
        }
        else
        {
            state = RecordState.STOP;
            notifyState();
        }
    }
    
    void pause()
    {
        if (state != RecordState.RECORDING)
        {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        state = RecordState.PAUSE;
        notifyState();
    }
    
    void resume()
    {
        if (state != RecordState.PAUSE)
        {
            Logger.e(TAG, "状态异常当前状态： %s", state.name());
            return;
        }
        tmpFile = mTmpFilesDir.resolve(newTmpFileName()).toFile();
        Logger.i(TAG, "tmpPCM File: %s", tmpFile.getAbsolutePath());
        audioRecordThread = new AudioRecordThread();
        audioRecordThread.start();
    }
    
    private void notifyState()
    {
        if (recordStateListener == null)
        {
            return;
        }
        mainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                recordStateListener.onStateChange(state);
            }
        });
        
        if (state == RecordState.STOP || state == RecordState.PAUSE)
        {
            if (recordSoundSizeListener != null)
            {
                recordSoundSizeListener.onSoundSize(0);
            }
        }
    }
    
    private void notifyFinish()
    {
        Logger.d(TAG, "录音结束 file: %s", resultFile.getAbsolutePath());
        
        mainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                if (recordStateListener != null)
                {
                    recordStateListener.onStateChange(RecordState.FINISH);
                }
                if (recordResultListener != null)
                {
                    recordResultListener.onResult(resultFile);
                }
            }
        });
    }
    
    private void notifyError(final String error)
    {
        if (recordStateListener == null)
        {
            return;
        }
        mainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                recordStateListener.onError(error);
            }
        });
    }
    
    private FftFactory fftFactory = new FftFactory(FftFactory.Level.Original);
    
    private void notifyData(final byte[] data)
    {
        if (recordDataListener == null && recordSoundSizeListener == null && recordFftDataListener == null)
        {
            return;
        }
        mainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                if (recordDataListener != null)
                {
                    recordDataListener.onData(data);
                }
                
                if (recordFftDataListener != null || recordSoundSizeListener != null)
                {
                    byte[] fftData = fftFactory.makeFftData(data);
                    if (fftData != null)
                    {
                        if (recordSoundSizeListener != null)
                        {
                            recordSoundSizeListener.onSoundSize(getDb(fftData));
                        }
                        if (recordFftDataListener != null)
                        {
                            recordFftDataListener.onFftData(fftData);
                        }
                    }
                }
            }
        });
    }
    
    private int getDb(byte[] data)
    {
        double sum = 0;
        double ave;
        int length = data.length > 128 ? 128 : data.length;
        int offsetStart = 8;
        for (int i = offsetStart; i < length; i++)
        {
            sum += data[i];
        }
        ave = (sum / (length - offsetStart)) * 65536 / 128f;
        int i = (int)(Math.log10(ave) * 20);
        return i < 0 ? 27 : i;
    }
    
    private void initMp3EncoderThread(int bufferSize)
    {
        try
        {
            mp3EncodeThread = new Mp3EncodeThread(resultFile, bufferSize);
            mp3EncodeThread.start();
        }
        catch (Exception e)
        {
            Logger.e(e, TAG, e.getMessage());
        }
    }
    
    private class AudioRecordThread extends Thread
    {
        private AudioRecord audioRecord;
        
        private int bufferSize;
        
        AudioRecordThread()
        {
            bufferSize = AudioRecord.getMinBufferSize(currentConfig.getSampleRate(),
                currentConfig.getChannelConfig(),
                currentConfig.getEncodingConfig()) * RECORD_AUDIO_BUFFER_TIMES;
            Logger.d(TAG, "record buffer size = %s", bufferSize);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, currentConfig.getSampleRate(),
                currentConfig.getChannelConfig(), currentConfig.getEncodingConfig(), bufferSize);
            if (currentConfig.getFormat() == RecordConfig.RecordFormat.MP3)
            {
                if (mp3EncodeThread == null)
                {
                    initMp3EncoderThread(bufferSize);
                }
                else
                {
                    Logger.e(TAG, "mp3EncodeThread != null, 请检查代码");
                }
            }
        }
        
        @Override
        public void run()
        {
            super.run();
            
            switch (currentConfig.getFormat())
            {
                case MP3:
                    startMp3Recorder();
                    break;
                default:
                    startPcmRecorder();
                    break;
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private void startPcmRecorder()
        {
            state = RecordState.RECORDING;
            notifyState();
            Logger.d(TAG, "开始录制 Pcm");
            try (PCMGainer pcmGainer = new PCMGainer(tmpFile, mMaxGain))
            {
                audioRecord.startRecording();
                byte[] byteBuffer = new byte[bufferSize];

                while (state == RecordState.RECORDING)
                {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
                    notifyData(byteBuffer);
                    pcmGainer.write(byteBuffer, 0, end);
                }
                audioRecord.stop();
            }
            catch (IOException ioException)
            {
                Logger.e(ioException, TAG, ioException.getMessage());
                notifyError("录音失败");
            }
            try {
                if (state == RecordState.STOP)
                {
                    files.add(tmpFile);
                    makeFile();
                }
                else
                {
                    Logger.i(TAG, "暂停！");
                }
            }catch (Exception e) {
                e.printStackTrace();
                notifyError("保存失败！");
            }
            if (state != RecordState.PAUSE)
            {
                state = RecordState.IDLE;
                notifyState();
                Logger.d(TAG, "录音结束");
            }
        }
        
        private void startMp3Recorder()
        {
            state = RecordState.RECORDING;
            notifyState();
            
            try
            {
                audioRecord.startRecording();
                short[] byteBuffer = new short[bufferSize];
                
                while (state == RecordState.RECORDING)
                {
                    int end = audioRecord.read(byteBuffer, 0, byteBuffer.length);
//                    for(int i=0;i<byteBuffer.length;i++)
//                    {
//                        byteBuffer[i]= (byte) (byteBuffer[i]*recordVolume);
//                    }
                    if (mp3EncodeThread != null)
                    {
                        mp3EncodeThread.addChangeBuffer(new Mp3EncodeThread.ChangeBuffer(byteBuffer, end));
                    }
                    notifyData(ByteUtils.toBytes(byteBuffer));
                }
                audioRecord.stop();
            }
            catch (Exception e)
            {
                Logger.e(e, TAG, e.getMessage());
                notifyError("录音失败");
            }
            if (state != RecordState.PAUSE)
            {
                state = RecordState.IDLE;
                notifyState();
                stopMp3Encoded();
            }
            else
            {
                Logger.d(TAG, "暂停");
            }
        }
    }
    
    private void stopMp3Encoded()
    {
        if (mp3EncodeThread != null)
        {
            mp3EncodeThread.stopSafe(new Mp3EncodeThread.EncordFinishListener()
            {
                @Override
                public void onFinish()
                {
                    notifyFinish();
                    mp3EncodeThread = null;
                }
            });
        }
        else
        {
            Logger.e(TAG, "mp3EncodeThread is null, 代码业务流程有误，请检查！！ ");
        }
    }
    
    private void makeFile()
    {
        switch (currentConfig.getFormat())
        {
            case MP3:
                return;
            case WAV:
                mergePcmFile();
                makeWav();
                break;
            case PCM:
                mergePcmFile();
                break;
            default:
                break;
        }
        notifyFinish();
        Logger.i(TAG, "录音完成！ path: %s ； 大小：%s", resultFile.getAbsoluteFile(), resultFile.length());
    }
    
    /**
     * 添加Wav头文件
     */
    private void makeWav()
    {
        if (!FileUtils.isFile(resultFile) || resultFile.length() == 0)
        {
            return;
        }
        byte[] header = WavUtils.generateWavFileHeader((int)resultFile.length(),
            currentConfig.getSampleRate(),
            currentConfig.getChannelCount(),
            currentConfig.getEncoding());
        WavUtils.writeHeader(resultFile, header);
    }
    
    /**
     * 合并文件
     */
    private void mergePcmFile()
    {
        boolean mergeSuccess = mergePcmFiles(resultFile, files);
        if (!mergeSuccess)
        {
            notifyError("合并失败");
        }
    }
    
    /**
     * 合并Pcm文件
     *
     * @param recordFile 输出文件
     * @param files 多个文件源
     * @return 是否成功
     */
    private boolean mergePcmFiles(File recordFile, List<File> files)
    {
        if (recordFile == null || files == null || files.size() <= 0)
        {
            return false;
        }
        
        FileOutputStream fos = null;
        BufferedOutputStream outputStream = null;
        byte[] buffer = new byte[1024];
        try
        {
            fos = new FileOutputStream(recordFile);
            outputStream = new BufferedOutputStream(fos);
            
            for (int i = 0; i < files.size(); i++)
            {
                BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(files.get(i)));
                int readCount;
                while ((readCount = inputStream.read(buffer)) > 0)
                {
                    outputStream.write(buffer, 0, readCount);
                }
                inputStream.close();
            }
        }
        catch (Exception e)
        {
            Logger.e(e, TAG, e.getMessage());
            return false;
        }
        finally
        {
            try
            {
                if (outputStream != null)
                {
                    outputStream.close();
                }
                if (fos != null)
                {
                    fos.close();
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        for (int i = 0; i < files.size(); i++)
        {
            files.get(i).delete();
        }
        files.clear();
        return true;
    }

    public boolean setTmpFilesDir(Path tmpFilesDir)
    {
        boolean isOk = true;
        File filesDir = tmpFilesDir.toFile();
        if (!filesDir.exists()) filesDir.mkdirs();
        if (filesDir.exists())
        {
            if (filesDir.isFile()) {
                filesDir.delete();
                if (filesDir.mkdirs()) mTmpFilesDir = tmpFilesDir;
                else {
                    mTmpFilesDir = getDefaultTmpFilesDir();
                    isOk = false;
                }
            } else mTmpFilesDir = tmpFilesDir;
        } else {
            mTmpFilesDir = getDefaultTmpFilesDir();
            isOk = false;
        }
        return isOk;
    }

    public Path getTmpFilePath()
    {
        return tmpFile.toPath();
    }
    /**
     * 根据当前的时间生成相应的文件名 实例 record_20160101_13_15_12
     */
    private Path getDefaultTmpFilesDir()
    {
        String fileDir = String
            .format(Locale.getDefault(), "%s/Record/", Environment.getExternalStorageDirectory().getAbsolutePath());
        if (!FileUtils.createOrExistsDir(fileDir))
        {
            Logger.e(TAG, "文件夹创建失败：%s", fileDir);
        }

        return Paths.get(fileDir);
    }

    private String newTmpFileName()
    {
        return String.format(Locale.getDefault(),
                "record_tmp_%s.pcm",
                FileUtils.getNowString(new SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.SIMPLIFIED_CHINESE)));
    }
    /**
     * 表示当前状态
     */
    public enum RecordState
    {
        /**
         * 空闲状态
         */
        IDLE,
        /**
         * 录音中
         */
        RECORDING,
        /**
         * 暂停中
         */
        PAUSE,
        /**
         * 正在停止
         */
        STOP,
        /**
         * 录音流程结束（转换结束）
         */
        FINISH
    }

    public void setMaxGain(float gain)
    {
        mMaxGain = gain;
    }

    public float getMaxGain()
    {
        return mMaxGain;
    }
}
