package com.v2ray.ang.dto

/**
 * The body of an HTTP response together with its headers.
 *
 * @property headers header names lower-cased; a repeated header keeps its last value
 */
data class UrlResponse(
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)
