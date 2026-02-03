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
        Regex("csrfToken\" value=\"(.+)\">")
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
                        Timber.e("Failed cookie consent with code: ${cookieConsent.code()}")
                        return@withContext
                    }
                }

                val crumbResponse = yahooFinanceCrumb.getCrumb()
                if (crumbResponse.isSuccessful) {
                    val crumb = crumbResponse.body()
                    if (!crumb.isNullOrEmpty()) {
                        appPreferences.setCrumb(crumb)
                    }
                } else {
                    Timber.e("Failed to get crumb with code: ${crumbResponse.code()}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Crumb load failed")
            }
        }
    }

    suspend fun getSuggestions(query: String): FetchResult<List<SuggestionNet>> =
        withContext(Dispatchers.IO) {
            val suggestions = try {
                suggestionApi.getSuggestions(query).result
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext FetchResult.failure(FetchException("Error fetching", e))
            }
            val suggestionList = suggestions?.let { ArrayList(it) } ?: ArrayList()
            return@withContext FetchResult.success<List<SuggestionNet>>(suggestionList)
        }

    suspend fun getStocks(tickerList: List<String>): FetchResult<List<Quote>> =
        withContext(Dispatchers.IO) {
            try {
                // Split tickers into MOEX and Yahoo
                val (moexTickers, yahooTickers) = tickerList.partition { MoexApi.isMoexTicker(it) }
                
                val quotes = mutableListOf<Quote>()
                
                // Fetch MOEX stocks
                if (moexTickers.isNotEmpty()) {
                    val moexApi = MoexApi()
                    moexTickers.forEach { ticker ->
                        try {
                            val quote = moexApi.fetchQuote(ticker)
                            if (quote != null) {
                                quotes.add(quote)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to fetch MOEX ticker: $ticker")
                        }
                    }
                }
                
                // Fetch Yahoo stocks
                if (yahooTickers.isNotEmpty()) {
                    val quoteNets = getStocksYahoo(yahooTickers)
                    if (quoteNets != null) {
                        quotes.addAll(quoteNets.toQuoteMap().toOrderedList(yahooTickers))
                    }
                }
                
                // Return in original order
                val orderedQuotes = tickerList.mapNotNull { ticker ->
                    quotes.find { it.symbol.equals(ticker, ignoreCase = true) || 
                                  it.symbol.equals("$ticker.ME", ignoreCase = true) }
                }
                
                return@withContext FetchResult.success(orderedQuotes)
            } catch (ex: Exception) {
                Timber.e(ex)
                return@withContext FetchResult.failure(FetchException("Failed to fetch", ex))
            }
        }

    suspend fun getStock(ticker: String): FetchResult<Quote> =
        withContext(Dispatchers.IO) {
            try {
                // Check if it's a MOEX ticker first
                if (MoexApi.isMoexTicker(ticker)) {
                    val moexApi = MoexApi()
                    val quote = moexApi.fetchQuote(ticker)
                    if (quote != null) {
                        return@withContext FetchResult.success(quote)
                    }
                    // If MOEX fetch fails, fall through to Yahoo
                    Timber.w("MOEX fetch failed for $ticker, trying Yahoo Finance")
                }
                
                // Fetch from Yahoo Finance
                val quoteNets = getStocksYahoo(listOf(ticker))
                    ?: return@withContext FetchResult.failure(FetchException("Failed to fetch $ticker"))
                return@withContext FetchResult.success(quoteNets.first().toQuote())
            } catch (ex: Exception) {
                Timber.e(ex)
                return@withContext FetchResult.failure(FetchException("Failed to fetch $ticker", ex))
            }
        }

    private suspend fun getStocksYahoo(
        tickerList: List<String>,
        invocationCount: Int = 1
    ): List<YahooQuoteNet>? =
        withContext(Dispatchers.IO) {
            val query = tickerList.joinToString(",")
            var quotesResponse: retrofit2.Response<YahooResponse>? = null
            try {
                quotesResponse = yahooFinance.getStocks(query)
                if (!quotesResponse.isSuccessful) {
                    Timber.e("Yahoo quote fetch failed with code ${quotesResponse.code()}")
                }
                if (quotesResponse.code() == 401) {
                    appPreferences.setCrumb(null)
                    loadCrumb()
                    if (invocationCount == 1) {
                        return@withContext getStocksYahoo(tickerList, invocationCount = invocationCount + 1)
                    }
                }
            } catch (ex: Exception) {
                Timber.e
