package com.vision.app

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar

/**
 * Plain commands that need no thinking. They run straight on the phone without asking the model,
 * so they answer almost at once. Used by text chat and by the voice (after on-phone speech recognition).
 *
 * v0.10: also song search, seek by seconds, lock screen, call answer/end/place, alarms, timers,
 * exact volume, save contact, time, favourites, and several commands in one sentence ("gaana bajao aur torch on").
 *
 * It is deliberately strict: a command is matched only when EVERY word of it belongs to that command's own
 * vocabulary (or is a song or contact name in the places where one is expected). Questions, "don't ...",
 * and long sentences are not matched and go to the model as before.
 */
object FastPath {
    private fun n(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()
    private fun words(vararg w: String): Set<String> = w.map { n(it) }.toSet()
    private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** One matched command. exec() returns the reply, or null when it could not be done (then the model may take over). */
    private class Plan(val exec: suspend () -> String?)

    // ------------------------------------------------------------------ vocabulary (original)

    private val FILL = words(
        "please", "plz", "pls", "the", "a", "my", "karo", "kar", "do", "kardo", "dena", "de", "zara", "jara",
        "phone", "vision", "hey", "hi", "ok", "okay", "ab", "jaldi", "turn", "switch", "set", "put", "make", "it",
        "ko", "ka", "ki", "करो", "कर", "दो", "दे", "देना", "कृपया", "प्लीज", "फोन", "फ़ोन", "विजन", "अब", "जल्दी", "ज़रा", "जरा"
    )
    private val FILL2 = FILL + words(
        "mujhe", "mere", "meri", "mera", "ek", "koi", "kuch", "kuchh", "ye", "yeh", "wo", "woh",
        "मुझे", "मेरे", "मेरी", "मेरा", "एक", "कोई", "कुछ", "ये", "यह", "वो"
    )
    private val NEG = words("nahi", "nahin", "mat", "not", "dont", "don", "नहीं", "मत")

    private val TORCH = words("torch", "flashlight", "flash", "tourch", "टॉर्च", "टार्च", "फ्लैशलाइट", "फ्लैश")
    private val ON = words("on", "chalu", "chalao", "jalao", "jala", "ऑन", "चालू", "चलाओ", "जलाओ", "जला")
    private val OFF = words("off", "band", "bujhao", "bujha", "ऑफ", "बंद", "बुझाओ", "बुझा")

    private val VOL = words("volume", "वॉल्यूम", "awaz", "avaaz", "आवाज़", "आवाज")
    private val UP = words("up", "increase", "raise", "badhao", "badha", "zyada", "jyada", "louder", "बढ़ाओ", "बढ़ा", "ज़्यादा", "ज्यादा", "तेज़", "तेज")
    private val DOWN = words("down", "decrease", "lower", "kam", "ghatao", "ghata", "quieter", "कम", "घटाओ", "घटा", "धीमा")
    private val VOL_FILL = words("thoda", "थोड़ा", "aur", "और", "more", "little", "bit", "bahut", "बहुत")

    private val NEXT = words("next", "skip", "agla", "agle", "अगला", "अगले", "अगली")
    private val PREV = words("previous", "prev", "pichla", "pichle", "पिछला", "पिछले")
    private val PAUSE = words("pause", "roko", "ruko", "रोको", "रुको", "पॉज़", "पॉज")
    private val PLAY = words("play", "resume", "chalao", "chala", "चलाओ", "चला")
    private val MEDIA_NOUN = words("song", "songs", "music", "gana", "gaana", "gane", "track", "गाना", "गाने", "संगीत", "म्यूजिक", "वीडियो", "video")

    private val BATTERY = words("battery", "बैटरी")
    private val BATTERY_FILL = words(
        "kitni", "kitna", "hai", "level", "percent", "status", "check", "how", "much", "what", "is", "whats", "s",
        "tell", "me", "batao", "bata", "कितनी", "कितना", "है", "लेवल", "प्रतिशत", "बताओ", "बता"
    )

    private val OPEN_W = words("open", "launch", "start", "kholo", "khol", "खोलो", "खोल")
    private val APP_W = words("app", "application", "ऐप", "एप", "ऐप्लिकेशन")

    // ------------------------------------------------------------------ vocabulary (new)

    private val CONN = words("aur", "और", "and", "then", "tatha")
    private val PHIR = words("phir", "fir", "फिर")
    private val SE = words("se", "से")

    private val PLAYV = words(
        "play", "bajao", "baja", "bajana", "lagao", "laga", "sunao", "suna", "chalao", "chala",
        "बजाओ", "बजा", "बजाना", "लगाओ", "लगा", "सुनाओ", "सुना", "चलाओ", "चला"
    )
    private val SRC = words("spotify", "स्पॉटिफाई", "स्पोटिफाई", "स्पॉटीफाई", "spotifai", "app", "ऐप", "एप")
    private val PREP = words("on", "par", "pe", "me", "mein", "pr", "पर", "पे", "में", "मे")
    private val FAVW = words("favourite", "favorite", "fav", "favs", "pasandida", "पसंदीदा", "फेवरेट", "फेवरिट", "फ़ेवरेट", "pasand", "पसंद")
    private val ADDW = words("add", "jodo", "jod", "save", "सेव", "जोड़ो", "जोड़", "जोड़ना", "store", "स्टोर")
    private val THIS = words("ye", "yeh", "is", "iss", "isko", "ise", "ये", "यह", "इस", "इसको", "इसे", "this", "current")

    private val FWD = words("aage", "aagey", "agey", "forward", "ahead", "skip", "आगे", "फॉरवर्ड", "फारवर्ड")
    private val BACK = words("peeche", "pichhe", "piche", "pichhey", "back", "rewind", "backward", "पीछे", "रिवाइंड", "बैक")
    private val UNIT_S = words("second", "seconds", "sec", "secs", "sekand", "sekend", "सेकंड", "सेकेंड", "सेकण्ड", "सेकंड्स", "सेकेण्ड", "सेकन्ड", "सेक")
    private val UNIT_M = words("minute", "minutes", "min", "mins", "minat", "मिनट", "मिनिट", "मिनट्स")
    private val UNIT_H = words("hour", "hours", "ghanta", "ghante", "घंटा", "घंटे", "घण्टे", "घण्टा")
    private val SEEK_FILL = words("thoda", "jao", "jaao", "chalo", "le", "lo", "ja", "जाओ", "जा", "ले", "लो", "चलो", "थोड़ा", "kar", "karo")

    private val REPEATW = words(
        "again", "repeat", "replay", "dobara", "dobaara", "dubara", "phirse", "firse", "restart", "phir", "fir", "se",
        "फिरसे", "दोबारा", "दुबारा", "रिपीट", "रीप्ले", "रिप्ले", "फिर", "से", "wapas", "वापस"
    )

    private val LOCKW = words("lock", "लॉक", "लाक")
    private val SCREENW = words("screen", "स्क्रीन", "mobile", "मोबाइल", "phone", "फोन", "फ़ोन")

    private val CALL_STRICT = words("call", "कॉल")
    private val CALLW = words("call", "कॉल", "phone", "फोन", "फ़ोन")
    private val ANS_STRONG = words(
        "uthao", "utha", "uthalo", "uthale", "उठाओ", "उठा", "उठालो", "उठाले", "answer", "receive", "pick", "pickup", "accept", "रिसीव", "रिसिव"
    )
    private val END_STRONG = words(
        "kaato", "kato", "kaat", "kat", "काटो", "काट", "कटो", "cut", "end", "reject", "disconnect", "decline", "hangup", "hang",
        "rakh", "rakho", "रख", "रखो"
    )
    private val END_WEAK = words("band", "बंद", "cancel")
    private val CALLV = words(
        "karo", "kar", "lagao", "laga", "milao", "mila", "kardo", "dena", "de", "do", "dial", "karna", "lagana",
        "करो", "कर", "लगाओ", "लगा", "मिलाओ", "मिला", "दो", "दे", "करना", "लगाना", "ko", "को", "se", "से"
    )

    private val TIMEW = words(
        "time", "samay", "waqt", "vakt", "वक्त", "वक़्त", "समय", "date", "tarikh", "tareekh", "तारीख", "तारीख़", "din", "day"
    )
    private val TIME_Q = words(
        "kya", "kitna", "kitne", "batao", "bata", "what", "tell", "whats", "s", "is", "hai", "hua", "ho", "raha", "rha", "abhi", "now",
        "current", "aaj", "today", "ka", "ki", "me", "baje", "बजे", "क्या", "कितना", "कितने", "बताओ", "बता", "है", "हुआ", "हो", "रहा", "अभी", "आज"
    )

    private val ALARMW = words("alarm", "अलार्म", "अलारम")
    private val TIMERW = words("timer", "टाइमर", "टाईमर")
    private val BAJE = words("baje", "bje", "bajke", "बजे", "बज", "am", "pm", "एएम", "पीएम")
    private val AFTER = words("baad", "mein", "me", "में", "बाद", "after", "in")
    private val AMW = words("am", "subah", "सुबह", "morning", "savere", "सवेरे", "सबेरे", "एएम")
    private val PM_SHAM = words("pm", "shaam", "sham", "शाम", "evening", "पीएम")
    private val PM_DOP = words("dopahar", "दोपहर", "afternoon", "dophar", "noon")
    private val NIGHT = words("raat", "रात", "night", "raatri")
    private val SAADE = words("saade", "sade", "साढ़े", "साढे", "साड़े")
    private val SAVA = words("sava", "सवा")
    private val PAUNE = words("paune", "पौने")
    private val ROZ = words("roz", "daily", "everyday", "rojana", "रोज", "रोज़", "रोजाना")
    private val WEEKDAYS = words("weekdays", "weekday")
    private val WEEKENDS = words("weekend", "weekends")
    private val DAYNAMES: Map<Int, Set<String>> = mapOf(
        Calendar.MONDAY to words("monday", "somvar", "somwar", "सोमवार"),
        Calendar.TUESDAY to words("tuesday", "mangalvar", "mangalwar", "मंगलवार"),
        Calendar.WEDNESDAY to words("wednesday", "budhvar", "budhwar", "बुधवार"),
        Calendar.THURSDAY to words("thursday", "guruvar", "veervar", "brihaspativar", "गुरुवार", "बृहस्पतिवार", "वीरवार"),
        Calendar.FRIDAY to words("friday", "shukravar", "shukrawar", "शुक्रवार"),
        Calendar.SATURDAY to words("saturday", "shanivar", "shaniwar", "शनिवार"),
        Calendar.SUNDAY to words("sunday", "ravivar", "itwar", "itvar", "रविवार", "इतवार")
    )

    private val FULLV = words("full", "max", "maximum", "फुल", "मैक्स", "फूल", "poori", "puri", "पूरी", "पूरा")
    private val MUTEV = words("mute", "म्यूट", "silent", "chup", "चुप", "khamosh", "खामोश", "ख़ामोश", "zero", "जीरो")
    private val PERCENTW = words("percent", "percentage", "parcent", "प्रतिशत", "पर्सेंट", "set", "at", "to", "tak", "तक")

    private val SAVEW = words("save", "सेव", "add", "जोड़ो", "जोड़", "जोड़ना", "store", "स्टोर")
    private val NAME_JUNK = words(
        "number", "nambar", "no", "नंबर", "नम्बर", "naam", "नाम", "contact", "कॉन्टैक्ट", "कांटैक्ट", "new", "naya", "नया", "save", "सेव", "add", "ka", "ki", "ko", "को", "का", "की", "is", "ye", "yeh"
    )

    private val HNUM: Map<String, Int> = mapOf(
        "ek" to 1, "एक" to 1, "do" to 2, "दो" to 2, "teen" to 3, "तीन" to 3, "char" to 4, "chaar" to 4, "चार" to 4,
        "paanch" to 5, "panch" to 5, "पांच" to 5, "पाँच" to 5, "chhe" to 6, "chhah" to 6, "che" to 6, "छह" to 6, "छः" to 6,
        "saat" to 7, "सात" to 7, "aath" to 8, "आठ" to 8, "nau" to 9, "नौ" to 9, "das" to 10, "दस" to 10,
        "gyarah" to 11, "ग्यारह" to 11, "barah" to 12, "बारह" to 12, "pandrah" to 15, "पंद्रह" to 15,
        "bees" to 20, "बीस" to 20, "pachees" to 25, "पच्चीस" to 25, "tees" to 30, "तीस" to 30,
        "chalis" to 40, "चालीस" to 40, "pachas" to 50, "पचास" to 50, "saath" to 60, "साठ" to 60
    )

    private fun num(t: String): Int? = t.toIntOrNull() ?: HNUM[t]
    private fun isNumTok(t: String): Boolean = num(t) != null

    // ------------------------------------------------------------------ entry point

    /** Returns the reply text when the message was a plain command (or several) and it ran, otherwise null. */
    suspend fun tryHandle(ctx: Context, text: String): String? {
        val toks = n(text).split(SPLIT).filter { it.isNotEmpty() }
        if (toks.isEmpty() || toks.size > 16) return null
        val hi = LanguageDetector.detectLanguage(text) == "hi"

        // Several commands in one sentence: every part must be a known command, otherwise the whole text is tried as one.
        val parts = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for ((i, t) in toks.withIndex()) {
            val isConn = t in CONN || (t in PHIR && toks.getOrNull(i + 1)?.let { it in SE } != true)
            if (isConn) { if (cur.isNotEmpty()) parts.add(cur); cur = ArrayList() } else cur.add(t)
        }
        if (cur.isNotEmpty()) parts.add(cur)
        if (parts.size in 2..4) {
            val plans = parts.map { plan(ctx, it, hi) }
            if (plans.all { it != null }) {
                val out = ArrayList<String>()
                for (p in plans) {
                    out.add(p!!.exec() ?: if (hi) "एक काम नहीं हो पाया।" else "One task failed.")
                }
                return out.joinToString(" ")
            }
        }
        return plan(ctx, toks, hi)?.exec()
    }

    private fun plan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.isEmpty() || toks.any { it in NEG }) return null
        return legacy(ctx, toks, hi)
            ?: alarmPlan(ctx, toks, hi)
            ?: callPlan(ctx, toks, hi)
            ?: lockPlan(toks, hi)
            ?: seekPlan(ctx, toks, hi)
            ?: volumePlan(ctx, toks, hi)
            ?: contactPlan(ctx, toks, hi)
            ?: timePlan(toks)
            ?: songPlan(ctx, toks, hi)
            ?: openAppPlan(ctx, toks, hi)
    }

    // ------------------------------------------------------------------ original commands

    private fun legacy(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.size > 7) return null

        // Torch
        if (toks.any { it in TORCH } && toks.all { it in FILL || it in TORCH || it in ON || it in OFF }) {
            val on = toks.any { it in ON }
            val off = toks.any { it in OFF }
            if (on == off) return null
            return Plan {
                val r = ToolRouter.run(ctx, "torch", JSONObject().put("state", if (on) "on" else "off"))
                if (!r.startsWith("Torch on") && !r.startsWith("Torch off")) r
                else if (hi) (if (on) "टॉर्च चालू कर दी।" else "टॉर्च बंद कर दी।") else (if (on) "Torch on." else "Torch off.")
            }
        }

        // Volume up / down
        if (toks.any { it in VOL } && toks.all { it in FILL || it in VOL || it in UP || it in DOWN || it in VOL_FILL }) {
            val up = toks.any { it in UP }
            val down = toks.any { it in DOWN }
            if (up == down) return null
            return Plan {
                ToolRouter.run(ctx, "volume", JSONObject().put("direction", if (up) "up" else "down"))
                if (hi) (if (up) "आवाज़ बढ़ा दी।" else "आवाज़ कम कर दी।") else (if (up) "Volume up." else "Volume down.")
            }
        }

        // Battery
        if (toks.any { it in BATTERY } && toks.all { it in FILL || it in BATTERY || it in BATTERY_FILL }) {
            return Plan { ToolRouter.run(ctx, "battery", JSONObject()) }
        }

        // Music keys
        if (toks.any { it in NEXT || it in PREV || it in PAUSE || it in PLAY } &&
            toks.all { it in FILL || it in NEXT || it in PREV || it in PAUSE || it in PLAY || it in MEDIA_NOUN }
        ) {
            val next = toks.any { it in NEXT }
            val prev = toks.any { it in PREV }
            val pause = toks.any { it in PAUSE }
            val play = toks.any { it in PLAY }
            val kinds = listOf(next, prev, pause || play).count { it }
            if (kinds != 1) return null
            val action = if (next) "next" else if (prev) "previous" else "play_pause"
            return Plan {
                // "play" with nothing in the player: start one of his favourite songs instead of a dead key press.
                if (action == "play_pause" && play && !pause && !PhoneControl.hasSession(ctx)) {
                    PhoneControl.playFavorite(ctx)
                } else {
                    ToolRouter.run(ctx, "media", JSONObject().put("action", action))
                    when (action) {
                        "next" -> if (hi) "अगला गाना।" else "Next."
                        "previous" -> if (hi) "पिछला गाना।" else "Previous."
                        else -> if (hi) "हो गया।" else "Done."
                    }
                }
            }
        }
        return null
    }

    private fun openAppPlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.size > 7 || toks.none { it in OPEN_W }) return null
        val core = toks.filter { it !in FILL && it !in OPEN_W && it !in APP_W }
        val atEdge = toks.first() in OPEN_W || toks.last() in OPEN_W || toks.last() in FILL
        if (core.isEmpty() || core.size > 3 || !atEdge) return null
        return Plan {
            val r = ToolRouter.run(ctx, "open_app", JSONObject().put("name", core.joinToString(" ")))
            if (r.startsWith("Opened")) {
                val label = r.removePrefix("Opened ").substringBefore(". Call read_screen")
                if (hi) "$label खोल दिया।" else "Opened $label."
            } else if (r.startsWith("Launch blocked")) r else null
        }
    }

    // ------------------------------------------------------------------ alarms and timers

    private fun durationSeconds(toks: List<String>): Int {
        var total = 0
        for (i in 1 until toks.size) {
            val v = num(toks[i - 1]) ?: continue
            total += when (toks[i]) {
                in UNIT_S -> v
                in UNIT_M -> v * 60
                in UNIT_H -> v * 3600
                else -> 0
            }
        }
        return total
    }

    /** Returns hour (0-23) and minute, or null when no clock time is in the words. */
    private fun parseClock(toks: List<String>): Pair<Int, Int>? {
        var h = -1
        var idx = -1
        for (i in toks.indices) {
            val v = toks[i].toIntOrNull() ?: continue
            val nx = toks.getOrNull(i + 1)
            if (v in 0..24 && !(nx != null && (nx in UNIT_S || nx in UNIT_M || nx in UNIT_H))) { h = v; idx = i; break }
        }
        if (h < 0) {
            for (i in 0 until toks.size - 1) {
                val v = HNUM[toks[i]]
                if (v != null && v in 1..12 && toks[i + 1] in BAJE) { h = v; idx = i; break }
            }
        }
        if (h < 0) return null

        var m = 0
        val nx = toks.getOrNull(idx + 1)
        val after = if (nx != null && nx in BAJE) toks.getOrNull(idx + 2) else nx
        val afterV = after?.toIntOrNull()
        if (after != null && afterV != null && afterV in 0..59 &&
            (after.length == 2 || toks.getOrNull(toks.indexOf(after) + 1)?.let { it in UNIT_M } == true)
        ) m = afterV
        val before = toks.getOrNull(idx - 1)
        if (before != null) {
            if (before in SAADE) m = 30
            if (before in SAVA) m = 15
            if (before in PAUNE) { h = if (h == 1) 12 else h - 1; m = 45 }
        }

        val am = toks.any { it in AMW }
        val sham = toks.any { it in PM_SHAM }
        val dop = toks.any { it in PM_DOP }
        val night = toks.any { it in NIGHT }
        if (h in 1..12 || h == 0) {
            when {
                sham -> if (h in 1..11) h += 12
                dop -> if (h in 1..6) h += 12
                night -> if (h in 6..11) h += 12 else if (h == 12) h = 0
                am -> if (h == 12) h = 0
                h in 1..12 -> {
                    // No am/pm said: take whichever of the two readings comes next on the clock.
                    val now = Calendar.getInstance()
                    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    fun delta(h24: Int): Int { val d = ((h24 * 60 + m) - nowMin + 1440) % 1440; return if (d == 0) 1440 else d }
                    val a = h % 12
                    val b = h % 12 + 12
                    h = if (delta(a) <= delta(b)) a else b
                }
            }
        }
        if (h !in 0..23) return null
        return h to m
    }

    private fun alarmDays(toks: List<String>): ArrayList<Int> {
        val out = ArrayList<Int>()
        for ((day, names) in DAYNAMES) if (toks.any { it in names }) out.add(day)
        if (toks.any { it in WEEKDAYS }) for (d in 2..6) if (d !in out) out.add(d)
        if (toks.any { it in WEEKENDS }) for (d in listOf(1, 7)) if (d !in out) out.add(d)
        if (out.isEmpty() && (toks.any { it in ROZ } || ("har" in toks && ("din" in toks || "दिन" in toks)))) out.addAll(1..7)
        return out
    }

    private fun alarmPlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        val isAlarm = toks.any { it in ALARMW }
        val isTimer = toks.any { it in TIMERW }
        if (!isAlarm && !isTimer) return null
        if (toks.size > 14) return null

        val dur = durationSeconds(toks)
        if (dur > 0 && (isTimer || toks.any { it in AFTER })) {
            return Plan {
                val r = ToolRouter.run(ctx, "set_timer", JSONObject().put("seconds", dur.toString()).put("label", "Vision"))
                if (!r.startsWith("Timer set")) r else {
                    val mins = dur / 60
                    val txt = if (dur % 60 == 0 && mins > 0) "$mins ${if (hi) "मिनट" else "minute"}" else "$dur ${if (hi) "सेकंड" else "seconds"}"
                    if (hi) "$txt का टाइमर लगा दिया।" else "Timer set for $txt."
                }
            }
        }
        if (!isAlarm) return null
        val (h, m) = parseClock(toks) ?: return null
        val days = alarmDays(toks)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val label = "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
        return Plan {
            val r = if (days.isEmpty())
                ToolRouter.run(ctx, "set_alarm", JSONObject().put("hour", h.toString()).put("minute", m.toString()).put("label", "Vision"))
            else
                PhoneControl.setRepeatingAlarm(ctx, h, m, days, "Vision")
            if (!r.startsWith("Alarm set") && !r.startsWith("Repeating alarm set")) r
            else if (hi) "$label का अलार्म लगा दिया।" else "Alarm set for $label."
        }
    }

    // ------------------------------------------------------------------ calls

    private fun callPlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.none { it in CALLW } || toks.size > 8) return null
        val strict = toks.any { it in CALL_STRICT }

        // Answer an incoming call
        if (toks.any { it in ANS_STRONG } && toks.all { it in FILL2 || it in CALLW || it in ANS_STRONG || it in words("up", "lo", "le", "लो", "ले") }) {
            return Plan {
                val r = PhoneControl.answerCall(ctx)
                if (r == "Answered") (if (hi) "कॉल उठा ली।" else "Answered.") else r
            }
        }
        // End or reject a call
        val endStrong = toks.any { it in END_STRONG }
        val endWeak = toks.any { it in END_WEAK } && strict
        if ((endStrong || endWeak) && toks.all { it in FILL2 || it in CALLW || it in END_STRONG || it in END_WEAK || it in words("up", "kar", "ko") }) {
            return Plan {
                val r = PhoneControl.endCall(ctx)
                if (r == "Call ended") (if (hi) "कॉल काट दी।" else "Call ended.") else r
            }
        }
        // Place a call: "Rahul ko call karo", "call mummy"
        if (!strict && !(toks.any { it in words("phone", "फोन", "फ़ोन") } && toks.any { it in words("lagao", "laga", "milao", "mila", "लगाओ", "लगा", "मिलाओ", "मिला") })) return null
        val name = toks.filter { it !in FILL2 && it !in CALLW && it !in CALLV && it !in ANS_STRONG && it !in END_STRONG && it !in END_WEAK }
        if (name.isEmpty() || name.size > 3) return null
        val edge = toks.first() in CALLW || toks.last() in CALLW || toks.first() in CALLV || toks.last() in CALLV || toks.last() in FILL2
        if (!edge) return null
        val digits = name.all { it.all { c -> c.isDigit() } }
        return Plan {
            val r = if (digits) ToolRouter.run(ctx, "call_number", JSONObject().put("number", name.joinToString("")))
                    else ToolRouter.run(ctx, "call_contact", JSONObject().put("name", name.joinToString(" ")))
            if (r.startsWith("Calling")) (if (hi) "कॉल लगा रही हूँ।" else r) else r
        }
    }

    // ------------------------------------------------------------------ lock screen

    private fun lockPlan(toks: List<String>, hi: Boolean): Plan? {
        if (toks.none { it in LOCKW } || toks.size > 6) return null
        if (!toks.all { it in FILL2 || it in LOCKW || it in SCREENW }) return null
        return Plan {
            val r = PhoneControl.lockScreen()
            if (r == "Locked") (if (hi) "स्क्रीन लॉक कर दी।" else "Locked.") else r
        }
    }

    // ------------------------------------------------------------------ seek / replay

    private fun seekPlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.size > 10) return null

        // "10 second aage", "peeche 20 second", "1 minute forward"
        val fwd = toks.any { it in FWD }
        val back = toks.any { it in BACK }
        val unitIdx = toks.indexOfFirst { it in UNIT_S || it in UNIT_M || it in UNIT_H }
        if (fwd != back && unitIdx >= 0 &&
            toks.all { it in FILL2 || it in FWD || it in BACK || it in UNIT_S || it in UNIT_M || it in UNIT_H || it in SEEK_FILL || it in MEDIA_NOUN || isNumTok(it) }
        ) {
            val amount = toks.getOrNull(unitIdx - 1)?.let { num(it) } ?: 10
            val unit = toks[unitIdx]
            val secs = amount * (if (unit in UNIT_M) 60 else if (unit in UNIT_H) 3600 else 1)
            if (secs !in 1..36000) return null
            return Plan {
                val r = PhoneControl.seek(ctx, (if (back) -secs else secs) * 1000L)
                if (r == "Done") (if (hi) (if (back) "$secs सेकंड पीछे।" else "$secs सेकंड आगे।") else (if (back) "Back $secs seconds." else "Forward $secs seconds."))
                else r
            }
        }

        // "phir se bajao", "repeat", "dobara"
        val strongRepeat = toks.any { it in words("again", "repeat", "replay", "dobara", "dobaara", "dubara", "phirse", "firse", "restart", "फिरसे", "दोबारा", "दुबारा", "रिपीट", "रीप्ले", "रिप्ले", "phir", "fir", "फिर") }
        if (strongRepeat && toks.all { it in FILL2 || it in REPEATW || it in PLAYV || it in MEDIA_NOUN || it in THIS }) {
            return Plan {
                val r = PhoneControl.replay(ctx)
                if (r == "Done") (if (hi) "फिर से शुरू।" else "Replaying.") else r
            }
        }
        return null
    }

    // ------------------------------------------------------------------ exact volume

    private fun volumePlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.size > 8) return null
        val hasVol = toks.any { it in VOL }
        val mute = toks.any { it in MUTEV }
        val full = toks.any { it in FULLV }
        val numTok = toks.firstOrNull { it.toIntOrNull() != null }
        if (!(hasVol || (mute && toks.size <= 3))) return null
        if (!toks.all { it in FILL2 || it in VOL || it in VOL_FILL || it in FULLV || it in MUTEV || it in PERCENTW || isNumTok(it) }) return null
        val pct = when {
            mute -> 0
            full -> 100
            numTok != null -> numTok.toInt()
            else -> return null
        }
        if (pct !in 0..100) return null
        return Plan {
            val r = PhoneControl.setVolume(ctx, pct)
            if (r.startsWith("Volume")) (if (hi) "आवाज़ $pct% पर।" else "Volume $pct%.") else r
        }
    }

    // ------------------------------------------------------------------ save contact

    private fun contactPlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.none { it in SAVEW } || toks.size > 16) return null
        val digitToks = toks.filter { t -> t.all { it.isDigit() } }
        val digits = digitToks.joinToString("")
        if (digits.length < 6) return null
        val firstDigit = toks.indexOfFirst { t -> t.all { it.isDigit() } }
        val name = toks.take(firstDigit).filter { it !in FILL2 && it !in NAME_JUNK && it !in SAVEW }
        if (name.isEmpty() || name.size > 3) return null
        val display = name.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return Plan {
            val r = PhoneControl.saveContact(ctx, display, digits)
            if (r.startsWith("Saved")) (if (hi) "$display का नंबर सेव कर दिया।" else "Saved $display.") else r
        }
    }

    // ------------------------------------------------------------------ time

    private fun timePlan(toks: List<String>): Plan? {
        if (toks.size > 6 || toks.none { it in TIMEW }) return null
        if (!toks.all { it in FILL2 || it in TIMEW || it in TIME_Q }) return null
        return Plan { PhoneControl.currentTime() }
    }

    // ------------------------------------------------------------------ songs

    /** Words that may stand at the edges of "play ..." without being part of the song name. */
    private val POLITE = words(
        "please", "plz", "pls", "zara", "jara", "kar", "karo", "kardo", "do", "de", "dena", "ab", "jaldi", "vision", "hey", "ok", "okay",
        "mujhe", "mere", "meri", "mera", "na", "yaar", "bhai", "ko", "ka", "ki", "wala", "wali",
        "phone", "फोन", "फ़ोन", "ज़रा", "जरा", "कर", "करो", "दो", "दे", "देना", "अब", "जल्दी", "विजन", "प्लीज", "कृपया", "मुझे", "मेरे", "मेरी", "मेरा", "ना", "यार", "भाई", "को", "का", "की", "वाला", "वाली"
    )
    private val ONE_OF = words("ek", "koi", "kuch", "kuchh", "एक", "कोई", "कुछ")

    private fun trimEdges(list: MutableList<String>) {
        fun edge(t: String) = t in POLITE || t in MEDIA_NOUN || t in FAVW || t in PLAYV || t in PREP || t in SRC
        var changed = true
        while (changed) {
            changed = false
            if (list.isNotEmpty() && edge(list.first())) { list.removeAt(0); changed = true }
            if (list.isNotEmpty() && edge(list.last())) { list.removeAt(list.size - 1); changed = true }
            // "ek gaana", "koi gaana": not a name
            if (list.size >= 2 && list[0] in ONE_OF && list[1] in MEDIA_NOUN) { list.removeAt(0); list.removeAt(0); changed = true }
        }
    }

    private fun songPlan(ctx: Context, toks: List<String>, hi: Boolean): Plan? {
        if (toks.size > 12) return null
        if (toks.any { it in NEXT || it in PREV || it in PAUSE }) return null

        // "ye gaana favourite me jodo": save the song that is playing
        if (toks.any { it in FAVW } && toks.any { it in ADDW } && toks.any { it in THIS || it in MEDIA_NOUN }) {
            return Plan {
                val r = PhoneControl.addCurrentFavorite(ctx)
                if (r.startsWith("Saved") || r.startsWith("Already")) (if (hi) "फेवरेट में जोड़ दिया।" else "Added to favourites.") else r
            }
        }

        val verbIdx = toks.indexOfFirst { it in PLAYV }
        if (verbIdx < 0) return null
        val wantsFav = toks.any { it in FAVW }

        // The verb must start or end the command, so ordinary sentences with "play" in them are left to the model.
        val core = toks.filter { it !in FILL2 && it !in SRC && it !in PREP }
        if (core.isEmpty() || !(core.first() in PLAYV || core.last() in PLAYV)) return null

        // Song name: everything except the verb, the app name and the little words next to it.
        val work = toks.filterIndexed { i, _ -> i != verbIdx }
        val name = ArrayList<String>()
        for ((i, t) in work.withIndex()) {
            if (t in SRC) continue
            val nearSrc = work.getOrNull(i - 1)?.let { it in SRC } == true || work.getOrNull(i + 1)?.let { it in SRC } == true
            if (t in PREP && nearSrc) continue
            name.add(t)
        }
        trimEdges(name)

        if (name.isEmpty()) {
            return Plan {
                // Something is paused: carry on. Nothing in the player: one of his favourites.
                if (!wantsFav && PhoneControl.isPlaying(ctx)) {
                    if (hi) "गाना पहले से बज रहा है।" else "Already playing."
                } else if (!wantsFav && PhoneControl.hasSession(ctx)) {
                    ToolRouter.run(ctx, "media", JSONObject().put("action", "play_pause"))
                    if (hi) "हो गया।" else "Done."
                } else {
                    val r = PhoneControl.playFavorite(ctx)
                    if (r.startsWith("Playing")) (if (hi) "आपका पसंदीदा गाना बजा रही हूँ।" else "Playing a favourite.") else r
                }
            }
        }
        if (name.size > 8) return null
        val query = name.joinToString(" ")
        return Plan {
            val r = PhoneControl.playSong(ctx, query)
            if (r.startsWith("Playing")) (if (hi) "$query बजा रही हूँ।" else "Playing $query.") else r
        }
    }
}
