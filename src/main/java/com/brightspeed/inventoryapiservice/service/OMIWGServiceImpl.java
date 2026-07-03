package com.brspd.iwg.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.integration.JavaUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.brspd.iwg.dto.BossPatchRequest;
import com.brspd.iwg.dto.FetchBossReq;
import com.brspd.iwg.dto.KafkaPayload;
import com.brspd.iwg.dto.RadiusPatchRequest;
import com.brspd.iwg.dto.Response;
import com.brspd.iwg.publisher.KafkaPublisher;
import com.brspd.iwg.util.ApplicationUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.logging.Level;
import java.util.logging.Logger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OMIWGServiceImpl implements OMIWGService {

	@Value("${brightspeed.api.base.url:}")
	private String baseURL;

	@Value("${brightspeed.api.unblock.url:}")
	private String unblock;

	@Value("${brightspeed.api.boss.url.ban:}")
	private String bossURLBan;

	@Value("${brightspeed.api.boss.url.instanceId:}")
	private String bossURLInstanceId;

	@Value("${brightspeed.api.boss.url.patch:}")
	private String bossURLUpdate;

	@Value("${brightspeed.api.boss.url.payment:}")
	private String bossURLPayment;

	@Value("${brightspeed.api.boss.username:}")
	private String username;

	@Value("${brightspeed.api.boss.password:}")
	private String password;

	@Autowired
	private OAuthTokenService oAuthTokenService;

	@Autowired
	private KafkaPublisher kafkaPublisher;

	private static final Logger log = Logger.getLogger("OMIWGServiceImpl");

	private static final String ORDER_ID_SERVICE_INSTANCE_ID_NOT_PROVIDED = "{\"status\": \"Order Id /Service instance id/Serial-Num not provided\"}";
	private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MM-dd-yyyy");
	private final static String NULL = "NULL";
	// private static Gson gson = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public Response getSubscriberId(String IPAddress) {
		ResponseEntity<String> backendResponse;
		Response response = new Response();
		String subscriber = "";
		try {

			String token = oAuthTokenService.getToken1();
			String subscriberUrl = baseURL + "sessionByIPv4/" + IPAddress;
			RestTemplate restTemplate = new RestTemplate();
			HttpHeaders bossOAuthHeader = ApplicationUtils.createOAuthTokenHeader(token);
			HttpEntity<String> request = new HttpEntity<String>(IPAddress, bossOAuthHeader);
            log.info("[GET SUBSCRIBER ID] Sending GET request. URL: " + subscriberUrl + ", Headers: " + bossOAuthHeader + ", Body: " + IPAddress);
            backendResponse = restTemplate.exchange(subscriberUrl, HttpMethod.GET, request, String.class, IPAddress);
            log.info("[GET SUBSCRIBER ID] Received response: " + backendResponse);
			if (backendResponse != null) {
				String body = backendResponse.getBody();
                log.info("[GET SUBSCRIBER ID] Response body: " + body);
				JsonArray o = (JsonArray) new JsonParser().parse(body);
				if (!o.isEmpty() || o.size() > 0) {
					subscriber = o.get(0).getAsJsonObject().get("subscriberId").getAsString();
					log.info("subscriber for IPAddress-- " + subscriber + "  " + IPAddress);
					response.setSubscriberId(subscriber);
				} else {
					response.setMessage("Subscriber Id not found in Radius");
					log.info("response " + response);
				}

			} else {
				log.info("Subscriber Id not found in Radius for IPAddress " + IPAddress);
				response.setMessage("Subscriber Id not found in Radius");
				log.info("response" + response);
				return response;
			}
		} catch (RestClientException e) {
			// todo create ServiceNow case
			log.info("Failed Radius getSubscriberId call Exception: {} " + e.getMessage());
			e.printStackTrace();
			return response;
		}
		return response;
	}

	@Override
	public Response orchestrator(String IPAddress, String userAction, String retryCount) {
		log.info("OMIWGServiceImpl::orchestrator: IPAddress: "+IPAddress+", userAction: "+userAction+", retrycount: "+ retryCount);

		ResponseEntity<String> SnowResponse = null;
		Response response = new Response();
		String subscriberId = "";
		Gson gson = new Gson();
		try {
			subscriberId = getSubscriberId(IPAddress).getSubscriberId();// "0000037365",
			// "0000039109", "0001563848"
		} catch (RestClientException e) {
			log.info("Failed to get subscriberId: {} " + e.getMessage());
			response.setSubscriberId(NULL);
			response.setMessage("Failed to find Subscriber Id not in Radius");
			e.printStackTrace();
		}
		if (subscriberId!=null && !subscriberId.isBlank()) {
			String subscriberUrl = bossURLInstanceId;
			RestTemplate restTemplate = new RestTemplate();
			HttpHeaders bossOAuthHeader = new HttpHeaders();
			bossOAuthHeader.setContentType(MediaType.APPLICATION_JSON);
			bossOAuthHeader.setBasicAuth(username, password);
			FetchBossReq fetchBossReq = new FetchBossReq();
			fetchBossReq.setInstanceId(subscriberId); // enable after test
			//fetchBossReq.setInstanceId("0000037365"); // 0000039109,0000037365
			// fetchBossReq.setInstanceId("1000001109"); 1000000615
			String json = gson.toJson(fetchBossReq); // convert
			log.info("json for fetch boss request " + "subscriberId : " + subscriberId + " json : " + json);
			HttpEntity<?> request = new HttpEntity<>(json, bossOAuthHeader);
			try {
				SnowResponse = restTemplate.exchange(subscriberUrl, HttpMethod.POST, request, String.class,
						subscriberId);
				log.info("SnowResponse " + SnowResponse);
			} catch (Exception e) {
				log.info("Failed to get BOSS response for Subscriber Id: {} " + subscriberId + " " + e.getMessage());
				e.printStackTrace();
				response.setSubscriberId(subscriberId);
				response.setMessage("Failed to get BOSS response from ServiceNow for Subscriber Id " +subscriberId);
				return response;
			}
			if (SnowResponse != null) {
				log.info("SnowResponse is not null. SnowResponse body : " + SnowResponse.getBody());
				String body = SnowResponse.getBody();
				JsonObject o = (JsonObject) new JsonParser().parse(body);

				JsonObject result = o.getAsJsonObject("result");
				Response responseUnlock;
				boolean status = result.get("radius").getAsBoolean();
				if(StringUtils.isEmpty(retryCount) )
				{
					retryCount = result.get("retryCount").getAsString();
				}
				String ban = result.get("ban").getAsString();
				log.info("subscriber Id " + ban + "--" + "retrycount " + retryCount);
				if (!status) {
					log.info("not migrated to Radius");
					response.setSubscriberId(subscriberId);
					response.setMessage("Subscriber not migrated to Radius");
				} else if (status) {
					if (retryCount.equalsIgnoreCase("0")) {
						retryCount = "Suspend_NP_1";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_NP_1")) {
						retryCount = "Suspend_NP_2";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_NP_2")) {
						retryCount = "Suspend_NP_3";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_NP_3")) {
						retryCount = "Suspend_NP_LOCKOUT";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("LOCKOUT")) {
						log.info("last try lockout");
						response.setMessage("No more tries left LOCKOUT");
					} else if (retryCount.contains("Suspend_VAC_1")) {
						retryCount = "Suspend_VAC_2";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_VAC_2")) {
						retryCount = "Suspend_VAC_3";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_VAC_3")) {
						retryCount = "Suspend_VAC_LOCKOUT";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_BSPD_NOW_1")) {
						retryCount = "Suspend_BSPD_NOW_2";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_BSPD_NOW_2")) {
						retryCount = "Suspend_BSPD_NOW_3";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					} else if (retryCount.contains("Suspend_BSPD_NOW_3")) {
						retryCount = "Suspend_BSPD_NOW_LOCKOUT";
						responseUnlock = unblockInternet(subscriberId, ban, retryCount);
						log.info("Internet enabled retry count to " + retryCount);
						response.setServiceStatus(responseUnlock.getServiceStatus());
						response.setServiceProfileSuspended(null);
					}

					log.info("ban migrated to radius status " + ban + "--" + status);
					response.setMessage(retryCount);
					response.setSubscriberId(subscriberId);
					log.info("ban status " + ban + "--" + status + "--" + response);
				}
			}
		} else {
			log.info("Subscriber Id not found in Radius for IPAddress " + IPAddress);
			response.setSubscriberId(NULL);
			response.setMessage("Failed to find Subscriber Id not in Radius");
		}
        log.info("Returning final response for IP {}: {}"+ IPAddress + " Response : "+  response);
		return response;
	}

	@Override
	public Response unblockInternet(String subscriberId, String ban, String suspendStatus) {
		log.info("unblockInternet "+subscriberId);
        log.info("unblockInternet() called with subscriberId: " +subscriberId + ", ban: " +ban
                + ", suspendStatus: " + suspendStatus);
		ResponseEntity<String> radiusResponse = null;
		ResponseEntity<String> SnowResponse = null;
		Response response = new Response();
		KafkaPayload kafkaPayload = new KafkaPayload();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
		RestTemplate restTemplate = new RestTemplate(factory);
		HttpHeaders bossOAuthHeader = new HttpHeaders();
		String token = oAuthTokenService.getToken();
		HttpHeaders radiusOAuthHeader = ApplicationUtils.createOAuthTokenHeader(token);

		String radiusUrl = unblock + subscriberId;
		RadiusPatchRequest radiusRequest = new RadiusPatchRequest();
		radiusRequest.setServiceStatus("enabled");
		radiusRequest.setServiceProfileSuspended(null);
		// Radius call for enable 2295554462
		try {
            log.info("Sending PATCH request to Radius URL: {}, payload: {}"+  radiusUrl +  radiusRequest);
			HttpEntity<RadiusPatchRequest> request = new HttpEntity<RadiusPatchRequest>(radiusRequest,
					radiusOAuthHeader);

			radiusResponse = restTemplate.exchange(radiusUrl, HttpMethod.PATCH, request, String.class, subscriberId);
			log.info("unblockInternet radius call success "+subscriberId);
		} catch (Exception e) {
			log.info("Failed to get Radius enable response for subscriber Id : " + subscriberId + ", "
					+ e.getMessage());
			e.printStackTrace();
		}
		if (radiusResponse != null) {
			log.info("radiusResponse is not null. SnowResponse body : " + radiusResponse.getBody());
			String body = radiusResponse.getBody();
			// JsonObject o = (JsonObject) new JsonParser().parse(body);
			LocalDateTime timestamp = LocalDateTime.now();
			
			kafkaPayload.setRetryCount(suspendStatus);
			kafkaPayload.setSubscriberId(subscriberId);
			kafkaPayload.setBan(ban);
			kafkaPayload.setTimestamp(timestamp);
			// put on queue subscriberId, timestamp, ban
			uploadSubscriber(kafkaPayload, subscriberId);
			// update ServiceNow
			BossPatchRequest SNRequest = new BossPatchRequest();
			SNRequest.setBan(ban);
			SNRequest.setRetryCount(suspendStatus);
			SNRequest.setTimestamp(timestamp);
			bossOAuthHeader.setContentType(MediaType.APPLICATION_JSON);
			bossOAuthHeader.setBasicAuth(username, password);
			// boss call to service now with status integer
			try {
				log.info("Boss Patch Request : BAN:  " + SNRequest.getBan() +", RetryCount: "+ SNRequest.getRetryCount()+", TimeStamp: "+SNRequest.getTimestamp());
				HttpEntity<BossPatchRequest> request1 = new HttpEntity<BossPatchRequest>(SNRequest, bossOAuthHeader);
				SnowResponse = restTemplate.exchange(bossURLUpdate, HttpMethod.PATCH, request1, String.class, ban);
			} catch (Exception e) {
				log.info("Failed to get BOSS OM update for subscriber Id : " + subscriberId + " " + e.getMessage());
				e.printStackTrace();
			}
			response.setSubscriberId(subscriberId);
			response.setMessage("Internet enabled retry count in Boss OM " + suspendStatus);
			response.setServiceStatus("enabled");
			response.setServiceProfileSuspended(null);
			log.info("unblockInternet! "+subscriberId);
			log.info("unblockInternet! "+response);
		} else {
			log.info("Failed to enable in radius ");
			response.setSubscriberId(subscriberId);
			response.setMessage("Failed to enable in radius ");
			response.setServiceStatus("disabled");
			response.setServiceProfileSuspended(null);
			return response;
		}
        log.info("Returning unblockInternet() response for subscriberId : " + subscriberId + ", " + response);
		return response;
	}

	@Override
	public Response unblockInternet24(String subscriberId, String ban, String retryCount) {
		log.info("unblockInternet24!"+subscriberId);
        log.info("unblockInternet24() called with subscriberId: " + subscriberId +  ", ban: "+ban+", retryCount: " + retryCount);
		ResponseEntity<String> SnowResponse = null;
		ResponseEntity<String> radiusResponse = null;
		Response response = new Response();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
		RestTemplate restTemplate = new RestTemplate(factory);
		
		HttpHeaders bossOAuthHeader = new HttpHeaders();
		bossOAuthHeader.setContentType(MediaType.APPLICATION_JSON);
		bossOAuthHeader.setBasicAuth(username, password);
		try {
			String token = oAuthTokenService.getToken();
			HttpHeaders radiusOAuthHeader = ApplicationUtils.createOAuthTokenHeader(token);

			String radiusUrl = unblock + subscriberId;
			RadiusPatchRequest radiusRequest = new RadiusPatchRequest();
			BossPatchRequest SNRequest = new BossPatchRequest();
			HttpEntity<BossPatchRequest> requestPay = new HttpEntity<BossPatchRequest>(SNRequest, bossOAuthHeader);
			SNRequest.setBan(ban);
			SNRequest.setSubscriberId(subscriberId);
			String paymentResponse = "";
			log.info("ban "+ban);
			// SNRequest.setBan("1000000615");//nonpayment BAN
			SnowResponse = restTemplate.exchange(bossURLPayment, HttpMethod.POST, requestPay, String.class,
					ban);
			if (!SnowResponse.getBody().isEmpty()) {
				JsonObject o = (JsonObject) new JsonParser().parse(SnowResponse.getBody());
				JsonObject result = o.getAsJsonObject("result");

				paymentResponse = result.get("paymentResponse").getAsString();
				log.info("paymentResponse "+paymentResponse+" subscriberId " +subscriberId );
			}
			if (!paymentResponse.equalsIgnoreCase("success")) {
				log.info("No payment for ban" + ban);
				if (retryCount.contains("NP")) {
					SNRequest.setRetryCount("Suspend_NP_1");
					radiusRequest.setServiceProfileSuspended("Suspend_NP_1");
				}
				if (retryCount.contains("VAC")) {
					SNRequest.setRetryCount("Suspend_VAC_1");
					radiusRequest.setServiceProfileSuspended("Suspend_VAC_1");
				}
				if (retryCount.contains("BSPD")) {
					SNRequest.setRetryCount("Suspend_BSPD_NOW_1");
					radiusRequest.setServiceProfileSuspended("Suspend_BSPD_NOW_1");
				}
				radiusRequest.setServiceStatus("suspended");
			} else if (paymentResponse.equalsIgnoreCase("success")) {
				radiusRequest.setServiceStatus("enabled");
				radiusRequest.setServiceProfileSuspended(null);
			}
					 //Radius call for enable 2295554462
			try {
				HttpEntity<RadiusPatchRequest> request = new HttpEntity<RadiusPatchRequest>(radiusRequest,
						radiusOAuthHeader);

				radiusResponse = restTemplate.exchange(radiusUrl, HttpMethod.PATCH, request, String.class, subscriberId);
				log.info("unblockInternet radius call success "+subscriberId);
			} catch (Exception e) {
				log.info("Failed to get Radius enable response for subscriber Id : {} " + subscriberId + " "
						+ e.getMessage());
				e.printStackTrace();
			}
			
			// boss call service now status integer
			HttpEntity<BossPatchRequest> request1 = new HttpEntity<BossPatchRequest>(SNRequest, bossOAuthHeader);
			SnowResponse = restTemplate.exchange(bossURLUpdate, HttpMethod.PATCH, request1, String.class, ban);
			String body1 = SnowResponse.getBody();
			response.setSubscriberId(subscriberId);
			response.setMessage("Boss OM enabled afer 24");
			//response.setServiceStatus("enabled");
			response.setServiceProfileSuspended("Boss OM enabled after 24");
				
			log.info("unblockInternet24! "+subscriberId);
			
			//response.setMessage("Boss OM enabled afer 24");
		} catch (RestClientException e) {
			log.info("Rest Access Exception in serviceNow enable: {} " + e.getMessage());
			e.printStackTrace();
			return response;
		}
        log.info("Returning unblockInternet24() response for subscriberId : " + subscriberId + " Response: " + response);
		return response;
	}
	

	@Override
	public Response blockInternet(String subscriberId, String ban, String suspendStatus) {
		log.info("block Internet call "+subscriberId);
        log.info("blockInternet() called with subscriberId: {}, ban: {}, suspendStatus: {}" +
                 " " + subscriberId + " " + ban + " " +  suspendStatus);

        ResponseEntity<String> backendResponse = null;
		ResponseEntity<String> SnowResponse = null;
		Response response = new Response();
		String retryCount = "";
		// KafkaPayload kafkaPayload = new KafkaPayload();
		HttpHeaders bossOAuthHeader = new HttpHeaders();
		bossOAuthHeader.setContentType(MediaType.APPLICATION_JSON);
		bossOAuthHeader.setBasicAuth(username, password);
		BossPatchRequest SNRequest = new BossPatchRequest();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
		RestTemplate restTemplate = new RestTemplate(factory);
		HttpEntity<BossPatchRequest> requestPay = new HttpEntity<BossPatchRequest>(SNRequest, bossOAuthHeader);
		String paymentResponse = "";
		try {
			SNRequest.setBan(ban);
			SNRequest.setSubscriberId(subscriberId);
			log.info("ban "+ban);
			// SNRequest.setBan("1000000615");//nonpayment BAN
			SnowResponse = restTemplate.exchange(bossURLPayment, HttpMethod.POST, requestPay, String.class,
					ban);
			if (!SnowResponse.getBody().isEmpty()) {
				JsonObject o = (JsonObject) new JsonParser().parse(SnowResponse.getBody());
				JsonObject result = o.getAsJsonObject("result");

				paymentResponse = result.get("paymentResponse").getAsString();
				log.info("paymentResponse "+paymentResponse+" subscriberId " +subscriberId );
			}
			response.setSubscriberId(subscriberId);

		} catch (Exception e) {
			log.info("Rest Access Exception in serviceNow payment check: {} " + e.getMessage());
		}
		if (!paymentResponse.equalsIgnoreCase("success")) {
			try {
				// check if payment received
				String token = oAuthTokenService.getToken();
				HttpHeaders raduisOAuthHeader = ApplicationUtils.createOAuthTokenHeader(token);

				String radiusUrl = unblock + subscriberId;
				RadiusPatchRequest PRequest = new RadiusPatchRequest();

				PRequest.setServiceStatus("suspended");
				PRequest.setServiceProfileSuspended(suspendStatus);

				HttpEntity<RadiusPatchRequest> request = new HttpEntity<RadiusPatchRequest>(PRequest,
						raduisOAuthHeader);

				backendResponse = restTemplate.exchange(radiusUrl, HttpMethod.PATCH, request, String.class,
						subscriberId);
				log.info("Payment not received blocked!"+subscriberId);
				response.setSubscriberId(subscriberId);
				response.setMessage("Payment not received blocked!");
			} catch (RestClientException e) {
				// todo create ServiceNow case
				log.info("Failed to get radius disable  response: {} " + e.getMessage());
				e.printStackTrace();
				return response;
			}
		} else if (paymentResponse.equalsIgnoreCase("success")) {
			suspendStatus = "0"; // reset status to 0 in BOSS OM
			log.info("Payment received thank you! "+subscriberId);
			response.setSubscriberId(subscriberId);
			response.setMessage("Payment received Thank you!");
		}
		if (response != null) {
			SNRequest.setBan(ban);
			SNRequest.setRetryCount(suspendStatus);
			HttpEntity<BossPatchRequest> requestUpdate = new HttpEntity<BossPatchRequest>(SNRequest, bossOAuthHeader);
			try {
				SnowResponse = restTemplate.exchange(bossURLUpdate, HttpMethod.PATCH, requestUpdate, String.class, ban);
				log.info("update Boss OM disable status "+subscriberId);
                log.info("ServiceNow update response for ban {}: statusCode={}, body={}" +
                        ban+  " " +SnowResponse.getStatusCode() + " " +  SnowResponse.getBody());
			} catch (RestClientException e) {
				log.info("Failed to update Boss OM disable status " + e.getMessage());
				e.printStackTrace();
				return response;
			}
			String body1 = SnowResponse.getBody();
			response.setSubscriberId(subscriberId);

		} else {
			log.info("Failed to get radius disable  response");
            log.info("Response object is null after Radius call for subscriberId {}" + subscriberId);
			return response;
		}
        log.info("Returning blockInternet() response for subscriberId {}: {}"   + subscriberId  + response);
		return response;
	}

	private void uploadSubscriber(KafkaPayload kafkaPayload, String addressId) {
		kafkaPublisher.sendKafkaObject(kafkaPayload);
	}

}
