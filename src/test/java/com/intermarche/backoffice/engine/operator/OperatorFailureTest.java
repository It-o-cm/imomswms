package com.intermarche.backoffice.engine.operator;

import org.junit.jupiter.api.Test;
import jakarta.persistence.OptimisticLockException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OperatorFailureTest {
 @Test void internalFaultHasSafePublicMessageAndTracking(){var failure=(OperatorFailure)OperatorFailure.safe(new RuntimeException("PRIVATE_SQL_AND_PASSWORD"));assertEquals(503,failure.getResponse().getStatus());var body=(Map<?,?>)failure.getResponse().getEntity();assertNotNull(body.get("trackingId"));assertEquals("SERVICE_UNAVAILABLE",body.get("code"));assertFalse(body.toString().contains("PRIVATE_SQL_AND_PASSWORD"));}
 @Test void nestedCommitConflictRemainsAConflict(){var failure=(OperatorFailure)OperatorFailure.safe(new RuntimeException("transaction rollback",new OptimisticLockException("private details")));assertEquals(409,failure.getResponse().getStatus());assertFalse(failure.getResponse().getEntity().toString().contains("private details"));}
 @Test void businessFailureKeepsItsCode(){var initial=new OperatorFailure(409,"STALE_REVISION","Actualisez");assertSame(initial,OperatorFailure.safe(initial));}
}
