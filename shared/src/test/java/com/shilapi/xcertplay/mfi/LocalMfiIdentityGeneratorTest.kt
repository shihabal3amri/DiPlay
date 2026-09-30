package com.shilapi.xcertplay.mfi

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMfiIdentityGeneratorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun testGenerateIdentityAndCertificate() {
        val dir = temporary.newFolder()
        LocalMfiIdentityGenerator.generate(dir)

        val pk8 = File(dir, "identity.pk8")
        val p7b = File(dir, "certificate.p7b")

        assertTrue("identity.pk8 should exist", pk8.exists())
        assertTrue("certificate.p7b should exist", p7b.exists())
        assertTrue("identity.pk8 should not be empty", pk8.length() > 0)
        assertTrue("certificate.p7b should not be empty", p7b.length() > 0)

        // Verify it can be loaded and validated by LocalMfiAuthenticationClient
        val client = LocalMfiAuthenticationClient.load(dir)
        assertNotNull(client)
        assertEquals(3, client.protocolMajor())

        val certBytes = client.readCertificate(8192)
        assertTrue(certBytes.isNotEmpty())

        val challenge = ByteArray(32) { it.toByte() }
        val signature = client.signChallenge(challenge)
        assertTrue(signature.isNotEmpty())
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
