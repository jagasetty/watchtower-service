package com.brightspeed.inventoryapiservice.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.brightspeed.inventoryapiservice.config.ApiConfig;
import com.brightspeed.inventoryapiservice.service.OAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ServiceClient {

	private RestTemplate restTemplate = new RestTemplate();
    private final OAuthService oAuthService;
    private final ApiConfig apiConfig;

	@Autowired
	public ServiceClient(RestTemplate restTemplate, OAuthService oAuthService, ApiConfig apiConfig) {
		this.restTemplate = restTemplate;
		this.oAuthService = oAuthService;
		this.apiConfig = apiConfig;
	}

	@Value("${rest.e2-etrace-url}")
	public String E2_ETRACE_URL;
	
	@Value("${rest.e2-nmi-creation-url:}")
	public String E2_NMI_CREATION_URL;
	
	@Value("${rest.e2-uni-creation-url:}")
	public String E2_UNI_CREATION_URL;
	
	@Value("${rest.e2-evc-creation-url:}")
	public String E2_EVC_CREATION_URL;
	
	@Value("${rest.e2-getlogicaluni-url:}")
	public String E2_GET_LOGICAL_UNI_URL;
	
	@Value("${rest.e2-deleteuni-url:}")
	public String E2_UNI_DELETE_URL;
	
	@Value("${rest.e2-deleteevc-url:}")
	public String E2_EVC_DELETE_URL;
	
	@Value("${rest.e2-deletenni-url:}")
	public String E2_NNI_DELETE_URL;
	
	@Value("${rest.e2-getecktid-url:}")
	private String E2_GET_ECKID_URL;
	
	@Value("${rest.e2-getevc-url:}")
	private String E2_EVC_DETAILS_URL;
	
	@Value("${rest.e2-getnni-url:}")
	private String E2_GET_NNI_URL;
	
	public Map<String, Object> getRoute(String srceDevice, String targetDevice) {
		log.info("Calling getRoute from {} to {}", srceDevice, targetDevice);

		Map<String, Object> traceResp = new HashMap<>();

		if (E2_ETRACE_URL == null || E2_ETRACE_URL.isBlank()) {
			log.error("E2 ETRACE URL is not configured");
			return traceResp;
		}

		String url = String.format("%s?deviceIdentifierSource=%s&deviceIdentifierTarget=%s", E2_ETRACE_URL, srceDevice,
				targetDevice);
		log.info("Constructed E2 Trace URL: {}", url);

		try {
			apiConfig.setContext("e2");
			HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			HttpEntity<String> entity = new HttpEntity<>(headers);
			ResponseEntity<Map> response = restTemplate.exchange(
		            url,
		            HttpMethod.GET,
		            entity,
		            Map.class
		        );
			if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
				traceResp = response.getBody();
				log.info("Received response from E2 Trace with keys: {}", traceResp.keySet());
			} else {
				log.warn("E2 Trace returned non-200 or empty body: {}", response.getStatusCode());
			}
		} catch (Exception ex) {
			log.error("GetRoute caught Exception: Reason - {}", ex.getMessage(), ex);
		}

		return traceResp;
	}
	
	public Map<String, Object> createNmiInE2(Map<String, Object> requestPayload) {
	    log.info("Calling E2 POST API with payload: {}", requestPayload);

	    Map<String, Object> responseMap = new HashMap<>();

	    if (E2_NMI_CREATION_URL == null || E2_NMI_CREATION_URL.isBlank()) {
	        log.error("E2 POST CKID URL is not configured");
	        return responseMap;
	    }

	    //String url = "https://api-qa.brightspeed.com/e2edc-qa-v1/circuit/postckid"; // override from properties if needed
	    
	    String url=E2_NMI_CREATION_URL;
	    log.info("E2 url: "+ url);
	    try {
	        apiConfig.setContext("e2");

	        // Get OAuth token and build headers
	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
	        log.info("oAuthService. AccessToken"+oAuthService.getAccessToken());
	        headers.set("Content-Type", "application/json");

	        // Create HTTP request entity
	        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestPayload, headers);

	        // Send POST request
	        ResponseEntity<Map> response = restTemplate.exchange(
	                url,
	                HttpMethod.POST,
	                entity,
	                Map.class
	        );

	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
	            responseMap = response.getBody();
	            log.info("E2 POST API successful, response keys: {}", responseMap.keySet());
	            return responseMap;
	        } else {
	            String msg = "E2 POST API returned error: " + response.getStatusCode();
	            log.error(msg);
	            throw new RuntimeException(msg);
	        }
	        
	     } catch (HttpClientErrorException e) {
	         // Capture 4xx errors
	         String body = e.getResponseBodyAsString();
	         log.error("E2 POST returned client error: {}", body);
	         throw new RuntimeException("E2 Client Error: " + body);
	     } catch (HttpServerErrorException e) {
	         // Capture 5xx errors
	         log.error("E2 POST returned server error: {}", e.getResponseBodyAsString());
	         throw new RuntimeException("E2 Server Error: " + e.getMessage());
	     } catch (Exception ex) {
	         log.error("E2 POST API unexpected error: {}", ex.getMessage(), ex);
	         throw new RuntimeException("Failed to call E2 POST API: " + ex.getMessage(), ex);
	     }
	 }
	
	public Map<String, Object> createUniInE2(String jsonPayload) {
	    log.info("Calling E2 POST API for UNI with payload: {}", jsonPayload);

	    Map<String, Object> responseMap = new HashMap<>();

	    if (E2_UNI_CREATION_URL == null || E2_UNI_CREATION_URL.isBlank()) {
	        log.error("E2 UNI creation URL is not configured");
	        return responseMap;
	    }

	    String url = E2_UNI_CREATION_URL;
	    log.info("E2 UNI URL: {}", url);

	    try {
	        apiConfig.setContext("e2");

	        // Get OAuth token and build headers
	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
	        headers.setContentType(MediaType.APPLICATION_JSON);

	        // Create HTTP request entity with JSON payload as String
	        HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

	        // Send POST request, expecting Map response
	        ResponseEntity<Map> response = restTemplate.exchange(
	                url,
	                HttpMethod.POST,
	                entity,
	                Map.class
	        );

	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
	            responseMap = response.getBody();
	            log.info("E2 POST API for UNI successful, response keys: {}", responseMap.keySet());
	            return responseMap;
	        } else {
	            String msg = "E2 POST API for UNI returned error: " + response.getStatusCode();
	            log.error(msg);
	            throw new RuntimeException(msg);
	        }

	    } catch (HttpClientErrorException e) {
	        String body = e.getResponseBodyAsString();
	        log.error("E2 POST UNI returned client error: {}", body);
	        throw new RuntimeException("E2 Client Error: " + body);
	    } catch (HttpServerErrorException e) {
	        log.error("E2 POST UNI returned server error: {}", e.getResponseBodyAsString());
	        throw new RuntimeException("E2 Server Error: " + e.getMessage());
	    } catch (Exception ex) {
	        log.error("E2 POST UNI API unexpected error: {}", ex.getMessage(), ex);
	        throw new RuntimeException("Failed to call E2 POST UNI API: " + ex.getMessage(), ex);
	    }
	}
	
	public Map<String, Object> createEVCInE2(String jsonPayload, String uniId1, String uniId2, String epInstanceId1, String epInstanceId2) {
	    log.info("Calling E2 POST API for EVC with payload: {}", jsonPayload);

	    Map<String, Object> responseMap = new HashMap<>();

	    if (E2_EVC_CREATION_URL == null || E2_EVC_CREATION_URL.isBlank()) {
	        log.error("E2 EVC creation URL is not configured");
	        return responseMap;
	    }

	    // Construct URL with query parameters
	    String url = E2_EVC_CREATION_URL + "?uniReferenceOne=" + uniId1 
	            + "&epInstanceIdOne=" + epInstanceId1 
	            + "&uniReferenceTwo=" + uniId2 
	            + "&epInstanceIdTwo=" + epInstanceId2;
	    log.info("E2 EVC URL with query params: {}", url);

	    try {
	        apiConfig.setContext("e2");

	        // Get OAuth token and build headers
	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
	        headers.setContentType(MediaType.APPLICATION_JSON);

	        // Create HTTP request entity with JSON payload as String
	        HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

	        // Send POST request, expecting Map response
	        ResponseEntity<Map> response = restTemplate.exchange(
	                url,
	                HttpMethod.POST,
	                entity,
	                Map.class
	        );

	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
	            responseMap = response.getBody();
	            log.info("E2 POST API for EVC successful, response keys: {}", responseMap.keySet());
	            return responseMap;
	        } else {
	            String msg = "E2 POST API for EVC returned error: " + response.getStatusCode();
	            log.error(msg);
	            throw new RuntimeException(msg);
	        }

	    } catch (HttpClientErrorException e) {
	        String body = e.getResponseBodyAsString();
	        log.error("E2 POST EVC returned client error: {}", body);
	        throw new RuntimeException("E2 Client Error: " + body);
	    } catch (HttpServerErrorException e) {
	        log.error("E2 POST EVC returned server error: {}", e.getResponseBodyAsString());
	        throw new RuntimeException("E2 Server Error: " + e.getMessage());
	    } catch (Exception ex) {
	        log.error("E2 POST EVC API unexpected error: {}", ex.getMessage(), ex);
	        throw new RuntimeException("Failed to call E2 POST EVC API: " + ex.getMessage(), ex);
	    }
	}

	
//	public Map<String, Object> getUniResponseFromE2(String uniId) {
//	    log.info("Inside getUniResponseFromE2 , {}", uniId);
//
//	    Map<String, Object> responseMap = new HashMap<>();
//	    apiConfig.setContext("e2");
//
//	    if (E2_GET_LOGICAL_UNI_URL == null || E2_GET_LOGICAL_UNI_URL.isBlank()) {
//	        log.error("E2 GET LOGICAL UNI URL is not configured");
//	        return responseMap;
//	    }
//
//	    String url = String.format("%s?uniServiceInstanceId=%s", E2_GET_LOGICAL_UNI_URL, uniId);
//	    log.info("Constructed E2 GET LOGICAL UNI URL: {}", url);
//
//	    try {
//	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
//	        HttpEntity<String> entity = new HttpEntity<>(headers);
//	        ResponseEntity<Map> response = restTemplate.exchange(
//	                url,
//	                HttpMethod.GET,
//	                entity,
//	                Map.class
//	        );
//
//	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
//	            responseMap = response.getBody();
//	            log.info("Received response from E2: {}", responseMap);
//	        } else {
//	            log.warn("E2 Fetch Uni returned non-200 or empty body: {}", response.getStatusCode());
//	        }
//	    } catch (Exception ex) {
//	        log.error("GetRoute caught Exception: Reason - {}", ex.getMessage(), ex);
//	    }
//
//	    return responseMap;
//	}
	
	public Map<String, Object> deleteUniInE2(String cktid) {
	    log.info("Calling E2 DELETE API for deleting UNI: {}", cktid);

	    Map<String, Object> responseMap = new HashMap<>();

	    if (E2_UNI_DELETE_URL == null || E2_UNI_DELETE_URL.isBlank()) {
	        log.error("E2 UNI DELETE URL is not configured");
	        return responseMap;
	    }

	    // Append the cktid as a query parameter to the URL
	    String url = E2_UNI_DELETE_URL + "?uniServiceInstanceId=" + cktid;
	    log.info("E2 UNI URL: {}", url);

	    try {
	        apiConfig.setContext("e2");

	        // Get OAuth token and build headers
	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
	        headers.setContentType(MediaType.APPLICATION_JSON);

	        // Create HTTP request entity (no body, just headers)
	        HttpEntity<Void> entity = new HttpEntity<>(headers);

	        // Send DELETE request, expecting Map response
	        ResponseEntity<Map> response = restTemplate.exchange(
	                url,
	                HttpMethod.DELETE,
	                entity,
	                Map.class
	        );

	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
	            responseMap = response.getBody();
	            log.info("E2 DELETE API for UNI deletion, response keys: {}", responseMap.keySet());
	            return responseMap;
	        } else {
	            responseMap.put("status", response.getStatusCodeValue());
	            responseMap.put("message", "E2 DELETE API returned non-success: " + response.getStatusCode());
	            return responseMap;
	        }

	    } catch (HttpClientErrorException e) {
	        String body = e.getResponseBodyAsString();
	        int statusCode = e.getRawStatusCode();

	        log.warn("E2 DELETE UNI returned client error {}: {}", statusCode, body);
	        responseMap.put("status", statusCode);
	        responseMap.put("message", "E2 Client Error: " + body);
	        return responseMap;

	    } catch (HttpServerErrorException e) {
	        String body = e.getResponseBodyAsString();
	        log.error("E2 DELETE UNI returned server error: {}", body);
	        responseMap.put("status", 500);
	        responseMap.put("message", "E2 Server Error: " + body);
	        return responseMap;

	    } catch (Exception ex) {
	        log.error("E2 DELETE UNI API unexpected error: {}", ex.getMessage(), ex);
	        responseMap.put("status", 500);
	        responseMap.put("message", "Exception while calling E2 DELETE UNI API: " + ex.getMessage());
	        return responseMap;
	    }
	}
	
	public Map<String, Object> deleteEVCInE2(String cktid, String uniServiceInstanceId1, String uniServiceInstanceId2) {
		log.info("Calling E2 DELETE API for deleting EVC: {}, uniServiceInstanceId1: {}, uniServiceInstanceId2: {}",
				cktid, uniServiceInstanceId1, uniServiceInstanceId2);

		Map<String, Object> responseMap = new HashMap<>();

		if (E2_EVC_DELETE_URL == null || E2_EVC_DELETE_URL.isBlank()) {
			log.error("E2 EVC DELETE URL is not configured");
			return responseMap;
		}

		String url = E2_EVC_DELETE_URL + "?serviceInstanceId=" + cktid + "&uniServiceInstanceId1="
				+ uniServiceInstanceId1 + "&uniServiceInstanceId2=" + uniServiceInstanceId2;
		log.info("E2 DELETE URL: {}", url);

		try {
			apiConfig.setContext("e2");

			// Get OAuth token and build headers
			HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Void> entity = new HttpEntity<>(headers);

			ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, Map.class);

			if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
				responseMap = response.getBody();
				log.info("E2 DELETE API for EVC deletion, response keys: {}", responseMap.keySet());
				return responseMap;
			} else {
	            responseMap.put("status", response.getStatusCodeValue());
	            responseMap.put("message", "E2 DELETE API returned non-success: " + response.getStatusCode());
	            return responseMap;
	        }

	    } catch (HttpClientErrorException e) {
	        String body = e.getResponseBodyAsString();
	        int statusCode = e.getRawStatusCode();

	        log.warn("E2 DELETE EVC returned client error {}: {}", statusCode, body);
	        responseMap.put("status", statusCode);
	        responseMap.put("message", "E2 Client Error: " + body);
	        return responseMap;

	    } catch (HttpServerErrorException e) {
	        String body = e.getResponseBodyAsString();
	        log.error("E2 DELETE EVC returned server error: {}", body);
	        responseMap.put("status", 500);
	        responseMap.put("message", "E2 Server Error: " + body);
	        return responseMap;

	    } catch (Exception ex) {
	        log.error("E2 DELETE EVC API unexpected error: {}", ex.getMessage(), ex);
	        responseMap.put("status", 500);
	        responseMap.put("message", "Exception while calling E2 DELETE EVC API: " + ex.getMessage());
	        return responseMap;
	    }
	}

	public Map<String, Object> deleteNNIInE2(String cktid) {
	    log.info("Calling E2 DELETE API for deleting NNI: {}", cktid);

	    Map<String, Object> responseMap = new HashMap<>();

	    if (E2_NNI_DELETE_URL == null || E2_NNI_DELETE_URL.isBlank()) {
	        log.error("E2 NNI DELETE URL is not configured");
	        return responseMap;
	    }

	    // Append the cktid as a query parameter to the URL
	    String url = E2_NNI_DELETE_URL + "?circuitId=" + cktid;
	    log.info("E2 EVC URL: {}", url);

	    try {
	        apiConfig.setContext("e2");

	        // Get OAuth token and build headers
	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
	        headers.setContentType(MediaType.APPLICATION_JSON);

	        // Create HTTP request entity (no body, just headers)
	        HttpEntity<Void> entity = new HttpEntity<>(headers);

	        // Send DELETE request, expecting Map response
	        ResponseEntity<Map> response = restTemplate.exchange(
	                url,
	                HttpMethod.DELETE,
	                entity,
	                Map.class
	        );

	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
	            responseMap = response.getBody();
	            log.info("E2 DELETE API for NNI deletion, response keys: {}", responseMap.keySet());
	            return responseMap;
	        } else {
	            responseMap.put("status", response.getStatusCodeValue());
	            responseMap.put("message", "E2 DELETE API returned non-success: " + response.getStatusCode());
	            return responseMap;
	        }

	    } catch (HttpClientErrorException e) {
	        String body = e.getResponseBodyAsString();
	        int statusCode = e.getRawStatusCode();

	        log.warn("E2 DELETE NNI returned client error {}: {}", statusCode, body);
	        responseMap.put("status", statusCode);
	        responseMap.put("message", body);
	        return responseMap;
	    } catch (HttpServerErrorException e) {
	        String body = e.getResponseBodyAsString();
	        log.error("E2 DELETE NNI returned server error: {}", body);
	        responseMap.put("status", 500);
	        responseMap.put("message", "E2 Server Error: " + body);
	        return responseMap;

	    } catch (Exception ex) {
	        log.error("E2 DELETE NNI unexpected error: {}", ex.getMessage(), ex);
	        responseMap.put("status", 500);
	        responseMap.put("message", "Exception while calling E2 DELETE NNI API: " + ex.getMessage());
	        return responseMap;
	    }
	}
	
	public JsonNode getPortDetailsFromEckid(String circuitId) {
		log.info("Calling E2 GET API to fetch portDetails for circuitId: {}", circuitId);

		if (E2_GET_ECKID_URL == null || E2_GET_ECKID_URL.isBlank()) {
			log.error("E2 GET ECKID URL is not configured");
			return null;
		}

		String url = E2_GET_ECKID_URL + "?circuitId=" + circuitId;
		log.info("Constructed E2 GET ECKID URL: {}", url);

		try {
			apiConfig.setContext("e2");

			// Build OAuth headers
			HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Void> entity = new HttpEntity<>(headers);

			// Send GET request
			ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

			if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
				log.info("E2 GET ECKID API call successful");
				ObjectMapper mapper = new ObjectMapper();
				JsonNode rootNode = mapper.convertValue(response.getBody(), JsonNode.class);
				JsonNode portDetails = rootNode.get("portDetails");

				if (portDetails != null && portDetails.isArray()) {
					log.info("Port details successfully extracted for circuitId {}", circuitId);
					return portDetails;
				} else {
					log.warn("No 'portDetails' array found in E2 GET response for circuitId {}", circuitId);
				}
			} else {
				log.error("E2 GET ECKID returned non-success status: {}", response.getStatusCode());
				throw new RuntimeException("E2 GET failed with status: " + response.getStatusCode());
			}

		} catch (HttpClientErrorException e) {
			String body = e.getResponseBodyAsString();
			log.error("E2 GET ECKID client error: {}", body);
			throw new RuntimeException("E2 Client Error: " + body, e);
		} catch (HttpServerErrorException e) {
			log.error("E2 GET ECKID server error: {}", e.getResponseBodyAsString());
			throw new RuntimeException("E2 Server Error: " + e.getMessage(), e);
		} catch (Exception e) {
			log.error("Unexpected error during E2 GET ECKID call: {}", e.getMessage(), e);
			throw new RuntimeException("Failed to fetch port details from E2: " + e.getMessage(), e);
		}

		return null;
	}

	public Map<String, Object> getEVCDetailsFromE2(String circuitId) {
		log.info("Calling E2 GET API to fetch EVC details for circuitId: {}", circuitId);

		Map<String, Object> responseMap = new HashMap<>();

		if (E2_EVC_DETAILS_URL == null || E2_EVC_DETAILS_URL.isBlank()) {
			log.error("E2 EVC DETAILS URL is not configured");
			return responseMap;
		}

		// Construct the full URL with both query parameters
		String url = E2_EVC_DETAILS_URL + "?serviceInstanceID=" + circuitId;
		log.info("E2 GET URL: {}", url);

		try {
			apiConfig.setContext("e2");

			// Create headers with OAuth token
			HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Void> entity = new HttpEntity<>(headers);

			// Perform GET request
			ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

			if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
				responseMap = response.getBody();
				log.info("E2 GET API response keys: {}", responseMap.keySet());
				return responseMap;
			} else {
				String msg = "E2 GET API returned error: " + response.getStatusCode();
				log.error(msg);
				throw new RuntimeException(msg);
			}

		} catch (HttpClientErrorException e) {
			log.error("E2 GET API client error: {}", e.getResponseBodyAsString());
			throw new RuntimeException("E2 Client Error: " + e.getResponseBodyAsString());
		} catch (HttpServerErrorException e) {
			log.error("E2 GET API server error: {}", e.getResponseBodyAsString());
			throw new RuntimeException("E2 Server Error: " + e.getMessage());
		} catch (Exception ex) {
			log.error("Unexpected error during E2 GET API call: {}", ex.getMessage(), ex);
			throw new RuntimeException("Failed to call E2 GET API: " + ex.getMessage(), ex);
		}
	}
	
	public Map<String, Object> getNniResponseFromE2(String circuitId) {
	    log.info("Inside getNniResponseFromE2 , circuitId: {}", circuitId);

	    Map<String, Object> responseMap = new HashMap<>();
	    apiConfig.setContext("e2");

	    if (E2_GET_NNI_URL == null || E2_GET_NNI_URL.isBlank()) {
	        log.error("E2 GET NNI URL is not configured");
	        return responseMap;
	    }

	    try {
	        String encodedCktid = URLEncoder.encode(circuitId, StandardCharsets.UTF_8.toString());
	        String url = String.format("%s?circuitId=%s", E2_GET_NNI_URL, encodedCktid);
	        log.info("Constructed E2 GET NNI URL: {}", url);

	        HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
	        HttpEntity<String> entity = new HttpEntity<>(headers);
	        ResponseEntity<Map> response = restTemplate.exchange(
	                url,
	                HttpMethod.GET,
	                entity,
	                Map.class
	        );

	        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
	            responseMap = response.getBody();
	            log.info("Received response from E2 for NNI: {}", responseMap);
	        } else {
	            log.warn("E2 Fetch NNI returned non-200 or empty body: {}", response.getStatusCode());
	        }
	    } catch (Exception ex) {
	        log.error("getNniResponseFromE2 caught Exception: {}", ex.getMessage(), ex);
	    }

	    return responseMap;
	}

}
