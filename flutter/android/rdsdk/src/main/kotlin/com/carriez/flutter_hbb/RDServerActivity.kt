package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import androidx.annotation.Keep

/**
 * Embeds ONLY the RustDesk controlled-end "Share screen" (Flutter `ServerPage`).
 *
 * Behaves exactly like [RDMainActivity] as a controlled-end (media projection,
 * input service, runtime server-config injection via `custom_config`), but the
 * Flutter side runs the `rdServerScreen` entry-point which shows a single page
 * without the client's bottom navigation tabs.
 */
@Keep
class RDServerActivity : RDMainActivity() {

    override fun getDartEntrypointFunctionName(): String = "rdServerScreen"

    companion object {
        internal fun getIntent(
            context: Context,
            idServer: String = "",
            relayServer: String = "",
            key: String = "",
            id: String = "",
            password: String = "",
            inputMode: String = "inapp"
        ): Intent = fillExtras(
            Intent(context, RDServerActivity::class.java),
            idServer, relayServer, key, id, password, inputMode
        )

        internal fun start(
            context: Context,
            idServer: String,
            relayServer: String,
            key: String,
            id: String,
            password: String,
            inputMode: String = "inapp"
        ) {
            RDMainActivity.start(
                context,
                getIntent(context, idServer, relayServer, key, id, password, inputMode)
            )
        }
    }
}
