package com.healthcheck;

/**
 * 파트너 정보 (인바운드/아웃바운드 URL 포함)
 */
public class Partner {

    private final String id;
    private final String name;
    private final String inboundUrl;  // null = 인바운드 없음
    private final String outboundUrl; // null = 아웃바운드 없음

    public Partner(String id, String name, String inboundUrl, String outboundUrl) {
        this.id = id;
        this.name = name;
        this.inboundUrl = inboundUrl;
        this.outboundUrl = outboundUrl;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getInboundUrl() { return inboundUrl; }
    public String getOutboundUrl() { return outboundUrl; }
    public boolean hasInbound() { return inboundUrl != null && !inboundUrl.isEmpty(); }
    public boolean hasOutbound() { return outboundUrl != null && !outboundUrl.isEmpty(); }
}
