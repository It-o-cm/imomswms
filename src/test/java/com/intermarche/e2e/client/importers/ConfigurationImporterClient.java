package com.intermarche.e2e.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.oms.Fulfillment;
import com.intermarche.e2e.Authenticate;

/**
 * REST Client for importing Configurations via CSV.
 * <p>
 * StoreCodes and GroupCodes are located at the end of the CSV line.
 */
public class ConfigurationImporterClient {

    private static final String BASE_URL = "http://localhost:8040";
    private static final String IMPORT_URL = BASE_URL + "/configurations/import";

    public static void main(String[] args) {
        String csvData = """
        name|type|priority|specification|storeCodes|groupCodes
        Standard Fulfillment|FULFILLMENT|10|{"maxOrders":100,"cutoffTime":"14:00"}|0101,0102|GRP_A
        Express Packing|PACKING|5|{"boxType":"Small","maxWeight":5.0}|0101|GRP_B
        Warehouse Fulfillment|FULFILLMENT|20|{"zone":"A","priority":1}|0103|
        Fragile Packing|PACKING|15|{"requiresBubbleWrap":true,"label":"FRAGILE"}|0104|GRP_A
        Holiday Fulfillment|FULFILLMENT|1|{"maxOrders":50,"overtimeAllowed":true}||GRP_B
        0101 Fulfillment Workflow|FULFILLER|50|[{"type":"ZoneGrouping", "buildingCode":"BLD-LILLE-01", "area":"ROTISSEUR"}, {"type":"ZoneGrouping", "buildingCode":"BLD-LILLE-WH", "area":"FROZEN"}, {"type":"ProductGrouping", "families":["FRUITS_ET_LEGUMES"]}, {"type":"ProductGrouping", "eans":["3000000000010", "3000000000027"]}, {"type":"Default"}]|0101|
        0101 Standard Packing Workflow|ORDER_HANDLER|60|[ {"type":"ProductCollecting", "buildingCode":"BLD-LILLE-01", "eans":["3000000000010", "3000000000027"], "weightMax": 20.0, "validityDelayMinutes": 15, "groupingWindowMinutes": 60}, {"type": "ZoneCollecting", "buildingCode": "BLD-LILLE-WH", "area": "FROZEN", "weightMin": 0.0, "weightMax": 20.0, "validityDelayMinutes": 15, "groupingWindowMinutes": 60}, {"type": "Grouping", "validityDelayMinutes": 15, "groupingWindowMinutes": 60, "targetBufferCode": "LILLE-S"}, {"type":"Packing", "validityDelayMinutes": 15, "groupingWindowMinutes": 60, "deadlineDelayMinutes": 30}]|0101|
        0102 Standard Packing Workflow|ORDER_HANDLER|60|[{"type":"Packing", "validityDelayMinutes": 15, "groupingWindowMinutes": 60, "deadlineDelayMinutes": 30}]|0102|
        0103 Standard Packing Workflow|ORDER_HANDLER|60|[{"type":"Packing", "validityDelayMinutes": 15, "groupingWindowMinutes": 60, "deadlineDelayMinutes": 30}]|0103|
        0104 Standard Packing Workflow|ORDER_HANDLER|60|[{"type":"Packing", "validityDelayMinutes": 15, "groupingWindowMinutes": 60, "deadlineDelayMinutes": 30}]|0104|
        0105 Standard Packing Workflow|ORDER_HANDLER|60|[{"type":"Packing", "validityDelayMinutes": 15, "groupingWindowMinutes": 60, "deadlineDelayMinutes": 30}]|0105|
        """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMPORT_URL))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                .POST(HttpRequest.BodyPublishers.ofString(csvData))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Server Response: " + response.body());
            if (response.statusCode() == 200) {
                System.out.println("Import successful!");
            } else {
                System.err.println("Error during processing.");
            }
        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

}