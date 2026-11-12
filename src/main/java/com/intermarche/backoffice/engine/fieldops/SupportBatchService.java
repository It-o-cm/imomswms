package com.intermarche.backoffice.engine.fieldops;

import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import jakarta.ws.rs.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** A finite numeric series, checked before any insertion and committed as one decision. */
@ApplicationScoped @Transactional
public class SupportBatchService {
 @Inject FieldOperationsService field; @Inject StockCommands receipts;
 public static class Input {public Long storeId,typeId;public String pattern,requestKey;public Integer start,count,rows,columns;public BigDecimal volumeLiters;}
 static List<String> codes(Input r){
  if(r==null||r.pattern==null||r.start==null||r.start<0||r.count==null||r.count<1||r.count>500)throw new BadRequestException("Choisissez de 1 à 500 éléments et un premier numéro positif ou nul");
  var m=Pattern.compile("\\{(0{0,8}1)\\}").matcher(r.pattern);
  if(!m.find())throw new BadRequestException("Modèle attendu, par exemple BAC-{0001} : utilisez BAC-{0001} et choisissez le premier numéro");
  int from=m.start(),to=m.end(),width=m.group(1).length();if(m.find())throw new BadRequestException("Un seul compteur est permis dans le modèle");
  var result=new ArrayList<String>();for(int i=0;i<r.count;i++){long n=(long)r.start+i;String code=r.pattern.substring(0,from)+String.format(Locale.ROOT,"%0"+width+"d",n)+r.pattern.substring(to);if(!code.matches("[A-Za-z0-9_-]{1,20}"))throw new BadRequestException("Chaque identifiant doit contenir de 1 à 20 lettres, chiffres, tirets ou soulignements");result.add(code);}return result;
 }
 private SupportType type(Store store,Input r){SupportType type=SupportType.find("store=?1 and id=?2",store,r.typeId).firstResult();if(type==null||!Set.of(SupportType.Kind.TOTE,SupportType.Kind.CART).contains(type.kind))throw new BadRequestException("Choisissez un type de bac ou de chariot du magasin");BigDecimal volume=r.volumeLiters==null?type.maxVolumeLiters:r.volumeLiters;if(volume==null||volume.signum()<=0||volume.scale()>3||volume.precision()>12||type.maxVolumeLiters!=null&&volume.compareTo(type.maxVolumeLiters)>0)throw new BadRequestException("Volume positif requis, dans la capacité du type, avec trois décimales maximum");if(type.kind==SupportType.Kind.CART&&(r.rows==null||r.columns==null||r.rows<1||r.rows>10||r.columns<1||r.columns>10))throw new BadRequestException("Choisissez de 1 à 10 rangées et colonnes pour les chariots");return type;}
 private List<String> collisions(List<String> codes){return Tote.getEntityManager().createQuery("select t.code from Tote t where t.code in ?1 order by t.code",String.class).setParameter(1,codes).getResultList();}
 public Object preview(Input r){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,false);field.requireManager();var codes=codes(r);type(store,r);var existing=collisions(codes);return Map.of("codes",codes,"count",codes.size(),"conflicts",existing,"canCreate",existing.isEmpty());}
 public Object create(Input r){if(r==null)throw new BadRequestException();Store store=field.store(r.storeId,true);field.requireManager();String fingerprint=field.hash(r);var previous=receipts.previous(store,r.requestKey,fingerprint);if(previous!=null)return receipts.value(previous);var codes=codes(r);var type=type(store,r);
  // Tote codes are globally unique, including historical totes in other stores.
  var existing=collisions(codes);if(!existing.isEmpty())throw new OperatorFailure(409,"BATCH_CODES_USED","Identifiants déjà utilisés : "+String.join(", ",existing.subList(0,Math.min(10,existing.size())))+". Changez le modèle ou le premier numéro.");
  BigDecimal volume=r.volumeLiters==null?type.maxVolumeLiters:r.volumeLiters;
  for(String code:codes){var tote=new Tote();tote.store=store;tote.code=code;tote.volume=volume;tote.toteType=Arrays.stream(type.allowedZones.split(",")).anyMatch(z->z.equals("CHILLED")||z.equals("FROZEN"))?Tote.ToteType.INSULATED:Tote.ToteType.STANDARD;tote.persist();var support=new PhysicalSupport();support.store=store;support.type=type;support.tote=tote;support.code=code;support.state="AVAILABLE";support.persist();if(type.kind==SupportType.Kind.CART)for(int row=1;row<=r.rows;row++)for(int column=1;column<=r.columns;column++){var c=new CartCompartment();c.cart=support;c.rowNumber=row;c.columnNumber=column;c.label=row+" / "+column;c.persist();}}
  Tote.getEntityManager().flush();return receipts.record(store,r.requestKey,fingerprint,"SUPPORT_BATCH",Map.of("count",codes.size(),"codes",codes,"kind",type.kind.name()));
 }
}
