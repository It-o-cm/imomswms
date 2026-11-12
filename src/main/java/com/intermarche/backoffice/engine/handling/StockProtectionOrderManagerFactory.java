package com.intermarche.backoffice.engine.handling;
import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.stock.StockProtectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
@ApplicationScoped
public class StockProtectionOrderManagerFactory extends BaseOrderHandlerBuilderFactory {
    @Inject StockProtectionService protection;
    public static String schema(){return """
      {"type":"object","properties":{
        "buildingCode":{"type":"string","minLength":1,"description":"Magasin dont les rayons doivent être protégés"},
        "targetBufferCode":{"type":"string","minLength":1,"description":"Zone sécurisée où déposer les bacs de picking, dans ce même bâtiment"},
        "eans":{"type":"array","items":{"type":"string"},"uniqueItems":true,"description":"Vide pour tous les produits"},
        "horizonMinutes":{"type":"integer","minimum":0,"maximum":43200,"description":"Horizon des disponibilités futures ; 0 signifie toutes les échéances connues"},
        "interventionMinutes":{"type":"integer","minimum":1,"maximum":120,"description":"Délai maximum d’intervention urgente"},
        "maxLinesPerOrder":{"type":"integer","minimum":1,"maximum":100},
        "enabled":{"type":"boolean"}
      },"required":["buildingCode","targetBufferCode","horizonMinutes","interventionMinutes","maxLinesPerOrder","enabled"]}
      """;}
    public String getName(){return "StockProtection";}
    public BaseOrderHandlerBuilder build(JsonNode config){
        var errors=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V7).getSchema(schema()).validate(config);
        if(!errors.isEmpty())throw new IllegalArgumentException(errors.toString());
        JsonNode rule=config.deepCopy();
        return new BaseOrderHandlerBuilder(){public void process(Store store){protection.evaluate(store,rule);}};
    }
}
