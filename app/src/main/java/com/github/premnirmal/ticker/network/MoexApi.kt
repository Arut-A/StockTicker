package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.network.data.MoexMarketData
import com.github.premnirmal.ticker.network.data.MoexQuote
import com.github.premnirmal.ticker.network.data.MoexResponse
import com.github.premnirmal.ticker.network.data.MoexSecurities
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
            val mdRow = mdData.getJSONArray(0)
            
            // Parse securities
            val securitiesObj = json.getJSONObject("securities")
            val secColumns = securitiesObj.getJSONArray("columns")
            val secData = securitiesObj.getJSONArray("data")
            
            if (secData.length() == 0) return null
            val secRow = secData.getJSONArray(0)
            
            // Find column indices
            val lastIndex = findColumnIndex(mdColumns, "LAST")
            val openIndex = findColumnIndex(mdColumns, "OPEN")
            val highIndex = findColumnIndex(mdColumns, "HIGH")
            val lowIndex = findColumnIndex(mdColumns, "LOW")
            val nameIndex = findColumnIndex(secColumns, "SHORTNAME")
            
            // Extract values
            val last = mdRow.optDouble(lastIndex, 0.0)
            val open = mdRow.optDouble(openIndex, last)
            val high = mdRow.optDouble(highIndex, last)
            val low = mdRow.optDouble(lowIndex, last)
            val name = secRow.optString(nameIndex, ticker)
            
            val change = last - open
            val changePercent = if (open > 0) (change / open) * 100 else 0.0
            
            // Create Quote object compatible with existing app structure
            return Quote(
                symbol = "$ticker.ME",  // Add .ME suffix for identification
                name = name,
                lastTradePrice = last.toFloat(),
                changeInPercent = changePercent.toFloat(),
                change = change.toFloat(),
                stockExchange = "MOEX",
                currency = "RUB",
                open = open.toFloat(),
                high = high.toFloat(),
                low = low.toFloat()
            )
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
            "NVTK", "GMKN", "TATN", "MGNT", "AFLT", "MTSS"
        )
        
        fun isMoexTicker(ticker: String): Boolean {
            val clean = ticker.replace(".ME", "").replace(".MOEX", "").uppercase()
            return MOEX_TICKERS.contains(clean) || ticker.endsWith(".ME") || ticker.endsWith(".MOEX")
        }
    }
}
