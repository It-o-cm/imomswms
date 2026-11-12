package com.intermarche.backoffice.engine.operator;

import com.intermarche.backoffice.auth.AuthenticationService;
import com.intermarche.backoffice.domain.auth.Account;
import com.intermarche.backoffice.domain.store.*;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.hibernate.Hibernate;
import java.time.OffsetDateTime;
import java.util.*;

/** Explicit public projection; account entities and credentials never cross this boundary. */
@ApplicationScoped
public class OperatorContext {
    @Inject AuthenticationService authentication;
    @Inject SecurityIdentity identity;
    @Inject OperatorRequestAccount requestAccount;
    public Account account() {
        if(identity.isAnonymous())throw new NotAuthorizedException("Authentification requise");
        Account account;
        try {account=requestAccount.get();}
        catch(IllegalStateException e){throw new ForbiddenException("Compte opérateur indisponible");}
        if(!account.isEnabled() || account.getRoles().stream().noneMatch(r->Set.of("ADMIN","MANAGER","PICKER").contains(r) && identity.hasRole(r)))
            throw new ForbiddenException("Compte inactif ou droits modifiés. Reconnectez-vous.");
        return account;
    }
    public Set<String> roles(Account account){var result=new TreeSet<>(account.getRoles());result.removeIf(r->!identity.hasRole(r));return result;}
    public Map<String,Object> view(Long requestedStore) {
        Account account=account();Set<String> roles=roles(account);boolean admin=roles.contains("ADMIN"),manager=roles.contains("MANAGER");
        List<Store> allowed=admin?Store.listAll():account.getStore()==null?List.of():List.of(Hibernate.unproxy(account.getStore(),Store.class));
        if(requestedStore!=null && allowed.stream().noneMatch(s->s.id.equals(requestedStore)))throw new ForbiddenException("Magasin hors de votre périmètre");
        var capabilities=new ArrayList<>(List.of("MISSIONS_READ","PLACES_READ","TOTES_READ","PRODUCTS_READ","STOCK_READ","OWN_ACTIVITY_READ"));
        if(account instanceof Employee)capabilities.addAll(List.of("MISSIONS_EXECUTE","OWN_POSITION_UPDATE","COMMAND_RECEIPTS_READ"));
        if(manager||admin)capabilities.addAll(List.of("PLANNING_MANAGE","PLACES_MANAGE","STOCK_MANAGE","TOTE_CONTENTS_CORRECT"));
        var actor=new LinkedHashMap<String,Object>();actor.put("username",account.getUsername());actor.put("displayName",account.getFullName());actor.put("roles",roles);
        if(account instanceof Employee e)actor.put("employeeId",e.id);
        return Map.of("identity",actor,"stores",allowed.stream().filter(s->requestedStore==null || requestedStore.equals(s.id)).map(s->Map.of("id",s.id,"code",s.code,"name",s.name==null?s.code:s.name)).toList(),
            "capabilities",capabilities,"contractVersions",List.of(1,2),"currentContractVersion",2,"serverTime",OffsetDateTime.now().toString(),"applicationVersion","1.0-SNAPSHOT","sessionPolicy",OperatorSupportService.policy());
    }
}
