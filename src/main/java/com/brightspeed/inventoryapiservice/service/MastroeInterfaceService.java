package com.brightspeed.inventoryapiservice.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.brightspeed.inventoryapiservice.util.ServiceClient;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MastroeInterfaceService {

	private ServiceClient serviceClient;
	
	public MastroeInterfaceService(ServiceClient serviceClient) {
		super();
		this.serviceClient = serviceClient;
	}

	public boolean isPortAvailable(Map<String,Object> portInfo) {
		boolean isAvailable = false;
		return isAvailable;
		//1. Request ports can be of multiple ports of same device or different devices. 
		//2. Form the payload and call ServiceClient api to invoke mastreoE API.
		//3. Response will have port availablity info of all ports requested.
		//4. Check whether the requested port is Available.
		//5. Update other ports availability info of this device.
	}

	public int getAvailableStag(Map<String,Object> portInfo) {
		int sTag = 0;

		return sTag;
	}

	public Map<String,Object> getBandwidthInfo(Map<String,Object> portInfo) {
		Map<String,Object> bandwidth = new HashMap<String,Object>();
		return bandwidth;
	}
}
