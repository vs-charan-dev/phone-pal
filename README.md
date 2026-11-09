# PhonePal (Phase 1: idea library)

Share a YouTube video, X post, web page or plain text to **Save to PhonePal**. The phone then:

1. fetches the content **on the device**: YouTube captions (a Kotlin port of the `youtube-transcript-api` approach), X post text (oEmbed) or page text
2. sends it to Gemini (your Google AI Studio key), which writes a summary, key points, **content ideas**, hooks, tags and a category
3. stores it in a local, searchable library (Room). You can export one item or everything as Markdown (works with Obsidian/Notion).

Your laptop isn't needed. Work runs in the background through WorkManager and retries when you're offline.

## Build & install
```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
On first launch, open **Settings** and paste your Gemini API key from Google AI Studio (stored only on the phone). You can also tap "Load my models" to pick a model and add a short "about me as a creator" note.

## Code map
- `ui/ShareActivity.kt`: the share-sheet target. It saves the link, queues the work and closes immediately.
- `fetch/YouTubeTranscript.kt`: watch page → InnerTube `player` (ANDROID client) → caption XML.
- `fetch/Fetchers.kt`: fetchers for X (oEmbed) and generic web pages.
- `ai/Summarizer.kt`: the Gemini `generateContent` call over REST.
- `work/ProcessWorker.kt`: the fetch → summarize → store pipeline.
- `data/`: the Room entity, DAO, link parser and settings.

## Known limits
- YouTube changes its internals from time to time. When transcripts stop working, the item is still summarized from the title and description, and the detail screen shows why.
- For X, only the post text is captured; images, videos and full threads are not.

## Payment and banking apps

Many payment/banking apps (PhonePe, Paytm, bank apps, …) refuse to work while a screen-reading
accessibility service is on. PhonePal never reads them, and when one opens it switches its accessibility
service off. To let it switch back on automatically when you leave the payment app, grant this once:

```bash
adb shell pm grant com.phonepal android.permission.WRITE_SECURE_SETTINGS
```

Without that, use the **PhonePal tracking** quick-settings tile to turn it back on. Settings → Payment apps
shows which installed apps are protected and lets you add more package names.
