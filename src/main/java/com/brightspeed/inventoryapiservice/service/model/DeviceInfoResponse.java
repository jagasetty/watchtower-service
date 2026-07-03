package com.brightspeed.inventoryapiservice.service.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DeviceInfoResponse {
	private String clliExists = "N";
	private String nidExists = "N";
	private String capacityExists = "N";
	private String portAvailable = "N";
	private String nmiAvailable = "N";
	private List<Map<String, Object>> equipments = new ArrayList<Map<String, Object>>();

	public String getClliExists() {
		return clliExists;
	}

	public void setClliExists(String clliExists) {
		this.clliExists = clliExists;
	}

	public String getNidExists() {
		return nidExists;
	}

	public void setNidExists(String nidExists) {
		this.nidExists = nidExists;
	}

	public String getCapacityExists() {
		return capacityExists;
	}

	public void setCapacityExists(String capacityExists) {
		this.capacityExists = capacityExists;
	}

	public String getPortAvailable() {
		return portAvailable;
	}

	public void setPortAvailable(String portAvailability) {
		this.portAvailable = portAvailability;
	}

	public List<Map<String, Object>> getEquipments() {
		return equipments;
	}

	public void setEquipments(List<Map<String, Object>> equipments) {
		this.equipments = equipments;
	}

	public String getNmiAvailable() {
		return nmiAvailable;
	}

	public void setNmiAvailable(String nmiAvailable) {
		this.nmiAvailable = nmiAvailable;
	}

	public Map<String, Object> getDeviceInfoMap(List<String> clliList) {

		// TBD: Flags to be set here
		ObjectMapper objectMapper = new ObjectMapper();
		Map<String, Object> devInfo = objectMapper.convertValue(this, Map.class);

		return devInfo;
	}

}
