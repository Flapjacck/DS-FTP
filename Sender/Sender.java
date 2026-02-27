import java.io.*;
import java.net.*;

public class Sender {

    public static void main(String[] args) throws Exception {

        // ----------------------------------------------------------------
        // 1. Parse command-line arguments
        // Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port>
        // <input_file> <timeout_ms> [window_size]
        //
        // Omit window_size --> Stop-and-Wait
        // Provide window_size --> Go-Back-N (must be multiple of 4, <= 128)
        // ----------------------------------------------------------------
        if (args.length < 5 || args.length > 6) {
            System.err.println(
                    "Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        String rcvIp = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMs = Integer.parseInt(args[4]);

        // Optional window size -- determines which protocol to use
        int windowSize = 0; // 0 means Stop-and-Wait
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

        // Make sure the input file exists before doing anything
        File file = new File(inputFile);
        if (!file.exists() || !file.isFile()) {
            System.err.println("Error: input file not found: " + inputFile);
            System.exit(1);
        }

        // ----------------------------------------------------------------
        // 2. Resolve receiver address
        // ----------------------------------------------------------------
        InetAddress rcvAddress = InetAddress.getByName(rcvIp);

        // ----------------------------------------------------------------
        // 3. Create UDP socket bound to sender_ack_port
        // The receiver sends ACKs back to this port.
        // The timeout controls how long we wait for each ACK.
        // ----------------------------------------------------------------
        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeoutMs);

        // ----------------------------------------------------------------
        // 4. Print startup info
        // ----------------------------------------------------------------
        System.out.println("=== DS-FTP Sender ===");
        System.out.println("Mode         : " + (useGBN ? "Go-Back-N (window=" + windowSize + ")" : "Stop-and-Wait"));
        System.out.println("Sending to   : " + rcvIp + ":" + rcvDataPort);
        System.out.println("Listening on : 0.0.0.0:" + senderAckPort + " (for ACKs)");
        System.out.println("File         : " + inputFile + " (" + file.length() + " bytes)");
        System.out.println("Timeout      : " + timeoutMs + " ms");
        System.out.println();

        // ----------------------------------------------------------------
        // Issue #3: Handshake (send SOT, wait for ACK 0)
        // ----------------------------------------------------------------
        long startTime = System.currentTimeMillis(); // timer starts when we send SOT

        // build the start-of-transmission packet
        DSPacket sotPacket = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        byte[] sotBytes = sotPacket.toBytes();
        DatagramPacket sotDatagram = new DatagramPacket(sotBytes, sotBytes.length, rcvAddress, rcvDataPort);

        // buffer for the ACK response
        byte[] ackBuf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket ackDatagram = new DatagramPacket(ackBuf, ackBuf.length);

        int timeoutCount = 0;
        boolean handshakeDone = false;

        // retry loop - keep sending SOT until we get ACK 0 or hit the 3-timeout limit
        while (!handshakeDone) {
            socket.send(sotDatagram);
            System.out.println("[Handshake] Sent SOT (Seq=0)");

            try {
                socket.receive(ackDatagram);
                DSPacket ack = new DSPacket(ackDatagram.getData());

                // check if this is the ACK we're looking for
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[Handshake] Received ACK (Seq=0) -- connection established");
                    handshakeDone = true;
                } else {
                    System.out.println("[Handshake] Unexpected packet (Type=" + ack.getType()
                            + ", Seq=" + ack.getSeqNum() + "), ignoring...");
                }
            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[Handshake] Timeout #" + timeoutCount + ", retransmitting SOT...");
                // fail after 3 consecutive timeouts - can't establish connection
                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    socket.close();
                    System.exit(1);
                }
            }
        }

        // ----------------------------------------------------------------
        // Issue #4: Stop-and-Wait data transfer + teardown
        // ----------------------------------------------------------------
        if (!useGBN) {

            FileInputStream fis = new FileInputStream(file);
            int seq = 1; // first DATA starts at seq 1 (SOT used seq 0)
            timeoutCount = 0;

            byte[] chunk = new byte[DSPacket.MAX_PAYLOAD_SIZE];
            int bytesRead = fis.read(chunk);

            // send one packet at a time and wait for its ACK
            while (bytesRead != -1) {
                byte[] payload = new byte[bytesRead];
                System.arraycopy(chunk, 0, payload, 0, bytesRead);

                DSPacket dataPacket = new DSPacket(DSPacket.TYPE_DATA, seq, payload);
                byte[] dataBytes = dataPacket.toBytes();
                DatagramPacket dataDatagram = new DatagramPacket(dataBytes, dataBytes.length, rcvAddress, rcvDataPort);

                boolean acked = false;
                while (!acked) {
                    socket.send(dataDatagram);
                    System.out.println("[S&W] Sent DATA Seq=" + seq + " (" + bytesRead + " bytes)");

                    try {
                        socket.receive(ackDatagram);
                        DSPacket ack = new DSPacket(ackDatagram.getData());

                        // check it's the ACK we're waiting for
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                            System.out.println("[S&W] Got ACK Seq=" + seq);
                            acked = true;
                            timeoutCount = 0; // reset counter on progress
                            seq = (seq + 1) % 128;
                        } else {
                            System.out.println("[S&W] Wrong ACK (got Seq=" + ack.getSeqNum() + "), retransmitting...");
                        }
                    } catch (SocketTimeoutException e) {
                        timeoutCount++;
                        System.out.println("[S&W] Timeout #" + timeoutCount + " on Seq=" + seq + ", retransmitting...");
                        // 3 consecutive timeouts on the same packet - give up
                        if (timeoutCount >= 3) {
                            System.out.println("Unable to transfer file.");
                            fis.close();
                            socket.close();
                            System.exit(1);
                        }
                    }
                }

                bytesRead = fis.read(chunk);
            }

            fis.close();

            // teardown - send EOT and wait for its ACK
            int eotSeq = seq; // seq already advanced past the last data packet
            DSPacket eotPacket = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
            byte[] eotBytes = eotPacket.toBytes();
            DatagramPacket eotDatagram = new DatagramPacket(eotBytes, eotBytes.length, rcvAddress, rcvDataPort);

            timeoutCount = 0;
            boolean eotAcked = false;
            while (!eotAcked) {
                socket.send(eotDatagram);
                System.out.println("[Teardown] Sent EOT Seq=" + eotSeq);

                try {
                    socket.receive(ackDatagram);
                    DSPacket ack = new DSPacket(ackDatagram.getData());

                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                        System.out.println("[Teardown] Got ACK for EOT");
                        eotAcked = true;
                    }
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[Teardown] Timeout #" + timeoutCount + " on EOT, retransmitting...");
                    if (timeoutCount >= 3) {
                        System.out.println("Unable to transfer file.");
                        socket.close();
                        System.exit(1);
                    }
                }
            }

            // print total time from SOT send to EOT ACK receipt
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("Total Transmission Time: %.2f seconds%n", elapsed / 1000.0);
        } else {
            // GBN Sender Mode
            System.out.println("[GBN] Starting Go-Back-N transmission with window size=" + windowSize);
            
            FileInputStream fis = new FileInputStream(file);
            
            // GBN sender state
            int base = 1;           // oldest unACKed packet sequence
            int nextSeq = 1;        // next sequence to send
            timeoutCount = 0;       // timeout counter for current window
            
            // Queues for packet management
            java.util.ArrayList<DSPacket> sendBuffer = new java.util.ArrayList<>();
            java.util.ArrayList<DSPacket> windowPackets = new java.util.ArrayList<>();
            
            // Read entire file into buffer of DSPackets
            System.out.println("[GBN] Reading file into packet buffer...");
            byte[] chunk = new byte[DSPacket.MAX_PAYLOAD_SIZE];
            int bytesRead;
            int packetSeq = 1;
            
            while ((bytesRead = fis.read(chunk)) != -1) {
                byte[] payload = new byte[bytesRead];
                System.arraycopy(chunk, 0, payload, 0, bytesRead);
                DSPacket pkt = new DSPacket(DSPacket.TYPE_DATA, packetSeq, payload);
                sendBuffer.add(pkt);
                System.out.println("[GBN] Buffered DATA Seq=" + packetSeq + " (" + bytesRead + " bytes)");
                packetSeq = (packetSeq + 1) % 128;
            }
            
            fis.close();
            int totalPackets = sendBuffer.size();
            System.out.println("[GBN] Total packets to send: " + totalPackets);
            
            if (totalPackets == 0) {
                // Empty file - send EOT immediately
                System.out.println("[GBN] Empty file - sending EOT");
                int eotSeq = 1;
                DSPacket eotPacket = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
                byte[] eotBytes = eotPacket.toBytes();
                DatagramPacket eotDatagram = new DatagramPacket(eotBytes, eotBytes.length, rcvAddress, rcvDataPort);
                
                timeoutCount = 0;
                boolean eotAcked = false;
                while (!eotAcked) {
                    socket.send(eotDatagram);
                    System.out.println("[Teardown] Sent EOT Seq=" + eotSeq);
                    
                    try {
                        socket.receive(ackDatagram);
                        DSPacket ack = new DSPacket(ackDatagram.getData());
                        
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                            System.out.println("[Teardown] Got ACK for EOT");
                            eotAcked = true;
                        }
                    } catch (SocketTimeoutException e) {
                        timeoutCount++;
                        System.out.println("[Teardown] Timeout #" + timeoutCount + " on EOT, retransmitting...");
                        if (timeoutCount >= 3) {
                            System.out.println("Unable to transfer file.");
                            socket.close();
                            System.exit(1);
                        }
                    }
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.printf("Total Transmission Time: %.2f seconds%n", elapsed / 1000.0);
                socket.close();
                return;
            }
            
            // GBN main loop - send window, receive ACKs
            int sendBufferIndex = 0;
            timeoutCount = 0;
            
            while (base <= totalPackets) {
                // Refill window from sendBuffer
                while (windowPackets.size() < windowSize && sendBufferIndex < totalPackets) {
                    windowPackets.add(sendBuffer.get(sendBufferIndex));
                    sendBufferIndex++;
                }
                
                // Transmit window packets with permutation
                System.out.println("[GBN] Transmitting window [base=" + base + ", nextSeq=" + nextSeq + "]");
                java.util.List<DSPacket> toSend = new java.util.ArrayList<>(windowPackets);
                
                // Apply permutation in groups of 4
                java.util.List<DSPacket> permuted = new java.util.ArrayList<>();
                int i = 0;
                while (i < toSend.size()) {
                    int groupEnd = Math.min(i + 4, toSend.size());
                    if (groupEnd - i == 4) {
                        // Exactly 4 packets - permute
                        java.util.List<DSPacket> group = toSend.subList(i, groupEnd);
                        java.util.List<DSPacket> permGroup = ChaosEngine.permutePackets(group);
                        permuted.addAll(permGroup);
                        i += 4;
                    } else {
                        // Fewer than 4 - send as-is
                        permuted.addAll(toSend.subList(i, groupEnd));
                        i = groupEnd;
                    }
                }
                
                // Send all permuted packets
                for (DSPacket pkt : permuted) {
                    byte[] pktBytes = pkt.toBytes();
                    DatagramPacket dgPkt = new DatagramPacket(pktBytes, pktBytes.length, rcvAddress, rcvDataPort);
                    socket.send(dgPkt);
                    System.out.println("[GBN] Sent Seq=" + pkt.getSeqNum());
                }
                
                // Wait for ACKs
                boolean windowComplete = false;
                while (!windowComplete) {
                    try {
                        socket.receive(ackDatagram);
                        DSPacket ack = new DSPacket(ackDatagram.getData());
                        
                        if (ack.getType() == DSPacket.TYPE_ACK) {
                            int ackSeq = ack.getSeqNum();
                            System.out.println("[GBN] Received ACK Seq=" + ackSeq);
                            
                            // Check if ACK is for packets in current window
                            if (WindowHelper.inWindow(ackSeq, base, windowSize) || ackSeq >= base) {
                                // Valid ACK - advance base
                                if (ackSeq >= base) {
                                    int advanceBy = (ackSeq - base + 1 + 128) % 128;
                                    if (advanceBy == 0) advanceBy = 1;
                                    
                                    for (int j = 0; j < advanceBy && windowPackets.size() > 0; j++) {
                                        windowPackets.remove(0);
                                    }
                                    
                                    base = (ackSeq + 1) % 128;
                                    timeoutCount = 0;
                                    System.out.println("[GBN] Window advanced, new base=" + base);
                                }
                            }
                            
                            // Check if all packets have been ACKed
                            if (base > totalPackets && windowPackets.isEmpty()) {
                                windowComplete = true;
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        timeoutCount++;
                        System.out.println("[GBN] Timeout #" + timeoutCount + " on base Seq=" + base + ", retransmitting window...");
                        
                        if (timeoutCount >= 3) {
                            System.out.println("Unable to transfer file.");
                            socket.close();
                            System.exit(1);
                        }
                        
                        // Retransmit entire window
                        break;
                    }
                }
            }
            
            System.out.println("[GBN] All packets sent and ACKed.");
            
            // Send EOT
            int eotSeq = nextSeq;
            DSPacket eotPacket = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
            byte[] eotBytes = eotPacket.toBytes();
            DatagramPacket eotDatagram = new DatagramPacket(eotBytes, eotBytes.length, rcvAddress, rcvDataPort);
            
            timeoutCount = 0;
            boolean eotAcked = false;
            while (!eotAcked) {
                socket.send(eotDatagram);
                System.out.println("[Teardown] Sent EOT Seq=" + eotSeq);
                
                try {
                    socket.receive(ackDatagram);
                    DSPacket ack = new DSPacket(ackDatagram.getData());
                    
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                        System.out.println("[Teardown] Got ACK for EOT");
                        eotAcked = true;
                    }
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[Teardown] Timeout #" + timeoutCount + " on EOT, retransmitting...");
                    if (timeoutCount >= 3) {
                        System.out.println("Unable to transfer file.");
                        socket.close();
                        System.exit(1);
                    }
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("Total Transmission Time: %.2f seconds%n", elapsed / 1000.0);
        }

        socket.close();
    }
}
