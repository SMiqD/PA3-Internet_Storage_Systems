import java.io.*;
import java.net.*;
import java.util.*;

public class client {

    private static final int TOTAL_FILES = 10;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java client [serverURL] [port_number]");
            return;
        }

        String serverURL = args[0];
        int port = Integer.parseInt(args[1]);

        // Keep RTTs separate per batch size (1,2,3) to match rubric.
        Map<Integer, List<Long>> rttsByBatchSize = new HashMap<>();

        try (
            Socket socket = new Socket(serverURL, port);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in))
        ) {
            System.out.println("Server: " + in.readUTF());

            while (true) {
                System.out.print("Enter command (SEND or bye): ");
                String command = userInput.readLine();

                if (command == null) break;

                if (command.equalsIgnoreCase("bye")) {
                    out.writeUTF("bye");
                    out.flush();

                    String response = in.readUTF();
                    System.out.println("Server: " + response);

                    if ("disconnected".equals(response)) {
                        System.out.println("exit");
                        break;
                    }
                    continue;
                }

                if (!command.equalsIgnoreCase("SEND")) {
                    out.writeUTF(command);
                    out.flush();
                    System.out.println("Server: " + in.readUTF());
                    continue;
                }

                int batchSize = promptBatchSize(userInput);

                long startTime = System.currentTimeMillis();

                List<Integer> randomOrder = generateRandomOrder();
                System.out.println("Random order: " + randomOrder);

                out.writeUTF("SEND");
                out.writeInt(batchSize);
                out.writeInt(TOTAL_FILES);

                for (int num : randomOrder) out.writeInt(num);
                out.flush();

                receiveFiles(in);

                String doneMsg = in.readUTF();
                long rtt = System.currentTimeMillis() - startTime;

                if ("DONE".equals(doneMsg)) {
                    List<Long> rtts = rttsByBatchSize.computeIfAbsent(batchSize, k -> new ArrayList<>());
                    rtts.add(rtt);

                    System.out.println("RTT (batch " + batchSize + "): " + rtt + " ms");

                    if (rtts.size() % 5 == 0) {
                        printStats(rtts, batchSize);
                    }
                } else {
                    System.out.println("Server: " + doneMsg);
                }
            }

        } catch (ConnectException e) {
            System.err.println("Could not connect to server");
        } catch (IOException e) {
            System.err.println("I/O Error: " + e.getMessage());
        } catch (NumberFormatException e) {
            System.err.println("Invalid number input.");
        }
    }

    private static int promptBatchSize(BufferedReader userInput) throws IOException {
        while (true) {
            System.out.print("Enter batch size (1, 2, or 3): ");
            String line = userInput.readLine();
            if (line == null) throw new EOFException("No batch size provided.");

            try {
                int batchSize = Integer.parseInt(line.trim());
                if (batchSize == 1 || batchSize == 2 || batchSize == 3) return batchSize;
            } catch (NumberFormatException ignored) {}

            System.out.println("Invalid batch size. Please enter 1, 2, or 3.");
        }
    }

    private static List<Integer> generateRandomOrder() {
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= TOTAL_FILES; i++) order.add(i);
        Collections.shuffle(order);
        return order;
    }

    private static void receiveFiles(DataInputStream in) throws IOException {
        File outputDir = new File("client_received");
        if (!outputDir.exists()) outputDir.mkdirs();

        while (true) {
            int batchCount = in.readInt();
            if (batchCount == 0) break;

            for (int i = 0; i < batchCount; i++) {
                String status = in.readUTF();
                String fileName = in.readUTF();
                long fileSize = in.readLong();

                if (!"OK".equals(status)) {
                    System.out.println("Server reported " + status + " for file: " + fileName);
                    // If status isn't OK, server will not send bytes for this file.
                    continue;
                }

                File outFile = new File(outputDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[4096];
                    long remaining = fileSize;

                    while (remaining > 0) {
                        int bytesRead = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (bytesRead == -1) {
                            throw new EOFException("Unexpected end of stream while receiving file.");
                        }
                        fos.write(buffer, 0, bytesRead);
                        remaining -= bytesRead;
                    }
                }

                System.out.println("Received file: " + fileName + " (" + fileSize + " bytes)");
            }
        }
    }

    static void printStats(List<Long> rtts, int batchSize) {
        if (rtts.isEmpty()) {
            System.out.println("No RTT data for batch " + batchSize + ".");
            return;
        }

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;

        for (long rtt : rtts) {
            min = Math.min(min, rtt);
            max = Math.max(max, rtt);
            sum += rtt;
        }

        double mean = (double) sum / rtts.size();

        double variance = 0;
        for (long rtt : rtts) variance += Math.pow(rtt - mean, 2);
        double stddev = Math.sqrt(variance / rtts.size());

        System.out.println("\nRTT Statistics (batch " + batchSize + ", " + rtts.size() + " samples)");
        System.out.printf("Maximum: %.2f ms%n", (double) max);
        System.out.printf("Minimum: %.2f ms%n", (double) min);
        System.out.printf("Mean: %.2f ms%n", mean);
        System.out.printf("Standard Deviation: %.2f ms%n%n", stddev);
    }
}
