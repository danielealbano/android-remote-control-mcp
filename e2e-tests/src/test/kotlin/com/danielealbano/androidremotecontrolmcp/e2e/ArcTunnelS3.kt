package com.danielealbano.androidremotecontrolmcp.e2e

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Creates an S3 bucket idempotently (200/409 = done) on a MinIO endpoint, retrying while it boots. */
object ArcTunnelS3 {
    private const val REGION = "us-east-1"
    private const val ATTEMPTS = 30
    private const val RETRY_MS = 1_000L
    private val amzDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun ensureBucket(
        endpoint: String,
        accessKey: String,
        secretKey: String,
        bucket: String,
    ) {
        var last = ""
        repeat(ATTEMPTS) {
            try {
                val code = putBucket(endpoint, accessKey, secretKey, bucket)
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CONFLICT) return
                last = "HTTP $code"
            } catch (e: java.io.IOException) {
                last = "${e::class.simpleName}: ${e.message}"
            }
            Thread.sleep(RETRY_MS)
        }
        throw IllegalStateException("S3 bucket '$bucket' not created on $endpoint: $last")
    }

    private fun putBucket(
        endpoint: String,
        accessKey: String,
        secretKey: String,
        bucket: String,
    ): Int {
        val uri = URI("$endpoint/$bucket")
        val host = uri.host + (if (uri.port > 0) ":${uri.port}" else "")
        val amzDate = amzDateFormat.format(ZonedDateTime.now(ZoneOffset.UTC))
        val day = amzDate.substring(0, 8)
        val emptyHash = sha256Hex(ByteArray(0))
        val canonical =
            "PUT\n/$bucket\n\nhost:$host\nx-amz-content-sha256:$emptyHash\nx-amz-date:$amzDate\n\n" +
                "host;x-amz-content-sha256;x-amz-date\n$emptyHash"
        val scope = "$day/$REGION/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val signingKey = hmac(hmac(hmac(hmac("AWS4$secretKey".toByteArray(), day), REGION), "s3"), "aws4_request")
        val signature = hmac(signingKey, toSign).joinToString("") { "%02x".format(it) }
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 2_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("x-amz-date", amzDate)
            conn.setRequestProperty("x-amz-content-sha256", emptyHash)
            conn.setRequestProperty(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, " +
                    "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature",
            )
            conn.doOutput = true
            conn.outputStream.close()
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun hmac(
        key: ByteArray,
        data: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(data.toByteArray())
    }
}
