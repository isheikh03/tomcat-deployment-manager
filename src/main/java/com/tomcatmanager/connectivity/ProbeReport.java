package com.tomcatmanager.connectivity;

import java.util.ArrayList;
import java.util.List;

/** Collects check results while a connection test runs. Every detail is sanitized on entry. */
public final class ProbeReport {
    private final List<ConnectionCheck> checks = new ArrayList<>();
    private HostKeyVerification hostKeyVerification = HostKeyVerification.NOT_CHECKED;
    private String presentedHostKey;
    private String serverInfo;

    public void record(CheckType type, CheckStatus status, String detail) {
        checks.add(new ConnectionCheck(type, status, DiagnosticSanitizer.clean(detail)));
    }
    public void success(CheckType type, String detail) { record(type, CheckStatus.SUCCESS, detail); }
    public void warning(CheckType type, String detail) { record(type, CheckStatus.WARNING, detail); }
    public void failed(CheckType type, String detail) { record(type, CheckStatus.FAILED, detail); }
    public void skip(String reason, CheckType... types) { for (CheckType t : types) record(t, CheckStatus.SKIPPED, reason); }
    public void notApplicable(CheckType... types) { for (CheckType t : types) record(t, CheckStatus.NOT_APPLICABLE, null); }

    public void hostKey(HostKeyVerification verification, String presented) {
        this.hostKeyVerification = verification; this.presentedHostKey = presented;
    }
    public void serverInfo(String info) { this.serverInfo = DiagnosticSanitizer.clean(info, 255); }

    /** FAILED if any check failed, WARNING if any warned, otherwise SUCCESS. */
    public CheckStatus outcome() {
        boolean warning = false;
        for (ConnectionCheck c : checks) {
            if (c.getStatus() == CheckStatus.FAILED) return CheckStatus.FAILED;
            if (c.getStatus() == CheckStatus.WARNING) warning = true;
        }
        return warning ? CheckStatus.WARNING : CheckStatus.SUCCESS;
    }

    /** Checks in pipeline order (CheckType declaration order), regardless of recording order. */
    public List<ConnectionCheck> checks() {
        return checks.stream().sorted(java.util.Comparator.comparing(ConnectionCheck::getCheckType)).toList();
    }
    public HostKeyVerification hostKeyVerification() { return hostKeyVerification; }
    public String presentedHostKey() { return presentedHostKey; }
    public String serverInfo() { return serverInfo; }
    public CheckStatus statusOf(CheckType type) {
        for (ConnectionCheck c : checks) if (c.getCheckType() == type) return c.getStatus();
        return null;
    }
}
