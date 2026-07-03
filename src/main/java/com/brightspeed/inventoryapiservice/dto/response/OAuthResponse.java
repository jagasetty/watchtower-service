package com.brightspeed.inventoryapiservice.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OAuthResponse {

	private String access_token;
	private String token_type;
	private String scope;

}
