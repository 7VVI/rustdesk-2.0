package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import androidx.annotation.Keep

/**
 * Public entry point for embedding the RustDesk controlled-end (被控端) into a
 * host application.
 *
 * Typical usage from a host app:
 * ```kotlin
 * // Use the official (built-in) rendezvous server.
 * RDMainRunner.start(context, "", "")
 *
 * // Use a self-hosted server, passing config at runtime.
 * RDMainRunner.start(context, idServer, relayServer, key, "", "")
 * ```
 *
 * All parameters are optional (empty string = not set). When a self-hosted
 * server is provided it is injected at startup; UDP hole punching and P2P
 * direct connection are enabled by default (see the Dart side `custom_config`).
 *
 * There is intentionally no registration/authorization layer.
 */
@Keep
object RDMainRunner {

    @Keep
    const val KEY_ID_SERVER = "idServer" // id/rendezvous server

    @Keep
    const val KEY_RELAY_SERVER = "relayServer" // relay server

    @Keep
    const val KEY_SERVER_KEY = "key" // server public key

    @Keep
    const val KEY_ID = "id" // fixed device id (optional)

    @Keep
    const val KEY_PASSWORD = "password" // permanent password (optional)

    /**
     * Build the launch [Intent] without starting it. Useful when the host wants
     * to add extra flags before launching.
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    fun getIntent(
        context: Context,
        idServer: String = "",
        relayServer: String = "",
        key: String = "",
        id: String = "",
        password: String = ""
    ): Intent {
        return RDMainActivity.getIntent(context, idServer, relayServer, key, id, password)
    }

    /**
     * Start the controlled-end using the official built-in server.
     * [id] / [password] may be empty.
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    fun start(context: Context, id: String = "", password: String = "") {
        RDMainActivity.start(context, "", "", "", id, password)
    }

    /**
     * Start the controlled-end using a self-hosted server.
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    fun start(
        context: Context,
        idServer: String,
        relayServer: String,
        key: String,
        id: String = "",
        password: String = ""
    ) {
        RDMainActivity.start(context, idServer, relayServer, key, id, password)
    }

    /**
     * Start with a pre-built [Intent] (see [getIntent]).
     */
    @JvmStatic
    @Keep
    fun start(context: Context, intent: Intent) {
        RDMainActivity.start(context, intent)
    }
}
