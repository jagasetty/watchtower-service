package com.brightspeed.inventoryapiservice.dto.request;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Data Transfer Object (DTO) representing a UNI (User Network Interface) connection request.
 * <p>
 * This class is typically used to carry request payload data from the client
 * to the backend service layer for UNI connection–related operations such as
 * creation, validation, or provisioning.
 * </p>
 *
 * <p>
 * The class is annotated with Lombok's {@link Data} annotation to automatically
 * generate getters, setters, {@code equals()}, {@code hashCode()}, and {@code toString()} methods.
 * </p>
 *
 * <p>
 * The fields are grouped into two main categories:
 * <ul>
 *     <li><b>Request Payload Fields</b> – values supplied by the client in the API request.</li>
 *     <li><b>Hardcoded / Default Fields</b> – values typically pre-filled, derived, or set internally.</li>
 * </ul>
 * </p>
 */
@Data
public class UNIConnectionDTO {

    // ========================
    // ✅ Request Payload Fields
    // ========================

    /** Circuit ID associated with the UNI. */
    private String cktid;

    /** External identifier for cross-system reference. */
    private String externalId;

    /** Network Channel Identifier (short form: nc). */
    private String networkChannel;

    /** Specification code or type of the UNI. */
    private String spec;

    /** Current lifecycle state of the UNI (e.g., ACTIVE, PENDING). */
    private String state;

    /** ZIP code of the physical location. */
    private String location_zip;

    /** Latitude coordinate of the physical location. */
    private String location_lat;

    /** Full street address of the location. */
    private String location_address;

    /** Longitude coordinate of the physical location. */
    private String location_long;

    /** Secondary Network Channel Interface (short form: secNci). */
    private String networkChannelInterfaceSec;

    /** Primary Network Channel Interface (short form: nci). */
    private String networkChannelInterface;

    /** State in which the UNI is located. */
    private String location_state;

    /** City in which the UNI is located. */
    private String location_city;

    /** Name of the subscriber (short form). */
    private String subscriberName;

    /** Full legal name of the subscriber. */
    private String subscriberFullName;

    /** Date and time the order was created or submitted. */
    private LocalDateTime orderDate;

    /** Requested action for the UNI (e.g., CREATE, MODIFY, DISCONNECT). */
    private String action;

    // ===============================
    // 🟩 Hardcoded / Default Values
    // ===============================

    /** Type of service associated with the UNI (e.g., EVC, DIA). */
    private String serviceType;

    /** Circuit format identifier. */
    private String cktfmt;

    /** Maximum number of EVCs or OVCs allowed for this UNI. */
    private int noOfEVCs_OVCsAllowed;

    /** Auto-negotiation setting for the port (e.g., ENABLED/DISABLED). */
    private String autoNegotiate;

    /** Type of subscriber (e.g., RESIDENTIAL, BUSINESS). */
    private String subcriberType;

    /** Current operational status (e.g., ACTIVE, INACTIVE). */
    private String status;

    /** Whether bundling is enabled for this UNI. */
    private String bundling;

    /** Whether all-to-1 bundling is enabled. */
    private String allTo1Bundling;

    /** Name or code of the requesting affiliate or partner. */
    private String requestingAffiliate;

    /** Functional status or operational readiness indicator. */
    private String functionalStatus;

    /** MCO (Master Control Office) or responsible organization. */
    private String MCO;

    /** Username or ID of the user initiating the request. */
    private String user;

    /** Source system identifier (e.g., frontend application). */
    private String sourceSys;

    /** Migration indicator or flag (e.g., "Y" for migrated UNI). */
    private String migration;

    /** Timestamp when the UNI record was created in the system. */
    private LocalDateTime createdOn;
}
