package com.brave.portal

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives push messages. The WordPress plugin sends DATA messages:
 *   title, body, url (portal page to open), channel (classes | reminders | general)
 */
class BraveFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: getString(R.string.app_name)
        val body = message.data["body"] ?: message.notification?.body ?: return
        Notifications.show(
            this, title, body,
            url = message.data[Notifications.EXTRA_URL],
            channelId = Notifications.channelFor(message.data["channel"])
        )
    }

    // Topic based: no token is stored or sent anywhere.
    override fun onNewToken(token: String) = Unit
}
