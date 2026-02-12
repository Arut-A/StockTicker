package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.network.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MoexApi {

    companion object {
        const val BASE_URL = "https://iss.moex.com/iss"

        val MOEX_TICKERS = setOf(
            "OZON", "SBER", "GAZP", "LKOH", "YNDX", "ROSN",
            "NVTK", "GMKN", "TATN", "MGNT", "AFLT", "MTSS",
            "VKCO", "POLY", "SNGS", "PLZL", "FEES", "ALRS",
            "MOEX", "RUAL", "CHMF", "NLMK", "PHOR", "PIKK",
            "VTBR", "IRAO", "SBERP", "TRNFP", "HYDR", "RTKM",
            "CBOM", "TCSG", "SMLT", "SGZH", "BELU", "FIXP",
            "OKEY", "FIVE", "GLTR", "BSPB", "DSKY", "LSRG",
            "MVID", "UPRO", "FLOT", "KMAZ", "SOFL", "ASTR",
            "MDMG", "HHRU", "WUSH", "POSI", "MSNG", "AQUA"
        )

        fun isMoexTicker(ticker: String): Boolean {
            val clean = cleanTicker(ticker)
            return MOEX_TICKERS.contains(clean) || ticker.endsWith(".ME") || ticker.endsWith(".MOEX")
        }

        fun cleanTicker(ticker: String): String {
            return ticker.replace(".ME", "").replace(".MOEX", "").uppercase()
        }
    }

    suspend fun fetchQuote(ticker: String, board: String = "TQBR"): Quote? {
        return withContext(Dispatchers.IO) {
            try {
                val cleanTicker = cleanTicker(ticker)
                val url = "$BASE_URL/engines/stock/markets/shares/boards/$board/securities/$cleanTicker.json"
                val response = URL(url).readText()
                parseToQuote(cleanTicker, response)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Fetch candle data from MOEX ISS for chart display.
     * @param interval: 1=1min, 10=10min, 60=1hour, 24=1day, 7=1week, 31=1month
     */
    suspend fun fetchCandles(ticker: String, from: String, till: String? = null, interval: Int = 24, board: String = "TQBR"): List<MoexCandle> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanTicker = cleanTicker(ticker)
                val tillParam = if (till != null) "&till=$till" else ""
                val url = "$BASE_URL/engines/stock/markets/shares/boards/$board/securities/$cleanTicker/candles.json?from=$from&interval=$interval$tillParam"
                val response = URL(url).readText()
                parseCandles(response)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    private fun parseCandles(jsonResponse: String): List<MoexCandle> {
        val candles = mutableListOf<MoexCandle>()
        try {
            val json = JSONObject(jsonResponse)
            val candlesObj = json.getJSONObject("candles")
            val columns = candlesObj.getJSONArray("columns")
            val data = candlesObj.getJSONArray("data")

            val openIdx = findColumnIndex(columns, "open")
            val closeIdx = findColumnIndex(columns, "close")
            val highIdx = findColumnIndex(columns, "high")
            val lowIdx = findColumnIndex(columns, "low")
            val volumeIdx = findColumnIndex(columns, "volume")
            val beginIdx = findColumnIndex(columns, "begin")
            val endIdx = findColumnIndex(columns, "end")

            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                val open = row.optDouble(openIdx, Double.NaN)
                val close = row.optDouble(closeIdx, Double.NaN)
                val high = row.optDouble(highIdx, Double.NaN)
                val low = row.optDouble(lowIdx, Double.NaN)
                if (open.isNaN() || close.isNaN() || high.isNaN() || low.isNaN()) continue
                candles.add(
                    MoexCandle(
                        open = open,
                        close = close,
                        high = high,
                        low = low,
                        volume = row.optLong(volumeIdx, 0L),
                        begin = row.optString(beginIdx, ""),
                        end = row.optString(endIdx, "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return candles
    }

    private fun parseToQuote(ticker: String, jsonResponse: String): Quote? {
        try {
            val json = JSONObject(jsonResponse)

            // Parse marketdata
            val marketdataObj = json.getJSONObject("marketdata")
            val mdColumns = marketdataObj.getJSONArray("columns")
            val mdData = marketdataObj.getJSONArray("data")

            if (mdData.length() == 0) return null

            // MOEX returns multiple boards - find TQBR (main board)
            var mdRow: org.json.JSONArray? = null
            val boardIdIndex = findColumnIndex(mdColumns, "BOARDID")

            for (i in 0 until mdData.length()) {
                val row = mdData.getJSONArray(i)
                val boardId = if (boardIdIndex >= 0) row.optString(boardIdIndex, "") else ""
                if (boardId == "TQBR") {
                    mdRow = row
                    break
                }
            }

            if (mdRow == null) mdRow = mdData.getJSONArray(0)

            // Parse securities for the name and previous price
            val securitiesObj = json.getJSONObject("securities")
            val secColumns = securitiesObj.getJSONArray("columns")
            val secData = securitiesObj.getJSONArray("data")

            if (secData.length() == 0) return null

            // Find matching SECID row
            var secRow: org.json.JSONArray? = null
            val secIdIndex = findColumnIndex(secColumns, "SECID")

            for (i in 0 until secData.length()) {
                val row = secData.getJSONArray(i)
                val secId = if (secIdIndex >= 0) row.optString(secIdIndex, "") else ""
                if (secId.equals(ticker, ignoreCase = true)) {
                    secRow = row
                    break
                }
            }

            if (secRow == null) secRow = secData.getJSONArray(0)

            // Find column indices in marketdata
            val lastIndex = findColumnIndex(mdColumns, "LAST")
            val openIndex = findColumnIndex(mdColumns, "OPEN")
            val highIndex = findColumnIndex(mdColumns, "HIGH")
            val lowIndex = findColumnIndex(mdColumns, "LOW")
            val changePctIndex = findColumnIndex(mdColumns, "LASTTOPREVPRICE")
            val volIndex = findColumnIndex(mdColumns, "VOLTODAY")
            val valIndex = findColumnIndex(mdColumns, "VALTODAY")
            val lclosePriceIndex = findColumnIndex(mdColumns, "LCLOSEPRICE")

            // Find column indices in securities
            val nameIndex = findColumnIndex(secColumns, "SHORTNAME")
            val secNameIndex = findColumnIndex(secColumns, "SECNAME")
            val prevPriceIndex = findColumnIndex(secColumns, "PREVPRICE")

            // Extract LAST price — fall back to LCLOSEPRICE if LAST is null/0
            var last = mdRow.optDouble(lastIndex, 0.0)
            if (last == 0.0 || last.isNaN()) {
                last = mdRow.optDouble(lclosePriceIndex, 0.0)
            }

            val prevPrice = secRow.optDouble(prevPriceIndex, 0.0)

            // If both last and prevPrice are 0, there's no useful data
            if (last == 0.0 && prevPrice == 0.0) return null

            // If last is still 0, use prevPrice as best estimate
            if (last == 0.0 || last.isNaN()) last = prevPrice

            val open = mdRow.optDouble(openIndex, Double.NaN).let { if (it.isNaN() || it == 0.0) last else it }
            val high = mdRow.optDouble(highIndex, Double.NaN).let { if (it.isNaN() || it == 0.0) last else it }
            val low = mdRow.optDouble(lowIndex, Double.NaN).let { if (it.isNaN() || it == 0.0) last else it }
            val changePct = mdRow.optDouble(changePctIndex, Double.NaN).let { if (it.isNaN()) 0.0 else it }
            val volume = mdRow.optLong(volIndex, 0L)

            // Extract name — prefer SECNAME (full name), fallback to SHORTNAME
            val shortName = secRow.optString(nameIndex, ticker)
            val secName = secRow.optString(secNameIndex, "")
            val name = if (secName.isNotEmpty()) secName else shortName

            // Calculate absolute change
            val change = if (prevPrice != 0.0) last - prevPrice else 0.0

            // Create Quote
            val quote = Quote(
                symbol = "$ticker.ME",
                name = name,
                lastTradePrice = last.toFloat(),
                changeInPercent = changePct.toFloat(),
                change = change.toFloat()
            ).apply {
                stockExchange = "MOEX"
                currencyCode = "RUB"
                dayHigh = high.toFloat()
                dayLow = low.toFloat()
                this.open = open.toFloat()
                previousClose = prevPrice.toFloat()
                regularMarketVolume = volume
                longName = name
                marketState = "REGULAR"  // MOEX doesn't report this; assume regular during fetch
                tradeable = true
            }

            return quote
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun findColumnIndex(columns: org.json.JSONArray, name: String): Int {
        for (i in 0 until columns.length()) {
            if (columns.getString(i) == name) {
                return i
            }
        }
        return -1
    }

    data class MoexCandle(
        val open: Double,
        val close: Double,
        val high: Double,
        val low: Double,
        val volume: Long,
        val begin: String,
        val end: String
    )
}
