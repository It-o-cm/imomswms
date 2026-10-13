package com.intermarche.backoffice.client;

import com.intermarche.backoffice.client.graphql.FulfillmentGraphQLClient;
import com.intermarche.backoffice.client.graphql.OrderGraphQLClient;
import com.intermarche.backoffice.client.importers.*;

import static java.lang.Thread.sleep;

public class ImportAllClient {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Importing Products...");
        ProductImporterClient.main(args);
        System.out.println("Importing Products Families...");
        ProductFamilyImporterClient.main(args);
        System.out.println("Importing Stores...");
        StoreImporterClient.main(args);
        System.out.println("Importing Store Groups...");
        StoreGroupImporterClient.main(args);
        System.out.println("Importing Buildings...");
        BuildingImporterClient.main(args);
        System.out.println("Importing Buffers...");
        BufferImporterClient.main(args);
        System.out.println("Importing Aisles...");
        //AisleImporterClient.main(args);
        System.out.println("Importing Locations...");
        LocationImporterClient.main(args);
        System.out.println("Importing Employees...");
        EmployeeImporterClient.main(args);
        System.out.println("Importing Tote...");
        ToteImporterClient.main(args);
        System.out.println("Importing Configurations...");
        ConfigurationImporterClient.main(args);
        System.out.println("Send an Order...");
        SendOrder.main(args);
        sleep(2000); // Wait a bit to ensure the order is processed before retrieval
        System.out.println("Retrieve Order...");
        OrderGraphQLClient.main(args);
        System.out.println("Retrieve Fulfillment...");
        FulfillmentGraphQLClient.main(args);
    }
}
