package io.github.edsuns.adfilter.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.edsuns.adfilter.AdFilter
import io.github.edsuns.adfilter.impl.AdFilterImpl
import io.github.edsuns.adfilter.impl.Constants.KEY_ALREADY_UP_TO_DATE
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOADED_DATA
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOAD_ETAG
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOAD_URL
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTER_ID
import io.github.edsuns.adfilter.impl.Constants.KEY_RAW_CHECKSUM
import io.github.edsuns.adfilter.impl.FilterWorkCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import timber.log.Timber
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext

internal class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = (AdFilter.get(applicationContext) as AdFilterImpl).binaryDataStore

    override suspend fun doWork(): Result {
        val filterId = inputData.getString(KEY_FILTER_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val checksum = inputData.getString(KEY_RAW_CHECKSUM).orEmpty()
        // Work UUID isolates cancellation/retries from a replacement download of the same filter.
        val dataName = "_${filterId}_$id"
        val context = currentCoroutineContext()
        var completed = false
        try {
            val result = FilterWorkCoordinator.download {
                runInterruptible(Dispatchers.IO) {
                    val etag = store.installedEtag(filterId, url, checksum)
                    FilterDownloader(FilterWorkCoordinator.MAX_SOURCE_BYTES, { context.ensureActive() })
                        .download(url, etag) { write -> store.saveData(dataName, write) }
                }
            }
            context.ensureActive()
            val output = workDataOf(
                KEY_FILTER_ID to filterId,
                KEY_DOWNLOAD_URL to url,
                KEY_DOWNLOADED_DATA to dataName,
                KEY_DOWNLOAD_ETAG to result.etag,
                KEY_ALREADY_UP_TO_DATE to result.notModified
            )
            completed = true
            return Result.success(output)
        } catch (e: IOException) {
            Timber.w(e, "Failed to download filter: $filterId")
            return Result.failure(inputData)
        } finally {
            if (!completed) store.clearData(dataName)
        }
    }
}
