package app.roadbeacon.storage

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

class KeyStore(private val settings: Settings) {

    private val secp256k1 = Secp256k1.get()

    fun generateKeyPair(): ByteArray {
        val privKey = ByteArray(32)
        SecureRandom().nextBytes(privKey)
        settings.nsec = privKey.toHex()
        return privKey
    }

    fun importNsec(bech32: String) {
        val privKey = if (bech32.startsWith("nsec1")) {
            Bech32.decode(bech32).second
        } else {
            bech32.hexToBytes()
        }
        require(privKey.size == 32) { "Private key must be 32 bytes" }
        settings.nsec = privKey.toHex()
    }

    fun getPrivateKey(): ByteArray? {
        val hex = settings.nsec ?: return null
        return hex.hexToBytes()
    }

    fun getPublicKey(): ByteArray? {
        val privKey = getPrivateKey() ?: return null
        return derivePublicKey(privKey)
    }

    fun getPublicKeyHex(): String? {
        return getPublicKey()?.toHex()
    }

    fun getNpub(): String? {
        val pubKey = getPublicKey() ?: return null
        return Bech32.encode("npub", pubKey)
    }

    fun getNsec(): String? {
        val privKey = getPrivateKey() ?: return null
        return Bech32.encode("nsec", privKey)
    }

    fun generateEphemeralKeyPair(): Pair<ByteArray, ByteArray> {
        val privKey = ByteArray(32)
        SecureRandom().nextBytes(privKey)
        val pubKey = derivePublicKey(privKey)
        return Pair(privKey, pubKey)
    }

    fun hasKey(): Boolean = settings.nsec != null

    private fun derivePublicKey(privKey: ByteArray): ByteArray {
        val fullPubKey = secp256k1.pubkeyCreate(privKey)
        val compressed = secp256k1.pubKeyCompress(fullPubKey)
        // x-only pubkey: drop the first byte (parity prefix)
        return compressed.copyOfRange(1, 33)
    }
}

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun encode(hrp: String, data: ByteArray): String {
        val converted = convertBits(data, 8, 5, true)
        val checksum = createChecksum(hrp, converted)
        val combined = converted + checksum
        val sb = StringBuilder(hrp.length + 1 + combined.size)
        sb.append(hrp)
        sb.append('1')
        for (b in combined) {
            sb.append(CHARSET[b.toInt()])
        }
        return sb.toString()
    }

    fun decode(bech32: String): Pair<String, ByteArray> {
        val pos = bech32.lastIndexOf('1')
        require(pos >= 1) { "Invalid bech32 string" }
        val hrp = bech32.substring(0, pos)
        val dataStr = bech32.substring(pos + 1)
        val data = ByteArray(dataStr.length) { i ->
            val idx = CHARSET.indexOf(dataStr[i])
            require(idx >= 0) { "Invalid character in bech32" }
            idx.toByte()
        }
        require(verifyChecksum(hrp, data)) { "Invalid bech32 checksum" }
        val payload = data.copyOfRange(0, data.size - 6)
        val converted = convertBits(payload, 5, 8, false)
        return Pair(hrp, converted)
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (b in data) {
            val value = b.toInt() and 0xFF
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else {
            require(bits < fromBits) { "Invalid padding" }
            require(((acc shl (toBits - bits)) and maxv) == 0) { "Non-zero padding" }
        }
        return ret.toByteArray()
    }

    private fun polymod(values: ByteArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor (v.toInt() and 0xFF)
            for (i in 0 until 5) {
                if ((b shr i) and 1 == 1) {
                    chk = chk xor gen[i]
                }
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): ByteArray {
        val ret = ByteArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            ret[i] = (hrp[i].code shr 5).toByte()
            ret[i + hrp.length + 1] = (hrp[i].code and 31).toByte()
        }
        ret[hrp.length] = 0
        return ret
    }

    private fun createChecksum(hrp: String, data: ByteArray): ByteArray {
        val values = hrpExpand(hrp) + data + ByteArray(6)
        val polymod = polymod(values) xor 1
        return ByteArray(6) { i -> ((polymod shr (5 * (5 - i))) and 31).toByte() }
    }

    private fun verifyChecksum(hrp: String, data: ByteArray): Boolean {
        return polymod(hrpExpand(hrp) + data) == 1
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi != -1 && lo != -1) { "Invalid hex character at index ${i * 2}" }
        ((hi shl 4) + lo).toByte()
    }
}
