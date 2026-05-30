import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;

public class JavaFileClient {
    private static Socket socket;
    private static DataInputStream in;
    private static DataOutputStream out;

    private static JFrame frame;
    private static DefaultListModel<String> fileListModel;
    private static JList<String> fileList;
    private static JButton btnDownload, btnUpload, btnRefresh;

    public static void main(String[] args) {
        showLoginDialog();
    }

    private static void showLoginDialog() {
        JPanel panel = new JPanel(new GridLayout(3, 2, 5, 5));
        JTextField txtHost = new JTextField("localhost");
        JTextField txtUser = new JTextField();
        JPasswordField txtPass = new JPasswordField();

        panel.add(new JLabel("Server Host:"));   panel.add(txtHost);
        panel.add(new JLabel("Username:"));      panel.add(txtUser);
        panel.add(new JLabel("Password:"));      panel.add(txtPass);

        int result = JOptionPane.showConfirmDialog(null, panel, "Connect to Sim-NFS Server", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            try {
                socket = new Socket(txtHost.getText(), 8080);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                // Attempt Authentication
                out.writeUTF("AUTH " + txtUser.getText() + " " + new String(txtPass.getPassword()));
                String response = in.readUTF();

                if ("AUTH_SUCCESS".equals(response)) {
                    createAndShowMainGUI();
                    refreshFileList();
                } else {
                    JOptionPane.showMessageDialog(null, "Invalid Credentials. Exiting.", "Auth Failed", JOptionPane.ERROR_MESSAGE);
                    System.exit(0);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Cannot connect to server: " + e.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
                System.exit(0);
            }
        } else {
            System.exit(0);
        }
    }

    private static void createAndShowMainGUI() {
        frame = new JFrame("NFS Client File Explorer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 400);

        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        JScrollPane scrollPane = new JScrollPane(fileList);

        JPanel controlPanel = new JPanel();
        btnRefresh = new JButton("Refresh Files");
        btnDownload = new JButton("Download Selected");
        btnUpload = new JButton("Upload File");

        controlPanel.add(btnRefresh);
        controlPanel.add(btnDownload);
        controlPanel.add(btnUpload);

        frame.add(new JLabel(" Available Server Files:", JLabel.LEFT), BorderLayout.NORTH);
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(controlPanel, BorderLayout.SOUTH);

        // Action Listeners
        btnRefresh.addActionListener(e -> refreshFileList());

        btnDownload.addActionListener(e -> {
            String selectedValue = fileList.getSelectedValue();
            if (selectedValue == null || selectedValue.startsWith("No files")) {
                JOptionPane.showMessageDialog(frame, "Please select a valid file to download.");
                return;
            }
            String rawFileName = selectedValue.split(" \\(")[0];
            downloadFile(rawFileName);
        });

        btnUpload.addActionListener(e -> uploadFile());

        frame.setVisible(true);
    }

    private static void refreshFileList() {
        try {
            out.writeUTF("LOOKUP");
            String response = in.readUTF();
            fileListModel.clear();
            if (response.isEmpty() || response.contains("No files")) {
                fileListModel.addElement("No files available on server.");
            } else {
                String[] files = response.split(",");
                for (String f : files) {
                    fileListModel.addElement(f);
                }
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Lost connection to server.");
        }
    }

    private static void downloadFile(String filename) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File(filename));
        int option = fileChooser.showSaveDialog(frame);
        if (option != JFileChooser.APPROVE_OPTION) return;

        File targetLocation = fileChooser.getSelectedFile();

        try {
            out.writeUTF("READ " + filename);
            long fileSize = in.readLong();

            if (fileSize == -1) {
                JOptionPane.showMessageDialog(frame, "File not found or access denied on server.");
                return;
            }

            try (FileOutputStream fos = new FileOutputStream(targetLocation)) {
                byte[] buffer = new byte[4096];
                long totalRead = 0;
                int bytesRead;
                while (totalRead < fileSize && (bytesRead = in.read(buffer, 0, (int)Math.min(buffer.length, fileSize - totalRead))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
            }
            JOptionPane.showMessageDialog(frame, "Download completed successfully!");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Error downloading file: " + e.getMessage());
        }
    }

    private static void uploadFile() {
        JFileChooser fileChooser = new JFileChooser();
        int option = fileChooser.showOpenDialog(frame);
        if (option != JFileChooser.APPROVE_OPTION) return;

        File selectedFile = fileChooser.getSelectedFile();

        try {
            out.writeUTF("WRITE " + selectedFile.getName() + " " + selectedFile.length());

            try (FileInputStream fis = new FileInputStream(selectedFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            String acknowledgement = in.readUTF();
            if ("WRITE_SUCCESS".equals(acknowledgement)) {
                JOptionPane.showMessageDialog(frame, "Upload complete!");
                refreshFileList();
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Error uploading file: " + e.getMessage());
        }
    }
}
