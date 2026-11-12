package com.intermarche.backoffice.auth;
import com.intermarche.backoffice.domain.catalog.*;
import com.intermarche.backoffice.domain.order.*;
import com.intermarche.backoffice.domain.store.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.List;
/** Completes the demo only; never overwrites store-authored catalogue configuration. */
@ApplicationScoped
public class DemoProductInformationInitializer {
 @Transactional public void seed(Store store,List<Product> products){
  for(Product p:products){
   if(ProductInstruction.count("store=?1 and product=?2",store,p)==0){var i=new ProductInstruction();i.store=store;i.product=p;i.revision=1;i.author="DEMO";i.text=switch(p.temperatureZone){case CHILLED->"Préserver la chaîne du froid. Vérifier l’intégrité de l’emballage et la date.";case FROZEN->"Maintenir surgelé. Ne pas déposer en zone ambiante.";case HOUSEHOLD->"Séparer des produits alimentaires et vérifier l’étanchéité.";default->"Vérifier l’intégrité de l’emballage. Ne pas écraser les produits fragiles.";};i.persist();}
   String unit=switch(p.productType){case UNIT->"PCS";case WEIGHT->"KG";case VOLUME->"L";};
   if(ProductPackaging.count("store=?1 and product=?2 and code=?3",store,p,"DEMO_CASE")==0){var pack=new ProductPackaging();pack.store=store;pack.product=p;pack.code="DEMO_CASE";pack.label=p.productType==ProductType.UNIT?"Carton de 12":"Conditionnement de 5 "+unit;pack.level="CASE";pack.unit=unit;pack.factor=new BigDecimal(p.productType==ProductType.UNIT?"12":"5");pack.persist();}
   alias(store,p,"SKU-"+p.ean,"BASE");alias(store,p,"BOX-"+p.ean,"DEMO_CASE");
  }
 }
 private void alias(Store store,Product p,String value,String packaging){if(ProductIdentifier.count("store=?1 and kind='INTERNAL' and context='' and normalized=?2",store,value.toUpperCase(java.util.Locale.ROOT))>0)return;var i=new ProductIdentifier();i.store=store;i.product=p;i.kind="INTERNAL";i.value=value;i.normalized=value.toUpperCase(java.util.Locale.ROOT);i.packaging=packaging;i.persist();}
}
