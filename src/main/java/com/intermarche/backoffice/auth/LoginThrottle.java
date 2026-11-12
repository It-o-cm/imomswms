package com.intermarche.backoffice.auth;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Slows down password guessing and reset flooding, in memory: a username is refused after a few failed logins in a
 * window, a client address after many, and password reset requests are limited per username and per address.
 * Refusals are silent for resets, so that they reveal nothing about the accounts.
 */
@ApplicationScoped
public class LoginThrottle {
 /** Failed logins of one username tolerated in the window. */
 @ConfigProperty(name="imomswms.login.max-failures",defaultValue="5") int maxFailures=5;
 /** Failed logins of one client address tolerated in the window, all usernames together. */
 @ConfigProperty(name="imomswms.login.max-failures-per-address",defaultValue="50") int maxFailuresPerAddress=50;
 /** Length of the login window, in minutes. */
 @ConfigProperty(name="imomswms.login.window-minutes",defaultValue="15") long windowMinutes=15;
 /** Password reset requests per username tolerated in the reset window. */
 @ConfigProperty(name="imomswms.password-reset.max-requests",defaultValue="3") int maxResets=3;
 /** Password reset requests per client address tolerated in the reset window. */
 @ConfigProperty(name="imomswms.password-reset.max-requests-per-address",defaultValue="20") int maxResetsPerAddress=20;
 /** Length of the reset window, in minutes. */
 @ConfigProperty(name="imomswms.password-reset.window-minutes",defaultValue="60") long resetWindowMinutes=60;
 /** Number of tracked keys above which expired entries are purged. */
 static final int PURGE_ABOVE=10_000;
 /** Clock, replaceable in tests. */
 Clock clock=Clock.systemUTC();
 private final Map<String,Deque<Instant>> failures=new ConcurrentHashMap<>(),resets=new ConcurrentHashMap<>();

 /**
  * Tells whether a login attempt must be refused before checking the password.
  *
  * @param username the username typed
  * @param address the client address, or null when unknown
  * @return true when the username or the address failed too often recently
  */
 public boolean loginBlocked(String username,String address){
  Duration window=Duration.ofMinutes(windowMinutes);
  return count(failures,userKey(username),window)>=maxFailures||address!=null&&count(failures,addressKey(address),window)>=maxFailuresPerAddress;
 }

 /**
  * Records a failed login.
  *
  * @param username the username typed
  * @param address the client address, or null when unknown
  */
 public void loginFailed(String username,String address){
  record(failures,userKey(username));if(address!=null)record(failures,addressKey(address));purge(failures,Duration.ofMinutes(windowMinutes));
 }

 /**
  * Forgets the failures of a username once it logs in.
  *
  * @param username the username
  */
 public void loginSucceeded(String username){failures.remove(userKey(username));}

 /**
  * Records a password reset request and tells whether it may be served.
  *
  * @param username the username of the request
  * @param address the client address, or null when unknown
  * @return true when the request stays within the limits
  */
 public boolean resetAllowed(String username,String address){
  Duration window=Duration.ofMinutes(resetWindowMinutes);
  boolean allowed=count(resets,userKey(username),window)<maxResets&&(address==null||count(resets,addressKey(address),window)<maxResetsPerAddress);
  if(allowed){record(resets,userKey(username));if(address!=null)record(resets,addressKey(address));}
  purge(resets,window);return allowed;
 }

 /**
  * Builds the key of a username, case-insensitive.
  *
  * @param username the username, possibly null
  * @return the key
  */
 static String userKey(String username){return "user:"+(username==null?"":username.trim().toLowerCase(Locale.ROOT));}

 /**
  * Builds the key of a client address.
  *
  * @param address the address
  * @return the key
  */
 static String addressKey(String address){return "address:"+address;}

 /**
  * Counts the events of a key still inside the window.
  *
  * @param events the tracked events
  * @param key the key
  * @param window the window
  * @return the number of recent events
  */
 int count(Map<String,Deque<Instant>> events,String key,Duration window){
  Deque<Instant> times=events.get(key);if(times==null)return 0;Instant since=clock.instant().minus(window);
  synchronized(times){while(!times.isEmpty()&&times.peekFirst().isBefore(since))times.pollFirst();return times.size();}
 }

 /**
  * Records an event now.
  *
  * @param events the tracked events
  * @param key the key
  */
 void record(Map<String,Deque<Instant>> events,String key){Deque<Instant> times=events.computeIfAbsent(key,k->new ArrayDeque<>());synchronized(times){times.addLast(clock.instant());}}

 /**
  * Drops the keys whose events all left the window, once many keys are tracked.
  *
  * @param events the tracked events
  * @param window the window
  */
 void purge(Map<String,Deque<Instant>> events,Duration window){
  if(events.size()<=PURGE_ABOVE)return;
  for(String key:events.keySet())if(count(events,key,window)==0)events.remove(key);
 }

 /**
  * Tells how many keys are tracked, for tests.
  *
  * @return the number of keys holding failed logins
  */
 int trackedFailures(){return failures.size();}
}
