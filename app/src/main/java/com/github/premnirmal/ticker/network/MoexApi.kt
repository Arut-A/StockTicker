package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.network.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MoexApi {
    
    suspend fun fetchQuote(ticker: String, board: String = "TQBR"): Quote? {
        return withContext(Dispatchers.IO) {
            try {
                val cleanTicker = ticker.replace(".ME", "").replace(".MOEX", "")
                val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/$board/securities/$cleanTicker.json"
                val response = URL(url).readText()
                parseToQuote(cleanTicker, response)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
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
            
            // Find column indices in securities (PREVPRICE is here!)
            val nameIndex = findColumnIndex(secColumns, "SHORTNAME")
            val prevPriceIndex = findColumnIndex(secColumns, "PREVPRICE")
            
            // Extract values from marketdata
            val last = mdRow.optDouble(lastIndex, 0.0)
            val open = mdRow.optDouble(openIndex, last)
            val high = mdRow.optDouble(highIndex, last)
            val low = mdRow.optDouble(lowIndex, last)
            val changePct = mdRow.optDouble(changePctIndex, 0.0)
            
            // Extract values from securities
            val prevPrice = secRow.optDouble(prevPriceIndex, open)
            val name = secRow.optString(nameIndex, ticker)
            
            // Calculate absolute change
            val change = last - prevPrice
            
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
    
    companion object {
        val MOEX_TICKERS = setOf(
            "OZON", "SBER", "GAZP", "LKOH", "YNDX", "ROSN", 
            "NVTK", "GMKN", "TATN", "MGNT", "AFLT", "MTSS",
            "VKCO", "POLY", "SNGS", "PLZL", "FEES", "ALRS"
        )
        
        fun isMoexTicker(ticker: String): Boolean {
            val clean = ticker.replace(".ME", "").replace(".MOEX", "").uppercase()
            return MOEX_TICKERS.contains(clean) || ticker.endsWith(".ME") || ticker.endsWith(".MOEX")
        }
    }
}
