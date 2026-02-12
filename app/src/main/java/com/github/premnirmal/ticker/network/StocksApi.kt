package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.model.FetchException
import com.github.premnirmal.ticker.model.FetchResult
import com.github.premnirmal.ticker.network.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StocksApi @Inject constructor() {

    // Single shared instance — reuses the singleton OkHttpClient inside
    private val moexApi = MoexApi()

    /**
     * Fetch quotes for a list of tickers. Only MOEX is supported now.
     */
    suspend fun getStocks(tickerList: List<String>): FetchResult<List<Quote>> =
        withContext(Dispatchers.IO) {
            val quotes = mutableListOf<Quote>()

            for (ticker in tickerList) {
                if (!MoexApi.isMoexTicker(ticker)) {
                    Timber.w("Non-MOEX ticker requested: $ticker")
                    continue
                }
                try {
                    val q = moexApi.fetchQuote(ticker)
                    if (q != null) quotes.add(q) else Timber.w("MOEX null: $ticker")
                } catch (e: Exception) {
                    Timber.e(e, "MOEX error: $ticker")
                }
            }

            if (quotes.isEmpty() && tickerList.isNotEmpty()) {
                return@withContext FetchResult.failure(FetchException("All fetches failed"))
            }

            // Return in original order
            val ordered = ArrayList<Quote>()
            for (ticker in tickerList) {
                quotes.find { it.symbol.equals(ticker, ignoreCase = true) }?.let { ordered.add(it) }
            }
            FetchResult.success(ordered)
        }

    /**
     * Fetch a single stock quote. Only MOEX supported.
     */
    suspend fun getStock(ticker: String): FetchResult<Quote> =
        withContext(Dispatchers.IO) {
            if (!MoexApi.isMoexTicker(ticker)) {
                return@withContext FetchResult.failure(FetchException("Non-MOEX ticker: $ticker"))
            }
            try {
                val q = moexApi.fetchQuote(ticker)
                if (q != null) {
                    return@withContext FetchResult.success(q)
                }
                return@withContext FetchResult.failure(FetchException("MOEX returned null for $ticker"))
            } catch (e: Exception) {
                Timber.e(e, "MOEX getStock error: $ticker")
                return@withContext FetchResult.failure(FetchException("MOEX failed: $ticker", e))
            }
        }

}
