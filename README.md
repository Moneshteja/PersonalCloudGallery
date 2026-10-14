# Personal Cloud Gallery (Android starter project)

A personal Android gallery app inspired by the general idea of Chitralaya CloudGallery. This is an independently written starter implementation, not a copy of that project's source.

## Current features
- Separate **Phone gallery** and **Backed up** tabs.
- Reads local photos and videos through Android MediaStore.
- Tap a tile to open the item or upload it to your Telegram bot.
- Files up to 50,000,000 bytes use Telegram's hosted Bot API.
- Larger files use your local Telegram Bot API server (default `http://127.0.0.1:8081`).
- Uploads stream in chunks rather than loading the whole media file into RAM.
- The Backed up tab records successful uploads made by this app.

## Important limitations in this starter
- Telegram Bot API does not offer bots a general way to browse the old message history of a chat. Therefore, the Backed up tab is based on this app's local backup records; it is not a full Telegram cloud browser.
- If you delete the app's data or uninstall the app, local backup records are lost. Backed-up media remains in Telegram.
- This version does not yet have automatic background backup, multi-select upload, retry queue, thumbnail caching, encryption-backed secret storage, or download/restore from Telegram.
- The token is hidden in the Settings input but is currently stored in app-private SharedPreferences, not Android Keystore encryption. Use only for personal testing and do not distribute the app with your token saved.
- For large files, the local Bot API server must be running on the same phone. `127.0.0.1` is correct when the server is in Termux on the same device.
- The local server needs its own API ID/hash configured in Termux. Never put those credentials in the app.

## Build
1. Install Android Studio on a computer and open this project folder.
2. Let Gradle sync. Use JDK 17 and an Android SDK platform compatible with compile SDK 35.
3. Build > Build APK(s), then install the generated debug APK on your phone.
4. Open Settings in the app and enter your bot token and chat ID. The chat ID defaults to the one you supplied in the project conversation; confirm it before uploading.
5. Keep your local Telegram Bot API server running in Termux before uploading files larger than 50 MB.

## Network note
The app enables cleartext HTTP because the local Telegram Bot API server is on localhost. Do not expose that local server to untrusted networks.
