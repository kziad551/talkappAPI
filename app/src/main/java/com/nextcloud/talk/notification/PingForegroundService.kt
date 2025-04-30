package com.nextcloud.talk.notification

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.nextcloud.talk.utils.NotificationPermissionHelper
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
    private val TAG = "PingForegroundService"
    private val NOTIF_ID = 1
    private val CHANNEL_PING = "ping_channel"
    private val CHANNEL_CHAT = "chat_channel"
    private val PREFS_NAME = "PingServicePrefs"
    private val KEY_LAST_NOTIF_ID = "last_notif_id"
    private val KEY_LAST_CHAT_ID = "last_chat_id"
    private val KEY_LAST_ROOM_TOKEN = "last_room_token"
    private val KEY_CONSECUTIVE_ERRORS = "consecutive_errors"
    
    // Static test credentials
    private val TEST_MODE = true
    private val TEST_SERVER_URL = "https://nextcloud.wztechno.com"
    private val TEST_USERNAME = "admin"
    private val TEST_PASSWORD = "admin"
    private val TEST_API_URL = "https://nextcloud.wztechno.com/ocs/v2.php/apps/notifications/api/v2/notifications"
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private var consecutiveErrors = 0
    private val maxBackoffSeconds = 300 // 5 minutes max delay

    companion object {
        /** Convenience helper to start service from anywhere */
        fun start(ctx: Context) {
            if (!NotificationPermissionHelper.hasNotificationPermission(ctx)) {
                Log.d("PingForegroundService", "Notification permission not granted")
                return
            }
            
            // Don't start if notifications are disabled
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.areNotificationsEnabled()) {
                Log.d("PingForegroundService", "Notifications are disabled by the user")
                return
            }
            
            ctx.startForegroundService(Intent(ctx, PingForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannels()
        
        val notification = createPersistentNotification()
        startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        
        // Start periodic checks only if we have notification permission
        if (NotificationPermissionHelper.hasNotificationPermission(this)) {
            startPeriodicChecks()
        } else {
            Log.e(TAG, "Missing notification permission - service cannot function properly")
            stopSelf()
        }
    }

    private fun startPeriodicChecks() {
        consecutiveErrors = prefs.getInt(KEY_CONSECUTIVE_ERRORS, 0)
        
        serviceScope.launch {
            while (true) {
                try {
                    val delaySeconds = if (consecutiveErrors > 0) {
                        // Exponential backoff: 2^errors seconds, max 5 minutes
                        val backoffSeconds = min(Math.pow(2.0, consecutiveErrors.toDouble()).toInt(), maxBackoffSeconds)
                        Log.d(TAG, "Using backoff delay of $backoffSeconds seconds after $consecutiveErrors consecutive errors")
                        backoffSeconds
                    } else {
                        30 // Standard 30-second check (changed from 60)
                    }
                    
                    checkNextcloudForUpdates()
                    delay(delaySeconds * 1000L)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic check", e)
                    delay(60000) // Wait a minute before retrying
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If we don't have notification permission or notifications are disabled, stop the service
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!NotificationPermissionHelper.hasNotificationPermission(this) || 
            !notificationManager.areNotificationsEnabled()) {
            Log.e(TAG, "Missing notification permission or notifications disabled - stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pingChannel = NotificationChannel(
                CHANNEL_PING,
                "Ping Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Periodic test notifications"
                setShowBadge(false)
            }

            val chatChannel = NotificationChannel(
                CHANNEL_CHAT,
                "Chat Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New chat message notifications"
                setShowBadge(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(listOf(pingChannel, chatChannel))
        }
    }

    private fun createPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_PING)
            .setContentTitle("Talk dev service is running")
            .setContentText("Polling server every minute")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private suspend fun checkNextcloudForUpdates() {
        // Use static test credentials if in test mode
        if (TEST_MODE) {
            Log.d(TAG, "🧪 Using test credentials")
            Log.d(TAG, "   Server URL: $TEST_SERVER_URL")
            Log.d(TAG, "   Username: $TEST_USERNAME")
            Log.d(TAG, "   API URL: $TEST_API_URL")
            
            try {
                // Check both notifications and chat messages
                val notificationsOk = checkNotifications(TEST_SERVER_URL, TEST_USERNAME, TEST_PASSWORD)
                
                // Don't check chat messages in test mode
                // val chatMessagesOk = checkChatMessages(TEST_SERVER_URL, TEST_USERNAME, TEST_PASSWORD)
                
                if (notificationsOk) {
                    // Reset error counter if at least one check was successful
                    if (consecutiveErrors > 0) {
                        consecutiveErrors = 0
                        prefs.edit().putInt(KEY_CONSECUTIVE_ERRORS, 0).apply()
                        Log.d(TAG, "Reset consecutive error counter")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates with test credentials", e)
                incrementConsecutiveErrors()
            }
            return
        }
        
        // Normal account-based operation
        val accountManager = AccountManager.get(this)
        val accounts = accountManager.getAccountsByType("com.nextcloud.talk")
        
        if (accounts.isEmpty()) {
            Log.d(TAG, "No accounts found")
            return
        }

        val account = accounts[0]
        val serverUrl = accountManager.getUserData(account, "server_url")
        val rawUsername = accountManager.getUserData(account, "raw_username")
        val password = accountManager.getPassword(account)

        if (serverUrl == null || password == null || rawUsername == null) {
            Log.e(TAG, "Missing server URL, username or password")
            Log.e(TAG, "  serverUrl: ${serverUrl != null}")
            Log.e(TAG, "  rawUsername: ${rawUsername != null}")
            Log.e(TAG, "  password: ${password != null}")
            incrementConsecutiveErrors()
            return
        }

        // Debug log credentials
        Log.d(TAG, "🔑 Using credentials:")
        Log.d(TAG, "   Server URL: $serverUrl")
        Log.d(TAG, "   Username: $rawUsername")
        Log.d(TAG, "   Password: ${if (password.isNotEmpty()) "***" else "empty"}")

        try {
            // Check both notifications and chat messages
            val notificationsOk = checkNotifications(serverUrl, rawUsername, password)
            val chatMessagesOk = checkChatMessages(serverUrl, rawUsername, password)
            
            if (notificationsOk || chatMessagesOk) {
                // Reset error counter if at least one check was successful
                if (consecutiveErrors > 0) {
                    consecutiveErrors = 0
                    prefs.edit().putInt(KEY_CONSECUTIVE_ERRORS, 0).apply()
                    Log.d(TAG, "Reset consecutive error counter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            incrementConsecutiveErrors()
        }
    }
    
    private fun incrementConsecutiveErrors() {
        consecutiveErrors++
        prefs.edit().putInt(KEY_CONSECUTIVE_ERRORS, consecutiveErrors).apply()
        Log.d(TAG, "Incremented consecutive errors to $consecutiveErrors")
    }

    private suspend fun checkNotifications(serverUrl: String, username: String, password: String): Boolean {
        // Use static test API URL if in test mode
        val apiUrl = if (TEST_MODE) {
            TEST_API_URL + "?format=json"
        } else {
            "$serverUrl/ocs/v2.php/apps/notifications/api/v2/notifications?format=json"
        }
        
        val credentials = Credentials.basic(username, password)

        Log.d(TAG, "🔍 Checking notifications at: $apiUrl")
        
        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", credentials)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch notifications: ${response.code}")
                incrementConsecutiveErrors()
                return false
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Empty response body")
                return false
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "⏬ Notifications API response:\n$responseBody")
            }

            val json = JSONObject(responseBody)
            val notifications = json.getJSONObject("ocs").getJSONArray("data")
            
            Log.d(TAG, "Received ${notifications.length()} notifications")
            
            if (notifications.length() > 0) {
                val lastNotifId = prefs.getInt(KEY_LAST_NOTIF_ID, -1)
                val currentNotifId = notifications.getJSONObject(0).getInt("notification_id")
                
                Log.d(TAG, "📊 Notification comparison:")
                Log.d(TAG, "   Last notification ID: $lastNotifId")
                Log.d(TAG, "   Current notification ID: $currentNotifId")
                
                if (currentNotifId > lastNotifId) {
                    val notification = notifications.getJSONObject(0)
                    val title = notification.getString("subject")
                    val message = notification.getString("message")
                    
                    Log.d(TAG, "📨 New notification detected:")
                    Log.d(TAG, "   Title: $title")
                    Log.d(TAG, "   Message: $message")
                    
                    showNewMessageNotification(title, message)
                    prefs.edit().putInt(KEY_LAST_NOTIF_ID, currentNotifId).apply()
                    Log.d(TAG, "✅ Updated last notification ID to: $currentNotifId")
                } else {
                    Log.d(TAG, "⏭️ No new notifications, skipping")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notifications", e)
            return false
        }
    }

    private suspend fun checkChatMessages(serverUrl: String, username: String, password: String): Boolean {
        val roomToken = prefs.getString(KEY_LAST_ROOM_TOKEN, null)
        if (roomToken == null) {
            Log.d(TAG, "💬 No room token available - skipping chat message check")
            return true  // Not a failure, just no data yet
        }
        
        val lastChatId = prefs.getInt(KEY_LAST_CHAT_ID, -1)
        
        Log.d(TAG, "💬 Chat message check:")
        Log.d(TAG, "   Room token: $roomToken")
        Log.d(TAG, "   Last chat ID: $lastChatId")
        
        val apiUrl = "$serverUrl/ocs/v2.php/apps/spreed/api/v4/room/$roomToken/messages?format=json&lastKnownMessageId=$lastChatId"
        val credentials = Credentials.basic(username, password)

        val request = Request.Builder()
            .url(apiUrl)
            .header("Authorization", credentials)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch chat messages: ${response.code}")
                return false
            }

            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Empty chat response body")
                return false
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "⏬ Chat API response:\n$responseBody")
            }

            val json = JSONObject(responseBody)
            val messages = json.getJSONObject("ocs").getJSONArray("data")
            
            Log.d(TAG, "Received ${messages.length()} new chat messages")
            
            if (messages.length() > 0) {
                val lastMessage = messages.getJSONObject(messages.length() - 1)
                val newLastId = lastMessage.getInt("id")
                
                Log.d(TAG, "📨 New chat messages detected:")
                Log.d(TAG, "   Number of new messages: ${messages.length()}")
                Log.d(TAG, "   Last message ID: $newLastId")
                
                // Show notification for each new message
                for (i in 0 until messages.length()) {
                    val message = messages.getJSONObject(i)
                    val sender = message.getString("actorDisplayName")
                    val text = message.getString("message")
                    
                    Log.d(TAG, "   Message from $sender: $text")
                    showNewMessageNotification("New message from $sender", text)
                }
                
                prefs.edit().putInt(KEY_LAST_CHAT_ID, newLastId).apply()
                Log.d(TAG, "✅ Updated last chat ID to: $newLastId")
            } else {
                Log.d(TAG, "⏭️ No new chat messages, skipping")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking chat messages", e)
            return false
        }
    }

    private fun showNewMessageNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_CHAT)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}