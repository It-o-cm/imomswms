package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.replenishment.ReplenishmentDefinition;
import com.intermarche.backoffice.engine.replenishment.ReplenishmentDefinitionResource;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.*;

/** One accepted field demand creates one existing replenishment definition; allocation stays in its existing editor. */
@ApplicationScoped @Transactional
public class FieldReplenishmentService {
 @Inject FieldOperationsService field;@Inject ReplenishmentDefinitionResource definitions;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="FieldDemandAcceptance") public static class Accept {public String expectedRevision;public ReplenishmentDefinitionResource.DefinitionRequest definition;}
 public Object accept(Long incidentId,Accept r){
  field.requireManager();if(r==null||r.definition==null)throw new BadRequestException();var request=r.definition;var s=field.store(request.storeId,true);var i=field.incident(s,incidentId);if(!i.kind.equals("REPLENISHMENT")||i.product==null)throw new BadRequestException("Ce dossier n’est pas une demande de réapprovisionnement");
  if(request.lines==null||request.lines.size()!=1||!Objects.equals(request.lines.getFirst().productId,id(i.product)))throw new BadRequestException("Le réapprovisionnement doit concerner le produit demandé");
  request.requestKey="field-demand-"+i.id;String fingerprint=field.hash(request);
  var previous=IncidentHistory.<IncidentHistory>find("incident=?1 and action='REPLENISHMENT_DEFINED'",i).firstResult();
  if(previous!=null){if(!previous.fingerprint.equals(fingerprint))throw new OperatorFailure(409,"DEMAND_ALREADY_ACCEPTED","Cette demande est déjà liée à un réapprovisionnement différent");return field.detail(s,i);}
  field.expected(i,r.expectedRevision);if(Set.of("RESOLVED","REJECTED").contains(i.state))throw new BadRequestException("Rouvrez le dossier avant de traiter cette demande");
  var created=definitions.create(request);i.replenishmentId=((Number)created.get("id")).longValue();i.owner=field.actor();i.state="OWNED";field.history(i,request.requestKey,fingerprint,"REPLENISHMENT_DEFINED","Réapprovisionnement "+created.get("code")+" défini ; affectation des emplacements dans son éditeur de manutention");field.notify(i,request.requestKey,"Demande acceptée : réapprovisionnement "+created.get("code"));field.flush();return field.detail(s,i);
 }
}
