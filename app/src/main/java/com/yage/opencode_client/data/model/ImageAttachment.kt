package com.yage.opencode_client.data.model

data class ImageAttachment(
    val base64Data: String,
    val mimeType: String,
    val filename: String
) {
    val asDataUrl: String
        get() = "data:$mimeType;base64,$base64Data"
}
