package com.brightspeed.inventoryapiservice.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.brightspeed.inventoryapiservice.dto.request.DesignImpactField;
import com.brightspeed.inventoryapiservice.dto.request.ValidationField;
import com.brightspeed.inventoryapiservice.repository.ConfigRepository;
import com.brightspeed.inventoryapiservice.util.Constants;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for loading and providing configuration data 
 * related to UNI connection validation.
 *
 * <p>This service fetches two types of configuration fields from the 
 * underlying {@link ConfigRepository}:</p>
 * <ul>
 *   <li>{@link ValidationField} - required fields that must be present 
 *       in the UNI connection payload.</li>
 *   <li>{@link DesignImpactField} - fields that impact design and 
 *       network validation checks.</li>
 * </ul>
 *
 * <p>Configurations are automatically refreshed every 24 hours 
 * using a scheduled job.</p>
 */
@Service
@Slf4j
public class ConfigService {

    private final ConfigRepository configRepository;
    private final ObjectMapper objectMapper;

    private List<ValidationField> validationFields = new ArrayList<>();
    private List<DesignImpactField> designImpactFields = new ArrayList<>();

    /**
     * Constructs a {@code ConfigService} with required dependencies.
     *
     * @param configRepository repository for retrieving configuration values
     * @param objectMapper     Jackson {@link ObjectMapper} for JSON deserialization
     */
    @Autowired
    public ConfigService(ConfigRepository configRepository, ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
    }

    
    /**
     * Loads all configuration data required by the application.
     * <p>
     * This method is a composite loader that triggers the loading of:
     * <ul>
     *     <li><b>Validation Fields</b> – used to check the presence of required fields
     *     in incoming payloads or requests.</li>
     *     <li><b>Design Impact Fields</b> – used to determine whether changes
     *     in payloads have design or network impact.</li>
     * </ul>
     *
     * <p>
     * Typical usage involves calling this method during application startup
     * or on-demand refresh (e.g., via a controller endpoint) when configuration
     * values in the data source (like a database) might have changed.
     * </p>
     *
     * <p>
     * Internally, it delegates to:
     * <ul>
     *     <li>{@link #loadValidationFields()}</li>
     *     <li>{@link #loadDesignImpactFields()}</li>
     * </ul>
     * </p>
     */
    public void loadConfigs() {
        loadValidationFields();
        loadDesignImpactFields();
    }

    /**
     * Loads validation fields from the repository and updates
     * the in-memory cache.
     *
     * <p>The configuration is deserialized from JSON into a list of
     * {@link ValidationField} objects.</p>
     *
     * @throws RuntimeException if the fields cannot be loaded or parsed
     */
    private void loadValidationFields() {
        try {
            String value = configRepository.findConfigValue(Constants.VALIDATION_FIELDS_FOR_UNI, Constants.ADD);
            this.validationFields = objectMapper.readValue(value, new TypeReference<List<ValidationField>>() {});
            log.info("Loaded validation fields: {}",validationFields.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load validation fields", e);
        }
    }

    /**
     * Loads design-impacting fields from the repository and updates
     * the in-memory cache.
     *
     * <p>The configuration is deserialized from JSON into a list of
     * {@link DesignImpactField} objects.</p>
     *
     * @throws RuntimeException if the fields cannot be loaded or parsed
     */
    private void loadDesignImpactFields() {
        try {
            String value = configRepository.findConfigValue(Constants.DESIGN_IMPACTING_FIELDS_FOR_UNI, Constants.ADD);
            this.designImpactFields = objectMapper.readValue(value, new TypeReference<List<DesignImpactField>>() {});
            log.info("Loaded design-impacting fields: {}",designImpactFields.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load design-impacting fields", e);
        }
    }

    /**
     * Returns the list of currently loaded validation fields.
     *
     * @return list of {@link ValidationField} objects
     */
    public List<ValidationField> getValidationFields() {
        return validationFields;
    }

    /**
     * Returns the list of currently loaded design-impacting fields.
     *
     * @return list of {@link DesignImpactField} objects
     */
    public List<DesignImpactField> getDesignImpactFields() {
        return designImpactFields;
    }
}
