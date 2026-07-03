FROM eclipse-temurin:17-jdk

RUN mkdir -p /opt/tmp/usilethernetorderservice

COPY ./target/usilethernetorderservice.jar /opt/tmp/usilethernetorderservice/usilethernetorderservice.jar

RUN chmod -R 777 /opt/tmp/usilethernetorderservice

EXPOSE 8089

CMD ["java", "-Xms1024m", "-Xmx4096m", "-jar", "/opt/tmp/usilethernetorderservice/usilethernetorderservice.jar"]
