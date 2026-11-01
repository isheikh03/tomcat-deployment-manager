package com.tomcatmanager.connectivity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class ConnectionCheck {
    @Enumerated(EnumType.STRING) @Column(name = "check_type", nullable = false, length = 40)
    private CheckType checkType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private CheckStatus status;
    @Column(length = 1000)
    private String detail;

    protected ConnectionCheck() { }
    public ConnectionCheck(CheckType checkType, CheckStatus status, String detail) {
        this.checkType = checkType; this.status = status; this.detail = detail;
    }

    public CheckType getCheckType() { return checkType; }
    public CheckStatus getStatus() { return status; }
    public String getDetail() { return detail; }
}
