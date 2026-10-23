package com.example.door   // خاص يطابق package name ديال المشروع ديالك

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("door", Context.MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var msg: TextView
    private var holdRunnable: Runnable? = null

    private var ip: String
        get() = prefs.getString("ip", "") ?: ""
        set(v) = prefs.edit().putString("ip", v).apply()
    private var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#111111")) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(20))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)
        render()
    }

    // أي خروج من التطبيق = وقف الباب
    override fun onPause() {
        super.onPause()
        stopHold()
        if (token.isNotEmpty()) call("/api/stop", null) { _, _ -> }
    }

    // ---------------- الشاشات ----------------
    private fun render() {
        root.removeAllViews()
        root.addView(label("🚪 التحكم فالباب", 26f, Color.WHITE))
        if (ip.isEmpty() || token.isEmpty()) pairScreen() else mainScreen()
        msg = label("", 18f, Color.WHITE)
        root.addView(msg)
    }

    private fun pairScreen() {
        root.addView(label("اقتران لأول مرة", 18f, Color.parseColor("#AAAAAA")))
        val ipBox = field("IP الحاسوب (مثلا 192.168.1.20)", ip)
        val codeBox = field("كود الاقتران (6 أرقام)", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val nameBox = field("سمية الهاتف", "هاتف")
        root.addView(ipBox); root.addView(codeBox); root.addView(nameBox)
        root.addView(button("اقتران", "#F9A825", Color.BLACK) {
            val newIp = ipBox.text.toString().trim()
            val body = JSONObject()
                .put("code", codeBox.text.toString().trim())
                .put("name", nameBox.text.toString().trim()).toString()
            ip = newIp
            say("كنتسناو الموافقة من الحاسوب... ⏳")
            call("/pair", body, 90000) { code, json ->
                if (code == 200 && json?.has("token") == true) {
                    token = json.getString("token"); render(); say("تم الاقتران ✅")
                } else say(json?.optString("error") ?: "ما قدرتش نتصل بالحاسوب")
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mainScreen() {
        root.addView(button("🚶 فتح للراجلين", "#2E7D32") { timed("ped") })
        root.addView(button("🚗 فتح للسيارات", "#1565C0") { timed("car") })
        root.addView(button("🔒 إغلاق", "#C62828") { timed("close") })
        root.addView(label("مانييل: الباب كيتحرك ما دام صباعك على الزر", 15f, Color.parseColor("#AAAAAA")))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(holdButton("⬆️ فتح مانييل", "#00897B", "hold_open"), weighted())
        row.addView(holdButton("⬇️ إغلاق مانييل", "#AD1457", "hold_close"), weighted())
        root.addView(row)

        root.addView(button("⚙️ إعادة الاقتران / تغيير IP", "#333333") {
            token = ""; render()
        })
    }

    // ---------------- الأوامر ----------------
    private fun timed(action: String) {
        call("/api/$action", null) { code, json ->
            when {
                code == 401 -> { token = ""; render(); say("خاصك تقترن من جديد") }
                code == 200 -> say(if (json?.optString("status") == "RUNNING") "الباب كيتحرك..." else "تم الوقف")
                else -> say(json?.optString("error") ?: "ما قدرتش نتصل بالحاسوب")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdButton(text: String, color: String, action: String): Button =
        button(text, color) {}.apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true) // ما يتحبسش الضغط بالسكرول
                        v.alpha = 0.6f; startHold(action)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f; stopHold()
                        call("/api/stop", null) { _, _ -> }
                        say("تم الوقف")
                    }
                }
                true
            }
        }

    // الطلب كيتعاود كل 0.3 ثانية، والسيرفر كيطفي وحدو إلا وقفو الطلبات
    private fun startHold(action: String) {
        stopHold()
        val r = object : Runnable {
            override fun run() {
                call("/api/$action", null) { code, json ->
                    if (code == 200) {
                        say(if (json?.optString("status") == "BUSY") "الباب كيتحرك، وقفو الأول" else "الباب كيتحرك...")
                    } else {
                        stopHold(); say(json?.optString("error") ?: "الاتصال تقطع")
                    }
                }
                ui.postDelayed(this, 300)
            }
        }
        holdRunnable = r
        r.run()
    }

    private fun stopHold() {
        holdRunnable?.let { ui.removeCallbacks(it) }
        holdRunnable = null
    }

    // ---------------- الشبكة ----------------
    private fun call(path: String, body: String?, timeoutMs: Int = 2000, cb: (Int, JSONObject?) -> Unit) {
        val currentIp = ip; val tok = token
        thread {
            var code = -1; var json: JSONObject? = null
            try {
                val c = URL("http://$currentIp:5000$path").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 2000; c.readTimeout = timeoutMs
                c.setRequestProperty("X-Token", tok)
                if (body != null) {
                    c.setRequestProperty("Content-Type", "application/json")
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                code = c.responseCode
                val stream = if (code < 400) c.inputStream else c.errorStream
                json = JSONObject(stream.bufferedReader().readText())
            } catch (e: Exception) { /* code = -1 */ }
            ui.post { cb(code, json) }
        }
    }

    // ---------------- أدوات UI ----------------
    private fun say(s: String) { if (::msg.isInitialized) msg.text = s }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY); gravity = Gravity.CENTER; textSize = 18f
    }

    private fun button(t: String, bg: String, fg: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this).apply {
            text = t; textSize = 20f; setTextColor(fg)
            setBackgroundColor(Color.parseColor(bg))
            layoutParams = LinearLayout.LayoutParams(-1, dp(80)).apply { setMargins(0, dp(8), 0, dp(8)) }
            setOnClickListener { onClick() }
        }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(100), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
}
