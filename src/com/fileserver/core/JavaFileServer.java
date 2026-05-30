package com.fileserver.core;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fileserver.network.ClientWorker; // Correctly imports standalone worker

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

    // Public method so ClientWorker can write logs back to the Server Console UI
    public static void log(String message) {
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

                    // Correctly passes the 3 parameters to your standalone ClientWorker file
                    threadPool.execute(new ClientWorker(clientSocket, STORAGE_DIR, userDatabase));
                }
            } catch (IOException e) {
                log("Server Socket error: " + e.getMessage());
            }
        }).start();
    }
}
