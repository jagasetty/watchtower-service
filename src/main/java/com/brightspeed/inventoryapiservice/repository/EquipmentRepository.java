package com.brightspeed.inventoryapiservice.repository;

import java.util.Map;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class EquipmentRepository {

	private final Neo4jClient neo4jClient;

	@SuppressWarnings("unchecked")
	public Map<String, Object> findEquipmentInfo(String deviceName) {
		return neo4jClient.query("""
				        MATCH (e:Equipment { TID: $deviceName })
				        OPTIONAL MATCH (e)<-[:COMPONENT_OF*]-(p:EquipmentPort)
				        WITH e, collect(DISTINCT p) AS portNodes
				        WITH
				            e,
				            [port IN portNodes | properties(port)] AS ports,
				              [port IN portNodes |
				            CASE
				                WHEN port.heci IS NOT NULL AND size(port.heci) > 2
				                THEN substring(port.heci, 0, size(port.heci) - 2)
				                ELSE port.heci
				            END
				        ] AS heciList
				        OPTIONAL MATCH (part:Part)
				        WHERE part.hecig_code IN heciList
				        WITH e, ports, collect(DISTINCT part.part_number) AS partNums
				        WITH
				        e, ports, partNums,
				        CASE
				            WHEN e.deviceRoles IS NULL THEN false
				            WHEN e.deviceRoles CONTAINS 'NID' THEN true
				             WHEN any(role IN (CASE
				                    WHEN e.deviceRoles[0] IS NOT NULL
				                    THEN e.deviceRoles
				                    ELSE split(e.deviceRoles, ',')
				                  END)
				          WHERE trim(role) = 'NID')
				THEN true
				ELSE false
				        END AS isNID
				        RETURN {
				          deviceInfo: properties(e),
				          ports: ports,
				          partNumbers: partNums,
				          isNID: isNID
				        } AS result
				    """).bind(deviceName).to("deviceName").fetchAs(Map.class)
				.mappedBy((typeSystem, record) -> record.get("result").asMap()).one().orElse(null);
	}
}
