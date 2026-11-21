package app.roadbeacon.mesh

import android.util.Log
import app.roadbeacon.BuildConfig
import app.roadbeacon.storage.Settings
import io.github.jooray.meshcore.localapi.ClientIdentity
import io.github.jooray.meshcore.localapi.CursorStore
import io.github.jooray.meshcore.localapi.KeyStore
import io.github.jooray.meshcore.localapi.MeshApiSession
import io.github.jooray.meshcore.localapi.MeshLogger
import io.github.jooray.meshcore.localapi.Route
import io.github.jooray.meshcore.localapi.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The radio through a MeshCore Local API host (MeshCore Open, meshcored), using the shared client
 * library (https://github.com/jooray/meshcore-local-api). The library pairs, subscribes to
 * `#roadstr`, follows the radio, asks for the channel, keeps the replay cursor and reconnects;
 * this class only maps it onto [MeshTransport].
 */
class LocalApiTransport(
    private val settings: Settings,
    scope: CoroutineScope,
) : MeshTransport {
    val session = MeshApiSession(
        MeshApiSession.Config(
            clientName = "Roadbeacon",
            clientVersion = BuildConfig.VERSION_NAME,
            channel = CHANNEL,
            dataTypes = listOf(DATA_TYPE_ROAD_EVENTS),
            url = settings.meshUrl,
        ),
        keyStore = keyStore(settings),
        scope = scope,
        cursorStore = SettingsCursorStore(settings),
        logger = AndroidLogger,
    )

    val state: StateFlow<SessionState> get() = session.state

    /** What the host's approval dialog shows for this app, e.g. `AB12 CD34 EF56 7890`. */
    val fingerprint: String get() = session.fingerprint

    override val connected: StateFlow<Boolean> get() = session.connected

    override val datagrams: Flow<MeshDatagram> =
        session.datagrams.map { MeshDatagram(it.payload, it.snrDb ?: 0.0, it.receivedAt, it.hops) }

    override fun start() = session.start()

    override fun stop() = session.stop()

    /** The radio's modulation from `hello`/`device`, for the airtime budget; null until known. */
    override val radio: RadioParams?
        get() = session.device.value?.device?.radio?.let { RadioParams(it.sf, it.bwHz, it.cr) }

    /** An own event or a bridged one: flooded within the configured region, or the radio's scope. */
    override suspend fun send(frame: ByteArray): Boolean =
        isConnected() && session.sendData(frame, region = settings.meshRegion.trim().ifEmpty { null }).isOk

    /**
     * The region for a bridge summary about [lat]/[lon]: the configured flood region, or the one
     * the host resolves for the point (capability `regions`). Null means no summary.
     */
    override suspend fun summaryRegion(lat: Double, lon: Double): String? {
        settings.meshRegion.trim().ifEmpty { null }?.let { return it }
        if (!isConnected()) return null
        return session.client?.resolveRegion(lat, lon)?.valueOrNull?.name
    }

    /** A summary: only ever with an explicit region, never the radio's default flood scope. */
    override suspend fun sendScoped(frame: ByteArray, region: String): Boolean =
        isConnected() && session.sendData(frame, region = region).isOk

    /** True when the host takes `path` in `channel.sendData` (`tx_path_data`). */
    override val zeroHopCapable: Boolean get() = session.canSendPath

    /** Zero-hop (`"path": ""`); the library refuses it, never floods, without `tx_path_data`. */
    override suspend fun sendZeroHop(frame: ByteArray): Boolean =
        isConnected() && session.sendData(frame, route = Route.ZeroHop).isOk

    private fun isConnected() = session.state.value is SessionState.Connected

    private class SettingsCursorStore(private val settings: Settings) : CursorStore {
        override fun load(): String? = settings.meshCursor
        override fun save(cursor: String) { settings.meshCursor = cursor }
    }

    private object AndroidLogger : MeshLogger {
        override fun debug(tag: String, message: String) { Log.d(tag, message) }
        override fun info(tag: String, message: String) { Log.i(tag, message) }
        override fun warn(tag: String, message: String, error: Throwable?) { Log.w(tag, message, error) }
    }

    companion object {
        const val CHANNEL = "#roadstr"

        /**
         * "Road Events", allocated in MeshCore's registry (`docs/number_allocations.md`,
         * meshcore-dev/MeshCore#3750). The only data type this app sends and accepts.
         */
        const val DATA_TYPE_ROAD_EVENTS = 0x0150

        /** The pairing key, kept in the same setting and JSON as before, so pairing survives. */
        fun keyStore(settings: Settings): KeyStore = object : KeyStore {
            override fun load(): String? = settings.meshIdentity
            override fun save(value: String) { settings.meshIdentity = value }
        }

        /** The fingerprint without a running connection (creates the key on first use). */
        suspend fun fingerprint(settings: Settings): String = withContext(Dispatchers.IO) {
            ClientIdentity.loadOrCreate(keyStore(settings), AndroidLogger).fingerprint
        }
    }
}
