package com.claudedriver.backend.ca

import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.security.KeyPairGenerator
import javax.security.auth.x500.X500Principal

class DeviceCaTest {

    private fun csrPem(cn: String): String {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = kpg.generateKeyPair()
        val builder = JcaPKCS10CertificationRequestBuilder(X500Principal("CN=$cn"), keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val csr = builder.build(signer)
        return StringWriter().also { sw -> JcaPEMWriter(sw).use { it.writeObject(csr) } }.toString()
    }

    @Test
    fun `issues a client cert signed by the CA with a stable fingerprint`() {
        val ca = DeviceCa.generate()
        val issued = ca.issueFromCsr(csrPem("machine-1"), machineId = "machine-1")

        val cert = DeviceCa.parseCertificate(issued.certificatePem)
        // Verifies the cert is signed by the CA's key (throws otherwise).
        assertDoesNotThrow { cert.verify(ca.caCertificate.publicKey) }
        assertEquals(issued.fingerprint, DeviceCa.fingerprintOf(cert))
        assertTrue(issued.notAfter.isAfter(issued.notBefore))
        assertTrue(cert.subjectX500Principal.name.contains("machine-1"))
    }

    @Test
    fun `two issued certs have distinct fingerprints and serials`() {
        val ca = DeviceCa.generate()
        val a = ca.issueFromCsr(csrPem("m-a"), "m-a")
        val b = ca.issueFromCsr(csrPem("m-b"), "m-b")
        assertTrue(a.fingerprint != b.fingerprint)
        assertTrue(a.serial != b.serial)
    }
}
