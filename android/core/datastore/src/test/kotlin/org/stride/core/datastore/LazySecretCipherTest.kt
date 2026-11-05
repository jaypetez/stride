package org.stride.core.datastore

import kotlin.test.assertEquals
import org.junit.Test

/** AUD-036: Keystore/Tink setup must not run while Hilt injects the Application on the main thread. */
class LazySecretCipherTest {
    private object Reverse : SecretCipher {
        override fun encrypt(plaintext: String, associatedData: String) = plaintext.reversed()

        override fun decrypt(ciphertext: String, associatedData: String) = ciphertext.reversed()
    }

    @Test
    fun buildsTheCipherOnFirstUseOnly() {
        var built = 0
        val cipher = LazySecretCipher {
            built++
            Reverse
        }
        assertEquals(0, built, "constructing (injecting) the cipher must not build it")
        assertEquals("cba", cipher.encrypt("abc", "k"))
        assertEquals("abc", cipher.decrypt("cba", "k"))
        assertEquals(1, built, "built once, then reused")
    }
}
