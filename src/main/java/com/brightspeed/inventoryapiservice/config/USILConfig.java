package com.brightspeed.inventoryapiservice.config;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import lombok.Data;

/**
 * Represents a configuration entry stored in Neo4j under the
 * {@code USILConfig} node label.
 * <p>
 * This entity is used to persist and retrieve configuration values
 * that drive validation and design-impact checks for UNI connections
 * and other inventory-related processes.
 * </p>
 *
 * <p><b>Mapping:</b></p>
 * <ul>
 *   <li>{@code name} — Unique identifier for the configuration entry.</li>
 *   <li>{@code subGroup} — Subcategory under which the config belongs (e.g., {@code ADD}).</li>
 *   <li>{@code value} — Stored value (JSON string or plain text) representing configuration data.</li>
 * </ul>
 *
 * <p><b>Example:</b></p>
 * <pre>{@code
 * USILConfig config = new USILConfig();
 * config.setName("validationFieldsForUNI");
 * config.setSubGroup("ADD");
 * config.setValue("[{\"jsonPathKey\": \"$.circuitId\"}]");
 * }</pre>
 *
 * @author Brightspeed
 * @since 1.0
 */
@Data
@Node("USILConfig")   // maps to :USILConfig node in Neo4j
public class USILConfig {

    /**
     * Unique identifier for the configuration entry.
     * Typically corresponds to the config {@code name}.
     */
    @Id
    private String name;   // could be Long if numeric IDs are required

    /**
     * Subcategory or group to which this configuration belongs.
     * Example values: {@code ADD}, {@code MODIFY}.
     */
    private String subGroup;

    /**
     * The configuration value stored in the database.
     * Often a JSON string representing validation or design-impact fields.
     */
    private String value;
}
