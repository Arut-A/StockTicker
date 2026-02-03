package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.model.FetchException
import com.github.premnirmal.ticker.model.FetchResult
import com.github.premnirmal.ticker.network.data.Quote
import com.github.premnirmal.ticker.network.data.SuggestionsNet.SuggestionNet
import com.github.premnirmal.ticker.network.data.YahooQuoteNet
import com.github.premnirmal.ticker.network.data.YahooResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by premnirmal on 3/3/16.
 */
@Singleton
class StocksApi @Inject constructor(
    private val yahooFinanceInitialLoad: YahooFinanceInitialLoad,
    private val yahooFinanceCrumb: YahooFinanceCrumb,
    private val yahooFinance: YahooFinance,
    private val appPreferences: AppPreferences,
    private val suggestionApi: SuggestionApi
) {

    val csrfTokenMatchPattern by lazy {
        Regex("csrfToken\\" value=\\"(.+)\\">")
    }

    private suspend fun loadCrumb() {
        withContext(Dispatchers.IO) {
            try {
                val initialLoad = yahooFinanceInitialLoad.initialLoad()
                val html = initialLoad.body() ?: ""
                val url = initialLoad.raw().request.url.toString()
                val match = csrfTokenMatchPattern.find(html)
                if (!match?.groupValues.isNullOrEmpty()) {
                    val csrfToken = match?.groupValues?.last().toString()
                    val sessionId = url.split("=").last()

                    val requestBody = FormBody.Builder()
                        .add("csrfToken", csrfToken)
                        .add("sessionId", sessionId)
                        .addEncoded("originalDoneUrl", "https://finance.yahoo.com/?guccounter=1")
                        .add("namespace", "yahoo")
                        .add("agree", "agree")
                        .build()

                    val cookieConsent = yahooFinanceInitialLoad.cookieConsent(url, requestBody)
                    if (!cookieConsent.isSuccessfu
