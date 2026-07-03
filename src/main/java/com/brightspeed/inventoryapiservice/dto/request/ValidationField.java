package com.brightspeed.inventoryapiservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a validation field used for payload verification.
 * <p>
 * Each validation field holds a {@code jsonPathKey}, which defines the
 * JSONPath expression pointing to a required field within an input payload.
 * This allows dynamic validation of request data against configurable rules.
 * </p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * // Define a validation rule for "circuitId" in the payload
 * ValidationField field = new ValidationField("$.circuitId");
 *
 * // Use in validation service
 * Validator.validateRequiredFields(payload, List.of(field));
 * }</pre>
 *
 * @author Brightspeed
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidationField {

    /**
     * The JSONPath expression pointing to a required field
     * in the request payload that must be validated.
     * <p>
     * Example: {@code $.circuitId}
     * </p>
     */
    private String jsonPathKey;
}
