package com.intermarche.backoffice.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.intermarche.backoffice.domain.store.Employee;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.security.Principal;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccountStateAugmentor}: which identities are checked, and how the current state of the account
 * changes or refuses them.
 */
class AccountStateAugmentorTest {

 /**
  * Builds an identity carrying a token issued at a given time with the roles of the token.
  *
  * @param issuedAt the issue time, in epoch seconds
  * @param roles the roles written in the token
  * @return the identity
  */
 private static SecurityIdentity tokenIdentity(long issuedAt,String... roles){
  JsonWebToken jwt=mock(JsonWebToken.class);when(jwt.getName()).thenReturn("BADGE-1");when(jwt.getIssuedAtTime()).thenReturn(issuedAt);
  return QuarkusSecurityIdentity.builder().setPrincipal(jwt).addRoles(Set.of(roles)).build();
 }

 /**
  * Checks that the roles of the account replace those of the token.
  */
 @Test
 void currentRolesReplaceThoseOfTheToken(){
  SecurityIdentity identity=tokenIdentity(1000,"MANAGER");
  SecurityIdentity result=AccountStateAugmentor.apply(identity,(JsonWebToken)identity.getPrincipal(),new AccountStateAugmentor.State(true,Set.of("PICKER"),null));
  assertEquals(Set.of("PICKER"),result.getRoles());
  assertSame(identity.getPrincipal(),result.getPrincipal());
  assertFalse(result.isAnonymous());
 }

 /**
  * Checks that an unknown account is refused.
  */
 @Test
 void unknownAccountIsRefused(){
  SecurityIdentity identity=tokenIdentity(1000,"MANAGER");
  assertThrows(AuthenticationFailedException.class,()->AccountStateAugmentor.apply(identity,(JsonWebToken)identity.getPrincipal(),null));
 }

 /**
  * Checks that a disabled account is refused.
  */
 @Test
 void disabledAccountIsRefused(){
  SecurityIdentity identity=tokenIdentity(1000,"MANAGER");
  assertThrows(AuthenticationFailedException.class,()->AccountStateAugmentor.apply(identity,(JsonWebToken)identity.getPrincipal(),new AccountStateAugmentor.State(false,Set.of("MANAGER"),null)));
 }

 /**
  * Checks that a token issued before the last password change is refused, and one issued at or after it accepted.
  */
 @Test
 void tokenOlderThanThePasswordChangeIsRefused(){
  SecurityIdentity old=tokenIdentity(999,"MANAGER");
  assertThrows(AuthenticationFailedException.class,()->AccountStateAugmentor.apply(old,(JsonWebToken)old.getPrincipal(),new AccountStateAugmentor.State(true,Set.of("MANAGER"),1000L)));
  SecurityIdentity same=tokenIdentity(1000,"MANAGER");
  assertEquals(Set.of("MANAGER"),AccountStateAugmentor.apply(same,(JsonWebToken)same.getPrincipal(),new AccountStateAugmentor.State(true,Set.of("MANAGER"),1000L)).getRoles());
 }

 /**
  * Checks that identities that are anonymous, outside the local profile or not built from a token are left untouched.
  */
 @Test
 void otherIdentitiesAreLeftUntouched(){
  AccountStateAugmentor augmentor=new AccountStateAugmentor();augmentor.profile="dev";
  AuthenticationRequestContext context=mock(AuthenticationRequestContext.class);
  SecurityIdentity anonymous=QuarkusSecurityIdentity.builder().setAnonymous(true).build();
  assertSame(anonymous,augmentor.augment(anonymous,context).await().indefinitely());
  Principal plain=()->"svc";
  SecurityIdentity notToken=QuarkusSecurityIdentity.builder().setPrincipal(plain).addRole("SERVICE_CALLER").build();
  assertSame(notToken,augmentor.augment(notToken,context).await().indefinitely());
  augmentor.profile="prod";
  SecurityIdentity token=tokenIdentity(1000,"MANAGER");
  assertSame(token,augmentor.augment(token,context).await().indefinitely());
  verifyNoInteractions(context);
 }

 /**
  * Checks that the state is read from the account, and that an unknown or unnamed account gives no state.
  */
 @Test
 void stateIsReadFromTheAccount(){
  AccountStateAugmentor augmentor=new AccountStateAugmentor();augmentor.accounts=mock(AuthenticationService.class);
  Employee employee=new Employee();employee.badgeId="BADGE-1";employee.role=Employee.EmployeeRole.PICKER;employee.active=true;employee.credentialsChangedAt=42L;
  when(augmentor.accounts.findAccountByUsername("BADGE-1")).thenReturn(employee);
  assertEquals(new AccountStateAugmentor.State(true,Set.of("PICKER"),42L),augmentor.read("BADGE-1"));
  assertNull(augmentor.read("GHOST"));
  assertNull(augmentor.read(null));
 }
}
