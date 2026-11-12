package com.intermarche.backoffice.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoginThrottle}: per-username and per-address login limits, window expiry, success reset,
 * reset request limits and purge of expired keys.
 */
class LoginThrottleTest {
 private LoginThrottle throttle;
 private Instant now;

 /**
  * Builds a throttle with small limits and a clock under control.
  */
 @BeforeEach
 void setUp(){
  throttle=new LoginThrottle();throttle.maxFailures=2;throttle.maxFailuresPerAddress=3;throttle.windowMinutes=15;throttle.maxResets=2;throttle.maxResetsPerAddress=3;throttle.resetWindowMinutes=60;
  now=Instant.parse("2026-10-30T10:00:00Z");throttle.clock=Clock.fixed(now,ZoneOffset.UTC);
 }

 /**
  * Moves the clock forward.
  *
  * @param duration how far
  */
 private void advance(Duration duration){now=now.plus(duration);throttle.clock=Clock.fixed(now,ZoneOffset.UTC);}

 /**
  * Checks that a username is blocked once its failures reach the limit, case and spaces ignored.
  */
 @Test
 void usernameIsBlockedAfterTooManyFailures(){
  assertFalse(throttle.loginBlocked("alice",null));
  throttle.loginFailed("alice",null);
  assertFalse(throttle.loginBlocked("alice",null));
  throttle.loginFailed(" ALICE ",null);
  assertTrue(throttle.loginBlocked("alice",null));
  assertFalse(throttle.loginBlocked("bob",null));
 }

 /**
  * Checks that an address is blocked once its failures, all usernames together, reach the limit.
  */
 @Test
 void addressIsBlockedAfterTooManyFailures(){
  throttle.loginFailed("a","10.0.0.1");throttle.loginFailed("b","10.0.0.1");
  assertFalse(throttle.loginBlocked("c","10.0.0.1"));
  throttle.loginFailed("d","10.0.0.1");
  assertTrue(throttle.loginBlocked("c","10.0.0.1"));
  assertFalse(throttle.loginBlocked("c","10.0.0.2"));
  assertFalse(throttle.loginBlocked("c",null));
 }

 /**
  * Checks that failures leave the window after its length.
  */
 @Test
 void failuresExpireWithTheWindow(){
  throttle.loginFailed("alice",null);throttle.loginFailed("alice",null);
  assertTrue(throttle.loginBlocked("alice",null));
  advance(Duration.ofMinutes(16));
  assertFalse(throttle.loginBlocked("alice",null));
 }

 /**
  * Checks that a successful login forgets the failures of the username but not those of the address.
  */
 @Test
 void successForgetsTheUsernameFailures(){
  throttle.loginFailed("alice","10.0.0.1");throttle.loginFailed("alice","10.0.0.1");throttle.loginFailed("bob","10.0.0.1");
  throttle.loginSucceeded("ALICE");
  assertFalse(throttle.loginBlocked("alice",null));
  assertTrue(throttle.loginBlocked("alice","10.0.0.1"));
 }

 /**
  * Checks the reset limits per username and per address, and that a refused request is not counted.
  */
 @Test
 void resetRequestsAreLimitedPerUsernameAndPerAddress(){
  assertTrue(throttle.resetAllowed("alice","10.0.0.1"));
  assertTrue(throttle.resetAllowed("alice",null));
  assertFalse(throttle.resetAllowed("alice",null));
  assertTrue(throttle.resetAllowed("bob","10.0.0.1"));
  assertTrue(throttle.resetAllowed("carol","10.0.0.1"));
  assertFalse(throttle.resetAllowed("dave","10.0.0.1"));
  assertTrue(throttle.resetAllowed("dave","10.0.0.2"));
  advance(Duration.ofMinutes(61));
  assertTrue(throttle.resetAllowed("alice","10.0.0.1"));
 }

 /**
  * Checks that a null username has a key of its own.
  */
 @Test
 void nullUsernameHasAKey(){
  assertEquals("user:",LoginThrottle.userKey(null));
  assertEquals("user:alice",LoginThrottle.userKey(" Alice "));
  assertEquals("address:10.0.0.1",LoginThrottle.addressKey("10.0.0.1"));
 }

 /**
  * Checks that expired keys are purged only once many keys are tracked.
  */
 @Test
 void expiredKeysArePurgedAboveTheThreshold(){
  for(int n=0;n<LoginThrottle.PURGE_ABOVE;n++)throttle.loginFailed("user"+n,null);
  assertEquals(LoginThrottle.PURGE_ABOVE,throttle.trackedFailures());
  advance(Duration.ofMinutes(16));
  throttle.loginFailed("late",null);
  assertEquals(1,throttle.trackedFailures());
 }
}
