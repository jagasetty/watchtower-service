package com.brightspeed.inventoryapiservice.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.brightspeed.inventoryapiservice.config.ApiConfig;
import com.brightspeed.inventoryapiservice.dto.response.OAuthResponse;
import com.brightspeed.inventoryapiservice.util.ApplicationUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OAuthService {
	
	private final ApiConfig apiConfig;
    private final RestTemplate restTemplate;

    public OAuthService(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
        this.restTemplate = new RestTemplate();
    }
	
	public String getAccessToken(){
		String user = apiConfig.getAuthUser();
		String pwd = apiConfig.getAuthPwd();
		String assuranceAuthUrl = apiConfig.getAuthUri();

		log.info("Fetching Auth token");
		OAuthResponse oAuthResponse = retrieveOAuthToken(restTemplate, assuranceAuthUrl, user, pwd);

		return oAuthResponse.getAccess_token();
	}
	
	private OAuthResponse retrieveOAuthToken(RestTemplate restTemplate, String authUrl, String clientID,
			String clientSecret) {
		HttpHeaders bossHeaders = ApplicationUtils.createAssuranceOAuthHeaders(clientID, clientSecret);

		MultiValueMap<String, String> bossOAuthMap = new LinkedMultiValueMap<>();
		bossOAuthMap.add("grant_type", "client_credentials");

		HttpEntity<MultiValueMap<String, String>> inventoryReqEntity = new HttpEntity<>(bossOAuthMap, bossHeaders);
		return restTemplate.postForObject(authUrl, inventoryReqEntity, OAuthResponse.class);
	}
}
