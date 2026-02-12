package com.github.premnirmal.ticker.network

import com.github.premnirmal.ticker.model.FetchException
import com.github.premnirmal.ticker.model.FetchResult
import com.github.premnirmal.ticker.network.data.NewsArticle
import com.github.premnirmal.ticker.network.data.Quote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsProvider @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val googleNewsApi: GoogleNewsApi,
    private val apeWisdom: ApeWisdom,
    private val stocksApi: StocksApi,
    private val appPreferences: com.github.premnirmal.ticker.AppPreferences
) {

    private var cachedBusinessArticles: List<NewsArticle> = emptyList()
    private var cachedTrendingStocks: List<Quote> = emptyList()

    fun initCache(force: Boolean = false) {
        coroutineScope.launch {
            try {
                val last = appPreferences.getLastNewsFetchMs()
                val now = System.currentTimeMillis()
                val oneDayMs = 24 * 60 * 60 * 1000L
                if (force || now - last >= oneDayMs) {
                    fetchMarketNews()
                    fetchTrendingStocks()
                    appPreferences.setLastNewsFetchMs(now)
                }
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
    }

    suspend fun fetchNewsForQuery(query: String): FetchResult<List<NewsArticle>> =
        withContext(Dispatchers.IO) {
            try {
                val newsFeed = googleNewsApi.getNewsFeed(query = query)
                val articles = newsFeed.articleList?.sorted() ?: emptyList()
                return@withContext FetchResult.success(articles)
            } catch (ex: Exception) {
                Timber.w(ex)
                return@withContext FetchResult.failure<List<NewsArticle>>(
                    FetchException("Error fetching news", ex)
                )
            }
        }

    suspend fun fetchMarketNews(useCache: Boolean = false): FetchResult<List<NewsArticle>> =
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val last = appPreferences.getLastNewsFetchMs()
                val oneDayMs = 24 * 60 * 60 * 1000L
                if (useCache && cachedBusinessArticles.isNotEmpty() && now - last < oneDayMs) {
                    return@withContext FetchResult.success(cachedBusinessArticles)
                }
                val businessNewsArticles = googleNewsApi.getBusinessNews().articleList.orEmpty()
                val articles: Set<NewsArticle> = HashSet<NewsArticle>().apply {
                    addAll(businessNewsArticles)
                }
                val newsArticleList = articles.toList().sorted()
                cachedBusinessArticles = newsArticleList
                appPreferences.setLastNewsFetchMs(System.currentTimeMillis())
                return@withContext FetchResult.success(newsArticleList)
            } catch (ex: Exception) {
                Timber.w(ex)
                return@withContext FetchResult.failure<List<NewsArticle>>(
                    FetchException("Error fetching news", ex)
                )
            }
        }

    suspend fun fetchTrendingStocks(useCache: Boolean = false): FetchResult<List<Quote>> =
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val last = appPreferences.getLastNewsFetchMs()
                val oneDayMs = 24 * 60 * 60 * 1000L
                if (useCache && cachedTrendingStocks.isNotEmpty() && now - last < oneDayMs) {
                    return@withContext FetchResult.success(cachedTrendingStocks)
                }

                // adding this extra try/catch because html format can change and parsing will fail
                // Use apewisdom as primary source for trending stocks
                val result = apeWisdom.getTrendingStocks().results
                val data = result.map { it.ticker }
                val trendingResult = stocksApi.getStocks(data)
                if (trendingResult.wasSuccessful) {
                    cachedTrendingStocks = trendingResult.data
                    appPreferences.setLastNewsFetchMs(System.currentTimeMillis())
                }
                return@withContext trendingResult
            } catch (ex: Exception) {
                Timber.w(ex)
                return@withContext FetchResult.failure<List<Quote>>(
                    FetchException("Error fetching trending", ex)
                )
            }
        }
}
