package id.rock.messenger

/**
 * X25519 по TweetNaCl (public domain), тот же результат, что у OpenSSL EVP_PKEY_X25519.
 * Векторы — RFC 7748, раздел 6.1.
 */
object X25519 {
    private val k121665 = longArrayOf(
        0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    fun selfTest(): Boolean {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        val ap = base(alice)
        val bp = base(bob)
        val s1 = scalarmult(alice, bp)
        val s2 = scalarmult(bob, ap)
        return ap.contentEquals(alicePub) && bp.contentEquals(bobPub) && s1.contentEquals(shared) && s1.contentEquals(s2)
    }

    fun base(scalar: ByteArray): ByteArray {
        val nine = ByteArray(32)
        nine[0] = 9
        return scalarmult(scalar, nine)
    }

    fun scalarmult(scalar: ByteArray, point: ByteArray): ByteArray {
        val z = ByteArray(32)
        for (i in 0 until 31) z[i] = scalar[i]
        z[31] = ((scalar[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = LongArray(80)
        unpack25519(x, 0, point)
        val a = LongArray(16)
        val b = LongArray(16)
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        for (i in 0 until 16) b[i] = x[i]
        a[0] = 1
        d[0] = 1
        var i = 254
        while (i >= 0) {
            val r = ((z[i shr 3].toInt() and 0xff) shr (i and 7)) and 1
            sel25519(a, b, r)
            sel25519(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            square(d, e)
            square(f, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            square(b, a)
            sub(c, d, f)
            mul(a, c, k121665)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            val point = LongArray(16)
            for (j in 0 until 16) point[j] = x[j]
            mul(d, b, point)
            square(b, e)
            sel25519(a, b, r)
            sel25519(c, d, r)
            i--
        }
        for (j in 0 until 16) {
            x[j + 16] = a[j]
            x[j + 32] = c[j]
            x[j + 48] = b[j]
            x[j + 64] = d[j]
        }
        inv25519(x, 32, x, 32)
        mul(x, 16, x, 16, x, 32)
        val out = ByteArray(32)
        pack25519(out, x, 16)
        return out
    }

    private fun car25519(o: LongArray, off: Int = 0) {
        for (i in 0 until 16) {
            o[off + i] += 1L shl 16
            val c = o[off + i] shr 16
            val idx = (i + 1) * (if (i < 15) 1 else 0)
            val fifteen = if (i == 15) 1L else 0L
            o[off + idx] += c - 1 + 37 * (c - 1) * fifteen
            o[off + i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, bit: Int) {
        val mask = (bit - 1).inv().toLong()
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(out: ByteArray, n: LongArray, off: Int) {
        val t = LongArray(16)
        val m = LongArray(16)
        for (i in 0 until 16) t[i] = n[off + i]
        car25519(t)
        car25519(t)
        car25519(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel25519(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            out[2 * i] = (t[i] and 0xff).toByte()
            out[2 * i + 1] = ((t[i] shr 8) and 0xff).toByte()
        }
    }

    private fun unpack25519(o: LongArray, off: Int, n: ByteArray) {
        for (i in 0 until 16) {
            o[off + i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        }
        o[off + 15] = o[off + 15] and 0x7fff
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) {
            for (j in 0 until 16) t[i + j] += a[i] * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car25519(o)
        car25519(o)
    }

    private fun mul(dst: LongArray, dstOff: Int, a: LongArray, aOff: Int, b: LongArray, bOff: Int) {
        val left = LongArray(16)
        val right = LongArray(16)
        val out = LongArray(16)
        for (i in 0 until 16) {
            left[i] = a[aOff + i]
            right[i] = b[bOff + i]
        }
        mul(out, left, right)
        for (i in 0 until 16) dst[dstOff + i] = out[i]
    }

    private fun square(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv25519(o: LongArray, oOff: Int, input: LongArray, inOff: Int) {
        val c = LongArray(16)
        val src = LongArray(16)
        for (i in 0 until 16) {
            c[i] = input[inOff + i]
            src[i] = input[inOff + i]
        }
        for (a in 253 downTo 0) {
            square(c, c)
            if (a != 2 && a != 4) mul(c, c, src)
        }
        for (i in 0 until 16) o[oOff + i] = c[i]
    }

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
