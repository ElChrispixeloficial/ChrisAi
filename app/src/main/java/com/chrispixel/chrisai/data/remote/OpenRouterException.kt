package com.chrispixel.chrisai.data.remote

sealed class OpenRouterException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class NoApiKey : OpenRouterException(
        "No hay una API key configurada. Añádela en Ajustes."
    )

    class Network(message: String, cause: Throwable? = null) : OpenRouterException(message, cause)

    class Timeout : OpenRouterException("La conexión tardó demasiado. Reinténtalo.")

    class RateLimited(message: String) : OpenRouterException(message)

    class Http(val status: Int, message: String) : OpenRouterException(message)

    class InvalidResponse(message: String) : OpenRouterException(message)

    class Unexpected(message: String, cause: Throwable? = null) : OpenRouterException(message, cause)
}