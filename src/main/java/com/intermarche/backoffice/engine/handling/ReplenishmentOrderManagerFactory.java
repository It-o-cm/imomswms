package com.intermarche.backoffice.engine.handling;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.store.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.intermarche.backoffice.engine.replenishment.AutomaticReplenishmentService;

/** Configured worker of the existing store activity engine, sharing its priority and store scope. */
@ApplicationScoped
public class ReplenishmentOrderManagerFactory extends BaseOrderHandlerBuilderFactory {
    @Inject AutomaticReplenishmentService service;
    public static final String SCHEMA="""
        {"type":"object","properties":{
          "sourceBuildingCode":{"type":"string","minLength":1,"description":"Entrepôt source appartenant au magasin"},
          "destinationBuildingCode":{"type":"string","minLength":1,"description":"Bâtiment magasin à réachalander"},
          "eans":{"type":"array","items":{"type":"string"},"uniqueItems":true,"description":"Produits concernés ; vide signifie tous les produits avec emplacements dans les deux bâtiments"},
          "minimumStock":{"type":"number","minimum":0,"maximum":1000000,"description":"Déclencher lorsque le solde projeté net de marge et réservations atteint ce seuil, en unité de base du produit"},
          "targetStock":{"type":"number","exclusiveMinimum":0,"maximum":1000000,"description":"Solde net cible après réachalandage"},
          "horizonMinutes":{"type":"integer","minimum":0,"maximum":10080,"description":"Horizon des besoins et réservations à anticiper"},
          "completionMinutes":{"type":"integer","minimum":1,"maximum":1440,"description":"Durée prévue de manutention avant disponibilité en rayon"},
          "maxWeightKg":{"type":"number","exclusiveMinimum":0,"maximum":20000,"description":"Charge maximale par tournée en kg ; produit sans poids connu exclu"},
          "maxVolumeLiters":{"type":"number","exclusiveMinimum":0,"maximum":100000,"description":"Volume maximal par tournée en litres ; produit sans volume connu exclu"},
          "minimumBatchWeightKg":{"type":"number","minimum":0,"maximum":20000,"description":"Poids minimal de déclenchement d’une tournée, 0 pour aucun minimum"},
          "maxLines":{"type":"integer","minimum":1,"maximum":100,"description":"Nombre maximal de lignes par tournée"},
          "maxOrdersPerCycle":{"type":"integer","minimum":1,"maximum":50,"description":"Nombre maximal de tournées créées par cycle"},
          "separateTemperatureZones":{"type":"boolean","description":"Séparer aussi ambiant, frais et surgelé en tournées distinctes ; l’alimentaire et l’entretien (non alimentaire) sont toujours séparés"},
          "allowPartial":{"type":"boolean","description":"Autoriser un réachalandage partiel si l’entrepôt ne couvre pas toute la cible"},
          "enabled":{"type":"boolean"}
        },"required":["sourceBuildingCode","destinationBuildingCode","minimumStock","targetStock","horizonMinutes","completionMinutes","maxWeightKg","maxVolumeLiters","minimumBatchWeightKg","maxLines","maxOrdersPerCycle","allowPartial","enabled","separateTemperatureZones"]}
        """;
    public static String schema(){return SCHEMA;}
    public String getName(){return "Replenishment";}
    public BaseOrderHandlerBuilder build(JsonNode spec) {
        var errors=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V7).getSchema(SCHEMA).validate(spec);
        if(!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
        if(spec.path("targetStock").decimalValue().compareTo(spec.path("minimumStock").decimalValue())<=0)
            throw new IllegalArgumentException("Le stock cible doit dépasser le seuil de déclenchement");
        if(spec.path("minimumBatchWeightKg").decimalValue().compareTo(spec.path("maxWeightKg").decimalValue())>0)
            throw new IllegalArgumentException("La charge minimale doit être inférieure à la charge maximale");
        JsonNode config=spec.deepCopy();
        return new BaseOrderHandlerBuilder(){public void process(Store store){service.evaluate(store,config);}};
    }
}
