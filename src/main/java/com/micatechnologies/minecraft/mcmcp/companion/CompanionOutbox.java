package com.micatechnologies.minecraft.mcmcp.companion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Frames waiting to go to one peer, sent a budget at a time.
 *
 * <h2>Why a budget</h2>
 *
 * Minecraft's network code has no back-pressure for custom payloads: every packet is written and
 * flushed as it is handed over, and a slow link grows netty's buffer without limit. A ten-megabyte
 * region read handed over at once would sit in that buffer ahead of the player's own block updates.
 * So replies queue here, bounded, and each tick sends at most a budget's worth — and nothing at all
 * while the connection reports it cannot take more.
 *
 * <p>Thread-safe: messages are added from dispatcher workers, frames are taken on the server thread.
 */
public final class CompanionOutbox {

    /** Where a drained frame goes. */
    public interface Sink {

        void send(byte[] frame);
    }

    private final int maxFrame;
    private final Deque<byte[]> frames = new ArrayDeque<>();
    private long queuedBytes;
    private int nextStream;

    public CompanionOutbox(int maxFrame) {
        this.maxFrame = maxFrame;
    }

    /**
     * Queues one message, unless it would take the queue past {@code maxQueuedBytes}.
     *
     * @return false when it was refused for size; nothing of it was queued
     */
    public synchronized boolean offer(byte type, byte[] message, long maxQueuedBytes) {
        int stream = nextStream;
        nextStream = (nextStream + 1) & 0x7FFFFFFF;
        List<byte[]> encoded = CompanionFrames.encode(type, stream, message, maxFrame);
        long size = 0L;
        for (byte[] frame : encoded) {
            size += frame.length;
        }
        if (queuedBytes + size > maxQueuedBytes && queuedBytes > 0) {
            return false;
        }
        frames.addAll(encoded);
        queuedBytes += size;
        return true;
    }

    /**
     * Sends frames until {@code budgetBytes} is spent or the queue is empty. Always sends at least
     * one frame when there is one, so a budget smaller than a frame still makes progress.
     *
     * @return bytes sent
     */
    public long drain(long budgetBytes, Sink sink) {
        long sent = 0L;
        while (true) {
            byte[] frame;
            synchronized (this) {
                frame = frames.peekFirst();
                if (frame == null || (sent > 0L && sent + frame.length > budgetBytes)) {
                    return sent;
                }
                frames.pollFirst();
                queuedBytes -= frame.length;
            }
            sink.send(frame);
            sent += frame.length;
        }
    }

    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    public synchronized void clear() {
        frames.clear();
        queuedBytes = 0L;
    }
}
