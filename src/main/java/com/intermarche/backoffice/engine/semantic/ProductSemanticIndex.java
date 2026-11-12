package com.intermarche.backoffice.engine.semantic;

import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Incremental background inference. Searches only scan cached vectors, never run a remote model. */
@ApplicationScoped
public class ProductSemanticIndex {
 @Inject LocalEmbeddingModel model;
 @ConfigProperty(name="imomswms.embeddings.enabled",defaultValue="true") boolean enabled;
 private final AtomicBoolean running=new AtomicBoolean();
 private final Map<Long,Entry> index=new ConcurrentHashMap<>();
 private volatile String state="PENDING",error="",version="";
 private volatile int total,missing;
 private volatile long lastScan;
 private boolean restored;
 private volatile boolean stopping;
 @jakarta.annotation.PreDestroy void stop(){stopping=true;}
 private record Document(Long id,String name,String brand,String ean,String text,ProductType type,TemperatureZone zone){}
 private record Entry(Document product,String hash,float[] vector){}
 public record Hit(Long id,double distance){}
 public record Matches(List<Hit> hits,int total,String state,String error,int indexed,int catalogSize,int missingCharacteristics){}
 public Map<String,Object> status(){return Map.of("state",state,"message",error,"indexed",index.size(),"total",total,"missingCharacteristics",missing,"model","intfloat/multilingual-e5-small","modelVersion",version);}
 public static String text(String value){return value==null?"":value.replaceAll("\\s+"," ").trim();}
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 @Scheduled(every="5s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
 void scheduled(){if(System.currentTimeMillis()-lastScan>30000)refreshNow();}
 public void invalidate(){lastScan=0;}
 public void refreshNow(){
  if(!enabled){state="DISABLED";error="Embeddings locaux désactivés sur ce serveur.";return;}
  if(!running.compareAndSet(false,true))return;
  try{
   state="INDEXING";error="";version=model.version();
   List<Document> products=QuarkusTransaction.requiringNew().call(()->Product.getEntityManager().createQuery("select p.id,p.name,p.brand,p.ean,p.characteristics,p.productType,p.temperatureZone from Product p where p.active=true order by p.id",Object[].class).getResultList().stream().map(row->new Document((Long)row[0],(String)row[1],(String)row[2],(String)row[3],text((String)row[4]),(ProductType)row[5],(TemperatureZone)row[6])).toList());
   total=products.size();missing=(int)products.stream().filter(p->p.text.isBlank()).count();
   var documentsById=new HashMap<Long,Document>();for(var document:products)documentsById.put(document.id,document);
   if(!restored){QuarkusTransaction.requiringNew().run(()->{for(ProductEmbedding row:ProductEmbedding.<ProductEmbedding>list("modelVersion",version)){var document=documentsById.get(row.product.id);if(document!=null && row.textHash.equals(hash(document.text)))index.put(document.id,new Entry(document,row.textHash,decode(row.vector)));}});restored=true;}
   Set<Long> active=new HashSet<>();for(var product:products)active.add(product.id);index.keySet().removeIf(id->!active.contains(id));
   var vectorsByHash=new HashMap<String,float[]>();for(var existing:index.values())vectorsByHash.put(existing.hash,existing.vector);
   for(var product:products){
    if(stopping || Thread.currentThread().isInterrupted())return;
    if(product.text.isBlank()){index.remove(product.id);continue;}
    String hash=hash(product.text);Entry existing=index.get(product.id);
    if(existing!=null && existing.hash.equals(hash)){index.put(product.id,new Entry(product,hash,existing.vector));continue;}
    float[] vector=vectorsByHash.computeIfAbsent(hash,ignored->model.embed(product.text));
    boolean saved=QuarkusTransaction.requiringNew().call(()->{Product current=Product.findById(product.id);if(current==null || !current.active || !hash.equals(hash(current.characteristics)))return false;ProductEmbedding row=ProductEmbedding.find("product",current).firstResult();if(row==null){row=new ProductEmbedding();row.product=current;}row.modelVersion=version;row.textHash=hash;row.vector=encode(vector);row.persist();return true;});
    if(saved)index.put(product.id,new Entry(product,hash,vector));
   }
   state="READY";
  }catch(Exception|LinkageError failure){state="ERROR";error=failure.getMessage()==null?"Index sémantique indisponible.":failure.getMessage();Logger.getLogger(ProductSemanticIndex.class).warn("Local embedding index unavailable",failure);}
  finally{lastScan=System.currentTimeMillis();running.set(false);}
 }
 public boolean current(Product product){Entry entry=index.get(product.id);return entry!=null && entry.hash.equals(hash(product.characteristics));}
 public Matches similar(Product source,String query,double maxDistance,int page){
  if(!Double.isFinite(maxDistance)||maxDistance<0||maxDistance>2||page<0||page>10000)throw new IllegalArgumentException("Distance (0–2) ou page invalide.");
  if(query!=null && query.length()>100)throw new IllegalArgumentException("Recherche trop longue.");
  String characteristics=text(source.characteristics);
  if(characteristics.isEmpty())return new Matches(List.of(),0,"MISSING_CHARACTERISTICS","Renseignez les caractéristiques de ce produit pour calculer sa proximité sémantique.",index.size(),total,missing);
  Entry origin=index.get(source.id);
  if(origin==null || !origin.hash.equals(hash(characteristics))){invalidate();return new Matches(List.of(),0,"ERROR".equals(state)||"DISABLED".equals(state)?state:"INDEXING",error,index.size(),total,missing);}
  String search=query==null?"":query.trim().toLowerCase(Locale.ROOT);
  var hits=index.values().stream().filter(e->!e.product.id.equals(source.id)&&e.product.type==source.productType&&e.product.zone==source.temperatureZone)
   .filter(e->search.isEmpty() || (e.product.name+" "+Objects.toString(e.product.brand,"")+" "+e.product.ean).toLowerCase(Locale.ROOT).contains(search))
   .map(e->new Hit(e.product.id,LocalEmbeddingModel.distance(origin.vector,e.vector))).filter(h->h.distance<=maxDistance)
   .sorted(Comparator.comparingDouble(Hit::distance).thenComparing(Hit::id)).toList();
  return new Matches(hits.stream().skip(page*40L).limit(40).toList(),hits.size(),state,error,index.size(),total,missing);
 }
 private static byte[] encode(float[] vector){ByteBuffer buffer=ByteBuffer.allocate(vector.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float value:vector)buffer.putFloat(value);return buffer.array();}
 private static float[] decode(byte[] bytes){var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();float[] vector=new float[buffer.remaining()];buffer.get(vector);return vector;}
}
