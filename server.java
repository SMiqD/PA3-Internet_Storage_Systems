import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class server {

    // Match your workspace folder: sFiles/sample01.bmp ... sample10.bmp
    private static final String FILE_DIR = "sFiles";
    private static final int TOTAL_FILES = 10;

    private static volatile ServerSocket serverSocket;

    // For graceful termination when ALL connected clients have typed "bye"
    private static final AtomicInteger totalConnections = new AtomicInteger(0);
    private static final AtomicInteger byeConnections = new AtomicInteger(0);
    private static final AtomicInteger activeHandlers = new AtomicInteger(0);

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java server [port_number]");
            return;
        }

        int port = Integer.parseInt(args[0]);

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server listening on port " + port);

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    totalConnections.incrementAndGet();
                    activeHandlers.incrementAndGet();

                    System.out.println("Client connected from: " + clientSocket.getInetAddress());

                    Thread clientThread = new Thread(new ClientHandler(clientSocket));
                    clientThread.start();
                } catch (SocketException e) {
                    // Expected when we close serverSocket to shutdown gracefully.
                    break;
                }
            }

            System.out.println("Server stopped accepting new connections. Waiting for handlers...");
            while (activeHandlers.get() > 0) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            System.out.println("Server exiting.");

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            boolean sentBye = false;

            try (
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream())
            ) {
                out.writeUTF("Hello!");
                out.flush();

                while (true) {
                    String command;
                    try {
                        command = in.readUTF();
                    } catch (EOFException e) {
                        break;
                    }

                    System.out.println("Received command: " + command);

                    if (command.equalsIgnoreCase("bye")) {
                        sentBye = true;
                        byeConnections.incrementAndGet();

                        out.writeUTF("disconnected");
                        out.flush();
                        break;
                    }

                    if (!command.equalsIgnoreCase("SEND")) {
                        out.writeUTF("Please type a different command");
                        out.flush();
                        continue;
                    }

                    int batchSize = in.readInt();
                    int totalFiles = in.readInt();

                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < totalFiles; i++) order.add(in.readInt());

                    System.out.println("Sending files in order: " + order + " with batch size " + batchSize);

                    sendFilesInBatches(order, batchSize, out);
                    out.writeUTF("DONE");
                    out.flush();
                }

            } catch (IOException e) {
                System.err.println("Client handler error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                System.out.println("Client disconnected: " + socket.getInetAddress());

                activeHandlers.decrementAndGet();

                // Graceful server termination condition:
                // Only exit when every connection has typed "bye" AND all handlers are done.
                if (activeHandlers.get() == 0
                        && totalConnections.get() > 0
                        && byeConnections.get() == totalConnections.get()) {
                    try { serverSocket.close(); } catch (IOException ignored) {}
                }
            }
        }

        private String fileNameFor(int fileNum) {
            // sample01.bmp ... sample10.bmp
            return String.format("sample%02d.bmp", fileNum);
        }

        private void sendFilesInBatches(List<Integer> order, int batchSize, DataOutputStream out) throws IOException {
            int index = 0;

            while (index < order.size()) {
                int currentBatchSize = Math.min(batchSize, order.size() - index);
                out.writeInt(currentBatchSize);

                for (int i = 0; i < currentBatchSize; i++) {
                    int fileNum = order.get(index++);
                    if (fileNum < 1 || fileNum > TOTAL_FILES) {
                        out.writeUTF("MISSING");
                        out.writeUTF("invalid_file_number_" + fileNum);
                        out.writeLong(0);
                        continue;
                    }

                    File file = new File(FILE_DIR, fileNameFor(fileNum));

                    if (!file.exists()) {
                        out.writeUTF("MISSING");
                        out.writeUTF(file.getName());
                        out.writeLong(0);
                        continue;
                    }

                    out.writeUTF("OK");
                    out.writeUTF(file.getName());
                    out.writeLong(file.length());

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    } catch (IOException ex) {
                        // If file read fails mid-way, report ERROR for this file (no bytes sent).
                        out.writeUTF("ERROR");
                        out.writeUTF(file.getName());
                        out.writeLong(0);
                        System.err.println("Error reading " + file.getName() + ": " + ex.getMessage());
                    }
                }

                out.flush();
            }

            out.writeInt(0); // end of all batches
            out.flush();
        }
    }
}
