package com.intermarche.backoffice.engine.operator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stable across JSON property order and equivalent decimal representations. */
public final class CommandCanonical {
    private static final ObjectMapper JSON=new ObjectMapper();
    private CommandCanonical(){}
    private static JsonNode sorted(JsonNode value){
        if(value.isObject()){ObjectNode out=JSON.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)if(!value.get(key).isNull())out.set(key,sorted(value.get(key)));return out;}
        if(value.isArray()){ArrayNode out=JSON.createArrayNode();value.forEach(v->out.add(sorted(v)));return out;}
        if(value.isNumber())return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        return value;
    }
    public static String hash(String json){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sorted(JSON.readTree(json)).toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalArgumentException("Commande non sérialisable",e);}}
}
