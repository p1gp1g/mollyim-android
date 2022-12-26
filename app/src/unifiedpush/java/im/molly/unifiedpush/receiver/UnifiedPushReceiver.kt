package im.molly.unifiedpush.receiver

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import im.molly.unifiedpush.components.settings.app.notifications.BROADCAST_NEW_ENDPOINT
import im.molly.unifiedpush.model.FetchStrategy
import im.molly.unifiedpush.model.UnifiedPushStatus
import im.molly.unifiedpush.model.saveStatus
import im.molly.unifiedpush.util.MollySocketRequest
import im.molly.unifiedpush.util.UnifiedPushHelper
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.gcm.FcmFetchManager
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.unifiedpush.android.connector.MessagingReceiver
import java.util.Timer
import kotlin.concurrent.schedule

class UnifiedPushReceiver : MessagingReceiver() {
  private val TAG = Log.tag(UnifiedPushReceiver::class.java)
  private val TIMEOUT = 20_000L // 20secs
  private val WAKE_LOCK_TAG = "${UnifiedPushReceiver::class.java}::wake_lock"

  override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
    Log.d(TAG, "New endpoint: $endpoint")
    if (SignalStore.unifiedpush().endpoint != endpoint) {
      SignalStore.unifiedpush().endpoint = endpoint
      when (SignalStore.unifiedpush().status) {
        UnifiedPushStatus.AIR_GAPED -> {
          // TODO: alert if air gaped and endpoint changes
          LocalBroadcastManager.getInstance(context).sendBroadcast(Intent().apply { action = BROADCAST_NEW_ENDPOINT })
        }
        in listOf(
          UnifiedPushStatus.INTERNAL_ERROR,
          UnifiedPushStatus.MISSING_ENDPOINT,
          UnifiedPushStatus.OK,
        ) -> {
          Thread {
            MollySocketRequest.registerToMollySocketServer().saveStatus()
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent().apply { action = BROADCAST_NEW_ENDPOINT })
            // TODO: alert if status changes from Ok to something else
          }.start()
        }
        else -> {
          LocalBroadcastManager.getInstance(context).sendBroadcast(Intent().apply { action = BROADCAST_NEW_ENDPOINT })
        }
      }
    }
  }

  override fun onRegistrationFailed(context: Context, instance: String) {
    // called when the registration is not possible, eg. no network
    // TODO: alert user the registration has failed
  }

  override fun onUnregistered(context: Context, instance: String) {
    // called when this application is unregistered from receiving push messages
    // isPushAvailable becomes false => The websocket starts
    SignalStore.unifiedpush().endpoint = null
  }

  override fun onMessage(context: Context, message: ByteArray, instance: String) {
    if (UnifiedPushHelper.isUnifiedPushAvailable()) {
      when (SignalStore.unifiedpush().fetchStrategy) {
        FetchStrategy.WEBSOCKET -> messageWebSocket()
        FetchStrategy.REST -> messageRest(context)
      }
      Log.d(TAG, "New message")
    }
  }

  fun messageRest(context: Context) {
    Thread {
      val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
        newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
          acquire(5000L /*5secs*/)
        }
      }
      val enqueueSuccessful = try {
        if (Build.VERSION.SDK_INT >= 31) {
          FcmFetchManager.enqueue(context, true)
        } else {
          FcmFetchManager.enqueue(context, false)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to start service.", e)
        false
      }
      if (!enqueueSuccessful) {
        Log.w(TAG, "Unable to start service. Falling back to legacy approach.")
        FcmFetchManager.retrieveMessages(context)
      }
      wakeLock?.let {
        if (it.isHeld) {
          it.release()
        }
      }
    }.start()
  }

  private fun messageWebSocket() {
    ApplicationDependencies.getIncomingMessageObserver().registerKeepAliveToken(UnifiedPushReceiver::class.java.name)
    Timer().schedule(TIMEOUT) {
      ApplicationDependencies.getIncomingMessageObserver().removeKeepAliveToken(UnifiedPushReceiver::class.java.name)
    }
  }
}
