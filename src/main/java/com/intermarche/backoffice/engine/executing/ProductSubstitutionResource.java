package com.intermarche.backoffice.engine.executing;
import com.intermarche.backoffice.domain.executing.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;
@Path("/product-substitutions") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER","PICKER"}) @Transactional
public class ProductSubstitutionResource implements GraphQLTrait {
 @Inject SecurityIdentity identity;@Inject PickingResolutionService service;
 public SecurityIdentity getIdentity(){return identity;}
 Store store(String code){Store s=Store.find("code",code).firstResult();if(s==null)throw new NotFoundException("Magasin introuvable.");if(!identity.hasRole("ADMIN") && !getStoreContext().id.equals(s.id))throw new ForbiddenException("Magasin hors périmètre.");return s;}
 Product source(String ean){Product p=Product.find("ean",ean).firstResult();if(p==null)throw new NotFoundException("Produit introuvable.");return p;}
 @GET @Path("/search") public Object search(@QueryParam("storeCode") String code,@QueryParam("q") String q){store(code);if(q==null || q.trim().length()<2)return List.of();if(q.length()>100)throw new BadRequestException("Recherche trop longue.");String term=q.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");return Product.<Product>find("active=true and (lower(name) like ?1 escape '!' or ean like ?1 escape '!') order by name","%"+term+"%").page(0,30).list().stream().map(service::product).toList();}
 @GET @Path("/{ean}/similar") public Object similar(@PathParam("ean") String ean,@QueryParam("storeCode") String code,@QueryParam("q") String q,@QueryParam("page") @DefaultValue("0") int page,@QueryParam("distance") @DefaultValue("0.20") double distance){store(code);try{return service.similar(source(ean),q,page,distance);}catch(IllegalArgumentException e){throw new BadRequestException(e.getMessage());}}
 @GET @Path("/{ean}") public Object get(@PathParam("ean") String ean,@QueryParam("storeCode") String code){return service.options(store(code),source(ean));}
 public static class Rules {public String storeCode;public List<Long> productIds;}
 @PUT @Path("/{ean}") @RolesAllowed({"ADMIN","MANAGER"}) public Object set(@PathParam("ean") String ean,Rules input){
  if(input==null || input.productIds==null || input.productIds.size()>100)throw new BadRequestException("Liste de substitutions invalide.");Store store=store(input.storeCode);Product source=source(ean);
  var em=Product.getEntityManager();em.lock(org.hibernate.Hibernate.unproxy(store,Store.class),jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
  Set<Long> ids=new LinkedHashSet<>(input.productIds);
  for(Long id:ids){if(id==null)throw new BadRequestException("Produit de substitution requis.");Product target=Product.findById(id);if(target==null || !target.active)throw new BadRequestException("Produit de substitution indisponible.");try{service.compatible(source,target);}catch(IllegalArgumentException e){throw new BadRequestException(e.getMessage());}}
  for(var rule:ProductSubstitution.<ProductSubstitution>list("store=?1 and requested=?2",store,source))rule.active=ids.contains(rule.replacement.id);
  for(Long id:ids){if(id==null)throw new BadRequestException("Produit de substitution requis.");Product target=Product.findById(id);if(ProductSubstitution.count("store=?1 and requested=?2 and replacement=?3",store,source,target)==0){var rule=new ProductSubstitution();rule.store=store;rule.requested=source;rule.replacement=target;rule.persist();}}
  return service.options(store,source);
 }
}
