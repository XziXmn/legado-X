package io.legado.app.model.chapterComment

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

data class HttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

/** DNS snapshot used by both the first-page request and the embedded WebView. */
data class SourceScopedNetworkContext(
    val origin: HttpOrigin,
    val pinnedAddresses: List<String>,
)

/** Network boundary for a source-authenticated comment WebView. */
object SourceScopedRequestPolicy {

    private val sensitiveQueryNames = setOf(
        "access_token",
        "authorization",
        "cookie",
        "password",
        "token",
        "api_key",
        "apikey",
    )
    private val metadataHosts = setOf(
        "169.254.169.254",
        "100.100.100.200",
        "metadata.google.internal",
        "fd00:ec2::254",
    )
    private val metadataAddressLiterals = setOf(
        "169.254.169.254",
        "100.100.100.200",
        "fd00:ec2::254",
    )

    fun validateActionUrl(url: String): HttpOrigin {
        require(url.length <= 4_096) { "Chapter comment URL is too long" }
        val uri = parseHttpUri(url)
        require(uri.rawUserInfo == null) { "User info is not allowed in chapter comment URLs" }
        require(!isMetadataHost(uri.host)) { "Cloud metadata addresses are not allowed" }
        val sensitiveName = uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .map { it.substringBefore('=').lowercase(Locale.ROOT) }
            .firstOrNull(sensitiveQueryNames::contains)
        require(sensitiveName == null) { "Credentials must not be placed in chapter comment URLs" }
        return origin(uri)
    }

    fun pinActionUrl(
        url: String,
        expectedOrigin: HttpOrigin,
        resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
    ): SourceScopedNetworkContext {
        val actualOrigin = validateActionUrl(url)
        require(actualOrigin == expectedOrigin) {
            "Chapter comment action must use the summary origin"
        }
        val addresses = runCatching { resolve(actualOrigin.host) }
            .getOrElse { throw IllegalArgumentException("Unable to resolve chapter comment host", it) }
        require(addresses.isNotEmpty()) { "Chapter comment host has no address" }
        require(addresses.none(::isForbiddenAuthenticatedAddress)) {
            "Loopback, link-local, and metadata addresses are not allowed"
        }
        return SourceScopedNetworkContext(
            origin = actualOrigin,
            pinnedAddresses = addresses.mapNotNull(InetAddress::getHostAddress).distinct(),
        )
    }

    fun restoreNetworkContext(
        origin: HttpOrigin,
        pinnedAddresses: List<String>,
    ): SourceScopedNetworkContext {
        require(pinnedAddresses.isNotEmpty()) { "Missing pinned chapter comment addresses" }
        val addresses = pinnedAddresses.map { address ->
            runCatching { InetAddress.getByName(address) }
                .getOrElse { throw IllegalArgumentException("Invalid pinned address", it) }
        }
        require(addresses.none(::isForbiddenAuthenticatedAddress)) {
            "Invalid pinned chapter comment address"
        }
        return SourceScopedNetworkContext(origin, addresses.mapNotNull(InetAddress::getHostAddress).distinct())
    }

    fun origin(url: String): HttpOrigin = origin(parseHttpUri(url))

    fun isSameOrigin(url: String, authenticatedOrigin: HttpOrigin): Boolean {
        return runCatching { origin(url) == authenticatedOrigin }.getOrDefault(false)
    }

    fun canNavigate(url: String, authenticatedOrigin: HttpOrigin): Boolean {
        val uri = runCatching { parseHttpUri(url) }.getOrNull() ?: return false
        return uri.rawUserInfo == null &&
                !isMetadataHost(uri.host) &&
                origin(uri) == authenticatedOrigin
    }

    fun canLoadSubresource(
        url: String,
        authenticatedOrigin: HttpOrigin,
        resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Boolean {
        val uri = runCatching { parseHttpUri(url) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        if (isMetadataHost(uri.host)) return false
        if (origin(uri) == authenticatedOrigin) return true
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (isObviouslyPrivateHost(uri.host)) return false
        val addresses = runCatching { resolve(uri.host) }.getOrElse { return false }
        return addresses.isNotEmpty() && addresses.none(::isPrivateAddress)
    }

    fun isCloudMetadataUrl(url: String): Boolean {
        return runCatching { isMetadataHost(parseHttpUri(url).host) }.getOrDefault(true)
    }

    private fun parseHttpUri(url: String): URI {
        val uri = runCatching { URI(url) }
            .getOrElse { throw IllegalArgumentException("Invalid chapter comment URL", it) }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "Only HTTP(S) chapter comment URLs are supported" }
        require(!uri.host.isNullOrBlank()) { "Chapter comment URL host is required" }
        return uri
    }

    private fun origin(uri: URI): HttpOrigin {
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return HttpOrigin(scheme, uri.host.lowercase(Locale.ROOT).trimEnd('.'), port)
    }

    private fun isMetadataHost(host: String): Boolean {
        return host.lowercase(Locale.ROOT).trim('[', ']', '.').let(metadataHosts::contains)
    }

    private fun isObviouslyPrivateHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        return normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")
    }

    internal fun decodePinnedAddresses(context: SourceScopedNetworkContext): List<InetAddress> {
        return context.pinnedAddresses.map(InetAddress::getByName)
    }

    internal fun isPrivateAddress(address: InetAddress): Boolean {
        if (isForbiddenAuthenticatedAddress(address) || address.isSiteLocalAddress) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 100 && second in 64..127) return true
            if (first == 198 && second in 18..19) return true
        }
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun isForbiddenAuthenticatedAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return metadataAddresses.any { it.address.contentEquals(address.address) }
    }

    private val metadataAddresses by lazy {
        metadataAddressLiterals.mapNotNull { host -> runCatching { InetAddress.getByName(host) }.getOrNull() }
    }
}

/** Pins the authenticated origin and revalidates every cross-origin DNS lookup. */
class SourceScopedDns(
    private val context: SourceScopedNetworkContext,
    private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.lowercase(Locale.ROOT).trimEnd('.') == context.origin.host) {
            return SourceScopedRequestPolicy.decodePinnedAddresses(context)
        }
        val addresses = runCatching { resolve(hostname) }
            .getOrElse { throw UnknownHostException(hostname).apply { initCause(it) } }
        if (addresses.isEmpty() || addresses.any(SourceScopedRequestPolicy::isPrivateAddress)) {
            throw UnknownHostException("Blocked private address for $hostname")
        }
        return addresses
    }
}

/** Uses platform certificate validation instead of the source runtime's permissive HTTP client. */
fun newSourceScopedHttpClient(context: SourceScopedNetworkContext): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .dns(SourceScopedDns(context))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}
