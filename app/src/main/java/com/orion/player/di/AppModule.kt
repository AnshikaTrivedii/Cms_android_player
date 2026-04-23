package com.orion.player.di

import android.content.Context
import androidx.room.Room
import com.orion.player.BuildConfig
import com.orion.player.data.local.OrionDatabase
import com.orion.player.data.local.PopLogDao
import com.orion.player.data.remote.OrionPlayerApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing application-scoped singletons:
 * OkHttp, Retrofit, Room Database, and API service.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOrionPlayerApi(retrofit: Retrofit): OrionPlayerApi {
        return retrofit.create(OrionPlayerApi::class.java)
    }

    @Provides
    @Singleton
    fun provideOrionDatabase(
        @ApplicationContext context: Context
    ): OrionDatabase {
        return Room.databaseBuilder(
            context,
            OrionDatabase::class.java,
            "orion_player_db"
        ).build()
    }

    @Provides
    fun providePopLogDao(database: OrionDatabase): PopLogDao {
        return database.popLogDao()
    }
}
