package com.tomcatmanager.connectivity;

import java.util.Set;

/**
 * Decides from POSIX mode bits whether an account can create files in a directory, without
 * writing anything to the server. Creating entries needs both write and search (x) permission.
 * ACLs and mandatory access control (SELinux) are not visible over SFTP, so a positive answer
 * is necessary but not always sufficient.
 */
public final class DirectoryPermissions {
    public enum Access { WRITABLE, NOT_WRITABLE }

    private DirectoryPermissions() { }

    public static Access evaluate(int mode, int ownerUid, int ownerGid, int uid, Set<Integer> gids) {
        if (uid == 0) return Access.WRITABLE;
        int bits;
        if (uid == ownerUid) bits = (mode >> 6) & 7;
        else if (gids.contains(ownerGid)) bits = (mode >> 3) & 7;
        else bits = mode & 7;
        return (bits & 3) == 3 ? Access.WRITABLE : Access.NOT_WRITABLE;
    }

    public static String symbolic(int mode) {
        StringBuilder sb = new StringBuilder(9);
        String rwx = "rwx";
        for (int shift = 6; shift >= 0; shift -= 3) {
            int bits = (mode >> shift) & 7;
            for (int i = 0; i < 3; i++) sb.append((bits & (4 >> i)) != 0 ? rwx.charAt(i) : '-');
        }
        return sb.toString();
    }
}
