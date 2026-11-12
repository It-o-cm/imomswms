package com.intermarche.backoffice.engine.fieldops;
import static com.intermarche.backoffice.domain.fieldops.FieldEntityIds.id;
import com.intermarche.backoffice.domain.fieldops.*;
import com.intermarche.backoffice.engine.operator.OperatorFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;

@ApplicationScoped @Transactional
public class FieldAttachmentService {
 @Inject FieldOperationsService field;@Inject StockCommands receipts;
 @org.eclipse.microprofile.openapi.annotations.media.Schema(name="FieldPhotoInput") public static class Input {public Long storeId;public String requestKey,filename,contentBase64;}
 Map<String,Object> metadata(IncidentAttachment a){return Map.of("id",a.id,"filename",a.filename,"mediaType",a.mediaType,"width",a.width,"height",a.height,"sha256",a.sha256);}
 public Object add(Long id,Input r){
  if(r==null)throw new BadRequestException();FieldOperationsService.key(r.requestKey);FieldOperationsService.text(r.filename,80);if(r.contentBase64==null||r.contentBase64.length()>2_800_000)throw new ClientErrorException("Photo limitée à 2 Mo",413);var s=field.store(r.storeId,true);var incident=field.incident(s,id);
  byte[] bytes;try{bytes=Base64.getDecoder().decode(r.contentBase64);}catch(IllegalArgumentException e){throw new BadRequestException("Photo encodée invalide");}if(bytes.length==0||bytes.length>2_097_152)throw new ClientErrorException("Photo limitée à 2 Mo",413);
  String digest;try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
  var existing=IncidentAttachment.<IncidentAttachment>find("incident=?1 and requestKey=?2",incident,r.requestKey).firstResult();String filename=r.filename.trim().replaceAll("[^A-Za-z0-9 _.-]","_");String fingerprint=field.hash(Arrays.asList("photo",id,digest,filename));var previous=receipts.previous(s,r.requestKey,fingerprint);if(previous!=null)return receipts.value(previous);if(existing!=null){if(!existing.sha256.equals(digest)||!existing.filename.equals(filename))throw new OperatorFailure(409,"IDEMPOTENCY_KEY_REUSED","Cette référence désigne une autre photo");return receipts.record(s,r.requestKey,fingerprint,"PHOTO_ADDED",metadata(existing));}if(IncidentAttachment.count("incident",incident)>=5)throw new BadRequestException("Cinq photos maximum par dossier");
  String mime;int width,height;
  try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new BadRequestException("Photo JPEG ou PNG requise");var reader=readers.next();try{reader.setInput(stream);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("jpeg","png").contains(format))throw new BadRequestException("Photo JPEG ou PNG requise");width=reader.getWidth(0);height=reader.getHeight(0);if(width<1||height<1||width>4096||height>4096||(long)width*height>8_000_000)throw new BadRequestException("Photo limitée à 4096 pixels de côté et 8 mégapixels");var image=reader.read(0);if(image==null)throw new BadRequestException("Photo illisible");image.flush();mime=format.equals("png")?"image/png":"image/jpeg";}finally{reader.dispose();}}
  catch(WebApplicationException e){throw e;}catch(Exception e){throw new BadRequestException("Photo tronquée ou invalide");}
  var a=new IncidentAttachment();a.incident=incident;a.requestKey=r.requestKey;a.sha256=digest;a.filename=filename;a.mediaType=mime;a.data=bytes;a.width=width;a.height=height;a.persistAndFlush();field.history(incident,field.hash(Arrays.asList("photo",r.requestKey)),field.hash(Arrays.asList(digest,filename)),"PHOTO_ADDED","Photo ajoutée : "+filename);return receipts.record(s,r.requestKey,fingerprint,"PHOTO_ADDED",metadata(a));
 }
 public Response read(Long storeId,Long incidentId,Long attachmentId){var s=field.store(storeId,false);var incident=field.incident(s,incidentId);var a=IncidentAttachment.<IncidentAttachment>find("id=?1 and incident=?2",attachmentId,incident).firstResult();if(a==null)throw new NotFoundException();return Response.ok(a.data,a.mediaType).header("Content-Disposition","inline; filename=\""+a.filename+"\"").header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").build();}
}
