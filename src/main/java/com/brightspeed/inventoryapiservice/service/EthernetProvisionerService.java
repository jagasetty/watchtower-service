package com.brightspeed.inventoryapiservice.service;

import com.brightspeed.inventoryapiservice.config.ApiConfig;
import com.brightspeed.inventoryapiservice.config.UNIConnectionDefaults;
import com.brightspeed.inventoryapiservice.dto.request.DesignImpactField;
import com.brightspeed.inventoryapiservice.dto.request.PortInfo;
import com.brightspeed.inventoryapiservice.dto.request.RouteInfo;
import com.brightspeed.inventoryapiservice.dto.request.UNIConnectionDTO;
import com.brightspeed.inventoryapiservice.dto.response.ImpactCheckResponse;
import com.brightspeed.inventoryapiservice.dto.response.ResponseStatus;
import com.brightspeed.inventoryapiservice.exception.ErrorCode;
import com.brightspeed.inventoryapiservice.exception.RelationshipNotCreatedException;
import com.brightspeed.inventoryapiservice.repository.InventorySearchRepository;
import com.brightspeed.inventoryapiservice.service.model.DeviceLocation;
import com.brightspeed.inventoryapiservice.util.ApplicationUtils;
import com.brightspeed.inventoryapiservice.util.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;

import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.Value;
import org.neo4j.driver.Session;
import org.neo4j.driver.Record;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.exceptions.NoSuchRecordException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service class responsible for Ethernet provisioning and validation
 * operations.
 *
 * <p>
 * This service integrates with Neo4j to manage network inventory data such as
 * UNI and NNI ports, validates incoming product and EVC orders, and applies
 * provisioning logic to assign network resources.
 * </p>
 *
 * <p>
 * Main responsibilities:
 * </p>
 * <ul>
 * <li>Extract product payload and build DTOs for processing.</li>
 * <li>Query Neo4j for available UNI and NNI ports.</li>
 * <li>Create and manage UNI connections in Neo4j.</li>
 * <li>Validate Ethernet Virtual Connection (EVC) orders for bandwidth, cTag
 * uniqueness, and design impacts.</li>
 * </ul>
 *
 * This service acts as the backend orchestration layer for the
 * {@link com.brightspeed.inventoryapiservice.controller.EthernetProvisionerController}.
 *
 * @author Brightspeed
 */
@Slf4j
@Service
public class EthernetProvisionerService {

	@Autowired
	EthernetOrderService ethOrderSrvc;

	@Autowired
	InventorySearchRepository inventorySearchRepo;

	@Autowired
	ApiConfig apiConfig;

	@Autowired
	ConfigService configSrvc;

	@Autowired
	UNIConnectionDefaults defaults;

	private final Driver driver;
	private final Neo4jClient neo4jClient;

	EthernetProvisionerService(Driver driver, Neo4jClient neo4jClient) {
		this.driver = driver;
		this.neo4jClient = neo4jClient;
	}

	/**
	 * Extracts the productPayload section from the incoming request body.
	 *
	 * @param assignedInventoryBody request payload containing product order details
	 * @return productPayload map if present
	 */
	public Map<String, Object> extractProductPayload(Map<String, Object> assignedInventoryBody) {
		return (Map<String, Object>) assignedInventoryBody.get(Constants.INV_PRODUCT_PAYLOAD);
	}

	/**
	 * Builds a {@link UNIConnectionDTO} from JSON payload.
	 *
	 * <p>
	 * This method parses input JSON and extracts:
	 * <ul>
	 * <li>Order identifiers (cktId, externalId)</li>
	 * <li>Subscriber details (name, full name)</li>
	 * <li>Location and product characteristics</li>
	 * <li>Defaults from {@link UNIConnectionDefaults} if missing</li>
	 * </ul>
	 * </p>
	 *
	 * @param root root JSON payload
	 * @return constructed {@link UNIConnectionDTO}
	 */
	public UNIConnectionDTO buildDTOFromPayload(JsonNode root) {
		UNIConnectionDTO dto = new UNIConnectionDTO();

		// ✅ From Request Payload
		dto.setCktid(root.path(Constants.INV_CIRCUIT_ID).asText());
		dto.setExternalId(root.path(Constants.INV_EXTERNAL_ID).asText());

		JsonNode payload = root.path(Constants.INV_PRODUCT_PAYLOAD);

		// Optional fields
		dto.setSpec(payload.path(Constants.SPEC).asText(null));
		dto.setNetworkChannel(payload.path(Constants.NETWORK_CHANNEL).asText(null));
		dto.setNetworkChannelInterface(payload.path(Constants.NETWORK_CHANNEL_INTERFACE).asText(null));
		dto.setNetworkChannelInterfaceSec(payload.path(Constants.NETWORK_CHANNEL_INTERFACE_SEC).asText(null));

		JsonNode relatedParty = payload.path(Constants.RELATED_PARTY);
		if (relatedParty.isArray() && relatedParty.size() > 0) {
			JsonNode party = relatedParty.get(0);

			// Subscriber Name = "name"
			dto.setSubscriberName(party.path(Constants.NAME).asText(null));

			// Subscriber Full Name = firstName + middleName (if present) + lastName
			String firstName = party.path(Constants.FIRST_NAME).asText(Constants.EMPTY_STRING);
			String middleName = party.path(Constants.MIDDLE_NAME).asText(Constants.EMPTY_STRING);
			String lastName = party.path(Constants.LAST_NAME).asText(Constants.EMPTY_STRING);

			String fullName = Stream.of(firstName, middleName, lastName).filter(s -> s != null && !s.isBlank())
					.collect(Collectors.joining(" "));

			dto.setSubscriberFullName(fullName);
		}

		// Order Date
		String orderDateStr = payload.path(Constants.ORDER_DATE).asText(null);
		if (orderDateStr != null) {
			dto.setOrderDate(LocalDateTime.parse(orderDateStr));
		}

		// Product Order Item (action, optional)
		JsonNode orderItem = payload.path(Constants.PRODUCT_ORDER_ITEM).get(0);
		if (orderItem != null) {
			dto.setAction(orderItem.path(Constants.ACTION).asText(null));
		}

		JsonNode location = orderItem.path(Constants.PRODUCT).path(Constants.LOCATION);
		dto.setLocation_zip(location.path(Constants.ZIP).asText(null));
		dto.setLocation_city(location.path(Constants.CITY).asText(null));
		dto.setLocation_state(location.path(Constants.STATE).asText(null));
		dto.setLocation_lat(location.path(Constants.LATITUDE).asText(null));
		dto.setLocation_long(location.path(Constants.LONGITUDE).asText(null));
		dto.setLocation_address(location.path(Constants.ADDRESS_LINE).asText(null));
		dto.setState(location.path(Constants.STATE).asText(null));

		// Characteristics override if exists
		JsonNode characteristics = orderItem.path(Constants.PRODUCT).path(Constants.PRODUCT_CAHRACTERISTIC);

		for (JsonNode characteristic : characteristics) {
			String name = characteristic.path(Constants.NAME).asText();
			String value = characteristic.path(Constants.VALUE).asText();
			switch (name) {
			case "serviceType" -> dto.setServiceType(value);
			case "nc" -> dto.setNetworkChannel(value);
			case "nci" -> dto.setNetworkChannelInterface(value);
			case "secNci" -> dto.setNetworkChannelInterfaceSec(value);
			case "spec" -> dto.setSpec(value);
			}
		}

		// 🟩 Hardcoded / Default Values
		dto.setCreatedOn(LocalDateTime.now());
		dto.setServiceType(defaults.getServiceType());
		dto.setCktfmt(defaults.getCktfmt());
		dto.setNoOfEVCs_OVCsAllowed(defaults.getNoOfEVCs_OVCsAllowed());
		dto.setAutoNegotiate(defaults.getAutoNegotiate());
		dto.setSubcriberType(defaults.getSubcriberType());
		dto.setStatus(defaults.getStatus());
		dto.setBundling(defaults.getBundling());
		dto.setAllTo1Bundling(defaults.getAllTo1Bundling());
		dto.setRequestingAffiliate(defaults.getRequestingAffiliate());
		dto.setFunctionalStatus(defaults.getFunctionalStatus());
		dto.setMCO(defaults.getMCO());
		dto.setUser(defaults.getUser());
		dto.setSourceSys(defaults.getSourceSys());
		dto.setMigration(defaults.getMigration());

		return dto;
	}

	/**
	 * Processes product order items to assign UNI/NNI ports.
	 *
	 * <p>
	 * Workflow:
	 * </p>
	 * <ul>
	 * <li>Iterates over productOrderItem list</li>
	 * <li>Identifies UNI products</li>
	 * <li>Fetches location and bandwidth info</li>
	 * <li>Finds eligible UNI ports and corresponding NNI ports</li>
	 * <li>Creates UNI connection and cross-connects in Neo4j</li>
	 * </ul>
	 *
	 * @param productPayload payload containing product order items
	 * @param ucDTO          DTO representing the UNI connection details
	 * @return map with selected UNI/NNI ports, bandwidth info, and location
	 */
	public ResponseStatus processProductOrderItems(Map<String, Object> productPayload, UNIConnectionDTO ucDTO) {

		Map<String, Object> result = new HashMap<>();
		List<Map<String, Object>> productOrderItems = (List<Map<String, Object>>) productPayload
				.get(Constants.PRODUCT_ORDER_ITEM);

		for (Map<String, Object> item : productOrderItems) {
			Map<String, Object> product = (Map<String, Object>) item.get(Constants.PRODUCT);

			if (!isUNIProduct(product))
				continue;

			DeviceLocation location = extractLocation(product);
			BandwidthInfo bwInfo = fetchBandwidthInfo(product);

			ResponseStatus eligibleUniPortsResponse = getAvailableUNIPorts(location, bwInfo.getBandwidthInRange(),
					bwInfo.getConnector());

			PortSelection selection = selectPorts(eligibleUniPortsResponse, location, bwInfo, ucDTO);

			if (selection.selectedUniPort() == null) {
				//throw new IllegalArgumentException(ErrorCode.UNI_PORT_NOT_FOUND.getMessage());
			return	ResponseStatus.failure(ErrorCode.UNI_PORT_NOT_FOUND);
				
			}

			result.put(Constants.UNI_PORTS, selection.selectedUniPort());
			result.put(Constants.NNI_PORTS, selection.selectedNniPorts());
			result.put(Constants.BANDWIDTH_INFO, bwInfo.toMap());
			result.put(Constants.LOCATION, location);
			
		}
		return ResponseStatus.success(result);
	}

	/**
	 * Determines if the given product is a UNI product based on its specification
	 * ID.
	 *
	 * <p>
	 * This method checks the "productSpecification" map within the product and
	 * compares its "id" field against the constant
	 * {@link Constants#PRODUCT_SPEC_UNI}. If the product or specification is
	 * missing or malformed, it returns {@code false}.
	 *
	 * @param product a {@link Map} representing the product, expected to contain a
	 *                nested "productSpecification" map with an "id" field
	 * @return {@code true} if the product specification ID matches
	 *         {@link Constants#PRODUCT_SPEC_UNI}, {@code false} otherwise
	 */
	private boolean isUNIProduct(Map<String, Object> product) {
		Map<String, Object> productSpec = (Map<String, Object>) product.get(Constants.PRODUCT_SPECIFICATION);
		String specId = (String) productSpec.get(Constants.ID);
		return Constants.PRODUCT_SPEC_UNI.equalsIgnoreCase(specId);
	}

	/**
	 * Extracts the location details from the given product map and constructs a
	 * {@link DeviceLocation} object.
	 *
	 * <p>
	 * The product map is expected to contain a "location" map with keys: "zip",
	 * "country", "state", "city", "addressLine", "latitude", and "longitude".
	 *
	 * @param product a {@link Map} representing the product, containing a nested
	 *                "location" map
	 * @return a {@link DeviceLocation} object populated with the location details
	 * @throws NullPointerException  if the "location" map or any required fields
	 *                               are missing
	 * @throws NumberFormatException if latitude or longitude cannot be parsed as
	 *                               double
	 */
	private DeviceLocation extractLocation(Map<String, Object> product) {
		Map<String, Object> locationMap = (Map<String, Object>) product.get(Constants.LOCATION);
		DeviceLocation location = new DeviceLocation();
		location.setZip((String) locationMap.get(Constants.ZIP));
		location.setCountry((String) locationMap.get(Constants.COUNTRY));
		location.setState((String) locationMap.get(Constants.STATE));
		location.setCity((String) locationMap.get(Constants.CITY));
		location.setAddressLine((String) locationMap.get(Constants.ADDRESS_LINE));
		location.setLatitude(Double.parseDouble(locationMap.get(Constants.LATITUDE).toString()));
		location.setLongitude(Double.parseDouble(locationMap.get(Constants.LONGITUDE).toString()));
		return location;
	}

	/**
	 * Fetches bandwidth information for the given product by using its NC, NCI, and
	 * SECNCI characteristics.
	 *
	 * <p>
	 * This method queries the repository for bandwidth and interface data,
	 * validates it, and constructs a {@link BandwidthInfo} record with the results.
	 *
	 * @param product a {@link Map} representing the product, expected to contain
	 *                characteristics "NC", "NCI", "SECNCI"
	 * @return a {@link BandwidthInfo} record containing bandwidth, bandwidth in
	 *         range, connector, and raw data map
	 * @throws IllegalArgumentException if the bandwidth or interface type is
	 *                                  missing or blank
	 */
	private BandwidthInfo fetchBandwidthInfo(Map<String, Object> product) {
		Map<String, String> characteristics = extractCharacteristics(product);
		Map<String, Object> bwData = inventorySearchRepo.bandwidthByNCNCI(
				characteristics.get(Constants.CHARACTERISTIC_NC), characteristics.get(Constants.CHARACTERISTIC_NCI),
				characteristics.get(Constants.CHARACTERISTIC_SECNCI));

		String bandwidth = (String) bwData.get(Constants.BANDWIDTH);
		String bwValue = bandwidth.replaceAll(Constants.REGEX_NON_DIGIT, Constants.EMPTY_STRING).toUpperCase();
		String bandwidthInRange = ApplicationUtils.supportedBandwidth(Integer.parseInt(bwValue));
		String interfaceType = (String) bwData.get(Constants.INTERFACE);

		if (StringUtils.isBlank(bandwidth) || StringUtils.isBlank(interfaceType)) {
			throw new IllegalArgumentException(Constants.ERROR_NO_BW_INTERFACE);
		}

		String connector = ApplicationUtils.deriveConnector(interfaceType);
		return new BandwidthInfo(bandwidth, bandwidthInRange, connector, bwData);
	}

	/**
	 * Immutable record representing bandwidth information of a UNI port or product.
	 *
	 * @param bandwidth        the raw bandwidth value as stored in the system
	 * @param bandwidthInRange the normalized or supported bandwidth range
	 * @param connector        the derived connector type
	 * @param rawData          the original map of data retrieved from the
	 *                         repository
	 */
	private static record BandwidthInfo(String bandwidth, String bandwidthInRange, String connector,
			Map<String, Object> rawData) {
		public String getBandwidthInRange() {
			return bandwidthInRange;
		}

		public String getConnector() {
			return connector;
		}

		public String getBandwidth() {
			return bandwidth;
		}

		public Map<String, Object> toMap() {
			return rawData;
		}
	}

	/**
	 * Queries Neo4j to retrieve available UNI ports at a given location, matching
	 * bandwidth and connector requirements.
	 *
	 * @param location  device location details
	 * @param bandwidth required bandwidth
	 * @param connector connector type
	 * @return {@link ResponseStatus} containing list of available UNI ports, or
	 *         error if none found
	 */
	private ResponseStatus getAvailableUNIPorts(DeviceLocation location, String bandwidth, String connector) {

		log.info("inside getAvaiblableUNIPorts");
		String query = String.format("""
				    MATCH (n_e:Equipment)-[:COMPONENT_OF]-(n_s:Shelf)-[:COMPONENT_OF]-(n_ep:EquipmentPort)
				    WHERE
				      n_e.clli_address = '%s' AND
				      n_e.clli_city = '%s' AND
				      n_e.clli_state = '%s' AND
				      n_e.clli_zip = '%s' AND
				      n_e.deviceRoles = 'NID' AND
				      n_ep.portFunction CONTAINS 'CF' AND
				      n_ep.bw IN [%s] AND
				      n_ep.connector CONTAINS '%s' AND
				      NOT (n_ep)-[:CONNECTED_TO]-(:UNIConnection) AND
				      NOT (n_ep)-[:XCONNECT]-(:EquipmentPort)
				    RETURN DISTINCT n_ep, n_e, elementId(n_ep) AS portId
				    ORDER BY portId
				""", location.getAddressLine(), location.getCity(), location.getState(), location.getZip(), bandwidth,
				connector);

		log.info("Cypher Query: {}", query);

		try (Session session = driver.session()) {
			List<PortInfo> ports = session.readTransaction(tx -> {
				List<PortInfo> list = new ArrayList<>();
				Result result = tx.run(query);
				while (result.hasNext()) {
					Record record = result.next();
					Value epNode = record.get("n_ep");
					Value eNode = record.get("n_e");

					list.add(new PortInfo(record.get("portId").asString(), eNode.get(Constants.TID).asString(),
							epNode.get(Constants.PORT_FUNCTION).asString(), epNode.get(Constants.PORT_KEY).asString(),
							epNode.get(Constants.CONNECTOR).asString(), epNode.get(Constants.BW).asString(),
							epNode.get(Constants.SHELF).asString(), epNode.get(Constants.UNIT).asString(),
							epNode.get(Constants.RELAYRCK).asString(), epNode.get(Constants.LOCATION).asString(), // location
							epNode.get(Constants.NODE_ID).asString(), eNode.get(Constants.CLLI_LATA).asString(),
							epNode.get(Constants.LOCATION).asString(), // locz
							epNode.get(Constants.BW).asString(), // portBandwidth
							epNode.get(Constants.LOCATION).asString(), // loca
							epNode.get(Constants.PORT_KEY).asString() // port_key
					));
				}
				return list;
			});
			if (ports.isEmpty()) {
				return ResponseStatus.failure(ErrorCode.UNI_PORT_NOT_FOUND);
			}

			return ResponseStatus.success(ports);
		} catch (Exception e) {
			log.error("Exception during getAvailableUNIPorts: {}", e.getMessage(), e);
			return ResponseStatus.failure(ErrorCode.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Immutable record representing the selection of a UNI port along with its
	 * associated NNI ports.
	 *
	 * <p>
	 * This record is typically used after evaluating available ports for a UNI
	 * connection.
	 *
	 * @param selectedUniPort  the {@link PortInfo} representing the selected UNI
	 *                         port; can be {@code null} if no suitable port was
	 *                         found
	 * @param selectedNniPorts a list of maps containing details of the selected NNI
	 *                         ports associated with the UNI port; can be empty if
	 *                         none are selected
	 */
	public static record PortSelection(PortInfo selectedUniPort, List<Map<String, Object>> selectedNniPorts) {
	}

	/**
	 * Selects UNI and NNI ports for provisioning, creates UNI connection, and
	 * establishes cross-connects in Neo4j.
	 *
	 * @param eligibleUniPortsResponse response with available UNI ports
	 * @param location                 target device location
	 * @param bwInfo                   bandwidth info
	 * @param ucDTO                    UNI connection DTO
	 * @return {@link PortSelection} containing selected UNI and NNI ports
	 */
	private PortSelection selectPorts(ResponseStatus eligibleUniPortsResponse, DeviceLocation location,
			BandwidthInfo bwInfo, UNIConnectionDTO ucDTO) {
		List<PortInfo> eligibleUniPorts = (List<PortInfo>) eligibleUniPortsResponse.getData();
		if (eligibleUniPorts == null || eligibleUniPorts.isEmpty()) {
			return new PortSelection(null, List.of());
		}

		for (PortInfo port : eligibleUniPorts) {
			List<Map<String, Object>> nniPortsForTid = getConnectedNNIPorts(location, port.getTid(),
					bwInfo.getBandwidth());

			if (nniPortsForTid != null && !nniPortsForTid.isEmpty() && createUniConnectionForPort(port, ucDTO)
					&& updateAllCircuitsDataWithUNI(port, ucDTO)) {

				String nniPortId = String.valueOf(nniPortsForTid.get(0).get("epId"));
				boolean xconnectCreated = createCrossConnect(port.getPortId(), nniPortId);
				if (!xconnectCreated) {
					throw new RuntimeException("Failed to create cross-connect");
				}

				return new PortSelection(port, nniPortsForTid);
			}
		}
		return new PortSelection(null, List.of());
	}

	/**
	 * Extracts key characteristics from a product map, specifically NC, NCI, and
	 * secondary NCI.
	 *
	 * @param product the product map containing the "productCharacteristic" list
	 * @return a map with keys {@link Constants#CHARACTERISTIC_NC},
	 *         {@link Constants#CHARACTERISTIC_NCI}, and
	 *         {@link Constants#CHARACTERISTIC_SECNCI} and their corresponding
	 *         values; values may be null if not present
	 */
	private Map<String, String> extractCharacteristics(Map<String, Object> product) {
		List<Map<String, Object>> characteristics = (List<Map<String, Object>>) product
				.get(Constants.PRODUCT_CAHRACTERISTIC);

		String nc = null, nci = null, secNci = null;
		for (Map<String, Object> characteristic : characteristics) {
			String name = (String) characteristic.get(Constants.NAME);
			String value = characteristic.get(Constants.VALUE).toString();
			if (Constants.CHARACTERISTIC_NC.equalsIgnoreCase(name))
				nc = value;
			else if (Constants.CHARACTERISTIC_NCI.equalsIgnoreCase(name))
				nci = value;
			else if (Constants.CHARACTERISTIC_SECNCI.equalsIgnoreCase(name))
				secNci = value;
		}
		return Map.of(Constants.CHARACTERISTIC_NC, nc, Constants.CHARACTERISTIC_NCI, nci,
				Constants.CHARACTERISTIC_SECNCI, secNci);
	}

	/**
	 * Retrieves NNI ports connected to a specific equipment (identified by TID) in
	 * a given location and bandwidth.
	 *
	 * <p>
	 * This method constructs and executes a Cypher query to fetch NNI connection
	 * details from Neo4j.
	 *
	 * @param location  the {@link DeviceLocation} representing the physical
	 *                  location of the equipment
	 * @param tid       the TID of the equipment
	 * @param bandwidth the bandwidth requirement for the port
	 * @return a list of maps, each containing details of connected NNI ports;
	 *         returns an empty list if no ports are found or if an error occurs
	 */
	private List<Map<String, Object>> getConnectedNNIPorts(DeviceLocation location, String tid, String bandwidth) {
		String cypher = String.format(
				"""
						MATCH (cfg:USILConfigMetaData {group: 'UNIConnection'})
						-[:HAS_CONFIG]->(bwCfg:USILConfig {subGroup: 'NMI',  name: 'bandwidths'})
						WITH bwCfg.bandwidths AS allowedBandwidths
						   MATCH (n_e:Equipment)-[:COMPONENT_OF]-(n_s:Shelf)-[:COMPONENT_OF]-(n_ep:EquipmentPort)
						   WHERE   n_e.TID = '%s'
						   AND n_ep.portFunction CONTAINS 'NF' AND  n_ep.bw IN allowedBandwidths
						   AND NOT (n_ep)-[:XCONNECT]-(:EquipmentPort)
						   WITH n_e, n_ep
						   MATCH (n_ep)-[:CONNECTED_TO]-(nni:NNIConnection)
						   RETURN DISTINCT n_e.TID as tid, n_ep.portKey as key, n_ep.connector as connector,
						          n_ep.bw as bandwidth, nni.id as nniId, n_ep.id AS epmId, elementId(n_ep) AS epId,  nni.cktid AS nmiCircuitId
						   ORDER BY n_ep.id
						""",
				tid);

		log.info("getConnectedNNIPorts cypher: {}", cypher);
		return runCypherQuery(cypher);
	}

	/**
	 * Executes a given Cypher query against Neo4j and returns the results as a list
	 * of maps.
	 *
	 * <p>
	 * Each map represents a single record, with keys as property names and values
	 * as property values.
	 *
	 * @param cypher the Cypher query string to execute
	 * @return a list of maps containing query results; returns an empty list if an
	 *         error occurs
	 */
	private List<Map<String, Object>> runCypherQuery(String cypher) {
		try (Session session = driver.session()) {
			return session.readTransaction(tx -> {
				Result result = tx.run(cypher);
				List<Map<String, Object>> records = new ArrayList<>();
				while (result.hasNext()) {
					Record rec = result.next();
					Map<String, Object> row = new HashMap<>();
					for (String key : rec.keys()) {
						row.put(key, rec.get(key).asObject());
					}
					records.add(row);
				}
				return records;
			});
		} catch (Exception e) {
			log.error("Error executing cypher query: {}", e.getMessage(), e);
			return Collections.emptyList();
		}
	}

	/**
	 * Creates a new {@code UNIConnection} node in Neo4j and links it to the
	 * specified port.
	 *
	 * @param portInfo port details
	 * @param dto      UNI connection details
	 * @return true if the node creation was successful, false otherwise
	 */
	private boolean createUniConnectionForPort(PortInfo portInfo, UNIConnectionDTO dto) {
		String query = """
				    MATCH (ep:EquipmentPort) where elementId(ep) = $portId
					CREATE (uni:UNIConnection {
						portId: $portId,
						cktid: $cktid,
						aliasCktId: $aliasCktId,
						serviceType: $serviceType,
						networkChannel: $networkChannel,
						spec: $spec,
						state: $state,
						networkChannelInterface: $networkChannelInterface,
						networkChannelInterfaceSec: $networkChannelInterfaceSec,
						location: $location,
						location_zip: $location_zip,
						location_lat: $location_lat,
						location_long: $location_long,
						location_address: $location_address,
						location_state: $location_state,
						location_city: $location_city,
						subscriberName: $subscriberName,
						subscriberFullName: $subscriberFullName,
						serviceMux: $serviceMux,

						portBandwidth: $portBandwidth,
						port_key: $port_key,
						relayrck: $relayrck,
						clli_lata: $clli_lata,
						node_id: $node_id,
						locz: $locz,
						loca: $loca,
						shelf: $shelf,
						unit: $unit,

						cktfmt: $cktfmt,
						noOfEVCs_OVCsAllowed: $noOfEVCs_OVCsAllowed,
						autoNegotiate: $autoNegotiate,
						subcriberType: $subcriberType,
						status: $status,
						bundling: $bundling,
						allTo1Bundling: $allTo1Bundling,
						requestingAffiliate: $requestingAffiliate,
						functionalStatus: $functionalStatus,
						MCO: $MCO,
						user: $user
						})

				    CREATE (uni)-[:CONNECTED_TO]->(ep)
				    RETURN uni.id AS uniId
				""";

		try {
			Map<String, Object> uniData = new HashMap<>();
			uniData.put(Constants.PORT_ID, portInfo.getPortId());
			uniData.put(Constants.CKT_ID, ApplicationUtils.isNull(dto.getCktid()));
			uniData.put(Constants.SERVICE_TYPE, ApplicationUtils.isNull(dto.getServiceType()));// need to check
			uniData.put(Constants.LOCATION_STATE, ApplicationUtils.isNull(dto.getLocation_state()));
			uniData.put(Constants.LOCATION_CITY, ApplicationUtils.isNull(dto.getLocation_city()));
			uniData.put(Constants.LOCATION_ZIP, ApplicationUtils.isNull(dto.getLocation_zip()));
			uniData.put(Constants.LOCATION_ADDRESS, ApplicationUtils.isNull(dto.getLocation_address()));
			uniData.put(Constants.NETWORKCHANNEL, ApplicationUtils.isNull(dto.getNetworkChannel()));
			uniData.put(Constants.NETWORKCHANNELINTERFACE, ApplicationUtils.isNull(dto.getNetworkChannelInterface()));
			uniData.put(Constants.NETWORKCHANNELINTERFACESPEC,
					ApplicationUtils.isNull(dto.getNetworkChannelInterfaceSec()));

			uniData.put(Constants.SPEC, ApplicationUtils.isNull(dto.getSpec()));
			uniData.put(Constants.SUBSCRIBER_NAME, ApplicationUtils.isNull(dto.getSubscriberName()));
			uniData.put(Constants.SUBSCRIBER_FULL_NAME, ApplicationUtils.isNull(dto.getSubscriberFullName()));
			uniData.put(Constants.PORT_BANDWIDTH, ApplicationUtils.isNull(portInfo.getPortBandwidth()));
			uniData.put(Constants.LOCZ, ApplicationUtils.isNull(portInfo.getLocz()));
			uniData.put(Constants.LOCA, ApplicationUtils.isNull(portInfo.getLoca()));
			uniData.put(Constants.CLLI_LATA, ApplicationUtils.isNull(portInfo.getClli_lata()));
			uniData.put(Constants.SHELF, ApplicationUtils.isNull(portInfo.getShelf()));
			uniData.put(Constants.UNIT, ApplicationUtils.isNull(portInfo.getUnit()));
			uniData.put(Constants.PORTKEY, ApplicationUtils.isNull(portInfo.getPort_key()));
			uniData.put(Constants.RELAYRCK, ApplicationUtils.isNull(portInfo.getRelayrck()));
			uniData.put(Constants.LOCATION, ApplicationUtils.isNull(portInfo.getLocation()));
			uniData.put(Constants.NODE_ID, ApplicationUtils.isNull(portInfo.getNode_id()));
			uniData.put(Constants.LOCATION_LAT, ApplicationUtils.isNull(dto.getLocation_lat()));
			uniData.put(Constants.LOCATION_LONG, ApplicationUtils.isNull(dto.getLocation_long()));
			uniData.put(Constants.ALIAS_CKT_ID, ApplicationUtils
					.isNull(dto.getCktid().replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING)));
			uniData.put(Constants.STATE, ApplicationUtils.isNull(dto.getState()));
			String spec = ApplicationUtils.isNull(dto.getSpec()) != null ? dto.getSpec() : "";
			uniData.put(Constants.SERVICE_MUX, Constants.SVCMUX.equalsIgnoreCase(spec) ? Constants.YES : Constants.NO);
			// Hardcoded/default values
			uniData.put(Constants.CKFMT, ApplicationUtils.isNull(dto.getCktfmt()));
			uniData.put(Constants.NO_OF_EVCS_OVCS_ALLOWED, dto.getNoOfEVCs_OVCsAllowed());
			uniData.put(Constants.AUTO_NEGOTIATE, ApplicationUtils.isNull(dto.getAutoNegotiate()));
			uniData.put(Constants.SUBSCRIBER_TYPE, ApplicationUtils.isNull(dto.getSubcriberType()));
			uniData.put(Constants.STATUS, ApplicationUtils.isNull(dto.getStatus()));
			uniData.put(Constants.BUNDLING, ApplicationUtils.isNull(dto.getBundling()));
			uniData.put(Constants.ALL_TO_1_BUNDLING, ApplicationUtils.isNull(dto.getAllTo1Bundling()));
			uniData.put(Constants.REQUESTING_AFFILIATE, ApplicationUtils.isNull(dto.getRequestingAffiliate()));
			uniData.put(Constants.FUNCTION_STATUS, ApplicationUtils.isNull(dto.getFunctionalStatus()));
			uniData.put(Constants.MCO, ApplicationUtils.isNull(dto.getMCO()));
			uniData.put(Constants.USER, ApplicationUtils.isNull(dto.getUser()));

			return neo4jClient.query(query).bindAll(uniData).fetch().first().isPresent();
		} catch (Exception e) {
			log.error("Failed to create UNIConnection for portId: {} - {}", portInfo.getPortId(), e.getMessage(), e);
			return false;
		}

	}

	/**
	 * Creates a cross-connect relationship in Neo4j between a UNI port and an NNI
	 * port.
	 *
	 * @param uniPort elementId of the UNI port
	 * @param nniPort elementId of the NNI port
	 * @return true if relationship was created successfully, false otherwise
	 */
	private boolean createCrossConnect(String uniPort, String nniPort) {
		try {

			log.info("uniport {} and nniport {}", uniPort, nniPort);
			// Create relationship in Neo4j
			String cypher = """
					MATCH (uni)
					WHERE elementId(uni) = $uniPort
					MATCH (nni)
					WHERE elementId(nni) = $nniPort
					CREATE (uni)-[:XCONNECTS_TO]->(nni)
					         """;

			neo4jClient.query(cypher).bind(uniPort).to(Constants.UNI_PORT).bind(nniPort).to(Constants.NNI_PORT).run();

			return true;
		} catch (Exception e) {
			log.error("Error creating cross-connect between {} and {}", uniPort, nniPort, e);
			return false;
		}
	}

	/**
	 * Fetches existing EVC details for a given service name from Neo4j.
	 *
	 * @param serviceName EVC service name
	 * @return map of EVC properties if found, otherwise null
	 */
	private Map<String, Object> fetchExistingEVCValues(String serviceName) {
		log.info("serviceName: {}", serviceName);
		String query = "MATCH (evc:EVCConnection {serviceName: $serviceName}) RETURN evc";

		return neo4jClient.query(query).bind(serviceName).to(Constants.SERVICE_NAME).fetchAs(Map.class)
				.mappedBy((typeSystem, record) -> {
					// Convert node properties into a Java Map
					return record.get("evc").asNode().asMap();
				}).one() // will return Optional<Map<String,Object>>
				.orElse(null); // if no node found, return null
	}

	/**
	 * Fetches available bandwidth and cTags for a given circuit from Neo4j.
	 *
	 * @param circuitName aliasCktId of the UNI connection
	 * @param session     Neo4j driver session
	 * @return Map with keys: availableBandwidth, allCTags
	 */
	/**
	 * Fetch available bandwidth and EVC details for given UNI.
	 */
	/**
	 * Fetch available bandwidth and return details for given UNI.
	 */
	private Map<String, Object> fetchBandwidthAndCTags(String circuit) {
		String aliasCktId = circuit.replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING).toUpperCase();
		log.info("circuit: {}, aliasCktId: {}", circuit, aliasCktId);
		String query = """
				MATCH (uni:UNIConnection {aliasCktId:$aliasCktId})
				OPTIONAL MATCH (uni)<-[a:AEND]-(evc:EVCConnection)
				OPTIONAL MATCH (uni)-[z:ZEND]->(evc2:EVCConnection)
				WITH uni,
					 collect(DISTINCT a.CTAG) + collect(DISTINCT z.CTAG) AS allCTags,
				       collect(evc.bandwidth) + collect(evc2.bandwidth) AS evcBandwidths,
				     uni.portBandwidth AS currentBandwidth
				RETURN currentBandwidth AS portBandwidth, evcBandwidths, allCTags
				""";

		Map<String, Object> result = new HashMap<>();

		// 1. Execute the query and store result
		Optional<Map<String, Object>> queryResult = neo4jClient.query(query).bind(aliasCktId).to(Constants.ALIAS_CKT_ID)
				.fetch().first();

		log.info("queryResult.." + queryResult);

		// 2. Process if present
		if (queryResult.isPresent()) {
			Map<String, Object> record = queryResult.get();
			String portBwStr = record.get(Constants.PORT_BANDWIDTH).toString();
			List<Object> evcBws = (List<Object>) record.get(Constants.INV_EVC_BANDWIDTHS);
			;
			double totalBandwidth = ApplicationUtils.normalizeBandwidth(portBwStr);
			log.info("Port Bandwidth: {} → normalized currentBandwidth: {}", portBwStr, totalBandwidth);
			double usedBw = evcBws.stream().map(Object::toString).mapToDouble(ApplicationUtils::normalizeBandwidth)
					.sum();
			double availableBw = totalBandwidth - usedBw;
			log.info("EVC Bandwidths: {}", evcBws);
			log.info("Used: {}, Available: {}", usedBw, availableBw);

			List<Object> cTags = (List<Object>) record.get(Constants.INV_ALL_CTAGS);
			log.info("CTAGs: {}", cTags);

			result.put(Constants.CIRCUIT_NAME, aliasCktId);
			result.put(Constants.INV_EVC_BANDWIDTHS, evcBws);
			result.put(Constants.INV_CURRENT_BANDWIDTHS, totalBandwidth);
			result.put(Constants.INV_USED_BANDWIDTH, usedBw);
			result.put(Constants.INV_AVAILABLE_BANDWIDTH, availableBw);
			result.put(Constants.INV_ALL_CTAGS, cTags);

		} else {
			log.warn("No UNIConnection found in Neo4j for aliasCktId={}", aliasCktId);
		}

		return result;

	}

	/**
	 * Fetch NNI and EVC details riding on given NNI and then find out the available
	 * bandwidth
	 * 
	 * @param payload
	 * @return
	 */
	public ResponseStatus checkNNIBandwidth(Map<String, Object> payload) {
		log.info("Inside checkNNIBandwidth()");
		ResponseStatus responseStatus;
		responseStatus = validateInputForCheckNNIBandwidth(payload);
		if (responseStatus != null)
			return responseStatus;
		String requestedBandwidth = payload.get(Constants.INV_REQUESTED_BANDWIDTH).toString();
		String circuitId = payload.get(Constants.INV_CIRCUIT_ID).toString();
		responseStatus = new ResponseStatus();
		String aliasCktId = circuitId.replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING)
				.toUpperCase();
		boolean exists = isNNIPresent(aliasCktId);
		if (!exists) {
			log.error("either NNI is not present or not active aliasCktId {}", aliasCktId);
			responseStatus.setCode(Integer.parseInt(ErrorCode.INV_NNI_NOT_FOUND.getCode()));
			responseStatus.setMessage(
					String.format("Given NNI Circuit Id %s is either not present or not active", circuitId));
			return responseStatus;
		}
		responseStatus = calculateBandwidth(aliasCktId, requestedBandwidth);
		log.info("validated bandwidth availability '{}'", responseStatus);
		return responseStatus;
	}

	/**
	 * Validates the input payload for checking NNI bandwidth availability.
	 * <p>
	 * This method ensures that the required fields are present in the request
	 * payload before proceeding with bandwidth checks:
	 * <ul>
	 * <li><b>circuitId</b> - Must be present and non-empty.</li>
	 * <li><b>requestedBandwidth</b> - Must be present and non-empty.</li>
	 * </ul>
	 * If the payload is {@code null}, or if any required field is missing/empty, a
	 * {@link ResponseStatus} object is returned with the corresponding
	 * {@link ErrorCode} and descriptive error message. <br>
	 * If validation passes, this method returns {@code null}.
	 * </p>
	 *
	 * @param payload a {@link Map} containing the request payload, expected to
	 *                include {@link Constants#INV_CIRCUIT_ID} and
	 *                {@link Constants#INV_REQUESTED_BANDWIDTH}.
	 * @return a {@link ResponseStatus} object containing validation error details,
	 *         or {@code null} if the input is valid.
	 *
	 * @see Constants#INV_CIRCUIT_ID
	 * @see Constants#INV_REQUESTED_BANDWIDTH
	 * @see ErrorCode
	 * @see ResponseStatus
	 */
	private ResponseStatus validateInputForCheckNNIBandwidth(Map<String, Object> payload) {
		if (payload == null) {
			log.info("Request Payload is null");
			return buildResponseStatus(ErrorCode.INV_REQUEST_PAYLOAD_EMPTY, null);
		}

		String circuitId = payload.getOrDefault(Constants.INV_CIRCUIT_ID, Constants.EMPTY_STRING).toString();
		String requestedBandwidth = payload.getOrDefault(Constants.INV_REQUESTED_BANDWIDTH, Constants.EMPTY_STRING)
				.toString();

		if (StringUtils.isEmpty(circuitId)) {
			log.error("Validation failed: 'Circuit Id can not be empty'");
			return buildResponseStatus(ErrorCode.INV_CIRCUIT_ID_NOT_FOUND, null);
		}
		if (StringUtils.isEmpty(requestedBandwidth)) {
			log.error("Validation failed: 'Requested Bandwidth can not be empty'");
			return buildResponseStatus(ErrorCode.INV_REQUESTED_BANDWIDTH_NOT_FOUND, null);
		}

		return null; // Validation passed
	}

	/**
	 * Builds a {@link ResponseStatus} object using the provided {@link ErrorCode}.
	 *
	 * @param errorCode the error code containing the response code and message
	 * @param data      the data Object
	 * @return a populated {@link ResponseStatus} instance
	 */
	public ResponseStatus buildResponseStatus(ErrorCode errorCode, Object data) {
		ResponseStatus status = new ResponseStatus();
		status.setCode(Integer.parseInt(errorCode.getCode()));
		status.setMessage(errorCode.getMessage());
		status.setData(data);
		return status;
	}

	/**
	 * calculate the occupied bandwidth and required bandwidth from total available
	 * bandwidth
	 * 
	 * @param aliasCktId
	 * @param requestedBandwidth
	 * @return
	 */
	private ResponseStatus calculateBandwidth(String aliasCktId, String requestedBandwidth) {
		log.info("inside calculateBandwidth()");
		ResponseStatus responseStatus = new ResponseStatus();
		try (Session session = driver.session()) {
			String bandwidthQuery = """
					MATCH (n:NNIConnection {aliasCktId: $aliasCktId}) WHERE n.deletedTimeStamp IS NULL
					OPTIONAL MATCH (n)-[ro1:RIDES_ON]->(r:ROUTE)-[ro2:RIDES_ON]->(evc:EVCConnection)
					WHERE ro1.deletedTimeStamp IS NULL AND ro2.deletedTimeStamp IS NULL
					AND r.deletedTimeStamp IS NULL AND evc.deletedTimeStamp IS NULL
					WITH n, n.bandwidth AS totalBandwidth, collect(evc.bandwidth) AS evcBandwidths
					RETURN totalBandwidth, evcBandwidths
					""";
			Result bandwidthResult = session.run(bandwidthQuery, Map.of(Constants.ALIAS_CKT_ID, aliasCktId));
			if (!bandwidthResult.hasNext()) {
				responseStatus.setCode(Integer.parseInt(ErrorCode.INV_NNI_NOT_FOUND.getCode()));// pure application code
																								// for this like
																								// USIL_NNI_NOT_FOUND -
																								// 1001
				responseStatus.setMessage(ErrorCode.INV_NNI_NOT_FOUND.getMessage());
				return responseStatus;
			}
			Record bandwidthRecord = bandwidthResult.next();
			String totalBandwidth = bandwidthRecord.get(Constants.INV_TOTAL_BANDWIDTH).toString();
			double totalNNIBandwidth = ApplicationUtils.normalizeBandwidth(totalBandwidth);
			double requiredNNIBandwidth = ApplicationUtils.normalizeBandwidth(requestedBandwidth);
			List<Object> evcBandwidthList = bandwidthRecord.get(Constants.INV_EVC_BANDWIDTHS).asList();
			double usedBandwidth = evcBandwidthList.stream().map(Object::toString)
					.mapToDouble(ApplicationUtils::normalizeBandwidth).sum();
			double availableBw = totalNNIBandwidth - usedBandwidth;
			responseStatus.setCode(Integer.parseInt(ErrorCode.SUCCESS.getCode()));
			responseStatus.setMessage(Constants.NNI_BANDWIDTH_CALCULATED_SUCCESSFULLY);
			Map<String, Object> data = new LinkedHashMap<>();
			data.put(Constants.INV_TOTAL_BANDWIDTH, totalNNIBandwidth);
			data.put(Constants.INV_USED_BANDWIDTH, usedBandwidth);
			data.put(Constants.INV_AVAILABLE_BANDWIDTH, availableBw);
			data.put(Constants.INV_REQUESTED_BANDWIDTH, requiredNNIBandwidth);
			data.put(Constants.INV_REQUESTED_BANDWIDTH_AVAILABLE, availableBw >= requiredNNIBandwidth);
			responseStatus.setData(data);
		} catch (Exception e) {
			responseStatus.setCode(500);
			responseStatus.setMessage(Constants.NNI_BANDWIDTH_ERROR + e.getMessage());
			log.error(responseStatus.getMessage(), e);
		}
		return responseStatus;
	}

	/**
	 * it will check that weather given NNI is present in neo4j DB
	 * 
	 * @param aliasCktId
	 * @return
	 */
	private boolean isNNIPresent(String aliasCktId) {
		String query = "MATCH (nni:NNIConnection{aliasCktId:$circuit}) WHERE nni.deletedTimeStamp IS NULL RETURN nni LIMIT 1";
		try (Session session = driver.session()) {
			Result result = session.run(query, Map.of(Constants.INV_CIRCUIT, aliasCktId));
			return result.hasNext();
		} catch (Exception e) {
			log.error("Error checking NNI existence for alias Ckt Id '{}': {}", aliasCktId, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Creates a new Route node in the database based on the given A-End and Z-End
	 * TID values, and establishes relationships to the specified NNI connections.
	 * <p>
	 * This method performs the following steps:
	 * <ol>
	 * <li>Validates the A-End and Z-End TID values.</li>
	 * <li>Generates a unique route name using a base prefix and a numeric
	 * suffix.</li>
	 * <li>Generates a new unique Route ID.</li>
	 * <li>Creates the Route node in the Neo4j database.</li>
	 * <li>Establishes RIDES_ON relationships from NNIConnection nodes to the
	 * Route.</li>
	 * <li>Returns a {@link ResponseStatus} containing route creation status and
	 * route data (if successful).</li>
	 * </ol>
	 * <p>
	 * If any step fails, an appropriate error response is returned.
	 *
	 * @param aEndTIDValue the TID value of the A-End (source) of the route
	 * @param zEndTIDValue the TID value of the Z-End (destination) of the route
	 * @param nniList      a list of NNI alias circuit IDs to associate with the
	 *                     route
	 * @return a {@link ResponseStatus} containing:
	 *         <ul>
	 *         <li>Success code and message with created route data on success</li>
	 *         <li>Error code and message if validation or any operation fails</li>
	 *         </ul>
	 */
	private ResponseStatus createRoute(String aEndTIDValue, String zEndTIDValue, List<String> nniList) {
		log.info("Inside createRoute: A-End='{}', Z-End='{}'", aEndTIDValue, zEndTIDValue);

		ResponseStatus responseStatus = validateAEndZendTIDValue(aEndTIDValue, zEndTIDValue);
		if (responseStatus != null) {
			return responseStatus;
		}

		Map<String, Object> routeMap;
		try (Session session = driver.session()) {
			String basePrefix = Constants.INV_ROUTE_STARTS_WITH + aEndTIDValue + Constants.INV_HYPHEN + zEndTIDValue
					+ Constants.INV_HYPHEN;
			int suffix = getNextAvailableSuffix(session, basePrefix);
			String route = basePrefix + suffix;

			log.debug("Generated new route name: '{}'", route);

			long routeId = generateNextRouteId(session);
			Record createdRouteRecord = createRouteNode(session, route, routeId);

			if (createdRouteRecord == null) {
				responseStatus = buildResponseStatus(ErrorCode.INV_FAILED_TO_CREATE_ROUTE, null);
				log.error("Route creation failed: {}", responseStatus);
				return responseStatus;
			}

			routeMap = createdRouteRecord.get(Constants.INV_RT).asMap();

			log.debug("Route created with Route_ID: '{}' and Route Name: '{}'", routeMap.get(Constants.INV_ROUTE_ID),
					routeMap.get(Constants.INV_ROUTE));
		} catch (Exception ex) {
			log.error("Failed to create Route node: Reason: '{}'", ex.getMessage());
			return buildResponseStatus(ErrorCode.INV_FAILED_TO_CREATE_ROUTE_EXCEPTION, null);
		}

		// Attempt to create relationships to NNI nodes
		if (nniList != null && !nniList.isEmpty()) {
			String routeId = (String) routeMap.get(Constants.INV_ROUTE_ID);
			responseStatus = createNNIRelationshipsToRoute(routeId, nniList);

			if (responseStatus != null)
				return responseStatus;
		}

		responseStatus = buildResponseStatus(ErrorCode.SUCCESS, routeMap);
		return responseStatus;
	}

	/**
	 * Creates RIDES_ON relationships from each NNIConnection node in the given list
	 * to the Route node identified by the provided route ID.
	 * <p>
	 * Each relationship is created only if both the NNIConnection node and the
	 * Route node exist. If a relationship fails to be created, it logs the error
	 * and continues processing the rest.
	 *
	 * @param routeId the Route_ID of the Route node
	 * @param nniList list of NNI alias circuit IDs to connect to the route
	 * @return ResponseStatus in case of any exception occurs otherwise it will be
	 *         null
	 */
	private ResponseStatus createNNIRelationshipsToRoute(String routeId, List<String> nniList) {
		ResponseStatus responseStatus = null;
		try (Session session = driver.session()) {
			for (String nniName : nniList) {
				String nniToRouteRel = "MATCH (nni:NNIConnection {aliasCktId: '" + nniName + "'}) "
						+ "MATCH (rt:ROUTE {Route_ID: '" + routeId + "'}) " + "CREATE (nni)-[rel:RIDES_ON]->(rt) "
						+ "RETURN rt";

				List<Record> result = session.run(nniToRouteRel).list();

				if (result == null || result.isEmpty()) {
					log.error("Failed to create Route Relationship to NNI: '{}'", nniName);
					throw new RelationshipNotCreatedException(
							ErrorCode.INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP.getMessage());
				} else {
					log.debug("Route relationship created to NNI '{}'", nniName);
				}
			}
		} catch (RelationshipNotCreatedException ex) {
			log.error("Inside RelationshipNotCreatedException catch handler.");
			throw ex;
		} catch (Exception e) {
			log.error("Failed to create NNI relationships: Reason: '{}'", e.getMessage());
			return buildResponseStatus(ErrorCode.INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP_EXCEPTION, null);
		}
		return responseStatus;
	}

	/**
	 * Determines the next available numeric suffix for a route name based on the
	 * given base prefix.
	 * <p>
	 * This method performs the following steps:
	 * <ul>
	 * <li>Queries the database for the latest route whose name starts with the
	 * specified base prefix.</li>
	 * <li>Extracts the numeric suffix from the route name using a regular
	 * expression.</li>
	 * <li>Returns the next suffix value by incrementing the highest existing
	 * one.</li>
	 * <li>If no matching route is found, returns {@code 1} as the starting
	 * suffix.</li>
	 * </ul>
	 *
	 * @param session    the active Neo4j session used to query existing routes
	 * @param basePrefix the base prefix (e.g., "RT-A-Z-") used to identify route
	 *                   name patterns
	 * @return the next available numeric suffix to be appended to the base route
	 *         name
	 */
	private int getNextAvailableSuffix(Session session, String basePrefix) {
		String suffixQuery = "MATCH (r:ROUTE) WHERE r.Route STARTS WITH $base RETURN r.Route As Route order by r.Route desc limit 1";
		try {
			Record record = session.run(suffixQuery, Map.of(Constants.INV_BASE_STRING, basePrefix)).single();
			String routeName = record.get(Constants.INV_ROUTE).asString();
			Matcher matcher = Pattern.compile(Constants.REGEX_ROUTE_ID_SUFFIX).matcher(routeName);

			if (matcher.matches()) {
				return Integer.parseInt(matcher.group(1)) + 1;
			}
		} catch (NoSuchRecordException e) {
			log.debug("No such route record with given base String '{}'", basePrefix);
			// No matching route found
		}

		return 1;
	}

	/**
	 * Generates the next available unique Route ID by querying the highest existing
	 * {@code Route_ID} from the database.
	 * <p>
	 * This method performs the following steps:
	 * <ul>
	 * <li>Queries the {@code ROUTE} nodes in Neo4j and retrieves the highest
	 * existing {@code Route_ID}.</li>
	 * <li>If no routes exist, it starts with {@code 1}.</li>
	 * <li>Otherwise, it increments the highest {@code Route_ID} by {@code 1} and
	 * returns it.</li>
	 * </ul>
	 *
	 * @param session the active Neo4j session used to execute the query
	 * @return the next available {@code long} Route_ID to assign to a new route
	 */
	private long generateNextRouteId(Session session) {
		String query = "MATCH (rt:ROUTE) RETURN toInteger(rt.Route_ID) AS Route ORDER BY Route DESC LIMIT 1";

		try {
			Record record = session.run(query).single();
			long routeId = record.get(Constants.INV_ROUTE).asLong();
			return routeId + 1;
		} catch (NoSuchRecordException e) {
			// No ROUTE nodes found — start from 1
			return 1;
		}
	}

	/**
	 * Creates a new {@code ROUTE} node in the Neo4j database.
	 * <p>
	 * The new node includes the specified {@code Route} name, {@code Route_ID}, and
	 * the current date. This method returns the created node's record if
	 * successful.
	 * <p>
	 * If the creation fails or no record is returned (which should not normally
	 * happen), the method returns {@code null}.
	 *
	 * @param session the active Neo4j session used to execute the query
	 * @param route   the full route name to assign (e.g., "RTE:ABC-XYZ-3")
	 * @param routeId the unique numeric Route_ID to assign to the node
	 * @return the created {@link Record} containing the new {@code ROUTE} node;
	 *         {@code null} if creation fails
	 */
	private Record createRouteNode(Session session, String route, long routeId) {
		String query = "CREATE (rt:ROUTE {Route: $route, Route_ID: $routeId, createdDate: date()}) RETURN rt";
		Map<String, Object> params = Map.of(Constants.INV_ROUTE_, route, Constants.ROUTE_ID, String.valueOf(routeId));

		try {
			return session.run(query, params).single();
		} catch (NoSuchRecordException e) {
			// Creation failed or something unexpected happened
			return null;
		}
	}

	/**
	 * Validates the provided A-End and Z-End TID values.
	 * <p>
	 * This method checks whether either the A-End or Z-End TID value is empty or
	 * null. If any of the values are invalid, it returns a {@link ResponseStatus}
	 * containing the appropriate error code and message.
	 * <p>
	 * If both values are valid (non-empty), the method returns {@code null}.
	 *
	 * @param aEndTIDValue the TID value representing the A-End (source) of the
	 *                     route
	 * @param zEndTIDValue the TID value representing the Z-End (destination) of the
	 *                     route
	 * @return a {@link ResponseStatus} with an error message if any TID value is
	 *         invalid; {@code null} if both are valid
	 */
	private ResponseStatus validateAEndZendTIDValue(String aEndTIDValue, String zEndTIDValue) {
		ResponseStatus responseStatus = null;
		if (StringUtils.isEmpty(aEndTIDValue)) {
			responseStatus = buildResponseStatus(ErrorCode.INV_AEND_TID_VALUE_IS_EMPTY, null);
		} else if (StringUtils.isEmpty(zEndTIDValue)) {
			responseStatus = buildResponseStatus(ErrorCode.INV_ZEND_TID_VALUE_IS_EMPTY, null);
		}
		return responseStatus;
	}

	/**
	 * Checks whether a given request payload causes a design or network impact by
	 * comparing its values against the existing {@code UNIConnection} node stored
	 * in Neo4j.
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Fetches the {@code UNIConnection} node from Neo4j using the provided
	 * {@code circuitId}.</li>
	 * <li>Iterates over the configured design-impacting fields.</li>
	 * <li>For each field:
	 * <ul>
	 * <li>Extracts the request value using a JSONPath expression.</li>
	 * <li>Retrieves the corresponding value from the Neo4j node.</li>
	 * <li>Compares the two values for equality.</li>
	 * </ul>
	 * </li>
	 * <li>If a mismatch or error occurs, the method flags both design and network
	 * impact as {@code "Y"}.</li>
	 * <li>If all values match, both flags are returned as {@code "N"}.</li>
	 * </ol>
	 *
	 * <p>
	 * <b>Return Values:</b>
	 * </p>
	 * <ul>
	 * <li>{@code designImpact = "Y"} and {@code networkImpact = "Y"} if:
	 * <ul>
	 * <li>No matching UNIConnection node is found, OR</li>
	 * <li>A mismatch is detected in at least one design-impacting field, OR</li>
	 * <li>An error occurs while evaluating a field.</li>
	 * </ul>
	 * </li>
	 * <li>{@code designImpact = "N"} and {@code networkImpact = "N"} if all fields
	 * match.</li>
	 * </ul>
	 *
	 * @param payload               the incoming request payload containing values
	 *                              to validate
	 * @param designImpactingFields list of fields (with JSONPath keys and DB keys)
	 *                              that drive impact checks
	 * @return an {@link ImpactCheckResponse} containing flags for design and
	 *         network impact, along with a descriptive message (e.g., success,
	 *         mismatch details, or failure reason)
	 */
	public ImpactCheckResponse checkUNIDesignAndNetworkImpact(Map<String, Object> payload,
			List<DesignImpactField> designImpactingFields) {

		String circuitId = (String) payload.get(Constants.INV_CIRCUIT_ID);
		String query = "MATCH (uniConnection:UNIConnection {cktid: $circuitId}) RETURN uniConnection LIMIT 1";

		// 🔹 Step 1: Query Neo4j for UNIConnection node
		Optional<Map<String, Object>> nodeOpt = neo4jClient.query(query).bind(circuitId).to(Constants.INV_CIRCUIT_ID)
				.fetchAs(Map.class).mappedBy((typeSystem, record) -> record.get(Constants.INV_UNICONNECTION).asMap())
				.one().map(result -> (Map<String, Object>) result);
		
		if (nodeOpt.isEmpty()) {
			// no node found
			return new ImpactCheckResponse(Constants.YES, Constants.YES, Constants.ERR_NO_UNI_CONNECTION);
		}

		Map<String, Object> nodeProps = nodeOpt.get();

		// 🔹 Step 2: Compare design impacting fields
		for (DesignImpactField field : designImpactingFields) {
			try {
		        Object valueObj = JsonPath.read(payload, field.getJsonPathKey());
		        String requestValue;

		        if (valueObj instanceof JSONArray) {
		            JSONArray arr = (JSONArray) valueObj;
		            requestValue = arr.isEmpty() ? null : arr.get(0).toString();
		        } else {
		            requestValue = valueObj != null ? valueObj.toString() : null;
		        }

				Object nodeValue = nodeProps.get(field.getDataObjectKey());
		        log.info("requestValue: {} nodeValue: {}", requestValue, nodeValue);

				if (!Objects.equals(requestValue, nodeValue)) {
					return new ImpactCheckResponse(Constants.YES, Constants.YES,
							String.format(Constants.ERR_FIELD_MISMATCH + " %s (req=%s, db=%s)",
									field.getDataObjectKey(), requestValue, nodeValue));
				}
			} catch (Exception e) {
				return new ImpactCheckResponse(Constants.YES, Constants.YES,
						Constants.ERR_READING_FIELD + field.getJsonPathKey() + ": " + e.getMessage());
			}
		}

		return new ImpactCheckResponse(Constants.NO, Constants.NO, Constants.SUCCESS_ALL_ATTRIBUTES_MATCH);
	}

	/**
	 * Creates or updates an {@code allCircuits} node in Neo4j with details from
	 * both {@link PortInfo} and {@link UNIConnectionDTO}.
	 * <p>
	 * This method constructs a dynamic Cypher query to persist circuit-level data.
	 * It maps all fields from the {@code UNIConnectionDTO} (service details,
	 * subscriber info, location fields, defaults) and from {@code PortInfo}
	 * (equipment/port details), then binds them as node properties.
	 * </p>
	 *
	 * <p>
	 * Specifically, it:
	 * </p>
	 * <ul>
	 * <li>Sets base properties such as {@code cktid}, {@code aliasCktId}, and
	 * {@code ST_Loscation_A} (mapped from location).</li>
	 * <li>Includes service, state, and subscriber details from the DTO.</li>
	 * <li>Persists location-related attributes (zip, latitude, longitude, address,
	 * state, city).</li>
	 * <li>Persists equipment and port-related attributes (bandwidth, port key,
	 * relay rack, CLLI, node, shelf, unit, etc.).</li>
	 * <li>Adds default/hardcoded properties such as {@code cktfmt},
	 * {@code noOfEVCs_OVCsAllowed}, {@code status}, {@code bundling}, {@code MCO},
	 * etc.</li>
	 * <li>Dynamically appends only non-null and non-empty values into the Cypher
	 * query.</li>
	 * <li>Executes the query against Neo4j and logs execution time.</li>
	 * </ul>
	 *
	 * @param portInfo the equipment/port-level metadata to be persisted in the node
	 * @param dto      the UNI connection DTO containing service, subscriber, and
	 *                 location details
	 * @return {@code true} if the node was successfully created/updated in Neo4j,
	 *         {@code false} otherwise (including any exceptions during execution)
	 */
	private boolean updateAllCircuitsDataWithUNI(PortInfo portInfo, UNIConnectionDTO dto) {
		log.info("Inside updateAllCircuitsData - ckt id is {}", dto.getCktid());

		try {
			StringBuilder queryBuilder = new StringBuilder();
			queryBuilder.append("CREATE (circuit:allCircuits) ").append("SET circuit.circuitName = $cktid, ")
					.append("circuit.aliasCktId = $aliasCktId, ").append("circuit.ST_Loscation_A = $location");

			Map<String, Object> params = new HashMap<>();

			// Base params

			params.put(Constants.CKT_ID, ApplicationUtils.isNull(dto.getCktid()));
			params.put(Constants.ALIAS_CKT_ID, ApplicationUtils
					.isNull(dto.getCktid().replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING)));
			params.put(Constants.LOCATION, ApplicationUtils.isNull(portInfo.getLocation()));

			// 🔹 Add all fields from UNIConnection
			params.put(Constants.SERVICE_TYPE, ApplicationUtils.isNull(dto.getServiceType()));
			params.put(Constants.SPEC, ApplicationUtils.isNull(dto.getSpec()));
			params.put(Constants.STATE, ApplicationUtils.isNull(dto.getState()));
			params.put(Constants.NETWORK_CHANNEL, ApplicationUtils.isNull(dto.getNetworkChannel()));
			params.put(Constants.NETWORK_CHANNEL_INTERFACE, ApplicationUtils.isNull(dto.getNetworkChannelInterface()));
			params.put(Constants.NETWORK_CHANNEL_INTERFACE_SEC,
					ApplicationUtils.isNull(dto.getNetworkChannelInterfaceSec()));
			params.put(Constants.SUBSCRIBER_NAME, ApplicationUtils.isNull(dto.getSubscriberName()));
			params.put(Constants.SUBSCRIBER_FULL_NAME, ApplicationUtils.isNull(dto.getSubscriberFullName()));
			params.put(Constants.SERVICE_MUX,
					Constants.SVCMUX.equalsIgnoreCase(dto.getSpec()) ? Constants.YES : Constants.NO);

			// Location fields
			params.put(Constants.LOCATION_ZIP, ApplicationUtils.isNull(dto.getLocation_zip()));
			params.put(Constants.LOCATION_LAT, ApplicationUtils.isNull(dto.getLocation_lat()));
			params.put(Constants.LOCATION_LONG, ApplicationUtils.isNull(dto.getLocation_long()));
			params.put(Constants.LOCATION_ADDRESS, ApplicationUtils.isNull(dto.getLocation_address()));
			params.put(Constants.LOCATION_STATE, ApplicationUtils.isNull(dto.getLocation_state()));
			params.put(Constants.LOCATION_CITY, ApplicationUtils.isNull(dto.getLocation_city()));

			// Equipment / port info
			params.put(Constants.PORT_BANDWIDTH, ApplicationUtils.isNull(portInfo.getPortBandwidth()));
			params.put(Constants.PORTKEY, ApplicationUtils.isNull(portInfo.getPort_key()));
			params.put(Constants.RELAYRCK, ApplicationUtils.isNull(portInfo.getRelayrck()));
			params.put(Constants.CLLI_LATA, ApplicationUtils.isNull(portInfo.getClli_lata()));
			params.put(Constants.NODE_ID, ApplicationUtils.isNull(portInfo.getNode_id()));
			params.put(Constants.LOCZ, ApplicationUtils.isNull(portInfo.getLocz()));
			params.put(Constants.LOCA, ApplicationUtils.isNull(portInfo.getLoca()));
			params.put(Constants.SHELF, ApplicationUtils.isNull(portInfo.getShelf()));
			params.put(Constants.UNIT, ApplicationUtils.isNull(portInfo.getUnit()));

			// Hardcoded / default values
			params.put(Constants.CKFMT, ApplicationUtils.isNull(dto.getCktfmt()));
			params.put(Constants.NO_OF_EVCS_OVCS_ALLOWED, dto.getNoOfEVCs_OVCsAllowed());
			params.put(Constants.AUTO_NEGOTIATE, ApplicationUtils.isNull(dto.getAutoNegotiate()));
			params.put(Constants.SUBSCRIBER_TYPE, ApplicationUtils.isNull(dto.getSubcriberType()));
			params.put(Constants.STATUS, ApplicationUtils.isNull(dto.getStatus()));
			params.put(Constants.BUNDLING, ApplicationUtils.isNull(dto.getBundling()));
			params.put(Constants.ALL_TO_1_BUNDLING, ApplicationUtils.isNull(dto.getAllTo1Bundling()));
			params.put(Constants.REQUESTING_AFFILIATE, ApplicationUtils.isNull(dto.getRequestingAffiliate()));
			params.put(Constants.FUNCTION_STATUS, ApplicationUtils.isNull(dto.getFunctionalStatus()));
			params.put(Constants.MCO, ApplicationUtils.isNull(dto.getMCO()));
			params.put(Constants.USER, ApplicationUtils.isNull(dto.getUser()));

			// Build dynamic query
			for (Map.Entry<String, Object> entry : params.entrySet()) {
				String key = entry.getKey();
				Object value = entry.getValue();

				if (value != null && !value.toString().isEmpty()) {
					queryBuilder.append(", circuit.").append(key).append(" = $").append(key);
				}
			}

			String finalQuery = queryBuilder.toString();
			log.info("updateAllCircuitQuery is {}", finalQuery);

			Instant start = Instant.now();
			boolean status = neo4jClient.query(finalQuery).bindAll(params).fetch().first().isPresent();
			Instant end = Instant.now();

			log.info("Time taken by query: {} ms", Duration.between(start, end).toMillis());
			return status;
		} catch (Exception e) {
			log.error("Error while updating allCircuit node for cktid {}: {}", dto.getCktid(), e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Creates an EVCConnection node in Neo4j from a provisioning payload and
	 * establishes relationships to the specified UNI connections. Assumes payload
	 * validation is already complete.
	 *
	 * @param payload The EVC provisioning payload containing service details and
	 *                UNI list
	 * @return The elementId of the newly created EVC connection, or null if
	 *         creation failed
	 */
	public String createEVCConnection(Map<String, Object> payload) {
		String serviceId = payload.get(Constants.INV_SERVICE_ID).toString();

		try {
			// 1. Determine EVC Service Type based on both A-end and Z-end UNIs
			List<Map<String, Object>> uniList = (List<Map<String, Object>>) payload.get(Constants.UNI_LIST);
			String aEndCircuitName = (String) uniList.get(0).get(Constants.CIRCUIT_NAME);
			String zEndCircuitName = (String) uniList.get(1).get(Constants.CIRCUIT_NAME);

			String aEndServiceType = getUNIServiceType(aEndCircuitName);
			String zEndServiceType = getUNIServiceType(zEndCircuitName);

			String evcServiceType = determineEVCServiceType(aEndServiceType, zEndServiceType);

			// 2. Get UNI Element IDs
			List<String> uniElementIds = new ArrayList<>();
			for (Map<String, Object> uni : uniList) {
				String circuitName = (String) uni.get(Constants.CIRCUIT_NAME);
				String elementId = findUNIElementIdByCircuitName(circuitName);
				uniElementIds.add(elementId);
			}

			// 3. Build property map and create EVC node
			Map<String, Object> evcData = buildEVCDataMap(payload, evcServiceType);

			// 4. Execute Cypher query to create EVCConnection node and return elementId
			String elementId = executeEVCCreateQueryAndReturnId(evcData);

			if (elementId == null) {
				log.error("Failed to create EVCConnection node for serviceId: {}", serviceId);
				return null;
			}

			// Execute Cypher query to create AllServices node and return elementId
			String allServicesElementId = executeAllServicesCreateQueryAndReturnId(evcData);

			if (allServicesElementId == null) {
				log.error("Failed to create AllServices node for serviceId: {}", serviceId);
				return null;
			}

			// 5. Create relationships to UNIs
			boolean relationshipsCreated = createEVCRelationships(serviceId, uniElementIds, uniList);

			if (!relationshipsCreated) {
				log.error("Failed to create relationships for EVC serviceId: {}", serviceId);
				// Note: The node was created but relationships failed. You might want to handle
				// this differently.
			}

			return elementId;

		} catch (Exception e) {
			log.error("Failed to create EVC connection for serviceId {}: {}", serviceId, e.getMessage(), e);
			throw e;
		}
	}

	/**
	 * Executes the EVC creation query and returns the elementId of the created node
	 */
	private String executeEVCCreateQueryAndReturnId(Map<String, Object> evcData) {
		String query = """
				CREATE (evc:EVCConnection {
				    serviceId: $serviceId,
				    name: $name,
				    serviceName: $serviceName,
				    ncCode: $ncCode,
				    deviceTypeIndicator: $deviceTypeIndicator,
				    bandwidth: $bandwidth,
				    aEndCktId: $aEndCktId,
				    zEndCktId: $zEndCktId,
				    serviceType: $serviceType,
				    requestingAffiliate: $requestingAffiliate,
				    MCO: $MCO,
				    aliasCktId: $aliasCktId,
				    createDate: $createDate,
				    lastUpdated: $lastUpdated
				})
				RETURN elementId(evc) AS elementId
				""";

		try {
			return neo4jClient.query(query).bindAll(evcData).fetchAs(String.class)
					.mappedBy((typeSystem, record) -> record.get(Constants.ELEMENT_ID).asString()).one().orElse(null);
		} catch (Exception e) {
			log.error("Failed to execute EVC creation query: {}", e.getMessage(), e);
			return null;
		}
	}

	private String determineEVCServiceType(String aEndServiceType, String zEndServiceType) {
		if (Constants.MEF_ENNI.equalsIgnoreCase(aEndServiceType)
				|| Constants.MEF_ENNI.equalsIgnoreCase(zEndServiceType)) {
			return Constants.MEF_OVC;
		} else {
			return Constants.MEF_EVC;
		}
	}

	/**
	 * Finds a UNI connection's elementId by its circuit name
	 */
	private String findUNIElementIdByCircuitName(String circuitName) {
		String normalizedCircuitId = circuitName.replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING)
				.toUpperCase();

		String query = """
				MATCH (uni:UNIConnection)
				WHERE uni.cktid = $circuitName OR uni.aliasCktId = $normalizedId
				RETURN elementId(uni) AS elementId
				LIMIT 1
				""";

		return neo4jClient.query(query).bind(circuitName).to(Constants.CIRCUIT_NAME).bind(normalizedCircuitId)
				.to(Constants.NORMALIZED_ID).fetchAs(String.class)
				.mappedBy((typeSystem, record) -> record.get(Constants.ELEMENT_ID).asString()).one()
				.orElseThrow(() -> new RuntimeException("UNI connection not found for circuit: " + circuitName));
	}

	/**
	 * Fetches the serviceType of a UNIConnection node from Neo4j
	 */
	private String getUNIServiceType(String circuitName) {
		String normalizedCircuitId = circuitName.replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING)
				.toUpperCase();

		String query = """
				MATCH (uni:UNIConnection)
				WHERE uni.cktid = $circuitName OR uni.aliasCktId = $normalizedId
				RETURN uni.serviceType AS serviceType
				LIMIT 1
				""";

		return neo4jClient.query(query).bind(circuitName).to(Constants.CIRCUIT_NAME).bind(normalizedCircuitId)
				.to(Constants.NORMALIZED_ID).fetchAs(String.class)
				.mappedBy((typeSystem, record) -> record.get(Constants.SERVICE_TYPE).asString()).one()
				.orElseThrow(() -> new RuntimeException("UNI connection not found for circuit: " + circuitName));
	}

	/**
	 * Builds the data map for EVC creation - only payload values + required
	 * hardcoded values
	 */
	/**
	 * Builds the data map for EVC creation - payload values + required hardcoded
	 * values + timestamps
	 */
	private Map<String, Object> buildEVCDataMap(Map<String, Object> payload, String evcServiceType) {
		Map<String, Object> evcData = new HashMap<>();
		List<Map<String, Object>> uniList = (List<Map<String, Object>>) payload.get(Constants.UNI_LIST);

		// Values from payload only
		evcData.put(Constants.INV_SERVICE_ID, Long.parseLong(payload.get(Constants.INV_SERVICE_ID).toString()));
		evcData.put(Constants.NAME, payload.get(Constants.SERVICE_NAME));
		evcData.put(Constants.SERVICE_NAME, payload.get(Constants.SERVICE_NAME));
		evcData.put(Constants.NETWORK_CHANNEL_CODE, payload.get(Constants.NETWORK_CHANNEL));
		evcData.put(Constants.DEVICE_TYPE_INDICATOR, payload.get(Constants.SERVICE_TYPE));

		// Handle bandwidth - parse integer from string if needed
		Object bandwidth = payload.get(Constants.BANDWIDTH);
		if (bandwidth instanceof String) {
			evcData.put(Constants.BANDWIDTH, Integer
					.parseInt(((String) bandwidth).replaceAll(Constants.REGEX_NON_DIGIT, Constants.EMPTY_STRING)));
		} else {
			evcData.put(Constants.BANDWIDTH, bandwidth);
		}

		// UNI circuit names from payload
		evcData.put(Constants.A_END_CKT_ID, uniList.get(0).get(Constants.CIRCUIT_NAME));
		evcData.put(Constants.Z_END_CKT_ID, uniList.get(1).get(Constants.CIRCUIT_NAME));

		// Determined service type
		evcData.put(Constants.SERVICE_TYPE, evcServiceType);

		// Required hardcoded values
		evcData.put(Constants.REQUESTING_AFFILIATE, Constants.BRIGHTSPEED);
		evcData.put(Constants.MCO, Constants.GNVLNCXAA01);

		// Generated values
		evcData.put(Constants.ALIAS_CKT_ID, payload.get(Constants.SERVICE_NAME).toString()
				.replaceAll(Constants.REGEX_NON_ALPHANUMERIC, Constants.EMPTY_STRING));

		// Timestamps
		evcData.put(Constants.CREATED_DATE, java.time.LocalDate.now().toString());
		evcData.put(Constants.LAST_UPDATED_DATE,
				java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ISO_INSTANT));

		return evcData;
	}

	/**
	 * Creates relationships between EVC and UNIs
	 */
	private boolean createEVCRelationships(String serviceId, List<String> uniElementIds,
			List<Map<String, Object>> uniList) {
		for (int i = 0; i < uniElementIds.size(); i++) {
			String relationshipType = (i == 0) ? Constants.AEND : Constants.ZEND;
			String cTag = (String) uniList.get(i).get(Constants.INV_C_TAG_START);

			String relationshipQuery = """
					MATCH (evc:EVCConnection {serviceId: $serviceId})
					MATCH (uni:UNIConnection)
					WHERE elementId(uni) = $uniElementId
					CREATE (evc)-[r:%s {CTAG: $cTag}]->(uni)
					RETURN type(r) AS relationshipType
					""".formatted(relationshipType);

			boolean relationshipCreated = neo4jClient.query(relationshipQuery).bind(Long.parseLong(serviceId))
					.to(Constants.INV_SERVICE_ID).bind(uniElementIds.get(i)).to(Constants.INV_UNI_ELEMENT_ID).bind(cTag)
					.to(Constants.INV_C_TAG).fetch().first().isPresent();

			if (!relationshipCreated) {
				log.error("Failed to create {} relationship for serviceId: {}", relationshipType, serviceId);
				return false;
			}
		}
		return true;

	}

	/**
	 * Assigns an Ethernet Virtual Connection (EVC) based on the provided request
	 * payload.
	 * <p>
	 * This method performs the following steps:
	 * <ul>
	 * <li>Extracts the UNI (User-Network Interface) list from the payload.</li>
	 * <li>Validates the extracted UNI list; returns a {@link ResponseStatus} error
	 * if invalid.</li>
	 * <li>Retrieves the A-End and Z-End circuit IDs from the UNI list.</li>
	 * <li>Attempts to resolve the associated TIDs (Terminal Identifiers) for both
	 * ends.</li>
	 * <li>If both TIDs are available, validates and attaches a route to the
	 * EVC.</li>
	 * <li>Returns the result of the route assignment as a {@link ResponseStatus}
	 * object.</li>
	 * </ul>
	 * 
	 * @param payload the input request payload containing EVC and UNI details.
	 *                Expected to include UNI connection information needed to
	 *                derive circuit IDs.
	 * @return a {@link ResponseStatus} indicating the result of the operation:
	 *         <ul>
	 *         <li><b>Success:</b> if the EVC is assigned and route is attached
	 *         successfully.</li>
	 *         <li><b>Error:</b>
	 *         <ul>
	 *         <li>400 if the UNI list is invalid.</li>
	 *         <li>500 if TIDs cannot be retrieved for both ends.</li>
	 *         <li>500 if route validation/attachment fails or an internal error
	 *         occurs.</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 */
	public ResponseStatus assignEVC(Map<String, Object> payload) {
		List<Map<String, Object>> uniList = extractUNIList(payload);

		log.info("payload: {}", payload);
		Map<String, Object> response = new HashMap<>();
		List<String> errors = new ArrayList<>();

		boolean mandatoryOk = validateMandatoryFields(payload, errors);
		boolean uniOk = validateUNIBandwidthAndCTags(payload, response, errors);

		log.info(mandatoryOk + "" + uniOk);
		if (!(mandatoryOk && uniOk)) {
			return buildResponseStatus(ErrorCode.INV_EVC_UN_ASSIGN_ERROR, errors);
		}

		boolean isUnassigned = unassignEVCConnection((String) payload.get(Constants.ALIAS_CKT_ID), errors);
		if (isUnassigned) {
			if (!isValidUNIList(uniList)) {
				return buildResponseStatus(ErrorCode.INV_INVALID_UNI_LIST, null);
			}

			String aEndCktId = getCircuitId(uniList, 0);
			String zEndCktId = getCircuitId(uniList, 1);
			log.info("=> assignEvc: START, aEndCktId={}, zEndCktId={}", aEndCktId, zEndCktId);

			List<String> tids = getEquipmentTIDs(aEndCktId, zEndCktId);
			if (tids.size() < 2) {
				log.error("Could not retrieve both TIDs for aEndCktId={} and zEndCktId={}", aEndCktId, zEndCktId);
				return buildResponseStatus(ErrorCode.INV_INVALID_UNI_LIST_EXCEPTION, null);
			}

			ResponseStatus routeResult = validateAndAttachRouteToEVC(tids, payload);
			if (routeResult == null) {
				return buildResponseStatus(ErrorCode.INTERNAL_SERVER_ERROR, routeResult);
			}

			return routeResult;
		} else
			return buildResponseStatus(ErrorCode.INV_EVC_UN_ASSIGN_ERROR, null);
	}

	/**
	 * Extracts the UNI (User Network Interface) list from the given request
	 * payload.
	 * <p>
	 * This method retrieves the value associated with {@link Constants#UNI_LIST}
	 * from the provided payload and casts it to a
	 * {@code List<Map<String, Object>>}.
	 * <p>
	 * Note: The method uses an unchecked cast, so if the payload does not contain
	 * the expected structure, a {@link ClassCastException} may occur at runtime.
	 *
	 * @param payload the request payload containing UNI-related information
	 * @return the UNI list extracted from the payload, or {@code null} if not
	 *         present
	 * @throws ClassCastException if the retrieved object is not of type
	 *                            {@code List<Map<String, Object>>}
	 */
	private List<Map<String, Object>> extractUNIList(Map<String, Object> payload) {
		return (List<Map<String, Object>>) payload.get(Constants.UNI_LIST);
	}

	/**
	 * Validates the given UNI (User Network Interface) list.
	 * <p>
	 * A UNI list is considered valid if:
	 * <ul>
	 * <li>It is not {@code null}, and</li>
	 * <li>It contains at least two UNI entries.</li>
	 * </ul>
	 *
	 * @param uniList the UNI list to validate, represented as a list of maps
	 * @return {@code true} if the list is valid, {@code false} otherwise
	 */
	private boolean isValidUNIList(List<Map<String, Object>> uniList) {
		return uniList != null && uniList.size() >= 2;
	}

	/**
	 * Retrieves the circuit ID from the UNI (User Network Interface) list at the
	 * specified index.
	 * <p>
	 * The circuit ID is extracted by reading the value associated with
	 * {@link Constants#CIRCUIT_NAME} from the UNI map at the given index.
	 *
	 * @param uniList the list of UNI entries, where each entry is a map containing
	 *                UNI attributes
	 * @param index   the position in the list from which to retrieve the circuit ID
	 * @return the circuit ID as a {@link String}, or {@code null} if the key is not
	 *         present
	 * @throws IndexOutOfBoundsException if the specified index is outside the list
	 *                                   bounds
	 * @throws ClassCastException        if the retrieved value is not a
	 *                                   {@link String}
	 */
	private String getCircuitId(List<Map<String, Object>> uniList, int index) {
		return (String) uniList.get(index).get(Constants.CIRCUIT_NAME);
	}

	/**
	 * Builds an error {@link ResponseStatus} object with the specified status code
	 * and error message.
	 * <p>
	 * This method is typically used to standardize error responses when validation
	 * fails or an internal error occurs.
	 *
	 * @param code    the error code to set in the response
	 * @param message the descriptive error message
	 * @return a {@link ResponseStatus} instance containing the provided code and
	 *         message
	 */
	private ResponseStatus buildErrorResponse(int code, String message) {
		ResponseStatus response = new ResponseStatus();
		response.setCode(code);
		response.setMessage(message);
		return response;
	}

	/**
	 * Validates the availability of routes between the given TIDs (Terminal
	 * Identifiers) and attempts to attach a route to the EVC (Ethernet Virtual
	 * Connection) based on the provided request payload.
	 * <p>
	 * Processing steps:
	 * <ul>
	 * <li>Logs the retrieved A-End and Z-End TIDs.</li>
	 * <li>Invokes {@code ethOrderSrvc.findRouteV1} to fetch available routes.</li>
	 * <li>Validates the response from the route service:
	 * <ul>
	 * <li>If no response or no data is returned, an error response is built with
	 * {@link ErrorCode#INV_NO_ROUTES_AVAILABLE}.</li>
	 * <li>Extracts the route list from the response data and validates it.</li>
	 * <li>If the route list is empty, an error response is returned.</li>
	 * </ul>
	 * </li>
	 * <li>If valid routes are found, delegates to {@code processRoutes} to continue
	 * route attachment.</li>
	 * </ul>
	 *
	 * @param tids    a list of TIDs, expected to contain exactly two values:
	 *                <ul>
	 *                <li>Index 0 → A-End TID</li>
	 *                <li>Index 1 → Z-End TID</li>
	 *                </ul>
	 * @param payload the original request payload containing EVC and UNI details
	 * @return a {@link ResponseStatus} indicating:
	 *         <ul>
	 *         <li>Success if a route was processed and attached successfully.</li>
	 *         <li>Error with {@link ErrorCode#INV_NO_ROUTES_AVAILABLE} if no routes
	 *         are available.</li>
	 *         </ul>
	 * @throws IndexOutOfBoundsException if the {@code tids} list has fewer than two
	 *                                   entries
	 * @throws ClassCastException        if the {@code result.getData()} structure
	 *                                   is not of the expected type
	 */
	private ResponseStatus validateAndAttachRouteToEVC(List<String> tids, Map<String, Object> payload) {
		log.info("=> assignEvc: Retrieved TIDs => AEndTID={}, ZEndTID={}", tids.get(0), tids.get(1));

		ResponseStatus result = ethOrderSrvc.findRouteV1(tids.get(0), tids.get(1),null);
		if (result == null || result.getData() == null) {
			return buildResponseStatus(ErrorCode.INV_NO_ROUTES_AVAILABLE, null);
		}

		Map<String, Object> dataMap = (Map<String, Object>) result.getData();
		log.info("Path from: {}", dataMap.get(Constants.PATH_FROM));
		log.info("Path to: {}", dataMap.get(Constants.PATH_TO));

		List<Map<String, Object>> routesList = (List<Map<String, Object>>) dataMap.get(Constants.ROUTE_LIST);
		if (routesList == null || routesList.isEmpty()) {
			return buildResponseStatus(ErrorCode.INV_NO_ROUTES_AVAILABLE, null);
		}

		return processRoutes(routesList, payload);
	}

	/**
	 * Processes a list of available routes and attempts to attach a valid route to
	 * an EVC (Ethernet Virtual Connection) based on the provided payload.
	 * <p>
	 * The method evaluates each candidate route and performs the following steps:
	 * <ul>
	 * <li>Extracts the list of route nodes from each route wrapper.</li>
	 * <li>Validates the route against the requested bandwidth using
	 * {@code validateRoute}, separating nodes into before- and after-MPLS
	 * segments.</li>
	 * <li>If valid, builds summary objects for both segments via
	 * {@code buildSummary}.</li>
	 * <li>Creates or updates a route entity using {@code createOrUpdateRoute}.</li>
	 * <li>Creates a new EVC connection from the payload via
	 * {@code createEVCConnection}.</li>
	 * <li>If an EVC connection is created successfully, attaches the route to it
	 * using {@code attachRouteToEVC}.</li>
	 * <li>Stops processing further routes once a successful attachment (response
	 * code = 0) is achieved.</li>
	 * </ul>
	 *
	 * @param routesList the list of candidate routes, where each route wrapper
	 *                   contains a list of route nodes
	 * @param payload    the request payload containing EVC details and requested
	 *                   bandwidth
	 * @return a {@link ResponseStatus} representing the result of processing:
	 *         <ul>
	 *         <li>Success response if a route is validated and attached to an
	 *         EVC.</li>
	 *         <li>{@code null} if no valid route could be processed
	 *         successfully.</li>
	 *         </ul>
	 *
	 * @throws ClassCastException if route structures in {@code routesList} or
	 *                            values in {@code payload} are not of the expected
	 *                            type
	 */
	private ResponseStatus processRoutes(List<Map<String, Object>> routesList, Map<String, Object> payload) {
		ResponseStatus response = null;
		String requestedBandwidth = (String) payload.get(Constants.BANDWIDTH);
		log.info("requestedBandwidth: {}", requestedBandwidth);

		List<Map<String, Object>> beforeMpls = new ArrayList<>();
		List<Map<String, Object>> afterMpls = new ArrayList<>();
		RouteInfo beforeSummary = null;
		RouteInfo afterSummary = null;

		outerLoop: for (int i = 0; i < routesList.size(); i++) {
			Map<String, Object> routeWrapper = routesList.get(i);
			List<Map<String, Object>> routeNodes = (List<Map<String, Object>>) routeWrapper.get(Constants.INV_ROUTE_);

			if (routeNodes == null || routeNodes.isEmpty())
				continue;

			boolean isValid = validateRoute(routeNodes, requestedBandwidth, beforeMpls, afterMpls, i);
			if (!isValid)
				continue;

			if (!beforeMpls.isEmpty())
				beforeSummary = buildSummary(beforeMpls);
			if (!afterMpls.isEmpty())
				afterSummary = buildSummary(afterMpls);

			Map<String, Object> route = createOrUpdateRoute(beforeSummary, afterSummary);
			String evcId = createEVCConnection(payload);
			if (evcId != null) {
				response = attachRouteToEVC(evcId, (String) route.get(Constants.BEFORE_MPLS_ROUTEID),
						(String) route.get(Constants.AFTER_MPLS_ROUTEID));
				if (response.getCode() == 0) {

					response = attachRouteToUNI(evcId, (String) route.get(Constants.BEFORE_MPLS_ROUTEID),
							(String) route.get(Constants.AFTER_MPLS_ROUTEID), payload);
					if (response.getCode() == 0) {
						response = attachRouteToNNI(evcId, (String) route.get(Constants.BEFORE_MPLS_ROUTEID),
								(String) route.get(Constants.AFTER_MPLS_ROUTEID), beforeSummary, afterSummary);

						if (response.getCode() == 0) {
							break outerLoop;
						}
					}
				}
			}
		}

		return response;
	}

	/**
	 * Attaches a given Route to an NNI connection by creating a relationship in
	 * Neo4j.
	 * <p>
	 * This method links the specified routes (before and after MPLS) to the
	 * corresponding NNI connections using the alias circuit IDs provided in the
	 * {@link RouteInfo} summaries.
	 * </p>
	 *
	 * @param evcId             the EVC identifier associated with the connection
	 * @param beforeMPLSRouteID the Route_ID of the route before MPLS
	 * @param afterMPLSRouteId  the Route_ID of the route after MPLS
	 * @param beforeSummary     route summary details before MPLS, containing alias
	 *                          circuit IDs
	 * @param afterSummary      route summary details after MPLS, containing alias
	 *                          circuit IDs
	 * @return {@link ResponseStatus} indicating success or failure of the attach
	 *         operation
	 */
	private ResponseStatus attachRouteToNNI(String evcId, String beforeMPLSRouteID, String afterMPLSRouteId,
			RouteInfo beforeSummary, RouteInfo afterSummary) {
		try (Session session = driver.session()) {
			// Handle before MPLS NNI relationships
			processNNIRelationships(session, beforeSummary.getAliasCktIds(), beforeMPLSRouteID, evcId);

			// Handle after MPLS NNI relationships
			processNNIRelationships(session, afterSummary.getAliasCktIds(), afterMPLSRouteId, evcId);

		} catch (RelationshipNotCreatedException ex) {
			log.error("Inside RelationshipNotCreatedException catch handler.");
			throw ex;
		} catch (Exception e) {
			log.error("Failed to create/update NNI relationships: Reason: '{}'", e.getMessage(), e);
			return buildResponseStatus(ErrorCode.INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP_EXCEPTION, null);
		}
		return buildResponseStatus(ErrorCode.SUCCESS, evcId);
	}

	/**
	 * Processes and creates/updates NNI-to-Route relationships for given NNI names.
	 *
	 * @param session  Neo4j session used for executing queries
	 * @param nniNames list of NNI alias circuit IDs
	 * @param routeId  route ID to attach the NNIs to
	 * @param evcId    the Ethernet Virtual Connection (EVC) ID
	 * @throws RelationshipNotCreatedException if a relationship cannot be created
	 *                                         or updated
	 */
	private void processNNIRelationships(Session session, List<String> nniNames, String routeId, String evcId) {
		String query = """
				MATCH (nni:NNIConnection {aliasCktId: $nniName})
				MATCH (rt:ROUTE {Route_ID: $routeId})
				MERGE (nni)-[rel:RIDES_ON]->(rt)
				SET rel.evcNames = apoc.coll.toSet(coalesce(rel.evcNames, []) + $evcId)
				RETURN rel
				""";

		for (String nniName : nniNames) {
			List<Record> result = session.run(query,
					Map.of(Constants.INV_NNI_NAME, nniName, Constants.ROUTE_ID, routeId, Constants.EVC_ID, evcId))
					.list();

			if (result == null || result.isEmpty()) {
				log.error("Failed to create/update Route Relationship to NNI: '{}'", nniName);
				throw new RelationshipNotCreatedException(
						ErrorCode.INV_FAILED_TO_CREATE_ROUTE_TO_NNI_RELATIONSHIP.getMessage());
			} else {
				log.debug("Route relationship created/updated to NNI '{}'", nniName);
			}
		}
	}

	/**
	 * Attaches UNI (User Network Interface) circuits to the specified routes
	 * (before and after) for a given EVC (Ethernet Virtual Connection).
	 * <p>
	 * This method retrieves the A-end and Z-end UNI circuit names from the provided
	 * payload, and attempts to attach them to the corresponding routes. If either
	 * attachment fails, an appropriate error response is returned. Otherwise, a
	 * success response is built.
	 * </p>
	 *
	 * @param evcElementId  the identifier of the EVC element
	 * @param beforeRouteId the route ID representing the "before MPLS" segment (can
	 *                      be null)
	 * @param afterRouteId  the route ID representing the "after MPLS" segment (can
	 *                      be null)
	 * @param payload       the payload containing UNI information, including the
	 *                      UNI list with circuit names for A-end and Z-end
	 * @return a {@link ResponseStatus} indicating success if UNIs were successfully
	 *         attached to their respective routes, or an error status otherwise
	 */
	private ResponseStatus attachRouteToUNI(String evcElementId, String beforeRouteId, String afterRouteId,
			Map<String, Object> payload) {

		Map<String, Object> result = new HashMap<>();
		try {

			List<Map<String, Object>> uniList = (List<Map<String, Object>>) payload.get(Constants.UNI_LIST);
			String aEndCircuitName = (String) uniList.get(0).get(Constants.CIRCUIT_NAME);
			String zEndCircuitName = (String) uniList.get(1).get(Constants.CIRCUIT_NAME);

			// Attach A-end UNI to BEFORE route
			if (beforeRouteId != null) {
				Boolean connectedA = attachUNIToRoute(aEndCircuitName, beforeRouteId, evcElementId);
				if (!connectedA)
					return buildResponseStatus(ErrorCode.INV_ROUTE_TO_UNI_ASSIGN_ERROR, beforeRouteId);
			}

			// Attach Z-end UNI to AFTER route
			if (afterRouteId != null) {
				Boolean connectedZ = attachUNIToRoute(zEndCircuitName, afterRouteId, evcElementId);
				if (!connectedZ)
					return buildResponseStatus(ErrorCode.INV_ROUTE_TO_UNI_ASSIGN_ERROR, afterRouteId);
			}

			result.put(Constants.EVC_ID, evcElementId);
			return buildResponseStatus(ErrorCode.SUCCESS, result);

		} catch (Exception e) {
			log.error("Exception while attaching routes to EVC {}: {}", evcElementId, e.getMessage(), e);
			return buildResponseStatus(ErrorCode.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	/**
	 * Helper method to attach a single UNI to a single ROUTE
	 */
	private Boolean attachUNIToRoute(String circuitName, String routeId, String evcElementId) {
		return neo4jClient.query("""
				MATCH (uni:UNIConnection {cktid: $circuitName})
				OPTIONAL MATCH (r:ROUTE {Route_ID: $routeId})
				MATCH (e:EVCConnection)
				WHERE elementId(e) = $evcId
				MERGE (uni)-[rel:CONNECTED_TO]->(r)
				SET rel.evcNames = apoc.coll.toSet(coalesce(rel.evcNames, []) + e.name)
				RETURN rel IS NOT NULL AS connected
				""").bind(circuitName).to(Constants.CIRCUIT_NAME).bind(routeId).to(Constants.ROUTE_ID)
				.bind(evcElementId).to(Constants.EVC_ID).fetchAs(Boolean.class).one().orElse(false);
	}

	/**
	 * Attaches BEFORE and AFTER routes to an existing EVC (Ethernet Virtual
	 * Connection) in the Neo4j database.
	 * <p>
	 * This method performs the following steps:
	 * <ul>
	 * <li>If a {@code beforeRouteId} is provided, it creates or merges a
	 * {@code RIDES_ON} relationship from the BEFORE route to the EVC.</li>
	 * <li>If an {@code afterRouteId} is provided, it creates or merges a
	 * {@code RIDES_ON} relationship from the AFTER route to the EVC.</li>
	 * <li>Logs successful link creation for both routes.</li>
	 * <li>Returns a {@link ResponseStatus} with success or error details.</li>
	 * </ul>
	 * <p>
	 * The method uses {@code MERGE} in Cypher to ensure that relationships are
	 * created if they do not already exist. If a relationship already exists, it
	 * will not throw an exception, but the {@code lastUpdated} timestamp will
	 * always be set to the current datetime on creation.
	 *
	 * @param evcElementId  the element ID of the EVC to which routes should be
	 *                      attached
	 * @param beforeRouteId the Route_ID of the BEFORE route; may be {@code null}
	 * @param afterRouteId  the Route_ID of the AFTER route; may be {@code null}
	 * @return a {@link ResponseStatus} indicating:
	 *         <ul>
	 *         <li>Success with the EVC ID if the routes were attached
	 *         successfully.</li>
	 *         <li>Error if an exception occurred during the Neo4j operation.</li>
	 *         </ul>
	 */
	private ResponseStatus attachRouteToEVC(String evcElementId, String beforeRouteId, String afterRouteId) {
		Map<String, String> result = new HashMap<>();
		try {
			if (beforeRouteId != null) {
				neo4jClient
						.query("MATCH (e:EVCConnection) where elementId(e) = $evcId" + " "
								+ "OPTIONAL MATCH (r:ROUTE {Route_ID: $routeId}) "
								+ "MERGE (r)-[:RIDES_ON {createdDate: date(), lastUpdated: datetime()}]->(e)")
						.bind(evcElementId).to(Constants.EVC_ID).bind(beforeRouteId).to(Constants.ROUTE_ID).run();

				log.info("Linked EVC {} to BEFORE Route {}", evcElementId, beforeRouteId);
			}

			if (afterRouteId != null) {
				neo4jClient
						.query("MATCH (e:EVCConnection) where elementId(e) = $evcId" + " "
								+ "OPTIONAL MATCH (r:ROUTE {Route_ID: $routeId})"
								+ "MERGE (r)-[:RIDES_ON {createdDate: date(), lastUpdated: datetime()}]->(e)")
						.bind(evcElementId).to(Constants.EVC_ID).bind(afterRouteId).to(Constants.ROUTE_ID).run();

				log.info("Linked EVC {} to AFTER Route {}", evcElementId, afterRouteId);
			}
			result.put(Constants.EVC_ID, evcElementId);

			return buildResponseStatus(ErrorCode.SUCCESS, result);

		} catch (Exception e) {
			log.error("Failed to attach routes to EVC {}: {}", evcElementId, e.getMessage(), e);
			return buildResponseStatus(ErrorCode.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	/**
	 * Validates a single route by checking each connection node for required
	 * attributes and bandwidth constraints, and separates nodes into before-MPLS
	 * and after-MPLS segments.
	 * <p>
	 * Processing steps:
	 * <ul>
	 * <li>Iterates over each connection node in the route.</li>
	 * <li>Logs the connection type and alias circuit ID for debugging.</li>
	 * <li>Tracks whether an MPLS connection has been encountered:
	 * <ul>
	 * <li>Nodes before the first MPLS connection are added to
	 * {@code beforeMpls}.</li>
	 * <li>Nodes after the first MPLS connection are added to
	 * {@code afterMpls}.</li>
	 * </ul>
	 * </li>
	 * <li>Validates that each node has a non-blank {@code aliasCktId}.</li>
	 * <li>Checks if the node satisfies the requested bandwidth using
	 * {@code checkBandwidth}.</li>
	 * <li>If any validation fails, the route is considered invalid and the method
	 * returns {@code false}.</li>
	 * </ul>
	 *
	 * @param routeNodes         the list of connection nodes in the route, each
	 *                           represented as a map
	 * @param requestedBandwidth the bandwidth required for this route
	 * @param beforeMpls         list to populate with nodes before the first MPLS
	 *                           connection
	 * @param afterMpls          list to populate with nodes after the first MPLS
	 *                           connection
	 * @param routeIndex         the index of the route in the routes list (used for
	 *                           logging)
	 * @return {@code true} if the route is valid, {@code false} otherwise
	 */
	private boolean validateRoute(List<Map<String, Object>> routeNodes, String requestedBandwidth,
			List<Map<String, Object>> beforeMpls, List<Map<String, Object>> afterMpls, int routeIndex) {
		boolean mplsSeen = false;

		for (Map<String, Object> conn : routeNodes) {
			String connType = conn.getOrDefault(Constants.CONNECTION_TYPE, Constants.EMPTY_STRING).toString();
			String aliasCktId = conn.get(Constants.ALIAS_CKT_ID) == null ? null
					: conn.get(Constants.ALIAS_CKT_ID).toString();

			log.info("connectionType: {}, aliasCktId: {}", connType, aliasCktId);

			if (Constants.MPLS.equalsIgnoreCase(connType)) {
				mplsSeen = true;
				continue;
			}

			if (aliasCktId == null || aliasCktId.isBlank()) {
				log.warn("Route index {}: missing aliasCktId, skipping route", routeIndex);
				return false;
			}

			if (!checkBandwidth(aliasCktId, requestedBandwidth, routeIndex)) {
				return false;
			}

			if (!mplsSeen)
				beforeMpls.add(conn);
			else
				afterMpls.add(conn);
		}

		return true;
	}

	/**
	 * Retrieves the Equipment TIDs (Terminal Identifiers) associated with the given
	 * pair of circuit IDs from the Neo4j database.
	 * <p>
	 * The method executes a Cypher query that:
	 * <ul>
	 * <li>Finds the equipment connected to the first UNIConnection identified by
	 * {@code circuitId1}.</li>
	 * <li>Finds the equipment connected to the second UNIConnection identified by
	 * {@code circuitId2}.</li>
	 * <li>Returns the distinct TIDs of the connected equipment for both
	 * circuits.</li>
	 * </ul>
	 * The results are returned as a list of strings, where the first element
	 * corresponds to {@code circuitId1} and the second element corresponds to
	 * {@code circuitId2}.
	 *
	 * @param circuitId1 the circuit ID of the first UNI connection
	 * @param circuitId2 the circuit ID of the second UNI connection
	 * @return a list of equipment TIDs associated with the provided circuit IDs;
	 *         may contain 0, 1, or 2 elements depending on database content
	 */
	private List<String> getEquipmentTIDs(String circuitId1, String circuitId2) {
		String query = """
				MATCH (u:UNIConnection {cktid: $circuitId1})-[:CONNECTED_TO*]-()-[:COMPONENT_OF*]->(eq1:Equipment)
				MATCH (u2:UNIConnection {cktid: $circuitId2})-[:CONNECTED_TO*]-()-[:COMPONENT_OF*]->(eq2:Equipment)
				RETURN DISTINCT eq1.TID AS tid1, eq2.TID AS tid2
				""";

		return neo4jClient.query(query).bind(circuitId1).to("circuitId1").bind(circuitId2).to("circuitId2").fetch()
				.all().stream().flatMap(record -> {
					List<String> tids = new ArrayList<>();
					if (record.get("tid1") != null) {
						tids.add(record.get("tid1").toString());
					}
					if (record.get("tid2") != null) {
						tids.add(record.get("tid2").toString());
					}
					return tids.stream();
				}).toList();
	}

	/**
	 * Builds a {@link RouteInfo} summary object from a list of connection maps.
	 * <p>
	 * This method extracts key information from the provided connections:
	 * <ul>
	 * <li>Collects all non-null {@code aliasCktId} values into a list.</li>
	 * <li>Retrieves the TID of the first A-End device in the connections list.</li>
	 * <li>Retrieves the TID of the last Z-End device in the connections list.</li>
	 * </ul>
	 * If the connections list is {@code null} or empty, the method returns
	 * {@code null}.
	 *
	 * @param connections a list of connection maps, each representing a network
	 *                    segment
	 * @return a {@link RouteInfo} object containing:
	 *         <ul>
	 *         <li>List of alias circuit IDs</li>
	 *         <li>First A-End TID</li>
	 *         <li>Last Z-End TID</li>
	 *         <li>{@code null} for any other summary fields not provided here</li>
	 *         </ul>
	 *         Returns {@code null} if the input list is {@code null} or empty.
	 */
	private RouteInfo buildSummary(List<Map<String, Object>> connections) {
		if (connections == null || connections.isEmpty()) {
			return null;
		}

		List<String> aliasCktIds = connections.stream().map(conn -> (String) conn.get(Constants.ALIAS_CKT_ID))
				.filter(Objects::nonNull).toList();

		String firstAEndTid = Optional.ofNullable(connections.get(0))
				.map(conn -> (Map<String, Object>) conn.get(Constants.AEND_INFO))
				.map(aEnd -> (Map<String, Object>) aEnd.get(Constants.DEVICE))
				.map(device -> (String) device.get(Constants.TID)).orElse(null);

		String lastZEndTid = Optional.ofNullable(connections.get(connections.size() - 1))
				.map(conn -> (Map<String, Object>) conn.get(Constants.ZEND_INFO))
				.map(zEnd -> (Map<String, Object>) zEnd.get(Constants.DEVICE))
				.map(device -> (String) device.get(Constants.TID)).orElse(null);

		return new RouteInfo(aliasCktIds, firstAEndTid, lastZEndTid, null);
	}

	/**
	 * Checks whether the given NNI (alias circuit ID) has sufficient bandwidth for
	 * the requested bandwidth value.
	 *
	 * @param aliasCktId         the alias circuit ID (NNI) to check
	 * @param requestedBandwidth the required bandwidth as provided in the payload
	 * @param routeIndex         the index of the current route (for logging
	 *                           purposes)
	 * @return true if the requested bandwidth is available, false otherwise
	 */
	private boolean checkBandwidth(String aliasCktId, String requestedBandwidth, int routeIndex) {
		try {
			// Call service to calculate available bandwidth
			ResponseStatus response = calculateBandwidth(aliasCktId, requestedBandwidth);

			if (response == null || response.getData() == null) {
				log.warn("Route index {}: bandwidth check returned null for aliasCktId={}", routeIndex, aliasCktId);
				return false;
			}

			// Extract the result
			Map<String, Object> data = (Map<String, Object>) response.getData();
			Boolean available = (Boolean) data.get(Constants.INV_REQUESTED_BANDWIDTH_AVAILABLE);

			boolean result = Boolean.TRUE.equals(available);
			log.info("Route index {}: aliasCktId={} bandwidthAvailable={}", routeIndex, aliasCktId, result);

			return result;

		} catch (Exception e) {
			log.warn("Route index {}: error checking bandwidth for {}: {}", routeIndex, aliasCktId, e.getMessage());
			return false;
		}
	}

	/**
	 * Creates or retrieves BEFORE and AFTER MPLS routes based on the provided
	 * summaries.
	 * <p>
	 * The method performs the following steps for both BEFORE and AFTER MPLS
	 * segments:
	 * <ul>
	 * <li>Checks if a {@link RouteInfo} summary is provided and has non-empty alias
	 * circuit IDs.</li>
	 * <li>Attempts to find an existing route using {@code findRoute}.</li>
	 * <li>If a route exists, stores its Route ID in the result map.</li>
	 * <li>If no route exists, creates a new route using {@code createRoute} and
	 * stores its Route ID.</li>
	 * <li>Logs success or failure of route creation and retrieval.</li>
	 * </ul>
	 * <p>
	 * This method ensures that each MPLS segment has a valid route associated with
	 * it, either by retrieving an existing route or creating a new one.
	 *
	 * @param beforeSummary summary information for the BEFORE MPLS segment; may be
	 *                      {@code null}
	 * @param afterSummary  summary information for the AFTER MPLS segment; may be
	 *                      {@code null}
	 * @return a {@link Map} containing:
	 *         <ul>
	 *         <li>{@link Constants#BEFORE_MPLS_ROUTEID} → Route ID of BEFORE MPLS
	 *         segment (or {@code null} if unavailable)</li>
	 *         <li>{@link Constants#AFTER_MPLS_ROUTEID} → Route ID of AFTER MPLS
	 *         segment (or {@code null} if unavailable)</li>
	 *         </ul>
	 */
	private Map<String, Object> createOrUpdateRoute(RouteInfo beforeSummary, RouteInfo afterSummary) {
		Map<String, Object> routeResult = new LinkedHashMap<>();

		// ---- BEFORE MPLS ----
		if (beforeSummary != null && !beforeSummary.getAliasCktIds().isEmpty()) {
			Optional<RouteInfo> beforeRoute = findRoute(beforeSummary.getAliasCktIds());
			if (beforeRoute.isPresent()) {
				log.debug("beforeRoute: {}", beforeRoute.toString());
				routeResult.put(Constants.BEFORE_MPLS_ROUTEID, beforeRoute.get().getRouteId());
			} else {
				String routeId = null;
				ResponseStatus responseStatus = createRoute(beforeSummary.getFirstAEndTID(),
						beforeSummary.getLastZEndTID(), beforeSummary.getAliasCktIds());
				if (responseStatus.getCode() == Integer.parseInt(ErrorCode.SUCCESS.getCode())) {
					Map<String, Object> data = (Map<String, Object>) responseStatus.getData();
					routeId = (String) data.get(Constants.INV_ROUTE_ID);
					log.info("Successfully created route with Route_ID={}", routeId);
				} else {
					log.error("Failed to create route: {}", responseStatus.getMessage());
				}
				routeResult.put(Constants.BEFORE_MPLS_ROUTEID, routeId);
			}
		}

		// ---- AFTER MPLS ----
		if (afterSummary != null && !afterSummary.getAliasCktIds().isEmpty()) {
			Optional<RouteInfo> afterRoute = findRoute(afterSummary.getAliasCktIds());
			if (afterRoute.isPresent()) {
				log.debug("afterRoute: {}", afterRoute.toString());
				routeResult.put(Constants.AFTER_MPLS_ROUTEID, afterRoute.get().getRouteId());
			} else {
				ResponseStatus responseStatus = createRoute(afterSummary.getFirstAEndTID(),
						afterSummary.getLastZEndTID(), afterSummary.getAliasCktIds());
				String routeId = null;
				if (responseStatus.getCode() == Integer.parseInt(ErrorCode.SUCCESS.getCode())) {
					Map<String, Object> data = (Map<String, Object>) responseStatus.getData();
					routeId = (String) data.get(Constants.INV_ROUTE_ID);
					log.info("Successfully created route with Route_ID={}", routeId);
				} else {
					log.error("Failed to create route: {}", responseStatus.getMessage());
				}
				routeResult.put(Constants.AFTER_MPLS_ROUTEID, routeId);
			}
		}

		return routeResult;
	}

	/**
	 * Attempts to find an existing route in the Neo4j database that exactly matches
	 * the provided alias circuit IDs (NNI connections).
	 * <p>
	 * The method executes a Cypher query that:
	 * <ul>
	 * <li>Matches ROUTE nodes that have {@code RIDES_ON} relationships from
	 * NNIConnection nodes.</li>
	 * <li>Collects all connected NNI alias circuit IDs for each route.</li>
	 * <li>Filters routes to only those whose connected NNIs contain all the
	 * provided {@code aliasCktIds} and no extra connections.</li>
	 * <li>Returns the first matching route along with the connected NNI list.</li>
	 * </ul>
	 *
	 * @param aliasCktIds the list of alias circuit IDs representing the NNI
	 *                    connections
	 * @return an {@link Optional} containing the matching {@link RouteInfo} if
	 *         found, or {@link Optional#empty()} if no matching route exists
	 */
	private Optional<RouteInfo> findRoute(List<String> aliasCktIds) {
		log.debug("inside fidnRoute");
		String cypher = """
				WITH $aliasCktIds AS nniIds
				MATCH (r:ROUTE)<-[:RIDES_ON]-(n:NNIConnection)
				WITH r, collect(DISTINCT n.aliasCktId) AS connectedNNIs, nniIds
				WHERE apoc.coll.containsAll(connectedNNIs, nniIds)
				  AND size(connectedNNIs) = size(nniIds)
				RETURN  r.Route_ID AS routeId, connectedNNIs LIMIT 1
				""";

		return neo4jClient.query(cypher).bind(aliasCktIds).to("aliasCktIds").fetchAs(RouteInfo.class)
				.mappedBy((typeSystem, record) -> {
					List<String> connectedNNIs = record.get("connectedNNIs").asList(Value::asString);
					String aEnd = connectedNNIs.get(0);
					String zEnd = connectedNNIs.get(connectedNNIs.size() - 1);
					String routeId = record.get(Constants.ROUTE_ID).asString();
					return new RouteInfo(connectedNNIs, aEnd, zEnd, routeId);
				}).one();
	}

	/**
	 * Unassigns and deletes an EVCConnection node by its aliasCktId. If the node
	 * doesn't exist, this method returns silently. If the node exists, it detaches
	 * and deletes the node along with all its relationships.
	 *
	 * @param evcAliasCktId The alias circuit ID of the EVC connection to unassign
	 * @return true if the node was found and deleted, false if no node was found
	 */
	public boolean unassignEVCConnection(String evcAliasCktId, List<String> errors) {
		log.info("Attempting to unassign EVC connection with aliasCktId: {}", evcAliasCktId);

		try {
			// Single query that handles both existence check and deletion
			String query = """
					MATCH (evc:EVCConnection {aliasCktId: $aliasCktId})
					DETACH DELETE evc
					RETURN count(evc) AS deletedCount
					""";

			neo4jClient.query(query).bind(evcAliasCktId).to(Constants.ALIAS_CKT_ID).fetchAs(Integer.class)
					.mappedBy((typeSystem, record) -> record.get("deletedCount").asInt()).one().orElse(0);

			return true;

		} catch (Exception e) {
			log.error("Error unassigning EVC connection with aliasCktId {}: {}", evcAliasCktId, e.getMessage(), e);
			if (errors != null) {
				errors.add("Error unassigning EVC connection with aliasCktId:" + evcAliasCktId);
			}
			return false;

		}
	}

	/**
	 * Validates an EVC (Ethernet Virtual Connection) order request.
	 *
	 * @param payload request payload
	 * @return validation result with errors, warnings, and impact flags
	 */
	public Map<String, Object> validateEVCOrderRequest(Map<String, Object> payload) {
		log.info("payload: {}", payload);
		Map<String, Object> response = new HashMap<>();
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		boolean mandatoryOk = validateMandatoryFields(payload, errors);
		boolean designOk = validateDesignImpact(payload, response, warnings);
		boolean uniOk = validateUNIBandwidthAndCTags(payload, response, errors);

		boolean isValid = mandatoryOk && designOk && uniOk;

		response.put("valid", isValid);
		response.put("errors", errors);
		response.put("warnings", warnings);
		return response;
	}

	/**
	 * Validates mandatory fields in the request payload.
	 *
	 * @return true if all mandatory fields are present, false otherwise
	 */
	private boolean validateMandatoryFields(Map<String, Object> payload, List<String> errors) {
		boolean valid = true;

		String evcOrderNumber = (String) payload.get(Constants.INV_EVC_ORDER_NUMBER);
		String serviceName = (String) payload.get(Constants.SERVICE_NAME);
		List<Map<String, Object>> uniList = (List<Map<String, Object>>) payload.get(Constants.UNI_LIST);

		if (evcOrderNumber == null || evcOrderNumber.isBlank()) {
			errors.add("Missing mandatory field: evcOrderNumber");
			valid = false;
		}
		if (serviceName == null || serviceName.isBlank()) {
			errors.add("Missing mandatory field: serviceName");
			valid = false;
		}
		if (uniList == null || uniList.isEmpty()) {
			errors.add("Missing mandatory field: uniList");
			valid = false;
		} else {
			for (Map<String, Object> uni : uniList) {
				String circuitName = (String) uni.get(Constants.CIRCUIT_NAME);
				String cTagStart = (String) uni.get(Constants.INV_C_TAG_START);
				if (circuitName == null || circuitName.isBlank()) {
					errors.add("Missing mandatory field: uniList.circuitName");
					valid = false;
				}
				if (cTagStart == null || cTagStart.isBlank()) {
					errors.add("Missing mandatory field: uniList.cTag_start");
					valid = false;
				}
			}
		}
		return valid;
	}

	/**
	 * Validates design and network impact by comparing against existing EVC values.
	 *
	 * @return true if no errors, false otherwise
	 */
	private boolean validateDesignImpact(Map<String, Object> payload, Map<String, Object> response,
			List<String> warnings) {
		String serviceName = (String) payload.get(Constants.SERVICE_NAME);
		String bandwidth = (String) payload.get(Constants.BANDWIDTH);

		Map<String, Object> dbValues = fetchExistingEVCValues(serviceName);
		log.info("dbValues.." + dbValues);

		if (dbValues == null) {
			// No existing EVC → Supp order scenario
			log.info("Design impact..yes");
			response.put(Constants.INV_DESIGN_IMPACT, Constants.YES);
			response.put(Constants.INV_NETWROK_IMPACT, Constants.YES);
		} else {
			if (!Objects.equals(bandwidth, dbValues.get(Constants.BANDWIDTH).toString())) {
				warnings.add("Design impact: Bandwidth change detected");
				response.put(Constants.INV_DESIGN_IMPACT, Constants.YES);
				response.put(Constants.INV_NETWROK_IMPACT, Constants.YES);
			} else {
				response.put(Constants.INV_DESIGN_IMPACT, Constants.NO);
				response.put(Constants.INV_NETWROK_IMPACT, Constants.NO);
			}
		}
		return true; // design impact never blocks request, only flags
	}

	/**
	 * Validates UNI bandwidth availability and CTAG uniqueness.
	 *
	 * @return true if all UNIs are valid, false otherwise
	 */
	private boolean validateUNIBandwidthAndCTags(Map<String, Object> payload, Map<String, Object> response,
			List<String> errors) {
		boolean valid = true;
		String bandwidth = (String) payload.get(Constants.BANDWIDTH);
		int requestedBandwidth = ((Number) ApplicationUtils.normalizeBandwidth(bandwidth)).intValue();

		List<Map<String, Object>> uniList = (List<Map<String, Object>>) payload.get(Constants.UNI_LIST);
		if (uniList != null) {
			List<Map<String, Object>> uniConnectionDetails = new ArrayList<>();
			for (Map<String, Object> uni : uniList) {
				String circuitName = uni.get(Constants.CIRCUIT_NAME).toString();
				String cTagStart = (String) uni.get(Constants.INV_C_TAG_START);

				// Fetch details from Neo4j
				Map<String, Object> details = fetchBandwidthAndCTags(circuitName);
				
				 if (details.isEmpty()) {
		                errors.add("No details found for circuit " + circuitName);
		                valid = false;
		                break; // or continue, depending on your logic
		            }

				// Check CTAG uniqueness
				@SuppressWarnings("unchecked")
				List<Object> allCTags = (List<Object>) details.get(Constants.INV_ALL_CTAGS);
				if (allCTags != null && allCTags.contains(cTagStart)) {
					errors.add("Duplicate cTag found: " + cTagStart + " for circuit " + circuitName);
					valid = false;
					break;
				}

				// Check bandwidth availability
				int availableBw = ((Number) details.get(Constants.INV_AVAILABLE_BANDWIDTH)).intValue();
				if ((availableBw - requestedBandwidth) < 0) {
					errors.add("No available bandwidth on circuit " + circuitName);
					valid = false;
					break;
				}
				uniConnectionDetails.add(details);
			}
			response.put(Constants.UNI_LIST, uniConnectionDetails);
		}
		return valid;
	}

	/**
	 * Executes the AllServices Node creation query and returns the elementId of the created node
	 */
	private String executeAllServicesCreateQueryAndReturnId(Map<String, Object> evcData) {
		String query = """
				CREATE (as:allServices {
				    serviceId: $serviceId,
				    name: $name,
				    serviceName: $serviceName,
				    ncCode: $ncCode,
				    deviceTypeIndicator: $deviceTypeIndicator,
				    bandwidth: $bandwidth,
				    aEndCktId: $aEndCktId,
				    zEndCktId: $zEndCktId,
				    serviceType: $serviceType,
				    requestingAffiliate: $requestingAffiliate,
				    MCO: $MCO,
				    aliasCktId: $aliasCktId,
				    createDate: $createDate,
				    lastUpdated: $lastUpdated
				})
				RETURN elementId(as) AS elementId
				""";

		try {
			return neo4jClient.query(query).bindAll(evcData).fetchAs(String.class)
					.mappedBy((typeSystem, record) -> record.get(Constants.ELEMENT_ID).asString()).one().orElse(null);
		} catch (Exception e) {
			log.error("Failed to execute AllServices Node creation query: {}", e.getMessage(), e);
			throw e;
		}
	}

    /**
     * Creates an XCONNECT relationship between EquipmentPorts connected to UNIConnection and NNIConnection
     * at the same location
     *
     * @param uniName cktid of the UNIConnection
     * @param nmiName cktid of the NNIConnection
     * @return Map with status and debug information
     */
    public Map<String, Object> createXConnect(String uniName, String nmiName) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Creating XCONNECT - UNI: {}, NMI: {}", uniName, nmiName);

            // Query to find EquipmentPorts connected via CONNECTED_TO relationships
            String cypher = """
            // Find UNIConnection and its location
            MATCH (uni:UNIConnection {cktid: $uniName})
            
            // Find EquipmentPort connected to UNIConnection via CONNECTED_TO
            MATCH (uni)-[:CONNECTED_TO]->(uniPort:EquipmentPort)
            WHERE uniPort.location = uni.location
            
            // Find NNIConnection
            MATCH (nmi:NNIConnection {cktid: $nmiName})
            
            // Find EquipmentPort connected to NNIConnection via CONNECTED_TO at same location
            MATCH (nmi)-[:CONNECTED_TO]->(nmiPort:EquipmentPort)
            WHERE nmiPort.location = uni.location
            
            // Check if XCONNECT relationship already exists
            OPTIONAL MATCH (uniPort)-[existing:XCONNECT]-(nmiPort)
            
            RETURN 
                uniPort.cktid as uniPortCktId,
                nmiPort.cktid as nmiPortCktId,
                uni.location as location,
                existing IS NOT NULL as relationshipExists
            """;

            Map<String, Object> resultMap = neo4jClient.query(cypher)
                    .bind(uniName).to("uniName")
                    .bind(nmiName).to("nmiName")
                    .fetch()
                    .one()
                    .orElse(null);

            if (resultMap == null) {
                String errorMsg = "Unable to find EquipmentPorts connected via CONNECTED_TO to UNI " + uniName + " and NMI " + nmiName + " at shared location";
                log.error(errorMsg);
                response.put("status", "error");
                response.put("message", errorMsg);
                return response;
            }

            String uniPortCktId = (String) resultMap.get("uniPortCktId");
            String nmiPortCktId = (String) resultMap.get("nmiPortCktId");
            String location = (String) resultMap.get("location");
            boolean relationshipExists = (Boolean) resultMap.get("relationshipExists");

            // Add debug information
            response.put("debug", Map.of(
                    "uniPortCktId", uniPortCktId,
                    "nmiPortCktId", nmiPortCktId,
                    "location", location,
                    "relationshipExists", relationshipExists
            ));

            if (relationshipExists) {
                String message = "XCONNECT relationship already exists between EquipmentPorts: " +
                        uniPortCktId + " and " + nmiPortCktId + " at location: " + location;
                log.info(message);
                response.put("status", "success");
                response.put("message", message);
                response.put("action", "no_action_taken");
                return response;
            }

            // Create XCONNECT relationship between EquipmentPorts
            String createCypher = """
            MATCH (uni:UNIConnection {cktid: $uniName})-[:CONNECTED_TO]->(uniPort:EquipmentPort {location: $location})
            MATCH (nmi:NNIConnection {cktid: $nmiName})-[:CONNECTED_TO]->(nmiPort:EquipmentPort {location: $location})
            WHERE uniPort.cktid = $uniPortCktId AND nmiPort.cktid = $nmiPortCktId
            CREATE (uniPort)-[:XCONNECT]->(nmiPort)
            """;

            neo4jClient.query(createCypher)
                    .bind(uniName).to("uniName")
                    .bind(nmiName).to("nmiName")
                    .bind(location).to("location")
                    .bind(uniPortCktId).to("uniPortCktId")
                    .bind(nmiPortCktId).to("nmiPortCktId")
                    .run();

            String successMessage = "Successfully created XCONNECT relationship between EquipmentPorts: " +
                    uniPortCktId + " and " + nmiPortCktId + " at location: " + location;
            log.info(successMessage);

            response.put("status", "success");
            response.put("message", successMessage);
            response.put("action", "relationship_created");
            return response;

        } catch (Exception e) {
            String errorMsg = "Error creating XCONNECT between UNI " + uniName + " and NMI " + nmiName + ": " + e.getMessage();
            log.error(errorMsg, e);
            response.put("status", "error");
            response.put("message", errorMsg);
            return response;
        }
    }
}
