package com.shilapi.xcertplay.mfi

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.asn1.DERSequence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMfiIdentityStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun target() = File(temporary.newFolder(), "offline-mfi")
    private fun ensure(target: File, assets: File) = LocalMfiIdentityStore.ensure(target) { File(assets, it).inputStream() }
    private fun sameIdentity(expected: File, actual: File) {
        for (name in listOf("identity.pk8", "certificate.p7b")) {
            assertArrayEquals(File(expected, name).readBytes(), File(actual, name).readBytes())
        }
        assertEquals(64, LocalMfiAuthenticationClient.load(actual).signChallenge(ByteArray(32)).size)
    }

    @Test fun freshInstallUsesValidatedPackagedIdentity() {
        val assets = identity()
        val target = target()
        assertEquals(LocalMfiIdentityStore.Result.INSTALLED_FROM_APK, ensure(target, assets))
        sameIdentity(assets, target)
    }

    @Test fun emptyPartialCorruptAndMismatchedCachesAreRepaired() {
        val assets = identity()
        for (damage in listOf("empty", "partial", "corrupt", "mismatched")) {
            val target = target()
            target.mkdirs()
            when (damage) {
                "partial" -> File(assets, "identity.pk8").copyTo(File(target, "identity.pk8"))
                "corrupt" -> {
                    File(target, "identity.pk8").writeText("broken key")
                    File(target, "certificate.p7b").writeText("broken certificate")
                }
                "mismatched" -> {
                    File(assets, "identity.pk8").copyTo(File(target, "identity.pk8"))
                    File(identity(), "certificate.p7b").copyTo(File(target, "certificate.p7b"))
                }
            }
            assertEquals(damage, LocalMfiIdentityStore.Result.REPAIRED_FROM_APK, ensure(target, assets))
            sameIdentity(assets, target)
            assertFalse(File(target.parentFile, "offline-mfi-previous").exists())
        }
    }

    @Test fun validExistingIdentityDoesNotRequireAssetsOrRotateKeys() {
        val target = identity()
        val before = File(target, "identity.pk8").readBytes()
        assertEquals(LocalMfiIdentityStore.Result.EXISTING, LocalMfiIdentityStore.ensure(target) {
            error("Must not read or replace an existing valid identity")
        })
        assertArrayEquals(before, File(target, "identity.pk8").readBytes())
    }

    @Test fun invalidPackagedPairLeavesOldCacheAndOtherSettingsUntouched() {
        val target = target().apply { mkdirs() }
        val old = File(target, "identity.pk8").apply { writeText("original broken key") }
        val preferences = File(target.parentFile, "settings-test").apply { writeText("keep vehicle and phone settings") }
        val assets = identity()
        File(identity(), "certificate.p7b").copyTo(File(assets, "certificate.p7b"), overwrite = true)
        assertThrows(Exception::class.java) { ensure(target, assets) }
        assertEquals("original broken key", old.readText())
        assertFalse(File(target, "certificate.p7b").exists())
        assertEquals("keep vehicle and phone settings", preferences.readText())
        assertFalse(File(target.parentFile, "offline-mfi-staging").exists())
    }

    @Test fun absentPackagedCertificateKeepsOldCacheAndRemovesPartialStaging() {
        val target = target().apply { mkdirs() }
        File(target, "identity.pk8").writeText("keep me")
        val assets = identity()
        File(assets, "certificate.p7b").delete()
        assertThrows(java.io.FileNotFoundException::class.java) { ensure(target, assets) }
        assertEquals("keep me", File(target, "identity.pk8").readText())
        assertFalse(File(target.parentFile, "offline-mfi-staging").exists())
    }

    @Test fun interruptedRenameRecoversPreviousIdentityWithoutAssets() {
        val target = target()
        val previous = File(target.parentFile, "offline-mfi-previous")
        val assets = identity()
        assets.copyRecursively(previous)
        assertEquals(LocalMfiIdentityStore.Result.RESTORED_PREVIOUS, LocalMfiIdentityStore.ensure(target) {
            error("Recovery should use the previous identity")
        })
        sameIdentity(assets, target)
    }

    @Test fun interruptedCommitResumesAValidatedStagingDirectory() {
        val target = target().apply { mkdirs() }
        File(target, "identity.pk8").writeText("broken old cache")
        val assets = identity()
        assets.copyRecursively(File(target.parentFile, "offline-mfi-staging"))
        assertEquals(LocalMfiIdentityStore.Result.RESTORED_STAGING, LocalMfiIdentityStore.ensure(target) {
            error("Recovery should use the validated stage")
        })
        sameIdentity(assets, target)
    }

    @Test fun invalidInterruptedStagingIsRebuiltBeforeInstallation() {
        val target = target()
        val staging = File(target.parentFile, "offline-mfi-staging").apply { mkdirs() }
        File(staging, "identity.pk8").writeText("incomplete copy")
        val assets = identity()
        assertEquals(LocalMfiIdentityStore.Result.INSTALLED_FROM_APK, ensure(target, assets))
        sameIdentity(assets, target)
    }

    private fun identity(): File = temporary.newFolder().also { directory ->
        // Fresh self-signed test material for each test. No stored accessory identity.
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay synthetic test only")
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
        val certificate = DERSequence(arrayOf<ASN1Encodable>(
            tbs, algorithm, DERBitString(signer.sign()),
        )).encoded
        File(directory, "identity.pk8").writeBytes(pair.private.encoded)
        File(directory, "certificate.p7b").writeBytes(certificate)
    }

}
