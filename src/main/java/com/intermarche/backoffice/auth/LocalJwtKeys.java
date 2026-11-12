package com.intermarche.backoffice.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Creates the key pair of the locally issued tokens when it does not exist yet. The keys are never part of the
 * sources: each installation generates its own pair, outside the classpath, so that nobody holding the repository can
 * forge a token. Nothing is done when the keys are read from the classpath (tests) or not configured (Keycloak).
 */
@ApplicationScoped
public class LocalJwtKeys {
 private static final Logger LOGGER=Logger.getLogger(LocalJwtKeys.class);
 /** Prefix of the key locations handled here. */
 static final String FILE_PREFIX="file:";
 /** Location of the signing key. */
 @ConfigProperty(name="smallrye.jwt.sign.key.location") Optional<String> signKeyLocation;
 /** Location of the verification key. */
 @ConfigProperty(name="mp.jwt.verify.publickey.location") Optional<String> verifyKeyLocation;

 /**
  * Generates the keys at startup when needed.
  *
  * @param event the startup event
  */
 void onStart(@Observes StartupEvent event){ensureKeys();}

 /**
  * Generates the key pair when both locations are files and the signing key is missing.
  *
  * @return true when a new pair was written
  */
 public synchronized boolean ensureKeys(){
  Path privateKey=file(signKeyLocation.orElse(null)),publicKey=file(verifyKeyLocation.orElse(null));
  if(privateKey==null||publicKey==null||Files.exists(privateKey)&&Files.exists(publicKey))return false;
  write(privateKey,publicKey);
  LOGGER.infof("New token key pair written to %s and %s",privateKey,publicKey);
  return true;
 }

 /**
  * Turns a key location into a file path.
  *
  * @param location the configured location, possibly null
  * @return the path, or null when the location is not a file
  */
 static Path file(String location){
  return location==null||!location.startsWith(FILE_PREFIX)?null:Path.of(location.substring(FILE_PREFIX.length()));
 }

 /**
  * Generates a 2048-bit RSA pair and writes it in PEM, the private key readable by its owner only when the file
  * system supports it.
  *
  * @param privateKey the file of the private key
  * @param publicKey the file of the public key
  */
 static void write(Path privateKey,Path publicKey){
  try{
   KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
   for(Path p:new Path[]{privateKey,publicKey})if(p.toAbsolutePath().getParent()!=null)Files.createDirectories(p.toAbsolutePath().getParent());
   Files.writeString(publicKey,pem("PUBLIC KEY",pair.getPublic().getEncoded()),StandardCharsets.US_ASCII);
   Files.writeString(privateKey,pem("PRIVATE KEY",pair.getPrivate().getEncoded()),StandardCharsets.US_ASCII);
   try{Files.setPosixFilePermissions(privateKey,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException e){LOGGER.debug("No POSIX permissions on this file system");}
  }catch(IOException e){throw new UncheckedIOException(e);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }

 /**
  * Encodes a key in PEM.
  *
  * @param type the PEM type, such as {@code PUBLIC KEY}
  * @param der the DER encoding of the key
  * @return the PEM text
  */
 static String pem(String type,byte[] der){
  return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,"\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)+"\n-----END "+type+"-----\n";
 }
}
