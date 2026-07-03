package com.brightspeed.inventoryapiservice.util;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.brightspeed.inventoryapiservice.dto.request.ValidationField;
import com.brightspeed.inventoryapiservice.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class Validator {

	private static final List<String> VALID_CIRCUIT_TYPES = List.of("EVC", "MEF EVC", "OVC", "MEF OVC", "UNI",
			"MEF UNI");
	private static final List<String> VALID_CIRCUIT_STATUSES = List.of("In Service", "Pending Disconnect", "Planned",
			"Pending Activation", "Disconnected");

	private static final ObjectMapper objectMapper = new ObjectMapper();

	public ResponseEntity<String> validate(Map<String, Object> assignedInventoryBody) {

		//String circuitType = (String) assignedInventoryBody.get("circuitType");
		String circuitName = (String) assignedInventoryBody.get("circuitName");
		String status = (String) assignedInventoryBody.get("status");

		// Validate circuitType
//		if (circuitType == null || !VALID_CIRCUIT_TYPES.contains(circuitType)) {
//			return new ResponseEntity<>(
//					"Invalid CIRCUITtype. Allowed values are: EVC, MEF EVC, OVC, MEF OVC, UNI, MEF UNI.",
//					HttpStatus.BAD_REQUEST);
//		}

		// Validate circuitName
		if (circuitName == null || circuitName.trim().isEmpty()) {
			return new ResponseEntity<>("circuitName cannot be null or empty.", HttpStatus.BAD_REQUEST);
		}

		// Validate status
		if (status == null || !VALID_CIRCUIT_STATUSES.contains(status)) {
			return new ResponseEntity<>(
					"Invalid circuitStatus. Allowed values are: 'In Service', 'Pending Disconnect', 'Planned', 'Pending Activation', 'Disconnected'.",
					HttpStatus.BAD_REQUEST);
		}

		// If all validations pass, return null indicating no error
		return null;
	}
	
	  /**
     * Validates the assigned UNI inventory request body.
     * <p>
     * This method ensures that mandatory fields such as {@code circuitId} 
     * are present and non-empty before further processing.
     *
     * @param assignedInventoryBody request payload containing inventory details
     * @return {@link ResponseEntity} with error details if validation fails,
     *         or {@code null} if validation passes and further processing should continue
     */
	public ResponseEntity<String> uniAssignValidate(Map<String, Object> assignedInventoryBody) {

		log.debug("Starting UNI assignment validation with request body: {}", assignedInventoryBody);
		String circuitId = (String) assignedInventoryBody.get("circuitId");
		
		if (circuitId == null || circuitId.trim().isEmpty()) {
			return new ResponseEntity<>("circuitId cannot be null or empty.", HttpStatus.BAD_REQUEST);
		}
		log.info("UNI assignment validation passed for circuitId: {}", circuitId);
		return null;
	}

	/**
     * Validates that all required fields defined in the given list of {@link ValidationField}
     * objects are present and non-empty in the provided request payload.
     *
     * <p>The method uses JSONPath expressions (provided via 
     * {@link ValidationField#getJsonPathKey()}) to navigate the payload and 
     * check for required values. If any field is missing, empty, or not found 
     * in the payload, a {@link ValidationException} is thrown.</p>
     *
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>If a field exists but the value is {@code null} or a blank string, 
     *       it is treated as invalid.</li>
     *   <li>If the JSONPath expression does not resolve to a value in the payload,
     *       it is treated as missing and invalid.</li>
     * </ul>
     *
     * <p><b>Logging:</b></p>
     * <ul>
     *   <li>Debug log for each JSONPath being validated.</li>
     *   <li>Info log showing resolved values for successfully validated paths.</li>
     *   <li>Error log if a path is missing or resolves to an empty value.</li>
     * </ul>
     *
     * @param payload          the request payload to validate, represented as a {@link Map}
     * @param validationFields the list of validation field definitions containing JSONPath keys
     * @throws ValidationException if any required field is missing, not found, or contains an empty value
     *
     * @see ValidationField
     * @see ValidationException
     * @see com.jayway.jsonpath.JsonPath
     */
	public static void validateRequiredFields(Map<String, Object> payload, List<ValidationField> validationFields) {

		List<String> jsonPathKeys = validationFields.stream().map(ValidationField::getJsonPathKey)
				.collect(Collectors.toList());
		Object json = objectMapper.convertValue(payload, Object.class);

		for (String path : jsonPathKeys) {
			log.debug("path  " + path);
			try {
				Object value = JsonPath.read(json, path);

				if (value == null || (value instanceof String && ((String) value).isBlank())) {
					log.error(" Missing or empty value for path: {}", path);
					throw new ValidationException("Missing or empty value for path: " + path);
				}

				log.info("Path: {} -> Value: {}", path, value);

			} catch (PathNotFoundException e) {
				log.error(" Path not found: {}", path, e);
				throw new ValidationException("Missing field at path: " + path, e);
			}
		}
	}
}