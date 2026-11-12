package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.store.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.util.*;

/** Physical staging is independent of whether a packing operation follows picking. */
@ApplicationScoped @Transactional
public class BufferOccupancyService {
    @Inject EntityManager em;
    @Inject com.intermarche.backoffice.engine.stock.StockSupportRelocator stockPositions;
    @Inject com.intermarche.backoffice.engine.stock.InventoryScopeGuard freezes;
    public static class BufferZoneOccupancy {
        public String zone;
        public int occupied,capacity,remaining;
    }
    public static class BufferToteOccupancy {
        public Long id;
        public String code,zone,supportKind,supportLabel,supportState;
        public boolean full; public String replacementCode;
        public int productLines; public boolean nonEmpty;
        public Long physicalToteId; public Long cabinetId; public Integer cellRow,cellColumn;
    }
    public static class BufferOccupancy {
        public Long id,planNodeId,storeId;
        public List<CabinetOccupancy> cabinets=new ArrayList<>();
        public String code,description,buildingCode,buildingType,planNodeCode;
        /** Primary use of the buffer (STAGING, PROCESSING or RECEIPT), shown as the place it represents. */
        public String purpose;
        public Double planX,planY;
        public boolean active;
        public List<BufferZoneOccupancy> zones=new ArrayList<>();
        public List<BufferToteOccupancy> totes=new ArrayList<>();
    }
    public static class CabinetOccupancy { public Long id; public String code,zone,supportKind,supportLabel,supportState;
        public boolean full; public String replacementCode; public int columns,rows;
        public List<CabinetCellCode> cells=new ArrayList<>(); }
    public record CabinetCellCode(int row,int column,String code) {}
    public static List<CabinetCellCode> cellCodes(Long cabinetId,int rows,int columns){
        if(cabinetId==null||cabinetId<=0||rows<=0||columns<=0)throw new IllegalArgumentException("Armoire invalide");
        var result=new ArrayList<CabinetCellCode>();
        for(int row=1;row<=rows;row++)for(int column=1;column<=columns;column++)result.add(new CabinetCellCode(row,column,"BUFFER-CELL:"+cabinetId+":"+row+":"+column));
        return result;
    }
    public static boolean hasContents(LoadedTote tote){
        if(tote==null || tote.status!=LoadedTote.Status.IN_USE || com.intermarche.backoffice.domain.shipping.ShipmentLine.count("loadedTote=?1 and toteReturnedAt is null",tote)>0)return false;
        if(com.intermarche.backoffice.domain.executing.PickedItem.count("loadedTote=?1 and quantity>0 and status=?2",tote,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED)>0)return true;
        return PickingToteLocation.count("select count(loc) from PickingToteLocation loc where loc.protectedTote=?1 and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?2)>0",tote,com.intermarche.backoffice.domain.util.DateTimeProvider.now())>0 || BufferToteStockLocation.count("select count(loc) from BufferToteStockLocation loc where loc.loadedTote=?1 and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?2)>0",tote,com.intermarche.backoffice.domain.util.DateTimeProvider.now())>0;
    }
    public static Set<Long> nonEmptyIds(Collection<LoadedTote> loads){
        if(loads.isEmpty())return Set.of();var ids=loads.stream().map(t->t.id).toList();var em=LoadedTote.getEntityManager();var present=new HashSet<Long>();
        present.addAll(em.createQuery("select distinct p.loadedTote.id from PickedItem p where p.loadedTote.id in ?1 and p.quantity>0 and p.status=?2",Long.class).setParameter(1,ids).setParameter(2,com.intermarche.backoffice.domain.executing.PickedItem.PickStatus.PICKED).getResultList());
        for(String source:List.of("BufferToteStockLocation loc|loadedTote","PickingToteLocation loc|protectedTote")){String[] parts=source.split("\\|");present.addAll(em.createQuery("select distinct loc."+parts[1]+".id from "+parts[0]+" where loc."+parts[1]+".id in ?1 and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?2)>0",Long.class).setParameter(1,ids).setParameter(2,com.intermarche.backoffice.domain.util.DateTimeProvider.now()).getResultList());}
        present.removeAll(em.createQuery("select distinct s.loadedTote.id from ShipmentLine s where s.loadedTote.id in ?1 and s.toteReturnedAt is null",Long.class).setParameter(1,ids).getResultList());return present;
    }
    private TemperatureZone capacityZone(TemperatureZone zone) {
        return zone==TemperatureZone.HOUSEHOLD ? TemperatureZone.AMBIENT : zone;
    }
    public List<BufferOccupancy> occupancy(Store store) {
        List<Buffer> buffers=Buffer.find("select b from Buffer b where b.building.id in (select x.id from Store s join s.buildings x where s=?1) order by b.bufferCode",store).list();
        if(buffers.isEmpty())return List.of();
        List<LoadedTote> loaded=LoadedTote.find("select distinct t from LoadedTote t left join fetch t.items where t.status=?1 and t.buffer in ?2",LoadedTote.Status.IN_USE,buffers).list();
        var types=new HashMap<Long,com.intermarche.backoffice.domain.fieldops.PhysicalSupport>();
        if(!loaded.isEmpty())for(var support:com.intermarche.backoffice.domain.fieldops.PhysicalSupport.<com.intermarche.backoffice.domain.fieldops.PhysicalSupport>list("select s from PhysicalSupport s join fetch s.type where s.store=?1 and s.tote in ?2",store,loaded.stream().map(t->t.tote).toList()))types.put(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(support.tote),support);
        var fullSupports=new HashMap<Long,com.intermarche.backoffice.domain.fieldops.SupportReplacement>();
        if(!loaded.isEmpty())for(var replacement:com.intermarche.backoffice.domain.fieldops.SupportReplacement.<com.intermarche.backoffice.domain.fieldops.SupportReplacement>list("source in ?1",loaded))fullSupports.put(com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id(replacement.source),replacement);
        var nonEmpty=nonEmptyIds(loaded);
        var looseLines=new HashMap<Long,Integer>();
        if(!loaded.isEmpty())for(String kind:List.of("BufferToteStockLocation","PickingToteLocation")){
            String owner=kind.equals("PickingToteLocation")?"protectedTote":"loadedTote";
            for(var count:em.createQuery("select loc."+owner+".id,count(loc.id) from "+kind+" loc where loc."+owner+" in ?1"+(kind.equals("PickingToteLocation")?" and loc.traceOnly=false":"")+" and (select sum(m.quantity) from StockMovement m where m.location=loc and m.effectiveAt<=?2)>0 group by loc."+owner+".id",Object[].class).setParameter(1,loaded).setParameter(2,com.intermarche.backoffice.domain.util.DateTimeProvider.now()).getResultList())looseLines.merge(((Number)count[0]).longValue(),((Number)count[1]).intValue(),Integer::sum);
        }

        var result=new ArrayList<BufferOccupancy>();
        for(Buffer buffer:buffers){
            var row=new BufferOccupancy();row.id=buffer.id;row.storeId=org.hibernate.Hibernate.unproxy(store,Store.class).id;
            for(var c:BufferCabinet.<BufferCabinet>list("buffer=?1 order by code",buffer)){var d=new CabinetOccupancy();d.id=c.id;d.code=c.code;d.zone=c.zone.name();d.columns=c.columns;d.rows=c.rows;d.cells=cellCodes(c.id,c.rows,c.columns);row.cabinets.add(d);}
            row.code=buffer.bufferCode;row.description=buffer.locationDescription;row.purpose=buffer.purpose.name();row.buildingCode=buffer.building.buildingCode;row.buildingType=buffer.building.getBuildingType();row.active=buffer.active;PlanNode access=buffer.planNodeCode==null?null:PlanNode.findByCode(buffer.building,buffer.planNodeCode);if(access!=null){row.planNodeId=access.id;row.planNodeCode=access.code;row.planX=access.x;row.planY=access.y;}
            for(var tote:loaded)if(Objects.equals(org.hibernate.Hibernate.unproxy(tote.buffer,Buffer.class).id,buffer.id)){
                var t=new BufferToteOccupancy();t.id=tote.id;t.nonEmpty=nonEmpty.contains(tote.id);t.physicalToteId=org.hibernate.Hibernate.unproxy(tote.tote,Tote.class).id;t.cabinetId=tote.cabinet==null?null:org.hibernate.Hibernate.unproxy(tote.cabinet,BufferCabinet.class).id;t.cellRow=tote.cellRow;t.cellColumn=tote.cellColumn;t.code=tote.tote.code;t.zone=tote.storageType.name();t.productLines=tote.items.size()+looseLines.getOrDefault(tote.id,0);var support=types.get(t.physicalToteId);t.supportKind=support==null?"TOTE":support.type.kind.name();t.supportLabel=support==null?"Bac":support.type.label;t.supportState=support==null?"AVAILABLE":support.state;var full=fullSupports.get(t.id);t.full=full!=null;t.replacementCode=full==null||full.replacement==null?null:full.replacement.tote.code;row.totes.add(t);
            }
            row.totes.sort(Comparator.comparing(t->t.code));
            for(var zone:List.of(TemperatureZone.AMBIENT,TemperatureZone.CHILLED,TemperatureZone.FROZEN)){
                var z=new BufferZoneOccupancy();z.zone=zone.name();z.capacity=capacity(buffer,zone);
                z.occupied=(int)row.totes.stream().filter(t->capacityZone(TemperatureZone.valueOf(t.zone))==zone).count();z.remaining=Math.max(0,z.capacity-z.occupied);row.zones.add(z);
            }
            result.add(row);
        }
        return result;
    }
    private int capacity(Buffer b,TemperatureZone zone){
        var cabinets=BufferCabinet.<BufferCabinet>list("buffer=?1 and zone=?2",b,zone);
        return cabinets.isEmpty()?b.getCapacityForZone(zone):cabinets.stream().mapToInt(c->c.columns*c.rows).sum();
    }
    public void deposit(LoadedTote tote,Long bufferId,Store store) {
        deposit(tote,bufferId,null,null,null,store);
    }
    public void deposit(LoadedTote tote,Long bufferId,Long cabinetId,Integer cellRow,Integer cellColumn,Store store) {
        if(tote.loadingDeparture!=null)throw new com.intermarche.backoffice.engine.operator.OperatorFailure(409,"TOTE_LOADED","Ce bac est chargé pour un départ. Utilisez le déchargement du départ avant de le déplacer.");
        if(bufferId==null)throw new IllegalArgumentException("Choisissez le buffer de dépôt de chaque bac.");
        Buffer buffer=em.find(Buffer.class,bufferId,LockModeType.PESSIMISTIC_WRITE);
        if(buffer==null)throw new IllegalArgumentException("Buffer de dépôt introuvable.");
        Store owner=Building.findStoreByBuildingId(org.hibernate.Hibernate.unproxy(buffer.building,Building.class).id);
        if(owner==null || !owner.id.equals(store.id) || !org.hibernate.Hibernate.unproxy(tote.tote.store,Store.class).id.equals(store.id))
            throw new IllegalArgumentException("Le bac et le buffer doivent appartenir au magasin de la collecte.");
        if(!buffer.active)throw new IllegalArgumentException("Ce buffer est désactivé.");
        if(tote.storageType==null)throw new IllegalArgumentException("La température du bac est requise.");
        for(var item:tote.items){
            var product=org.hibernate.Hibernate.unproxy(item.product,com.intermarche.backoffice.domain.order.Product.class);
            if(product==null || product.temperatureZone!=tote.storageType || !tote.tote.isUsageAllowed(product.temperatureZone))
                throw new IllegalArgumentException("La température du bac ne correspond pas à ses produits.");
        }
        TemperatureZone zone=capacityZone(tote.storageType);
        long occupied=LoadedTote.count("buffer=?1 and status=?2 and storageType in ?3 and id<>?4",buffer,LoadedTote.Status.IN_USE,
            zone==TemperatureZone.AMBIENT?List.of(TemperatureZone.AMBIENT,TemperatureZone.HOUSEHOLD):List.of(zone),tote.id);
        if(occupied>=capacity(buffer,zone))throw new IllegalArgumentException("Buffer plein pour la zone "+zone+". Choisissez un autre dépôt.");
        BufferCabinet cabinet=null;
        if(cabinetId!=null){
            com.intermarche.backoffice.domain.fieldops.PhysicalSupport support=com.intermarche.backoffice.domain.fieldops.PhysicalSupport.find("tote",tote.tote).firstResult();if(support!=null&&Set.of(com.intermarche.backoffice.domain.fieldops.SupportType.Kind.PALLET,com.intermarche.backoffice.domain.fieldops.SupportType.Kind.CART).contains(support.type.kind))throw new IllegalArgumentException("Palette ou chariot : choisissez le buffer sans cellule d’armoire");
            cabinet=BufferCabinet.findById(cabinetId);
            if(cabinet==null || !org.hibernate.Hibernate.unproxy(cabinet.buffer,Buffer.class).id.equals(buffer.id))throw new IllegalArgumentException("Armoire absente de ce buffer.");
            if(cellRow==null || cellColumn==null || cellRow<1 || cellRow>cabinet.rows || cellColumn<1 || cellColumn>cabinet.columns)throw new IllegalArgumentException("Cellule invalide.");
            if(cabinet.zone!=zone)throw new IllegalArgumentException("Température incompatible avec cette armoire.");
            if(LoadedTote.count("buffer=?1 and cabinet=?2 and cellRow=?3 and cellColumn=?4 and status=?5 and id<>?6",buffer,cabinet,cellRow,cellColumn,LoadedTote.Status.IN_USE,tote.id)>0)throw new IllegalArgumentException("Cette cellule est déjà occupée.");
        }else if(cellRow!=null || cellColumn!=null)throw new IllegalArgumentException("Choisissez une armoire pour cette cellule.");
        freezes.tote(store,tote);freezes.cell(store,cabinet,cellRow,cellColumn);
        tote.clearCell();tote.cabinet=cabinet;if(cabinet!=null){tote.cellRow=cellRow;tote.cellColumn=cellColumn;}
        tote.buffer=buffer;
        stockPositions.afterDeposit(tote,store);
        em.flush(); // The next tote in the batch must see this reservation.
    }
}
