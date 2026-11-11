package com.intermarche.backoffice.engine.policy;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import com.intermarche.backoffice.rules.RuleSchemaRegistry;import java.util.*;
/** Fixed execution constraints are intentionally distinct from mission-triggering rules. */
public final class OperationalPolicySchemas {
 public static final String CATEGORY="OPERATIONAL_POLICY";
 public static final List<String> TYPES=List.of("TraceabilityConstraints","InventoryConstraints","ApprovalConstraints","SupportConstraints","ParcelConstraints","ConsultationConstraints","CateringConstraints");
 private static final ObjectMapper JSON=new ObjectMapper();
 private OperationalPolicySchemas(){}
 private static ObjectNode bool(String title,boolean value){var p=JSON.createObjectNode().put("type","boolean").put("title",title).put("default",value);return p;}
 private static ObjectNode number(String title,String type,double minimum,double maximum,double value){var p=JSON.createObjectNode().put("type",type).put("title",title).put("minimum",minimum).put("maximum",maximum);if("integer".equals(type))p.put("default",(long)value);else p.put("default",value);return p;}
 private static ObjectNode choice(String title,String value,String... values){var p=JSON.createObjectNode().put("type","string").put("title",title).put("default",value);p.set("enum",JSON.valueToTree(values));return p;}
 public static ObjectNode schema(String type){var root=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var p=root.putObject("properties");p.set("type",JSON.createObjectNode().put("type","string").put("const",type));p.set("name",JSON.createObjectNode().put("type","string").put("maxLength",200));p.set("priority",JSON.createObjectNode().put("type","integer"));p.set("id",JSON.createObjectNode().put("type","string"));
  switch(type){
   case "TraceabilityConstraints"->{var product=number("Produit","integer",1,Long.MAX_VALUE,1);product.remove("default");p.set("productId",product);p.set("context",choice("Opération","PICKING","RECEIPT","PICKING","SHIPPING","RETURN"));p.set("lotRequired",bool("Lot fabricant obligatoire",false));p.set("serialRequired",bool("Numéro de série obligatoire",false));p.set("dateRequired",bool("Date du lot obligatoire",false));p.set("minimumRemainingDays",number("Durée restante minimale · jours","integer",0,3650,0));p.set("bestBeforeStrict",bool("Appliquer aussi la durée minimale à la DDM",false));p.set("allocationMethod",choice("Choix du lot","FEFO","FEFO","FIFO"));for(String key:List.of("hazardClass","separationGroup","incompatibleHazards"))p.set(key,JSON.createObjectNode().put("type","string").put("title",key.equals("hazardClass")?"Classe de danger":key.equals("separationGroup")?"Groupe à séparer":"Classes incompatibles · séparées par virgules").put("default","").put("maxLength",key.equals("incompatibleHazards")?600:60).put("pattern","^[A-Z0-9_, -]*$"));}
   case "InventoryConstraints"->{p.set("absoluteTolerance",number("Tolérance absolue","number",0,1000000,0));var relative=number("Tolérance relative · %","number",0,100,0);relative.set("type",JSON.valueToTree(List.of("number","null")));relative.set("default",NullNode.instance);p.set("relativeTolerancePercent",relative);p.set("maximumRounds",number("Nombre maximal de comptages","integer",1,5,2));p.set("differentCounterRequired",bool("Recomptage par un autre préparateur",false));p.set("bayMovementMode",choice("Mouvements pendant le comptage","RECONCILE_MOVEMENTS","RECONCILE_MOVEMENTS","TARGETED_FREEZE"));}
   case "ApprovalConstraints"->{p.set("expiryMinutes",number("Validité d’une approbation · minutes","integer",5,120,30));var actions=JSON.createObjectNode().put("type","array").put("title","Corrections pouvant être soumises").put("uniqueItems",true).put("minItems",1);actions.set("items",choice("Action","RETURN_ITEM","RETURN_ITEM","MOVE_ITEM","REOPEN_SUPPLY","CORRECT_SHORTAGE"));actions.set("default",JSON.valueToTree(List.of("RETURN_ITEM","MOVE_ITEM","REOPEN_SUPPLY","CORRECT_SHORTAGE")));p.set("allowedActions",actions);p.set("independentApproverRequired",bool("Approbation par un autre responsable · obligatoire",true).put("const",true));}
   case "SupportConstraints"->{var kinds=JSON.createObjectNode().put("type","array").put("title","Types de supports autorisés").put("uniqueItems",true).put("minItems",1);kinds.set("items",choice("Type","TOTE","TOTE","CARTON","PALLET","CART"));kinds.set("default",JSON.valueToTree(List.of("TOTE","CARTON","PALLET","CART")));p.set("allowedKinds",kinds);p.set("maximumFillPercent",number("Remplissage maximal du volume · %","integer",10,100,100));var weight=number("Poids maximal par support · kg","number",0.001,10000,25);weight.set("type",JSON.valueToTree(List.of("number","null")));weight.set("default",NullNode.instance);p.set("maximumWeightKg",weight);p.set("separateFoodAndTemperature",bool("Séparer températures et alimentaire/entretien · obligatoire",true).put("const",true));}
   case "ParcelConstraints"->{p.set("requireWeight",bool("Poids réel obligatoire",true));p.set("requireDimensions",bool("Dimensions obligatoires",false));p.set("requireContentConfirmation",bool("Confirmation du contenu obligatoire",true));p.set("requireScan",bool("Scan du colis obligatoire",false));}
   case "ConsultationConstraints"->{p.set("showOwnActivity",bool("Afficher l’activité personnelle",true));p.set("showCommandHistory",bool("Afficher l’historique des confirmations",true));p.set("showPreviousProductInstructions",bool("Afficher les anciennes consignes produit",true));p.set("activityWindowHours",number("Historique consultable · heures","integer",1,168,24));}
   case "CateringConstraints"->{
    p.set("enabled",bool("Traiteur activé",true));
    p.set("autoReferenceUnknownItems",bool("Référencer automatiquement un EAN inconnu",true));
    p.set("defaultSourcingMode",choice("Mode d’obtention par défaut","FABRICATED","FABRICATED","SUPPLIED","STOCKED"));
    p.set("defaultTemperatureZone",choice("Zone de température par défaut","CHILLED","AMBIENT","CHILLED","FROZEN","HOUSEHOLD"));
    p.set("recipeRequired",bool("Nomenclature obligatoire",false));
    p.set("shelfLifeHours",number("Durée de conservation · heures","integer",1,720,48));
    p.set("maxAdvanceHours",number("Avance maximale de fabrication · heures","integer",0,720,24));
    p.set("consumptionMarginHours",number("Marge de consommation · heures","integer",0,72,6));
    p.set("stagingLeadMinutes",number("Délai de mise en attente · minutes","integer",0,1440,60));
    p.set("defaultLaborSeconds",number("Temps de fabrication par défaut · secondes","integer",0,86400,600));
    p.set("batchAcrossOrders",bool("Regrouper entre commandes",true));
    p.set("proposalHorizonDays",number("Horizon de proposition · jours","integer",1,60,14));
    p.set("autoValidateProposals",bool("Valider automatiquement les propositions",false));
    p.set("planningLeadHours",number("Anticipation de planification · heures","integer",1,240,24));
    p.set("componentShortagePolicy",choice("Manque de composant","WARN","BLOCK","WARN"));
    p.set("supplierLeadDays",number("Délai fournisseur · jours","integer",0,30,2));
    var cutoff=JSON.createObjectNode();cutoff.set("type",JSON.valueToTree(List.of("string","null")));cutoff.put("title","Heure limite de commande fournisseur").put("pattern","^([01]\\d|2[0-3]):[0-5]\\d$");cutoff.set("default",NullNode.instance);p.set("supplierOrderCutoff",cutoff);
    var template=JSON.createObjectNode();template.set("type",JSON.valueToTree(List.of("string","null")));template.put("title","Gabarit d’étiquette").put("maxLength",100);template.put("default","CATERING_UNIT");p.set("labelTemplate",template);
    p.set("labelCopies",number("Nombre d’exemplaires d’étiquette","integer",1,5,1));
    p.set("handoverRequiresPaymentAck",bool("Accusé d’encaissement obligatoire à la remise",true));
    p.set("amendmentAfterStart",choice("Modification après début de fabrication","REFUSE","REFUSE","ACCEPT_DECREASE"));
    p.set("cancelAfterStart",choice("Annulation après début de fabrication","ACCEPT_WITH_DISPOSITION","REFUSE","ACCEPT_WITH_DISPOSITION"));
    var disp=JSON.createObjectNode().put("type","array").put("title","Devenirs d’annulation autorisés").put("uniqueItems",true).put("minItems",1);disp.set("items",choice("Devenir","REALLOCATE","REALLOCATE","ISOLATE"));disp.set("default",JSON.valueToTree(List.of("REALLOCATE","ISOLATE")));p.set("cancelDispositions",disp);
    var roles=JSON.createObjectNode().put("type","array").put("title","Rôles destinataires des notifications").put("uniqueItems",true).put("minItems",1);roles.set("items",choice("Rôle","MANAGER","ADMIN","CASHIER","MANAGER","PICKER"));roles.set("default",JSON.valueToTree(List.of("MANAGER")));p.set("notificationRoles",roles);
   }
   default->throw new IllegalArgumentException("Politique inconnue");
  }
  var required=root.putArray("required");p.fieldNames().forEachRemaining(k->{if(!Set.of("name","priority","id").contains(k))required.add(k);});return root;
 }
 public static List<RuleSchemaRegistry.Definition> definitions(){String[] names={"Qualité et traçabilité","Contrôle des inventaires","Approbation des corrections","Utilisation des supports","Contrôle final des colis","Consultation opérateur","Traiteur et réceptions"};var result=new ArrayList<RuleSchemaRegistry.Definition>();for(int n=0;n<TYPES.size();n++)result.add(new RuleSchemaRegistry.Definition(TYPES.get(n),CATEGORY,names[n],schema(TYPES.get(n)).toString()));return result;}
 public static ObjectNode defaults(String type){var v=JSON.createObjectNode().put("type",type);schema(type).path("properties").fields().forEachRemaining(e->{if(e.getValue().has("default"))v.set(e.getKey(),e.getValue().get("default").deepCopy());});return v;}
 public static String scope(JsonNode value){String kind=value.path("type").asText();if(!TYPES.contains(kind))throw new IllegalArgumentException("Politique inconnue");return "TraceabilityConstraints".equals(kind)?kind+":"+value.path("productId").asLong()+":"+value.path("context").asText():kind;}
}
