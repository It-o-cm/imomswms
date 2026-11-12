package com.intermarche.backoffice.engine.executing;

import com.intermarche.backoffice.domain.store.TemperatureZone;
import java.math.BigDecimal;

/** Une clé identifie exactement une intention. Les données métier restent les DTO et enums existants. */
public class OperatorCommandInput {
    /** Omitted means legacy v1; v2 requires the revision(s) returned by the order read. */
    public Integer contractVersion;
    public java.util.List<String> finalScans;
    public String scanId,depositScanCode;
    public com.intermarche.backoffice.engine.catalog.ProductMeasure.Input measurement;
    public String expectedRevision;
    public java.util.Map<Long,String> expectedRevisions;
    public Long sourceLocationId,stockSegmentId,manufacturerLotId,serialId;
    public Long storeId,orderId,executionId,itemId,shortageId,productId,bufferId,cabinetId;
    public Integer cellRow,cellColumn;
    public String requestKey,action,toteCode,targetToteCode,reason;
    public BigDecimal quantity,expectedQuantity;
    public TemperatureZone usage;
    public com.intermarche.backoffice.engine.fieldops.InventoryCountingService.CountInput inventory;
    public com.intermarche.backoffice.engine.replenishment.ReceiptDiscrepancyResource.Input deliveryDiscrepancy;
    public HandlingOrderService.BatchValidationRequest report;
    public com.intermarche.backoffice.engine.replenishment.ReplenishmentResource.Receipt receipt;
    public com.intermarche.backoffice.engine.replenishment.ReplenishmentResource.ReopenRequest reopen;
}
