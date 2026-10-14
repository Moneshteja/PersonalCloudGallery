package com.personal.cloudgallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQ = 51;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<MediaItem> phoneItems = new ArrayList<>();
    private final ArrayList<MediaItem> backedItems = new ArrayList<>();
    private SharedPreferences prefs;
    private LinearLayout root, toolbar, tabs, content, footer;
    private TextView title, status;
    private String activeTab = "Phone";
    private static final String PREFS = "gallery_settings";
    private static final String BACKED_JSON = "backed_media";

    static class MediaItem {
        Uri uri;
        String name, mime, key;
        long size, date;
        boolean video, backed;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        loadBackedItems();
        requestMediaPermission();
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(0xFF202124); return t;
    }
    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b;
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F8FA);
        toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(12), dp(12), dp(8));
        title = text("Personal Cloud Gallery", 22); title.setTypeface(null, 1);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("Settings"); settings.setOnClickListener(v -> showSettings());
        toolbar.addView(settings);
        root.addView(toolbar);
        TextView subtitle = text("Photos and videos · Telegram backup", 13);
        subtitle.setPadding(dp(16), 0, dp(16), dp(12)); subtitle.setTextColor(0xFF5F6368);
        root.addView(subtitle);

        tabs = new LinearLayout(this); tabs.setPadding(dp(12), 0, dp(12), dp(8));
        Button phone = button("Phone gallery");
        Button backed = button("Backed up");
        phone.setOnClickListener(v -> { activeTab = "Phone"; renderGrid(); });
        backed.setOnClickListener(v -> { activeTab = "Backed up"; renderGrid(); });
        tabs.addView(phone, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(backed, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(12), dp(8), dp(12), dp(12));
        status = text("Choose a photo or video to back it up.", 12);
        footer.addView(status);
        Button refresh = button("Refresh gallery");
        refresh.setOnClickListener(v -> loadMedia());
        footer.addView(refresh);
        root.addView(footer);
        setContentView(root);
    }

    private void requestMediaPermission() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (permissions.isEmpty()) loadMedia();
        else requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQ);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQ) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) loadMedia();
            else status.setText("Allow photo and video access to show your gallery.");
        }
    }

    private void loadMedia() {
        executor.execute(() -> {
            ArrayList<MediaItem> found = new ArrayList<>();
            queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, found);
            queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, found);
            found.sort((a,b) -> Long.compare(b.date, a.date));
            runOnUiThread(() -> {
                phoneItems.clear(); phoneItems.addAll(found);
                Set<String> saved = backedKeys();
                for (MediaItem item : phoneItems) item.backed = saved.contains(item.key);
                loadBackedItems();
                renderGrid();
                status.setText(phoneItems.size() + " items on this phone · " + backedItems.size() + " backed-up records");
            });
        });
    }

    private void queryMedia(Uri collection, boolean isVideo, ArrayList<MediaItem> out) {
        String[] projection = new String[] {
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        };
        try (Cursor c = getContentResolver().query(collection, projection, null, null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                MediaItem item = new MediaItem();
                long id = c.getLong(0);
                item.uri = ContentUris.withAppendedId(collection, id);
                item.name = c.getString(1) == null ? "media" : c.getString(1);
                item.mime = c.getString(2) == null ? (isVideo ? "video/mp4" : "image/jpeg") : c.getString(2);
                item.size = c.getLong(3);
                item.date = c.getLong(4);
                item.video = isVideo;
                item.key = item.uri.toString();
                out.add(item);
            }
        } catch (Exception ignored) {}
    }

    private Set<String> backedKeys() {
        Set<String> set = new HashSet<>();
        JSONArray arr;
        try { arr = new JSONArray(prefs.getString(BACKED_JSON, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) set.add(o.optString("key"));
        }
        return set;
    }

    private void loadBackedItems() {
        backedItems.clear();
        JSONArray arr;
        try { arr = new JSONArray(prefs.getString(BACKED_JSON, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            MediaItem item = new MediaItem();
            item.key = o.optString("key");
            item.name = o.optString("name", "Backed-up media");
            item.mime = o.optString("mime", "application/octet-stream");
            item.video = item.mime.startsWith("video/");
            item.size = o.optLong("size", 0);
            item.backed = true;
            // A backed-up record is not necessarily still present locally.
            item.uri = item.key.startsWith("content://") ? Uri.parse(item.key) : null;
            backedItems.add(item);
        }
    }

    private void renderGrid() {
        if (content == null) return;
        content.removeAllViews();
        ArrayList<MediaItem> list = activeTab.equals("Phone") ? phoneItems : backedItems;
        if (list.isEmpty()) {
            TextView empty = text(activeTab.equals("Phone") ?
                    "No media found. Allow access, then refresh." :
                    "Nothing backed up from this app yet. Back up an item from Phone gallery first.", 15);
            empty.setPadding(dp(20), dp(32), dp(20), dp(32));
            content.addView(empty); return;
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        int cell = (width - dp(40)) / 3;
        GridLayout grid = new GridLayout(this); grid.setColumnCount(3);
        for (MediaItem item : list) {
            FrameLayout tile = new FrameLayout(this);
            tile.setBackgroundColor(0xFFE5E7EB);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell; lp.height = cell; lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
            if (item.uri != null) {
                try {
                    Bitmap bmp;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), item.uri);
                        bmp = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                            decoder.setTargetSize(dp(180), dp(180));
                        });
                    } else {
                        bmp = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(),
                                ContentUris.parseId(item.uri), MediaStore.Images.Thumbnails.MINI_KIND, null);
                    }
                    image.setImageBitmap(bmp);
                } catch (Exception ignored) {}
            } else {
                image.setImageResource(android.R.drawable.ic_menu_gallery);
                image.setScaleType(ImageView.ScaleType.CENTER);
            }
            if (item.video) {
                TextView play = text("▶", 22); play.setTextColor(0xFFFFFFFF);
                play.setShadowLayer(4, 1, 1, 0xFF000000);
                FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
                tile.addView(play, pp);
            }
            TextView badge = text(item.backed ? "✓" : (item.video ? "▶" : ""), 14);
            badge.setTextColor(0xFFFFFFFF); badge.setBackgroundColor(item.backed ? 0xFF16834A : 0x99000000);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP);
            tile.addView(badge, bp);
            tile.setOnClickListener(v -> {
                if (activeTab.equals("Phone")) showItemActions(item);
                else showBackedItem(item);
            });
            grid.addView(tile, lp);
        }
        content.addView(grid);
    }

    private void showItemActions(MediaItem item) {
        String size = item.size > 0 ? String.format(Locale.US, "%.1f MB", item.size / 1048576.0) : "Size unknown";
        String[] actions = item.backed ? new String[]{"Open", "Back up again"} : new String[]{"Open", "Back up to Telegram"};
        new AlertDialog.Builder(this).setTitle(item.name).setMessage(size + (item.backed ? "\n✓ Backed up by this app" : "\nNot backed up yet"))
            .setItems(actions, (d, which) -> {
                if (which == 0) openMedia(item);
                else backup(item);
            }).show();
    }

    private void showBackedItem(MediaItem item) {
        if (item.uri != null) {
            new AlertDialog.Builder(this).setTitle(item.name)
                .setMessage("This item was backed up by this app. Telegram Bot API does not let a bot browse its old chat history, so this tab uses the app's local backup record.")
                .setPositiveButton("Open local item", (d,w) -> openMedia(item))
                .setNegativeButton("Close", null).show();
        } else {
            new AlertDialog.Builder(this).setTitle(item.name)
                .setMessage("Backup record saved on this phone. The original local file is no longer available. This starter version does not download media back from Telegram.")
                .setPositiveButton("OK", null).show();
        }
    }

    private void openMedia(MediaItem item) {
        if (item.uri == null) { toast("Original local file is not available."); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(item.uri, item.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) { toast("No compatible viewer found."); }
    }

    private void backup(MediaItem item) {
        String token = prefs.getString("bot_token", "").trim();
        String chat = prefs.getString("chat_id", "").trim();
        String localBase = prefs.getString("local_base", "http://127.0.0.1:8081").trim();
        if (token.isEmpty() || chat.isEmpty()) { showSettings(); toast("Enter your bot token and chat ID in Settings first."); return; }
        if (item.uri == null) { toast("Local file is unavailable."); return; }
        status.setText("Backing up " + item.name + "… keep the local Telegram API server running for files over 50 MB.");
        executor.execute(() -> {
            String result;
            try {
                long actualSize = item.size;
                String method = item.video ? "sendVideo" : "sendDocument";
                String field = item.video ? "video" : "document";
                String base = actualSize > 50_000_000L ? localBase : "https://api.telegram.org";
                String url = base + "/bot" + token + "/" + method;
                result = multipartUpload(url, field, chat, item);
                JSONObject json = new JSONObject(result);
                if (!json.optBoolean("ok", false)) throw new Exception(json.optString("description", result));
                saveBacked(item);
                runOnUiThread(() -> {
                    status.setText("Backup successful: " + item.name);
                    toast("Backed up successfully");
                    loadMedia();
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Upload failed" : e.getMessage();
                runOnUiThread(() -> { status.setText("Backup failed: " + msg); toast("Backup failed: " + msg); });
            }
        });
    }

    private String multipartUpload(String endpoint, String mediaField, String chatId, MediaItem item) throws Exception {
        String boundary = "----PersonalGallery" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30 * 60 * 1000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(64 * 1024);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + chatId + "\r\n");
            String mime = item.mime == null ? "application/octet-stream" : item.mime;
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + mediaField +
                    "\"; filename=\"" + safeFilename(item.name) + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
            try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(item.uri))) {
                if (in == null) throw new Exception("Cannot read selected media");
                byte[] buffer = new byte[64 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            write(out, "\r\n--" + boundary + "--\r\n");
        }
        int code = conn.getResponseCode();
        InputStream response = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (response != null) {
            byte[] b = new byte[4096]; int n;
            while ((n = response.read(b)) != -1) sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
            response.close();
        }
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + sb);
        return sb.toString();
    }

    private void write(OutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
    private String safeFilename(String name) {
        return name == null ? "media.bin" : name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private void saveBacked(MediaItem item) throws Exception {
        JSONArray arr;
        try { arr = new JSONArray(prefs.getString(BACKED_JSON, "[]")); }
        catch (Exception e) { arr = new JSONArray(); }
        JSONObject record = new JSONObject();
        record.put("key", item.key); record.put("name", item.name);
        record.put("mime", item.mime); record.put("size", item.size);
        record.put("backedAt", System.currentTimeMillis());
        JSONArray updated = new JSONArray();
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optString("key").equals(item.key)) updated.put(o);
        }
        updated.put(record);
        prefs.edit().putString(BACKED_JSON, updated.toString()).apply();
    }

    private void showSettings() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(4));
        EditText token = new EditText(this); token.setHint("Telegram bot token"); token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString("bot_token", ""));
        EditText chat = new EditText(this); chat.setHint("Telegram chat ID"); chat.setSingleLine(true);
        chat.setText(prefs.getString("chat_id", "-1001923669533"));
        EditText local = new EditText(this); local.setHint("Local API base URL"); local.setSingleLine(true);
        local.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        local.setText(prefs.getString("local_base", "http://127.0.0.1:8081"));
        TextView note = text("Files ≤ 50 MB use Telegram's hosted Bot API. Larger files use the local Bot API server at the URL above. Keep that server running in Termux. Do not share your bot token.", 12);
        note.setPadding(0, dp(8), 0, dp(8));
        form.addView(token); form.addView(chat); form.addView(local); form.addView(note);
        new AlertDialog.Builder(this).setTitle("Telegram settings").setView(form)
            .setPositiveButton("Save", (d,w) -> {
                String t = token.getText().toString().trim();
                String c = chat.getText().toString().trim();
                String l = local.getText().toString().trim();
                if (l.endsWith("/")) l = l.substring(0, l.length()-1);
                prefs.edit().putString("bot_token", t).putString("chat_id", c).putString("local_base", l).apply();
                toast("Settings saved on this device.");
            }).setNegativeButton("Cancel", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { super.onDestroy(); executor.shutdown(); }
}
