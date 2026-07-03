package com.brightspeed.inventoryapiservice.dto.response;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangeOrderResponse {

	private int code;
	private String message;

	private Boolean isDispatchRequired;
}
