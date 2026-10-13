package com.intermarche.backoffice.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Simple HTTP client to test Product bulk import.
 * <p>
 * This class sends a CSV payload containing a list of products to
 * {@code /products/import} endpoint using Java 11+ HttpClient.
 * <p>
 * Updated CSV format (10 columns): EAN, Name, Description, Brand, ReferenceWeight,
 * ReferenceVolume, ProductType, UnitName, TemperatureZone, Active
 */
public class ProductImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/products/import";

    /**
     * Main method to execute import process.
     * <p>
     * Prepares a CSV string containing sample data and sends it
     * via POST request to the configured {@link #IMPORT_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|temperatureZone|active}
        String csvData = """
            ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|temperatureZone|active
            3300000000001|Pommes Golden|Pommes fraîches bio|Brand A|1.000|2.500|WEIGHT|kg|CHILLED|true
            3300000000002|Lait UHT 1L|Lait demi-écrémé|Brand B|1.000|1.000|UNIT|L|AMBIENT|true
            3300000000003|Baguette Tradition|Pain de tradition|Brand C|0.250|0.600|UNIT|kg|AMBIENT|true
            3300000000004|Café Grains 500g|Café moulu arabica|Brand D|0.500|1.250|UNIT|kg|AMBIENT|true
            3300000000005|Pâtes Penne 500g|Pâtes alimentaires|Brand E|0.500|1.250|WEIGHT|kg|AMBIENT|true
            3300000000006|Huile d'Olive 1L|Huile vierge extra|Brand F|1.000|1.000|UNIT|L|AMBIENT|true
            3300000000007|Eau Minérale 1.5L|Eau de source|Brand G|1.500|1.500|UNIT|L|AMBIENT|true
            3300000000008|Jambon Blanc 100g|Tranches de jambon|Brand H|0.100|0.250|WEIGHT|kg|CHILLED|true
            3300000000009|Beurre Demi-Sel 250g|Motte de beurre|Brand I|0.250|0.300|WEIGHT|kg|CHILLED|true
            3300000000010|Yaourt Nature 4x125g|Pots de yaourt|Brand J|0.500|1.250|UNIT|kg|CHILLED|true
            3300000000011|Coca-Cola 1.5L|Boisson gazeuse|Brand K|1.500|1.500|UNIT|L|AMBIENT|true
            3300000000012|Orangina 1.25L|Boisson aux agrumes|Brand L|1.250|1.250|UNIT|L|AMBIENT|true
            3300000000013|Biscuits Chocolat 200g|Paquet de biscuits|Brand M|0.200|0.400|UNIT|kg|AMBIENT|true
            3300000000014|Chips Classiques 150g|Chips de pomme de terre|Brand N|0.150|0.300|UNIT|kg|AMBIENT|true
            3300000000015|Sauce Tomate 500g|Sauce bolognaise|Brand O|0.500|1.250|UNIT|kg|AMBIENT|true
            3300000000016|Purée de Pomme de Terre 500g|Purée instantanée|Brand P|0.500|1.250|UNIT|kg|AMBIENT|true
            3300000000017|Endives (Chicory)|Légume frais|Brand Q|0.500|1.000|WEIGHT|kg|CHILLED|true
            3300000000018|Tomates Cerises 500g|Tomates rondes|Brand R|0.500|0.800|WEIGHT|kg|CHILLED|true
            3300000000019|Oeufs Bio 6 unités|Oeufs frais gros|Brand S|0.360|0.500|UNIT|kg|CHILLED|true
            3300000000020|Poulet Rôti 1.2kg|Poulet fermier|Brand T|1.200|3.000|WEIGHT|kg|CHILLED|true
            3300000000021|Saumon Fume 200g|Tranches de saumon|Brand U|0.200|0.250|WEIGHT|kg|CHILLED|true
            3300000000022|Riz Basmati 1kg|Riz long grain|Brand V|1.000|2.500|UNIT|kg|AMBIENT|true
            3300000000023|Lentilles Vertes 500g|Légumes secs|Brand W|0.500|1.250|UNIT|kg|AMBIENT|true
            3300000000024|Miel d'Acacia 500g|Pot de miel|Brand X|0.500|0.700|UNIT|kg|AMBIENT|true
            3300000000025|Lessive Liquide 1.5L|Lessive linge|Brand Y|1.500|1.500|UNIT|L|HOUSEHOLD|true
            3300000000026|Eponge Vaisselle 3 unités|Eponges abrasives|Brand Z|0.100|0.250|UNIT|kg|HOUSEHOLD|true
            3300000000027|Coton Bio 500g|Disques de coton|Brand A1|0.500|1.250|UNIT|kg|HOUSEHOLD|true
            3300000000028|Piles AA 4 unités|Piles alcalines|Brand B1|0.080|0.200|UNIT|kg|HOUSEHOLD|true
            3300000000029|Chewing-Gum Menthe|Pommes de menthe|Brand C1|0.050|0.125|UNIT|kg|AMBIENT|true
            3300000000030|Dentifrice Menthe 100ml|Tube dentifrice|Brand D1|0.100|0.100|UNIT|L|HOUSEHOLD|true
            3300000000031|Poêle Antiadhésive 28cm|Poêle fonte alum|Tefal|0.800|0.000|UNIT|pcs|HOUSEHOLD|true
            3300000000032|Casserole Inox 20cm|Casserole acier inox|Staub|1.200|0.000|UNIT|pcs|HOUSEHOLD|true
            3300000000033|Set de Couteaux Chef|Couteaux acier inox|Sabatier|0.500|0.000|UNIT|pcs|HOUSEHOLD|true
            3560070049010|Roasted Farm Chicken|Rotisserie cooked chicken|Rotisserie|1.200|2.500|WEIGHT|kg|AMBIENT|true
            3017620422003|Chocapic Cereal|Chocolate cereal|Nestle|0.800|4.000|UNIT|kg|AMBIENT|true
            3229820125000|Cristaline Spring Water|Natural spring water|Cristaline|1.500|1.500|UNIT|L|AMBIENT|true
            3334345566778|Red Bordeaux Wine|Appellation Bordeaux Controlee|Chateau IM|1.400|0.750|UNIT|L|AMBIENT|true
            3068320112305|Vanilla Ice Cream|Premium vanilla ice cream|IM Gourmand|0.500|1.000|UNIT|L|FROZEN|true
            3000000000010|Lavazza Qualità Oro|Café Moulu 100% Arabica|Lavazza|0.250|3.000|UNIT|kg|AMBIENT|true
            3000000000027|Nutella Pot 825g|Pâte à tartiner noisettes et cacao|Ferrero|0.825|1.000|UNIT|kg|AMBIENT|true
            3300000000035|Petits Pois Surgelés|Frozen Peas|Froid|0.500|0.800|WEIGHT|kg|FROZEN|true
            3300000000036|Dinde Rotie|Roasted Turkey|Rotisserie|1.500|3.000|WEIGHT|kg|AMBIENT|true
            3300000000040|Ch'ti Blonde Beer|Bière blonde locale|Ch'ti|0.500|0.500|UNIT|L|AMBIENT|true
            3300000000041|Maroilles Cheese|Fromage fort du Nord|Maroilles|0.250|0.300|WEIGHT|kg|CHILLED|true
            3300000000042|Welsh Rarebit (Prepared)|Plat préparé fromage bière|Traiteur|0.450|0.500|WEIGHT|kg|CHILLED|true
            3300000000043|Merveux Cheese|Fromage à pâte molle|Merveux|0.200|0.250|WEIGHT|kg|CHILLED|true
            3300000000044|Glace Chocolat|Crème glacée chocolat|IM Gourmand|0.500|1.000|UNIT|L|FROZEN|true
            3300000000045|Miel d'Acacia 500g|Pot de miel local|Brand X|0.500|0.700|UNIT|kg|AMBIENT|true
            3300000000046|Poulet Rôti Fermier|Poulet fermier rôti|Rotisserie|1.300|2.800|WEIGHT|kg|AMBIENT|true
            3300000000047|Fromage de Chevre|Bûche de chèvre|Chèvre|0.150|0.200|WEIGHT|kg|CHILLED|true
            3300000000048|Quiche Lorraine|Quiche lardon oeuf|Traiteur|0.350|0.450|WEIGHT|kg|CHILLED|true
            3300000000049|Pizza Reine Surgelée|Pizza jambon fromage|Froid|0.400|0.800|UNIT|kg|FROZEN|true
            3300000000050|Pain d'Epices|Pain d'épices traditionnel|Boulangerie|0.300|0.600|UNIT|kg|AMBIENT|true
            3300000000051|Bêtises de Cambrai|Bonbons menthe|Confiserie|0.150|0.200|UNIT|kg|AMBIENT|true
            3300000000052|Riz Basmati 1kg|Riz long grain|Brand V|1.000|1.500|UNIT|kg|AMBIENT|true
            3300000000053|Sauce Tomate 400g|Sauce bolognaise|Brand O|0.400|0.450|UNIT|kg|AMBIENT|true
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