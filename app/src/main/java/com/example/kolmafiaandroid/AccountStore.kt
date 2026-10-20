package com.example.kolmafiaandroid

import android.content.Context
import org.json.JSONArray

/**
 * The KoL accounts known on this phone. Each account is a name, plus (only if "Remember me" was ticked
 * when it logged in) its password, encrypted with the Android Keystore. Accounts without a saved
 * password are asked for it at login.
 */
class AccountStore(context: Context) {
    data class Account(val name: String, val remembered: Boolean)

    private val prefs = context.getSharedPreferences("kolmob_accounts", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    init {
        // one-time migration from the old single-login store
        if (names().isEmpty()) {
            secure.load()?.let { (user, pass) ->
                remember(user, pass)
                setLastUser(user)
                secure.clear()
            }
        }
    }

    private fun key(name: String) = "pw_" + name.trim().lowercase()

    private fun names(): MutableList<String> {
        val a = try { JSONArray(prefs.getString("names", "[]")) } catch (_: Exception) { JSONArray() }
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun saveNames(list: List<String>) {
        prefs.edit().putString("names", JSONArray(list).toString()).apply()
    }

    fun accounts(): List<Account> = names().map { Account(it, prefs.contains(key(it))) }

    /** Add the name if it is new (does not touch any saved password). */
    fun add(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val list = names()
        if (list.none { it.equals(n, ignoreCase = true) }) saveNames(list + n)
    }

    /** Add the account and keep its password (encrypted) so it can log in without asking. */
    fun remember(name: String, password: String) {
        add(name)
        prefs.edit().putString(key(name), secure.encrypt(password)).apply()
    }

    fun forgetPassword(name: String) {
        prefs.edit().remove(key(name)).apply()
    }

    fun remove(name: String) {
        forgetPassword(name)
        saveNames(names().filter { !it.equals(name, ignoreCase = true) })
        if (lastUser().equals(name, ignoreCase = true)) prefs.edit().remove("last").apply()
    }

    /** The saved password, or null when none is saved (or the Keystore key was lost). */
    fun password(name: String): String? {
        val blob = prefs.getString(key(name), null) ?: return null
        val p = secure.decrypt(blob)
        if (p == null) forgetPassword(name)
        return p
    }

    fun lastUser(): String? = prefs.getString("last", null)

    fun setLastUser(name: String) {
        prefs.edit().putString("last", name.trim()).apply()
    }
}
