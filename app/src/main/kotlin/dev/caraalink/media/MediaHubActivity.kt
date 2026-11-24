package dev.caraalink.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/** Phone-side settings for the media hub: permissions, radio list, a preview of the car panel. */
@SuppressLint("SetTextI18n")
class MediaHubActivity : ComponentActivity() {

    private lateinit var radioList: LinearLayout
    private lateinit var permStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(title("AA Bridge Media"))
        col.addView(para(
            "On the car screen, tap the ♪ Media button in the corner of Android Auto to open music, " +
                "radio, videos and the web browser. Music and radio also appear in Android Auto's own " +
                "media list once you turn on Android Auto → Developer settings → Unknown sources.\n\n" +
                "Videos and the web browser only run while the car is parked (GPS speed). " +
                "Music and radio play through the phone's Bluetooth, like the rest of AA Bridge.",
        ))

        col.addView(header("Access"))
        permStatus = para("")
        col.addView(permStatus)
        col.addView(button("Allow music, video and location access") { askPermissions() })

        col.addView(header("Radio stations"))
        col.addView(para("Paste a stream link (MP3, AAC or .m3u8). Most stations list theirs on their website."))
        val name = EditText(this).apply { hint = "Station name" }
        val url = EditText(this).apply { hint = "https://…/stream" }
        col.addView(name)
        col.addView(url)
        col.addView(button("Add station") {
            val u = url.text.toString().trim()
            if (!u.startsWith("http")) {
                Toast.makeText(this, "The link must start with http:// or https://", Toast.LENGTH_SHORT).show()
            } else {
                MediaCatalog.addRadio(this, name.text.toString().trim(), u)
                name.setText("")
                url.setText("")
                refreshRadios()
            }
        })
        radioList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(radioList)

        col.addView(header("Try it"))
        col.addView(button("Open the car media panel on this phone") {
            startActivity(Intent(this, CarMediaPreviewActivity::class.java))
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        MediaCatalog.invalidate()
        refreshPerms()
        refreshRadios()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        MediaCatalog.invalidate()
        refreshPerms()
    }

    private fun askPermissions() {
        val perms = buildList {
            add(MediaCatalog.audioPermission())
            add(MediaCatalog.videoPermission())
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct().toTypedArray()
        requestPermissions(perms, 7)
    }

    private fun refreshPerms() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        permStatus.text = "${mark(MediaCatalog.hasAudio(this))} Music   ${mark(MediaCatalog.hasVideo(this))} Videos   ${mark(loc)} Location (parked check)"
    }

    private fun refreshRadios() {
        radioList.removeAllViews()
        val radios = MediaCatalog.radios(this)
        if (radios.isEmpty()) radioList.addView(para("No stations yet."))
        radios.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(para("${r.name}\n${r.url}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    MediaCatalog.removeRadio(this@MediaHubActivity, i)
                    refreshRadios()
                }
            })
            radioList.addView(row)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun title(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 26f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun header(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun para(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}

/** The car panel full-screen on the phone, to try it without the car. */
class CarMediaPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.BLACK)
        setContentView(CarMediaPanel(this) { finish() })
    }
}
