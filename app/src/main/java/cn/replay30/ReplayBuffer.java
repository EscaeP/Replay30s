package cn.replay30;

import java.util.*;

/** Immutable encoded frames; export snapshots share data without copying it. */
public final class ReplayBuffer {
    public static final long WINDOW_US = 30_000_000L;
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    public static final class Frame {
        public final byte[] data;
        public final long pts;
        public final boolean key;
        public Frame(byte[] data, long pts, boolean key) { this.data=data; this.pts=pts; this.key=key; }
    }
    public static final class Audio {
        public final byte[] data;
        public final long pts;
        public final int flags;
        public Audio(byte[] data, long pts, int flags) { this.data=data; this.pts=pts; this.flags=flags; }
    }
    public static final class Snapshot {
        public final List<Frame> video;
        public final List<Audio> audio;
        Snapshot(List<Frame> video, List<Audio> audio) { this.video=video; this.audio=audio; }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final ArrayDeque<Audio> audio = new ArrayDeque<>();
    private long bytes;
    public synchronized void add(Frame f) {
        if (f.data.length > MAX_BYTES) { clear(); return; }
        if (frames.isEmpty() && !f.key) return;
        if (!frames.isEmpty() && f.pts <= frames.getLast().pts) return;
        frames.addLast(f); bytes += f.data.length;
        // Keep the keyframe immediately preceding the 30-second boundary.
        long cutoff = f.pts - WINDOW_US;
        Frame nextKey = null;
        for (Frame x : frames) if (x.key && x.pts <= cutoff) nextKey=x;
        if (nextKey != null) while (frames.peekFirst() != nextKey) removeFirst();
        while (bytes > MAX_BYTES) removeFirst();
        while (!frames.isEmpty() && !frames.peekFirst().key) removeFirst();
        trimAudio();
    }
    public synchronized void addAudio(Audio sample) {
        if (sample.data.length > MAX_BYTES || (!audio.isEmpty() && sample.pts <= audio.getLast().pts)) return;
        audio.addLast(sample); bytes += sample.data.length;
        trimAudio();
        while (bytes > MAX_BYTES && !audio.isEmpty()) { bytes -= audio.removeFirst().data.length; }
    }
    private void trimAudio() {
        if (frames.isEmpty()) return;
        long firstPts = frames.getFirst().pts;
        while (!audio.isEmpty() && audio.getFirst().pts < firstPts) bytes -= audio.removeFirst().data.length;
    }
    private void removeFirst() { bytes -= frames.removeFirst().data.length; }
    /** Export ends at the latest encoded frame; UI and codec clocks can differ. */
    public synchronized Snapshot snapshot() {
        if (frames.isEmpty()) return new Snapshot(Collections.emptyList(), Collections.emptyList());
        long endUs=frames.getLast().pts;
        List<Frame> result=new ArrayList<>();
        Frame start=null;
        for (Frame f:frames) if (f.key && f.pts <= endUs-WINDOW_US) start=f;
        boolean include=start==null;
        for (Frame f:frames) { if(f==start) include=true; if(include && f.pts<=endUs) result.add(f); }
        if (result.isEmpty()) return new Snapshot(result, Collections.emptyList());
        long startPts = result.get(0).pts;
        List<Audio> audioResult = new ArrayList<>();
        for (Audio sample : audio) if (sample.pts >= startPts && sample.pts <= endUs) audioResult.add(sample);
        return new Snapshot(result, audioResult);
    }
    public synchronized double seconds() { return frames.size()<2 ? 0 : Math.min(30, (frames.getLast().pts-frames.getFirst().pts)/1_000_000.0); }
    public synchronized long bytes() { return bytes; }
    public synchronized void clear() { frames.clear(); audio.clear(); bytes=0; }
}
