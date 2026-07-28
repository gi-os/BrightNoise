package com.gios.lightnoise.service

import android.content.Context
import com.gios.lightnoise.audio.SoundId

/** Last mix survives a reboot, so the app opens on whatever you fell asleep to. */
internal object Prefs {

    private const val FILE = "lightnoise"

    fun save(context: Context, s: NoiseState) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            putString("a", encode(s.pickA))
            putString("b", encode(s.pickB))
            putFloat("la", s.levelA)
            putFloat("lb", s.levelB)
            putFloat("m", s.master)
            putInt("timer", s.timerMinutes)
            apply()
        }
    }

    fun load(context: Context): NoiseState? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!p.contains("a")) return null
        return NoiseState(
            pickA = decode(p.getString("a", null)),
            pickB = decode(p.getString("b", null)),
            levelA = p.getFloat("la", 1f),
            levelB = p.getFloat("lb", 0.45f),
            master = p.getFloat("m", 0.9f),
            timerMinutes = p.getInt("timer", 0),
        )
    }

    private fun encode(pick: Pick): String = when (pick) {
        Pick.None -> ""
        is Pick.Synth -> "s:${pick.id.name}"
    }

    private fun decode(raw: String?): Pick = when {
        raw.isNullOrEmpty() -> Pick.None
        raw.startsWith("s:") -> {
            // An unknown name means the enum changed under a saved pref; fall back.
            val id = SoundId.entries.firstOrNull { it.name == raw.substring(2) }
            if (id != null) Pick.Synth(id) else Pick.Synth(SoundId.RAIN)
        }
        else -> Pick.None
    }
}
