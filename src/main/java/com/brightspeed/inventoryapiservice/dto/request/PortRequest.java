package com.brightspeed.inventoryapiservice.dto.request;

import lombok.Data;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortRequest {
    private String portValue;
    private String portSpeed;
    private String portType;
    private String stag;
    private String ctag;
}