package com.example.rddemo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 服务器设置页：运行时填写自建服务器信息并本地保存。
 * 被控端启动时会读取这里的配置注入到 rustdesk 核心。
 */
class ServerSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "服务器设置"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        fun field(label: String, value: String): EditText {
            root.addView(TextView(this).apply {
                text = label
                setPadding(0, 16, 0, 4)
            })
            val et = EditText(this).apply { setText(value) }
            root.addView(et)
            return et
        }

        val idServer = field("ID Server (idServer)", Prefs.getIdServer(this))
        val relay = field("Relay Server (relayServer)", Prefs.getRelayServer(this))
        val key = field("Server Key (key，可空)", Prefs.getServerKey(this))

        root.addView(TextView(this).apply {
            setPadding(0, 24, 0, 8)
            text = "全部留空则使用 RustDesk 官方默认服务器"
        })
        root.addView(Button(this).apply {
            text = "保存"
            setOnClickListener {
                Prefs.saveServer(
                    this@ServerSettingsActivity,
                    idServer.text.toString().trim(),
                    relay.text.toString().trim(),
                    key.text.toString().trim()
                )
                Toast.makeText(this@ServerSettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        setContentView(root)
    }
}
