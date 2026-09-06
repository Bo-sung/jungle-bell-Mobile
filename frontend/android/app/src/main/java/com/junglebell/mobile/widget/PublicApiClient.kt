package com.junglebell.mobile.widget

import android.webkit.CookieManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * Client for the widget data.
 *
 * Laundry and meals use the public (unauthenticated) API so they keep working
 * without any user session. Attendance is personal: it is fetched with the
 * mobile session cookie that the in-app WebView stores after the user pairs
 * their phone with the PC. When there is no session the widget simply shows a
 * "connect" hint and the public widgets keep working.
 */
interface PublicApiClient {

    @GET("api/public/laundry")
    suspend fun laundry(): PublicLaundrySnapshot

    @GET("api/public/meals")
    suspend fun meals(): PublicMealsSnapshot

    @GET("api/me/attendance")
    suspend fun attendance(@Header("Cookie") cookie: String): MobileAttendanceEnvelope

    companion object {
        const val BASE_URL = "https://jungle-bell.sijun-yang.com/"

        /**
         * Returns the WebView cookie string for the app origin, or null when the
         * user has not paired (no mobile session). Native code may read HttpOnly
         * cookies of the app's own WebView — only JavaScript is restricted.
         */
        @Suppress("DEPRECATION")
        fun webViewSessionCookie(): String? {
            return try {
                CookieManager.getInstance()
                    .getCookie(BASE_URL.trimEnd('/'))
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }

        val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val instance: PublicApiClient by lazy {
            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(PublicApiClient::class.java)
        }
    }
}
