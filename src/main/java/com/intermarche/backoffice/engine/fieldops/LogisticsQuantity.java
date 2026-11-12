package com.intermarche.backoffice.engine.fieldops;
import java.math.BigDecimal;import com.intermarche.backoffice.domain.order.ProductType;import jakarta.ws.rs.BadRequestException;
final class LogisticsQuantity {private LogisticsQuantity(){}static BigDecimal positive(BigDecimal q,ProductType type){if(q==null||q.signum()<=0||q.precision()-q.scale()>13||q.stripTrailingZeros().scale()>(type==ProductType.UNIT?0:3))throw new BadRequestException("Quantité positive en pièces entières ou au maximum trois décimales kg/L");return q.stripTrailingZeros();}}
