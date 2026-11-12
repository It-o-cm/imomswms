package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
import com.intermarche.backoffice.domain.store.*;
import jakarta.persistence.*;
@Entity @Table(name="physical_supports",uniqueConstraints={@UniqueConstraint(columnNames={"tote_id"}),@UniqueConstraint(columnNames={"store_id","code"})})
public class PhysicalSupport extends BaseEntity {
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public Store store;
 @ManyToOne(optional=false,fetch=FetchType.LAZY) public SupportType type;
 @OneToOne(optional=false,fetch=FetchType.LAZY) public Tote tote;
 @Column(nullable=false,length=20) public String code;
 @Column(nullable=false,length=20) public String state="AVAILABLE";
 @Override public int getChecksum(){return java.util.Objects.hash(id);}
 @Column(nullable=false) public boolean consumableUsed=false;
 public static void requireAvailable(Tote tote){requireAvailable(tote,null);}
 public static void requireAvailable(Tote tote,TemperatureZone usage){PhysicalSupport s=find("select s from PhysicalSupport s join fetch s.type where s.tote=?1",tote).firstResult();if(s==null)return;if(!"AVAILABLE".equals(s.state))throw new IllegalArgumentException("Support "+tote.code+" "+("BLOCKED".equals(s.state)?"bloqué":"retiré du service"));if(usage!=null&&!java.util.Arrays.asList(s.type.allowedZones.split(",")).contains(usage.name()))throw new IllegalArgumentException("Support "+tote.code+" incompatible avec le conditionnement "+usage);}

}
