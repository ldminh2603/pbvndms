package com.webcore.api.auth;

import java.math.BigInteger;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Equivalent to .NET RSA.ImportFromPem: accepts PKCS#1 and PKCS#8/X.509 PEM. */
public final class RsaPemKeys {
    private RsaPemKeys() { }

    public static RSAPublicKey readPublicKey(String pem) throws Exception {
        byte[] der = decode(pem);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        try {
            return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (java.security.spec.InvalidKeySpecException ignored) {
            try {
                // .NET RSA.ImportFromPem also accepts a private PEM at the public-key
                // call site and exposes its public CRT values. Existing AUTH_SYSTEM
                // data uses that representation.
                RSAPrivateKey privateKey = readPrivateKey(pem);
                if (privateKey instanceof RSAPrivateCrtKey crtKey) {
                    return (RSAPublicKey) factory.generatePublic(
                            new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
                }
            } catch (Exception notPrivateKey) {
                // Continue with certificate and PKCS#1 representations below.
            }
            try {
                java.security.PublicKey certificateKey = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
                if (certificateKey instanceof RSAPublicKey rsaPublicKey) return rsaPublicKey;
                throw new IllegalArgumentException("AUTH_SYSTEM Public_Key certificate is not RSA.");
            } catch (java.security.cert.CertificateException notCertificate) {
                DerReader reader = new DerReader(der).sequence();
                return (RSAPublicKey) factory.generatePublic(
                        new RSAPublicKeySpec(reader.integer(), reader.integer()));
            }
        }
    }

    public static RSAPrivateKey readPrivateKey(String pem) throws Exception {
        byte[] der = decode(pem);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        try {
            return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (java.security.spec.InvalidKeySpecException ignored) {
            // PKCS#1 RSA private key.
            DerReader reader = new DerReader(der).sequence();
            reader.integer(); // PKCS#1 version
            RSAPrivateCrtKeySpec key = new RSAPrivateCrtKeySpec(
                    reader.integer(), reader.integer(), reader.integer(), reader.integer(), reader.integer(),
                    reader.integer(), reader.integer(), reader.integer());
            return (RSAPrivateKey) factory.generatePrivate(key);
        }
    }

    /** Derives the matching public key from a CRT private key used for JWT signing. */
    public static RSAPublicKey publicKeyFromPrivateKey(RSAPrivateKey privateKey) throws Exception {
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException("JWT private key must include RSA CRT parameters.");
        }
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
    }

    private static byte[] decode(String pem) {
        // Database rows can contain either PKCS#1 or standard PEM labels. Strip
        // the complete armor, as RSA.ImportFromPem does on the .NET side.
        String body = pem.replaceAll("-----BEGIN [^-]+-----|-----END [^-]+-----|\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static final class DerReader {
        private final byte[] bytes;
        private int position;

        private DerReader(byte[] bytes) { this.bytes = bytes; }

        private DerReader sequence() {
            requireTag(0x30);
            int length = length();
            if (position + length != bytes.length) throw new IllegalArgumentException("Invalid RSA DER sequence.");
            byte[] sequence = java.util.Arrays.copyOfRange(bytes, position, position + length);
            return new DerReader(sequence);
        }

        private BigInteger integer() {
            requireTag(0x02);
            int length = length();
            if (length < 1 || position + length > bytes.length) throw new IllegalArgumentException("Invalid RSA DER integer.");
            byte[] value = java.util.Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return new BigInteger(1, value);
        }

        private void requireTag(int tag) {
            if (position >= bytes.length || (bytes[position++] & 0xff) != tag) throw new IllegalArgumentException("Invalid RSA DER tag.");
        }

        private int length() {
            if (position >= bytes.length) throw new IllegalArgumentException("Invalid RSA DER length.");
            int first = bytes[position++] & 0xff;
            if ((first & 0x80) == 0) return first;
            int count = first & 0x7f;
            if (count < 1 || count > 4 || position + count > bytes.length) throw new IllegalArgumentException("Invalid RSA DER length.");
            int result = 0;
            for (int index = 0; index < count; index++) result = (result << 8) | (bytes[position++] & 0xff);
            return result;
        }
    }
}
