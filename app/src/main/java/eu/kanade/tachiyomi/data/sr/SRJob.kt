package eu.kanade.tachiyomi.data.sr

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SRJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_SR_PROGRESS) {
            setContentTitle(applicationContext.stringResource(MR.strings.sr_notification_group))
            setContentText(applicationContext.stringResource(MR.strings.sr_notification_processing))
            setSmallIcon(android.R.drawable.ic_media_play)
            setOngoing(true)
        }.build()
        return ForegroundInfo(
            Notifications.ID_SR_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        logcat(LogPriority.INFO) { "SR: SRJob started" }

        val processor = try {
            Injekt.get<SRQueueProcessor>()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: SRQueueProcessor not registered, cannot start SRJob" }
            return Result.failure()
        }

        setForegroundSafely()

        // Only start if not already running
        if (!processor.isRunning) {
            processor.start()
        }

        // Keep the job alive while processor is running
        while (processor.isRunning) {
            delay(1000)
        }

        logcat(LogPriority.INFO) { "SR: SRJob completed" }
        return Result.success()
    }

    companion object {
        private const val TAG = "SRQueue"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<SRJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
