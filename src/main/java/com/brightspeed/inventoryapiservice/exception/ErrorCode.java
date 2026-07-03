package com.brightspeed.inventoryapiservice.exception;

/**
 * Enum representing application-specific error codes and their associated messages.
 * <p>
 * This enum centralizes error codes for consistent error handling across the system.
 * Each error code has a unique identifier (code) and a descriptive message that can
 * be used in responses, logs, or exceptions.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * throw new InventoryException(ErrorCode.UNI_PORT_NOT_FOUND);
 * }</pre>
 */
public enum ErrorCode {

    /** Error code when no NID is found for the given location. */
    NID_NOT_FOUND("1001", "No NID found for given location"),

    /** Error code when no UNI ports are available on the device. */
    UNI_PORT_NOT_FOUND("1002", "No UNI ports available on device"),

    /** Error code when no NMI ports are available on the device. */
    NMI_PORT_NOT_FOUND("1003", "No NMI ports available on device"),

    /** Error code when the request payload is invalid. */
    INVALID_REQUEST("1004", "Request payload is invalid"),

    /** Error code when requested bandwidth is not available. */
    BANDNWIDTH_NOT_AVAILABLE("1005", "Bandwidth Not Available"),

    /** Success response code. */
    SUCCESS("0000", "Success"),

    /** Error code when an NNI not found. */
    INV_NNI_NOT_FOUND("1006", "NNI not found"),

    /** Error code when a circuit Id not found. */
    INV_CIRCUIT_ID_NOT_FOUND("1007", "Circuit Id is either null or empty"),

    /** Error code when an requested bandwidth not found. */
    INV_REQUESTED_BANDWIDTH_NOT_FOUND("1008", "Requested Bandwidth is either null or empty"),

    /** Error code when a request payload empty. */
    INV_REQUEST_PAYLOAD_EMPTY("1009", "Request Payload is either null or empty"),

    /** Error code when an unexpected server error occurs. */
    INTERNAL_SERVER_ERROR("500", "Unexpected server error"),

    /**Error when the A-End TID value is null or empty.*/
    INV_AEND_TID_VALUE_IS_EMPTY("1010", "aEndTIDValue is either null or empty"),

    /** Error when the Z-End TID value is null or empty.*/
    INV_ZEND_TID_VALUE_IS_EMPTY("1011", "zEndTIDValue is either null or empty"),

    /**Error indicating failure to create the Route node.*/
    INV_FAILED_TO_CREATE_ROUTE("1012", "Failed to create Route node"),
	
	/**Error indicating Validation failure of UniConnection.*/
	INV_UNI_CONNECTION_VALIDATION_FAILED("1013", "Valdiation Failed for UNI Connection"),
	
	
	    /**Error indicating failure of Creation of Route to NNI Relationship.*/
    INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP("1014", "Failed to create Route to NNI relationship"),
	
	 /** Success response for loading configuration fields */
    SUCCESS_CONFIG_LOADED("1015", "Configuration fields successfully loaded."),
    
    /** Generic error for configuration load failures */
    ERROR_CONFIG_LOAD_FAILED("1016", "Failed to load configuration fields"),

    /**Error indicating failure to create the Route Exception.*/
    INV_FAILED_TO_CREATE_ROUTE_EXCEPTION("1017", "Failed to create Route Exception"),

    /**Error indicating failure of Creation of Route to NNI Relationship Exception.*/
    INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP_EXCEPTION("1018", "Failed to create Route to NNI relationship Exception"),
	

	/** Error indicating no EVC connection found with the specified aliasCktId. */
	INV_NO_EVC_CONNECTION_FOUND_EXCEPTION("1019", "No EVC connection found with the specified aliasCktId"),

	/** Message indicating EVC connection unassigned and deleted successfully. */
	INV_EVC_CONNECTION_UNASSIGNED_DELETED_SUCCESS("1020", "EVC connection unassigned and deleted successfully"),

	/** Error indicating failure while unassigning EVC connection. */
	INV_FAILED_TO_UNASSIGN_EVC_CONNECTION_EXCEPTION("1021", "Failed to unassign EVC connection"),
	
	/** 
	 * Error indicating that TIDs could not be found.
	 */
	INV_MISSING_TIDS_FOR_CIRCUITS_EXCEPTION("1022", "Missing TIDs for one or both circuits"),

	/** 
	 * Error indicating that the provided UNI list is invalid. 
	 * At least two UNIs are required to perform EVC unassignment. 
	 */
	INV_INVALID_UNI_LIST_EXCEPTION("1023", "Invalid UNI list. Need at least two UNIs"),
	
	/**
     * Indicates that no network routes are available for the requested operation.
     */
	INV_NO_ROUTES_AVAILABLE("1024", "No Routes availble"),

	 /**
     * Indicates that an EVC (Ethernet Virtual Connection) has been successfully assigned.
     */
	INV_EVC_ASSIGNED_SUCESSFULLY("1025","EVC assigned successfully"),
	
	/**
     * Indicates that an Error while unAssigning EVC.
     */
	INV_EVC_UN_ASSIGN_ERROR("1026","Error while unAssigning EVC"),
	
	/**
     * Indicates that an Error while attaching Route to UNI
     */
	INV_ROUTE_TO_UNI_ASSIGN_ERROR("1027","Failed To attach UNI to Route"),
	
	/**
     * Indicates that an Error, Invalid UNI List in the request
     */
	INV_INVALID_UNI_LIST("1028","Invalid UNI List in the request");

    /** The unique error code identifier. */
    private final String code;

    /** The descriptive error message. */
    private final String message;

    /**
     * Constructs an {@link ErrorCode} enum with a specific code and message.
     *
     * @param code    the unique error code identifier
     * @param message the descriptive error message
     */
    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Returns the unique error code identifier.
     *
     * @return the error code as a {@link String}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the descriptive error message.
     *
     * @return the error message as a {@link String}
     */
    public String getMessage() {
        return message;
    }
}
