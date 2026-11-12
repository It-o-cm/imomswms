package com.intermarche.backoffice.engine.fieldops;

import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.stock.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.time.LocalDate;
import java.util.*;

/** Manufacturer identity is independent from preparation lots. Unknown legacy identity stays null. */
@ApplicationScoped @Transactional
public class TraceabilityService {
    @Inject FieldOperationsService field;@Inject OperationalPolicyVersions policyVersions;
    @org.eclipse.microprofile.openapi.annotations.media.Schema(name="ProductTraceabilityInput")
    public static class Input {
        public Long storeId,productId,lotId;
        public String code,context,claimKey,expectedRevision,allocationMethod,hazardClass,separationGroup,incompatibleHazards;
        public Integer minimumRemainingDays;
        public Boolean bestBeforeStrict;
        public LocalDate manufacturedOn,bestBefore,useBy;
        public boolean lotRequired,serialRequired,dateRequired;
    }
    private Product product(Long product){Product p=Product.findById(product);if(p==null)throw new NotFoundException("Produit inconnu");return p;}
    private String revision(com.intermarche.backoffice.domain.BaseEntity e){return field.hash(Arrays.asList(e.id,e.version));}
    private Map<String,Object> lotView(ManufacturerLot lot){var v=new LinkedHashMap<String,Object>();v.put("id",lot.id);v.put("code",lot.code);v.put("productId",id(lot.product));v.put("manufacturedOn",lot.manufacturedOn);v.put("bestBefore",lot.bestBefore);v.put("useBy",lot.useBy);v.put("revision",revision(lot));return v;}
    public Object compatibility(Long storeId,Long productId){Store store=field.store(storeId,false);Product product=product(productId);var result=new LinkedHashMap<String,String>();
     for(var item:com.intermarche.backoffice.domain.executing.PickedItem.<com.intermarche.backoffice.domain.executing.PickedItem>list("select i from PickedItem i join fetch i.loadedTote t join fetch t.tote where t.tote.store=?1 and t.status=?2 and i.status=?3 and i.quantity>0",store,com.intermarche.backoffice.domain.executing.LoadedTote.Status.IN_USE,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED)){
      String code=item.loadedTote.tote.code;if(result.containsKey(code))continue;try{com.intermarche.backoffice.engine.stock.ProductHandlingRules.requireCompatible(store,product,item.product);}catch(OperatorFailure denied){result.put(code,((Map<?,?>)denied.getResponse().getEntity()).get("message").toString());}
     }return result;
    }
    public Object lots(Long storeId,Long productId){Store s=field.store(storeId,false);Product p=product(productId);return ManufacturerLot.<ManufacturerLot>find("store=?1 and product=?2 order by id desc",s,p).range(0,99).list().stream().map(this::lotView).toList();}
    public Object lotByCode(Long storeId,Long productId,String code){Store s=field.store(storeId,false);Product p=product(productId);FieldOperationsService.text(code,100);ManufacturerLot lot=ManufacturerLot.find("store=?1 and product=?2 and code=?3",s,p,code.trim()).firstResult();return lot==null?Map.of("exists",false):Map.of("exists",true,"lot",lotView(lot));}
    public Object serialByCode(Long storeId,Long productId,String code){Store s=field.store(storeId,false);Product p=product(productId);FieldOperationsService.text(code,100);ProductSerial serial=ProductSerial.find("store=?1 and product=?2 and code=?3",s,p,code.trim()).firstResult();if(serial==null)return Map.of("exists",false);var view=new LinkedHashMap<String,Object>();view.put("id",serial.id);view.put("code",serial.code);view.put("lotId",serial.lot==null?null:id(serial.lot));return Map.of("exists",true,"serial",view);}
    public Object saveLot(Input r){if(r==null)throw new BadRequestException();Store s=field.store(r.storeId,true);Product p=product(r.productId);FieldOperationsService.text(r.code,100);
        if(r.manufacturedOn!=null&&(r.bestBefore!=null&&r.manufacturedOn.isAfter(r.bestBefore)||r.useBy!=null&&r.manufacturedOn.isAfter(r.useBy)))throw new BadRequestException("La date de fabrication dépasse la date limite");
        ManufacturerLot lot=ManufacturerLot.find("store=?1 and product=?2 and code=?3",s,p,r.code.trim()).firstResult();
        if(lot!=null){if(!Objects.equals(lot.manufacturedOn,r.manufacturedOn)||!Objects.equals(lot.bestBefore,r.bestBefore)||!Objects.equals(lot.useBy,r.useBy))throw new OperatorFailure(409,"LOT_IDENTITY_CONFLICT","Ce lot existe avec d’autres dates : faites vérifier son identité");return lotView(lot);}
        lot=new ManufacturerLot();lot.store=s;lot.product=p;lot.code=r.code.trim();lot.manufacturedOn=r.manufacturedOn;lot.bestBefore=r.bestBefore;lot.useBy=r.useBy;lot.persistAndFlush();return lotView(lot);
    }
    public Object claimSerial(Input r){if(r==null)throw new BadRequestException();Store s=field.store(r.storeId,true);Product p=product(r.productId);FieldOperationsService.text(r.code,100);FieldOperationsService.text(r.claimKey,160);
        ManufacturerLot lot=r.lotId==null?null:ManufacturerLot.find("id=?1 and store=?2 and product=?3",r.lotId,s,p).firstResult();if(r.lotId!=null&&lot==null)throw new BadRequestException("Lot d’un autre produit ou magasin");
        ProductSerial serial=ProductSerial.find("store=?1 and product=?2 and code=?3",s,p,r.code.trim()).firstResult();
        if(serial!=null){if(!serial.claimKey.equals(r.claimKey)||!Objects.equals(serial.lot==null?null:id(serial.lot),r.lotId))throw new OperatorFailure(409,"SERIAL_ALREADY_ASSIGNED","Ce numéro de série a déjà été attribué");}
        else{serial=new ProductSerial();serial.store=s;serial.product=p;serial.code=r.code.trim();serial.claimKey=r.claimKey;serial.lot=lot;serial.persistAndFlush();}
        return Map.of("id",serial.id,"code",serial.code,"claimKey",serial.claimKey);
    }
    private Map<String,Object> policyView(TraceabilityPolicy policy){var v=new LinkedHashMap<String,Object>();v.put("context",policy.context);v.put("lotRequired",policy.lotRequired);v.put("serialRequired",policy.serialRequired);v.put("dateRequired",policy.dateRequired);v.put("allocationMethod",policy.allocationMethod);v.put("minimumRemainingDays",policy.minimumRemainingDays);v.put("bestBeforeStrict",policy.bestBeforeStrict);v.put("hazardClass",policy.hazardClass);v.put("separationGroup",policy.separationGroup);v.put("incompatibleHazards",policy.incompatibleHazards);v.put("revision",revision(policy));return v;}
    public Object policies(Long storeId,Long productId){Store s=field.store(storeId,false);return TraceabilityPolicy.<TraceabilityPolicy>list("store=?1 and product=?2 order by context",s,product(productId)).stream().map(this::policyView).toList();}
    public Object savePolicy(Input r){if(r==null||!Set.of("RECEIPT","PICKING","SHIPPING","RETURN").contains(r.context==null?"":r.context))throw new BadRequestException("Contexte de traçabilité requis");Store s=field.store(r.storeId,true);field.requireManager();Product p=product(r.productId);
        TraceabilityPolicy policy=TraceabilityPolicy.find("store=?1 and product=?2 and context=?3",s,p,r.context).firstResult();if(policy==null){policy=new TraceabilityPolicy();policy.store=s;policy.product=p;policy.context=r.context;}else if(!Objects.equals(r.expectedRevision,revision(policy)))throw new OperatorFailure(409,"STALE_TRACE_POLICY","La politique a changé. Actualisez.");
        if(r.serialRequired&&p.productType!=com.intermarche.backoffice.domain.order.ProductType.UNIT)throw new BadRequestException("Les séries exigent un produit géré à l’unité");
        if(r.minimumRemainingDays!=null&&(r.minimumRemainingDays<0||r.minimumRemainingDays>3650))throw new BadRequestException("Durée minimale entre 0 et 3650 jours");
        if(r.allocationMethod!=null&&!Set.of("FIFO","FEFO").contains(r.allocationMethod))throw new BadRequestException("Allocation FIFO ou FEFO");
        if(r.minimumRemainingDays!=null)policy.minimumRemainingDays=r.minimumRemainingDays;if(r.allocationMethod!=null)policy.allocationMethod=r.allocationMethod;if(r.bestBeforeStrict!=null)policy.bestBeforeStrict=r.bestBeforeStrict;
        if(r.hazardClass!=null)policy.hazardClass=handlingToken(r.hazardClass,60);if(r.separationGroup!=null)policy.separationGroup=handlingToken(r.separationGroup,60);if(r.incompatibleHazards!=null)policy.incompatibleHazards=handlingToken(r.incompatibleHazards,600);
        policy.lotRequired=r.lotRequired;policy.serialRequired=r.serialRequired;policy.dateRequired=r.dateRequired;policy.persistAndFlush();var values=new LinkedHashMap<String,Object>(policyView(policy));values.put("productId",p.id);policyVersions.record(s,"TraceabilityConstraints",values,"Modification de la politique produit");return policyView(policy);
    }
    private static String handlingToken(String value,int limit){String v=value.trim().toUpperCase(java.util.Locale.ROOT);if(v.length()>limit||!v.matches("[A-Z0-9_, -]*"))throw new BadRequestException("Classe ou groupe : lettres, chiffres, tirets et virgules uniquement");return v;}
    public static void validate(Store store,Product product,String context,ManufacturerLot lot,ProductSerial serial){
        if(lot!=null&&(!id(lot.store).equals(store.id)||!id(lot.product).equals(product.id))||serial!=null&&(!id(serial.store).equals(store.id)||!id(serial.product).equals(product.id)||!Objects.equals(serial.lot==null?null:id(serial.lot),lot==null?null:lot.id)))throw new BadRequestException("Identité de traçabilité incompatible");
        TraceabilityPolicy policy=TraceabilityPolicy.find("store=?1 and product=?2 and context=?3",store,product,context).firstResult();if(policy==null)return;
        if(policy.lotRequired&&lot==null||policy.serialRequired&&serial==null||policy.dateRequired&&(lot==null||lot.useBy==null&&lot.bestBefore==null))throw new BadRequestException("Lot, série ou date requis par la politique du produit");
    }
}
