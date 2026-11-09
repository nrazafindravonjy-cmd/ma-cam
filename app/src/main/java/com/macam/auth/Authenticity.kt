package com.macam.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.operator.ContentSigner
import org.contentauth.c2pa.Builder
import org.contentauth.c2pa.FileStream
import org.contentauth.c2pa.Signer
import org.contentauth.c2pa.SigningAlgorithm
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * Authenticité des photos (Content Credentials / C2PA) :
 * - clé de signature créée et conservée dans la puce sécurisée (StrongBox si disponible), jamais exportable ;
 * - attestation matérielle Android (chaîne signée par Google) prouvant que la clé vit dans un vrai téléphone ;
 * - autorité locale propre au téléphone (elle aussi dans le matériel) qui émet le certificat de signature ;
 * - empreinte du mouvement de la main pendant la prise de vue (gyroscope), propre à une vraie capture.
 *
 * Les vérificateurs C2PA (ex. contentcredentials.org/verify) confirment l'intégrité de la photo ; l'émetteur
 * apparaît « non reconnu » tant que Ma Cam n'a pas de certificat d'une autorité de la liste de confiance C2PA.
 */
object Authenticity {
    private const val CA_ALIAS = "macam_c2pa_ca"
    private const val KEY_ALIAS = "macam_c2pa_signer"
    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Clé EC P-256 matérielle ; StrongBox si présent, sinon zone sécurisée (TEE). */
    private fun ensureKey(alias: String, purpose: Int, attest: Boolean): Boolean {
        if (ks.containsAlias(alias)) return true
        fun gen(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, purpose).apply {
                setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                setDigests(KeyProperties.DIGEST_SHA256)
                if (attest) setAttestationChallenge("Ma Cam C2PA".toByteArray())
                if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
            }.build())
        }.generateKeyPair()
        return try { gen(true); true } catch (e: Exception) { gen(false); false }
    }

    /** Signataire BouncyCastle qui délègue la signature à une clé du Keystore (non exportable). */
    private class KeystoreSigner(private val key: PrivateKey) : ContentSigner {
        private val buf = ByteArrayOutputStream()
        override fun getAlgorithmIdentifier() = AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.10045.4.3.2")) // ecdsa-with-SHA256
        override fun getOutputStream() = buf
        override fun getSignature(): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(buf.toByteArray()); sign() }
    }

    private fun cert(subject: String, issuer: String, pub: PublicKey, issuerPub: PublicKey, signer: PrivateKey, ca: Boolean, years: Int): X509Certificate {
        val now = System.currentTimeMillis()
        val b = X509v3CertificateBuilder(X500Name(issuer), BigInteger.valueOf(now), Date(now - 300_000), Date(now + years * 365L * 86_400_000),
            X500Name(subject), SubjectPublicKeyInfo.getInstance(pub.encoded))
        val ext = JcaX509ExtensionUtils()
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(pub))
        if (!ca) b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(issuerPub))
        b.addExtension(Extension.basicConstraints, true, if (ca) BasicConstraints(0) else BasicConstraints(false))
        b.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
        if (!ca) b.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection))
        return JcaX509CertificateConverter().getCertificate(b.build(KeystoreSigner(signer)))
    }

    private fun pem(c: X509Certificate) = "-----BEGIN CERTIFICATE-----\n" +
        Base64.encodeToString(c.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"

    /** Chaîne PEM (signataire puis autorité locale), créée une fois puis conservée. */
    @Synchronized private fun chain(ctx: Context): String {
        val f = File(ctx.filesDir, "c2pa_chain.pem")
        if (f.exists() && ks.containsAlias(KEY_ALIAS) && ks.containsAlias(CA_ALIAS)) return f.readText()
        ensureKey(CA_ALIAS, KeyProperties.PURPOSE_SIGN, attest = false)
        ensureKey(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY, attest = true)
        val caKey = ks.getKey(CA_ALIAS, null) as PrivateKey
        val caPub = ks.getCertificate(CA_ALIAS).publicKey
        val eePub = ks.getCertificate(KEY_ALIAS).publicKey
        val dn = "O=Ma Cam, OU=${Build.MANUFACTURER} ${Build.MODEL}"
        val root = cert("CN=Ma Cam Device CA, $dn", "CN=Ma Cam Device CA, $dn", caPub, caPub, caKey, ca = true, years = 20)
        val ee = cert("CN=Ma Cam Capture, $dn", "CN=Ma Cam Device CA, $dn", eePub, caPub, caKey, ca = false, years = 10)
        return (pem(ee) + pem(root)).also { f.writeText(it) }
    }

    /** Chaîne d'attestation matérielle Android de la clé de signature (DER en base64). */
    private fun attestation(): JSONArray = JSONArray().apply {
        ks.getCertificateChain(KEY_ALIAS)?.forEach { put(Base64.encodeToString(it.encoded, Base64.NO_WRAP)) }
    }

    val strongBox: Boolean get() = runCatching {
        val k = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return false
        val info = java.security.KeyFactory.getInstance(k.algorithm, "AndroidKeyStore")
            .getKeySpec(k, android.security.keystore.KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX else info.isInsideSecureHardware
    }.getOrDefault(false)

    /** Signature DER (ASN.1) → format brut r‖s attendu par C2PA. */
    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2; if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): ByteArray { val len = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len; return v }
        val r = int(); val s = int()
        fun pad(x: ByteArray) = ByteArray(32).also { val t = x.dropWhile { it == 0.toByte() }.toByteArray(); t.copyInto(it, 32 - t.size) }
        return pad(r) + pad(s)
    }

    /** Empreinte de mouvement : résumé + hachage des mesures gyroscopiques entourant la prise de vue. */
    fun motionFingerprint(samples: List<FloatArray>): JSONObject {
        val md = MessageDigest.getInstance("SHA-256")
        var sumSq = 0.0
        val bb = java.nio.ByteBuffer.allocate(12)
        for (s in samples) { bb.clear(); bb.putFloat(s[0]).putFloat(s[1]).putFloat(s[2]); md.update(bb.array()); sumSq += s[0] * s[0] + s[1] * s[1] + s[2] * s[2] }
        // Aperçu décimé (64 points) : permet de juger la plausibilité d'un tremblement humain.
        val step = maxOf(1, samples.size / 64)
        return JSONObject().apply {
            put("samples", samples.size)
            put("rms_rad_s", if (samples.isEmpty()) 0.0 else kotlin.math.sqrt(sumSq / samples.size))
            put("sha256", md.digest().joinToString("") { "%02x".format(it) })
            put("preview", JSONArray().apply { for (k in samples.indices step step) { val s = samples[k]; put(JSONArray(listOf(s[0], s[1], s[2]).map { it.toDouble() })) } })
        }
    }

    /**
     * Signe [src] (JPEG) en [dst] avec un manifeste C2PA. [capture] décrit la prise de vue (objectif, ISO…).
     */
    fun signJpeg(ctx: Context, src: File, dst: File, capture: JSONObject, motion: List<FloatArray>) {
        val certs = chain(ctx)
        val key = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val manifest = JSONObject().apply {
            put("claim_generator", "Ma Cam/$version")
            put("title", src.name)
            put("format", "image/jpeg")
            put("assertions", JSONArray().apply {
                put(JSONObject().apply {
                    put("label", "c2pa.actions")
                    put("data", JSONObject().put("actions", JSONArray().put(JSONObject().apply {
                        put("action", "c2pa.created")
                        // Image calculée à partir de plusieurs captures du capteur (fusion multi-images).
                        put("digitalSourceType", "http://cv.iptc.org/newscodes/digitalsourcetype/computationalCapture")
                    })))
                })
                put(JSONObject().apply {
                    put("label", "com.macam.capture")
                    put("data", JSONObject(capture.toString()).apply {
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("android", Build.VERSION.RELEASE)
                        put("motion", motionFingerprint(motion))
                        put("key_attestation", attestation())
                        put("strongbox", strongBox)
                    })
                })
            })
        }.toString()
        val signer = Signer.withCallback(SigningAlgorithm.ES256, certs, null) { data ->
            derToRaw(Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() })
        }
        val builder = Builder.fromJson(manifest)
        val i = FileStream(src, FileStream.Mode.READ); val o = FileStream(dst, FileStream.Mode.WRITE)
        try { builder.sign("image/jpeg", i, o, signer) } finally { builder.close(); i.close(); o.close(); signer.close() }
    }
}
