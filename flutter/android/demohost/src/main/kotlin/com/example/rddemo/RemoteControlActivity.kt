package com.example.rddemo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carriez.flutter_hbb.RDMainRunner

/**
 * 远程控制(控制端)入口 —— 直接进入 RustDesk "连接(ConnectionPage)" 界面。
 *
 * 服务器配置读 [Prefs]：若填了自建 idServer 则用自建服务器，否则走官方服务器。
 * 进入后用户输入对端机号即可发起远程会话。
 */
class RemoteControlActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val idServer = Prefs.getIdServer(this)
        val relay = Prefs.getRelayServer(this)
        val key = Prefs.getServerKey(this)
        if (idServer.isNotEmpty()) {
            RDMainRunner.startConnectScreen(this, idServer, relay, key)
        } else {
            RDMainRunner.startConnectScreen(this)
        }
        finish()
    }
}
