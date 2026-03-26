package com.cusc.media.base.player;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class AlbumArtServer {
    private static final String TAG = "AlbumArtServer";
    private static final int PORT = 8080;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final Context context;

    public AlbumArtServer(Context context) {
        this.context = context;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Server started on port " + PORT);
                while (isRunning) {
                    try (Socket socket = serverSocket.accept()) {
                        handleRequest(socket);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error handling request", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not start server", e);
            }
        }).start();
    }

    public void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server", e);
            }
        }
    }

    private void handleRequest(Socket socket) {
        try {
            InputStream input = socket.getInputStream();
            Scanner scanner = new Scanner(input).useDelimiter("\r\n");
            if (!scanner.hasNext()) return;
            
            String line = scanner.next();
            String[] parts = line.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) return;

            String path = parts[1];
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            File file = new File(context.getCacheDir(), path);
            if (file.exists() && file.isFile()) {
                sendResponse(socket, "200 OK", "image/jpeg", file);
            } else {
                sendResponse(socket, "404 Not Found", "text/plain", null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Handle request failed", e);
        }
    }

    private void sendResponse(Socket socket, String status, String contentType, File file) throws IOException {
        OutputStream output = new BufferedOutputStream(socket.getOutputStream());
        output.write(("HTTP/1.1 " + status + "\r\n").getBytes());
        output.write(("Content-Type: " + contentType + "\r\n").getBytes());
        if (file != null) {
            output.write(("Content-Length: " + file.length() + "\r\n").getBytes());
        }
        output.write("\r\n".getBytes());

        if (file != null) {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
            }
        }
        output.flush();
    }

    public static String getHttpUrl(String fileUri) {
        if (fileUri == null || !fileUri.startsWith("file:")) return fileUri;
        // Extract filename from file:/.../cache/filename.jpg
        int lastSlash = fileUri.lastIndexOf('/');
        if (lastSlash != -1) {
            String fileName = fileUri.substring(lastSlash + 1);
            return "http://127.0.0.1:" + PORT + "/" + fileName;
        }
        return fileUri;
    }
}
