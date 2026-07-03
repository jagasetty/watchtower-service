package com.brightspeed.inventoryapiservice.service.model;

import lombok.Data;

@Data
public class Equipment {
    private String node_id;
    private String IPV4MGMROUTERID;
    private String TID;
    private String model;
    private String deviceCategory;
}