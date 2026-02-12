package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.network.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class MoexApi {

    companion object {
        const val BASE_URL = "https://iss.moex.com/iss"

        // Singleton OkHttpClient shared across ALL MoexApi instances
        // Uses connection pooling so subsequent requests reuse TCP connections
        val client: OkHttpClient by lazy {
            Timber.d("MOEX: Creating shared OkHttpClient")
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

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

    private fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Timber.e("MOEX HTTP ${response.code} for $url")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "MOEX HTTP failed: $url")
            null
        }
    }

    suspend fun fetchQuote(ticker: String): Quote? = withContext(Dispatchers.IO) {
        try {
            val clean = cleanTicker(ticker)
            val url = "$BASE_URL/engines/stock/markets/shares/securities/$clean.json?iss.meta=off&iss.only=securities,marketdata"
            Timber.d("MOEX fetch: $url")
            val body = httpGet(url) ?: return@withContext null
            parseQuote(ticker, clean, body)
        } catch (e: Exception) {
            Timber.e(e, "MOEX fetchQuote failed: $ticker")
            null
        }
    }

    suspend fun fetchCandles(
        ticker: String,
        from: String,
        till: String? = null,
        interval: Int = 24
    ): List<MoexCandle> = withContext(Dispatchers.IO) {
        try {
            val clean = cleanTicker(ticker)
            val tillParam = if (till != null) "&till=$till" else ""
            val url = "$BASE_URL/engines/stock/markets/shares/boards/TQBR/securities/$clean/candles.json?from=$from&interval=$interval$tillParam&iss.meta=off"
            Timber.d("MOEX candles: $url")
            val body = httpGet(url) ?: return@withContext emptyList()
            parseCandles(body)
        } catch (e: Exception) {
            Timber.e(e, "MOEX fetchCandles failed: $ticker")
            emptyList()
        }
    }

    // --- Parsers ---

    private fun parseQuote(originalTicker: String, clean: String, json: String): Quote? {
        try {
            val root = JSONObject(json)

            val secObj = root.getJSONObject("securities")
            val secCols = secObj.getJSONArray("columns")
            val secData = secObj.getJSONArray("data")
            if (secData.length() == 0) { Timber.e("MOEX: empty securities for $clean"); return null }

            val mdObj = root.getJSONObject("marketdata")
            val mdCols = mdObj.getJSONArray("columns")
            val mdData = mdObj.getJSONArray("data")
            if (mdData.length() == 0) { Timber.e("MOEX: empty marketdata for $clean"); return null }

            // Find TQBR rows
            val secRow = findRow(secData, colIdx(secCols, "BOARDID"), "TQBR") ?: secData.getJSONArray(0)
            val mdRow = findRow(mdData, colIdx(mdCols, "BOARDID"), "TQBR") ?: mdData.getJSONArray(0)

            // Prices
            var last = dbl(mdRow, colIdx(mdCols, "LAST"))
            val lclose = dbl(mdRow, colIdx(mdCols, "LCLOSEPRICE"))
            val prev = dbl(secRow, colIdx(secCols, "PREVPRICE")) ?: 0.0

            if (last == null || last == 0.0) last = lclose
            if (last == null || last == 0.0) last = prev
            if (last == 0.0 && prev == 0.0) { Timber.e("MOEX: no price for $clean"); return null }

            val open = dbl(mdRow, colIdx(mdCols, "OPEN")) ?: last
            val high = dbl(mdRow, colIdx(mdCols, "HIGH")) ?: last
            val low = dbl(mdRow, colIdx(mdCols, "LOW")) ?: last
            val pct = dbl(mdRow, colIdx(mdCols, "LASTTOPREVPRICE")) ?: 0.0
            val vol = mdRow.optLong(colIdx(mdCols, "VOLTODAY"), 0L)

            val shortName = secRow.optString(colIdx(secCols, "SHORTNAME"), clean)
            val secName = secRow.optString(colIdx(secCols, "SECNAME"), "")
            val name = if (secName.isNotEmpty() && secName != "null") secName else shortName
            val change = if (prev != 0.0) last - prev else 0.0

            Timber.d("MOEX: $clean=$last prev=$prev Δ=$change (${pct}%) vol=$vol")

            return Quote(
                symbol = originalTicker,
                name = name,
                lastTradePrice = last.toFloat(),
                changeInPercent = pct.toFloat(),
                change = change.toFloat()
            ).apply {
                stockExchange = "MOEX"
                currencyCode = "RUB"
                dayHigh = high.toFloat()
                dayLow = low.toFloat()
                this.open = open.toFloat()
                previousClose = prev.toFloat()
                regularMarketVolume = vol
                longName = name
                marketState = "REGULAR"
                tradeable = true
            }
        } catch (e: Exception) {
            Timber.e(e, "MOEX parseQuote error: $clean")
            return null
        }
    }

    private fun parseCandles(json: String): List<MoexCandle> {
        val out = mutableListOf<MoexCandle>()
        try {
            val root = JSONObject(json)
            val obj = root.getJSONObject("candles")
            val cols = obj.getJSONArray("columns")
            val data = obj.getJSONArray("data")

            val oi = colIdx(cols, "open"); val ci = colIdx(cols, "close")
            val hi = colIdx(cols, "high"); val li = colIdx(cols, "low")
            val vi = colIdx(cols, "volume"); val bi = colIdx(cols, "begin"); val ei = colIdx(cols, "end")

            for (i in 0 until data.length()) {
                val r = data.getJSONArray(i)
                val o = dbl(r, oi) ?: continue
                val c = dbl(r, ci) ?: continue
                val h = dbl(r, hi) ?: continue
                val l = dbl(r, li) ?: continue
                out.add(MoexCandle(o, c, h, l, r.optLong(vi, 0), r.optString(bi, ""), r.optString(ei, "")))
            }
            Timber.d("MOEX: parsed ${out.size} candles")
        } catch (e: Exception) {
            Timber.e(e, "MOEX parseCandles error")
        }
        return out
    }

    // --- Tiny helpers ---

    private fun dbl(arr: org.json.JSONArray, idx: Int): Double? {
        if (idx < 0 || idx >= arr.length() || arr.isNull(idx)) return null
        return try { val v = arr.getDouble(idx); if (v.isNaN() || v.isInfinite()) null else v } catch (_: Exception) { null }
    }

    private fun colIdx(cols: org.json.JSONArray, name: String): Int {
        for (i in 0 until cols.length()) if (cols.getString(i) == name) return i; return -1
    }

    private fun findRow(data: org.json.JSONArray, boardIdx: Int, board: String): org.json.JSONArray? {
        if (boardIdx < 0) return null
        for (i in 0 until data.length()) {
            val row = data.getJSONArray(i)
            if (!row.isNull(boardIdx) && row.optString(boardIdx) == board) return row
        }
        return null
    }

    data class MoexCandle(val open: Double, val close: Double, val high: Double, val low: Double, val volume: Long, val begin: String, val end: String)
}
