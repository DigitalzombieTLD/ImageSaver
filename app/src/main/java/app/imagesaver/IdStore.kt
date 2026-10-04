package app.imagesaver

import android.content.Context

interface IdStore {
    /** Returns a new, never previously returned ID (persisted). */
    fun nextId(): Long
}

class PrefsIdStore(context: Context) : IdStore {
    private val prefs = context.getSharedPreferences("imagesaver", Context.MODE_PRIVATE)

    @Synchronized
    override fun nextId(): Long {
        val next = prefs.getLong(KEY, 0L) + 1
        if (!prefs.edit().putLong(KEY, next).commit()) throw java.io.IOException("Could not persist image ID")
        return next
    }

    private companion object {
        const val KEY = "last_id"
    }
}
