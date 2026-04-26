package com.picsou.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Generates and stores the Enable Banking RSA-2048 key pair.
 *
 * <p><strong>Idempotent by design.</strong> If the private PEM already exists
 * on disk, the public half is re-derived from it — never a fresh pair. This
 * matters because the user uploads the public key to Enable Banking's
 * dashboard, and overwriting it silently would break every JWT the connector
 * signs. A "Rotate key pair" flow (out of scope here) is the only legitimate
 * way to replace the pair.
 *
 * <p>Private key is written with POSIX 0600 permissions on systems that
 * support them (Linux containers) and plain open-on-Windows dev hosts. The
 * containing directory is created with 0700.
 */
@Service
public class EnableBankingKeyPairService {

    private static final Logger log = LoggerFactory.getLogger(EnableBankingKeyPairService.class);
    private static final int RSA_BITS = 2048;

    private final Path privateKeyPath;

    public EnableBankingKeyPairService(
        @Value("${app.enablebanking.private-key-path:/data/keys/enablebanking-private.pem}") String path
    ) {
        this.privateKeyPath = Path.of(path);
    }

    public Path privateKeyPath() {
        return privateKeyPath;
    }

    public boolean exists() {
        return Files.exists(privateKeyPath);
    }

    /**
     * Returns the current key pair's public PEM, generating a new pair on disk
     * only if none exists yet.
     */
    public String getOrGeneratePublicPem() {
        if (exists()) {
            log.info("Enable Banking private key already present — returning existing public half");
            return derivePublicPemFromPrivate();
        }
        return generateNewPairReturningPublicPem();
    }

    private String generateNewPairReturningPublicPem() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_BITS);
            KeyPair kp = gen.generateKeyPair();

            writePrivateKey(kp.getPrivate());
            log.info("Enable Banking key pair generated and persisted to {}", privateKeyPath);
            return toPem(kp.getPublic(), "PUBLIC KEY");
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new IllegalStateException(
                "Failed to generate Enable Banking key pair at " + privateKeyPath, ex);
        }
    }

    /**
     * Imports an externally supplied PKCS#8 private key PEM (e.g. the file
     * Enable Banking gives you when creating an application). The key is
     * validated by deriving the public half before being persisted, so a
     * malformed or unsupported PEM fails fast without corrupting the on-disk
     * state.
     *
     * @return the derived public key PEM, ready to be shown / verified in the UI
     */
    public String importPrivateKey(String pemContent) {
        if (pemContent == null || pemContent.isBlank()) {
            throw new IllegalArgumentException("PEM content is empty.");
        }
        String trimmed = pemContent.strip();
        if (trimmed.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            throw new IllegalArgumentException(
                "PKCS#1 format (BEGIN RSA PRIVATE KEY) is not supported. " +
                "Convert to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem"
            );
        }
        if (!trimmed.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalArgumentException(
                "Not a valid PKCS#8 private key PEM (expected -----BEGIN PRIVATE KEY-----)."
            );
        }
        // Dry-run validation: parse the key before touching disk.
        validatePkcs8Pem(trimmed);
        try {
            writePemToDisk(trimmed);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write imported private key to disk.", ex);
        }
        log.info("Enable Banking private key imported from external PEM — public half derived");
        return derivePublicPemFromPrivate();
    }

    private void writePrivateKey(PrivateKey key) throws IOException {
        writePemToDisk(toPem(key, "PRIVATE KEY"));
    }

    private void writePemToDisk(String pem) throws IOException {
        Path parent = privateKeyPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
            trySetPosix(parent, PosixFilePermissions.fromString("rwx------"));
        }
        Files.writeString(privateKeyPath, pem);
        trySetPosix(privateKeyPath, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static void validatePkcs8Pem(String pem) {
        try {
            String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                "The PEM could not be parsed as a valid RSA PKCS#8 private key: " + ex.getMessage(), ex
            );
        }
    }

    private String derivePublicPemFromPrivate() {
        try {
            String pem = Files.readString(privateKeyPath);
            String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(der));

            // Re-derive the public key from RSAPrivateCrtKey (modulus + public exponent).
            var crt = (java.security.interfaces.RSAPrivateCrtKey) priv;
            var pubSpec = new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            PublicKey pub = kf.generatePublic(pubSpec);

            // Round-trip so we output the exact X509 SubjectPublicKeyInfo Enable Banking expects.
            PublicKey decoded = kf.generatePublic(new X509EncodedKeySpec(pub.getEncoded()));
            return toPem(decoded, "PUBLIC KEY");
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | ClassCastException ex) {
            throw new IllegalStateException(
                "Failed to derive public key from existing private key at " + privateKeyPath +
                ". The file may be corrupted; delete it to regenerate (invalidates any uploaded public key).",
                ex);
        }
    }

    private static String toPem(java.security.Key key, String label) {
        String base64 = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    private static void trySetPosix(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows dev host) — best effort.
        }
    }
}
