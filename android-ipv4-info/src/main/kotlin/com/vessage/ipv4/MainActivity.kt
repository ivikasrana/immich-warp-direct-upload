package com.vessage.ipv4

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

private const val TARGET_HOST = "photos.example.com"

class MainActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(out)
        })
    }

    override fun onResume() {
        super.onResume()
        val networks = networks()
        out.text = networks
        thread {
            val result = resolve(TARGET_HOST)
            runOnUiThread { out.text = "$networks\n\n$TARGET_HOST\n$result" }
        }
    }

    private fun networks(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val types = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
            NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET"
        )
        return cm.allNetworks.joinToString("\n\n") { n ->
            val lp = cm.getLinkProperties(n)
            val caps = cm.getNetworkCapabilities(n)
            val type = types.filter { caps?.hasTransport(it.first) == true }.joinToString("+") { it.second }
            val ips = lp?.linkAddresses.orEmpty()
                .filter { it.address is Inet4Address }
                .joinToString { "${it.address.hostAddress}/${it.prefixLength}" }
            val dns = lp?.dnsServers.orEmpty()
                .filterIsInstance<Inet4Address>()
                .joinToString { it.hostAddress.orEmpty() }
            val gateway = lp?.routes.orEmpty()
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            "${if (n == active) "* " else ""}$type ${lp?.interfaceName}\nIPv4: $ips\nGateway: $gateway\nDNS: $dns"
        }
    }

    private fun resolve(host: String): String = try {
        InetAddress.getAllByName(host)
            .filterIsInstance<Inet4Address>()
            .joinToString("\n") { "${it.hostAddress}  443: ${reachable(it)}" }
            .ifEmpty { "no IPv4" }
    } catch (e: Exception) {
        e.message ?: "failed"
    }

    private fun reachable(address: InetAddress): String = try {
        Socket().use { it.connect(InetSocketAddress(address, 443), 3000) }
        "open"
    } catch (e: Exception) {
        "closed"
    }
}
