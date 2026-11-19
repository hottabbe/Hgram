package xyz.nextalone.nagram

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

object NkmrConfig {
    private const val USER_PREFERENCES_NAME = "nkmrcfg"
    private const val DEFAULT_PREFERENCES_NAME = "nkmrcfg_default"

    private val lock = Any()
    private val NULL_SENTINEL = Any()
    private val memoryDefaults = ConcurrentHashMap<String, Any>()

    private val listenerWrappers = IdentityHashMap<
            SharedPreferences.OnSharedPreferenceChangeListener,
            SharedPreferences.OnSharedPreferenceChangeListener,
            >()
    private val userPreferences = ApplicationLoader.applicationContext.getSharedPreferences(
        USER_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val defaultPreferences = ApplicationLoader.applicationContext.getSharedPreferences(
        DEFAULT_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @JvmField
    val preferences: SharedPreferences = MergedSharedPreferences()

    @JvmStatic
    fun compact() {
        synchronized(lock) {
            val editor = userPreferences.edit()
            var changed = false
            userPreferences.all.forEach { (key, value) ->
                val defVal = getRegisteredDefault(key)
                if (defVal !== NULL_SENTINEL && valuesEqual(value, defVal)) {
                    editor.remove(key)
                    changed = true
                }
            }
            if (changed) {
                editor.apply()
            }
        }
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            memoryDefaults.clear()
            userPreferences.edit().clear().apply()
            defaultPreferences.edit().clear().apply()
        }
    }

    private fun registerDefault(key: String, value: Any?) {
        val stored = when (value) {
            null -> NULL_SENTINEL
            is Set<*> -> HashSet(value)
            else -> value
        }
        memoryDefaults[key] = stored
    }

    private fun getRegisteredDefault(key: String): Any? {
        val mem = memoryDefaults[key]
        if (mem != null) {
            return if (mem === NULL_SENTINEL) null else mem
        }
        return if (defaultPreferences.contains(key)) defaultPreferences.all[key] else NULL_SENTINEL
    }

    private fun valuesEqual(first: Any?, second: Any?): Boolean {
        return when {
            first is Set<*> && second is Set<*> -> first == second
            first == null || second == null -> first == second
            first.javaClass != second.javaClass -> false
            else -> first == second
        }
    }

    private fun putValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?,
    ): SharedPreferences.Editor {
        return when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                require(value.all { it is String }) { "Unsupported preference value for $key: non-String set" }
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, HashSet(value as Set<String>))
            }
            else -> throw IllegalArgumentException("Unsupported preference value for $key: ${value.javaClass.name}")
        }
    }

    private class MergedSharedPreferences : SharedPreferences {
        override fun getAll(): Map<String, *> =
            HashMap<String, Any?>().apply {
                putAll(defaultPreferences.all)
                memoryDefaults.forEach { (k, v) ->
                    put(k, if (v === NULL_SENTINEL) null else v)
                }
                putAll(userPreferences.all)
            }

        override fun getString(key: String, defValue: String?): String? {
            registerDefault(key, defValue)
            return userPreferences.getString(key, defValue)
        }

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            registerDefault(key, defValues)
            return userPreferences.getStringSet(key, defValues)
        }

        override fun getInt(key: String, defValue: Int): Int {
            registerDefault(key, defValue)
            return userPreferences.getInt(key, defValue)
        }

        override fun getLong(key: String, defValue: Long): Long {
            registerDefault(key, defValue)
            return userPreferences.getLong(key, defValue)
        }

        override fun getFloat(key: String, defValue: Float): Float {
            registerDefault(key, defValue)
            return userPreferences.getFloat(key, defValue)
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            registerDefault(key, defValue)
            return userPreferences.getBoolean(key, defValue)
        }

        override fun contains(key: String): Boolean =
            userPreferences.contains(key) || memoryDefaults.containsKey(key) || defaultPreferences.contains(key)

        override fun edit(): SharedPreferences.Editor = CompactingEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener == null) return
            synchronized(lock) {
                if (listenerWrappers.containsKey(listener)) return
                val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    listener.onSharedPreferenceChanged(preferences, key)
                }
                listenerWrappers[listener] = wrapper
                userPreferences.registerOnSharedPreferenceChangeListener(wrapper)
            }
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener == null) return
            synchronized(lock) {
                listenerWrappers.remove(listener)?.let {
                    userPreferences.unregisterOnSharedPreferenceChangeListener(it)
                }
            }
        }
    }

    private class CompactingEditor : SharedPreferences.Editor {
        private val editorLock = Any()
        private val changes = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = value
            }
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = values?.let(::HashSet)
            }
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = value
            }
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = value
            }
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = value
            }
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = value
            }
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                changes[key] = null
            }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            synchronized(editorLock) {
                clearRequested = true
            }
        }

        override fun commit(): Boolean = execute(commit = true)

        override fun apply() {
            execute(commit = false)
        }

        private fun execute(commit: Boolean): Boolean {
            val pendingChanges: Map<String, Any?>
            val shouldClear: Boolean
            synchronized(editorLock) {
                pendingChanges = LinkedHashMap(changes)
                shouldClear = clearRequested
                changes.clear()
                clearRequested = false
            }

            synchronized(lock) {
                val editor = userPreferences.edit()
                if (shouldClear) {
                    editor.clear()
                }
                pendingChanges.forEach { (key, value) ->
                    val defVal = getRegisteredDefault(key)
                    if (value != null && defVal !== NULL_SENTINEL && valuesEqual(value, defVal)) {
                        editor.remove(key)
                    } else {
                        putValue(editor, key, value)
                    }
                }
                if (commit) {
                    return editor.commit()
                }
                editor.apply()
                return true
            }
        }
    }
}
