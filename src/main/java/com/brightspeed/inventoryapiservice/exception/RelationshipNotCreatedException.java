package com.brightspeed.inventoryapiservice.exception;

/**
 * Custom runtime exception used to indicate failure in creating a relationship
 * between entities during processing of requests in the inventory API service.
 * <p>
 * This exception should be thrown when an attempt to establish a required
 * relationship between domain entities (e.g., linking a circuit to a location)
 * fails due to validation issues, system errors, or business rule violations.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * if (!relationshipService.createLink(circuit, location)) {
 *     throw new RelationshipNotCreatedException("Failed to create relationship between circuit and location.");
 * }
 * }</pre>
 *
 * @author Brightspeed
 * @since 1.0
 */
public class RelationshipNotCreatedException extends RuntimeException {

    /**
     * Constructs a new {@code RelationshipNotCreatedException} with the specified detail message.
     *
     * @param message the detail message describing why the relationship could not be created
     */
    public RelationshipNotCreatedException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code RelationshipNotCreatedException} with the specified detail message
     * and cause.
     *
     * @param message the detail message describing the failure
     * @param cause   the underlying cause of the failure (may be {@code null})
     */
    public RelationshipNotCreatedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the detail message string of this exception.
     *
     * @return the detail message string
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }

    /**
     * Returns a string representation of this exception.
     *
     * @return a string representation of this {@code RelationshipNotCreatedException}
     */
    @Override
    public String toString() {
        return "RelationshipNotCreatedException: " + getMessage();
    }
}

