package com.brightspeed.inventoryapiservice.dto.response;

import com.brightspeed.inventoryapiservice.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * Represents a standardized response object for API operations.
 * <p>
 * This class is designed to encapsulate API responses in a consistent format
 * with three key attributes:
 * <ul>
 *     <li>{@code code} – Numeric status code representing success or failure.</li>
 *     <li>{@code message} – Descriptive message corresponding to the status.</li>
 *     <li>{@code data} – Optional payload that may contain any response data.</li>
 * </ul>
 * <p>
 * The class provides static helper methods {@link #success(Object)} and
 * {@link #failure(ErrorCode)} to simplify the creation of response objects
 * for common use cases.
 * </p>
 *
 * <p>
 * Annotations used:
 * <ul>
 *   <li>{@link EntityScan} – Marks this class for entity scanning.</li>
 *   <li>{@link Data}, {@link NoArgsConstructor}, {@link AllArgsConstructor} – Lombok annotations to generate
 *       boilerplate code (getters, setters, constructors, equals, hashCode, toString).</li>
 *   <li>{@link JsonInclude} – Ensures null fields are excluded from the JSON response.</li>
 * </ul>
 * </p>
 */
@EntityScan
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseStatus {
    /**
     * Numeric response code. 
     * Typically maps to {@link ErrorCode} values (e.g., 200 for success, 400 for validation error).
     */
    Integer code;

    /**
     * Human-readable description or message associated with the response status.
     */
    String message;

    /**
     * The actual response payload returned to the client, can be any type of object.
     * This field may be {@code null} in case of failures.
     */
    Object data;

    /**
     * Builds a success response with the given data.
     *
     * @param data the payload to be included in the response
     * @return a {@link ResponseStatus} representing a successful operation
     */
    public static ResponseStatus success(Object data) {
        return new ResponseStatus(
                Integer.valueOf(ErrorCode.SUCCESS.getCode()),
                ErrorCode.SUCCESS.getMessage(),
                data
        );
    }

    /**
     * Builds a failure response with the given error code.
     *
     * @param errorCode the {@link ErrorCode} defining the failure reason
     * @return a {@link ResponseStatus} representing a failed operation
     */
    public static ResponseStatus failure(ErrorCode errorCode) {
        return new ResponseStatus(
                Integer.valueOf(errorCode.getCode()),
                errorCode.getMessage(),
                null
        );
    }
}
