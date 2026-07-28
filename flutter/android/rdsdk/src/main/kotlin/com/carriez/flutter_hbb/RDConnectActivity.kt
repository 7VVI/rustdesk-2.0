package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import androidx.annotation.Keep

/**
 * Embeds ONLY the RustDesk control-end "Connection" screen (Flutter
 * `ConnectionPage`). The user enters a peer id and connects out; the remote
 * session is pushed within the same Flutter Navigator.
 *
 * It reuses [RDMainActivity]'s Flutter engine/channel plumbing but runs the
 * `rdConnectScreen` entry-point, whose channel handler only injects the runtime
 * server config (no controlled service, no media-projection request).
 */
@Keep
class RDConnectActivity : RDMainActivity() {

    override fun getDartEntrypointFunctionName(): String = "rdConnectScreen"

    companion object {
        internal fun getIntent(
            context: Context,
            idServer: String = "",
            relayServer: String = "",
            key: String = ""
        ): Intent = fillExtras(
            Intent(context, RDConnectActivity::class.java),
            idServer, relayServer, key, "", ""
        )

        internal fun start(
            context: Context,
            idServer: String,
            relayServer: String,
            key: String
        ) {
            RDMainActivity.start(context, getIntent(context, idServer, relayServer, key))
        }
    }
}
