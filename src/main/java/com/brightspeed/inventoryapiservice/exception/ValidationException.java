package com.brightspeed.inventoryapiservice.exception;

/**
 * Custom runtime exception used to indicate validation errors
 * that occur during processing of requests in the inventory API service.
 * <p>
 * This exception should be thrown when an input or request payload
 * fails validation checks such as missing required fields,
 * invalid data format, or business rule violations.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * if (payload.getCircuitId() == null) {
 *     throw new ValidationException("Circuit ID cannot be null");
 * }
 * }</pre>
 *
 * @author Brightspeed
 * @since 1.0
 */
public class ValidationException extends RuntimeException {

    /**
     * Constructs a new {@code ValidationException} with the specified detail message.
     *
     * @param message the detail message describing the validation error
     */
    public ValidationException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code ValidationException} with the specified detail message
     * and cause.
     *
     * @param message the detail message describing the validation error
     * @param cause   the underlying cause of the exception (may be {@code null})
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
