package com.aicontrol.app.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object HuggingFaceModelsClient {

    private const val TAG = "HFModelsClient"
    private const val MODELS_URL =
        "https://huggingface.co/api/models?pipeline_tag=text-generation&sort=trending&limit=40&full=false&cardData=false"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class HFModel(
        @SerializedName("id") val id: String,
        @SerializedName("private") val isPrivate: Boolean = false
    )

    suspend fun fetchTextModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(MODELS_URL)
                .header("Authorization", "Bearer $apiKey")
                .build()

            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchTextModels failed: ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val type = object : TypeToken<List<HFModel>>() {}.type
            val models: List<HFModel> = gson.fromJson(body, type)

            models
                .filter { !it.isPrivate && it.id.isNotBlank() }
                .map { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTextModels error", e)
            emptyList()
        }
    }
}
