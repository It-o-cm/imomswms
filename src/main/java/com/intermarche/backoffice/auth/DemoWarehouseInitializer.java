package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.domain.order.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Completes the demo warehouse without replacing existing locations, stock or an edited plan. */
@ApplicationScoped
public class DemoWarehouseInitializer {
    @Inject EntityManager em;
    private static final String[] AISLES={"R01","R02","R03","R04","R05","R06"};
    private static final double[] Y={-300,-180,-60,60,180,300};
    public void seed(WarehouseBuilding warehouse) {
        java.util.List<Product> products=Product.<Product>list("order by ean").stream().sorted(Comparator.comparing(p->p.description!=null && p.description.startsWith("DEMO-ALTERNATIVE-"))).toList();
        for(Product product:products) {
            boolean located=warehouse.locations.stream().anyMatch(l -> l.product!=null && product.id.equals(org.hibernate.Hibernate.unproxy(l.product,Product.class).id));
            if(located)continue;
            if(product.description!=null && product.description.startsWith("DEMO-ALTERNATIVE-")) {
                String originalEan=product.description.substring("DEMO-ALTERNATIVE-".length());
                ShelfLocation origin=warehouse.locations.stream().filter(l->l.product!=null && originalEan.equals(l.product.ean)).findFirst().orElse(null);
                if(origin!=null){WarehouseShelfLocation alternative=new WarehouseShelfLocation();alternative.product=product;alternative.area=origin.area;alternative.aisle=origin.aisle;alternative.unit=origin.unit;alternative.elevation=origin.elevation;alternative.position="2";alternative.aislePosition=origin.aislePosition;alternative.maxPallets=2;alternative.maxWeightKg=1000.0;alternative.persistAndFlush();warehouse.locations.add(alternative);continue;}
            }

            int first=switch(product.temperatureZone){case CHILLED -> 2;case FROZEN -> 4;case HOUSEHOLD -> 5;default -> 0;};
            int last=first==0?1:first==2?3:first;
            int row=first;String bay=null;
            for(;row<=last;row++) {
                Set<String> used=new HashSet<>();String aisle=scopedCode(warehouse,AISLES[row]);
                warehouse.locations.stream().filter(l -> aisle.equals(l.aisle)).forEach(l -> used.add(l.unit));
                for(int slot=1;slot<=16;slot++) {String unit=String.format("B%02d",slot);if(!used.contains(unit)){bay=unit;break;}}
                if(bay!=null)break;
            }
            if(bay==null)throw new IllegalStateException("Demo warehouse capacity exceeded");
            WarehouseShelfLocation location=new WarehouseShelfLocation();location.product=product;location.area=scopedCode(warehouse,product.temperatureZone.name());
            location.aisle=scopedCode(warehouse,AISLES[row]);location.unit=bay;location.elevation="1";location.position="1";location.maxPallets=2;location.maxWeightKg=1000.0;
            location.persistAndFlush();warehouse.locations.add(location);
        }
        for(ShelfLocation location:warehouse.locations) if(location.aislePosition==null && location.unit!=null && location.unit.matches("B[0-9]+")) location.aislePosition=(Integer.parseInt(location.unit.substring(1))-0.5)/16;
        em.flush();
        if(PlanNode.count("building",warehouse)==0 && Aisle.count("building",warehouse)==0 && PlanZone.count("building",warehouse)==0) createPlan(warehouse);
        for(Aisle a:Aisle.listByBuilding(warehouse)) if("BIDIRECTIONAL".equals(a.flowType)){a.flowType="LINEAR";a.aisleType="LINEAR";}
        buffer(warehouse,warehouse.buildingCode+"-REC",Buffer.BufferPurpose.RECEIPT,"Quai de réception camion");
        buffer(warehouse,warehouse.buildingCode+"-OUT",Buffer.BufferPurpose.PROCESSING,"Préparation et départ vers le magasin");
        if(warehouse.planImage==null || warehouse.planImage.isBlank()) drawBackground(warehouse);
    }
    private String scopedCode(Building building,String code){return "LYO-042-WH".equals(building.buildingCode)?code:("LYO-042-ANNEXE".equals(building.buildingCode)?"AN_":"W"+building.id+"_")+code;}
    private void buffer(Building building,String code,Buffer.BufferPurpose purpose,String label) {
        if(Buffer.count("bufferCode",code)>0)return;
        Buffer b=new Buffer();b.building=building;b.bufferCode=code;b.purpose=purpose;b.locationDescription=label;b.active=true;
        b.ambientCapacity=24;b.chilledCapacity=12;b.frozenCapacity=12;b.maxWeightKg=1500.0;b.planNodeCode=scopedCode(building,"WH_DOCK");b.persist();
    }
    private PlanNode node(Building b,String code,double x,double y,PlanNodeType type,String label) {
        PlanNode n=new PlanNode();n.building=b;n.code=scopedCode(b,code);n.x=x;n.y=y;n.nodeType=type;n.label=label;n.persist();return n;
    }
    private void aisle(Building b,String code,PlanNode head,PlanNode tail) {
        Aisle a=new Aisle();a.building=b;a.code=scopedCode(b,code);a.headNode=head;a.tailNode=tail;a.flowType="LINEAR";a.aisleType="LINEAR";a.shapeType="STRAIGHT";
        a.lengthMeters=Math.hypot(head.x-tail.x,head.y-tail.y)/8;a.persist();
    }
    private void createPlan(WarehouseBuilding b) {
        PlanNode[] left=new PlanNode[7],right=new PlanNode[7];
        for(int i=0;i<7;i++) {
            double y=i<6?Y[i]:390;
            left[i]=node(b,"WH_L"+i,-520,y,PlanNodeType.INTERSECTION,"Circulation ouest "+i);
            right[i]=node(b,"WH_R"+i,520,y,PlanNodeType.INTERSECTION,"Circulation est "+i);
            if(i<6)aisle(b,AISLES[i],left[i],right[i]);
            if(i>0){aisle(b,"WH_W"+i,left[i-1],left[i]);aisle(b,"WH_E"+i,right[i-1],right[i]);}
        }
        PlanNode dock=node(b,"WH_DOCK",0,390,PlanNodeType.INTERSECTION,"Réception et préparation");
        PlanNode entrance=node(b,"WH_TRUCK",0,460,PlanNodeType.ENTRANCE,"Accès quai camion");
        aisle(b,"WH_QUAI_W",left[6],dock);aisle(b,"WH_QUAI_E",dock,right[6]);aisle(b,"WH_ACCES",entrance,dock);
        java.util.List<Aisle> aisles=Aisle.listByBuilding(b);
        for(Aisle a:aisles) {
            a.connectedToHead=aisles.stream().filter(other -> other!=a && (other.headNode.id.equals(a.headNode.id)||other.tailNode.id.equals(a.headNode.id))).map(other -> other.code).sorted().collect(java.util.stream.Collectors.joining(","));
            a.connectedToTail=aisles.stream().filter(other -> other!=a && (other.headNode.id.equals(a.tailNode.id)||other.tailNode.id.equals(a.tailNode.id))).map(other -> other.code).sorted().collect(java.util.stream.Collectors.joining(","));
        }
        zone(b,"AMBIENT","Stockage ambiant",TemperatureZone.AMBIENT,"#fef3c7",-355,-125);
        zone(b,"CHILLED","Chambre froide +2 °C",TemperatureZone.CHILLED,"#dbeafe",-115,115);
        zone(b,"FROZEN","Chambre surgelée −18 °C",TemperatureZone.FROZEN,"#e0e7ff",125,235);
        zone(b,"HOUSEHOLD","Entretien · stockage séparé",TemperatureZone.HOUSEHOLD,"#dcfce7",245,355);
        zone(b,"WH_RECEIPT","Réception camion et préparation magasin",null,"#cffafe",365,435);
        em.flush();
    }
    private void zone(Building building,String code,String label,TemperatureZone temp,String color,double top,double bottom) {
        PlanZone z=new PlanZone();z.building=building;z.code=scopedCode(building,code);z.label=label;z.temperatureZone=temp;z.color=color;z.labelOffsetX=0.0;z.labelOffsetY=0.0;
        double[][] points={{-570,top},{570,top},{570,bottom},{-570,bottom}};
        for(int i=0;i<4;i++){PlanZoneVertex v=new PlanZoneVertex();v.zone=z;v.position=i;v.x=points[i][0];v.y=points[i][1];z.vertices.add(v);}z.persist();
    }
    private void drawBackground(Building building) {
        int width=1280,height=1000;BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new Color(0xf8fafc));g.fillRect(0,0,width,height);
        g.translate(640,450);g.setColor(new Color(0x475569));g.setStroke(new BasicStroke(5));g.drawRect(-600,-390,1200,850);
        for(int row=0;row<6;row++) {
            g.setColor(row<2?new Color(0xfffbeb):row<4?new Color(0xeff6ff):row==4?new Color(0xeef2ff):new Color(0xf0fdf4));
            g.fillRect(-570,(int)Y[row]-50,1140,100);
            g.setColor(new Color(0x64748b));g.setFont(new Font("SansSerif",Font.BOLD,15));g.drawString(scopedCode(building,AISLES[row]),-565,(int)Y[row]+5);
            for(int bay=0;bay<16;bay++) {
                int x=-480+bay*60;
                g.setColor(new Color(0xcbd5e1));g.fillRoundRect(x,(int)Y[row]-43,50,25,3,3);g.fillRoundRect(x,(int)Y[row]+18,50,25,3,3);
                g.setColor(new Color(0x475569));g.setFont(new Font("SansSerif",Font.PLAIN,10));g.drawString(String.format("B%02d",bay+1),x+13,(int)Y[row]-26);
            }
        }
        g.setColor(new Color(0x0e7490));g.setFont(new Font("SansSerif",Font.BOLD,18));g.drawString("QUAI RÉCEPTION CAMION",-490,410);g.drawString("DÉPART VERS MAGASIN",180,410);
        g.setColor(new Color(0x334155));g.setFont(new Font("SansSerif",Font.BOLD,21));g.drawString("ENTREPÔT · "+building.buildingCode,-220,-410);
        g.dispose();try {ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);building.planImage=Base64.getEncoder().encodeToString(out.toByteArray());building.planImageX=-640;building.planImageY=-450;building.planImageScale=1;}
        catch(Exception e){throw new IllegalStateException("Warehouse background generation failed",e);}
    }
}
