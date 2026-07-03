package com.brightspeed.inventoryapiservice.dto.request;

public class XConnectRequest {
    private String uniName;
    private String nmiName;

    // getters and setters
    public String getUniName() { return uniName; }
    public void setUniName(String uniName) { this.uniName = uniName; }

    public String getNmiName() { return nmiName; }
    public void setNmiName(String nmiName) { this.nmiName = nmiName; }
}