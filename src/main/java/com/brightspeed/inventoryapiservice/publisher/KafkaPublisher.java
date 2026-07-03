package com.brspd.iwg.publisher;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.brspd.iwg.dto.KafkaPayload;

@Slf4j
@Component
public class KafkaPublisher {
    @Autowired
    private StreamBridge streamBridge;

    public static final String ONPROVISION_COMPLETE = "Dev_PreProvisionComplete";

    public static String ENABLE_STATUS = "sendEnableStatus-out-0";

    /*@Value("${brightspeed.publish.servicenow.topic.name}")
    public String ONPROVISION_RESPONSE;

    @Value("${brightspeed.publish.preprov.topic.name}")
    public String PREPROV_COMPLETE;

    @Value("${brightspeed.publish.servicenowreply.topic.name}")
    public String ONT_SERVICE_NOW_COMPLETE;

    @Value("${brightspeed.publish.cpereply.topic.name}")
    public String ENABLE_STATUS;

   /* @Value("${brightspeed.publish.cpe.topic.name}")
    public String ON_CPE_PROVISION_RESPONSE;*/


    public Boolean sendMessage(String taskId, String payLoad) {
        log.info("publishing COMPLETE msg to kafka {}" + payLoad);
        return streamBridge.send( ONPROVISION_COMPLETE,
                MessageBuilder
                        .withPayload(payLoad)
                        .setHeader(KafkaHeaders.MESSAGE_KEY, "application/json")
                        .build());
    }


    public Boolean sendKafkaObject(KafkaPayload payLoad) {
        log.info("publishing RESPONSE to kafka topic: {} \n Msg:  {} " , ENABLE_STATUS, payLoad);
        return streamBridge.send( ENABLE_STATUS,
                MessageBuilder
                        .withPayload(payLoad)
                        .setHeader("contentType", "application/json")
                        .build());
    }
}
