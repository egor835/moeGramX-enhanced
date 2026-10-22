/*
 * This file is a part of moeGramX
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.push

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.drinkless.tdlib.TdApi.DeviceTokenSimplePush
import org.unifiedpush.android.connector.UnifiedPush
import tgx.bridge.DeviceTokenRetriever
import tgx.bridge.PushManagerBridge
import tgx.bridge.TokenRetrieverListener

/**
 * Push notifications via UnifiedPush (ntfy, NextPush, etc.) for devices without Google Play Services.
 *
 * Telegram servers deliver pushes to the UnifiedPush endpoint as Simple Push,
 * so a push only wakes the app up, and messages are then fetched through the regular connection.
 */
class UnifiedPushTokenRetriever : DeviceTokenRetriever("unifiedpush") {
  override fun isAvailable(context: Context): Boolean =
    isDistributorAvailable(context)

  override fun performInitialization(context: Context): Boolean = true

  override val configuration: String
    get() = "distributor: ${currentDistributor ?: "none"}"

  override fun fetchDeviceToken(context: Context, listener: TokenRetrieverListener) {
    val appContext = context.applicationContext
    mainHandler.post {
      PushManagerBridge.log("UnifiedPush: registering...")
      UnifiedPush.tryUseCurrentOrDefaultDistributor(appContext) { success ->
        if (!success) {
          // No default distributor: pick the first installed one (usually there's only one, e.g. ntfy)
          val distributor = UnifiedPush.getDistributors(appContext).firstOrNull()
          if (distributor == null) {
            PushManagerBridge.log("UnifiedPush: no distributor installed")
            listener.onTokenRetrievalError("UNIFIEDPUSH_NO_DISTRIBUTOR", null)
            return@tryUseCurrentOrDefaultDistributor
          }
          UnifiedPush.saveDistributor(appContext, distributor)
        }
        currentDistributor = UnifiedPush.getSavedDistributor(appContext)
        PushManagerBridge.log("UnifiedPush: using distributor %s", currentDistributor ?: "unknown")
        addPendingListener(listener)
        try {
          UnifiedPush.register(appContext, INSTANCE)
        } catch (e: Throwable) {
          PushManagerBridge.error("UnifiedPush: registration failed", e)
          onRegistrationFailed("UNIFIEDPUSH_REGISTRATION_ERROR", e)
        }
      }
    }
  }

  companion object {
    const val INSTANCE = "default"
    private const val REGISTRATION_TIMEOUT_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingListeners = mutableListOf<TokenRetrieverListener>()
    private val timeoutRunnable = Runnable {
      onRegistrationFailed("UNIFIEDPUSH_TIMEOUT", null)
    }

    @Volatile
    private var currentDistributor: String? = null

    @JvmStatic
    fun isDistributorAvailable(context: Context): Boolean =
      try {
        UnifiedPush.getDistributors(context).isNotEmpty()
      } catch (e: Throwable) {
        false
      }

    private fun addPendingListener(listener: TokenRetrieverListener) {
      synchronized(pendingListeners) {
        pendingListeners.add(listener)
      }
      mainHandler.removeCallbacks(timeoutRunnable)
      mainHandler.postDelayed(timeoutRunnable, REGISTRATION_TIMEOUT_MS)
    }

    private fun takePendingListeners(): List<TokenRetrieverListener> {
      mainHandler.removeCallbacks(timeoutRunnable)
      synchronized(pendingListeners) {
        val listeners = pendingListeners.toList()
        pendingListeners.clear()
        return listeners
      }
    }

    /**
     * @return true if the endpoint was delivered to a pending token request
     */
    @JvmStatic
    fun onEndpointReceived(endpoint: String): Boolean {
      val listeners = takePendingListeners()
      if (listeners.isEmpty()) {
        return false
      }
      val token = DeviceTokenSimplePush(endpoint)
      mainHandler.post {
        for (listener in listeners) {
          listener.onTokenRetrievalSuccess(token)
        }
      }
      return true
    }

    @JvmStatic
    fun onRegistrationFailed(errorKey: String, e: Throwable?) {
      val listeners = takePendingListeners()
      mainHandler.post {
        for (listener in listeners) {
          listener.onTokenRetrievalError(errorKey, e)
        }
      }
    }
  }
}
