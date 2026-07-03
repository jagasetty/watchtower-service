package com.brightspeed.inventoryapiservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the Inventory API Service.
 * <p>
 * This class is annotated with {@link ControllerAdvice}, making it a centralized
 * exception handling mechanism across all controllers in the application.
 * It ensures that exceptions are consistently transformed into meaningful HTTP responses.
 * </p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles {@link ValidationException} thrown by controllers or services.
     * <p>
     * This method captures validation-related errors and returns
     * a structured error response with HTTP 400 (Bad Request).
     * </p>
     *
     * @param ex the {@link ValidationException} instance
     * @return a {@link ResponseEntity} containing the error details and HTTP status
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<?> handleValidationException(ValidationException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles generic {@link Exception} not explicitly caught elsewhere.
     * <p>
     * This catch-all handler ensures that any unexpected errors result in a proper
     * HTTP 500 (Internal Server Error) response, preventing stack traces or raw exceptions
     * from being exposed to API clients.
     * </p>
     *
     * @param ex the unhandled {@link Exception} instance
     * @return a {@link ResponseEntity} containing a generic error message and HTTP status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", "Internal server error");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handles {@link RelationshipNotCreatedException} thrown by controllers or services.
     * <p>
     * This method captures relationship-creation failures and returns
     * a structured error response with HTTP 500 (Internal Server Error).
     * </p>
     *
     * @param ex the {@link RelationshipNotCreatedException} instance
     * @return a {@link ResponseEntity} containing the error details and HTTP status
     */
    @ExceptionHandler(RelationshipNotCreatedException.class)
    public ResponseEntity<?> handleRelationshipNotCreatedException(RelationshipNotCreatedException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage());
        error.put("code",ErrorCode.INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP.getCode());
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
    
    @ExceptionHandler(EquipmentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleEquipmentNotFound(EquipmentNotFoundException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.NOT_FOUND.value());
        error.put("error", "Equipment Not Found");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }
}
