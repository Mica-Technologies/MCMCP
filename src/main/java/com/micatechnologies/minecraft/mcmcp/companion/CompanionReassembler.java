package com.micatechnologies.minecraft.mcmcp.companion;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Puts one peer's frames back together into messages, within limits.
 *
 * <p>The limits are the point. Frames arrive from the network, from a peer this side does not
 * trust, and each open stream holds memory until it finishes. So the total size of a message, the
 * number of streams open at once and the order of frames within a stream are all checked, and any
 * breach is a {@link CompanionProtocolException} that ends the peer's companion state.
 *
 * <p>Not thread-safe: one instance per peer, fed from that peer's network thread.
 */
public final class CompanionReassembler {

    /** One complete message. */
    public static final class Message {

        public final byte type;
        public final int stream;
        public final byte[] bytes;

        Message(byte type, int stream, byte[] bytes) {
            this.type = type;
            this.stream = stream;
            this.bytes = bytes;
        }
    }

    private static final class Open {

        final byte type;
        final int flags;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int nextSeq;

        Open(byte type, int flags) {
            this.type = type;
            this.flags = flags;
        }
    }

    private final int maxMessageBytes;
    private final int maxOpenStreams;
    private final Map<Integer, Open> open = new HashMap<>();

    public CompanionReassembler(int maxMessageBytes, int maxOpenStreams) {
        this.maxMessageBytes = maxMessageBytes;
        this.maxOpenStreams = maxOpenStreams;
    }

    /**
     * Takes one frame.
     *
     * @return the finished message when this was its last frame, else null
     * @throws CompanionProtocolException when the frame breaks the protocol or a limit
     */
    @Nullable
    public Message accept(byte[] bytes) throws CompanionProtocolException {
        CompanionFrames.Frame frame = CompanionFrames.decode(bytes);
        Open stream = open.get(frame.stream);
        if (stream == null) {
            if (frame.seq != 0) {
                throw new CompanionProtocolException("Stream " + frame.stream + " starts at frame "
                    + frame.seq + " instead of 0");
            }
            if (open.size() >= maxOpenStreams) {
                throw new CompanionProtocolException("More than " + maxOpenStreams
                    + " messages are being sent at once");
            }
            stream = new Open(frame.type, frame.flags & CompanionProtocol.FLAG_DEFLATE);
            open.put(frame.stream, stream);
        }
        else if (frame.seq != stream.nextSeq || frame.type != stream.type) {
            open.remove(frame.stream);
            throw new CompanionProtocolException("Stream " + frame.stream + " expected frame "
                + stream.nextSeq + " and got " + frame.seq);
        }
        if (stream.body.size() + frame.payload.length > maxMessageBytes) {
            open.remove(frame.stream);
            throw new CompanionProtocolException("A message is larger than " + maxMessageBytes + " bytes");
        }
        stream.body.write(frame.payload, 0, frame.payload.length);
        stream.nextSeq++;
        if (!frame.isLast()) {
            return null;
        }
        open.remove(frame.stream);
        byte[] body = stream.body.toByteArray();
        if ((stream.flags & CompanionProtocol.FLAG_DEFLATE) != 0) {
            body = CompanionFrames.inflate(body, maxMessageBytes);
        }
        return new Message(stream.type, frame.stream, body);
    }

    /** Messages started and not yet finished. */
    public int openStreams() {
        return open.size();
    }
}
