package com.toptrumps.nsdspike

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Throwaway manual spike for slice 0 of the NSD investigation.
 *
 * Deliberate choices baked in per the TDD (do not "fix" these, they are the point):
 *  - AndroidManifest declares ONLY INTERNET + ACCESS_NETWORK_STATE, no runtime
 *    permission requests anywhere in this file.
 *  - No MulticastLock is ever acquired.
 *  - A fresh listener instance is created for every registerService/discoverServices/
 *    resolveService call - reusing a listener throws "listener already in use".
 *  - "Resolve All" fires resolveService for every discovered item back-to-back with
 *    no delay, specifically to provoke FAILURE_ALREADY_ACTIVE.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NsdSpike"
        private const val SERVICE_TYPE = "_toptrumps._tcp"
    }

    private lateinit var nsdManager: NsdManager
    private lateinit var serviceNameEdit: EditText
    private lateinit var discoveredListLayout: LinearLayout
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    // serviceName -> NsdServiceInfo as received from onServiceFound (unresolved).
    private val discovered = ConcurrentHashMap<String, NsdServiceInfo>()

    // serviceName -> the row View shown on screen, so onServiceLost can remove it.
    private val discoveredRows = ConcurrentHashMap<String, View>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        serviceNameEdit = findViewById(R.id.serviceNameEdit)
        discoveredListLayout = findViewById(R.id.discoveredListLayout)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        logTextView.movementMethod = ScrollingMovementMethod()

        serviceNameEdit.setText("Spike-" + randomSuffix())

        findViewById<Button>(R.id.registerButton).setOnClickListener { onRegisterClicked() }
        findViewById<Button>(R.id.discoverButton).setOnClickListener { onDiscoverClicked() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { onStopClicked() }
        findViewById<Button>(R.id.resolveAllButton).setOnClickListener { onResolveAllClicked() }

        appendLog("App started. SDK_INT=${Build.VERSION.SDK_INT}, MODEL=${Build.MODEL}")
    }

    private fun randomSuffix(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            val line = "${timeFormat.format(System.currentTimeMillis())}  $message\n"
            logTextView.append(line)
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------------------------------------------------------------------
    // Register
    // ---------------------------------------------------------------------

    private fun onRegisterClicked() {
        val requestedName = serviceNameEdit.text.toString().ifBlank { "Spike-" + randomSuffix() }

        // NsdServiceInfo.setPort(0) is rejected, so bind a real ServerSocket first
        // and read back the OS-assigned localPort. The socket never needs to accept
        // a connection for this spike - it only needs to hold a real port number.
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(0)
        } catch (e: Exception) {
            appendLog("Failed to bind ServerSocket: $e")
            return
        }
        val port = serverSocket!!.localPort
        appendLog("Bound ServerSocket on localPort=$port")

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = requestedName
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        // Fresh listener every call - never reuse one across registerService calls.
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                appendLog(
                    "onServiceRegistered: actualServiceName='${nsdServiceInfo.serviceName}' " +
                        "(requested='$requestedName')"
                )
            }

            override fun onRegistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                appendLog("onRegistrationFailed: errorCode=$errorCode requested='$requestedName'")
            }

            override fun onServiceUnregistered(nsdServiceInfo: NsdServiceInfo) {
                appendLog("onServiceUnregistered: serviceName='${nsdServiceInfo.serviceName}'")
            }

            override fun onUnregistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                appendLog("onUnregistrationFailed: errorCode=$errorCode serviceName='${nsdServiceInfo.serviceName}'")
            }
        }
        registrationListener = listener

        appendLog("Calling registerService(name='$requestedName', type='$SERVICE_TYPE', port=$port)")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // ---------------------------------------------------------------------
    // Discover
    // ---------------------------------------------------------------------

    private fun onDiscoverClicked() {
        if (discovering) {
            appendLog("Discover tapped but already discovering - ignoring")
            return
        }

        // Fresh listener every call - never reuse one across discoverServices calls.
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                discovering = true
                appendLog("onDiscoveryStarted: regType='$regType'")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Log the raw serviceType exactly as received - this is how a
                // trailing dot (e.g. "_toptrumps._tcp." vs "_toptrumps._tcp") is observed.
                appendLog(
                    "onServiceFound: serviceName='${serviceInfo.serviceName}' " +
                        "rawServiceType='${serviceInfo.serviceType}'"
                )
                discovered[serviceInfo.serviceName] = serviceInfo
                runOnUiThread { addOrReplaceRow(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                appendLog("onServiceLost: serviceName='${serviceInfo.serviceName}'")
                discovered.remove(serviceInfo.serviceName)
                runOnUiThread { removeRow(serviceInfo.serviceName) }
            }

            override fun onDiscoveryStopped(regType: String) {
                discovering = false
                appendLog("onDiscoveryStopped: regType='$regType'")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                discovering = false
                appendLog("onStartDiscoveryFailed: regType='$regType' errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                appendLog("onStopDiscoveryFailed: regType='$regType' errorCode=$errorCode")
            }
        }
        discoveryListener = listener

        appendLog("Calling discoverServices(type='$SERVICE_TYPE')")
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // ---------------------------------------------------------------------
    // Stop
    // ---------------------------------------------------------------------

    private fun onStopClicked() {
        discoveryListener?.let { listener ->
            try {
                appendLog("Calling stopServiceDiscovery()")
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                appendLog("stopServiceDiscovery() threw: $e")
            }
            discoveryListener = null
        }
        registrationListener?.let { listener ->
            try {
                appendLog("Calling unregisterService()")
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                appendLog("unregisterService() threw: $e")
            }
            registrationListener = null
        }
    }

    // ---------------------------------------------------------------------
    // Resolve
    // ---------------------------------------------------------------------

    private fun resolveOne(serviceInfo: NsdServiceInfo) {
        // Fresh listener every call - never reuse one across resolveService calls.
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                val note = if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    " (FAILURE_ALREADY_ACTIVE)"
                } else {
                    ""
                }
                appendLog("onResolveFailed: serviceName='${info.serviceName}' errorCode=$errorCode$note")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                // Never construct a .local hostname manually - take the InetAddress
                // straight off the resolved NsdServiceInfo.
                val hostAddress = info.host?.hostAddress
                appendLog("onServiceResolved: serviceName='${info.serviceName}' host=$hostAddress port=${info.port}")
            }
        }
        appendLog("Calling resolveService(serviceName='${serviceInfo.serviceName}')")
        nsdManager.resolveService(serviceInfo, listener)
    }

    private fun onResolveAllClicked() {
        val snapshot = discovered.values.toList()
        appendLog("Resolve All: firing resolveService for ${snapshot.size} item(s) back-to-back")
        // Deliberately tight loop, no delay between calls - this is meant to provoke
        // FAILURE_ALREADY_ACTIVE from concurrent resolveService calls.
        for (info in snapshot) {
            resolveOne(info)
        }
    }

    // ---------------------------------------------------------------------
    // Discovered list UI (plain rows in a LinearLayout)
    // ---------------------------------------------------------------------

    private fun addOrReplaceRow(serviceInfo: NsdServiceInfo) {
        removeRow(serviceInfo.serviceName)
        val row = TextView(this).apply {
            text = "${serviceInfo.serviceName}   [${serviceInfo.serviceType}]"
            setPadding(16, 24, 16, 24)
            setBackgroundColor(0xFFFFFFFF.toInt())
            setOnClickListener { resolveOne(serviceInfo) }
        }
        discoveredRows[serviceInfo.serviceName] = row
        discoveredListLayout.addView(row)
    }

    private fun removeRow(serviceName: String) {
        discoveredRows.remove(serviceName)?.let { discoveredListLayout.removeView(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignore - this spike doesn't care about clean socket teardown.
        }
    }
}
