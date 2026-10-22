/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 05/04/2015 at 08:53
 */
package org.thunderdog.challegram

import android.content.Context
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import org.thunderdog.challegram.push.PushServiceSelector
import org.thunderdog.challegram.service.PushHandler
import org.thunderdog.challegram.telegram.TdlibNotificationUtils
import org.thunderdog.challegram.tool.UI
import tgx.bridge.DeviceTokenRetriever
import tgx.bridge.DeviceTokenRetrieverFactory
import tgx.bridge.PushManagerBridge
import tgx.flavor.TgxApplication

class BaseApplication : TgxApplication(), Configuration.Provider {
  companion object {
    lateinit var scope: CoroutineScope
  }

  override fun onCreate() {
    super.onCreate()
    scope = MainScope()

    PushManagerBridge.initialize(
      scope,

      PushHandler(),
      object : DeviceTokenRetrieverFactory {
        override fun onCreateNewTokenRetriever(context: Context): DeviceTokenRetriever =
          PushServiceSelector.createTokenRetriever(context)
      }
    )

    UI.initApp(applicationContext)

    PushServiceSelector.configure(this, TdlibNotificationUtils.getDeviceTokenRetriever())
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().build()
}