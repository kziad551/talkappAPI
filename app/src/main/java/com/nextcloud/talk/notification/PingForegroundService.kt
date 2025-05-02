package com.nextcloud.talk.notification

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nextcloud.talk.BuildConfig
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.utils.NotificationPermissionHelper
import com.nextcloud.talk.utils.bundle.BundleKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class PingForegroundService : Service() {

    // --- constants ---------------------------------------------------------

    private val TAG = "PingForegroundService"

    private val NOTIF_ID              = 1
    private val CHANNEL_PING          = "ping_channel"
    private val CHANNEL_CHAT          = "chat_channel"

    private val PREFS_NAME            = "PingServicePrefs"
    private val KEY_LAST_NOTIF_ID     = "last_notif_id"
    private val KEY_LAST_CHAT_ID      = "last_chat_id"
    private val KEY_LAST_ROOM_TOKEN   = "last_room_token"
    private val KEY_CONSECUTIVE_ERR   = "consecutive_errors"

    // --- test credentials --------------------------------------------------

    private val TEST_MODE        = true
    private val TEST_SERVER_URL  = "https://nextcloud.wztechno.com"
    private val TEST_USERNAME    = "admin"
    private val TEST_PASSWORD    = "admin"
    private val TEST_API_URL     = "$TEST_SERVER_URL/ocs/v2.php/apps/notifications/api/v2/notifications"

    // --- members -----------------------------------------------------------

    private val svcScope     = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var prefs: SharedPreferences

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30,  TimeUnit.SECONDS)
        .build()

    // will be kept in prefs, too
    private var consecutiveErrors = 0
    private val maxBackoffSeconds = 300      // 5-minutes cap

    // -----------------------------------------------------------------------

    companion object {
        fun start(ctx: Context) {
            if (!NotificationPermissionHelper.hasNotificationPermission(ctx)) {
                Log.d("PingForegroundService", "Notification permission not granted")
                return
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) {
                Log.d("PingForegroundService", "Notifications disabled by the user")
                return
            }
            ctx.startForegroundService(Intent(ctx, PingForegroundService::class.java))
        }
    }

    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        consecutiveErrors = prefs.getInt(KEY_CONSECUTIVE_ERR, 0)

        createNotificationChannels()
        startForeground(
            NOTIF_ID,
            createPersistentNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (NotificationPermissionHelper.hasNotificationPermission(this)) {
            startPeriodicChecks()
        } else {
            Log.e(TAG, "Missing notification permission – stopping")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!NotificationPermissionHelper.hasNotificationPermission(this) || !nm.areNotificationsEnabled()) {
            Log.e(TAG, "Permission revoked / notifications disabled – stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------
    // Polling loop
    // -----------------------------------------------------------------------

    private fun startPeriodicChecks() {
        svcScope.launch {
            while (true) {
                val delaySec = if (consecutiveErrors > 0) {
                    min(1.shl(consecutiveErrors), maxBackoffSeconds)
                } else {
                    30                                          // 🔔 30-second cycle
                }

                try {
                    checkNextcloudForUpdates()
                    delay(delaySec * 1000L)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in loop", e)
                    delay(60_000)
                }
            }
        }
    }

    // -----------------------------------------------------------------------

    private suspend fun checkNextcloudForUpdates() {
        if (TEST_MODE) {
            if (checkNotifications(TEST_SERVER_URL, TEST_USERNAME, TEST_PASSWORD)) {
                resetErrorCounter()
            }
            return
        }

        val am       = AccountManager.get(this)
        val accounts = am.getAccountsByType("com.nextcloud.talk")
        if (accounts.isEmpty()) { Log.d(TAG, "No NC accounts"); return }

        val acc        = accounts[0]
        val serverUrl  = am.getUserData(acc, "server_url")
        val rawUser    = am.getUserData(acc, "raw_username")
        val pwd        = am.getPassword(acc)

        if (serverUrl == null || rawUser == null || pwd == null) {
            Log.e(TAG, "Missing credentials data")
            incrementErrorCounter()
            return
        }

        val notifOk = checkNotifications(serverUrl, rawUser, pwd)
        val chatOk  = checkChatMessages(serverUrl, rawUser, pwd)

        if (notifOk || chatOk) resetErrorCounter()
    }

    // -----------------------------------------------------------------------
    // Notification  & chat polling
    // -----------------------------------------------------------------------

    private suspend fun checkNotifications(serverUrl: String, username: String, password: String): Boolean {
        val apiUrl = if (TEST_MODE) "$TEST_API_URL?format=json"
                     else "$serverUrl/ocs/v2.php/apps/notifications/api/v2/notifications?format=json"

        val req = Request.Builder()
            .url(apiUrl)
            .header("Authorization", Credentials.basic(username, password))
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) {
                    Log.e(TAG, "Notif fetch failed: ${rsp.code}")
                    incrementErrorCounter(); return false
                }

                val body = rsp.body?.string() ?: return false
                if (BuildConfig.DEBUG) Log.d(TAG, "⏬ Notifications:\n$body")

                val dataArr = JSONObject(body).getJSONObject("ocs").getJSONArray("data")
                if (dataArr.length() == 0) return true

                val newId       = dataArr.getJSONObject(0).getInt("notification_id")
                val lastSavedId = prefs.getInt(KEY_LAST_NOTIF_ID, -1)
                if (newId > lastSavedId) {
                    val n          = dataArr.getJSONObject(0)
                    val title      = n.getString("subject")
                    val text       = n.getString("message")
                    val roomToken  = n.extractRoomToken()

                    Log.d(TAG, "📨 New notification ($newId) – token: $roomToken")
                    showNewMessageNotification(title, text, roomToken)

                    prefs.edit().putInt(KEY_LAST_NOTIF_ID, newId).apply()
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notif check error", e); return false
        }
    }

    private suspend fun checkChatMessages(serverUrl: String, username: String, password: String): Boolean {
        val roomToken = prefs.getString(KEY_LAST_ROOM_TOKEN, null) ?: return true
        val lastId    = prefs.getInt(KEY_LAST_CHAT_ID, -1)

        val apiUrl = "$serverUrl/ocs/v2.php/apps/spreed/api/v4/room/$roomToken/messages" +
                     "?format=json&lastKnownMessageId=$lastId"

        val req = Request.Builder()
            .url(apiUrl)
            .header("Authorization", Credentials.basic(username, password))
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) { Log.e(TAG, "Chat fetch failed: ${rsp.code}"); return false }

                val body = rsp.body?.string() ?: return false
                if (BuildConfig.DEBUG) Log.d(TAG, "⏬ Chat:\n$body")

                val dataArr = JSONObject(body).getJSONObject("ocs").getJSONArray("data")
                if (dataArr.length() == 0) return true

                for (i in 0 until dataArr.length()) {
                    val m     = dataArr.getJSONObject(i)
                    val from  = m.getString("actorDisplayName")
                    val text  = m.getString("message")
                    showNewMessageNotification("New message from $from", text, roomToken)
                }

                val newLast = dataArr.getJSONObject(dataArr.length() - 1).getInt("id")
                prefs.edit().putInt(KEY_LAST_CHAT_ID, newLast).apply()
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat check error", e); return false
        }
    }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------

    /** Extracts Talk room-token from different API variants */
    private fun JSONObject.extractRoomToken(): String {
        optString("roomToken").takeIf { it.isNotEmpty() }?.let { return it }

        optString("object_id").substringBefore('/').takeIf { it.isNotEmpty() }?.let { return it }

        val link = optString("link")
        val idx  = link.indexOf("/call/")
        if (idx != -1) {
            val start = idx + "/call/".length
            return link.substring(start).substringBefore('#')
        }
        return ""
    }

    private fun showNewMessageNotification(title: String, message: String, roomToken: String) {
        // Create an intent based on whether we have a room token
        val intent = if (roomToken.isEmpty()) {
            Log.d(TAG, "No room token available - will open conversation list")
            Intent(this, ConversationsListActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(BundleKeys.KEY_ROOM_TOKEN, roomToken)
            }
        }

        // Create a proper back stack with TaskStackBuilder
        val pendingIntent = TaskStackBuilder.create(this).run {
            // Add the back stack
            addNextIntentWithParentStack(intent)
            // Get the PendingIntent containing the entire back stack
            getPendingIntent(
                0,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_CHAT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        // Use room token hash as notification ID to collapse notifications per room
        // If no room token, use a fixed ID for general notifications
        val notificationId = if (roomToken.isEmpty()) NOTIF_ID + 1 else roomToken.hashCode()
        notificationManager.notify(notificationId, notification)
    }

    private fun createPersistentNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_PING)
            .setContentTitle("Talk dev service is running")
            .setContentText("Polling server every 30 s")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ping = NotificationChannel(CHANNEL_PING, "Ping Notifications", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Periodic background polling" }

            val chat = NotificationChannel(CHANNEL_CHAT, "Chat Notifications", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "New chat messages" }

            (getSystemService(NotificationManager::class.java))
                .createNotificationChannels(listOf(ping, chat))
        }
    }

    // -----------------------------------------------------------------------
    // error-counter helpers
    // -----------------------------------------------------------------------

    private fun resetErrorCounter() {
        if (consecutiveErrors != 0) {
            consecutiveErrors = 0
            prefs.edit().putInt(KEY_CONSECUTIVE_ERR, 0).apply()
        }
    }

    private fun incrementErrorCounter() {
        consecutiveErrors++
        prefs.edit().putInt(KEY_CONSECUTIVE_ERR, consecutiveErrors).apply()
        Log.d(TAG, "Incremented consecutive errors → $consecutiveErrors")
    }
}
