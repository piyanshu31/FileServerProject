package com.fileserver.network;

import java.io.*;
import java.net.Socket;
import java.util.Map;

public class ClientWorker implements Runnable {
    private final Socket socket;
    private final String storageDir;
    private final Map<String, String> userDatabase;

    private DataInputStream in;
    private DataOutputStream out;
    private boolean isAuthenticated = false;

    // Pass the socket, storage path, and user database references from the main server
    public ClientWorker(Socket socket, String storageDir, Map<String, String> userDatabase) {
        this.socket = socket;
        this.storageDir = storageDir;
        this.userDatabase = userDatabase;
    }

    @Override
    public void run() {
        try {
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            while (true) {
                // Read the command string sent by the client
                String command = in.readUTF();

                // Block actions if the client hasn't logged in yet
                if (!isAuthenticated && !command.startsWith("AUTH")) {
                    out.writeUTF("ERROR: Authentication required.");
                    continue;
                }

                // Sim-NFS Protocol Router
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
            System.out.println("Client disconnected info: " + socket.getRemoteSocketAddress());
        } finally {
            cleanUp();
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
        } else {
            out.writeUTF("AUTH_FAIL");
        }
    }

    private void handleLookup() throws IOException {
        File folder = new File(storageDir);
        File[] listOfFiles = folder.listFiles();
        StringBuilder fileList = new StringBuilder();

        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                if (file.isFile()) {
                    fileList.append(file.getName())
                            .append(" (")
                            .append(file.length())
                            .append(" bytes),");
                }
            }
        }

        if (fileList.length() > 0) {
            fileList.setLength(fileList.length() - 1); // Remove trailing comma
        } else {
            fileList.append("No files available.");
        }
        out.writeUTF(fileList.toString());
    }

    private void handleRead(String command) throws IOException {
        String filename = command.substring(5).trim();
        File file = new File(storageDir, filename);

        // Security check: Prevent path traversal exploits (e.g., ../../etc/passwd)
        if (!file.getParentFile().getCanonicalPath().equals(new File(storageDir).getCanonicalPath())) {
            out.writeLong(-1);
            return;
        }

        if (file.exists() && file.isFile()) {
            out.writeLong(file.length()); // Send file size first
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } else {
            out.writeLong(-1); // Tell client file doesn't exist
        }
    }

    private void handleWrite(String command) throws IOException {
        String[] tokens = command.split(" ", 3);
        String filename = tokens[1];
        long fileSize = Long.parseLong(tokens[2]);

        File file = new File(storageDir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            long totalRead = 0;
            int bytesRead;
            // Stream the exact file byte length down the socket channel
            while (totalRead < fileSize && (bytesRead = in.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
        }
        out.writeUTF("WRITE_SUCCESS");
    }

    private void cleanUp() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing client socket: " + e.getMessage());
        }
    }
}
