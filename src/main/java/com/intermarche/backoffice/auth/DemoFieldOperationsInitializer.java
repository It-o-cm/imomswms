package com.intermarche.backoffice.auth;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.wms.BaseHandlingOrder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
/** Explicit demo cases, with no inferred shortage and no stock adjustment. */
@ApplicationScoped
public class DemoFieldOperationsInitializer {
 @Transactional public void seed(Store store){
  var picker=Employee.findByBadgeId("100235");if(picker==null||picker.store==null||!picker.store.id.equals(store.id))return;
  for(String kind:java.util.List.of("DAMAGED","REPLENISHMENT")){
   String key="DEMO-FIELD-"+kind;if(FieldIncident.count("store=?1 and reporter=?2 and requestKey=?3",store,picker.badgeId,key)>0)continue;
   Product product=Product.findByEan(kind.equals("DAMAGED")?"3245412632006":"3017620422003");if(product==null)continue;
   var incident=new FieldIncident();incident.store=store;incident.reporter=picker.badgeId;incident.requestKey=key;incident.fingerprint="DEMO";incident.kind=kind;incident.title=kind.equals("DAMAGED")?"Démo · emballage à vérifier":"Démo · besoin de réapprovisionnement";incident.comment=kind.equals("DAMAGED")?"Scénario de démonstration : contrôler un emballage abîmé et décider de la suite sans corriger automatiquement le stock.":"Scénario de démonstration : le préparateur demande douze pièces supplémentaires. Le responsable choisit le flux et la période avant de définir la manutention.";incident.product=product;incident.handlingOrder=BaseHandlingOrder.find("store=?1 and code='PRO-DEMO-OPT'",store).firstResult();incident.observedAddress="Magasin · emplacement du produit";if(kind.equals("REPLENISHMENT"))incident.requestedQuantity=new BigDecimal("12");incident.persist();
   var history=new IncidentHistory();history.incident=incident;history.eventKey=key;history.fingerprint="DEMO";history.actor="DEMO";history.action="REPORTED";history.detail=incident.comment;history.persist();
  }
 }
}
