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
    // 端口 0 让系统分配随机高端口，避免端口冲突
    private static final int PORT_AUTO = 0;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final Context context;
    // 实际绑定成功后的端口号，0 表示服务尚未启动
    private volatile int actualPort = 0;

    public AlbumArtServer(Context context) {
        this.context = context;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT_AUTO);
                actualPort = serverSocket.getLocalPort();
                Log.d(TAG, "Server started on port " + actualPort);
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
        actualPort = 0;
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

    public String getHttpUrl(String fileUri) {
        if (fileUri == null || !fileUri.startsWith("file:")) return fileUri;
        if (actualPort == 0) {
            // 服务尚未完成绑定，回退原始 URI，避免生成无效 URL
            Log.w(TAG, "getHttpUrl called before server is ready, returning original URI");
            return fileUri;
        }
        // Extract filename from file:/.../cache/filename.jpg
        int lastSlash = fileUri.lastIndexOf('/');
        if (lastSlash != -1) {
            String fileName = fileUri.substring(lastSlash + 1);
            return "http://127.0.0.1:" + actualPort + "/" + fileName;
        }
        return fileUri;
    }
}
