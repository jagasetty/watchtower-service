package com.brightspeed.inventoryapiservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the result of an impact check performed on a UNI connection
 * or related configuration.
 * <p>
 * This response object captures whether the provided payload or configuration
 * causes a design impact, a network impact, and includes a descriptive message
 * indicating success or failure reasons.
 * </p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * ImpactCheckResponse response = new ImpactCheckResponse("Y", "N", "Design impacted due to bandwidth change");
 * }</pre>
 *
 * <p><b>Fields:</b></p>
 * <ul>
 *   <li>{@code designImpact} — Flag indicating if design impact occurred ({@code Y} / {@code N}).</li>
 *   <li>{@code networkImpact} — Flag indicating if network impact occurred ({@code Y} / {@code N}).</li>
 *   <li>{@code message} — Descriptive message about the check result (e.g., "success" or failure reason).</li>
 * </ul>
 *
 * @author Brightspeed
 * @since 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ImpactCheckResponse {

    /**
     * Indicates whether the operation has a design impact.
     * Expected values: {@code Y} (Yes) or {@code N} (No).
     */
    private String designImpact;

    /**
     * Indicates whether the operation has a network impact.
     * Expected values: {@code Y} (Yes) or {@code N} (No).
     */
    private String networkImpact;

    /**
     * Human-readable message summarizing the impact check result.
     * Example values: {@code "success"}, {@code "Missing required field"}.
     */
    private String message;
}
