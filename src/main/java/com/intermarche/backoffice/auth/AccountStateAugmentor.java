package com.intermarche.backoffice.auth;

import com.intermarche.backoffice.domain.auth.Account;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Applies the current state of an account to every request carrying one of our tokens: a disabled or deleted account
 * is refused, a token issued before the last password change is refused, and the roles are those the account holds
 * now, not those written in the token. A deactivation, a demotion or a password reset therefore takes effect at once
 * instead of waiting for the token to expire.
 */
@ApplicationScoped
public class AccountStateAugmentor implements SecurityIdentityAugmentor {
 @Inject AuthenticationService accounts;
 @ConfigProperty(name="quarkus.profile") String profile;

 /**
  * The state of an account read for one request.
  *
  * @param enabled whether the account may log in
  * @param roles the roles it holds now
  * @param credentialsChangedAt when its password last changed, in epoch seconds, or null
  */
 record State(boolean enabled,Set<String> roles,Long credentialsChangedAt){}

 /**
  * Checks the account behind a locally issued token.
  *
  * @param identity the identity built from the token
  * @param context the authentication context, used to read the database off the event loop
  * @return the identity carrying the current roles
  */
 @Override
 public Uni<SecurityIdentity> augment(SecurityIdentity identity,AuthenticationRequestContext context){
  if(identity.isAnonymous()||!"dev".equals(profile)||!(identity.getPrincipal() instanceof JsonWebToken jwt))return Uni.createFrom().item(identity);
  return context.runBlocking(()->apply(identity,jwt,QuarkusTransaction.requiringNew().call(()->read(jwt.getName()))));
 }

 /**
  * Reads the state of an account.
  *
  * @param username the username or badge of the token
  * @return the state, or null when the account does not exist
  */
 State read(String username){Account account=username==null?null:accounts.findAccountByUsername(username);return account==null?null:new State(account.isEnabled(),Set.copyOf(account.getRoles()),account.getCredentialsChangedAt());}

 /**
  * Applies the state of the account to the identity.
  *
  * @param identity the identity built from the token
  * @param jwt the token
  * @param state the state of the account, or null when it does not exist
  * @return the identity with the current roles
  * @throws AuthenticationFailedException when the account is unknown or disabled, or the token predates a password change
  */
 static SecurityIdentity apply(SecurityIdentity identity,JsonWebToken jwt,State state){
  if(state==null||!state.enabled())throw new AuthenticationFailedException("Compte inconnu ou désactivé");
  if(state.credentialsChangedAt()!=null&&jwt.getIssuedAtTime()<state.credentialsChangedAt())throw new AuthenticationFailedException("Session antérieure au dernier changement de mot de passe");
  return QuarkusSecurityIdentity.builder().setPrincipal(identity.getPrincipal()).addCredentials(identity.getCredentials()).addAttributes(identity.getAttributes()).addRoles(state.roles()).setAnonymous(false).build();
 }
}
