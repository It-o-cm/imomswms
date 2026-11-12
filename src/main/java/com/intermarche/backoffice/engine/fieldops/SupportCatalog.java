package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.*;
/** Adapts legacy tote creation/import without changing physical identity or any contents. */
@ApplicationScoped @Transactional
public class SupportCatalog {
 private void lock(Store store){Tote.getEntityManager().createNativeQuery("select id from stores where id=?1 for update").setParameter(1,id(store)).getSingleResult();}
 private Map<String,SupportType> types(Store store){var result=new HashMap<String,SupportType>();for(var t:SupportType.<SupportType>list("store=?1 and code in ?2",store,List.of("TOTE_STANDARD","TOTE_INSULATED")))result.put(t.code,t);for(var code:List.of("TOTE_STANDARD","TOTE_INSULATED"))if(!result.containsKey(code)){var t=new SupportType();t.store=store;t.code=code;t.kind=SupportType.Kind.TOTE;t.label=code.equals("TOTE_STANDARD")?"Bac standard":"Bac isotherme";t.allowedZones=code.equals("TOTE_STANDARD")?"AMBIENT,HOUSEHOLD":"CHILLED,FROZEN";t.persist();result.put(code,t);}return result;}
 private PhysicalSupport create(Tote tote,Map<String,SupportType> types){var support=new PhysicalSupport();support.store=tote.store;support.tote=tote;support.code=tote.code;support.type=types.get(tote.toteType==Tote.ToteType.INSULATED?"TOTE_INSULATED":"TOTE_STANDARD");support.persist();return support;}
 public void register(Tote tote){lock(tote.store);PhysicalSupport support=PhysicalSupport.find("tote",tote).firstResult();if(support==null){create(tote,types(tote.store));return;}if(!id(support.store).equals(id(tote.store)))throw new IllegalArgumentException("Un support existant ne peut pas changer de magasin");for(var load:LoadedTote.<LoadedTote>list("tote=?1 and status=?2",tote,LoadedTote.Status.IN_USE))if(!tote.isUsageAllowed(load.storageType))throw new IllegalArgumentException("Le support contient une session de conditionnement incompatible");if(support.type.kind==SupportType.Kind.TOTE&&Set.of("TOTE_STANDARD","TOTE_INSULATED").contains(support.type.code))support.type=types(tote.store).get(tote.toteType==Tote.ToteType.INSULATED?"TOTE_INSULATED":"TOTE_STANDARD");else{if(support.type.maxVolumeLiters!=null&&tote.volume.compareTo(support.type.maxVolumeLiters)>0)throw new IllegalArgumentException("Volume supérieur à la capacité du type de support");for(var zone:support.type.allowedZones.split(","))if(!tote.isUsageAllowed(TemperatureZone.valueOf(zone)))throw new IllegalArgumentException("Conditionnement incompatible avec le type de support");}support.code=tote.code;}
 public void registerAll(Store store){lock(store);var missing=Tote.<Tote>list("select t from Tote t where t.store=?1 and not exists (select s.id from PhysicalSupport s where s.tote=t)",store);if(missing.isEmpty())return;var types=types(store);for(var tote:missing)create(tote,types);}
}
