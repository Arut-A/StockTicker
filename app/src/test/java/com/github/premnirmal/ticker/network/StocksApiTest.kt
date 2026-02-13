package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.BaseUnitTest
import com.github.premnirmal.ticker.mock.Mocker
import com.github.premnirmal.ticker.network.data.Quote
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

@HiltAndroidTest
class StocksApiTest : BaseUnitTest() {

    companion object {
        val TEST_TICKER_LIST = arrayListOf("OZON", "SBER", "GAZP", "LKOH", "YNDX")
    }

    internal lateinit var moexApi: MoexApi
    internal lateinit var mockPrefs: AppPreferences

    private lateinit var stocksApi: StocksApi

    @Before fun initMocks() {
        runBlocking {
            moexApi = Mocker.provide(MoexApi::class)
            mockPrefs = Mocker.provide(AppPreferences::class)
            val suggestionApi = Mocker.provide(SuggestionApi::class)
            stocksApi = StocksApi(mockPrefs, suggestionApi, moexApi)

            // Mock MOEX responses
            val mockQuote = Quote(symbol = "OZON", name = "OZON Holdings", lastTradePrice = 2500f)
            mockQuote.stockExchange = "MOEX"
            mockQuote.currencyCode = "RUB"
            whenever(moexApi.fetchQuote(any())).thenReturn(mockQuote)
        }
    }

    @After fun clear() {
        Mocker.clearMocks()
    }

    @Test fun testGetStocks() {
        runBlocking {
            val testTickerList = TEST_TICKER_LIST
            val stocks = stocksApi.getStocks(testTickerList)
            verify(moexApi).fetchQuote(any())
            assertEquals(testTickerList.size, stocks.data.size)
        }
    }

    @Test
    fun testFailure() {
        runBlocking {
            val error = RuntimeException("Network error")
            doThrow(error).whenever(moexApi)
                .fetchQuote(any())
            val testTickerList = TEST_TICKER_LIST
            val result = stocksApi.getStocks(testTickerList)
            assertFalse(result.wasSuccessful)
            assertTrue(result.hasError)
            verify(moexApi).fetchQuote(any())
        }
    }
}
