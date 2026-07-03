package com.brightspeed.inventoryapiservice.dto.request;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object (DTO) representing detailed information about a network port.
 * <p>
 * This class is used to encapsulate port-related details such as identifiers,
 * bandwidth, connector type, hardware location, and associated node information. 
 * </p>
 *
 * <p>
 * Since this class implements {@link Serializable}, it can also be safely 
 * serialized for use in distributed systems, caching, or message passing 
 * scenarios if required.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortInfo implements Serializable {

    /** Serial version UID for ensuring serialization compatibility. */
    private static final long serialVersionUID = 1L;

    /** Unique identifier of the port. */
    private String portId;

    /** Terminal Identifier (TID) associated with the port. */
    private String tid;

    /** Function or role of the port (e.g., UNI, NNI). */
    private String function;

    /** Unique key identifying the port within the system. */
    private String key;

    /** Type of connector (e.g., SFP, RJ45, XFP). */
    private String connector;

    /** Bandwidth capacity of the port (e.g., 1GE, 10GE). */
    private String bandwidth;

    /** Shelf identifier where the port resides in the equipment. */
    private String shelf;

    /** Unit identifier within the shelf for the port. */
    private String unit;

    /** Relay rack information where the port is physically located. */
    private String relayrck;

    /** Location identifier of the port. */
    private String location;

    /** Node identifier in the network topology. */
    private String node_id;

    /** CLLI (Common Language Location Identifier) and LATA (Local Access Transport Area) associated with the port. */
    private String clli_lata;

    /** Logical location Z-side reference for the port. */
    private String locz;

    /** Derived or computed port bandwidth (possibly normalized). */
    private String portBandwidth;

    /** Logical location A-side reference for the port. */
    private String loca;

    /** Alternate key or reference for the port. */
    private String port_key;
}
