package com.brightspeed.inventoryapiservice.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;

import lombok.extern.slf4j.Slf4j;

@Configuration
@Component
@Slf4j
public class BigQueryConnectionConfig {

	@Value("${google.cloud.bigquery.credentials.location}")
	public String credentialsData;

	@Value("${google.cloud.bigquery.credentials.projectId}")
	public String projectId;

	@Bean
	public BigQuery bigQuery() throws IOException {

		System.out.println("credentials path " + credentialsData);

		try {

			GoogleCredentials credentials = GoogleCredentials
					.fromStream(new ByteArrayInputStream(credentialsData.getBytes()));

			return BigQueryOptions.newBuilder().setProjectId(projectId).setCredentials(credentials).build()
					.getService();

		} catch (IOException e) {

			log.error("Failed to connect to BigQuery: {}", e.getMessage(), e);
			throw new RuntimeException("Could not initialize BigQuery client", e);
		}

	}

}
