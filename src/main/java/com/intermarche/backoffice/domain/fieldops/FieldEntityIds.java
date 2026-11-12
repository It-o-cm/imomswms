package com.intermarche.backoffice.domain.fieldops;
import com.intermarche.backoffice.domain.BaseEntity;
/** Reading a lazy entity's inherited public id must not read the proxy's uninitialised field. */
public final class FieldEntityIds {
 public static Long id(Object entity){if(entity==null)return null;if(entity instanceof org.hibernate.proxy.HibernateProxy p)return (Long)p.getHibernateLazyInitializer().getIdentifier();return ((BaseEntity)entity).id;}
 private FieldEntityIds(){}
}
