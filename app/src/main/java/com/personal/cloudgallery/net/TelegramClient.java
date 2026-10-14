package com.personal.cloudgallery.net;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TelegramClient {
    public static class UploadResult {
        public boolean ok;
        public String rawJson;
        public String telegramChatId;
        public long telegramMessageId;
        public String telegramFileId;
        public String telegramFileUniqueId;
        public String error;
    }

    public static UploadResult upload(Context ctx, Uri sourceUri, String displayName, String mime,
                                      long size, boolean isVideo, String token, String chatId, String localBase) throws Exception {
        UploadResult result = new UploadResult();
        ContentResolver resolver = ctx.getContentResolver();
        long actualSize = size;
        String base = actualSize > 50_000_000L ? localBase : "https://api.telegram.org";
        String method = isVideo ? "sendVideo" : "sendDocument";
        String endpoint = base + "/bot" + token + "/" + method;

        String boundary = "----PersonalGallery" + System.currentTimeMillis();
        String lineEnd = "\r\n";

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30 * 60 * 1000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(64 * 1024);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
            // chat_id
            out.write(("--" + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"chat_id\"" + lineEnd + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.write((chatId + lineEnd).getBytes(StandardCharsets.UTF_8));

            String fileName = displayName == null ? "media.bin" : displayName.replace("\"", "_").replace("\r", "_").replace("\n", "_");
            String fileHeader = "--" + boundary + lineEnd +
                    "Content-Disposition: form-data; name=\"" + (isVideo ? "video" : "document") + "\"; filename=\"" + fileName + "\"" + lineEnd +
                    "Content-Type: " + (mime == null ? "application/octet-stream" : mime) + lineEnd + lineEnd;
            out.write(fileHeader.getBytes(StandardCharsets.UTF_8));

            try (InputStream in = new BufferedInputStream(resolver.openInputStream(sourceUri))) {
                if (in == null) throw new Exception("Cannot read selected media");
                byte[] buffer = new byte[64 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }

            out.write((lineEnd + "--" + boundary + "--" + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int code = conn.getResponseCode();
        InputStream response = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (response != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();

        String responseText = sb.toString();
        result.rawJson = responseText;

        if (code >= 200 && code < 300) {
            JSONObject json = new JSONObject(responseText);
            boolean ok = json.optBoolean("ok", false);
            result.ok = ok;
            if (ok) {
                JSONObject message = json.optJSONObject("result");
                if (message != null) {
                    JSONObject chat = message.optJSONObject("chat");
                    if (chat != null) result.telegramChatId = String.valueOf(chat.opt("id"));
                    result.telegramMessageId = message.optLong("message_id", 0);
                    JSONObject doc = message.optJSONObject("document");
                    if (doc == null) doc = message.optJSONObject("video");
                    if (doc != null) {
                        result.telegramFileId = doc.optString("file_id", null);
                        result.telegramFileUniqueId = doc.optString("file_unique_id", null);
                    }
                }
            } else {
                result.error = json.optString("description", "Telegram API error");
            }
        } else {
            result.ok = false;
            result.error = "HTTP " + code + ": " + responseText;
        }

        return result;
    }
}
