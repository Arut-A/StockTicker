package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.model.FetchException
import com.github.premnirmal.ticker.model.FetchResult
import com.github.premnirmal.ticker.network.data.Quote
import com.github.premnirmal.ticker.network.data.SuggestionsNet.SuggestionNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StocksApi @Inject constructor(
    private val appPreferences: AppPreferences,
    private val suggestionApi: SuggestionApi,
    private val moexApi: MoexApi
) {

    suspend fun getSuggestions(query: String): FetchResult<List<SuggestionNet>> =
        withContext(Dispatchers.IO) {
            FetchResult.success<List<SuggestionNet>>(emptyList())
        }

    suspend fun getStocks(tickerList: List<String>): FetchResult<List<Quote>> =
        withContext(Dispatchers.IO) {
            // Parallel fetching using async/await for better performance
            val deferredQuotes = coroutineScope {
                tickerList.map { ticker ->
                    async {
                        try {
                            val q = moexApi.fetchQuote(ticker)
                            if (q != null) {
                                ticker to q
                            } else {
                                Timber.w("MOEX null: $ticker")
                                ticker to null
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "MOEX error: $ticker")
                            // Fail fast: if network is completely down, don't continue
                            if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) {
                                throw e
                            }
                            ticker to null
                        }
                    }
                }
            }

            // Await all results in parallel
            val results = try {
                deferredQuotes.awaitAll()
            } catch (e: Exception) {
                Timber.e(e, "Network error during parallel fetch")
                return@withContext FetchResult.failure(FetchException("Network error: ${e.message}", e))
            }

            // Build map for O(1) lookup instead of O(n²)
            val quoteMap = results.mapNotNull { (ticker, quote) ->
                quote?.let { ticker.uppercase() to it }
            }.toMap()

            if (quoteMap.isEmpty() && tickerList.isNotEmpty()) {
                return@withContext FetchResult.failure(FetchException("All fetches failed"))
            }

            // Maintain original order using map lookup (O(n) instead of O(n²))
            val ordered = tickerList.mapNotNull { ticker ->
                quoteMap[ticker.uppercase()]
            }

            FetchResult.success(ArrayList(ordered))
        }

    suspend fun getStock(ticker: String): FetchResult<Quote> =
        withContext(Dispatchers.IO) {
            try {
                val q = moexApi.fetchQuote(ticker)
                if (q != null) {
                    FetchResult.success(q)
                } else {
                    FetchResult.failure(FetchException("MOEX null for $ticker"))
                }
            } catch (e: Exception) {
                Timber.e(e, "MOEX error: $ticker")
                FetchResult.failure(FetchException("MOEX failed: $ticker", e))
            }
        }
}
