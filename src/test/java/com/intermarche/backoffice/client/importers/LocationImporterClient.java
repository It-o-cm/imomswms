package com.intermarche.backoffice.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Client to import Locations via CSV.
 * <p>
 * Sends a CSV payload to the {@code /locations/import} endpoint.
 * Links locations to existing Buildings and Products.
 */
public class LocationImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/locations/import";

    /**
     * Main method to execute the import process.
     * <p>
     * Prepares a CSV string containing sample Location data.
     * <p>
     * CSV Structure: BuildingCode|ProductEan|Type(SHELF/BIN)|Area|Aisle|Unit|Elevation|Position|[Facings|Dept]|[MaxWeight|MaxPallets]
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Uses Building codes from BuildingImporterClient and EANs from ProductImporterClient
        String csvData = """
            buildingCode|productEan|type|area|aisle|unit|elevation|position|extra1|extra2
            BLD-LILLE-01|3560070049010|SHELF|ROTISSEUR|A|01|1|01|2|TRAITEUR
            BLD-LILLE-01|3017620422003|SHELF|EPICERIE|B|02|2|05|3|PETIT DEJ
            BLD-LILLE-01|3229820125000|SHELF|BOISSONS|C|10|1|20|6|EAUX
            BLD-LILLE-01|3334345566778|SHELF|CAVES|D|01|3|10|4|VINS
            BLD-LILLE-01|3068320112305|SHELF|SURGELES|E|05|3|02|2|GLACES
            BLD-LILLE-01|3300000000001|SHELF|FRESH|F|01|1|01|5|FRUITS_LEGUMES
            BLD-LILLE-01|3300000000036|SHELF|ROTISSEUR|A|01|1|02|2|TRAITEUR
            BLD-LILLE-01|3000000000010|SHELF|EPICERIE|B|02|2|06|5|CAFE
            BLD-LILLE-01|3000000000027|SHELF|EPICERIE|B|02|2|07|4|PETIT_DEJ
            BLD-LILLE-01|3300000000013|SHELF|EPICERIE|B|03|1|03|1|BISCUITS
            BLD-LILLE-01|3300000000008|SHELF|FRESH|G|05|1|01|1|BOULANGERIE
            BLD-LILLE-01|3300000000002|SHELF|FRESH|H|02|1|05|3|LAIT
            BLD-LILLE-WH|3068320112305|BIN|FROZEN|Z1|RACK-A|Level1|Bin-05|100.0|2
            BLD-LILLE-WH|3560070049010|BIN|FRESH|Z2|RACK-B|Level2|Bin-10|50.0|3
            BLD-LILLE-WH|3300000000035|BIN|FROZEN|Z1|RACK-A|Level1|Bin-06|50.0|3
            BLD-LILLE-WH|3229820125000|BIN|AMBIENT|Z4|RACK-C|Level2|Bin-50|500.0|10
            BLD-LILLE-WH|3017620422003|BIN|AMBIENT|Z4|RACK-D|Level1|Bin-12|200.0|5
            BLD-LILLE-WH|3334345566778|BIN|AMBIENT|Z5|RACK-E|Level3|Bin-99|100.0|8
            BLD-LILLE-01|3300000000003|SHELF|BOULANGERIE|I|01|1|01|1|PAIN
            BLD-LILLE-01|3300000000004|SHELF|EPICERIE|B|02|1|01|1|CAFE
            BLD-LILLE-01|3300000000005|SHELF|EPICERIE|B|04|1|08|2|PATES
            BLD-LILLE-01|3300000000006|SHELF|EPICERIE|B|05|1|01|1|HUILE
            BLD-LILLE-01|3300000000007|SHELF|BOISSONS|C|11|1|01|4|EAUX
            BLD-LILLE-01|3300000000009|SHELF|FRESH|H|03|1|02|2|CREMERIE
            BLD-LILLE-01|3300000000010|SHELF|FRESH|H|03|2|03|1|YAOURT
            BLD-LILLE-01|3300000000011|SHELF|BOISSONS|C|12|1|05|2|SODA
            BLD-LILLE-01|3300000000012|SHELF|BOISSONS|C|12|2|06|3|SODA
            BLD-LILLE-01|3300000000014|SHELF|EPICERIE|B|06|1|04|2|SNACKS
            BLD-LILLE-01|3300000000015|SHELF|EPICERIE|B|07|1|01|1|SAUCE
            BLD-LILLE-WH|3300000000001|BIN|FRESH|Z3|RACK-F|Level1|Bin-01|200.0|10
            BLD-LILLE-WH|3300000000008|BIN|CHILLED|Z3|RACK-G|Level2|Bin-02|50.0|5
            BLD-LILLE-WH|3300000000013|BIN|AMBIENT|Z4|RACK-H|Level1|Bin-03|100.0|4
            BLD-LILLE-WH|3300000000003|BIN|AMBIENT|Z4|RACK-I|Level2|Bin-04|150.0|6
            BLD-LILLE-01|3300000000040|SHELF|BOISSONS|C|20|1|10|5|BIERES
            BLD-LILLE-01|3300000000041|SHELF|FRESH|G|10|1|05|3|FROMAGES
            BLD-LILLE-01|3300000000042|SHELF|FRESH|G|11|1|01|1|TRAITEUR
            BLD-LILLE-01|3300000000043|SHELF|FRESH|G|10|1|06|4|FROMAGES
            BLD-LILLE-01|3300000000044|SHELF|SURGELES|E|05|3|03|1|GLACES
            BLD-LILLE-01|3300000000045|SHELF|EPICERIE|B|08|1|01|1|MIEL
            BLD-LILLE-01|3300000000046|SHELF|ROTISSEUR|A|02|1|01|1|VOLAILLES
            BLD-LILLE-01|3300000000047|SHELF|FRESH|G|10|2|01|2|FROMAGES
            BLD-LILLE-01|3300000000048|SHELF|FRESH|G|11|2|01|1|TRAITEUR
            BLD-LILLE-01|3300000000049|SHELF|SURGELES|E|06|1|01|1|PIZZAS
            BLD-LILLE-01|3300000000050|SHELF|BOULANGERIE|I|02|1|02|1|PAIN
            BLD-LILLE-01|3300000000051|SHELF|EPICERIE|B|09|1|01|1|CONFISERIE
            BLD-LILLE-01|3300000000017|SHELF|FRESH|F|02|1|01|1|LEGUMES
            BLD-LILLE-01|3300000000018|SHELF|FRESH|F|02|1|02|1|LEGUMES
            BLD-LILLE-01|3300000000019|SHELF|FRESH|H|04|1|01|1|OEUFFS
            BLD-LILLE-01|3300000000020|SHELF|ROTISSEUR|A|03|1|01|1|VOLAILLES
            BLD-LILLE-01|3300000000021|SHELF|FRESH|G|12|1|01|1|POISSON
            BLD-LILLE-01|3300000000052|SHELF|EPICERIE|B|10|1|01|1|RIZ
            BLD-LILLE-01|3300000000053|SHELF|EPICERIE|B|07|1|02|1|SAUCE
            BLD-LILLE-WH|3300000000040|BIN|AMBIENT|Z4|RACK-J|Level1|Bin-20|150.0|8
            BLD-LILLE-WH|3300000000046|BIN|FRESH|Z2|RACK-B|Level1|Bin-11|40.0|2
            BLD-LILLE-WH|3300000000049|BIN|FROZEN|Z1|RACK-A|Level1|Bin-07|30.0|1
            BLD-LILLE-WH|3300000000045|BIN|AMBIENT|Z4|RACK-K|Level1|Bin-30|80.0|5
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