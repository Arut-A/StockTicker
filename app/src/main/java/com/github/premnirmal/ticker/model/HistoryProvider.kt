package com.github.premnirmal.ticker.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.FIVE_YEARS
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.MAX
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.ONE_DAY
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.ONE_MONTH
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.ONE_YEAR
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.THREE_MONTH
import com.github.premnirmal.ticker.model.HistoryProvider.Range.Companion.TWO_WEEKS
import com.github.premnirmal.ticker.network.ChartApi
import com.github.premnirmal.ticker.network.MoexApi
import com.github.premnirmal.ticker.network.data.DataPoint
import com.github.premnirmal.tickerwidget.ui.theme.ColourPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Serializable
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryProvider @Inject constructor(
    private val chartApi: ChartApi
) {

    private var cachedData: WeakReference<Pair<String, ChartData>>? = null

    suspend fun fetchDataByRange(
        symbol: String,
        range: Range
    ): FetchResult<ChartData> = withContext(Dispatchers.IO) {
        // For MOEX tickers, use MOEX ISS candle API
        if (MoexApi.isMoexTicker(symbol)) {
            return@withContext fetchMoexChartData(symbol, range)
        }

        val chartData = try {
            val historicalData =
                chartApi.fetchChartData(
                    symbol = symbol,
                    interval = range.intervalParam(),
                    range = range.rangeParam()
                )
            with(historicalData.chart.result.first()) {
                val chartPreviousClose = meta.chartPreviousClose.toFloat()
                val regularMarketPrice = meta.regularMarketPrice.toFloat()
                val dataPoints = timestamp?.mapIndexed { i, stamp ->
                    val dataQuote = indicators?.quote?.firstOrNull()
                    if (dataQuote == null ||
                        dataQuote.low == null || dataQuote.high == null ||
                        dataQuote.open == null || dataQuote.close == null ||
                        dataQuote.high[i] === null || dataQuote.low[i] === null ||
                        dataQuote.open[i] === null || dataQuote.close[i] === null
                    ) {
                        null
                    } else {
                        DataPoint(
                            stamp.toFloat(),
                            dataQuote.high[i]!!.toFloat(),
                            dataQuote.low[i]!!.toFloat(),
                            dataQuote.open[i]!!.toFloat(),
                            dataQuote.close[i]!!.toFloat()
                        )
                    }
                }?.filterNotNull()?.sorted().orEmpty()
                ChartData(
                    chartPreviousClose = chartPreviousClose,
                    regularMarketPrice = regularMarketPrice,
                    dataPoints = dataPoints
                )
            }
        } catch (ex: Exception) {
            Timber.w(ex)
            return@withContext FetchResult.failure(
                FetchException("Error fetching datapoints", ex)
            )
        }
        return@withContext FetchResult.success(chartData)
    }

    private val moexApi = MoexApi()

    private suspend fun fetchMoexChartData(
        symbol: String,
        range: Range
    ): FetchResult<ChartData> {
        try {
            val fromDate = LocalDate.now().minusDays(range.duration.toDays())
            val from = fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val interval = range.moexInterval()

            val candles = moexApi.fetchCandles(
                ticker = symbol,
                from = from,
                interval = interval
            )

            if (candles.isEmpty()) {
                return FetchResult.failure(FetchException("No candle data from MOEX for $symbol"))
            }

            val moscowZone = ZoneId.of("Europe/Moscow")
            val moexDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

            val dataPoints = candles.mapNotNull { candle ->
                try {
                    val dateTime = LocalDateTime.parse(candle.begin, moexDateFormat)
                    val epochSecond = dateTime.atZone(moscowZone).toEpochSecond()
                    DataPoint(
                        epochSecond.toFloat(),
                        candle.high.toFloat(),
                        candle.low.toFloat(),
                        candle.open.toFloat(),
                        candle.close.toFloat()
                    )
                } catch (e: Exception) {
                    null
                }
            }.sorted()

            if (dataPoints.isEmpty()) {
                return FetchResult.failure(FetchException("Failed to parse MOEX candle data"))
            }

            val chartPreviousClose = dataPoints.first().openVal
            val regularMarketPrice = dataPoints.last().closeVal

            return FetchResult.success(
                ChartData(
                    chartPreviousClose = chartPreviousClose,
                    regularMarketPrice = regularMarketPrice,
                    dataPoints = dataPoints
                )
            )
        } catch (ex: Exception) {
            Timber.w(ex)
            return FetchResult.failure(FetchException("Error fetching MOEX chart data", ex))
        }
    }

    private fun Range.intervalParam(): String {
        return when (this) {
            ONE_DAY -> "1h"
            TWO_WEEKS -> "1h"
            else -> "1d"
        }
    }

    private fun Range.rangeParam(): String {
        return when (this) {
            ONE_DAY -> "1d"
            TWO_WEEKS -> "14d"
            ONE_MONTH -> "1mo"
            THREE_MONTH -> "3mo"
            ONE_YEAR -> "1y"
            FIVE_YEARS -> "5y"
            MAX -> "max"
            else -> "max"
        }
    }

    /**
     * Maps Range to MOEX ISS candle interval values.
     * 1=1min, 10=10min, 60=1hour, 24=1day, 7=1week, 31=1month
     */
    private fun Range.moexInterval(): Int {
        return when (this) {
            ONE_DAY -> 10       // 10-minute candles for intraday
            TWO_WEEKS -> 60     // hourly candles
            ONE_MONTH -> 24     // daily candles
            THREE_MONTH -> 24   // daily candles
            ONE_YEAR -> 7       // weekly candles
            FIVE_YEARS -> 31    // monthly candles
            MAX -> 31           // monthly candles
            else -> 24
        }
    }

    data class ChartData(
        val chartPreviousClose: Float,
        val regularMarketPrice: Float,
        val dataPoints: List<DataPoint>,
    ) {
        val change: Float
            get() = regularMarketPrice - chartPreviousClose

        val changeInPercent: Float
            get() = (regularMarketPrice - chartPreviousClose) / chartPreviousClose * 100f

        val isUp: Boolean
            get() = change > 0f

        val isDown: Boolean
            get() = change < 0f

        val changeColour: Color
            @Composable get() = if (isUp) ColourPalette.ChangePositive else ColourPalette.ChangeNegative

        fun changeString(): String = AppPreferences.SELECTED_DECIMAL_FORMAT.format(change)

        fun changeStringWithSign(): String {
            val changeString = AppPreferences.SELECTED_DECIMAL_FORMAT.format(change)
            if (change >= 0) {
                return "+$changeString"
            }
            return changeString
        }

        fun changePercentString(): String =
            "${AppPreferences.DECIMAL_FORMAT_2DP.format(changeInPercent)}%"

        fun changePercentStringWithSign(): String {
            val changeString = "${AppPreferences.DECIMAL_FORMAT_2DP.format(changeInPercent)}%"
            if (changeInPercent >= 0) {
                return "+$changeString"
            }
            return changeString
        }
    }

    sealed class Range(val duration: Duration) : Serializable {
        val end = LocalDate.now().minusDays(duration.toDays())
        class DateRange(duration: Duration) : Range(duration)
        companion object {
            val ONE_DAY = DateRange(Duration.ofDays(1))
            val TWO_WEEKS = DateRange(Duration.ofDays(14))
            val ONE_MONTH = DateRange(Duration.ofDays(30))
            val THREE_MONTH = DateRange(Duration.ofDays(90))
            val ONE_YEAR = DateRange(Duration.ofDays(365))
            val FIVE_YEARS = DateRange(Duration.ofDays(5 * 365))
            val MAX = DateRange(Duration.ofDays(20 * 365))
        }
    }
}
