package com.xiaoxuan.audio;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.xiaoxuan.audio.recorderlib.utils.ByteUtils;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class PCMGainer implements Closeable {

    private static final String TAG = PCMGainer.class.getSimpleName();
    private float mMaxGain;
    private FileOutputStream mTmpOutStream;
    private Path mTmpTo;
    private Path mSaveTo;

    public PCMGainer(Path saveTo, float maxGain) throws IOException {
        mSaveTo = saveTo;
        mMaxGain = maxGain;
        Path parent = mSaveTo.getParent();
        String tmpName = SystemClock.uptimeMillis() + ".pcm";
        if (Objects.nonNull(parent)) mTmpTo = parent.resolve(tmpName);
        else mTmpTo = Paths.get(tmpName);
        File tmpFile = mTmpTo.toFile();
        if (tmpFile.exists()) tmpFile.delete();
        tmpFile.createNewFile();
        mTmpOutStream = new FileOutputStream(tmpFile);
        Log.d(TAG, String.format("saveTo=%s, maxGain=%f", saveTo, maxGain));
    }

    public PCMGainer(File saveTo, float maxGain) throws IOException {
        this(saveTo.toPath(), maxGain);
    }

    private short mMaxAmp = Short.MIN_VALUE;

    public void write(byte[] arr, int offset, int len) {
        selectMax(arr);
        try {
            mTmpOutStream.write(arr, offset, len);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            mTmpOutStream.flush();
            mTmpOutStream.close();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
        Log.d(TAG, "allow max gain=" + allowMaxGain());
        try (FileInputStream in = new FileInputStream(mTmpTo.toFile())){
            int len;
            byte[] buffer = new byte[1024];
            try (FileOutputStream out = new FileOutputStream(mSaveTo.toFile())){
                while ((len = in.read(buffer, 0, buffer.length)) != -1)
                {
                    short[] data = toGain(ByteUtils.toShorts(buffer));
                    out.write(ByteUtils.toBytes(data), 0, len);
                    out.flush();
                }
            }
        }catch (IOException ioException) {
            ioException.printStackTrace();
        }
        mTmpTo.toFile().delete();
    }

    private short[] toGain(short[] desc)
    {
        float gain = (float) Short.MAX_VALUE / mMaxAmp;
        gain = gain > mMaxGain? mMaxGain : gain;
        for (int i = 0; i < desc.length; ++i) desc[i] *= gain;
        return desc;
    }

    public float allowMaxGain()
    {
        return (float) Short.MAX_VALUE / mMaxAmp;
    }

    private void selectMax(byte[] arr) {
        short[] shortArray = ByteUtils.toShorts(arr);
        for (int i = 0; i < shortArray.length; ++i) if (mMaxAmp < shortArray[i]) mMaxAmp = shortArray[i];
    }
}
