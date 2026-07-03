package com.brightspeed.inventoryapiservice.dto.request;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents route-related information for a UNI/NNI connection.
 * <p>
 * This DTO is used to encapsulate details about a network route,
 * including associated circuit IDs, terminal identifiers at the
 * start and end points, and the unique route identifier.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     RouteInfo routeInfo = new RouteInfo(
 *         List.of("1021GEMONRINAE00WMON", "1022GEMONRINAE00WMON"),
 *         "MDVLINAE00W",
 *         "NYCMNYAE01W",
 *         "route-12345"
 *     );
 * </pre>
 *
 * @see com.brightspeed.inventoryapiservice.dto.request.DesignImpactField
 * @see com.brightspeed.inventoryapiservice.dto.request.ValidationField
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RouteInfo {

    /**
     * List of alias circuit IDs associated with this route.
     * <p>
     * Example: {@code ["1021GEMONRINAE00WMON", "1022GEMONRINAE00WMON"]}
     * </p>
     */
    private List<String> aliasCktIds;

    /**
     * The terminal identifier (TID) of the first "A-end" node in the route.
     * <p>
     * Example: {@code MDVLINAE00W}
     * </p>
     */
    private String firstAEndTID;

    /**
     * The terminal identifier (TID) of the last "Z-end" node in the route.
     * <p>
     * Example: {@code NYCMNYAE01W}
     * </p>
     */
    private String lastZEndTID;

    /**
     * The unique identifier for this route.
     * <p>
     * Example: {@code route-12345}
     * </p>
     */
    private String routeId;
}
