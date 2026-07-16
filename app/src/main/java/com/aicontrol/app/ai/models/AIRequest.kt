package com.aicontrol.app.ai.models

import com.google.gson.annotations.SerializedName

data class ChatCompletionRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int = 1024,
    @SerializedName("temperature") val temperature: Double = 0.1
)

data class Message(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: Any
)

data class TextContent(
    @SerializedName("type") val type: String = "text",
    @SerializedName("text") val text: String
)

data class ImageContent(
    @SerializedName("type") val type: String = "image_url",
    @SerializedName("image_url") val imageUrl: ImageUrl
)

data class ImageUrl(
    @SerializedName("url") val url: String,
    @SerializedName("detail") val detail: String = "high"
)
