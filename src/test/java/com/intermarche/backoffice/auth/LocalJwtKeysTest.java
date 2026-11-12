package com.intermarche.backoffice.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LocalJwtKeys}: when a pair is generated, and that the files hold a usable RSA pair.
 */
class LocalJwtKeysTest {
 @TempDir Path dir;

 /**
  * Builds the generator for two locations.
  *
  * @param sign the signing key location, or null
  * @param verify the verification key location, or null
  * @return the generator
  */
 private static LocalJwtKeys keys(String sign,String verify){
  LocalJwtKeys keys=new LocalJwtKeys();keys.signKeyLocation=Optional.ofNullable(sign);keys.verifyKeyLocation=Optional.ofNullable(verify);return keys;
 }

 /**
  * Reads the DER content of a PEM file.
  *
  * @param file the file
  * @return the decoded key
  * @throws Exception if the file cannot be read
  */
 private static byte[] der(Path file)throws Exception{
  return Base64.getMimeDecoder().decode(Files.readString(file).replaceAll("-----[A-Z ]+-----",""));
 }

 /**
  * Checks that a missing pair is written in a new directory, as a matching RSA pair, then kept on the next start.
  *
  * @throws Exception if the files cannot be read
  */
 @Test
 void missingPairIsWrittenOnceAndKept()throws Exception{
  Path priv=dir.resolve("jwt/privateKey.pem"),pub=dir.resolve("jwt/publicKey.pem");
  LocalJwtKeys keys=keys("file:"+priv,"file:"+pub);
  assertTrue(keys.ensureKeys());
  assertTrue(Files.readString(priv).startsWith("-----BEGIN PRIVATE KEY-----\n"));
  assertTrue(Files.readString(pub).startsWith("-----BEGIN PUBLIC KEY-----\n"));
  var factory=KeyFactory.getInstance("RSA");
  assertNotNull(factory.generatePrivate(new PKCS8EncodedKeySpec(der(priv))));
  assertNotNull(factory.generatePublic(new X509EncodedKeySpec(der(pub))));
  String before=Files.readString(priv);
  assertFalse(keys.ensureKeys());
  assertEquals(before,Files.readString(priv));
 }

 /**
  * Checks that a pair missing one of its files is written again.
  *
  * @throws Exception if the files cannot be written
  */
 @Test
 void halfPairIsReplaced()throws Exception{
  Path priv=dir.resolve("privateKey.pem"),pub=dir.resolve("publicKey.pem");
  Files.writeString(priv,"old");
  assertTrue(keys("file:"+priv,"file:"+pub).ensureKeys());
  assertNotEquals("old",Files.readString(priv));
  Files.delete(priv);
  assertTrue(keys("file:"+priv,"file:"+pub).ensureKeys());
 }

 /**
  * Checks that nothing is done when either location is missing or on the classpath.
  */
 @Test
 void classpathOrMissingLocationsAreLeftAlone(){
  assertFalse(keys(null,"file:"+dir.resolve("p.pem")).ensureKeys());
  assertFalse(keys("file:"+dir.resolve("k.pem"),null).ensureKeys());
  assertFalse(keys("privateKey.pem","publicKey.pem").ensureKeys());
  assertNull(LocalJwtKeys.file(null));
  assertNull(LocalJwtKeys.file("classpath:privateKey.pem"));
  assertEquals(Path.of("data/jwt/k.pem"),LocalJwtKeys.file("file:data/jwt/k.pem"));
 }
}
