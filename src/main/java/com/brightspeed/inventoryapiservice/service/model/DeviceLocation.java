package com.brightspeed.inventoryapiservice.service.model;

import org.springframework.boot.autoconfigure.domain.EntityScan;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@EntityScan
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLocation {

    private String addressId;
    private String name;
    private String addressLine;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String stateAbbr;
    private String zip;
    private String country;
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;
    private String latitudeStr;
    private String longitudeStr;
    private String networkType;
    private String u_site_name;
    private String phone;
    private String type;
}