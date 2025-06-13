package org.distrinet.lanshield.vpnservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import org.distrinet.lanshield.LANShieldBroadcastReceiver
import org.distrinet.lanshield.LANShieldIntentAction
import org.distrinet.lanshield.LANShieldIntentExtra
import org.distrinet.lanshield.LAN_TRAFFIC_DETECTED_CHANNEL_ID
import org.distrinet.lanshield.MainActivity
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.SERVICE_NOTIFICATION_CHANNEL_ID
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.ui.lantraffic.getLanTrafficPerAppRoute
import java.net.InetSocketAddress

class LANShieldNotificationManager(private val context: Context) {

    data class ActiveNotification(
        val notificationId: Int,
        val messageLines: MutableList<String>,
        val notificationBuilder: NotificationCompat.Builder
    )

        private val activeNotifications = mutableMapOf<String, ActiveNotification>()
        private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private var _notificationIdCounter = 2 // Notification ID can not be 1 (https://stackoverflow.com/questions/13062798/)
        private var _intentRequestCodeCounter = 2

        private fun getNewNotificationId() : Int {
            _notificationIdCounter += 1
            return _notificationIdCounter
        }

        private fun getNewIntentRequestCode() : Int {
            _intentRequestCodeCounter += 1
            return _intentRequestCodeCounter
        }

        private fun createUpdatePolicyIntent(packageName: String, packageIsSystem: Boolean, policy: Policy) : Intent{
            return Intent(context, LANShieldBroadcastReceiver::class.java).apply {
                action = LANShieldIntentAction.UPDATE_LAN_POLICY.name
                putExtra("${context.packageName}.${LANShieldIntentExtra.POLICY.name}", policy.name)
                putExtra("${context.packageName}.${LANShieldIntentExtra.PACKAGE_NAME.name}", packageName)
                putExtra("${context.packageName}.${LANShieldIntentExtra.PACKAGE_IS_SYSTEM.name}", packageIsSystem)
            }
        }

        fun dismissNotification(packageName: String) {
            activeNotifications[packageName]?.let {
                val notificationId = it.notificationId
                notificationManager.cancel(notificationId)
                activeNotifications.remove(packageName)
            }
        }

        private fun policyToActionString(policy: Policy) : String {
            return when(policy) {
                Policy.ALLOW -> context.getString(R.string.allowed)
                Policy.BLOCK -> context.getString(R.string.blocked)
                Policy.DEFAULT -> context.getString(R.string.default_x)
            }
        }

        private fun createActiveNotification(packageName: String) : ActiveNotification {
            val packageMetadata = getPackageMetadata(packageName, context)
            val blockIntent = createUpdatePolicyIntent(packageName = packageName, packageIsSystem = packageMetadata.isSystem, policy = Policy.BLOCK)
            val blockPendingIntent = PendingIntent.getBroadcast(context, getNewIntentRequestCode(), blockIntent,PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            val allowIntent = createUpdatePolicyIntent(packageName = packageName, packageIsSystem = packageMetadata.isSystem, policy = Policy.ALLOW)
            val allowPendingIntent = PendingIntent.getBroadcast(context, getNewIntentRequestCode(), allowIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)

            val lanTrafficPerAppIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("${context.packageName}://${getLanTrafficPerAppRoute(packageName)}")
            }
            val lanTrafficPerAppPendingIntent = PendingIntent.getActivity(context, 0, lanTrafficPerAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val packageIcon : Bitmap? = try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(config = Bitmap.Config.ARGB_8888)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }

            val builder = NotificationCompat.Builder(context, LAN_TRAFFIC_DETECTED_CHANNEL_ID)
                .setSmallIcon(R.mipmap.logo_foreground)
                .setLargeIcon(packageIcon)
                .setContentTitle(
                    context.getString(
                        R.string.lan_traffic_from_with_package_name,
                        packageMetadata.packageLabel
                    ))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, context.getString(R.string.block), blockPendingIntent)
                .addAction(0, context.getString(R.string.allow), allowPendingIntent)
                .setContentIntent(lanTrafficPerAppPendingIntent)

            return ActiveNotification(getNewNotificationId(), mutableListOf(), builder)
        }

        private fun buildNotification(activeNotification: ActiveNotification, appliedPolicy: Policy, remotePeer: InetSocketAddress) : Notification {
            val line = context.getString(
                R.string.peer,
                remotePeer.toString().drop(1),
                policyToActionString(appliedPolicy)
            )
            activeNotification.messageLines.add(0, line)

            val inboxStyle = NotificationCompat.InboxStyle()
            activeNotification.messageLines.forEach { inboxStyle.addLine(it) }

            return activeNotification.notificationBuilder
                .setContentText(line)
                .setStyle(inboxStyle)
                .build()

        }

        fun postNotification(
            packageName: String,
            appliedPolicy: Policy,
            remotePeer: InetSocketAddress
        ) {

            val activeNotification = activeNotifications.getOrPut(packageName) { createActiveNotification(packageName) }
            val notification = buildNotification(activeNotification, appliedPolicy, remotePeer)
            notificationManager.notify(activeNotification.notificationId, notification)
        }

    fun createNotificationChannels() {

        val serviceChannel = NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            "LANShield service banner",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows a notification if LANShield is enabled."
        }

        val lanConnectionDetectedChannel = NotificationChannel(
            LAN_TRAFFIC_DETECTED_CHANNEL_ID,
            "LAN traffic detected",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows a notification LANShield detected LAN traffic."
        }

        // Register the channel with the system
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(lanConnectionDetectedChannel)
    }
}