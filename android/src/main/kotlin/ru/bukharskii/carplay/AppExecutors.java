package ru.bukharskii.carplay;


import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AppExecutors
{
    private static class BackgroundThreadExecutor implements Executor
    {
        private final Executor executor;

        public BackgroundThreadExecutor()
        {
            executor = Executors.newSingleThreadExecutor();
        }

        @Override
        public void execute(@NonNull Runnable command)
        {
            executor.execute(command);
        }
    }

    private static class MainThreadExecutor implements Executor
    {
        private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable command)
        {
            mainThreadHandler.post(command);
        }
    }

    private static volatile AppExecutors INSTANCE;

    private final BackgroundThreadExecutor usbIn;
    private final BackgroundThreadExecutor usbOut;
    private final BackgroundThreadExecutor mediaCodec1;
    private final BackgroundThreadExecutor mediaCodec2;
    private final BackgroundThreadExecutor mediaCodec;
    private final MainThreadExecutor mainThread;

    private AppExecutors()
    {
        usbIn = new BackgroundThreadExecutor();
        usbOut = new BackgroundThreadExecutor();
        mediaCodec = new BackgroundThreadExecutor();
        mediaCodec1 = new BackgroundThreadExecutor();
        mediaCodec2 = new BackgroundThreadExecutor();
        mainThread = new MainThreadExecutor();
    }

    public static AppExecutors getInstance()
    {
        if(INSTANCE == null)
        {
            synchronized(AppExecutors.class)
            {
                if(INSTANCE == null)
                {
                    INSTANCE = new AppExecutors();
                }
            }
        }
        return INSTANCE;
    }

    public Executor usbIn()
    {
        return usbIn;
    }

    public Executor usbOut()
    {
        return usbOut;
    }

    public Executor mediaCodec() {
        return mediaCodec;
    }

    public Executor mediaCodec1() {
        return mediaCodec1;
    }

    public Executor mediaCodec2() {
        return mediaCodec2;
    }

    public Executor mainThread()
    {
        return mainThread;
    }
}