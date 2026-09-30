package com.yage.opencode_client

import android.content.Context
import android.content.SharedPreferences
import com.yage.opencode_client.util.SessionStatsStore
import io.mockk.every
import io.mockk.mockk

/** In-memory SharedPreferences for JVM unit tests. */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = data.toMap()
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        data[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    private fun put(key: String, value: Any?) {
        if (value == null) data.remove(key) else data[key] = value
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { put(key, value) }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { put(key, values) }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { put(key, value) }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { put(key, value) }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { put(key, value) }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { put(key, value) }
        override fun remove(key: String): SharedPreferences.Editor = apply { put(key, null) }
        override fun clear(): SharedPreferences.Editor = apply { data.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}

/** A [SessionStatsStore] backed by an in-memory prefs for JVM unit tests. */
fun testSessionStatsStore(prefs: FakeSharedPreferences = FakeSharedPreferences()): SessionStatsStore =
    SessionStatsStore(
        mockk<Context> {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
    )
