package com.intermarche.e2e.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.e2e.Authenticate;

/**
 * Standalone client program to submit an order using JWT authentication.
 * <p>
 * This program authenticates as the 'service' account (SERVICE_CALLER role)
 * to obtain a JWT, then uses that token to submit the order.
 */
public class SendOrder {
    private static final String ORDER_URL = "http://localhost:8080/orders";

    private static final String JSON_PAYLOAD = """
        {
          "orderHeader": {
            "orderId": "ORD-2023-1024-89711",
            "createdAt": "2026-03-01T10:00:00Z",
            "channel": "ECOMMERCE_APP",
            "status": "VALIDATED",
            "store": {
              "id": 1,
              "code": "0101",
              "name": "Intermarché Test 1",
              "address": {
                "streetLine1": "1 Test Street",
                "postalCode": "59000",
                "city": "Lille",
                "country": "France",
                "latitude": 50.63,
                "longitude": 3.06
              }
            }
          },
          "customer": {
            "customerId": "CUS-998877",
            "firstName": "Alice",
            "lastName": "Durand",
            "email": "alice.durand@example.com",
            "phone": "+33610203040",
            "loyaltyCardId": "340123456789",
            "address": {
              "streetLine1": "25 Flowers Avenue",
              "streetLine2": "Apt 3B",
              "postalCode": "59000",
              "city": "Lille",
              "country": "France",
              "latitude": 50.6325,
              "longitude": 3.0582,
              "deliveryInstructions": "Keypad code 1234A, 3rd floor left"
            }
          },
          "fulfillment": {
            "type": "HOME_DELIVERY",
            "slot": {
              "start": "2026-04-01T09:00:00Z",
              "end": "2026-04-01T10:00:00Z"
            }
          },
          "packaging": {
            "description": "Bin allocation specific to temperature zones.",
            "supports": [
              {
                "type": "REUSABLE_CRATE_FRESH",
                "name": "Reusable Fresh Crate",
                "quantity": 2,
                "purpose": "Fresh & Hot Products",
                "unitDepositFee": 2.00,
                "totalDepositFee": 4.00
              },
              {
                "type": "REUSABLE_CRATE_FROZEN",
                "name": "Reusable Frozen Crate",
                "quantity": 1,
                "purpose": "Frozen Products",
                "unitDepositFee": 2.00,
                "totalDepositFee": 2.00
              },
              {
                "type": "CARDBOARD_BOX",
                "name": "Standard Cardboard Box",
                "quantity": 3,
                "purpose": "Ambient Grocery",
                "unitDepositFee": 0.00
              },
              {
                "type": "BOTTLE_CRATE",
                "name": "Reusable Bottle Crate",
                "quantity": 1,
                "purpose": "Alcoholic Beverages",
                "unitDepositFee": 1.50,
                "totalDepositFee": 1.50
              }
            ],
            "summary": {
              "totalDepositFee": 7.50
            }
          },
          "lines": [
            {
              "lineNumber": 1,
              "product": {
                "ean": "3560070049010",
                "name": "Roasted Farm Chicken",
                "productType": "WEIGHT",
                "referenceWeight": 1.200,
                "unitName": "kg"
              },
              "requestedQuantity": 1.0,
              "pricing": {
                "basePrice": 12.00,
                "unitPrice": 9.50,
                "totalPrice": 9.50,
                "discountAmount": 2.50
              },
              "offer": {
                "code": "PROMO_ROTI_SEMAINE",
                "type": "IMMEDIATE_DISCOUNT",
                "description": "Roasted Chicken: -20% immediate",
                "mechanics": {
                  "discountPercentage": 20.0
                },
                "funding": "STORE"
              },
              "instructions": {
                "butchery": "Cut into pieces",
                "notes": "Well done please"
              },
              "logistics": {
                "weight": 1.200,
                "volume": 0.002,
                "fragile": false,
                "temperatureZone": "HOT"
              }
            },
            {
              "lineNumber": 2,
              "product": {
                "ean": "3017620422003",
                "name": "Chocapic Cereal",
                "productType": "UNIT",
                "unitName": "pcs"
              },
              "requestedQuantity": 2.0,
              "pricing": {
                "basePrice": 3.45,
                "unitPrice": 3.45,
                "totalPrice": 6.90,
                "discountAmount": 0.00
              },
              "offer": {
                "code": "OFFRE_COOL",
                "type": "BOGO",
                "description": "Buy 1 = Get 1 free (deferred value on loyalty card)",
                "mechanics": {
                  "triggerQuantity": 1,
                  "rewardQuantity": 1,
                  "rewardType": "VOUCHER_LOYALTY"
                },
                "validity": "Credit of 3.45€ on loyalty card for next visit",
                "funding": "BRAND"
              },
              "logistics": {
                "weight": 0.800,
                "volume": 0.004,
                "fragile": false,
                "temperatureZone": "AMBIENT"
              }
            },
            {
              "lineNumber": 3,
              "product": {
                "ean": "3229820125000",
                "name": "Cristaline Spring Water",
                "productType": "UNIT",
                "referenceVolume": 1.5,
                "unitName": "L"
              },
              "requestedQuantity": 6.0,
              "pricing": {
                "basePrice": 0.65,
                "unitPrice": 0.65,
                "totalPrice": 3.90,
                "discountAmount": 0.00
              },
              "offer": {
                "code": "OPERATION_VIGNETTES",
                "type": "STAMPS",
                "description": "School Stamps Operation",
                "mechanics": {
                  "stampsEarned": 12,
                  "ratio": 2,
                  "goal": "Collection for school supplies discount"
                },
                "funding": "BRAND"
              },
              "logistics": {
                "weight": 9.5,
                "volume": 9.0,
                "fragile": false,
                "temperatureZone": "AMBIENT"
              }
            },
            {
              "lineNumber": 4,
              "product": {
                "ean": "3334345566778",
                "name": "Red Bordeaux Wine",
                "productType": "UNIT",
                "referenceVolume": 0.75,
                "unitName": "L"
              },
              "requestedQuantity": 2.0,
              "pricing": {
                "basePrice": 8.50,
                "unitPrice": 8.50,
                "totalPrice": 17.00,
                "discountAmount": 0.00
              },
              "offer": {
                "code": "CARTE_VINS",
                "type": "LOYALTY_PRICE",
                "description": "Wine card loyalty price",
                "mechanics": {
                  "requiresLoyaltyCard": true,
                  "exclusivity": "MEMBER_ONLY"
                },
                "funding": "STORE"
              },
              "logistics": {
                "weight": 2.8,
                "volume": 1.5,
                "fragile": true,
                "temperatureZone": "AMBIENT"
              }
            },
            {
              "lineNumber": 5,
              "product": {
                "ean": "3068320112305",
                "name": "Vanilla Ice Cream",
                "productType": "UNIT",
                "unitName": "pcs"
              },
              "requestedQuantity": 1.0,
              "pricing": {
                "basePrice": 5.20,
                "unitPrice": 5.20,
                "totalPrice": 5.20,
                "discountAmount": 0.00
              },
              "offer": {
                "code": null,
                "type": "NONE",
                "description": "No current offer"
              },
              "logistics": {
                "weight": 0.5,
                "volume": 0.001,
                "fragile": false,
                "temperatureZone": "FROZEN"
              }
            },
            {
              "lineNumber": 6,
              "product": {
                "ean": "3300000000001",
                "name": "Pommes Golden",
                "productType": "WEIGHT",
                "unitName": "kg"
              },
              "requestedQuantity": 1.5,
              "pricing": {
                "basePrice": 2.50,
                "unitPrice": 2.50,
                "totalPrice": 3.75
              },
              "logistics": {
                "weight": 1.5,
                "volume": 0.003,
                "temperatureZone": "FRESH"
              }
            },
            {
              "lineNumber": 7,
              "product": {
                "ean": "3000000000010",
                "name": "Lavazza Qualità Oro",
                "productType": "UNIT",
                "referenceVolume": 0.25,
                "unitName": "L"
              },
              "requestedQuantity": 1.0,
              "pricing": {
                "basePrice": 6.50,
                "unitPrice": 6.50,
                "totalPrice": 6.50
              },
              "offer": {
                "code": "PROMO_COFFEE",
                "type": "IMMEDIATE_DISCOUNT",
                "description": "-1€ sur le café moulu",
                "funding": "STORE"
              },
              "logistics": {
                "weight": 0.26,
                "volume": 0.003,
                "temperatureZone": "AMBIENT"
              }
            },
            {
              "lineNumber": 8,
              "product": {
                "ean": "3000000000027",
                "name": "Nutella Pot 825g",
                "productType": "UNIT",
                "referenceVolume": 0.825,
                "unitName": "kg"
              },
              "requestedQuantity": 1.0,
              "pricing": {
                "basePrice": 4.90,
                "unitPrice": 4.90,
                "totalPrice": 4.90
              },
              "logistics": {
                "weight": 0.85,
                "volume": 0.001,
                "temperatureZone": "AMBIENT"
              }
            },
            {
              "lineNumber": 9,
              "product": {
                "ean": "3300000000035",
                "name": "Petits Pois Surgelés",
                "productType": "WEIGHT",
                "unitName": "kg"
              },
              "requestedQuantity": 0.5,
              "pricing": {
                "basePrice": 4.00,
                "unitPrice": 4.00,
                "totalPrice": 2.00
              },
              "logistics": {
                "weight": 0.5,
                "volume": 0.001,
                "temperatureZone": "FROZEN"
              }
            },
            {
              "lineNumber": 10,
              "product": {
                "ean": "3300000000036",
                "name": "Dinde Rotie",
                "productType": "WEIGHT",
                "unitName": "kg"
              },
              "requestedQuantity": 1.5,
              "pricing": {
                "basePrice": 11.00,
                "unitPrice": 11.00,
                "totalPrice": 16.50
              },
              "logistics": {
                "weight": 1.5,
                "volume": 0.003,
                "temperatureZone": "HOT"
              }
            }
          ],
          "summary": {
            "totalItems": 14,
            "totalPriceExclTax": 75.65,
            "totalTax": 7.56,
            "totalPriceInclTax": 83.21,
            "logisticsTotals": {
              "totalWeight": 20.11,
              "totalVolume": 10.518,
              "requiresTemperatureControl": true,
              "temperatureZones": [
                "HOT",
                "FROZEN",
                "AMBIENT",
                "FRESH"
              ],
              "containsFragileItems": true
            }
          }
        }
        """;

    public static void main(String[] args) {
        try {
            // 1. Authenticate with the 'service' account to get a JWT
            String token = authenticate("service", "service");
            if (token == null) {
                System.err.println("Failed to authenticate. Exiting.");
                return;
            }
            // 2. Submit the order using the token
            submitOrder(token);
        } catch (Exception e) {
            System.err.println("Technical error during order submission: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Submits the order payload to the backend using the Bearer token.
     *
     * @param token the valid JWT for authorization.
     * @throws Exception if the HTTP request fails.
     */
    private static void submitOrder(String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        // Construct request with Bearer token
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ORDER_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(JSON_PAYLOAD))
                .build();
        System.out.println("Sending order to " + ORDER_URL + "...");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Display result
        System.out.println("Status Code: " + response.statusCode());
        System.out.println("Response Body: " + response.body());
        if (response.statusCode() == 202) {
            System.out.println("SUCCESS: Order accepted.");
        } else if (response.statusCode() == 403 || response.statusCode() == 401) {
            System.err.println("FAILURE: Authorization failed.");
        } else {
            System.err.println("FAILURE: Order rejected.");
        }
    }

}