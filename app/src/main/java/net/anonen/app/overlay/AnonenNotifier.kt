package net.anonen.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import net.anonen.app.R
import net.anonen.app.ui.MainActivity

class AnonenNotifier(private val context: Context) {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun serviceNotification(
        showCancelRecording: Boolean = false,
        showCancelTranscribe: Boolean = false,
    ): Notification {
        val intent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(context, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_service_title))
                .setContentIntent(intent)
                .setOngoing(true)
        if (showCancelRecording) {
            builder.addAction(
                serviceAction(
                    requestCode = 2,
                    intent = FloatingButtonService.cancelRecordingIntent(context),
                    label = context.getString(R.string.notif_recording_cancel_action),
                ),
            )
        }
        if (showCancelTranscribe) {
            builder.addAction(
                serviceAction(
                    requestCode = 1,
                    intent = FloatingButtonService.cancelTranscribeIntent(context),
                    label = context.getString(R.string.notif_transcribe_cancel_action),
                ),
            )
        }
        return builder.build()
    }

    private fun serviceAction(
        requestCode: Int,
        intent: Intent,
        label: String,
    ): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification),
            label,
            PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE),
        ).build()

    fun canPost(): Boolean =
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_RESULT)?.importance != NotificationManager.IMPORTANCE_NONE

    fun notifyRecordingDiscarded(
        undoable: Boolean,
        timeoutMs: Long,
    ) {
        val builder =
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(OverlayFeedback.of(FeedbackEvent.RECORDING_DISCARDED).text)
                .setAutoCancel(true)
                .setTimeoutAfter(timeoutMs)
        if (undoable) {
            builder.addAction(
                serviceAction(
                    requestCode = 3,
                    intent = FloatingButtonService.undoDiscardIntent(context),
                    label = FeedbackAction.UNDO_DISCARD.label.orEmpty(),
                ),
            )
        }
        manager.notify(DISCARDED_NOTIFICATION_ID, builder.build())
    }

    fun cancelRecordingDiscarded() {
        manager.cancel(DISCARDED_NOTIFICATION_ID)
    }

    fun notifyTranscribeCancelled(audioSavedToHistory: Boolean) {
        notifyResult(OverlayFeedback.of(FeedbackEvent.TRANSCRIBE_CANCELLED, audioKept = audioSavedToHistory).text)
    }

    fun notifyResult(
        title: String,
        text: String? = null,
    ) {
        val builder =
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setAutoCancel(true)
        if (text != null) {
            builder.setContentText(text)

            builder.setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    fun notifyWarning(
        title: String,
        text: String? = null,
    ) {
        val builder =
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setAutoCancel(true)
        if (text != null) {
            builder.setContentText(text)
            builder.setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager.notify(WARNING_NOTIFICATION_ID, builder.build())
    }

    fun notifyModelRetired(
        switchTargetId: String?,
        switchTargetName: String?,
        detail: String? = null,
    ) {
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (detail != null) {
                        context.getString(R.string.notif_cloud_model_not_switched_title)
                    } else {
                        OverlayFeedback.of(FeedbackEvent.MODEL_RETIRED).text
                    },
                )
                .setContentIntent(openApp)
                .setAutoCancel(true)
        if (detail != null) {
            builder.setContentText(detail)
            builder.setStyle(Notification.BigTextStyle().bigText(detail))
        }
        if (switchTargetId != null) {
            val switch =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    CloudModelSwitchReceiver.intent(context, switchTargetId, switchTargetName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_notification),
                    context.getString(
                        R.string.notif_cloud_model_switch_action,
                        switchTargetName ?: switchTargetId,
                    ),
                    switch,
                ).build(),
            )
        }
        manager.notify(MODEL_RETIRED_NOTIFICATION_ID, builder.build())
    }

    fun notifyNoModelSelected(detail: String? = null) {
        val chooseModel =
            PendingIntent.getActivity(
                context,
                CHOOSE_MODEL_REQUEST_CODE,
                MainActivity.chooseModelIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(OverlayFeedback.of(FeedbackEvent.NO_MODEL).text)
                .setContentIntent(chooseModel)
                .setAutoCancel(true)

        if (detail != null) {
            builder.setContentText(detail)
            builder.setStyle(Notification.BigTextStyle().bigText(detail))
        }
        manager.notify(MODEL_RETIRED_NOTIFICATION_ID, builder.build())
    }

    fun notifyAccessRequired() {
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            WARNING_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(OverlayFeedback.of(FeedbackEvent.ACCESS_REQUIRED).text)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun notifyButtonStopped() {
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            WARNING_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(OverlayFeedback.of(FeedbackEvent.BUTTON_STOPPED).text)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun notifyModelNotSwitched(text: String) {
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            MODEL_RETIRED_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_cloud_model_not_switched_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun notifyModelSwitched(displayName: String) {
        manager.notify(
            MODEL_RETIRED_NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_cloud_model_switched, displayName))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2
        private const val WARNING_NOTIFICATION_ID = 3
        private const val MODEL_RETIRED_NOTIFICATION_ID = 4
        private const val DISCARDED_NOTIFICATION_ID = 5

        private const val CHOOSE_MODEL_REQUEST_CODE = 6
        private const val CHANNEL_SERVICE = "service"
        private const val CHANNEL_RESULT = "result"

        fun serviceActionsVisible(context: Context): Boolean {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_SERVICE)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
