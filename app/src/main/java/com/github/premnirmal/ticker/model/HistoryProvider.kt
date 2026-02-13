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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryProvider @Inject constructor(
    private val chartApi: ChartApi // kept for Hilt DI, unused
) {

    private val moexApi = MoexApi()
    private var cachedData: WeakReference<Pair<String, ChartData>>? = null

    suspend fun fetchDataByRange(
        symbol: String,
        range: Range
    ): FetchResult<ChartData> = withContext(Dispatchers.IO) {
        try {
            val fromDate = LocalDate.now().minusDays(range.duration.toDays())
            val from = fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val interval = range.moexInterval()

            Timber.d("MOEX chart: $symbol from=$from interval=$interval")

            val candles = moexApi.fetchCandles(
                ticker = symbol,
                from = from,
                interval = interval
            )

            if (candles.isEmpty()) {
                Timber.w("MOEX: no candles for $symbol")
                return@withContext FetchResult.failure(
                    FetchException("No chart data for $symbol")
                )
            }

            val moscowZone = ZoneId.of("Europe/Moscow")
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

            val dataPoints = candles.mapNotNull { c ->
                try {
                    val dt = LocalDateTime.parse(c.begin, fmt)
                    val epoch = dt.atZone(moscowZone).toEpochSecond()
                    DataPoint(
                        epoch.toFloat(),
                        c.high.toFloat(),
                        c.low.toFloat(),
                        c.open.toFloat(),
                        c.close.toFloat()
                    )
                } catch (e: Exception) { null }
            }.sorted()

            if (dataPoints.isEmpty()) {
                return@withContext FetchResult.failure(
                    FetchException("Failed to parse chart data")
                )
            }

            val chartData = ChartData(
                chartPreviousClose = dataPoints.first().openVal,
                regularMarketPrice = dataPoints.last().closeVal,
                dataPoints = dataPoints
            )
            Timber.d("MOEX chart OK: ${dataPoints.size} points")
            return@withContext FetchResult.success(chartData)
        } catch (ex: Exception) {
            Timber.e(ex, "MOEX chart error: $symbol")
            return@withContext FetchResult.failure(
                FetchException("Chart fetch failed", ex)
            )
        }
    }

    /**
     * MOEX candle intervals: 1=1min, 10=10min, 60=1hr, 24=1day, 7=1week, 31=1month
     */
    private fun Range.moexInterval(): Int = when (this) {
        ONE_DAY -> 10
        TWO_WEEKS -> 60
        ONE_MONTH -> 24
        THREE_MONTH -> 24
        ONE_YEAR -> 7
        FIVE_YEARS -> 31
        MAX -> 31
        else -> 24
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
            if (change >= 0) return "+$changeString"
            return changeString
        }

        fun changePercentString(): String =
            "${AppPreferences.DECIMAL_FORMAT_2DP.format(changeInPercent)}%"

        fun changePercentStringWithSign(): String {
            val changeString = "${AppPreferences.DECIMAL_FORMAT_2DP.format(changeInPercent)}%"
            if (changeInPercent >= 0) return "+$changeString"
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
