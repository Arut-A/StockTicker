package com.github.premnirmal.ticker.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for MOEX ISS API (iss.moex.com).
 * Uses the same OkHttpClient as the rest of the app.
 */
interface MoexIssApi {

    /**
     * Fetch quote data for a given security.
     * Returns JSON with "securities" and "marketdata" blocks.
     */
    @GET("engines/stock/markets/shares/securities/{ticker}.json?iss.meta=off&iss.only=securities,marketdata")
    suspend fun getQuote(@Path("ticker") ticker: String): String

    /**
     * Fetch candle (OHLCV) data for chart display.
     * interval: 1=1min, 10=10min, 60=1hr, 24=1day, 7=1week, 31=1month
     */
    @GET("engines/stock/markets/shares/boards/TQBR/securities/{ticker}/candles.json?iss.meta=off")
    suspend fun getCandles(
        @Path("ticker") ticker: String,
        @Query("from") from: String,
        @Query("interval") interval: Int,
        @Query("till") till: String? = null
    ): String
}
