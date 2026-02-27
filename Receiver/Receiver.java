import java.io.*;
import java.net.*;

public class Receiver {

    public static void main(String[] args) throws Exception {

        // ----------------------------------------------------------------
        // 1. Parse command-line arguments
        // Usage: java Receiver <sender_ip> <sender_ack_port>
        // <rcv_data_port> <output_file> <RN> [window_size]
        //
        // RN = 0 --> no ACKs dropped
        // RN = X --> every Xth ACK is dropped (via ChaosEngine)
        // Optional window_size: if omitted, receiver auto-detects (universal mode)
        // If provided, enables explicit GBN mode with that window size
        // ----------------------------------------------------------------
        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN> [window_size]");
            System.exit(1);
        }

        String senderIp = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        if (rn < 0) {
            System.err.println("Error: RN must be >= 0.");
            System.exit(1);
        }

        // Optional window size for explicit GBN mode
        int windowSize = 0; // 0 = auto-detect (universal mode)
        boolean useGBN = false;

        if (args.length == 6) {
            windowSize = Integer.parseInt(args[5]);

            // Validate GBN window size per spec
            if (windowSize <= 0 || windowSize > 128 || windowSize % 4 != 0) {
                System.err.println("Error: window_size must be a multiple of 4 and <= 128.");
                System.exit(1);
            }
            useGBN = true;
        }

        // ----------------------------------------------------------------
        // 2. Resolve sender address (we send ACKs back here)
        // ----------------------------------------------------------------
        InetAddress senderAddress = InetAddress.getByName(senderIp);

        // ----------------------------------------------------------------
        // 3. Create UDP socket bound to rcv_data_port
        // The sender sends all data packets to this port.
        // We send ACKs back to senderIp:senderAckPort.
        // ----------------------------------------------------------------
        DatagramSocket socket = new DatagramSocket(rcvDataPort);

        // ----------------------------------------------------------------
        // 4. Print startup info
        // ----------------------------------------------------------------
        System.out.println("=== DS-FTP Receiver ===");
        System.out.println("Listening on : 0.0.0.0:" + rcvDataPort + " (for data)");
        System.out.println("Sending ACKs : " + senderIp + ":" + senderAckPort);
        System.out.println("Output file  : " + outputFile);
        System.out.println("Reliability  : RN=" + rn + (rn == 0 ? " (no drops)" : " (drop every " + rn + "th ACK)"));
        System.out.println();

        // ----------------------------------------------------------------
        // Issue #3: Handshake (wait for SOT, reply ACK 0)
        // ----------------------------------------------------------------
        // tracks total ACKs sent - needed for ChaosEngine dropping logic later
        int ackCount = 0;

        byte[] rcvBuf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket rcvDatagram = new DatagramPacket(rcvBuf, rcvBuf.length);

        // block until we receive the sender's SOT
        System.out.println("[Handshake] Waiting for SOT...");
        socket.receive(rcvDatagram);
        DSPacket sotPacket = new DSPacket(rcvDatagram.getData());

        // validate it's the right packet
        if (sotPacket.getType() != DSPacket.TYPE_SOT || sotPacket.getSeqNum() != 0) {
            System.err.println("[Handshake] Expected SOT (Type=0, Seq=0), got Type="
                    + sotPacket.getType() + " Seq=" + sotPacket.getSeqNum());
            socket.close();
            System.exit(1);
        }
        System.out.println("[Handshake] Received SOT (Seq=0)");

        // send ACK 0 back to sender's ACK port
        ackCount++;
        DSPacket ackPacket = new DSPacket(DSPacket.TYPE_ACK, 0, null);
        byte[] ackBytes = ackPacket.toBytes();
        DatagramPacket ackDatagram = new DatagramPacket(ackBytes, ackBytes.length, senderAddress, senderAckPort);

        // let ChaosEngine decide if this ACK gets dropped
        if (!ChaosEngine.shouldDrop(ackCount, rn)) {
            socket.send(ackDatagram);
            System.out.println("[Handshake] Sent ACK (Seq=0) -- connection established");
        } else {
            System.out.println("[Handshake] ACK (Seq=0) dropped by ChaosEngine (ackCount=" + ackCount + ")");
        }

        // ----------------------------------------------------------------
        // Issue #4: Stop-and-Wait data receive + teardown
        // ----------------------------------------------------------------
        FileOutputStream fos = new FileOutputStream(outputFile);
        
        if (!useGBN) {
            // Stop-and-Wait Receiver Mode
            int expectedSeq = 1; // first DATA packet should be seq 1
            int lastAckedSeq = 0; // last seq we ACKed (SOT was seq 0)

            System.out.println("[S&W] Waiting for data...");

            while (true) {
                // fresh datagram each iteration so old data doesn't bleed through
                rcvDatagram = new DatagramPacket(rcvBuf, rcvBuf.length);
                socket.receive(rcvDatagram);
                DSPacket pkt = new DSPacket(rcvDatagram.getData());

            if (pkt.getType() == DSPacket.TYPE_DATA) {

                if (pkt.getSeqNum() == expectedSeq) {
                    // in-order packet - write it and advance expectedSeq
                    fos.write(pkt.getPayload());
                    System.out.println("[S&W] Received DATA Seq=" + pkt.getSeqNum()
                            + " (" + pkt.getLength() + " bytes), ACKing");
                    lastAckedSeq = expectedSeq;
                    expectedSeq = (expectedSeq + 1) % 128;
                } else {
                    // duplicate or out-of-order - re-ACK the last good seq
                    System.out.println("[S&W] Out-of-order Seq=" + pkt.getSeqNum()
                            + ", re-ACKing Seq=" + lastAckedSeq);
                }

                // send ACK (whether in-order or duplicate)
                ackCount++;
                DSPacket reply = new DSPacket(DSPacket.TYPE_ACK, lastAckedSeq, null);
                byte[] replyBytes = reply.toBytes();
                DatagramPacket replyDg = new DatagramPacket(replyBytes, replyBytes.length, senderAddress,
                        senderAckPort);
                if (!ChaosEngine.shouldDrop(ackCount, rn)) {
                    socket.send(replyDg);
                } else {
                    System.out.println("[S&W] ACK Seq=" + lastAckedSeq + " dropped by ChaosEngine");
                }

            } else if (pkt.getType() == DSPacket.TYPE_EOT) {
                // teardown - ACK the EOT then break out
                System.out.println("[Teardown] Received EOT Seq=" + pkt.getSeqNum());
                ackCount++;
                DSPacket reply = new DSPacket(DSPacket.TYPE_ACK, pkt.getSeqNum(), null);
                byte[] replyBytes = reply.toBytes();
                DatagramPacket replyDg = new DatagramPacket(replyBytes, replyBytes.length, senderAddress,
                        senderAckPort);
                if (!ChaosEngine.shouldDrop(ackCount, rn)) {
                    socket.send(replyDg);
                    System.out.println("[Teardown] Sent ACK for EOT -- closing");
                } else {
                    System.out.println("[Teardown] ACK for EOT dropped by ChaosEngine");
                }
                break;
            }
        }

        fos.close();

        // ----------------------------------------------------------------
        // Issue #5 & #6: Go-Back-N data receive + teardown
        // ----------------------------------------------------------------
        } else {
            // GBN Receiver Mode
            System.out.println("[GBN] Waiting for data (window=" + windowSize + ")...");
            
            // GBN receiver state
            int expectedSeq = 1; // first expected in-order packet
            int lastAckedSeq = 0; // last contiguous in-order seq delivered (cumulative ACK)
            
            // Receive buffer for out-of-order packets
            DSPacket[] recvBuffer = new DSPacket[windowSize];
            boolean[] hasPacket = new boolean[windowSize];
            
            while (true) {
                // fresh datagram each iteration
                rcvDatagram = new DatagramPacket(rcvBuf, rcvBuf.length);
                socket.receive(rcvDatagram);
                DSPacket pkt = new DSPacket(rcvDatagram.getData());

                if (pkt.getType() == DSPacket.TYPE_DATA) {
                    int seqNum = pkt.getSeqNum();
                    
                    // Check if packet is within receive window
                    if (WindowHelper.inWindow(seqNum, expectedSeq, windowSize)) {
                        // Within window - buffer it
                        int bufIdx = WindowHelper.bufferIndex(seqNum, expectedSeq, windowSize);
                        
                        if (!hasPacket[bufIdx]) {
                            // New packet - store it
                            recvBuffer[bufIdx] = pkt;
                            hasPacket[bufIdx] = true;
                            System.out.println("[GBN] Received DATA Seq=" + seqNum
                                    + " (" + pkt.getLength() + " bytes), buffered at index " + bufIdx);
                        } else {
                            // Duplicate within window
                            System.out.println("[GBN] Received duplicate Seq=" + seqNum + ", buffered already");
                        }
                        
                        // Attempt to deliver contiguous packets
                        while (hasPacket[0]) {
                            // Buffer[0] corresponds to expectedSeq
                            DSPacket toDeliver = recvBuffer[0];
                            fos.write(toDeliver.getPayload());
                            System.out.println("[GBN] Delivered Seq=" + expectedSeq);
                            
                            lastAckedSeq = expectedSeq;
                            expectedSeq = (expectedSeq + 1) % 128;
                            
                            // Shift buffer down
                            for (int i = 0; i < windowSize - 1; i++) {
                                recvBuffer[i] = recvBuffer[i + 1];
                                hasPacket[i] = hasPacket[i + 1];
                            }
                            recvBuffer[windowSize - 1] = null;
                            hasPacket[windowSize - 1] = false;
                        }
                        
                    } else {
                        // Outside window - discard silently
                        System.out.println("[GBN] Received Seq=" + seqNum + " outside window [" 
                                + expectedSeq + ", " + ((expectedSeq + windowSize - 1) % 128) + "), discarded");
                    }
                    
                    // Send cumulative ACK for every packet (even duplicates/out-of-order)
                    ackCount++;
                    DSPacket reply = new DSPacket(DSPacket.TYPE_ACK, lastAckedSeq, null);
                    byte[] replyBytes = reply.toBytes();
                    DatagramPacket replyDg = new DatagramPacket(replyBytes, replyBytes.length, senderAddress,
                            senderAckPort);
                    if (!ChaosEngine.shouldDrop(ackCount, rn)) {
                        socket.send(replyDg);
                        System.out.println("[GBN] Sent cumulative ACK up to Seq=" + lastAckedSeq);
                    } else {
                        System.out.println("[GBN] Cumulative ACK up to Seq=" + lastAckedSeq + " dropped by ChaosEngine");
                    }

                } else if (pkt.getType() == DSPacket.TYPE_EOT) {
                    // Teardown - ACK the EOT then break out
                    System.out.println("[Teardown] Received EOT Seq=" + pkt.getSeqNum());
                    ackCount++;
                    DSPacket reply = new DSPacket(DSPacket.TYPE_ACK, pkt.getSeqNum(), null);
                    byte[] replyBytes = reply.toBytes();
                    DatagramPacket replyDg = new DatagramPacket(replyBytes, replyBytes.length, senderAddress,
                            senderAckPort);
                    if (!ChaosEngine.shouldDrop(ackCount, rn)) {
                        socket.send(replyDg);
                        System.out.println("[Teardown] Sent ACK for EOT -- closing");
                    } else {
                        System.out.println("[Teardown] ACK for EOT dropped by ChaosEngine");
                    }
                    break;
                }
            }

            fos.close();
        }

        socket.close();
    }
}
