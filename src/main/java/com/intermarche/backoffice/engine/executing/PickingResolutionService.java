package com.intermarche.backoffice.engine.executing;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.wms.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.*;
@ApplicationScoped
public class PickingResolutionService {
 @jakarta.inject.Inject com.intermarche.backoffice.engine.semantic.ProductSemanticIndex semanticIndex;
 public Product replacement(Store store,Product requested,Long id){
  if(id==null || requested.id.equals(id))return requested;
  Product product=Product.findById(id);
  if(product==null || !product.active || ProductSubstitution.count("store=?1 and requested=?2 and replacement=?3 and active=true",store,requested,product)==0)throw new IllegalArgumentException("Cette substitution n’est pas autorisée pour ce produit dans ce magasin.");
  compatible(requested,product);return product;
 }
 public void compatible(Product source,Product target){if(source.id.equals(target.id) || source.productType!=target.productType || source.temperatureZone!=target.temperatureZone)throw new IllegalArgumentException("Choisissez un autre produit de même unité de vente et de même température.");}
 public void record(BaseHandlingOrder order,LotLine line,Employee employee,BigDecimal quantity,Product actual,String reason){
  if(quantity==null || quantity.signum()<=0 || quantity.stripTrailingZeros().scale()>3 || (line.product.productType==com.intermarche.backoffice.domain.order.ProductType.UNIT && quantity.stripTrailingZeros().scale()>0))throw new IllegalArgumentException("Quantité de manquant invalide.");
  if(reason==null || reason.isBlank() || reason.length()>300)throw new IllegalArgumentException("Indiquez le motif du manquant (300 caractères maximum).");
  if(actual!=null)replacement(order.store,line.product,actual.id);
  PickingShortage event=new PickingShortage();event.handlingOrder=order;event.lotLine=line;event.employee=employee;event.requestedProduct=line.product;event.replacementProduct=actual;event.quantity=quantity;event.reason=reason.trim();event.persist();
 }
 public BigDecimal unfulfilled(LotLine line){List<PickingShortage> rows=PickingShortage.list("lotLine=?1 and replacementProduct is null",line);return rows.stream().map(PickingShortage::effectiveQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);}
 public List<Map<String,Object>> options(Store store,Product source){return ProductSubstitution.<ProductSubstitution>list("store=?1 and requested=?2 and active=true order by id",store,source).stream().filter(r->r.replacement.active).map(r->product(r.replacement)).toList();}
 public Map<String,Object> product(Product p){var dto=new LinkedHashMap<String,Object>();dto.put("id",p.id);dto.put("ean",p.ean);dto.put("name",p.name);dto.put("productType",p.productType.name());dto.put("temperatureZone",p.temperatureZone.name());dto.put("brand",p.brand);dto.put("characteristics",p.characteristics);dto.put("unitName",p.unitName);dto.put("referenceWeight",p.referenceWeight);dto.put("referenceVolume",p.referenceVolume);return dto;}

 public Map<String,Object> similar(Product source,String search,int page){return similar(source,search,page,0.20);}
 public Map<String,Object> similar(Product source,String search,int page,double distance){
  var matches=semanticIndex.similar(source,search,distance,page);
  var ids=matches.hits().stream().map(com.intermarche.backoffice.engine.semantic.ProductSemanticIndex.Hit::id).toList();
  Map<Long,Product> products=new HashMap<>();if(!ids.isEmpty())for(Product candidate:Product.<Product>list("id in ?1 and active=true",ids))products.put(candidate.id,candidate);
  var items=new ArrayList<Map<String,Object>>();for(var hit:matches.hits()){var candidate=products.get(hit.id());if(candidate==null || candidate.productType!=source.productType || candidate.temperatureZone!=source.temperatureZone || !semanticIndex.current(candidate)){semanticIndex.invalidate();continue;}var dto=product(candidate);dto.put("distance",hit.distance());items.add(dto);}
  var response=new LinkedHashMap<String,Object>();response.put("items",items);response.put("hasMore",(page+1L)*40<matches.total());response.put("page",page);response.put("total",matches.total());response.put("maxDistance",distance);response.put("index",semanticIndex.status());response.put("state",matches.state());response.put("message",matches.error());return response;
 }
}
