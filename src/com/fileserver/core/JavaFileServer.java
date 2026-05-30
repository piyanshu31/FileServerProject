import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fileserver.network.ClientWorker;

public class JavaFileServer {
    private static final int PORT = 8080;
    private static final String STORAGE_DIR = "./server_storage";
    private static final String AUTH_FILE = "./users.txt";

    private static JTextArea logArea;
    private static Map<String, String> userDatabase = new HashMap<>();
    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        setupEnvironment();
        loadUsers();
        createAndShowGUI();
        startServerEngine();
    }

    private static void setupEnvironment() {
        File storage = new File(STORAGE_DIR);
        if (!storage.exists()) storage.mkdir();

        File auth = new File(AUTH_FILE);
        if (!auth.exists()) {
            try (PrintWriter writer = new PrintWriter(new FileWriter(auth))) {
                // Default mock users: admin/admin123 and user/password
                writer.println("admin:admin123");
                writer.println("user:password");
            } catch (IOException e) {
                System.err.println("Could not create mock user database.");
            }
        }
    }

    private static void loadUsers() {
        try (BufferedReader reader = new BufferedReader(new FileReader(AUTH_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    userDatabase.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            log("Error loading user credentials.");
        }
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("Java Sim-NFS File Server Console");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(Color.BLACK);
        logArea.setForeground(Color.GREEN);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(logArea);
        frame.add(scrollPane, BorderLayout.CENTER);

        JLabel statusLabel = new JLabel(" Server Status: RUNNING on Port " + PORT, JLabel.LEFT);
        statusLabel.setPreferredSize(new Dimension(600, 30));
        frame.add(statusLabel, BorderLayout.SOUTH);

        frame.setVisible(true);
        log("Server logs initialized...");
    }

    private static void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
        System.out.println(message);
    }

    private static void startServerEngine() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                log("NFS Server Engine listening on port " + PORT + "...");
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    log("Inbound connection from: " + clientSocket.getRemoteSocketAddress());
                    threadPool.execute(new ClientWorker(clientSocket, STORAGE_DIR, userDatabase));
                }
            } catch (IOException e) {
                log("Server Socket error: " + e.getMessage());
            }
        }).start();
    }

    // Worker Thread Handling Protocol Actions
    private static class ClientWorker implements Runnable {
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private boolean isAuthenticated = false;

        public ClientWorker(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                while (true) {
                    String command = in.readUTF();

                    if (!isAuthenticated && !command.startsWith("AUTH")) {
                        out.writeUTF("ERROR: Authentication required.");
                        continue;
                    }

                    if (command.startsWith("AUTH")) {
                        handleAuth(command);
                    } else if (command.equals("LOOKUP")) {
                        handleLookup();
                    } else if (command.startsWith("READ")) {
                        handleRead(command);
                    } else if (command.startsWith("WRITE")) {
                        handleWrite(command);
                    } else if (command.equals("EXIT")) {
                        out.writeUTF("GOODBYE");
                        break;
                    }
                }
            } catch (IOException e) {
                log("Client disconnected abruptly: " + socket.getRemoteSocketAddress());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void handleAuth(String command) throws IOException {
            String[] tokens = command.split(" ");
            if (tokens.length < 3) {
                out.writeUTF("AUTH_FAIL");
                return;
            }
            String user = tokens[1];
            String pass = tokens[2];

            if (userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
                isAuthenticated = true;
                out.writeUTF("AUTH_SUCCESS");
                log("User '" + user + "' successfully authenticated.");
            } else {
                out.writeUTF("AUTH_FAIL");
                log("Failed login attempt for user: " + user);
            }
        }

        private void handleLookup() throws IOException {
            File folder = new File(STORAGE_DIR);
            File[] listOfFiles = folder.listFiles();
            StringBuilder fileList = new StringBuilder();

            if (listOfFiles != null) {
                for (File file : listOfFiles) {
                    if (file.isFile()) {
                        fileList.append(file.getName()).append(" (").append(file.length()).append(" bytes),");
                    }
                }
            }
            if (fileList.length() > 0) {
                fileList.setLength(fileList.length() - 1); // strip trailing comma
            } else {
                fileList.append("No files available.");
            }
            out.writeUTF(fileList.toString());
        }

        private void handleRead(String command) throws IOException {
            String filename = command.substring(5).trim();
            File file = new File(STORAGE_DIR, filename);

            // Prevent path traversal exploits
            if (!file.getParentFile().getCanonicalPath().equals(new File(STORAGE_DIR).getCanonicalPath())) {
                out.writeLong(-1);
                return;
            }

            if (file.exists() && file.isFile()) {
                out.writeLong(file.length());
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                log("Sent file '" + filename + "' to client.");
            } else {
                out.writeLong(-1); // File not found flag
            }
        }

        private void handleWrite(String command) throws IOException {
            String[] tokens = command.split(" ", 3);
            String filename = tokens[1];
            long fileSize = Long.parseLong(tokens[2]);

            File file = new File(STORAGE_DIR, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                long totalRead = 0;
                int bytesRead;
                while (totalRead < fileSize && (bytesRead = in.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
            }
            out.writeUTF("WRITE_SUCCESS");
            log("Received and saved file '" + filename + "' from client.");
        }
    }
}
