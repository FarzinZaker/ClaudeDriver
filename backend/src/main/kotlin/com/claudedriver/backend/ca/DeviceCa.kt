package com.claudedriver.backend.ca

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/** A device certificate issued to an enrolled machine. */
data class IssuedCertificate(
    val certificatePem: String,
    val caChainPem: String,
    val fingerprint: String,
    val serial: String,
    val notBefore: Instant,
    val notAfter: Instant,
)

/**
 * The device certificate authority (Constitution Principle I/IV). Signs short-lived client
 * certificates that agents present for mutual-TLS. Deterministic crypto → unit-testable offline.
 */
class DeviceCa(
    val caCertificate: X509Certificate,
    private val caPrivateKey: PrivateKey,
) {
    /** Issue a client certificate from a PEM PKCS#10 CSR, bound to [machineId] in the subject CN. */
    fun issueFromCsr(csrPem: String, machineId: String, validityDays: Long = 30): IssuedCertificate {
        val csr = parseCsr(csrPem)
        val csrPublicKey = JcaPKCS10CertificationRequest(csr).setProvider(BC).getPublicKey()

        val now = Instant.now()
        val notAfter = now.plus(validityDays, ChronoUnit.DAYS)
        val serial = BigInteger(128, SecureRandom())
        val subject = X500Name("CN=$machineId,O=ClaudeDriver Agent")

        val builder = JcaX509v3CertificateBuilder(
            caCertificate,
            serial,
            Date.from(now),
            Date.from(notAfter),
            subject,
            csrPublicKey,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
        }

        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(caPrivateKey)
        val cert = JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer))

        return IssuedCertificate(
            certificatePem = toPem(cert),
            caChainPem = toPem(caCertificate),
            fingerprint = fingerprintOf(cert),
            serial = serial.toString(16),
            notBefore = now,
            notAfter = notAfter,
        )
    }

    fun caCertificatePem(): String = toPem(caCertificate)

    /** Export the CA private key (PEM) — for provisioning a persistent CA into a secret store. */
    fun caPrivateKeyPem(): String = toPem(caPrivateKey)

    companion object {
        const val BC = BouncyCastleProvider.PROVIDER_NAME

        init {
            if (Security.getProvider(BC) == null) Security.addProvider(BouncyCastleProvider())
        }

        /** Generate a fresh self-signed CA (dev/bootstrap; in prod the CA key lives in Secrets Manager). */
        fun generate(commonName: String = "ClaudeDriver Device CA", validityDays: Long = 3650): DeviceCa {
            val kpg = KeyPairGenerator.getInstance("RSA", BC).apply { initialize(2048) }
            val keyPair = kpg.generateKeyPair()
            val now = Instant.now()
            val notAfter = now.plus(validityDays, ChronoUnit.DAYS)
            val name = X500Name("CN=$commonName")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(128, SecureRandom()),
                Date.from(now),
                Date.from(notAfter),
                name,
                keyPair.public,
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
                )
                addExtension(
                    Extension.subjectKeyIdentifier,
                    false,
                    JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.public),
                )
            }
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(keyPair.private)
            val cert = JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer))
            return DeviceCa(cert, keyPair.private)
        }

        /** SHA-256 fingerprint (hex) — the trust-store lookup key. */
        fun fingerprintOf(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }

        fun parseCertificate(pem: String): X509Certificate {
            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(pem.byteInputStream()) as X509Certificate
        }

        /** Load a persistent CA from PEM material (prod: cert from config, key from a secret store). */
        fun loadFromPem(certPem: String, keyPem: String): DeviceCa =
            DeviceCa(parseCertificate(certPem), parsePrivateKey(keyPem))

        fun loadFromFiles(certPath: String, keyPath: String): DeviceCa =
            loadFromPem(java.io.File(certPath).readText(), java.io.File(keyPath).readText())

        private fun parsePrivateKey(pem: String): java.security.PrivateKey =
            org.bouncycastle.openssl.PEMParser(StringReader(pem)).use { parser ->
                val obj = parser.readObject()
                val converter = org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider(BC)
                when (obj) {
                    is org.bouncycastle.openssl.PEMKeyPair -> converter.getKeyPair(obj).private
                    is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(obj)
                    else -> throw IllegalArgumentException("Unsupported private key PEM")
                }
            }

        private fun parseCsr(pem: String): PKCS10CertificationRequest =
            PEMParser(StringReader(pem)).use { parser ->
                parser.readObject() as? PKCS10CertificationRequest
                    ?: throw IllegalArgumentException("Not a valid PKCS#10 CSR")
            }

        fun toPem(obj: Any): String {
            val writer = StringWriter()
            JcaPEMWriter(writer).use { it.writeObject(obj) }
            return writer.toString()
        }
    }
}
