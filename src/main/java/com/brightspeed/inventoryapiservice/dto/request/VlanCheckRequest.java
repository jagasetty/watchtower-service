package com.brightspeed.inventoryapiservice.dto.request;

import java.util.List;

import lombok.Data;

@Data
public class VlanCheckRequest {
    private List<DeviceRequest> devices;
}
