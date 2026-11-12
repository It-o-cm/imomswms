package com.intermarche.backoffice.auth;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.executing.LoadedTote;
import com.intermarche.backoffice.domain.util.DateTimeProvider;
import com.intermarche.backoffice.engine.stock.TemporalStockService;
import com.intermarche.backoffice.engine.executing.BufferOccupancyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
@ApplicationScoped
public class DemoBufferInitializer {
 @Inject TemporalStockService stocks;@Inject BufferOccupancyService occupancy;
 @Transactional public void seed(Store store){
  Buffer buffer=Buffer.find("select b from Buffer b where b.building.id in (select x.id from Store s join s.buildings x where s=?1) and b.active=true order by b.bufferCode",store).firstResult();if(buffer==null)return;
  int index=0;for(var zone:new TemperatureZone[]{TemperatureZone.AMBIENT,TemperatureZone.CHILLED,TemperatureZone.FROZEN}){
   index++;String code="DEMO-BAC-"+zone; if(Tote.count("code",code)>0)continue;
   Product product=Product.find("temperatureZone=?1 and ean<>?2 order by id",zone,"3017620422003").firstResult();if(product==null)continue;
   BufferCabinet cabinet=new BufferCabinet();cabinet.buffer=buffer;cabinet.code="DEMO-"+zone;cabinet.zone=zone;cabinet.columns=zone==TemperatureZone.AMBIENT?4:3;cabinet.rows=zone==TemperatureZone.CHILLED?2:3;
   BufferCabinet existing=BufferCabinet.find("buffer=?1 and code=?2",buffer,cabinet.code).firstResult();if(existing==null)cabinet.persistAndFlush();else cabinet=existing;
   Tote tote=new Tote();tote.code=code;tote.store=store;tote.volume=new BigDecimal("60");tote.toteType=zone==TemperatureZone.AMBIENT?Tote.ToteType.STANDARD:Tote.ToteType.INSULATED;tote.persist();
   LoadedTote loaded=LoadedTote.useAnEmptyTote(tote);loaded.storageType=zone;loaded.persistAndFlush();
   BufferToteStockLocation location=new BufferToteStockLocation();location.product=product;location.loadedTote=loaded;location.buffer=buffer;location.area="BUFFER";location.aisle="DEMO";location.unit="DEMO"+index;location.elevation="1";location.position="1";location.persistAndFlush();buffer.building.locations.add(location);
   var account=stocks.account(store.id,product.id);stocks.adjust(account.id,location.id,"demo-buffer-"+zone,new BigDecimal(index+2),DateTimeProvider.now());
   occupancy.deposit(loaded,buffer.id,cabinet.id,1,1,store);
  }
  seedAwaitingPlacement(store,buffer.building);

 }
 private void seedAwaitingPlacement(Store store,Building origin){
  for(var zone:new TemperatureZone[]{TemperatureZone.AMBIENT,TemperatureZone.CHILLED,TemperatureZone.FROZEN})for(int number=1;number<=2;number++){
   String code="AFF-"+zone+"-"+number;if(Tote.count("code",code)>0)continue;
   Tote previous=Tote.find("code","AFFECTER-"+zone+"-"+number).firstResult();if(previous!=null && org.hibernate.Hibernate.unproxy(previous.store,Store.class).id.equals(store.id)){previous.code=code;continue;}
   Product product=Product.find("temperatureZone=?1 and ean<>?2 order by id",zone,"3017620422003").firstResult();if(product==null)continue;
   Tote physical=new Tote();physical.code=code;physical.store=store;physical.toteType=zone==TemperatureZone.AMBIENT?Tote.ToteType.STANDARD:Tote.ToteType.INSULATED;physical.volume=new BigDecimal("60");physical.persist();
   LoadedTote loaded=LoadedTote.useAnEmptyTote(physical);loaded.storageType=zone;loaded.persistAndFlush();
   PickingToteLocation location=new PickingToteLocation();location.product=product;location.protectedTote=loaded;location.area="DEPOT";location.aisle="ATTENTE";location.unit="ATT"+zone.ordinal()+number;location.elevation="1";location.position="1";location.locationCode="BAC-"+code;location.persistAndFlush();origin.locations.add(location);
   var account=stocks.account(store.id,product.id);stocks.adjust(account.id,location.id,"demo-awaiting-"+code,BigDecimal.valueOf(number*3L),DateTimeProvider.now());
  }
 }

}
