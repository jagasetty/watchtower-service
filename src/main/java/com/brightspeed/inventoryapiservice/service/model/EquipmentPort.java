package com.brightspeed.inventoryapiservice.service.model;

import lombok.Data;

@Data
public class EquipmentPort {
    private String id;
    private String portKey;
    private String bw;
    private String portFunction;
    private String node_id;
    private boolean isUNI;
}