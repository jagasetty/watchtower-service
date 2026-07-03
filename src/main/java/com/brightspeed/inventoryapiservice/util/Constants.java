package com.brightspeed.inventoryapiservice.util;

import java.util.regex.Pattern;

/**
 * The {@code Constants} class holds all static constant values used across the
 * Inventory API service.
 * <p>
 * This includes product specifications, characteristic keys, error messages,
 * measurement units, connector types, route and inventory keys, regex patterns,
 * and other reusable string keys used throughout the codebase.
 * </p>
 *
 * <p>
 * <b>Usage Example:</b>
 * </p>
 * 
 * <pre>{@code
 * if (productSpec.equals(Constants.PRODUCT_SPEC_UNI)) {
 * 	// Handle UNI specific logic
 * }
 * }</pre>
 *
 * @author Brightspeed
 * @since 1.0
 */
public final class Constants {

	private Constants() {
		// Prevent instantiation
	}

	// ================= Product Specifications ================= //

	/** Product specification type for UNI (User Network Interface). */
	public static final String PRODUCT_SPEC_UNI = "UNI";

	// ================= Characteristic Keys ================= //

	/** Characteristic key for Network Channel (NC). */
	public static final String CHARACTERISTIC_NC = "NC";

	/** Characteristic key for Network Channel Interface (NCI). */
	public static final String CHARACTERISTIC_NCI = "NCI";

	/** Characteristic key for Secondary Network Channel Interface (SECNCI). */
	public static final String CHARACTERISTIC_SECNCI = "SECNCI";

	// ================= Error Messages ================= //

	/**
	 * Error message when no bandwidth/interface is found for a given Circuit ID.
	 */
	public static final String ERROR_NO_BW_INTERFACE = "No bandwidth/interface found for Circuit ID";

	/**
	 * Error message when inventory assignment fails (uses {} for SLF4J-style
	 * formatting).
	 */
	public static final String ERROR_ASSIGNING_INVENTORY = "Error assigning inventory: {}";

	/** Generic error message returned in API responses. */
	public static final String ERROR_RESPONSE_MESSAGE = "Failed to process inventory assignment";

	/** Error message indicating that TIDs are missing for one or both circuits. */
	public static final String TID_NOT_AVAILABLE = "Missing TIDs for one or both circuits";

	/**
	 * Error message indicating that the provided UNI list is invalid.
	 * <p>
	 * At least two UNIs are required.
	 * </p>
	 */
	public static final String INVALID_UNI_LIST = "Invalid UNI list. Need at least two UNIs";

	// ================= Status & Generic Keys ================= //

	/** Constant value for representing a 'valid' status. */
	public static final String VALID = "valid";

	/** Constant value for representing a 'status' */
	public static final String STATUS = "status";

	/** Constant value for representing a 'bundling' */
	public static final String BUNDLING = "bundling";

	/** Constant value for representing a 'allTo1Bundling' */
	public static final String ALL_TO_1_BUNDLING = "allTo1Bundling";

	/** Constant value for representing a 'requestingAffiliate' */
	public static final String REQUESTING_AFFILIATE = "requestingAffiliate";

	/** Constant value for representing a 'createDate' */
	public static final String CREATED_DATE = "createDate";

	/** Constant value for representing a 'aEndCktId' */
	public static final String A_END_CKT_ID = "aEndCktId";
	
	/** Constant value for representing a 'BRIGHTSPEED' */
	public static final String BRIGHTSPEED = "BRIGHTSPEED";
	
	/** Constant value for representing a 'GNVLNCXAA01' */
	public static final String GNVLNCXAA01 = "GNVLNCXAA01";

	/** Constant value for representing a 'zEndCktId' */
	public static final String Z_END_CKT_ID = "zEndCktId";

	/** Constant value for representing a 'lastUpdated' */
	public static final String LAST_UPDATED_DATE = "lastUpdated";

	/** Constant value for representing a 'functionalStatus' */
	public static final String FUNCTION_STATUS = "functionalStatus";

	/** Constant value for representing a 'MCO' */
	public static final String MCO = "MCO";

	/** Constant value for representing a 'user' */
	public static final String USER = "user";

	/** Constant name for TID field. */
	public static final String TID = "TID";

	/** Key representing a port key. */
	public static final String PORT_KEY = "portKey";

	/** Key representing a port key. */
	public static final String PORTKEY = "port_key";

	/** Key representing a location_lat. */
	public static final String LOCATION_LAT = "location_lat";

	/** Key representing a location_long. */
	public static final String LOCATION_LONG = "location_long";

	/** Key representing a port function. */
	public static final String PORT_FUNCTION = "portFunction";

	/** Key for port ID. */
	public static final String PORT_ID = "portId";

	/** Key for connector type. */
	public static final String CONNECTOR = "connector";

	/** Key for bandwidth. */
	public static final String BW = "bw";

	/** Key for equipment shelf. */
	public static final String SHELF = "shelf";

	/** Key for equipment unit. */
	public static final String UNIT = "unit";

	/** Key for relay rack. */
	public static final String RELAYRCK = "relayrck";

	/** Key for node ID. */
	public static final String NODE_ID = "node_id";

	/** Key for CLLI LATA (equipment location code). */
	public static final String CLLI_LATA = "clli_lata";

	/** Generic ID field name. */
	public static final String ID = "id";

	// ================= Measurement Units ================= //

	/** Measurement unit: Megabyte (MB). */
	public static final String MB = "MB";

	/** Bandwidth type: Gigabit Ethernet (GE). */
	public static final String GE = "GE";

	/** Special bandwidth constant representing 2.5 Gigabit Ethernet. */
	public static final String SPECIAL_BW_2_5_GE = "2.5";

	/** Represents an empty string constant. */
	public static final String EMPTY_STRING = "";

	/** Represents MPLS string constant. */
	public static final String MPLS = "MPLS";

	/** Route id key (lowercase). */
	public static final String ROUTE_ID = "routeId";

	/** Route id key (upper/labeled). */
	public static final String ROUTEID = "Route_ID";

	// ================= Connector Types ================= //

	/** Connector type: Small Form-factor Pluggable (SFP). */
	public static final String CONNECTOR_SFP = "SFP";

	/** Connector type: RJ45 (Ethernet). */
	public static final String CONNECTOR_RJ45 = "RJ45";

	/** Connector type: 10 Gigabit Small Form-factor Pluggable (XFP). */
	public static final String CONNECTOR_XFP = "XFP";

	// ================= Inventory Bandwidth Keys ================= //

	/** JSON/Map key representing bandwidth. */
	public static final String BANDWIDTH = "bandwidth";

	/** JSON/Map key representing interface type. */
	public static final String INTERFACE = "interfaceType";

	/** JSON/Map key for total bandwidth capacity. */
	public static final String INV_TOTAL_BANDWIDTH = "totalBandwidth";

	/** JSON/Map key for allocated EVC bandwidths. */
	public static final String INV_EVC_BANDWIDTHS = "evcBandwidths";

	/** JSON/Map key for CurrentBandwidth. */
	public static final String INV_CURRENT_BANDWIDTHS = "CurrentBandwidth";

	/** JSON/Map key for used bandwidth. */
	public static final String INV_USED_BANDWIDTH = "usedBandwidth";

	/** JSON/Map key for available bandwidth. */
	public static final String INV_AVAILABLE_BANDWIDTH = "availableBandwidth";

	/** JSON/Map key to check if required bandwidth is available. */
	public static final String INV_REQUESTED_BANDWIDTH_AVAILABLE = "requestedBandwidthAvailable";

	/** JSON/Map key for Circuit ID. */
	public static final String INV_CIRCUIT_ID = "circuitId";

	/** JSON/Map key for Circuit */
	public static final String INV_CIRCUIT = "circuit";

	/** JSON/Map key for capacity. */
	public static final String INV_CAPACITY = "capacity";

	/** JSON/Map key for External ID. */
	public static final String INV_EXTERNAL_ID = "externalId";

	/** JSON/Map key for Product Payload. */
	public static final String INV_PRODUCT_PAYLOAD = "productPayload";

	/** JSON/Map key for requested bandwidth. */
	public static final String INV_REQUESTED_BANDWIDTH = "requestedBandwidth";

	// ================= Config Keys ================= //

	/** Key representing validation fields for UNI configuration. */
	public static final String VALIDATION_FIELDS_FOR_UNI = "validationFieldsForUNI";

	/** Key representing design-impacting fields for UNI configuration. */
	public static final String DESIGN_IMPACTING_FIELDS_FOR_UNI = "designImpactingFieldsForUNI";

	/** Constant value representing the "ADD" operation type. */
	public static final String ADD = "ADD";

	/** Constant value representing a device type. */
	public static final String DEVICE = "device";

	/** Constant value representing aEnd info. */
	public static final String AEND_INFO = "aEndInfo";

	/** Constant value representing zEnd info. */
	public static final String ZEND_INFO = "zEndInfo";

	/** Constant value representing AEND marker. */
	public static final String AEND = "AEND";

	/** Constant value representing ZEND marker. */
	public static final String ZEND = "ZEND";

	/** Constant value representing a boolean-like "Yes". */
	public static final String YES = "Y";

	/** Constant value representing a boolean-like "No". */
	public static final String NO = "N";

	// ================= Route Keys ================= //

	/**
	 * Key used as a parameter name when passing the base prefix of the route in
	 * Cypher queries.
	 */
	public static final String INV_BASE_STRING = "base";

	/** Key used to extract the route name from a Neo4j query result. */
	public static final String INV_ROUTE_NAME = "routeName";

	/**
	 * Label or property name used in the Neo4j graph to refer to the route name.
	 */
	public static final String INV_ROUTE = "Route";

	/**
	 * Label or property name used to fetch details from business logic (lowercase).
	 */
	public static final String INV_ROUTE_ = "route";

	/** Alias used for the 'ROUTE' node in Cypher queries. */
	public static final String INV_RT = "rt";

	/**
	 * Property key used in Neo4j nodes to store the unique identifier for a route.
	 */
	public static final String INV_ROUTE_ID = "Route_ID";

	/** Prefix indicating a route identifier (e.g., {@code "RTE:123"}). */
	public static final String INV_ROUTE_STARTS_WITH = "RTE:";

	/** Constant representing a hyphen ("-"). */
	public static final String INV_HYPHEN = "-";

	/** Error message indicating no UNIConnection found in Neo4j. */
	public static final String ERR_NO_UNI_CONNECTION = "No UNIConnection found in Neo4j";

	/** Error message indicating a mismatch in a field during validation. */
	public static final String ERR_FIELD_MISMATCH = "Mismatch in field:";

	/** Error message indicating an exception or error while reading a field. */
	public static final String ERR_READING_FIELD = "Error reading field";

	/** Success message indicating that all attributes match. */
	public static final String SUCCESS_ALL_ATTRIBUTES_MATCH = "All attributes match";

	/** Key representing the "before MPLS" route id. */
	public static final String BEFORE_MPLS_ROUTEID = "beforeMPLS";

	/** Key representing the "after MPLS" route id. */
	public static final String AFTER_MPLS_ROUTEID = "afterMPLS";

	/** Key for EVC ID. */
	public static final String EVC_ID = "evcId";

	/** Key for circuit name. */
	public static final String CIRCUIT_NAME = "circuitName";

	/** Key for UNI list. */
	public static final String UNI_LIST = "uniList";

	/** Key indicating path origin. */
	public static final String PATH_FROM = "pathFrom";

	/** Key indicating path destination. */
	public static final String PATH_TO = "pathTo";

	/** Key for service name. */
	public static final String SERVICE_NAME = "serviceName";

	/** Key for normalized id. */
	public static final String NORMALIZED_ID = "normalizedId";

	/** Key for element id. */
	public static final String ELEMENT_ID = "elementId";

	/** Key for service type. */
	public static final String SERVICE_TYPE = "serviceType";

	/** Key for list of routes. */
	public static final String ROUTE_LIST = "routesList";

	/** Key for connection type. */
	public static final String CONNECTION_TYPE = "connectionType";

	/** Key for alias circuit id. */
	public static final String ALIAS_CKT_ID = "aliasCktId";

	/** Key for location address. */
	public static final String LOCATION_ADDRESS = "location_address";

	/** Key for location state. */
	public static final String LOCATION_STATE = "location_state";

	/** Key for location ZIP code. */
	public static final String LOCATION_ZIP = "location_zip";

	/** Key for location city. */
	public static final String LOCATION_CITY = "location_city";

	/** Key for circuit id. */
	public static final String CKT_ID = "cktid";

	/** Success message when NNI bandwidth calculation succeeds. */
	public static final String NNI_BANDWIDTH_CALCULATED_SUCCESSFULLY = "NNI bandwidth calculated successfully.";

	/** Error prefix when NNI bandwidth calculation fails. */
	public static final String NNI_BANDWIDTH_ERROR = " Exception during calculation of NNI bandwidth:";

	/** Key for uniConnection JSON/object. */
	public static final String INV_UNICONNECTION = "uniConnection";

	/** Key for NNI name. */
	public static final String INV_NNI_NAME = "nniName";

	/** Key for EVC order number. */
	public static final String INV_EVC_ORDER_NUMBER = "evcOrderNumber";

	/** Key for C-Tag start. */
	public static final String INV_C_TAG_START = "cTag_start";

	/** Key for service id. */
	public static final String INV_SERVICE_ID = "serviceId";

	/** Key for uni element id. */
	public static final String INV_UNI_ELEMENT_ID = "uniElementId";

	/** Key for cTag. */
	public static final String INV_C_TAG = "cTag";

	/** Key for design impact flag. */
	public static final String INV_DESIGN_IMPACT = "designImpact";

	/** Key for network impact flag (note: kept same spelling as original). */
	public static final String INV_NETWROK_IMPACT = "networkImpact";

	/** Key for all C-Tags list. */
	public static final String INV_ALL_CTAGS = "allCTags";

	// ================= Location / Address Keys ================= //

	/** Key for postal/ZIP code field. */
	public static final String ZIP = "zip";

	/** Key for country field. */
	public static final String COUNTRY = "country";

	/** Key for state or province field. */
	public static final String STATE = "state";

	/** Key for city field. */
	public static final String CITY = "city";

	/** Key for the full address line field. */
	public static final String ADDRESS_LINE = "addressLine";

	/** Key for latitude coordinate. */
	public static final String LATITUDE = "latitude";

	/** Key for longitude coordinate. */
	public static final String LONGITUDE = "longitude";

	/** Key for the location object inside the product payload. */
	public static final String LOCATION = "location";

	// ================= Product / Payload Keys ================= //

	/** Key for product specification field inside payload. */
	public static final String PRODUCT_SPECIFICATION = "productSpecification";

	/** Key for productOrderItem inside payload. */
	public static final String PRODUCT_ORDER_ITEM = "productOrderItem";

	/**
	 * Key for productCharacteristic inside payload. (constant name preserved from
	 * original: PRODUCT_CAHRACTERISTIC)
	 */
	public static final String PRODUCT_CAHRACTERISTIC = "productCharacteristic";

	/** Key for serviceMux */
	public static final String SERVICE_MUX = "serviceMux";

	/** Key for serviceMux */
	public static final String SVCMUX = "SVCMUX";

	/** Key for noOfEVCs_OVCsAllowed */
	public static final String NO_OF_EVCS_OVCS_ALLOWED = "noOfEVCs_OVCsAllowed";

	/** Key for autoNegotiate */
	public static final String AUTO_NEGOTIATE = "autoNegotiate";

	/** Key for subcriberType */
	public static final String SUBSCRIBER_TYPE = "subcriberType";

	/** Key for cktfmt */
	public static final String CKFMT = "cktfmt";

	/** Key for product object. */
	public static final String PRODUCT = "product";

	/** Key for selected UNI ports list. */
	public static final String UNI_PORTS = "uniPorts";

	/** Key for selected UNI port . */
	public static final String UNI_PORT = "uniPort";

	/** Key for selected NNI port . */
	public static final String NNI_PORT = "nniPort";

	/** Key for selected NNI ports list. */
	public static final String NNI_PORTS = "nniPorts";

	/** Key for bandwidthInfo object. */
	public static final String BANDWIDTH_INFO = "bandwidthInfo";

	/** JSON field for specification ("spec"). */
	public static final String SPEC = "spec";

	/** JSON field for network channel ("nc"). */
	public static final String NETWORK_CHANNEL = "nc";
	
	/** JSON field for network channel ("ncCode"). */
	public static final String NETWORK_CHANNEL_CODE = "ncCode";
	
	/** JSON field for deviceTypeIndicator. */
	public static final String DEVICE_TYPE_INDICATOR = "deviceTypeIndicator";

	/** JSON field for network channel interface ("nci"). */
	public static final String NETWORK_CHANNEL_INTERFACE = "nci";

	/** JSON field for secondary network channel interface ("secNci"). */
	public static final String NETWORK_CHANNEL_INTERFACE_SEC = "secNci";

	/** JSON field for related party details. */
	public static final String RELATED_PARTY = "relatedParty";

	/** JSON field for subscriber name. */
	public static final String NAME = "name";

	/** JSON field for subscriber first name. */
	public static final String FIRST_NAME = "firstName";

	/** JSON field for subscriber middle name. */
	public static final String MIDDLE_NAME = "middleName";

	/** JSON field for subscriber last name. */
	public static final String LAST_NAME = "lastName";

	/** JSON field for order date. */
	public static final String ORDER_DATE = "orderDate";

	/** Generic JSON field value used inside product characteristics. */
	public static final String VALUE = "value";

	/** Generic JSON field value used inside product characteristics. */
	public static final String ACTION = "action";

	/** JSON field for MEF ENNI. */
	public static final String MEF_ENNI = "MEF ENNI";

	/** JSON field for MEF OVC. */
	public static final String MEF_OVC = "MEF OVC";

	/** JSON field for MEF EVC. */
	public static final String MEF_EVC = "MEF EVC";

	// ================= Regex Patterns ================= //

	/**
	 * Regular expression pattern that matches any character that is <b>not</b>
	 * alphanumeric (A–Z, a–z, 0–9).
	 * <p>
	 * Example:
	 * 
	 * <pre>{@code
	 * String input = "abc@123!";
	 * String clean = input.replaceAll(Constants.REGEX_NON_ALPHANUMERIC, "");
	 * // clean == "abc123"
	 * }</pre>
	 */
	public static final String REGEX_NON_ALPHANUMERIC = "[^a-zA-Z0-9]";

	/**
	 * Regular expression pattern that matches any character that is <b>not</b> a
	 * digit (0–9).
	 * <p>
	 * Example:
	 * 
	 * <pre>{@code
	 * String input = "ABC123XYZ";
	 * String digitsOnly = input.replaceAll(Constants.REGEX_NON_DIGIT, "");
	 * // digitsOnly == "123"
	 * }</pre>
	 */
	public static final String REGEX_NON_DIGIT = "[^0-9]";

	/** Precompiled {@link Pattern} for {@link #REGEX_NON_DIGIT}. */
	public static final Pattern PATTERN_NON_DIGIT = Pattern.compile(REGEX_NON_DIGIT);

	/** Precompiled {@link Pattern} for {@link #REGEX_NON_ALPHANUMERIC}. */
	public static final Pattern PATTERN_NON_ALPHANUMERIC = Pattern.compile(REGEX_NON_ALPHANUMERIC);

	/** Key for networkChannel. */
	public static final String NETWORKCHANNEL = "networkChannel";

	/** Key for networkChannelInterface. */
	public static final String NETWORKCHANNELINTERFACE = "networkChannelInterface";

	/** Key for networkChannelInterfaceSec. */
	public static final String NETWORKCHANNELINTERFACESPEC = "networkChannelInterfaceSec";

	/** Key for subscriberName. */
	public static final String SUBSCRIBER_NAME = "subscriberName";

	/** Key for subscriberFullName. */
	public static final String SUBSCRIBER_FULL_NAME = "subscriberFullName";

	/** Key for portBandwidth. */
	public static final String PORT_BANDWIDTH = "portBandwidth";

	/** Key for locz. */
	public static final String LOCZ = "locz";

	/** Key for loca. */
	public static final String LOCA = "loca";

	/**
	 * Regex pattern to extract the trailing numeric ID from a route name.
	 * <p>
	 * Example: For "ROUTE-123", it will capture "123".
	 * </p>
	 */
	public static final String REGEX_ROUTE_ID_SUFFIX = ".*-(\\d+)$";
	
}
