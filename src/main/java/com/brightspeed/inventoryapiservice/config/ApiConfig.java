package com.brightspeed.inventoryapiservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;

@Getter
@Configuration
public class ApiConfig {

    // Inventory auth
    @Value("${rest.brspd.inventory.auth.uri:}")
    private String inventoryUri;

    @Value("${rest.brspd.inventory.auth.authUser:}")
    private String inventoryUser;

    @Value("${rest.brspd.inventory.auth.authPwd:}")
    private String inventoryPwd;

    // E2 auth
    @Value("${rest.brspd.inventory.e2.auth.uri:}")
    private String e2Uri;

    @Value("${rest.brspd.inventory.e2.auth.authUser:}")
    private String e2User;

    @Value("${rest.brspd.inventory.e2.auth.authPwd:}")
    private String e2Pwd;

    @Value("${rest.nni.api.url:}")
    private String createNNIUrl;

    @Value("${rest.uni.api.url:}")
    private String createUNIUrl;
    
    @Value("${rest.nni.update.url:}")
    private String updateNNIUrl;
    
    @Value("${rest.uni.update.url:}")
    private String updateUNIUrl;
    
    // Maestro auth and URLs
    @Value("${rest.brspd.inventory.maestro.auth.uri:}")
    private String maestroUri;

    @Value("${rest.brspd.inventory.maestro.auth.authUser:}")
    private String maestroUser;

    @Value("${rest.brspd.inventory.maestro.auth.authPwd:}")
    private String maestroPwd;
    
    @Value("${rest.maestro.vlanAvailability.url:}")
    private String maestroVlanAvailabilityUrl;
    
    @Value("${rest.maestro.bandwidthAvailability.url:}")
    private String maestroBandwidthAvailabilityUrl;
    
    @Value("${rest.maestro.serviceAvailability.url:}")
    private String maestroServiceAvailabilityUrl;
    
    private String currentContext = "inventory"; // default

    public void setContext(String context) {
        this.currentContext = context;
    }

    
    
    public String getAuthUri() {
        return switch (currentContext) {
            case "e2" -> e2Uri;
            case "maestro" -> maestroUri;
            default -> inventoryUri;
        };
    }

    public String getAuthUser() {
        return switch (currentContext) {
            case "e2" -> e2User;
            case "maestro" -> maestroUser;
            default -> inventoryUser;
        };
    }

    public String getAuthPwd() {
        return switch (currentContext) {
            case "e2" -> e2Pwd;
            case "maestro" -> maestroPwd;
            default -> inventoryPwd;
        };
    }
}

