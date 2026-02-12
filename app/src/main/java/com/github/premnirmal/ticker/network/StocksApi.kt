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

@Singleton
class StocksApi @Inject constructor(
    private val yahooFinanceInitialLoad: YahooFinanceInitialLoad,
    private val yahooFinanceCrumb: YahooFinanceCrumb,
    private val yahooFinance: YahooFinance,
    private val appPreferences: AppPreferences,
    private val suggestionApi: SuggestionApi
) {

    // Single shared instance — reuses the singleton OkHttpClient inside
    private val moexApi = MoexApi()

    val csrfTokenMatchPattern by lazy {
        Regex("\"csrfToken\":\"(.+)\"")
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
                    if (!cookieConsent.isSuccessful) {
                        Timber.e("Failed cookie consent: ${cookieConsent.code()}")
                    }
                }
                val crumbResponse = yahooFinanceCrumb.getCrumb()
                if (crumbResponse.isSuccessful) {
                    val crumb = crumbResponse.body()
                    if (!crumb.isNullOrEmpty()) appPreferences.setCrumb(crumb)
                }
            } catch (e: Exception) {
                Timber.e(e, "Crumb load failed")
            }
        }
    }

    suspend fun getSuggestions(query: String): FetchResult<List<SuggestionNet>> =
        withContext(Dispatchers.IO) {
            try {
                val suggestions = suggestionApi.getSuggestions(query).result
                val list = suggestions?.let { ArrayList(it) } ?: ArrayList()
                FetchResult.success<List<SuggestionNet>>(list)
            } catch (e: Exception) {
                Timber.e(e)
                FetchResult.failure(FetchException("Error fetching suggestions", e))
            }
        }

    /**
     * Fetch quotes for a list of tickers.
     * MOEX and Yahoo are completely isolated — one cannot crash the other.
     */
    suspend fun getStocks(tickerList: List<String>): FetchResult<List<Quote>> =
        withContext(Dispatchers.IO) {
            val quotes = mutableListOf<Quote>()

            val moexTickers = tickerList.filter { MoexApi.isMoexTicker(it) }
            val yahooTickers = tickerList.filter { !MoexApi.isMoexTicker(it) }

            // --- MOEX: each ticker isolated ---
            for (ticker in moexTickers) {
                try {
                    val q = moexApi.fetchQuote(ticker)
                    if (q != null) quotes.add(q) else Timber.w("MOEX null: $ticker")
                } catch (e: Exception) {
                    Timber.e(e, "MOEX error: $ticker")
                }
            }

            // --- Yahoo: batch fetch, isolated from MOEX ---
            if (yahooTickers.isNotEmpty()) {
                try {
                    val nets = getStocksYahoo(yahooTickers)
                    if (nets != null) {
                        for (net in nets) {
                            quotes.add(net.toQuote())
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Yahoo batch error")
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
     * Fetch a single stock quote.
     * MOEX tickers go ONLY to MOEX, never Yahoo.
     */
    suspend fun getStock(ticker: String): FetchResult<Quote> =
        withContext(Dispatchers.IO) {
            if (MoexApi.isMoexTicker(ticker)) {
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

            // Yahoo path
            try {
                val nets = getStocksYahoo(listOf(ticker))
                    ?: return@withContext FetchResult.failure(FetchException("Yahoo null for $ticker"))
                FetchResult.success(nets.first().toQuote())
            } catch (e: Exception) {
                Timber.e(e)
                FetchResult.failure(FetchException("Yahoo failed: $ticker", e))
            }
        }

    private suspend fun getStocksYahoo(
        tickerList: List<String>,
        retry: Int = 1
    ): List<YahooQuoteNet>? = withContext(Dispatchers.IO) {
        val query = tickerList.joinToString(",")
        try {
            val resp = yahooFinance.getStocks(query)
            if (resp.code() == 401 && retry == 1) {
                appPreferences.setCrumb(null)
                loadCrumb()
                return@withContext getStocksYahoo(tickerList, retry = 2)
            }
            resp.body()?.quoteResponse?.result
        } catch (e: Exception) {
            Timber.e(e)
            throw e
        }
    }

    private fun YahooQuoteNet.toQuote(): Quote {
        val quote = Quote(
            symbol = this.symbol,
            name = (this.name ?: this.longName).orEmpty(),
            lastTradePrice = this.lastTradePrice ?: 0f,
            changeInPercent = this.changePercent ?: 0f,
            change = this.change ?: 0f
        )
        quote.stockExchange = this.exchange ?: ""
        quote.currencyCode = this.currency ?: "USD"
        quote.annualDividendRate = this.annualDividendRate ?: 0f
        quote.annualDividendYield = this.annualDividendYield ?: 0f
        quote.region = this.region
        quote.quoteType = this.quoteType
        quote.longName = this.longName
        quote.gmtOffSetMilliseconds = this.gmtOffSetMilliseconds ?: 0L
        quote.dayHigh = this.regularMarketDayHigh
        quote.dayLow = this.regularMarketDayLow
        quote.previousClose = this.regularMarketPreviousClose ?: 0f
        quote.open = this.regularMarketOpen
        quote.regularMarketVolume = this.regularMarketVolume
        quote.trailingPE = this.trailingPE
        quote.marketState = this.marketState ?: ""
        quote.tradeable = this.tradeable ?: false
        quote.triggerable = this.triggerable ?: false
        quote.fiftyTwoWeekLowChange = this.fiftyTwoWeekLowChange
        quote.fiftyTwoWeekLowChangePercent = this.fiftyTwoWeekLowChangePercent
        quote.fiftyTwoWeekHighChange = this.fiftyTwoWeekHighChange
        quote.fiftyTwoWeekHighChangePercent = this.fiftyTwoWeekHighChangePercent
        quote.fiftyTwoWeekLow = this.fiftyTwoWeekLow
        quote.fiftyTwoWeekHigh = this.fiftyTwoWeekHigh
        quote.dividendDate = this.dividendDate?.times(1000)
        quote.earningsTimestamp = this.earningsTimestamp?.times(1000)
        quote.fiftyDayAverage = this.fiftyDayAverage
        quote.fiftyDayAverageChange = this.fiftyDayAverageChange
        quote.fiftyDayAverageChangePercent = this.fiftyDayAverageChangePercent
        quote.twoHundredDayAverage = this.twoHundredDayAverage
        quote.twoHundredDayAverageChange = this.twoHundredDayAverageChange
        quote.twoHundredDayAverageChangePercent = this.twoHundredDayAverageChangePercent
        quote.marketCap = this.marketCap
        return quote
    }
}
