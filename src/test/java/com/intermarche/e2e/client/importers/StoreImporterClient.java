package com.intermarche.e2e.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.e2e.Authenticate;

/**
 * Client to import Stores via CSV.
 * <p>
 * Updated to include the specific Store referenced in the Order JSON example.
 */
public class StoreImporterClient {

    private static final String BASE_URL = "http://localhost:8040";
    private static final String IMPORT_URL = BASE_URL + "/stores/import";

    public static void main(String[] args) {
        // CSV Structure: code|name|streetLine1|streetLine2|postalCode|city|country|latitude|longitude
        // Line 1 updated to match the Order JSON example exactly.
        String csvData = """
                code|name|streetLine1|streetLine2|postalCode|city|country
                0101|Intermarché Test 1|1 Test Street||59000|Lille|France
                0102|Intermarché Test 2|12 Avenue des Fleurs||33000|Bordeaux|France
                0103|Intermarché Test 3|99 Boulevard de la Liberte|Etage 2|69000|Lyon|France
                0104|Intermarché Test 4|25 Rue de Rivoli||75001|Paris|France
                0105|Intermarché Test 5|8 Place de la Gare|Bat C|67000|Strasbourg|France
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