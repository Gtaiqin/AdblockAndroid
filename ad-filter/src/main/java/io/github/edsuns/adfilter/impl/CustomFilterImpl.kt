package io.github.edsuns.adfilter.impl

import io.github.edsuns.adfilter.CustomFilter
import io.github.edsuns.adfilter.util.RuleIterator
import kotlinx.coroutines.launch

/**
 * Created by Edsuns@qq.com on 2021/7/29.
 */
internal class CustomFilterImpl constructor(
    private val filterDataLoader: FilterDataLoader,
    data: String? = null
) : CustomFilter, RuleIterator(data) {

    override fun flush() {
        val blacklistStr = dataBuilder.toString()
        if (blacklistStr.isNotBlank()) {
            val rawData = blacklistStr.toByteArray()
            // flushing writes the raw rules, re-parses them natively and reloads the client;
            // that must not block the caller, which is usually the main thread
            filterDataLoader.scope.launch {
                filterDataLoader.loadCustomFilterAsync(rawData)
            }
        }
    }
}