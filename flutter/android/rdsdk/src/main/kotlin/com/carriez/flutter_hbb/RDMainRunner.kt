package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import androidx.annotation.Keep

/**
 * Public entry point for embedding individual RustDesk screens into a host
 * application. Instead of launching the whole tabbed client, the host picks
 * exactly the screen it needs:
 *
 * ```kotlin
 * // Controlled-end (被控端): only the "Share screen" (ServerPage).
 * RDMainRunner.startServerScreen(context, /*id*/"", /*password*/"")                 // official server
 * RDMainRunner.startServerScreen(context, idServer, relayServer, key, id, password) // self-hosted
 *
 * // Control-end (控制端): only the "Connection" (ConnectionPage).
 * RDMainRunner.startConnectScreen(context)                             // official server
 * RDMainRunner.startConnectScreen(context, idServer, relayServer, key) // self-hosted
 * ```
 *
 * All server parameters are optional (empty string = not set). When a
 * self-hosted server is provided it is injected at startup; UDP hole punching
 * and P2P direct connection are enabled by default (see the Dart side
 * `custom_config`).
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

    // ---------------------------------------------------------------------
    // Controlled-end (被控端): "Share screen" (ServerPage)
    // ---------------------------------------------------------------------

    /**
     * Start the controlled-end "Share screen" using the official built-in
     * server. [id] / [password] may be empty.
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    fun startServerScreen(context: Context, id: String = "", password: String = "") {
        RDServerActivity.start(context, "", "", "", id, password)
    }

    /**
     * Start the controlled-end "Share screen" using a self-hosted server.
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    fun startServerScreen(
        context: Context,
        idServer: String,
        relayServer: String,
        key: String,
        id: String = "",
        password: String = ""
    ) {
        RDServerActivity.start(context, idServer, relayServer, key, id, password)
    }

    // ---------------------------------------------------------------------
    // Control-end (控制端): "Connection" (ConnectionPage)
    // ---------------------------------------------------------------------

    /**
     * Start the control-end "Connection" screen using the official built-in
     * server. The user enters a peer id and connects out; the remote session is
     * pushed within the same Flutter Navigator.
     */
    @JvmStatic
    @Keep
    fun startConnectScreen(context: Context) {
        RDConnectActivity.start(context, "", "", "")
    }

    /**
     * Start the control-end "Connection" screen using a self-hosted server.
     */
    @JvmStatic
    @Keep
    fun startConnectScreen(
        context: Context,
        idServer: String,
        relayServer: String,
        key: String
    ) {
        RDConnectActivity.start(context, idServer, relayServer, key)
    }

    // ---------------------------------------------------------------------
    // Intent builder (controlled-end) + deprecated compatibility aliases
    // ---------------------------------------------------------------------

    /**
     * Build the controlled-end launch [Intent] without starting it. Useful when
     * the host wants to add extra flags before launching.
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
        return RDServerActivity.getIntent(context, idServer, relayServer, key, id, password)
    }

    /**
     * @deprecated Use [startServerScreen] instead. Kept as a compatibility
     * alias for the controlled-end "Share screen".
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    @Deprecated(
        "Use startServerScreen(...)",
        ReplaceWith("startServerScreen(context, id, password)")
    )
    fun start(context: Context, id: String = "", password: String = "") {
        startServerScreen(context, id, password)
    }

    /**
     * @deprecated Use [startServerScreen] instead. Kept as a compatibility
     * alias for the controlled-end "Share screen" with a self-hosted server.
     */
    @JvmStatic
    @JvmOverloads
    @Keep
    @Deprecated(
        "Use startServerScreen(...)",
        ReplaceWith("startServerScreen(context, idServer, relayServer, key, id, password)")
    )
    fun start(
        context: Context,
        idServer: String,
        relayServer: String,
        key: String,
        id: String = "",
        password: String = ""
    ) {
        startServerScreen(context, idServer, relayServer, key, id, password)
    }

    /**
     * @deprecated Start with a pre-built [Intent] (see [getIntent]).
     */
    @JvmStatic
    @Keep
    @Deprecated("Use startServerScreen(...) / startConnectScreen(...)")
    fun start(context: Context, intent: Intent) {
        RDMainActivity.start(context, intent)
    }
}
