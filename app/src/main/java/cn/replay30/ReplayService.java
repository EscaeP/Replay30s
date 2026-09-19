package cn.replay30;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.TextView;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

public class ReplayService extends Service {
    public static final String START = "cn.replay30.START";
    public static final String SAVE = "cn.replay30.SAVE";
    public static final String STOP = "cn.replay30.STOP";
    public static final String OVERLAY = "cn.replay30.OVERLAY";
    public static final String HIDE_OVERLAY = "cn.replay30.HIDE_OVERLAY";

    public static volatile boolean active;
    public static volatile boolean starting;
    public static volatile boolean saving;
    public static volatile boolean paused;
    public static volatile double available;
    public static volatile String message = "";

    private static final String CHANNEL = "replay_cache";
    private static final int NOTIFICATION_ID = 1;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private MediaCodec audioEncoder;
    private AudioRecord audioRecord;
    private Thread drainThread;
    private Thread audioThread;
    private volatile boolean capturing;
    private volatile boolean audioCapturing;
    private final ReplayBuffer buffer = new ReplayBuffer();
    private MediaFormat outputFormat;
    private MediaFormat audioOutputFormat;
    private long audioBasePtsUs;
    private int videoWidth;
    private int videoHeight;
    private boolean usingCompatibilityResolution;
    private WindowManager windowManager;
    private View overlayView;
    private boolean overlayCollapsed;
    private int overlayFullSize;
    private final Runnable collapseOverlayTask = this::collapseOverlay;
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                stopCapture("屏幕已熄灭，已停止缓存。");
            }
        }
    };
    private float overlayX = 40f;
    private float overlayY = 200f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        screenReceiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        switch (intent.getAction()) {
            case START:
                startCapture(intent);
                break;
            case SAVE:
                saveReplay();
                break;
            case STOP:
                pauseCapture();
                break;
            case OVERLAY:
                if (active) showOverlay();
                break;
            case HIDE_OVERLAY:
                removeOverlay();
                break;
        }
        return START_NOT_STICKY;
    }

    private void startCapture(Intent intent) {
        if (active || starting) return;
        starting = true;
        paused = false;
        buffer.clear();
        available = 0;
        message = "正在启动…";
        startForeground(NOTIFICATION_ID, buildNotification());
        new Thread(() -> {
            try {
                int code = intent.getIntExtra("code", Activity.RESULT_CANCELED);
                Intent data = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra("data", Intent.class)
                        : intent.getParcelableExtra("data");
                MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = mgr.getMediaProjection(code, data);
                if (projection == null) throw new IllegalStateException("无法获取录屏授权");
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        mainHandler.post(() -> { if (active || starting) stopCapture("系统已结束录屏。"); });
                    }
                }, mainHandler);

                DisplayMetrics dm = getResources().getDisplayMetrics();
                int sw = dm.widthPixels;
                int sh = dm.heightPixels;
                // Keep the device screen resolution. H.264 requires even dimensions.
                videoWidth = Math.max(2, Math.round(sw / 2f) * 2);
                videoHeight = Math.max(2, Math.round(sh / 2f) * 2);
                int density = dm.densityDpi;
                Surface inputSurface = createCompatibleVideoEncoder();

                virtualDisplay = projection.createVirtualDisplay(
                        "Replay30",
                        videoWidth,
                        videoHeight,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface,
                        null,
                        null);

                boolean audioReady = startPlaybackAudioCapture();

                capturing = true;
                drainThread = new Thread(this::drainEncoder, "replay-drain");
                drainThread.start();

                active = true;
                starting = false;
                message = (usingCompatibilityResolution ? String.format(Locale.CHINA,"编码器不支持原始分辨率，已使用 %d × %d。",videoWidth,videoHeight) : "")
                        + (audioReady ? "" : (usingCompatibilityResolution ? "\n播放声音不可用，已仅录画面。" : "播放声音不可用，已仅录画面。"));
                // Window overlays must be attached on the main thread. This
                // makes the button appear as soon as caching is ready.
                mainHandler.post(this::showOverlay);
                updateNotification();
            } catch (Exception e) {
                starting = false;
                active = false;
                message = "启动失败：" + e.getMessage();
                releaseCapture();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "replay-start").start();
    }

    /** Try native resolution first. Some hardware AVC encoders reject tall or high-density displays. */
    private Surface createCompatibleVideoEncoder() throws Exception {
        usingCompatibilityResolution = false;
        int width = videoWidth, height = videoHeight;
        Exception last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                int bitRate = Math.min(7_500_000, Math.max(1_800_000, width * height * 3));
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                encoder = MediaCodec.createEncoderByType("video/avc");
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface surface = encoder.createInputSurface();
                encoder.start();
                videoWidth = width; videoHeight = height; usingCompatibilityResolution = attempt > 0;
                return surface;
            } catch (Exception error) {
                last = error;
                if (encoder != null) { try { encoder.release(); } catch (Exception ignored) { } encoder = null; }
                width = Math.max(2, Math.round(width * .75f / 2f) * 2);
                height = Math.max(2, Math.round(height * .75f / 2f) * 2);
            }
        }
        throw new IllegalStateException("设备不支持 H.264 屏幕编码", last);
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (capturing) {
            try {
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.getOutputFormat();
                    continue;
                }
                if (index < 0) continue;

                ByteBuffer out = encoder.getOutputBuffer(index);
                if (out == null) {
                    encoder.releaseOutputBuffer(index, false);
                    continue;
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    // Output buffers may start at a non-zero offset. Copy only
                    // the current encoded access unit, never codec buffer slack.
                    ByteBuffer sample = out.duplicate();
                    sample.position(info.offset);
                    sample.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    sample.get(data);
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    buffer.add(new ReplayBuffer.Frame(data, info.presentationTimeUs, key));
                    available = buffer.seconds();
                }
                encoder.releaseOutputBuffer(index, false);
                updateNotification();
            } catch (Exception e) {
                if (capturing) mainHandler.post(() -> stopCapture("录屏异常：" + e.getMessage()));
                break;
            }
        }
    }

    /** Capture app playback through Android's playback-capture API, never the microphone. */
    private boolean startPlaybackAudioCapture() {
        try {
            int sampleRate = 48_000;
            int channelMask = AudioFormat.CHANNEL_IN_STEREO;
            AudioFormat pcm = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(channelMask).build();
            AudioPlaybackCaptureConfiguration captureConfig = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            int minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IllegalStateException("设备不支持此播放音频格式");
            audioRecord = new AudioRecord.Builder().setAudioFormat(pcm)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setBufferSizeInBytes(Math.max(minBuffer * 2, 16_384)).build();
            MediaFormat aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
            aac.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            aac.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioBasePtsUs = System.nanoTime() / 1_000;
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("无法开始播放音频捕获");
            audioCapturing = true;
            audioThread = new Thread(() -> drainAudioEncoder(sampleRate), "replay-audio");
            audioThread.start();
            return true;
        } catch (Exception e) {
            releaseAudioCapture();
            return false;
        }
    }

    private void drainAudioEncoder(int sampleRate) {
        long submittedSamples = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (audioCapturing) {
                int input = audioEncoder.dequeueInputBuffer(10_000);
                if (input >= 0) {
                    ByteBuffer in = audioEncoder.getInputBuffer(input);
                    if (in != null) {
                        in.clear();
                        int read = audioRecord.read(in, Math.min(in.remaining(), 8_192), AudioRecord.READ_BLOCKING);
                        if (read > 0) {
                            long pts = audioBasePtsUs + submittedSamples * 1_000_000L / sampleRate;
                            submittedSamples += read / 4L; // 16-bit stereo PCM
                            audioEncoder.queueInputBuffer(input, 0, read, pts, 0);
                        } else {
                            audioEncoder.queueInputBuffer(input, 0, 0, 0, 0);
                        }
                    }
                }
                drainAudioOutput(info);
            }
        } catch (Exception ignored) {
            // Video capture continues if playback capture becomes unavailable.
        } finally {
            drainAudioOutput(info);
        }
    }

    private void drainAudioOutput(MediaCodec.BufferInfo info) {
        if (audioEncoder == null) return;
        try {
            while (true) {
                int index = audioEncoder.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { audioOutputFormat = audioEncoder.getOutputFormat(); continue; }
                if (index < 0) continue;
                ByteBuffer out = audioEncoder.getOutputBuffer(index);
                if (out != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer sample = out.duplicate();
                    sample.position(info.offset); sample.limit(info.offset + info.size);
                    byte[] data = new byte[info.size]; sample.get(data);
                    buffer.addAudio(new ReplayBuffer.Audio(data, info.presentationTimeUs, info.flags));
                }
                audioEncoder.releaseOutputBuffer(index, false);
            }
        } catch (Exception ignored) { }
    }

    private void releaseAudioCapture() { releaseAudioCapture(false); }

    private void releaseAudioCapture(boolean keepExportFormat) {
        audioCapturing = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) { }
        }
        if (audioThread != null) {
            try { audioThread.join(1_500); } catch (InterruptedException ignored) { }
            audioThread = null;
        }
        if (audioRecord != null) { audioRecord.release(); audioRecord = null; }
        if (audioEncoder != null) {
            try { audioEncoder.stop(); } catch (Exception ignored) { }
            audioEncoder.release(); audioEncoder = null;
        }
        if (!keepExportFormat) audioOutputFormat = null;
    }

    private void saveReplay() {
        if ((!active && !paused) || saving) return;
        // The snapshot shares immutable encoded data. Capture continues while
        // the list is written to MediaStore on another thread.
        ReplayBuffer.Snapshot snapshot = buffer.snapshot();
        List<ReplayBuffer.Frame> frames = snapshot.video;
        if (frames.isEmpty()) {
            message = "暂无可保存画面，请稍后再试。";
            return;
        }
        ReplayBuffer.Frame firstKey = null;
        for (ReplayBuffer.Frame f : frames) {
            if (f.key) {
                firstKey = f;
                break;
            }
        }
        if (firstKey == null) {
            message = "暂无可解码画面，请稍后再试。";
            return;
        }

        saving = true;
        message = "正在保存…";
        updateOverlayState();
        updateNotification();

        final List<ReplayBuffer.Frame> exportFrames = new ArrayList<>();
        boolean include = false;
        for (ReplayBuffer.Frame f : frames) {
            if (f == firstKey) include = true;
            if (include) exportFrames.add(f);
        }
        final MediaFormat exportFormat = outputFormat;
        final List<ReplayBuffer.Audio> exportAudio = snapshot.audio;
        final MediaFormat exportAudioFormat = audioOutputFormat;

        new Thread(() -> {
            Uri uri = null;
            try {
                String name = "Replay_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".mp4";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Replay30");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("无法创建媒体文件");

                try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w")) {
                    if (pfd == null) throw new IOException("无法打开输出流");
                    muxToFile(pfd.getFileDescriptor(), exportFrames, exportFormat, exportAudio, exportAudioFormat);
                }

                values.clear();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);

                double duration = (exportFrames.get(exportFrames.size() - 1).pts - exportFrames.get(0).pts) / 1_000_000.0;
                message = String.format(Locale.CHINA, "已保存 %.1f 秒\nMovies/Replay30/%s", duration, name);
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                message = "保存失败：" + e.getMessage();
            } finally {
                saving = false;
                mainHandler.post(this::updateOverlayState);
                updateNotification();
            }
        }, "replay-save").start();
    }

    private void muxToFile(FileDescriptor fd, List<ReplayBuffer.Frame> frames, MediaFormat exportFormat,
                           List<ReplayBuffer.Audio> audio, MediaFormat exportAudioFormat) throws IOException {
        MediaMuxer muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (exportFormat == null) throw new IOException("编码器尚未输出视频格式");
        MediaFormat format = exportFormat;
        int track = muxer.addTrack(format);
        int audioTrack = (exportAudioFormat != null && !audio.isEmpty()) ? muxer.addTrack(exportAudioFormat) : -1;
        muxer.start();

        long basePts = frames.get(0).pts;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int videoIndex = 0, audioIndex = 0;
        while (videoIndex < frames.size() || (audioTrack >= 0 && audioIndex < audio.size())) {
            boolean writeVideo = videoIndex < frames.size() &&
                    (audioTrack < 0 || audioIndex >= audio.size() || frames.get(videoIndex).pts <= audio.get(audioIndex).pts);
            if (writeVideo) {
                ReplayBuffer.Frame frame = frames.get(videoIndex++);
                info.offset = 0; info.size = frame.data.length;
                info.presentationTimeUs = frame.pts - basePts;
                info.flags = frame.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(track, ByteBuffer.wrap(frame.data), info);
            } else {
                ReplayBuffer.Audio sample = audio.get(audioIndex++);
                info.offset = 0; info.size = sample.data.length;
                info.presentationTimeUs = Math.max(0, sample.pts - basePts);
                info.flags = sample.flags;
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), info);
            }
        }
        muxer.stop();
        muxer.release();
    }

    private static ByteBuffer extractSps(ByteBuffer csd) {
        int offset = 0;
        while (offset + 3 < csd.limit()) {
            int len = nextNalLength(csd, offset);
            if (len <= 0) break;
            int type = csd.get(offset + 4) & 0x1F;
            if (type == 7) {
                byte[] sps = new byte[len];
                csd.position(offset + 4);
                csd.get(sps, 0, len);
                return ByteBuffer.wrap(sps);
            }
            offset += 4 + len;
        }
        return csd.duplicate();
    }

    private static ByteBuffer extractPps(ByteBuffer csd) {
        int offset = 0;
        while (offset + 3 < csd.limit()) {
            int len = nextNalLength(csd, offset);
            if (len <= 0) break;
            int type = csd.get(offset + 4) & 0x1F;
            if (type == 8) {
                byte[] pps = new byte[len];
                csd.position(offset + 4);
                csd.get(pps, 0, len);
                return ByteBuffer.wrap(pps);
            }
            offset += 4 + len;
        }
        return ByteBuffer.allocate(0);
    }

    private static int nextNalLength(ByteBuffer csd, int offset) {
        if (offset + 4 > csd.limit()) return -1;
        return ((csd.get(offset) & 0xFF) << 24)
                | ((csd.get(offset + 1) & 0xFF) << 16)
                | ((csd.get(offset + 2) & 0xFF) << 8)
                | (csd.get(offset + 3) & 0xFF);
    }

    private void stopCapture(String msg) {
        if (!active && !starting) {
            message = msg;
            return;
        }
        capturing = false;
        active = false;
        starting = false;
        paused = false;
        available = 0;
        message = msg;
        removeOverlay();
        releaseCapture();
        buffer.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Stop collection but retain encoded samples until a new start or app exit. */
    private void pauseCapture() {
        if (!active && !starting) return;
        capturing = false;
        active = false;
        starting = false;
        paused = true;
        available = buffer.seconds();
        message = "已暂停。缓存片段仍可保存。";
        removeOverlay();
        releaseCapture(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void releaseCapture() { releaseCapture(false); }

    private void releaseCapture(boolean keepExportFormat) {
        releaseAudioCapture(keepExportFormat);
        if (drainThread != null) {
            try {
                drainThread.join(1500);
            } catch (InterruptedException ignored) {
            }
            drainThread = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception ignored) {
            }
            encoder.release();
            encoder = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (!keepExportFormat) outputFormat = null;
    }

    private void showOverlay() {
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("overlay_enabled", false)) return;
        if (!Settings.canDrawOverlays(this)) return;
        removeOverlay();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        TextView btn = new TextView(this);
        btn.setText("↓");
        btn.setTextSize(22);
        boolean night = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("night_mode", false);
        btn.setTextColor(night ? Color.WHITE : Color.rgb(0, 122, 255));
        btn.setGravity(Gravity.CENTER);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(night ? Color.argb(220, 44, 44, 46) : Color.argb(225, 255, 255, 255));
        bg.setStroke(1, night ? Color.argb(90, 255, 255, 255) : Color.argb(35, 0, 0, 0));
        btn.setBackground(bg);
        btn.setElevation(6 * getResources().getDisplayMetrics().density);
        btn.setContentDescription("保存回放");
        int size = (int) (48 * getResources().getDisplayMetrics().density);
        overlayFullSize = size;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) overlayX;
        lp.y = (int) overlayY;

        final float[] touch = new float[4];
        btn.setOnTouchListener((v, e) -> {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touch[0] = e.getRawX();
                    touch[1] = e.getRawY();
                    touch[2] = p.x;
                    touch[3] = p.y;
                    mainHandler.removeCallbacks(collapseOverlayTask);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (overlayCollapsed) return true;
                    p.x = (int) (touch[2] + e.getRawX() - touch[0]);
                    p.y = (int) (touch[3] + e.getRawY() - touch[1]);
                    clampOverlay(p, size);
                    overlayX = p.x;
                    overlayY = p.y;
                    windowManager.updateViewLayout(v, p);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (overlayCollapsed) {
                        expandOverlay();
                        saveReplay();
                        return true;
                    }
                    float dx = Math.abs(e.getRawX() - touch[0]);
                    float dy = Math.abs(e.getRawY() - touch[1]);
                    if (dx < 12 && dy < 12 && !saving) {
                        saveReplay();
                    } else if (dx >= 12 || dy >= 12) {
                        collapseOverlay();
                    } else {
                        scheduleOverlayCollapse();
                    }
                    return true;
            }
            return false;
        });

        overlayView = btn;
        windowManager.addView(overlayView, lp);
        updateOverlayState();
        btn.setScaleX(.7f); btn.setAlpha(.35f);
        btn.animate().scaleX(1f).alpha(1f).setDuration(260).start();
        scheduleOverlayCollapse();
    }

    private void clampOverlay(WindowManager.LayoutParams p, int size) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        p.x = Math.max(0, Math.min(p.x, dm.widthPixels - size));
        p.y = Math.max(0, Math.min(p.y, dm.heightPixels - size));
    }

    private void updateOverlayState() {
        if (!(overlayView instanceof TextView)) return;
        TextView btn = (TextView) overlayView;
        GradientDrawable bg = (GradientDrawable) btn.getBackground();
        boolean night = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("night_mode", false);
        if (overlayCollapsed) {
            btn.setText("");
        } else if (saving) {
            btn.setText("…");
            btn.setTextColor(Color.WHITE);
            bg.setColor(Color.argb(225, 255, 149, 0));
        } else {
            btn.setText("↓");
            btn.setTextColor(night ? Color.WHITE : Color.rgb(0, 122, 255));
            bg.setColor(night ? Color.argb(220, 44, 44, 46) : Color.argb(225, 255, 255, 255));
        }
    }

    private void scheduleOverlayCollapse() {
        mainHandler.removeCallbacks(collapseOverlayTask);
        if (overlayView != null && !saving) mainHandler.postDelayed(collapseOverlayTask, 3_500);
    }

    /** Shrink to a slim edge tab. It remains visible and can be tapped. */
    private void collapseOverlay() {
        if (overlayView == null || windowManager == null || saving || overlayCollapsed) return;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        int tabWidth = (int) (14 * getResources().getDisplayMetrics().density);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean left = p.x + overlayFullSize / 2 < dm.widthPixels / 2;
        p.width = tabWidth; p.height = overlayFullSize;
        p.x = left ? 0 : dm.widthPixels - tabWidth;
        p.y = Math.max(0, Math.min(p.y, dm.heightPixels - overlayFullSize));
        overlayX = p.x; overlayY = p.y;
        overlayCollapsed = true;
        updateOverlayState();
        try { windowManager.updateViewLayout(overlayView, p); } catch (Exception ignored) { }
        overlayView.animate().alpha(.58f).setDuration(220).start();
    }

    /** Show the full save button before a collapsed-tab save animation. */
    private void expandOverlay() {
        if (overlayView == null || windowManager == null || !overlayCollapsed) return;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) overlayView.getLayoutParams();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean left = p.x < dm.widthPixels / 2;
        p.width = overlayFullSize; p.height = overlayFullSize;
        p.x = left ? 0 : dm.widthPixels - overlayFullSize;
        overlayX = p.x; overlayY = p.y;
        overlayCollapsed = false;
        updateOverlayState();
        try { windowManager.updateViewLayout(overlayView, p); } catch (Exception ignored) { }
        overlayView.setAlpha(.45f); overlayView.setScaleX(.72f);
        overlayView.animate().alpha(1f).scaleX(1f).setDuration(220).start();
        scheduleOverlayCollapse();
    }

    private void removeOverlay() {
        mainHandler.removeCallbacks(collapseOverlayTask);
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        overlayView = null;
        overlayCollapsed = false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "屏幕缓存", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("回录 30 秒运行状态");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private PendingIntent serviceIntent(String action) {
        Intent i = new Intent(this, ReplayService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) openFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, openFlags);

        String text = saving ? "正在保存 · 缓存继续" : String.format(Locale.CHINA, "正在缓存 · 可保存 %.1f 秒", available);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setContentTitle("回录 30 秒")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "保存回放", serviceIntent(SAVE)).build())
                .addAction(new Notification.Action.Builder(null, "暂停缓存", serviceIntent(STOP)).build());
        return builder.build();
    }

    private void updateNotification() {
        if (!active && !starting) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        releaseCapture();
        active = false; starting = false; saving = false; paused = false; available = 0;
        buffer.clear();
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver);
            screenReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
