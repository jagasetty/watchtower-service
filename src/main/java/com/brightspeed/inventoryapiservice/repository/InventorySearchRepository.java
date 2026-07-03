package com.brightspeed.inventoryapiservice.repository;

import com.brightspeed.inventoryapiservice.config.BigQueryConnectionConfig;
import com.brightspeed.inventoryapiservice.service.model.DeviceLocation;
import com.google.cloud.bigquery.*;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@Slf4j
public class InventorySearchRepository {

    BigQueryConnectionConfig bigQueryConfig;

    public InventorySearchRepository(BigQueryConnectionConfig bigQueryConfig) {
        this.bigQueryConfig = bigQueryConfig;
    }

	public List<String> findClliByLocation(DeviceLocation location) {

		String queryStr = "SELECT DISTINCT location.SITE_CLLI as clli "
				+ " FROM da-prod-rpt-wholesale.RPT_WHOLESALE_SNDBX_DS.USIL_CLONES_DATA_FROM_ICONECTIV  location"
				+ " WHERE location.UNFORMATTED_ADDRESS_DATA = '" + location.getAddressLine() + "'";

		if (StringUtils.isNotBlank(location.getCity())) {
			queryStr += " AND location.city = '" + location.getCity() + "'";
		}
		if (StringUtils.isNotBlank(location.getState())) {
			queryStr += " AND location.state_name = '" + location.getState() + "'";
		}
		if (StringUtils.isNotBlank(location.getZip())) {
			queryStr += " AND location.POSTAL_CODE = '" + location.getZip() + "'";
		}
		if (StringUtils.isNotBlank(location.getCountry())) {
			queryStr += " AND location.COUNTRY_CODE = '" + location.getCountry() + "'";
		}
		/* TBD: Comment temporarily to handle differently
		if (Double.isNaN(location.getLatitude())) {
			queryStr += " AND location.LAT = " + location.getLatitude();
		}
		if (Double.isNaN(location.getLongitude())) {
			queryStr += " AND location.LONG = " + location.getLongitude();
		}*/

		log.info("CLLI Query : {}", queryStr);
		// query
		QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(queryStr).build();

		List<String> cilliList = new ArrayList<>();
		try {
			TableResult result = bigQueryConfig.bigQuery().query(queryConfig);
			log.info(result.getTotalRows() + " number of rows");

			if (result != null && result.getTotalRows() > 0) {

				result.iterateAll().forEach(row -> {
					/* clli */
					String clli = getValueOrDefault(row, "clli");
					cilliList.add(clli);
				});

			}
		} catch (Exception e) {
			log.error("Error running query: {}", e.getMessage(), e);
		}
		return cilliList;
	}

	public List<Map<String, Object>> findDeviceByLocation(DeviceLocation location) {

		String query = "SELECT DISTINCT dev.CLLI as clli, dev.vendor as vendor, dev.type as model, dev.deviceType as deviceSeries, dev.nodeType as nodeType,"
				+ " dev.node_id as node_id, dev.relayrck as relayrck, dev.base_heci as base_heci, dev.TID as TID"
				+ " FROM da-prod-rpt-wholesale.RPT_WHOLESALE_SNDBX_DS.USIL_DEVICE_DATA  dev "
				+ " INNER JOIN  da-prod-rpt-wholesale.RPT_WHOLESALE_SNDBX_DS.USIL_CLONES_DATA_FROM_ICONECTIV  location"
				+ " ON dev.CLLI = location.SITE_CLLI ";

		if (StringUtils.isNotBlank(location.getAddressLine())) {
			query += " AND location.UNFORMATTED_ADDRESS_DATA = '" + location.getAddressLine() + "'";
		}
		if (StringUtils.isNotBlank(location.getCity())) {
			query += " AND location.city = '" + location.getCity() + "'";
		}
		if (StringUtils.isNotBlank(location.getState())) {
			query += "  AND location.state_name = '" + location.getState() + "'";
		}
		if (StringUtils.isNotBlank(location.getZip())) {
			query += " AND location.POSTAL_CODE = '" + location.getZip() + "'";
		}
		if (StringUtils.isNotBlank(location.getCountry())) {
			query += " AND location.COUNTRY_CODE = '" + location.getCountry() + "'";
		}
		if (Double.isNaN(location.getLatitude())) {
			query += " AND location.LAT = " + location.getLatitude();
		}
		if (Double.isNaN(location.getLongitude())) {
			query += " AND location.LONG = " + location.getLongitude();
		}

		// query
		QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();

		List<Map<String, Object>> cilliList = new ArrayList<>();
		try {
			TableResult result = bigQueryConfig.bigQuery().query(queryConfig);
			log.info(result.getTotalRows() + " number of rows");

			if (result != null && result.getTotalRows() > 0) {

				result.iterateAll().forEach(row -> {
					/* node_id, relayrck, base_heci, TID, vendor, model, nodeType */
					String node_id = getValueOrDefault(row, "node_id");
					String relayrck = getValueOrDefault(row, "relayrck");
					String base_heci = getValueOrDefault(row, "base_heci");
					String name = getValueOrDefault(row, "TID");
					String vendor = getValueOrDefault(row, "vendor");
					String model = getValueOrDefault(row, "model");
					String nodeType = getValueOrDefault(row, "nodeType");
					String clli = getValueOrDefault(row, "clli");

					Map<String, Object> clliDetailMap = new HashMap<>();
					clliDetailMap.put("clli", clli);
					clliDetailMap.put("node_id", node_id);
					clliDetailMap.put("relayrck", relayrck);
					clliDetailMap.put("base_heci", base_heci);
					clliDetailMap.put("deviceName", name);
					clliDetailMap.put("vendor", vendor);
					clliDetailMap.put("model", model);
					clliDetailMap.put("nodeType", nodeType);
					cilliList.add(clliDetailMap);
				});

			}
		} catch (Exception e) {
			log.error("Error running query: {}", e.getMessage(), e);
		}
		return cilliList;
	}

	public List<Map<String, Object>> getFiberConnInfo(String clliA, String clliZ, String cableName, String strandId,
			int connNum) {

		String frmTable = "";
		String hdrTable = "";
		String termA = clliA;
		String termZ = clliZ;

		if (StringUtils.equalsIgnoreCase(clliA, clliZ)) {
			frmTable = "da-tsa-pr.AV_DWSTGORA.TRKTCI_FRAME_DAILY_MV_AV";
			hdrTable = "da-tsa-pr.AV_DWSTGORA.TRKTCI_HDR_DAILY_MV_AV";
		} else {
			frmTable = "da-tsa-pr.AV_DWSTGORA.TRKFCI_FRAME_DAILY_MV_AV";
			hdrTable = "da-tsa-pr.AV_DWSTGORA.TRKFCI_HDR_DAILY_MV_AV";
		}
		String fiberQuery = "SELECT FRSTPAIR, TRMA, TERMA, TRMZ, TERMZ, FACTYPE, SUBPATH, CABLE, ADRST,"
				+ " LENGTH, TWAVLTH1, TFLAISLA, TBAYA, TFLAISLZ, TBAYZ,TFRMTYPA,TFRMTYPZ, TAZATTA, TAZATTZ, TZAATTA,TZAATTZ "
				+ " FROM " + hdrTable
				+ " WHERE ((TERMA = '" + clliA + "' AND TERMZ = '" + clliZ + "') OR (TERMA = '" + clliZ + "' AND TERMZ = '" + clliA + "'))";

		if (StringUtils.isNotBlank(cableName)) {
			fiberQuery += " AND CABLE = '" + cableName + "'";
		}
		if (StringUtils.isNotBlank(cableName)) {
			fiberQuery += " AND FRSTPAIR = '" + strandId + "'";
		}

		log.info(" Fiber Connection Query:{} ", fiberQuery);
		// query
		QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(fiberQuery).build();

		List<Map<String, Object>> fiberConnList = new ArrayList<Map<String, Object>>();
		try {
			TableResult result = bigQueryConfig.bigQuery().query(queryConfig);
			log.info(" number of rows: " + result.getTotalRows());

			if (result != null && result.getTotalRows() > 0) {

				result.iterateAll().forEach(row -> {

					String aTERM = getValueOrDefault(row, "TERMA");
					String zTERM = getValueOrDefault(row, "TERMZ");
					String aTrm = getValueOrDefault(row, "TRMA");
					String zTrm = getValueOrDefault(row, "TRMZ");

					String cable = getValueOrDefault(row, "CABLE");
					String strand = getValueOrDefault(row, "FRSTPAIR");

					String aFramType = getValueOrDefault(row, "TFRMTYPA");
					String zFramType = getValueOrDefault(row, "TFRMTYPZ");
					String aAttAZ = getValueOrDefault(row, "TAZATTA");
					String zAttAZ = getValueOrDefault(row, "TAZATTZ");
					String aAttZA = getValueOrDefault(row, "TZAATTA");
					String zAttZA = getValueOrDefault(row, "TZAATTZ");
					String length = getValueOrDefault(row, "LENGTH");
					String adrst = getValueOrDefault(row, "ADRST");// Need to know its meaning???
					String wl = getValueOrDefault(row, "TWAVLTH1");
					String subpath = getValueOrDefault(row, "SUBPATH");
					String connType = getValueOrDefault(row, "FACTYPE");
					String aRack = getValueOrDefault(row, "TFLAISLA");
					String aBay = getValueOrDefault(row, "TBAYA");
					String aRelayRack = aRack + "." + aBay;
					String zRack = getValueOrDefault(row, "TFLAISLZ");
					String zBay = getValueOrDefault(row, "TBAYZ");
					String zRelayRack = zRack + "." + zBay;

					Map<String, Object> fiberConnMap = new LinkedHashMap<>();

					fiberConnMap.put("aClli", clliA);
					fiberConnMap.put("zClli", clliZ);
					fiberConnMap.put("cableName", cable);
					fiberConnMap.put("strandId", strand);
					fiberConnMap.put("connectionNumber", connNum);

					fiberConnMap.put("length", length);
					fiberConnMap.put("wavelength", wl);
					fiberConnMap.put("subpath", subpath);
					fiberConnMap.put("connectionType", connType);

					if (connNum % 2 == 0) {
						fiberConnMap.put("aAttr", aAttZA);
						fiberConnMap.put("zAttr", zAttZA);
					} else {
						fiberConnMap.put("aAttr", aAttAZ);
						fiberConnMap.put("zAttr", zAttAZ);
					}
					if (StringUtils.equals(clliA, aTERM)) {

						fiberConnMap.put("aRelayRack", aRelayRack);
						fiberConnMap.put("zRelayRack", zRelayRack);
						fiberConnMap.put("fta", aFramType);
						fiberConnMap.put("ftz", zFramType);
						fiberConnMap.put("aTrm", aTrm);
						fiberConnMap.put("zTrm", zTrm);

					} else {
						fiberConnMap.put("aRelayRack", zRelayRack);
						fiberConnMap.put("zRelayRack", aRelayRack);
						fiberConnMap.put("fta", zFramType);
						fiberConnMap.put("ftz", aFramType);
						fiberConnMap.put("aTrm", zTrm);
						fiberConnMap.put("zTrm", aTrm);

					}

					fiberConnList.add(fiberConnMap);

				});

			}
		} catch (Exception e) {
			log.error("Error running query: {}", e.getMessage(), e);
		}
		return fiberConnList;
	}

	public Map<String, Object> bandwidthByNCNCI(String nc, String nci, String secNci) {

		String bwQuery = "SELECT distinct BANDWIDTH_PROFILE_NM AS bandwidth, INTERFACE_TYPE AS interfaceType, "
				+ " TRANSMISSIONRATE AS transRate, AUTO_NEGOTIATION AS autoNegotiation FROM  da-tsa-pr.AV_STAGE.ARM_NC_NCI_BANDWIDTH_AV"
				+ " WHERE BANDWIDTH_PROFILE_TYPE = 'UNI' AND NC_CD = '" + nc + "' AND NCI_CPE_CD = '" + nci
				+ "' AND NCI_QWEST_CD = '"+ secNci+"' LIMIT 1";

		log.info(" Bandwidth Info Query:{} ", bwQuery);
		// query
		QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(bwQuery).build();

		// List<Map<String, Object>> fiberConnList = new ArrayList<Map<String,
		// Object>>();
		Map<String, Object> bwMap = new LinkedHashMap<>();
		try {
			TableResult result = bigQueryConfig.bigQuery().query(queryConfig);
			log.info(" number of rows: " + result.getTotalRows());

			if (result != null && result.getTotalRows() > 0) {

				result.iterateAll().forEach(row -> {

					String bandwidth = getValueOrDefault(row, "bandwidth");
					String interfaceType = getValueOrDefault(row, "interfaceType");
					String transRate = getValueOrDefault(row, "transRate");
					String autoNegotiation = getValueOrDefault(row, "autoNegotiation");

					bwMap.put("bandwidth", bandwidth);
					bwMap.put("interfaceType", interfaceType);
					bwMap.put("transRate", transRate);
					bwMap.put("autoNegotiation", autoNegotiation);

				});
			}
		} catch (Exception e) {
			log.error("Error running query: {}", e.getMessage(), e);
		}
		return bwMap;
	}

    public String getValueOrDefault(FieldValueList row, String fieldName) {
        return Optional.ofNullable(row.get(fieldName)).filter(fieldValue -> !fieldValue.isNull())
                .map(fieldValue -> fieldValue.getStringValue()).orElse(""); // or null if preferred
    }
    
    public List<Map<String, Object>> getAddressByClli(String clli) {

        List<Map<String, Object>> resultList = new ArrayList<>();
        String queryStr =
        	    "SELECT DISTINCT " +
        	    " NETWORK_ENTITY_DESCRIPTION AS CLLI_DESCRIPTION, " +
        	    " NETWORK_SITE_DESCRIPTION AS CLLI_SITE_DESCRIPTION, " +
        	    " LATA AS CLLI_LATA, " +
        	    " UNFORMATTED_ADDRESS_DATA AS CLLI_ADDRESS, " +
        	    " city AS CLLI_CITY, " +
        	    " state_name AS CLLI_STATE, " +
        	    " POSTAL_CODE AS CLLI_ZIP, " +
        	    " LAT AS CLLI_LAT, " +
        	    " LONG AS CLLI_LONG " +
        	    "FROM `da-prod-rpt-wholesale.RPT_WHOLESALE_SNDBX_DS.USIL_CLONES_DATA_FROM_ICONECTIV` " +
        	    "WHERE FULL_CLLI = '" + clli + "'";

        QueryJobConfiguration queryConfig =
                QueryJobConfiguration.newBuilder(queryStr).build();

        try {
            TableResult result = bigQueryConfig.bigQuery().query(queryConfig);

            if (result != null && result.getTotalRows() > 0) {
                result.iterateAll().forEach(row -> {

                    Map<String, Object> record = new HashMap<>();
                    record.put("CLLI_DESCRIPTION", getValueOrDefault(row, "CLLI_DESCRIPTION"));
                    record.put("CLLI_SITE_DESCRIPTION", getValueOrDefault(row, "CLLI_SITE_DESCRIPTION"));
                    record.put("CLLI_LATA", getValueOrDefault(row, "CLLI_LATA"));
                    record.put("CLLI_ADDRESS", getValueOrDefault(row, "CLLI_ADDRESS"));
                    record.put("CLLI_CITY", getValueOrDefault(row, "CLLI_CITY"));
                    record.put("CLLI_STATE", getValueOrDefault(row, "CLLI_STATE"));
                    record.put("CLLI_ZIP", getValueOrDefault(row, "CLLI_ZIP"));
                    record.put("CLLI_LAT", getValueOrDefault(row, "CLLI_LAT"));
                    record.put("CLLI_LONG", getValueOrDefault(row, "CLLI_LONG"));

                    resultList.add(record);
                });
            }
        } catch (Exception e) {
            log.error("Error running query: {}", e.getMessage(), e);
        }

        return resultList;
    }
}
