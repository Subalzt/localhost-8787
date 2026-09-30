package dev.periy.bridge.net

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The tunnel's primitives (docs/tunnel-protocol.md): X25519, HKDF-SHA256, and a frame cipher of
 * SHAKE256 keystream plus HMAC-SHA256 tag. The same as the laptop helper's, which has only what
 * Python's standard library has: SHAKE256 and HMAC, no AES.
 */
object TunnelCrypto {

    // ------------------------------------------------------------------ X25519 (RFC 7748)

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val PM2 = P.subtract(BigInteger.valueOf(2))

    /** Plain big integers, not constant-time: every key is ephemeral and used once. */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = scalar.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kk = le(k)
        val uu = u.copyOf(32).also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = le(uu).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = if (kk.testBit(t)) 1 else 0
            if ((swap xor bit) == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = bit
            val a = x2.add(z2); val b = x2.subtract(z2)
            val c = x3.add(z3); val d = x3.subtract(z3)
            val aa = a.multiply(a).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            val s = da.add(cb); val m = da.subtract(cb)
            x3 = s.multiply(s).mod(P)
            z3 = x1.multiply(m.multiply(m)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return toLe(x2.multiply(z2.modPow(PM2, P)).mod(P))
    }

    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun toLe(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) {
            val j = be.size - 1 - i
            if (i < 32) out[i] = be[j]
        }
        return out
    }

    // ------------------------------------------------------------------ HMAC, HKDF

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach(m::update)
        return m.doFinal()
    }

    fun hmac16(key: ByteArray, vararg parts: ByteArray): ByteArray = hmac(key, *parts).copyOf(16)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < n) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(n)
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach(::update); digest() }

    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    // ------------------------------------------------------------------ SHAKE256

    private val RC = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AUL.toLong(), 0x8000000080008000UL.toLong(),
        0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081UL.toLong(), 0x8000000000008009UL.toLong(),
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, 0x800000000000008BUL.toLong(), 0x8000000000008089UL.toLong(), 0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(), 0x8000000000000080UL.toLong(), 0x000000000000800AL, 0x800000008000000AUL.toLong(),
        0x8000000080008081UL.toLong(), 0x8000000000008080UL.toLong(), 0x0000000080000001L, 0x8000000080008008UL.toLong(),
    )

    private fun rol(v: Long, n: Int) = (v shl n) or (v ushr (64 - n))

    /** Keccak-f[1600], lane x + 5y in aXY; rho and pi unrolled, the way fast implementations do it. */
    private fun keccakF(s: LongArray) {
        var a00 = s[0]; var a01 = s[1]; var a02 = s[2]; var a03 = s[3]; var a04 = s[4]
        var a05 = s[5]; var a06 = s[6]; var a07 = s[7]; var a08 = s[8]; var a09 = s[9]
        var a10 = s[10]; var a11 = s[11]; var a12 = s[12]; var a13 = s[13]; var a14 = s[14]
        var a15 = s[15]; var a16 = s[16]; var a17 = s[17]; var a18 = s[18]; var a19 = s[19]
        var a20 = s[20]; var a21 = s[21]; var a22 = s[22]; var a23 = s[23]; var a24 = s[24]
        for (round in 0 until 24) {
            // theta
            var c0 = a00 xor a05 xor a10 xor a15 xor a20
            var c1 = a01 xor a06 xor a11 xor a16 xor a21
            val c2 = a02 xor a07 xor a12 xor a17 xor a22
            val c3 = a03 xor a08 xor a13 xor a18 xor a23
            val c4 = a04 xor a09 xor a14 xor a19 xor a24
            val d1 = rol(c1, 1) xor c4
            val d2 = rol(c2, 1) xor c0
            val d3 = rol(c3, 1) xor c1
            val d4 = rol(c4, 1) xor c2
            val d0 = rol(c0, 1) xor c3
            a00 = a00 xor d1; a05 = a05 xor d1; a10 = a10 xor d1; a15 = a15 xor d1; a20 = a20 xor d1
            a01 = a01 xor d2; a06 = a06 xor d2; a11 = a11 xor d2; a16 = a16 xor d2; a21 = a21 xor d2
            a02 = a02 xor d3; a07 = a07 xor d3; a12 = a12 xor d3; a17 = a17 xor d3; a22 = a22 xor d3
            a03 = a03 xor d4; a08 = a08 xor d4; a13 = a13 xor d4; a18 = a18 xor d4; a23 = a23 xor d4
            a04 = a04 xor d0; a09 = a09 xor d0; a14 = a14 xor d0; a19 = a19 xor d0; a24 = a24 xor d0
            // rho and pi
            c1 = rol(a01, 1)
            a01 = rol(a06, 44); a06 = rol(a09, 20); a09 = rol(a22, 61); a22 = rol(a14, 39)
            a14 = rol(a20, 18); a20 = rol(a02, 62); a02 = rol(a12, 43); a12 = rol(a13, 25)
            a13 = rol(a19, 8); a19 = rol(a23, 56); a23 = rol(a15, 41); a15 = rol(a04, 27)
            a04 = rol(a24, 14); a24 = rol(a21, 2); a21 = rol(a08, 55); a08 = rol(a16, 45)
            a16 = rol(a05, 36); a05 = rol(a03, 28); a03 = rol(a18, 21); a18 = rol(a17, 15)
            a17 = rol(a11, 10); a11 = rol(a07, 6); a07 = rol(a10, 3); a10 = c1
            // chi
            c0 = a00 xor (a01.inv() and a02); c1 = a01 xor (a02.inv() and a03)
            a02 = a02 xor (a03.inv() and a04); a03 = a03 xor (a04.inv() and a00); a04 = a04 xor (a00.inv() and a01)
            a00 = c0; a01 = c1
            c0 = a05 xor (a06.inv() and a07); c1 = a06 xor (a07.inv() and a08)
            a07 = a07 xor (a08.inv() and a09); a08 = a08 xor (a09.inv() and a05); a09 = a09 xor (a05.inv() and a06)
            a05 = c0; a06 = c1
            c0 = a10 xor (a11.inv() and a12); c1 = a11 xor (a12.inv() and a13)
            a12 = a12 xor (a13.inv() and a14); a13 = a13 xor (a14.inv() and a10); a14 = a14 xor (a10.inv() and a11)
            a10 = c0; a11 = c1
            c0 = a15 xor (a16.inv() and a17); c1 = a16 xor (a17.inv() and a18)
            a17 = a17 xor (a18.inv() and a19); a18 = a18 xor (a19.inv() and a15); a19 = a19 xor (a15.inv() and a16)
            a15 = c0; a16 = c1
            c0 = a20 xor (a21.inv() and a22); c1 = a21 xor (a22.inv() and a23)
            a22 = a22 xor (a23.inv() and a24); a23 = a23 xor (a24.inv() and a20); a24 = a24 xor (a20.inv() and a21)
            a20 = c0; a21 = c1
            // iota
            a00 = a00 xor RC[round]
        }
        s[0] = a00; s[1] = a01; s[2] = a02; s[3] = a03; s[4] = a04
        s[5] = a05; s[6] = a06; s[7] = a07; s[8] = a08; s[9] = a09
        s[10] = a10; s[11] = a11; s[12] = a12; s[13] = a13; s[14] = a14
        s[15] = a15; s[16] = a16; s[17] = a17; s[18] = a18; s[19] = a19
        s[20] = a20; s[21] = a21; s[22] = a22; s[23] = a23; s[24] = a24
    }

    private const val RATE = 136

    /** XORs SHAKE256([input]) into [buf] from [off] for [len] bytes (or writes it, with [xor] false). */
    fun shake256(input: ByteArray, buf: ByteArray, off: Int = 0, len: Int = buf.size, xor: Boolean = true) {
        val s = LongArray(25)
        val block = ByteArray(RATE)
        var p = 0
        fun absorb(blk: ByteArray) {
            for (i in 0 until RATE / 8) {
                var v = 0L
                for (j in 7 downTo 0) v = (v shl 8) or (blk[i * 8 + j].toLong() and 0xFF)
                s[i] = s[i] xor v
            }
            keccakF(s)
        }
        while (input.size - p >= RATE) { absorb(input.copyOfRange(p, p + RATE)); p += RATE }
        java.util.Arrays.fill(block, 0)
        System.arraycopy(input, p, block, 0, input.size - p)
        block[input.size - p] = (block[input.size - p].toInt() xor 0x1F).toByte()
        block[RATE - 1] = (block[RATE - 1].toInt() xor 0x80).toByte()
        absorb(block)
        var done = 0
        while (true) {
            var i = 0
            while (i < RATE && done < len) {
                val byte = (s[i ushr 3] ushr ((i and 7) * 8)).toByte()
                val k = off + done
                buf[k] = if (xor) (buf[k].toInt() xor byte.toInt()).toByte() else byte
                i++; done++
            }
            if (done >= len) return
            keccakF(s)
        }
    }
}

/**
 * One direction of a tunnel connection: the frame counter, the keystream and the tag. Not
 * thread-safe; the connection serialises its writes and has one reader.
 */
class TunnelCipher(private val enc: ByteArray, macKey: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
    private var n = 0L
    private val seed = ByteArray(enc.size + 8).also { System.arraycopy(enc, 0, it, 0, enc.size) }

    private fun counter(): ByteArray = java.nio.ByteBuffer.allocate(8).putLong(n).array()

    private fun keystream(buf: ByteArray, off: Int, len: Int) {
        System.arraycopy(counter(), 0, seed, enc.size, 8)
        TunnelCrypto.shake256(seed, buf, off, len)
        n++
    }

    private fun tag(c: ByteArray, lenBytes: ByteArray, buf: ByteArray, off: Int, len: Int): ByteArray {
        mac.update(c); mac.update(lenBytes); mac.update(buf, off, len)
        return mac.doFinal().copyOf(16)
    }

    /** The whole frame for [plain]: length, ciphertext, tag. */
    fun seal(plain: ByteArray, off: Int = 0, len: Int = plain.size): ByteArray {
        val out = ByteArray(4 + len + 16)
        java.nio.ByteBuffer.wrap(out).putInt(len)
        System.arraycopy(plain, off, out, 4, len)
        val c = counter()
        keystream(out, 4, len)
        val t = tag(c, out.copyOfRange(0, 4), out, 4, len)
        System.arraycopy(t, 0, out, 4 + len, 16)
        return out
    }

    /** Decrypts [ct] in place after checking [tagBytes]; false when the check fails. */
    fun open(ct: ByteArray, len: Int, tagBytes: ByteArray): Boolean {
        val lenBytes = java.nio.ByteBuffer.allocate(4).putInt(len).array()
        if (!TunnelCrypto.equal(tag(counter(), lenBytes, ct, 0, len), tagBytes)) return false
        keystream(ct, 0, len)
        return true
    }
}
