package com.github.premnirmal.ticker.network.data

import kotlinx.serialization.Serializable

@Serializable
data class MoexQuote(
    val symbol: String,
    val name: String,
    val lastPrice: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String = "RUB",
    val exchange: String = "MOEX"
)

@Serializable
data class MoexResponse(
    val securities: MoexSecurities,
    val marketdata: MoexMarketData
)

@Serializable
data class MoexSecurities(
    val columns: List<String>,
    val data: List<List<String>>
)

@Serializable
data class MoexMarketData(
    val columns: List<String>,
    val data: List<List<String?>>
)
