package com.vcam.ayuscam;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

import de.robv.android.xposed.XposedBridge;

public class VideoToFrames implements Runnable {
    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;

    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private LinkedBlockingQueue<byte[]> mQueue;
    private OutputImageFormat outputImageFormat;
    private boolean stopDecode = false;

    private String videoFilePath;
    private Thread childThread;
    private Surface play_surf;

    public enum OutputImageFormat {
        I420, NV21, JPEG
    }

    public void setSaveFrames(OutputImageFormat imageFormat) {
        outputImageFormat = imageFormat;
    }

    public void setSurface(Surface player_surface) {
        if (player_surface != null) {
            play_surf = player_surface;
        }
    }

    public void stopDecode() {
        stopDecode = true;
    }

    public void decode(String videoFilePath) {
        this.videoFilePath = videoFilePath;
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.start();
        }
    }

    @Override
    public void run() {
        try {
            videoDecode(videoFilePath);
        } catch (Throwable t) {
            XposedBridge.log("AyusCam: VideoToFrames Error - " + t.getMessage());
        }
    }

    private void videoDecode(String videoFilePath) throws IOException {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        FileInputStream fis = null;
        try {
            File videoFile = new File(videoFilePath);
            if (!videoFile.exists()) return;

            extractor = new MediaExtractor();
            
            // BYPASS DAEMON PATH RESTRICTIONS WITH FILEDESCRIPTOR
            fis = new FileInputStream(videoFile);
            extractor.setDataSource(fis.getFD());
            
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) return;
            extractor.selectTrack(trackIndex);

            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);

            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
            }

            decoder.configure(mediaFormat, play_surf, null, 0);
            decoder.start();

            while (!stopDecode) {
                decodeFramesToImage(decoder, extractor);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                decoder.flush();
            }
        } finally {
            if (decoder != null) {
                try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
            }
            if (extractor != null) {
                extractor.release();
            }
            if (fis != null) {
                try { fis.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor) {
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        long startWhen = 0;
        boolean is_first = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outputBufferId = decoder.dequeueOutputBuffer(info, 10000);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                boolean doRender = (info.size != 0);

                if (doRender) {
                    if (!is_first) {
                        startWhen = System.currentTimeMillis();
                        is_first = true;
                    }
                    if (play_surf == null) {
                        try {
                            Image image = decoder.getOutputImage(outputBufferId);
                            if (image != null) {
                                if (outputImageFormat != null) {
                                    HookMain.data_buffer = getDataFromImage(image, COLOR_FormatNV21);
                                }
                                image.close();
                            }
                        } catch (Exception e) {}
                    }

                    long sleepTime = (info.presentationTimeUs / 1000) - (System.currentTimeMillis() - startWhen);
                    if (sleepTime > 0) {
                        try { Thread.sleep(sleepTime); } catch (InterruptedException ignored) {}
                    }
                    decoder.releaseOutputBuffer(outputBufferId, true);
                }
            }
        }
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) return true;
        }
        return false;
    }

    private static byte[] getDataFromImage(Image image, int colorFormat) {
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        int channelOffset = 0;
        int outputStride = 1;

        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0: channelOffset = 0; outputStride = 1; break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) { channelOffset = width * height; outputStride = 1; }
                    else if (colorFormat == COLOR_FormatNV21) { channelOffset = width * height + 1; outputStride = 2; }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) { channelOffset = (int) (width * height * 1.25); outputStride = 1; }
                    else if (colorFormat == COLOR_FormatNV21) { channelOffset = width * height; outputStride = 2; }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;

            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
    }
}
