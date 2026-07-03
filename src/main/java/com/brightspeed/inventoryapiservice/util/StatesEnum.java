package com.brightspeed.inventoryapiservice.util;

public enum StatesEnum {

	ALABAMA("AL", "ALABAMA"),
    ALASKA("AK", "ALASKA"),
    ARIZONA("AZ", "ARIZONA"),
    ARKANSAS("AR", "ARKANSAS"),
    AMERICAN_SAMOA("AS", "AMERICAN SAMOA"),
    CALIFORNIA("CA", "CALIFORNIA"),
    COLORADO("CO", "COLORADO"),
    CONNECTICUT("CT", "CONNECTICUT"),
    DELAWARE("DE", "DELAWARE"),
    DISTRICT_OF_COLUMBIA("DC", "DISTRICT OF COLUMBIA"),
    FLORIDA("FL", "FLORIDA"),
    GEORGIA("GA", "GEORGIA"),
    GUAM("GU", "GUAM"),
    HAWAII("HI", "HAWAII"),
    IDAHO("ID", "IDAHO"),
    ILLINOIS("IL", "ILLINOIS"),
    INDIANA("IN", "INDIANA"),
    IOWA("IA", "IOWA"),
    KANSAS("KS", "KANSAS"),
    KENTUCKY("KY", "KENTUCKY"),
    LOUISIANA("LA", "LOUISIANA"),
    MAINE("ME", "MAINE"),
    MARYLAND("MD", "MARYLAND"),
    MASSACHUSETTS("MA", "MASSACHUSETTS"),
    MICHIGAN("MI", "MICHIGAN"),
    MINNESOTA("MN", "MINNESOTA"),
    MISSISSIPPI("MS", "MISSISSIPPI"),
    MISSOURI("MO", "MISSOURI"),
    MONTANA("MT", "MONTANA"),
    NEBRASKA("NE", "NEBRASKA"),
    NEVADA("NV", "NEVADA"),
    NEW_HAMPSHIRE("NH", "NEW HAMPSHIRE"),
    NEW_JERSEY("NJ", "NEW JERSEY"),
    NEW_MEXICO("NM", "NEW MEXICO"),
    NEW_YORK("NY", "NEW YORK"),
    NORTH_CAROLINA("NC", "NORTH CAROLINA"),
    NORTH_DAKOTA("ND", "NORTH DAKOTA"),
    NORTHERN_MARIANA_ISLANDS("MP", "NORTHERN MARIANA ISLANDS"),
    OHIO("OH", "OHIO"),
    OKLAHOMA("OK", "OKLAHOMA"),
    OREGON("OR", "OREGON"),
    PENNSYLVANIA("PA", "PENNSYLVANIA"),
    PUERTO_RICO("PR", "PUERTO RICO"),
    RHODE_ISLAND("RI", "RHODE ISLAND"),
    SOUTH_CAROLINA("SC", "SOUTH CAROLINA"),
    SOUTH_DAKOTA("SD", "SOUTH DAKOTA"),
    TENNESSEE("TN", "TENNESSEE"),
    TEXAS("TX", "TEXAS"),
    TRUST_TERRITORIES("TT", "TRUST TERRITORIES"),
    UTAH("UT", "UTAH"),
    VERMONT("VT", "VERMONT"),
    VIRGINIA("VA", "VIRGINIA"),
    VIRGIN_ISLANDS("VI", "VIRGIN ISLANDS"),
    WASHINGTON("WA", "WASHINGTON"),
    WEST_VIRGINIA("WV", "WEST VIRGINIA"),
    WISCONSIN("WI", "WISCONSIN"),
    WYOMING("WY", "WYOMING");

	private final String abbr;
	private final String name;

	StatesEnum(String abbr, String fullName) {
		this.abbr = abbr;
		this.name = fullName;
	}

	public String getAbbreviation() {
		return abbr;
	}

	public String getName() {
		return name;
	}

	public static String getAbbreviationByName(String name) {
		for (StatesEnum state : StatesEnum.values()) {
			if (state.getName().equalsIgnoreCase(name)) {
				return state.getAbbreviation();
			}
		}
		return null;
	}

	public static String getNameByAbbreviation(String abbreviation) {
		for (StatesEnum state : StatesEnum.values()) {
			if (state.getAbbreviation().equalsIgnoreCase(abbreviation)) {
				return state.getName();
			}
		}
		return null;
	}
}
