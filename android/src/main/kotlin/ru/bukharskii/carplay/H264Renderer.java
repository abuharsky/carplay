package ru.bukharskii.carplay;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimerTask;


public class H264Renderer {
    private static final String LOG_TAG = "H264Renderer";
    protected final SurfaceTexture texture;
    private MediaCodec mCodec;
    private MediaCodec.Callback codecCallback;
    private ArrayList<Integer> codecAvailableBufferIndexes = new ArrayList<>(10);
    private int width;
    private int height;
    private Surface surface;
    private boolean running = false;
    private boolean bufferLoopRunning = false;
    private LogCallback logCallback;

    private AppExecutors executors = AppExecutors.getInstance();

    private PacketRingByteBuffer ringBuffer;

    public H264Renderer(Context context, int width, int height, SurfaceTexture texture, int textureId, LogCallback logCallback) {
        this.width = width;
        this.height = height;
        this.texture = texture;
        this.logCallback = logCallback;

        surface = new Surface(texture);

        ringBuffer = new PacketRingByteBuffer(1024 * 500);

        codecCallback = createCallback();
    }

    private void log(String message) {

        logCallback.log(message);
    }

    public void start() {
        if (running) return;

        running = true;

        log("start");

        try {
            initCodec(width, height, surface);
            mCodec.start();
        } catch (Exception e) {
            log("start error " + e.toString());
            e.printStackTrace();

            log("restarting in 5s ");
            new java.util.Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    if (running) {
                        start();
                    }
                }
            }, 5000);
        }
    }

    private boolean fillFirstAvailableCodecBuffer(MediaCodec codec) {

        if (codec != mCodec) return false;

        if (ringBuffer.isEmpty() || codecAvailableBufferIndexes.isEmpty()) return false;

        synchronized (codecAvailableBufferIndexes) {
            int index = codecAvailableBufferIndexes.remove(0);

            ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
            byteBuffer.put(ringBuffer.readPacket());

            mCodec.queueInputBuffer(index, 0, byteBuffer.position(), 0, 0);
        }

        return true;
    }

    private void fillAllAvailableCodecBuffers(MediaCodec codec) {
        boolean filled = true;

        while (filled) {
            filled = fillFirstAvailableCodecBuffer(codec);
        }
    }

    private void feedCodec() {
        executors.mediaCodec1().execute(() -> {
            try {
                fillAllAvailableCodecBuffers(mCodec);
            } catch (Exception e) {
                log("[Media Codec] fill input buffer error:" + e.toString());

                try {
                    mCodec.stop();
                    mCodec.reset();
                    mCodec.setCallback(codecCallback);
                    mCodec.start();
                } catch (Exception e1) {
                    log("[Media Codec] reset codec error:" + e.toString());

                    // re-create codec
                    executors.mainThread().execute(() -> reset());
                }
            }
        });
    }

    public void stop() {
        if (!running) return;

        running = false;

        mCodec.stop();
        mCodec.release();

        mCodec = null;
    }

    public void reset() {
        stop();
        start();
    }

    private void initCodec(int width, int height, Surface surface) throws Exception {
        log("init media codec");

        mCodec = MediaCodec.createDecoderByType("video/avc");

        final MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);

        log("configure media codec");
        mCodec.configure(mediaformat, surface, null, 0);

        codecAvailableBufferIndexes.clear();

        log("media codec in async mode");
        mCodec.setCallback(codecCallback);
    }

    public void processDataDirect(int length, int skipBytes, PacketRingByteBuffer.DirectWriteCallback callback) {
        ringBuffer.directWriteToBuffer(length, skipBytes, callback);
        feedCodec();
    }

    ////////////////////////////////////////

    private MediaCodec.Callback createCallback() {
        return new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                if (codec != mCodec) return;

//                log("[Media Codec] onInputBufferAvailable index:" + index);
                synchronized (codecAvailableBufferIndexes) {
                    codecAvailableBufferIndexes.add(index);
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                if (codec != mCodec) return;

  //              log("[Media Codec] onOutputBufferAvailable index:" + index);

                executors.mediaCodec2().execute(() -> {
                    boolean doRender = (info.size != 0);
                    mCodec.releaseOutputBuffer(index, doRender);
                });

            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                if (codec != mCodec) return;

                log("[Media Codec] onError " + e.toString());
                reset();
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                if (codec != mCodec) return;

                log("[Media Codec] onOutputFormatChanged: " + format);
                log("onOutputFormatChanged: " + format.getInteger("color-format") + "size: " + format.getInteger("width") + "x" + format.getInteger("height"));
            }
        };
    }
}
