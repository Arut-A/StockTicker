package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.model.FetchException
import com.github.premnirmal.ticker.model.FetchResult
import com.github.premnirmal.ticker.network.data.Quote
import com.github.premnirmal.ticker.network.data.SuggestionsNet.SuggestionNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StocksApi @Inject constructor(
    // Keep these params so Hilt DI doesn't break — they're just unused now
    private val yahooFinanceInitialLoad: YahooFinanceInitialLoad,
    private val yahooFinanceCrumb: YahooFinanceCrumb,
    private val yahooFinance: YahooFinance,
    private val appPreferences: AppPreferences,
    private val suggestionApi: SuggestionApi
) {

    private val moexApi = MoexApi()

    suspend fun getSuggestions(query: String): FetchResult<List<SuggestionNet>> =
        withContext(Dispatchers.IO) {
            // Return empty — user adds MOEX tickers by name directly
            FetchResult.success<List<SuggestionNet>>(emptyList())
        }

    suspend fun getStocks(tickerList: List<String>): FetchResult<List<Quote>> =
        withContext(Dispatchers.IO) {
            val quotes = mutableListOf<Quote>()
            for (ticker in tickerList) {
                try {
                    val q = moexApi.fetchQuote(ticker)
                    if (q != null) {
                        quotes.add(q)
                        Timber.d("OK: ${q.symbol}=${q.lastTradePrice} RUB")
                    } else {
                        Timber.w("MOEX null for $ticker")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "MOEX error: $ticker")
                }
            }
            if (quotes.isEmpty() && tickerList.isNotEmpty()) {
                return@withContext FetchResult.failure(FetchException("All fetches failed"))
            }
            // Return in original order
            val ordered = tickerList.mapNotNull { t ->
                quotes.find { it.symbol.equals(t, ignoreCase = true) }
            }
            FetchResult.success(ArrayList(ordered))
        }

    suspend fun getStock(ticker: String): FetchResult<Quote> =
        withContext(Dispatchers.IO) {
            try {
                val q = moexApi.fetchQuote(ticker)
                if (q != null) {
                    Timber.d("OK: ${q.symbol}=${q.lastTradePrice} RUB")
                    FetchResult.success(q)
                } else {
                    Timber.e("MOEX null for $ticker")
                    FetchResult.failure(FetchException("MOEX returned null for $ticker"))
                }
            } catch (e: Exception) {
                Timber.e(e, "MOEX error: $ticker")
                FetchResult.failure(FetchException("MOEX failed: $ticker", e))
            }
        }
}
