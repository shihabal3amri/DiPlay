package com.shilapi.xcertplay.mfi

import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * Generates autonomous, self-signed local MFi authentication identity material
 * (secp256r1 EC keypair and X.509 certificate) for CarPlay local experimental authentication.
 */
object LocalMfiIdentityGenerator {

    fun generate(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val identityFile = File(directory, "identity.pk8")
        val certificateFile = File(directory, "certificate.p7b")

        if (identityFile.exists() && certificateFile.exists()) {
            return
        }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()

        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay local accessory")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(tbs.encoded)

        val certificate = DERSequence(
            arrayOf<ASN1Encodable>(
                tbs,
                algorithm,
                DERBitString(signer.sign()),
            ),
        ).encoded

        identityFile.writeBytes(pair.private.encoded)
        identityFile.setReadable(false, false)
        identityFile.setReadable(true, true)
        identityFile.setWritable(false, false)
        identityFile.setWritable(true, true)

        certificateFile.writeBytes(certificate)
        certificateFile.setReadable(false, false)
        certificateFile.setReadable(true, true)
        certificateFile.setWritable(false, false)
        certificateFile.setWritable(true, true)
    }
}
