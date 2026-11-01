package com.tomcatmanager.connectivity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** WinSCP/PuTTY users bring .ppk keys; they must load and authenticate like OpenSSH keys. */
class PuttyKeyTest {
    @TempDir Path root;

    @Test void unencryptedPpkKeyLoadsAndAuthenticates() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String ppk = ppkV2(pair, "test-key");

        assertThat(SshKeyLoader.isPuttyKey(ppk)).isTrue();
        List<KeyPair> loaded = SshKeyLoader.load(ppk, null);
        assertThat(loaded).singleElement().satisfies(k -> assertThat(k.getPublic()).isEqualTo(pair.getPublic()));

        java.nio.file.Files.createDirectories(root);
        SshConnectivityClient client = new SshConnectivityClient();
        try (EmbeddedSshServer server = new EmbeddedSshServer(root, pair.getPublic(), true)) {
            ProbeReport report = new ProbeReport();
            client.probe(new SshConnectivityClient.SshTarget(InetAddress.getLoopbackAddress(), "localhost", server.port(), server.hostKeyFingerprint(),
                    SshCredentials.privateKey(EmbeddedSshServer.USER, ppk, null), null, Duration.ofSeconds(10)), null, report);
            assertThat(report.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.SUCCESS);
        } finally {
            client.destroy();
        }
    }

    @Test void publicKeyFileIsRejectedWithHelpfulMessage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SshKeyLoader.load("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHk user@host", null))
                .isInstanceOf(SshKeyLoader.InvalidKeyException.class);
    }

    /** Writes an unencrypted PuTTY v2 RSA key file (format documented in PuTTY's sshpubk.c). */
    private static String ppkV2(KeyPair pair, String comment) throws Exception {
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) pair.getPrivate();
        byte[] publicBlob = blob(out -> { string(out, "ssh-rsa".getBytes(StandardCharsets.US_ASCII)); mpint(out, pub.getPublicExponent()); mpint(out, pub.getModulus()); });
        byte[] privateBlob = blob(out -> { mpint(out, priv.getPrivateExponent()); mpint(out, priv.getPrimeP()); mpint(out, priv.getPrimeQ()); mpint(out, priv.getCrtCoefficient()); });
        byte[] macData = blob(out -> {
            string(out, "ssh-rsa".getBytes(StandardCharsets.US_ASCII)); string(out, "none".getBytes(StandardCharsets.US_ASCII));
            string(out, comment.getBytes(StandardCharsets.UTF_8)); string(out, publicBlob); string(out, privateBlob);
        });
        byte[] macKey = MessageDigest.getInstance("SHA-1").digest("putty-private-key-file-mac-key".getBytes(StandardCharsets.US_ASCII));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
        String pubB64 = Base64.getEncoder().encodeToString(publicBlob), privB64 = Base64.getEncoder().encodeToString(privateBlob);
        return "PuTTY-User-Key-File-2: ssh-rsa\nEncryption: none\nComment: " + comment + "\n"
                + "Public-Lines: " + lines(pubB64).size() + "\n" + String.join("\n", lines(pubB64)) + "\n"
                + "Private-Lines: " + lines(privB64).size() + "\n" + String.join("\n", lines(privB64)) + "\n"
                + "Private-MAC: " + HexFormat.of().formatHex(mac.doFinal(macData)) + "\n";
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] blob(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); }
        return bytes.toByteArray();
    }
    private static void string(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static void mpint(DataOutputStream out, BigInteger value) throws IOException { string(out, value.toByteArray()); }
    private static List<String> lines(String b64) {
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < b64.length(); i += 64) lines.add(b64.substring(i, Math.min(b64.length(), i + 64)));
        return lines;
    }
}
