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

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging
import moe.kirao.mgx.MoexConfig
import org.thunderdog.challegram.BuildConfig
import org.thunderdog.challegram.telegram.TdlibManager
import org.thunderdog.challegram.telegram.TdlibNotificationUtils
import org.thunderdog.challegram.unsorted.Settings
import org.unifiedpush.android.connector.UnifiedPush
import tgx.bridge.DeviceTokenRetriever
import tgx.bridge.PushManagerBridge
import tgx.extension.TelegramXExtension

/**
 * Chooses push service according to moeGramX settings: Firebase, UnifiedPush or automatic choice.
 */
object PushServiceSelector {
  /**
   * Set when Firebase failed to provide a token in automatic mode,
   * e.g. because Firebase project doesn't allow this build's signature.
   */
  @Volatile
  private var firebaseFailed = false

  @JvmStatic
  fun isFirebaseAvailable(context: Context): Boolean =
    FirebaseDeviceTokenRetriever().isAvailable(context)

  @JvmStatic
  fun isUnifiedPushAvailable(context: Context): Boolean =
    UnifiedPushTokenRetriever.isDistributorAvailable(context)

  @JvmStatic
  fun createTokenRetriever(context: Context): DeviceTokenRetriever {
    val firebase = FirebaseDeviceTokenRetriever()
    when (MoexConfig.instance().pushService) {
      MoexConfig.PUSH_SERVICE_FIREBASE -> return firebase
      MoexConfig.PUSH_SERVICE_UNIFIEDPUSH -> {
        if (isUnifiedPushAvailable(context)) {
          return UnifiedPushTokenRetriever()
        }
        // Distributor was removed, fall back to automatic choice
      }
    }
    val preferAlternative =
      Settings.instance().isExperimentEnabled(Settings.EXPERIMENT_FLAG_FORCE_ALTERNATIVE_PUSH_SERVICE) ||
      !firebase.isAvailable(context)
    val extensionRetriever = TelegramXExtension.createNewTokenRetriever(context)
    if (extensionRetriever != null && !BuildConfig.EXPERIMENTAL && preferAlternative) {
      return extensionRetriever
    }
    if ((preferAlternative || firebaseFailed) && isUnifiedPushAvailable(context)) {
      return UnifiedPushTokenRetriever()
    }
    return firebase
  }

  /**
   * Disables services that are not used, so they don't wake the app up in vain.
   */
  @JvmStatic
  fun configure(application: Application, retriever: DeviceTokenRetriever) {
    if (BuildConfig.EXPERIMENTAL) {
      return
    }
    TelegramXExtension.configure(application, retriever)
    setFirebaseAutoInitEnabled(retriever is FirebaseDeviceTokenRetriever)
  }

  private fun setFirebaseAutoInitEnabled(enabled: Boolean) {
    try {
      FirebaseMessaging.getInstance().isAutoInitEnabled = enabled
    } catch (e: Throwable) {
      // Firebase may be not initialized on devices without Google Play Services
    }
  }

  /**
   * Applies changed push service setting without restarting the app.
   */
  @JvmStatic
  fun onPushServiceChanged(application: Application) {
    val oldRetriever = TdlibNotificationUtils.getDeviceTokenRetriever()
    TdlibNotificationUtils.resetDeviceTokenRetriever()
    val newRetriever = TdlibNotificationUtils.getDeviceTokenRetriever()
    PushManagerBridge.log("Push service changed: %s -> %s", oldRetriever.name, newRetriever.name)
    if (oldRetriever.name == newRetriever.name) {
      return
    }
    configure(application, newRetriever)
    if (oldRetriever is FirebaseDeviceTokenRetriever) {
      // Invalidate FCM token, so Telegram stops sending pushes there
      try {
        FirebaseMessaging.getInstance().deleteToken()
      } catch (e: Throwable) {
        PushManagerBridge.error("Unable to delete Firebase token", e)
      }
    } else if (oldRetriever is UnifiedPushTokenRetriever) {
      try {
        UnifiedPush.unregister(application, UnifiedPushTokenRetriever.INSTANCE)
      } catch (e: Throwable) {
        PushManagerBridge.error("Unable to unregister from UnifiedPush", e)
      }
    }
    TdlibManager.instance().refreshDeviceToken()
  }

  /**
   * Called when push token couldn't be obtained.
   *
   * @return true if another push service will be tried instead
   */
  @JvmStatic
  fun onTokenRetrievalFailed(application: Application, errorKey: String): Boolean {
    val retriever = TdlibNotificationUtils.getDeviceTokenRetriever()
    if (retriever is FirebaseDeviceTokenRetriever &&
      MoexConfig.instance().pushService == MoexConfig.PUSH_SERVICE_AUTO &&
      !firebaseFailed &&
      isUnifiedPushAvailable(application)) {
      PushManagerBridge.log("Firebase failed (%s), falling back to UnifiedPush", errorKey)
      firebaseFailed = true
      // Posted to avoid re-entering token request that is being finished
      Handler(Looper.getMainLooper()).post {
        onPushServiceChanged(application)
      }
      return true
    }
    return false
  }

  @JvmStatic
  fun currentServiceName(): String =
    TdlibNotificationUtils.getDeviceTokenRetriever().name
}
