package com.intermarche.backoffice.engine.catalog;
import com.intermarche.backoffice.domain.catalog.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.operator.*;
import com.intermarche.backoffice.engine.storeops.StoreOperationsResource;
import com.intermarche.backoffice.engine.executing.OperatorCommandInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.util.*;

/** Store-scoped product information and exact physical-unit contracts. Resolution never moves stock. */
@ApplicationScoped @Transactional
public class ProductInformationService {
 @Inject StoreOperationsResource scopes;@Inject OperatorContext context;@Inject EntityManager em;@Inject ObjectMapper json;
 public static String unit(Product p){return switch(p.productType){case UNIT->"PCS";case WEIGHT->"KG";case VOLUME->"L";};}
 Store store(Long id,boolean write){if(id==null)throw new BadRequestException("Magasin requis");context.account();Store s=scopes.scope(id);if(write){if(Collections.disjoint(context.roles(context.account()),Set.of("MANAGER","ADMIN")))throw new ForbiddenException();lock(s);}return s;}
 void lock(Store s){em.createNativeQuery("select id from stores where id = ?1 for update").setParameter(1,s.id).getSingleResult();}
 Product product(Long id){Product p=Product.findById(id);if(p==null || !p.active)throw new NotFoundException();return p;}
 String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="ProductInformationEdit") public static class Edit {public Long storeId;public String expectedRevision,text,kind,value,context,packaging,code,label,level,parentCode,unit;public Long identifierId;public BigDecimal factor;}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="ProductMeasurementRequest") public static class Measurement {public Long storeId;public ProductMeasure.Input measurement;}
 public Object measure(Long id,Measurement input){
  if(input==null)throw new BadRequestException("Mesure requise");Store s=store(input.storeId,false);lock(s);Product p=product(id);String quantity=ProductMeasure.normalize(input.measurement,unit(p)).toPlainString();
  var result=new LinkedHashMap<String,Object>();String scanId=UUID.randomUUID().toString();result.put("state","RESOLVED");result.put("scanId",scanId);result.put("quantity",quantity);result.put("unit",unit(p));result.put("product",productView(p));result.put("productRevision",view(s,p).get("revision"));result.put("measurement",input.measurement);
  var receipt=new ProductScanReceipt();receipt.store=s;receipt.principal=context.account().getUsername();receipt.scanId=scanId;receipt.fingerprint=CommandCanonical.hash(encode(input));receipt.result=encode(result);receipt.persistAndFlush();return result;
 }
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="ProductConversion") public static class Conversion {public Long storeId;public String packaging;public BigDecimal quantity;}
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="ProductScanRequest") public static class Scan {public Long storeId;public String scanId,code,kind,context;public Long identifierId,productId;}
 Map<String,Object> productView(Product p){var v=new LinkedHashMap<String,Object>();v.put("id",p.id);v.put("ean",p.ean);v.put("name",p.name);v.put("description",p.description);v.put("characteristics",p.characteristics);v.put("brand",p.brand);v.put("unit",unit(p));v.put("productType",p.productType);v.put("temperatureZone",p.temperatureZone);v.put("referenceWeight",p.referenceWeight);v.put("referenceVolume",p.referenceVolume);v.put("referenceWeightKnown",p.referenceWeight!=null);v.put("referenceVolumeKnown",p.referenceVolume!=null);v.put("measurementsIncomplete",p.referenceWeight==null||p.referenceVolume==null);return v;}
 List<ProductIdentifier> identifiers(Store s,Product p){return ProductIdentifier.list("store=?1 and product=?2 and active=true order by id",s,p);}
 List<ProductPackaging> packages(Store s,Product p){return ProductPackaging.list("store=?1 and product=?2 and active=true order by code",s,p);}
 public Map<String,Object> view(Long storeId,Long id){return view(store(storeId,false),product(id));}
 Map<String,Object> view(Store s,Product p){
  var v=new LinkedHashMap<String,Object>();v.put("product",productView(p));
  boolean historyVisible=!Collections.disjoint(context.roles(context.account()),Set.of("MANAGER","ADMIN"))||com.intermarche.backoffice.engine.fieldops.OperationalPolicyVersions.active(s,"ConsultationConstraints").path("showPreviousProductInstructions").asBoolean();v.put("instructionHistoryVisible",historyVisible);
  v.put("instructions",ProductInstruction.<ProductInstruction>find("store=?1 and product=?2 order by revision desc",s,p).range(0,historyVisible?19:0).list().stream().map(i->Map.of("revision",i.revision,"text",i.text,"author",i.author,"createdAt",i.createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS))).toList());
  v.put("identifiers",identifiers(s,p).stream().map(i->Map.of("id",i.id,"kind",i.kind,"value",i.value,"context",i.context,"packaging",i.packaging)).toList());
  v.put("packagings",packagingViews(packages(s,p),unit(p)));
  v.put("canEdit",!Collections.disjoint(context.roles(context.account()),Set.of("MANAGER","ADMIN")));
  v.put("revision",CommandCanonical.hash(encode(v)));return v;
 }
 List<Map<String,Object>> packagingViews(List<ProductPackaging> packages,String unit){
  var factors=factors(packages,unit);var rows=new ArrayList<Map<String,Object>>();
  rows.add(Map.of("code","BASE","label",unit.equals("PCS")?"Pièce":unit.equals("KG")?"Kilogramme":"Litre","level","UNIT","parentCode","BASE","factor","1","unit",unit,"baseFactor","1"));
  packages.forEach(x->rows.add(Map.of("code",x.code,"label",x.label,"level",x.level,"parentCode",x.parentCode,"factor",x.factor.toPlainString(),"unit",x.unit,"baseFactor",factors.get(x.code).toPlainString())));
  return rows;
 }
 void revision(Edit e,Store s,Product p){if(e.expectedRevision==null || !e.expectedRevision.equals(view(s,p).get("revision")))throw new OperatorFailure(409,"STALE_PRODUCT","La fiche a changé. Actualisez avant d’enregistrer.");}
 public Object instructions(Long id,Edit e){if(e==null)throw new BadRequestException("Informations requises");Store s=store(e.storeId,true);Product p=product(id);revision(e,s,p);text(e.text,5000);var latest=ProductInstruction.<ProductInstruction>find("store=?1 and product=?2 order by revision desc",s,p).firstResult();var item=new ProductInstruction();item.store=s;item.product=p;item.revision=latest==null?1:latest.revision+1;item.text=e.text.trim();item.author=context.account().getUsername();item.persistAndFlush();return view(s,p);}
 static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("Champ requis, "+max+" caractères maximum");}
 static String code(String value){text(value,40);String v=value.trim().toUpperCase(Locale.ROOT);if(!v.matches("[A-Z0-9_-]{1,40}"))throw new IllegalArgumentException("Code de conditionnement invalide");return v;}
 public Object identifier(Long id,Edit e){
  if(e==null)throw new BadRequestException("Informations requises");Store s=store(e.storeId,true);Product p=product(id);revision(e,s,p);text(e.kind,12);text(e.value,120);if(!Set.of("GTIN","INTERNAL","SUPPLIER").contains(e.kind))throw new IllegalArgumentException("Type d’identifiant inconnu");
  String ctx=e.context==null?"":e.context.trim().toUpperCase(Locale.ROOT);if(ctx.length()>100 || e.kind.equals("SUPPLIER")&&ctx.isBlank())throw new IllegalArgumentException("Contexte fournisseur requis (100 caractères maximum)");if(!e.kind.equals("SUPPLIER")&&!ctx.isBlank())throw new IllegalArgumentException("Le contexte est réservé aux codes fournisseur");
  String normalized=e.kind.equals("GTIN")?Gs1Parser.gtin(e.value.trim()):e.value.trim().toUpperCase(Locale.ROOT);
  String packaging=e.packaging==null?"BASE":code(e.packaging);if(!packaging.equals("BASE")&&packages(s,p).stream().noneMatch(x->x.code.equals(packaging)))throw new IllegalArgumentException("Conditionnement inconnu");
  ProductIdentifier item=e.identifierId==null?null:ProductIdentifier.findById(e.identifierId);if(item!=null && (!item.store.id.equals(s.id)||!item.product.id.equals(p.id)))throw new NotFoundException();if(e.identifierId!=null&&item==null)throw new NotFoundException();
  ProductIdentifier collision=ProductIdentifier.find("store=?1 and kind=?2 and context=?3 and normalized=?4",s,e.kind,ctx,normalized).firstResult();if(item==null&&collision!=null&&!collision.active&&collision.product.id.equals(p.id))item=collision;if(collision!=null && (item==null||!collision.id.equals(item.id)))throw new OperatorFailure(409,"IDENTIFIER_COLLISION","Ce code appartient déjà à une autre définition. Choisissez son produit et son contexte explicitement.");
  if(e.kind.equals("GTIN"))for(Product other:primaryCandidates(normalized))if(!other.id.equals(p.id))throw new OperatorFailure(409,"IDENTIFIER_COLLISION","Ce GTIN est le code principal d’un autre produit.");
  if(item==null){if(identifiers(s,p).size()>=100)throw new IllegalArgumentException("100 identifiants maximum");item=new ProductIdentifier();item.store=s;item.product=p;}
  item.kind=e.kind;item.value=e.value.trim();item.normalized=normalized;item.context=ctx;item.packaging=packaging;item.active=true;item.persistAndFlush();return view(s,p);
 }
 public Object removeIdentifier(Long productId,Long id,Edit e){if(e==null)throw new BadRequestException("Informations requises");Store s=store(e.storeId,true);Product p=product(productId);revision(e,s,p);ProductIdentifier x=ProductIdentifier.find("id=?1 and store=?2 and product=?3",id,s,p).firstResult();if(x==null)throw new NotFoundException();x.active=false;em.flush();return view(s,p);}
 public Object packaging(Long id,Edit e){
  if(e==null)throw new BadRequestException("Informations requises");Store s=store(e.storeId,true);Product p=product(id);revision(e,s,p);String c=code(e.code);if(c.equals("BASE"))throw new IllegalArgumentException("L’unité de base ne peut pas être redéfinie");text(e.label,80);if(!Set.of("UNIT","PACK","CASE","PALLET").contains(e.level==null?"":e.level))throw new IllegalArgumentException("Niveau de conditionnement requis");
  if(!unit(p).equals(e.unit))throw new IllegalArgumentException("Dimension différente de l’unité de base");positive(e.factor);String parent=e.parentCode==null?"BASE":code(e.parentCode);
  ProductPackaging x=ProductPackaging.find("store=?1 and product=?2 and code=?3",s,p,c).firstResult();if(x==null){if(packages(s,p).size()>=24)throw new IllegalArgumentException("24 conditionnements maximum");x=new ProductPackaging();x.store=s;x.product=p;x.code=c;}
  x.label=e.label.trim();x.level=e.level;x.parentCode=parent;x.unit=e.unit;x.factor=e.factor;x.active=true;x.persist();em.flush();factors(packages(s,p),unit(p));return view(s,p);
 }
 static void positive(BigDecimal q){if(q==null||q.signum()<=0||q.scale()>6||q.precision()>19||q.precision()-q.scale()>13)throw new IllegalArgumentException("Quantité positive exacte, six décimales maximum");}
 Map<String,BigDecimal> factors(List<ProductPackaging> packages,String base){Map<String,ProductPackaging> byCode=new HashMap<>();packages.forEach(x->byCode.put(x.code,x));var result=new HashMap<String,BigDecimal>();result.put("BASE",BigDecimal.ONE);for(var p:packages)factor(p.code,byCode,result,new HashSet<>(),base);return result;}
 BigDecimal factor(String code,Map<String,ProductPackaging> packages,Map<String,BigDecimal> result,Set<String> path,String unit){
  if(result.containsKey(code))return result.get(code);if(!path.add(code)||path.size()>24)throw new IllegalArgumentException("Cycle de conversion interdit");var p=packages.get(code);if(p==null||!unit.equals(p.unit))throw new IllegalArgumentException("Parent de conversion absent ou incompatible");positive(p.factor);BigDecimal value=p.factor.multiply(factor(p.parentCode,packages,result,path,unit)).stripTrailingZeros();if(value.scale()>6||value.precision()>19||value.precision()-value.scale()>13||unit.equals("PCS")&&value.scale()>0)throw new IllegalArgumentException("Conversion inexacte ou fraction d’une pièce");path.remove(code);result.put(code,value);return value;
 }
 public Object convert(Long id,Conversion c){if(c==null)throw new BadRequestException("Conversion requise");Store s=store(c.storeId,false);Product p=product(id);positive(c.quantity);String packaging=c.packaging==null?"BASE":code(c.packaging);var factors=factors(packages(s,p),unit(p));if(!factors.containsKey(packaging))throw new IllegalArgumentException("Conditionnement inconnu");BigDecimal quantity=c.quantity.multiply(factors.get(packaging)).stripTrailingZeros();positive(quantity);if(unit(p).equals("PCS")&&quantity.scale()>0)throw new IllegalArgumentException("La conversion produit une fraction de pièce");return Map.of("quantity",quantity.toPlainString(),"unit",unit(p),"packaging",packaging,"factor",factors.get(packaging).toPlainString());}
 List<Product> primaryCandidates(String value){var variants=new HashSet<String>();variants.add(value);if(value.matches("[0-9]{8,14}")){String padded="0".repeat(14-value.length())+value;for(int n:List.of(8,12,13,14))if(padded.substring(0,14-n).chars().allMatch(c->c=='0'))variants.add(padded.substring(14-n));}return Product.list("active=true and ean in ?1",variants);}
 record Candidate(Long identifierId,Product product,String context,String packaging){}
 List<Candidate> candidates(Store s,String identifier,String kind,String ctx){
  var choices=new ArrayList<Candidate>();String normalized=identifier.trim().toUpperCase(Locale.ROOT);if(identifier.matches("[0-9]{8,14}"))normalized="0".repeat(14-identifier.length())+identifier;
  String query="select i from ProductIdentifier i join fetch i.product where i.store=?1 and i.active=true and (i.normalized=?2 or i.normalized=?3)";var args=new ArrayList<Object>(List.of(s,identifier.trim().toUpperCase(Locale.ROOT),normalized));
  if(kind!=null&&!kind.isBlank()){if(!Set.of("GTIN","INTERNAL","SUPPLIER").contains(kind))throw new IllegalArgumentException("Type inconnu");query+=" and i.kind=?"+(args.size()+1);args.add(kind);}
  if(ctx!=null&&!ctx.isBlank()){query+=" and i.context=?"+(args.size()+1);args.add(ctx.trim().toUpperCase(Locale.ROOT));}
  for(var i:ProductIdentifier.<ProductIdentifier>list(query,args.toArray())){Product p=org.hibernate.Hibernate.unproxy(i.product,Product.class);if(p.active)choices.add(new Candidate(i.id,p,i.context,i.packaging));}
  if(ctx==null||ctx.isBlank())if(kind==null||kind.isBlank()||kind.equals("GTIN"))for(Product p:primaryCandidates(identifier))choices.add(new Candidate(-p.id,p,"Principal","BASE"));
  return choices.stream().collect(java.util.stream.Collectors.toMap(c->c.product.id+"|"+c.packaging,c->c,(a,b)->a,LinkedHashMap::new)).values().stream().toList();
 }
 public Object resolve(Scan input){
  if(input==null||input.scanId==null||!input.scanId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw new IllegalArgumentException("Identifiant du scan physique requis");Store s=store(input.storeId,false);lock(s);String actor=context.account().getUsername();
  var identity=new LinkedHashMap<String,Object>();identity.put("code",input.code);identity.put("kind",input.kind);identity.put("context",input.context);identity.put("productId",input.productId);String hash=CommandCanonical.hash(encode(identity));
  ProductScanReceipt receipt=ProductScanReceipt.find("store=?1 and principal=?2 and scanId=?3",s,actor,input.scanId).firstResult();
  if(receipt!=null){if(!receipt.fingerprint.equals(hash))throw new OperatorFailure(409,"SCAN_ID_REUSED","Ce scanId désigne un autre scan physique");try{var previous=json.readTree(receipt.result);if(!previous.path("state").asText().equals("AMBIGUOUS")){if(input.identifierId!=null&&previous.path("identifierId").asLong()!=input.identifierId)throw new OperatorFailure(409,"SCAN_SELECTION_CHANGED","La sélection de ce scan est déjà confirmée");return json.readValue(receipt.result,Map.class);}}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
  var parsed=("INTERNAL".equals(input.kind)||"SUPPLIER".equals(input.kind))?new Gs1Parser.Parsed(input.code,Map.of(),null,null,List.of()):Gs1Parser.parse(input.code);
  List<Candidate> candidates=candidates(s,parsed.identifier(),input.kind,input.context);if(input.productId!=null)candidates=candidates.stream().filter(c->c.product.id.equals(input.productId)).toList();
  var result=new LinkedHashMap<String,Object>();result.put("scanId",input.scanId);result.put("parserVersion","GS1-SUBSET-1");result.put("fields",parsed.fields());result.put("warnings",parsed.warnings());
  if(input.identifierId!=null){candidates=candidates.stream().filter(c->c.identifierId.equals(input.identifierId)).toList();if(candidates.size()!=1)throw new IllegalArgumentException("Choix non présent dans les produits autorisés pour ce code");}
  if(candidates.isEmpty()){result.put("state","UNKNOWN");result.put("choices",List.of());}
  else if(candidates.size()>1){result.put("state","AMBIGUOUS");result.put("choices",candidates.stream().map(c->Map.of("identifierId",c.identifierId,"product",productView(c.product),"context",c.context,"packaging",c.packaging)).toList());}
  else{
   Candidate c=candidates.getFirst();Product p=c.product;var allFactors=factors(packages(s,p),unit(p));BigDecimal factor=allFactors.get(c.packaging);if(factor==null)throw new IllegalArgumentException("Conditionnement du code absent");
   BigDecimal quantity=parsed.quantity();if(quantity!=null && !unit(p).equals(parsed.unit()))throw new IllegalArgumentException("La mesure du code a une autre dimension que le produit");
   if(quantity==null && unit(p).equals("PCS"))quantity=factor;
   else if(quantity!=null&&parsed.unit().equals("PCS"))quantity=quantity.multiply(factor);
   if(quantity!=null)positive(quantity);
   result.put("state","RESOLVED");result.put("identifierId",c.identifierId);result.put("product",productView(p));result.put("packaging",c.packaging);var packagingDefinition=packages(s,p).stream().filter(x->x.code.equals(c.packaging)).findFirst().orElse(null);result.put("packagingLabel",packagingDefinition==null?"Unité":packagingDefinition.label);result.put("packagingLevel",packagingDefinition==null?"UNIT":packagingDefinition.level);result.put("baseFactor",factor.toPlainString());result.put("quantity",quantity==null?null:quantity.toPlainString());result.put("unit",unit(p));result.put("productRevision",view(s,p).get("revision"));
   if(!parsed.fields().isEmpty()&&parsed.fields().containsKey("02"))result.put("quantity",parsed.quantity().toPlainString()); // (37) already counts base trade items identified by (02).
  }
  if(receipt==null){receipt=new ProductScanReceipt();receipt.store=s;receipt.principal=actor;receipt.scanId=input.scanId;receipt.fingerprint=hash;}receipt.result=encode(result);receipt.persistAndFlush();return result;
 }
 /** The receipt bounds the cumulative quantity of split lines; business request keys make retries idempotent. */
 public void preparePick(OperatorCommandInput c,Product p,Store store,Employee employee){
  BigDecimal measured=c.measurement==null?null:ProductMeasure.normalize(c.measurement,unit(p));
  if(measured!=null&&c.scanId==null){if(c.quantity!=null&&c.quantity.compareTo(measured)!=0)throw new IllegalArgumentException("Quantité différente du net mesuré");c.quantity=measured;}
  if(c.scanId!=null){
   ProductScanReceipt scan=ProductScanReceipt.find("store=?1 and principal=?2 and scanId=?3",store,employee.badgeId,c.scanId).firstResult();if(scan==null)throw new IllegalArgumentException("Scan absent de cette session");
   try{
    var resolved=json.readTree(scan.result);
    if(!resolved.path("state").asText().equals("RESOLVED")||resolved.path("product").path("id").asLong()!=p.id)throw new IllegalArgumentException("Produit du scan non confirmé");
    if(!resolved.path("productRevision").asText().equals(view(store,p).get("revision")))throw new OperatorFailure(409,"STALE_PRODUCT","Le conditionnement ou la fiche a changé depuis le scan");
    BigDecimal available=resolved.path("quantity").isNull()||resolved.path("quantity").isMissingNode()?null:new BigDecimal(resolved.path("quantity").asText());
    if(measured!=null){if(available==null||measured.compareTo(available)!=0)throw new IllegalArgumentException("La mesure ne correspond pas au reçu physique");if(!resolved.has("measurement")||!CommandCanonical.hash(encode(c.measurement)).equals(CommandCanonical.hash(resolved.get("measurement").toString())))throw new IllegalArgumentException("La mesure a changé depuis sa vérification");}
    if(c.quantity==null)c.quantity=available;
    positive(c.quantity);
    if(available==null){if(scan.usedRequestKey!=null)throw new OperatorFailure(409,"DUPLICATE_PHYSICAL_SCAN","Ce scan a déjà servi à une confirmation");}
    else{BigDecimal consumed=scan.consumedQuantity==null?BigDecimal.ZERO:scan.consumedQuantity;if(consumed.add(c.quantity).compareTo(available)>0)throw new OperatorFailure(409,"DUPLICATE_PHYSICAL_SCAN","La quantité de ce scan est déjà confirmée ou insuffisante");scan.consumedQuantity=consumed.add(c.quantity);}
    scan.usedRequestKey=c.requestKey;
   }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  }
 }
}
