package com.brightspeed.inventoryapiservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a field used for design impact validation.
 * <p>
 * This DTO defines the mapping between a JSONPath expression (used to extract a field
 * from an incoming payload) and its corresponding key in a data object
 * used for impact analysis. It is primarily used to identify which fields
 * influence design-related changes in the system.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     DesignImpactField field = new DesignImpactField("$.productOrderItem[0].circuitId", "cktid");
 * </pre>
 *
 * @see com.brightspeed.inventoryapiservice.service.Validator
 * @see com.brightspeed.inventoryapiservice.dto.request.ValidationField
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DesignImpactField {

    /**
     * The JSONPath expression that points to the location of the field in the input payload.
     * <p>
     * Example: {@code $.productOrderItem[0].circuitId}
     * </p>
     */
    private String jsonPathKey;

    /**
     * The key used to store or map the extracted value in the target data object.
     * <p>
     * Example: {@code cktid}
     * </p>
     */
    private String dataObjectKey;
}
