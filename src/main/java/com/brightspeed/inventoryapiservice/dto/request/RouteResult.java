package com.brightspeed.inventoryapiservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Represents the result of a route evaluation, separating
 * the path into segments before and after the MPLS (Multiprotocol Label Switching) network.
 * <p>
 * This DTO is typically used when analyzing or visualizing a route,
 * where the portion of the path leading up to the MPLS network
 * (beforeMpls) and the portion following it (afterMpls) must be
 * distinctly tracked.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     RouteInfo before = new RouteInfo(List.of("ckt1"), "A-TID", "MPLS-Entry", "route-1");
 *     RouteInfo after  = new RouteInfo(List.of("ckt2"), "MPLS-Exit", "Z-TID", "route-1");
 *
 *     RouteResult result = new RouteResult(before, after);
 * </pre>
 *
 * @see com.brightspeed.inventoryapiservice.dto.request.RouteInfo
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RouteResult {

    /**
     * Route information for the segment before the MPLS entry point.
     */
    private RouteInfo beforeMPLS;

    /**
     * Route information for the segment after the MPLS exit point.
     */
    private RouteInfo afterMPLS;
}
