package com.ghar.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ghar.player.shared.MiniHttp
import com.ghar.player.shared.Protocol
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** The TV's small web API for Bcash Player (see Protocol). */
object ReceiverServer {

    private fun ok(o: JSONObject) = MiniHttp.json(200, o.toString())
    private fun fail(status: Int, msg: String) = MiniHttp.json(status, JSONObject().put("ok", false).put("error", msg).toString())

    fun handle(s: PlaybackService, r: MiniHttp.Request): MiniHttp.Response? {
        if (r.path == Protocol.HELLO) {
            return ok(
                JSONObject().put("app", "gharplayer").put("v", Protocol.VERSION)
                    .put("deviceId", Pairing.deviceId(s)).put("name", Pairing.deviceName())
            )
        }
        if (r.path == Protocol.PAIR && r.method == "POST") {
            val o = JSONObject(r.bodyText())
            return when (Pairing.check(o.optString("code").trim())) {
                Pairing.OK -> {
                    val token = Pairing.addPhone(s, o.optString("phoneId"), o.optString("phoneName", "Phone"))
                    ok(JSONObject().put("ok", true).put("token", token).put("deviceId", Pairing.deviceId(s)).put("name", Pairing.deviceName()))
                }
                Pairing.WRONG -> fail(403, "wrong code")
                else -> fail(403, "Open Ghar Player on the TV to see the code")
            }
        }
        val phone = Pairing.phoneByToken(s, r.header(Protocol.HEADER_TOKEN)) ?: return fail(403, "not paired")
        val body = if (r.body.isEmpty()) JSONObject() else JSONObject(r.bodyText())
        return when (r.path) {
            Protocol.STATE -> ok(s.onMain { s.stateJson() })
            Protocol.LOAD -> { s.onMain { s.load(body.put("phoneName", body.optString("phoneName", phone))) }; ok(JSONObject().put("ok", true)) }
            Protocol.CONTROL -> { s.onMain { s.control(body) }; ok(s.onMain { s.stateJson() }) }
            Protocol.QUEUE -> { s.onMain { s.queue(body) }; ok(JSONObject().put("ok", true)) }
            Protocol.META -> { s.onMain { s.meta(body) }; ok(JSONObject().put("ok", true)) }
            Protocol.SETTINGS -> { s.onMain { s.settings(body) }; ok(JSONObject().put("ok", true)) }
            "/api/image" -> { s.onMain { s.picture(body) }; ok(JSONObject().put("ok", true)) }
            else -> null
        }
    }
}

/**
 * Pairing without accounts: while the TV screen is open it shows a 6-digit code; the phone sends
 * it once and gets a random token. Five wrong codes make a new code.
 */
object Pairing {
    const val OK = 0
    const val WRONG = 1
    const val CLOSED = 2

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var code: String? = null
    private var wrong = 0
    var onChanged: (() -> Unit)? = null

    @Synchronized
    fun open(): String {
        val c = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
        code = c; wrong = 0
        return c
    }

    @Synchronized
    fun close() {
        code = null
    }

    fun code(): String? = code

    @Synchronized
    fun check(given: String): Int {
        val c = code ?: return CLOSED
        if (c == given) return OK
        if (++wrong >= 5) {
            open()
            main.post { onChanged?.invoke() }
        }
        return WRONG
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun deviceId(c: Context): String {
        val p = prefs(c)
        return p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
    }

    fun deviceName(): String {
        val m = Build.MODEL?.trim().orEmpty()
        return if (m.isEmpty()) "Ghar Player" else "Ghar Player · $m"
    }

    /** Saves a paired phone; returns its token. */
    @Synchronized
    fun addPhone(c: Context, phoneId: String, name: String): String {
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val phones = JSONObject(prefs(c).getString("phones", "{}") ?: "{}")
        phones.put(phoneId.ifEmpty { token.take(8) }, JSONObject().put("name", name).put("token", token))
        prefs(c).edit().putString("phones", phones.toString()).apply()
        main.post { onChanged?.invoke() }
        return token
    }

    /** The phone's name for a valid token, or null. */
    fun phoneByToken(c: Context, token: String): String? {
        if (token.isEmpty()) return null
        val phones = JSONObject(prefs(c).getString("phones", "{}") ?: "{}")
        phones.keys().forEach { k ->
            val p = phones.optJSONObject(k) ?: return@forEach
            if (p.optString("token") == token) return p.optString("name", "Phone")
        }
        return null
    }

    fun pairedCount(c: Context): Int = JSONObject(prefs(c).getString("phones", "{}") ?: "{}").length()

    fun forgetAll(c: Context) {
        prefs(c).edit().remove("phones").apply()
        main.post { onChanged?.invoke() }
    }
}
