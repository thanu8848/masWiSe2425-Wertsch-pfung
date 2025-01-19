package de.htw_berlin.fb4.mas.worker;


import com.fasterxml.jackson.databind.ObjectMapper;

import de.htw_berlin.fb4.mas.model.LabelResponse;




import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;



import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * This class handles external tasks in Camunda, fetches service point locations based on provided address data,
 * prepares an email body with the fetched data, and sends the email.
 */
public class RunAPI implements ExternalTaskHandler {
    private static final Logger log = LoggerFactory.getLogger(RunAPI.class);


    /**
     * Constructor that loads mail properties from a file.
     */
    public RunAPI() {

    }

    /**
     * Executes the external task by fetching service point locations, preparing the email body, and sending the email.
     *
     * @param externalTask the external task
     * @param externalTaskService the external task service
     */
    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        log.info("Handling external task (Task ID: {} - Process Instance ID {})", externalTask.getId(), externalTask.getProcessInstanceId());

        // Retrieve variables from the external task
        String receiverId = externalTask.getVariable("receiverId");
        String name1 = externalTask.getVariable("name1");
        String addressStreet = externalTask.getVariable("addressStreet");
        String addressHouse = externalTask.getVariable("addressHouse");
        String postalCode= externalTask.getVariable("postalCode");
        String city= externalTask.getVariable("city");
        String speicherort = externalTask.getVariable("speicherort");

        try {
            String token = tokenRequester();
            if (token == null || token.isEmpty()) {
                throw new RuntimeException("Fehler bei der Token-Anforderung.");
            }

            String jsonResponse = createShippingLabelRaw(token, receiverId, name1, addressStreet, addressHouse, postalCode, city);

            LabelResponse labelResponse = mapJsonToLabelResponse(jsonResponse);

            saveLabelAsPdf(labelResponse, speicherort);

            log.info("Versandetikett erfolgreich erstellt und gespeichert.");
            externalTaskService.complete(externalTask);

        } catch (Exception e) {
            log.error("Fehler bei der Verarbeitung der Aufgabe: ", e);
            externalTaskService.handleFailure(externalTask, "Fehler beim API-Aufruf", e.getMessage(), 0, 0);
        }
    }

    private String tokenRequester() {
        String tokenUrl = "https://api-sandbox.dhl.com/parcel/de/account/auth/ropc/v1/token";

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(tokenUrl);

            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            post.setHeader("Accept", "application/json");

            String body = "grant_type=password&username=user-valid&password=SandboxPasswort2023!"
                    + "&client_id=AebfFj0CSgYh3mDic1pGlpAI8MyxZkGh"
                    + "&client_secret=h4vAat8cIRjicIo5";
            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                    return objectMapper.readTree(responseBody).get("access_token").asText();
                } else {
                    log.error("Fehler bei der Token-Anfrage. Status: {}", response.getStatusLine().getStatusCode());
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Fehler bei der Token-Anforderung", e);
            return null;
        }
    }

    private String createShippingLabelRaw(String token, String receiverId, String name, String street,
                                          String houseNumber, String postalCode, String city) throws Exception {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://api-sandbox.dhl.com/parcel/de/shipping/returns/v1/orders?labelType=BOTH");

            post.setHeader("Authorization", "Bearer " + token);
            post.setHeader("Content-Type", "application/json");

            String body = String.format("""
                {
                    "receiverId": "%s",
                    "shipper": {
                        "name1": "%s",
                        "addressStreet": "%s",
                        "addressHouse": "%s",
                        "postalCode": "%s",
                        "city": "%s"
                    },
                    "labelType": "PDF"
                }
                """, receiverId, name, street, houseNumber, postalCode, city);

            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                if (response.getStatusLine().getStatusCode() == 201) {
                    return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    log.error("Fehler beim Erstellen des Versandetiketts. Status: {}", response.getStatusLine().getStatusCode());
                    throw new RuntimeException("Fehler beim Erstellen des Versandetiketts.");
                }
            }
        }
    }

    private LabelResponse mapJsonToLabelResponse(String jsonResponse) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(jsonResponse, LabelResponse.class);
    }

    private void saveLabelAsPdf(LabelResponse labelResponse, String outputPath) {
        try {
            String base64Label = labelResponse.getLabel().getB64();
            byte[] decodedBytes = Base64.getDecoder().decode(base64Label);

            Path path = Path.of(outputPath, "versandetikett.pdf"); // PDF-Dateiname hinzufügen
            Files.createDirectories(path.getParent()); // Verzeichnis erstellen, falls nicht vorhanden
            Files.write(path, decodedBytes);

            log.info("Versandetikett gespeichert unter: {}", path.toAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Fehler beim Speichern des Versandetiketts.", e);
        }
    }

}