package com.alexander.carplay.data.video;

import java.nio.ByteBuffer;

public class PacketRingByteBuffer {
    public interface DirectWriteCallback {
        void write(byte[] bytes, int offset);
    }

    private static final int MAX_BUFFER_SIZE = 128 * 1024 * 1024;
    private static final int MIN_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int EMERGENCY_RESET_THRESHOLD = 96 * 1024 * 1024;

    private byte[] buffer;
    private int readPosition = 0;
    private int writePosition = 0;
    private int lastWritePositionBeforeEnd = 0;
    private int packetCount = 0;
    private int resizeAttemptCount = 0;
    private LogCallback logCallback;

    public PacketRingByteBuffer(int initialSize) {
        int safeSize = Math.max(MIN_BUFFER_SIZE, Math.min(initialSize, MAX_BUFFER_SIZE));
        buffer = new byte[safeSize];
        if (safeSize != initialSize) {
            log("Buffer size adjusted from " + initialSize + " to " + safeSize);
        }
    }

    public void setLogCallback(LogCallback logCallback) {
        this.logCallback = logCallback;
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    public boolean isEmpty() {
        return packetCount == 0;
    }

    public int availablePacketsToRead() {
        return packetCount;
    }

    public void directWriteToBuffer(int length, int skipBytesCount, DirectWriteCallback callback) {
        synchronized (this) {
            if (length < 0 || skipBytesCount < 0 || skipBytesCount > length) {
                log("Invalid write parameters length=" + length + ", skip=" + skipBytesCount);
                return;
            }

            boolean hasSpaceToWriteHeader = availableSpaceAtHead() > 8;
            boolean hasSpaceAtHead = availableSpaceAtHead() > length + 8;
            boolean hasSpaceAtStart = availableSpaceAtStart() > length + 8;

            while (!hasSpaceToWriteHeader || !(hasSpaceAtHead || hasSpaceAtStart)) {
                reorganizeAndResizeIfNeeded();
                hasSpaceToWriteHeader = availableSpaceAtHead() > 8;
                hasSpaceAtHead = availableSpaceAtHead() > length + 8;
                hasSpaceAtStart = availableSpaceAtStart() > length + 8;
            }

            writeInt(writePosition, length);
            writePosition += 4;
            writeInt(writePosition, skipBytesCount);
            writePosition += 4;

            if (!hasSpaceAtHead && hasSpaceAtStart) {
                lastWritePositionBeforeEnd = writePosition;
                writePosition = 0;
            }

            callback.write(buffer, writePosition);
            writePosition += length;
            packetCount++;
        }
    }

    public ByteBuffer readPacket() {
        synchronized (this) {
            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                reset();
                return ByteBuffer.allocate(0);
            }

            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            int actualLength = length - skipBytes;
            int startPosition = readPosition + skipBytes;

            if (actualLength < 0 || startPosition + actualLength > buffer.length) {
                reset();
                return ByteBuffer.allocate(0);
            }

            ByteBuffer result = ByteBuffer.wrap(buffer, startPosition, actualLength);
            readPosition += length;
            packetCount--;
            return result;
        }
    }

    public void reset() {
        packetCount = 0;
        writePosition = 0;
        readPosition = 0;
        lastWritePositionBeforeEnd = 0;
    }

    public void trimToMinSize() {
        if (buffer.length == MIN_BUFFER_SIZE) {
            reset();
            resizeAttemptCount = 0;
            return;
        }

        buffer = new byte[MIN_BUFFER_SIZE];
        reset();
        resizeAttemptCount = 0;
        log("Ring buffer trimmed to " + MIN_BUFFER_SIZE + " bytes");
    }

    private void reorganizeAndResizeIfNeeded() {
        int available;
        if (writePosition > readPosition) {
            available = readPosition + buffer.length - writePosition;
        } else {
            available = readPosition - writePosition;
        }

        int newLength = buffer.length;
        if (available < buffer.length / 2) {
            int proposedSize = newLength * 2;
            resizeAttemptCount++;
            if (proposedSize > MAX_BUFFER_SIZE) {
                if (buffer.length >= EMERGENCY_RESET_THRESHOLD) {
                    performEmergencyReset();
                    return;
                }
                newLength = MAX_BUFFER_SIZE;
            } else {
                newLength = proposedSize;
            }
            log("Ring buffer resize to " + newLength + " bytes, attempt " + resizeAttemptCount);
        }

        byte[] newBuffer = new byte[newLength];
        if (writePosition < readPosition) {
            int dataAtEndLength = lastWritePositionBeforeEnd - readPosition;
            if (dataAtEndLength < 0) {
                reset();
                return;
            }

            System.arraycopy(buffer, readPosition, newBuffer, 0, dataAtEndLength);
            System.arraycopy(buffer, 0, newBuffer, dataAtEndLength, writePosition);

            readPosition = 0;
            writePosition += dataAtEndLength;
        } else {
            int copyLength = writePosition - readPosition;
            if (copyLength < 0) {
                reset();
                return;
            }

            System.arraycopy(buffer, readPosition, newBuffer, 0, copyLength);
            writePosition -= readPosition;
            readPosition = 0;
        }

        buffer = newBuffer;
    }

    private int availableSpaceAtHead() {
        if (writePosition < readPosition) {
            return readPosition - writePosition;
        }
        return buffer.length - writePosition;
    }

    private int availableSpaceAtStart() {
        if (writePosition < readPosition) {
            return 0;
        }
        return readPosition;
    }

    private void writeInt(int offset, int value) {
        if (offset < 0 || offset + 3 >= buffer.length) {
            return;
        }

        buffer[offset] = (byte) ((value & 0xFF000000) >> 24);
        buffer[offset + 1] = (byte) ((value & 0x00FF0000) >> 16);
        buffer[offset + 2] = (byte) ((value & 0x0000FF00) >> 8);
        buffer[offset + 3] = (byte) (value & 0x000000FF);
    }

    private int readInt(int offset) {
        if (offset < 0 || offset + 3 >= buffer.length) {
            return 0;
        }

        return ((buffer[offset] << 24) & 0xFF000000)
            | ((buffer[offset + 1] << 16) & 0x00FF0000)
            | ((buffer[offset + 2] << 8) & 0x0000FF00)
            | (buffer[offset + 3] & 0x000000FF);
    }

    private void performEmergencyReset() {
        log("Emergency ring buffer reset at " + buffer.length + " bytes");
        buffer = new byte[MIN_BUFFER_SIZE];
        reset();
        resizeAttemptCount = 0;
    }
}
