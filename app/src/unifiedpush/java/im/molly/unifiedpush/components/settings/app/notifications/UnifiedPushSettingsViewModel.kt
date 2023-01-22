package im.molly.unifiedpush.components.settings.app.notifications

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import im.molly.unifiedpush.events.UnifiedPushRegistrationEvent
import im.molly.unifiedpush.model.FetchStrategy
import im.molly.unifiedpush.model.UnifiedPushStatus
import im.molly.unifiedpush.model.saveStatus
import im.molly.unifiedpush.util.MollySocketRequest
import im.molly.unifiedpush.util.UnifiedPushHelper
import org.greenrobot.eventbus.Subscribe
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.concurrent.SerialMonoLifoExecutor
import org.thoughtcrime.securesms.util.livedata.Store
import org.unifiedpush.android.connector.UnifiedPush

class UnifiedPushSettingsViewModel(private val application: Application) : ViewModel() {

  private val TAG = Log.tag(UnifiedPushSettingsViewModel::class.java)
  private val store = Store(getState())
  private var status: UnifiedPushStatus? = null
  private val EXECUTOR = SerialMonoLifoExecutor(SignalExecutors.UNBOUNDED)

  val state: LiveData<UnifiedPushSettingsState> = store.stateLiveData

  @Subscribe
  fun onNewEndpoint(e: UnifiedPushRegistrationEvent) {
    processNewStatus()
  }

  private fun getState(): UnifiedPushSettingsState {
    status ?: run { status = SignalStore.unifiedpush().status }
    val distributor = UnifiedPush.getDistributor(application)
    var count = -1
    var selected = -1

    var distributors = UnifiedPush.getDistributors(application).map {
      count++
      if (it == distributor) {
        selected = count
      }
      Distributor(
        applicationId = it,
        name = try {
          val ai = application.packageManager.getApplicationInfo(it, 0)
          /* When Android 13 will be supported:
          val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getApplicationInfo(it,
              PackageManager.ApplicationInfoFlags.of(
                PackageManager.GET_META_DATA.toLong()
              )
            )
          } else {
            application.packageManager.getApplicationInfo(it, 0)
          }*/
          application.packageManager.getApplicationLabel(ai)
        } catch (e: PackageManager.NameNotFoundException) {
          it
        } as String
      )
    }

    if (distributors.isEmpty()) {
      distributors = listOf(
        Distributor(
          name = application.getString(R.string.UnifiedPushSettingsViewModel__no_distributor),
          applicationId = "",
        )
      )
      status = UnifiedPushStatus.NO_DISTRIBUTOR
    }

    return UnifiedPushSettingsState(
      enabled = SignalStore.unifiedpush().enabled,
      airGaped = SignalStore.unifiedpush().airGaped,
      device = SignalStore.unifiedpush().device,
      distributors = distributors,
      selected = selected,
      endpoint = SignalStore.unifiedpush().endpoint,
      mollySocketUrl = SignalStore.unifiedpush().mollySocketUrl,
      mollySocketOk = SignalStore.unifiedpush().mollySocketFound,
      fetchStrategy = SignalStore.unifiedpush().fetchStrategy,
      status = status ?: SignalStore.unifiedpush().status,
    )
  }

  fun setUnifiedPushEnabled(enabled: Boolean) {
    SignalStore.unifiedpush().enabled = enabled
    if (enabled) {
      UnifiedPush.getDistributors(application).getOrNull(0)?.let {
        status = UnifiedPushStatus.PENDING
        store.update { getState() }
        EXECUTOR.enqueue {
          UnifiedPush.saveDistributor(application, it)
          UnifiedPush.registerApp(application)
          UnifiedPushHelper.initializeMollySocketLinkedDevice()
          processNewStatus()
        }
        // Do not enable if there is no distributor
      } ?: return
    } else {
      UnifiedPush.unregisterApp(application)
      processNewStatus()
    }
  }

  fun setUnifiedPushAirGaped(airGaped: Boolean) {
    SignalStore.unifiedpush().airGaped = airGaped
    processNewStatus()
  }

  fun setFetchStrategy(strategy: FetchStrategy) {
    SignalStore.unifiedpush().fetchStrategy = strategy
    processNewStatus()
  }

  fun setUnifiedPushDistributor(distributor: String) {
    UnifiedPush.saveDistributor(application, distributor)
    UnifiedPush.registerApp(application)
    store.update { getState() }
  }

  fun setMollySocketUrl(url: String?) {
    SignalStore.unifiedpush().mollySocketUrl = if (url.isNullOrBlank()) {
      null
    } else if (url.last() != '/') {
      "$url/"
    } else {
      url
    }
    processNewStatus()
  }

  private fun restartServiceIfNeeded() {
    ApplicationContext.getInstance().initializeFcmCheck()
    if (
      (UnifiedPushHelper.pushRequireForeground() && !ApplicationDependencies.getIncomingMessageObserver().isForegroundService) ||
      (!UnifiedPushHelper.pushRequireForeground() && ApplicationDependencies.getIncomingMessageObserver().isForegroundService)
    ) {
      Log.d(TAG, "Restarting foreground service")
      ApplicationDependencies.getIncomingMessageObserver().stopForegroundService()
      ApplicationDependencies.closeConnections()
      ApplicationDependencies.getIncomingMessageObserver()
    }
  }

  private fun processNewStatus() {
    status = SignalStore.unifiedpush().status
    if (SignalStore.unifiedpush().status in listOf(
        UnifiedPushStatus.OK,
        UnifiedPushStatus.FORBIDDEN_UUID,
        UnifiedPushStatus.INTERNAL_ERROR,
        UnifiedPushStatus.SERVER_NOT_FOUND_AT_URL,
      )
    ) {
      Log.d(TAG, "Trying to register to MollySocket")
      status = UnifiedPushStatus.PENDING
      EXECUTOR.enqueue {
        try {
          if (MollySocketRequest.discoverMollySocketServer()) {
            SignalStore.unifiedpush().mollySocketFound = true
            MollySocketRequest.registerToMollySocketServer().saveStatus()
            status = SignalStore.unifiedpush().status
            restartServiceIfNeeded()
          } else {
            SignalStore.unifiedpush().mollySocketFound = false
            status = SignalStore.unifiedpush().status
          }
        } catch (e: Exception) {
          SignalStore.unifiedpush().mollySocketFound = false
          status = UnifiedPushStatus.INTERNAL_ERROR
        }
        store.update { getState() }
      }
    } else {
      status = SignalStore.unifiedpush().status
      restartServiceIfNeeded()
    }
    store.update { getState() }
  }

  class Factory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(UnifiedPushSettingsViewModel(application)))
    }
  }
}
