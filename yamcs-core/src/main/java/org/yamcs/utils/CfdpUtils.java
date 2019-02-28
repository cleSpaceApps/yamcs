package org.yamcs.utils;

import java.nio.ByteBuffer;

public class CfdpUtils {

    // counting from zero (and big-endian) , get the bitnr't bit of the input byte
    public static boolean getBitOfByte(Byte input, int bitnr) {
        return ((input >> (7 - bitnr)) & 1) == 1;
    }

    /*
     * get an unsigned byte value from an input buffer, at its current position
     * A byte has range -2ˆ7+1 to 2ˆ7-1, while we want 0 to 2ˆ15-1, the mask and short cast takes care of this
     */
    public static short getUnsignedByte(ByteBuffer buffer) {
        return (short) (buffer.get() & 0xff);
    }

    /*
     * write the given short as an unsigned byte to the given buffer at its current position
     */
    public static void writeUnsignedByte(ByteBuffer buffer, short input) {
        buffer.put((byte) (input & 0xff));
    }

    /*
     * get an unsigned short value from an input buffer, at its current position
     * A short has range -2ˆ15+1 to 2ˆ15-1, while we want 0 to 2ˆ16-1, the mask and int cast takes care of this
     */
    public static int getUnsignedShort(ByteBuffer buffer) {
        return buffer.getShort() & 0xffff;
    }

    /**
     * get an unsigned int value from an input buffer, at its current position A short has range -2ˆ31+1 to 2ˆ31-1,
     * while we want 0 to 2ˆ32-1, the mask and int cast takes care of this
     */
    public static long getUnsignedInt(ByteBuffer buffer) {
        return buffer.getInt() & 0xffffffff;
    }

    /**
     * Write the given long as an unsigned int to the given buffer at its current position
     */
    public static void writeUnsignedInt(ByteBuffer buffer, long input) {
        buffer.putInt((int) (input & 0xffffffff));
    }

    /*
     * Read nrOfBytesToRead bytes (max 8) from a given buffer at the current position, 
     * return the result as an unsigned long,
     * moves the position of the buffer to after the read bytes
     * 
     * TODO: what if nrOfBytesToRead > 8
     * 
     */
    public static Long getUnsignedLongFromBuffer(ByteBuffer buffer, int nrOfBytesToRead) {
        byte[] temp = new byte[nrOfBytesToRead];
        buffer.get(temp);
        return getUnsignedLongFromByteArray(temp);
    }

    public static Long getUnsignedLongFromByteArray(byte[] b) {
        long toReturn = 0;
        for (int i = 0; i < b.length - 1; i++) {
            toReturn |= b[i] & 0xFF;
            toReturn <<= 8;
        }
        toReturn |= b[b.length - 1] & 0xFF;
        return toReturn;
    }

    public static byte boolToByte(boolean bool) {
        return (byte) (bool ? 1 : 0);
    }

    public static byte[] longToBytes(Long input, int length) {
        byte[] toReturn = new byte[length];
        for (int i = 0; i < length; i++) {
            toReturn[i] = (byte) (input >> i * 8);
        }
        return toReturn;
    }
}
