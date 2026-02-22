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
        // TODO Issue #3: Handshake (send SOT, wait for ACK 0)
        // TODO Issue #4: Stop-and-Wait data transfer + teardown
        // TODO Issue #5: Go-Back-N data transfer + teardown
        // TODO Issue #6: Integrate ChaosEngine packet reordering (GBN)
        // ----------------------------------------------------------------

        socket.close();
    }
}
