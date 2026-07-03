package com.brightspeed.inventoryapiservice.dto.request;

import java.util.List;

public record NniPortStagDto(String nniId, Integer firstAvailableStag, List<String> portKeys) { }
