import java.io.*;
import java.net.*;

public class Receiver {

    public static void main(String[] args) throws Exception {

        // ----------------------------------------------------------------
        // 1. Parse command-line arguments
        // Usage: java Receiver <sender_ip> <sender_ack_port>
        // <rcv_data_port> <output_file> <RN>
        //
        // RN = 0 --> no ACKs dropped
        // RN = X --> every Xth ACK is dropped (via ChaosEngine)
        // ----------------------------------------------------------------
        if (args.length != 5) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
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
        // TODO Issue #3: Handshake (wait for SOT, reply ACK 0)
        // TODO Issue #4: Stop-and-Wait data receive + teardown
        // TODO Issue #5: Go-Back-N data receive + teardown
        // TODO Issue #6: Integrate ChaosEngine ACK dropping
        // ----------------------------------------------------------------

        socket.close();
    }
}
