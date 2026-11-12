package com.intermarche.backoffice.engine.operator;
import com.intermarche.backoffice.auth.AuthenticationService;import com.intermarche.backoffice.domain.auth.Account;import jakarta.enterprise.context.RequestScoped;import jakarta.inject.Inject;
/** One account lookup per HTTP request, never a cross-request permission cache. */
@RequestScoped public class OperatorRequestAccount {
 @Inject AuthenticationService authentication;
 private Account account;
 public Account get(){if(account==null)account=authentication.getConnectedAccount();return account;}
}
