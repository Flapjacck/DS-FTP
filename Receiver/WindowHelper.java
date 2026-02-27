/**
 * WindowHelper - Utility for modulo-128 wrap-around arithmetic
 * 
 * Provides helper methods for window-based protocol logic where
 * sequence numbers wrap modulo 128.
 */
public class WindowHelper {

    /**
     * Checks if seqNum is within the window [base, base+windowSize).
     * Handles wrap-around correctly (e.g., base=126, windowSize=4 includes {126, 127, 0, 1}).
     *
     * @param seqNum The sequence number to check
     * @param base The base (start) of the window
     * @param windowSize The size of the window
     * @return true if seqNum is in [base, base+windowSize) modulo 128
     */
    public static boolean inWindow(int seqNum, int base, int windowSize) {
        // Normalize to [0, 128)
        seqNum = seqNum & 0xFF;
        base = base & 0xFF;
        
        // Check if seqNum is within the circular window
        int offset = (seqNum - base + 128) % 128;
        return offset < windowSize;
    }

    /**
     * Computes the buffer index for a packet within the receive window.
     * 
     * @param seqNum The packet's sequence number
     * @param expectedSeq The first sequence number we expect to deliver
     * @param windowSize The size of the buffer
     * @return Index in the buffer [0, windowSize)
     */
    public static int bufferIndex(int seqNum, int expectedSeq, int windowSize) {
        seqNum = seqNum & 0xFF;
        expectedSeq = expectedSeq & 0xFF;
        
        return (seqNum - expectedSeq + 128) % 128;
    }

    /**
     * Checks if seqNumA is less than seqNumB accounting for wrap-around.
     * Useful for cumulative ACK logic.
     *
     * @param seqNumA First sequence number
     * @param seqNumB Second sequence number
     * @return true if seqNumA < seqNumB in modulo 128 space
     */
    public static boolean lessModulo128(int seqNumA, int seqNumB) {
        int a = seqNumA & 0xFF;
        int b = seqNumB & 0xFF;
        return ((b - a + 128) % 128) > 0 && ((b - a + 128) % 128) < 64;
    }

    /**
     * Advances a sequence number by 1 modulo 128.
     *
     * @param seq Current sequence number
     * @return Next sequence number (seq + 1) % 128
     */
    public static int nextSeq(int seq) {
        return (seq + 1) % 128;
    }
}
