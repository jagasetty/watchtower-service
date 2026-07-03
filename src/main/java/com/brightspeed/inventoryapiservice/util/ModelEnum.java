package com.brightspeed.inventoryapiservice.util;

public enum ModelEnum {
    //enum(usil model, mastro model)
	CN5142("CN5142", "Ciena 5142"),
    ETX205A("ETX-205A", "RAD ETX205A"),
    EXT2i10G("RAD DATA COMMUNICATIONS INC EXT-2i-10G-B GATEWAY", "RAD etx-2i-10G"),
    CN5164("CIENA 5164 ROUTER", "Ciena 5164"),
    CN5132("CIENA 5132 ROUTER", "Ciena 5132"),
    ETX203AXH("ETX-203AX/BRSD/H/2SFP/4SFP/X", "RAD ETX203AX"),
    ETX203AXGE("ETX-203AX_BRSD/GE/2SFP/2UTP2SFP/X", "RAD ETX203AX"),
    CN3926M("CN3926M", "Ciena 3926M"),
    ETX203AM("ETX-203AM", "RAD ETX203AM"),
    CN8700("8700", "Ciena 8700-04"),
    NCS5500ROUTER("CISCO  NCS-5500 SERIES ROUTER", "Cisco ncs5501-se"),
    CISCONCS5500ROUTER("CISCO SYSTEMS, INC. NCS-5500-SERIES ROUTER", "Cisco ncs5501-se"),
    NCS5500("CISCO NCS-5500", "Cisco ncs5501-se"),
    NCS5501("CISCO NCS 5501", "Cisco ncs5501-se"),
    CISCON540("CISCO N540", "Cisco n540"),
    CN5150("CIENA CN5150", "Ciena 5150"),
    NOKIA7750SR12("7750 SR-12", "Nokia 7750-SR-12"),
    CALIXE72("E7-2", "Calix E72"),
    CALIXE720("E7-20", "Calix E72");

	private final String usilModel;
	private final String mastroEModel;

	ModelEnum(String usilModel, String mastroEModel) {
		this.usilModel = usilModel;
		this.mastroEModel = mastroEModel;
	}

	public String getUsilModel() {
		return usilModel;
	}

	public String getMastroeModel() {
		return mastroEModel;
	}

	public static String getUsilModelByMatroeName(String name) {
		for (ModelEnum model : ModelEnum.values()) {
			if (model.getMastroeModel().equalsIgnoreCase(name)) {
				return model.getUsilModel();
			}
		}
		return name;
	}

	public static String getMastroeNameByUsilModel(String usilName) {
		for (ModelEnum model : ModelEnum.values()) {
			if (model.getUsilModel().equalsIgnoreCase(usilName)) {
				return model.getMastroeModel();
			}
		}
		return usilName;
	}
}
