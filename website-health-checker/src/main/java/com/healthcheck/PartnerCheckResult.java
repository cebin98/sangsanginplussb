package com.healthcheck;

/**
 * 파트너별 인바운드/아웃바운드 체크 결과
 */
public class PartnerCheckResult {

    private final Partner partner;
    private CheckResult inboundResult;
    private CheckResult outboundResult;

    public PartnerCheckResult(Partner partner) {
        this.partner = partner;
    }

    public boolean isAlertNeeded() {
        boolean inboundAlert = inboundResult != null && inboundResult.isAlertNeeded();
        boolean outboundAlert = outboundResult != null && outboundResult.isAlertNeeded();
        return inboundAlert || outboundAlert;
    }

    public Partner getPartner() { return partner; }
    public CheckResult getInboundResult() { return inboundResult; }
    public void setInboundResult(CheckResult inboundResult) { this.inboundResult = inboundResult; }
    public CheckResult getOutboundResult() { return outboundResult; }
    public void setOutboundResult(CheckResult outboundResult) { this.outboundResult = outboundResult; }
}
