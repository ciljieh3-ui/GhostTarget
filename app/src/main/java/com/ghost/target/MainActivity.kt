package com.ghost.target

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Bitmap

class MainActivity : Activity() {

    private val bg = Color.rgb(7, 11, 14)
    private val panel = Color.rgb(15, 23, 27)
    private val green = Color.rgb(0, 255, 145)
    private val muted = Color.rgb(133, 157, 151)

    private lateinit var root: LinearLayout
    private var photo: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        render()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setBackgroundColor(panel)
            val p = LinearLayout.LayoutParams(
                -1, -2
            )
            p.setMargins(0, 0, 0, dp(12))
            layoutParams = p
        }
    }

    private fun button(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setTextColor(green)
            setBackgroundColor(Color.rgb(23, 39, 34))
            setOnClickListener { action() }
        }
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(23), dp(20), dp(15))
        }

        header.addView(
            text("GHOSTTARGET / X", 23f, green, true)
        )
        header.addView(
            text(
                "SECURE DEVICE MANAGEMENT SYSTEM",
                10f, muted, true
            )
        )

        root.addView(header)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(5), dp(16), dp(25))
        }

        val isAdmin = BuildConfig.APP_ROLE == "ADMIN"

        content.addView(text(
            if (isAdmin) "COMMAND CENTER" else "CLIENT TERMINAL",
            12f, green, true
        ))

        content.addView(text(
            if (isAdmin)
                "Control dashboard / Local overview"
            else
                "Device session / User-controlled actions",
            14f, Color.WHITE
        ))

        val status = card()
        status.addView(text("● SYSTEM STATUS", 12f, green, true))
        status.addView(text("APPLICATION : ONLINE", 13f))
        status.addView(text("ROLE : ${BuildConfig.APP_ROLE}", 13f))
        status.addView(text("VERSION : 1.0.0", 13f))
        status.addView(text("SECURITY : LOCAL SESSION", 12f, muted))
        content.addView(status)

        if (isAdmin) {
            val devices = card()
            devices.addView(text("DEVICE OVERVIEW", 15f, green, true))
            devices.addView(text("Registered devices: 0", 13f))
            devices.addView(text("Active sessions: 0", 13f))
            devices.addView(text("Pending approvals: 0", 13f))
            devices.addView(text("Backend: Not configured", 12f, muted))
            content.addView(devices)

            val activity = card()
            activity.addView(text("ACTIVITY LOG", 15f, green, true))
            activity.addView(text("System initialized.", 13f))
            activity.addView(text("No remote session connected.", 13f, muted))
            activity.addView(text("Waiting for secure pairing...", 13f, muted))
            content.addView(activity)

            val security = card()
            security.addView(text("SECURITY CENTER", 15f, green, true))
            security.addView(text("Local dashboard active", 13f))
            security.addView(text("Remote actions disabled until authenticated pairing.", 12f, muted))
            content.addView(security)

        } else {
            val camera = card()
            camera.addView(text("CAMERA SESSION", 16f, green, true))
            camera.addView(text(
                "Kamera hanya aktif setelah kamu menekan tombol dan menyetujui penggunaan kamera.",
                12f, muted
            ))

            camera.addView(button("OPEN CAMERA / TAKE PHOTO") {
                openCamera()
            })

            photo = ImageView(this).apply {
                adjustViewBounds = true
                visibility = View.GONE
            }
            camera.addView(photo)
            content.addView(camera)

            val info = card()
            info.addView(text("DEVICE INFORMATION", 15f, green, true))
            info.addView(text("Android: ${android.os.Build.VERSION.RELEASE}", 13f))
            info.addView(text("Model: ${android.os.Build.MODEL}", 13f))
            info.addView(text("Manufacturer: ${android.os.Build.MANUFACTURER}", 13f))
            info.addView(text("Camera: User initiated only", 12f, muted))
            content.addView(info)

            val privacy = card()
            privacy.addView(text("PRIVACY CONTROL", 15f, green, true))
            privacy.addView(text(
                "Tidak ada pengambilan gambar tersembunyi. " +
                "Sesi kamera memerlukan tindakan langsung pengguna.",
                12f, muted
            ))
            content.addView(privacy)
        }

        content.addView(text(
            "GHOSTTARGET X  •  BUILD 001",
            10f, muted
        ))

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.CAMERA), 100
            )
            return
        }

        launchCamera()
    }

    private fun launchCamera() {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, 101)
            } else {
                Toast.makeText(
                    this, "Camera app unavailable",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this, "Camera error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        }
    }

    @Deprecated("Legacy camera result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 101 && resultCode == RESULT_OK) {
            val bitmap = data?.extras?.get("data") as? Bitmap
            if (bitmap != null) {
                photo?.apply {
                    setImageBitmap(bitmap)
                    visibility = View.VISIBLE
                }
                Toast.makeText(
                    this, "Photo captured successfully",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
