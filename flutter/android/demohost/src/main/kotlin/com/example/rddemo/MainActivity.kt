package com.example.rddemo

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.carriez.flutter_hbb.RDMainRunner

/**
 * Home screen: the device (机号) list.
 *
 *  - "+ 新增设备"    : input a 机号 and add it to the list
 *  - long-press item : delete it
 *  - tap item        : launch RustDesk 被控端 (share screen) directly via
 *                      [RDMainRunner.startServerScreen]; no intermediate page.
 *  - "远程控制"       : [RemoteControlActivity] -> RustDesk 控制端 (connection)
 *  - "服务器设置"     : [ServerSettingsActivity]
 */
class MainActivity : AppCompatActivity() {

    private val devices = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "设备列表"
        Prefs.ensureDefaults(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // top entries: 远程控制 / 服务器设置
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "远程控制"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, RemoteControlActivity::class.java))
            }
        })
        actions.addView(Button(this).apply {
            text = "服务器设置"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ServerSettingsActivity::class.java))
            }
        })
        root.addView(actions)

        // add device
        root.addView(Button(this).apply {
            text = "+ 新增设备"
            setOnClickListener { showAddDialog() }
        })

        root.addView(TextView(this).apply {
            text = "点击设备 → 作为被控端上线(共享屏幕)；长按删除"
            setPadding(0, 16, 0, 16)
        })

        // device list
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devices)
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            startControlled(devices[pos])
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(devices[pos]); true
        }
        root.addView(list)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        devices.clear()
        devices.addAll(Prefs.getDevices(this))
        adapter.notifyDataSetChanged()
    }

    private fun showAddDialog() {
        val input = EditText(this).apply { hint = "请输入机号(设备ID)" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("新增设备")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val id = input.text.toString().trim()
                when {
                    id.isEmpty() -> toast("机号不能为空")
                    Prefs.addDevice(this, id) -> reload()
                    else -> toast("该机号已存在")
                }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            // Register the dialog's window so remote input (taps + keyboard)
            // reaches it. The SDK can't auto-enumerate dialog windows pre-API33.
            dialog.window?.decorView?.let {
                com.carriez.flutter_hbb.RdForegroundActivityTracker.registerDialogWindow(it)
            }
        }
        dialog.setOnDismissListener {
            dialog.window?.decorView?.let {
                com.carriez.flutter_hbb.RdForegroundActivityTracker.unregisterDialogWindow(it)
            }
        }
        dialog.show()
    }

    private fun confirmDelete(id: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除设备")
            .setMessage("确定删除机号 $id ?")
            .setPositiveButton("删除") { _, _ ->
                Prefs.removeDevice(this, id); reload()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.let {
                com.carriez.flutter_hbb.RdForegroundActivityTracker.registerDialogWindow(it)
            }
        }
        dialog.setOnDismissListener {
            dialog.window?.decorView?.let {
                com.carriez.flutter_hbb.RdForegroundActivityTracker.unregisterDialogWindow(it)
            }
        }
        dialog.show()
    }

    /**
     * Bring the local device online as the RustDesk 被控端 and jump straight to
     * the original RustDesk share-screen UI ([RDMainRunner]).
     * Server config comes from [ServerSettingsActivity]; empty -> official server.
     * [deviceId] is used as the fixed device id (机号); password is fixed to 123456.
     */
    private fun startControlled(deviceId: String) {
        val idServer = Prefs.getIdServer(this)
        val relay = Prefs.getRelayServer(this)
        val key = Prefs.getServerKey(this)
        val password = "123456"
        if (idServer.isNotEmpty()) {
            RDMainRunner.startServerScreen(this, idServer, relay, key, deviceId, password)
        } else {
            RDMainRunner.startServerScreen(this, deviceId, password)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
