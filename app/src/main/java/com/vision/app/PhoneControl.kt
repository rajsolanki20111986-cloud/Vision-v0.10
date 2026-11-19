package com.vision.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Direct phone actions that need no screen reading: seek in a song, play a song from Spotify search,
 * favourites, lock screen, answer or end a call, save a contact, timer, repeating alarm, exact volume, time.
 * FastPath calls these straight from the voice; Gemini can call the same ones as tools.
 */
object PhoneControl {
    const val FAV_PATH = "facts/favorite_songs.md"
    private const val SPOTIFY = "com.spotify.music"
    private const val NEED_LISTENER =
        "Needs Notification access: Settings > Notification access > Vision Notifications > on."

    private fun d(name: String, desc: String, vararg p: Pair<String, String>): JSONObject {
        val props = JSONObject()
        p.forEach { props.put(it.first, JSONObject().put("type", "STRING").put("description", it.second)) }
        val o = JSONObject().put("name", name).put("description", desc)
        if (p.isNotEmpty()) o.put("parameters", JSONObject()
            .put("type", "OBJECT").put("properties", props).put("required", JSONArray(p.map { it.first })))
        return o
    }

    fun declarations(): JSONArray = JSONArray().apply {
        put(d("seek_media", "Jump the playing song or video forward or back by an exact number of seconds (Spotify, YouTube, any player). Works with the screen off.",
            "seconds" to "positive number of seconds, e.g. 10 or 90", "direction" to "forward or back"))
        put(d("replay_media", "Restart the current song from the beginning."))
        put(d("play_song", "Search a song, artist or playlist in Spotify and play it right away. Use for every 'play <song>' request.",
            "query" to "song and artist, e.g. kesariya arijit singh"))
        put(d("play_favorite", "Play one of his saved favourite songs. Use when he asks to play a song without naming one."))
        put(d("favorite_add", "Save a song in his favourites list. Use 'current' to save the song that is playing now.",
            "song" to "song name and artist, or the word current"))
        put(d("lock_screen", "Lock the phone screen now."))
        put(d("answer_call", "Answer the incoming phone call."))
        put(d("end_call", "End the current call, or reject the incoming one."))
        put(d("save_contact", "Save a new contact in the phone.", "name" to "contact name", "number" to "phone number, digits and optional +"))
        put(d("set_timer", "Start a countdown timer.", "seconds" to "length in seconds", "label" to "timer label"))
        put(d("set_repeating_alarm", "Set an alarm that repeats on days.",
            "hour" to "hour in 24-hour format, 0 to 23", "minute" to "minute, 0 to 59",
            "days" to "daily, weekdays, weekends, or day names separated by commas, e.g. monday,friday", "label" to "alarm label"))
        put(d("set_volume", "Set the media volume to an exact percentage.", "percent" to "0 to 100"))
        put(d("current_time", "Get the current time and date."))
    }

    fun run(ctx: Context, name: String, a: JSONObject): String = when (name) {
        "seek_media" -> {
            val secs = (a.optString("seconds").toDoubleOrNull() ?: 10.0).coerceIn(0.0, 36000.0)
            val back = a.optString("direction").lowercase().let { it.startsWith("b") || it.startsWith("r") || it.contains("peeche") }
            seek(ctx, ((if (back) -secs else secs) * 1000).toLong())
        }
        "replay_media" -> replay(ctx)
        "play_song" -> playSong(ctx, a.optString("query"))
        "play_favorite" -> playFavorite(ctx)
        "favorite_add" -> {
            val s = a.optString("song").trim()
            if (s.isEmpty() || s.equals("current", true)) addCurrentFavorite(ctx) else addFavorite(s)
        }
        "lock_screen" -> lockScreen()
        "answer_call" -> answerCall(ctx)
        "end_call" -> endCall(ctx)
        "save_contact" -> saveContact(ctx, a.optString("name"), a.optString("number"))
        "set_timer" -> setTimer(ctx, a.optString("seconds").toDoubleOrNull()?.toInt() ?: 0, a.optString("label"))
        "set_repeating_alarm" -> setRepeatingAlarm(
            ctx, a.optString("hour").toIntOrNull() ?: -1, a.optString("minute").toIntOrNull() ?: -1,
            parseDays(a.optString("days")), a.optString("label"))
        "set_volume" -> setVolume(ctx, a.optString("percent").toDoubleOrNull()?.toInt() ?: -1)
        "current_time" -> currentTime()
        else -> "Unknown tool: $name"
    }

    private fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ media sessions

    /** Active players. Needs Notification access; null means it is not granted. */
    private fun controllers(ctx: Context): List<MediaController>? = try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(ctx, VisionNotificationListener::class.java))
    } catch (e: SecurityException) {
        null
    }

    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()

    /** True when some player has a session. If unknown (no Notification access) it says true, so a plain key press is sent. */
    fun hasSession(ctx: Context): Boolean = controllers(ctx)?.isNotEmpty() ?: true

    fun isPlaying(ctx: Context): Boolean =
        controllers(ctx)?.any { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: false

    fun seek(ctx: Context, deltaMs: Long): String {
        val list = controllers(ctx) ?: return NEED_LISTENER
        val c = pick(list) ?: return "Nothing is playing."
        val st = c.playbackState ?: return "Nothing is playing."
        val now = if (st.state == PlaybackState.STATE_PLAYING)
            st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        else st.position
        var target = (now + deltaMs).coerceAtLeast(0L)
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur > 1000L) target = target.coerceAtMost(dur - 500L).coerceAtLeast(0L)
        c.transportControls.seekTo(target)
        return "Done"
    }

    fun replay(ctx: Context): String {
        val list = controllers(ctx) ?: return NEED_LISTENER
        val c = pick(list) ?: return "Nothing is playing."
        c.transportControls.seekTo(0L)
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) c.transportControls.play()
        return "Done"
    }

    // ------------------------------------------------------------------ songs

    fun playSong(ctx: Context, query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "Tell me which song."
        // Spotify is already running: ask its own session, no screen needed.
        controllers(ctx)?.firstOrNull { it.packageName == SPOTIFY }?.let {
            it.transportControls.playFromSearch(q, Bundle())
            return "Playing $q."
        }
        val installed = runCatching { ctx.packageManager.getPackageInfo(SPOTIFY, 0); true }.getOrDefault(false)
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (installed) i.setPackage(SPOTIFY)
        return try {
            ctx.startActivity(i)
            "Playing $q."
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Spotify search for $q, tap the song to play."
            } catch (e2: Exception) {
                "Could not start music: ${e2.message}. Allow 'Display over other apps' for Vision."
            }
        }
    }

    fun favorites(): List<String> = MemoryStore.read(FAV_PATH).orEmpty().lines()
        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }

    fun playFavorite(ctx: Context): String {
        val f = favorites()
        if (f.isEmpty()) return "No favourite songs saved yet. Say the names and I will save them."
        return playSong(ctx, f.random())
    }

    fun addFavorite(song: String): String = MemoryStore.write(FAV_PATH, "- " + song.trim(), append = true)

    fun addCurrentFavorite(ctx: Context): String {
        val list = controllers(ctx) ?: return NEED_LISTENER
        val md = pick(list)?.metadata ?: return "Nothing is playing."
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (title.isBlank()) return "I cannot see the song name."
        return addFavorite(if (artist.isBlank()) title else "$title $artist")
    }

    // ------------------------------------------------------------------ phone

    fun lockScreen(): String {
        val s = VisionAccessibilityService.inst ?: return "Accessibility service is not running."
        if (Build.VERSION.SDK_INT < 28) return "Lock needs Android 9 or newer."
        return if (s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) "Locked" else "Lock failed"
    }

    @Suppress("MissingPermission")
    fun answerCall(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "Allow the Phone calls permission for Vision in Android settings."
        return try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            "Answered"
        } catch (e: Exception) {
            "Could not answer: ${e.message}"
        }
    }

    @Suppress("MissingPermission")
    fun endCall(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "Ending calls needs Android 9 or newer."
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "Allow the Phone calls permission for Vision in Android settings."
        return try {
            if ((ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()) "Call ended" else "No call to end."
        } catch (e: Exception) {
            "Could not end the call: ${e.message}"
        }
    }

    fun saveContact(ctx: Context, name: String, number: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        val nm = name.trim()
        if (nm.isEmpty() || !Regex("""\+?[0-9]{3,15}""").matches(n)) return "I need a name and a valid phone number."
        if (!has(ctx, Manifest.permission.WRITE_CONTACTS)) {
            return try {
                ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, nm)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, n)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "The contacts permission is not granted, so the new contact screen is open. Tap Save."
            } catch (e: Exception) { "Could not save the contact: ${e.message}" }
        }
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, nm).build())
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, n)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())
        return try {
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            "Saved $nm $n"
        } catch (e: Exception) {
            "Could not save the contact: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ clock and sound

    fun setTimer(ctx: Context, seconds: Int, label: String): String {
        if (seconds !in 1..86400) return "Bad timer length."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Vision" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); "Timer set for $seconds seconds" } catch (e: Exception) { "No clock app handled the timer: ${e.message}" }
    }

    /** days: Calendar.SUNDAY..SATURDAY (1..7). */
    fun setRepeatingAlarm(ctx: Context, h: Int, m: Int, days: ArrayList<Int>, label: String): String {
        if (h !in 0..23 || m !in 0..59) return "Bad time. Use hour 0 to 23 and minute 0 to 59."
        if (days.isEmpty()) return "Tell me which days."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_DAYS, days)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Vision" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); "Repeating alarm set for %02d:%02d".format(h, m) } catch (e: Exception) { "No clock app handled the alarm: ${e.message}" }
    }

    fun parseDays(s: String): ArrayList<Int> {
        val out = ArrayList<Int>()
        val t = s.lowercase()
        fun all(vararg d: Int) = d.forEach { if (it !in out) out.add(it) }
        if (t.contains("daily") || t.contains("every")) all(1, 2, 3, 4, 5, 6, 7)
        if (t.contains("weekday")) all(2, 3, 4, 5, 6)
        if (t.contains("weekend")) all(1, 7)
        if (t.contains("mon")) all(Calendar.MONDAY)
        if (t.contains("tue")) all(Calendar.TUESDAY)
        if (t.contains("wed")) all(Calendar.WEDNESDAY)
        if (t.contains("thu")) all(Calendar.THURSDAY)
        if (t.contains("fri")) all(Calendar.FRIDAY)
        if (t.contains("sat")) all(Calendar.SATURDAY)
        if (t.contains("sun")) all(Calendar.SUNDAY)
        return out
    }

    fun setVolume(ctx: Context, percent: Int): String {
        if (percent !in 0..100) return "Volume must be 0 to 100."
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
        return "Volume $percent%"
    }

    fun currentTime(): String = SimpleDateFormat("h:mm a, EEEE d MMMM yyyy", Locale.getDefault()).format(Date())
}
