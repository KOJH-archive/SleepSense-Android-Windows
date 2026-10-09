package kr.sleepsense.app.media

import android.service.notification.NotificationListenerService
import kr.sleepsense.app.graph

/** Intentionally does not override notification-posted callbacks or read notification text. */
class MediaAccessListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); graph.media.connect() }
    override fun onListenerDisconnected() { graph.media.disconnect(); super.onListenerDisconnected() }
    override fun onDestroy() { graph.media.disconnect(); super.onDestroy() }
}
