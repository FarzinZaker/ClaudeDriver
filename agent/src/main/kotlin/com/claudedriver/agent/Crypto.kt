package com.claudedriver.agent

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** Agent-side key material: generate a device keypair + CSR, and fingerprint the issued cert. */
object Crypto {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class KeyAndCsr(val privateKeyPem: String, val csrPem: String)

    fun generateKeyPairAndCsr(commonName: String): KeyAndCsr {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME).apply { initialize(2048) }
        val keyPair = kpg.generateKeyPair()
        val builder = JcaPKCS10CertificationRequestBuilder(X500Principal("CN=$commonName"), keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val csr = builder.build(signer)
        return KeyAndCsr(privateKeyPem = toPem(keyPair.private), csrPem = toPem(csr))
    }

    /** SHA-256 fingerprint (hex) of a PEM certificate — matches the backend trust-store key. */
    fun fingerprint(certPem: String): String {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certPem.byteInputStream()) as X509Certificate
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    private fun toPem(obj: Any): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(obj) }
        return sw.toString()
    }
}
