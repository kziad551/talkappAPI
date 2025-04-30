/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Ibrahim Guler <contact@ibrahimguler.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.nextcloud.talk.activities.MainActivity

/**
 * Service to handle account authentication for the Nextcloud Talk app.
 * It provides functionality to manage Nextcloud Talk accounts within the Android system.
 */
class NextcloudTalkAuthenticatorService : Service() {
    private lateinit var authenticator: NextcloudTalkAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = NextcloudTalkAuthenticator(this)
    }

    override fun onBind(intent: Intent): IBinder {
        return authenticator.iBinder
    }

    /**
     * Authenticator implementation for Nextcloud Talk accounts.
     * Handles adding accounts and retrieving authentication tokens.
     */
    private class NextcloudTalkAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
        private val TAG = "NextcloudAuthenticator"

        override fun editProperties(
            response: AccountAuthenticatorResponse,
            accountType: String
        ): Bundle {
            Log.d(TAG, "editProperties called for account type: $accountType")
            return Bundle()
        }

        /**
         * Called when the user adds a new account. Redirects to login activity.
         */
        override fun addAccount(
            response: AccountAuthenticatorResponse,
            accountType: String,
            authTokenType: String?,
            requiredFeatures: Array<String>?,
            options: Bundle?
        ): Bundle {
            Log.d(TAG, "addAccount called for account type: $accountType")
            
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            }
            
            return Bundle().apply {
                putParcelable(AccountManager.KEY_INTENT, intent)
            }
        }

        /**
         * Checks if the stored credentials are valid.
         */
        override fun confirmCredentials(
            response: AccountAuthenticatorResponse,
            account: Account,
            options: Bundle?
        ): Bundle {
            Log.d(TAG, "confirmCredentials called for account: ${account.name}")
            return Bundle()
        }

        /**
         * Gets an auth token for an existing account or redirects to login activity.
         */
        override fun getAuthToken(
            response: AccountAuthenticatorResponse,
            account: Account,
            authTokenType: String,
            options: Bundle?
        ): Bundle {
            Log.d(TAG, "getAuthToken called for account: ${account.name}")
            
            val accountManager = AccountManager.get(context)
            val password = accountManager.getPassword(account)
            
            if (password != null) {
                return Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                    putString(AccountManager.KEY_AUTHTOKEN, password)
                }
            }
            
            // If we get here, we need to ask the user for their password
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            }
            
            return Bundle().apply {
                putParcelable(AccountManager.KEY_INTENT, intent)
            }
        }

        override fun getAuthTokenLabel(authTokenType: String): String {
            return "Nextcloud Talk Auth Token"
        }

        override fun updateCredentials(
            response: AccountAuthenticatorResponse,
            account: Account,
            authTokenType: String?,
            options: Bundle?
        ): Bundle {
            Log.d(TAG, "updateCredentials called for account: ${account.name}")
            return Bundle()
        }

        override fun hasFeatures(
            response: AccountAuthenticatorResponse,
            account: Account,
            features: Array<String>
        ): Bundle {
            // This app doesn't use account features
            return Bundle().apply {
                putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
            }
        }
    }
} 