package com.brightspeed.inventoryapiservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "uniconnection.default")
public class UNIConnectionDefaults{

    private String serviceType;
    private String cktfmt;
    private int noOfEVCs_OVCsAllowed;
    private String autoNegotiate;
    private String subcriberType;
    private String status;
    private String bundling;
    private String allTo1Bundling;
    private String requestingAffiliate;
    private String functionalStatus;
    private String MCO;
    private String user;
    private String sourceSys;
    private String migration;
}

