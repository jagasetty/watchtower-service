package com.brightspeed.inventoryapiservice.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.lang.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.brightspeed.inventoryapiservice.config.ApiConfig;
import com.brightspeed.inventoryapiservice.dto.request.DeviceRequest;
import com.brightspeed.inventoryapiservice.dto.request.NniPortStagDto;
import com.brightspeed.inventoryapiservice.dto.request.PortRequest;
import com.brightspeed.inventoryapiservice.dto.request.VlanCheckRequest;
import com.brightspeed.inventoryapiservice.dto.response.ChangeOrderResponse;
import com.brightspeed.inventoryapiservice.dto.response.ResponseStatus;
import com.brightspeed.inventoryapiservice.exception.EquipmentNotFoundException;
import com.brightspeed.inventoryapiservice.repository.EquipmentRepository;
import com.brightspeed.inventoryapiservice.repository.InventorySearchRepository;
import com.brightspeed.inventoryapiservice.service.model.DeviceInfoResponse;
import com.brightspeed.inventoryapiservice.service.model.DeviceLocation;
import com.brightspeed.inventoryapiservice.service.model.Equipment;
import com.brightspeed.inventoryapiservice.service.model.EquipmentPort;
import com.brightspeed.inventoryapiservice.service.model.PortEquip;
import com.brightspeed.inventoryapiservice.util.ApplicationUtils;
import com.brightspeed.inventoryapiservice.util.ModelEnum;
import com.brightspeed.inventoryapiservice.util.ServiceClient;
import com.brightspeed.inventoryapiservice.util.StatesEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import static org.neo4j.driver.Values.parameters;

@Slf4j
@Service
public class EthernetOrderService {

	@Autowired
	InventorySearchRepository inventorySearchRepo;

	@Autowired
	ApiConfig apiConfig;
	
	@Autowired
	EquipmentRepository equipmentRepository;
	
	@Autowired
	Neo4jClient neo4jClient;
	
	@Autowired
    private ObjectMapper objectMapper;

	private ServiceClient serviceClient;
	private final OAuthService oAuthService;
	private final RestTemplate restTemplate;
	private final Driver driver;

	EthernetOrderService(Driver driver, ServiceClient serviceClient, OAuthService oAuthService,
			RestTemplate restTemplate) {
		this.driver = driver;
		this.serviceClient = serviceClient;
		this.oAuthService = oAuthService;
		this.restTemplate = restTemplate;
	}

	public ResponseStatus getEquipmentsByLocations(List<Map<String, Object>> assignedInventoryList) {
		log.info("=> EthernetOrderService:getEquipmentsByLocations: START");

		ResponseStatus responseStatus = new ResponseStatus();
		responseStatus.setCode(200);
		responseStatus.setMessage("Successfully found devices");

		Map<String, Object> equipmentDetailsMap = new HashMap<>();

		try {
			List<Map<String, Object>> equipmentDetailsList = new ArrayList<>();

			for (Map<String, Object> assignedInventoryBody : assignedInventoryList) {
				Map<String, Object> address = buildAddress(assignedInventoryBody);

				// Call the existing getEquipmentsByLocation method for each location
				ResponseStatus locationResponse = getEquipmentsByLocation(assignedInventoryBody);

				// If data is available in the locationResponse, process it
				if (locationResponse.getData() != null) {
					Map<String, Object> locationData = (Map<String, Object>) locationResponse.getData();
					if (locationData.containsKey("equipmentDetails")) {
						Map<String, Object> equipmentDetails = new LinkedHashMap<>();
						equipmentDetails.put("address", address);
						Map<String, Object> equipmentData = (Map<String, Object>) locationData.get("equipmentDetails");

						equipmentDetails.putAll(equipmentData);

						// Add the equipment details to the list for final response
						equipmentDetailsList.add(equipmentDetails);
					}
				}
			}
			equipmentDetailsMap.put("equipmentDetails", equipmentDetailsList);
			responseStatus.setData(equipmentDetailsMap);

		} catch (Exception ex) {
			log.error("Error in processing locations: '{}'", ex.getMessage());
			responseStatus.setCode(500);
			responseStatus.setMessage("Failed to process locations. Reason: " + ex.getMessage());
		}

		log.info("<= EthernetOrderService:getEquipmentsByLocations: END");
		return responseStatus;
	}

	// Helper method to build the address info
	private Map<String, Object> buildAddress(Map<String, Object> assignedInventoryBody) {
		Map<String, Object> address = new HashMap<>();
		address.put("addressLine", assignedInventoryBody.get("addressLine"));
		address.put("city", assignedInventoryBody.get("city"));
		address.put("state", assignedInventoryBody.get("state"));
		address.put("zip", assignedInventoryBody.get("zip"));
		address.put("country", assignedInventoryBody.get("country"));
		String speed = (String) assignedInventoryBody.get("capacity");
		if (StringUtils.isNotBlank(speed))
			address.put("capacity", speed);
		return address;
	}

	public ResponseStatus getEquipmentsByLocation(Map<String, Object> assignedInventoryBody) {
		log.info("=>EthernetOrderService:getEquipmentsByLocation: START");
		log.info("Requested location for device: {}", assignedInventoryBody.toString());
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		DeviceLocation location = new DeviceLocation();
		DeviceInfoResponse devInfoResp = new DeviceInfoResponse();

		List<String> clliList = new ArrayList<String>();
		Map<String, Object> devices = new HashMap<String, Object>();
		//devices.put("equipmentDetails", devInfoResp.getDeviceInfoMap(clliList));
		try {
			String country = (String) assignedInventoryBody.get("country");
			if (StringUtils.isNotBlank(country))
				country = country.toUpperCase();
			String tState = (String) assignedInventoryBody.get("state");
			if (StringUtils.isNotBlank(tState))
				tState = tState.toUpperCase();
			String city = (String) assignedInventoryBody.get("city");
			if (StringUtils.isNotBlank(city))
				city = city.toUpperCase();
			String address = (String) assignedInventoryBody.get("addressLine");
			if (StringUtils.isNotBlank(address))
				address = address.toUpperCase();
			String state = StatesEnum.getNameByAbbreviation(tState);
			if (StringUtils.isNotBlank(state)) {
				location.setState(state);
				location.setStateAbbr(tState);
			} else {
				location.setState(tState);
				location.setStateAbbr(StatesEnum.getAbbreviationByName(tState));
			}
			location.setAddressId((String) assignedInventoryBody.get("name"));
			location.setAddressLine(address);
			location.setCity(city);
			location.setZip((String) assignedInventoryBody.get("zip"));
			location.setCountry(StringUtils.equals("US", country) ? "USA" : country);
			String lat = (String) assignedInventoryBody.get("latitude");
			if (StringUtils.isNotBlank(lat)) {
				location.setLatitude(Double.parseDouble(lat));
			}
			location.setLatitudeStr(lat);
			String longi = (String) assignedInventoryBody.get("longitude");
			if (StringUtils.isNotBlank(lat)) {
				location.setLongitude(Double.parseDouble(longi));
			}
			location.setLongitudeStr(longi);
			location.setNetworkType((String) assignedInventoryBody.get("networkType"));
			location.setU_site_name((String) assignedInventoryBody.get("u_site_name"));
			location.setPhone((String) assignedInventoryBody.get("phone"));
			location.setType((String) assignedInventoryBody.get("type"));

			String speedStr = (String) assignedInventoryBody.get("capacity");
			int speed = 0;
			String suppBandwidth = "";
			if (StringUtils.isNotBlank(speedStr)) {
				speed = Integer.valueOf(speedStr);
				suppBandwidth = ApplicationUtils.supportedBandwidth(speed);
			}

			clliList = inventorySearchRepo.findClliByLocation(location);

//			log.info("Clli from location: '{}'", clliList.toString());
//
//			List<Map<String, Object>> qualificationDetails = new ArrayList<>();
//			Map<String, Object> qualification = new LinkedHashMap<>();
//
//			if(clliList == null || clliList.isEmpty()) {
//				devices.put("equipmentDetails", devInfoResp.getDeviceInfoMap(clliList));
//				response.setCode(HttpStatus.OK.value());
//				response.setMessage("Device not found at the location!");
//				response.setData(devices);
//				return response;
//			}
//			devInfoResp.setClliExists("Y");
			// Get device info from Neo4j

			List<String> neo4jClliList = new ArrayList<String>();
			neo4jClliList = getClliFromNeo4j(location);

			if (clliList == null || clliList.isEmpty()) {
				clliList.addAll(neo4jClliList);
			} else {
				for (String clli : neo4jClliList) {
					if (!clliList.contains(clli))
						clliList.add(clli);
				}
			}			
			Boolean isUNI = false;
			Object isUniObj = assignedInventoryBody.get("isUNI");
			if (isUniObj instanceof Boolean) {
			    isUNI = (Boolean) isUniObj;
			}
			
			String circuitName = null;
			Object circuitObj = assignedInventoryBody.get("circuitName");
			if (circuitObj instanceof String) {
			    circuitName = (String) circuitObj;
			}

			List<Map<String, Object>> devInfoList = getDevicesInfo(clliList, devInfoResp, location, suppBandwidth, isUNI, circuitName);

			devInfoResp.setEquipments(devInfoList);
			// Set all qualification flags
			// setQualificationFlags(clliList, devInfoList, qualification,devInfoResp);

			// Add devices under qualification
			// qualification.put("devices", devInfoList);
			// qualificationDetails.add(qualification);

			// Set the qualification details in the response data
			devices.put("equipmentDetails", devInfoResp.getDeviceInfoMap(clliList));

			if (devInfoList == null || devInfoList.isEmpty()) {
				log.error("Device not found. ");
				response.setCode(HttpStatus.OK.value());
				response.setMessage("Device not found.");
				response.setData(devices);
			} else {
				response.setCode(HttpStatus.OK.value());
				response.setMessage("Successfully found devices");
				response.setData(devices);
			}
		} catch (Exception ex) {
			log.error("Devices not found!. Reason - '{}'", ex.getMessage());
			response.setCode(HttpStatus.NO_CONTENT.value());
			response.setMessage("Device not found.");
			response.setData(devices);
		}

		log.info("<= EthernetOrderService:getEquipmentsByLocation: END");
		return response;
	}

	private List<String> getClliFromNeo4j(DeviceLocation location) {
		List<String> clliList = new ArrayList<String>();

		String clliQuery = "MATCH(eqmt:Equipment) WHERE eqmt.clli_address = '" + location.getAddressLine() + "'"
				+ " AND eqmt.clli_city = '" + location.getCity() + "' " + " AND (eqmt.clli_state = '"
				+ location.getState() + "' OR eqmt.clli_state = '" + location.getStateAbbr() + "')"
				+ " AND eqmt.clli_zip = '" + location.getZip() + "'" + " RETURN distinct eqmt.CLLI as clli";

		log.info("query: {}", clliQuery);

		try (Session session = driver.session();) {

			Transaction tx = session.beginTransaction();

			List<Record> clliRecords = tx.run(clliQuery).list();

			if (clliRecords != null && !clliRecords.isEmpty()) {
				for (Record clliRec : clliRecords) {
					String clli = clliRec.get("clli").asString();
					if (StringUtils.isNotBlank(clli))
						clliList.add(clli);
				}
			}
		} catch (Exception ex) {

		}
		return clliList;
	}

	// Method to set all qualification flags
	private void setQualificationFlags(List<String> clliList, List<Map<String, Object>> devInfoList,
			Map<String, Object> qualification, DeviceInfoResponse devResp) {

		// Set clliExists flag to "Y" if CLLI list is not empty
//		if (clliList != null && !clliList.isEmpty()) {
//			qualification.put("clliExists", "Y");
//		} else {
//			qualification.put("clliExists", "N");
//		}

		// Set nidExists flag to "Y" if there are devices in the list
//		if (devInfoList != null && !devInfoList.isEmpty()) {
//			qualification.put("nidExists", "Y");
//		} else {
//			qualification.put("nidExists", "N");
//		}

		// Check if device has ports and set capacityExists and portAvailability flags
		boolean portsAvailable = false;
		if (devInfoList != null && !devInfoList.isEmpty()) {
			for (Map<String, Object> device : devInfoList) {
				if (device != null && device.containsKey("availablePorts")) {
					List<?> ports = (List<?>) device.get("availablePorts");
					if (ports != null && !ports.isEmpty()) {
						portsAvailable = true;
						break;
					}
				}
			}
		}

		if (devInfoList != null && !devInfoList.isEmpty()) {
			for (Map<String, Object> device : devInfoList) {
				if (device != null && device.containsKey("networkConnections")) {
					List<?> nmis = (List<?>) device.get("networkConnections");
					if (nmis != null && !nmis.isEmpty()) {
						devResp.setNmiAvailable("Y");
						break;
					}
				}
			}
		}
		// Set capacityExists and portAvailability flags to "Y" if ports are available
		if (portsAvailable) {
			devResp.setPortAvailable("Y");
			devResp.setCapacityExists("Y");
		}
	}

	// Method to get devices for the CLLI
	List<Map<String, Object>> getDevicesInfo(List<String> clliList, DeviceInfoResponse devResp, DeviceLocation loc,
			String suppBw, Boolean isUNI, String circuitName) {

		List<Map<String, Object>> deviceInfoList = new ArrayList<Map<String, Object>>();

		Transaction tx = null;
		Session session = null;

		String clliStr = "";

		for (String clli : clliList) {
			if (StringUtils.isNotBlank(clliStr)) {
				clliStr += ",'" + clli + "'";
			} else {
				clliStr = "'" + clli + "'";
				devResp.setClliExists("Y");
			}
		}

//		String devQuery = "MATCH (equipment:allDevices) WHERE  equipment.deletedTimeStamp is null ";
//		if(StringUtils.isNotBlank(loc.getZip()))
//			devQuery += " AND equipment.clli_zip = '"+loc.getZip()+"' ";
//		if ((StringUtils.isNotBlank(loc.getState())) || (StringUtils.isNotBlank(loc.getStateAbbr()))) {
//			devQuery += "AND (";
//			if (StringUtils.isNotBlank(loc.getState()))
//				devQuery += " equipment.clli_state = '" + loc.getState() + "'";
//
//			if ((StringUtils.isNotBlank(loc.getState())) && (StringUtils.isNotBlank(loc.getStateAbbr()))) {
//				devQuery += " OR ";
//			}
//			if (StringUtils.isNotBlank(loc.getStateAbbr()))
//				devQuery += " equipment.clli_state = '" + loc.getStateAbbr() + "'";
//			devQuery += ")";
//		}
//		if(StringUtils.isNotBlank(loc.getCity()))
//			devQuery += " AND  equipment.clli_city = '" +  loc.getCity() + "'";
//		if(StringUtils.isNotBlank(loc.getAddressLine()))
//			devQuery += " AND equipment.clli_address = '" + loc.getAddressLine() +"'";
//		if(StringUtils.isNotBlank(loc.getLatitudeStr()))
//			devQuery += " AND equipment.clli_lat = '" + loc.getLatitudeStr() + "'";
//		if(StringUtils.isNotBlank(loc.getLongitudeStr()))
//			devQuery += " AND equipment.clli_long = '" + loc.getLongitudeStr() +"'";
//		devQuery += " RETURN equipment";

		String deviceQuery = "MATCH (equipment:allDevices) WHERE equipment.CLLI IN [" + clliStr + "] "
				//+ " AND equipment.deviceRoles contains 'NID' "
				/*+ " AND NOT equipment.provisionStatus in ['Deactivated','Pending Disconnect','Planned for Removal','Removed','Restricted']"*/ 
				//TBD: uncomment later
				+ " AND equipment.deletedTimeStamp is null ";
		if (Boolean.TRUE.equals(isUNI)) {
		    deviceQuery += "AND equipment.deviceRoles contains 'NID' ";
		}

		deviceQuery += "RETURN equipment";

		log.info("Executing query: {}", deviceQuery);

		try {
			session = driver.session();
			tx = session.beginTransaction();

			List<Record> deviceList = tx.run(deviceQuery).list();

			if (deviceList != null && !deviceList.isEmpty()) {
				devResp.setNidExists("Y");
				devResp.setClliExists("Y");
			}
//			int totalPortCount = 0;

			for (Record deviceRecord : deviceList) {
				Map<String, Object> devRec = new HashMap<String, Object>(deviceRecord.asMap());

				if (deviceRecord.containsKey("equipment") && !deviceRecord.get("equipment").isNull()) {
					devRec = deviceRecord.get("equipment").asMap();

					Map<String, Object> deviceInfo = new LinkedHashMap<>();

					deviceInfo.put("base_heci", (String) devRec.get("base_heci"));
					deviceInfo.put("clli", (String) devRec.get("CLLI"));
					deviceInfo.put("modelSeries", (String) devRec.get("deviceType"));
					deviceInfo.put("vendor", (String) devRec.get("vendor"));
					deviceInfo.put("model", (String) devRec.get("model"));
					deviceInfo.put("partNumber", (String) devRec.get("partNum"));
					deviceInfo.put("deviceName", (String) devRec.get("TID"));
					deviceInfo.put("partType", (String) devRec.get("partType"));
					deviceInfo.put("relayRack", (String) devRec.get("relayrck"));

					// Get ports for the current device
					List<Map<String, Object>> devicePorts = getFreePortsForDevice(suppBw,
							(String) devRec.get("base_heci"), (String) devRec.get("node_id"), circuitName, tx);

					List<Map<String, Object>> nwConns = getNmisOnDevice((String) devRec.get("base_heci"),
							(String) devRec.get("node_id"), tx);

					// Add ports to device info
					deviceInfo.put("availablePorts", devicePorts);
					deviceInfo.put("networkConnections", nwConns);

					if (nwConns != null && !nwConns.isEmpty()) {
						devResp.setNmiAvailable("Y");
					}
					// Only add device if it has at least one port
					if (devicePorts != null && !devicePorts.isEmpty()) {
						deviceInfoList.add(deviceInfo);
						devResp.setPortAvailable("Y");
						devResp.setCapacityExists("Y");
						// Add the number of ports to the total port count
//						totalPortCount += devicePorts.size();

						// Break the loop if we have reached 6 ports
//						if (totalPortCount >= 6) {
//							break;
//						}
					}
				}
			}
		} catch (Exception ex) {
			log.error("Error fetching device info: {}", ex.getMessage());
		} finally {
			if (session != null) {
				session.close();
			}
			if (tx != null) {
				tx.close();
			}
		}
		return deviceInfoList;
	}

	List<Map<String, Object>> getFreePortsForDevice(String suppBw, String base_heci, String node_id, String circuitName, Transaction tx) {
		List<Map<String, Object>> portsInfoList = new ArrayList<>();
		
		String aliasCktId = null;
	    if (StringUtils.isNotBlank(circuitName)) {
	        aliasCktId = circuitName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
	    }

//		Transaction tx = null;
//		Session session = null;

		String portQuery = "MATCH (n:Equipment{node_id:$node_id,base_heci: $base_heci})<-[:COMPONENT_OF*]-(ep:EquipmentPort) "
				+ "OPTIONAL MATCH (ep)<-[:CONNECTED_TO]-(c:NNIConnection|UNIConnection) WHERE n.deletedTimeStamp IS NULL AND NOT ep.portFunction = 'NF' AND (c IS NULL ";
		
		 // Allow port of the given circuit
	    if (StringUtils.isNotBlank(aliasCktId)) {
	        portQuery += " OR c.aliasCktId = $aliasCktId ";
	    }

	    portQuery += ")";
	    
		if (StringUtils.isNotBlank(suppBw))
			portQuery += " AND ep.bw in [" + suppBw + "]";
		portQuery += " RETURN ep.id AS port, ep.shelf AS shelfNumber, ep.slot AS cardNumber, ep.subslot as subCardNumber, "
				+ " ep.connector AS portType, ep.bw AS bandwidthSupported limit 6";

		log.info("Executing port query: {}", portQuery.replace("$base_heci", base_heci).replace("$node_id", node_id).replace("$aliasCktId", aliasCktId));

		try {
//			session = driver.session();
//			tx = session.beginTransaction();
			Map<String, Object> params = new HashMap<>();
			params.put("base_heci", base_heci);
			params.put("node_id", node_id);
			if (StringUtils.isNotBlank(aliasCktId)) {
	            params.put("aliasCktId", aliasCktId);
	        }

			List<Record> portList = tx.run(portQuery, params).list();

			for (Record portRecord : portList) {
				Map<String, Object> portRec = new HashMap<>(portRecord.asMap());

				Map<String, Object> portInfo = new LinkedHashMap<>();

				portInfo.put("shelfNumber",
						(portRec.get("shelfNumber") != null ? (String) portRec.get("shelfNumber") : "-"));
				portInfo.put("cardNumber",
						(portRec.get("cardNumber") != null ? (String) portRec.get("cardNumber") : "-"));
				portInfo.put("subCardNumber",
						(portRec.get("subCardNumber") != null ? (String) portRec.get("subCardNumber") : "-"));
				portInfo.put("port", (String) portRec.get("port"));
				portInfo.put("portType", (String) portRec.get("portType"));
				portInfo.put("supportedBandwidth",
						(portRec.get("bandwidthSupported") != null ? (String) portRec.get("bandwidthSupported") : "0"));
				portsInfoList.add(portInfo);

			}
		} catch (Exception ex) {
			log.error("Error fetching port info: {}", ex.getMessage());
		}
		return portsInfoList;
	}

	List<Map<String, Object>> getNmisOnDevice(String base_heci, String node_id, Transaction tx) {
		List<Map<String, Object>> connsList = new ArrayList<>();

		String nmiQuery = "MATCH (aDev:Equipment{node_id:$node_id,base_heci: $base_heci})<-[:COMPONENT_OF*]-(aPort:EquipmentPort)"
				+ "<-[:CONNECTED_TO]-(nni:NNIConnection)-[:CONNECTED_TO]->(zPort:EquipmentPort)-[:COMPONENT_OF*]->(zDev:Equipment) "
				+ " WHERE ((nni.A_port_key = aPort.portKey AND  nni.Z_port_key = zPort.portKey) "
				+ "    OR (nni.Z_port_key = aPort.portKey AND  nni.A_port_key = zPort.portKey)) "
				+ " AND aDev.deletedTimeStamp IS NULL AND nni.deletedTimeStamp IS NULL AND zDev.deletedTimeStamp IS NULL"
				+ " MATCH (aAllDev:allDevices{node_id:aDev.node_id})"
				+ " MATCH (zAllDev:allDevices{node_id:zDev.node_id})"
				+ " RETURN aDev.TID as aDevName, aPort.id AS aPort, aPort.shelf AS aShelfNumber, aPort.slot AS aCardNumber,"
				+ " aPort.subslot as aSubCardNumber, aPort.connector AS aPortType, aPort.bw as aBandwidth, nni.cktid as nmiName, "
				+ " zDev.TID as zDevName, zDev.relayrck as zRelayrck, zDev.model as zModel, zDev.vendor as zVendor, "
				+ " zPort.id AS zPort, zPort.shelf AS zShelfNumber, zPort.slot AS zCardNumber,"
				+ " zPort.subslot as zSubCardNumber, zPort.connector AS zPortType, zPort.bw as zBandwidth, "
				+ " aAllDev.deviceRoles as aDevRole, zAllDev.deviceRoles as zDevRole";

		log.info("Executing nmiQuery query: {}",
				nmiQuery.replace("$base_heci", base_heci).replace("$node_id", node_id));

		try {
//			session = driver.session();
//			tx = session.beginTransaction();
			Map<String, Object> params = new HashMap<>();
			params.put("base_heci", base_heci);
			params.put("node_id", node_id);

			List<Record> portList = tx.run(nmiQuery, params).list();

			for (Record portRecord : portList) {
				Map<String, Object> portRec = new HashMap<>(portRecord.asMap());
				Map<String, Object> nmi = new LinkedHashMap<>();
				Map<String, Object> aPortInfo = new LinkedHashMap<>();
				Map<String, Object> zPortInfo = new LinkedHashMap<>();

				String nmiName = (String) portRec.get("nmiName");
				aPortInfo.put("deviceName", (String) portRec.get("aDevName"));
				aPortInfo.put("deviceRole", (String) portRec.get("aDevRole"));
				aPortInfo.put("shelf",
						(portRec.get("aShelfNumber") != null ? (String) portRec.get("aShelfNumber") : "-"));
				aPortInfo.put("card", (portRec.get("aCardNumber") != null ? (String) portRec.get("aCardNumber") : "-"));
				aPortInfo.put("subCard",
						(portRec.get("aSubCardNumber") != null ? (String) portRec.get("aSubCardNumber") : "-"));
				aPortInfo.put("port", (String) portRec.get("aPort"));
				aPortInfo.put("portType", (String) portRec.get("aPortType"));
				aPortInfo.put("bandwidth", (String) portRec.get("aBandwidth"));

				zPortInfo.put("deviceName", (String) portRec.get("zDevName"));
				zPortInfo.put("relayRack", (String) portRec.get("zRelayrck"));
				zPortInfo.put("model", (String) portRec.get("zModel"));
				zPortInfo.put("vendor", (String) portRec.get("zVendor"));
				zPortInfo.put("deviceRole", (String) portRec.get("zDevRole"));
				zPortInfo.put("shelf",
						(portRec.get("zShelfNumber") != null ? (String) portRec.get("zShelfNumber") : "-"));
				zPortInfo.put("card", (portRec.get("zCardNumber") != null ? (String) portRec.get("zCardNumber") : "-"));
				zPortInfo.put("subCard",
						(portRec.get("zSubCardNumber") != null ? (String) portRec.get("zSubCardNumber") : "-"));
				zPortInfo.put("port", (String) portRec.get("zPort"));
				zPortInfo.put("portType", (String) portRec.get("zPortType"));
				zPortInfo.put("bandwidth", (String) portRec.get("zBandwidth"));

				nmi.put("connectionName", nmiName.replace(" ", ""));
				nmi.put("aEnd", aPortInfo);
				nmi.put("zEnd", zPortInfo);

				connsList.add(nmi);

			}
		} catch (Exception ex) {
			log.error("Error fetching Connection info: {}", ex.getMessage());
		}

		return connsList;
	}

	public ResponseStatus getEVCDesign(String evcName) {
		log.info("=>getEVCDesign of evc:  '{}'", evcName);
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to retrieved EVC design for: " + evcName);
		Map<String, Object> result = new HashMap<String, Object>();
		String aliasCktId = evcName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		// 1. Get EVC Details basic details.
		String evcQuery = "MATCH (evc:EVCConnection)  " + " WHERE (evc.aliasCktId = '" + aliasCktId
				+ "' OR evc.serviceName = '" + evcName + "') and  evc.deletedTimeStamp IS NULL with evc "
				+ " return evc.ncCode as ncCode, evc.name as evcName";

		log.info("Query to get EVC type details " + evcQuery);

		Transaction tx = null;
		Session session = null;
		try {
			session = driver.session();
			tx = session.beginTransaction();

			List<Record> evcInfoList = tx.run(evcQuery).list();
			if (evcInfoList == null || evcInfoList.isEmpty()) {
				log.error("No EVC circuit found with EVC name: " + evcName);
				// response.setMessage("No Circuits found on SCID:" + scid);
				response.setCode(204);
				response.setMessage("No EVC circuit found with EVC name: " + evcName);
				return response;
			}

			Record evcRec = evcInfoList.get(0);
			String ncCode = evcRec.get("ncCode").asString();

			if (StringUtils.equals("VLM-", ncCode)) {
				response = getEVPLANDesign(evcName, tx);
			} else if (StringUtils.equals("VLP-", ncCode)) {
				response = getElineDesign(evcName, tx);
			} else if (StringUtils.equals("VLC-", ncCode)) {
				// TBD
				response = getElineDesign(evcName, tx);
			}

		} catch (Exception ex) {
			log.error("Failed to get EVC design: Caught Exception: '{}'", ex.getMessage());
			response.setCode(500);
			response.setMessage("Failed to get EVC design for: " + evcName);

		} finally {
			if (session != null) {
				session.close();
			}
			if (tx != null) {
				tx.close();
			}
		}
		return response;
	}

	public ResponseStatus getElineDesign(String circuit,Transaction tx) {
		log.info("=>getElineDesign for evc:  '{}'", circuit);
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to retrieved EVC design for: " + circuit);

		Map<String, Object> evcDesign = new HashMap<String, Object>();
		String evcName = circuit.toUpperCase();

		Map<String, Object> result = new HashMap<String, Object>();
		Map<String, Object> evcConnection = new HashMap<String, Object>();
		String uniNames = "";
		// 1. Get EVC Details basic details.
		String evcQuery = "MATCH (evc:EVCConnection)  " + " WHERE (evc.aliasCktId = '" + evcName
				+ "' OR evc.serviceName = '" + evcName + "') and  evc.deletedTimeStamp IS NULL with evc "
				+ " MATCH  (evc)-[ar:AEND]->(aEndCkt:UNIConnection)-[r1:CONNECTED_TO]->(aPort:EquipmentPort)-[r2:COMPONENT_OF*]->(aDev:Equipment)"
				+ " WHERE evc.deletedTimeStamp IS NULL AND ar.deletedTimeStamp IS NULL AND aEndCkt.deletedTimeStamp IS NULL "
				+ " AND aPort.deletedTimeStamp IS NULL AND aDev.deletedTimeStamp IS NULL "
				+ " AND r1.deletedTimeStamp is null AND ALL(rel in r2 WHERE rel.deletedTimeStamp IS NULL) "
				+ " MATCH  (evc)<-[zr:ZEND]-(zEndCkt:UNIConnection)-[r3:CONNECTED_TO]->(zPort:EquipmentPort)-[r4:COMPONENT_OF*]->(zDev:Equipment)"
				+ " WHERE evc.deletedTimeStamp IS NULL AND zr.deletedTimeStamp IS NULL "
				+ " AND zEndCkt.deletedTimeStamp IS NULL AND zPort.deletedTimeStamp IS NULL "
				+ " AND zDev.deletedTimeStamp IS NULL "
				+ " AND r3.deletedTimeStamp is null AND ALL(rel in r4 WHERE rel.deletedTimeStamp IS NULL) "
				+ " MATCH (aDevice:allDevices) WHERE aDevice.TID = aDev.TID "
				+ " MATCH (zDevice:allDevices) WHERE zDevice.TID = zDev.TID "
				+ " return evc as EvcInfo, aEndCkt as aEndCircuit, aPort as aPort, aDevice as aDevice, zEndCkt as zEndCircuit, zPort as zPort, "
				+ " zDevice as zDevice, ar.STAG as aCktStag, ar.CTAG as aCktCtag, ar.evcNci as aNci, ar.evcBandwidth as aevcBandwidth, ar.classOfService as aclassOfService, "
				+ " zr.STAG as zCktStag, zr.CTAG as zCktCtag, zr.evcNci as zNci, zr.evcBandwidth as zevcBandwidth, zr.classOfService as zclassOfService";

		log.info("Query to get EVC basic details " + evcQuery);

		//Transaction tx = null;
		//Session session = null;

		try {
			//session = driver.session();
			//tx = session.beginTransaction();

			List<Record> evcInfoList = tx.run(evcQuery).list();
			if (evcInfoList == null || evcInfoList.isEmpty()) {
				log.error("No EVC circuit found with EVC name: " + evcName);
				// response.setMessage("No Circuits found on SCID:" + scid);
				response.setCode(204);
				response.setMessage("No EVC circuit found with EVC name: " + circuit);
				return response;
			}
			Record evcRec = evcInfoList.get(0);
			Map<String, Object> evcInfo = evcRec.get("EvcInfo").asMap();

			Map<String, Object> aEndCircuit = new HashMap<>(evcRec.get("aEndCircuit").asMap());
			String aUniType = (String)aEndCircuit.get("serviceType");
			aEndCircuit.put("evcNci", evcRec.get("aNci").asString());
			aEndCircuit.put("portBasedRateLimited", true);
			aEndCircuit.put("evcBandwidth", evcRec.get("aevcBandwidth").asString());
			aEndCircuit.put("classOfService", evcRec.get("aclassOfService").asString());
			String aStatus = (String)aEndCircuit.get("status");
			String aCktId = (String)aEndCircuit.get("cktid");
			if((StringUtils.equalsIgnoreCase(aStatus, "In Service")) || (StringUtils.equalsIgnoreCase(aStatus, "Pending Disconnect"))
					|| (StringUtils.equalsIgnoreCase(aStatus, "Inservice")) ) {
				aEndCircuit.put("isNewUni", false);
			}else {
				aEndCircuit.put("isNewUni", true);
			}
			boolean isNewUni = isNewUni(aCktId,tx);
			//aEndCircuit.put("isNewUni", isNewUni);

			Map<String, Object> aPort = new HashMap<>(convertedPort(evcRec.get("aPort").asMap()));
			aPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) aPort.get("bw"),true,true));
			//Map<String, Object> aDevice = new HashMap<>(evcRec.get("aDevice").asMap());
			Map<String, Object> aDevice = new HashMap<String,Object>();
			if(StringUtils.equals(aUniType, "MEF UNI"))
				aDevice = convertDevice(evcRec.get("aDevice").asMap(),true);
			else
				aDevice = convertDevice(evcRec.get("aDevice").asMap(),false);
			//String model = (String)aDevice.get("model");
			//aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
			Map<String, Object> zEndCircuit = new HashMap<>(evcRec.get("zEndCircuit").asMap());
			String zUniType = (String)zEndCircuit.get("serviceType");
			zEndCircuit.put("evcNci", evcRec.get("zNci").asString());
			zEndCircuit.put("portBasedRateLimited", true);
			zEndCircuit.put("evcBandwidth", evcRec.get("zevcBandwidth").asString());
			zEndCircuit.put("classOfService", evcRec.get("zclassOfService").asString());
			String zCktId = (String)zEndCircuit.get("cktid");
			String zStatus = (String)aEndCircuit.get("status");
			if((StringUtils.equalsIgnoreCase(zStatus, "In Service")) || (StringUtils.equalsIgnoreCase(zStatus, "Pending Disconnect"))
					|| (StringUtils.equalsIgnoreCase(zStatus, "Inservice")) ) {
				zEndCircuit.put("isNewUni", false);
			}else {
				zEndCircuit.put("isNewUni", true);
			}
			//zEndCircuit.put("isNewUni", isNewUni(zCktId,tx));

			uniNames = "'"+(String)aEndCircuit.get("cktid")+"','"+(String)zEndCircuit.get("cktid")+"'";

			Map<String, Object> zPort = new HashMap<>(convertedPort(evcRec.get("zPort").asMap()));
			zPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) zPort.get("bw"),true,true));
			//Map<String, Object> zDevice = new HashMap<>(evcRec.get("zDevice").asMap());
			Map<String, Object> zDevice = new HashMap<String,Object>();
			if(StringUtils.equals(zUniType, "MEF UNI"))
				zDevice = convertDevice(evcRec.get("zDevice").asMap(),true);
			else
				zDevice = convertDevice(evcRec.get("zDevice").asMap(),false);
			//model = (String)zDevice.get("model");
			//zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
			String aCktStag = evcRec.get("aCktStag").asString();
			String aCktCtag = "";
			if(StringUtils.isNotBlank(evcRec.get("aCktCtag").asString()))
				aCktCtag = evcRec.get("aCktCtag").asString();
			String zCktStag = evcRec.get("zCktStag").asString();
			String zCktCtag = "";
			if(StringUtils.isNotBlank(evcRec.get("zCktCtag").asString()))
				zCktCtag = evcRec.get("zCktCtag").asString();

			result.put("evcCircuit", evcInfo);
			result.put("aEndCircuit", aEndCircuit);
			result.put("aPort", aPort);
			result.put("aDevice", aDevice);
			result.put("zEndCircuit", zEndCircuit);
			result.put("zPort", zPort);
			result.put("zDevice", zDevice);
			result.put("aCktStag", aCktStag);
			result.put("aCktCtag", aCktCtag);
			result.put("zCktStag", zCktStag);
			result.put("zCktCtag", zCktCtag);

			result = convertToLower(result);
			evcConnection.put("evcConnection", result);

			String sTag = "0";
			if(StringUtils.isNotBlank(aCktStag)) {
				sTag = aCktCtag;
			}else if(StringUtils.isNotBlank(zCktStag)) {
				sTag = zCktCtag;
			}
			List<Map<String, Object>> connInfoTemp = getEvcRoute(evcName, aCktCtag,aCktStag,zCktCtag,zCktStag, aDevice, zDevice, tx);
			List<String> nmis = getNMIs(uniNames,tx);
			List<Map<String, Object>> connInfo = new ArrayList<Map<String,Object>>();

			for (Map<String,Object> conn : connInfoTemp){
				String nmiName = (String)conn.get("nniName");
				if(nmis.contains(nmiName)) {
					conn.put("connectionType", "NMI");
				}
				connInfo.add(conn);
			}
 			evcConnection.put("evcRoute", connInfo);
			response.setData(evcConnection);

			response.setCode(200);
			response.setMessage("Successfully retrieved EVC design for: " + circuit);
			log.info("EVC details '{}", evcConnection.toString());
		} catch (ServiceUnavailableException ex) {
			log.error("Service unavailable: '{}'", ex.getMessage());
			response.setCode(404);
			response.setMessage("Service unavailable while fetching EVC design.");
		} catch (Exception ex) {
			log.error("Failed to get EVC design: Caught Exception: '{}'", ex.getMessage());
			response.setCode(500);
			response.setMessage("Failed to get EVC design for: " + circuit);
		}
		return response;
	}

	private boolean isNewUni(String circuitName, Transaction tx) {
		boolean isNewUni = false;;

		String uniQuery = "MATCH (uni:UNIConnection{cktid:'"+circuitName+"'})-[:AEND|ZEND|VLAND]-(evc:EVCConnection) "
				+ " RETURN count(evc) as evcCount";
		int evcCount = tx.run(uniQuery).single().get("evcCount").asInt();
		if(evcCount <= 1)
			isNewUni = true;
		return isNewUni;
	}
	private List<Map<String, Object>> getEvcRoute(String evcName, String aCktCtag, String aCktStag,
			String zCktCtag,String zCktStag, Map<String, Object> aDevice,
			Map<String, Object> zDevice, Transaction tx) {

		List<Map<String, Object>> connInfo = new ArrayList<Map<String, Object>>();

		try {
			// 1. Get routes
			String aDeviceName = (String) aDevice.get("name");
			String zDeviceName = (String) zDevice.get("name");

			log.info("getEvcRoute for EVC - '{}' from aEnd: {} to zEnd: {}", evcName, aDeviceName, zDeviceName);

			String routeQuery = "MATCH (evc:EVCConnection)<-[r:RIDES_ON]-(rt:ROUTE) " + "WHERE (evc.aliasCktId = '"
					+ evcName + "' OR evc.serviceName = '" + evcName + "') and evc.deletedTimeStamp IS NULL "
					+ "AND r.deletedTimeStamp IS NULL AND rt.deletedTimeStamp IS NULL " + "RETURN rt.Route as route";
			log.info("Query to get the evcRoute :" + routeQuery);
			List<Record> routeList = tx.run(routeQuery).list();
			if (routeList == null || routeList.isEmpty()) {
				log.error("Service route NOT found for the EVC/OVC : " + evcName);

				return connInfo;
			}
			Record routeRec1 = routeList.get(0);
			String aEndRoute = routeRec1.get("route").asString();

			String rtDevName = endDeviceFromRoute(aEndRoute);
			String zEndRoute = null;
			if (StringUtils.equals(rtDevName, zDeviceName)) {
				zEndRoute = aEndRoute;
				aEndRoute = null;
			}

			String otherRoute = null;
			Record routeRec2 = null;

			if (routeList.size() > 1) {
				routeRec2 = routeList.get(1);
				otherRoute = routeRec2.get("route").asString();

				rtDevName = endDeviceFromRoute(otherRoute);

				if (StringUtils.equals(rtDevName, aDeviceName)) {
					aEndRoute = otherRoute;
				} else if (StringUtils.equals(rtDevName, zDeviceName)) {
					zEndRoute = otherRoute;
				}
			}

			List<Record> aRtList = new ArrayList<Record>();
			List<Record> zRtList = new ArrayList<Record>();
			if (StringUtils.isNotBlank(aEndRoute)) {
				aRtList = getRouteRecords(aEndRoute, tx);
			}
			if (StringUtils.isNotBlank(zEndRoute)) {
				zRtList = getRouteRecords(zEndRoute, tx);
			}

			connInfo = getRouteConnections(aRtList, aCktCtag,aCktStag,zCktCtag,zCktStag, aDevice, zRtList, zDevice);

		} catch (Exception ex) {
			log.error("Caught Exception: '{}'", ex.getMessage());
		}
		return connInfo;
	}

	private List<String> getNMIs(String uniNames, Transaction tx){
		List<String> nmis = new ArrayList<>();

		String nmiQuery = "MATCH (uni:UNIConnection)-[ur:CONNECTED_TO]->(up:EquipmentPort)-[xr:XCONNECT]"
				+ "->(np:EquipmentPort)<-[nr:CONNECTED_TO]-(nmi:NNIConnection)"
				+ " WHERE uni.cktid in ["+uniNames+"] return nmi.cktid as nmi";
		try {
			log.info("NMI Query :" + nmiQuery);
			List<Record> nmiList = tx.run(nmiQuery).list();

			for( Record nmiRec : nmiList) {
				String nmi = nmiRec.get("nmi").asString();
				nmis.add(nmi);
			}
		}catch (Exception ex) {
			log.error("Caught Exception while getting NMIs");
		}
		return nmis;
	}
	private List<Map<String, Object>> getRouteConnections(List<Record> aEndrt, String aCktCtag, String aCktStag,
			String zCktCtag,String zCktStag, Map<String, Object> aForEndDevice,
			List<Record> zEndrt, Map<String, Object> zForEndDevice) {
		log.info("==>getRouteConnections");
		List<Map<String, Object>> connList = new ArrayList<Map<String, Object>>();

		String aDeviceName = (String) aForEndDevice.get("name");
		String zDeviceName = (String) zForEndDevice.get("name");

		String aNextNode = aDeviceName;
		String zNextNode = zDeviceName;
		//Map<String, Object> aEndMplsDevice = new HashMap<String, Object>(aForEndDevice);
		Map<String, Object> aEndMplsDevice = convertDevice(aForEndDevice,false);
		//String mplsmodel = (String)aEndMplsDevice.get("model");
		//aEndMplsDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(mplsmodel));

		List<Record> tRecList = new ArrayList<Record>();
		int connCount = 1;

		Map<String, Object> evcMPLSConn = new HashMap<String, Object>();
		// When No routes available and both end devices are not same. Assuming both are
		// on MPLS network

		if (((aEndrt == null) || aEndrt.isEmpty()) && ((zEndrt == null) || zEndrt.isEmpty())) {
			if (StringUtils.equals(aNextNode, zNextNode) == false) {
				log.info("NO aEnd & zEnd routes. Create MPLS segment connection between '{} and {}", aNextNode,
						zNextNode);

				evcMPLSConn.put("connection", connCount);
				evcMPLSConn.put("connectionType", "MPLS");
				Map<String, Object> aEndDevice = new HashMap<String, Object>();
				//Map<String, Object> aDevice = new HashMap<String, Object>(aForEndDevice);
				Map<String, Object> aDevice = convertDevice(aForEndDevice,false);
				//String model = (String)aDevice.get("model");
				//aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));

				aEndDevice.put("device", aDevice);
				evcMPLSConn.put("aEndInfo", aEndDevice);

				Map<String, Object> zEndDevice = new HashMap<String, Object>();
				//Map<String, Object> zDevice = new HashMap<String, Object>(zForEndDevice);
				Map<String, Object> zDevice = convertDevice(zForEndDevice,false);
				//model = (String)zDevice.get("model");
				//zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
				zEndDevice.put("device", zDevice);
				evcMPLSConn.put("zEndInfo", zEndDevice);
				connList.add(convertToLower(evcMPLSConn));
				return connList;
			}
		}
		// AEnd side Route is available
		if ((aEndrt != null) && (aEndrt.isEmpty() == false)) {
			tRecList.addAll(aEndrt);

			while (true) {
				int recSize = tRecList.size();
				for (int i = 0; i < tRecList.size(); i++) {

					Record rtRec = tRecList.get(i);
					Map<String, Object> nniMap = rtRec.get("nni").asMap();
					String locA = (String) nniMap.get("A_location");
					String locZ = (String) nniMap.get("Z_location");
					boolean isNmi = false;
					if((StringUtils.equals(aDeviceName, locA)) || (StringUtils.equals(aDeviceName, locZ)) 
							|| (StringUtils.equals(zDeviceName, locA)) || (StringUtils.equals(zDeviceName, locA))){
						isNmi = true;
					}
					if (StringUtils.equals(locA, aNextNode)) {
						Map<String, Object> evcConn = new HashMap<String, Object>();
						String nniName = (String) nniMap.get("cktid");
						Map<String, Object> aPort = new HashMap<>(convertedPort(rtRec.get("aPort").asMap()));
						aPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) aPort.get("bw"),true,true));
						Map<String, Object> zPort = new HashMap<>(convertedPort(rtRec.get("zPort").asMap()));
						zPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) zPort.get("bw"),true,true));
						//Map<String, Object> aDevice = new HashMap<>(rtRec.get("aAllDev").asMap());
						Map<String, Object> aDevice = convertDevice(rtRec.get("aAllDev").asMap(),false);
						//String model = (String)aDevice.get("model");
						//aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						//Map<String, Object> zDevice = new HashMap<>(rtRec.get("zAllDev").asMap());
						Map<String, Object> zDevice = convertDevice(rtRec.get("zAllDev").asMap(),false);
						//model = (String)zDevice.get("model");
						//zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));

						Map<String, Object> aEndInfo = new HashMap<String, Object>();
						Map<String, Object> zEndInfo = new HashMap<String, Object>();

						evcConn.put("connection", connCount++);
						evcConn.put("connectionType", "NNI");
						//evcConn.put("connectionType", isNmi ? "NMI": "NNI");
						evcConn.put("cTag", aCktCtag);
						evcConn.put("sTag", aCktStag);
						evcConn.put("nniName", nniName);
						evcConn.put("nniInfo", nniMap);

						aEndInfo.put("port", aPort);
						aEndInfo.put("device", aDevice);
						aEndInfo.put("deviceName", locA);
						evcConn.put("aEndInfo", aEndInfo);
						zEndInfo.put("port", zPort);
						zEndInfo.put("device", zDevice);
						zEndInfo.put("deviceName", locZ);
						evcConn.put("zEndInfo", zEndInfo);

						aNextNode = locZ;
						aEndMplsDevice.putAll(zDevice);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));
					} else if (StringUtils.equals(locZ, aNextNode)) {
						Map<String, Object> evcConn = new HashMap<String, Object>();
						String nniName = (String) nniMap.get("cktid");
						Map<String, Object> aPort = new HashMap<>(convertedPort(rtRec.get("aPort").asMap()));
						aPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) aPort.get("bw"),true,true));
						Map<String, Object> zPort = new HashMap<>(convertedPort(rtRec.get("zPort").asMap()));
						zPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) zPort.get("bw"),true,true));
						//Map<String, Object> aDevice = new HashMap<>(rtRec.get("aAllDev").asMap());
						Map<String, Object> aDevice = convertDevice(rtRec.get("aAllDev").asMap(),false);
						//String model = (String)aDevice.get("model");
						//aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));

						//Map<String, Object> zDevice = new HashMap<>(rtRec.get("zAllDev").asMap());
						Map<String, Object> zDevice = convertDevice(rtRec.get("zAllDev").asMap(),false);
						//model = (String)zDevice.get("model");
						//zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));

						Map<String, Object> aEndInfo = new HashMap<String, Object>();
						Map<String, Object> zEndInfo = new HashMap<String, Object>();

						//evcConn.put("connectionType", isNmi ? "NMI": "NNI");
						evcConn.put("connection", connCount++);
						evcConn.put("connectionType", "NNI");
						evcConn.put("sTag", aCktStag);
						evcConn.put("cTag", aCktCtag);
						evcConn.put("nniName", nniName);
						evcConn.put("nniInfo", nniMap);

						aEndInfo.put("port", zPort);
						aEndInfo.put("device", zDevice);
						evcConn.put("aEndInfo", aEndInfo);
						zEndInfo.put("port", aPort);
						zEndInfo.put("device", aDevice);
						evcConn.put("zEndInfo", zEndInfo);

						aNextNode = locA;
						aEndMplsDevice.putAll(aDevice);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));
					}

				}
				if (tRecList.isEmpty()) {
					break;
				}
				if(recSize == tRecList.size()) {
					break;
				}
			}
			tRecList.removeAll(aEndrt);
		}

		// AEnd route available but zEnd route not available. zEnd device is MPLS Edge
		// device
		if ((zEndrt == null) || zEndrt.isEmpty()) {
			if (StringUtils.equals(aNextNode, zNextNode) == false) {
				log.info("No zEnd route. Create MPLS segment connection between '{} and {}", aNextNode, zNextNode);
				evcMPLSConn.put("connection", connCount);
				evcMPLSConn.put("connectionType", "MPLS");
				Map<String, Object> aEndDevice = new HashMap<String, Object>();
				aEndDevice.put("device", aEndMplsDevice);
				evcMPLSConn.put("aEndInfo", aEndDevice);

				Map<String, Object> zEndDevice = new HashMap<String, Object>();
				//Map<String, Object> zForEndDeviceTemp = new HashMap<String,Object>(zForEndDevice);
				Map<String, Object> zForEndDeviceTemp = convertDevice(zForEndDevice,false);
				//String zfarmodel = (String)zForEndDeviceTemp.get("model");
				//zForEndDeviceTemp.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(zfarmodel));
				zEndDevice.put("device", zForEndDeviceTemp);
				evcMPLSConn.put("zEndInfo", zEndDevice);
				connList.add(convertToLower(evcMPLSConn));
				return connList;
			}
		}

		// ZEnd route is available.
		if (zEndrt != null && (zEndrt.isEmpty() == false)) {
			tRecList.addAll(zEndrt);
			// get zEnd nnis

			boolean isNodeFound = false;
			for (int i = 0; i < tRecList.size(); i++) {
				Record rtRec = tRecList.get(i);
				Map<String, Object> nniMap = rtRec.get("nni").asMap();
				String locA = (String) nniMap.get("A_location");
				String locZ = (String) nniMap.get("Z_location");
				if ((StringUtils.equals(locA, aNextNode)) || (StringUtils.equals(locZ, aNextNode))) {
					isNodeFound = true;
					break;
				}
			}
			// aEndRoute last device and zEndRoute first device are not name and hence
			// concludes that both are MPLS Edge devices.
			if (isNodeFound == false) {
				evcMPLSConn.put("connection", connCount);
				evcMPLSConn.put("connectionType", "MPLS");
				Map<String, Object> aEndDevice = new HashMap<String, Object>();
				//Map<String, Object> aEndMplsDeviceTemp = new HashMap<>(aEndMplsDevice);
				Map<String, Object> aEndMplsDeviceTemp = convertDevice(aEndMplsDevice,false);
				//String aMplsmodel = (String)aEndMplsDeviceTemp.get("model");
				//aEndMplsDeviceTemp.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(aMplsmodel));
				aEndDevice.put("device", aEndMplsDeviceTemp);
				evcMPLSConn.put("aEndInfo", aEndDevice);

			} else {
				connCount--;
			}
			int zconncount = connCount + tRecList.size();
			Map<String, Object> zEndMplsDevice = new HashMap<String, Object>(zForEndDevice);
			while (true) {
				int recSize = tRecList.size();
				for (int i = 0; i < tRecList.size(); i++) {

					Record rtRec = tRecList.get(i);
					Map<String, Object> nniMap = rtRec.get("nni").asMap();
					String locA = (String) nniMap.get("A_location");
					String locZ = (String) nniMap.get("Z_location");
					String nniName = (String) nniMap.get("cktid");
					Map<String, Object> aPort = new HashMap<>(convertedPort(rtRec.get("aPort").asMap()));
					aPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) aPort.get("bw"),true,true));
					Map<String, Object> zPort = new HashMap<>(convertedPort(rtRec.get("zPort").asMap()));
					zPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) zPort.get("bw"),true,true));
					//Map<String, Object> aDevice = new HashMap<>(rtRec.get("aAllDev").asMap());
					Map<String, Object> aDevice = convertDevice(rtRec.get("aAllDev").asMap(),false);
					//String model = (String)aDevice.get("model");
					//aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
					//Map<String, Object> zDevice = new HashMap<>(rtRec.get("zAllDev").asMap());
					Map<String, Object> zDevice = convertDevice(rtRec.get("zAllDev").asMap(),false);
					//model = (String)zDevice.get("model");
					//zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
					Map<String, Object> aEndInfo = new HashMap<String, Object>();
					Map<String, Object> zEndInfo = new HashMap<String, Object>();
					if (StringUtils.equals(locA, zNextNode)) {
						Map<String, Object> evcConn = new HashMap<String, Object>();
						evcConn.put("connection", zconncount--);
						evcConn.put("connectionType", "NNI");
						evcConn.put("sTag", zCktStag);
						evcConn.put("cTag", zCktCtag);
						evcConn.put("nniName", nniName);
						evcConn.put("nniInfo", nniMap);

						aEndInfo.put("port", zPort);
						aEndInfo.put("device", zDevice);
						evcConn.put("aEndInfo", aEndInfo);
						zEndInfo.put("port", aPort);
						zEndInfo.put("device", aDevice);
						evcConn.put("zEndInfo", zEndInfo);

						zNextNode = locZ;
						zEndMplsDevice.putAll(zDevice);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));

					} else if (StringUtils.equals(locZ, zNextNode)) {
						Map<String, Object> evcConn = new HashMap<String, Object>();
						evcConn.put("connection", zconncount--);
						evcConn.put("connectionType", "NNI");
						evcConn.put("sTag", zCktStag);
						evcConn.put("cTag", zCktCtag);
						evcConn.put("nniName", nniName);
						evcConn.put("nniInfo", nniMap);

						aEndInfo.put("port", aPort);
						aEndInfo.put("device", aDevice);
						evcConn.put("aEndInfo", aEndInfo);
						zEndInfo.put("port", zPort);
						zEndInfo.put("device", zDevice);
						evcConn.put("zEndInfo", zEndInfo);

						zNextNode = locA;
						zEndMplsDevice.putAll(aDevice);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));
					}
				}
				if (tRecList.isEmpty()) {
					break;
				}
				if(recSize == tRecList.size()) {
					break;
				}
			}

			if (isNodeFound == false) {
				log.info("aEnd route last device and zEnd route 1st device are not same."
						+ " Create MPLS segment connection between '{} and {}", aNextNode, zNextNode);
				Map<String, Object> zEndDevice = new HashMap<String, Object>();
				zEndDevice.put("device", zEndMplsDevice);
				evcMPLSConn.put("zEndInfo", zEndDevice);
				connList.add(convertToLower(evcMPLSConn));
			}
		}

		return connList;
	}

	private String endDeviceFromRoute(String route) {
		String routeSubStr = route.substring(4);
		if (StringUtils.isBlank(routeSubStr))
			return "";
		String[] rtSplitedArr = routeSubStr.split("-");
		String deviceName = rtSplitedArr[0];
		return deviceName;
	}

	private List<Record> getRouteRecords(String route, Transaction tx) {
		String rtQuery = "MATCH (rt:ROUTE{Route:'" + route + "'})<-[r1:RIDES_ON]-(nni:NNIConnection)"
				+ " WHERE nni.deletedTimeStamp IS NULL " + " with nni "
				+ " OPTIONAL MATCH (nni)-[r2:CONNECTED_TO]->(aport:EquipmentPort{portKey:nni.A_port_key})-[r3:COMPONENT_OF*]->"
				+ "(aDev:Equipment{node_id:aport.node_id}) " + " WHERE aDev.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (nni)-[r4:CONNECTED_TO]->(zport:EquipmentPort{portKey:nni.Z_port_key})-[r5:COMPONENT_OF*]->"
				+ "(zDev:Equipment{node_id:zport.node_id})" + " WHERE zDev.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (allNni:allCircuits{aliasCktId:nni.aliasCktId})"
				+ " OPTIONAL MATCH (aAllDev:allDevices{node_id:aDev.node_id})"
				+ " OPTIONAL MATCH (zAllDev:allDevices{node_id:zDev.node_id})"
				+ " RETURN nni as nni, allNni as acNni, aport as aPort, aAllDev as aDevice, zport as zPort, zAllDev as zDevice, aAllDev, zAllDev";
		log.info("Query to get route info :" + rtQuery);
		List<Record> routeRecList = tx.run(rtQuery).list();
		return routeRecList;
	}

	public ResponseStatus getCircuitInfo(String circuit, boolean strictSearch) {
		log.info("getCircuitInfo of : '{}'", circuit);
		Map<String, Object> circuitInfo = new HashMap<String, Object>();
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		log.info("=>getCircuitInfo: circuitName '{}', strictSearch '{}'", circuit, strictSearch);

		String query = "";
		String label = "";

		Transaction tx = null;
		Session session = null;
		String getCircuitLabelProperty = "{aliasCktId: $circuit}";
		String serialConnectionProperty = "{aliasCktId: $circuit}";
		String carrierConnectionProperty = "{aliasCktId: $circuit}";
		String circuitName = circuit;

		if (!strictSearch) {
			circuit = circuit.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
			log.info(
					"Strict Search is off, reassigning circuit with alias ID and then searching circuit with its alias ID. {}",
					circuit);
		} else {
			log.info("Strict Search is on, continue searching with given circuit ID.");
			getCircuitLabelProperty = "{circuitName: $circuit}";
			serialConnectionProperty = "{cktid: $circuit}";
			carrierConnectionProperty = "{cktid: $circuit}";
		}

		try {
			session = driver.session();
			tx = session.beginTransaction();

			// Step 1: Run the query to get the circuitLabel
			String labelQuery = "MATCH (n:allCircuits " + getCircuitLabelProperty + ") "
					+ "WHERE n.deletedTimeStamp is null " + "RETURN n LIMIT 1";
			log.info("query to get the circuitLabel " + labelQuery);
			Map<String, Object> labelParams = new HashMap<>();
			labelParams.put("circuit", circuit);
			Map<String, Object> basicCircuitInfo = new LinkedHashMap<>();

			try {

				Result cktResult = tx.run(labelQuery, labelParams);
				if (cktResult.hasNext()) {
					Record element = cktResult.next();
					basicCircuitInfo = element.get("n").asMap();
					label = basicCircuitInfo.get("type").toString();
					log.info("Retrieved label: " + label);
				} else {
					// No record found, handle it gracefully
					log.error("No circuit found with the circuit name: '{}'", circuit);
					response.setCode(404);
					response.setMessage("No circuit found with the circuit name: " + circuitName);
					return response;
				}

			} catch (Exception ex) {
				log.error("Failed: caught exception: " + ex.getMessage());
				response.setCode(500);
				response.setMessage("Failed to get the circuit information for: " + circuitName);
				return response;
			}

			// Step 2: Determine the query based on the label
//	        if ((StringUtils.equals(label, "Serial")) || (StringUtils.equals(label, "Carrier")
//					|| StringUtils.equals(label, "Message") || StringUtils.equals(label, "Trunk"))) {
//
//			}

			if (!basicCircuitInfo.isEmpty() && basicCircuitInfo.containsKey("resultStatus")
					&& !StringUtils.isEmpty(basicCircuitInfo.get("resultStatus").toString())
					&& (basicCircuitInfo.get("resultStatus").toString().equalsIgnoreCase("partialSuccess"))) {
				log.info("Circuit Status is 'partial success'. other connection information not available.");
				response.setCode(200);
				response.setMessage("Found basic information related to Circuit.");
				Map<String, List<Map<String, Object>>> info = new LinkedHashMap<>();
				List<Map<String, Object>> infoList = new ArrayList<>();
				infoList.add(convertToLower(basicCircuitInfo));
				info.put("circuitInfo", infoList);
				response.setData(info);
				return response;
			}

			if (StringUtils.equals(label, "Serial")) {
				Map<String, Object> uniInfo = new HashMap<String, Object>();
				Map<String, Object> lcUniInfo = new HashMap<String, Object>();
				uniInfo = getUniDesign(circuit, serialConnectionProperty, tx);

				if (uniInfo == null || uniInfo.isEmpty()) {
					log.error("UNI circuit info not found for circuit: '{}'", circuitName);
					response.setCode(404);
					response.setMessage("UNI circuit info not found for circuit: " + circuitName);
					return response;
				}

				uniInfo.remove("impactsDetails");
				lcUniInfo = convertToLower(uniInfo);

				String aliasCktId = circuit.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
				List<String> evcList = getEvcs(aliasCktId, true, tx);
				lcUniInfo.put("serviceList", evcList);

				Map<String, Object> npeInfo = new LinkedHashMap<>();
				Map<String, Object> nmiConn = getNmiByUni(aliasCktId, tx);

				if (nmiConn != null && !nmiConn.isEmpty()) {
					Map<String, Object> zEndDevice = (Map<String, Object>) nmiConn.get("zEndDevice");
					String zEndDeviceName = (zEndDevice != null && zEndDevice.containsKey("TID")) ? (String) zEndDevice.get("TID") : "";
					String zEndDeviceNodeId = (zEndDevice != null && zEndDevice.containsKey("node_id")) ? (String) zEndDevice.get("node_id") : "";

				//lcUniInfo.put("nmiConnection", nmiConn);


					Map<String, Object> uniConnection = (Map<String, Object>) lcUniInfo.get("uniConnection");

					String sourceDevice = "";
					String sourceNodeId = "";
					String targetDevice = "";
					String targetNodeId = "";

					String uniName = "";
					if (uniConnection != null) {
						Map<String, Object> device = new HashMap<>((Map<String, Object>) uniConnection.get("device"));
						//String model = (String)device.get("model");
						//device.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> uniCircuit = (Map<String, Object>) uniConnection.get("uniCircuit");

						uniName = "'"+(String)uniCircuit.get("cktid")+"'";
						//sourceDevice = device != null ? (String) device.getOrDefault("TID", "") : "";
						//sourceNodeId = device != null ? (String) device.getOrDefault("node_id", "") : ""; // ✅ from device
						targetDevice = device != null ? (String) device.getOrDefault("npeName", "") : "";
						targetNodeId = device!= null ? (String) device.getOrDefault("npe_Node_Id", "") : "";
					}

					sourceDevice = zEndDeviceName;
					sourceNodeId = zEndDeviceNodeId;
					log.info("Source Device: {}", sourceDevice);
					log.info("Source Node ID: {}", sourceNodeId);
					log.info("Target Device: {}", targetDevice);
					log.info("Target Node ID: {}", targetNodeId);

					//Map<String, Object> npeInfo = new LinkedHashMap<>();

					npeInfo = getNpeRoute(nmiConn, targetDevice, targetNodeId, uniName, tx);
				}
//				List<String> nmiList = getNMIs(uniName, tx);
//
//				// Call findRouteV1 method to get routes from USIL
//				ResponseStatus routeResponse = findRouteV1(targetDevice, sourceDevice);
//
//				Map<String, Object> npeInfo = new LinkedHashMap<>();
//				String nmiQuery = "MATCH (npe:Equipment {node_id: $nodeId}) RETURN npe";
//				Map<String, Object> params = new HashMap<>();
//				params.put("nodeId", targetNodeId);
//
//				Map<String, Object> npeDeviceInfo = getInfo(nmiQuery, params, "npe");
//				npeInfo.put("npeDevice", npeDeviceInfo);
//
//				if (routeResponse.getCode() == 200 && routeResponse.getData() != null) {
//					Map<String, Object> routeData = (Map<String, Object>) routeResponse.getData();
//					List<Map<String, Object>> routesList = (List<Map<String, Object>>) routeData.get("routesList");
//
//					if (routesList != null && !routesList.isEmpty()) {
//						for (Map<String, Object> routeEntry : routesList) {
//							List<Map<String, Object>> routeTemp = (List<Map<String, Object>>) routeEntry.get("route");
//
//							if (routeTemp != null && !routeTemp.isEmpty()) {
//								List<Map<String, Object>> route = new ArrayList<Map<String,Object>>();
//
//								for (Map<String,Object> conn : routeTemp){
//									String nmiName = (String)conn.get("nniName");
//									String connectionType = (String)conn.get("connectionType");
//									if(nmiList.contains(nmiName) && StringUtils.equals(connectionType, "NNI")) {
//										conn.put("connectionType", "NMI");
//									}
//									route.add(conn);
//								}
//
//								npeInfo.put("route", route);
//								break;
//							}
//						}
//					}
//				} else {
//					log.warn("Route info could not be retrieved: {}", routeResponse.getMessage());
//				}
				lcUniInfo.put("nmiConnection", nmiConn);
				lcUniInfo.put("npeInfo", npeInfo);
				circuitInfo.put("circuitInfo", lcUniInfo);

				response.setData(circuitInfo);
				response.setCode(200);
				response.setMessage("Successfully retrieved CircuitInfo.");
			} else if (List.of("Carrier", "Message", "Trunk").contains(label)) {
				response.setCode(500);
				response.setMessage("NNI Connection circuit feature not implemented yet.");
			} else {
				response.setCode(500);
				response.setMessage("Circuit type not identified.");
			}

		} catch (Exception ex) {
			log.error("Exception occurred: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to retrieve CircuitInfo for: " + circuitName);
			if (tx != null)
				tx.rollback();
		} finally {
			if (session != null)
				session.close();
		}

		return response;
	}

	private Map<String, Object> getNpeRoute(Map<String, Object> nmi, String npeDeviceName, String npeNodeId,
			String uniName, Transaction tx) {
		Map<String, Object> npeInfo = new LinkedHashMap<String, Object>();
		List<Map<String, Object>> npeRoute = new ArrayList<Map<String, Object>>();

		Map<String, Object> aEndDevice = (Map<String, Object>) nmi.get("aEndDevice");
		Map<String, Object> aEndPort = new HashMap<>(convertedPort((Map<String, Object>) nmi.get("aEndPort")));
		//Map<String, Object> aEndPort = (Map<String, Object>) nmi.get("aEndPort");
		Map<String, Object> zEndDevice = (Map<String, Object>) nmi.get("zEndDevice");
		Map<String, Object> zEndPort = new HashMap<>(convertedPort((Map<String, Object>) nmi.get("zEndPort")));
		String nmiName = (String) nmi.get("nmiName");

		Map<String, Object> aEndInfo = new LinkedHashMap<>();
		aEndInfo.put("device", aEndDevice);
		aEndInfo.put("port", aEndPort);

		Map<String, Object> zEndInfo = new LinkedHashMap<>();
		zEndInfo.put("device", zEndDevice);
		zEndInfo.put("port", zEndPort);

		Map<String, Object> firstConn = new LinkedHashMap<>();
		// Represent connection from NPE to NID
		firstConn.put("connection", 1);
		firstConn.put("connectionType", "NMI");
		firstConn.put("nniName", nmiName);
		firstConn.put("aEndInfo", zEndInfo);
		firstConn.put("zEndInfo", aEndInfo);
		npeRoute.add(firstConn);

		String zEndDeviceName = (zEndDevice != null && zEndDevice.containsKey("TID")) ? (String) zEndDevice.get("TID"): "";
		String zEndDeviceNodeId = (zEndDevice != null && zEndDevice.containsKey("node_id"))? (String) zEndDevice.get("node_id"): "";

		if (StringUtils.equals(zEndDeviceName, npeDeviceName)) {
			npeInfo.put("npeDevice", zEndDevice);
			npeInfo.put("route", npeRoute);
		} else {

			// Call findRouteV1 method to get routes from USIL
			ResponseStatus routeResponse = findRouteV1(npeDeviceName, zEndDeviceName, null);

			String npeQuery = "MATCH (npe:Equipment {node_id: $nodeId}) RETURN npe";
			Map<String, Object> params = new HashMap<>();
			params.put("nodeId", npeNodeId);

			Map<String, Object> npeDeviceInfo = getInfo(npeQuery, params, "npe");
			npeInfo.put("npeDevice", npeDeviceInfo);

			if (routeResponse.getCode() == 200 && routeResponse.getData() != null) {
				Map<String, Object> routeData = (Map<String, Object>) routeResponse.getData();
				List<Map<String, Object>> routesList = (List<Map<String, Object>>) routeData.get("routesList");

				if (routesList != null && !routesList.isEmpty()) {
					for (Map<String, Object> routeEntry : routesList) {
						List<Map<String, Object>> routeTemp = (List<Map<String, Object>>) routeEntry.get("route");

						if (routeTemp != null && !routeTemp.isEmpty()) {
							List<Map<String, Object>> route = new ArrayList<Map<String, Object>>();

							for (Map<String, Object> conn : routeTemp) {
								int connNum = (int) conn.get("connection");
								conn.put("connection", connNum++);
								// String nmiName = (String)conn.get("nniName");
								// String connectionType = (String)conn.get("connectionType");
//							if(nmiList.contains(nmiName) && StringUtils.equals(connectionType, "NNI")) {
//								conn.put("connectionType", "NMI");
//							}
								route.add(conn);
							}

							npeInfo.put("route", route);
							break;
						}
					}
				}
			} else {
				log.warn("Route info could not be retrieved: {}", routeResponse.getMessage());
			}
		}
		return npeInfo;
	}

	public Map<String, Object> getUniDesign(String uniCktId, String serialConnectionProperty, Transaction tx) {
		log.info("=>getUniDesign");
		Map<String, Object> result = new HashMap<String, Object>();
		Map<String, Object> uniConnection = new HashMap<String, Object>();

		try {
			Map<String, Object> parameters = new HashMap<>();
			parameters.put("circuit", uniCktId);

			// 1. Get UNI Details basic details.
			String uniQuery = "MATCH (uni:UNIConnection" + serialConnectionProperty + ")"
					+ " WHERE uni.deletedTimeStamp IS NULL "
					+ " OPTIONAL MATCH (uni)-[r:CONNECTED_TO]->(uniPort:EquipmentPort)"
					+ " OPTIONAL MATCH (uniPort)-[s:COMPONENT_OF*]->(ne:Equipment)"
					+ " WHERE ne.deletedTimeStamp IS NULL " + "MATCH (dev:allDevices{node_id:ne.node_id})"
					+ " RETURN uni,uniPort,dev as device";
			log.info("UNI Circuit Query : " + uniQuery);

			List<Record> uniInfoList = tx.run(uniQuery, parameters).list();
			if (uniInfoList == null || uniInfoList.isEmpty()) {
				log.error("UNI circuit not found");
				return uniConnection;
			}
			Record uniRec = uniInfoList.get(0);
			Map<String, Object> uni = uniRec.get("uni").asMap();
			Map<String, Object> uniPort = new HashMap<>(convertedPort(uniRec.get("uniPort").asMap()));
			//Map<String, Object> device = new HashMap<>(uniRec.get("device").asMap());
			String aUniType = (String) uni.get("serviceType");
			String provisionStatus =  (String) uni.get("status");
			Map<String, Object> device = new HashMap<String, Object>();
			if (StringUtils.equals(aUniType, "MEF UNI"))
				device = convertDevice(uniRec.get("device").asMap(), true);
			else
				device = convertDevice(uniRec.get("device").asMap(), false);
			//String model = (String)device.get("model");
			//device.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
			Object bwValue = uniPort.get("bw");
			String portSpeed = ApplicationUtils.bandwidthConvertor(bwValue != null ? bwValue.toString() : "", true,true);
			uniPort.put("portSpeed", portSpeed);

			List<Map<String, Object>> layer1List = new ArrayList<>();
			List<Map<String, Object>> transportList = new ArrayList<>();

			Map<String, Object> uniObj = new HashMap<String, Object>((Map<String, Object>)uni);
			if (uniObj.containsKey("layer1") && (uniObj.get("layer1") != null)) {

				ObjectMapper mapper = new ObjectMapper();

				Object layer1Obj = uniObj.get("layer1");
				if (layer1Obj instanceof String jsonString) {
					try {
						layer1List = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse layer1 JSON string.", e);
					}
				} else if (layer1Obj instanceof List<?> rawList) {
					for (Object L1Obj : rawList) {
						if (L1Obj instanceof Map<?, ?> layer1Map) {
							Map<String, Object> layer1InfoMap = new HashMap<>();
							layer1Map.forEach((k, v) -> layer1InfoMap.put(String.valueOf(k), v));
							layer1List.add(layer1InfoMap);
						}
					}
				}
				uniObj.put("layer1", layer1List);
			}
			if (uniObj.containsKey("transport") && (uniObj.get("transport") != null)) {

				ObjectMapper mapper = new ObjectMapper();

				Object transportObj = uniObj.get("transport");
				if (transportObj instanceof String jsonString) {
					try {
						transportList = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse layer1 JSON string.", e);
					}
				} else if (transportObj instanceof List<?> rawList) {
					for (Object tpObj : rawList) {
						if (tpObj instanceof Map<?, ?> transportMap) {
							Map<String, Object> transportInfoMap = new HashMap<>();
							transportMap.forEach((k, v) -> transportInfoMap.put(String.valueOf(k), v));
							transportList.add(transportInfoMap);
						}
					}
				}
				uniObj.put("transport", transportList);
			}
			boolean uniL1ProvRequired = false;
            if(uniObj.containsKey("uniL1ProvRequired")) {
				uniL1ProvRequired = (boolean)uniObj.get("uniL1ProvRequired");
			}

            result.put("uniL1ProvRequired", uniL1ProvRequired);
			result.put("uniCircuit", uniObj);
			result.put("uniPort", uniPort);
			result.put("device", device);
			boolean isDispatchRequired = true;
			if(StringUtils.equalsAnyIgnoreCase(provisionStatus, "In Service") )
				isDispatchRequired = false;

			uniConnection.put("uniConnection", result);
			uniConnection.put("serviceType", aUniType);
			uniConnection.put("isDispatchRequired", isDispatchRequired);

			log.info("UNI Connection details '{}", uniConnection.toString());
		} catch (Exception ex) {
			log.error("Failed to get Uni design. Caught Exception: '{}'", ex.getMessage());
		}
		return uniConnection;
	}

	private Map<String, Object> getNmiByUni(String circuit, Transaction tx) {

		Map<String, Object> nmiConn = new HashMap<String, Object>();
		/*String nmiQuery = "MATCH (uni:UNIConnection{aliasCktId: '" + circuit + "'}) WHERE uni.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (uni)-[:CONNECTED_TO]->(route:ROUTE)<-[:RIDES_ON]-(rtNni:NNIConnection) "
				+ " WHERE (rtNni.A_node_id = uni.node_id OR rtNni.Z_node_id = uni.node_id) AND rtNni.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (uni)-[r:CONNECTED_TO]->(uniPort:EquipmentPort)-[:XCONNECT]"
				+ "->(nniPort:EquipmentPort)<-[:CONNECTED_TO]-(xNni:NNIConnection) "
				+ " WHERE xNni.deletedTimeStamp IS NULL AND nniPort.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (uni)-[t:AEND|ZEND]-(evc:EVCConnection)<-[:RIDES_ON]"
				+ "-(evroute:ROUTE)<-[:RIDES_ON]-(evNni:NNIConnection) "
				+ " WHERE (evNni.A_node_id = uni.node_id OR evNni.Z_node_id = uni.node_id) "
				+ " AND evc.deletedTimeStamp IS NULL AND evNni.deletedTimeStamp IS NULL "
				+ "WITH uni, rtNni, xNni, evNni, " + "CASE WHEN rtNni is not null THEN rtNni "
				+ " WHEN xNni is not null THEN xNni " + " WHEN evNni is not null THEN evNni " + " ELSE null END as nmi "
				+ " RETURN nmi.cktid as nmiName, nmi.loca as aEndDevice, nmi.A_port_key as aEndPort, "
				+ " nmi.Z_port_key as zEndPort, nmi.locz as zEndDevice nmi.layer1ProvRequired as layer1ProvRequired";*/

		String nmiQuery =  "MATCH (uni:UNIConnection{aliasCktId: '" + circuit + "'}) WHERE uni.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (uni)-[r:CONNECTED_TO]->(uniPort:EquipmentPort)-[:XCONNECT]->(nidPort:EquipmentPort)"
				+ "<-[:CONNECTED_TO]-(xNni:NNIConnection)-[:CONNECTED_TO]->(aggrPort:EquipmentPort) "
				+ " WHERE xNni.deletedTimeStamp IS NULL AND nidPort.deletedTimeStamp IS NULL AND aggrPort.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (nidPort)-[:COMPONENT_OF*]->(nid:Equipment) "
				+ " OPTIONAL MATCH (aggrPort)-[:COMPONENT_OF*]->(aggr:Equipment) "
				+ " RETURN xNni as nmi, xNni.layer1ProvRequired as layer1ProvRequired,  aggrPort, uniPort, nidPort, nid AS aEndDevice,aggr AS zEndDevice";

		log.info("NMI connection query: '{}'", nmiQuery);
		try {
			List<Record> nmiInfoList = tx.run(nmiQuery).list();
			if (nmiInfoList == null || nmiInfoList.isEmpty()) {
				log.error("NMI connection not found");
				return nmiConn;
			}
			Record nmiRec = nmiInfoList.get(0);
			Map<String,Object> nmi = nmiRec.get("nmi").asMap();
			String nmiName = (String)nmi.get("cktid");
			Map<String,Object> aEndPort = new HashMap<>(convertedPort(nmiRec.get("nidPort").asMap()));
			String aportSpeed = (String)aEndPort.get("bw");
			aportSpeed = ApplicationUtils.bandwidthConvertor(aportSpeed, true, true);
			aEndPort.put("portSpeed", aportSpeed);

			Map<String,Object> zEndPort = new HashMap<>(convertedPort(nmiRec.get("aggrPort").asMap()));
			String zportSpeed = (String)zEndPort.get("bw");
			zportSpeed = ApplicationUtils.bandwidthConvertor(zportSpeed, true, true);
			zEndPort.put("portSpeed", zportSpeed);
			Map<String,Object> aEndDevice = new HashMap(nmiRec.get("aEndDevice").asMap());
			aEndDevice = convertDevice(aEndDevice,true);
			Map<String,Object> zEndDevice = new HashMap(nmiRec.get("zEndDevice").asMap());
			zEndDevice = convertDevice(zEndDevice,false);

			List<Map<String, Object>> layer1List = new ArrayList<>();
			List<Map<String, Object>> transportList = new ArrayList<>();

			Map<String, Object> nniObj = new HashMap<String, Object>((Map<String, Object>)nmi);
			if (nniObj.containsKey("layer1") && (nniObj.get("layer1") != null)) {

				ObjectMapper mapper = new ObjectMapper();

				Object layer1Obj = nniObj.get("layer1");
				if (layer1Obj instanceof String jsonString) {
					try {
						layer1List = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse layer1 JSON string.", e);
					}
				} else if (layer1Obj instanceof List<?> rawList) {
					for (Object L1Obj : rawList) {
						if (L1Obj instanceof Map<?, ?> layer1Map) {
							Map<String, Object> layer1InfoMap = new HashMap<>();
							layer1Map.forEach((k, v) -> layer1InfoMap.put(String.valueOf(k), v));
							layer1List.add(layer1InfoMap);
						}
					}
				}
				nniObj.put("layer1", layer1List);
			}
			if (nniObj.containsKey("transport") && (nniObj.get("transport") != null)) {

				ObjectMapper mapper = new ObjectMapper();

				Object transportObj = nniObj.get("transport");
				if (transportObj instanceof String jsonString) {
					try {
						transportList = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse layer1 JSON string.", e);
					}
				} else if (transportObj instanceof List<?> rawList) {
					for (Object tpObj : rawList) {
						if (tpObj instanceof Map<?, ?> transportMap) {
							Map<String, Object> transportInfoMap = new HashMap<>();
							transportMap.forEach((k, v) -> transportInfoMap.put(String.valueOf(k), v));
							transportList.add(transportInfoMap);
						}
					}
				}
				nniObj.put("transport", transportList);
			}
			boolean layer1ProvRequired = false;
            if(nniObj.containsKey("layer1ProvRequired")) {
				layer1ProvRequired = (boolean)nniObj.get("layer1ProvRequired");
			}
            nmiConn.put("nmiLayer1ProvRequired", layer1ProvRequired);

			nmiConn.put("nmi", nniObj);
			nmiConn.put("nmiName", nmiName);
			nmiConn.put("aEndDevice", aEndDevice);
			nmiConn.put("aEndPort", aEndPort);
			nmiConn.put("zEndDevice", zEndDevice);
			nmiConn.put("zEndPort", zEndPort);

			nmiConn = convertToLower(nmiConn);
			log.info("Found NMI Connection Info: '{}'", nmiConn);
		} catch (Exception ex) {
			log.error("Failed to retrieve NMI connection. Caught Exception: " + ex.getMessage());
		}
		return nmiConn;
	}

	private List<String> getEvcs(String circuit, boolean isUni, Transaction tx) {

		String evcQuery = "";
		List<String> evcList = new ArrayList<String>();

		try {
			if (isUni) {

				evcQuery = "MATCH (uni:UNIConnection{aliasCktId: '" + circuit
						+ "'}) WHERE uni.deletedTimeStamp IS NULL "
						+ " OPTIONAL MATCH (uni)-[:AEND|ZEND]-(ev:EVCConnection) "
						+ " WHERE ev.deletedTimeStamp IS NULL "
						+ " WITH uni,ev, COALESCE(ev,null) AS evc RETURN evc.name as evcName";

				log.info("EVC connection query: '{}'", evcQuery);

				List<Record> evcRecList = tx.run(evcQuery).list();
				for (Record evcRec : evcRecList) {
					String evcName = evcRec.get("evcName").asString();
					if (StringUtils.isNotBlank(evcName)) {
						evcList.add(evcName);
					}
				}

			} else {

			}

		} catch (Exception ex) {
			log.error("Failed to retrieve EVCs List: Caught Exception: " + ex.getMessage());
		}
		return evcList;
	}

	/********************************************************************************************************************************************************/
	public ResponseStatus getVCToProvision(String circuit) {
		log.info("=>getVCToProvision for:  '{}'", circuit);
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to retrieved VC design for: " + circuit);

		Map<String, Object> evcDesign = new HashMap<String, Object>();
		String evcName = circuit.toUpperCase();

		Map<String, Object> result = new HashMap<String, Object>();
		Map<String, Object> evcConnection = new LinkedHashMap<String, Object>();
		Map<String, Object> services = new LinkedHashMap<String, Object>();
		Map<String, Object> uni = new LinkedHashMap<String, Object>();
		Map<String, Object> targetUni = new LinkedHashMap<String, Object>();

		// 1. Get EVC Details basic details.
		String evcQuery = "MATCH (evc:EVCConnection)  " + " WHERE (evc.aliasCktId = '" + evcName
				+ "' OR evc.serviceName = '" + evcName + "') and  evc.deletedTimeStamp IS NULL with evc "
				+ " MATCH  (evc)-[ar:AEND]->(aEndCkt:UNIConnection)-[r1:CONNECTED_TO]->(aPort:EquipmentPort)-[r2:COMPONENT_OF*]->(aDev:Equipment)"
				+ " WHERE aDev.deletedTimeStamp IS NULL"
				+ " MATCH  (evc)<-[zr:ZEND]-(zEndCkt:UNIConnection)-[r3:CONNECTED_TO]->(zPort:EquipmentPort)-[r4:COMPONENT_OF*]->(zDev:Equipment)"
				+ " WHERE zDev.deletedTimeStamp IS NULL " + " MATCH (aAllDev:allDevices{node_id:aDev.node_id}) "
				+ " MATCH (zAllDev:allDevices{node_id:zDev.node_id}) "
				+ " RETURN evc as EvcInfo, aEndCkt as aEndCircuit, aPort as aPort, aDev as aDevice, aAllDev, "
				+ " zEndCkt as zEndCircuit, zPort as zPort, zDev as zDevice, zAllDev, ar.STAG as aCktStag,"
				+ " ar.CTAG as aCktCtag, ar.uniBandwidth as aUnibw, zr.STAG as zCktStag, zr.CTAG as zCktCtag, zr.uniBandwidth as zUnibw";

		log.info("Query to get EVC basic details " + evcQuery);

		Transaction tx = null;
		Session session = null;

		try {
			session = driver.session();
			tx = session.beginTransaction();

			List<Record> evcInfoList = tx.run(evcQuery).list();
			if (evcInfoList == null || evcInfoList.isEmpty()) {
				log.error("No VC circuit found with EVC name: " + evcName);
				response.setCode(500);
				response.setMessage("No VC circuit found with EVC name: " + circuit);
				return response;
			}
			Record evcRec = evcInfoList.get(0);
			Map<String, Object> evcInfo = evcRec.get("EvcInfo").asMap();
			Map<String, Object> aEndCircuit = evcRec.get("aEndCircuit").asMap();
			Map<String, Object> aPort = evcRec.get("aPort").asMap();
			Map<String, Object> aDevice = evcRec.get("aAllDev").asMap();
			Map<String, Object> zEndCircuit = evcRec.get("zEndCircuit").asMap();
			Map<String, Object> zPort = evcRec.get("zPort").asMap();
			Map<String, Object> zDevice = evcRec.get("zAllDev").asMap();
			String aCktStag = evcRec.get("aCktStag").asString();
			String aCktCtag = evcRec.get("aCktCtag").asString();
			String zCktStag = evcRec.get("zCktStag").asString();
			String zCktCtag = evcRec.get("zCktCtag").asString();
			String aCktBandWidth = evcRec.get("aUnibw").asString();
			String zCktBandWidth = evcRec.get("zUnibw").asString();
			String serviceSubtype = (String) evcInfo.get("serviceSubtype");
			if (StringUtils.isNotBlank(serviceSubtype))
				serviceSubtype = String.valueOf(serviceSubtype).toLowerCase();

			services.put("serviceInstanceId", (String) evcInfo.get("name"));
			services.put("serviceOrderNumber", (String) evcInfo.get("orderNumber"));
			services.put("serviceName", serviceSubtype);
			services.put("speed", (String) evcInfo.get("speed"));
			services.put("serviceCos", (String) evcInfo.get("serviceCos"));
			services.put("dueDate", (String) evcInfo.get("dueDate"));
			services.put("requestId", (String) evcInfo.get("requestId"));

			List<Map<String, Object>> relatedParty = new ArrayList<Map<String, Object>>();
			relatedParty.add(new HashMap<>() {
				{
					put("customerID", evcInfo.get("customerAccountId"));
				}
			});
			services.put("relatedParty", relatedParty);

			// aEnd UNI Info
			uni = getUNIInfo(aEndCircuit, aPort, (String) aDevice.get("deviceRole"), aCktBandWidth, "1000base-fx");
			evcConnection.put("uni", uni);
			uni = getUNIInfo(zEndCircuit, zPort, (String) zDevice.get("deviceRole"), zCktBandWidth, "1000base-fx");
			evcConnection.put("targetUni", uni);
			evcConnection.put("services", services);

			Map<String, Object> virtualServices = new LinkedHashMap<String, Object>();

			String serviceType = (String) evcInfo.get("serviceType");
			if (StringUtils.equals("MEF EVC", serviceType)) {
				serviceType = "evc";
			} else {
				serviceType = "ovc";
			}

			virtualServices.put("vcConnectionType", serviceType);
			log.info("aDevice: '{}', zDevice: '{}'", aDevice.get("name"), aDevice.get("name"));

			List<Map<String, Object>> connInfo = getVCRouteForProvision(evcName, aDevice, zDevice, aCktCtag, tx);

			virtualServices.put("bridges", connInfo);

			evcConnection.put("virtualServices", virtualServices);

			response.setData(evcConnection);

			response.setCode(200);
			response.setMessage("Successfully retrieved VC design for: " + circuit);
			log.info("VC details '{}", evcConnection.toString());

		} catch (Exception ex) {
			log.error("Failed to get VC design: Caught Exception: '{}'", ex.getMessage());
			response.setCode(500);
			response.setMessage("Failed to get VC design for: " + circuit);

		} finally {
			if (session != null) {
				session.close();
			}
			if (tx != null) {
				tx.close();
			}
		}
		return response;
	}

	private List<Map<String, Object>> getVCRouteForProvision(String evcName, Map<String, Object> aDevice,
			Map<String, Object> zDevice, String cTag, Transaction tx) {
		log.info("=> getVCRouteForProvision() ");
		List<Map<String, Object>> connInfo = new ArrayList<Map<String, Object>>();

		try {
			// 1. Get routes
			String aDeviceName = (String) aDevice.get("name");
			String zDeviceName = (String) zDevice.get("name");

			log.info("getEvcRoute for EVC - '{}' from aEnd: {} to zEnd: {}", evcName, aDeviceName, zDeviceName);

			String routeQuery = "MATCH (evc:EVCConnection)<-[r:RIDES_ON]-(rt:ROUTE) " + " WHERE (evc.aliasCktId = '"
					+ evcName + "' OR evc.serviceName = '" + evcName + "')" + " AND evc.deletedTimeStamp IS NULL "
					+ " AND rt.deletedTimeStamp IS NULL " + " RETURN rt.Route as route";
			log.info("Query to get the evcRoute :" + routeQuery);
			List<Record> routeList = tx.run(routeQuery).list();
			if (routeList == null || routeList.isEmpty()) {
				log.error("Service route NOT found for the EVC/OVC : " + evcName);

				return connInfo;
			}
			Record routeRec1 = routeList.get(0);
			String aEndRoute = routeRec1.get("route").asString();

			String rtDevName = endDeviceFromRoute(aEndRoute);
			String zEndRoute = null;
			if (StringUtils.equals(rtDevName, zDeviceName)) {
				zEndRoute = aEndRoute;
				aEndRoute = null;
			}

			String otherRoute = null;
			Record routeRec2 = null;

			if (routeList.size() > 1) {
				routeRec2 = routeList.get(1);
				otherRoute = routeRec2.get("route").asString();

				rtDevName = endDeviceFromRoute(otherRoute);

				if (StringUtils.equals(rtDevName, aDeviceName)) {
					aEndRoute = otherRoute;
				} else if (StringUtils.equals(rtDevName, zDeviceName)) {
					zEndRoute = otherRoute;
				}
			}

			List<Record> aRtList = new ArrayList<Record>();
			List<Record> zRtList = new ArrayList<Record>();
			if (StringUtils.isNotBlank(aEndRoute)) {
				aRtList = getRouteRecords(aEndRoute, tx);
			}
			if (StringUtils.isNotBlank(zEndRoute)) {
				zRtList = getRouteRecords(zEndRoute, tx);
			}

			connInfo = getRouteConnsForProvision(aRtList, aDevice, zRtList, zDevice, cTag);
			log.info("<=getVCRouteForProvision() ");
		} catch (Exception ex) {
			log.error("Caught Exception: '{}'", ex.getMessage());
		}
		return connInfo;
	}

	private List<Map<String, Object>> getRouteConnsForProvision(List<Record> aEndrt, Map<String, Object> aForEndDevice,
			List<Record> zEndrt, Map<String, Object> zForEndDevice, String cTag) {
		log.info("==>getRouteConnsForProvision");
		List<Map<String, Object>> connList = new ArrayList<Map<String, Object>>();

		String aDeviceName = (String) aForEndDevice.get("name");
		String zDeviceName = (String) zForEndDevice.get("name");

		String aNextNode = aDeviceName;
		String zNextNode = zDeviceName;
		Map<String, Object> aEndMplsDevice = new HashMap<String, Object>(aForEndDevice);

		List<Record> tRecList = new ArrayList<Record>();
		int connCount = 1;
		String hopStr = "";

		Map<String, Object> evcMPLSConn = new LinkedHashMap<String, Object>();
		// When No routes available and both end devices are not same. Assuming both are
		// on MPLS network

		if (((aEndrt == null) || aEndrt.isEmpty()) && ((zEndrt == null) || zEndrt.isEmpty())) {
			if (StringUtils.equals(aNextNode, zNextNode) == false) {
				log.info("NO aEnd & zEnd routes. Create MPLS segment connection between '{} and {}", aNextNode,
						zNextNode);

				evcMPLSConn.put("hopNumber", connCount);
				evcMPLSConn.put("hop", new ArrayList<>());
				evcMPLSConn.put("connectionType", "MPLS");
				evcMPLSConn.put("ckId", new ArrayList<>() {
					{
						add("cloud");
					}
				});
				evcMPLSConn.put("ctagvlanmap", cTag);
				evcMPLSConn.put("stagVlanMap", cTag);

				evcMPLSConn.put("device1", getHopDeviceInfo(aForEndDevice, ""));
				evcMPLSConn.put("device2", getHopDeviceInfo(zForEndDevice, ""));

				connList.add(convertToLower(evcMPLSConn));
				return connList;
			}
		}
		// AEnd side Route is available
		if ((aEndrt != null) && (aEndrt.isEmpty() == false)) {
			tRecList.addAll(aEndrt);

			while (true) {
				int recSize = tRecList.size();
				for (int i = 0; i < tRecList.size(); i++) {

					Record rtRec = tRecList.get(i);
					Map<String, Object> nniMap = rtRec.get("nni").asMap();
					String locA = (String) nniMap.get("A_location");
					String locZ = (String) nniMap.get("Z_location");

					if (StringUtils.equals(locA, aNextNode)) {
						Map<String, Object> evcConn = new LinkedHashMap<String, Object>();
						String nniName = (String) nniMap.get("cktid");
						if (StringUtils.isNotBlank(nniName))
							nniName = nniName.replace(" ", "").toLowerCase();
						Map<String, Object> aPort = rtRec.get("aPort").asMap();
						Map<String, Object> zPort = rtRec.get("zPort").asMap();
						Map<String, Object> aDevice = new HashMap<>(rtRec.get("aDevice").asMap());
						String model = (String)aDevice.get("model");
						aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> zDevice = new HashMap<>(rtRec.get("zDevice").asMap());
						model = (String)zDevice.get("model");
						zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> aAllDev = new HashMap<>(rtRec.get("aAllDev").asMap());
						model = (String)aAllDev.get("model");
						aAllDev.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> zAllDev = new HashMap<>(rtRec.get("zAllDev").asMap());
						model = (String)zAllDev.get("model");
						zAllDev.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> acNni = rtRec.get("acNni").asMap();
						String nniPortBW = (String) acNni.get("cxrType");
						if (StringUtils.isBlank(nniPortBW)) {
							nniPortBW = (String) aPort.get("bw");
						}

						Map<String, Object> aEndInfo = new HashMap<String, Object>();
						Map<String, Object> zEndInfo = new HashMap<String, Object>();
						Map<String, Object> hopMap = getHop(nniMap, aAllDev, aPort, nniPortBW, zAllDev, zPort);

						evcConn.put("hopNumber", connCount++);
						String[] hopList = { "hop:" + (String) hopMap.get("hopStr") };
						evcConn.put("hop", hopList);

						String aDevName = (String) aDevice.get("TID");
						String zDevName = (String) zDevice.get("TID");
						if ((StringUtils.equals(aDeviceName, aDevName))
								|| (StringUtils.equals(aDeviceName, zDevName))) {
							String[] epList = { "ep:" + (String) hopMap.get("hopStr") };
							evcConn.put("epInstanceId", epList);
						}

						evcConn.put("connectionType", "SwitchedEthernet");
						evcConn.put("ctagvlanmap", cTag);
						evcConn.put("stagVlanMap", cTag);
						String[] ckId = { nniName };
						evcConn.put("ckId", ckId);

						evcConn.put("device1", (Map<String, Object>) hopMap.get("device1"));
						evcConn.put("device2", (Map<String, Object>) hopMap.get("device2"));

						aNextNode = locZ;
						aEndMplsDevice.putAll(zAllDev);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));
					} else if (StringUtils.equals(locZ, aNextNode)) {
						Map<String, Object> evcConn = new LinkedHashMap<String, Object>();
						String nniName = (String) nniMap.get("cktid");
						if (StringUtils.isNotBlank(nniName))
							nniName = nniName.replace(" ", "").toLowerCase();
						Map<String, Object> aPort = rtRec.get("aPort").asMap();
						Map<String, Object> zPort = rtRec.get("zPort").asMap();
						Map<String, Object> aDevice = new HashMap<>(rtRec.get("aDevice").asMap());
						String model = (String)aDevice.get("model");
						aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> zDevice = new HashMap<>(rtRec.get("zDevice").asMap());
						model = (String)aDevice.get("model");
						zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> aAllDev = new HashMap<>(rtRec.get("aAllDev").asMap());
						model = (String)aAllDev.get("model");
						aAllDev.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> zAllDev = new HashMap<>(rtRec.get("zAllDev").asMap());
						model = (String)zAllDev.get("model");
						zAllDev.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
						Map<String, Object> acNni = rtRec.get("acNni").asMap();
						String nniPortBW = (String) acNni.get("cxrType");
						if (StringUtils.isBlank(nniPortBW)) {
							nniPortBW = (String) aPort.get("bw");
						}

						Map<String, Object> aEndInfo = new HashMap<String, Object>();
						Map<String, Object> zEndInfo = new HashMap<String, Object>();
						Map<String, Object> hopMap = getHop(nniMap, zAllDev, zPort, nniPortBW, aAllDev, aPort);

						evcConn.put("hopNumber", connCount++);
						String[] hopList = { "hop:" + (String) hopMap.get("hopStr") };
						evcConn.put("hop", hopList);

						String aDevName = (String) aDevice.get("TID");
						String zDevName = (String) zDevice.get("TID");
						if ((StringUtils.equals(aDeviceName, aDevName))
								|| (StringUtils.equals(aDeviceName, zDevName))) {
							String[] epList = { "ep:" + (String) hopMap.get("hopStr") };
							evcConn.put("epInstanceId", epList);
						}

						evcConn.put("connectionType", "SwitchedEthernet");
						evcConn.put("ctagvlanmap", cTag);
						evcConn.put("stagVlanMap", cTag);
						String[] ckId = { nniName };
						evcConn.put("ckId", ckId);
						evcConn.put("device1", (Map<String, Object>) hopMap.get("device1"));
						evcConn.put("device2", (Map<String, Object>) hopMap.get("device2"));

						aNextNode = locA;
						aEndMplsDevice.putAll(aAllDev);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));
					}

				}
				if (tRecList.isEmpty()) {
					break;
				}
				if(recSize == tRecList.size()) {
					break;
				}
			}
			tRecList.removeAll(aEndrt);
		}

		// AEnd route available but zEnd route not available. zEnd device is MPLS Edge
		// device
		if ((zEndrt == null) || zEndrt.isEmpty()) {
			if (StringUtils.equals(aNextNode, zNextNode) == false) {
				log.info("No zEnd route. Create MPLS segment connection between '{} and {}", aNextNode, zNextNode);
				evcMPLSConn.put("hopNumber", connCount);
				evcMPLSConn.put("hop", new ArrayList<>());
				evcMPLSConn.put("connectionType", "MPLS");
				evcMPLSConn.put("ckId", new ArrayList<>() {
					{
						add("cloud");
					}
				});
				evcMPLSConn.put("ctagvlanmap", cTag);
				evcMPLSConn.put("stagVlanMap", cTag);

				evcMPLSConn.put("device1", getHopDeviceInfo(aEndMplsDevice, ""));
				evcMPLSConn.put("device2", getHopDeviceInfo(zForEndDevice, ""));

				return connList;
			}
		}

		// ZEnd route is available.
		if (zEndrt != null && (zEndrt.isEmpty() == false)) {
			tRecList.addAll(zEndrt);
			// get zEnd nnis

			boolean isNodeFound = false;
			for (int i = 0; i < tRecList.size(); i++) {
				Record rtRec = tRecList.get(i);
				Map<String, Object> nniMap = rtRec.get("nni").asMap();
				String locA = (String) nniMap.get("A_location");
				String locZ = (String) nniMap.get("Z_location");
				if ((StringUtils.equals(locA, aNextNode)) || (StringUtils.equals(locZ, aNextNode))) {
					isNodeFound = true;
					break;
				}
			}
			// aEndRoute last device and zEndRoute first device are not name and hence
			// concludes that both are MPLS Edge devices.
			if (isNodeFound == false) {
				evcMPLSConn.put("hopNumber", connCount);
				evcMPLSConn.put("hop", new ArrayList<>());
				evcMPLSConn.put("connectionType", "MPLS");
				evcMPLSConn.put("ckId", new ArrayList<>() {
					{
						add("cloud");
					}
				});
				evcMPLSConn.put("ctagvlanmap", cTag);
				evcMPLSConn.put("stagVlanMap", cTag);
				evcMPLSConn.put("device1", getHopDeviceInfo(aEndMplsDevice, ""));

			} else {
				connCount--;
			}
			int zconncount = connCount + tRecList.size();
			Map<String, Object> zEndMplsDevice = new HashMap<String, Object>(zForEndDevice);
			while (true) {
				int recSize = tRecList.size();
				for (int i = 0; i < tRecList.size(); i++) {

					Record rtRec = tRecList.get(i);
					Map<String, Object> nniMap = rtRec.get("nni").asMap();
					String locA = (String) nniMap.get("A_location");
					String locZ = (String) nniMap.get("Z_location");
					String nniName = (String) nniMap.get("cktid");
					if (StringUtils.isNotBlank(nniName))
						nniName = nniName.replace(" ", "").toLowerCase();
					Map<String, Object> aPort = rtRec.get("aPort").asMap();
					Map<String, Object> zPort = rtRec.get("zPort").asMap();
					Map<String, Object> aDevice = new HashMap<>(rtRec.get("aDevice").asMap());
					String model = (String)aDevice.get("model");
					aDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
					Map<String, Object> zDevice = new HashMap<>(rtRec.get("zDevice").asMap());
					model = (String)zDevice.get("model");
					zDevice.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
					Map<String, Object> aAllDev = new HashMap<>(rtRec.get("aAllDev").asMap());
					model = (String)aAllDev.get("model");
					aAllDev.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
					Map<String, Object> zAllDev = new HashMap<>(rtRec.get("zAllDev").asMap());
					model = (String)zAllDev.get("model");
					zAllDev.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
					Map<String, Object> acNni = rtRec.get("acNni").asMap();
					String nniPortBW = (String) acNni.get("cxrType");
					if (StringUtils.isBlank(nniPortBW)) {
						nniPortBW = (String) aPort.get("bw");
					}

					Map<String, Object> aEndInfo = new HashMap<String, Object>();
					Map<String, Object> zEndInfo = new HashMap<String, Object>();
					Map<String, Object> hopMap = new HashMap<String, Object>();

					if (StringUtils.equals(locA, zNextNode)) {
						Map<String, Object> evcConn = new LinkedHashMap<String, Object>();
						hopMap = getHop(nniMap, zAllDev, zPort, nniPortBW, aAllDev, aPort);

						evcConn.put("hopNumber", zconncount--);
						String[] hopList = { "hop:" + (String) hopMap.get("hopStr") };
						evcConn.put("hop", hopList);

						String aDevName = (String) aDevice.get("TID");
						String zDevName = (String) zDevice.get("TID");
						if ((StringUtils.equals(zDeviceName, aDevName))
								|| (StringUtils.equals(zDeviceName, zDevName))) {
							String[] epList = { "ep:" + (String) hopMap.get("hopStr") };
							evcConn.put("epInstanceId", epList);
						}

						evcConn.put("connectionType", "SwitchedEthernet");
						evcConn.put("ctagvlanmap", cTag);
						evcConn.put("stagVlanMap", cTag);
						String[] ckId = { nniName };
						evcConn.put("ckId", ckId);

						evcConn.put("device1", (Map<String, Object>) hopMap.get("device1"));
						evcConn.put("device2", (Map<String, Object>) hopMap.get("device2"));

						zNextNode = locZ;
						zEndMplsDevice.putAll(zAllDev);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));

					} else if (StringUtils.equals(locZ, zNextNode)) {
						Map<String, Object> evcConn = new LinkedHashMap<String, Object>();

						hopMap = getHop(nniMap, aAllDev, aPort, nniPortBW, zAllDev, zPort);

						evcConn.put("hopNumber", zconncount--);
						String[] hopList = { "hop:" + (String) hopMap.get("hopStr") };
						evcConn.put("hop", hopList);
						evcConn.put("connectionType", "SwitchedEthernet");

						String aDevName = (String) aDevice.get("TID");
						String zDevName = (String) zDevice.get("TID");
						if ((StringUtils.equals(zDeviceName, aDevName))
								|| (StringUtils.equals(zDeviceName, zDevName))) {
							String[] epList = { "ep:" + (String) hopMap.get("hopStr") };
							evcConn.put("epInstanceId", epList);
						}

						evcConn.put("ctagvlanmap", cTag);
						evcConn.put("stagVlanMap", cTag);
						String[] ckId = { nniName };
						evcConn.put("ckId", ckId);

						evcConn.put("device1", (Map<String, Object>) hopMap.get("device1"));
						evcConn.put("device2", (Map<String, Object>) hopMap.get("device2"));

						zNextNode = locA;
						zEndMplsDevice.putAll(aAllDev);
						tRecList.remove(i);
						connList.add(convertToLower(evcConn));
					}
				}
				if (tRecList.isEmpty()) {
					break;
				}
				if(recSize == tRecList.size()) {
					break;
				}
			}

			if (isNodeFound == false) {
				log.info("aEnd route last device and zEnd route 1st device are not same."
						+ " Create MPLS segment connection between '{} and {}", aNextNode, zNextNode);
				evcMPLSConn.put("device2", getHopDeviceInfo(zEndMplsDevice, ""));
				connList.add(convertToLower(evcMPLSConn));
			}
		}

		return connList;
	}

	private Map<String, Object> getHop(Map<String, Object> nniMap, Map<String, Object> aDev, Map<String, Object> aPort,
			String portBw, Map<String, Object> zDev, Map<String, Object> zPort /* , String cTag */) {
		log.info("=>getHop()");
		Map<String, Object> hopMap = new HashMap<String, Object>();

		String aShelf = StringUtils.isNotBlank((String) aPort.get("shelf")) ? (String) aPort.get("shelf") : "-1";
		String aSlot = StringUtils.isNotBlank((String) aPort.get("slot")) ? (String) aPort.get("slot") : "-1";
		String aSubSlot = StringUtils.isNotBlank((String) aPort.get("subSlot")) ? (String) aPort.get("subSlot") : "-1";
		String aPortNum = (String) aPort.get("id");
		String aPortType = (String) aPort.get("connector");
		String aDevName = (String) aPort.get("location");

		String zShelf = StringUtils.isNotBlank((String) zPort.get("shelf")) ? (String) zPort.get("shelf") : "-1";
		String zSlot = StringUtils.isNotBlank((String) zPort.get("slot")) ? (String) zPort.get("slot") : "-1";
		String zSubSlot = StringUtils.isNotBlank((String) zPort.get("subSlot")) ? (String) zPort.get("subSlot") : "-1";
		String zPortNum = (String) zPort.get("id");
		String zPortType = (String) zPort.get("connector");
		String zDevName = (String) zPort.get("location");

		log.info("Getting hop aDev: '{}' and zDev '{}'", aDevName, zDevName);
		if (StringUtils.isNotBlank(portBw))
			portBw = portBw.replace(" ", "");

		String aPortStr = aDevName + "/" + aShelf + "/" + aSlot + "/" + aSubSlot + "/" + aPortNum + "/" + aPortType
				+ "/" + portBw;
		String zPortStr = zDevName + "/" + zShelf + "/" + zSlot + "/" + zSubSlot + "/" + zPortNum + "/" + zPortType
				+ "/" + portBw;

		String hopStr = aPortStr + ":" + zPortStr;
		hopStr = hopStr.toLowerCase();

		hopMap.put("hopStr", hopStr);

		hopMap.put("device1", getHopDeviceInfo(aDev, (String) aPort.get("bw")));
		hopMap.put("device2", getHopDeviceInfo(zDev, (String) zPort.get("bw")));

		return hopMap;
	}

//	private Map<String,Object> getHopInfo(Map<String,Object> nniMap, Map<String,Object> aDev, Map<String,Object> aPort, Map<String,Object> zDev, Map<String,Object> zPort, String cTag){
//		Map<String,Object> hopMap = new HashMap<String,Object>();
//
//		String nniName = (String) nniMap.get("cktid");
//		if(StringUtils.isNotBlank(nniName))
//			nniName = nniName.toLowerCase();
//
//		return hopMap;
//	}
	private Map<String, Object> getHopDeviceInfo(Map<String, Object> device, String portSpeed) {
		Map<String, Object> devInfo = new LinkedHashMap<String, Object>();

		devInfo.put("deviceIdentifier", String.valueOf((String) device.get("TID")).toLowerCase());
		devInfo.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(String.valueOf((String) device.get("model")).toLowerCase()));
		devInfo.put("deviceCategory", (String) device.get("deviceRole"));
		devInfo.put("portSpeed", (StringUtils.isNotBlank(portSpeed)) ? portSpeed.toLowerCase() : "");
		devInfo.put("shelfMenId", (String) device.get("topologyName"));
		devInfo.put("shelfRelayRack", String.valueOf((String) device.get("relayrck")).toLowerCase());
		devInfo.put("shelfMacId", (String) device.get("MACADDRESS"));
		devInfo.put("shelfUrl", (String) device.get("IPV4CONSOLE1"));
		// devInfo.put("shelfCoId", "");//TBD: More info required to handle this???
		devInfo.put("shelfLata", (String) device.get("clli_lata"));

		return devInfo;
	}

	private Map<String, Object> getUNIInfo(Map<String, Object> uniMap, Map<String, Object> portMap, String devRole,
			String portBW, String accessType) {
		log.info("=>getUNIInfo()");
		Map<String, Object> uni = new LinkedHashMap<String, Object>();

		String shelf = StringUtils.isNotBlank((String) portMap.get("shelf")) ? (String) portMap.get("shelf") : "-1";
		String slot = StringUtils.isNotBlank((String) portMap.get("slot")) ? (String) portMap.get("slot") : "-1";
		String subSlot = StringUtils.isNotBlank((String) portMap.get("subSlot")) ? (String) portMap.get("subSlot")
				: "-1";
		String portNum = (String) portMap.get("id");
		String portType = (String) portMap.get("connector");

		if (StringUtils.isNotBlank(portBW))
			portBW = portBW.replace(" ", "");

		String uniId = "uni:customer:" + (String) portMap.get("location") + "/" + shelf + "/" + slot + "/" + subSlot
				+ "/" + portNum + "/" + portType + "/" + portBW;
		log.info("UNI port: '{}'", uniId);
		uniId = String.valueOf(uniId).toLowerCase();
		String uniCktId = (String) uniMap.get("cktid");
		uniCktId = uniCktId.replace(" ", "");
		uni.put("uniServiceInstanceId", uniCktId);
		uni.put("uniId", uniId);
		uni.put("uniPortSpeed", portBW);
		uni.put("deviceRole", devRole);
		uni.put("isNewUNI", "true");
		uni.put("portBasedRateLimited", false);

		Map<String, Object> uniLinks = new LinkedHashMap<String, Object>();
		uniLinks.put("listOfPhysicalLinkId", "");
		uniLinks.put("listOfPhysicalLinkPl", accessType);
		uniLinks.put("listOfPhysicalLinkFs", "disable");
		uniLinks.put("listOfPhysicalLinkPt", "disable");
		List<Map<String, Object>> luniLinks = new ArrayList<>() {
			{
				add(uniLinks);
			}
		};
		uni.put("uniLinks", luniLinks);

		return uni;
	}

	public ResponseStatus isCircuitExist(String circuitName, String circuitType) {
		log.info("=> EthernetOrderService:isCircuitExist: START");
		ResponseStatus resp = new ResponseStatus();
		resp.setCode(200);
		resp.setMessage("Failed");
		Map<String, Object> circuitExist = new HashMap<String, Object>();
		circuitExist.put("exists", "false");

		try (Session session = driver.session()) {

			circuitName = circuitName.toUpperCase();
			Transaction tx = session.beginTransaction();
			String aliasCktId = circuitName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
			String circuitQuery = "";

			if (StringUtils.equalsAnyIgnoreCase(circuitType, "UNI")) {
				circuitQuery = "MATCH (ne: UNIConnection) WHERE ne.cktid = '" + circuitName + "'"
						+ " OR ne.aliasCktId = '" + aliasCktId + "' RETURN ne.cktid as circuitName";
			} else if (StringUtils.equalsAnyIgnoreCase(circuitType, "EVC")) {
				circuitQuery = "MATCH (ne: EVCConnection) WHERE ne.name = '" + circuitName + "'"
						+ " OR ne.aliasCktId = '" + aliasCktId + "' "
						+ " OPTIONAL MATCH (ne)-[ar:AEND]-(aUni:UNIConnection) WHERE aUni.deletedTimeStamp is null"
						+ " AND ar.deletedTimeStamp is null"
						+ " OPTIONAL MATCH (ne)-[zr:ZEND]-(zUni:UNIConnection)  WHERE zUni.deletedTimeStamp is null "
						+ " AND zr.deletedTimeStamp is null"
						+ " RETURN ne.name as circuitName, ne.ncCode as ncCode,  aUni.cktid as aCktId, ar.CTAG as aCTag,"
						+ " ar.STAG as aSTag, zUni.cktid as zCktId, zr.CTAG as zCTag, zr.STAG as zSTag";
			} else {
			}

			log.info("Circuit exist Query: '{}'", circuitQuery);

			List<Record> cktList = tx.run(circuitQuery).list();

			if (cktList != null && !cktList.isEmpty()) {
				Record cktRec = cktList.get(0);
				String cktName = cktRec.get("circuitName").asString();

				if (StringUtils.isNotBlank(cktName)) {
					circuitExist.put("exists", "true");
					log.info("'{}' Circuit exist with circuitName: '{}'", circuitType, circuitName);
					if (StringUtils.equalsAnyIgnoreCase(circuitType, "EVC")) {
						circuitExist.put("aCktId", cktRec.get("aCktId").asString());
						String aCtag = cktRec.get("aCTag").asString();
						circuitExist.put("aCTag", cktRec.get("aCTag").asString());
						circuitExist.put("aSTag", cktRec.get("aSTag").asString());
						circuitExist.put("zCktId", cktRec.get("zCktId").asString());
						circuitExist.put("zCTag", cktRec.get("zCTag").asString());
						circuitExist.put("zSTag", cktRec.get("zSTag").asString());
						circuitExist.put("ncCode", cktRec.get("ncCode").asString());
					}
					resp.setCode(200);
					resp.setMessage("Found the circuit");
				} else {
					circuitExist.put("exists", "false");
					log.info("'{}' Circuit Not exist with circuitName: '{}'", circuitType, circuitName);
					resp.setCode(404);
					resp.setMessage("Circuit not found");
				}
			} else {
				circuitExist.put("exists", "false");
				log.info("'{}' Circuit Not exist with circuitName: '{}'", circuitType, circuitName);
				resp.setCode(404);
				resp.setMessage("Circuit not found");
			}
		} catch (Exception ex) {
			log.error("isCircuitExist: Caught-Exception '{}'", ex.getMessage());
			resp.setCode(500);
			resp.setMessage("Failed");
		}
		resp.setData(circuitExist);
		return resp;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> convertToLower(Map<String, Object> source) {
		/** Commented temporarily to pass the response as it is */
		/*
		 * Map<String, Object> destMap = new HashMap<>(); // Iterate through source
		 * entries for (Map.Entry<String, Object> entry : source.entrySet()) { String
		 * key = entry.getKey(); Object value = entry.getValue(); // Skip arrays and
		 * objects if (value == null) { continue; } Object attributeValue = null; if
		 * (value instanceof Map) { Map<String, Object> childMap =
		 * convertToLower((Map<String, Object>) value); attributeValue = childMap; }
		 * else { attributeValue = String.valueOf(value).toLowerCase(); }
		 * 
		 * // Adding attributes to equipment query and equipAttr map destMap.put(key,
		 * attributeValue); }
		 */
		return source;
	}

	public ResponseStatus saveNMIOrderInfo(Map<String, Object> assignedInventoryBody) {
		log.info("=>EthernetOrderService:saveNMIOrderInfo: START");
		log.info("Save Ethernet Order Info: {}", assignedInventoryBody.toString());
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String nmiOrderNumber = (String) assignedInventoryBody.get("nmiOrderNumber");

		// Initialize a new map to hold flattened properties
		Map<String, Object> flattenedProperties = new HashMap<>();
		flattenedProperties.put("nmiOrderNumber", nmiOrderNumber);

		// Flatten the entire structure dynamically
		flattenMap("", assignedInventoryBody, flattenedProperties);

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			// 1. Use MERGE to ensure that the NMIOrder node is either matched or created
			StringBuilder mergeOrderQuery = new StringBuilder("MERGE (n:NMIOrder {nmiOrderNumber: $nmiOrderNumber})");
			mergeOrderQuery.append(" SET ");
			String properties = flattenedProperties.entrySet().stream()
					.filter(entry -> !entry.getKey().equals("nmiOrderNumber"))
					.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey()).collect(Collectors.joining(", "));
			mergeOrderQuery.append(properties);
			String finalQuery = mergeOrderQuery.toString();
			Instant start = Instant.now();
			tx.run(finalQuery, flattenedProperties);
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute saveNMIOrderInfo : " + timeElapsed.toMillis() + " ms");
			// Commit the transaction after creating the node
			tx.commit();

			// Set response status to success
			response.setCode(200);
			response.setMessage("NMI order saved successfully.");
		} catch (Exception ex) {
			log.error("Error saving NMI order: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save NMI order: " + ex.getMessage());
		}

		return response;
	}

	public static void flattenMap(String prefix, Map<String, Object> currentMap,
			Map<String, Object> flattenedProperties) {

		ObjectMapper objectMapper = new ObjectMapper();

		// Configure the ObjectMapper to exclude null or empty properties during
		// serialization
		objectMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

		for (Map.Entry<String, Object> entry : currentMap.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();

			// Skip if the value is null or an empty string
			if (value == null || (value instanceof String && ((String) value).isEmpty())) {
				continue;
			}

			// If the value is a Map, check if it's empty before serializing
			if (value instanceof Map) {
				Map<String, Object> nestedMap = (Map<String, Object>) value;

				// Check if all values in the nested map are null or empty
				boolean allValuesEmpty = true;
				for (Object nestedValue : nestedMap.values()) {
					if (nestedValue != null && !(nestedValue instanceof String && ((String) nestedValue).isEmpty())
							&& !(nestedValue instanceof List && ((List<?>) nestedValue).isEmpty())) {
						allValuesEmpty = false;
						break;
					}
				}
				// Skip entire map if all values are empty or null
				if (allValuesEmpty) {
					continue;
				}
				// Serialize the entire map as a JSON string
				try {
					String jsonValue = objectMapper.writeValueAsString(nestedMap);
					flattenedProperties.put(prefix + key, jsonValue);
				} catch (JsonProcessingException e) {
					log.error("Error serializing map: {}", e.getMessage());
				}
			}
			// If the value is a List, check if it's empty before serializing
			else if (value instanceof List) {
				List<?> list = (List<?>) value;

				// Skip empty lists or lists containing only null or empty items
				boolean allItemsEmpty = true;
				for (Object item : list) {
					if (item != null && !(item instanceof String && ((String) item).isEmpty())
							&& !(item instanceof Map && ((Map<?, ?>) item).isEmpty())) {
						allItemsEmpty = false;
						break;
					}
				}

				// Skip list if all values are null or empty
				if (allItemsEmpty) {
					continue;
				}

				try {
					String jsonValue = objectMapper.writeValueAsString(list);
					flattenedProperties.put(prefix + key, jsonValue);
				} catch (JsonProcessingException e) {
					log.error("Error serializing list: {}", e.getMessage());
				}
			}
			// If it's a primitive or simple data type, store it as is
			else {
				flattenedProperties.put(prefix + key, value);
			}
		}
	}

	public ResponseStatus getNMIOrderInfo(String nmiOrderNumber) {
		log.info("=> EthernetOrderService: getNMIOrderInfo: START");
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		try (Session session = driver.session()) {

			String query = "MATCH (n:NMIOrder {nmiOrderNumber: $nmiOrderNumber}) " + "RETURN n";
			Instant start = Instant.now();
			Result result = session.run(query, Map.of("nmiOrderNumber", nmiOrderNumber));
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute getNMIOrderInfo : " + timeElapsed.toMillis() + " ms");
			Map<String, Object> orderProperties = new HashMap<String, Object>();

			// Check if result exists
			while (result.hasNext()) {
				Record record = result.next();
				orderProperties = record.get("n").asMap();
			}
			if (orderProperties != null && !orderProperties.isEmpty()) {
				response.setCode(200);
				response.setMessage("NMI order retrieved successfully.");
				response.setData(orderProperties);
			} else {
				response.setCode(404);
				response.setMessage("NMI order not found for NMI Order Number: " + nmiOrderNumber);
			}
		} catch (Exception ex) {
			log.error("Error retrieving NMI order for NMI Order Number '{}': {}", nmiOrderNumber, ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to retrieve NMI order: " + ex.getMessage());
		}

		log.info("<= EthernetOrderService: getNMIOrderInfo: END");
		return response;
	}

	public ResponseStatus saveEthernetOrderInfo(Map<String, Object> assignedInventoryBody) {
		log.info("=>EthernetOrderService:saveEthernetOrderInfo: START");
		log.info("Save Ethernet Order Info: {}", assignedInventoryBody.toString());

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		Map<String, Object> flattenedProperties = new HashMap<>();

		try {
			// Extract circuitId from the object
			String circuitId = (String) assignedInventoryBody.get("circuitId");
			if (circuitId == null || circuitId.isEmpty()) {
				response.setCode(400);
				response.setMessage("Missing circuitId in request");
				return response;
			}

			String aliasCktId = circuitId.replaceAll("[^A-Z0-9]", "");
			flattenedProperties.put("circuitId", circuitId);
			flattenedProperties.put("aliasCktId", aliasCktId);

			// Extract productPayload object
			Map<String, Object> productPayload = (Map<String, Object>) assignedInventoryBody.get("productPayload");
			if (productPayload == null) {
				response.setCode(400);
				response.setMessage("Missing productPayload in request");
				return response;
			}

			// Extract and set externalId from productPayload
			String externalId = (String) productPayload.get("externalId");
			if (externalId == null || externalId.trim().isEmpty()) {
				response.setCode(204);
				response.setMessage("External ID is missing in productPayload.");
				return response;
			}

			flattenedProperties.put("externalId", externalId);
			flattenedProperties.put("orderNumber", externalId);

			// Convert productPayload to JSON string and save as property
			ObjectMapper mapper = new ObjectMapper();
			String productPayloadJson = mapper.writeValueAsString(productPayload);
			flattenedProperties.put("productPayload", productPayloadJson);
			// Extract and save engineeringFacilities as JSON
			List<Map<String, Object>> engineeringFacilities = (List<Map<String, Object>>) assignedInventoryBody
					.get("engineeringFacilities");
			if (engineeringFacilities != null && !engineeringFacilities.isEmpty()) {
				try {
					String engineeringFacilitiesJson = mapper.writeValueAsString(engineeringFacilities);
					flattenedProperties.put("engineeringFacilities", engineeringFacilitiesJson);
				} catch (JsonProcessingException e) {
					log.error("Failed to convert engineeringFacilities to JSON", e);
					response.setCode(500);
					response.setMessage("Failed to serialize engineeringFacilities: " + e.getMessage());
					return response;
				}
			}

//	        try {
//	            Map<String, Object> payLoad = mapper.readValue(productPayloadJson, new TypeReference<Map<String, Object>>() {});
//	            System.out.println("payLoad" + payLoad);
//	        } catch (Exception e) {
//	            log.error("Failed to parse productPayload JSON", e);
//	        }
//	        JSONObject jsonObject = new JSONObject(productPayloadJson);
//	        System.out.println(jsonObject);

			// Save to Neo4j
			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();

				StringBuilder mergeQuery = new StringBuilder("MERGE (n:EthernetOrder {circuitId: $circuitId}) SET ");
				String propertyAssignments = flattenedProperties.entrySet().stream()
						.filter(entry -> !"circuitId".equals(entry.getKey()))
						.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
						.collect(Collectors.joining(", "));
				mergeQuery.append(propertyAssignments);

				Instant start = Instant.now();
				tx.run(mergeQuery.toString(), flattenedProperties);
				tx.commit();
				Instant end = Instant.now();

				log.info("Time taken by query to execute saveEthernetOrderInfo: {} ms",
						Duration.between(start, end).toMillis());
				response.setCode(200);
				response.setMessage("EthernetOrder saved successfully.");
			}

		} catch (ServiceUnavailableException ex) {
			log.error("Service unavailable: {}", ex.getMessage());
			response.setCode(404);
			response.setMessage("Service unavailable: " + ex.getMessage());
		} catch (Exception ex) {
			log.error("Error saving EthernetOrder: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save EthernetOrder: " + ex.getMessage());
		}

		return response;
	}

	public ResponseStatus setCircuitStatus(Map<String, Object> assignedInventoryBody) {
		log.info("=>EthernetOrderService:setCircuitStatus: START");
		log.info("Update Circuit Status: {}", assignedInventoryBody.toString());
		ResponseStatus response = new ResponseStatus();
		Map<String, String> responseData = new HashMap<>();
		response.setCode(500);
		response.setMessage("Failed");

		String circuitType = (String) assignedInventoryBody.get("circuitType");
		String circuitName = (String) assignedInventoryBody.get("circuitName");
		circuitName = circuitName.toUpperCase();
		String status = (String) assignedInventoryBody.get("status");
		String aliasCktId = circuitName.replaceAll("[^A-Z0-9]", "");
		String vpnId = (String) assignedInventoryBody.get("vpnId");

		if (circuitType.contains("UNI")) {
			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();
				String checkCircuitQuery = "MATCH (n:UNIConnection) "
						+ "WHERE n.cktid = $circuitName OR n.aliasCktId = $aliasCktId " + "RETURN n LIMIT 1";

				Result result = tx.run(checkCircuitQuery, Map.of("circuitName", circuitName, "aliasCktId", aliasCktId));
				log.info("checkCircuitQuery" + checkCircuitQuery);

				if (result.hasNext()) {

					// Update the status in UNIConnection
					String updateCircuitStatusQuery = "MATCH (n:UNIConnection) "
							+ "WHERE n.cktid = $circuitName OR n.aliasCktId = $aliasCktId " + "SET n.status = $status "
							+ "RETURN n LIMIT 1";
					tx.run(updateCircuitStatusQuery,
							Map.of("circuitName", circuitName, "aliasCktId", aliasCktId, "status", status));
					log.info("updateCircuitStatusQuery" + updateCircuitStatusQuery);

					// Update the status in AllCircuits
					String updateAllCircuitsQuery = "MATCH (n:allCircuits) "
							+ "WHERE n.circuitName = $circuitName OR n.aliasCktId = $aliasCktId "
							+ "SET n.status = $status " + "RETURN n LIMIT 1";
					tx.run(updateAllCircuitsQuery,
							Map.of("circuitName", circuitName, "aliasCktId", aliasCktId, "status", status));
					log.info("updateAllCircuitsQuery" + updateAllCircuitsQuery);

					// Update Device/NID status
					if (StringUtils.equals(status, "In Service")) {

						String deviceQuery = "MATCH (n:UNIConnection)-[:CONNECTED_TO]->(up:EquipmentPort)-[:COMPONENT_OF*]->(ne:Equipment)  "
								+ "WHERE n.cktid = $circuitName OR n.aliasCktId = $aliasCktId "
								+ "OPTIONAL MATCH (up)-[:XCONNECT]->(np:EquipmentPort)<-[:CONNECTED_TO]-(nmi:NNIConnection)"
								+ "RETURN ne.TID AS name, ne.node_id AS nodeId, ne.base_heci AS baseHeci, ne.provisionStatus AS status, nmi.cktid as nmiName LIMIT 1";

						log.info("Device Query" + deviceQuery);

						Result devResult = tx.run(deviceQuery,
								Map.of("circuitName", circuitName, "aliasCktId", aliasCktId));

						if (devResult.hasNext()) {
							Record devRec = devResult.next();
							String devName = devRec.get("name").asString();
							String nodeId = devRec.get("nodeId").asString();
							String baseHeci = devRec.get("baseHeci").asString();
							String devStatus = devRec.get("status").asString();
							String nmiName = devRec.get("nmiName").asString();

							if (!StringUtils.equalsIgnoreCase(devStatus, "active")) {
								Map<String, Object> deviceStatusMap = new HashMap<>();
								deviceStatusMap.put("status", "active");
								deviceStatusMap.put("deviceName", devName);
								deviceStatusMap.put("nodeId", nodeId);
								deviceStatusMap.put("baseHeci", baseHeci);
								response = updateDeviceStatus(deviceStatusMap, tx);
							}
							updateNMIStatus(nmiName,tx);
						}
					}

					tx.commit();

					response.setMessage("UNI Circuit status updated successfully.");
					response.setCode(200);
					responseData.put("status", "Success");
					response.setData(responseData);
				} else {
					// Circuit does not exist
					response.setMessage("No matching UNI circuit found with the provided circuit name");
					response.setCode(404);
					responseData.put("status", "Fail");
					response.setData(responseData);
					return response;
				}
			} catch (Exception e) {
				response.setMessage("Error updating UNI circuit status: " + e.getMessage());
				response.setCode(500);
				responseData.put("status", "Fail");
				response.setData(responseData);
				return response;
			}
		} else if (circuitType.contains("EVC")) {
			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();
				String checkCircuitQuery = "MATCH (n:EVCConnection) "
						+ "WHERE n.serviceName = $circuitName OR n.aliasCktId = $aliasCktId  RETURN n LIMIT 1";

				Result result = tx.run(checkCircuitQuery, Map.of("circuitName", circuitName, "aliasCktId", aliasCktId));
				log.info("checkCircuitQuery" + checkCircuitQuery);

				if (result.hasNext()) {
					// Update the status in EVCConnection
					String updateCircuitStatusQuery = "MATCH (n:EVCConnection) WHERE n.serviceName = $circuitName OR n.aliasCktId = $aliasCktId "
							+ "SET n.provisionStatus = $status "
							+ (vpnId != null ? ", n.vpnId = $vpnId " : "")
							+ "RETURN n LIMIT 1";
					 Map<String, Object> params = new HashMap<>();
			            params.put("circuitName", circuitName);
			            params.put("aliasCktId", aliasCktId);
			            params.put("status", status);
			            if (vpnId != null) {
			                params.put("vpnId", vpnId);
			            }

			            tx.run(updateCircuitStatusQuery, params);
					log.info("updateCircuitStatusQuery" + updateCircuitStatusQuery);

					// Update the status in allServices
					String updateAllServicesQuery = "MATCH (n:allServices) "
							+ "WHERE n.serviceName = $circuitName OR n.aliasCktId = $aliasCktId "
							+ "SET n.provisionStatus = $status " 
							+ (vpnId != null ? ", n.vpnId = $vpnId " : "")
							+ "RETURN n LIMIT 1";
					tx.run(updateAllServicesQuery, params);
					log.info("updateAllCircuitsQuery" + updateAllServicesQuery);

					tx.commit();

					response.setMessage("EVC/OVC Circuit status updated successfully.");
					response.setCode(200);
					responseData.put("status", "Success");
					response.setData(responseData);
				} else {
					// Circuit does not exist
					response.setMessage("No matching EVC/OVC circuit found with the provided circuit name");
					response.setCode(404);
					responseData.put("status", "Fail");
					response.setData(responseData);
					return response;
				}
			} catch (Exception e) {
				response.setMessage("Error updating EVC/OVC circuit status: " + e.getMessage());
				response.setCode(500);
				responseData.put("status", "Fail");
				response.setData(responseData);
				return response;
			}
		}

		return response;
	}

	public ResponseStatus updateDeviceStatus(Map<String, Object> requestObj, Transaction tx ) {
		log.info("=>EthernetOrderService:updateDeviceStatus: START");
		log.info("Update Device Status: {}", requestObj.toString());
		ResponseStatus response = new ResponseStatus();
		Map<String, String> responseData = new HashMap<>();
		response.setCode(500);
		response.setMessage("Failed");

		String devName = (String) requestObj.get("deviceName");
		String nodeId = (String) requestObj.get("nodeId");
		String baseHeci = (String) requestObj.get("baseHeci");
		devName = devName.toUpperCase();
		String status = (String) requestObj.get("status");
		String provisionFlag = "false";

		if ((StringUtils.equals(status, "In Service")) || (StringUtils.equals(status, "Restricted"))
				|| (StringUtils.equals(status, "Planned for Removal"))
				|| (StringUtils.equals(status, "Pending Disconnect"))
				|| (StringUtils.equals(status, "Activated")) || (StringUtils.equals(status, "Configured"))) {
			provisionFlag = "true";
		}
			String deviceQuery = "MATCH (ne:Equipment) "
					+ " WHERE ne.TID = $deviceName and ne.node_id = $nodeId and ne.base_heci = $baseHeci "
					+ "RETURN ne LIMIT 1";

			log.info("checkCircuitQuery" + deviceQuery);

			Map<String, Object> devParams = new HashMap<>();
			devParams.put("deviceName", devName);
			devParams.put("nodeId", nodeId);
			devParams.put("baseHeci", baseHeci);
			Result result = tx.run(deviceQuery, devParams);

			if (result.hasNext()) {

				// Update the status in UNIConnection
				String updateDevStatusQuery = "MATCH (ne:Equipment) "
						+ " WHERE ne.TID = $deviceName and ne.node_id = $nodeId and ne.base_heci = $baseHeci "
						+ " OPTIONAL MATCH (ne)<-[:COMPONENT_OF*]-(np:Equipment)<-[:CONNECTED_TO]-(nni:NNIConnection)"
						+ " WHERE NOT ne.provisionStatus = $status "
						+ " OPTIONAL MATCH (ac:allCircuits{cktid:nni.cktid}) "
						+ " SET ne.provisionStatus = $status, ne.isProvisioned = $provisionFlag, "
						+ " nni.provisionStatus = $status, ac.provisionStatus = $status "
						+ "RETURN ne LIMIT 1";
				log.info("update Device Status Query" + updateDevStatusQuery);
				tx.run(updateDevStatusQuery,
						Map.of("deviceName", devName, "nodeId", nodeId, "baseHeci", baseHeci, "status", status, "provisionFlag",provisionFlag));

				// Update the status in allDevices
				String updateAllDevQuery = "MATCH (ne:allDevices) "
						+ "WHERE ne.TID = $deviceName and ne.node_id = $nodeId and ne.base_heci = $baseHeci "
						+ "SET ne.provisionStatus = $status, ne.isProvisioned = $provisionFlag "
						+ "RETURN ne LIMIT 1";
				tx.run(updateAllDevQuery,
						Map.of("deviceName", devName, "nodeId", nodeId, "baseHeci", baseHeci, "status", status, "provisionFlag",provisionFlag));
				log.info("updateAllDevQuery" + updateAllDevQuery);

				log.info("Equipment '" + devName + "' status '" + status + "' updated successfully.");
				response.setMessage("Equipment '" + devName + "' status '" + status + "' updated successfully.");
				response.setCode(200);
				responseData.put("status", "Success");
				response.setData(responseData);
			} else {
				// Circuit does not exist
				response.setMessage("No matching Equipment found with the name '" + devName + "'");
				response.setCode(404);
				responseData.put("status", "Fail");
				response.setData(responseData);
				return response;
			}
		return response;
	}

	private void updateNMIStatus(String nmiName, Transaction tx ) {
		log.info("=>EthernetOrderService:updateNMIStatus: START");
		log.info("Update NMI Status: {}", nmiName);

		String aliasCktId = nmiName.replaceAll("[^a-zA-Z0-9]", "");
		String provisionStatus = "In Service";

		String nmiStatusQuery = "MATCH (nmi:NNIConnection) "
					+ " WHERE nmi.cktid = $nmiName or nmi.aliasCktId = $aliasCktId  "
					+ " MATCH (ac:allCircuits{aliasCktId:nmi.aliasCktId}) "
					+ " SET nmi.provisionStatus = $provisionStatus, "
					+ "  nmi.acivityCode = $acivityCode, "
					+ "  ac.lastUpdated = datetime(), "
					+ "  ac.provisionStatus = $provisionStatus, "
					+ "  ac.acivityCode = $acivityCode,"
					+ "  ac.lastUpdated = datetime() "
					+ " RETURN nmi LIMIT 1";

			log.info("nmiStatusQuery {}", nmiStatusQuery);

			Map<String, Object> nmiParams = new HashMap<>();
			nmiParams.put("nmiName", nmiName);
			nmiParams.put("aliasCktId", aliasCktId);
			nmiParams.put("acivityCode", "IE");
			nmiParams.put("provisionStatus", provisionStatus);
			Result result = tx.run(nmiStatusQuery, nmiParams);

			if (result.hasNext()) {
				log.info("Successfully updated provisioning status: In service for NMI {}",nmiName);
			} else {
				log.warn("Failed to update provisioning Status for NMI {}", nmiName);
			}
		return;
	}

	public ResponseStatus setDeviceStatus(Map<String, Object> requestObj) {
		log.info("=>EthernetOrderService:setDeviceStatus: START");
		ResponseStatus response = new ResponseStatus();
		Map<String, String> responseData = new HashMap<>();
		response.setCode(500);
		response.setMessage("Failed");

		Transaction tx = null;
		try (Session session = driver.session()) {
			tx = session.beginTransaction();
			response = updateDeviceStatus(requestObj,tx);
			if(response.getCode() == 200)
				tx.commit();
		} catch (Exception e) {
			response.setMessage("Error updating Equipment status: " + e.getMessage());
			response.setCode(500);
			responseData.put("status", "Fail");
			response.setData(responseData);
			return response;
		} finally {
			tx.close();
		}
		return response;
	}

	public ResponseStatus saveUNIOrderInfo(Map<String, Object> assignedInventoryBody) {
		log.info("=> EthernetOrderService: saveUNIOrderInfo: START");
		log.info("Save UNI Order Info: {}", assignedInventoryBody.toString());
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String orderNumber = (String) assignedInventoryBody.get("orderNumber");

		String uniCircuitId = "";
		Map<String, Object> customerConnection = (Map<String, Object>) assignedInventoryBody.get("customerConnection");
		if (customerConnection != null && customerConnection.get("connectionName") instanceof String) {
			uniCircuitId = (String) customerConnection.get("connectionName");
		}

		String aliasCktId = uniCircuitId.replaceAll("[^A-Z0-9]", "");

		Map<String, Object> flattenedProperties = new HashMap<>();
		flattenedProperties.put("orderNumber", orderNumber);
		flattenedProperties.put("uniCircuitId", uniCircuitId);
		flattenedProperties.put("aliasCktId", aliasCktId);

		// Flatten the entire structure dynamically
		flattenMap("", assignedInventoryBody, flattenedProperties);
		String buildStatus = (String) flattenedProperties.get("buildStatus");

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			String circuitQuery = "MATCH (n:UNIOrder {uniCircuitId: $uniCircuitId}) RETURN n";
			Map<String, Object> paramMap = new HashMap<>();
			paramMap.put("uniCircuitId", aliasCktId);
			Result circuitResult = tx.run(circuitQuery, paramMap);

			if (!circuitResult.hasNext()) {
				// Circuit not found, prepare input data and set buildStatus to "inProgress"
				// only if not passed as "completed"
				if (StringUtils.isEmpty(buildStatus) || StringUtils.equalsIgnoreCase(buildStatus, "inProgress")) {
					flattenedProperties.put("buildStatus", "inProgress");
				} else if (StringUtils.equalsIgnoreCase(buildStatus, "completed")) {
					flattenedProperties.put("buildStatus", "completed");
				}

				// MERGE on uniOrderNumber
				StringBuilder mergeOrderQuery = new StringBuilder("MERGE (n:UNIOrder {uniCircuitId: $uniCircuitId})");
				mergeOrderQuery.append(" SET ");
				String properties = flattenedProperties.entrySet().stream()
						.filter(entry -> !entry.getKey().equals("uniCircuitId"))
						.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
						.collect(Collectors.joining(", "));
				mergeOrderQuery.append(properties);
				String finalQuery = mergeOrderQuery.toString();

				Instant start = Instant.now();
				tx.run(finalQuery, flattenedProperties);
				Instant end = Instant.now();
				Duration timeElapsed = Duration.between(start, end);
				log.info("Time taken by query to execute saveUNIOrderInfo: " + timeElapsed.toMillis() + " ms");

				tx.commit();
				response.setCode(200);
				response.setMessage("UNI order saved successfully.");
			} else {
				// Circuit found, check existing buildStatus
				Record record = circuitResult.next();
				Node existingNode = record.get("n").asNode();
				String existingBuildStatus = existingNode.get("buildStatus").asString("");

				if (StringUtils.isEmpty(existingBuildStatus)
						|| StringUtils.equalsIgnoreCase(existingBuildStatus, "inProgress")) {
					flattenedProperties.put("buildStatus", "inProgress");

					// Create UNIOrder node
					StringBuilder mergeOrderQuery = new StringBuilder(
							"MERGE (n:UNIOrder {uniCircuitId: $uniCircuitId})");
					mergeOrderQuery.append(" SET ");
					String properties = flattenedProperties.entrySet().stream()
							.filter(entry -> !entry.getKey().equals("uniCircuitId"))
							.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
							.collect(Collectors.joining(", "));
					mergeOrderQuery.append(properties);
					String finalQuery = mergeOrderQuery.toString();
					tx.run(finalQuery, flattenedProperties);
					tx.commit();

					response.setCode(200);
					response.setMessage("UNI order saved successfully.");
				} else {
					response.setCode(400);
					response.setMessage("Invalid buildStatus: " + buildStatus);
				}
			}
		} catch (Exception ex) {
			log.error("Error saving UNI order: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save UNI order: " + ex.getMessage());
		}

		return response;
	}

	public ResponseEntity<?> getUNIOrderInfo(String searchKey, boolean isCircuit) {
		log.info("=> EthernetOrderService: getUNIOrderInfo: START");

		String cktName = searchKey;
		if (isCircuit) {
			searchKey = searchKey.replaceAll("[^a-zA-Z0-9]", "");
		}

		try (Session session = driver.session()) {
			List<Map<String, Object>> circuitInfoList = new ArrayList<>();
			String orderNumber = null;
			Map<String, Object> params = Map.of("searchKey", searchKey);
			String query;
			ObjectMapper mapper = new ObjectMapper();

			if (isCircuit) {
				// Circuit-based: Try UNIOrder
				query = "MATCH (n:UNIOrder {aliasCktId: $searchKey}) RETURN n";
				Result result = session.run(query, params);

				while (result.hasNext()) {
					Record record = result.next();
					Map<String, Object> data = record.get("n").asMap();
					Map<String, Object> transformed = new HashMap<>();

					for (Map.Entry<String, Object> entry : data.entrySet()) {
						transformed.put(entry.getKey(), parseJson(entry.getValue(), mapper));
					}

					circuitInfoList.add(transformed);
					if (orderNumber == null && data.get("orderNumber") != null) {
						orderNumber = data.get("orderNumber").toString();
					}
				}

				if (circuitInfoList.isEmpty()) {
					// Try EthernetOrder
					query = "MATCH (n:EthernetOrder {aliasCktId: $searchKey}) RETURN n";
					result = session.run(query, params);

					while (result.hasNext()) {
						Record record = result.next();
						Map<String, Object> data = record.get("n").asMap();
						Map<String, Object> transformed = transformEthernetOrderCircuit(data, mapper);
						circuitInfoList.add(transformed);
						if (orderNumber == null && data.get("orderNumber") != null) {
							orderNumber = data.get("orderNumber").toString();
						}
					}
				}

				if (!circuitInfoList.isEmpty()) {
					Map<String, Object> data = new HashMap<>();
					data.put("orderNumber", orderNumber != null ? orderNumber : "");
					data.put("locations", circuitInfoList.size());
					data.put("circuitInfo", circuitInfoList);
					data.put("isEvcOrder", false);

					Map<String, Object> responseMap = new LinkedHashMap<>();
					responseMap.put("code", 200);
					responseMap.put("message", "Order info fetched successfully");
					responseMap.put("data", data);

					return new ResponseEntity<>(responseMap, HttpStatus.OK);

				} else {

					Map<String, Object> responseMap = new LinkedHashMap<>();
					query = "MATCH (n:allCircuits {aliasCktId: $searchKey}) RETURN n";
					Result cktResult = session.run(query, params);
					if (cktResult.hasNext()) {
						log.info("Migrated circuit '{}' found in allCircuits", cktName);
						responseMap.put("code", 412);
						responseMap.put("message", "Migrated Circuit present with the circuit name: '" + cktName + "'");

						return new ResponseEntity<>(responseMap, HttpStatus.PRECONDITION_FAILED);
					}
					//TBD: This part needs to be updated when the feature to change the EVC is implemented
					query = "MATCH (n:allServices {aliasCktId: $searchKey}) RETURN n";
					cktResult = session.run(query, params);
					if (cktResult.hasNext()) {
						log.info("Migrated circuit '{}' found in allServices", cktName);
						responseMap.put("code", 412);
						responseMap.put("message", "Migrated Circuit present with the circuit name: '" + cktName + "'");
						return new ResponseEntity<>(responseMap, HttpStatus.PRECONDITION_FAILED);
					}

					// Try EVC order
					Map<String, Object> evcInfo = getEvcOrderInfoV2(searchKey, session);
					evcInfo.put("isEvcOrder", true);

					responseMap.put("code", 200);
					responseMap.put("message", "EVC Order info fetched successfully");
					responseMap.put("data", evcInfo);

					return new ResponseEntity<>(responseMap, HttpStatus.OK);
				}

			} else {
				// Order-based: try EthernetOrder first
				query = "MATCH (n:EthernetOrder {orderNumber: $searchKey}) RETURN n";
				Result result = session.run(query, params);

				while (result.hasNext()) {
					Record record = result.next();
					Map<String, Object> data = record.get("n").asMap();
					String aliasCktId = (String) data.get("aliasCktId");

					if (StringUtils.isNotBlank(aliasCktId)) {
						Map<String, Object> uniParams = Map.of("aliasCktId", aliasCktId);
						String uniQuery = "MATCH (u:UNIOrder {aliasCktId: $aliasCktId}) RETURN u";
						Result uniResult = session.run(uniQuery, uniParams);

						if (uniResult.hasNext()) {
							Record uniRecord = uniResult.next();
							Map<String, Object> uniData = new HashMap<>();
							for (Map.Entry<String, Object> entry : uniRecord.get("u").asMap().entrySet()) {
								uniData.put(entry.getKey(), parseJson(entry.getValue(), mapper));
							}
							circuitInfoList.add(uniData);
						} else {
							Map<String, Object> transformed = transformEthernetOrderCircuit(data, mapper);
							circuitInfoList.add(transformed);
						}
					}

					if (orderNumber == null && data.get("orderNumber") != null) {
						orderNumber = data.get("orderNumber").toString();
					}
				}

				if (circuitInfoList.isEmpty()) {
					// Fallback to UNIOrder by orderNumber
					query = "MATCH (n:UNIOrder {orderNumber: $searchKey}) RETURN n";
					result = session.run(query, params);

					while (result.hasNext()) {
						Record record = result.next();
						Map<String, Object> data = record.get("n").asMap();
						Map<String, Object> transformed = new HashMap<>();
						for (Map.Entry<String, Object> entry : data.entrySet()) {
							transformed.put(entry.getKey(), parseJson(entry.getValue(), mapper));
						}
						circuitInfoList.add(transformed);
						if (orderNumber == null && data.get("orderNumber") != null) {
							orderNumber = data.get("orderNumber").toString();
						}
					}
				}

				if (!circuitInfoList.isEmpty()) {
					Map<String, Object> data = new HashMap<>();
					data.put("orderNumber", orderNumber != null ? orderNumber : "");
					data.put("locations", circuitInfoList.size());
					data.put("circuitInfo", circuitInfoList);
					data.put("isEvcOrder", false);

					Map<String, Object> responseMap = new LinkedHashMap<>();
					responseMap.put("code", 200);
					responseMap.put("message", "Order info fetched successfully");
					responseMap.put("data", data);

					return new ResponseEntity<>(responseMap, HttpStatus.OK);

				} else {
					Map<String, Object> responseMap = new LinkedHashMap<>();
					responseMap.put("code", 404);
					responseMap.put("message", "No orders found for orderNumber: " + searchKey);

					return new ResponseEntity<>(responseMap, HttpStatus.NOT_FOUND);

				}
			}

		} catch (Exception ex) {
			log.error("Exception occurred while fetching UNI order info: {}", ex.getMessage(), ex);
			return new ResponseEntity<>(
					Map.of("code", 500, "message", "Failed to retrieve order info: " + ex.getMessage()),
					HttpStatus.INTERNAL_SERVER_ERROR);
		} finally {
			log.info("<= EthernetOrderService: getUNIOrderInfo: END");
		}
	}

	private Map<String, Object> transformEthernetOrderCircuit(Map<String, Object> data, ObjectMapper mapper) {
		boolean isUNI = false;

		Map<String, Object> transformed = new HashMap<>();
		transformed.put("orderNumber", data.getOrDefault("externalId", ""));
		transformed.put("buildStatus", "inProgress");
		transformed.put("connectionName", data.getOrDefault("circuitId", ""));

		Map<String, Object> customerConnection = new HashMap<>();
		customerConnection.put("connectionName", data.getOrDefault("circuitId", ""));

		Object productPayloadObj = data.get("productPayload");

		if (productPayloadObj instanceof String jsonString) {
			try {
				Map<String, Object> productPayload = mapper.readValue(jsonString, new TypeReference<>() {
				});
				Object productOrderItemObj = productPayload.get("productOrderItem");

				if (productOrderItemObj instanceof List<?> productOrderItems) {
					String nc = "", nci = "", secNci = "", speed = "";
					for (Object itemObj : productOrderItems) {
						if (itemObj instanceof Map<?, ?> orderItem) {
							Object productObj = orderItem.get("product");
							if (productObj instanceof Map<?, ?> product) {
								
								Object productSpecObj = product.get("productSpecification");
								if (productSpecObj instanceof Map<?, ?> productSpec) {
								    String productSpecId = String.valueOf(productSpec.get("id"));

								    if (StringUtils.isNotBlank(productSpecId)
								            && productSpecId.toUpperCase().contains("UNI")) {
								        isUNI = true;
								    }
								}
								
								Object characteristicsObj = product.get("productCharacteristic");
								if (characteristicsObj instanceof List<?> characteristics) {
									for (Object ch : characteristics) {
										if (ch instanceof Map<?, ?> charMap) {
											String name = String.valueOf(charMap.get("name"));
											Object value = charMap.get("value");

											switch (name.toLowerCase()) {
											case "icsc" -> customerConnection.put("icscCode", value);
											case "nc" -> customerConnection.put("NC", nc = (String) value);
											case "nci" -> customerConnection.put("NCI", nci = (String) value);
											case "secnci" ->
												customerConnection.put("secondaryNCI", secNci = (String) value);
											case "site" -> customerConnection.put("site", value);
											case "speed" -> customerConnection.put("speed", speed = (String) value);
											}
										}
									}

									if (StringUtils.isNotBlank(nc) && StringUtils.isNotBlank(nci)
											&& StringUtils.isNotBlank(secNci)) {
										Map<String, Object> bwMap = inventorySearchRepo.bandwidthByNCNCI(nc, nci,
												secNci);
										if (bwMap != null && !bwMap.isEmpty()) {
											String bw = (String) bwMap.get("bandwidth");
											if (StringUtils.isNotBlank(bw)) {
												bw = ApplicationUtils.bandwidthConvertor(bw,true,false);
											}
											customerConnection.put("circuitBandwidth", bw);
											customerConnection.put("interfaceType",
													(String) bwMap.get("interfaceType"));
											customerConnection.put("transmissionRate", (String) bwMap.get("transRate"));
											customerConnection.put("autoNegotiation",
													(String) bwMap.get("autoNegotiation"));
										}else {
											speed = ApplicationUtils.bandwidthConvertor(speed,true,false);
											customerConnection.put("circuitBandwidth", speed);
										}
									}
								}

								Object locationObj = product.get("location");
								String locationID = "";
                                // Get address details from location object
								if (locationObj instanceof Map<?, ?> location) {
									locationID = (String) location.get("id");
                                    customerConnection.put("subscriberAddress", location.get("addressLine"));
                                    customerConnection.put("subscriberCity", location.get("city"));
                                    customerConnection.put("subscriberState", location.get("state"));
                                    customerConnection.put("subscriberZip", location.get("zip"));

                                    // Get country from location with fallback to "Unknown"
                                    String country = (String) location.get("country");
                                    if (StringUtils.isBlank(country)) {
                                        country = "USA";
                                    }
                                    customerConnection.put("subscriberCountry", country);

                                    // Build full address from location
                                    StringBuilder fullAddressBuilder = new StringBuilder();
                                    fullAddressBuilder.append(location.get("addressLine")).append(", ");
                                    fullAddressBuilder.append(location.get("city")).append(", ");
                                    fullAddressBuilder.append(location.get("state")).append(" ");
                                    fullAddressBuilder.append(location.get("zip"));
                                    customerConnection.put("subscriberFullAddress", fullAddressBuilder.toString());
								}

								Object relatedPartyObj = productPayload.get("relatedParty");
                                if (relatedPartyObj instanceof List<?> relatedPartyList) {
                                    for (Object itemObj1 : relatedPartyList) {
                                        if (itemObj1 instanceof Map<?, ?> locationMap) {
                                            customerConnection.put("subscriberName", locationMap.get("name"));
                                            break; // Just get the first valid name
                                        }
                                    }
                                }
							}
						}
					
					}
				}

				Object enterpriseWholesaleObj = productPayload.get("enterpriseWholesaleInfo");
				if (enterpriseWholesaleObj instanceof Map<?, ?> enprsWholesale) {
					String acna = String.valueOf(enprsWholesale.get("ACNA"));
					if(StringUtils.isNotBlank(acna) && !StringUtils.equalsIgnoreCase(acna, "null"))
						customerConnection.put("ACNA", acna);
					String ccna = String.valueOf(enprsWholesale.get("CCNA"));
					if(StringUtils.isNotBlank(ccna) && !StringUtils.equalsIgnoreCase(ccna, "null"))
						customerConnection.put("CCNA", ccna);
				}

			} catch (Exception e) {
				log.error("Failed to parse productPayload JSON string", e);
			}
		} else {
			log.warn("productPayload is not a string");
		}
		Object engFacilitiesObj = data.get("engineeringFacilities");

		if (engFacilitiesObj instanceof String engJson && StringUtils.isNotBlank(engJson)) {
		    try {
		        List<Map<String, Object>> engList = mapper.readValue(engJson, new TypeReference<>() {});
		        Map<String, Object> engMap = new LinkedHashMap<>();

		        // Desired output order
		        String[] keysOrder = {
		                "nidclli", "acOrDcPowered", "sfpCustomerFacing", "sfpNetworkFacing",
		                "diversity", "deviceType", "fiberALocZLoc", "aggClli", "sfpCentralOffice", "scopeOfWork"
		        };

		        for (String key : keysOrder) {
		            for (Map<String, Object> item : engList) {
		                String name = (String) item.get("name");
		                String value = (String) item.get("value");
		                if (StringUtils.isBlank(name) || StringUtils.isBlank(value)) continue;
		                if (!name.equals(key)) continue;

		                switch (key) {
		                    case "nidclli" -> engMap.put("nidClli", value);
		                    case "acOrDcPowered" -> engMap.put("nidAcOrDcPowered", value);
		                    case "sfpCustomerFacing" -> engMap.put("nidCFPartNumber", value);
		                    case "sfpNetworkFacing" -> engMap.put("nidNFPartNumber", value);
		                    case "diversity" -> engMap.put("diversity", value);
		                    case "deviceType" -> engMap.put("nidDeviceType", value);
		                    case "fiberALocZLoc" -> {
		                        List<Map<String, Object>> parsedFibers  = parseFiberLoc(value);
		                        List<Map<String, Object>> finalFiberList = new ArrayList<>();
		                        for (Map<String, Object> fiberInfo : parsedFibers) {
		                            String pair = (String) fiberInfo.get("pair");
		                            String formattedPair = pair.length() < 5 ? String.format("%05d", Integer.parseInt(pair)) : pair;

		                            List<Map<String, Object>> connInfoList = inventorySearchRepo.getFiberConnInfo(
		                                    (String) fiberInfo.get("aClli"),
		                                    (String) fiberInfo.get("zClli"),
		                                    (String) fiberInfo.get("cableName"),
		                                    formattedPair,
		                                    1
		                            );
		                    
		                            if (connInfoList != null && !connInfoList.isEmpty()) {
		                                finalFiberList.addAll(connInfoList);
		                            }
		                        }

		                        if (!finalFiberList.isEmpty()) {
		                            engMap.put("fiberInfo", finalFiberList);
		                        }
		                    }
		                    case "aggClli" -> engMap.put("aggClli", value);
		                    case "sfpCentralOffice" -> engMap.put("aggPartNumber", value);
		                    case "scopeOfWork" -> engMap.put("scopeOfWork", value);
		                }
		            }
		        }
		       
		        transformed.put("engineeringFacilities", engMap);

		    } catch (Exception e) {
		        log.error("Failed to parse engineeringFacilities JSON string", e);
		    }
		}
		 transformed.put("isUNI", isUNI);
		transformed.put("customerConnection", customerConnection);
		return transformed;
	}
	
	private List<Map<String, Object>> parseFiberLoc(String fiberLoc) {
		List<Map<String, Object>> fiberList = new ArrayList<>();
		try {
			String[] parts = fiberLoc.split("/");

			for (int i = 0; i + 2 < parts.length; i += 3) {
				String aClli = parts[i];
				String zClli = parts[i + 1];
				String cablePair = parts[i + 2];

				String[] cableParts = cablePair.split("\\.", 2);
				String cableName = cableParts[0];
				String pairPart = (cableParts.length > 1) ? cableParts[1] : "";

				if (!pairPart.contains(",")) {
					Map<String, Object> fiberInfo = new LinkedHashMap<>();
					fiberInfo.put("aClli", aClli);
					fiberInfo.put("zClli", zClli);
					fiberInfo.put("cableName", cableName);
					fiberInfo.put("pair", pairPart);
					fiberList.add(fiberInfo);
				} else {
					String[] pairParts = pairPart.split(",");
					Map<String, Object> firstSegment = new LinkedHashMap<>();
					firstSegment.put("aClli", aClli);
					firstSegment.put("zClli", zClli);
					firstSegment.put("cableName", cableName);
					firstSegment.put("pair", pairParts[0]);
					fiberList.add(firstSegment);

					if (i + 3 < parts.length) {
						String nextZ = parts[i + 3];
						if (i + 4 < parts.length) {
							String nextCablePair = parts[i + 4];
							String[] nextCableParts = nextCablePair.split("\\.", 2);
							Map<String, Object> nextSegment = new LinkedHashMap<>();
							nextSegment.put("aClli", pairParts[1]);
							nextSegment.put("zClli", nextZ);
							nextSegment.put("cableName", nextCableParts[0]);
							if (nextCableParts.length > 1)
								nextSegment.put("pair", nextCableParts[1]);
							fiberList.add(nextSegment);
						}
					}
				}
			}

		} catch (Exception e) {
			log.warn("Failed to parse fiberALocZLoc '{}': {}", fiberLoc, e.getMessage());
		}

		return fiberList;
	}

	private Map<String, Object> getEvcOrderInfo(String serviceName, Session session) {
		Map<String, Object> evcInfo = new HashMap<>();

		String evcAliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
		String evcQuery = "MATCH (evcOrdr:EVCOrder) WHERE evcOrdr.aliasCktId = $aliasCktId RETURN evcOrdr";
		try {
			Map<String, Object> params = new HashMap<String, Object>();
			// params.put("serviceName", serviceName);
			params.put("aliasCktId", evcAliasCktId);

			Result result = session.run(evcQuery, params);
			ObjectMapper mapper = new ObjectMapper();

			if (result.hasNext()) {
				Record record = result.next();
				Map<String, Object> data = record.get("evcOrdr").asMap();
				Map<String, Object> transformed = new LinkedHashMap<>();

				transformed.put("serviceName", (String) data.get("serviceName"));

				Map<String,Object> evcExistMap = isEvcExist(serviceName,"EVC");
				String bExist = (String)evcExistMap.get("exists");
				boolean isnewEvc = true;
				if(StringUtils.equalsIgnoreCase("true", bExist))
					isnewEvc = false;

				log.info("Is new EVC : {}",isnewEvc);
				String ncCode = (String) data.get("nc");
				transformed.put("NC", ncCode);
				transformed.put("nci", (String) data.get("nci"));
				String bw = (String) data.get("bandwidth");
				if (StringUtils.isNotBlank(bw)) {
					bw = ApplicationUtils.bandwidthConvertor(bw,true,false);
				}
				transformed.put("bandwidth", bw);
				transformed.put("serviceCos", (String) data.get("serviceCos"));
				transformed.put("serviceType", (String) data.get("serviceType"));
				transformed.put("subscriberName", (String) data.get("subscriberName"));
				transformed.put("subscriberType", (String) data.get("subscriberType"));
				transformed.put("serviceId", (String) data.get("serviceId"));
				transformed.put("evcOrderNumber", (String) data.get("evcOrderNumber"));
				transformed.put("acna_ccna_subscriberId", (String) data.get("serviceId"));
				transformed.put("isNewEVC", isnewEvc);

				List<Map<String, Object>> uniInfoList = new ArrayList<>();
				Object uniListObj = data.get("uniList");
				if (uniListObj instanceof String jsonString) {
					try {
						uniInfoList = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse uniList JSON string.", e);
					}
				} else if (uniListObj instanceof List<?> rawList) {
					for (Object uniObj : rawList) {
						if (uniObj instanceof Map<?, ?> uniMap) {
							Map<String, Object> uniInfoMap = new HashMap<>();
							uniMap.forEach((k, v) -> uniInfoMap.put(String.valueOf(k), v));
							uniInfoList.add(uniInfoMap);
						}
					}
				}

				List<Map<String, Object>> uniConnections = new ArrayList<>();

				for (Map<String, Object> uniInfo : uniInfoList) {
					String cktid = (String) uniInfo.get("circuitName");
					if (cktid == null || cktid.trim().isEmpty())
						continue;
					String aliasCktIdInner = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
					String subQuery = "MATCH (uni:UNIConnection) "
							+ "WHERE uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId WITH uni where uni.deletedTimeStamp is null "
							+ "MATCH (uni)-[C1:CONNECTED_TO]-(up:EquipmentPort)-[:COMPONENT_OF*]->(dev:Equipment) where up.deletedTimeStamp is null and dev.deletedTimeStamp is null "
							+ "AND C1.deletedTimeStamp is null  "
							+ "MATCH (up)-[C3:XCONNECT]->(anp:EquipmentPort)<-[C4:CONNECTED_TO]-(nmi:NNIConnection)-[C5:CONNECTED_TO]->(znp:EquipmentPort)-[COMPONENT_OF*]->(nmiDev:Equipment) "
							+ "WHERE C4.deletedTimeStamp is null and C5.deletedTimeStamp is null  "
							+ "AND anp.deletedTimeStamp is null and nmi.deletedTimeStamp is null and znp .deletedTimeStamp is null and nmiDev.deletedTimeStamp is null "
							+ " MATCH (aAllDev:allDevices{TID:dev.TID})" 
							+ " MATCH (zAllDev:allDevices{TID:nmiDev.TID})"
							+ " OPTIONAL MATCH (uni)-[:AEND|ZEND|VEND]-(evc:EVCConnection{aliasCktId:'"+evcAliasCktId+"'})"
							+ "RETURN uni, up, aAllDev as dev, anp, nmi.cktid as nmiName, znp, zAllDev as nmiDev, evc.serviceName as evcName";
					Map<String, Object> subParams = Map.of("cktid", cktid, "aliasCktId", aliasCktIdInner);
					Result subResult = session.run(subQuery, subParams);
					if (subResult.hasNext()) {
						Record subRecord = subResult.next();
						Map<String, Object> uni = subRecord.get("uni").asMap();
						Map<String, Object> up = subRecord.get("up").asMap();
						Map<String, Object> dev = subRecord.get("dev").asMap();
						String nmiName = subRecord.get("nmiName").asString();
						Map<String, Object> anp = subRecord.get("anp").asMap();
						Map<String, Object> znp = subRecord.get("znp").asMap();
						Map<String, Object> nmiDev = subRecord.get("nmiDev").asMap();
						String evcName = subRecord.get("evcName").asString();

						Map<String, Object> uniCircuitInfo = new LinkedHashMap<>();
						uniCircuitInfo.put("uniCircuit", uni);
						uniCircuitInfo.put("device", dev);
						uniCircuitInfo.put("port", up);

						String cTagStart = (String) uniInfo.getOrDefault("cTag_start", "");
						String cTagEnd = (String) uniInfo.getOrDefault("cTag_end", "");
						String evcNci = (String) uniInfo.getOrDefault("evcNci", "");

						Map<String, Object> aEndInfo = new LinkedHashMap<>();
						aEndInfo.put("device", dev);
						aEndInfo.put("port", anp);

						Map<String, Object> zEndInfo = new LinkedHashMap<>();
						zEndInfo.put("device", nmiDev);
						zEndInfo.put("port", znp);

						Map<String, Object> nniInfo = new LinkedHashMap<>();
						nniInfo.put("nniName", nmiName);
						nniInfo.put("aEndInfo", aEndInfo);
						nniInfo.put("zEndInfo", zEndInfo);

						Map<String, Object> uniConnection = new LinkedHashMap<>();
						uniConnection.put("uniCircuitInfo", uniCircuitInfo);
						uniConnection.put("circuitName", (String) uni.get("cktid"));
						uniConnection.put("location", (String) uni.get("cktid"));
						uniConnection.put("cTag_start", cTagStart);
						uniConnection.put("cTag_end", cTagEnd);
						uniConnection.put("evcNci", evcNci);
						uniConnection.put("nniInfo", nniInfo);
						if(StringUtils.isBlank(evcName))
							uniConnection.put("isNewLocation",false );
						else
							uniConnection.put("isNewLocation", true);
						uniConnections.add(uniConnection);
					}
				}

				if (StringUtils.equals(ncCode, "VLM-")) {
					List<Map<String, Object>> uniConnsForEvplan = new ArrayList<>();
					if (!uniConnections.isEmpty()) {
						uniConnsForEvplan = buildEVPLANRoute(session, evcAliasCktId, uniConnections);
					}
					transformed.put("uniConnections", uniConnsForEvplan);
				} else {
					transformed.put("uniConnections", uniConnections);
					if (!uniConnections.isEmpty()) {
						List<Map<String, Object>> evcRoute = new ArrayList<Map<String,Object>>();
						if (isnewEvc == true) {
							evcRoute = buildEvcRoute(session, uniConnections);
						} else {

							String aCircuitName = (String) uniConnections.get(0).get("circuitName");
							Map<String, Object> aDev = (Map<String, Object>) ((Map<String, Object>) uniConnections.get(0).get("uniCircuitInfo")).get("device");
							String zCircuitName = (String) uniConnections.get(1).get("circuitName");
							Map<String, Object> zDev = (Map<String, Object>) ((Map<String, Object>) uniConnections.get(1).get("uniCircuitInfo")).get("device");

							String aCktId = (String) evcExistMap.get("aCktId");
							String aCTag = (String) evcExistMap.get("aCTag");
							String aSTag = (String) evcExistMap.get("aSTag");
							String zCktId = (String) evcExistMap.get("zCktId");
							String zCTag = (String) evcExistMap.get("zCTag");
							String zSTag = (String) evcExistMap.get("zSTag");

							boolean isUNIsOnEVC = true;
							Transaction tx = null;
							try {
								 tx = session.beginTransaction();
								if ((StringUtils.equals(aCircuitName, aCktId))
										&& (StringUtils.equals(zCircuitName, zCktId))) {
									isUNIsOnEVC = true;
									evcRoute = getEvcRoute(serviceName, aCTag, aSTag, zCTag, zSTag, aDev, zDev, tx);
								} else if ((StringUtils.equals(aCircuitName, zCktId))
										&& (StringUtils.equals(zCircuitName, aCktId))) {
									isUNIsOnEVC = true;
									evcRoute = getEvcRoute(serviceName, zCTag, zSTag, aCTag, aSTag, zDev, aDev, tx);
								} else {
									isUNIsOnEVC = false;
								}
							} catch (Exception ex) {
								isUNIsOnEVC = false;
							}finally {
								if (tx != null) {
									tx.close();
								}
							}

							// Existing EVC is not on same unis.
							if (isUNIsOnEVC == false) {
								evcRoute = buildEvcRoute(session, uniConnections);
							}
						}

						transformed.put("evcRoute", evcRoute);
						List<Map<String, Object>> alternateRoutes = getAlternateRoutes(session, uniConnections);
						transformed.put("alternateRoutes", alternateRoutes);
						transformed.put("routesCount", (alternateRoutes.isEmpty() ? 0 : alternateRoutes.size()));
					}
				}

				evcInfo = transformed;
			}
		} catch (Exception ex) {
			log.error("Failed to get EVC order info for  {}:  {}", serviceName, ex.getMessage(), ex);
		}

		return evcInfo;
	}

	private Map<String, Object> getEvcOrderInfoV2(String serviceName, Session session) throws Exception {
		log.info("getEvcOrderInfoV2 : {}",serviceName);

		Map<String, Object> evcInfo = new HashMap<>();

		Map<String,Object> evcExistMap = isEvcExist(serviceName,"EVC");
		String bExist = (String)evcExistMap.get("exists");
		boolean isnewEvc = true;
		if(StringUtils.equalsIgnoreCase("true", bExist))
			isnewEvc = false;

		// If the EVC is not new and
		// ncCode not VLM-, then return stating that changes are not allowed on ncCode evcs.
		// ncCode is VLM-, if the unis are marked as change or no unis exist, return with message EVC can be seen in inventory page.
		// ncCode is VLM-, if the UNIs are marked as removed, return with message EVC can be seen in inventory page.
		// ncCode is VLM-, if the UNIs are makred as add, load the UNIs as new with its routes and load all other existing unis with its routes as existing.

		String evcAliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		String ncCode = "";

		if(!isnewEvc) {
			ncCode = (String)evcExistMap.get("ncCode");
			if (!("VLM-".equals(ncCode) || "VLC-".equals(ncCode))) {
			    throw new Exception("EVPLAN EVCs only allowed to add new location");
			}

			evcInfo = getEvplanToAddLocation(serviceName, session);
			return evcInfo;
		}

		String evcQuery = "MATCH (evcOrdr:EVCOrder) WHERE evcOrdr.aliasCktId = $aliasCktId RETURN evcOrdr";
		try {
			Map<String, Object> params = new HashMap<String, Object>();
			// params.put("serviceName", serviceName);
			params.put("aliasCktId", evcAliasCktId);

			Result result = session.run(evcQuery, params);
			ObjectMapper mapper = new ObjectMapper();

			if (result.hasNext()) {
				Record record = result.next();
				Map<String, Object> data = record.get("evcOrdr").asMap();
				Map<String, Object> transformed = new LinkedHashMap<>();

				transformed.put("serviceName", (String) data.get("serviceName"));

				log.info("Is new EVC : {}",isnewEvc);

				ncCode = (String) data.get("nc");
				transformed.put("NC", ncCode);
				transformed.put("nci", (String) data.get("nci"));
				String bw = (String) data.get("bandwidth");
				if (StringUtils.isNotBlank(bw)) {
					bw = ApplicationUtils.bandwidthConvertor(bw,true,false);
				}
				transformed.put("bandwidth", bw);
				transformed.put("serviceCos", (String) data.get("serviceCos"));
				transformed.put("serviceType", (String) data.get("serviceType"));
				transformed.put("subscriberName", (String) data.get("subscriberName"));
				transformed.put("subscriberType", (String) data.get("subscriberType"));
				transformed.put("serviceId", (String) data.get("serviceId"));
				transformed.put("evcOrderNumber", (String) data.get("evcOrderNumber"));
				transformed.put("acna_ccna_subscriberId", (String) data.get("serviceId"));
				transformed.put("isNewEVC", isnewEvc);

				List<Map<String, Object>> uniInfoList = new ArrayList<>();
				Object uniListObj = data.get("uniList");
				if (uniListObj instanceof String jsonString) {
					try {
						uniInfoList = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse uniList JSON string.", e);
					}
				} else if (uniListObj instanceof List<?> rawList) {
					for (Object uniObj : rawList) {
						if (uniObj instanceof Map<?, ?> uniMap) {
							Map<String, Object> uniInfoMap = new HashMap<>();
							uniMap.forEach((k, v) -> uniInfoMap.put(String.valueOf(k), v));
							uniInfoList.add(uniInfoMap);
						}
					}
				}

				List<Map<String, Object>> uniConnections = new ArrayList<>();

				boolean isOVC=false;
				for (Map<String, Object> uniInfo : uniInfoList) {
					String cktid = (String) uniInfo.get("circuitName");
					if (cktid == null || cktid.trim().isEmpty())
						continue;
					String aliasCktIdInner = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
					String subQuery = "MATCH (uni:UNIConnection) "
							+ "WHERE uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId WITH uni where uni.deletedTimeStamp is null "
							+ "MATCH (uni)-[C1:CONNECTED_TO]-(up:EquipmentPort)-[:COMPONENT_OF*]->(dev:Equipment) where up.deletedTimeStamp is null and dev.deletedTimeStamp is null "
							+ "AND C1.deletedTimeStamp is null  "
							+ "MATCH (up)-[C3:XCONNECT]->(anp:EquipmentPort)<-[C4:CONNECTED_TO]-(nmi:NNIConnection)-[C5:CONNECTED_TO]->(znp:EquipmentPort)-[COMPONENT_OF*]->(nmiDev:Equipment) "
							+ "WHERE C4.deletedTimeStamp is null and C5.deletedTimeStamp is null  "
							+ "AND anp.deletedTimeStamp is null and nmi.deletedTimeStamp is null and znp .deletedTimeStamp is null and nmiDev.deletedTimeStamp is null "
							+ " MATCH (aAllDev:allDevices{TID:dev.TID})"
							+ " MATCH (zAllDev:allDevices{TID:nmiDev.TID})"
							+ " OPTIONAL MATCH (uni)-[:AEND|ZEND|VEND]-(evc:EVCConnection{aliasCktId:'"+evcAliasCktId+"'})"
							+ "RETURN uni, up, aAllDev as dev, anp, nmi.cktid as nmiName, znp, zAllDev as nmiDev, evc.serviceName as evcName";
					Map<String, Object> subParams = Map.of("cktid", cktid, "aliasCktId", aliasCktIdInner);
					Result subResult = session.run(subQuery, subParams);
					if (subResult.hasNext()) {
						Record subRecord = subResult.next();
						Map<String, Object> uni = subRecord.get("uni").asMap();
						// ENNI detection from UNIConnection
						String serviceType = String.valueOf(uni.get("serviceType"));

						if ("MEF ENNI".equalsIgnoreCase(serviceType)) {
						    isOVC = true;
						}

						Map<String, Object> up = subRecord.get("up").asMap();
						Map<String, Object> dev = subRecord.get("dev").asMap();
						String nmiName = subRecord.get("nmiName").asString();
						Map<String, Object> anp = subRecord.get("anp").asMap();
						Map<String, Object> znp = subRecord.get("znp").asMap();
						Map<String, Object> nmiDev = subRecord.get("nmiDev").asMap();
						String evcName = subRecord.get("evcName").asString();

						Map<String, Object> uniCircuitInfo = new LinkedHashMap<>();
						uniCircuitInfo.put("uniCircuit", uni);
						uniCircuitInfo.put("device", dev);
						uniCircuitInfo.put("port", up);

						String cTagStart = (String) uniInfo.getOrDefault("cTag_start", "");
						String cTagEnd = (String) uniInfo.getOrDefault("cTag_end", "");
						String evcNci = (String) uniInfo.getOrDefault("evcNci", "");
						String classOfService = (String) uniInfo.getOrDefault("serviceCos","");
						String evcBandwidth = (String) uniInfo.getOrDefault("evcBandwidth","");

						if (evcBandwidth.equalsIgnoreCase("UNI")) {
							evcBandwidth = (String) uni.getOrDefault("bandwidth","");
						}

						if (StringUtils.isNotBlank(evcBandwidth)) {
							evcBandwidth = ApplicationUtils.bandwidthConvertor(evcBandwidth,true,false);
						}

						Map<String, Object> aEndInfo = new LinkedHashMap<>();
						aEndInfo.put("device", dev);
						aEndInfo.put("port", anp);

						Map<String, Object> zEndInfo = new LinkedHashMap<>();
						zEndInfo.put("device", nmiDev);
						zEndInfo.put("port", znp);

						Map<String, Object> nniInfo = new LinkedHashMap<>();
						nniInfo.put("nniName", nmiName);
						nniInfo.put("aEndInfo", aEndInfo);
						nniInfo.put("zEndInfo", zEndInfo);

						Map<String, Object> uniConnection = new LinkedHashMap<>();
						uniConnection.put("uniCircuitInfo", uniCircuitInfo);
						uniConnection.put("circuitName", (String) uni.get("cktid"));
						uniConnection.put("location", (String) uni.get("cktid"));
						uniConnection.put("cTag_start", cTagStart);
						uniConnection.put("cTag_end", cTagEnd);
						uniConnection.put("evcNci", evcNci);
						uniConnection.put("nniInfo", nniInfo);
						uniConnection.put("classOfService", classOfService);
						uniConnection.put("evcBandwidth", evcBandwidth);
						if(StringUtils.isBlank(evcName))
							uniConnection.put("isNewLocation",false );
						else
							uniConnection.put("isNewLocation", true);
						uniConnections.add(uniConnection);
					}
				}

				if (StringUtils.equals(ncCode, "VLM-")) {
					List<Map<String, Object>> uniConnsForEvplan = new ArrayList<>();
					if (!uniConnections.isEmpty()) {
						uniConnsForEvplan = buildEVPLANRoute(session, evcAliasCktId, uniConnections);
					}
					transformed.put("uniConnections", uniConnsForEvplan);
				} else {
					transformed.put("uniConnections", uniConnections);
					if (!uniConnections.isEmpty()) {
						List<Map<String, Object>> evcRoute = new ArrayList<Map<String,Object>>();
						if (isnewEvc == true) {
							evcRoute = buildEvcRoute(session, uniConnections);
						} else {

							String aCircuitName = (String) uniConnections.get(0).get("circuitName");
							Map<String, Object> aDev = (Map<String, Object>) ((Map<String, Object>) uniConnections.get(0).get("uniCircuitInfo")).get("device");
							String zCircuitName = (String) uniConnections.get(1).get("circuitName");
							Map<String, Object> zDev = (Map<String, Object>) ((Map<String, Object>) uniConnections.get(1).get("uniCircuitInfo")).get("device");

							String aCktId = (String) evcExistMap.get("aCktId");
							String aCTag = (String) evcExistMap.get("aCTag");
							String aSTag = (String) evcExistMap.get("aSTag");
							String zCktId = (String) evcExistMap.get("zCktId");
							String zCTag = (String) evcExistMap.get("zCTag");
							String zSTag = (String) evcExistMap.get("zSTag");

							boolean isUNIsOnEVC = true;
							Transaction tx = null;
							try {
								 tx = session.beginTransaction();
								if ((StringUtils.equals(aCircuitName, aCktId))
										&& (StringUtils.equals(zCircuitName, zCktId))) {
									isUNIsOnEVC = true;
									evcRoute = getEvcRoute(serviceName, aCTag, aSTag, zCTag, zSTag, aDev, zDev, tx);
								} else if ((StringUtils.equals(aCircuitName, zCktId))
										&& (StringUtils.equals(zCircuitName, aCktId))) {
									isUNIsOnEVC = true;
									evcRoute = getEvcRoute(serviceName, zCTag, zSTag, aCTag, aSTag, zDev, aDev, tx);
								} else {
									isUNIsOnEVC = false;
								}
							} catch (Exception ex) {
								isUNIsOnEVC = false;
							}finally {
								if (tx != null) {
									tx.close();
								}
							}

							// Existing EVC is not on same unis.
							if (isUNIsOnEVC == false) {
								evcRoute = buildEvcRoute(session, uniConnections);
							}
						}

						transformed.put("evcRoute", evcRoute);
						List<Map<String, Object>> alternateRoutes = getAlternateRoutes(session, uniConnections);
						transformed.put("alternateRoutes", alternateRoutes);
						transformed.put("routesCount", (alternateRoutes.isEmpty() ? 0 : alternateRoutes.size()));
						transformed.put("isOVC", isOVC);

						 
					}
				}

				evcInfo = transformed;
			}
		} catch (Exception ex) {
			log.error("Failed to get EVC order info for  {}:  {}", serviceName, ex.getMessage(), ex);
			throw new Exception(ex.getMessage());
		}

		return evcInfo;
	}

	private Map<String, Object> getEvplanToAddLocation(String serviceName, Session session) throws Exception {
		log.info("getEvplanToAddLocation : {}", serviceName);

		Map<String, Object> evcInfo = new HashMap<>();

		String evcAliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		try {

			// Get EVC info from EVC Connection
			String evcQuery = "MATCH (evc:EVCConnection)-[rel:AEND|ZEND|VEND]-(uni:UNIConnection) "
					+ " WHERE evc.aliasCktId = $aliasCktId or evc.name = $serviceName "
					+ " AND uni.deletedTimeStamp is null RETURN evc, uni.cktid as uniName";

			Map<String, Object> params = new HashMap<String, Object>();
			// params.put("serviceName", serviceName);
			params.put("aliasCktId", evcAliasCktId);
			params.put("serviceName", serviceName);

			List<Record> evcInfoList = session.run(evcQuery, params).list();
			List<String> uniCktNames = new ArrayList<String>();
			Map<String, Object> transformed = new LinkedHashMap<>();
			List<Map<String, Object>> uniConnsRouteList = new ArrayList<>();

			if (evcInfoList != null && !evcInfoList.isEmpty()) {
				Record eRec = evcInfoList.get(0);
				Map<String, Object> data = eRec.get("evc").asMap();

				transformed.put("serviceName", (String) data.get("serviceName"));
				String ncCode = (String) data.get("ncCode");
				transformed.put("NC", ncCode);
				// transformed.put("nci", (String) data.get("nci"));
				String bw = (String) data.get("bandwidth");
				if (StringUtils.isNotBlank(bw)) {
					bw = ApplicationUtils.bandwidthConvertor(bw, true, false);
				} else {
					bw = (String) data.get("bandWidth");
					if (StringUtils.isNotBlank(bw)) {
						bw = ApplicationUtils.bandwidthConvertor(bw, true, false);
					}
				}
				transformed.put("bandwidth", bw);
				transformed.put("serviceCos", (String) data.get("serviceCos"));
				transformed.put("serviceType", (String) data.get("serviceType"));
				transformed.put("serviceId", (String) data.get("serviceId"));
				transformed.put("isNewEVC", false);

				for (Record evcRec : evcInfoList) {
					String uniName = evcRec.get("uniName").asString();
					uniCktNames.add(uniName);
				}
			}

			List<Map<String, Object>> uniConnsOfEvc = new ArrayList<>();
			uniConnsOfEvc = getUniConnsOfEvc(serviceName, uniCktNames, session);

			List<Map<String, Object>> uniConnections = new ArrayList<>(uniConnsOfEvc);

			List<Map<String, Object>> uniConnsOfEvplan = new ArrayList<>();
			if (!uniConnsOfEvc.isEmpty()) {
				uniConnsOfEvplan = getEVPLANLocationsRoute(session, serviceName, uniConnsOfEvc);
			}

			for (Map<String, Object> uniConnMap : uniConnsOfEvplan) {
				uniConnsRouteList.add(uniConnMap);
			}

	        String evcOrderQuery = "MATCH (evcOrder:EVCOrder) WHERE evcOrder.aliasCktId = $aliasCktId RETURN evcOrder";
	        Result evcOrderResult = session.run(evcOrderQuery, Map.of("aliasCktId", evcAliasCktId));

	        String sourceNode = "EVCChangeOrder"; // default
	        Map<String, Object> orderData = null;

	        if (evcOrderResult.hasNext()) {
	            Record evcOrderRecord = evcOrderResult.next();
	            orderData = evcOrderRecord.get("evcOrder").asMap();
	            String buildStatus = (String) orderData.get("buildStatus");
	            if (!"COMPLETED".equalsIgnoreCase(buildStatus)) {
	                // If not completed, use EVCOrder as source
	                sourceNode = "EVCOrder";
	            }
	        }

	        ObjectMapper mapper = new ObjectMapper();
	        List<Map<String, Object>> uniInfoList = new ArrayList<>();

	        // FETCH info from selected sourceNode
	        if ("EVCChangeOrder".equals(sourceNode)) {
	            String changeOrderQuery = "MATCH (evcOrdr:EVCChangeOrder) WHERE evcOrdr.aliasCktId = $aliasCktId RETURN evcOrdr";
	            Result orderResult = session.run(changeOrderQuery, Map.of("aliasCktId", evcAliasCktId));
	            if (orderResult.hasNext()) {
	                Record record = orderResult.next();
	                orderData = record.get("evcOrdr").asMap();
	            }
	        } else {
	            // Fetch EVCOrder if buildStatus not completed
	            String pendingOrderQuery = "MATCH (evcOrder:EVCOrder) WHERE evcOrder.aliasCktId = $aliasCktId RETURN evcOrder";
	            Result orderResult = session.run(pendingOrderQuery, Map.of("aliasCktId", evcAliasCktId));
	            if (orderResult.hasNext()) {
	                Record record = orderResult.next();
	                orderData = record.get("evcOrder").asMap();
	            }
	        }

	        if (orderData != null) {
				transformed.put("evcOrderNumber", (String) orderData.get("evcOrderNumber"));
				Object uniListObj = orderData.get("uniList");
				if (uniListObj instanceof String jsonString) {
					try {
						uniInfoList = mapper.readValue(jsonString, new TypeReference<>() {
						});
					} catch (Exception e) {
						log.error("Failed to parse uniList JSON string.", e);
					}
				} else if (uniListObj instanceof List<?> rawList) {
					for (Object uniObj : rawList) {
						if (uniObj instanceof Map<?, ?> uniMap) {
							Map<String, Object> uniInfoMap = new HashMap<>();
							uniMap.forEach((k, v) -> uniInfoMap.put(String.valueOf(k), v));
							uniInfoList.add(uniInfoMap);
						}
					}
				}

				for (Map<String, Object> uniInfo : uniInfoList) {

					String cktid = (String) uniInfo.get("circuitName");
					String action = (String) uniInfo.get("action");
					if (!StringUtils.equalsIgnoreCase("add", action)) {
						// If the Action on uni circuit is not add, means adding a new circuit to the
						// existing evc, then dont add the circuit to uni list.
						continue;
					}
					if (cktid == null || cktid.trim().isEmpty())
						continue;
					String aliasCktIdInner = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
					String subQuery = "MATCH (uni:UNIConnection) "
							+ "WHERE uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId WITH uni where uni.deletedTimeStamp is null "
							+ "MATCH (uni)-[C1:CONNECTED_TO]-(up:EquipmentPort)-[:COMPONENT_OF*]->(dev:Equipment) where up.deletedTimeStamp is null and dev.deletedTimeStamp is null "
							+ "AND C1.deletedTimeStamp is null  "
							+ "MATCH (up)-[C3:XCONNECT]->(anp:EquipmentPort)<-[C4:CONNECTED_TO]-(nmi:NNIConnection)-[C5:CONNECTED_TO]->(znp:EquipmentPort)-[COMPONENT_OF*]->(nmiDev:Equipment) "
							+ "WHERE C4.deletedTimeStamp is null and C5.deletedTimeStamp is null  "
							+ "AND anp.deletedTimeStamp is null and nmi.deletedTimeStamp is null and znp .deletedTimeStamp is null and nmiDev.deletedTimeStamp is null "
							+ " MATCH (aAllDev:allDevices{TID:dev.TID})" + " MATCH (zAllDev:allDevices{TID:nmiDev.TID})"
							+ " OPTIONAL MATCH (uni)-[:AEND|ZEND|VEND]-(evc:EVCConnection{aliasCktId:'" + evcAliasCktId
							+ "'})"
							+ "RETURN uni, up, aAllDev as dev, anp, nmi.cktid as nmiName, znp, zAllDev as nmiDev, evc.serviceName as evcName";
					Map<String, Object> subParams = Map.of("cktid", cktid, "aliasCktId", aliasCktIdInner);
					Result subResult = session.run(subQuery, subParams);
					if (subResult.hasNext()) {
						Record subRecord = subResult.next();
						Map<String, Object> uni = subRecord.get("uni").asMap();
						Map<String, Object> up = subRecord.get("up").asMap();
						Map<String, Object> dev = subRecord.get("dev").asMap();
						String nmiName = subRecord.get("nmiName").asString();
						Map<String, Object> anp = subRecord.get("anp").asMap();
						Map<String, Object> znp = subRecord.get("znp").asMap();
						Map<String, Object> nmiDev = subRecord.get("nmiDev").asMap();
						String evcName = subRecord.get("evcName").asString();

						Map<String, Object> uniCircuitInfo = new LinkedHashMap<>();
						uniCircuitInfo.put("uniCircuit", uni);
						uniCircuitInfo.put("device", dev);
						uniCircuitInfo.put("port", up);

						String cTagStart = (String) uniInfo.getOrDefault("cTag_start", "");
						String cTagEnd = (String) uniInfo.getOrDefault("cTag_end", "");
						String evcNci = (String) uniInfo.getOrDefault("evcNci", "");
						String classOfService = (String) uniInfo.getOrDefault("serviceCos","");
						String evcBandwidth = (String) uniInfo.getOrDefault("evcBandwidth","");

						if (evcBandwidth.equalsIgnoreCase("UNI")) {
							evcBandwidth = (String) uni.getOrDefault("bandwidth","");
						}

						if (StringUtils.isNotBlank(evcBandwidth)) {
							evcBandwidth = ApplicationUtils.bandwidthConvertor(evcBandwidth,true,false);
						}

						Map<String, Object> aEndInfo = new LinkedHashMap<>();
						aEndInfo.put("device", dev);
						aEndInfo.put("port", anp);

						Map<String, Object> zEndInfo = new LinkedHashMap<>();
						zEndInfo.put("device", nmiDev);
						zEndInfo.put("port", znp);

						Map<String, Object> nniInfo = new LinkedHashMap<>();
						nniInfo.put("nniName", nmiName);
						nniInfo.put("aEndInfo", aEndInfo);
						nniInfo.put("zEndInfo", zEndInfo);

						Map<String, Object> uniConnection = new LinkedHashMap<>();
						uniConnection.put("uniCircuitInfo", uniCircuitInfo);
						uniConnection.put("circuitName", (String) uni.get("cktid"));
						uniConnection.put("location", (String) uni.get("cktid"));
						uniConnection.put("cTag_start", cTagStart);
						uniConnection.put("cTag_end", cTagEnd);
						uniConnection.put("evcNci", evcNci);
						uniConnection.put("nniInfo", nniInfo);
						uniConnection.put("classOfService", classOfService);
						uniConnection.put("evcBandwidth", evcBandwidth);
						if (StringUtils.isBlank(evcName))
							uniConnection.put("isNewLocation", false);
						else
							uniConnection.put("isNewLocation", true);
						uniConnections.add(uniConnection);
					}
				}

				List<Map<String, Object>> uniConnsForEvplan = new ArrayList<>();
				if (!uniConnections.isEmpty()) {
					uniConnsForEvplan = buildEVPLANRoute(session, evcAliasCktId, uniConnections);
				}

				for (Map<String, Object> uniConnMap : uniConnsForEvplan) {
					uniConnsRouteList.add(uniConnMap);
				}

			}
			transformed.put("uniConnections", uniConnsRouteList);

			evcInfo = transformed;
		} catch (Exception ex) {
			log.error("Failed to get EVC order info for  {}:  {}", serviceName, ex.getMessage(), ex);
			throw new Exception(ex.getMessage());
		}
		return evcInfo;
	}

	private List<Map<String, Object>> getUniConnsOfEvc(String evcName, List<String> uniCktNames, Session session) {

		List<Map<String, Object>> uniConnections = new ArrayList<>();

		for (String uniName : uniCktNames) {
			String cktid = uniName;

			if (cktid == null || cktid.trim().isEmpty())
				continue;
			String aliasCktIdInner = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
			String evcAliasCktId = evcName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
			String subQuery = "MATCH (uni:UNIConnection) "
					+ "WHERE uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId WITH uni where uni.deletedTimeStamp is null "
					+ "MATCH (uni)-[C1:CONNECTED_TO]-(up:EquipmentPort)-[:COMPONENT_OF*]->(dev:Equipment) where up.deletedTimeStamp is null and dev.deletedTimeStamp is null "
					+ "AND C1.deletedTimeStamp is null  "
					+ "MATCH (up)-[C3:XCONNECT]->(anp:EquipmentPort)<-[C4:CONNECTED_TO]-(nmi:NNIConnection)-[C5:CONNECTED_TO]->(znp:EquipmentPort)-[COMPONENT_OF*]->(nmiDev:Equipment) "
					+ "WHERE C4.deletedTimeStamp is null and C5.deletedTimeStamp is null  "
					+ "AND anp.deletedTimeStamp is null and nmi.deletedTimeStamp is null and znp .deletedTimeStamp is null and nmiDev.deletedTimeStamp is null "
					+ " MATCH (aAllDev:allDevices{TID:dev.TID})" + " MATCH (zAllDev:allDevices{TID:nmiDev.TID})"
					+ " MATCH (uni)-[urel:AEND|ZEND|VEND]-(evc:EVCConnection{aliasCktId:'" + evcAliasCktId + "'}) "
					+ " RETURN uni, up, aAllDev as dev, anp, nmi.cktid as nmiName, znp, zAllDev as nmiDev, evc.serviceName as evcName, urel.CTAG as cTag, urel.STAG as sTag, urel.evcNci as evcNci, urel.evcBandwidth as evcBandwidth";

			Map<String, Object> subParams = Map.of("cktid", cktid, "aliasCktId", aliasCktIdInner);
			Result subResult = session.run(subQuery, subParams);
			if (subResult.hasNext()) {
				Record subRecord = subResult.next();
				Map<String, Object> uni = subRecord.get("uni").asMap();
				Map<String, Object> up = subRecord.get("up").asMap();
				Map<String, Object> dev = subRecord.get("dev").asMap();
				String nmiName = subRecord.get("nmiName").asString();
				Map<String, Object> anp = subRecord.get("anp").asMap();
				Map<String, Object> znp = subRecord.get("znp").asMap();
				Map<String, Object> nmiDev = subRecord.get("nmiDev").asMap();
				// String evcName = subRecord.get("evcName").asString();

				Map<String, Object> uniCircuitInfo = new LinkedHashMap<>();
				uniCircuitInfo.put("uniCircuit", uni);
				uniCircuitInfo.put("device", dev);
				uniCircuitInfo.put("port", up);

				String cTagStart = "";
				String evcNci = "";
				String cTagEnd = "";
				String sTag = "";
				String evcBandwidth = "0";
				String evcBW = subRecord.get("evcBandwidth").asString();
				if (StringUtils.isNotBlank(evcBW) && !StringUtils.equalsIgnoreCase("null", evcBW))
					evcBandwidth = evcBW;
				String tagVal = subRecord.get("cTag").asString();
				if (StringUtils.isNotBlank(tagVal) && !StringUtils.equalsIgnoreCase("null", tagVal))
					cTagStart = tagVal;
				tagVal = subRecord.get("sTag").asString();
				if (StringUtils.isNotBlank(tagVal) && !StringUtils.equalsIgnoreCase("null", tagVal))
					sTag = tagVal;
				tagVal = subRecord.get("evcNci").asString();
				if (StringUtils.isNotBlank(tagVal) && !StringUtils.equalsIgnoreCase("null", tagVal))
					evcNci = tagVal;

				Map<String, Object> aEndInfo = new LinkedHashMap<>();
				aEndInfo.put("device", dev);
				aEndInfo.put("port", anp);

				Map<String, Object> zEndInfo = new LinkedHashMap<>();
				zEndInfo.put("device", nmiDev);
				zEndInfo.put("port", znp);

				Map<String, Object> nniInfo = new LinkedHashMap<>();
				nniInfo.put("nniName", nmiName);
				nniInfo.put("aEndInfo", aEndInfo);
				nniInfo.put("zEndInfo", zEndInfo);

				Map<String, Object> uniConnection = new LinkedHashMap<>();
				uniConnection.put("uniCircuitInfo", uniCircuitInfo);
				uniConnection.put("circuitName", (String) uni.get("cktid"));
				uniConnection.put("location", (String) uni.get("cktid"));
				uniConnection.put("cTag_start", cTagStart);
				uniConnection.put("cTag_end", cTagEnd);
				uniConnection.put("evcBandwidth", evcBandwidth);
				uniConnection.put("sTag", sTag);
				uniConnection.put("evcNci", evcNci);
				uniConnection.put("nniInfo", nniInfo);
				uniConnection.put("isNewLocation", false);

				uniConnections.add(uniConnection);
			}
		}
		return uniConnections;
	}
	private boolean isUniConnected(Session session, String evcAliasCktId, String uniCktName) {
		boolean isConnected = false;

		return isConnected;
	}
	private Map<String,Object> isEvcExist(String circuitName, String type) {

		ResponseStatus resp = new ResponseStatus();
		resp = isCircuitExist(circuitName,type);
		Map<String,Object>cktExist = (Map<String,Object>)resp.getData();
		return cktExist;
	}

	public List<Map<String, Object>> getAlternateRoutes(Session session, List<Map<String, Object>> uniConnections) {
		List<Map<String, Object>> alternateRoutes = new ArrayList<>();

		log.info("Get alternate routes");

		int connectionCount = 1;
		// Get the first and last NNI info
		Map<String, Object> firstNniInfo = (Map<String, Object>) uniConnections.get(0).get("nniInfo");
		Map<String, Object> lastNniInfo = (Map<String, Object>) uniConnections.get(uniConnections.size() - 1)
				.get("nniInfo");

		boolean isOnMpls = false;
		String aEndCtag = (String) uniConnections.get(0).get("cTag_start");
		String zEndCtag = (String) uniConnections.get(uniConnections.size() - 1).get("cTag_start");

		// Add first connection (NNI)
		Map<String, Object> firstConnection = new LinkedHashMap<>();
		firstConnection.put("connection", connectionCount++);
		firstConnection.put("connectionType", "NNI");
		firstConnection.put("nniName", firstNniInfo.get("nniName"));
		firstConnection.put("aEndInfo", firstNniInfo.get("aEndInfo"));
		firstConnection.put("zEndInfo", firstNniInfo.get("zEndInfo"));
		firstConnection.put("sTag", aEndCtag);

		// Fetch first and last device names from the last part of firstNniInfo and
		// lastNniInfo
		String firstDevice = ((Map<String, Object>) ((Map<String, Object>) firstNniInfo.get("zEndInfo")).get("device"))
				.get("name").toString();
		String lastDevice = ((Map<String, Object>) ((Map<String, Object>) lastNniInfo.get("zEndInfo")).get("device"))
				.get("name").toString();

		boolean bSameDevice = false;
		if(StringUtils.equals(firstDevice, lastDevice)) {
			bSameDevice = true;
		}

		Result pathResult = null;
		if(!bSameDevice) {
			// Query to get the shortest path
			String shortestPathQuery = "MATCH path = SHORTEST 5 (loc:DeviceEntity{TID: $firstDevice})-"
					+ "[:NETWORK_CONNECTION|MPLS_CONNECTION]-+(rem:DeviceEntity{TID: $lastDevice}) RETURN path LIMIT 5";

			Map<String, Object> pathParams = Map.of("firstDevice", firstDevice, "lastDevice", lastDevice);
			pathResult = session.run(shortestPathQuery, pathParams);
		} else  {
			/* Both source and target devices are same. Hence it is only one default route.*/
			List<Map<String, Object>> routeOne = new ArrayList<>();
			Map<String, Object> routeOneMap = new HashMap<String, Object>();
			routeOne.add(firstConnection);

			Map<String, Object> aEndInfo = (Map<String, Object>) lastNniInfo.get("zEndInfo");
			Map<String, Object> zEndInfo = (Map<String, Object>) lastNniInfo.get("aEndInfo");

			Map<String, Object> finalConn = new LinkedHashMap<>();
			finalConn.put("connection", connectionCount);
			finalConn.put("connectionType", "NNI");
			finalConn.put("nniName", lastNniInfo.get("nniName"));
			finalConn.put("aEndInfo", aEndInfo);
			finalConn.put("zEndInfo", zEndInfo);
			if (isOnMpls)
				finalConn.put("sTag", zEndCtag);
			else
				finalConn.put("sTag", aEndCtag);
			routeOne.add(finalConn);

			routeOneMap.put("route", routeOne);
			alternateRoutes.add(routeOneMap);
			return alternateRoutes;
		}

		while (!bSameDevice && pathResult.hasNext()) {

			Map<String, Object> altRouteMap = new HashMap<String, Object>();
			List<Map<String, Object>> altRoute = new ArrayList<>();
			altRoute.add(firstConnection);
			connectionCount = 2;
			boolean isfaultyRoute = false;

			Record record = pathResult.next();
			org.neo4j.driver.types.Path path = record.get("path").asPath();

			// Convert Iterables to Lists
			List<Relationship> relationships = new ArrayList<>();
			path.relationships().forEach(relationships::add);

			List<Node> nodes = new ArrayList<>();
			path.nodes().forEach(nodes::add);

			// Iterate over relationships using index to get start and end nodes properly
			for (int i = 0; i < relationships.size(); i++) {
				try {
				Relationship relationship = relationships.get(i);
				Node startNode = nodes.get(i);
				Node endNode = nodes.get(i + 1);

				String aDeviceName = startNode.get("TID").asString();
				String zDeviceName = endNode.get("TID").asString();

				Map<String, Object> aEndDevice = fetchDeviceInfo(session, aDeviceName);
				Map<String, Object> zEndDevice = fetchDeviceInfo(session, zDeviceName);
				if ((aEndDevice == null || aEndDevice.isEmpty()) || (zEndDevice == null || zEndDevice.isEmpty())) {
					// i = relationships.size();
					log.info("Device is not available.");
					isfaultyRoute = true;
					break;
				}
				Map<String, Object> aEndInfo = Map.of("device", aEndDevice);
				Map<String, Object> zEndInfo = Map.of("device", zEndDevice);

				String relationshipType = relationship.type();
				if ("MPLS_CONNECTION".equals(relationshipType)) {
					Map<String, Object> props = relationship.asMap();
					String menId = (String) props.getOrDefault("menId", "");

					Map<String, Object> connection = new LinkedHashMap<>();
					connection.put("connection", connectionCount++);
					connection.put("connectionType", "MPLS");
					connection.put("menId", menId);
					connection.put("aEndInfo", aEndInfo);
					connection.put("zEndInfo", zEndInfo);

					altRoute.add(connection);
					isOnMpls = true;

				} else if ("NETWORK_CONNECTION".equals(relationshipType)) {
					Map<String, Object> props = relationship.asMap();

					String nniQuery = "MATCH (nni:NNIConnection) WHERE nni.deletedTimeStamp is null "
							+ "AND ((nni.loca = $zDeviceName AND nni.locz = $aDeviceName) "
							+ "OR (nni.locz = $zDeviceName AND nni.loca = $aDeviceName)) " + "WITH nni "
							+ "MATCH (nni)-[C1:CONNECTED_TO]->(aport:EquipmentPort { location: $aDeviceName }) WHERE aport.deletedTimeStamp is null AND C1.deletedTimeStamp is null  "
							+ "MATCH (nni)-[C2:CONNECTED_TO]->(zport:EquipmentPort { location: $zDeviceName }) WHERE zport.deletedTimeStamp is null AND C2.deletedTimeStamp is null "
							+ "RETURN nni.cktid AS nmiName, aport AS aport, zport AS zport LIMIT 1";

					Map<String, Object> queryParams = Map.of("aDeviceName", aDeviceName, "zDeviceName", zDeviceName);

					Result nniResult = session.run(nniQuery, queryParams);

					if (nniResult.hasNext()) {
						Record nniRecord = nniResult.next();
						String nmiName = nniRecord.get("nmiName").asString();

						Map<String, Object> aportNodeProps = new HashMap<>(nniRecord.get("aport").asNode().asMap());
						Map<String, Object> zportNodeProps = new HashMap<>(nniRecord.get("zport").asNode().asMap());

						if ((aportNodeProps == null || aportNodeProps.isEmpty())
								|| (zportNodeProps == null || zportNodeProps.isEmpty())) {
							log.info("Equipment Port is not available.");
							isfaultyRoute = true;
							break;
						}
						Map<String, Object> aEndInfo1 = new LinkedHashMap<>();
						aEndInfo1.put("device", aEndDevice);
						{
							String portSpeed = (String) aportNodeProps.get("bw");
							portSpeed = ApplicationUtils.bandwidthConvertor(portSpeed,true,false);
							aportNodeProps.put("portSpeed", portSpeed);
						}
						aEndInfo1.put("port", aportNodeProps);

						Map<String, Object> zEndInfo1 = new LinkedHashMap<>();
						zEndInfo1.put("device", zEndDevice);
						{
							String portSpeed = (String) zportNodeProps.get("bw");
							portSpeed = ApplicationUtils.bandwidthConvertor(portSpeed,true,false);
							zportNodeProps.put("portSpeed", portSpeed);
						}
						zEndInfo1.put("port", zportNodeProps);

						Map<String, Object> connection = new LinkedHashMap<>();
						connection.put("connection", connectionCount++);
						connection.put("connectionType", "NNI");
						connection.put("nniName", nmiName);
						connection.put("aEndInfo", aEndInfo1);
						connection.put("zEndInfo", zEndInfo1);
						if (isOnMpls)
							connection.put("sTag", zEndCtag);
						else
							connection.put("sTag", aEndCtag);
						altRoute.add(connection);
					} else {
						log.info("Network Connection details failed to fetch.");
						isfaultyRoute = true;
						break;
					}
				}
				}catch (Exception ex) {
					log.error("Caught exception in Alternate Routes - '{}'",ex.getMessage());
				}
			}

			// Add last connection (NNI)
			if (!isfaultyRoute) {
				Map<String, Object> aEndInfo = (Map<String, Object>) lastNniInfo.get("zEndInfo");
				Map<String, Object> zEndInfo = (Map<String, Object>) lastNniInfo.get("aEndInfo");

				Map<String, Object> finalConn = new LinkedHashMap<>();
				finalConn.put("connection", connectionCount);
				finalConn.put("connectionType", "NNI");
				finalConn.put("nniName", lastNniInfo.get("nniName"));
				finalConn.put("aEndInfo", aEndInfo);
				finalConn.put("zEndInfo", zEndInfo);
				if (isOnMpls)
					finalConn.put("sTag", zEndCtag);
				else
					finalConn.put("sTag", aEndCtag);
				altRoute.add(finalConn);

				altRouteMap.put("route", altRoute);
				alternateRoutes.add(altRouteMap);
			}

		}
		return alternateRoutes;
	}

	public List<Map<String, Object>> getConnections(Session session, String srcDev, List<String> targetDevs) {
		List<Map<String, Object>> connList = new ArrayList<>();

		String targetDevStr = "";
		for (String devName : targetDevs) {
			if (StringUtils.isNotBlank(targetDevStr)) {
				targetDevStr += ",'" + devName + "'";
			} else {
				targetDevStr = "'" + devName + "'";
			}
		}
		String shortestPathQuery = "MATCH (loc:DeviceEntity {TID: '" + srcDev + "' }) "
				+ " MATCH (rem:DeviceEntity) WHERE rem.TID IN [ " + targetDevStr + " ]"
				+ " MATCH path = shortestPath((loc)-[r:MPLS_CONNECTION|NETWORK_CONNECTION*]-(rem)) " + " RETURN path";

		Result pathResult = session.run(shortestPathQuery);

		while (pathResult.hasNext()) {
			int connectionCount = 2;
			List<Map<String, Object>> altRoute = new ArrayList<>();
			boolean isfaultyRoute = false;

			Record record = pathResult.next();
			org.neo4j.driver.types.Path path = record.get("path").asPath();

			List<Relationship> relationships = new ArrayList<>();
			path.relationships().forEach(relationships::add);

			List<Node> nodes = new ArrayList<>();
			path.nodes().forEach(nodes::add);

			for (int i = 0; i < relationships.size(); i++) {
				Relationship relationship = relationships.get(i);
				Node startNode = nodes.get(i);
				Node endNode = nodes.get(i + 1);

				String aDeviceName = startNode.get("TID").asString();
				String zDeviceName = endNode.get("TID").asString();

				Map<String, Object> aEndDevice = fetchDeviceInfo(session, aDeviceName);
				Map<String, Object> zEndDevice = fetchDeviceInfo(session, zDeviceName);

				if ((aEndDevice == null || aEndDevice.isEmpty()) || (zEndDevice == null || zEndDevice.isEmpty())) {
					log.info("Device is not available.");
					isfaultyRoute = true;
					break;
				}

				Map<String, Object> aEndInfo = Map.of("device", aEndDevice);
				Map<String, Object> zEndInfo = Map.of("device", zEndDevice);
				String relationshipType = relationship.type();

				if ("MPLS_CONNECTION".equals(relationshipType)) {
					Map<String, Object> props = relationship.asMap();
					String menId = (String) props.getOrDefault("menId", "");

					Map<String, Object> connection = new LinkedHashMap<>();
					connection.put("connection", connectionCount++);
					connection.put("connectionType", "MPLS");
					connection.put("menId", menId);
					connection.put("aEndInfo", aEndInfo);
					connection.put("zEndInfo", zEndInfo);

					altRoute.add(connection);

				} else if ("NETWORK_CONNECTION".equals(relationshipType)) {
					Map<String, Object> queryParams = Map.of("aDeviceName", aDeviceName, "zDeviceName", zDeviceName);

					String nniQuery = "MATCH (nni:NNIConnection) WHERE nni.deletedTimeStamp is null "
							+ " AND ((nni.loca = $aDeviceName AND nni.locz = $zDeviceName) "
							+ " OR (nni.locz = $aDeviceName AND nni.loca = $zDeviceName)) " + " WITH nni "
							+ " MATCH (nni)-[C1:CONNECTED_TO]->(aport:EquipmentPort { location: $aDeviceName }) "
							+ " WHERE aport.deletedTimeStamp is null AND C1.deletedTimeStamp is null "
							+ " MATCH (nni)-[C2:CONNECTED_TO]->(zport:EquipmentPort { location: $zDeviceName }) "
							+ " WHERE zport.deletedTimeStamp is null AND C2.deletedTimeStamp is null "
							+ " RETURN nni.cktid AS nmiName, aport AS aport, zport AS zport LIMIT 1";

					Result nniResult = session.run(nniQuery, queryParams);

					if (nniResult.hasNext()) {
						Record nniRecord = nniResult.next();
						String nmiName = nniRecord.get("nmiName").asString();

						Map<String, Object> aportNodeProps = new HashMap<>(nniRecord.get("aport").asNode().asMap());
						Map<String, Object> zportNodeProps = new HashMap<>(nniRecord.get("zport").asNode().asMap());

						if ((aportNodeProps == null || aportNodeProps.isEmpty())
								|| (zportNodeProps == null || zportNodeProps.isEmpty())) {
							log.info("Equipment Port is not available.");
							isfaultyRoute = true;
							break;
						}

						Map<String, Object> aEndInfo1 = new LinkedHashMap<>();
						aEndInfo1.put("device", aEndDevice);
						{
							String portSpeed = (String) aportNodeProps.get("bw");
							portSpeed = ApplicationUtils.bandwidthConvertor(portSpeed,true,false);
							aportNodeProps.put("portSpeed", portSpeed);
						}
						aEndInfo1.put("port", aportNodeProps);

						Map<String, Object> zEndInfo1 = new LinkedHashMap<>();
						zEndInfo1.put("device", zEndDevice);
						{
							String zportSpeed = (String) zportNodeProps.get("bw");
							zportSpeed = ApplicationUtils.bandwidthConvertor(zportSpeed,true,false);
							zportNodeProps.put("portSpeed", zportSpeed);
						}
						zEndInfo1.put("port", zportNodeProps);

						Map<String, Object> connection = new LinkedHashMap<>();
						connection.put("connection", connectionCount++);
						connection.put("connectionType", "NNI");
						connection.put("nniName", nmiName);
						connection.put("aEndInfo", aEndInfo1);
						connection.put("zEndInfo", zEndInfo1);

						altRoute.add(connection);
					} else {
						log.info("Network Connection details failed to fetch.");
						isfaultyRoute = true;
						break;
					}
				}
			}

			if (!isfaultyRoute && !altRoute.isEmpty()) {
				Map<String, Object> routeWrapper = new HashMap<>();
				routeWrapper.put("route", altRoute);
				connList.add(routeWrapper);
			}
		}

		// List<Map<String,Object>> connsRefined = new ArrayList<Map<String,Object>>();

		List<Map<String, Object>> allRoutes = new ArrayList<>();

		for (Map<String, Object> altRoute : connList) {
			List<Map<String, Object>> connections = (List<Map<String, Object>>) altRoute.get("route");
			List<Map<String, Object>> finalRoute = new ArrayList<>();
			List<String> nniNames = new ArrayList<>();
			List<String> mplsAEndDevices = new ArrayList<>();

			for (Map<String, Object> connection : connections) {
				String connectionType = (String) connection.get("connectionType");
				Map<String, Object> aEndInfo = (Map<String, Object>) connection.get("aEndInfo");
				Map<String, Object> aEndDevice = (Map<String, Object>) (aEndInfo != null ? aEndInfo.get("device")
						: null);

				if (aEndDevice != null) {
					String deviceRole = (String) aEndDevice.get("deviceRoles");

					if ("NNI".equalsIgnoreCase(connectionType)) {
						if (deviceRole != null && (deviceRole.contains("NPE") || deviceRole.contains("MER"))) {
							// Convert to MPLS connection
							String menId = (String) aEndDevice.get("topologyName");

							Map<String, Object> newConnection = new LinkedHashMap<>();
							newConnection.put("connection", connection.get("connection"));
							newConnection.put("connectionType", "MPLS");
							newConnection.put("menId", menId);
							Map<String, Object> newAEndInfo = Map.of("device", aEndDevice);
							newConnection.put("aEndInfo", newAEndInfo);

							finalRoute.add(newConnection);
							break; // Stop further processing for this route
						}
					} else if ("MPLS".equalsIgnoreCase(connectionType)) {
						if (deviceRole != null) {
							Map<String, Object> newConnection = new LinkedHashMap<>();
							newConnection.put("connection", connection.get("connection"));
							newConnection.put("connectionType", "MPLS");
							newConnection.put("menId", connection.get("menId"));
							Map<String, Object> newAEndInfo = Map.of("device", aEndDevice);
							newConnection.put("aEndInfo", newAEndInfo);

							finalRoute.add(newConnection);
							break; // Stop further processing for this route
						}
					}
				}

				if ("NNI".equalsIgnoreCase(connectionType)) {
					String nniName = (String) connection.get("nniName");
					if (nniName != null)
						nniNames.add(nniName);
				} else if ("MPLS".equalsIgnoreCase(connectionType) && aEndDevice != null) {
					String aEndName = (String) aEndDevice.get("TID");
					if (aEndName != null)
						mplsAEndDevices.add(aEndName);
				}

				finalRoute.add(connection);
			}

			// Check duplicates
			boolean isDuplicate = false;
			for (Map<String, Object> existingRouteMap : allRoutes) {
				List<Map<String, Object>> existingRoute = (List<Map<String, Object>>) existingRouteMap.get("route");
				if (areRoutesEquivalent(existingRoute, finalRoute)) {
					isDuplicate = true;
					break;
				}
			}

			if (!isDuplicate) {
				allRoutes.add(Map.of("route", finalRoute));
			}
		}

		List<Map<String, Object>> requiredConnList = new ArrayList<Map<String, Object>>();
		// TODO:Consider the shortest number of connections
		int lsize = 0;
		for (Map<String, Object> routeMap : allRoutes) {
			List<Map<String, Object>> tConnList = (List<Map<String, Object>>) routeMap.get("route");

			int tsize = tConnList.size();
			if (lsize > 0) {
				if (lsize > tsize) {
					requiredConnList = new ArrayList<Map<String, Object>>(tConnList);
				}
			} else {
				requiredConnList = new ArrayList<Map<String, Object>>(tConnList);
			}

		}
		return requiredConnList;
	}

	public List<Map<String, Object>> buildEVPLANRoute(Session session, String evcAliasCktId, List<Map<String, Object>> uniConnections) {
		List<Map<String, Object>> evplanUniConns = new ArrayList<>();

		// temp uniConnections.
		List<Map<String, Object>> tempUniConns = new ArrayList<>(uniConnections);

		List<Map<String, Object>> uniNmiDevList = new ArrayList<>();
		List<String> nwDevList = new ArrayList<>();
		List<String> cktList = new ArrayList<>();

		List<String> newDevList = new ArrayList<>();
		for (Map<String, Object> uniConn : tempUniConns) {

			String circuitName = (String) uniConn.get("circuitName");
			cktList.add(circuitName);

			Map<String, Object> nniInfo = (Map<String, Object>) uniConn.get("nniInfo");
			String zSW = ((Map<String, Object>) ((Map<String, Object>) nniInfo.get("zEndInfo")).get("device"))
					.get("name").toString();
			if (!nwDevList.contains(zSW)) {
				nwDevList.add(zSW);
			}

			//Add only when the circuit is new to add to EVC
			Map<String, Object> cktSAMap = new HashMap<String, Object>();
			if( (boolean) uniConn.get("isNewLocation")) {
				cktSAMap.put("circuitName", circuitName);
				cktSAMap.put("nwDevice", zSW);
				uniNmiDevList.add(cktSAMap);
				newDevList.add(zSW);
			}
		}

		List<Map<String, Object>> tempNmiDevList = new ArrayList<>(uniNmiDevList);
		List<String> tempNwDevsL = new ArrayList<>(nwDevList);
		List<String> tempCircuitsL = new ArrayList<>(cktList);

		// For each nwDev
		for (String srcDev : tempNwDevsL) {

			if(!newDevList.contains(srcDev)) {
				continue;
			}
			List<Map<String, Object>> connList = new ArrayList<Map<String, Object>>();
			List<String> targetDevs = new ArrayList<>(nwDevList);
			targetDevs.remove(srcDev);

			// Get connections from source to targetDevices
			connList = getConnections(session, srcDev, targetDevs);

			// Each circuit in circuit, nwDev map
			for (Map<String, Object> cktNwMap : tempNmiDevList) {

				String nwDevName = (String) cktNwMap.get("nwDevice");
				if (StringUtils.equals(nwDevName, srcDev)) {

					Map<String, Object> uniConnection = new HashMap<String, Object>();

					String cktName = (String) cktNwMap.get("circuitName");

					Map<String, Object> nniMap = new HashMap<String, Object>();
					// Map<String,Object> uniConn = new HashMap<String,Object>();
					Map<String, Object> firstConnection = new LinkedHashMap<>();
					List<Map<String, Object>> uniRoute = new ArrayList<Map<String, Object>>();
					// Check each circuit in UNIConns
					for (Map<String, Object> temUniConnMap : tempUniConns) {

						String circuitName = (String) temUniConnMap.get("circuitName");
						if (StringUtils.equals(cktName, circuitName)) {

							boolean iscompleted = false;
							for (Map<String, Object> locMap : evplanUniConns) {
								String locName = (String) locMap.get("circuitName");
								if (StringUtils.equals(cktName, locName) && locMap.containsKey("locationRoute")) {
									iscompleted = true;
									break;
								}
							}

							if (iscompleted == false) {
								uniConnection.putAll(temUniConnMap);
								nniMap.putAll((Map<String, Object>) temUniConnMap.get("nniInfo"));

								firstConnection.put("connection", 1);
								firstConnection.put("connectionType", "NNI");
								firstConnection.put("nniName", nniMap.get("nniName"));
								firstConnection.put("aEndInfo", nniMap.get("aEndInfo"));
								firstConnection.put("zEndInfo", nniMap.get("zEndInfo"));
								firstConnection.put("sTag", temUniConnMap.get("cTag_start"));
								uniRoute.add(firstConnection);
								// uniRoute.addAll(connList);
								for (Map<String, Object> conn : connList) {

									Map<String, Object> tconn = new HashMap<>(conn);
									String connType = (String) tconn.get("connectionType");
									if (StringUtils.equals(connType, "NNI")) {
										tconn.put("sTag", temUniConnMap.get("cTag_start"));
									}
									uniRoute.add(tconn);
								}
								uniConnection.put("locationRoute", uniRoute);
								// remove the map from uni list
								// tempUniConns.remove(temUniConnMap);
								// tempNmiDevList.remove(cktNwMap);
								evplanUniConns.add(uniConnection);
							}
							break;
						}
					}
				}

			}
		}

		return evplanUniConns;
	}

	public List<Map<String, Object>> getEVPLANLocationsRoute(Session session, String serviceName, List<Map<String, Object>> uniConnections) throws Exception {
		List<Map<String, Object>> evplanUniConns = new ArrayList<>();

		// temp uniConnections.
		List<Map<String, Object>> tempUniConns = new ArrayList<>(uniConnections);

		List<Map<String, Object>> uniNmiDevList = new ArrayList<>();
		List<String> nwDevList = new ArrayList<>();
		List<String> cktList = new ArrayList<>();

		Transaction tx = null;
		String evcAliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		try {
			tx = session.beginTransaction();
		List<String> newDevList = new ArrayList<>();
		for (Map<String, Object> uniConn : tempUniConns) {


			String circuitName = (String) uniConn.get("circuitName");
			String uniAliasId = circuitName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

			cktList.add(circuitName);

			Map<String, Object> nniInfo = (Map<String, Object>) uniConn.get("nniInfo");
			String uniDevName = ((Map<String, Object>) ((Map<String, Object>) nniInfo.get("aEndInfo")).get("device"))
					.get("name").toString();
			String  cTag = (String) uniConn.get("cTag_start");
			String  sTag = (String) uniConn.get("sTag");
			String  nci = (String) uniConn.get("evcNci");

			String nmiStr = (String)nniInfo.get("nniName");
			List<Map<String, Object>> uniRoute = new ArrayList<Map<String, Object>>();

			List<Map<String, Object>> connList = getUniRoute(evcAliasCktId, circuitName, uniAliasId, uniDevName, cTag, sTag,tx);

			for (Map<String, Object> conn : connList) {

				Map<String, Object> tconn = new HashMap<>(conn);
				String connType = (String) tconn.get("connectionType");
				if (StringUtils.equals(connType, "NNI")) {
					tconn.remove("STAG");
					tconn.remove("CTAG");
					tconn.put("sTag", sTag);
					tconn.put("cTag", cTag);
				}
				uniRoute.add(tconn);
			}

			//Add only when the circuit is new to add to EVC

			Map<String, Object> uniConnection = new HashMap<String, Object>();
			uniConnection.putAll(uniConn);
			uniConnection.put("locationRoute", uniRoute);

			evplanUniConns.add(uniConnection);

		}
		}catch(Exception ex) {
			throw new Exception(ex.getMessage());
		}finally {
			if(tx != null) {
				tx.close();
			}
		}

		return evplanUniConns;
	}

	public List<Map<String, Object>> buildEvcRoute(Session session, List<Map<String, Object>> uniConnections) {
	    List<Map<String, Object>> evcRoute = new ArrayList<>();
	    int connectionCount = 1;

	    // Get the first and last NNI info
	    Map<String, Object> firstNniInfo = (Map<String, Object>) uniConnections.get(0).get("nniInfo");
	    Map<String, Object> lastNniInfo = (Map<String, Object>) uniConnections.get(uniConnections.size() - 1).get("nniInfo");

	    boolean isOnMpls = false;
	    String aEndCtag = (String) uniConnections.get(0).get("cTag_start");
	    String zEndCtag = (String) uniConnections.get(uniConnections.size() - 1).get("cTag_start");;
	    // Add first connection (NNI)
	    Map<String, Object> firstConnection = new LinkedHashMap<>();
	    firstConnection.put("connection", connectionCount++);
	    firstConnection.put("connectionType", "NNI");
	    firstConnection.put("nniName", firstNniInfo.get("nniName"));
	    firstConnection.put("aEndInfo", firstNniInfo.get("aEndInfo"));
	    firstConnection.put("zEndInfo", firstNniInfo.get("zEndInfo"));
		firstConnection.put("sTag", aEndCtag);
	    evcRoute.add(firstConnection);

	    // Fetch first and last device names from the last part of firstNniInfo and lastNniInfo
	    String firstDevice = ((Map<String, Object>) ((Map<String, Object>) firstNniInfo.get("zEndInfo")).get("device")).get("name").toString();
	    String lastDevice = ((Map<String, Object>) ((Map<String, Object>) lastNniInfo.get("zEndInfo")).get("device")).get("name").toString();

		boolean bSameDevice = false;
		if(StringUtils.equals(firstDevice, lastDevice)) {
			bSameDevice = true;
		}
		Result pathResult = null;
		if(bSameDevice == false) {
			// Query to get the shortest path
			String shortestPathQuery = "MATCH path = SHORTEST 1 (loc:DeviceEntity{TID: $firstDevice})-[:NETWORK_CONNECTION|MPLS_CONNECTION]-+(rem:DeviceEntity{TID: $lastDevice}) RETURN path LIMIT 1";
			Map<String, Object> pathParams = Map.of("firstDevice", firstDevice, "lastDevice", lastDevice);
			pathResult = session.run(shortestPathQuery, pathParams);
		}

		// Process path relationships and nodes
		if ( !bSameDevice && pathResult.hasNext()) {
			Record record = pathResult.next();
			org.neo4j.driver.types.Path path = record.get("path").asPath();

	        // Convert Iterables to Lists
	        List<Relationship> relationships = new ArrayList<>();
	        path.relationships().forEach(relationships::add);

	        List<Node> nodes = new ArrayList<>();
	        path.nodes().forEach(nodes::add);

	        // Iterate over relationships using index to get start and end nodes properly
	        for (int i = 0; i < relationships.size(); i++) {
	            Relationship relationship = relationships.get(i);
	            Node startNode = nodes.get(i);
	            Node endNode = nodes.get(i + 1);

	            String aDeviceName = startNode.get("TID").asString();
	            String zDeviceName = endNode.get("TID").asString();
	            
	            Map<String, Object> aEndDevice = fetchDeviceInfo(session, aDeviceName);
	            Map<String, Object> zEndDevice = fetchDeviceInfo(session, zDeviceName);

	            Map<String, Object> aEndInfo = Map.of("device", aEndDevice);
	            Map<String, Object> zEndInfo = Map.of("device", zEndDevice);

	            String relationshipType = relationship.type();
	            if ("MPLS_CONNECTION".equals(relationshipType)) {
	                Map<String, Object> props = relationship.asMap();
	                String menId = (String) props.getOrDefault("menId", "");

	                Map<String, Object> connection = new LinkedHashMap<>();
	                connection.put("connection", connectionCount++);
	                connection.put("connectionType", "MPLS");
	                connection.put("menId", menId);
	                connection.put("aEndInfo", aEndInfo);
	                connection.put("zEndInfo", zEndInfo);

	                evcRoute.add(connection);
					isOnMpls = true;

	            } else if ("NETWORK_CONNECTION".equals(relationshipType)) {
	                Map<String, Object> props = relationship.asMap();

	                String nniQuery =
	                        "MATCH (nni:NNIConnection) WHERE nni.deletedTimeStamp is null " +
	                        "AND ((nni.loca = $zDeviceName AND nni.locz = $aDeviceName) " +
	                        "OR (nni.locz = $zDeviceName AND nni.loca = $aDeviceName)) " +
	                        "WITH nni " +
	                        "MATCH (nni)-[C1:CONNECTED_TO]->(aport:EquipmentPort { location: $aDeviceName }) WHERE aport.deletedTimeStamp is null AND C1.deletedTimeStamp is null  " +
	                        "MATCH (nni)-[C2:CONNECTED_TO]->(zport:EquipmentPort { location: $zDeviceName }) WHERE zport.deletedTimeStamp is null AND C2.deletedTimeStamp is null " +
	                        "RETURN nni.cktid AS nmiName, aport AS aport, zport AS zport LIMIT 1";

	                Map<String, Object> queryParams = Map.of(
	                        "aDeviceName", aDeviceName,
	                        "zDeviceName", zDeviceName
	                );

	                Result nniResult = session.run(nniQuery, queryParams);

	                if (nniResult.hasNext()) {
	                    Record nniRecord = nniResult.next();
	                    String nmiName = nniRecord.get("nmiName").asString();

	                    Map<String, Object> aportNodeProps = nniRecord.get("aport").asNode().asMap();
	                    Map<String, Object> zportNodeProps = nniRecord.get("zport").asNode().asMap();

						Map<String, Object> aEndInfo1 = new LinkedHashMap<>();
						aEndInfo1.put("device", aEndDevice);
						aEndInfo1.put("port", aportNodeProps);

						Map<String, Object> zEndInfo1 = new LinkedHashMap<>();
						zEndInfo1.put("device", zEndDevice);
						zEndInfo1.put("port", zportNodeProps);

	                    Map<String, Object> connection = new LinkedHashMap<>();
	                    connection.put("connection", connectionCount++);
	                    connection.put("connectionType", "NNI");
	                    connection.put("nniName", nmiName);
	                    connection.put("aEndInfo", aEndInfo1);
	                    connection.put("zEndInfo", zEndInfo1);
						if (isOnMpls)
							connection.put("sTag", zEndCtag);
						else
							connection.put("sTag", aEndCtag);
	                    evcRoute.add(connection);
	                }
	            }
	        }
	    }

	    // Add last connection (NNI)
	    Map<String, Object> aEndInfo = (Map<String, Object>) lastNniInfo.get("zEndInfo");
	    Map<String, Object> zEndInfo = (Map<String, Object>) lastNniInfo.get("aEndInfo");

	    Map<String, Object> finalConn = new LinkedHashMap<>();
	    finalConn.put("connection", connectionCount);
	    finalConn.put("connectionType", "NNI");
	    finalConn.put("nniName", lastNniInfo.get("nniName"));
	    finalConn.put("aEndInfo", aEndInfo);
	    finalConn.put("zEndInfo", zEndInfo);
		if (isOnMpls)
			finalConn.put("sTag", zEndCtag);
		else
			finalConn.put("sTag", aEndCtag);
	    evcRoute.add(finalConn);
	    evcRoute=processEvcAndUpdateStags(evcRoute,uniConnections,null);
	    return evcRoute;
	}

	// Helper method to fetch device information based on device TID
	private Map<String, Object> fetchDeviceInfo(Session session, String deviceTID) {
		Map<String, Object> deviceParams = Map.of("deviceTID", deviceTID);
		Result deviceResult = session.run(
				"MATCH (n:allDevices) WHERE n.TID = $deviceTID and n.deletedTimeStamp is null RETURN n LIMIT 1",
				deviceParams);

		Map<String, Object> deviceInfo = new HashMap<>();
		if (deviceResult.hasNext()) {
			Record deviceRecord = deviceResult.next();
			Map<String, Object> device = new HashMap<>((Map<String, Object>) deviceRecord.get("n").asMap());
			deviceInfo = convertDevice(device,false);
			//String model = (String)deviceInfo.get("model");
			//deviceInfo.put("deviceModel", ModelEnum.getMastroeNameByUsilModel(model));
		}
		return deviceInfo;
	}

	private Object parseJson(Object value, ObjectMapper mapper) {
		if (value instanceof String jsonString) {
			try {
				if (jsonString.trim().startsWith("{")) {
					return mapper.readValue(jsonString, new TypeReference<Map<String, Object>>() {
					});
				} else if (jsonString.trim().startsWith("[")) {
					return mapper.readValue(jsonString, new TypeReference<List<Map<String, Object>>>() {
					});
				}
			} catch (Exception e) {
				log.warn("Failed to parse JSON: {}", jsonString, e);
			}
		}
		return value;
	}

	public ResponseStatus getEthOrderInfo(String orderNumber) {
		log.info("=> EthernetOrderService: getEthOrderInfo: START");
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		try (Session session = driver.session()) {
			String query = "MATCH (n:UNIOrder {orderNumber: $orderNumber}) " + "RETURN n";
			Instant start = Instant.now();
			Result result = session.run(query, Map.of("uniCircuitId", orderNumber));
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute getUNIOrderInfo : " + timeElapsed.toMillis() + " ms");
			Map<String, Object> orderProperties = new HashMap<String, Object>();

		} catch (Exception ex) {
			log.error("Error retrieving Ethernet order  '{}': {}", orderNumber, ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to retrieve Ethernet order: " + ex.getMessage());
		}

		log.info("<= EthernetOrderService: getEthOrderInfo: END");
		return response;
	}

	public ResponseStatus disconnectUNI(Map<String, Object> uniInfo) {

		log.info("=>EthernetOrderService:disconnectUNI: START");
		log.info("Disconnect UNI : {}", uniInfo.toString());
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String cktid = (String) uniInfo.get("cktid");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			// Check if circuit exists or not
			String existenceCheckQuery = "MATCH (uni:UNIConnection) WHERE uni.deletedTimeStamp IS NULL "
					+ "AND (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) RETURN uni";

			Map<String, Object> params = new HashMap<>();
			params.put("cktid", cktid);
			params.put("aliasCktId", aliasCktId);

			List<Record> existResult = tx.run(existenceCheckQuery, params).list();

			if (existResult == null || existResult.isEmpty()) {
				response.setCode(404);
				response.setMessage("UNI doesn't with circuitId: " + cktid);
				return response;
			}

			// Check if UNI is in service (linked to EVC or ROUTE)
			String inServiceCheckQuery = "MATCH (uni:UNIConnection) WHERE (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) "
					+ "OPTIONAL MATCH (uni)-[:AEND | ZEND]-(evc:EVCConnection) WHERE evc.deletedTimeStamp IS NULL "
					+ "WITH uni, evc WHERE evc IS NOT NULL RETURN uni limit 5";

			List<Record> serviceCheck = tx.run(inServiceCheckQuery, params).list();

			if (serviceCheck != null && !serviceCheck.isEmpty()) {
				response.setCode(400);
				response.setMessage("UNI is currently in service and cannot be disconnected.");
				return response;
			}

			// Update the relation status to port and delete XCNECT relation and relation
			// with Route
			String disconnectQuery = "MATCH (uni:UNIConnection) where (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) "
					+ "OPTIONAL MATCH (uni)-[r1:CONNECTED_TO]->(port:EquipmentPort) "
					+ "SET uni.status = 'Disconnected', port.status = 'Available', r1.status = 'Deleted' "
					+ "WITH uni,port " + "OPTIONAL MATCH (uni)-[r3:CONNECTED_TO]->(route:ROUTE)"
					+ "OPTIONAL MATCH (port)-[r2:XCONNECT]->() " + "DELETE r2,r3";
			tx.run(disconnectQuery, params);
			tx.commit();

			response.setCode(200);
			response.setMessage("UNI disconnected successfully.");
		} catch (Exception e) {
			log.error("Exception while disconnecting UNI: ", e);
			response.setCode(500);
			response.setMessage("Internal server error during UNI disconnection.");
		}
		return response;
	}

	public ResponseStatus getFiberConnectionInfo(String clliA, String clliZ, String fiberName, String strandId,
			int connNum) {
		log.info("=>EthernetOrderService:getFiberConnectionInfo: START");
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to get fiber connection!.");

		try {

			List<Map<String, Object>> fiberConnList = inventorySearchRepo.getFiberConnInfo(clliA, clliZ, fiberName,
					strandId, connNum);
			if (fiberConnList != null && !fiberConnList.isEmpty()) {
				response.setCode(200);
				response.setMessage("Found Fiber Data");
				response.setData(fiberConnList);
				log.info("Successfully found Fiber connection info!.");
			}
		} catch (Exception ex) {
			log.error("Get Fiber Connection - Caught Exception: Reason:  {}", ex);
			response.setCode(500);
			response.setMessage("Failed to get Fiber Connection.");
		}
		log.info("<=EthernetOrderService:getFiberConnectionInfo: ENDS");
		return response;
	}

	public List<Map<String, Object>> getDeviceInfo(Map<String, Object> devObj, String connectionType) {
		List<Map<String, Object>> resultList = new ArrayList<>();

		if (devObj == null || devObj.isEmpty())
			return resultList;

		String circuitId = devObj.get("circuitId") != null
				? devObj.get("circuitId").toString().replaceAll("[^a-zA-Z0-9]", "")
				: "";
		String deviceName = devObj.get("deviceIdentifier") != null
				? devObj.get("deviceIdentifier").toString().replaceAll("[^a-zA-Z0-9]", "")
				: "";

		if ("MPLS".equalsIgnoreCase(connectionType)) {
			String devQuery = "MATCH (dev:allDevices) WHERE dev.TID = '" + deviceName
					+ "' AND dev.deletedTimeStamp IS NULL RETURN dev";
			log.info("Executing MPLS query: {}", devQuery);

			try (Session session = driver.session()) {
				Result result = session.run(devQuery);
				while (result.hasNext()) {
					Record record = result.next();
					Map<String, Object> map = new HashMap<>();
					if (!record.get("dev").isNull()) {
						map.put("device", record.get("dev").asMap());
					}
					resultList.add(map);
				}
			} catch (Exception e) {
				log.error("Exception in getDeviceInfo (MPLS): {}", e.getMessage(), e);
			}
		} else {
			String devQuery = "MATCH (nni:NNIConnection) where nni.aliasCktId = '" + circuitId + "' "
					+ "and nni.deletedTimeStamp is null  with nni "
					+ "OPTIONAL MATCH (nni)-[c:CONNECTED_TO]->(ep:EquipmentPort) where ep.location = '" + deviceName
					+ "' " + "and c.deletedTimeStamp is null and ep.deletedTimeStamp is null with ep "
					+ "OPTIONAL MATCH (dev:allDevices) where dev.node_id=ep.node_id and dev.deletedTimeStamp is null return ep,dev ";
			log.info("Executing query: {}", devQuery);

			try (Session session = driver.session()) {
				Result result = session.run(devQuery);
				while (result.hasNext()) {
					Record record = result.next();
					Map<String, Object> map = new HashMap<>();

					if (!record.get("ep").isNull()) {
						map.put("port", record.get("ep").asMap());
					}
					if (!record.get("dev").isNull()) {
						map.put("device", record.get("dev").asMap());
					}

					if (!map.isEmpty())
						resultList.add(map);
				}
			} catch (Exception e) {
				log.error("Exception in getDeviceInfo (standard): {}", e.getMessage(), e);
			}
		}

		return resultList;
	}

	public ResponseStatus findRoute(String sourceDevName, String sourceNodeId, String targetDevName,
			String targetNodeId) {
		log.info("=>EthernetOrderService:findRoute: START");
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to find route!.");

		try {
			Map<String, Object> routeData = serviceClient.getRoute(sourceDevName, targetDevName);
			// Map<String, Object> routeData = getRouteObj(sourceDevName, targetDevName);
			Map<String, Object> path = (Map<String, Object>) routeData.get("path");

			if (path == null || path.isEmpty()) {
				response.setMessage("No path found.");
				return response;
			}

			Map<String, Object> responseData = new LinkedHashMap<>();
			responseData.put("pathFrom",
					path.get("pathFrom") != null ? path.get("pathFrom").toString().toUpperCase() : null);
			responseData.put("pathTo", path.get("pathTo") != null ? path.get("pathTo").toString().toUpperCase() : null);

			List<Map<String, Object>> hops = (List<Map<String, Object>>) path.get("hops");
			List<Map<String, Object>> evcRoute = new ArrayList<>();

			for (Map<String, Object> hop : hops) {
				Map<String, Object> hopMap = new HashMap<>();

				Map<String, Object> aDev = (Map<String, Object>) hop.get("device1");
				Map<String, Object> zDev = (Map<String, Object>) hop.get("device2");

				String connectionType = hop.get("connectionType") != null ? hop.get("connectionType").toString() : "";
				String formattedConnectionType = "SwitchedEthernet".equalsIgnoreCase(connectionType) ? "NNI"
						: connectionType;

				// Convert identifiers to uppercase
				if (aDev != null) {
					if (aDev.get("circuitId") != null)
						aDev.put("circuitId", aDev.get("circuitId").toString().toUpperCase());
					if (aDev.get("deviceIdentifier") != null)
						aDev.put("deviceIdentifier", aDev.get("deviceIdentifier").toString().toUpperCase());
				}

				if (zDev != null) {
					if (zDev.get("circuitId") != null)
						zDev.put("circuitId", zDev.get("circuitId").toString().toUpperCase());
					if (zDev.get("deviceIdentifier") != null)
						zDev.put("deviceIdentifier", zDev.get("deviceIdentifier").toString().toUpperCase());
				}

				List<Map<String, Object>> aDevInfoList = getDeviceInfo(aDev, connectionType);
				List<Map<String, Object>> zDevInfoList = getDeviceInfo(zDev, connectionType);

				Map<String, Object> aEndInfo = new HashMap<>();
				Map<String, Object> zEndInfo = new HashMap<>();

				if (!aDevInfoList.isEmpty()) {
					Map<String, Object> info = aDevInfoList.get(0);
					if ("MPLS".equalsIgnoreCase(connectionType)) {
						aEndInfo.put("device", info.get("device"));
						aEndInfo.put("deviceName", aDev.get("deviceIdentifier"));
					} else {
						aEndInfo.put("port", info.get("port"));
						aEndInfo.put("device", info.get("device"));
					}
				}

				if (!zDevInfoList.isEmpty()) {
					Map<String, Object> info = zDevInfoList.get(0);
					if ("MPLS".equalsIgnoreCase(connectionType)) {
						zEndInfo.put("device", info.get("device"));
						zEndInfo.put("deviceName", zDev.get("deviceIdentifier"));
					} else {
						zEndInfo.put("port", info.get("port"));
						zEndInfo.put("device", info.get("device"));
					}
				}
				String nniCkt = (String) aDev.get("circuitId");
				String aliasCktId = nniCkt.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
				String nniQuery = "MATCH (nni:NNIConnection) WHERE nni.aliasCktId = $aliasCktId RETURN nni";
				Map<String, Object> params = new HashMap<>();
				params.put("aliasCktId", aliasCktId);
				Map<String, Object> nniInfo = getInfo(nniQuery, params, "nni");
				hopMap.put("nniInfo", nniInfo);
				hopMap.put("aEndInfo", aEndInfo);
				hopMap.put("zEndInfo", zEndInfo);
				hopMap.put("connection", hop.get("hop"));
				hopMap.put("connectionType", formattedConnectionType);
				if (!"MPLS".equalsIgnoreCase(connectionType)) {
					hopMap.put("nniName", aDev.get("circuitId"));
				}
				evcRoute.add(hopMap);
			}

			responseData.put("evcRoute", evcRoute);

			response.setCode(200);
			response.setMessage("Successfully found the route");
			response.setData(responseData);

		} catch (Exception ex) {
			log.error("Find Route - Caught Exception: Reason: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to find route.");
		}

		log.info("<=EthernetOrderService:findRoute: ENDS");
		return response;
	}

	Map<String, Object> getRouteObj(String sourceDevName, String sourceNodeId, String targetDevName,
			String targetNodeId) {
		Map<String, Object> rtMap = new HashMap<String, Object>();
		try {
			String rtFileName = "Route-" + sourceDevName + "-" + targetDevName + ".json";
			ClassPathResource resource = new ClassPathResource(rtFileName);
			Path path = resource.getFile().toPath();
			String jsonContent = new String(Files.readAllBytes(path));
			JSONObject jsonObject = new JSONObject(jsonContent);
			log.info("JSONObject: " + jsonObject.toString(4));
			ObjectMapper mapper = new ObjectMapper();
			rtMap = mapper.readValue(jsonObject.toString(), Map.class);

			log.info("MAP: " + rtMap.toString());
		} catch (Exception ex) {
			log.info("error: " + ex.getMessage());
		}
		return rtMap;
	}

	public ResponseStatus buildCustConnection(Map<String, Object> orderInfo) {
		ResponseStatus responseStatus = new ResponseStatus();
		StringBuilder e2ErrorMessages = new StringBuilder();
		
		boolean nmiInE2Success = false;
		boolean uniInE2Success = false;

		try {
			System.out.println("=> buildCustConnection(): START");

			Map<String, Object> networkConnection = (Map<String, Object>) orderInfo.get("networkConnection");
			if (networkConnection == null) {
				responseStatus.setCode(400);
				responseStatus.setMessage("Missing networkConnection section in orderInfo.");
				return responseStatus;
			}
			String createNetworkConnection = (String) networkConnection.get("createNetworkConnection");
			String nniCktid = (String) networkConnection.get("connectionName");

			boolean nniExistsOrBuilt = false;
			if ("Y".equalsIgnoreCase(createNetworkConnection)) {
				ResponseStatus nniResponse = buildNNIConnection(orderInfo);

				if (nniResponse.getCode() != 200) {
					return nniResponse;
				}

//				try {
//					Map<String, Object> e2Resp = createNmiInE2(nniCktid);
//					Object codeObj = e2Resp.get("code");
//					int code = (codeObj instanceof Integer) ? (Integer) codeObj : Integer.parseInt(codeObj.toString());
//					boolean updateE2NNIFlag = (code == 200); // Set the flag based on E2 response
//
//					if (updateE2NNIFlag) {
//						log.info("NMI creation in E2 successful for '{}'", nniCktid);
//						nmiInE2Success = true;
//					} else {
//						log.warn("NMI creation in E2 failed for '{}': {}", nniCktid, e2Resp);
//						e2ErrorMessages.append("NMI creation failed in E2. ");
//					}
//					// Update the updateE2Flag flag in NNIConnection and allCircuits nodes
//					boolean updateSuccess = updateE2NNIFlag(nniCktid, updateE2NNIFlag);
//					if (!updateSuccess) {
//						log.error("Failed to update updateE2NNIFlag flag for NNI cktid '{}'", nniCktid);
//					}
//
//				} catch (Exception ex) {
//					log.error("Exception while calling createNmiInE2 for '{}': {}", nniCktid, ex.getMessage(), ex);
//					updateE2NNIFlag(nniCktid, false);
//					e2ErrorMessages.append("Exception during NMI creation in E2: ").append(ex.getMessage())
//							.append(". ");
//				}

			} else {
				// Check if NNI already exists
				if (nniCktid == null || nniCktid.trim().isEmpty()) {
					responseStatus.setCode(400);
					responseStatus.setMessage("Missing NNI circuit ID (cktid) for validation.");
					return responseStatus;
				}
				boolean exists = checkNNIExists(nniCktid);
				if (!exists) {
					responseStatus.setCode(404);
					responseStatus.setMessage("Network connection does not exist.");
					return responseStatus;
				}
				nniExistsOrBuilt = true;
//				//check NNI existence in E2
//				Map<String, Object> e2CheckResponse = serviceClient.getNniResponseFromE2(nniCktid);
//				Object codeObj = e2CheckResponse.get("code");
//				int code = (codeObj instanceof Integer) ? (Integer) codeObj : 500;
//
//				if (code == 200) {
//					log.info("NNI '{}' exists in E2.", nniCktid);
//					nmiInE2Success = true;
//				} else {
//					log.warn("NNI '{}' not found in E2. Response: {}", nniCktid, e2CheckResponse);
//					e2ErrorMessages.append("NNI does not exist in E2. ");
//				}

			}
			if (nniExistsOrBuilt) {
				ResponseStatus uniResponse = buildUNIConnection(orderInfo);

				if (uniResponse.getCode() != 200) {
					return uniResponse;
				}

				try {
					String uniCktid = (String) ((Map<String, Object>) orderInfo.get("customerConnection"))
							.get("connectionName");

					Map<String, Object> portInfo = fetchPortKeysForXConnect(uniCktid, nniCktid);
					if (portInfo != null && portInfo.containsKey("cPort") && portInfo.containsKey("nPort")) {
						String uniPortKey = (String) portInfo.get("cPort");
						log.info(uniPortKey + "uniPortKey");
						String nniPortKey = (String) portInfo.get("nPort");
						log.info(nniPortKey + "nniPortKey");
						createXConnectRelation(uniPortKey, nniPortKey);
					} else {
						log.warn("Skipping XCONNECT creation: Port info incomplete or not found");
					}
				} catch (Exception ex) {
					log.error("XCONNECT creation failed: {}", ex.getMessage(), ex);
					responseStatus.setCode(500);
					responseStatus.setMessage(
							"Customer connection partially built. Failed to create XCONNECT: " + ex.getMessage());
					return responseStatus;
				}
			}

			Map<String, Object> npeInfo = (Map<String, Object>) orderInfo.get("npeInformation");
			if (npeInfo != null && npeInfo.get("deviceName") != null
					&& !((String) npeInfo.get("deviceName")).isEmpty()) {
				Map<String, Object> customerDeviceInfo = (Map<String, Object>) orderInfo.get("customerDevice");
				String npeName = (String) npeInfo.get("deviceName");
				String CLLI= (String) npeInfo.get("CLLI");
				String relayrck= (String) npeInfo.get("relayrck");
				String customerDeviceName = (String) customerDeviceInfo.get("deviceName");
				Integer numberOfPaths = 1;

				String npeNodeId = getNodeIdFromDevice(npeName, CLLI, relayrck );
				if (npeNodeId == null) {
					responseStatus.setCode(404);
					responseStatus.setMessage("NPE device not found in allDevices.");
					return responseStatus;
				}

				// Call findRouteV1 to get routes between customerDevice and npeDevice
				ResponseStatus routeResponse = findRouteV1(npeName, customerDeviceName, numberOfPaths);
				if (routeResponse.getCode() != 200) {
					responseStatus.setCode(routeResponse.getCode());
					responseStatus.setMessage("Error fetching route info: " + routeResponse.getMessage());
					return responseStatus;
				}

				Map<String, Object> routeData = (Map<String, Object>) routeResponse.getData();
				List<Map<String, Object>> routesList = (List<Map<String, Object>>) routeData.get("routesList");

				List<String> npeRoutes = new ArrayList<>();

				if (routesList != null && !routesList.isEmpty()) {
					Map<String, Object> firstRouteEntry = routesList.get(0);
					List<Map<String, Object>> route = (List<Map<String, Object>>) firstRouteEntry.get("route");
					if (route != null && !route.isEmpty()) {
						for (Map<String, Object> segment : route) {
							Object nniNameObj = segment.get("nniName");
							if (nniNameObj != null) {
								npeRoutes.add(nniNameObj.toString());
							}
						}
					}
				} else {
					System.out.println("No routes found.");
				}

				updateDeviceNodesWithNPEInfo(customerDeviceName, npeName, npeNodeId, npeRoutes);
			}

			// Save build status
			orderInfo.put("buildStatus", "completed");
			ResponseStatus saveResponse = saveUNIOrderInfo(orderInfo);

			if (saveResponse.getCode() != 200) {
				responseStatus.setCode(saveResponse.getCode());
				responseStatus.setMessage(saveResponse.getMessage());
				return responseStatus;
			}

//			// Call createUNIInE2 service method ONLY if NMI exists in E2
//			if (nmiInE2Success) {
//				try {
//					String uniCktid = (String) ((Map<String, Object>) orderInfo.get("customerConnection"))
//							.get("connectionName");
//					ResponseStatus e2Response = createUNIInE2(uniCktid);
//
//					boolean updateE2UNIFlag = (e2Response.getCode() == 200); // Set the flag based on E2 response
//					if (updateE2UNIFlag) {
//						log.info("UNI circuit '{}' created successfully in E2.", uniCktid);
//						uniInE2Success = true;
//					} else {
//						log.warn("UNI creation in E2 failed: {}", e2Response.getMessage());
//						e2ErrorMessages.append("UNI creation failed in E2. ");
//					}
//
//					// Update the updateE2UNI flag in the UNIConnection node
//					boolean updateSuccess = updateE2UNIFlag(uniCktid, updateE2UNIFlag);
//					if (!updateSuccess) {
//						log.error("Failed to update updateE2UNIFlag flag for UNI cktid '{}'", uniCktid);
//					}
//
//				} catch (Exception e) {
//					log.error("Exception while calling createUNIInE2: {}", e.getMessage(), e);
//					String uniCktid = (String) ((Map<String, Object>) orderInfo.get("customerConnection"))
//							.get("connectionName");
//					updateE2UNIFlag(uniCktid, false);
//					e2ErrorMessages.append("Exception during UNI creation in E2: ").append(e.getMessage()).append(". ");
//				}
//			} else {
//				// NMI missing in E2, skip UNI creation
//				log.warn("Skipping UNI creation in E2 because NMI does not exist in E2 for '{}'", nniCktid);
//				e2ErrorMessages.append("NMI needs to be created in E2 before UNI creation. ");
//			}

			if (e2ErrorMessages.length() == 0) {
				responseStatus.setCode(200);
				responseStatus.setMessage("Customer connection built successfully in USIL ");
			} else if (uniInE2Success ) {
				responseStatus.setCode(207);
				responseStatus.setMessage(
						"Customer connection built successfully in USIL "
								+ e2ErrorMessages.toString().trim());
			} else {
				responseStatus.setCode(207);
				responseStatus.setMessage("Customer connection built successfully in USIL"
						+ e2ErrorMessages.toString().trim());
			}

		} catch (Exception e) {
			responseStatus.setCode(500);
			responseStatus.setMessage("Error occurred: " + e.getMessage());
		}

		System.out.println("<= buildCustConnection(): END");
		return responseStatus;
	}

	private boolean checkNNIExists(String nniCktid) {
		String circuit = nniCktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
		String query = "MATCH (nni:NNIConnection{aliasCktId:$circuit}) RETURN nni LIMIT 1";

		try (Session session = driver.session()) {
			Result result = session.run(query, Map.of("circuit", circuit));
			return result.hasNext();
		} catch (Exception e) {
			log.error("Error checking NNI existence for cktid '{}': {}", nniCktid, e.getMessage(), e);
			return false;
		}
	}

	private Map<String, Object> fetchPortKeysForXConnect(String uniCktid, String nniCktid) {
		String aliasUniCktid = uniCktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		String query = "MATCH (u:UNIConnection{aliasCktId:$aliasUniCktid})-[:CONNECTED_TO]->(up:EquipmentPort) "
				+ "MATCH (nni:NNIConnection{aliasCktId:$aliasNniCktId})-[:CONNECTED_TO]-(np:EquipmentPort{node_id:up.node_id}) "
				+ "RETURN up.portKey AS cPort, np.portKey AS nPort";

		String aliasNniCktId = nniCktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		try (Session session = driver.session()) {
			Result result = session.run(query, Map.of("aliasUniCktid", aliasUniCktid, "aliasNniCktId", aliasNniCktId));

			if (result.hasNext()) {
				Record record = result.next();
				Map<String, Object> portKeys = new HashMap<>();
				portKeys.put("cPort", record.get("cPort").asString());
				portKeys.put("nPort", record.get("nPort").asString());
				return portKeys;
			}
		} catch (Exception e) {
			log.error("Failed to fetch port keys for XConnect (UNI cktid: {}, NNI cktid: {}): {}", uniCktid, nniCktid,
					e.getMessage(), e);
		}

		return null;
	}

	public ResponseStatus buildNNIConnection(Map<String, Object> orderInfo) {
		ResponseStatus responseStatus = new ResponseStatus();

		try {
			Map<String, Object> networkPayload = buildNNIParams(orderInfo);

			apiConfig.setContext("inventory");

			HttpHeaders headers =
					ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request =
					new HttpEntity<>(networkPayload, headers);

			ResponseEntity<ResponseStatus> response =
					restTemplate.postForEntity(
							apiConfig.getCreateNNIUrl(),
							request,
							ResponseStatus.class);

			if (response.getBody() != null) {
				return response.getBody();
			}

			responseStatus.setCode(500);
			responseStatus.setMessage("No response received from NNI API");
			return responseStatus;

		} catch (HttpStatusCodeException ex) {

			String errorMessage = ex.getResponseBodyAsString();

			responseStatus.setCode(ex.getStatusCode().value());
			responseStatus.setMessage(errorMessage);

			log.error("NNI API Error: {}", errorMessage);

			return responseStatus;

		} catch (Exception ex) {

			responseStatus.setCode(500);
			responseStatus.setMessage(ex.getMessage());

			log.error("Error calling createNetworkConnection API", ex);

			return responseStatus;
		}
	}

	public ResponseStatus buildUNIConnection(Map<String, Object> orderInfo) {
		ResponseStatus responseStatus = new ResponseStatus();

		try {
			Map<String, Object> uniPayload = buildUNIParams(orderInfo);

			apiConfig.setContext("inventory");

			HttpHeaders headers =
					ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request =
					new HttpEntity<>(uniPayload, headers);

			log.info("UNI HTTP Request: {}", request);

			ResponseEntity<ResponseStatus> response =
					restTemplate.postForEntity(
							apiConfig.getCreateUNIUrl(),
							request,
							ResponseStatus.class);

			if (response.getBody() != null) {

				if (response.getStatusCode() == HttpStatus.OK
						&& response.getBody().getCode() == 200) {

					Map<String, Object> customerPort =
							(Map<String, Object>) orderInfo.get("customerPort");

					if (customerPort != null && !customerPort.isEmpty()) {
						updateCircuitPortInfo(customerPort);
					}
				}

				return response.getBody();
			}

			responseStatus.setCode(500);
			responseStatus.setMessage("No response received from UNI API");
			return responseStatus;

		} catch (HttpStatusCodeException ex) {

			log.error("UNI API Error: {}", ex.getResponseBodyAsString(), ex);

			responseStatus.setCode(ex.getStatusCode().value());
			responseStatus.setMessage(ex.getResponseBodyAsString());

			return responseStatus;

		} catch (Exception ex) {

			log.error("Error calling external createUNIConnection API", ex);

			responseStatus.setCode(500);
			responseStatus.setMessage(ex.getMessage());

			return responseStatus;
		}
	}

	private boolean updateCircuitPortInfo(Map<String,Object> portInfo) {

		boolean res = false;
		String breakoutPort = (String) portInfo.get("breakoutPort");
		String portLabel =
		        "Yes".equalsIgnoreCase(breakoutPort)
		        ? "BreakoutPort"
		        : "EquipmentPort";


		try (Session session = driver.session()) {
			String portKey = (String) portInfo.get("portKey");
			String baseHeci = (String) portInfo.get("baseHeci");
			String at = (String) portInfo.get("AT");
			String functionCode = (String) portInfo.get("functionCode");
			String bankCode = (String) portInfo.get("bankCode");
			String partNumber = (String) portInfo.get("partNumber");
			String partDesc = (String) portInfo.get("partDesc");
			String notes = (String) portInfo.get("notes");

			String portQuery = "MATCH(port:" + portLabel + "{portKey:'" + portKey + "', base_heci:'" + baseHeci + "'}) "
					+ " SET port.AT = '" + at + "', port.functionCode = '" + functionCode+ "',"
					+ " port.bankCode = '"+ bankCode + "', port.partNumber = '" + partNumber+ "',"
					+ " port.partDesc = '" + partDesc+ "', port.notes = '" + notes + "'"
					+ " RETURN port";
			log.info("Update PartNumber of Port: '{}'", portQuery);
			Result result = session.run(portQuery);
			if (result.hasNext()) {
				log.info("PartNumber info updated in EquipmentPort: '{}'", portKey);
				res = true;
			}
		} catch (Exception ex) {
			log.error("Failed to update partNumber info in EquipmentPort.");
		}
		return res;
	}
	public Map<String, Object> buildNNIParams(Map<String, Object> orderInfo) {
		Map<String, Object> nniParams = new HashMap<>();

		try {
			// Extract customerEnd from networkConnection
			String customerEnd = "";
			String cktId = "";
			if (orderInfo.containsKey("networkConnection")) {
				Map<String, Object> networkConnection = (Map<String, Object>) orderInfo.get("networkConnection");
				customerEnd = (String) networkConnection.getOrDefault("customerEnd", "");
				cktId = (String) networkConnection.getOrDefault("connectionName", "");
				String circuitBandwidth = (String) networkConnection.get("circuitBandwidth");
				if (circuitBandwidth != null) {
				    circuitBandwidth = ApplicationUtils.bandwidthConvertor(circuitBandwidth, true, false);
				    networkConnection.put("circuitBandwidth", circuitBandwidth);
				}

				Map<String, String> fieldMapping = Map.ofEntries(Map.entry("sys", "channelgroup"),
						Map.entry("cxrFacType", "cxrType"), Map.entry("diversity", "isDiverse"),
						Map.entry("circuitBandwidth", "bandwidth"), Map.entry("MCO", "MCO"), Map.entry("NC", "NC"),
						Map.entry("NCI", "NCI"), Map.entry("secondaryNCI", "SECNCI"), Map.entry("WCO", "WCO"),
						Map.entry("CLO", "CLO"), Map.entry("ECO", "ECO"), Map.entry("OCO", "OCO"),
						Map.entry("CCO", "CCO"), Map.entry("allocationPercent", "allocationPercent"),
						Map.entry("overflow", "overflow"),
						Map.entry("ethernetBearerCircuits", "ethernetBearerCircuits"), Map.entry("isLAG", "isLAG"));

				for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
					String sourceKey = entry.getKey();
					String targetKey = entry.getValue();
					Object value = networkConnection.get(sourceKey);
					if (value != null && !(value instanceof String && ((String) value).trim().isEmpty())) {
						nniParams.put(targetKey, value);
					}
				}
			}

			if (orderInfo.containsKey("layer1")) {
				List<Map<String, Object>> layer1List = (List<Map<String, Object>>) orderInfo.get("layer1");

				if (layer1List != null && !layer1List.isEmpty()) {
					List<Map<String, Object>> inventoriedConnections = new ArrayList<>();

					for (Map<String, Object> connection : layer1List) {
						inventoriedConnections.add(connection);
						/*Object inventoried = connection.get("type");
						if (inventoried instanceof Boolean && (Boolean) inventoried) {
							inventoriedConnections.add(connection);
						}*/
					}

					if (!inventoriedConnections.isEmpty()) {
						try {
							ObjectMapper mapper = new ObjectMapper();
							String layer1Json = mapper.writeValueAsString(inventoriedConnections);
							nniParams.put("layer1", layer1Json);
						} catch (JsonProcessingException e) {
							log.error("Failed to serialize inventoried layer1 records to JSON", e);
						}
					}
				}
			}

			if (orderInfo.containsKey("transport")) {
			    List<Map<String, Object>> transportList = (List<Map<String, Object>>) orderInfo.get("transport");

			    boolean layer1ProvRequired = false;

			    if (transportList != null && !transportList.isEmpty()) {
			        List<Map<String, Object>> transPorts = new ArrayList<>();

			        for (Map<String, Object> transPort : transportList) {
			            transPorts.add(transPort);

			            if (!layer1ProvRequired) {
			                Map<String, Object> connection = (Map<String, Object>) transPort.get("connection");

			                if (connection != null && connection.get("connectionName") != null) {
			                    Map<String, Object> aEnd = (Map<String, Object>) transPort.get("aEnd");
			                    if (aEnd != null) {
			                        Boolean isNew = (Boolean) aEnd.get("isNew");
			                        String tech = (String) aEnd.get("tech");

			                        if (Boolean.FALSE.equals(isNew) && "OPTICAL".equalsIgnoreCase(tech)) {
			                            layer1ProvRequired = true;
			                        }
			                    }
			                } else {
			                    Map<String, Object> zEnd = (Map<String, Object>) transPort.get("zEnd");
			                    if (zEnd != null) {
			                        Boolean isNew = (Boolean) zEnd.get("isNew");
			                        String tech = (String) zEnd.get("tech");

			                        if (Boolean.FALSE.equals(isNew) && "OPTICAL".equalsIgnoreCase(tech)) {
			                            layer1ProvRequired = true;
			                        }
			                    }
			                }
			            }
			        }

			        try {
			            ObjectMapper mapper = new ObjectMapper();
			            String transportJson = mapper.writeValueAsString(transPorts);
			            nniParams.put("transport", transportJson);
			        } catch (JsonProcessingException e) {
			            log.error("Failed to serialize inventoried Optical Transport records to JSON", e);
			        }
			    }

			    nniParams.put("layer1ProvRequired", layer1ProvRequired);
			    log.info("layer1ProvRequired = {}", layer1ProvRequired);
			}

			// Extract portA and portZ from networkPort
			Map<String, Object> networkPort = (Map<String, Object>) orderInfo.get("networkPort");
			if (networkPort != null) {
			Map<String, Object> portA = (Map<String, Object>) networkPort.get("portA");
			Map<String, Object> portZ = (Map<String, Object>) networkPort.get("portZ");

			// Determine source and destination port numbers based on customerEnd
			String sourcePortNum = "";
			String destPortNum = "";
			String sourceBreakoutIndex = "";
			String destBreakoutIndex = "";

			if ("A".equalsIgnoreCase(customerEnd)) {
				// Port assignments
				sourcePortNum = (String) portA.get("portKey");
				destPortNum = (String) portZ.get("portKey");
				sourceBreakoutIndex = (String) portA.get("breakOutIndex");
				destBreakoutIndex = (String) portZ.get("breakOutIndex");

//				Map<String, Object> sourceInfo = getDeviceInfo(sourcePortNum);
//				Map<String, Object> destInfo = getDeviceInfo(destPortNum);
				boolean isBreakOut=false;
				
				 if (portA != null) {
				        String aBreakoutPort = (String) portA.get("breakoutPort");
				        if (aBreakoutPort != null) {
				            nniParams.put("sourceBreakoutPort", aBreakoutPort);
				            isBreakOut =true;
				        }
				    }
				 Map<String, Object> sourceInfo = getDeviceInfoV2(sourcePortNum,isBreakOut);
				 
				 if (portZ != null) {
				        String zBreakoutPort = (String) portZ.get("breakoutPort");
				        if (zBreakoutPort != null) {
				            nniParams.put("destinationBreakoutPort", zBreakoutPort);
				            isBreakOut =true;
				        }
				    }
				 Map<String, Object> destInfo = getDeviceInfoV2(destPortNum,isBreakOut);

				nniParams.put("sourceNodeId", sourceInfo.get("node_id"));
				nniParams.put("sourceBaseHeci", sourceInfo.get("base_heci"));
				nniParams.put("destNodeId", destInfo.get("node_id"));
				nniParams.put("destBaseHeci", destInfo.get("base_heci"));
				nniParams.put("aPartNumber", portA.get("partNumber"));
				nniParams.put("aPartDesc", portA.get("partDesc"));
				nniParams.put("zPartNumber", portZ.get("partNumber"));
				nniParams.put("zPartDesc", portZ.get("partDesc"));
			} else if ("Z".equalsIgnoreCase(customerEnd)) {
				sourcePortNum = (String) portZ.get("portKey");
				destPortNum = (String) portA.get("portKey");
				destBreakoutIndex = (String) portA.get("breakOutIndex");
				sourceBreakoutIndex = (String) portZ.get("breakOutIndex");

//				Map<String, Object> sourceInfo = getDeviceInfo(sourcePortNum);
//				Map<String, Object> destInfo = getDeviceInfo(destPortNum);
				boolean isBreakOut=false;
				if (portZ != null) {
			        String zBreakoutPort = (String) portZ.get("breakoutPort");
			        if (zBreakoutPort != null) {
			            nniParams.put("sourceBreakoutPort", zBreakoutPort);
			            isBreakOut =true;
			        }
			        log.info("zBreakoutPort"+zBreakoutPort);
			    }
				Map<String, Object> sourceInfo = getDeviceInfoV2(sourcePortNum,isBreakOut);
			 if (portA != null) {
			        String aBreakoutPort = (String) portA.get("breakoutPort");
			        if (aBreakoutPort != null) {
			            nniParams.put("destinationBreakoutPort", aBreakoutPort);
			            isBreakOut =true;
			        }
			        log.info("aBreakoutPort"+aBreakoutPort);
			    }
			 Map<String, Object> destInfo = getDeviceInfoV2(destPortNum,isBreakOut);
				
				log.info("sourcePortNum"+sourcePortNum);
				log.info("destPortNum"+destPortNum);

				nniParams.put("sourceNodeId", sourceInfo.get("node_id"));
				nniParams.put("sourceBaseHeci", sourceInfo.get("base_heci"));
				nniParams.put("destNodeId", destInfo.get("node_id"));
				nniParams.put("destBaseHeci", destInfo.get("base_heci"));
				nniParams.put("aPartNumber", portZ.get("partNumber"));
				nniParams.put("aPartDesc", portZ.get("partDesc"));
				nniParams.put("zPartNumber", portA.get("partNumber"));
				nniParams.put("zPartDesc", portA.get("partDesc"));
			} else {
				log.warn("Invalid or missing 'customerEnd' value in networkConnection");
			}
			nniParams.put("sourcePortNum", sourcePortNum);
			nniParams.put("destPortNum", destPortNum);
			// setting breakout index:
			if (sourceBreakoutIndex != null && !sourceBreakoutIndex.isEmpty()) {
				nniParams.put("sourceBreakoutIndex", sourceBreakoutIndex);
			}
			if (destBreakoutIndex != null && !destBreakoutIndex.isEmpty()) {
				nniParams.put("destBreakoutIndex", destBreakoutIndex);
			}
			}else {
			    log.warn("networkPort is null, skipping port info");
			}
			nniParams.put("cktid", cktId);
			nniParams.put("user", orderInfo.get("user"));
			String cac = generateUsilCAC(false);
			nniParams.put("cac", cac);
			// nniParams.put("cac", "UCAA8AA2");
			nniParams.put("type", "Carrier");

			log.info("Built NNI params: {}", nniParams);
		} catch (Exception e) {
			log.error("Error while building NNI params: {}", e.getMessage(), e);
		}

		return nniParams;
	}

	public Map<String, Object> buildUNIParams(Map<String, Object> orderInfo) {
		Map<String, Object> uniParams = new HashMap<>();
		ObjectMapper mapper = new ObjectMapper();
		String aliasCktId = null;
		
		boolean isUNI = false;
		Object isUNIObj = orderInfo.get("isUNI");
		if (isUNIObj instanceof Boolean) {
		    isUNI = (Boolean) isUNIObj;
		}

		try {
			if (orderInfo.containsKey("customerConnection")) {
				Map<String, Object> customerConnection = (Map<String, Object>) orderInfo.get("customerConnection");
				
				String circuitBandwidth = (String) customerConnection.get("circuitBandwidth");
				if (circuitBandwidth != null) {
				    circuitBandwidth = ApplicationUtils.bandwidthConvertor(circuitBandwidth, true, false);
				    customerConnection.put("circuitBandwidth", circuitBandwidth);
				}
				
				 // Prepare aliasCktId from connectionName
	            String connectionName = (String) customerConnection.get("connectionName");
	            if (connectionName != null && !connectionName.trim().isEmpty()) {
	                aliasCktId = connectionName.replaceAll("[^A-Z0-9]", "");
	                log.info("Prepared aliasCktId: {}", aliasCktId);

	                try (Session session = driver.session()) {
	                    String query = "MATCH (n:EthernetOrder) " +
	                                   "WHERE n.aliasCktId = $aliasCktId AND n.deletedTimeStamp IS NULL " +
	                                   "RETURN n.productPayload AS productPayload LIMIT 1";

	                    Record record = null;
	                    try (Transaction tx = session.beginTransaction()) {
	                        Result result = tx.run(query, Map.of("aliasCktId", aliasCktId));
	                        if (result.hasNext()) {
	                            record = result.next();
	                        }
	                    }

	                    if (record != null && !record.get("productPayload").isNull()) {
	                        String productPayloadJson = record.get("productPayload").asString();
	                        log.info("Fetched productPayload for aliasCktId {}: {}", aliasCktId, productPayloadJson);

	                        try {
	                            Map<String, Object> productPayload = mapper.readValue(productPayloadJson, new TypeReference<>() {});
	                            Object relatedPartyObj = productPayload.get("relatedParty");

	                            if (relatedPartyObj instanceof List<?> relatedPartyList) {
	                                for (Object itemObj1 : relatedPartyList) {
	                                    if (itemObj1 instanceof Map<?, ?> locationMap) {
	                                        String locID = (String) locationMap.get("locationID");
	                                        uniParams.put("addressId", locationMap.get("locationID"));
                                            uniParams.put("billingAccountNumber", locationMap.get("billingAccountNumber"));
	                                        if (locID != null && !locID.isEmpty()) {
	                                            Object addressObj = locationMap.get("address");
	                                            if (addressObj instanceof Map<?, ?> addressMap) {
	                                                uniParams.put("subscriberAddress", addressMap.get("addressLine"));
	                                                uniParams.put("subscriberCity", addressMap.get("city"));
	                                                uniParams.put("subscriberState", addressMap.get("state"));
	                                                uniParams.put("subscriberZip", addressMap.get("zip"));
	                                                uniParams.put("subscriberCountry", "USA");
	                                                uniParams.put("subscriberFullAddress", addressMap.get("name"));
	                                            }
	                                        }
	                                    }
	                                }
	                            }
	                        } catch (Exception ex) {
	                            log.error("Failed to parse productPayload JSON for aliasCktId {}: {}", aliasCktId, ex.getMessage(), ex);
	                        }
	                    }
	                } catch (Exception ex) {
	                    log.error("Neo4j query failed for aliasCktId {}: {}", aliasCktId, ex.getMessage(), ex);
	                }
	            }

				Map<String, String> fieldMapping = Map.ofEntries(Map.entry("connectionName", "circuitName"),
						Map.entry("circuitBandwidth", "bandwidth"), Map.entry("icscCode", "icscCode"),
						Map.entry("NC", "networkChannel"), Map.entry("NCI", "networkChannelInterface"),
						Map.entry("secondaryNCI", "networkChannelInterfaceSec"), Map.entry("WCO", "WCO"),
						Map.entry("ECO", "ECO"), Map.entry("OCO", "OCO"), Map.entry("CCO", "CCO"),
						Map.entry("affiliateOwner", "affiliateOwner"), Map.entry("acivityCode", "acivityCode"),
						Map.entry("bundling", "bundling"), Map.entry("isDiverse", "isDiverse"),
						Map.entry("frameSize", "frameSize"), Map.entry("noOfEVCs_OVCsAllowed", "noOfEVCs_OVCsAllowed"),
						Map.entry("CTAG", "CTAG"));

				for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
					String sourceKey = entry.getKey();
					String targetKey = entry.getValue();
					Object value = customerConnection.get(sourceKey);

					if (value != null && !(value instanceof String && ((String) value).trim().isEmpty())) {
						uniParams.put(targetKey, value);
					}
				}

				// Add bundling also to 'allTo1Bundling'
				Object bundlingValue = customerConnection.get("bundling");
				if (bundlingValue != null
						&& !(bundlingValue instanceof String && ((String) bundlingValue).trim().isEmpty())) {
					uniParams.put("allTo1Bundling", bundlingValue);
				}
			} else {
				log.warn("'customerConnection' key not found in orderInfo");
			}

			uniParams.put("portBasedRateLimited", "true");

			if (orderInfo.containsKey("customerPort")) {
				Map<String, Object> customerPort = (Map<String, Object>) orderInfo.get("customerPort");
				Object portKey = customerPort.get("portKey");
				String breakoutIndex = (String) customerPort.get("breakOutIndex");
				log.info(portKey + "portKey");
				boolean isBreakOutPort=false;
				String breakoutPort = (String) customerPort.get("breakoutPort");
			    if (breakoutPort != null) {
			        uniParams.put("breakoutPort", breakoutPort);
			        isBreakOutPort=true;
			    }

				if (portKey != null && !(portKey instanceof String && ((String) portKey).trim().isEmpty())) {
					String portKeyStr = portKey.toString();
					uniParams.put("portNum", portKeyStr);
					uniParams.put("partNumber", customerPort.get("partNumber"));
					uniParams.put("partDesc", customerPort.get("partDesc"));
					// setting breakout index:
					if (breakoutIndex != null && !breakoutIndex.isEmpty()) {
						uniParams.put("breakoutIndex", breakoutIndex);
					}
					try {
						Map<String, Object> deviceInfo = getDeviceInfoV2(portKeyStr,isBreakOutPort);
						if (deviceInfo != null) {
							Object nodeId = deviceInfo.get("node_id");
							Object baseHeci = deviceInfo.get("base_heci");
							Object deviceName = deviceInfo.get("deviceName");

							if (nodeId != null)
								uniParams.put("deviceNodeId", nodeId);
							if (baseHeci != null)
								uniParams.put("deviceBaseHeci", baseHeci);
							if (deviceName != null)
								uniParams.put("deviceName", deviceName);
						}
					} catch (Exception ex) {
						log.warn("getDeviceInfo failed for portKey {}: {}", portKeyStr, ex.getMessage());
					}
				}
			}
			if (orderInfo.containsKey("npeInformation")) {
				Map<String, Object> npeDevice = (Map<String, Object>) orderInfo.get("npeInformation");
				Object npeName = npeDevice.get("deviceName");

				if (npeName != null && !(npeName instanceof String && ((String) npeName).trim().isEmpty())) {
					String npeNameStr = npeName.toString();
					uniParams.put("npeName", npeNameStr);

					try (Session session = driver.session()) {
						String query = "MATCH (n:allDevices) WHERE n.name = $deviceName AND n.deletedTimeStamp is null RETURN n.node_id AS nodeId LIMIT 1";
						Record record = session.readTransaction(tx -> {
							Result result = tx.run(query, Map.of("deviceName", npeNameStr));
							return result.hasNext() ? result.next() : null;
						});

						if (record != null && record.get("nodeId") != null) {
							String nodeId = record.get("nodeId").asString();
							if (!nodeId.trim().isEmpty()) {
								uniParams.put("npe_Node_Id", nodeId);
							}
						}
					} catch (Exception ex) {
						log.warn("Neo4j query failed for npeName {}: {}", npeNameStr, ex.getMessage());
					}
				}
			}

			uniParams.put("uniType", "Serial");
			String cac = generateUsilCAC(true);
			uniParams.put("cac", cac);
			uniParams.put("serviceType", isUNI ? "MEF UNI" : "MEF ENNI");
            uniParams.put("hotcutRequired", orderInfo.getOrDefault("hotcutRequired", false));
            uniParams.put("user", orderInfo.get("user"));
			if (orderInfo.containsKey("rateLimitType") && orderInfo.get("rateLimitType") != null) {
				uniParams.put("rateLimitType", orderInfo.get("rateLimitType"));
			} else {
				uniParams.put("rateLimitType", "Per Port");
			}

			String loca = ""; 
			Object uniLayer1Obj = orderInfo.get("uniLayer1");
			if (uniLayer1Obj instanceof List) {
				List<Map<String, Object>> uniLayer1List = (List<Map<String, Object>>) uniLayer1Obj;
				for(Map<String, Object> l1Obj: uniLayer1List) {
					int conn = (int)l1Obj.get("connectionNumber");
					if(conn == 2 ) {
						loca = (String)l1Obj.get("clliA");
						uniParams.put("loca", loca);
						break;
					}
				}
				if (!uniLayer1List.isEmpty()) {
					try {
						String layer1Json = mapper.writeValueAsString(uniLayer1List);
						uniParams.put("layer1", layer1Json);
					} catch (JsonProcessingException e) {
						log.error("Failed to serialize UNI layer1 records to JSON", e);
					}
				}
			}


			if (orderInfo.containsKey("uniTransport")) {
				List<Map<String, Object>> transportList = (List<Map<String, Object>>) orderInfo.get("uniTransport");

				boolean layer1ProvRequired = false;

				if (transportList != null && !transportList.isEmpty()) {
					List<Map<String, Object>> transPorts = new ArrayList<>();

					for (Map<String, Object> transPort : transportList) {
						transPorts.add(transPort);

						if (!layer1ProvRequired) {
							Map<String, Object> connection = (Map<String, Object>) transPort.get("connection");

							if (connection != null && connection.get("connectionName") != null) {
								int connNum = (int) connection.get("connectionNumber");
								Map<String, Object> aEnd = (Map<String, Object>) transPort.get("aEnd");
								if (aEnd != null) {
									Boolean isNew = (Boolean) aEnd.get("isNew");
									String tech = (String) aEnd.get("tech");
									if (connNum == 2 && StringUtils.isBlank(loca)) {
										loca = (String) aEnd.get("deviceName");
										uniParams.put("loca", loca);
									}

									if (Boolean.FALSE.equals(isNew) && "OPTICAL".equalsIgnoreCase(tech)) {
										layer1ProvRequired = true;
									}
								}
							} else {
								Map<String, Object> zEnd = (Map<String, Object>) transPort.get("zEnd");
								if (zEnd != null) {
									Boolean isNew = (Boolean) zEnd.get("isNew");
									String tech = (String) zEnd.get("tech");

									if (Boolean.FALSE.equals(isNew) && "OPTICAL".equalsIgnoreCase(tech)) {
										layer1ProvRequired = true;
									}
								}
							}
						}
					}

					try {
						String transportJson = mapper.writeValueAsString(transPorts);
						uniParams.put("transport", transportJson);
					} catch (JsonProcessingException e) {
						log.error("Failed to serialize inventoried Optical Transport records to JSON", e);
					}
				}

				uniParams.put("uniL1ProvRequired", layer1ProvRequired);
				log.info("uniL1ProvRequired = {}", layer1ProvRequired);
			}
			// uniParams.put("cac", "USAA8AA2");
			log.info("Built UNI params: {}", uniParams);
		} catch (Exception e) {
			log.error("Error while building UNI params: {}", e.getMessage(), e);
		}
		return uniParams;
	}

	public Map<String, Object> getDeviceInfo(String portKey) {
		Map<String, Object> resultMap = new HashMap<>();

		String query = "MATCH (n:EquipmentPort)-[:COMPONENT_OF*]->(e:Equipment) WHERE n.portKey = $portKey "
				+ "RETURN e.node_id AS node_id, e.base_heci AS base_heci, e.name as deviceName";

		try (Session session = driver.session()) {
			Instant start = Instant.now();
			Result result = session.run(query, Map.of("portKey", portKey));
			if (result.hasNext()) {
				Record record = result.next();
				resultMap.put("node_id", record.get("node_id").asString());
				resultMap.put("base_heci", record.get("base_heci").asString());
				resultMap.put("deviceName", record.get("deviceName").asString());
			} else {
				log.warn("No Equipment found for portKey: {}", portKey);
			}
			log.info("Neo4j query executed in {} ms", Duration.between(start, Instant.now()).toMillis());

		} catch (Exception e) {
			log.error("Error querying Neo4j for portKey {}: {}", portKey, e.getMessage(), e);
		}

		return resultMap;
	}
	
	public Map<String, Object> getDeviceInfoV2(String portKey, boolean isBreakOut) {
		Map<String, Object> resultMap = new HashMap<>();

		
		
		 String query;
		    if (isBreakOut) {
		        query = "MATCH (p {portKey: $portKey}) "
		              + "WHERE (p:EquipmentPort OR p:BreakoutPort) "
		              + "OPTIONAL MATCH (p)-[:COMPONENT_OF*0..]->(n:Equipment) "
		              + "RETURN n.node_id AS node_id, n.base_heci AS base_heci, n.name AS deviceName";
		    } else {
		        query = "MATCH (n:EquipmentPort)-[:COMPONENT_OF*]->(e:Equipment) "
		              + "WHERE n.portKey = $portKey "
		              + "RETURN e.node_id AS node_id, e.base_heci AS base_heci, e.name AS deviceName";
		    }

		    log.info("query.."+query);
		    try (Session session = driver.session()) {
			Instant start = Instant.now();
			Result result = session.run(query, Map.of("portKey", portKey));
			if (result.hasNext()) {
				Record record = result.next();
				resultMap.put("node_id", record.get("node_id").asString());
				resultMap.put("base_heci", record.get("base_heci").asString());
				resultMap.put("deviceName", record.get("deviceName").asString());
			} else {
				log.warn("No Equipment found for portKey: {}", portKey);
			}
			log.info("Neo4j query executed in {} ms", Duration.between(start, Instant.now()).toMillis());
			log.info("resultMap.."+resultMap);
		} catch (Exception e) {
			log.error("Error querying Neo4j for portKey {}: {}", portKey, e.getMessage(), e);
		}

		return resultMap;
	}

	public void createXConnectRelation(String fromPortKey, String toPortKey) {
		String query = "MATCH (p1:EquipmentPort {portKey: $fromPortKey}), (p2:EquipmentPort {portKey: $toPortKey}) "
				+ "MERGE (p1)-[:XCONNECT]->(p2)";

		try (Session session = driver.session()) {
			session.run(query, Map.of("fromPortKey", fromPortKey, "toPortKey", toPortKey));
			log.info("XCONNECTS_TO relationship created from {} to {}", fromPortKey, toPortKey);
		} catch (Exception e) {
			log.error("Failed to create XCONNECTS_TO between {} and {}: {}", fromPortKey, toPortKey, e.getMessage());
		}
	}

	public ResponseStatus saveEVCOrderInfo(Map<String, Object> requestObject) {
		log.info("=>EthernetOrderService:saveEVCOrderInfo: START");
		log.info("Save EVC Order Info: {}", requestObject.toString());

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		Map<String, Object> flattenedProperties = new HashMap<>();

		try {
			// Extract circuitId from the object
			String serviceName = (String) requestObject.get("evcCircuit");
			if (StringUtils.isBlank(serviceName)) {
				response.setCode(400);
				response.setMessage("Missing circuitId in request");
				return response;
			}
			String ncCode = (String) requestObject.get("nc");
			if (StringUtils.isBlank(ncCode)) {
				response.setCode(400);
				response.setMessage("Missing NC code in request");
				return response;
			}
			String bandwidth = (String) requestObject.get("bandwidth");
			if (StringUtils.isBlank(bandwidth)) {
				response.setCode(400);
				response.setMessage("Missing bandwidth in request");
				return response;
			}

			String aliasCktId = serviceName.replaceAll("[^A-Z0-9]", "");
			flattenedProperties.put("serviceName", serviceName);
			flattenedProperties.put("aliasCktId", aliasCktId);
			flattenedProperties.put("bandwidth", bandwidth);
			flattenedProperties.put("nc", ncCode);
			String nci = (String) requestObject.get("nci");
			if (StringUtils.isNotBlank(nci))
				flattenedProperties.put("nci", nci);
			String serviceCos = (String) requestObject.get("serviceCos");
			if (StringUtils.isNotBlank(serviceCos))
				flattenedProperties.put("serviceCos", serviceCos);
			String serviceType = (String) requestObject.get("serviceType");
			if (StringUtils.isNotBlank(serviceType))
				flattenedProperties.put("serviceType", serviceType);

			String serviceId = (String) requestObject.get("serviceId");
			if (StringUtils.isNotBlank(serviceId))
				flattenedProperties.put("serviceId", serviceId);
			String evcOrderNumber = (String) requestObject.get("evcOrderNumber");
			if (StringUtils.isNotBlank(evcOrderNumber))
				flattenedProperties.put("evcOrderNumber", evcOrderNumber);

			String subscriberName = (String) requestObject.get("subscriberName");
			if (StringUtils.isNotBlank(subscriberName))
				flattenedProperties.put("subscriberName", subscriberName);
			String subscriberType = (String) requestObject.get("subscriberType");
			if (StringUtils.isNotBlank(subscriberType))
				flattenedProperties.put("subscriberType", subscriberType);
			String acna_ccna_subscriberId = (String) requestObject.get("acna_ccna_subscriberId");
			if (StringUtils.isNotBlank(acna_ccna_subscriberId))
				flattenedProperties.put("acna_ccna_subscriberId", acna_ccna_subscriberId);

			List<Map<String, Object>> uniList = (List<Map<String, Object>>) requestObject.get("uniList");
			if (uniList == null || uniList.isEmpty() == true) {
				response.setCode(400);
				response.setMessage("Empty uni circuits info in request");
				return response;
			}

			// Convert productPayload to JSON string and save as property
			ObjectMapper mapper = new ObjectMapper();
			String uniListStr = mapper.writeValueAsString(uniList);
			flattenedProperties.put("uniList", uniListStr);

			// Save to Neo4j
			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();

				StringBuilder mergeQuery = new StringBuilder("MERGE (n:EVCOrder {serviceName: $serviceName}) SET ");
				String propertyAssignments = flattenedProperties.entrySet().stream()
						.filter(entry -> !"serviceName".equals(entry.getKey()))
						.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
						.collect(Collectors.joining(", "));
				mergeQuery.append(propertyAssignments);

				tx.run(mergeQuery.toString(), flattenedProperties);
				tx.commit();

				response.setCode(200);
				response.setMessage("EVC Order saved successfully.");
			}
		} catch (ServiceUnavailableException ex) {
			log.error("Service unavailable: {}", ex.getMessage());
			response.setCode(404);
			response.setMessage("Service unavailable: " + ex.getMessage());
		} catch (Exception ex) {
			log.error("Error saving EthernetOrder: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save EVC Order: " + ex.getMessage());
		}
		return response;
	}

	public Map<String, Object> getInfo(String query, Map<String, Object> params, String key) {
		Map<String, Object> resultMap = new HashMap<>();

		try (Session session = driver.session()) {
			Result result = session.run(query, params);

			if (result.hasNext()) {
				Record record = result.next();
				Map<String, Object> deviceInfo = record.get(key).asMap();
				resultMap = convertDevice(deviceInfo,false);
			} else {
				log.warn("No results found for query: {}", query);
			}
		} catch (Exception e) {
			log.error("Query execution failed. Reason: {}", e.getMessage(), e);
		}

		return resultMap;
	}

	public ResponseStatus getConnections(String sourceDevName, String targetDevName) {
		log.info("=>EthernetOrderService:getConnections: START");
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to find connections!.");

		String devName = sourceDevName;
		String devQuery = "MATCH(dev:allDevices{TID:$devName}) RETURN dev";

		try (Session session = driver.session();) {

			Transaction tx = session.beginTransaction();
			List<Record> devRecords = tx.run(devQuery, Map.of("devName", devName)).list();

			if (devRecords == null || devRecords.isEmpty()) {
				response.setMessage("Device '" + devName + "' not found!.");
				return response;
			}

			Record devRec = devRecords.get(0);
			Map<String, Object> srcDev = new HashMap<String, Object>();
			srcDev = devRec.get("dev").asMap();

			devName = targetDevName;
			devRecords = tx.run(devQuery, Map.of("devName", devName)).list();

			if (devRecords == null || devRecords.isEmpty()) {
				response.setMessage("Device '" + devName + "' not found!.");
				return response;
			}

			devRec = devRecords.get(0);
			Map<String, Object> trgtDev = new HashMap<String, Object>();
			trgtDev = devRec.get("dev").asMap();

			String srcRole = (String) srcDev.get("deviceRoles");
			String srcMenId = (String) srcDev.get("topologyName");

			String tgtRole = (String) trgtDev.get("deviceRoles");
			String tgtMenId = (String) trgtDev.get("topologyName");

			List<Map<String, Object>> connList = new ArrayList<Map<String, Object>>();

			if ((StringUtils.contains(srcRole, "NPE") || StringUtils.contains(srcRole, "MER"))
					&& (StringUtils.contains(tgtRole, "NPE") || StringUtils.contains(tgtRole, "MER"))
					&& (StringUtils.equalsIgnoreCase(srcMenId, tgtMenId))) {

				connList.add(Map.of("MPLS", srcMenId));
			}

			String connQuery = "MATCH(nni:NNIConnection) WHERE (nni.loca = '" + sourceDevName + "' AND nni.locz = '"
					+ targetDevName + "')" + " OR (nni.locz = '" + sourceDevName + "' AND nni.loca = '" + targetDevName
					+ "') RETURN nni.cktid as circuitName";

			List<Record> connRecords = tx.run(connQuery).list();

			for (Record conRec : connRecords) {
				connList.add(Map.of("NNI", conRec.get("circuitName").asString()));
			}

			if (connList != null && !connList.isEmpty()) {
				response.setCode(200);
				response.setMessage("Found connections");
				response.setData(Map.of("connections", connList));
			}

		} catch (Exception ex) {
			log.error("Get connections - Caught Exception: Reason: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to get connections.");
		}

		log.info("<=EthernetOrderService:getConnections: ENDS");
		return response;
	}

	private String generateUsilCAC(boolean isUNI) {
		String cac = "UCAA8AA1";
		String cacQuery = "";
		String cacStarter = "UC";
		if (isUNI)
			cacStarter = "US";

		if (isUNI) {
			cacQuery = "MATCH (ckt:UNIConnection) WHERE ckt.cac STARTS WITH '" + cacStarter + "'"
						+ " RETURN ckt.cac AS cac ORDER BY cac DESC limit 1";
		} else {
			cacQuery = "MATCH (ckt:NNIConnection) WHERE ckt.cac STARTS WITH '" + cacStarter + "'"
						+ " RETURN ckt.cac AS cac ORDER BY cac DESC limit 1";
		}

		try (Session session = driver.session()) {
			Result result = session.run(cacQuery);
			String cacStr = "";
			if (result.hasNext()) {
				Record record = result.next();
				cacStr = record.get("cac").asString();
			}
			if (StringUtils.isNotBlank(cacStr)) {
				char[] cacArray = cacStr.toCharArray();

				for (int i = cacArray.length - 1; i > 1; i--) {
					if (i == 4)
						continue;

					char c = cacArray[i];
					if (i == 7) {
						int num = Character.getNumericValue(c);
						if (num < 9) {
							num++;
							cacArray[i] = Character.forDigit(num, 10);
							break;
						} else {
							num = 0;
							cacArray[i] = Character.forDigit(num, 10);
						}
					} else {
						if (c == 'Z') {
							cacArray[i] = 'A';
						} else {
							c++;
							cacArray[i] = c;
							break;
						}
					}
				}

				cac = new String(cacArray);
			} else {
				if (isUNI) {
					cac = "USAA8AA1";
				} else {
					cac = "UCAA8AA1";
				}
			}
		} catch (Exception ex) {
			log.error("Caught Exception while generating CAC");
		}
		log.info("Generated USIL CAC: '{}'", cac);
		return cac;
	}

	public ResponseStatus findRouteV1(String sourceDevName, String targetDevName, Integer numberOfPaths) {
		log.info("=>EthernetOrderService:findRouteV1: START");
		log.info("=>EthernetOrderService:sourceDevName: targetDevName "+sourceDevName+".."+targetDevName);
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to find route.");

		try {
			Map<String, Object> responseData = new LinkedHashMap<>();
			responseData.put("pathFrom", sourceDevName.toUpperCase());
			responseData.put("pathTo", targetDevName.toUpperCase());

			try (Session session = driver.session()) {
				List<Map<String, Object>> altRoutes = getAlternateRoutesV1(session, sourceDevName, targetDevName, numberOfPaths);
				List<Map<String, Object>> routesList = new ArrayList<>();

				for (Map<String, Object> altRoute : altRoutes) {
					Map<String, Object> routeMap = new HashMap<>();
					routeMap.put("route", altRoute.getOrDefault("route", new ArrayList<>()));
					routesList.add(routeMap);
				}

				responseData.put("routesList", routesList);
			} catch (Exception e) {
				log.error("Failed to fetch alternate routes: {}", e.getMessage(), e);
				responseData.put("routesList", new ArrayList<>()); // fallback empty list
			}

			response.setCode(200);
			response.setMessage("Successfully found alternate routes.");
			response.setData(responseData);

		} catch (Exception ex) {
			log.error("findRouteV1 - Exception: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Exception occurred during route finding.");
		}

		log.info("<=EthernetOrderService:findRouteV1: END");
		return response;
	}

	public List<Map<String, Object>> getAlternateRoutesV1(Session session, String firstDevice, String lastDevice,
			Integer numberOfPaths) {
		List<Map<String, Object>> alternateRoutes = new ArrayList<>();
		log.info("Get alternate routes");

		if (numberOfPaths == null) {
			numberOfPaths = 5;
		}

		String shortestPathQuery = "MATCH path = SHORTEST " + numberOfPaths + "(loc:DeviceEntity{TID: $firstDevice})-"
				+ "[:MPLS_CONNECTION|NETWORK_CONNECTION]-+(rem:DeviceEntity{TID: $lastDevice}) RETURN path LIMIT "
				+ numberOfPaths;

		Map<String, Object> pathParams = Map.of("firstDevice", firstDevice, "lastDevice", lastDevice);
		Result pathResult = session.run(shortestPathQuery, pathParams);

		while (pathResult.hasNext()) {
			int connectionCount = 1;
			List<Map<String, Object>> altRoute = new ArrayList<>();
			boolean isfaultyRoute = false;

			Record record = pathResult.next();
			org.neo4j.driver.types.Path path = record.get("path").asPath();

			List<Relationship> relationships = new ArrayList<>();
			path.relationships().forEach(relationships::add);

			List<Node> nodes = new ArrayList<>();
			path.nodes().forEach(nodes::add);

			boolean isMPLSFound = false;
			for (int i = 0; i < relationships.size(); i++) {
				Relationship relationship = relationships.get(i);
				Node startNode = nodes.get(i);
				Node endNode = nodes.get(i + 1);

				String aDeviceName = startNode.get("TID").asString();
				String zDeviceName = endNode.get("TID").asString();

				Map<String, Object> aEndDevice = fetchDeviceInfo(session, aDeviceName);
				Map<String, Object> zEndDevice = fetchDeviceInfo(session, zDeviceName);

				if ((aEndDevice == null || aEndDevice.isEmpty()) || (zEndDevice == null || zEndDevice.isEmpty())) {
					log.info("Device is not available.");
					isfaultyRoute = true;
					break;
				}

				Map<String, Object> aEndInfo = Map.of("device", aEndDevice);
				Map<String, Object> zEndInfo = Map.of("device", zEndDevice);
				String relationshipType = relationship.type();

				if ("MPLS_CONNECTION".equals(relationshipType)) {
					if (isMPLSFound) {
						log.info("Route dont allow to go through multiple MER devices.");
						isfaultyRoute = true;
						break;
					}
					Map<String, Object> props = relationship.asMap();
					String menId = (String) props.getOrDefault("menId", "");

					Map<String, Object> connection = new LinkedHashMap<>();
					connection.put("connection", connectionCount++);
					connection.put("connectionType", "MPLS");
					connection.put("menId", menId);
					connection.put("aEndInfo", aEndInfo);
					connection.put("zEndInfo", zEndInfo);

					altRoute.add(connection);
					isMPLSFound = true;

				} else if ("NETWORK_CONNECTION".equals(relationshipType)) {
					Map<String, Object> queryParams = Map.of("aDeviceName", aDeviceName, "zDeviceName", zDeviceName);

					String nniQuery = "MATCH (nni:NNIConnection) WHERE nni.deletedTimeStamp is null "
							+ "AND ((nni.loca = $zDeviceName AND nni.locz = $aDeviceName) "
							+ "OR (nni.locz = $zDeviceName AND nni.loca = $aDeviceName)) " + "WITH nni "
							+ "MATCH (nni)-[C1:CONNECTED_TO]->(aport:EquipmentPort { location: $aDeviceName }) "
							+ "WHERE aport.deletedTimeStamp is null AND C1.deletedTimeStamp is null "
							+ "MATCH (nni)-[C2:CONNECTED_TO]->(zport:EquipmentPort { location: $zDeviceName }) "
							+ "WHERE zport.deletedTimeStamp is null AND C2.deletedTimeStamp is null "
							+ "RETURN nni.cktid AS nmiName, nni.aliasCktId AS aliasCktId, aport AS aport, zport AS zport LIMIT 1";

					Result nniResult = session.run(nniQuery, queryParams);

					if (nniResult.hasNext()) {
						Record nniRecord = nniResult.next();
						String nmiName = nniRecord.get("nmiName").asString();
						String aliasCktId = nniRecord.get("aliasCktId").asString();

						Map<String, Object> aportNodeProps = new HashMap<>(convertedPort(nniRecord.get("aport").asNode().asMap()));
						Map<String, Object> zportNodeProps = new HashMap<>(convertedPort(nniRecord.get("zport").asNode().asMap()));

						if ((aportNodeProps == null || aportNodeProps.isEmpty())
								|| (zportNodeProps == null || zportNodeProps.isEmpty())) {
							log.info("Equipment Port is not available.");
							isfaultyRoute = true;
							break;
						}

						Map<String, Object> aEndInfo1 = new LinkedHashMap<>();
						aEndInfo1.put("device", aEndDevice);
						{
							String portSpeed = (String) aportNodeProps.get("bw");
							portSpeed = ApplicationUtils.bandwidthConvertor(portSpeed,true,true);
							aportNodeProps.put("portSpeed", portSpeed);
						}
						aEndInfo1.put("port", aportNodeProps);

						Map<String, Object> zEndInfo1 = new LinkedHashMap<>();
						zEndInfo1.put("device", zEndDevice);
						{
							String zportSpeed = (String) zportNodeProps.get("bw");
							zportSpeed = ApplicationUtils.bandwidthConvertor(zportSpeed,true,true);
							zportNodeProps.put("portSpeed", zportSpeed);
						}
						zEndInfo1.put("port", zportNodeProps);

						Map<String, Object> connection = new LinkedHashMap<>();
						connection.put("connection", connectionCount++);
						connection.put("connectionType", "NNI");
						connection.put("nniName", nmiName);
						connection.put("aEndInfo", aEndInfo1);
						connection.put("aliasCktId", aliasCktId);
						connection.put("zEndInfo", zEndInfo1);

						altRoute.add(connection);
					} else {
						log.info("Network Connection details failed to fetch.");
						isfaultyRoute = true;
						break;
					}
				}
			}

			if (!isfaultyRoute && !altRoute.isEmpty()) {
				Map<String, Object> routeWrapper = new HashMap<>();
				routeWrapper.put("route", altRoute);
				alternateRoutes.add(routeWrapper);
			}
		}

		return alternateRoutes;
	}

	public ResponseStatus hardDisconnectUNI(Map<String, Object> uniInfo) {
		log.info("=> EthernetOrderService:hardDisconnectUNI: START");
		ResponseStatus response = new ResponseStatus();
		StringBuilder errorMessages = new StringBuilder();

		String cktid = (String) uniInfo.get("circuitName");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		boolean usilDeleteSuccess = false;
		boolean e2DeleteSuccess = false;

		try (Session session = driver.session()) {
			Map<String, Object> params = new HashMap<>();
			params.put("cktid", cktid);
			params.put("aliasCktId", aliasCktId);

			// 1. Check UNI existence
			String checkUNI = "MATCH (uni:UNIConnection) WHERE uni.deletedTimeStamp IS NULL "
					+ "AND (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) RETURN uni";
			if (session.run(checkUNI, params).list().isEmpty()) {
				response.setCode(404);
				response.setMessage("UNI not found for circuitId: " + cktid);
				return response;
			}

			// 2. Check if UNI is in service
			String checkInService = "MATCH (uni:UNIConnection) WHERE (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) "
					+ "OPTIONAL MATCH (uni)-[:AEND|ZEND]-(evc:EVCConnection) WHERE evc.deletedTimeStamp IS NULL and evc.deltaStatus is null "
					+ "WITH evc WHERE evc IS NOT NULL RETURN evc LIMIT 1";
			if (!session.run(checkInService, params).list().isEmpty()) {
				response.setCode(400);
				response.setMessage("UNI is in service and cannot be hard deleted.");
				return response;
			}

			// 3. Attempt USIL deletion
			try (Transaction tx = session.beginTransaction()) {
				String deleteQuery = "MATCH (uni:UNIConnection) WHERE (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) "
						+ "OPTIONAL MATCH (uni)-[r1:CONNECTED_TO]->(port:EquipmentPort) "
						+ "OPTIONAL MATCH (port)-[r2:XCONNECT]->() "
						+ "OPTIONAL MATCH (uni)-[r4]-(evc: EVCConnection) "
						+ "OPTIONAL MATCH (uni)-[r3:CONNECTED_TO]->(route:ROUTE) "
						+ "SET port.status = 'Available' "
						+ "DELETE r1, r2, r3, r4, uni";

				tx.run(deleteQuery, params);
				String updateUniOrderQuery = "MATCH(uni:UNIOrder) WHERE uni.aliasCktId = $aliasCktId SET uni.buildStatus = 'inProgress', uni.updatedOn = datetime()";
				tx.run(updateUniOrderQuery, params);
				tx.commit();
				usilDeleteSuccess = true;
				log.info("UNI '{}' deleted successfully in USIL.", cktid);
			} catch (Exception ex) {
				log.error("Error deleting UNI from USIL: {}", ex.getMessage(), ex);
				errorMessages.append("UNI deletion failed in USIL. ");
			}

//			// 4. Attempt E2 deletion
//			try {
//				ResponseStatus e2Response = deleteUNIInE2(cktid);
//				int e2Code = e2Response.getCode();
//
//				if (e2Code == 200 || e2Code == 404) {
//				    if (e2Code == 200) {
//				        log.info("UNI '{}' deleted successfully in E2 system.", cktid);
//				    } else {
//				        log.warn("UNI '{}' not found in E2. Proceeding with deletion in USIL.", cktid);
//				    }
//				    e2DeleteSuccess = true;
//				} else {
//				    log.error("E2 deletion failed for UNI '{}': {}", cktid, e2Response.getMessage());
//				    errorMessages.append("UNI deletion failed in E2: ").append(e2Response.getMessage()).append(". ");
//				}
//			} catch (Exception ex) {
//				log.error("Exception during E2 UNI deletion: {}", ex.getMessage(), ex);
//				errorMessages.append("Exception during UNI deletion in E2: ").append(ex.getMessage()).append(". ");
//			}

			// 5. Attempt AllCircuits deletion
			if (usilDeleteSuccess) {
				try (Transaction tx2 = session.beginTransaction()) {
					String deleteAllCircuitsQuery = "MATCH (ac:allCircuits) WHERE ac.circuitName = $cktid OR ac.aliasCktId = $aliasCktId DELETE ac";
					tx2.run(deleteAllCircuitsQuery, params);
					tx2.commit();
					log.info("AllCircuits node deleted for cktid: {}", cktid);
				} catch (Exception ex) {
					log.warn("Failed to delete AllCircuits node for '{}': {}", cktid, ex.getMessage());
					errorMessages.append("AllCircuits deletion failed in USIL. ");
				}
			}

			// Final response
			if (usilDeleteSuccess && e2DeleteSuccess && errorMessages.length() == 0) {
			    response.setCode(200);
			    response.setMessage("UNI and associated entities deleted successfully.");
			} else if (usilDeleteSuccess && !e2DeleteSuccess) {
			    response.setCode(207);
			    response.setMessage("UNI deleted successfully in USIL. Warnings: " + errorMessages.toString().trim());
			} else if (!usilDeleteSuccess) {
			    response.setCode(500);
			    response.setMessage("UNI deletion failed in USIL. " + errorMessages.toString().trim());
			}

		} catch (Exception ex) {
			log.error("Exception in hardDisconnectUNI: ", ex);
			response.setCode(500);
			response.setMessage("Internal error during hard delete.");
		}

		return response;
	}
	
	public ResponseStatus hardDisconnectUNIV2(Map<String, Object> uniInfo) {
		log.info("=> EthernetOrderService:hardDisconnectUNI: START");
		ResponseStatus response = new ResponseStatus();
		StringBuilder errorMessages = new StringBuilder();

		String cktid = (String) uniInfo.get("circuitName");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		boolean usilDeleteSuccess = false;
		boolean e2DeleteSuccess = false;

		try (Session session = driver.session()) {
			Map<String, Object> params = new HashMap<>();
			params.put("cktid", cktid);
			params.put("aliasCktId", aliasCktId);

			// 1. Check UNI existence
			String checkUNI = "MATCH (uni:UNIConnection) WHERE uni.deletedTimeStamp IS NULL "
					+ "AND (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) RETURN uni";
			if (session.run(checkUNI, params).list().isEmpty()) {
				response.setCode(404);
				response.setMessage("UNI not found for circuitId: " + cktid);
				return response;
			}

			// 2. Check if UNI is in service
			String checkInService = "MATCH (uni:UNIConnection) WHERE (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) "
					+ "OPTIONAL MATCH (uni)-[:AEND|ZEND]-(evc:EVCConnection) WHERE evc.deletedTimeStamp IS NULL and evc.deltaStatus is null "
					+ "WITH evc WHERE evc IS NOT NULL RETURN evc LIMIT 1";
			if (!session.run(checkInService, params).list().isEmpty()) {
				response.setCode(400);
				response.setMessage("UNI is in service and cannot be hard deleted.");
				return response;
			}

			// 3. Attempt USIL deletion
			try (Transaction tx = session.beginTransaction()) {
//				String deleteQuery = "MATCH (uni:UNIConnection) WHERE (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) "
//						+ "OPTIONAL MATCH (uni)-[r1:CONNECTED_TO]->(port:EquipmentPort) "
//						+ "OPTIONAL MATCH (port)-[r2:XCONNECT]->() "
//						+ "OPTIONAL MATCH (uni)-[r4]-(evc: EVCConnection) "
//						+ "OPTIONAL MATCH (uni)-[r3:CONNECTED_TO]->(route:ROUTE) "
//						+ "SET port.status = 'Available' "
//						+ "DELETE r1, r2, r3, r4, uni";
				
				
				String disconnectQuery = 
				        "MATCH (uni:UNIConnection) " +
				        "WHERE (uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId) " +

				        "OPTIONAL MATCH (uni)-[r1:CONNECTED_TO]->(port:EquipmentPort) " +
				        "OPTIONAL MATCH (port)-[r2:XCONNECT]->() " +
				        "OPTIONAL MATCH (uni)-[r4]-(evc:EVCConnection) " +
				        "OPTIONAL MATCH (uni)-[r3:CONNECTED_TO]->(route:ROUTE) " +

				        "SET port.status = 'Available', " +
				        "    uni.status = 'DISCONNECTED', " +
				        "    uni.disconnectedAt = coalesce(uni.disconnectedAt, datetime()), " +
				        "    uni.deletedTimeStamp = coalesce(uni.deletedTimeStamp, datetime()) " +

				        "DELETE r1, r2, r3, r4";


				tx.run(disconnectQuery, params);
				tx.commit();
				usilDeleteSuccess = true;
				log.info("UNI '{}' deleted successfully in USIL.", cktid);
			} catch (Exception ex) {
				log.error("Error deleting UNI from USIL: {}", ex.getMessage(), ex);
				errorMessages.append("UNI deletion failed in USIL. ");
			}

//			// 4. Attempt E2 deletion
//			try {
//				ResponseStatus e2Response = deleteUNIInE2(cktid);
//				int e2Code = e2Response.getCode();
//
//				if (e2Code == 200 || e2Code == 404) {
//				    if (e2Code == 200) {
//				        log.info("UNI '{}' deleted successfully in E2 system.", cktid);
//				    } else {
//				        log.warn("UNI '{}' not found in E2. Proceeding with deletion in USIL.", cktid);
//				    }
//				    e2DeleteSuccess = true;
//				} else {
//				    log.error("E2 deletion failed for UNI '{}': {}", cktid, e2Response.getMessage());
//				    errorMessages.append("UNI deletion failed in E2: ").append(e2Response.getMessage()).append(". ");
//				}
//			} catch (Exception ex) {
//				log.error("Exception during E2 UNI deletion: {}", ex.getMessage(), ex);
//				errorMessages.append("Exception during UNI deletion in E2: ").append(ex.getMessage()).append(". ");
//			}

			// 5. Attempt AllCircuits deletion
			if (usilDeleteSuccess) {
				try (Transaction tx2 = session.beginTransaction()) {
					String deleteAllCircuitsQuery = "MATCH (ac:allCircuits) " +
						    "WHERE ac.circuitName = $cktid OR ac.aliasCktId = $aliasCktId " +
						    "SET ac.status = 'DELETED', " +
						    "    ac.deletedAt = coalesce(ac.deletedAt, datetime()), " +
						    "    ac.updatedAt = datetime()";
					tx2.run(deleteAllCircuitsQuery, params);
					tx2.commit();
					log.info("AllCircuits node deleted for cktid: {}", cktid);
				} catch (Exception ex) {
					log.warn("Failed to delete AllCircuits node for '{}': {}", cktid, ex.getMessage());
					errorMessages.append("AllCircuits deletion failed in USIL. ");
				}
			}

			// Final response
			if (usilDeleteSuccess && e2DeleteSuccess && errorMessages.length() == 0) {
			    response.setCode(200);
			    response.setMessage("UNI and associated entities deleted successfully.");
			} else if (usilDeleteSuccess && !e2DeleteSuccess) {
			    response.setCode(207);
			    response.setMessage("UNI deleted successfully in USIL. Warnings: " + errorMessages.toString().trim());
			} else if (!usilDeleteSuccess) {
			    response.setCode(500);
			    response.setMessage("UNI deletion failed in USIL. " + errorMessages.toString().trim());
			}

		} catch (Exception ex) {
			log.error("Exception in hardDisconnectUNI: ", ex);
			response.setCode(500);
			response.setMessage("Internal error during hard delete.");
		}

		return response;
	}

	public String getNodeIdFromDevice(String deviceName, String CLLI, String relayrck ) {
		String query = "MATCH (n:allDevices) WHERE n.name = $deviceName AND n.deletedTimeStamp IS NULL AND "
				+ "n.CLLI = $CLLI AND n.relayrck = $relayrck  "
				+ "RETURN n.node_id AS nodeId LIMIT 1";

		Map<String, Object> params = new HashMap<>();
		params.put("deviceName", deviceName);
		params.put("CLLI", CLLI);
		params.put("relayrck", relayrck);

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();
			try {
				Result result = tx.run(query, params);
				if (result.hasNext()) {
					Record record = result.next();
					return record.get("nodeId").asString(null); // returns null if not found
				}
			} catch (Exception e) {
				log.error("Query failed in getNodeIdFromDevice for '{}': {}", deviceName, e.getMessage(), e);
			} finally {
				tx.commit();
			}
		} catch (Exception ex) {
			log.error("Error connecting to Neo4j in getNodeIdFromDevice: {}", ex.getMessage(), ex);
		}

		return null;
	}

	public String extractNniName(List<Map<String, Object>> routeList) {
		if (routeList == null || routeList.isEmpty()) {
			return null;
		}

		for (Map<String, Object> route : routeList) {
			if (route.containsKey("nniName")) {
				Object nniNameObj = route.get("nniName");
				if (nniNameObj instanceof String && StringUtils.isNotBlank((String) nniNameObj)) {
					return (String) nniNameObj;
				}
			}
		}

		return null;
	}

	public boolean updateDeviceNodesWithNPEInfo(String customerDeviceName, String npeDeviceName, String npeNodeId,
			List<String> npeRoutes) {
		try (Session session = driver.session()) {
			session.writeTransaction(tx -> {
				Map<String, Object> params = new HashMap<>();
				params.put("customerDeviceName", customerDeviceName);
				params.put("npeDeviceName", npeDeviceName);
				params.put("npeNodeId", npeNodeId);
				params.put("npeRoutes", npeRoutes);

				// Update Equippment node
				String updateEquipmentQuery = """
						MATCH (n:Equipment)
						WHERE n.TID = $customerDeviceName
						SET n.npeName = $npeDeviceName,
						n.npe_Node_Id = $npeNodeId,
						n.npeRoutes = $npeRoutes
						""";
				tx.run(updateEquipmentQuery, params);

				// Update allDevices node
				String updateAllDevicesQuery = """
						MATCH (a:allDevices)
						WHERE a.name = $customerDeviceName
						SET a.npeName = $npeDeviceName,
						a.npe_Node_Id = $npeNodeId,
						a.npeRoutes = $npeRoutes
						""";
				tx.run(updateAllDevicesQuery, params);

				return null;
			});

			log.info("Updated Equippment and allDevices for device: {}", customerDeviceName);
			return true;
		} catch (Exception e) {
			log.error("Failed to update Neo4j device nodes: {}", e.getMessage(), e);
			return false;
		}
	}

	public ResponseStatus hardDisconnectEVCV2(Map<String, Object> evcInfo) {
		log.info("=> EthernetOrderService:hardDisconnectEVC: START");

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String cktid = (String) evcInfo.get("circuitName");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
		List<String> uniCircuitIds = (List<String>) evcInfo.get("uniCircuitIds");

		boolean routeDeleted = false;
		boolean evcDeleted = false;
		boolean uniProcessed = false;
		boolean isDeletedInE2 = false;
		StringBuilder errorMessages = new StringBuilder();

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			// Step 1: Check if EVC exists
			String existenceCheckQuery = "MATCH (evc:EVCConnection) " + "WHERE evc.deletedTimeStamp IS NULL AND "
					+ "(evc.serviceName = $cktid OR evc.aliasCktId = $aliasCktId) " + "RETURN evc";
			Map<String, Object> params = Map.of("cktid", cktid, "aliasCktId", aliasCktId);
			List<Record> existResult = tx.run(existenceCheckQuery, params).list();
			
			System.out.println("cktid=[" + cktid + "]");
			System.out.println("cktid length=" + cktid.length());


			if (existResult.isEmpty()) {
				response.setCode(404);
				response.setMessage("EVC not found with serviceName: " + cktid);
				tx.commit();
				return response;
			}

			// Step 2: Check if route has other EVCs
//			String routeCheckQuery = "MATCH (evc:EVCConnection {serviceName: $cktid})<-[:RIDES_ON]-(route:ROUTE) "
//					+ "WITH evc, route " + "MATCH (other:EVCConnection)<-[:RIDES_ON]-(route) "
//					+ "RETURN COUNT(other) AS evcCount";
//			long evcCount = tx.run(routeCheckQuery, params).single().get("evcCount").asLong();
//
//			if (evcCount == 1) {
//				// No other EVCs – delete both EVC and route
//				log.info("No other EVCs on route. Deleting both EVC and route for '{}'", cktid);
//				String deleteEvcAndRoute = "MATCH (evc:EVCConnection {serviceName: $cktid})<-[:RIDES_ON]-(route:ROUTE) "
//						+ "DETACH DELETE evc, route";
//				tx.run(deleteEvcAndRoute, params);
//				routeDeleted = true;
//				evcDeleted = true;
//			} else {
//				// Other EVCs exist – delete only EVC
//				log.info("Other EVCs exist. Deleting only EVC '{}'", cktid);
//				String deleteOnlyEvc = "MATCH (evc:EVCConnection {serviceName: $cktid}) DETACH DELETE evc";
//				tx.run(deleteOnlyEvc, params);
//				evcDeleted = true;
//			}
//			tx.commit();
//			
			
			String softDisconnectQuery =
				    "MATCH (evc:EVCConnection) " +
				    "WHERE (evc.serviceName = $cktid OR evc.aliasCktId = $aliasCktId) " +
				    "OPTIONAL MATCH (evc)<-[r1:RIDES_ON]-(route:ROUTE) " +
				    "SET evc.status = 'DISCONNECTED', " +
				    "    evc.disconnectedAt = coalesce(evc.disconnectedAt, datetime()), " +
				    "    evc.deletedTimeStamp = coalesce(evc.deletedTimeStamp, datetime()) " +
				    "DELETE r1";

				tx.run(softDisconnectQuery, params);
				evcDeleted = true;
				tx.commit();

//			// Step 2.5: Call E2
//			try {
//				ResponseStatus e2DeleteStatus = deleteEVCInE2(cktid);
//				int e2Code = e2DeleteStatus.getCode();
//				if (e2Code == 200 || e2Code == 404) {
//					if (e2Code == 200) {
//						log.info("EVC '{}' deleted successfully in E2 system.", cktid);
//					} else {
//						log.warn("EVC '{}' not found in E2. Proceeding with deletion in USIL.", cktid);
//					}
//					isDeletedInE2 = true;
//				} else {
//					log.error("E2 deletion failed for circuit '{}': {}", cktid, e2DeleteStatus.getMessage());
//					errorMessages.append("EVC deletion failed in E2: ").append(e2DeleteStatus.getMessage())
//							.append(". ");
//				}
//			} catch (Exception ex) {
//				 String errorMsg = ex.getMessage();
//				    if (errorMsg != null && errorMsg.contains("404") && errorMsg.toLowerCase().contains("not found")) {
//				        log.warn("EVC '{}' not found in E2 (404). Proceeding as success.", cktid);
//				        isDeletedInE2 = true;
//				    } else {
//				        log.error("Exception while deleting EVC from E2: {}", errorMsg, ex);
//				        errorMessages.append("Exception during EVC deletion in E2: ").append(errorMsg).append(". ");
//				    }
//			}

			// Step 3: Delete allServices
			try (Transaction tx2 = session.beginTransaction()) {
			//	String deleteAllServicesQuery = "MATCH (ac:allServices) WHERE ac.name = $cktid OR ac.aliasCktId = $aliasCktId DELETE ac";
				String updateAllServicesQuery =
					    "MATCH (ac:allServices) " +
					    "WHERE ac.name = $cktid OR ac.aliasCktId = $aliasCktId " +
					    "SET ac.status = 'DISCONNECTED', " +
					    "    ac.disconnectedAt = datetime()";

					tx2.run(updateAllServicesQuery, params);
				
				tx2.commit();
				log.info("allServices node deleted for name: {}", cktid);
			} catch (Exception ex) {
				log.warn("Failed to delete allServices node for name: {}. Error: {}", cktid, ex.getMessage());
			}

			// Step 4: If uniCircuitIds are provided, delete each UNI using
			// hardDisconnectUNI()
			if (uniCircuitIds != null && !uniCircuitIds.isEmpty()) {
				for (String uniCktId : uniCircuitIds) {
					log.info("Calling hardDisconnectUNI for UNI circuit '{}'", uniCktId);
					Map<String, Object> uniInfo = new HashMap<>();
					uniInfo.put("circuitName", uniCktId);

					try {
						ResponseStatus uniResponse = hardDisconnectUNIV2(uniInfo);
						if (uniResponse.getCode() != 200) {
							log.warn("UNI '{}' could not be deleted. Reason: {}", uniCktId, uniResponse.getMessage());
							errorMessages.append("UNI '").append(uniCktId).append("' deletion failed: ")
									.append(uniResponse.getMessage()).append(". ");
						} else {
							log.info("UNI '{}' deleted successfully.", uniCktId);
							uniProcessed = true;
						}
					} catch (Exception ex) {
						log.error("Exception while deleting UNI '{}': {}", uniCktId, ex.getMessage(), ex);
						errorMessages.append("Exception during UNI deletion for '").append(uniCktId).append("': ")
								.append(ex.getMessage()).append(". ");
					}
				}
			} else {
				log.info("No UNI circuits provided in request. Skipping UNI disconnect.");
			}
			
			StringBuilder msg = new StringBuilder();
//			if (evcDeleted && routeDeleted && isDeletedInE2) {
//				msg.append("EVC and its associated route deleted successfully.");
//			} else if (evcDeleted && isDeletedInE2) {
//				msg.append("EVC deleted successfully and  Route retained.");
//			}
//			if (uniProcessed) {
//				msg.append(" Hard disconnect completed for associated UNIs.");
//			}
//			if (errorMessages.length() > 0) {
//				msg.append(" Warnings: ").append(errorMessages.toString().trim());
//				response.setCode(207);
//			} else {
//				response.setCode(200);
//			}
//
//			response.setMessage(msg.toString());
			
			
			if (evcDeleted) {
			    msg.append("EVC soft-disconnected successfully.");
			}

			if (uniProcessed) {
			    msg.append(" Associated UNIs soft-disconnected.");
			}

			if (errorMessages.length() > 0) {
			    msg.append(" Warnings: ").append(errorMessages.toString().trim());
			    response.setCode(207);
			} else {
			    response.setCode(200);
			}

			response.setMessage(msg.toString());

		} catch (Exception ex) {
			log.error("Exception in hardDisconnectEVC: ", ex);
			response.setCode(500);
			response.setMessage("Internal error during hard disconnect. " + ex.getMessage());
		}

		return response;
	}
	
	public ResponseStatus hardDisconnectEVC(Map<String, Object> evcInfo) {
		log.info("=> EthernetOrderService:hardDisconnectEVC: START");

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String cktid = (String) evcInfo.get("circuitName");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
		List<String> uniCircuitIds = (List<String>) evcInfo.get("uniCircuitIds");

		boolean routeDeleted = false;
		boolean evcDeleted = false;
		boolean uniProcessed = false;
		boolean isDeletedInE2 = false;
		StringBuilder errorMessages = new StringBuilder();

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			// Step 1: Check if EVC exists
			String existenceCheckQuery = "MATCH (evc:EVCConnection) " + "WHERE evc.deletedTimeStamp IS NULL AND "
					+ "(evc.serviceName = $cktid OR evc.aliasCktId = $aliasCktId) " + "RETURN evc";
			Map<String, Object> params = Map.of("cktid", cktid, "aliasCktId", aliasCktId);
			List<Record> existResult = tx.run(existenceCheckQuery, params).list();

			if (existResult.isEmpty()) {
				response.setCode(404);
				response.setMessage("EVC not found with serviceName: " + cktid);
				tx.commit();
				return response;
			}

			// Step 2: Check if route has other EVCs
			String routeCheckQuery = "MATCH (evc:EVCConnection {serviceName: $cktid})<-[:RIDES_ON]-(route:ROUTE) "
					+ "WITH evc, route " + "MATCH (other:EVCConnection)<-[:RIDES_ON]-(route) "
					+ "RETURN COUNT(other) AS evcCount";
			long evcCount = tx.run(routeCheckQuery, params).single().get("evcCount").asLong();

			if (evcCount == 1) {
				// No other EVCs – delete both EVC and route
				log.info("No other EVCs on route. Deleting both EVC and route for '{}'", cktid);
				String deleteEvcAndRoute = "MATCH (evc:EVCConnection {serviceName: $cktid})<-[:RIDES_ON]-(route:ROUTE) "
						+ "DETACH DELETE evc, route";
				tx.run(deleteEvcAndRoute, params);
				routeDeleted = true;
				evcDeleted = true;
			} else {
				// Other EVCs exist – delete only EVC
				log.info("Other EVCs exist. Deleting only EVC '{}'", cktid);
				String deleteOnlyEvc = "MATCH (evc:EVCConnection {serviceName: $cktid}) DETACH DELETE evc";
				tx.run(deleteOnlyEvc, params);
				evcDeleted = true;
			}
			tx.commit();

//			// Step 2.5: Call E2
//			try {
//				ResponseStatus e2DeleteStatus = deleteEVCInE2(cktid);
//				int e2Code = e2DeleteStatus.getCode();
//				if (e2Code == 200 || e2Code == 404) {
//					if (e2Code == 200) {
//						log.info("EVC '{}' deleted successfully in E2 system.", cktid);
//					} else {
//						log.warn("EVC '{}' not found in E2. Proceeding with deletion in USIL.", cktid);
//					}
//					isDeletedInE2 = true;
//				} else {
//					log.error("E2 deletion failed for circuit '{}': {}", cktid, e2DeleteStatus.getMessage());
//					errorMessages.append("EVC deletion failed in E2: ").append(e2DeleteStatus.getMessage())
//							.append(". ");
//				}
//			} catch (Exception ex) {
//				 String errorMsg = ex.getMessage();
//				    if (errorMsg != null && errorMsg.contains("404") && errorMsg.toLowerCase().contains("not found")) {
//				        log.warn("EVC '{}' not found in E2 (404). Proceeding as success.", cktid);
//				        isDeletedInE2 = true;
//				    } else {
//				        log.error("Exception while deleting EVC from E2: {}", errorMsg, ex);
//				        errorMessages.append("Exception during EVC deletion in E2: ").append(errorMsg).append(". ");
//				    }
//			}

			// Step 3: Delete allServices
			try (Transaction tx2 = session.beginTransaction()) {
				String deleteAllServicesQuery = "MATCH (ac:allServices) WHERE ac.name = $cktid OR ac.aliasCktId = $aliasCktId DELETE ac";
				tx2.run(deleteAllServicesQuery, params);
				tx2.commit();
				log.info("allServices node deleted for name: {}", cktid);
			} catch (Exception ex) {
				log.warn("Failed to delete allServices node for name: {}. Error: {}", cktid, ex.getMessage());
			}

			// Step 4: If uniCircuitIds are provided, delete each UNI using
			// hardDisconnectUNI()
			if (uniCircuitIds != null && !uniCircuitIds.isEmpty()) {
				for (String uniCktId : uniCircuitIds) {
					log.info("Calling hardDisconnectUNI for UNI circuit '{}'", uniCktId);
					Map<String, Object> uniInfo = new HashMap<>();
					uniInfo.put("circuitName", uniCktId);

					try {
						ResponseStatus uniResponse = hardDisconnectUNI(uniInfo);
						if (uniResponse.getCode() != 200) {
							log.warn("UNI '{}' could not be deleted. Reason: {}", uniCktId, uniResponse.getMessage());
							errorMessages.append("UNI '").append(uniCktId).append("' deletion failed: ")
									.append(uniResponse.getMessage()).append(". ");
						} else {
							log.info("UNI '{}' deleted successfully.", uniCktId);
							uniProcessed = true;
						}
					} catch (Exception ex) {
						log.error("Exception while deleting UNI '{}': {}", uniCktId, ex.getMessage(), ex);
						errorMessages.append("Exception during UNI deletion for '").append(uniCktId).append("': ")
								.append(ex.getMessage()).append(". ");
					}
				}
			} else {
				log.info("No UNI circuits provided in request. Skipping UNI disconnect.");
			}
			
			StringBuilder msg = new StringBuilder();
			if (evcDeleted && routeDeleted && isDeletedInE2) {
				msg.append("EVC and its associated route deleted successfully.");
			} else if (evcDeleted && isDeletedInE2) {
				msg.append("EVC deleted successfully and  Route retained.");
			}
			if (uniProcessed) {
				msg.append(" Hard disconnect completed for associated UNIs.");
			}
			if (errorMessages.length() > 0) {
				msg.append(" Warnings: ").append(errorMessages.toString().trim());
				response.setCode(207);
			} else {
				response.setCode(200);
			}

			response.setMessage(msg.toString());

		} catch (Exception ex) {
			log.error("Exception in hardDisconnectEVC: ", ex);
			response.setCode(500);
			response.setMessage("Internal error during hard disconnect. " + ex.getMessage());
		}

		return response;
	}
	
	public ResponseStatus findEVPLANRoutes(Map<String, Object> devicesInfo) {
		log.info("=> EthernetOrderService:findEVPLANRoutes: START");

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String sourceDevice = (String) devicesInfo.get("sourceDevice");
		List<String> targetDevices = (List<String>) devicesInfo.get("targetDevices");
		targetDevices.removeIf(Objects::isNull);

		if (sourceDevice == null || targetDevices == null || targetDevices.isEmpty()) {
			response.setMessage("Invalid input: sourceDevice or targetDevices missing.");
			return response;
		}

		Map<String, Object> responseData = new LinkedHashMap<>();
		responseData.put("pathFrom", sourceDevice.toUpperCase());
		responseData.put("pathTo", targetDevices.stream().map(String::toUpperCase).collect(Collectors.toList()));
		List<Map<String, Object>> allRoutes = new ArrayList<>();
		Integer numberOfPaths = 2;

		try (Session session = driver.session()) {
			for (String targetDevice : targetDevices) {
				List<Map<String, Object>> alternateRoutes = getAlternateRoutesV1(session, sourceDevice.toUpperCase(),
						targetDevice.toUpperCase(), numberOfPaths);

				for (Map<String, Object> altRoute : alternateRoutes) {
					List<Map<String, Object>> connections = (List<Map<String, Object>>) altRoute.get("route");
					List<Map<String, Object>> finalRoute = new ArrayList<>();
					List<String> nniNames = new ArrayList<>();
					List<String> mplsAEndDevices = new ArrayList<>();

					for (Map<String, Object> connection : connections) {
						String connectionType = (String) connection.get("connectionType");
						Map<String, Object> aEndInfo = (Map<String, Object>) connection.get("aEndInfo");
						Map<String, Object> aEndDevice = (Map<String, Object>) (aEndInfo != null
								? aEndInfo.get("device")
								: null);

						if (aEndDevice != null) {
							String deviceRole = (String) aEndDevice.get("deviceRoles");

							if ("NNI".equalsIgnoreCase(connectionType)) {
								if (deviceRole != null && (deviceRole.contains("NPE") || deviceRole.contains("MER"))) {
									// Convert to MPLS connection
									String menId = (String) aEndDevice.get("topologyName");

									Map<String, Object> newConnection = new LinkedHashMap<>();
									newConnection.put("connection", connection.get("connection"));
									newConnection.put("connectionType", "MPLS");
									newConnection.put("menId", menId);
									Map<String, Object> newAEndInfo = Map.of("device", aEndDevice);
									newConnection.put("aEndInfo", newAEndInfo);

									finalRoute.add(newConnection);
									break; // Stop further processing for this route
								}
							} else if ("MPLS".equalsIgnoreCase(connectionType)) {
								if (deviceRole != null && (deviceRole.contains("NPE") || deviceRole.contains("MER"))) {
									Map<String, Object> newConnection = new LinkedHashMap<>();
									newConnection.put("connection", connection.get("connection"));
									newConnection.put("connectionType", "MPLS");
									newConnection.put("menId", connection.get("menId"));
									Map<String, Object> newAEndInfo = Map.of("device", aEndDevice);
									newConnection.put("aEndInfo", newAEndInfo);

									finalRoute.add(newConnection);
									break; // Stop further processing for this route
								}
							}
						}

						if ("NNI".equalsIgnoreCase(connectionType)) {
							String nniName = (String) connection.get("nniName");
							if (nniName != null)
								nniNames.add(nniName);
						} else if ("MPLS".equalsIgnoreCase(connectionType) && aEndDevice != null) {
							String aEndName = (String) aEndDevice.get("TID");
							if (aEndName != null)
								mplsAEndDevices.add(aEndName);
						}

						finalRoute.add(connection);
					}

					// Check duplicates
					boolean isDuplicate = false;
					for (Map<String, Object> existingRouteMap : allRoutes) {
						List<Map<String, Object>> existingRoute = (List<Map<String, Object>>) existingRouteMap
								.get("route");
						if (areRoutesEquivalent(existingRoute, finalRoute)) {
							isDuplicate = true;
							break;
						}
					}

					if (!isDuplicate) {
						allRoutes.add(Map.of("route", finalRoute));
					}
				}
			}

			response.setCode(200);
			response.setMessage("Successfully found routes.");
			responseData.put("routesList", allRoutes);
			response.setData(responseData);

		} catch (Exception ex) {
			log.error("findEVPLANRoutes - Exception: {}", ex.getMessage(), ex);
			response.setMessage("Exception occurred while finding routes.");
		}

		log.info("<= EthernetOrderService:findEVPLANRoutes: END");
		return response;
	}

	/**
	 * Helper method to check two routes based on: - Number of connections - NNI
	 * names - MPLS aEnd device names
	 */
	private boolean areRoutesEquivalent(List<Map<String, Object>> route1, List<Map<String, Object>> route2) {
		if (route1.size() != route2.size()) {
			return false;
		}

		List<String> nniNames1 = new ArrayList<>();
		List<String> mplsAEndDevices1 = new ArrayList<>();

		List<String> nniNames2 = new ArrayList<>();
		List<String> mplsAEndDevices2 = new ArrayList<>();

		for (Map<String, Object> conn : route1) {
			String connectionType = (String) conn.get("connectionType");
			Map<String, Object> aEndInfo = (Map<String, Object>) conn.get("aEndInfo");
			Map<String, Object> aEndDevice = (aEndInfo != null) ? (Map<String, Object>) aEndInfo.get("device") : null;

			if ("NNI".equalsIgnoreCase(connectionType)) {
				String nniName = (String) conn.get("nniName");
				if (nniName != null)
					nniNames1.add(nniName);
			} else if ("MPLS".equalsIgnoreCase(connectionType) && aEndDevice != null) {
				String aEndName = (String) aEndDevice.get("TID");
				if (aEndName != null)
					mplsAEndDevices1.add(aEndName);
			}
		}

		for (Map<String, Object> conn : route2) {
			String connectionType = (String) conn.get("connectionType");
			Map<String, Object> aEndInfo = (Map<String, Object>) conn.get("aEndInfo");
			Map<String, Object> aEndDevice = (aEndInfo != null) ? (Map<String, Object>) aEndInfo.get("device") : null;

			if ("NNI".equalsIgnoreCase(connectionType)) {
				String nniName = (String) conn.get("nniName");
				if (nniName != null)
					nniNames2.add(nniName);
			} else if ("MPLS".equalsIgnoreCase(connectionType) && aEndDevice != null) {
				String aEndName = (String) aEndDevice.get("TID");
				if (aEndName != null)
					mplsAEndDevices2.add(aEndName);
			}
		}

		Collections.sort(nniNames1);
		Collections.sort(nniNames2);
		Collections.sort(mplsAEndDevices1);
		Collections.sort(mplsAEndDevices2);

		return nniNames1.equals(nniNames2) && mplsAEndDevices1.equals(mplsAEndDevices2);
	}

	public ResponseStatus hardDisconnectNNIV2(Map<String, Object> nniInfo) {
		log.info("=> EthernetOrderService:hardDisconnectNNI: START");
		ResponseStatus response = new ResponseStatus();
		StringBuilder errorMessages = new StringBuilder();

		String cktid = (String) nniInfo.get("cktid");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		boolean usilDeleteSuccess = false;
		boolean e2DeleteSuccess = false;

		try (Session session = driver.session()) {
			Map<String, Object> params = new HashMap<>();
			params.put("cktid", cktid);
			params.put("aliasCktId", aliasCktId);

			// 1. Check NNI existence
			String checkNNI = "MATCH (nni:NNIConnection) WHERE nni.deletedTimeStamp IS NULL "
					+ "AND (nni.cktid = $cktid OR nni.aliasCktId = $aliasCktId) RETURN nni";
			if (session.run(checkNNI, params).list().isEmpty()) {
				response.setCode(404);
				response.setMessage("NNI not found for circuitId: " + cktid);
				return response;
			}

			// 2. Check if NNI is in service (has a route)
			String checkInService = "MATCH (nni:NNIConnection) WHERE (nni.cktid = $cktid OR nni.aliasCktId = $aliasCktId) "
					+ "WITH nni MATCH (nni)-[:RIDES_ON]->(route:ROUTE) WHERE route.deletedTimeStamp IS NULL "
					+ "WITH route WHERE route IS NOT NULL RETURN route LIMIT 1";
			if (!session.run(checkInService, params).list().isEmpty()) {
				response.setCode(400);
				response.setMessage("NNI is associated with a route and cannot be hard deleted.");
				return response;
			}

			// 3. Attempt USIL deletion
			try (Transaction tx = session.beginTransaction()) {
				String deleteQuery = "MATCH (nni:NNIConnection) WHERE (nni.cktid = $cktid OR nni.aliasCktId = $aliasCktId) "
						+ "OPTIONAL MATCH (nni)-[r1:CONNECTED_TO]->(aPort:EquipmentPort) WHERE nni.A_node_id = aPort.node_id "
						+ "OPTIONAL MATCH (nni)-[r2:CONNECTED_TO]->(zPort:EquipmentPort) WHERE nni.Z_node_id = zPort.node_id "
						+ "SET aPort.status = 'Available', zPort.status = 'Available' "
						+ "SET nni.status = 'DISCONNECTED', " 
						+ "nni.disconnectedAt = coalesce(nni.disconnectedAt, datetime()), " 
						+ "nni.deletedTimeStamp = coalesce(nni.deletedTimeStamp, datetime()) " 
						+"DELETE r1, r2";

				tx.run(deleteQuery, params);
				tx.commit();
				usilDeleteSuccess = true;
				log.info("NNI '{}' deleted successfully in USIL.", cktid);
			} catch (Exception ex) {
				log.error("Error deleting NNI from USIL: {}", ex.getMessage(), ex);
				errorMessages.append("NNI deletion failed in USIL. ");
			}

//			// 4. Attempt E2 deletion
//			try {
//				ResponseStatus e2Response = deleteNNIInE2(cktid);
//				int e2Code = e2Response.getCode();
//
//				if (e2Code == 200 || e2Code == 404) {
//					e2DeleteSuccess = true;
//					if (e2Code == 200) {
//						log.info("NNI '{}' deleted successfully in E2.", cktid);
//					} else {
//						log.warn("NNI '{}' not found in E2. Treating as already deleted.", cktid);
//					}
//				} else {
//					errorMessages.append("NNI deletion failed in E2: ").append(e2Response.getMessage()).append(". ");
//				}
//			} catch (Exception ex) {
//				log.error("Exception during NNI deletion in E2: {}", ex.getMessage(), ex);
//				errorMessages.append("Exception during NNI deletion in E2: ").append(ex.getMessage()).append(". ");
//			}

			// 5. Delete AllCircuits node if USIL deletion succeeded
			if (usilDeleteSuccess) {
				try (Transaction tx2 = session.beginTransaction()) {
					String deleteAllCircuitsQuery = "MATCH (ac:allCircuits) WHERE ac.circuitName = $cktid OR ac.aliasCktId = $aliasCktId "
							+ "SET ac.status = 'DELETED', " +
					        "    ac.deletedAt = coalesce(ac.deletedAt, datetime()), " +
					        "    ac.updatedAt = datetime() " +
					        "RETURN ac";
					tx2.run(deleteAllCircuitsQuery, params);
					tx2.commit();
					log.info("AllCircuits node deleted for cktid: {}", cktid);
				} catch (Exception ex) {
					log.warn("Failed to delete AllCircuits node for '{}': {}", cktid, ex.getMessage());
					errorMessages.append("AllCircuits deletion failed in USIL. ");
				}
			}

			// 6. Final response handling
	        StringBuilder msg = new StringBuilder();
	        if (usilDeleteSuccess && e2DeleteSuccess && errorMessages.length() == 0) {
	            msg.append("NNI and associated entities deleted successfully.");
	            response.setCode(200);
	        } else if (usilDeleteSuccess && !e2DeleteSuccess) {
	            msg.append("NNI deleted in USIL. ");
	            msg.append("Warnings: ").append(errorMessages.toString().trim());
	            response.setCode(207);
	        } else {
	            msg.append("NNI deletion failed in USIL. ");
	            if (errorMessages.length() > 0) {
	                msg.append("Errors: ").append(errorMessages.toString().trim());
	            }
	            response.setCode(500);
	        }
	        response.setMessage(msg.toString());

	    } catch (Exception ex) {
	        log.error("Exception in hardDisconnectNNI: ", ex);
	        response.setCode(500);
	        response.setMessage("Internal error during hard delete: " + ex.getMessage());
	    }

	    return response;
	}
	
	public ResponseStatus hardDisconnectNNI(Map<String, Object> nniInfo) {
		log.info("=> EthernetOrderService:hardDisconnectNNI: START");
		ResponseStatus response = new ResponseStatus();
		StringBuilder errorMessages = new StringBuilder();

		String cktid = (String) nniInfo.get("cktid");
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		boolean usilDeleteSuccess = false;
		boolean e2DeleteSuccess = false;

		try (Session session = driver.session()) {
			Map<String, Object> params = new HashMap<>();
			params.put("cktid", cktid);
			params.put("aliasCktId", aliasCktId);

			// 1. Check NNI existence
			String checkNNI = "MATCH (nni:NNIConnection) WHERE nni.deletedTimeStamp IS NULL "
					+ "AND (nni.cktid = $cktid OR nni.aliasCktId = $aliasCktId) RETURN nni";
			if (session.run(checkNNI, params).list().isEmpty()) {
				response.setCode(404);
				response.setMessage("NNI not found for circuitId: " + cktid);
				return response;
			}

			// 2. Check if NNI is in service (has a route)
			String checkInService = "MATCH (nni:NNIConnection) WHERE (nni.cktid = $cktid OR nni.aliasCktId = $aliasCktId) "
					+ "WITH nni MATCH (nni)-[:RIDES_ON]->(route:ROUTE) WHERE route.deletedTimeStamp IS NULL "
					+ "WITH route WHERE route IS NOT NULL RETURN route LIMIT 1";
			if (!session.run(checkInService, params).list().isEmpty()) {
				response.setCode(400);
				response.setMessage("NNI is associated with a route and cannot be hard deleted.");
				return response;
			}

			// 3. Attempt USIL deletion
			try (Transaction tx = session.beginTransaction()) {
				String deleteQuery = "MATCH (nni:NNIConnection) WHERE (nni.cktid = $cktid OR nni.aliasCktId = $aliasCktId) "
						+ "OPTIONAL MATCH (nni)-[r1:CONNECTED_TO]->(aPort:EquipmentPort) WHERE nni.A_node_id = aPort.node_id "
						+ "OPTIONAL MATCH (nni)-[r2:CONNECTED_TO]->(zPort:EquipmentPort) WHERE nni.Z_node_id = zPort.node_id "
						+ "SET aPort.status = 'Available', zPort.status = 'Available' "
						+ "DELETE r1, r2, nni";

				tx.run(deleteQuery, params);
				tx.commit();
				usilDeleteSuccess = true;
				log.info("NNI '{}' deleted successfully in USIL.", cktid);
			} catch (Exception ex) {
				log.error("Error deleting NNI from USIL: {}", ex.getMessage(), ex);
				errorMessages.append("NNI deletion failed in USIL. ");
			}

//			// 4. Attempt E2 deletion
//			try {
//				ResponseStatus e2Response = deleteNNIInE2(cktid);
//				int e2Code = e2Response.getCode();
//
//				if (e2Code == 200 || e2Code == 404) {
//					e2DeleteSuccess = true;
//					if (e2Code == 200) {
//						log.info("NNI '{}' deleted successfully in E2.", cktid);
//					} else {
//						log.warn("NNI '{}' not found in E2. Treating as already deleted.", cktid);
//					}
//				} else {
//					errorMessages.append("NNI deletion failed in E2: ").append(e2Response.getMessage()).append(". ");
//				}
//			} catch (Exception ex) {
//				log.error("Exception during NNI deletion in E2: {}", ex.getMessage(), ex);
//				errorMessages.append("Exception during NNI deletion in E2: ").append(ex.getMessage()).append(". ");
//			}

			// 5. Delete AllCircuits node if USIL deletion succeeded
			if (usilDeleteSuccess) {
				try (Transaction tx2 = session.beginTransaction()) {
					String deleteAllCircuitsQuery = "MATCH (ac:allCircuits) WHERE ac.circuitName = $cktid OR ac.aliasCktId = $aliasCktId DELETE ac";
					tx2.run(deleteAllCircuitsQuery, params);
					tx2.commit();
					log.info("AllCircuits node deleted for cktid: {}", cktid);
				} catch (Exception ex) {
					log.warn("Failed to delete AllCircuits node for '{}': {}", cktid, ex.getMessage());
					errorMessages.append("AllCircuits deletion failed in USIL. ");
				}
			}

			// 6. Final response handling
	        StringBuilder msg = new StringBuilder();
	        if (usilDeleteSuccess && e2DeleteSuccess && errorMessages.length() == 0) {
	            msg.append("NNI and associated entities deleted successfully.");
	            response.setCode(200);
	        } else if (usilDeleteSuccess && !e2DeleteSuccess) {
	            msg.append("NNI deleted in USIL. ");
	            msg.append("Warnings: ").append(errorMessages.toString().trim());
	            response.setCode(207);
	        } else {
	            msg.append("NNI deletion failed in USIL. ");
	            if (errorMessages.length() > 0) {
	                msg.append("Errors: ").append(errorMessages.toString().trim());
	            }
	            response.setCode(500);
	        }
	        response.setMessage(msg.toString());

	    } catch (Exception ex) {
	        log.error("Exception in hardDisconnectNNI: ", ex);
	        response.setCode(500);
	        response.setMessage("Internal error during hard delete: " + ex.getMessage());
	    }

	    return response;
	}


//	public Map<String, Object> createNmiInE2(String circuitId) {
//		
//		Map<String, Object> responseMap = new HashMap<>();
//	    responseMap.put("code", 500);
//	    responseMap.put("message", "Failed to create NMI in E2");
//	    
//		String query = """
//				WITH $circuitId AS inputCktId
//				MATCH (nni:NNIConnection)
//				WHERE nni.cktid = inputCktId
//				MATCH (nni)-[:CONNECTED_TO]->(port:EquipmentPort)
//				WITH nni,
//				     [p IN COLLECT(port) WHERE p.portKey = nni.A_port_key][0] AS aport,
//				     [p IN COLLECT(port) WHERE p.portKey = nni.Z_port_key][0] AS zport
//				RETURN aport, zport, nni
//				""";
//
//		Map<String, Object> params = Map.of("circuitId", circuitId);
//
//		try (Session session = driver.session()) {
//			Map<String, Object> circuitPayload = session.readTransaction(tx -> {
//				Result resultSet = tx.run(query, params);
//
//				if (!resultSet.hasNext()) {
//					throw new RuntimeException("Circuit ID not found: " + circuitId);
//				}
//
//				Record record = resultSet.next();
//				Map<String, Object> aport =  new HashMap<>(record.get("aport").asMap());
//
//				Map<String, Object> zport =  new HashMap<>(record.get("zport").asMap());
//				Map<String, Object> nni = record.get("nni").asMap();
//
//				// Call your service method to convert bandwidth
//				String aportBw = ApplicationUtils.bandwidthConvertor((String) aport.get("bw"),false,true);
//				String zportBw = ApplicationUtils.bandwidthConvertor((String) zport.get("bw"),false,true);
//
//				// Format port data
//				Map<String, Object> portA = buildPortData(aport, aportBw);
//				Map<String, Object> portZ = buildPortData(zport, zportBw);
//
//				// Build final response
//				Map<String, Object> response = new LinkedHashMap<>();
//				String cktid = "";
//				if (circuitId != null) {
//		            cktid = circuitId.trim();
//		            if (circuitId.endsWith("*")) {
//		            	cktid = circuitId.substring(0, circuitId.length() - 1);
//		            }
//		        }
//				response.put("circuitId", cktid);
//				response.put("ckidType", "transport");
//				response.put("serviceMigrationTag", nni.get("cac"));
//				response.put("relatedckidRelationship", null);
//				response.put("nc", null);
//				response.put("nci", null);
//				response.put("secnci", null);
//				response.put("specCode", null);
//				response.put("ringId", null);
//				response.put("facilityMakeupType", null);
//				response.put("portData", List.of(portZ, portA));
//
//				return response;
//
//			});

//			Map<String, Object> e2Response = serviceClient.createNmiInE2(circuitPayload);
//	        log.info("Response from E2 for circuit '{}': {}", circuitId, e2Response);
//
//	        if (e2Response != null && e2Response.containsKey("code")) {
//	            Object codeObj = e2Response.get("code");
//	            int code = (codeObj instanceof Integer) ? (Integer) codeObj : Integer.parseInt(codeObj.toString());
//	            responseMap.put("code", code);
//	            responseMap.put("message", e2Response.getOrDefault("message", ""));
//	        } else {
//	            responseMap.put("code", 500);
//	            responseMap.put("message", "Invalid response from E2 API");
//	        }

//	        boolean updateE2NNIFlag = ((int) responseMap.get("code") == 200);
//	        boolean updated = updateE2NNIFlag(circuitId, updateE2NNIFlag);
//
//	        if (!updated) {
//	            log.warn("Failed to update updateE2NNIFlag flag for circuit '{}'", circuitId);
//	        }

//	    } catch (RuntimeException ex) {
//	        log.error("Runtime error in createNmiInE2 for circuit '{}': {}", circuitId, ex.getMessage(), ex);
//	        responseMap.put("code", 500);
//	        responseMap.put("message", "Runtime error: " + ex.getMessage());
//	    } catch (Exception ex) {
//	        log.error("Unexpected error in createNmiInE2 for circuit '{}': {}", circuitId, ex.getMessage(), ex);
//	        responseMap.put("code", 500);
//	        responseMap.put("message", "Unexpected error: " + ex.getMessage());
//	    }
//
//	    return responseMap;
//
//	}

	private Map<String, Object> buildPortData(Map<String, Object> port, String modifiedBw) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("deviceIdentifier", ((String) port.get("location")).toLowerCase());
		data.put("shelfNumber", toInt(port.get("shelf")));
		data.put("cardNumber", port.get("slot") != null ? toInt(port.get("slot")) : -1);
		data.put("subcardNumber", port.get("subslot") != null ? toInt(port.get("subslot")) : -1);
		data.put("portNumber", toInt(port.get("id")));
		data.put("portConnectionInUse", ((String) port.get("connector")).toLowerCase());
		data.put("portSpeedInUse", modifiedBw);
		data.put("portMigrationTag", port.get("portKey"));
		return data;
	}

	private int toInt(Object val) {
		if (val == null)
			return -1;
		return Integer.parseInt(val.toString());

	}
	
//	public ResponseStatus createUNIInE2(String circuitId) {
//	    log.info("=> createUNIInE2(): building UNI payload for circuit '{}'", circuitId);
//	    ResponseStatus status = new ResponseStatus();
//
//	    try (Session session = driver.session()) {
//	        Transaction tx = session.beginTransaction();
//
//	        String payload = prepareUniPayload(tx, circuitId);
//	        tx.commit();
//
//	        if (payload == null || payload.isBlank()) {
//	            log.error("Failed to prepare UNI payload for circuit '{}'", circuitId);
//	            status.setCode(400);
//	            status.setMessage("No payload generated for circuit " + circuitId);
//	            return status;
//	        }
//	        
//	     //Extract NMI ID from payload
//	        ObjectMapper mapper = new ObjectMapper();
//
//	        Map<String, Object> payloadMap = mapper.readValue(payload, Map.class);
//
//	        // Navigate to nmiInfo.cktid
//	        Map<String, Object> nmiInfo = (Map<String, Object>) payloadMap.get("nmiInfo");
//	        String nmiId = nmiInfo != null ? (String) nmiInfo.get("cktid") : null;
//	        
//	        if (nmiId == null || nmiId.isBlank()) {
//	            log.error("NMI ID is null or empty. Cannot proceed with UNI creation.");
//	            status.setCode(404);
//	            status.setMessage("NMI not found in E2. Cannot create UNI.");
//	            return status;
//	        }
//	       
//	        Map<String, Object> nmiCheckResponse = serviceClient.getNniResponseFromE2(nmiId);
//	        Object codeObj = nmiCheckResponse.get("code");
//	        int code = (codeObj instanceof Integer) ? (Integer) codeObj : 500;
//
//	        if (code == 200) {
//	            log.info("NMI '{}' exists in E2.", nmiId);
//	            // Proceed with UNI creation
//	        } else {
//	        	log.error("NMI '{}' not found in E2. UNI creation aborted.", nmiId);
//	            status.setCode(404);
//	            status.setMessage("NMI '" + nmiId + "' not found in E2. Cannot create UNI.");
//	            return status;
//	        }

//	        Map<String, Object> e2Response = serviceClient.createUniInE2(payload);
//	        log.info("E2 response for UNI '{}': {}", circuitId, e2Response);
//
//            boolean success = e2Response != null && !e2Response.containsKey("error");
//
//            if (success) {
//                status.setCode(200);
//                status.setMessage("UNI circuit '" + circuitId + "' created successfully in E2.");
//                status.setData(e2Response);
//
//                boolean updateE2UNIFlag = updateE2UNIFlag(circuitId, true);
//                if (!updateE2UNIFlag) {
//                    log.warn("Failed to update updateE2UNIFlag flag for UNI circuit '{}'", circuitId);
//                } else {
//                    log.info("updateE2UNIFlag flag updated to true for UNI circuit '{}'", circuitId);
//                }
//            } else {
//                status.setCode(500);
//                status.setMessage("E2 creation failed for circuit '" + circuitId + "': " + e2Response.get("error"));
//            }
//	    } catch (Exception ex) {
//	        log.error("Exception in createUNIInE2: {}", ex.getMessage(), ex);
//	        status.setCode(500);
//	        status.setMessage("Exception occurred: " + ex.getMessage());
//	    }
//
//	    return status;
//	}

	private String prepareUniPayload(Transaction tx, String circuitId) throws JsonProcessingException {
        String reqPayload = "";
        String aliasCktId = circuitId.replaceAll("[^A-Z0-9]", "");
        String uniQuery =
        		"MATCH (uni:UNIConnection {aliasCktId:'"+ aliasCktId +"'})"
                        + " WHERE uni.deletedTimeStamp IS NULL"
                        + " WITH uni"
                        + " MATCH (uni)-[con1:CONNECTED_TO]->(uniPort:EquipmentPort)-[xcon:XCONNECT]->(nmiPort:EquipmentPort)"
                        + "<-[con2:CONNECTED_TO]-(nni:NNIConnection)-[con3:CONNECTED_TO]->(nmiPort2:EquipmentPort)"
                        + " WHERE con1.deletedTimeStamp IS NULL"
                        + " AND uniPort.deletedTimeStamp IS NULL"
                        + " AND xcon.deletedTimeStamp IS NULL"
                        + " AND nmiPort.deletedTimeStamp IS NULL"
                        + " AND con2.deletedTimeStamp IS NULL"
                        + " AND nni.deletedTimeStamp IS NULL"
                        + " AND con3.deletedTimeStamp IS NULL"
                        + " AND nmiPort2.deletedTimeStamp IS NULL"
                        + " WITH uni, uniPort, nmiPort, nmiPort2, nni"
                        + " RETURN"
                        + " uni AS UniInfo,"
                        + " uniPort AS UniPortInfo,"
                        + " nni as NmiInfo ";

        log.info("Query to get UNI basic details "+ uniQuery);
        try {
            List<Record> uniInfoList = tx.run(uniQuery).list();
            if (uniInfoList == null || uniInfoList.isEmpty()) {
                log.error("No UNI circuit found with aliasId : " + circuitId);
                return "";
            }
            Record uniRec = uniInfoList.get(0);
            Map<String, Object> uniInfo = new HashMap<>(uniRec.get("UniInfo").asMap());
            Map<String, Object> uniPortInfo = new HashMap<>(uniRec.get("UniPortInfo").asMap());
            Map<String, Object> NmiInfo = new HashMap<>(uniRec.get("NmiInfo").asMap());
           // Map<String, Object> evcRelationshipInfo = new HashMap<>(uniRec.get("EvcRelationshipInfo").asMap());

            reqPayload = buildUniPayload(uniInfo, uniPortInfo, NmiInfo);

            log.info("UNI details '{}", uniInfo);
        } catch (Exception ex) {
            log.error("Caught Exception: '{}'", ex.getMessage());
            throw ex;
        }
        return reqPayload;
    }

	public ResponseStatus removeUniFromEVPLAN(String evcName, String uniName) {
		log.info("=> EthernetOrderService:removeUniFromEVPLAN: START");

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		String errMsg = "";
		String cktid = evcName;
		String aliasCktId = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		String uniCktId = uniName.toUpperCase();
		String uniAliasCktId = uniName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		boolean routeDeleted = false;
		boolean evcDeleted = false;
		boolean uniProcessed = false;

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			// Step 1: Check if EVC exists
			String existenceCheckQuery = "MATCH (evc:EVCConnection) "
					+ " WHERE (evc.serviceName = $cktid OR evc.aliasCktId = $aliasCktId) RETURN evc";
			Map<String, Object> params = Map.of("cktid", cktid, "aliasCktId", aliasCktId);
			List<Record> existResult = tx.run(existenceCheckQuery, params).list();

			if (existResult.isEmpty()) {
				log.error("EVC not found with serviceName: " + cktid);
				response.setCode(404);
				response.setMessage("EVC not found with serviceName: " + cktid);
				tx.commit();
				return response;
			}

			// 1. Check UNI existence
			String checkUNI = "MATCH (uni:UNIConnection) WHERE (uni.cktid = $uniCktId "
					+ " OR uni.aliasCktId = $uniAliasCktId) RETURN uni";
			Map<String, Object> uniParams = Map.of("uniCktId", uniCktId, "uniAliasCktId", uniAliasCktId);

			List<Record> uniExistResult = tx.run(checkUNI, uniParams).list();
			if (uniExistResult.isEmpty()) {
				log.error("UNI not found for circuitId: " + uniName);
				response.setCode(404);
				response.setMessage("UNI not found for circuitId: " + uniName);
				return response;
			}

			// Step 2: Check if route has other EVCs
			String routeQuery = "MATCH (uni:UNIConnection) WHERE uni.cktid= $uniCktId OR uni.aliasCktId = $uniAliasCktId "
					+ "MATCH (evc:EVCConnection) WHERE evc.serviceName= $cktid OR evc.aliasCktId = $aliasCktId "
					+ " MATCH (uni)-[:CONNECTED_TO]->(rt:ROUTE)-[:RIDES_ON]->(evc)"
					+ " MATCH (uni)-[rel:AEND|ZEND|VEND]-(uevc:EVCConnection) WITH count(uevc) as evcOnUniCount, rt"
					+ " OPTIONAL MATCH (rt)<-[:CONNECTED_TO]-(uc:UNIConnection) WITH rt, evcOnUniCount, count(uc) as uniCount"
					+ " OPTIONAL MATCH (rt)-[:RIDES_ON]->(ec:EVCConnection) "
					+ " WITH rt, uniCount, evcOnUniCount, count(ec) as evcCount"
					+ " RETURN   uniCount, evcCount, rt.Route_ID as routeId, evcOnUniCount";
			Map<String, Object> rtParams = Map.of("uniCktId", uniCktId, "uniAliasCktId", uniAliasCktId, "cktid", cktid,
					"aliasCktId", aliasCktId);
			List<Record> rtResult = tx.run(routeQuery, rtParams).list();
			if (!rtResult.isEmpty()) {

				Record rtRec = rtResult.get(0);
				int uniCt = rtRec.get("uniCount").asInt();
				int evcCt = rtRec.get("evcCount").asInt();
				int evcOnUniCount = rtRec.get("evcOnUniCount").asInt();
				String  routeId = rtRec.get("routeId").asString();
				String rtDeleteQuery = "";
				if ((uniCt <= 1) && (evcCt <= 1)) {
					rtDeleteQuery = "MATCH (rt:ROUTE{Route_ID:'" + routeId + "'}) DETACH DELETE rt";
					tx.run(rtDeleteQuery);
					routeDeleted = true;
				} else {
					//if (evcCt == 1) {
						rtDeleteQuery = "MATCH (rt:ROUTE{Route_ID:'" + routeId
								+ "'})-[rel:RIDES_ON]->(evc:EVCConnection) "
								+ " WHERE (evc.serviceName= $cktid OR evc.aliasCktId = $aliasCktId) DELETE rel";
						tx.run(rtDeleteQuery, params);
						routeDeleted = false;
					//}
					if (evcOnUniCount == 1) {
						rtDeleteQuery = "MATCH (rt:ROUTE{Route_ID:'" + routeId
								+ "'})<-[:CONNECTED_TO]-(uni:UNIConnection) "
								+ " WHERE ( uni.cktid= $uniCktId OR uni.aliasCktId = $uniAliasCktId) DELETE rel";
						tx.run(rtDeleteQuery, uniParams);
						routeDeleted = false;
					}
				}
			}

			// Step 3: remove relation to UNI from EVC.
			String uniEvcRel = "MATCH (evc:EVCConnection)-[rel:AEND|ZEND|VEND]-(uni:UNIConnection)"
					+ " WHERE (uni.cktid= $uniCktId OR uni.aliasCktId = $uniAliasCktId)"
					+ " AND (evc.serviceName= $cktid OR evc.aliasCktId = $aliasCktId) DELETE rel";
			Map<String, Object> unievcParams = Map.of("uniCktId", uniCktId, "uniAliasCktId", uniAliasCktId, "cktid",
					cktid, "aliasCktId", aliasCktId);
			tx.run(uniEvcRel, unievcParams);
			tx.commit();

			response.setCode(200);
			response.setMessage(" Successfully removed UNI connection '" + uniName + "' of EVC '" + evcName + "'.");

		} catch (Exception ex) {
			log.error("Exception in removeUniFromEVPLAN: ", ex);
			response.setCode(500);
			response.setMessage("Internal error during uni disconnect on EVPLAN.");
		}

		return response;
	}

	public String buildUniPayload(Map<String, Object> uniInfo, Map<String, Object> uniPortInfo,
			Map<String, Object> NmiInfo ) throws JsonProcessingException {

		Map<String, Object> payload = new LinkedHashMap<>();

		Map<String, Object> uni = new LinkedHashMap<>();

		String cktId = (String) uniInfo.get("cktid");
		uni.put("uniServiceInstanceId", cktId);
		String pbrl = (String) uniInfo.get("portBasedRateLimited");
		if(StringUtils.equalsIgnoreCase(pbrl, "true"))
		uni.put("portBasedRateLimited", true);
		else
			uni.put("portBasedRateLimited", false);

		// Constructing uniId
		String location = (String) uniPortInfo.get("location");
		String shelf = getOrDefault(uniPortInfo, "shelf", "-1");
		String slot = "-1";
		String subSlot = "-1";
		String idorport = getOrDefault(uniPortInfo, "id", "-1");
		String connector = (String) uniPortInfo.get("connector");
	    String bw = ApplicationUtils.bandwidthConvertor(safeStr(uniPortInfo.get("bw")),false,true);
		String uniId = String.format("uni:customer:%s/%s/%s/%s/%s/%s/%s", location, shelf, slot, subSlot, idorport,
				connector, bw);
		uni.put("uniId", uniId);

		uni.put("uniInstantiation", "physical");
		uni.put("uniVirtualFrameMap", null);

		Map<String, Object> link = new HashMap<>();
		link.put("listOfPhysicalLinkId", null);
		link.put("listOfPhysicalLinkPl", "100base-fx");
		link.put("listOfPhysicalLinkFs", "disable");
		link.put("listOfPhysicalLinkPt", "disable");

		uni.put("uniLinks", List.of(link));

		// Hardcoded fields
		uni.put("uniLinkAggregation", "not applicable");
		uni.put("uniConversationId", null);
		uni.put("uniServiceFrameFormat", null);
		uni.put("uniMaxServiceFrameSize", 2000);
		uni.put("uniMaxNumberOfEp", Integer.parseInt(getOrDefault(uniInfo, "noOfEVCs_OVCsAllowed", "1")));
		uni.put("uniMaxVlan", 4094);
		uni.put("uniTokenShare", null);
		uni.put("uniEnvelopes", null);
		uni.put("uniLinkOam", "disable");
		uni.put("uniMeg", "disable");
		uni.put("uniLagLinkMeg", "disable");
		uni.put("uniL2CPAddressSet", "ctb-2");
		uni.put("uniL2CPPeering", null);
		uni.put("uniComments", null);

		Object value = uniInfo.get("NoOfEVCs_OVCsAllowed");
		int noOfEVCs_OVCsAllowed = (value != null) ? (Integer) value : 0;

		// Check if noOfEVCs_OVCsAllowed is greater than 1
		String uniServiceMultiplexing = (noOfEVCs_OVCsAllowed > 1) ? "enable" : "disable";
		uni.put("uniServiceMultiplexing", uniServiceMultiplexing);

		uni.put("uniBundling", "enable");
		uni.put("uniAlltoonebundling","disable");

		uni.put("uniNC", uniInfo.get("networkChannel"));
		uni.put("uniNCI", uniInfo.get("networkChannelInterface"));
		uni.put("uniSECNCI", uniInfo.get("networkChannelInterfaceSec"));
		uni.put("uniSpecCode", uniInfo.get("spec"));
		uni.put("uniProvider", null);
		uni.put("uniProviderTransportDetails", null);
		uni.put("uniMigrationTag", null);
		uni.put("uniServiceInstanceStatus", "reserved");

		// ---- EP section ----
		Map<String, Object> ep = new LinkedHashMap<>();
		ep.put("count", 1);
		ep.put("epConnectionType", "evc");
		ep.put("epInstanceId", null);
		ep.put("epId", buildEpId(NmiInfo));
		ep.put("epServiceInstanceStatus", "reserved");
		ep.put("epRole", "root");
		ep.put("epIngressCosMap", null);
		ep.put("epColorMap", null);
		ep.put("epEgressMap", null);
		ep.put("epIngressBandwidthProfile", null);
		ep.put("epCosNameIngressProfile", null);
		ep.put("epEgressProfile", null);
		ep.put("epCosNameEgressProfile", null);
		ep.put("epSourceMacAddressLimit", null);
		ep.put("epSourceMacAddressLearning", false);
		ep.put("epSubscriberMeg", 0);
		ep.put("epUniOrEnniInstanceId", List.of(cktId));
		ep.put("epMaxNumberofEnni", 10);
		ep.put("epComments", new ArrayList<>());

//		int stag = Integer.parseInt(getOrDefault(evcRelationshipInfo, "STAG", "0"));
//		int ctag = Optional.ofNullable(evcRelationshipInfo.get("CTAG"))
//			    .map(Object::toString)
//			    .map(String::trim)
//			    .filter(s -> !s.isEmpty())
//			    .map(val -> {
//			        try {
//			            return Integer.parseInt(val);
//			        } catch (NumberFormatException e) {
//			            log.warn("Invalid CTAG '{}', defaulting to 0", val);
//			            return 0;
//			        }
//			    }).orElse(0);

		ep.put("epCtagVlanMap", "list");
		ep.put("epCtagList", List.of(0));
		ep.put("epMigrationTag", null);
		ep.put("epMaxNumberofUni", 10);
		ep.put("epMaxNumberOfCtag", 1);

		uni.put("ep", ep);
		uni.put("portBasedRateLimited", true);
		uni.put("isNewUni", true);

				return new ObjectMapper().writeValueAsString(uni);
	}


	private String safeStr(Object val) {
		return val == null ? "" : val.toString();
	}

	private String safeStr(Object val, String defaultVal) {
		return val == null ? defaultVal : val.toString();
	}
	
	private String getOrDefault(Map<String, Object> map, String key, String defaultVal) {
	    return map.getOrDefault(key, defaultVal).toString().trim();
	}
	
	private String buildEpId(Map<String, Object> uniInfo) {
	    try {
	        String circuitId = (String) uniInfo.get("cktid");

	        // Remove trailing '*' if present (and trim just in case)
	        if (circuitId != null) {
	            circuitId = circuitId.trim();
	            if (circuitId.endsWith("*")) {
	                circuitId = circuitId.substring(0, circuitId.length() - 1);
	            }
	        }

	        JsonNode portDetails = serviceClient.getPortDetailsFromEckid(circuitId);
	        return buildEpIdFromPortDetails(portDetails);
	    } catch (Exception e) {
	        log.error("Failed to build epId from port details: {}", e.getMessage(), e);
	        return "ep:default:default";
	    }
	}
	
	private String buildEpIdFromPortDetails(JsonNode portDetailsNode) {
	    if (portDetailsNode == null || !portDetailsNode.isArray() || portDetailsNode.size() < 2) {
	        log.warn("Invalid or insufficient portDetails for epId construction");
	        return "ep:default:default";
	    }

	    JsonNode port1 = portDetailsNode.get(0);
	    JsonNode port2 = portDetailsNode.get(1);

	    String epPart1 = formatEndpointPart(port1);
	    String epPart2 = formatEndpointPart(port2);

	    return String.format("ep:%s:%s", epPart1, epPart2);
	}

	private String formatEndpointPart(JsonNode portNode) {
	    String device = safeStr(portNode.get("deviceIdentifier")).toLowerCase();
	    String shelf = safeStr(portNode.get("shelfNumber"));
	    String card = safeStr(portNode.get("cardNumber"));
	    String subcard = safeStr(portNode.get("subCardNumber"));
	    String port = safeStr(portNode.get("portNumber"));
	    String connector = safeStr(portNode.get("portConnectionInUse"));
	    String speed = safeStr(portNode.get("portSpeedInUse"));

	    return String.join("/", device, shelf, card, subcard, port, connector, speed);
	}

	private String safeStr(JsonNode node) {
	    return (node != null && !node.isNull()) ? node.asText() : "-";
	}
	
	private String isEnabled(Object value) {
	    if (value == null) return "disable";

	    String valStr = value.toString().trim();
	    return valStr.equalsIgnoreCase("Y") || valStr.equalsIgnoreCase("YES") ? "enable" : "disable";
	}
	
//	public ResponseStatus createEVCInE2(String circuitId) {
//		log.info("=> createEVCInE2(): building EVC payload for circuit '{}'", circuitId);
//		ResponseStatus status = new ResponseStatus();
//
//		try (Session session = driver.session()) {
//			Transaction tx = session.beginTransaction();
//			
//			circuitId = circuitId.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();		
//			// Step 1: Validate UNI and NMI existence in E2
//	        ResponseStatus validationStatus = validateUniAndNmiExistenceInE2(circuitId);
//	        if (validationStatus.getCode() != 200) {
//	            log.warn("UNI/NMI validation failed: {}", validationStatus.getMessage());
//	            return validationStatus;
//	        }
//	        
//	        // Step 2: If validation passed, proceed with payload creation
//	        Map<String, Object> payloadInfo = prepareEVCPayload(tx, circuitId);
//
//			String payload = (String) payloadInfo.get("reqPayload");
//	        Map<String, Object> uni1Info = (Map<String, Object>) payloadInfo.get("uni1Info");
//	        Map<String, Object> uni2Info = (Map<String, Object>) payloadInfo.get("uni2Info");
//
//			if (payload == null || payload.isBlank()) {
//				log.error("Failed to prepare EVC payload for circuit '{}'", circuitId);
//				status.setCode(400);
//				status.setMessage("No payload generated for circuit " + circuitId);
//				return status;
//			}
//			
//			// Clean the circuit IDs (cktid) before using them
//			String cleanedUni1Cktid = cleanCktid((String) uni1Info.get("cktid"));
//			String cleanedUni2Cktid = cleanCktid((String) uni2Info.get("cktid"));
//
//			// Retrieve the epInstanceIds using the cleaned circuit IDs
//			String epInstanceIdOne = getEpInstanceIdFromUni(cleanedUni1Cktid);
//			String epInstanceIdTwo = getEpInstanceIdFromUni(cleanedUni2Cktid);
//
//			log.info("Retrieved epInstanceId for UNI1: {}", epInstanceIdOne);
//			log.info("Retrieved epInstanceId for UNI2: {}", epInstanceIdTwo);
//			log.info(payload + "payload in E2");
//
//			Map<String, Object> e2Response = serviceClient.createEVCInE2(payload, 
//			        cleanedUni1Cktid, cleanedUni2Cktid, epInstanceIdOne, epInstanceIdTwo);
//
//		        if (e2Response.containsKey("error")) {
//				status.setCode(500);
//				status.setMessage("E2 creation failed: " + e2Response.get("error"));
//				boolean updated = updateE2EVCFlag(circuitId, false);
//			    if (!updated) {
//			        log.warn("Failed to update updateE2EVCFlag flag to false for circuit '{}'", circuitId);
//			    } else {
//			        log.info("updateE2EVCFlag flag updated to false for circuit '{}'", circuitId);
//			    }
//			} else {
//				status.setCode(200);
//				status.setMessage("EVC circuit '" + circuitId + "' created successfully in E2.");
//				status.setData(e2Response);
//
//				// Update the flag to true
//				boolean updated = updateE2EVCFlag(circuitId, true);
//				if (!updated) {
//					log.warn("Failed to update updateE2EVCFlag flag for circuit '{}'", circuitId);
//				} else {
//					log.info("updateE2EVCFlag flag updated to true for circuit '{}'", circuitId);
//				}
//			}
//		} catch (Exception ex) {
//			log.error("Exception in createEVCInE2: {}", ex.getMessage(), ex);
//			status.setCode(500);
//			status.setMessage("Exception occurred: " + ex.getMessage());
//		}
//
//		return status;
//	}

	private Map<String, Object> prepareEVCPayload(Transaction tx, String circuitId) throws Exception {
	    Map<String, Object> result = new HashMap<>();
	    String reqPayload = "";
	    circuitId = circuitId.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

	    // EVC Query to fetch relevant data
	    String evcQuery = "MATCH (evc:EVCConnection {aliasCktId: '" + circuitId + "'}) "
	            + "WHERE evc.deletedTimeStamp IS NULL "
	            + "MATCH (evc)-[aend:AEND]->(uni1:UNIConnection) "
	            + "WHERE aend.deletedTimeStamp IS NULL AND uni1.deletedTimeStamp IS NULL "
	            + "MATCH (evc)<-[zend:ZEND]-(uni2:UNIConnection) "
	            + "WHERE zend.deletedTimeStamp IS NULL AND uni2.deletedTimeStamp IS NULL "
	            + "RETURN evc, aend, uni1, zend, uni2";

	    try {
	        // Run the query to get EVC and UNI details
	        List<Record> evcInfoList = tx.run(evcQuery).list();
	        if (evcInfoList == null || evcInfoList.isEmpty()) {
	            log.error("No EVC circuit found with aliasId : " + circuitId);
	            return result;
	        }

	        // Extract the data from the returned records
	        Record uniRec = evcInfoList.get(0);
	        Map<String, Object> evcInfo = new HashMap<>(uniRec.get("evc").asMap());
	        Map<String, Object> uni1Info = new HashMap<>(uniRec.get("uni1").asMap());
	        Map<String, Object> uni2Info = new HashMap<>(uniRec.get("uni2").asMap());
	        Map<String, Object> aendInfo = new HashMap<>(uniRec.get("aend").asMap());
	        Map<String, Object> zendInfo = new HashMap<>(uniRec.get("zend").asMap());

	        // Prepare the final EVC data
	        reqPayload = prepareEVCData(evcInfo, uni1Info, uni2Info, aendInfo, zendInfo);

	        log.info("EVC details: {}", evcInfo);

	        // Return payload and UNI info as a Map
	        result.put("reqPayload", reqPayload);
	        result.put("uni1Info", uni1Info);
	        result.put("uni2Info", uni2Info);
	    } catch (Exception ex) {
	        log.error("Caught Exception: '{}'", ex.getMessage());
	        throw ex;
	    }

	    return result;
	}

	private String prepareEVCData(Map<String, Object> evcInfo, Map<String, Object> uni1Info,
			Map<String, Object> uni2Info, Map<String, Object> aendInfo, Map<String, Object> zendInfo) throws Exception {
		String reqPayload = "";

		// EVC ID from evcInfo
		String evcId = (String) evcInfo.get("name");
		// ServiceType (hardcoded as evpl )
		String serviceType = "evpl";
		String serviceSubtype = (String) evcInfo.get("serviceSubtype");
		Map<String, Object> service = new HashMap<>();
		service.put("serviceInstanceId", evcId);
		service.put("serviceInstanceStatus", "Working");
		service.put("serviceName", serviceType);
		service.put("serviceSubType", serviceSubtype);
		service.put("serviceListOfVc", Collections.singletonList(evcId));
		service.put("serviceConnectionType", "Point-to-Point");
		service.put("servicesEpLanCount", "null");
		service.put("servicesLanCount", "null");
		service.put("serviceMigrationTag", "string");
		service.put("serviceComments", new ArrayList<>());
		service.put("serviceCos", "Silver");
		service.put("servicesDiverseRoutes", "true");
		service.put("servicesHasEthernetTransport", "true");
		service.put("servicesTSP", 0);

		
		// Initialize the speed variable as an integer
		Integer speed = 0;

		String speedFromEvc = (String) evcInfo.get("speed");
		if (StringUtils.isBlank(speedFromEvc)) {
		    // If speed is not available from evcInfo, try to get it from AEND (uni1Info)
		    speedFromEvc = (String) aendInfo.get("uniBandwidth");
		}

		if (StringUtils.isNotBlank(speedFromEvc)) {
		    try {
		        // Convert the speed from string to integer
		        speed = Integer.parseInt(speedFromEvc);
		    } catch (NumberFormatException e) {
		        log.error("Invalid speed value: '{}' cannot be converted to Integer", speedFromEvc);
		        // In case of invalid format, default speed to 0
		        speed = 0;
		    }
		}

		// Store the integer speed in the service map
		service.put("speed", speed);


		// build the Virtual Service part
		Map<String, Object> virtualService = new LinkedHashMap<>();
		List<Map<String, String>> vcListOfEp = new ArrayList<>();

		// Get EP Instance ID for UNI1
		String uniId1 = cleanCktid((String) uni1Info.get("cktid"));
		//String epInstanceId1 = getEpInstanceIdFromUni(uniId1);
		Map<String, String> uni1Ep = new LinkedHashMap<>();
		uni1Ep.put("connectionType", "evc");
		uni1Ep.put("epInstanceId", null);
		vcListOfEp.add(uni1Ep);

		// Get EP Instance ID for UNI2
		String uniId2 = cleanCktid((String) uni2Info.get("cktid"));
	//	String epInstanceId2 = getEpInstanceIdFromUni(uniId2);
		Map<String, String> uni2Ep = new LinkedHashMap<>();
		uni2Ep.put("connectionType", "evc");
		uni2Ep.put("epInstanceId", null);
		vcListOfEp.add(uni2Ep);

		virtualService.put("vcId", evcId);
		virtualService.put("vcListOfEp", vcListOfEp);
		virtualService.put("vcConnectionType", "evc");

		ResponseStatus serviceDetails = getVCToProvision(evcInfo.get("serviceName").toString());
		HashMap<String, Object> data = (HashMap<String, Object>) serviceDetails.getData();
		Map<String, Object> vsData = (HashMap<String, Object>) data.get("virtualServices");
		List<Map<String, Object>> bridgeData = (ArrayList<Map<String, Object>>) vsData.get("bridges");
		log.info("bridgeData" + bridgeData);

		// Sort using Streams and collect into a new list
		List<Map<String, Object>> sortedBridgeData = bridgeData.stream()
		    .sorted(Comparator.comparingInt(bridge -> (Integer) bridge.get("hopNumber"))) // Sort by hopNumber
		    .collect(Collectors.toList());

		List<Map<String, Object>> bridges = new ArrayList<>();

		for (Map<String, Object> bridge : sortedBridgeData) {
		    Integer hopNumber = (Integer) bridge.get("hopNumber");

		    Map<String, Object> bridgeEntry = new HashMap<>();
		    bridgeEntry.put("hopNumber", hopNumber);

		    String epId = hopNumber == 2 ? null : (hopNumber == 1 ? null : null);
		    bridgeEntry.put("epInstanceId", epId);

		    bridgeEntry.put("connectionType", bridge.getOrDefault("connectionType", "SwitchedEthernet"));
		    bridgeEntry.put("hop", bridge.getOrDefault("hop", new ArrayList<>()));

		    // Clean and set ckId
		    Object rawCkId = bridge.get("ckId");
		    List<String> cleanedCkIds = new ArrayList<>();
		    if (rawCkId instanceof String[]) {
		        for (String item : (String[]) rawCkId) {
		            cleanedCkIds.add(cleanCktid(item));
		        }
		    } else if (rawCkId instanceof List<?>) {
		        for (Object item : (List<?>) rawCkId) {
		            if (item instanceof String str) {
		                cleanedCkIds.add(cleanCktid(str));
		            }
		        }
		    } else if (rawCkId instanceof String) {
		        cleanedCkIds.add(cleanCktid((String) rawCkId));
		    }
		    bridgeEntry.put("ckId", cleanedCkIds);

		    // Use already stored connectionType
		    String connectionType = (String) bridgeEntry.get("connectionType");

		    // Only add VLAN lists if SwitchedEthernet
		    if ("SwitchedEthernet".equalsIgnoreCase(connectionType)) {
		        String stagVlanMap = (String) bridge.get("stagVlanMap");
		        String ctagVlanMap = (String) bridge.get("ctagvlanmap");

		        List<String> stagVlanList = new ArrayList<>();
		        List<String> ctagVlanList = new ArrayList<>();

		        if (stagVlanMap != null) {
		            stagVlanList.add(stagVlanMap);
		        }
		        if (ctagVlanMap != null) {
		            ctagVlanList.add(ctagVlanMap);
		        }

		        bridgeEntry.put("stagVlanList", stagVlanList);
		        bridgeEntry.put("ctagVlanList", ctagVlanList);
		    }

		    bridgeEntry.put("epipeorvplsid", null);

		    bridges.add(bridgeEntry);
		}



		virtualService.put("bridges", bridges);
		virtualService.put("vcServiceFrameDisposition", "deliver unconditionally");
		virtualService.put("vcBroadcastFrameDisposition", "deliver conditionally");
		virtualService.put("vcUnicastFrameDisposition", "deliver unconditionally");
		virtualService.put("vcTagPreservationCTAGPCP", "Enable");
		virtualService.put("vcTagPreservationCTAGDEI", "Enable");
		virtualService.put("vcTagPreservationVLANID", "disable");
		virtualService.put("vcTagPreservationVLANCOS", "disable");
		virtualService.put("vcTagPreservationSTAGPCP", "disable");
		virtualService.put("vcTagPreservationSTAGDEI", "disable");
		virtualService.put("vcMaxServiceFrameSize", 2000);
		virtualService.put("vcOverSubscription", 0);
		virtualService.put("virtualServiceMigrationTag", "virtualServiceMigrationTag");
		virtualService.put("vcServiceLevelSpecification", null);
		virtualService.put("vcGroupMembership", null);
		virtualService.put("vcMegLevel", null);

		Map<String, Object> finalRequest = new LinkedHashMap<>();
		finalRequest.put("uni", null);
		finalRequest.put("service", service);
		finalRequest.put("virtualService", virtualService);
		
		ObjectMapper mapper = new ObjectMapper();
		reqPayload = mapper.writeValueAsString(finalRequest);

		// Log the payload
		log.info("Prepared EVC payload for circuit '{}': {}", evcId, reqPayload);

		return reqPayload;
	}

//	private String getEpInstanceIdFromUni(String uniId) throws Exception {
//
//	    Map<String, Object> responseMap = serviceClient.getUniResponseFromE2(uniId);
//	    if (responseMap != null && !responseMap.isEmpty()) {
//	        List<Map<String, Object>> epList = (List<Map<String, Object>>) responseMap.get("epList");
//
//	        if (epList != null && !epList.isEmpty()) {
//	            return (String) epList.get(0).get("epInstanceId");
//	        } else {
//	            throw new Exception("EP List is empty or not found for Uni Id: " + uniId);
//	        }
//	    } else {
//	        throw new Exception("Uni Id not found in the response from E2: " + uniId);
//	    }
//	}
	
	private String cleanCktid(String uniId) {
	    if (uniId != null && !uniId.isEmpty()) {
	        uniId = uniId.trim(); // Trim whitespace only

	        // Replace internal whitespace with slashes
	        uniId = uniId.replaceAll("\\s+", "/");

	        // Remove trailing non-alphanumeric (but NOT the slash)
	        if (!uniId.isEmpty()) {
	            char lastChar = uniId.charAt(uniId.length() - 1);
	            if (!Character.isLetterOrDigit(lastChar) && lastChar != '/') {
	                uniId = uniId.substring(0, uniId.length() - 1);
	            }
	        }

	        // Ensure it ends with a slash
//	        if (!uniId.endsWith("/")) {
//	            uniId += "/";
//	        }
	    }
	    return uniId.toLowerCase(); // Convert to lowercase as required
	}
	
//	public ResponseStatus deleteUNIInE2(String circuitId) {
//	    log.info("=> deleteUNIInE2(): Deleting UNI circuit '{}'", circuitId);
//	    ResponseStatus status = new ResponseStatus();
//	    status.setCode(500);
//	    status.setMessage("Failed to delete UNI in E2");
//
//	    try {
//	        // Call external E2 service to delete the UNI
//	        Map<String, Object> e2Response = serviceClient.deleteUniInE2(circuitId);
//
//	        Object statusObj = e2Response.get("status");
//	        Object messageObj = e2Response.get("message");	        
//
//	        String statusStr = (statusObj != null) ? statusObj.toString() : null;
//	        String messageStr = (messageObj != null) ? messageObj.toString() : "";
//
//	        if (statusStr.equals("404") || statusStr.contains("Not Found")) {
//	            // 404 Not Found from E2 service (UNI not found in E2)
//	            log.warn("E2 UNI deletion returned 404 for '{}': {}", circuitId, messageStr);
//	            status.setCode(404);
//	            status.setMessage(messageStr.isEmpty() ? "UNI not found in E2." : messageStr);
//	        } else if (statusStr.equals("200") || statusStr.contains("OK")) {
//	            // Success
//	            log.info("Successfully deleted UNI '{}' in E2 system.", circuitId);
//	            status.setCode(200);
//	            status.setMessage("UNI circuit '" + circuitId + "' deleted successfully in E2.");
//	        } else {
//	            // Unexpected response from E2
//	            log.warn("Unexpected E2 UNI deletion response for '{}': {}", circuitId, e2Response);
//	            status.setCode(500);
//	            status.setMessage("UNI deletion failed in E2: Reason: " +
//	                    (messageStr.isEmpty() ? "Unknown error from E2." : messageStr));
//	        }
//	    } catch (Exception ex) {
//	        log.error("Exception in deleteUNIInE2: {}", ex.getMessage(), ex);
//	        status.setCode(500);
//	        status.setMessage("Exception occurred while deleting UNI in E2: " + ex.getMessage());
//	    }
//
//	    return status;
//	}
	
//	public ResponseStatus deleteEVCInE2(String circuitId) {
//	    log.info("=> deleteEVCInE2(): Deleting EVC circuit '{}'", circuitId);
//	    ResponseStatus status = new ResponseStatus();
//	    status.setCode(500);
//	    status.setMessage("Failed to delete EVC in E2");
//
//	    try {
//	        // Step 1: Get EVC details to retrieve both uniServiceInstanceIds
//	        ResponseStatus getEvcStatus = getEVCDetailsFromE2(circuitId);
//
//	        if (getEvcStatus.getCode() == 404 || 
//	                (getEvcStatus.getMessage() != null && getEvcStatus.getMessage().toLowerCase().contains("not found"))) {
//	                log.warn("EVC '{}' not found during EVC details fetch. Treating as success.", circuitId);
//	                status.setCode(404);
//	                status.setMessage("EVC not found in E2 (404). Proceeding as success.");
//	                return status;
//	            }
//
//	            if (getEvcStatus.getCode() != 200) {
//	                log.warn("Failed to get EVC details before deletion for '{}': {}", circuitId, getEvcStatus.getMessage());
//	                return getEvcStatus;
//	            }
//
//	        // Extract uniServiceInstanceId and targetUniServiceInstanceId
//	        String uniInstanceServiceId = null;
//	        String targetUniInstanceServiceId = null;
//
//	        Object dataObj = getEvcStatus.getData();
//	        if (dataObj instanceof Map) {
//	            Map<?, ?> dataMap = (Map<?, ?>) dataObj;
//	            Object uniIdObj = dataMap.get("uniServiceInstanceId");
//	            Object targetUniIdObj = dataMap.get("targetUniServiceInstanceId");
//
//	            if (uniIdObj != null) {
//	                uniInstanceServiceId = uniIdObj.toString();
//	            }
//	            if (targetUniIdObj != null) {
//	                targetUniInstanceServiceId = targetUniIdObj.toString();
//	            }
//	        }
//
//	        if ((uniInstanceServiceId == null || uniInstanceServiceId.isEmpty()) &&
//	            (targetUniInstanceServiceId == null || targetUniInstanceServiceId.isEmpty())) {
//	            log.warn("Neither uniServiceInstanceId nor targetUniServiceInstanceId found for '{}'. Cannot proceed.", circuitId);
//	            status.setCode(404);
//	            status.setMessage("No uniServiceInstanceIds found, cannot delete EVC.");
//	            return status;
//	        }
//
//	        // Step 2: Call E2 to delete the EVC
//	        try {
//	            Map<String, Object> e2Response = serviceClient.deleteEVCInE2(circuitId, uniInstanceServiceId, targetUniInstanceServiceId);
//
//	            Object statusObj = e2Response.get("status");
//	            Object messageObj = e2Response.get("message");
//
//	            String statusStr = (statusObj != null) ? statusObj.toString() : null;
//	            String messageStr = (messageObj != null) ? messageObj.toString() : "";
//
//	            if (statusStr != null && statusStr.contains("404")) {
//	                log.warn("E2 deletion returned 404 for '{}': {}", circuitId, messageStr);
//	                status.setCode(404);
//	                status.setMessage(messageStr.isEmpty() ? "EVC not found in E2." : messageStr);
//	            } else if (statusStr != null && statusStr.contains("200")) {
//	                log.info("Successfully deleted EVC '{}' in E2 system.", circuitId);
//	                status.setCode(200);
//	                status.setMessage("EVC circuit '" + circuitId + "' deleted successfully in E2.");
//	            } else {
//	                log.warn("Unexpected E2 deletion response for '{}': {}", circuitId, e2Response);
//	                String reason = messageStr.isEmpty() ? "Unknown reason" : messageStr;
//	                status.setCode(500);
//	                status.setMessage("EVC deletion failed in E2: Reason: " + reason);
//	            }
//
//	        } catch (Exception ex) {
//	            String msg = ex.getMessage();
//	            if (msg != null && msg.contains("404")) {
//	                log.warn("E2 deletion 404 handled for '{}': {}", circuitId, msg);
//	                status.setCode(404);
//	                status.setMessage("EVC not found in E2 (handled as success).");
//	            } else {
//	                log.error("E2 deletion failed for '{}': {}", circuitId, msg, ex);
//	                status.setCode(500);
//	                status.setMessage("Error during E2 deletion: " + msg);
//	            }
//	        }
//
//	    } catch (Exception ex) {
//	        log.error("Exception in deleteEVCInE2: {}", ex.getMessage(), ex);
//	        status.setCode(500);
//	        status.setMessage("Exception occurred while deleting in E2: " + ex.getMessage());
//	    }
//
//	    return status;
//	}

//	public ResponseStatus deleteNNIInE2(String circuitId) {
//	    log.info("=> deleteNNIInE2(): Deleting NNI circuit '{}'", circuitId);
//	    ResponseStatus status = new ResponseStatus();
//	    status.setCode(500);
//	    status.setMessage("Failed to delete NNI in E2");
//
//	    try {
//	        // Call external E2 service to delete the NNI
//	        Map<String, Object> e2Response = serviceClient.deleteNNIInE2(circuitId);
//
//	        System.out.println(e2Response +"e2Response");
//	        Object statusObj = e2Response.get("status");
//	        Object messageObj = e2Response.get("message");
//
//	        String statusStr = (statusObj != null) ? statusObj.toString() : null;
//	        String messageStr = (messageObj != null) ? messageObj.toString() : "";
//
//	        if (statusStr != null && statusStr.contains("404")) {
//	            // 404 Not Found from E2 service (NNI not found in E2)
//	            log.warn("E2 NNI deletion returned 404 for '{}': {}", circuitId, messageStr);
//	            status.setCode(404);
//	            status.setMessage(messageStr.isEmpty() ? "NNI not found in E2." : messageStr);
//	        } else if (statusStr != null && statusStr.contains("200")) {
//	            // Success
//	            log.info("Successfully deleted NNI '{}' in E2 system.", circuitId);
//	            status.setCode(200);
//	            status.setMessage("NNI circuit '" + circuitId + "' deleted successfully in E2.");
//	        } else {
//	            // Unexpected response from E2
//	            log.warn("Unexpected E2 NNI deletion response for '{}': {}", circuitId, e2Response);
//	            status.setCode(500);
//	            status.setMessage("NNI deletion failed in E2: Reason: " +
//	                    (messageStr.isEmpty() ? "Unknown error from E2." : messageStr));
//	        }
//	    } catch (Exception ex) {
//	        log.error("Exception in deleteNNIInE2: {}", ex.getMessage(), ex);
//	        status.setCode(500);
//	        status.setMessage("Exception occurred while deleting NNI in E2: " + ex.getMessage());
//	    }
//
//	    return status;
//	}
	
//	public ResponseStatus getEVCDetailsFromE2(String circuitId) {
//	    log.info("=> getEVCDetailsFromE2(): Fetching EVC details for circuit '{}'", circuitId);
//	    ResponseStatus status = new ResponseStatus();
//	    status.setCode(500);
//	    status.setMessage("Failed to fetch EVC details from E2");

//	    try {
//	        // Call to logicalservices/serviceDetails API
//	        Map<String, Object> response = serviceClient.getEVCDetailsFromE2(circuitId);
//
//	        Object statusObj = response.get("status");
//	        Object messageObj = response.get("message");
//	        String statusStr = (statusObj != null) ? statusObj.toString() : null;
//	        String messageStr = (messageObj != null) ? messageObj.toString() : "";
//
//	        if (statusStr != null && statusStr.contains("200")) {
//	            log.info("Successfully fetched EVC details for '{}'", circuitId);
//
//	            // Initialize both IDs
//	            String uniServiceInstanceId = null;
//	            String targetUniServiceInstanceId = null;
//
//	            // Extract from uniDTO
//	            Map<String, Object> uniDTO = (Map<String, Object>) response.get("uniDTO");
//	            if (uniDTO != null && uniDTO.containsKey("uniServiceInstanceId")) {
//	                uniServiceInstanceId = (String) uniDTO.get("uniServiceInstanceId");
//	            }
//
//	            // Extract from targetUniDTO
//	            Map<String, Object> targetUniDTO = (Map<String, Object>) response.get("targetUniDTO");
//	            if (targetUniDTO != null && targetUniDTO.containsKey("uniServiceInstanceId")) {
//	                targetUniServiceInstanceId = (String) targetUniDTO.get("uniServiceInstanceId");
//	            }
//
//	            if ((uniServiceInstanceId != null && !uniServiceInstanceId.isEmpty())
//	                    || (targetUniServiceInstanceId != null && !targetUniServiceInstanceId.isEmpty())) {
//	                Map<String, Object> dataMap = new HashMap<>();
//	                dataMap.put("uniServiceInstanceId", uniServiceInstanceId);
//	                dataMap.put("targetUniServiceInstanceId", targetUniServiceInstanceId);
//
//	                status.setCode(200);
//	                status.setMessage("EVC details fetched successfully.");
//	                status.setData(dataMap);
//	            } else {
//	                log.warn("No uniServiceInstanceId values found in response for '{}'", circuitId);
//	                status.setCode(404);
//	                status.setMessage("uniServiceInstanceIds not found in EVC details.");
//	            }
//
//	        } else if (statusStr != null && statusStr.contains("404")) {
//	            log.warn("EVC not found for '{}': {}", circuitId, messageStr);
//	            status.setCode(404);
//	            status.setMessage(messageStr.isEmpty() ? "EVC not found in E2." : messageStr);
//	        } else {
//	            log.warn("Unexpected response while fetching EVC details for '{}': {}", circuitId, response);
//	            status.setCode(500);
//	            status.setMessage("Unexpected response from E2: " + response.toString());
//	        }
//	    } catch (Exception ex) {
//	        log.error("Exception in getEVCDetailsFromE2: {}", ex.getMessage(), ex);
//	        status.setCode(500);
//	        status.setMessage("Exception occurred while fetching EVC details: " + ex.getMessage());
//	    }

//	    return status;
//	}

	private Map<String, Object> convertedPort(Map<String, Object> aPort) {

		Map<String, Object> newPort = new HashMap<>(aPort);

		String shelf = (String) newPort.get("shelf");
		String slot = (String) newPort.get("slot");
		String subslot = (String) newPort.get("subslot");
		String id = (String) newPort.get("id");

		if (StringUtils.isNotBlank(shelf)
				&& (!StringUtils.equalsIgnoreCase(shelf, "-1") || !StringUtils.equalsIgnoreCase(shelf, "-"))) {
			int shelfNum = Integer.valueOf(shelf);
			newPort.put("shelf", String.valueOf(shelfNum));
		}
		if (StringUtils.isNotBlank(slot)
				&& (!StringUtils.equalsIgnoreCase(slot, "-1") || !StringUtils.equalsIgnoreCase(slot, "-"))) {
			int slotNum = Integer.valueOf(slot);
			newPort.put("slot", String.valueOf(slotNum));
		}
		if (StringUtils.isNotBlank(subslot)
				&& (!StringUtils.equalsIgnoreCase(subslot, "-1") || !StringUtils.equalsIgnoreCase(subslot, "-"))) {
			int subslotNum = Integer.valueOf(subslot);
			newPort.put("subslot", String.valueOf(subslotNum));
		}
		if (StringUtils.isNotBlank(id)
				&& (!StringUtils.equalsIgnoreCase(id, "-1") || !StringUtils.equalsIgnoreCase(id, "-"))) {
			int portNum = Integer.valueOf(id);
			newPort.put("id", String.valueOf(portNum));
		}
		return newPort;
	}

	private Map<String, Object> convertDevice(Map<String, Object> aDevice, boolean isNID) {

		Map<String, Object> newDev = new HashMap<>(aDevice);

		String deviceModel = (String) newDev.get("model");
		String deviceRoles = (String) newDev.get("deviceRoles");
		if (StringUtils.isNotBlank(deviceModel))
			deviceModel = ModelEnum.getMastroeNameByUsilModel(deviceModel);
		String deviceCategory = "";
		if (StringUtils.isNotBlank(deviceRoles))
			deviceCategory = ApplicationUtils.getDeviceCategory(deviceRoles, isNID);
		newDev.put("deviceModel", deviceModel);
		newDev.put("deviceCategory", deviceCategory);

		return newDev;
	}
	
	private boolean updateE2UNIFlag(String uniCktid, boolean availableInE2) {
		String query = "MATCH (n:UNIConnection) WHERE n.cktid = $uniCktid "
                + "SET n.availableInE2 = $availableInE2 "
                + "WITH n "
                + "MATCH (a:allCircuits) WHERE a.circuitName = $uniCktid "
                + "SET a.availableInE2 = $availableInE2 "
                + "RETURN n, a";
		
	    Map<String, Object> parameters = new HashMap<>();
	    parameters.put("uniCktid", uniCktid);
	    parameters.put("availableInE2", availableInE2 ? "true" : "false");

	    try (Session session = driver.session()) {
	        Result result = session.run(query, parameters);
	        return result.hasNext();
	    } catch (Exception e) {
	        log.error("Error updating availableInE2 flag for UNI cktid '{}': {}", uniCktid, e.getMessage(), e);
	        return false;
	    }
	}
	
	private boolean updateE2NNIFlag(String nniCktid, boolean availableInE2) {
	    String cypherQuery = 
	        "MATCH (n:NNIConnection {cktid: $nniCktid}) " +
	        "SET n.availableInE2 = $availableInE2 " +
	        "WITH n " +
	        "MATCH (ac:allCircuits {circuitName: $nniCktid}) " +
	        "SET ac.availableInE2 = $availableInE2 " +
	        "RETURN n, ac";
	    
	    Map<String, Object> parameters = new HashMap<>();
	    parameters.put("nniCktid", nniCktid);
	    parameters.put("availableInE2", availableInE2 ? "true" : "false");

	    try (Session session = driver.session()) {
	        Result result = session.run(cypherQuery, parameters);
	        return result.hasNext();
	    } catch (Exception e) {
	        log.error("Error updating availableInE2 flag for NNI cktid '{}': {}", nniCktid, e.getMessage(), e);
	        return false; 
	    }
	}
	
	private boolean updateE2EVCFlag(String evcCktid, boolean availableInE2) {
	    String cypherQuery = 
	        "MATCH (n:EVCConnection {name: $evcCktid}) " +
	        "SET n.availableInE2 = $availableInE2 " +
	        "WITH n " +
	        "MATCH (ac:allServices {name: $evcCktid}) " +
	        "SET ac.availableInE2 = $availableInE2 " +
	        "RETURN n, ac";
	    
	    Map<String, Object> parameters = new HashMap<>();
	    parameters.put("evcCktid", evcCktid);
	    parameters.put("availableInE2", availableInE2 ? "true" : "false");

	    try (Session session = driver.session()) {
	        Result result = session.run(cypherQuery, parameters);
	        return result.hasNext();
	    } catch (Exception e) {
	        log.error("Error updating availableInE2 flag for EVC cktid '{}': {}", evcCktid, e.getMessage(), e);
	        return false; 
	    }
	}	
	
	private String getNMI(Transaction tx, String aliasCktId) {
	    if (aliasCktId == null || aliasCktId.isBlank()) {
	        return null;
	    }

	    String query = "MATCH (uni:UNIConnection {aliasCktId:'" + aliasCktId + "'})" +
	            " WHERE uni.deletedTimeStamp IS NULL" +
	            " WITH uni" +
	            " MATCH (uni)-[con1:CONNECTED_TO]->(uniPort:EquipmentPort)-[xcon:XCONNECT]->(nmiPort:EquipmentPort)" +
	            " <-[con2:CONNECTED_TO]-(nni:NNIConnection)-[con3:CONNECTED_TO]->(nmiPort2:EquipmentPort)" +
	            " WHERE con1.deletedTimeStamp IS NULL" +
	            " AND uniPort.deletedTimeStamp IS NULL" +
	            " AND xcon.deletedTimeStamp IS NULL" +
	            " AND nmiPort.deletedTimeStamp IS NULL" +
	            " AND con2.deletedTimeStamp IS NULL" +
	            " AND nni.deletedTimeStamp IS NULL" +
	            " AND con3.deletedTimeStamp IS NULL" +
	            " AND nmiPort2.deletedTimeStamp IS NULL" +
	            " RETURN nni.cktid AS nmiCktId LIMIT 1";

	    try {
	        Result result = tx.run(query);
	        if (result.hasNext()) {
	            Record record = result.next();
	            return record.get("nmiCktId").asString(null);
	        } else {
	            return null;
	        }
	    } catch (Exception e) {
	        log.error("Error checking NMI existence for aliasCktId '{}': {}", aliasCktId, e.getMessage());
	        return null;
	    }
	}
	
	private ResponseStatus validateUniAndNmiExistenceInE2(String circuitId) {
		log.info( "Inside validateUniAndNmiExistenceInE2()");
	    ResponseStatus status = new ResponseStatus();
	    status.setCode(200); 

	    try (Session session = driver.session()) {
	        Transaction tx = session.beginTransaction();

	        // Step 1: Get UNI details from EVC query
	        String evcQuery = "MATCH (evc:EVCConnection {aliasCktId: '" + circuitId + "'}) "
	                + "WHERE evc.deletedTimeStamp IS NULL "
	                + "MATCH (evc)-[aend:AEND]->(uni1:UNIConnection) "
	                + "WHERE aend.deletedTimeStamp IS NULL AND uni1.deletedTimeStamp IS NULL "
	                + "MATCH (evc)<-[zend:ZEND]-(uni2:UNIConnection) "
	                + "WHERE zend.deletedTimeStamp IS NULL AND uni2.deletedTimeStamp IS NULL "
	                + "RETURN uni1.aliasCktId AS uni1Alias, uni2.aliasCktId AS uni2Alias";

	        Result evcResult = tx.run(evcQuery);
	        if (!evcResult.hasNext()) {
	            status.setCode(404);
	            status.setMessage("No UNI connections found for given EVC.");
	            return status;
	        }

	        Record evcRecord = evcResult.next();
	        String uni1Alias = evcRecord.get("uni1Alias").asString();
	        String uni2Alias = evcRecord.get("uni2Alias").asString();

//	        // Step 2: Check UNI existence in E2
//	        Map<String, Object> uni1Resp = null;
//	        boolean uni1Exists = false;
//	        try {
//	            uni1Resp = serviceClient.getUniResponseFromE2(uni1Alias);
//	            uni1Exists = uni1Resp != null && !uni1Resp.isEmpty();
//	        } catch (Exception e) {
//	            log.warn("Failed to get UNI1 from E2 for '{}': {}", uni1Alias, e.getMessage());
//	        }
//
//	        Map<String, Object> uni2Resp = null;
//	        boolean uni2Exists = false;
//	        try {
//	            uni2Resp = serviceClient.getUniResponseFromE2(uni2Alias);
//	            uni2Exists = uni2Resp != null && !uni2Resp.isEmpty();
//	        } catch (Exception e) {
//	            log.warn("Failed to get UNI2 from E2 for '{}': {}", uni2Alias, e.getMessage());
//	        }

	        // Step 3: Get NMI details from UNI query
	        String aliasCktId = uni1Alias.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
	        String uniQuery = "MATCH (uni:UNIConnection {aliasCktId:'" + aliasCktId + "'}) "
	                + "WHERE uni.deletedTimeStamp IS NULL "
	                + "WITH uni "
	                + "MATCH (uni)-[con1:CONNECTED_TO]->(uniPort:EquipmentPort)-[xcon:XCONNECT]->(nmiPort:EquipmentPort) "
	                + "<-[con2:CONNECTED_TO]-(nni:NNIConnection)-[con3:CONNECTED_TO]->(nmiPort2:EquipmentPort) "
	                + "WHERE con1.deletedTimeStamp IS NULL "
	                + "AND uniPort.deletedTimeStamp IS NULL "
	                + "AND xcon.deletedTimeStamp IS NULL "
	                + "AND nmiPort.deletedTimeStamp IS NULL "
	                + "AND con2.deletedTimeStamp IS NULL "
	                + "AND nni.deletedTimeStamp IS NULL "
	                + "AND con3.deletedTimeStamp IS NULL "
	                + "AND nmiPort2.deletedTimeStamp IS NULL "
	                + "RETURN nni.aliasCktId AS nmiAlias";

	        Result uniResult = tx.run(uniQuery);
	        String nmiAlias = null;
	        if (uniResult.hasNext()) {
	            nmiAlias = uniResult.next().get("nmiAlias").asString();
	        }
	        boolean nmiExists = false;

//	        if (nmiAlias != null) {
//	            try {
//	                nmiExists = serviceClient.getNniResponseFromE2(nmiAlias) != null;
//	            } catch (Exception e) {
//	                log.warn("Failed to get NMI from E2 for '{}': {}", nmiAlias, e.getMessage());
//	            }
//	        }

//	        // Step 4: Build response messages
//	        if (!uni1Exists && !uni2Exists && !nmiExists) {
//	            status.setCode(404);
//	            status.setMessage("UNI and NMI not found in E2. Cannot proceed with EVC creation.");
//	        } else if ((uni1Exists || uni2Exists) && !nmiExists) {
//	            status.setCode(404);
//	            status.setMessage("UNI found but NMI not found in E2. Cannot proceed with EVC creation.");
//	        } else if (!(uni1Exists || uni2Exists) && nmiExists) {
//	            status.setCode(404);
//	            status.setMessage("NMI found but UNI not found in E2. Cannot proceed with EVC creation.");
//	        } else {
//	            status.setCode(200);
//	            status.setMessage("UNI and NMI found in E2. Validation successful.");
//	        }

	    } catch (Exception e) {
	        status.setCode(500);
	        status.setMessage("Exception during UNI/NMI validation: " + e.getMessage());
	        log.error(status.getMessage(), e);
	    }

	    return status;
	}
	
	public ResponseStatus updateNPEInfo(Map<String, Object> deviceInfo) {
		ResponseStatus responseStatus = new ResponseStatus();

		try {
			String npeName = (String) deviceInfo.get("npeDeviceName");
			String nidName = (String) deviceInfo.get("nidDeviceName");
			String npeCLLI = (String) deviceInfo.get("npeCLLI");
			String npeRelayrck = (String) deviceInfo.get("npeRelayrck");

			if (npeName == null || nidName == null || npeCLLI == null || npeRelayrck == null) {
				responseStatus.setCode(400);
				responseStatus
						.setMessage("Missing required fields: npeDeviceName, nidDeviceName, npeCLLI, or npeRelayrck.");
				return responseStatus;
			}

			// Step 1: Get nodeId of NPE device
			String npeNodeId = getNodeIdFromDevice(npeName, npeCLLI, npeRelayrck);
			if (npeNodeId == null) {
				responseStatus.setCode(404);
				responseStatus.setMessage("NPE device not found in allDevices.");
				return responseStatus;
			}

			// Step 2: Get route from NPE to NID
			ResponseStatus routeResponse = findRouteV1(npeName, nidName, null);
			if (routeResponse.getCode() != 200) {
				responseStatus.setCode(routeResponse.getCode());
				responseStatus.setMessage("Error fetching route info: " + routeResponse.getMessage());
				return responseStatus;
			}

			Map<String, Object> routeData = (Map<String, Object>) routeResponse.getData();
			List<Map<String, Object>> routesList = (List<Map<String, Object>>) routeData.get("routesList");

			List<String> npeRoutes = new ArrayList<>();

			if (routesList != null && !routesList.isEmpty()) {
				Map<String, Object> firstRouteEntry = routesList.get(0);
				List<Map<String, Object>> route = (List<Map<String, Object>>) firstRouteEntry.get("route");

				if (route != null && !route.isEmpty()) {
					for (Map<String, Object> segment : route) {
						Object nniNameObj = segment.get("nniName");
						if (nniNameObj != null) {
							npeRoutes.add(nniNameObj.toString());
						}
					}
				}
			} else {
				responseStatus.setCode(204);
				responseStatus.setMessage("No route found between NID and NPE.");
				return responseStatus;
			}

			// Step 3: Update Equipment and allDevices nodes
			boolean updateSuccess = updateDeviceNodesWithNPEInfo(nidName, npeName, npeNodeId, npeRoutes);
			if (!updateSuccess) {
				responseStatus.setCode(500);
				responseStatus.setMessage("Failed to update device nodes with NPE info.");
				return responseStatus;
			}
			responseStatus.setCode(200);
			responseStatus.setMessage("NPE information updated successfully.");
			return responseStatus;

		} catch (Exception ex) {
			ex.printStackTrace();
			responseStatus.setCode(500);
			responseStatus.setMessage("Exception while updating NPE info: " + ex.getMessage());
			return responseStatus;
		}
	}

	public ResponseStatus getUniBandwdith(String cktName) {
		log.info("getUniBandwdith of : '{}'", cktName);
		Map<String, Object> circuitInfo = new HashMap<String, Object>();

		log.info("=>getUniBandwdith: circuitName '{}'", cktName);

		double usedBW = 0;
		long actualBW = 0;
		double availableBW = 0;
		long portSpeed = 0;
		Map<String,Object> bandwidthInfo = new LinkedHashMap<String,Object>();
		bandwidthInfo.put("circuit", cktName);
        bandwidthInfo.put("nidClli", null);
        bandwidthInfo.put("sfpCustomerFacing", null);
        bandwidthInfo.put("sfpCentralOffice", null);
        bandwidthInfo.put("sfpNetworkFacing", null);
        bandwidthInfo.put("aggClli", null);

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		Transaction tx = null;
		Session session = null;

		String circuitName = cktName;
		String aliasCktId = cktName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		try {
			session = driver.session();
			tx = session.beginTransaction();

			// Step 1: Run the query to get the circuit
            String uniQuery = "MATCH (uni:UNIConnection)-[:CONNECTED_TO]->(ep1:EquipmentPort) " +
                    "WHERE (uni.aliasCktId = $aliasCktId OR uni.cktid = $circuit) " +
                    "OPTIONAL MATCH (ep1)-[:COMPONENT_OF*]->(eq1:Equipment) " +
                    "OPTIONAL MATCH (ep1)-[:XCONNECT]->(ep2:EquipmentPort) " +
                    "OPTIONAL MATCH (nni:NNIConnection)-[:CONNECTED_TO]->(ep2) " +
                    "OPTIONAL MATCH (nni)-[:CONNECTED_TO]->(ep3:EquipmentPort) " +
                    "WHERE ep3 <> ep2 " +
                    "RETURN uni as uni, eq1 as device, ep1 as equipmentPort, " +
                    "ep1.location as nidClli, " +
                    "ep2 as xconnectPort, nni as nniConnection, " +
                    "ep3 as aggPort, ep3.location as aggClli " +
                    "LIMIT 1";
			log.info("query to get the circuitLabel " + uniQuery);

			Map<String, Object> cktParams = new HashMap<>();
			cktParams.put("circuit", circuitName);
			cktParams.put("aliasCktId", aliasCktId);
			Map<String, Object> uniInfo = new LinkedHashMap<>();
			Map<String, Object> device = new HashMap<>();
			Map<String, Object> equipmentPort = new HashMap<>();
            Map<String, Object> xconnectPort = new HashMap<>();
            Map<String, Object> nniConnection = new HashMap<>();
            Map<String, Object> aggPort = new HashMap<>();
            String nidClli = null;
            String aggClli = null;

			Result cktResult = tx.run(uniQuery, cktParams);
            // Replace the existing result extraction with:
            if (cktResult.hasNext()) {
                Record element = cktResult.next();
                uniInfo = element.get("uni").asMap();
                device = element.get("device").asMap();
                equipmentPort = element.get("equipmentPort").asMap();
                
                if (uniInfo.containsKey("deletedTimeStamp")) {
                    log.error("Circuit '{}' is in a disconnected state", circuitName);
                    response.setCode(404);
                    response.setMessage("The circuit is in a disconnected state.");
                    return response;
                }

                // Get nidClli directly from ep1.location in query result
                if (element.containsKey("nidClli")) {
                    nidClli = element.get("nidClli").asString();
                }

                xconnectPort = element.containsKey("xconnectPort") ? element.get("xconnectPort").asMap() : null;
                nniConnection = element.containsKey("nniConnection") ? element.get("nniConnection").asMap() : null;
                aggPort = element.containsKey("aggPort") ? element.get("aggPort").asMap() : null;

                // Get aggClli directly from ep3.location in query result
                if (element.containsKey("aggClli")) {
                    aggClli = element.get("aggClli").asString();
                }
            }

			if( uniInfo == null || uniInfo.isEmpty()) {
				log.error("No circuit found with the circuit name: '{}'", circuitName);
				response.setCode(404);
				response.setMessage("No circuit found with the circuit name: " + circuitName);
				return response;
			}

			Map<String, Object> location = new HashMap<>();

			if(device != null && !device.isEmpty()) {
				location.put("address", (String) device.get("clli_address"));
				location.put("city", (String) device.get("clli_city"));
				location.put("state", (String) device.get("clli_state"));
				location.put("zip", (String) device.get("clli_zip"));
				location.put("latitude", StringUtils.isNotBlank((String)device.get("clli_lat")) ? (String) device.get("clli_lat") : "");
				location.put("longitude", StringUtils.isNotBlank((String)device.get("clli_long")) ? (String) device.get("clli_long") : "");
				bandwidthInfo.put("location", location);
			}

			String uniBw = (String) uniInfo.get("bandwidth");
			String uniBwStr = ApplicationUtils.bandwidthConvertor(uniBw, true, false);
			actualBW = (long) Double.parseDouble(uniBwStr);
			
			String portSpd = (String) equipmentPort.get("bw");
			String portSpdStr = ApplicationUtils.bandwidthConvertor(portSpd, true, false);
			portSpeed = (long) Double.parseDouble(portSpdStr);
			
			String rateLimitType = (String) uniInfo.get("rateLimitType");

			String evcQuery = "MATCH (n:UNIConnection) WHERE (n.aliasCktId = $aliasCktId OR n.cktid = $circuit) "
					+ " MATCH (n)-[r1:AEND|ZEND|VEND]-(e:EVCConnection) WHERE r1.deletedTimeStamp is null"
					+ " AND e.deletedTimeStamp is null RETURN r1.evcBandwidth as evcBW";

			List<Record> evcBwRecords = tx.run(evcQuery, cktParams).list();

			if (evcBwRecords != null && !evcBwRecords.isEmpty()) {
				for (Record bwRec : evcBwRecords) {
					String bwStr = bwRec.get("evcBW").asString();
					if (StringUtils.isNotBlank(bwStr)) {
						bwStr = ApplicationUtils.bandwidthConvertor(bwStr, true, false);
						usedBW += Double.parseDouble(bwStr);
					}
				}
			}

			if ("EVCM".equalsIgnoreCase(rateLimitType)) {
				availableBW = actualBW - usedBW;
			} else {
			    availableBW = actualBW;
			}

            if (StringUtils.isNotBlank(nidClli)) {
                bandwidthInfo.put("nidClli", nidClli);
            }

            if (uniInfo.containsKey("partNumber")) {
                String sfpCustomerFacing = (String) uniInfo.get("partNumber");
                if (StringUtils.isNotBlank(sfpCustomerFacing)) {
                    bandwidthInfo.put("sfpCustomerFacing", sfpCustomerFacing);
                }
            }

            if (xconnectPort == null || xconnectPort.isEmpty()) {
                log.info("No XCONNECT relationship exists for circuit: '{}'", circuitName);
                // Continue with existing bandwidth info, new fields remain null
            } else {
                if (nniConnection != null && !nniConnection.isEmpty()) {
                    String nniLoca = (String) nniConnection.get("loca");
                    String nniLocz = (String) nniConnection.get("locz");
                    String xconnectPortLocation = (String) xconnectPort.get("location");

                    // Get sfpNetworkFacing (based on XCONNECT EquipmentPort location)
                    if (xconnectPortLocation != null) {
                        if (xconnectPortLocation.equals(nniLoca)) {
                            // loca matches XCONNECT port → aPartNumber for sfpNetworkFacing
                            String aPartNumber = (String) nniConnection.get("aPartNumber");
                            if (StringUtils.isNotBlank(aPartNumber)) {
                                bandwidthInfo.put("sfpNetworkFacing", aPartNumber);
                            }
                        } else if (xconnectPortLocation.equals(nniLocz)) {
                            // locz matches XCONNECT port → zPartNumber for sfpNetworkFacing
                            String zPartNumber = (String) nniConnection.get("zPartNumber");
                            if (StringUtils.isNotBlank(zPartNumber)) {
                                bandwidthInfo.put("sfpNetworkFacing", zPartNumber);
                            }
                        }
                    }

                    // Get sfpCentralOffice (based on aggregation EquipmentPort location)
                    if (StringUtils.isNotBlank(aggClli)) {
                        if (aggClli.equals(nniLoca)) {
                            // loca matches aggregation port → aPartNumber for sfpCentralOffice
                            String aPartNumber = (String) nniConnection.get("aPartNumber");
                            if (StringUtils.isNotBlank(aPartNumber)) {
                                bandwidthInfo.put("sfpCentralOffice", aPartNumber);
                            }
                        } else if (aggClli.equals(nniLocz)) {
                            // locz matches aggregation port → zPartNumber for sfpCentralOffice
                            String zPartNumber = (String) nniConnection.get("zPartNumber");
                            if (StringUtils.isNotBlank(zPartNumber)) {
                                bandwidthInfo.put("sfpCentralOffice", zPartNumber);
                            }
                        }
                    }
                }
                if (StringUtils.isNotBlank(aggClli)) {
                    bandwidthInfo.put("aggClli", aggClli);
                }
            }

			bandwidthInfo.put("actualBandwidth", actualBW);
			bandwidthInfo.put("usedBandwidth", usedBW);
			bandwidthInfo.put("availableBandwidth", availableBW);
			bandwidthInfo.put("portSpeed", portSpeed);

			 // ================== New: UNI Layer1 Fiber extraction ==================
	        String uniLayer1Query =
	            "MATCH (uni:UNIConnection) " +
	            "WHERE (uni.aliasCktId = $aliasCktId OR uni.cktid = $circuit) AND uni.layer1 IS NOT NULL " +
	            "WITH uni, apoc.convert.fromJsonList(uni.layer1) AS layers " +
	            "WITH uni, [l IN layers WHERE l.type='Fiber'] AS fiberLayers " +
	            "WITH uni, apoc.coll.flatten([f IN fiberLayers | [t IN f.terms | f.clliA + '/' + f.clliZ + '/' + f.cableName + '/.' + t.strandId]]) AS fiberStrings, fiberLayers " +
	            "RETURN uni.aliasCktId AS uniId, apoc.text.join(fiberStrings, ',') AS fiberALocZLoc, [f IN fiberLayers | f.terms[0].wavelength][0] AS uniFiberLength LIMIT 1";

	        Result uniLayer1Result = tx.run(uniLayer1Query, cktParams);

	        if (uniLayer1Result.hasNext()) {
	            Record rec = uniLayer1Result.next();
	            
	            // Create nested map for uniLayer1
	            Map<String, Object> uniLayer1Map = new LinkedHashMap<>();
	            uniLayer1Map.put("fiberALocZLoc", rec.get("fiberALocZLoc").asString());
	            uniLayer1Map.put("fiberLength", rec.get("uniFiberLength").asString());
	            
	            // Add to bandwidthInfo
	            bandwidthInfo.put("uniLayer1", uniLayer1Map);
	        }


	        // ================== New: NNI Layer1 Fiber extraction ==================
	        String nniLayer1Query =
	            "MATCH (uni:UNIConnection)-[ct:CONNECTED_TO]->(ep1:EquipmentPort)-[xc:XCONNECT]->(ep2:EquipmentPort)<-[ct2:CONNECTED_TO]-(n:NNIConnection) " +
	            "WHERE (uni.aliasCktId = $aliasCktId OR uni.cktid = $circuit) AND n.layer1 IS NOT NULL and ct.deletedTimeStamp is null and ep1.deletedTimeStamp is null "+ 
	            "and xc.deletedTimeStamp is null and ep2.deletedTimeStamp is null and ct2.deletedTimeStamp is null and n.deletedTimeStamp is null " +
	            "WITH n, apoc.convert.fromJsonList(n.layer1) AS layers " +
	            "WITH n, [l IN layers WHERE l.type='Fiber'] AS fiberLayers " +
	            "WITH n, apoc.coll.flatten([f IN fiberLayers | [t IN f.terms | f.clliA + '/' + f.clliZ + '/' + f.cableName + '/.' + t.strandId]]) AS fiberStrings, fiberLayers " +
	            "RETURN n.aliasCktId AS nniId, apoc.text.join(fiberStrings, ',') AS nniLayer1Fiber, [f IN fiberLayers | f.terms[0].wavelength][0] AS nniFiberLength LIMIT 1";

	        Result nniLayer1Result = tx.run(nniLayer1Query, cktParams);

	        if (nniLayer1Result.hasNext()) {
	            Record rec = nniLayer1Result.next();
	            
	            // Create nested map for nmiLayer1
	            Map<String, Object> nmiLayer1Map = new LinkedHashMap<>();
	            nmiLayer1Map.put("fiberALocZLoc", rec.get("nniLayer1Fiber").asString());
	            nmiLayer1Map.put("fiberLength", rec.get("nniFiberLength").asString());
	            
	            // Add to bandwidthInfo
	            bandwidthInfo.put("nmiLayer1", nmiLayer1Map);
	        }
			
			
			response.setCode(200);
			response.setData(bandwidthInfo);
			response.setMessage("Successfully found circuit bandwidth");
			log.info("Circuit Bandwidth details: '{}'",bandwidthInfo);

		} catch (Exception ex) {
				log.error("Failed: caught exception:" + ex.getMessage());
				response.setCode(500);
				response.setMessage("Failed to get the circuit information for: " + circuitName);
			} finally {
			if (session != null)
				session.close();
			}
		return response;
	}
	
	public ResponseStatus saveUNIChangeOrder(Map<String, Object> assignedInventoryBody) {
		log.info("=> EthernetOrderService:saveUNIChangeOrder: START");
		log.info("Save UNIChange Order Info: {}", assignedInventoryBody.toString());

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		try {
			// 1. Validate required fields
			String circuitName = (String) assignedInventoryBody.get("circuitName");
			String nc = (String) assignedInventoryBody.get("nc");
			String nci = (String) assignedInventoryBody.get("nci");
			String secnci = (String) assignedInventoryBody.get("secnci");
			String user = (String) assignedInventoryBody.get("user");

			String aliasCktId = circuitName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

			if (StringUtils.isBlank(circuitName)) {
				throw new IllegalArgumentException("Missing circuitName in request");
			}
			if (StringUtils.isBlank(nc)) {
				throw new IllegalArgumentException("Missing nc in request");
			}
			if (StringUtils.isBlank(nci)) {
				throw new IllegalArgumentException("Missing nci in request");
			}
			if (StringUtils.isBlank(secnci)) {
				throw new IllegalArgumentException("Missing secnci in request");
			}

			// 2. Get Bandwidth from DWH
			Map<String, Object> bwMap = inventorySearchRepo.bandwidthByNCNCI(nc, nci, secnci);
			if (bwMap == null || bwMap.isEmpty()) {
				throw new IllegalStateException("Bandwidth info not found for provided NC/NCI/Secondary NCI");
			}

			String rawBw = (String) bwMap.get("bandwidth");
			if (StringUtils.isBlank(rawBw)) {
				throw new IllegalStateException("Empty bandwidth value returned from DWH");
			}

			String convertedBw = ApplicationUtils.bandwidthConvertor(rawBw, true, false);
			long requestedBw = Long.parseLong(convertedBw);

			// 3. Check if UNIConnection exists
			Map<String, Object> portMap = null;
			long portSpeed = 0;

			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();

				String uniQuery = "MATCH (n:UNIConnection)-[:CONNECTED_TO]->(up:EquipmentPort)"
						+ "WHERE (n.aliasCktId = $aliasCktId OR n.cktid = $circuit) AND n.deletedTimeStamp IS NULL AND up.EquipmentPort IS NULL "
						+ "RETURN n AS uni, up AS port LIMIT 1";

				Map<String, Object> queryParams = Map.of("circuit", circuitName, "aliasCktId", aliasCktId);
				Result result = tx.run(uniQuery, queryParams);

				if (!result.hasNext()) {
					throw new IllegalStateException("No matching UNI Circuit found : " + circuitName);
				}

				Record record = result.next();
				portMap = record.get("port").asMap();

				String portSpeedRaw = (String) portMap.get("bw");
				portSpeed = Long.parseLong(ApplicationUtils.bandwidthConvertor(portSpeedRaw, true, false));
			}

			// 4. Save UNIChangeOrder only after all validations pass
			Map<String, Object> properties = new HashMap<>(assignedInventoryBody);
			properties.put("createdOn", Instant.now().toString());
			properties.put("aliasCktId", aliasCktId);

			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();

				StringBuilder mergeQuery = new StringBuilder(
						"MERGE (n:UNIChangeOrder {circuitName: $circuitName}) SET ");
				String propertyAssignments = properties.entrySet().stream()
						.filter(entry -> !"circuitName".equals(entry.getKey()))
						.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
						.collect(Collectors.joining(", "));
				mergeQuery.append(propertyAssignments);

				tx.run(mergeQuery.toString(), properties);
				tx.commit();
				log.info("UNIChangeOrder saved for circuit: {}", circuitName);
			}

			// 5. Update bandwidth on UNIConnection if requestedBw <= portSpeed
			if (requestedBw <= portSpeed) {
				try (Session session = driver.session()) {
					Transaction tx = session.beginTransaction();

					String updateUniQuery = "MATCH (n:UNIConnection) "
							+ "WHERE (n.aliasCktId = $aliasCktId OR n.cktid = $circuit) AND n.deletedTimeStamp IS NULL "
							+ "SET n.bandwidth = $bandwidth, " + "    n.updatedBy = $updatedBy, "
							+ "    n.updatedOn = $updatedOn";

					Map<String, Object> updateParams = Map.of("circuit", circuitName, "aliasCktId", aliasCktId,
							"bandwidth", requestedBw, "updatedBy", user, "updatedOn", Instant.now().toString());

					tx.run(updateUniQuery, updateParams);
					log.info("UNIConnection updated with bandwidth: {}", requestedBw);

					String updateAllCircuitsQuery = "MATCH (n:allCircuits) "
							+ "WHERE (n.aliasCktId = $aliasCktId OR n.circuitName = $circuit) AND n.deletedTimeStamp IS NULL "
							+ "SET n.bandwidth = $bandwidth, " + "    n.updatedBy = $updatedBy, "
							+ "    n.updatedOn = $updatedOn";
					tx.run(updateAllCircuitsQuery, updateParams);
					log.info("allCircuits updated with bandwidth: {}", requestedBw);
					tx.commit();
					response.setCode(200);
					response.setMessage("UNI Change Order saved and updated bandwidth successfully.");
				}
			} else {
				log.warn("Requested bandwidth [{}] exceeds port speed [{}]. Skipping UNIConnection update.",
						requestedBw, portSpeed);
				response.setCode(200);
				response.setMessage(
						"UNI Change Order saved. Bandwidth update skipped: requested BW exceeds port speed.");
			}
		} catch (IllegalArgumentException | IllegalStateException ex) {
			log.warn("Validation failed: {}", ex.getMessage());
			response.setCode(400);
			response.setMessage("Validation failed: " + ex.getMessage());
		} catch (Exception ex) {
			log.error("Unexpected error in saveUNIChangeOrder: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save UNIChange order. Reason: " + ex.getMessage());
		}

		log.info("<= EthernetOrderService:saveUNIChangeOrder: END");
		return response;
	}

	private ResponseStatus getEVPLANDesign(String evcName, Transaction tx) {
		//Map<String, Object> result = new HashMap<String, Object>();
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to retrieved EVC design for: " + evcName);

		String aliasCktId = evcName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
		List<Map<String, Object>> evcRoutes = new ArrayList<Map<String, Object>>();

		Map<String, Object> evcDesign = new HashMap<String, Object>();
		//evcDesign.put("evcType", "EVPLAN");
		//evcDesign.put("ncCode", "VLM-");
		//evcDesign.put("isOnMPLS", "true");
		// 1. Get EVC Details basic details.
		String evcQuery = "MATCH (evc:EVCConnection)  "
		        + " WHERE (evc.aliasCktId = '" + aliasCktId+ "' OR evc.serviceName = '" + evcName + "') AND  evc.deletedTimeStamp IS NULL WITH evc "
				+ " MATCH  (evc)-[eRel:AEND|ZEND|VEND]-(uniCkt:UNIConnection)-[uRel:CONNECTED_TO]->(uPort:EquipmentPort)"
				+ "-[upRel:COMPONENT_OF*]->(uDev:Equipment)"
				+ " WHERE evc.deletedTimeStamp IS NULL AND eRel.deletedTimeStamp IS NULL AND uniCkt.deletedTimeStamp IS NULL "
				+ " AND uPort.deletedTimeStamp IS NULL AND uDev.deletedTimeStamp IS NULL "
				+ " AND uRel.deletedTimeStamp is null AND ALL(rel in upRel WHERE rel.deletedTimeStamp IS NULL) "
				+ " MATCH (allDev: allDevices) WHERE allDev.TID = uDev.TID AND allDev.relayrck = uDev.relayrck  "
				+ " RETURN evc as evcInfo, uniCkt as uniCircuit, uPort as uPort, allDev as uDevice, "
				+ " eRel.STAG as sTag, eRel.CTAG as cTag, eRel.evcNci as nci, eRel.evcBandwidth as evcBandwidth, eRel.classOfService as classOfService";

		log.info("Query to get EVC-EVPLAN basic details " + evcQuery);
		try {
			Instant start = Instant.now();
			List<Record> evcInfoList = tx.run(evcQuery).list();
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute inside getEVCDesign(): " + timeElapsed.toMillis() + " ms");
			if (evcInfoList == null || evcInfoList.isEmpty()) {
				log.error("No EVC circuit found with EVC name: " + evcName);
				response.setCode(204);
				response.setMessage("EVC circuit not found with EVC name: " + evcName);
				return response;
			}

			Map<String, Object> evcInfo = new HashMap<String, Object>();
			String oallCTag = "";
			String oallSTag = "";
			for (Record evcRec : evcInfoList) {
				String cTag = evcRec.get("cTag").asString();
				String sTag = evcRec.get("sTag").asString();
				if (StringUtils.isNotBlank(cTag)) {
					oallCTag = cTag;
				}
				if (StringUtils.isNotBlank(sTag)) {
					oallSTag = sTag;
				}
			}

			for (Record evcRec : evcInfoList) {
				Map<String, Object> uniMap = new HashMap<String, Object>();

				Map<String, Object> uniCircuit = new HashMap<String, Object>();

				Map<String, Object> uniDevice = new HashMap<String, Object>();

				if (evcInfo == null || evcInfo.isEmpty())
					evcInfo = evcRec.get("evcInfo").asMap();

				Map<String, Object> uni  = new HashMap<>(evcRec.get("uniCircuit").asMap());
				//uni = evcRec.get("uniCircuit").asMap();
				uni.put("evcNci", evcRec.get("nci").asString());
				String uniStatus = (String)uni.get("status");
				if((StringUtils.equalsIgnoreCase(uniStatus, "In Service")) || (StringUtils.equalsIgnoreCase(uniStatus, "Pending Disconnect"))) {
					uni.put("isNewUni", false);
				}else {
					uni.put("isNewUni", true);
				}
				uni.put("portBasedRateLimited", true);

				Map<String, Object> uniPort = new HashMap<>(convertedPort(evcRec.get("uPort").asMap()));
				//uniPort = evcRec.get("uPort").asMap();
				uniPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) uniPort.get("bw"),true,true));
				uniDevice = evcRec.get("uDevice").asMap();
				String aUniType = (String)uni.get("serviceType");

				if(StringUtils.equals(aUniType, "MEF UNI"))
					uniDevice = convertDevice(uniDevice,true);
				else
					uniDevice = convertDevice(uniDevice,false);

				String cTag = evcRec.get("cTag").asString();
				String sTag = evcRec.get("sTag").asString();

				uniCircuit.put("uni", uni);
				uniCircuit.put("port", uniPort);
				uniCircuit.put("device", uniDevice);
				if (StringUtils.isNotBlank(cTag)) {
					uniCircuit.put("cTag", cTag);
				}else {
					uniCircuit.put("cTag", oallCTag);
				}
				if (StringUtils.isNotBlank(sTag)) {
					uniCircuit.put("sTag", sTag);
				}else {
					uniCircuit.put("sTag", oallSTag);
				}
				uniCircuit.put("evcNci", evcRec.get("nci").asString());
				uniCircuit.put("evcBandwidth", evcRec.get("evcBandwidth").asString());
				uniCircuit.put("classOfService", evcRec.get("classOfService").asString());
				String uniCktId = (String) uni.get("cktid");
				String uniAliasId = (String) uni.get("aliasCktId");
				String uniDevName = (String) uniDevice.get("TID");
				uniMap.put("circuitName", uniCktId);
				uniMap.put("uniCircuit", uniCircuit);

				List<Map<String, Object>> uniRoute = getUniRoute(evcName, uniCktId, uniAliasId, uniDevName, cTag, sTag,tx);

				List<String> nmis = getNMIs("'" + uniCktId + "'", tx);
				List<Map<String, Object>> connInfo = new ArrayList<Map<String, Object>>();

				for (Map<String, Object> conn : uniRoute) {
					String nmiName = (String) conn.get("nniName");
					if (nmis.contains(nmiName)) {
						conn.put("connectionType", "NMI");
					}
					connInfo.add(conn);
				}

				uniMap.put("locationRoute", connInfo);
				evcRoutes.add(uniMap);
			}

			//evcDesign.put("CTAG", oallCTag);
			//evcDesign.put("STAG", oallSTag);
			evcDesign.put("evcConnection", evcInfo);
			evcDesign.put("locations", evcRoutes);
			response.setData(evcDesign);
			response.setCode(200);
			response.setMessage("Successfully found design for EVC: "+evcName);

			log.info("EVC-EVPLAN details:  '{}", evcDesign.toString());
		} catch (Exception ex) {
			log.error("EVC-EVPLAN design Caught Exception: '{}'", ex.getMessage());
			response.setMessage("Failed to find EVC design. Reason: "+ex.getMessage());
		}
		return response;
	}

	private List<Map<String, Object>> getUniRoute(String evcName, String uniCktId, String uniAliasId, String uniDevName,
			String cTag, String sTag, Transaction tx) {

		List<Map<String, Object>> uniRouteList = new ArrayList<Map<String, Object>>();

		String routQuery = "MATCH (evc:EVCConnection)<-[ert:RIDES_ON]-(rt:ROUTE)<-[urt:CONNECTED_TO]"
				+ "-(uni:UNIConnection{cktid:'" + uniCktId + "'}) "
				+ " WHERE (evc.aliasCktId = '" + evcName+ "' OR evc.serviceName = '" + evcName + "') AND "
				+ " evc.deletedTimeStamp IS NULL AND uni.deletedTimeStamp IS NULL" + " RETURN rt.Route as route, rt.Route_ID as routeId";

		try {
			log.info("Query to get UNI route for UNI: " + routQuery);
			List<Record> routeList = tx.run(routQuery).list();

			Record routeRec = routeList.get(0);
			String route = routeRec.get("route").asString();
			String routeId = routeRec.get("routeId").asString();

			List<Record> rtList = getRouteRecords(route, routeId, tx);
			String nextAEndDev = uniDevName;
			int connCounter = 1;
			int ct = rtList.size();
			Map<String, Object> nextDevice = new HashMap<String, Object>();
			while (ct > 0) {

				for (int i = 0; i < rtList.size(); i++) {

					Record rtRec = rtList.get(i);
					// nni as nni, aport as aPort, allADev as aDevice, zport as zPort, allZDev as
					// zDevice
					Map<String, Object> aDev = convertDevice(rtRec.get("aDevice").asMap(),false);
					String aDevName = (String) aDev.get("TID");
					Map<String, Object> zDev = convertDevice(rtRec.get("zDevice").asMap(),false);
					String zDevName = (String) zDev.get("TID");
					Map<String, Object> connMap = new HashMap<String, Object>();

					Map<String, Object> aEndInfo = new HashMap<String, Object>();
					Map<String, Object> zEndInfo = new HashMap<String, Object>();
					Map<String, Object> aPort = new HashMap<>(convertedPort(rtRec.get("aPort").asMap()));
					aPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) aPort.get("bw"),true,true));

					Map<String, Object> zPort = new HashMap<>(convertedPort(rtRec.get("zPort").asMap()));
					zPort.put("portSpeed", ApplicationUtils.bandwidthConvertor((String) zPort.get("bw"),true,true));

					if (StringUtils.equalsIgnoreCase(aDevName, nextAEndDev)) {

						Map<String, Object> nni = rtRec.get("nni").asMap();
						String nniName = (String) nni.get("cktid");

						connMap.put("connection", connCounter++);
						connMap.put("connectionType", "NNI");
						connMap.put("nniName", nniName);
						connMap.put("nniInfo", nni);
						connMap.put("STAG", sTag);
						connMap.put("CTAG", cTag);

						aEndInfo.put("device", aDev);
						aEndInfo.put("deviceName", (String) aDev.get("TID"));
						aEndInfo.put("port", aPort);
						connMap.put("aEndInfo", aEndInfo);
						zEndInfo.put("device", zDev);
						zEndInfo.put("deviceName", (String) zDev.get("TID"));
						zEndInfo.put("port", zPort);
						connMap.put("zEndInfo", zEndInfo);
						uniRouteList.add(connMap);
						rtList.remove(i);
						nextAEndDev = zDevName;
						nextDevice.putAll(zDev);
					} else if (StringUtils.equalsIgnoreCase(zDevName, nextAEndDev)) {

						Map<String, Object> nni = rtRec.get("nni").asMap();
						String nniName = (String) nni.get("cktid");

						connMap.put("connection", connCounter++);
						connMap.put("connectionType", "NNI");
						connMap.put("nniName", nniName);
						connMap.put("nniInfo", nni);
						connMap.put("STAG", sTag);
						connMap.put("CTAG", cTag);

						aEndInfo.put("device", zDev);
						aEndInfo.put("deviceName", (String) zDev.get("TID"));
						aEndInfo.put("port", zPort);
						connMap.put("aEndInfo", aEndInfo);
						zEndInfo.put("device", aDev);
						zEndInfo.put("deviceName", (String) aDev.get("TID"));
						zEndInfo.put("port", aPort);
						connMap.put("zEndInfo", zEndInfo);

						uniRouteList.add(connMap);
						rtList.remove(i);
						nextAEndDev = aDevName;
						nextDevice.putAll(aDev);
					}
					if (rtList.isEmpty()) {
						break;
					}
				}
				ct--;
			}
			Map<String, Object> lastConnMap = new HashMap<String, Object>();
			lastConnMap.put("connection", connCounter++);
			lastConnMap.put("connectionType", "MPLS");
			lastConnMap.put("menId", (String) nextDevice.get("topologyName"));
			Map<String, Object> mplsDevInfo = new HashMap<String, Object>();
			mplsDevInfo.put("device", nextDevice);
			mplsDevInfo.put("deviceName", (String) nextDevice.get("TID"));
			lastConnMap.put("aEndInfo", mplsDevInfo);

			uniRouteList.add(lastConnMap);

		} catch (Exception ex) {
			log.error("UNI Route Caught Exception: '{}'", ex.getMessage());
		}
		return uniRouteList;
	}

	private List<Record> getRouteRecords(String route, String routeId, Transaction tx) {
		String rtQuery = "MATCH (rt:ROUTE{Route_ID:'" + routeId + "'})<-[r1:RIDES_ON]-(nni:NNIConnection)"
				+ " WHERE nni.deletedTimeStamp IS NULL AND rt.deletedTimeStamp IS NULL AND r1.deletedTimeStamp IS NULL "
				+ " with nni "
				+ " OPTIONAL MATCH (nni)-[r2:CONNECTED_TO]->(aport:EquipmentPort{portKey:nni.A_port_key})-[r3:COMPONENT_OF*]->"
				+ "(aDev:Equipment{node_id:aport.node_id}) "
				+ " WHERE r2.deletedTimeStamp IS NULL AND aport.deletedTimeStamp IS NULL "
				+ " AND ALL(rel IN r3 WHERE rel.deletedTimeStamp IS NULL) AND aDev.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (allADev: allDevices) WHERE allADev.TID = aDev.TID AND allADev.relayrck = aDev.relayrck  "
				+ " OPTIONAL MATCH (nni)-[r4:CONNECTED_TO]->(zport:EquipmentPort{portKey:nni.Z_port_key})-[r5:COMPONENT_OF*]->"
				+ "(zDev:Equipment{node_id:zport.node_id})"
				+ " WHERE r4.deletedTimeStamp IS NULL AND zport.deletedTimeStamp IS NULL "
				+ " AND ALL(rel IN r5 WHERE rel.deletedTimeStamp IS NULL) AND zDev.deletedTimeStamp IS NULL "
				+ " OPTIONAL MATCH (allZDev: allDevices) WHERE allZDev.TID = zDev.TID AND allZDev.relayrck = zDev.relayrck  "
				+ " RETURN nni as nni, aport as aPort, allADev as aDevice, zport as zPort, allZDev as zDevice";
		Instant start = Instant.now();
		List<Record> routeRecList = tx.run(rtQuery).list();
		Instant end = Instant.now();
		Duration timeElapsed = Duration.between(start, end);
		log.info("Time taken by query to execute inside getRouteRecords(): "+ timeElapsed.toMillis() +" ms");
		return routeRecList;
	}

	public ResponseStatus saveEVCChangeOrderInfo(Map<String, Object> requestObject) {
		log.info("=>EthernetOrderService:saveEVCChangeOrderInfo: START");
		log.info("Save EVC Change Order Info: {}", requestObject.toString());

		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");
		
		String serviceName = (String) requestObject.get("evcCircuit");
		if (StringUtils.isBlank(serviceName)) {
			response.setCode(400);
			response.setMessage("Missing circuitId in request");
			return response;
		}

		String user = (String) requestObject.get("user");
		if (StringUtils.isBlank(user)) {
			response.setCode(400);
			response.setMessage("Missing user Name in request");
			return response;
		}

		String aliasCktId = serviceName.replaceAll("[^A-Z0-9]", "");

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();

			Map<String, Object> flattenedProperties = new HashMap<>();
			{
				flattenedProperties.put("createdOn", Instant.now().toString());
				flattenedProperties.put("serviceName", serviceName);
				flattenedProperties.put("aliasCktId", aliasCktId);

				if (StringUtils.isNotBlank(user))
					flattenedProperties.put("user", user);
				String tbandwidth = (String) requestObject.get("bandwidth");
				if (StringUtils.isNotBlank(tbandwidth) && !StringUtils.equalsIgnoreCase("null", tbandwidth))
					flattenedProperties.put("bandwidth", tbandwidth);
				String tserviceCos = (String) requestObject.get("serviceCos");
				if (StringUtils.isNotBlank(tserviceCos)&& !StringUtils.equalsIgnoreCase("null", tserviceCos)) {
					flattenedProperties.put("serviceCos", tserviceCos.toUpperCase());
					int pbit;
					switch (tserviceCos.trim().toLowerCase()) {
					case "gold":
						flattenedProperties.put("pbit", "5");
						break;
					case "silver":
						flattenedProperties.put("pbit", "3");
						break;
					case "bronze":
						flattenedProperties.put("pbit", "2");
						break;
					case "best effort":
						flattenedProperties.put("pbit", "0");
						break;
					default:
						log.warn("Invalid serviceCos provided: {}. Skipping QoS update.", tserviceCos);
						pbit = -1;
					}
				}
				String serviceType = (String) requestObject.get("serviceType");
				if (StringUtils.isNotBlank(serviceType) && !StringUtils.equalsIgnoreCase("null", tbandwidth))
					flattenedProperties.put("serviceType", serviceType);

				String serviceId = (String) requestObject.get("serviceId");
				if (StringUtils.isNotBlank(serviceId) && !StringUtils.equalsIgnoreCase("null", serviceId))
					flattenedProperties.put("serviceId", serviceId);
				String evcOrderNumber = (String) requestObject.get("evcOrderNumber");
				if (StringUtils.isNotBlank(evcOrderNumber) && !StringUtils.equalsIgnoreCase("null", evcOrderNumber))
					flattenedProperties.put("evcOrderNumber", evcOrderNumber);

				String subscriberType = (String) requestObject.get("subscriberType");
				if (StringUtils.isNotBlank(subscriberType) && !StringUtils.equalsIgnoreCase("null", subscriberType))
					flattenedProperties.put("subscriberType", subscriberType);
				String acna_ccna_subscriberId = (String) requestObject.get("acna_ccna_subscriberId");
				if (StringUtils.isNotBlank(acna_ccna_subscriberId) && !StringUtils.equalsIgnoreCase("null", acna_ccna_subscriberId))
					flattenedProperties.put("acna_ccna_subscriberId", acna_ccna_subscriberId);

				List<Map<String, Object>> uniList = (List<Map<String, Object>>) requestObject.get("uniList");
//				if (uniList == null || uniList.isEmpty() == true) {
//					response.setCode(400);
//					response.setMessage("Empty uni circuits info in request");
//					return response;
//				}

				// Convert productPayload to JSON string and save as property
				if(uniList != null && !uniList.isEmpty()) {
					ObjectMapper mapper = new ObjectMapper();
					String uniListStr = mapper.writeValueAsString(uniList);
					flattenedProperties.put("uniList", uniListStr);
				}

			}
			StringBuilder mergeQuery = new StringBuilder("MERGE (n:EVCChangeOrder {serviceName: $serviceName}) SET ");
			String propertyAssignments = flattenedProperties.entrySet().stream()
					.filter(entry -> !"serviceName".equals(entry.getKey()))
					.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
					.collect(Collectors.joining(", "));
			mergeQuery.append(propertyAssignments);

			tx.run(mergeQuery.toString(), flattenedProperties);
			tx.commit();

			log.info("EVCChangeOrder saved successfully for: {}", aliasCktId);
		} catch (Exception ex) {
			log.error("Failed to save EVCChangeOrder: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save EVCChangeOrder: " + ex.getMessage());
			return response;
		}

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();
			// Validate the EVC exist.
			// save the EVC order info
			// Check if the request is to update class of service. update class of service.
			// Check if the request is to update cTag on UNIs. Update if available.
			// Validate if the request is to upgrade bandwidth. if allowed update the
			// bandwidth.
			// if action on UNI is to add, add the uni circuit only if the EVC ncCode = VPM-
			// if the action on UNI is to remove, mark the uni to remove.


			String evcQuery = "MATCH (evc:EVCConnection) WHERE (evc.aliasCktId = '" + aliasCktId
					+ "' OR evc.serviceName = '" + serviceName + "') RETURN evc.ncCode as ncCode, evc.name as evcName, evc.provisionStatus AS provisionStatus ";

			List<Record> evcList = tx.run(evcQuery).list();

			if (evcList == null || evcList.isEmpty()) {
				log.error("No EVC circuit found with EVC name: " + serviceName);
				response.setCode(500);
				response.setMessage("No EVC circuit found with EVC name: " + serviceName);
				return response;
			}

			Record evcRec = evcList.get(0);
			String ncCode = evcRec.get("ncCode").asString();
			String evcName = evcRec.get("evcName").asString();
			String provisionStatus = evcRec.get("provisionStatus").isNull()
			        ? ""
			        : evcRec.get("provisionStatus").asString();
			boolean isInService = "IN SERVICE".equalsIgnoreCase(provisionStatus);

			String bandwidth = (String) requestObject.get("bandwidth");
            String serviceCos = (String) requestObject.get("serviceCos");

			if (StringUtils.isNotBlank(serviceCos)) {
				String pbit;
				switch (serviceCos.trim().toLowerCase()) {
				case "gold":
					pbit = "5";
					break;
				case "silver":
					pbit = "3";
					break;
				case "bronze":
					pbit = "2";
					break;
				case "best effort":
					pbit = "0";
					break;
				default:
					log.warn("Invalid serviceCos provided: {}. Skipping QoS update.", serviceCos);
					pbit = "-1";
				}
				if (!StringUtils.equals("-1", pbit)) {
					// Update in EVCConnection
					String updateEvcCosQuery = "MATCH (n:EVCConnection) WHERE n.serviceName = $evcName OR n.aliasCktId = $aliasCktId "
							+ "SET n.serviceCos = $serviceCos, n.pbit = $pbit, n.updatedBy = $updatedBy,  n.updatedOn = $updatedOn  RETURN n LIMIT 1";
					tx.run(updateEvcCosQuery, Map.of("evcName", evcName, "aliasCktId", aliasCktId, "serviceCos",
							serviceCos, "pbit", pbit, "updatedBy", user, "updatedOn", Instant.now().toString()));
					log.info("Updated serviceCos and pbit in EVCConnection: {}, {}", serviceCos, pbit);

					// Update in allServices
					String updateAllServicesCosQuery = "MATCH (n:allServices) WHERE n.serviceName = $evcName OR n.aliasCktId = $aliasCktId "
							+ "SET n.serviceCos = $serviceCos, n.pbit = $pbit, n.updatedBy = $updatedBy,  n.updatedOn = $updatedOn RETURN n LIMIT 1";
					tx.run(updateAllServicesCosQuery, Map.of("evcName", evcName, "aliasCktId", aliasCktId, "serviceCos",
							serviceCos, "pbit", pbit,  "updatedBy", user, "updatedOn", Instant.now().toString()));
					log.info("Updated serviceCos and pbit in allServices: {}, {}", serviceCos, pbit);

				}
			}
            
			if (StringUtils.isNotBlank(bandwidth)) {
				try {
			        Map<String, Object> validateRequest = new HashMap<>();
			        validateRequest.put("circuitName", serviceName);
			        validateRequest.put("requriedCapacity", bandwidth);

			        ResponseStatus bandwidthValidation = validateEVCSpeed(validateRequest);
			        if (bandwidthValidation.getCode() == 200 && bandwidthValidation.getData() != null) {
			            Map<String, Object> bwData = (Map<String, Object>) bandwidthValidation.getData();
			            String isBandwidthAvailable = (String) bwData.getOrDefault("isBandWidthAvaialble", "N");

			            if ("Y".equalsIgnoreCase(isBandwidthAvailable)) {
			                // Update bandwidth in EVCConnection
			                String updateEvcBWQuery = "MATCH (n:EVCConnection) WHERE n.serviceName = $evcName OR n.aliasCktId = $aliasCktId "
			                        + "SET n.bandwidth = $bandwidth, n.updatedBy = $updatedBy,  n.updatedOn = $updatedOn RETURN n LIMIT 1";
			                tx.run(updateEvcBWQuery, Map.of("evcName", evcName, "aliasCktId", aliasCktId, "bandwidth", bandwidth,
			                		"updatedBy", user, "updatedOn", Instant.now().toString()));
			                log.info("Updated bandwidth in EVCConnection: {}", bandwidth);

			                // Update bandwidth in allServices
			                String updateAllServicesBWQuery = "MATCH (n:allServices) WHERE n.serviceName = $evcName OR n.aliasCktId = $aliasCktId "
			                        + "SET n.bandwidth = $bandwidth, n.updatedBy = $updatedBy, n.updatedOn = $updatedOn  RETURN n LIMIT 1";
			                tx.run(updateAllServicesBWQuery, Map.of("evcName", evcName, "aliasCktId", aliasCktId, "bandwidth", bandwidth,
			                		"updatedBy", user, "updatedOn", Instant.now().toString()));
			                log.info("Updated bandwidth in allServices: {}", bandwidth);
			            } else {
			                log.warn("Requested bandwidth is not available for EVC: {}", serviceName);
			                response.setCode(400);
			                response.setMessage("Requested bandwidth is not available for the EVC");
			                return response;
			            }
			        } else {
			            log.warn("Bandwidth validation failed for EVC: {}", serviceName);
			            response.setCode(400);
			            response.setMessage("Unable to validate bandwidth availability");
			            return response;
			        }
			    } catch (Exception e) {
			        log.error("Exception while validating/updating bandwidth: {}", e.getMessage(), e);
			        response.setCode(500);
			        response.setMessage("Error while validating/updating bandwidth: " + e.getMessage());
			        return response;
			    }
			}
			List<Map<String, Object>> uniList = new ArrayList<Map<String, Object>>(
					(List<Map<String, Object>>) requestObject.get("uniList"));
			if (uniList == null || uniList.isEmpty()) {
				tx.commit();
				log.info("Execution completed. UNI list is empty in the request for EVC name: " + serviceName);
				response.setCode(200);
				response.setMessage("EVC circuit update completed for EVC name: " + serviceName);
				return response;
			}

			for (Map<String, Object> uniInfo : uniList) {

			    String action = (String) uniInfo.get("action");
			    if (StringUtils.equalsIgnoreCase(action, "change")) {

			        String circuitName = (String) uniInfo.get("circuitName");
			        String uniAliasCktId = circuitName.replaceAll("[^A-Z0-9]", "");

			        // Validate cTag_start
			        if (uniInfo.containsKey("cTag_start")) {
			            String cTag = (String) uniInfo.get("cTag_start");
			            if (StringUtils.isBlank(cTag)) {
			                log.error("cTag property exists but is blank or null for UNI circuit: {}", circuitName);
			                response.setCode(500);
			                response.setMessage("cTag property exists but is blank or null for UNI circuit: " + circuitName);
			                return response;
			            }
			        }

			        // Validate nci
			        if (uniInfo.containsKey("nci")) {
			            String nci = (String) uniInfo.get("nci");
			            if (StringUtils.isBlank(nci)) {
			                log.error("nci property exists but is blank or null for UNI circuit: {}", circuitName);
			                response.setCode(500);
			                response.setMessage("nci property exists but is blank or null for UNI circuit: " + circuitName);
			                return response;
			            }
			        }

					// Validate bandwidth
					if (uniInfo.containsKey("bandwidth")) {
						String bandWidth = (String) uniInfo.get("bandwidth");
						if (StringUtils.isBlank(bandWidth)) {
							log.error("bandwidth property exists but is blank or null for UNI circuit: {}",
									circuitName);
							response.setCode(500);
							response.setMessage("bandwidth property exists but is blank or null for UNI circuit: "
									+ circuitName);
							return response;
						}
						
						 // --- Bandwidth availability check ---
					    ResponseStatus bwResponse = getUniBandwdith(circuitName);
					    if (bwResponse.getCode() != 200) {
					        log.error("Failed to fetch bandwidth details for UNI: {}", circuitName);
					        response.setCode(bwResponse.getCode());
					        response.setMessage(bwResponse.getMessage());
					        return response;
					    }

					    Map<String, Object> bwData = (Map<String, Object>) bwResponse.getData();
					    long availableBW = (long) Double.parseDouble(bwData.get("availableBandwidth").toString());
					    long requestedBW = (long) Double.parseDouble(ApplicationUtils.bandwidthConvertor(bandWidth, true, false));

					    if (availableBW < requestedBW) {
					        log.error("Insufficient bandwidth for UNI: {}. Requested: {} , Available: {} ",
					                circuitName, requestedBW, availableBW);
					        response.setCode(409);
					        response.setMessage("Insufficient bandwidth on UNI circuit: " + circuitName +
					                " (Requested: " + requestedBW + " , Available: " + availableBW + " )");
					        return response;
					    }

					    log.info("UNI {} has sufficient bandwidth. Requested: {} Mbps, Available: {} Mbps",
					            circuitName, requestedBW, availableBW);
					
					}

					// Validate classOfService
					if (uniInfo.containsKey("serviceCos")) {
						String classOfService = (String) uniInfo.get("serviceCos");
						if (StringUtils.isBlank(classOfService)) {
							log.error("serviceCos property exists but is blank or null for UNI circuit: {}",
									circuitName);
							response.setCode(500);
							response.setMessage("serviceCos property exists but is blank or null for UNI circuit: "
									+ circuitName);
							return response;
						}
					}

			        String cTag = (String) uniInfo.get("cTag_start");
			        String ctagProp = propName("CTAG", isInService);
			        if (cTag != null) {
			            // Check if any other EVC is using the same cTag on this UNI
			            String ctagCheckQuery =
			                " MATCH (evc:EVCConnection)-[r:AEND|ZEND|VEND]-(uni:UNIConnection) " +
			                " WHERE toUpper(uni.aliasCktId) = toUpper($uniAliasCktId) " +
			                "   AND r.deletedTimeStamp IS NULL " +
			                "   AND evc.deletedTimeStamp IS NULL " +
			                "   AND toUpper(evc.aliasCktId) <> toUpper($aliasCktId) " +
			                "   AND r.CTAG = $requestedCtag " +
			                " RETURN evc.serviceName as conflictingEVC";

			            Map<String, Object> ctagParams = Map.of(
			                "uniAliasCktId", uniAliasCktId,
			                "aliasCktId", aliasCktId,
			                "requestedCtag", cTag
			            );

			            List<Record> ctagConflicts = tx.run(ctagCheckQuery, ctagParams).list();

			            if (ctagConflicts != null && !ctagConflicts.isEmpty()) {
			                String conflictEvc = ctagConflicts.get(0).get("conflictingEVC").asString();
			                log.error("cTag {} is already in use by EVC: {}", cTag, conflictEvc);
			                response.setCode(409);
			                response.setMessage("cTag " + cTag + " is already in use by EVC: " + conflictEvc);
			                return response;
			            }

			            // Update cTag in the relationship between EVC and UNI
			            String updateCtagQuery =
			                " MATCH (evc:EVCConnection)-[r:AEND|ZEND|VEND]-(uni:UNIConnection) " +
			                " WHERE (evc.aliasCktId = $aliasCktId OR evc.serviceName = $evcName) " +
			                "   AND toUpper(uni.circuitName) = toUpper($uniCktName) " +
			                " SET r." + ctagProp + " = $requestedCtag " +
			                " RETURN r";

			            Map<String, Object> updateParams = Map.of(
			                "aliasCktId", aliasCktId,
			                "evcName", evcName,
			                "uniCktName", circuitName,
			                "requestedCtag", cTag
			            );

			            tx.run(updateCtagQuery, updateParams);
			            log.info("Successfully updated cTag {} for UNI circuit: {}", cTag, circuitName);
			        }
			        
					String nci = (String) uniInfo.get("nci");
					String nciProp = propName("evcNci", isInService);
					if (nci != null) {
						// Update nci in the relationship between EVC and UNI
						String updateNciQuery = " MATCH (evc:EVCConnection)-[r:AEND|ZEND|VEND]-(uni:UNIConnection) "
								+ " WHERE (evc.aliasCktId = $aliasCktId OR evc.serviceName = $evcName) "
								+ "   AND toUpper(uni.aliasCktId) = toUpper($uniAliasCktId) " + " SET r." + nciProp + " = $nci "
								+ " RETURN r";

						Map<String, Object> updateParams = Map.of("aliasCktId", aliasCktId, "evcName", evcName,
								"uniAliasCktId", uniAliasCktId, "nci", nci);

						tx.run(updateNciQuery, updateParams);
						log.info("Successfully updated evcNci {} for UNI circuit: {}", nci, circuitName);
					}

					String classOfService = (String) uniInfo.get("serviceCos");
					String cosProp = propName("classOfService", isInService);
					if (classOfService != null) {
						// Update classOfService in the relationship between EVC and UNI
						String updateCosQuery = " MATCH (evc:EVCConnection)-[r:AEND|ZEND|VEND]-(uni:UNIConnection) "
								+ " WHERE (evc.aliasCktId = $aliasCktId OR evc.serviceName = $evcName) "
								+ "   AND toUpper(uni.aliasCktId) = toUpper($uniAliasCktId) "
								+ " SET r." + cosProp + " = $classOfService " + " RETURN r";

						Map<String, Object> updateParams = Map.of("aliasCktId", aliasCktId, "evcName", evcName,
								"uniAliasCktId", uniAliasCktId, "classOfService", classOfService);

						tx.run(updateCosQuery, updateParams);
						log.info("Successfully updated classOfService {} for UNI circuit: {}", classOfService,
								circuitName);
					}
					
					String bandWidth = (String) uniInfo.get("bandwidth");
					String bwProp = propName("evcBandwidth", isInService);
					if (bandWidth != null) {
						// Update bandwidth in the relationship between EVC and UNI
						String updateBandwidthQuery = " MATCH (evc:EVCConnection)-[r:AEND|ZEND|VEND]-(uni:UNIConnection) "
								+ " WHERE (evc.aliasCktId = $aliasCktId OR evc.serviceName = $evcName) "
								+ "   AND toUpper(uni.aliasCktId) = toUpper($uniAliasCktId) "
								+ " SET r." + bwProp + " = $bandWidth " + " RETURN r";

						Map<String, Object> updateParams = Map.of("aliasCktId", aliasCktId, "evcName", evcName,
								"uniAliasCktId", uniAliasCktId, "bandWidth", bandWidth);

						tx.run(updateBandwidthQuery, updateParams);
						log.info("Successfully updated evcBandwidth {} for UNI circuit: {}", bandWidth,
								circuitName);
					}

			    } else if (StringUtils.equalsIgnoreCase(action, "add")) {
			        // TBD: Add new location to the EVC.
			    } else if (StringUtils.equalsIgnoreCase(action, "remove")) {
			        // TBD: Remove the location from the EVC
			    }
			}

			tx.commit();
			response.setCode(200);
			response.setMessage("EVC Change Order updated successfully.");

		} catch (ServiceUnavailableException ex) {
			log.error("Service unavailable: {}", ex.getMessage());
			response.setCode(404);
			response.setMessage("Service unavailable: " + ex.getMessage());
			response.setMessage("Failed to update EVC Change Order. Service unavailable.");
		} catch (Exception ex) {
			log.error("Error saving EthernetChangeOrder: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to update EVC Change Order: " + ex.getMessage());
			response.setMessage("Failed to update EVC Change Order.");
		}
		return response;
	}
	
	private String propName(String baseProp, boolean isInService) {
	    return isInService ? baseProp + "_new" : baseProp;
	}

	private String updateCTag(String evcAliasCktId, Map<String, Object> uniInfo, Transaction tx) {
		String result = "FAIL";

		String circuitName = (String) uniInfo.get("circuitName");
		String uniAliasCktId = circuitName.replaceAll("[^A-Z0-9]", "");
		String cTagQuery = "MATCH (uni:UNIConnection)-[rel:AEND|ZEND|VEND]-(evc:EVCConnection) WHERE "
				+ " (uni.aliasCktId = '" + uniAliasCktId + "' OR uni.cktid = '" + circuitName
				+ "') return rel.CTAG as CTAG";

		List<Record> cTagList = tx.run(cTagQuery).list();

		return result;
	}
	
	public ResponseStatus validateEVCSpeed(Map<String, Object> validateRequest) {

		String cktName = (String) validateRequest.get("circuitName");
		log.info("validateEVCSpeed for: {}", cktName);

		Object capacityObj = validateRequest.get("requiredCapacity");
		long requestedCapacity;

		try {
			 if (capacityObj instanceof Double) {
			        requestedCapacity = ((Double) capacityObj).longValue();
			    } else if (capacityObj instanceof Long) {
			        requestedCapacity = (Long) capacityObj;
			    } else if (capacityObj instanceof Number) {
			        requestedCapacity = ((Number) capacityObj).longValue();
			    } else {
			        requestedCapacity = Long.parseLong(capacityObj.toString());
			    }
		} catch (Exception e) {
			log.error("Invalid 'requiredCapacity' value: {}", capacityObj);
			ResponseStatus error = new ResponseStatus();
			error.setCode(400);
			error.setMessage("Invalid required capacity");

			Map<String, Object> errorData = new HashMap<>();
			error.setData(errorData);

			return error;
		}

		ResponseStatus response = new ResponseStatus();
		response.setCode(200);
		response.setMessage("Successfully Validated EVC Speed");
		
		Map<String, Object> capacityInfo = new LinkedHashMap<>();
		capacityInfo.put("evcName", cktName);
		capacityInfo.put("requiredCapacity", requestedCapacity);

		Map<String, Object> resultData = new HashMap<>();	
	    List<Map<String, Object>> uniResults = new ArrayList<>();
	    
		String aliasCktId = cktName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		// Query for EVC
		String evcExistQuery = "MATCH (evc:EVCConnection) WHERE (evc.aliasCktId = '" + aliasCktId
				+ "' OR evc.serviceName = '" + cktName + "') "
				+ "AND evc.deletedTimeStamp IS NULL RETURN COUNT(evc) AS evcCount";

		// Query for UNIs linked to that EVC
		String evcUniQuery = "MATCH (evc:EVCConnection) WHERE (evc.aliasCktId = '" + aliasCktId
				+ "' OR evc.serviceName = '" + cktName + "') AND evc.deletedTimeStamp IS NULL WITH evc "
				+ "MATCH (evc)-[r1:AEND|ZEND|VEND]-(uni:UNIConnection) "
				+ "WHERE r1.deletedTimeStamp IS NULL AND uni.deletedTimeStamp IS NULL "
				+ "RETURN uni, evc.bandwidth as evcBandwidth";

		log.info("EVC Existence Query: {}", evcExistQuery);
		log.info("EVC UNI Query: {}", evcUniQuery);

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();
			// Check if EVC exists
			Record evcExistRecord = tx.run(evcExistQuery).single();
			long evcCount = evcExistRecord.get("evcCount").asLong(0);
			if (evcCount == 0) {
				log.warn("EVC not found for circuit: {}", cktName);
				response.setCode(404);
				response.setMessage("EVC not found: " + cktName);
				tx.commit();
				return response;
			}
			// Fetch UNIs associated with EVC
			List<Record> uniInfoList = tx.run(evcUniQuery).list();
			if (uniInfoList == null || uniInfoList.isEmpty()) {
				log.warn("EVC found but no UNI connected for circuit: {}", cktName);
				response.setCode(204);
				response.setMessage("EVC found but no UNI connections available for circuit: " + cktName);
				tx.commit();
				return response;
			}
			for (Record record : uniInfoList) {
			    Map<String, Object> uni = record.get("uni").asMap();
			    String uniCktName = (String) uni.getOrDefault("cktid", "");

			    ResponseStatus bandwidthResponse = getUniBandwdith(uniCktName);
			    if (bandwidthResponse.getCode() != 200)
			        continue;

			    Map<String, Object> bwData = (Map<String, Object>) bandwidthResponse.getData();

			    Object availableBWObj = bwData.getOrDefault("availableBandwidth", 0L);
			    long availableBW = 0L;
			    if (availableBWObj instanceof Number) {
			        availableBW = ((Number) availableBWObj).longValue();
			    } else {
			        availableBW = Long.parseLong(availableBWObj.toString());
			    }
			    
			    long usedBW = bwData.getOrDefault("usedBandwidth", 0L) instanceof Number
	                    ? ((Number) bwData.getOrDefault("usedBandwidth", 0L)).longValue()
	                    : Long.parseLong(bwData.get("usedBandwidth").toString());

	            long actualBW = bwData.getOrDefault("actualBandwidth", 0L) instanceof Number
	                    ? ((Number) bwData.getOrDefault("actualBandwidth", 0L)).longValue()
	                    : Long.parseLong(bwData.get("actualBandwidth").toString());

			    String evcBW = record.get("evcBandwidth").asString();
			    if (StringUtils.isNotBlank(evcBW)) {
			        evcBW = ApplicationUtils.bandwidthConvertor(evcBW, true, false);
			        availableBW += Long.parseLong(evcBW);
			    }

			    Map<String, Object> uniResult = new LinkedHashMap<>();
	            uniResult.put("uniCircuitName", uniCktName);
	            uniResult.put("availableBandwidth", availableBW);
				uniResult.put("usedBandwidth", usedBW);
				uniResult.put("actualBandwidth", actualBW);
				uniResult.put("isBandwidthAvailable", availableBW >= requestedCapacity ? "Y" : "N");

				uniResults.add(uniResult);
			}
			tx.commit();

		} catch (Exception ex) {
			log.error("Exception during validation: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to validate EVC speed : " + ex.getMessage());
		}
		capacityInfo.put("locations", uniResults);
		resultData.put("capacityInfo", capacityInfo);
	    response.setData(resultData);
		return response;
	}
	
	public Map<String, Object> getEquipmentInfo(String deviceName) {
	    Map<String, Object> result = equipmentRepository.findEquipmentInfo(deviceName);

	    if (result == null || result.isEmpty() || result.get("deviceInfo") == null) {
	        throw new EquipmentNotFoundException("No equipment found with name: " + deviceName);
	    }

	    Map<String, Object> deviceInfo = (Map<String, Object>) result.get("deviceInfo");
	    List<Map<String, Object>> ports = (List<Map<String, Object>>) result.get("ports");
	    Boolean isNID = (Boolean) result.get("isNID");
	    List<String> partNumbers = (List<String>) result.get("partNumbers");
	    Map<String, Object> convertedDeviceInfo	= convertDevice(deviceInfo,isNID);
	    Map<String, Object> response = new LinkedHashMap<>();
	    response.put("EquipmentInfo", convertedDeviceInfo);
	    

	    //  Equipment found but no ports
	    if (ports == null || ports.isEmpty()) {
	       // response.put("ports", Collections.emptyList());
	        response.put("partNumbers", Collections.emptyList());
	        response.put("message", "Equipment found, but no ports linked.");
	        return response;
	    }

	    //  Ports found but no parts
	    if (partNumbers == null || partNumbers.isEmpty()) {
	        response.put("partNumbers", Collections.emptyList());
	        response.put("message", "Ports found, but no part numbers found.");
	        return response;
	    }

	    //  Everything found
	    response.put("partNumbers", partNumbers);
	    response.put("message", "Equipment, ports and part numbers found successfully.");
	    return response;
	}

	public ResponseStatus updateVpnIdToEVCService(Map<String, Object> request) {
		ResponseStatus responseStatus;

		responseStatus = validateVpnIdToEVCServiceRequest(request);
		if(responseStatus != null)
			return responseStatus;

		String serviceName = request.getOrDefault("serviceName","").toString();
		String vpnId = request.getOrDefault("vpnId","").toString();

		try {
			boolean isEvcExists = isEvcExistsInAllServicesAndEVCConnection(serviceName);
			if(!isEvcExists) {
				responseStatus = new ResponseStatus();
				responseStatus.setCode(404);
				responseStatus.setMessage("provided serviceName is either not found or not properly configured in database");
				return responseStatus;
			}
		} catch (Exception e) {
			responseStatus = new ResponseStatus();
			log.error("Exception found during the check for EVC: {}", e.getMessage(), e);
			responseStatus.setCode(500);
			responseStatus.setMessage("Failed to validate EVC service : " + e.getMessage());
			return responseStatus;
		}

		try {
			responseStatus = updateVpnId(serviceName, vpnId);
		} catch (Exception e) {
			responseStatus = new ResponseStatus();
			log.error("Exception found during update for VPN ID to EVC: {}", e.getMessage(), e);
			responseStatus.setCode(500);
			responseStatus.setMessage("Failed to update EVC service : " + e.getMessage());
			return responseStatus;
		}
		return responseStatus;
	}

	private ResponseStatus validateVpnIdToEVCServiceRequest(Map<String, Object> request) {
		ResponseStatus responseStatus;
		String serviceName = request.getOrDefault("serviceName", "").toString();
		String vpnId = request.getOrDefault("vpnId", "").toString();

		if(StringUtils.isEmpty(serviceName)) {
			responseStatus = new ResponseStatus();
			responseStatus.setCode(400);
			responseStatus.setMessage("serviceName is required.");
			return responseStatus;
		}

		if(StringUtils.isEmpty(vpnId)) {
			responseStatus = new ResponseStatus();
			responseStatus.setCode(400);
			responseStatus.setMessage("vpnId is required.");
			return responseStatus;
		}

		return null;
	}

	private boolean isEvcExistsInAllServicesAndEVCConnection(String serviceName) {
		String aliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		String allSvcsQuery = "MATCH (evc:allServices) WHERE (evc.aliasCktId = '" + aliasCktId
				+ "' OR evc.serviceName = '" + serviceName + "') AND evc.deletedTimeStamp IS NULL RETURN evc";

		log.info("allServices Query: {}", allSvcsQuery);

		String evcQuery = "MATCH (evc:EVCConnection) WHERE (evc.aliasCktId = '" + aliasCktId
				+ "' OR evc.serviceName = '" + serviceName + "') AND evc.deletedTimeStamp IS NULL RETURN evc";

		log.info("EVC Query: {}", evcQuery);

		boolean isExistsInAllSvcs = false;
		boolean isExistsInEVCCon = false;

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();
			Instant start = Instant.now();
			List<Record> allSvcsList = tx.run(allSvcsQuery).list();
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute EVCExistsInAllServices : " + timeElapsed.toMillis() + " ms");

			if(allSvcsList != null && !allSvcsList.isEmpty())
				isExistsInAllSvcs = true;

			start = Instant.now();
			List<Record> evcList = tx.run(evcQuery).list();
			end = Instant.now();
			timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute EVCExistsInEVCConnection : " + timeElapsed.toMillis() + " ms");

			if(evcList != null && !evcList.isEmpty())
				isExistsInEVCCon = true;

            return isExistsInAllSvcs && isExistsInEVCCon;

        } catch (Exception ex) {
			log.error("Exception during isEvcExistsInAllServicesAndEVCConnection: {}", ex.getMessage());
			throw ex;
		}
	}

	private ResponseStatus updateVpnId(String serviceName, String vpnId) {
		ResponseStatus responseStatus;
		String aliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

		String allSvcQuery = "CALL { " + "MATCH (alSvc:allServices) " + "WHERE (alSvc.aliasCktId = $aliasCktId OR alSvc.serviceName = $serviceName) "
				+ "AND alSvc.deletedTimeStamp IS NULL " + "SET alSvc.vpnId = $vpnId " + "} "
				+ "CALL { " + "MATCH (evc:EVCConnection) " + "WHERE (evc.aliasCktId = $aliasCktId OR evc.serviceName = $serviceName) "
				+ "AND evc.deletedTimeStamp IS NULL " + "SET evc.vpnId = $vpnId " + "}";

		log.info("execute updateVpnId() allSvcEVC Query: {}", allSvcQuery);

		try (Session session = driver.session()) {
			Transaction tx = session.beginTransaction();
			Instant start = Instant.now();
			tx.run(allSvcQuery, parameters("aliasCktId", aliasCktId,
					"serviceName", serviceName,
					"vpnId", vpnId)).list();
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			log.info("Time taken by query to execute updateVpnId() : " + timeElapsed.toMillis() + " ms");
			tx.commit();
			responseStatus = new ResponseStatus();
			responseStatus.setCode(200);
			responseStatus.setMessage("Updated VPNId to EVCConnection and allServices successfully.");
		} catch (Exception ex) {
			log.error("Exception during updateVpnId(): {}", ex.getMessage());
			throw ex;
		}
		return responseStatus;
	}

    public ResponseStatus updateEquipment(Map<String, Object> equipmentInfo) {
        log.info("=> updateEquipment(): START");

        ResponseStatus responseStatus = new ResponseStatus();
        responseStatus.setCode(500); // Default to error
        responseStatus.setMessage("Failed");

        // Validate mandatory fields
        String deviceName = (String) equipmentInfo.get("deviceName");
        String nodeId = (String) equipmentInfo.get("nodeId");
        String ipAddress = (String) equipmentInfo.get("ipAddress");
        String macAddress = (String) equipmentInfo.get("macAddress");
        String topologyName = (String) equipmentInfo.get("topologyName");

        if (deviceName == null || deviceName.trim().isEmpty()) {
            responseStatus.setCode(500);
            responseStatus.setMessage("DeviceName is empty.");
            log.warn("Validation failed: DeviceName is empty");
            return responseStatus;
        }

        Session session = null;
        Transaction tx = null;

		try {
			session = driver.session();
			tx = session.beginTransaction();

			deviceName = deviceName.trim();
			String eqmtQuery = "MATCH (eq:Equipment{TID:'" + deviceName + "'}) RETURN eq.TID AS deviceName LIMIT 1";

			Result eqRes = tx.run(eqmtQuery);
			if (!eqRes.hasNext()) {
				responseStatus.setCode(500);
				responseStatus.setMessage("Device '" + deviceName + "'not available.");
				log.warn("Validation failed: Device '{}'not available.", deviceName);
				return responseStatus;

			}
			// Verify IP address, mac address is not assigned to any other equipment.

			String updateQuery = "MATCH (eq:Equipment) WHERE eq.TID = '" + deviceName + "'"
					+ " MATCH (ad:allDevices{TID:eq.TID}) ";
			boolean propAvailable = false;
			if (StringUtils.isNotBlank(ipAddress)) {
				ipAddress = ipAddress.trim();
				String ipAddrQuery = "MATCH (eq:Equipment{IPV4MGMROUTERID : '" + ipAddress+ "'}) "
						+ "RETURN eq.TID AS deviceName LIMIT 1";
				Result ipRes = tx.run(ipAddrQuery);
				if (ipRes.hasNext()) {
					Record element = ipRes.next();
					String equipmentName = element.get("deviceName").asString();
					if (!StringUtils.equals(equipmentName, deviceName)) {
						responseStatus.setCode(500);
						responseStatus.setMessage(
								"Another device '" + equipmentName + "' contains the IPAddress: '" + ipAddress + "'.");
						log.warn("Validation failed : Another device '{}' contains the IPAddress: '{}'.", equipmentName,
								ipAddress);
						return responseStatus;
					}
				} else {
					updateQuery += "SET eq.IPV4MGMROUTERID = '" + ipAddress + "', ad.IPV4MGMROUTERID = '"
							+ ipAddress + "' ";
					propAvailable = true;
				}
			}
			if (StringUtils.isNotBlank(macAddress)) {
				macAddress = macAddress.trim();
				String macAddrQuery = "MATCH (eq:Equipment{MACADDRESS : '" + macAddress+ "'}) "
						+ "RETURN eq.TID AS deviceName LIMIT 1";
				Result macRes = tx.run(macAddrQuery);
				if (macRes.hasNext()) {
					Record element = macRes.next();
					String equipmentName = element.get("deviceName").asString();
					if (!StringUtils.equals(equipmentName, deviceName)) {
						responseStatus.setCode(500);
						responseStatus.setMessage("Another device '" + equipmentName + "' contains the MACADDRESS: '"
								+ macAddress + "'.");
						log.warn("Validation failed : Another device '{}' contains the MACADDRESS: '{}'.",
								equipmentName, macAddress);
						return responseStatus;
					}
				} else {
					if (!propAvailable) {
						updateQuery += "SET eq.MACADDRESS = '" + macAddress + "', ad.MACADDRESS = '" + macAddress + "' ";
					} else {
						updateQuery += ", eq.MACADDRESS = '" + macAddress + "', ad.MACADDRESS = '" + macAddress + "' ";
					}
					propAvailable = true;
				}
			}
			if (StringUtils.isNotBlank(topologyName)) {
				topologyName = topologyName.trim();
				if (!propAvailable) {
					updateQuery += "SET eq.topologyName = '" + topologyName + "', ad.topologyName = '"
							+ topologyName + "' ";
				} else {
					updateQuery += ", eq.topologyName = '" + topologyName + "', ad.topologyName = '" 
				            + topologyName+ "' ";
				}
				propAvailable = true;
			}

			if (propAvailable) {
				updateQuery += ", eq.lastUpdated = dateTime(), ad.lastUpdated = dateTime() RETURN eq.TID";

				log.info(" Equipment update Query {}", updateQuery);

				Result updateResult = tx.run(updateQuery);
				tx.commit();

				responseStatus.setCode(200);
				responseStatus.setMessage("Equipment '" + deviceName + "' updated successfully");
				responseStatus.setData("Equipment '" + deviceName + "' updated successfully");
			}else {
				responseStatus.setCode(200);
				responseStatus.setMessage("Requested data on Equipment '" + deviceName + "' is already available");
				responseStatus.setData("Requested data on Equipment '" + deviceName + "' is already available");
			}
			log.info("Successfully updated equipment '{}'", deviceName);

		} catch (Exception ex) {
			if (tx != null) {
				tx.rollback();
			}
			log.error("Error updating equipment: {}", ex.getMessage());
			responseStatus.setCode(500);
			responseStatus.setMessage("Error updating equipment: " + ex.getMessage());
		} finally {
			if (session != null) {
				session.close();
			}
		}

		log.info("<= updateEquipment(): END");
		return responseStatus;
	}

    public ResponseStatus updateCircuitProperties(Map<String, Object> request) {
	    ResponseStatus response = new ResponseStatus();

	    log.info("Starting updateCircuitProperties() with request: {}", request);
	    Map<String, Object> networkConnection = (Map<String, Object>) request.get("networkConnection");
	    Map<String, Object> customerConnection = (Map<String, Object>) request.get("customerConnection");
	    Boolean useExistingConnecting = (Boolean) request.get("useExistingConnecting");
	    
	    String updateNW = (String) request.getOrDefault("updateNetworkConnection", "N");
	    String updateCUS = (String) request.getOrDefault("updateCustomerConnection", "N");
		
		log.info("updateNetworkConnection flag: {}", updateNW);
	    log.info("updateCustomerConnection flag: {}", updateCUS);
		
		Map<String, Object> nniPayload = null;
		Map<String, Object> uniPayload = null;

		if ("Y".equalsIgnoreCase(updateNW)) {

			nniPayload = buildNNIParams(request);
		    if (!nniPayload.containsKey("sourcePortNum") || !nniPayload.containsKey("destPortNum")) {
		    	String customerEnd = (String) ((Map<String, Object>) request.get("networkConnection")).getOrDefault("customerEnd", "");
		        String cktId = (String) ((Map<String, Object>) request.get("networkConnection")).getOrDefault("connectionName", "");
		        nniPayload = populatePortsFromNMI(nniPayload, customerEnd, cktId);	    	
		    }
		}
		if ("Y".equalsIgnoreCase(updateCUS)) {
		    uniPayload = buildUNIParams(request);
		}

	    Session session = driver.session();
	    try (Transaction tx = session.beginTransaction()) {

	        // Update NNI if updateNW = Y 	
			if ("Y".equalsIgnoreCase(updateNW) && !useExistingConnecting) {
				log.info("Starting NNI update...");
				boolean nniUpdated = updateNNIProperties(nniPayload);
				if (!nniUpdated) {
					tx.rollback();
					response.setCode(500);
					response.setMessage("Failed to update NNI properties.");
					return response;
				}
			}

			// Update UNI if updateCUS = Y
			if ("Y".equalsIgnoreCase(updateCUS)) {
				log.info("Starting UNI update...");
				boolean uniUpdated = updateUNIProperties(uniPayload);
				if (!uniUpdated) {
					tx.rollback();
					response.setCode(500);
					response.setMessage("Failed to update UNI properties.");
					return response;
				}
			}

			if ("Y".equalsIgnoreCase(updateNW) && "Y".equalsIgnoreCase(updateCUS)) {
				try {
					checkNMIXconnect(nniPayload, uniPayload, tx);
				} catch (Exception e) {
					tx.rollback();
					log.error("Error in NMI XCONNECT check: {}", e.getMessage(), e);
					response.setCode(500);
					response.setMessage("Error in NMI XCONNECT: " + e.getMessage());
					return response;
				}
			}

	        tx.commit();
	        response.setCode(200);
	        response.setMessage("Circuit properties updated successfully.");

	    } catch (Exception e) {
	        log.error("Error updating circuit properties: {}", e.getMessage(), e);
	        response.setCode(500);
	        response.setMessage("Error updating circuit: " + e.getMessage());
	    } finally {
	        session.close();
	    }

	    return response;
	}
    
    private Map<String, Object> populatePortsFromNMI(Map<String, Object> nniPayload, String customerEnd, String cktId) {
        try {
            Map<String, Object> nmiPortInfo = getNMIDetails(cktId);

            if (nmiPortInfo != null) {
            	
                Map<String, Object> portA = (Map<String, Object>) nmiPortInfo.get("portA");
                Map<String, Object> portZ = (Map<String, Object>) nmiPortInfo.get("portZ");

                String sourcePortNum = "";
                String destPortNum = "";

                if ("A".equalsIgnoreCase(customerEnd) && portA != null && portZ != null) {
                    sourcePortNum = (String) portA.get("portKey");
                    destPortNum = (String) portZ.get("portKey");

                    Map<String, Object> sourceInfo = getDeviceInfo(sourcePortNum);
                    Map<String, Object> destInfo = getDeviceInfo(destPortNum);

                    nniPayload.put("sourceNodeId", sourceInfo.get("node_id"));
                    nniPayload.put("sourceBaseHeci", sourceInfo.get("base_heci"));
                    nniPayload.put("destNodeId", destInfo.get("node_id"));
                    nniPayload.put("destBaseHeci", destInfo.get("base_heci"));
                    nniPayload.put("aPartNumber", portA.get("partNumber"));
                    nniPayload.put("aPartDesc", portA.get("partDesc"));
                    nniPayload.put("zPartNumber", portZ.get("partNumber"));
                    nniPayload.put("zPartDesc", portZ.get("partDesc"));
                } else if ("Z".equalsIgnoreCase(customerEnd) && portA != null && portZ != null) {
                    sourcePortNum = (String) portZ.get("portKey");
                    destPortNum = (String) portA.get("portKey");

                    Map<String, Object> sourceInfo = getDeviceInfo(sourcePortNum);
                    Map<String, Object> destInfo = getDeviceInfo(destPortNum);

                    nniPayload.put("sourceNodeId", sourceInfo.get("node_id"));
                    nniPayload.put("sourceBaseHeci", sourceInfo.get("base_heci"));
                    nniPayload.put("destNodeId", destInfo.get("node_id"));
                    nniPayload.put("destBaseHeci", destInfo.get("base_heci"));
                    nniPayload.put("aPartNumber", portZ.get("partNumber"));
                    nniPayload.put("aPartDesc", portZ.get("partDesc"));
                    nniPayload.put("zPartNumber", portA.get("partNumber"));
                    nniPayload.put("zPartDesc", portA.get("partDesc"));
                }

                nniPayload.put("sourcePortNum", sourcePortNum);
                nniPayload.put("destPortNum", destPortNum);
            }

        } catch (Exception e) {
            log.error("Error fetching port info from NMI for cktId {}: {}", cktId, e.getMessage(), e);
        }

        return nniPayload;
    }
    
    public Map<String, Object> getNMIDetails(String cktId) {
        Map<String, Object> portDetails = new HashMap<>();

        try (Session session = driver.session()) {
            String query = "MATCH (nmi:NNIConnection {cktid: $cktId}) " +
                           "RETURN nmi.A_port_key AS portA, nmi.Z_port_key AS portZ";

            Record record = session.readTransaction(tx -> {
            	 Result result = tx.run(query, Map.of("cktId", cktId));
                return result.single();
            });

            if (record != null) {
            	Map<String, Object> portAMap = Map.of("portKey", record.get("portA").asString());
                Map<String, Object> portZMap = Map.of("portKey", record.get("portZ").asString());
                portDetails.put("portA", portAMap);
                portDetails.put("portZ", portZMap);
            } else {
                // If no record is found
                portDetails.put("portA", null);
                portDetails.put("portZ", null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            portDetails.put("portA", null);
            portDetails.put("portZ", null);
        }

        return portDetails;
    }
	
	public void checkNMIXconnect(Map<String, Object> networkPayload, Map<String, Object> uniPayload, Transaction tx) {
	    String nmiCktId = (String) networkPayload.get("cktid");
	    String uniCktId = (String) uniPayload.get("circuitName");

	    if (nmiCktId == null || uniCktId == null) {
	        log.warn("Missing cktid(s) for UNI or NMI. Skipping XCONNECT check.");
	        return;
	    }

	    String uniAliasCktId = uniCktId.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
	    String nmiAliasCktId = nmiCktId.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

	    try {
	        Map<String, Object> nmiConn = getNmiByUni(uniAliasCktId, tx);

	        String sourcePortKey = (String) networkPayload.get("sourcePortNum");
	        String destPortKey = (String) networkPayload.get("destPortNum");

	        boolean needXconnect = true;

	        if (nmiConn != null && !nmiConn.isEmpty()) {
	            Map<String, Object> portNode = (Map<String, Object>) nmiConn.get("aEndPort");
	            String existingNmiPort = portNode != null ? (String) portNode.get("portKey") : null;

	            if (existingNmiPort != null && 
	                (existingNmiPort.equalsIgnoreCase(sourcePortKey) || existingNmiPort.equalsIgnoreCase(destPortKey))) {
	                log.info("Existing NMI [{}] already connected to correct port [{}]. Skipping XCONNECT.", nmiAliasCktId, existingNmiPort);
	                needXconnect = false;
	            }
	        }

	        if (needXconnect) {
	        	 try (Session session = driver.session()) {
	                 session.writeTransaction(innerTx -> {
	                     disconnectExistingXconnect(innerTx, uniAliasCktId);
	                     return null;
	                 });
	             }
	            handleXconnectCreation(uniAliasCktId, nmiAliasCktId);
	        }

	    } catch (Exception e) {
	        log.error("Error checking/creating NMI XCONNECT for UNI [{}]: {}", uniCktId, e.getMessage(), e);
	        throw e;
	    }
	}
	
	private void disconnectExistingXconnect(Transaction tx, String cktid) {
	    try {
	        if (cktid == null || cktid.isEmpty()) {
	            throw new IllegalArgumentException("cktid is null or empty");
	        }

	        String query =
	                "MATCH (u:UNIConnection {aliasCktId: $cktid})-[r:CONNECTED_TO]->(ep:EquipmentPort) " +
	                "OPTIONAL MATCH (ep)-[x:XCONNECT]-(other:EquipmentPort) " +
	                "DELETE  x";

	        log.info("Disconnecting  XCONNECT for UNI [{}]", cktid);

	        tx.run(query, parameters("cktid", cktid));

	        log.info("Disconnected relations for [{}]", cktid);

	    } catch (Exception e) {
	        log.error("Error disconnecting port relation for {}: {}", cktid, e.getMessage(), e);
	        throw e;
	    }
	} 
	private void handleXconnectCreation(String uniAliasCktId, String nmiAliasCktId) {
	    Map<String, Object> portInfo = fetchPortKeysForXConnect(uniAliasCktId, nmiAliasCktId);

	    if (portInfo != null && portInfo.containsKey("cPort") && portInfo.containsKey("nPort")) {
	        String uniPortKey = (String) portInfo.get("cPort");
	        String nniPortKey = (String) portInfo.get("nPort");
	        log.info("Creating XCONNECT between UNI [{}] port [{}] and NMI [{}] port [{}]", uniAliasCktId, uniPortKey, nmiAliasCktId, nniPortKey);
	        createXConnectRelation(uniPortKey, nniPortKey);
	    } else {
	        log.warn("Port info not found for UNI [{}] and NMI [{}]. Skipping XCONNECT creation.", uniAliasCktId, nmiAliasCktId);
	    }
	}
	
	private boolean updateUNIProperties(Map<String, Object> uniPayload) {
		try {
			log.info("Calling UNI update API with payload: {}", uniPayload);

			apiConfig.setContext("inventory");
			HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(uniPayload, headers);
			log.info("HTTP Request for UNI update: {}", request);

			ResponseEntity<ResponseStatus> response = restTemplate.postForEntity(apiConfig.getUpdateUNIUrl(), request,
					ResponseStatus.class);

			if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
					&& response.getBody().getCode() == 200) {

				log.info("UNI properties updated successfully.");
				return true;
			} else {
				log.error("UNI update API failed: {}",
						response.getBody() != null ? response.getBody().getMessage() : "No response body");
				return false;
			}

		} catch (Exception ex) {
			log.error("Error calling external UNI update API: {}", ex.getMessage(), ex);
			return false;
		}
	}
	
	private boolean updateNNIProperties(Map<String, Object> networkPayload) {
		try {
			log.info("Calling NNI update API with payload: {}", networkPayload);
			
			apiConfig.setContext("inventory");
			HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(networkPayload, headers);
			log.info("HTTP Request for NNI update: {}", request);

			ResponseEntity<ResponseStatus> response = restTemplate.postForEntity(apiConfig.getUpdateNNIUrl(), request,
					ResponseStatus.class);

			if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
					&& response.getBody().getCode() == 200) {

				log.info("NNI properties updated successfully.");
				return true;
			} else {
				log.error("NNI update API failed: {}",
						response.getBody() != null ? response.getBody().getMessage() : "No response body");
				return false;
			}

		} catch (Exception ex) {
			log.error("Error calling external NNI update API: {}", ex.getMessage(), ex);
			return false;
		}
	}
	
	public ResponseStatus getEVCInfoToModify(String aliasCktId) {
	    ResponseStatus response = new ResponseStatus();
	    Map<String, Object> finalResult = new LinkedHashMap<>();

	    String normalizedAlias = aliasCktId.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();

	    try (Session session = driver.session(); Transaction tx = session.beginTransaction()) {

	        // 1. Get existing EVC design
	        ResponseStatus serviceDesignResp = getEVCDesign(aliasCktId);
	        if (serviceDesignResp.getCode() != 200) return serviceDesignResp;

	        Map<String, Object> serviceData = (Map<String, Object>) serviceDesignResp.getData();
	        finalResult.put("serviceDesign", serviceData);

	        // 2. Fetch latest change order
	        String changeOrderQuery =
	                "MATCH (c:EVCChangeOrder) " +
	                "WHERE c.aliasCktId = $aliasCktId " +
	                "RETURN c.uniList AS uniList " ;

	        List<Record> records = tx.run(changeOrderQuery, Map.of("aliasCktId", normalizedAlias)).list();
	        List<Map<String, Object>> filteredUniList = new ArrayList<>();

	        if (!records.isEmpty() && !records.get(0).get("uniList").isNull()) {
	            String uniListJson = records.get(0).get("uniList").asString();
	            if (uniListJson != null && !uniListJson.isBlank()) {
	                List<Map<String, Object>> uniList = objectMapper.readValue(uniListJson, List.class);

	                // Extract only required fields
	                for (Map<String, Object> uni : uniList) {
	                    Map<String, Object> filteredUni = new HashMap<>();
	                    filteredUni.put("circuitName", uni.get("circuitName"));
	                    filteredUni.put("evcNci", uni.get("evcNci"));
	                    filteredUni.put("cTag_start", uni.get("cTag_start"));
	                    filteredUni.put("cTag_end", uni.get("cTag_end"));
	                    filteredUni.put("bandwidth", uni.get("bandwidth"));
	                    filteredUni.put("serviceCos", uni.get("serviceCos"));
	                    filteredUni.put("pbit", uni.get("pbit"));
	                    filteredUniList.add(filteredUni);
	                }
	            }
	        }

	        finalResult.put("uniList", filteredUniList);

	        response.setCode(200);
	        response.setMessage("Successfully retrieved evc info to modify");
	        response.setData(finalResult);

	    } catch (Exception ex) {
	        log.error("compareCircuit - Exception: {}", ex.getMessage(), ex);
	        response.setCode(500);
	        response.setMessage("Failed to retrieve data: " + ex.getMessage());
	    }

	    return response;
	}
	
	public ResponseStatus updateEVCInfo(String vcName, Map<String, Object> request) {
	    ResponseStatus response = new ResponseStatus();
	    String aliasCktId = vcName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
	    try (Session session = driver.session()) {

	        // Step 1: Check if EVC node exists
	        String checkQuery = "MATCH (vc:EVCConnection {aliasCktId: $aliasCktId}) where vc.deletedTimeStamp is null RETURN vc LIMIT 1";
	        Map<String, Object> params = new HashMap<>();
	        params.put("aliasCktId", aliasCktId);

	        Result result = session.run(checkQuery, params);
	        if (!(result.hasNext())) {
	            log.warn("EVC with name '{}' doesn't exist.", vcName);
	            response.setCode(404);
	            response.setMessage("EVC with the specified name doesn't exist.");
	            return response;
	        }
	        
	        // Step 2: Check and update the relationship between EVC and UNI connections
	        List<Map<String, Object>> uniList = (List<Map<String, Object>>) request.get("uniList");
	        if (uniList != null) {
	            for (Map<String, Object> uni : uniList) {
	                String cktid = (String) uni.get("circuitName");
	                String evcNci = (String) uni.get("evcNci");
	                String evcBandwidth = (String) uni.get("evcBandwidth");

	                // Query to check if the UNIConnection with the given circuitName exists
	                String checkCircuitQuery = "MATCH (uni:UNIConnection {cktid: $cktid}) where uni.deletedTimeStamp is null  RETURN uni LIMIT 1";
	                Map<String, Object> circuitParams = new HashMap<>();
	                circuitParams.put("cktid", cktid);

	                Result circuitResult = session.run(checkCircuitQuery, circuitParams);
	                if (!(circuitResult.hasNext())) {
	                    log.warn("UNIConnection with cktid '{}' doesn't exist.", cktid);
	                    response.setCode(404);
	                    response.setMessage("UNIConnection with cktid '" + cktid + "' doesn't exist.");
	                    return response;
	                }

	                // Step 4: Update properties on the relationship between EVC and UNI
	                String updateRelationQuery = 
	                    "MATCH (vc:EVCConnection {aliasCktId: $aliasCktId})-[r:AEND|ZEND|VEND]->(uni:UNIConnection {cktid: $cktid}) " +
	                    "where r.deletedTimeStamp is null and uni.deletedTimeStamp is null " +
	                    "SET r.evcNci = $evcNci, r.evcBandwidth = $evcBandwidth " +
	                    "RETURN r";

	                Map<String, Object> relationParams = new HashMap<>();
	                relationParams.put("aliasCktId", aliasCktId);
	                relationParams.put("cktid", cktid);
	                relationParams.put("evcNci", evcNci);
	                relationParams.put("evcBandwidth", evcBandwidth);

	                session.run(updateRelationQuery, relationParams);
	                log.info("Updated relationship between EVC '{}' and UNI '{}'", vcName, cktid );
	            }
	        } else {
	            log.warn("uniList is missing or empty.");
	            response.setCode(400);
	            response.setMessage("Bad Request: 'uniList' is missing or empty.");
	            return response;
	        }

	        // After successfully updating the relationship
	        response.setCode(200);
	        response.setMessage("EVC to UNI relationship properties updated successfully.");

	    } catch (Exception ex) {
	        log.error("Error updating EVC to UNI relationship properties: {}", ex.getMessage(), ex);
	        response.setCode(500);
	        response.setMessage("Internal Server Error: Failed to update relationship properties. Reason: " + ex.getMessage());
	    }
	    return response;
	}

	public ResponseEntity<Object> checkServiceAvailability(@RequestBody List<Map<String, Object>> portList) {
		log.info("portList..{}", portList);
		VlanCheckRequest body = buildServiceCheckRequest(portList);

		try {
			String respJson = getServiceAvailability(body);

			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(respJson);

			boolean isAnyPortAvailable = false;

			JsonNode devices = root.path("devices");
			if (devices.isArray()) {
				for (JsonNode device : devices) {
					JsonNode ports = device.path("ports");
					if (ports.isArray()) {
						for (JsonNode port : ports) {

							// IMPORTANT: false means AVAILABLE
							boolean serviceAvailable = port.path("serviceAvailable").asBoolean(true);

							if (!serviceAvailable) {
								isAnyPortAvailable = true;
								break;
							}
						}
					}
					if (isAnyPortAvailable)
						break;
				}
			}

			// ✅ At least one port is AVAILABLE
			if (isAnyPortAvailable) {
				Map<String, Object> response = new LinkedHashMap<>();
				response.put("status", "success");
				response.put("isPortAvailable", true);
				return ResponseEntity.ok(response);
			}

			// ❌ No available ports
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("status", "error");
			error.put("message", "No available ports found");

			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);

		} catch (Exception ex) {
			log.error("Error while checking service availability", ex);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
		}
	}

	public VlanCheckRequest buildServiceCheckRequest(List<Map<String, Object>> portList) {
		Map<String, DeviceRequest> deviceMap = new LinkedHashMap<>();
		log.info("..." + portList + "...");
		for (Map<String, Object> entry : portList) {
			Object portVal = entry.get("portKey");
			if (portVal == null) {
				continue;
			}

			String portKey = String.valueOf(portVal);
			Boolean isUNI = Boolean.FALSE;
			Object isUNIVal = entry.get("isUNI");
			if (isUNIVal instanceof Boolean) {
				isUNI = (Boolean) isUNIVal;
			}

			String query = """
					MATCH (p:EquipmentPort {portKey: $portKey})-[:COMPONENT_OF*1..]->(e:Equipment)
					RETURN
					    p.id AS portId,
					    p.portKey AS portKey,
					    p.bw AS bw,
					   // p.portFunction AS portFunction,
					    p.node_id AS nodeId,
					    e.node_id AS equipNodeId,
					    e.IPV4MGMROUTERID AS ip,
					    e.TID AS tid,
					    e.model AS model,
					    e.deviceRoles AS deviceCategory,
					    EXISTS {
					        MATCH (e)<-[:COMPONENT_OF]-(:EquipmentPort)
					              <-[:CONNECTED_TO]-(:UNIConnection)
					    } AS hasUniConnection

							""";

			Map<String, Object> params = Map.of("portKey", portKey);

			List<PortEquip> result = neo4jClient.query(query).bindAll(params).fetch().all() // returns List<Map<String,
																							// Object>>
					.stream().map(r -> {
						EquipmentPort port = new EquipmentPort();
						port.setId(((String) r.get("portId")));
						port.setPortKey((String) r.get("portKey"));
						port.setBw((String) r.get("bw"));
						//port.setPortFunction((String) r.get("portFunction"));
						port.setNode_id((String) r.get("nodeId"));

						Equipment equip = new Equipment();
						equip.setNode_id((String) r.get("equipNodeId"));
						equip.setIPV4MGMROUTERID((String) r.get("ip"));
						equip.setTID((String) r.get("tid"));
						String model = (String) r.get("model");
						String deviceModel = ModelEnum.getMastroeNameByUsilModel(model);
						boolean isNID = Boolean.TRUE.equals(r.get("hasUniConnection"));
						String deviceCategory = ApplicationUtils.getDeviceCategory((String) r.get("deviceCategory"),
								isNID);
						log.info("isNID" + isNID + ".." + "deviceModel" + ".." + deviceModel + ".." + "model" + ".."
								+ model + ".." + "deviceCategory" + (String) r.get("deviceCategory") + ".."
								+ deviceCategory);
						equip.setModel(deviceModel);
						equip.setDeviceCategory(deviceCategory);

						PortEquip pe = new PortEquip();
						pe.setPort(port);
						pe.setEquip(equip);
						return pe;
					}).toList();
			for (PortEquip pe : result) {
				EquipmentPort port = pe.getPort();
				Equipment equip = pe.getEquip();
				String deviceKey = equip.getNode_id();

				DeviceRequest dev = deviceMap.computeIfAbsent(deviceKey, k -> {
					DeviceRequest d = new DeviceRequest();
					d.setDeviceIp(equip.getIPV4MGMROUTERID());
					d.setDeviceIdentifier(equip.getTID());
					d.setDeviceModel(equip.getModel());
					d.setDeviceCategory(equip.getDeviceCategory());
					d.setPorts(new ArrayList<>());
					return d;
				});

				PortRequest pReq = new PortRequest();
				pReq.setPortValue(convertPortKeyToCli(port.getPortKey()));
				pReq.setPortSpeed(ApplicationUtils.bandwidthConvertor(port.getBw(),true,true)/*convertSpeed(port.getBw())*/);
				pReq.setPortType(isUNI ? "UNI" : "NMI");

				dev.getPorts().add(pReq);
			}
		}

		VlanCheckRequest req = new VlanCheckRequest();
		req.setDevices(new ArrayList<>(deviceMap.values()));
		return req;
	}

	private String convertPortKeyToCli(String portKey) {
		if (portKey == null || portKey.isBlank()) {
			return null;
		}

		String[] parts = portKey.split("\\|");

		if (parts.length < 3) {
			return portKey; // nothing to convert
		}

		String slot = parts[2]; // usually stable

		// Find last numeric part (port number)
		String port = null;
		for (int i = parts.length - 1; i >= 0; i--) {
			if (parts[i].matches("\\d+")) {
				port = parts[i];
				break;
			}
		}

		if (port == null) {
			return portKey; // fallback
		}

		return slot + "/-1/-1/" + Integer.parseInt(port);
	}

	private String convertSpeed(String bw) {
		log.info("bw.." + bw);
		if (bw == null)
			return null;

		String s = bw.trim().toUpperCase();

		// Normalize inputs
		if (s.equals("1G") || s.equals("1GE"))
			return "1000Mbps";
		if (s.equals("10G") || s.equals("10GE"))
			return "10000Mbps";
		if (s.equals("100G") || s.equals("100GE"))
			return "100000Mbps";

		// If already numeric like "1000"
		if (s.matches("\\d+"))
			return s + "Mbps";

		// Unknown → return as is (or return null)
		return s;
	}

	private String determinePortType(String portFunction) {
		if (portFunction == null)
			return "UNI";
		if (portFunction.contains("NF"))
			return "NMI";
		if (portFunction.contains("UN"))
			return "UNI";
		return "UNI";
	}

	public String getServiceAvailability(VlanCheckRequest vlanRequest) throws JsonProcessingException {

		log.info("vlanRequest.." + vlanRequest);
		apiConfig.setContext("maestro");

		HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
		headers.setContentType(MediaType.APPLICATION_JSON);

		HttpEntity<VlanCheckRequest> request = new HttpEntity<>(vlanRequest, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(apiConfig.getMaestroServiceAvailabilityUrl(),
				request, String.class);

		log.info("Maestro Raw Response: {}", response.getBody());

		if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
			return response.getBody(); // ✅ ALWAYS return raw JSON
		}

		throw new RuntimeException("Maestro service failed");
	}

	public List<Map<String, Object>> buildPortStagListFromCircuits(List<String> circuitIds) {
		List<NniPortStagDto> nniList = getFirstAvailableStags(circuitIds);
		log.info("nniList....{}", nniList);

		List<Map<String, Object>> portStagList = new ArrayList<>();
		for (NniPortStagDto nni : nniList) {
			Integer stag = nni.firstAvailableStag();
			for (String portKey : nni.portKeys()) {
				Map<String, Object> map = new HashMap<>();
				map.put("portKey", portKey);
				map.put("stag", stag);
				portStagList.add(map);
			}
		}
		log.info("portStagList....{}", portStagList);
		return portStagList;
	}

	public List<NniPortStagDto> getFirstAvailableStags(List<String> circuitIds) {
		String cypher = """
				MATCH (nni:NNIConnection)-[:CONNECTED_TO]->(ep)
				WHERE nni.aliasCktId IN $circuitIds
				OPTIONAL MATCH (nni)-[:RIDES_ON]->(route:ROUTE)
				OPTIONAL MATCH (route)<-[:CONNECTED_TO]-(uni:UNIConnection)
				OPTIONAL MATCH (uni)-[rel:AEND|ZEND|VEND]->(evc:EVCConnection)
				WITH nni, ep, [x IN collect(DISTINCT rel.STAG) WHERE x IS NOT NULL] AS usedStags
				WHERE size(usedStags) < 4093
				ORDER BY nni.aliasCktId
				LIMIT 2
				WITH nni, usedStags, collect(ep.portKey) AS portKeys, range(2, 4094) AS allTags
				RETURN nni.aliasCktId AS nniId, portKeys,
				       head([x IN allTags WHERE NOT x IN usedStags]) AS firstAvailableStag
				""";

		return new ArrayList<>(neo4jClient.query(cypher).bind(circuitIds).to("circuitIds").fetchAs(NniPortStagDto.class)
				.mappedBy((ts, record) -> new NniPortStagDto(record.get("nniId").asString(),
						record.get("firstAvailableStag").asInt(), record.get("portKeys").asList(Value::asString)))
				.all());
	}

	public ResponseEntity<Object> checkBandwidthAvailability(List<Map<String, Object>> portStagList) {

		VlanCheckRequest body = buildVlanRequest(portStagList, false, true);

		try {
			String respJson = getBandwidthAvailability(body);

			if (respJson == null) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.body("Failed to get response from Maestro");
			}

			ObjectMapper mapper = new ObjectMapper();
			JsonNode root = mapper.readTree(respJson);

			JsonNode devicesNode = root.path("devices");

			List<ObjectNode> cleanedDevices = new ArrayList<>();

			if (devicesNode.isArray()) {
				for (JsonNode deviceNode : devicesNode) {

					// only SUCCESS devices
					if ("success".equalsIgnoreCase(deviceNode.path("status").asText())) {

						ObjectNode cleanDevice = deviceNode.deepCopy();

						// ❌ remove Maestro internal fields
						cleanDevice.remove("status");
						cleanDevice.remove("message");

						cleanedDevices.add(cleanDevice);
					}
				}
			}

			ObjectNode response = mapper.createObjectNode();

			// ✅ at least one device has bandwidth
			if (!cleanedDevices.isEmpty()) {
				response.put("status", "success");
				response.put("message", "Device with bandwidth availability exists");
				response.set("devices", mapper.valueToTree(cleanedDevices));

				return ResponseEntity.ok(response);
			}

			// ❌ no device has bandwidth
			response.put("status", "failed");
			response.put("message", "No device with bandwidth availability exists");
			response.set("devices", mapper.createArrayNode());

			return ResponseEntity.badRequest().body(response);

		} catch (Exception ex) {
			log.error("Error checking bandwidth availability", ex);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
		}
	}

	public VlanCheckRequest buildDeviceRequest(List<Map<String, Object>> portStagList, boolean includeStag,
			boolean includeSpeed) {
		Map<String, DeviceRequest> deviceMap = new LinkedHashMap<>();
		log.info("..." + portStagList + "...");
		for (Map<String, Object> entry : portStagList) {
			Object portVal = entry.get("portKey");
			String portKey = portVal == null ? null : String.valueOf(portVal);

			Object stagVal = entry.get("stag");
			String stag = stagVal == null ? null : String.valueOf(stagVal);

			String query = """
					    MATCH (p:EquipmentPort {portKey: $portKey})-[:COMPONENT_OF*1..]->(e:Equipment)
					    RETURN p.id AS portId, p.portKey AS portKey, p.bw AS bw, p.portFunction AS portFunction,
					           p.node_id AS nodeId, e.node_id AS equipNodeId, e.IPV4MGMROUTERID AS ip,
					           e.TID AS tid, e.model AS model, e.deviceRoles AS deviceCategory,
					           EXISTS {
					    	         MATCH (e)<-[:COMPONENT_OF]-(:EquipmentPort)
					    	               <-[:CONNECTED_TO]-(:UNIConnection)
					    	     } AS hasUniConnection
					""";

			Map<String, Object> params = Map.of("portKey", portKey);

			List<PortEquip> result = neo4jClient.query(query).bindAll(params).fetch().all() // returns List<Map<String,
																							// Object>>
					.stream().map(r -> {
						EquipmentPort port = new EquipmentPort();
						port.setId(((String) r.get("portId")));
						port.setPortKey((String) r.get("portKey"));
						port.setBw((String) r.get("bw"));
						port.setPortFunction((String) r.get("portFunction"));
						port.setNode_id((String) r.get("nodeId"));

						String model = (String) r.get("model");
						String deviceModel = ModelEnum.getMastroeNameByUsilModel(model);
						boolean isNID = Boolean.TRUE.equals(r.get("hasUniConnection"));
						String deviceCategory = ApplicationUtils.getDeviceCategory((String) r.get("deviceCategory"),
								isNID);
						log.info("isNID" + isNID + ".." + "tid" + ".." + (String) r.get("tid") + ".." + "model" + ".."
								+ model + ".." + "deviceCategory.." + (String) r.get("deviceCategory") + ".."
								+ deviceCategory);

						Equipment equip = new Equipment();
						equip.setNode_id((String) r.get("equipNodeId"));
						equip.setIPV4MGMROUTERID((String) r.get("ip"));
						equip.setTID((String) r.get("tid"));
						equip.setModel(deviceModel);
						equip.setDeviceCategory(deviceCategory);

						PortEquip pe = new PortEquip();
						pe.setPort(port);
						pe.setEquip(equip);
						return pe;
					}).toList();
			for (PortEquip pe : result) {
				EquipmentPort port = pe.getPort();
				Equipment equip = pe.getEquip();
				String deviceKey = equip.getNode_id();

				DeviceRequest dev = deviceMap.computeIfAbsent(deviceKey, k -> {
					DeviceRequest d = new DeviceRequest();
					d.setDeviceIp(equip.getIPV4MGMROUTERID());
					d.setDeviceIdentifier(equip.getTID());
					d.setDeviceModel(equip.getModel());
					d.setDeviceCategory(equip.getDeviceCategory());
					d.setPorts(new ArrayList<>());
					return d;
				});

				PortRequest pReq = new PortRequest();
				pReq.setPortValue(convertPortKeyToCli(port.getPortKey()));
				if (includeSpeed)
					pReq.setPortSpeed(convertSpeed(port.getBw()));
				pReq.setPortType(determinePortType(port.getPortFunction()));
				if ("UNI".equals(pReq.getPortType())) {
					pReq.setCtag("0");
				}

				if (includeStag && !"UNI".equals(pReq.getPortType())) {
					pReq.setStag(stag);
				}

				dev.getPorts().add(pReq);
			}
		}

		VlanCheckRequest req = new VlanCheckRequest();
		req.setDevices(new ArrayList<>(deviceMap.values()));
		return req;
	}

	public VlanCheckRequest buildVlanRequest(List<Map<String, Object>> portStagList, boolean includeStag,
			boolean includeSpeed) {
		return buildDeviceRequest(portStagList, includeStag, includeSpeed);
	}

	public String getBandwidthAvailability(VlanCheckRequest vlanRequest) throws JsonProcessingException {

		apiConfig.setContext("maestro");

		HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
		headers.setContentType(MediaType.APPLICATION_JSON);

		ObjectMapper mapper = new ObjectMapper();
		log.info("JSON Payload: {}", mapper.writeValueAsString(vlanRequest));

		HttpEntity<VlanCheckRequest> request = new HttpEntity<>(vlanRequest, headers);

		String url = apiConfig.getMaestroBandwidthAvailabilityUrl();
		log.info("Calling Maestro Bandwidth Check URL: {}", url);

		try {
			ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

			log.info("Maestro Raw Response: {}", response.getBody());
			log.info("Maestro HTTP Status: {}", response.getStatusCode());

			return response.getBody();

		} catch (Exception ex) {
			log.error("Error calling Maestro Bandwidth Check API", ex);
			return null;
		}
	}

	private String parseCommonVlanRange(String apiResponseJson) {
        try {
            Map<String, Object> responseMap = new ObjectMapper().readValue(apiResponseJson, Map.class);
            String status = (String) responseMap.get("status");
            if (!"success".equalsIgnoreCase(status)) {
                log.warn("VLAN check API returned non-success status: {}", status);
                return null;
            }

            @SuppressWarnings("unchecked")
            List<String> commonVlans = (List<String>) responseMap.get("commonVlans");
            if (commonVlans == null || commonVlans.isEmpty()) {
                log.warn("commonVlans array is missing or empty in API response");
                return null;
            }

            // Take the first range only
            String firstRange = commonVlans.get(0).trim(); // e.g., "2-103" or "3"
            log.info("Selected first range: {}", firstRange);

            // Extract the starting (lowest) VLAN number from the range
            String[] parts = firstRange.split("-");
            String firstAvailableVlan = parts[0].trim(); // "2" from "2-103", or "3" from "3"

            log.info("Extracted first available VLAN ID: {}", firstAvailableVlan);
            return firstAvailableVlan;

        } catch (Exception e) {
            log.error("Failed to parse VLAN availability response: {}", apiResponseJson, e);
            return null;
        }
    }
	
	private List<Map<String, Object>> processEvcAndUpdateStags(List<Map<String, Object>> evcRoute,
			List<Map<String, Object>> uniConnections, String serviceType) {

		log.info("inside processEvcAndUpdateStags");
		try {
			// Existing transformation if any
			// evcInfo = transformed;

			List<VlanCheckRequest> vlanCheckRequests = buildDeviceRequestsFromEvcResponse(evcRoute, uniConnections,
					serviceType);

			// Find MPLS index (already computed in buildDeviceRequestsFromEvcResponse
			// logic)
			int mplsIndex = evcRoute.size();
			for (int i = 0; i < evcRoute.size(); i++) {
				Map<String, Object> entry = evcRoute.get(i);
				String connectionType = (String) entry.get("connectionType");
				if ("MPLS".equals(connectionType)) {
					mplsIndex = i;
					break;
				}
			}

			ObjectMapper mapper = new ObjectMapper();

			// Process each VLAN check request (pre-MPLS and optional post-MPLS)
			for (int i = 0; i < vlanCheckRequests.size(); i++) {
				VlanCheckRequest request = vlanCheckRequests.get(i);

				// Log the request JSON (existing logic)
				try {
					String jsonRequest = mapper.writeValueAsString(request);
					if (i == 0) {
						log.info("VlanCheckRequest #1 (Pre-MPLS segment):\n{}", jsonRequest);
					} else {
						log.info("VlanCheckRequest #2 (Post-MPLS segment):\n{}", jsonRequest);
					}
				} catch (JsonProcessingException e) {
					log.error("Failed to serialize VlanCheckRequest #{} to JSON for logging", i + 1, e);
				}

				// Call the VLAN availability API
				String apiResponseJson = getVlanAvailability(request);

				if (apiResponseJson == null) {
					log.warn("No response from VLAN availability API for segment {}",
							i == 0 ? "pre-MPLS" : "post-MPLS");
					continue;
				}

				// Parse the common VLAN range
				String commonVlanRange = parseCommonVlanRange(apiResponseJson);
				if (commonVlanRange == null || commonVlanRange.isBlank()) {
					log.warn("No valid common VLAN range in response for segment {}: {}",
							i == 0 ? "pre-MPLS" : "post-MPLS", apiResponseJson);
					continue;
				}

				log.info("Retrieved common VLAN range for {} segment: {}", i == 0 ? "pre-MPLS" : "post-MPLS",
						commonVlanRange);

				// Determine the route indices for this segment
				int startIndex = (i == 0) ? 0 : mplsIndex + 1;
				int endIndex = (i == 0) ? mplsIndex : evcRoute.size();

				// Update sTag for all NNI connections in this segment
				int updatedCount = 0;
				for (int j = startIndex; j < endIndex; j++) {
					Map<String, Object> routeEntry = evcRoute.get(j);
					if (!("MPLS".equals(routeEntry.get("connectionType")))) {
						routeEntry.put("sTag", commonVlanRange);
						updatedCount++;
					}
				}

				log.info("Updated {} NNI connection(s) in {} segment with sTag = {}", updatedCount,
						i == 0 ? "pre-MPLS" : "post-MPLS", commonVlanRange);
			}

			return evcRoute;

		} catch (Exception ex) {
			log.error("Failed to process EVC and update sTags: {}", ex.getMessage(), ex);
			throw new RuntimeException("Failed to process EVC VLAN availability", ex);
		}
	}
	
	public String getVlanAvailability(VlanCheckRequest vlanRequest) throws JsonProcessingException {
    	apiConfig.setContext("maestro");
    	log.info("Context set to MAESTRO for VLAN check.. Token: {}", oAuthService.getAccessToken());

    	// Prepare headers
    	HttpHeaders headers = ApplicationUtils.createOAuthTokenHeader(oAuthService.getAccessToken());
    	headers.setContentType(MediaType.APPLICATION_JSON);

    	// Log JSON payload
    	ObjectMapper mapper = new ObjectMapper();
    	log.info("JSON Payload: {}", mapper.writeValueAsString(vlanRequest));

    	HttpEntity<VlanCheckRequest> request = new HttpEntity<>(vlanRequest, headers);

    	log.info("Calling Maestro VLAN Check URL: {}", apiConfig.getMaestroVlanAvailabilityUrl());


    	 try {
             ResponseEntity<String> response =
                     restTemplate.postForEntity(
                   		  apiConfig.getMaestroVlanAvailabilityUrl(), request, String.class);

             log.info("Maestro Raw Response: {}", response.getBody());
             log.info("Maestro HTTP Status: {}", response.getStatusCode());

             return response.getBody();

         } catch (Exception ex) {
             log.error("Error calling Maestro Bandwidth Check API", ex);
             return null;
         }
     }
	
	private List<VlanCheckRequest> buildDeviceRequestsFromEvcResponse(List<Map<String, Object>> evcRoute,
			List<Map<String, Object>> uniConnections, String evpLanserviceType) {

		log.info("inside buildDeviceRequestsFromEvcResponse");
		List<VlanCheckRequest> requests = new ArrayList<>();

// Determine isNid flags for endpoints
		boolean startIsNid = false; // For the very first connection (first UNI)
		boolean endIsNid = false; // For the very last connection (second UNI or fallback to first)

		if (evpLanserviceType != null) {
			log.info("inside evpLanserviceType");
			startIsNid = "MEF UNI".equals(evpLanserviceType);
		}

		if (uniConnections != null && !uniConnections.isEmpty()) {
// First UNI → start of the path
			Map<String, Object> firstUni = uniConnections.get(0);
			Map<String, Object> uniCircuitInfo = (Map<String, Object>) firstUni.get("uniCircuitInfo");
			Map<String, Object> uniCircuit = (Map<String, Object>) uniCircuitInfo.get("uniCircuit");
			if (uniCircuit != null) {

				String serviceType = (String) uniCircuit.get("serviceType");
				startIsNid = "MEF UNI".equals(serviceType);
			}

// Second UNI (if exists) → end of the path
			if (uniConnections.size() > 1) {
				Map<String, Object> lastUni = uniConnections.get(uniConnections.size() - 1);
				Map<String, Object> lastUniCircuitInfo = (Map<String, Object>) lastUni.get("uniCircuitInfo");
				Map<String, Object> lastUniCircuit = (Map<String, Object>) lastUniCircuitInfo.get("uniCircuit");
				if (lastUniCircuit != null) {
					String serviceType = (String) lastUniCircuit.get("serviceType");
					endIsNid = "MEF UNI".equals(serviceType);
				}
			}
		}

		int mplsIndex = evcRoute.size();
		for (int i = 0; i < evcRoute.size(); i++) {
			Map<String, Object> entry = evcRoute.get(i);
			String connectionType = (String) entry.get("connectionType");
			if ("MPLS".equals(connectionType)) {
				mplsIndex = i;
				break;
			}
		}

// Pre-MPLS segment: start segment (first connection uses startIsNid), not end segment
		VlanCheckRequest preMplsRequest = buildRequestForRange(evcRoute, 0, mplsIndex, true, false, startIsNid,
				endIsNid);
		requests.add(preMplsRequest);

// Post-MPLS segment (if exists): not start segment, is end segment (last connection uses endIsNid)
		if (mplsIndex < evcRoute.size() - 1) {
			VlanCheckRequest postMplsRequest = buildRequestForRange(evcRoute, mplsIndex + 1, evcRoute.size(), false,
					true, startIsNid, endIsNid);
			requests.add(postMplsRequest);
		}

		return requests;
	}
	
	private VlanCheckRequest buildRequestForRange(List<Map<String, Object>> evcRoute, int startInclusive,
			int endExclusive, boolean isStartSegment, boolean isEndSegment, boolean startIsNid, boolean endIsNid) {
		log.info("inside buildRequestForRange");
		Map<String, DeviceRequest> deviceMap = new LinkedHashMap<>();

		Map<String, Object> previousZEndDevice = null;
		Map<String, Object> previousZEndPort = null;
		String previousSTag = null;

		for (int i = startInclusive; i < endExclusive; i++) {
			Map<String, Object> routeEntry = evcRoute.get(i);
			String currentSTag = String.valueOf(routeEntry.get("sTag"));

			Map<String, Object> aEndInfo = (Map<String, Object>) routeEntry.get("aEndInfo");
			Map<String, Object> zEndInfo = (Map<String, Object>) routeEntry.get("zEndInfo");

			Map<String, Object> aEndDevice = (Map<String, Object>) aEndInfo.get("device");
			Map<String, Object> aEndPort = (Map<String, Object>) aEndInfo.get("port");

			Map<String, Object> zEndDevice = (Map<String, Object>) zEndInfo.get("device");
			Map<String, Object> zEndPort = (Map<String, Object>) zEndInfo.get("port");

// Determine isNid for A-End port add: true only if this is the very first connection in the entire path
			boolean aEndIsNid = isStartSegment && (i == startInclusive) ? startIsNid : false;
			addPortToDevice(deviceMap, aEndDevice, aEndPort, "NMI", currentSTag, aEndIsNid);

// Previous Z-End port add: always false (intermediate/transit)
			if (previousZEndPort != null) {
// String prevPortFunction = (String) previousZEndPort.get("portFunction");
//  String prevPortType = (prevPortFunction != null && prevPortFunction.contains("NF")) ? "NMI" : "UNI";
				addPortToDevice(deviceMap, aEndDevice, previousZEndPort, "NMI", currentSTag, false);
			}

// Update previous
			previousZEndDevice = zEndDevice;
			previousZEndPort = zEndPort;
			previousSTag = currentSTag;
		}

// Final Z-End port add: true only if this is the very last connection in the entire path
		if (previousZEndPort != null && previousZEndDevice != null && previousSTag != null) {
			boolean finalIsNid = isEndSegment ? endIsNid : false;
			addPortToDevice(deviceMap, previousZEndDevice, previousZEndPort, "NMI", previousSTag, finalIsNid);
		}

		VlanCheckRequest request = new VlanCheckRequest();
		request.setDevices(new ArrayList<>(deviceMap.values()));
		return request;
	}

	private void addPortToDevice(Map<String, DeviceRequest> deviceMap, Map<String, Object> deviceJson,
			Map<String, Object> portJson, String portType, String sTag, boolean isNidForCategory) {
		log.info("inside add port to device");
		if (deviceJson == null || portJson == null)
			return;

		String nodeId = (String) deviceJson.get("node_id");
		if (nodeId == null)
			return;

		String tid = (String) deviceJson.get("TID");
		String ip = (String) deviceJson.get("IPV4MGMROUTERID");
		String model = (String) deviceJson.get("model");
		String deviceRoles = (String) deviceJson.get("deviceRoles");

		String maestroModel = ModelEnum.getMastroeNameByUsilModel(model);
		String deviceCategory = ApplicationUtils.getDeviceCategory(deviceRoles, isNidForCategory);

		DeviceRequest dev = deviceMap.computeIfAbsent(nodeId, k -> {
			DeviceRequest d = new DeviceRequest();
			d.setDeviceIp(ip);
			d.setDeviceIdentifier(tid);
			d.setDeviceModel(maestroModel);
			d.setDeviceCategory(deviceCategory);
			d.setPorts(new ArrayList<>());
			return d;
		});

		String portKey = (String) portJson.get("portKey");
		String bw = (String) portJson.get("bw");

		PortRequest portReq = new PortRequest();
		portReq.setPortValue(convertPortKeyToCli(portKey));
		portReq.setPortSpeed(convertSpeed(bw));
		portReq.setPortType(portType);
		portReq.setStag(sTag);
		portReq.setCtag(null);

		dev.getPorts().add(portReq);
	}

	public ResponseStatus findEVCRoute(String serviceName, String ncCode, List<String> uniInfoList) {
		log.info("=>EthernetOrderService:findEVCRoute: START");
		log.info("=>EthernetOrderService:between '{}'",uniInfoList);
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed to find route.");
		Map<String, Object> evcInfo = new HashMap<>();
		boolean isnewEvc = true;

		try (Session session = driver.session()) {
			Map<String, Object> responseData = new LinkedHashMap<>();
			Map<String, Object> transformed = new LinkedHashMap<>();
			//Result result = session.run(evcQuery, params);
			String evcAliasCktId = serviceName.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
			transformed.put("serviceName", serviceName);
			transformed.put("NC", ncCode);
			transformed.put("nci", "");
			if(StringUtils.equalsAnyIgnoreCase(ncCode, "VLC-"))
				transformed.put("serviceType", "MEF OVC");
			else
				transformed.put("serviceType", "MEF EVC");

			transformed.put("isNewEVC", false);

			List<Map<String, Object>> uniConnections = new ArrayList<>();

			for (String uniInfo : uniInfoList) {
				String cktid = uniInfo;
				if (cktid == null || cktid.trim().isEmpty())
					continue;
				String aliasCktIdInner = cktid.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
				String subQuery = "MATCH (uni:UNIConnection) "
					+ "WHERE uni.cktid = $cktid OR uni.aliasCktId = $aliasCktId WITH uni where uni.deletedTimeStamp is null "
					+ "MATCH (uni)-[C1:CONNECTED_TO]-(up:EquipmentPort)-[:COMPONENT_OF*]->(dev:Equipment) where up.deletedTimeStamp is null and dev.deletedTimeStamp is null "
					+ "AND C1.deletedTimeStamp is null  "
					+ "MATCH (up)-[C3:XCONNECT]->(anp:EquipmentPort)<-[C4:CONNECTED_TO]-(nmi:NNIConnection)-[C5:CONNECTED_TO]->(znp:EquipmentPort)-[COMPONENT_OF*]->(nmiDev:Equipment) "
					+ "WHERE C4.deletedTimeStamp is null and C5.deletedTimeStamp is null  "
					+ "AND anp.deletedTimeStamp is null and nmi.deletedTimeStamp is null and znp .deletedTimeStamp is null and nmiDev.deletedTimeStamp is null "
					+ " MATCH (aAllDev:allDevices{TID:dev.TID})"
					+ " MATCH (zAllDev:allDevices{TID:nmiDev.TID})"
					+ " OPTIONAL MATCH (uni)-[:AEND|ZEND|VEND]-(evc:EVCConnection{aliasCktId:'"+evcAliasCktId+"'})"
					+ "RETURN uni, up, aAllDev as dev, anp, nmi.cktid as nmiName, znp, zAllDev as nmiDev, evc.serviceName as evcName";
				Map<String, Object> subParams = Map.of("cktid", cktid, "aliasCktId", aliasCktIdInner);
				Result subResult = session.run(subQuery, subParams);
				if (subResult.hasNext()) {
					Record subRecord = subResult.next();
					Map<String, Object> uni = subRecord.get("uni").asMap();
					Map<String, Object> up = subRecord.get("up").asMap();
					Map<String, Object> dev = subRecord.get("dev").asMap();
					String nmiName = subRecord.get("nmiName").asString();
					Map<String, Object> anp = subRecord.get("anp").asMap();
					Map<String, Object> znp = subRecord.get("znp").asMap();
					Map<String, Object> nmiDev = subRecord.get("nmiDev").asMap();
					String evcName = subRecord.get("evcName").asString();

					Map<String, Object> uniCircuitInfo = new LinkedHashMap<>();
					uniCircuitInfo.put("uniCircuit", uni);
					uniCircuitInfo.put("device", dev);
					uniCircuitInfo.put("port", up);

					String cTagStart =  "";
					String cTagEnd = "";
					String evcNci = "";
					String classOfService = "";
					String evcBandwidth = "0";

					if (StringUtils.isNotBlank(evcBandwidth)) {
						evcBandwidth = ApplicationUtils.bandwidthConvertor(evcBandwidth,true,false);
					}

					Map<String, Object> aEndInfo = new LinkedHashMap<>();
					aEndInfo.put("device", dev);
					aEndInfo.put("port", anp);

					Map<String, Object> zEndInfo = new LinkedHashMap<>();
					zEndInfo.put("device", nmiDev);
					zEndInfo.put("port", znp);

					Map<String, Object> nniInfo = new LinkedHashMap<>();
					nniInfo.put("nniName", nmiName);
					nniInfo.put("aEndInfo", aEndInfo);
					nniInfo.put("zEndInfo", zEndInfo);

					Map<String, Object> uniConnection = new LinkedHashMap<>();
					uniConnection.put("uniCircuitInfo", uniCircuitInfo);
					uniConnection.put("circuitName", (String) uni.get("cktid"));
					uniConnection.put("location", (String) uni.get("cktid"));
					uniConnection.put("cTag_start", cTagStart);
					uniConnection.put("cTag_end", cTagEnd);
					uniConnection.put("evcNci", evcNci);
					uniConnection.put("nniInfo", nniInfo);
					uniConnection.put("classOfService", classOfService);
					uniConnection.put("evcBandwidth", evcBandwidth);
					if(StringUtils.isBlank(evcName))
						uniConnection.put("isNewLocation",false );
					else
						uniConnection.put("isNewLocation", true);
					uniConnections.add(uniConnection);
				}
			}

			if (StringUtils.equals(ncCode, "VLM-")) {
				List<Map<String, Object>> uniConnsForEvplan = new ArrayList<>();
				if (!uniConnections.isEmpty()) {
					uniConnsForEvplan = buildEVPLANRoute(session, evcAliasCktId, uniConnections);
				}
				transformed.put("uniConnections", uniConnsForEvplan);
			} else {
				transformed.put("uniConnections", uniConnections);
				if (!uniConnections.isEmpty()) {
					List<Map<String, Object>> evcRoute = new ArrayList<Map<String,Object>>();
					if (isnewEvc == true) {
						evcRoute = buildEvcRoute(session, uniConnections);
					}

					transformed.put("evcRoute", evcRoute);
					List<Map<String, Object>> alternateRoutes = getAlternateRoutes(session, uniConnections);
					transformed.put("alternateRoutes", alternateRoutes);
					transformed.put("routesCount", (alternateRoutes.isEmpty() ? 0 : alternateRoutes.size()));
				}
			}

			evcInfo = transformed;
			evcInfo.put("isEvcOrder", false);

			response.setCode(200);
			response.setMessage("EVC Order info fetched successfully");
			response.setData(evcInfo);
			/*responseData.put("pathFrom", sourceDevName.toUpperCase());
			responseData.put("pathTo", targetDevName.toUpperCase());

			try (Session session = driver.session()) {
				List<Map<String, Object>> altRoutes = getAlternateRoutesV1(session, sourceDevName, targetDevName, null);
				List<Map<String, Object>> routesList = new ArrayList<>();

				for (Map<String, Object> altRoute : altRoutes) {
					Map<String, Object> routeMap = new HashMap<>();
					routeMap.put("route", altRoute.getOrDefault("route", new ArrayList<>()));
					routesList.add(routeMap);
				}

				responseData.put("routesList", routesList);
			} catch (Exception e) {
				log.error("Failed to fetch alternate routes: {}", e.getMessage(), e);
				responseData.put("routesList", new ArrayList<>()); // fallback empty list
			}

			response.setCode(200);
			response.setMessage("Successfully found alternate routes.");
			response.setData(responseData);*/

		} catch (Exception ex) {
			log.error("findEVCRoute - Exception: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Exception occurred during route finding.");
		}

		log.info("<=EthernetOrderService:findEVCRoute: END");
		return response;
	}

	public ChangeOrderResponse saveUNIChangeOrderV2(Map<String, Object> assignedInventoryBody) {
		log.info("=> EthernetOrderService:saveUNIChangeOrder: START");
		log.info("Save UNIChange Order Info: {}", assignedInventoryBody.toString());

		ChangeOrderResponse response = new ChangeOrderResponse();
		response.setCode(500);
		response.setMessage("Failed");
		try {
			// 1. Validate required fields
			String circuitId = (String) assignedInventoryBody.get("circuitId");
			String oldCircuitId = (String) assignedInventoryBody.get("oldCircuitId");

			String effectiveCircuitId;

			if (StringUtils.isNotBlank(oldCircuitId)) {
				effectiveCircuitId = oldCircuitId;
			} else if (StringUtils.isNotBlank(circuitId)) {
				effectiveCircuitId = circuitId;
			} else {
				throw new IllegalArgumentException("Missing circuitId in request");
			}

			Map<String, Object> productPayload = (Map<String, Object>) assignedInventoryBody.get("productPayload");

			if (productPayload == null) {
				throw new IllegalArgumentException("Missing productPayload");
			}

			List<Map<String, Object>> productOrderItems = (List<Map<String, Object>>) productPayload
					.get("productOrderItem");

			if (CollectionUtils.isEmpty(productOrderItems)) {
				throw new IllegalArgumentException("Missing productOrderItem");
			}

			Map<String, Object> product = (Map<String, Object>) productOrderItems.get(0).get("product");

			List<Map<String, Object>> characteristics = (List<Map<String, Object>>) product
					.get("productCharacteristic");

			String nc = null;
			String nci = null;
			String secnci = null;

			for (Map<String, Object> c : characteristics) {
				String name = (String) c.get("name");
				String value = (String) c.get("value");

				if ("nc".equalsIgnoreCase(name)) {
					nc = value;
				} else if ("nci".equalsIgnoreCase(name)) {
					nci = value;
				} else if ("secnci".equalsIgnoreCase(name)) {
					secnci = value;
				}
			}

			if (StringUtils.isBlank(nc)) {
				throw new IllegalArgumentException("Missing nc in request");
			}
			if (StringUtils.isBlank(nci)) {
				throw new IllegalArgumentException("Missing nci in request");
			}
			if (StringUtils.isBlank(secnci)) {
				throw new IllegalArgumentException("Missing secnci in request");
			}
			String aliasCktId = effectiveCircuitId.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
			String user = (String) assignedInventoryBody.get("user");

			// 2. Get Bandwidth from DWH
			Map<String, Object> bwMap = inventorySearchRepo.bandwidthByNCNCI(nc, nci, secnci);
			if (bwMap == null || bwMap.isEmpty()) {
				throw new IllegalStateException("Bandwidth info not found for provided NC/NCI/Secondary NCI");
			}

			String rawBw = (String) bwMap.get("bandwidth");
			if (StringUtils.isBlank(rawBw)) {
				throw new IllegalStateException("Empty bandwidth value returned from DWH");
			}

			String convertedBw = ApplicationUtils.bandwidthConvertor(rawBw, true, false);
			long requestedBw = Long.parseLong(convertedBw);

			// 3. Check if UNIConnection exists
			Map<String, Object> portMap = null;
			long portSpeed = 0;

			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();

				String uniQuery = "MATCH (n:UNIConnection)-[:CONNECTED_TO]->(up:EquipmentPort)"
						+ "WHERE (n.aliasCktId = $aliasCktId OR n.cktid = $circuit) AND n.deletedTimeStamp IS NULL AND up.EquipmentPort IS NULL "
						+ "RETURN n AS uni, up AS port LIMIT 1";

				Map<String, Object> queryParams = Map.of("circuit", effectiveCircuitId, "aliasCktId", aliasCktId);
				Result result = tx.run(uniQuery, queryParams);

				if (!result.hasNext()) {
					throw new IllegalStateException("No matching UNI Circuit found : " + effectiveCircuitId);
				}

				Record record = result.next();
				portMap = record.get("port").asMap();

				String portSpeedRaw = (String) portMap.get("bw");
				portSpeed = Long.parseLong(ApplicationUtils.bandwidthConvertor(portSpeedRaw, true, false));
			}

			ObjectMapper mapper = new ObjectMapper();

			String productPayloadJson = mapper.writeValueAsString(assignedInventoryBody.get("productPayload"));

			String engineeringFacilitiesJson = mapper
					.writeValueAsString(assignedInventoryBody.get("engineeringFacilities"));

			// 4. Save UNIChangeOrder only after all validations pass
			Map<String, Object> properties = new HashMap<>(assignedInventoryBody);
			properties.put("createdOn", Instant.now().toString());
			properties.put("aliasCktId", aliasCktId);
			properties.put("circuitName", effectiveCircuitId);
			properties.put("productPayload", productPayloadJson);
			properties.put("engineeringFacilities", engineeringFacilitiesJson);

			try (Session session = driver.session()) {
				Transaction tx = session.beginTransaction();

				StringBuilder mergeQuery = new StringBuilder(
						"MERGE (n:UNIChangeOrder {circuitName: $circuitName}) SET ");
				String propertyAssignments = properties.entrySet().stream()
						.filter(entry -> !"circuitName".equals(entry.getKey()))
						.map(entry -> "n." + entry.getKey() + " = $" + entry.getKey())
						.collect(Collectors.joining(", "));
				mergeQuery.append(propertyAssignments);

				tx.run(mergeQuery.toString(), properties);
				tx.commit();
				log.info("UNIChangeOrder saved for circuit: {}", effectiveCircuitId);
			}

			// 5. Dispatch flag
			boolean dispatchedRequired = false;
			List<Map<String, Object>> engineeringFacilities = (List<Map<String, Object>>) assignedInventoryBody
					.get("engineeringFacilities");

			if (!CollectionUtils.isEmpty(engineeringFacilities)) {
				for (Map<String, Object> ef : engineeringFacilities) {
					Object val = ef.get("value");
					if (val != null && StringUtils.isNotBlank(val.toString())) {
						dispatchedRequired = true;
						break;
					}
				}
			}

			// 6. Update bandwidth on UNIConnection if requestedBw <= portSpeed
			if (requestedBw <= portSpeed) {
				try (Session session = driver.session()) {
					Transaction tx = session.beginTransaction();

					String updateUniQuery = "MATCH (n:UNIConnection) "
							+ "WHERE (n.aliasCktId = $aliasCktId OR n.cktid = $circuit) AND n.deletedTimeStamp IS NULL "
							+ "SET n.bandwidth = $bandwidth, " + "    n.updatedBy = $updatedBy, "
							+ "    n.updatedOn = $updatedOn";

					Map<String, Object> updateParams = Map.of("circuit", effectiveCircuitId, "aliasCktId", aliasCktId,
							"bandwidth", requestedBw, "updatedBy", user, "updatedOn", Instant.now().toString());

					tx.run(updateUniQuery, updateParams);
					log.info("UNIConnection updated with bandwidth: {}", requestedBw);

					String updateAllCircuitsQuery = "MATCH (n:allCircuits) "
							+ "WHERE (n.aliasCktId = $aliasCktId OR n.circuitName = $circuit) AND n.deletedTimeStamp IS NULL "
							+ "SET n.bandwidth = $bandwidth, " + "    n.updatedBy = $updatedBy, "
							+ "    n.updatedOn = $updatedOn";
					tx.run(updateAllCircuitsQuery, updateParams);
					log.info("allCircuits updated with bandwidth: {}", requestedBw);
					tx.commit();
					response.setCode(200);
					response.setMessage("UNI Change Order saved and updated bandwidth successfully.");
					response.setIsDispatchRequired(dispatchedRequired);
				}
			} else {
				log.warn("Requested bandwidth [{}] exceeds port speed [{}]. Skipping UNIConnection update.",
						requestedBw, portSpeed);
				response.setCode(200);
				response.setMessage(
						"UNI Change Order saved. Bandwidth update skipped: requested BW exceeds port speed.");
				response.setIsDispatchRequired(dispatchedRequired);
			}

		} catch (IllegalArgumentException | IllegalStateException ex) {
			log.warn("Validation failed: {}", ex.getMessage());
			response.setCode(400);
			response.setMessage("Validation failed: " + ex.getMessage());
		} catch (Exception ex) {
			log.error("Unexpected error in saveUNIChangeOrder: {}", ex.getMessage(), ex);
			response.setCode(500);
			response.setMessage("Failed to save UNIChange order. Reason: " + ex.getMessage());
		}

		log.info("<= EthernetOrderService:saveUNIChangeOrder: END");
		return response;
	}
	public ResponseStatus getAddressByClli(String clli) {
		log.info("=>EthernetOrderService:getAddressByClli: START");
		log.info("Requested clli for address: {}", clli);
		ResponseStatus response = new ResponseStatus();
		response.setCode(500);
		response.setMessage("Failed");

		List<Map<String, Object>> clliList = new ArrayList<>();
		try {
			clliList = inventorySearchRepo.getAddressByClli(clli);

			if (clliList == null || clliList.isEmpty()) {
				log.error("Address not found. ");
				response.setCode(HttpStatus.OK.value());
				response.setMessage("Address not found.");
				response.setData(clliList);
			} else {
				response.setCode(HttpStatus.OK.value());
				response.setMessage("Successfully found address");
				response.setData(clliList);
			}
		} catch (Exception ex) {
			log.error("Address not found!. Reason - '{}'", ex.getMessage());
			response.setCode(HttpStatus.NO_CONTENT.value());
			response.setMessage("Address not found.");
			response.setData(clliList);
		}

		log.info("<= EthernetOrderService:getAddressByClli: END");
		return response;
	}
}