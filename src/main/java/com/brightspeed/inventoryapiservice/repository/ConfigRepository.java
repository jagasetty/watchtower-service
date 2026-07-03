package com.brightspeed.inventoryapiservice.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.brightspeed.inventoryapiservice.config.USILConfig;

/**
 * Repository interface for accessing and managing {@link USILConfig} entities in Neo4j.
 * <p>
 * This repository extends {@link Neo4jRepository} to provide basic CRUD operations and 
 * defines custom queries for retrieving configuration values stored in Neo4j nodes.
 * </p>
 */
@Repository
public interface ConfigRepository extends Neo4jRepository<USILConfig, String> {

    /**
     * Retrieves the configuration value for a given {@code name} and {@code subGroup}.
     * <p>
     * Executes a Cypher query to match a {@code USILConfig} node by its {@code name} and 
     * {@code subGroup} properties and returns the corresponding {@code value}.
     * </p>
     *
     * @param name the configuration name to search for (non-null).
     * @param subGroup the subgroup under which the configuration is categorized (non-null).
     * @return the configuration value if found; {@code null} if no matching node exists.
     */
    @Query("MATCH (c:USILConfig {name: $name, subGroup: $subGroup}) RETURN c.value AS value")
    String findConfigValue(@Param("name") String name, @Param("subGroup") String subGroup);
}
