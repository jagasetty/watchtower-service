package com.brightspeed.inventoryapiservice.util;

import java.nio.charset.Charset;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.Values;
import org.springframework.http.HttpHeaders;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApplicationUtils {



	public static String supportedBandwidth(int bw) {
		String suppBw = "";

		switch (bw) {
		case 10:
			suppBw = "'10MB','10/100MB', '10/100/1000','10/100/1000MB'";
			break;
		case 50:
			suppBw = "'50MB'";
			break;
		case 100:
			suppBw = "'100MB','10/100MB', '100/1000','10/100/1000','10/100/1000MB'";
			break;
		case 150:
			suppBw = "'150MB'";
			break;
		case 800:
			suppBw = "'800MB'";
			break;
		case 1000:
			suppBw = "'10/100/1000','10/100/1000MB','100/1000','1G','1GE','1G/10G'";
			break;
		case 2500:
			suppBw = "'2.5GE'";
			break;
		case 4000:
			suppBw = "'4GE'";
			break;
		case 10000:
			suppBw = "'10GE','1G/10G'";
			break;
		case 40000:
			suppBw = "'40GE'";
			break;
		case 100000:
			suppBw = "'100GE'";
			break;
		case 200000:
			suppBw = "'200GE'";
			break;
		default:
			suppBw = "";
		}
		return suppBw;
	}

	public static HttpHeaders createAssuranceOAuthHeaders(String username, String password) {
		return new HttpHeaders() {
			private static final long serialVersionUID = 1L;
			{
				String auth = username + ":" + password;
				byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(Charset.forName("US-ASCII")));
				String authHeader = "Basic " + new String(encodedAuth);
				set("Authorization", authHeader);
			}
		};
	}

	public static HttpHeaders createOAuthTokenHeader(String token) {
		return new HttpHeaders() {
			private static final long serialVersionUID = 1L;
			{
				String bearerToken = "Bearer " + token;
				set("Authorization", bearerToken);
				set("Content-Type", "application/json");
			}
		};
	}

	public static String bandwidthConvertor(String bw, boolean convertInMbps, boolean unitRequired) {
		String bandwidth = "";
		
		if((StringUtils.isBlank(bw)) || (StringUtils.equalsIgnoreCase(bw, "NULL"))) {
			return bandwidth = "0";
		}
		
		bw = bw.trim();
		
		String[] rtSplitedArr = bw.split("/");
		int arraySize = rtSplitedArr.length;

		String bwStr = rtSplitedArr[0];
		if (arraySize > 1) {
			bwStr = rtSplitedArr[rtSplitedArr.length - 1];
		}

		String bwUnit = bwStr.replaceAll("[^a-zA-Z]", "").trim().toUpperCase();
		String bwValStr = bwStr.replaceAll("[^0-9.]", "").trim().toUpperCase();
		
		int val = 0;
	    try {
	    	if(StringUtils.isNotBlank(bwValStr)) {
	        val = (int)Double.parseDouble(bwValStr);
	    	}
	    } catch (NumberFormatException e) {
	        return "";
	    }

		if ((StringUtils.equalsIgnoreCase(bwUnit, "G")) || (StringUtils.equalsIgnoreCase(bwUnit, "GE"))
				|| (StringUtils.equalsIgnoreCase(bwUnit, "GBPS"))) {

			if(convertInMbps) {
				val = val * 1000;
				bandwidth = String.valueOf(val) + "Mbps";
			}else {
				bandwidth = bwValStr + "GE";
			}
		} else if ((StringUtils.equalsIgnoreCase(bwUnit, "MB")) || (StringUtils.equalsIgnoreCase(bwUnit, "M"))
				|| (StringUtils.equalsIgnoreCase(bwUnit, "MBPS")) || (StringUtils.equalsIgnoreCase(bwUnit, "MPS"))) {

			if(convertInMbps) {
				bandwidth = bwValStr + "Mbps";
			}else {
				if (val >= 1000) {
					if (val == 2500)
						bandwidth = "2.5GE";
					else {
						val = val / 1000;
						bandwidth = String.valueOf(val) + "GE";
					}
				} else {
					bandwidth = bwValStr + "MB";
				}
			}
		}else if(StringUtils.isBlank(bwUnit)) {

			if(convertInMbps) {
				bandwidth = bwValStr + "Mbps";
			}else {
				if (val >= 1000) {
					if (val == 2500)
						bandwidth = "2.5GE";
					else {
						val = val / 1000;
						bandwidth = String.valueOf(val) + "GE";
					}
				} else {
					bandwidth = bwValStr + "MB";
				}
			}
		}
		if(!unitRequired && convertInMbps) {
			bandwidth = StringUtils.substringBefore(bandwidth, "Mbps");
		}
		return bandwidth;
	}

	public static String getDeviceCategory(String devroles, boolean isNID) {
		String devCat = "";
		if (StringUtils.contains(devroles, "NID") && isNID) {
			devCat = "NID";
		} else if (StringUtils.contains(devroles, "MER") || StringUtils.contains(devroles, "NPE")) {
			devCat = "Core";
		} else if (StringUtils.contains(devroles, "MER") || StringUtils.contains(devroles, "MCR")
				|| StringUtils.contains(devroles, "MGR") || StringUtils.contains(devroles, "RCR")
				|| StringUtils.contains(devroles, "RFR1") || StringUtils.contains(devroles, "RER1")
				|| StringUtils.contains(devroles, "DCR") || StringUtils.contains(devroles, "DGR")
				|| StringUtils.contains(devroles, "MAS") || StringUtils.contains(devroles, "MFR")) {
			devCat = "SR";
		} else if (StringUtils.contains(devroles, "AGG") || StringUtils.contains(devroles, "SAT")) {
			devCat = "SA";
		} else if (StringUtils.contains(devroles, "NNI") || StringUtils.contains(devroles, "MSO")
				|| StringUtils.contains(devroles, "TWR") || StringUtils.contains(devroles, "NID")) {
			devCat = "NID";
		}

		return devCat;
	}
	
	/**
     * Derives the connector type from the given interface type.
     * <p>
     * Rules:
     * <ul>
     *   <li>If {@code interfaceType} is {@code null}, defaults to {@link #CONNECTOR_SFP SFP}.</li>
     *   <li>If the interface type contains {@code RJ45}, returns {@link #CONNECTOR_RJ45 RJ45}.</li>
     *   <li>If the interface type contains {@code XFP}, returns {@link #CONNECTOR_XFP XFP}.</li>
     *   <li>Otherwise, defaults to {@link #CONNECTOR_SFP SFP}.</li>
     * </ul>
     *
     * @param interfaceType the raw interface type string (can be {@code null})
     * @return the derived connector type as a String
     */
	public static String deriveConnector(String interfaceType) {
		if (interfaceType == null)
		{
			log.debug("Interface type is null. Defaulting to connector: {}", Constants.CONNECTOR_SFP);
            return Constants.CONNECTOR_SFP;
		}
		interfaceType = interfaceType.toUpperCase();
		log.info("Deriving connector for interface type: {}", interfaceType);

		if (interfaceType.contains(Constants.CONNECTOR_RJ45))
		{
			log.info("Matched connector: {}", Constants.CONNECTOR_RJ45);
			return Constants.CONNECTOR_RJ45;
		}
			
		else if (interfaceType.contains(Constants.CONNECTOR_XFP))
		{
			log.info("Matched connector: {}", Constants.CONNECTOR_XFP);
			return Constants.CONNECTOR_XFP;
		}
			
		else
		{
			log.info("No match found. Defaulting to connector: {}", Constants.CONNECTOR_SFP);
			return Constants.CONNECTOR_SFP;
		}
			
	}

	/**
	 * Converts a given bandwidth string into its equivalent value in Mbps.
	 * <p>
	 * Supported formats:
	 * <ul>
	 *   <li>{@code "<n>GE"} → Interpreted as GigE (e.g., {@code "1GE"} → {@code 1000 Mbps},
	 *       {@code "2.5GE"} → {@code 2500 Mbps} as a special case)</li>
	 *   <li>{@code "<n>MB"} → Interpreted directly as Mbps (e.g., {@code "500MB"} → {@code 500 Mbps})</li>
	 * </ul>
	 *
	 * <p>
	 * If the input string is blank (checked using {@link org.apache.commons.lang3.StringUtils#isBlank(CharSequence)}),
	 * or does not end with {@code GE} or {@code MB}, this method returns {@code 0}.
	 *
	 * @param bw the bandwidth string (e.g., {@code "1GE"}, {@code "2.5GE"}, {@code "500MB"})
	 * @return the bandwidth in Mbps, or {@code 0} if the input is invalid or blank
	 */
    public static double toMbps(String bw) {
        if (StringUtils.isBlank(bw)) return 0;

        String normalized = bandwidthConvertor(bw, false,true);
        log.info("Normalized bandwidth: {}", normalized);
        if (normalized.endsWith(Constants.GE)) {
            String num = normalized.replace(Constants.GE, Constants.EMPTY_STRING);
            if (num.equals(Constants.SPECIAL_BW_2_5_GE)) 
            	{
            	log.info("Special bandwidth case: 2.5GE -> 2500 Mbps");
            	return 2500;
            	}
            double mbps = Double.parseDouble(num) * 1000;
            log.info("Converted {}GE to {} Mbps", num, mbps);
            return mbps;
        } else if (normalized.endsWith(Constants.MB)) {
            String num = normalized.replace(Constants.MB, Constants.EMPTY_STRING);
            double mbps = Double.parseDouble(num);
            log.info("Converted {}MB to {} Mbps", num, mbps);
            return mbps;
        }
        log.error("Unsupported bandwidth format: {}. Returning 0.", bw);
        return 0;
    }
    
    /**
     * Utility method to safely handle potential {@code null} values.
     * <p>
     * If the provided {@code value} is non-null, it is returned as-is.
     * Otherwise, it returns {@link org.neo4j.driver.Values#NULL}, which is
     * the Neo4j driver's representation of a null value.
     * </p>
     *
     * @param value the input object that may be {@code null}.
     * @return the same object if not {@code null}, otherwise {@link Values#NULL}.
     */
    public static Object isNull(Object value) {
		return value != null ? value : Values.NULL;
	}
    
    /**
     * Normalizes the given bandwidth string into a numeric value in megabits per second (Mbps).
     * <p>
     * The method converts bandwidth representations such as {@code "1GE"}, {@code "10GE"},
     * {@code "100MB"} into their equivalent numeric values in Mbps:
     * <ul>
     *     <li>{@code "1GE"} → {@code 1000}</li>
     *     <li>{@code "10GE"} → {@code 10000}</li>
     *     <li>{@code "100MB"} → {@code 100}</li>
     *     <li>Special case: {@code "2.5GE"} → {@code 2500}</li>
     * </ul>
     * If the input is blank or invalid, {@code 0} is returned.
     * </p>
     *
     * @param bw the raw bandwidth string (e.g., "1GE", "100MB", "2.5GE")
     * @return the normalized bandwidth as a double in Mbps, or {@code 0} if blank/invalid
     */
    public static double normalizeBandwidth(String bw) {
		if (StringUtils.isBlank(bw)) return 0;

        String normalized = ApplicationUtils.bandwidthConvertor(bw, false,true);

        if (normalized.endsWith(Constants.GE)) {
            String num = normalized.replace(Constants.GE, Constants.EMPTY_STRING);
            if (num.equals(Constants.SPECIAL_BW_2_5_GE)) return 2500; // special case
            return Double.parseDouble(num) * 1000;
        } else if (normalized.endsWith(Constants.MB)) {
            String num = normalized.replace(Constants.MB, Constants.EMPTY_STRING);
            return Double.parseDouble(num);
        }
        return 0;
    
	}

}
