package com.intermarche.backoffice.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Client to import Buildings via CSV.
 * <p>
 * Sends a CSV payload to the {@code /buildings/import} endpoint.
 * Links buildings to existing Stores using the Store code.
 */
public class BuildingImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/buildings/import";

    /**
     * Main method to execute the import process.
     * <p>
     * Prepares a CSV string containing sample Building data and sends it
     * via POST request.
     * <p>
     * CSV Structure: BuildingCode|StoreCode|Type(STORE/WAREHOUSE)|StreetLine1|StreetLine2|PostalCode|City|Country|Latitude|Longitude
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Data matches the Stores created by StoreImporterClient
        String csvData = """
                code|storeCode|type|streetLine1|streetLine2|postalCode|city|country|latitude|longitude
                BLD-LILLE-01|0101|STORE|1 Test Street||59000|Lille|France|50.6300|3.0600
                BLD-LILLE-WH|0101|WAREHOUSE|Zone Industrielle Rue du Port||59000|Lille|France|50.6500|3.0800
                BLD-BDX-01|0102|STORE|12 Avenue des Fleurs||33000|Bordeaux|France|44.8500|-0.5800
                BLD-LYON-01|0103|STORE|99 Boulevard de la Liberte|Etage 2|69000|Lyon|France|45.7600|4.8400
                BLD-PARIS-01|0104|STORE|25 Rue de Rivoli||75001|Paris|France|48.8600|2.3500
                BLD-PARIS-WH|0104|WAREHOUSE|Zone Logistique Est||93000|Saint Denis|France|48.9100|2.3600
                BLD-STG-01|0105|STORE|8 Place de la Gare|Bat C|67000|Strasbourg|France|48.5800|7.7500
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