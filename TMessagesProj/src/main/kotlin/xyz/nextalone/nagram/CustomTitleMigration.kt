package xyz.nextalone.nagram

import android.content.SharedPreferences

/** Pure, one-shot migration for brand names persisted by older builds. */
object CustomTitleMigration {
    @JvmStatic
    fun normalize(
        preferences: SharedPreferences,
        key: String,
        target: String,
        versionKey: String,
        version: Int
    ): Boolean {
        if (preferences.getInt(versionKey, 0) >= version) {
            return false
        }
        val saved = preferences.getString(key, null)
        val normalized = if (saved != null && isLegacyBrand(saved)) target else null
        val changed = normalized != null && normalized != saved
        val editor = preferences.edit().putInt(versionKey, version)
        if (changed) {
            editor.putString(key, normalized)
        }
        editor.apply()
        return changed
    }

    @JvmStatic
    fun isLegacyBrand(value: String): Boolean {
        val lower = value.trim().lowercase(java.util.Locale.ROOT)
        return lower == "nagram" || lower == "nekox" || lower == "hgram"
    }

    /** Display guard for values persisted before the one-shot migration ran. */
    @JvmStatic
    fun displayValue(value: String?, target: String): String {
        if (value == null) {
            return target
        }
        return if (isLegacyBrand(value)) target else value
    }
}
