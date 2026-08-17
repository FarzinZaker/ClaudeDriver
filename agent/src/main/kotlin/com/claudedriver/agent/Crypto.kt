package com.claudedriver.agent

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import java.io.StringReader
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
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

    /**
     * In-memory password for the client keystore. It is never written to disk (the keystore is
     * built in memory each run from the enrolled PEM files), so the value is immaterial.
     */
    val KEYSTORE_PASSWORD: CharArray = "claudedriver".toCharArray()

    /**
     * Build an in-memory PKCS12 keystore holding the enrolled device key + certificate, for use as
     * a TLS client certificate (mutual TLS to the ALB agent listener). The ALB verifies the leaf
     * against the device-CA trust store, so presenting the leaf alone is sufficient.
     */
    fun clientKeyStore(privateKeyPem: String, certPem: String): KeyStore {
        val key = parsePrivateKey(privateKeyPem)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certPem.byteInputStream()) as X509Certificate
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("agent", key, KEYSTORE_PASSWORD, arrayOf(cert))
        }
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
        PEMParser(StringReader(pem)).use { parser ->
            return when (val obj = parser.readObject()) {
                is PEMKeyPair -> converter.getKeyPair(obj).private
                is PrivateKeyInfo -> converter.getPrivateKey(obj)
                else -> error("Unsupported private-key PEM: ${obj?.javaClass?.name}")
            }
        }
    }

    private fun toPem(obj: Any): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(obj) }
        return sw.toString()
    }
}
