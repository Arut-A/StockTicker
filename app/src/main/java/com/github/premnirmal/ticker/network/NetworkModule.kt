package com.github.premnirmal.ticker.network

import android.content.Context
import com.github.premnirmal.tickerwidget.BuildConfig
import com.github.premnirmal.tickerwidget.R
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.converter.simplexml.SimpleXmlConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Created by premnirmal on 3/3/16.
 */
@Module
@InstallIn(SingletonComponent::class)
class NetworkModule {

    companion object {
        internal const val CONNECTION_TIMEOUT: Long = 5000
        internal const val READ_TIMEOUT: Long = 5000
    }

    @Provides @Singleton
    internal fun provideHttpClient(): OkHttpClient {
        val logger = HttpLoggingInterceptor()
        logger.level =
            if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        val okHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(logger)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .build()
        return okHttpClient
    }

    // Yahoo-specific HTTP client removed — app no longer uses Yahoo APIs.

    @Provides @Singleton
    internal fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
            prettyPrint = true
        }
    }

    @Provides @Singleton
    internal fun provideJsonFactory(json: Json): Converter.Factory {
        return json.asConverterFactory("application/json".toMediaType())
    }

    // Suggestion API removed — suggestions no longer fetched from Yahoo.

    // Yahoo providers removed.

    @Provides @Singleton
    internal fun provideApeWisdom(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        converterFactory: Converter.Factory
    ): ApeWisdom {
        val retrofit = Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl(context.getString(R.string.apewisdom_endpoint))
            .addConverterFactory(converterFactory)
            .build()
        val apewisdom = retrofit.create(ApeWisdom::class.java)
        return apewisdom
    }

    // Yahoo "most active" provider removed.

    @Provides @Singleton
    internal fun provideGoogleNewsApi(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): GoogleNewsApi {
        val retrofit =
            Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(context.getString(R.string.google_news_endpoint))
                .addConverterFactory(SimpleXmlConverterFactory.create())
                .build()
        return retrofit.create(GoogleNewsApi::class.java)
    }

    // Yahoo news provider removed.

    @Provides @Singleton
    internal fun provideHistoricalDataApi(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        converterFactory: Converter.Factory
    ): ChartApi {
        val retrofit = Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl(context.getString(R.string.historical_data_endpoint))
            .addConverterFactory(converterFactory)
            .build()
        val api = retrofit.create(ChartApi::class.java)
        return api
    }

    // @Provides @Singleton
    // internal fun provideGithubApi(
    //     @ApplicationContext context: Context,
    //     okHttpClient: OkHttpClient,
    //     converterFactory: Converter.Factory
    // ): GithubApi {
    //     val retrofit = Retrofit.Builder()
    //         .client(okHttpClient)
    //         .baseUrl(context.getString(R.string.github_endoint))
    //         .addConverterFactory(converterFactory)
    //         .build()
    //     return retrofit.create(GithubApi::class.java)
    // }
}
