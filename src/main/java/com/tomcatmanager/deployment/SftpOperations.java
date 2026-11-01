package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.RemoteOperationException;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatExtensionInfo;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHStatPathExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * File operations used to deploy over SFTP. Only SFTP requests are used, so no shell command is
 * ever built from configuration or user input.
 */
final class SftpOperations {
    private static final int BUFFER = 64 * 1024;

    private SftpOperations() { }

    /** What currently exists in the deployment directory for a WAR file name. */
    record RemoteState(boolean warExists, long warSize, boolean explodedExists, Long freeBytes) {
        boolean applicationPresent() { return warExists || explodedExists; }
    }

    static RemoteState inspect(SftpClient sftp, String directory, String filename) throws IOException {
        SftpClient.Attributes dir = statOrNull(sftp, directory);
        if (dir == null || !dir.isDirectory()) throw new RemoteOperationException("Deployment directory " + directory + " does not exist.");
        SftpClient.Attributes war = statOrNull(sftp, join(directory, filename));
        SftpClient.Attributes exploded = statOrNull(sftp, join(directory, filename.substring(0, filename.length() - 4)));
        return new RemoteState(war != null && war.isRegularFile(), war == null ? 0 : war.getSize(),
                exploded != null && exploded.isDirectory(), freeBytes(sftp, directory));
    }

    /** Free bytes for the account, via the OpenSSH statvfs extension; null when the server does not support it. */
    static Long freeBytes(SftpClient sftp, String directory) {
        try {
            OpenSSHStatPathExtension ext = sftp.getExtension(OpenSSHStatPathExtension.class);
            if (ext == null || !ext.isSupported()) return null;
            OpenSSHStatExtensionInfo info = ext.stat(directory);
            return info.f_bavail * (info.f_frsize > 0 ? info.f_frsize : info.f_bsize);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Downloads a remote file to a local path, returning its SHA-256 and size. */
    static Checksum download(SftpClient sftp, String remote, Path local) throws IOException {
        Files.createDirectories(local.getParent());
        if (Files.exists(local)) throw new java.nio.file.FileAlreadyExistsException("local backup file already exists");
        MessageDigest digest = sha256();
        long total = 0;
        try (InputStream in = new DigestInputStream(sftp.read(remote), digest); OutputStream out = Files.newOutputStream(local)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) { out.write(buffer, 0, read); total += read; }
        }
        return new Checksum(HexFormat.of().formatHex(digest.digest()), total);
    }

    /** Streams {@code in} to a remote file, reporting progress; returns the number of bytes written. */
    static long upload(SftpClient sftp, InputStream in, String remote, LongConsumer progress) throws IOException {
        long total = 0, reported = 0;
        try (OutputStream out = sftp.write(remote)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (total - reported >= 8L * 1024 * 1024) { progress.accept(total); reported = total; }
            }
        }
        progress.accept(total);
        return total;
    }

    /** Re-reads a remote file and computes its SHA-256 (verifies what actually arrived). */
    static Checksum remoteChecksum(SftpClient sftp, String remote) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        try (InputStream in = new DigestInputStream(sftp.read(remote), digest)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) total += read;
        }
        return new Checksum(HexFormat.of().formatHex(digest.digest()), total);
    }

    /**
     * Moves the verified temporary file over the final name. Uses the OpenSSH atomic
     * posix-rename when available, so Tomcat never sees a partially written WAR.
     * Returns true when the replacement was atomic.
     */
    static boolean replace(SftpClient sftp, String temp, String target) throws IOException {
        OpenSSHPosixRenameExtension posix = sftp.getExtension(OpenSSHPosixRenameExtension.class);
        if (posix != null && posix.isSupported()) {
            posix.posixRename(temp, target);
            return true;
        }
        if (statOrNull(sftp, target) != null) sftp.remove(target);
        sftp.rename(temp, target);
        return false;
    }

    static void deleteQuietly(SftpClient sftp, String remote) {
        try { sftp.remove(remote); } catch (IOException ignored) { /* best effort */ }
    }

    static SftpClient.Attributes statOrNull(SftpClient sftp, String path) throws IOException {
        try {
            return sftp.stat(path);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) return null;
            throw e;
        }
    }

    static String join(String directory, String name) { return directory.endsWith("/") ? directory + name : directory + "/" + name; }

    record Checksum(String sha256, long size) { }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
