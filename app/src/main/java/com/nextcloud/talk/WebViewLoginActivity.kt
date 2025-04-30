package com.nextcloud.talk

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.os.Bundle
import android.util.Log

class WebViewLoginActivity : Activity() {
    private val TAG = "WebViewLoginActivity"

    private fun saveAccount(serverUrl: String, username: String, password: String) {
        val account = Account("$username@${serverUrl.removePrefix("https://")}", "com.nextcloud.talk")
        val am = AccountManager.get(this)
        
        // Store both the server URL and raw username as user data
        am.addAccountExplicitly(account, password, Bundle().apply {
            putString("server_url", serverUrl)
            putString("raw_username", username)  // Store raw username separately
        })
        
        Log.d(TAG, "✅ Account saved: $username@$serverUrl")
        finish()
    }
} 