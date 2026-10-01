package com.focusblock.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.focusblock.app.blocking.DomainRules
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var store: BlockListStore
    private lateinit var statusText: TextView
    private lateinit var enableSwitch: MaterialSwitch
    private lateinit var input: TextInputEditText
    private lateinit var countText: TextView
    private lateinit var domainList: LinearLayout

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startBlocker()
            } else {
                updateStatus(false)
                toast("VPN permission is needed to block sites.")
            }
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BlockerVpnService.ACTION_STATE) {
                updateStatus(intent.getBooleanExtra(BlockerVpnService.EXTRA_RUNNING, false))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = BlockListStore(this)

        statusText = findViewById(R.id.statusText)
        enableSwitch = findViewById(R.id.enableSwitch)
        input = findViewById(R.id.domainInput)
        countText = findViewById(R.id.countText)
        domainList = findViewById(R.id.domainList)

        findViewById<MaterialButton>(R.id.addButton).setOnClickListener { addDomain() }

        enableSwitch.setOnCheckedChangeListener(switchListener)

        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        updateStatus(BlockerVpnService.isRunning)
        val filter = IntentFilter(BlockerVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun startBlocker() {
        startService(Intent(this, BlockerVpnService::class.java).setAction(BlockerVpnService.ACTION_START))
        updateStatus(true)
    }

    private fun addDomain() {
        val raw = input.text?.toString().orEmpty()
        val normalized = DomainRules.normalize(raw)
        if (normalized == null) {
            input.error = "Enter a domain like reddit.com"
            return
        }
        val existing = store.getAll()
        if (normalized in existing) {
            input.error = "Already in your list"
            return
        }
        store.add(normalized)
        input.text?.clear()
        input.error = null
        refreshList()
        notifyServiceListChanged()
        toast("Blocked: $normalized")
    }

    private fun removeDomain(domain: String) {
        store.remove(domain)
        refreshList()
        notifyServiceListChanged()
    }

    private fun notifyServiceListChanged() {
        startService(
            Intent(this, BlockerVpnService::class.java)
                .setAction(BlockerVpnService.ACTION_LIST_CHANGED)
        )
    }

    private fun refreshList() {
        val domains = store.getAll()
        countText.text = getString(R.string.blocked_sites_count, domains.size)
        domainList.removeAllViews()
        if (domains.isEmpty()) {
            val hint = TextView(this).apply {
                text = getString(R.string.empty_list_hint)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.row_padding), 0, resources.getDimensionPixelSize(R.dimen.row_padding))
            }
            domainList.addView(hint)
            return
        }
        val inflater = layoutInflater
        domains.forEach { domain ->
            val row = inflater.inflate(R.layout.item_blocked_domain, domainList, false)
            row.findViewById<TextView>(R.id.domainText).text = domain
            row.findViewById<MaterialButton>(R.id.removeButton).setOnClickListener {
                removeDomain(domain)
            }
            domainList.addView(row)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.divider_height),
                )
                setBackgroundColor(getColor(R.color.divider))
            }
            domainList.addView(divider)
        }
    }

    private fun updateStatus(running: Boolean) {
        enableSwitch.setOnCheckedChangeListener(null)
        enableSwitch.isChecked = running
        enableSwitch.setOnCheckedChangeListener(switchListener)
        statusText.text = if (running) {
            getString(R.string.status_on, store.getAll().size)
        } else {
            getString(R.string.status_off)
        }
    }

    private val switchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            val prepareIntent = VpnService.prepare(this@MainActivity)
            if (prepareIntent != null) {
                vpnConsentLauncher.launch(prepareIntent)
            } else {
                startBlocker()
            }
        } else {
            startService(
                Intent(this@MainActivity, BlockerVpnService::class.java)
                    .setAction(BlockerVpnService.ACTION_STOP)
            )
            updateStatus(false)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
