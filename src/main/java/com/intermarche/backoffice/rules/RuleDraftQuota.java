package com.intermarche.backoffice.rules;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Limits, per user, the number of rule drafts asked to the AI assistant in a window, so that one account cannot spend
 * the AI budget of the installation. Kept in memory: a restart resets the counters.
 */
@ApplicationScoped
public class RuleDraftQuota {
 /** Drafts tolerated per user in the window. */
 @ConfigProperty(name="imomswms.rules.draft.max-per-user",defaultValue="20") int maxPerUser=20;
 /** Length of the window, in minutes. */
 @ConfigProperty(name="imomswms.rules.draft.window-minutes",defaultValue="60") long windowMinutes=60;
 /** Clock, replaceable in tests. */
 Clock clock=Clock.systemUTC();
 private final Map<String,Deque<Instant>> drafts=new ConcurrentHashMap<>();

 /**
  * Creates the quota with the configured limits.
  */
 public RuleDraftQuota(){}

 /**
  * Creates a quota with given limits.
  *
  * @param maxPerUser drafts tolerated per user in the window
  * @param windowMinutes length of the window, in minutes
  * @param clock the clock
  */
 public RuleDraftQuota(int maxPerUser,long windowMinutes,Clock clock){this.maxPerUser=maxPerUser;this.windowMinutes=windowMinutes;this.clock=clock;}

 /**
  * Records a draft request and tells whether it may be served.
  *
  * @param user the principal asking for the draft
  * @return true when the user stays within the quota
  */
 public boolean allow(String user){
  Deque<Instant> times=drafts.computeIfAbsent(user==null?"":user,k->new ArrayDeque<>());Instant now=clock.instant(),since=now.minus(Duration.ofMinutes(windowMinutes));
  synchronized(times){
   while(!times.isEmpty()&&times.peekFirst().isBefore(since))times.pollFirst();
   if(times.size()>=maxPerUser)return false;
   times.addLast(now);return true;
  }
 }
}
