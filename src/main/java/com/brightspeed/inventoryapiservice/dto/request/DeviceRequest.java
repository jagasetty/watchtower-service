package com.brightspeed.inventoryapiservice.dto.request;

import java.util.List;

import lombok.Data;

@Data
public class DeviceRequest {
    private String deviceIp;
    private String deviceIdentifier;
    private String deviceModel;
    private String deviceCategory;
    private List<PortRequest> ports;
}