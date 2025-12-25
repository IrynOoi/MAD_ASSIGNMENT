// NotificationManager.kt
package edu.utem.ftmk.slm02

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notification Manager for the Food Allergen Prediction app
 * Handles all types of notifications with Android 13+ permission safety
 */
class NotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID_PROGRESS = "prediction_progress"
        private const val CHANNEL_ID_COMPLETION = "prediction_completion"
        private const val CHANNEL_ID_ERROR = "prediction_error"

        // IDs
        private const val PROGRESS_ID = 1001
        private const val COMPLETION_ID = 1002
        private const val ERROR_ID = 1003
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

            // 1. Progress Channel
            val progressChannel = NotificationChannel(
                CHANNEL_ID_PROGRESS,
                "Prediction Progress",
                AndroidNotificationManager.IMPORTANCE_LOW // Low importance for progress bars
            ).apply {
                description = "Shows prediction progress"
                setShowBadge(false)
            }

            // 2. Completion Channel
            val completionChannel = NotificationChannel(
                CHANNEL_ID_COMPLETION,
                "Prediction Completion",
                AndroidNotificationManager.IMPORTANCE_DEFAULT // Default for results
            ).apply {
                description = "Shows finished predictions"
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
                setShowBadge(true)
            }

            // 3. Error Channel
            val errorChannel = NotificationChannel(
                CHANNEL_ID_ERROR,
                "Prediction Errors",
                AndroidNotificationManager.IMPORTANCE_HIGH // High for errors
            ).apply {
                description = "Shows critical errors"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completionChannel)
            notificationManager.createNotificationChannel(errorChannel)
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    fun showProgressNotification(
        current: Int,
        total: Int,
        currentItem: String = "",
        datasetName: String = ""
    ) {
        if (!hasPermission()) return

        val title = if (datasetName.isNotEmpty()) "Processing $datasetName" else "Processing Dataset"
        val text = "$current/$total: $currentItem"

        // FIX: Use safe public icon 'android.R.drawable.stat_sys_download'
        // FIX: setPriority is safe here because we imported androidx.core.app.NotificationCompat
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, current, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(PROGRESS_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun showCompletionNotification(
        successCount: Int,
        totalCount: Int,
        datasetName: String = "", // FIX: Ensure this is String, not Int
        firebaseSuccess: Int = 0,
        firebaseFailure: Int = 0,
        elapsedTime: Long = 0
    ) {
        if (!hasPermission()) return

        val title = if (datasetName.isNotEmpty()) "✅ $datasetName Complete" else "✅ Prediction Complete"
        val bigText = "Predictions: $successCount/$totalCount successful\nFirebase: $firebaseSuccess saved"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETION)
            .setContentTitle(title)
            .setContentText("Processed $successCount/$totalCount items")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setColor(Color.GREEN)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(COMPLETION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
    fun showErrorNotification(
        errorMessage: String,
        errorType: String = "",
        datasetName: String = ""
    ) {
        if (!hasPermission()) return

        val title = if (errorType.isNotEmpty()) "❌ $errorType" else "❌ Prediction Error"

        // FIX: Use safe public icon 'android.R.drawable.ic_dialog_alert'
        // Replaced 'stat_notify_error' which caused the error
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ERROR)
            .setContentTitle(title)
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setColor(Color.RED)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(ERROR_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun cancelAllNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }
}