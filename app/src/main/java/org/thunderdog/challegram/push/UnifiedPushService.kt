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

import org.drinkless.tdlib.TdApi.DeviceTokenSimplePush
import org.thunderdog.challegram.service.PushProcessor
import org.thunderdog.challegram.tool.UI
import org.thunderdog.challegram.unsorted.Settings
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import tgx.bridge.PushManagerBridge

class UnifiedPushService : PushService() {
  override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
    UI.initApp(applicationContext)
    PushManagerBridge.log("UnifiedPush: new endpoint: %s", endpoint.url)
    if (!UnifiedPushTokenRetriever.onEndpointReceived(endpoint.url)) {
      // Endpoint was changed by the distributor
      PushManagerBridge.onNewToken(this, DeviceTokenSimplePush(endpoint.url))
    }
  }

  override fun onMessage(message: PushMessage, instance: String) {
    UI.initApp(applicationContext)
    val pushId = Settings.instance().newPushId()
    val receivedTime = System.currentTimeMillis()
    PushManagerBridge.log("UnifiedPush: message received, size: %d", message.content.size)
    // Payload of a Simple Push doesn't contain message data, so all accounts are synchronized instead.
    // Processing waits for the sync to finish, so it must not block the main thread.
    val context = applicationContext
    Thread({
      PushProcessor(context).processSyncPush(pushId, receivedTime)
    }, "UnifiedPushProcessor").start()
  }

  override fun onRegistrationFailed(reason: FailedReason, instance: String) {
    PushManagerBridge.log("UnifiedPush: registration failed: %s", reason.name)
    UnifiedPushTokenRetriever.onRegistrationFailed("UNIFIEDPUSH_${reason.name}", null)
  }

  override fun onUnregistered(instance: String) {
    PushManagerBridge.log("UnifiedPush: unregistered by distributor")
  }
}
