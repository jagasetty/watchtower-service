package com.brightspeed.inventoryapiservice.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.brightspeed.inventoryapiservice.dto.request.XConnectRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.brightspeed.inventoryapiservice.dto.request.DesignImpactField;
import com.brightspeed.inventoryapiservice.dto.request.ValidationField;
import com.brightspeed.inventoryapiservice.dto.response.ImpactCheckResponse;
import com.brightspeed.inventoryapiservice.dto.response.ResponseStatus;
import com.brightspeed.inventoryapiservice.exception.ErrorCode;
import com.brightspeed.inventoryapiservice.exception.ValidationException;
import com.brightspeed.inventoryapiservice.service.ConfigService;
import com.brightspeed.inventoryapiservice.service.EthernetProvisionerService;
import com.brightspeed.inventoryapiservice.util.Constants;
import com.brightspeed.inventoryapiservice.util.Validator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for handling Ethernet provisioning and validation requests
 * in the USIL system. This controller provides APIs for assigning UNI
 * inventory and validating EVC orders.
 *
 * <p>It integrates with {@link EthernetProvisionerService} to process
 * inventory requests, performs validations using {@link Validator},
 * and responds with standardized {@link ResponseStatus} objects.</p>
 *
 * <p>All responses are wrapped in {@link ResponseEntity} for proper
 * HTTP status handling.</p>
 *
 * @author Brightspeed
 */
@CrossOrigin(maxAge = 1800)
@Slf4j
@RestController
@Api(value = "Ethernet Order Controller for USIL system providing Ethernet inventory services",
     tags = "ethernet-order-controller")
public class EthernetProvisionerController {

    
    @Autowired
    private EthernetProvisionerService ethProvSrvc;

    @Autowired
	private ConfigService configSrvc;

	@Autowired
    private Validator validator;

    @Autowired
    private ObjectMapper objectMapper;

   
    /**
     * Assigns a UNI (User Network Interface) inventory to a request payload.
     * <p>
     * Steps:
     * <ul>
     *   <li>Validates the input request payload.</li>
     *   <li>Extracts product details from the request.</li>
     *   <li>Builds a {@code UNIConnectionDTO} object from the payload.</li>
     *   <li>Processes product order items and assigns inventory.</li>
     * </ul>
     * </p>
     *
     * @param assignedInventoryBody request payload containing product and order details
     * @return a {@link ResponseEntity} containing {@link ResponseStatus}
     *         with assigned inventory details if successful,
     *         or error response in case of validation/processing failure.
     */
    @PostMapping("/assignUNI")
    public ResponseEntity<?> assignInventory(@RequestBody Map<String, Object> assignedInventoryBody) {
        try {
            validator.uniAssignValidate(assignedInventoryBody);
            log.info("Received assignInventory request: {}", assignedInventoryBody);

            Map<String, Object> productPayload = ethProvSrvc.extractProductPayload(assignedInventoryBody);
            JsonNode payloadNode = objectMapper.convertValue(assignedInventoryBody, JsonNode.class);
            com.brightspeed.inventoryapiservice.dto.request.UNIConnectionDTO ucDTO = ethProvSrvc.buildDTOFromPayload(payloadNode);

            ResponseStatus result = ethProvSrvc.processProductOrderItems(productPayload, ucDTO);

            return ResponseEntity.ok(result);

        }  catch (Exception e) {
            log.error(Constants.ERROR_ASSIGNING_INVENTORY, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                                 .body( new ResponseStatus(
                                         Integer.parseInt(ErrorCode.INTERNAL_SERVER_ERROR.getCode()),
                                         ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
                                         e.getMessage()));
        }
    }


    /**
     * Validates an EVC (Ethernet Virtual Connection) order request for the USIL system.
     * <p>
     * Steps:
     * <ul>
     *   <li>Delegates validation logic to {@link EthernetProvisionerService}.</li>
     *   <li>Returns HTTP 1005 (Bandwidth Not Available) if validation fails with details.</li>
     *   <li>Returns HTTP 200 (OK) if validation succeeds.</li>
     *   <li>Returns HTTP 500 (Internal Server Error) in case of unexpected errors.</li>
     * </ul>
     * </p>
     *
     * @param payload request payload containing EVC order details
     * @return a {@link ResponseEntity} containing {@link ResponseStatus}
     *         with validation result and messages.
     */
    @PostMapping("/evc/validate")
    public ResponseEntity<?> validateEVCOrder(@RequestBody Map<String, Object> payload) {
       try {
    	   Map<String, Object> result = ethProvSrvc.validateEVCOrderRequest(payload);
    	   boolean isValid = (boolean) result.getOrDefault(Constants.VALID, false);

    	    if (!isValid) {
    	        // Return 1005 Bandwidth Not Available with errors
    	        return ResponseEntity.badRequest().body(
    	                new ResponseStatus(
    	                        Integer.parseInt(ErrorCode.BANDNWIDTH_NOT_AVAILABLE.getCode()),
    	                        ErrorCode.BANDNWIDTH_NOT_AVAILABLE.getMessage(),
    	                        result
    	                )
    	        );
    	    }

    	    // If valid → return success
    	    return ResponseEntity.ok(
    	            new ResponseStatus(
    	                    Integer.parseInt(ErrorCode.SUCCESS.getCode()), 
    	                    ErrorCode.SUCCESS.getMessage(), 
    	                    result
    	            )
    	    );

    	} catch (Exception e) {
    	    return ResponseEntity.internalServerError()
    	                         .body(new ResponseStatus(
    	                                 Integer.parseInt(ErrorCode.INTERNAL_SERVER_ERROR.getCode()),
    	                                 ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
    	                                 null));
    	}
}
    /**
     * Fetch Available bandwidth of given NNI based on occupied bandwidth by EVCs
     * @param payload
     * @return responseStatus
     */
    @PostMapping(value = "/v1/checkNNIBandwidth")
    public ResponseEntity<ResponseStatus> checkNNIBandwidth(@RequestBody Map<String, Object> payload) {
        log.info("=> checkNNIBandwidth(): START");
        log.debug("Received payload: '{}'", payload);
        try {
            ResponseStatus response = ethProvSrvc.checkNNIBandwidth(payload);
            if (response.getCode() != 500) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        } catch (RuntimeException ex) {
            String errorMsg = "Error occurred while calculation of NNI bandwidth";
            log.error("{}: {}", errorMsg, ex.getMessage(), ex);
            ResponseStatus status = new ResponseStatus();
            status.setCode(500);
            status.setMessage(errorMsg + ": " + ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(status);
        }
    }

    /**
     * Validates the UNIConnection request payload against required and 
     * design-impacting fields.
     *
     * <p>This endpoint performs the following steps:</p>
     * <ol>
     *   <li>Retrieves the list of required fields from configuration and validates
     *       their presence in the request payload.</li>
     *   <li>Fetches design-impacting fields from configuration.</li>
     *   <li>Evaluates design and network impact by comparing the payload with 
     *       existing UNIConnection data in Neo4j.</li>
     *   <li>Returns the validation result, including impact flags or success status.</li>
     * </ol>
     *
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>If required fields are missing, returns {@code HTTP 400 Bad Request} with
     *       {@link ResponseStatus} containing validation failure details.</li>
     *   <li>If an unexpected error occurs, returns {@code HTTP 500 Internal Server Error} 
     *       with {@link ResponseStatus} describing the failure.</li>
     * </ul>
     *
     * @param payload the request body containing UNIConnection details, including
     *                {@code circuitId} and {@code productPayload}
     * @return a {@link ResponseEntity} containing:
     *         <ul>
     *           <li>{@link ImpactCheckResponse} with validation flags if successful (HTTP 200 OK).</li>
     *           <li>{@link ResponseStatus} with error information if validation or processing fails.</li>
     *         </ul>
     *
     * @see ValidationField
     * @see DesignImpactField
     * @see ImpactCheckResponse
     * @see ResponseStatus
     * @see Validator#validateRequiredFields(Map, List)
     */
	@PostMapping("/validateUNIConnection")
	public ResponseEntity<?> validateUNIConnection(@RequestBody Map<String, Object> payload) {
		try {
			// 1. Validate required fields
			List<ValidationField> requiredFields = configSrvc.getValidationFields();
			Validator.validateRequiredFields(payload, requiredFields);

			// 2. Get design impacting fields
			List<DesignImpactField> designImpactFields = configSrvc.getDesignImpactFields();

			// 3. Check design and network impact
			ImpactCheckResponse impactResult = ethProvSrvc.checkUNIDesignAndNetworkImpact(payload, designImpactFields);

			// 4. Return result
			return ResponseEntity.ok(impactResult);

		} catch (ValidationException ve) {
			 return ResponseEntity.badRequest().body(
 	                new ResponseStatus(
 	                        Integer.parseInt(ErrorCode.INV_UNI_CONNECTION_VALIDATION_FAILED.getCode()),
 	                        ErrorCode.INV_UNI_CONNECTION_VALIDATION_FAILED.getMessage(),
 	                       ve.getMessage()));
		} catch (Exception e) {
			// Unexpected errors
			return ResponseEntity.internalServerError()
                    .body(new ResponseStatus(
                            Integer.parseInt(ErrorCode.INTERNAL_SERVER_ERROR.getCode()),
                            ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
                            e.getMessage()));
		}
	}

	
    /**
     * REST endpoint to reload configuration fields.
     * <p>
     * This method invokes {@link com.example.config.service.ConfigService#loadConfigs()}
     * to reload both validation fields and design-impact fields from the data source.
     * It is typically used when configuration values need to be refreshed without
     * restarting the application.
     * </p>
     *
     * <p>
     * The method provides structured logging at entry, on success, and on error.
     * In case of failure, the exception is logged and an HTTP 500 response is returned.
     * </p>
     *
     * <h3>API Details:</h3>
     * <ul>
     *     <li><b>HTTP Method:</b> GET</li>
     *     <li><b>Endpoint:</b> /api/configs/load</li>
     *     <li><b>Success Response:</b> HTTP 200 with message
     *     "Configuration fields successfully loaded."</li>
     *     <li><b>Error Response:</b> HTTP 500 with message
     *     "Failed to load configuration fields: &lt;error-message&gt;"</li>
     * </ul>
     *
     * @return {@link ResponseEntity} containing either a success or error message,
     * depending on the outcome of the configuration reload operation.
     */
    @GetMapping("/loadConfigs")
    public ResponseEntity<ResponseStatus> loadConfigs() {
        log.info("Received request to reload configuration fields");
        try {
            configSrvc.loadConfigs();
            log.info("Configuration fields successfully reloaded");
            return ResponseEntity.ok(
                    new ResponseStatus(
                            Integer.parseInt(ErrorCode.SUCCESS_CONFIG_LOADED.getCode()),
                            ErrorCode.SUCCESS_CONFIG_LOADED.getMessage(),
                            null
                    )
            );
        } catch (Exception ex) {
            log.error("Error occurred while loading configuration fields", ex);
            return ResponseEntity.status(500).body(
                    new ResponseStatus(
                            Integer.parseInt(ErrorCode.ERROR_CONFIG_LOAD_FAILED.getCode()),
                            ErrorCode.ERROR_CONFIG_LOAD_FAILED.getMessage(),
                            ex.getMessage()
                    )
            );
        }
    }

    /**
     * Assigns an Ethernet Virtual Connection (EVC) based on the provided request payload.
     * <p>
     * This endpoint accepts a JSON payload representing the details required for EVC assignment
     * and delegates the processing to the {@code ethProvSrvc.assignEvc()} service method.
     * The response status is determined based on the execution result:
     * <ul>
     *     <li>Returns {@code 200 OK} if the EVC assignment is successful.</li>
     *     <li>Returns {@code 400 Bad Request} if the service fails with a known error.</li>
     *     <li>Returns {@code 500 Internal Server Error} if an unexpected exception occurs.</li>
     * </ul>
     *
     * @param payload the request body containing EVC assignment details, provided as a key-value map
     * @return a {@link ResponseEntity} containing a standardized {@link ResponseStatus} object
     *         indicating success, validation error, or server error
     */
    @PostMapping("/assignEVC")
    public ResponseEntity<?> assignEVC(@RequestBody Map<String, Object> payload) {
        try {
            ResponseStatus result = ethProvSrvc.assignEVC(payload);
            if (result.getCode().equals(Integer.parseInt(ErrorCode.SUCCESS.getCode()))) {
                return ResponseEntity.ok(ethProvSrvc.buildResponseStatus(ErrorCode.SUCCESS, result.getData()));
            }
            return ResponseEntity.badRequest().body(ethProvSrvc.buildResponseStatus(ErrorCode.INTERNAL_SERVER_ERROR, result.getData()));
        } catch (Exception e) {
            log.error(Constants.ERROR_ASSIGNING_INVENTORY, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(ethProvSrvc.buildResponseStatus(ErrorCode.INTERNAL_SERVER_ERROR, null));
        }
    }
    
    /**
     * Unassigns and deletes an EVC connection by its alias circuit ID
     *
     * @param evcAliasCktId The alias circuit ID of the EVC to unassign
     * @return ResponseEntity indicating success or failure
     */
    @DeleteMapping("/evc/{evcAliasCktId}")
    public ResponseEntity<?> unassignEVCConnection(@PathVariable String evcAliasCktId) {
        log.info("Received request to unassign EVC connection: {}", evcAliasCktId);
        
        try {
            boolean wasDeleted = ethProvSrvc.unassignEVCConnection(evcAliasCktId,null);
            
            if (wasDeleted) {
                return ResponseEntity.ok().body(ethProvSrvc.buildResponseStatus(ErrorCode.INV_EVC_CONNECTION_UNASSIGNED_DELETED_SUCCESS, evcAliasCktId)
                );
            } else {
                return ResponseEntity.ok().body(ethProvSrvc.buildResponseStatus(ErrorCode.INV_NO_EVC_CONNECTION_FOUND_EXCEPTION, evcAliasCktId)
                        );
            }
            
        } catch (Exception e) {
            log.error("Error unassigning EVC connection: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(ethProvSrvc.buildResponseStatus(ErrorCode.INV_FAILED_TO_UNASSIGN_EVC_CONNECTION_EXCEPTION, evcAliasCktId)
                    );
        }
    }

    @PostMapping("/xconnect")
    public ResponseEntity<Map<String, Object>> createXConnect(@RequestBody Map<String, String> request) {
        String uniName = request.get("uniName");
        String nmiName = request.get("nmiName");

        if (uniName == null || nmiName == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "uniName and nmiName are required parameters"
            ));
        }

        Map<String, Object> serviceResponse = ethProvSrvc.createXConnect(uniName, nmiName);
        String status = (String) serviceResponse.get("status");

        if ("success".equals(status)) {
            return ResponseEntity.ok(serviceResponse);
        } else {
            return ResponseEntity.badRequest().body(serviceResponse);
        }
    }

}
