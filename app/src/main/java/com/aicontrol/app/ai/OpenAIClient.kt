package com.aicontrol.app.ai

import com.aicontrol.app.ai.models.ChatCompletionRequest
import com.aicontrol.app.ai.models.ChatCompletionResponse
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface OpenAIApiService {
    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}

object OpenAIClient {

    // HuggingFace OpenAI-compatible Inference Router — supports /v1/chat/completions
    const val BASE_URL_HUGGINGFACE = "https://router.huggingface.co/"
    const val BASE_URL_OPENAI      = "https://api.openai.com/"

    fun create(baseUrl: String = BASE_URL_HUGGINGFACE): OpenAIApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAIApiService::class.java)
    }
}
