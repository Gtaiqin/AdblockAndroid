package io.github.edsuns.adfilter.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.edsuns.adblockclient.AdBlockClient
import io.github.edsuns.adfilter.AdFilter
import io.github.edsuns.adfilter.impl.AdFilterImpl
import io.github.edsuns.adfilter.impl.Constants.KEY_ALREADY_UP_TO_DATE
import io.github.edsuns.adfilter.impl.Constants.KEY_CHECK_LICENSE
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOADED_DATA
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTERS_COUNT
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTER_ID
import io.github.edsuns.adfilter.impl.Constants.KEY_FILTER_NAME
import io.github.edsuns.adfilter.impl.Constants.KEY_RAW_CHECKSUM
import io.github.edsuns.adfilter.util.Checksum
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOAD_ETAG
import io.github.edsuns.adfilter.impl.Constants.KEY_DOWNLOAD_URL
import io.github.edsuns.adfilter.impl.FilterWorkCoordinator
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import timber.log.Timber

/**
 * Created by Edsuns@qq.com on 2021/1/5.
 */
internal class InstallationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(
    context,
    params
) {
    private val binaryDataStore = (AdFilter.get(applicationContext) as AdFilterImpl).binaryDataStore

    override suspend fun doWork(): Result {
        val context = currentCoroutineContext()
        val downloadedDataName = inputData.getString(KEY_DOWNLOADED_DATA)
        try {
            return FilterWorkCoordinator.process {
                context.ensureActive()
                install { context.ensureActive() }
            }
        } catch (e: IOException) {
            Timber.w(e, "Failed to install filter")
            return Result.failure()
        } finally {
            downloadedDataName?.let { binaryDataStore.clearData(it) }
        }
    }

    private fun install(checkActive: () -> Unit): Result {
        val id = inputData.getString(KEY_FILTER_ID) ?: return Result.failure()
        val rawChecksum = inputData.getString(KEY_RAW_CHECKSUM) ?: return Result.failure()
        val url = inputData.getString(KEY_DOWNLOAD_URL)
        val etag = inputData.getString(KEY_DOWNLOAD_ETAG)
        if (inputData.getBoolean(KEY_ALREADY_UP_TO_DATE, false)) {
            // A 304 is useful only while its installed version still exists.
            if (url == null || etag == null || binaryDataStore.installedEtag(id, url, rawChecksum) != etag) {
                return Result.failure()
            }
            return Result.success(workDataOf(KEY_ALREADY_UP_TO_DATE to true))
        }
        val downloadedDataName = inputData.getString(KEY_DOWNLOADED_DATA) ?: return Result.failure()
        val checkLicense = inputData.getBoolean(KEY_CHECK_LICENSE, false)
        val rawData = binaryDataStore.loadData(downloadedDataName, FilterWorkCoordinator.MAX_SOURCE_BYTES)
        // Keep full text/checksum temporaries out of the native parsing frame.
        val metadata = inspect(rawData, checkLicense)
        if (metadata == null) {
            Timber.w("Filter is invalid: $id")
            return Result.failure()
        }
        checkActive()
        if (binaryDataStore.hasData(id) && metadata.checksum == rawChecksum) {
            if (url != null) binaryDataStore.saveInstalledEtag(id, url, metadata.checksum, etag)
            return Result.success(workDataOf(KEY_FILTER_NAME to metadata.name, KEY_ALREADY_UP_TO_DATE to true))
        }
        // Invalidate before replacement; a crash must never associate old HTTP metadata with new data.
        binaryDataStore.clearData("$id.http")
        val filtersCount = persistFilterData(id, rawData, checkActive)
        if (url != null) binaryDataStore.saveInstalledEtag(id, url, metadata.checksum, etag)
        return Result.success(
            workDataOf(
                KEY_FILTERS_COUNT to filtersCount,
                KEY_FILTER_NAME to metadata.name,
                KEY_RAW_CHECKSUM to metadata.checksum
            )
        )
    }

    private data class Metadata(val name: String?, val checksum: String)

    private fun inspect(rawData: ByteArray, checkLicense: Boolean): Metadata? {
        val text = String(rawData, Charsets.UTF_8)
        val checksum = Checksum(text)
        if (checksum.checksumIn == null && checkLicense && !validateLicense(text)) return null
        if (!checksum.validate()) return null
        // WorkManager Data is small; server-supplied titles must not overflow it.
        return Metadata(extractTitle(text)?.take(512), checksum.checksumCalc)
    }

    private val licenseRegexp = Regex(
        "^\\s*!\\s*licen[sc]e[\\s\\-:]+([\\S ]+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    /**
     * Check if the filter includes a license.
     * Returning false often means that the filter is invalid.
     */
    private fun validateLicense(data: String): Boolean = licenseRegexp.containsMatchIn(data)

    private val titleRegexp = Regex(
        "^\\s*!\\s*title[\\s\\-:]+([\\S ]+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    private fun extractTitle(data: String): String? = titleRegexp.find(data)?.groupValues?.get(1)

    private fun persistFilterData(id: String, rawBytes: ByteArray, checkActive: () -> Unit): Int =
        AdBlockClient(id).use { client ->
            client.loadBasicData(rawBytes, true)
            checkActive()
            val processed = client.getProcessedData()
            checkActive()
            binaryDataStore.saveData(id, processed)
            client.getFiltersCount()
        }
}
