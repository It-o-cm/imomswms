package com.intermarche.backoffice.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Client to import Totes via CSV.
 * <p>
 * Sends a CSV payload to the {@code /totes/import} endpoint.
 * Links totes to existing Stores using the Store code.
 * <p>
 * Prioritizes Store 0101 for the sample data.
 */
public class ToteImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/totes/import";

    /**
     * Main method to execute the import process.
     * <p>
     * Prepares a CSV string containing sample Tote data and sends it
     * via POST request.
     * <p>
     * CSV Structure: Code|StoreCode|Volume|ToteType
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {code|storeCode|volume|toteType}
        // We prioritize Store 0101 (Lille) with a mix of STANDARD and INSULATED totes.
        String csvData = """
                code|storeCode|volume|toteType
                TOT-STD-0101-01|0101|45.500|STANDARD
                TOT-STD-0101-02|0101|45.500|STANDARD
                TOT-STD-0101-03|0101|45.500|STANDARD
                TOT-STD-0101-04|0101|45.500|STANDARD
                TOT-STD-0101-05|0101|45.500|STANDARD
                TOT-STD-0101-06|0101|45.500|STANDARD
                TOT-ISO-0101-01|0101|35.000|INSULATED
                TOT-ISO-0101-02|0101|35.000|INSULATED
                TOT-ISO-0101-03|0101|35.000|INSULATED
                TOT-ISO-0101-04|0101|35.000|INSULATED
                TOT-ISO-0101-05|0101|35.000|INSULATED
                TOT-ISO-0101-06|0101|35.000|INSULATED
                TOT-ISO-0101-07|0101|35.000|INSULATED
                TOT-STD-0101-07|0101|60.000|STANDARD
                TOT-STD-0102-01|0102|45.500|STANDARD
                TOT-STD-0103-01|0103|45.500|STANDARD
                TOT-STD-0104-01|0104|45.500|STANDARD
                TOT-ISO-0104-01|0104|35.000|INSULATED
                TOT-STD-0105-01|0105|45.500|STANDARD
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