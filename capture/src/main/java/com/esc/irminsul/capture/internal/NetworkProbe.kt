package com.esc.irminsul.capture.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Reads what the device's network looks like before the VPN takes it over: the
 * real DNS servers to forward to, and the diagnostics that make a broken
 * capture explainable from logcat alone.
 */
internal object NetworkProbe {

    private const val TAG = "NetworkProbe"

    /**
     * Used when the active network publishes no IPv4 DNS at all. Only one
     * address: nothing here probes reachability, so a longer list would be
     * decoration — the old loop returned the first entry unconditionally.
     */
    private const val FALLBACK_DNS_V4 = "223.5.5.5"

    fun dnsServerV4(context: Context): String =
        firstDnsAddress(context) { it is Inet4Address } ?: FALLBACK_DNS_V4.also {
            Log.w(TAG, "No system IPv4 DNS, forwarding to the fallback $FALLBACK_DNS_V4")
        }

    /** Null means this network has no IPv6 DNS, so the VPN skips IPv6. */
    fun dnsServerV6(context: Context): String? =
        firstDnsAddress(context) { it is Inet6Address }

    /** True when the network has a routable IPv6 address (link-local alone is not enough). */
    fun hasIPv6(context: Context): Boolean =
        linkProperties(context)?.linkAddresses.orEmpty()
            .any { it.address is Inet6Address && !it.address.isLinkLocalAddress }

    /** A one-line summary of the private-DNS configuration, for diagnostics. */
    fun privateDnsMode(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "unknown"
        val lp = linkProperties(context) ?: return "unknown"
        return when {
            lp.privateDnsServerName != null -> "strict (${lp.privateDnsServerName})"
            lp.isPrivateDnsActive -> "opportunistic"
            else -> "off"
        }
    }

    fun networkType(context: Context): String {
        val caps = activeNetworkCapabilities(context) ?: return "No active network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
    }

    private fun firstDnsAddress(context: Context, matches: (java.net.InetAddress) -> Boolean): String? =
        linkProperties(context)?.dnsServers?.firstOrNull(matches)?.hostAddress

    private fun connectivityManager(context: Context): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun activeNetwork(context: Context): Network? =
        connectivityManager(context)?.activeNetwork

    private fun linkProperties(context: Context): LinkProperties? =
        try {
            activeNetwork(context)?.let { connectivityManager(context)?.getLinkProperties(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read link properties", e)
            null
        }

    private fun activeNetworkCapabilities(context: Context): NetworkCapabilities? =
        try {
            activeNetwork(context)?.let { connectivityManager(context)?.getNetworkCapabilities(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read network capabilities", e)
            null
        }
}
