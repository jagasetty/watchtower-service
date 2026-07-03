package com.brightspeed.inventoryapiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@EnableRetry
@EnableScheduling
public class USILEthernetOrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(USILEthernetOrderServiceApplication.class, args);
    }
    
    @Bean
	public RestTemplate restTemplate() {
	    return new RestTemplate();
	}
}