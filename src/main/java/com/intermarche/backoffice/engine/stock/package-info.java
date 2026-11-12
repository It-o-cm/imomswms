/**
 * Temporal stock in product base units and point-of-sale local time, matching the existing domain clock.
 *
 * <p>The immutable physical ledger is separate from expected supplies and outstanding order-line
 * commitments. A projection evaluates every supply and preparation deadline, then takes the minimum
 * of all subsequent balances to compute availability to promise. Margin is applied once per queried
 * scope. Past-due unreceived supplies are excluded until their ETA is revised or they are received.
 *
 * <p>Every writer takes the store's database lock. Stable operation keys make physical adjustments,
 * receipts and preparations idempotent; reuse with a different payload is rejected. Preparing stock
 * consumes the commitment and the physical stock in one transaction. Receiving consumes the expected
 * supply and credits physical stock in one transaction. A final partial receipt closes the shortfall.
 *
 * <p>An external supply has no source; an internal supply has two locations in the same store. Internal
 * relocation is atomic on receipt and never increases store-wide inventory. The projection caps a
 * transfer by the source building's availability, including later commitments. Transport dispatch and
 * in-transit positions are not represented as independent operations in this initial model.
 *
 * <p>The GraphQL API is the integration boundary for POS changes, imported/new order commitments,
 * replenishment plans and actual execution. Existing OMS orders and POS feeds are not automatically
 * inferred from unrelated quantities. A projection returns a protection signal and quantity for the
 * handling orchestrator; it does not itself create or execute a collecting order.
 *
 * <p>The Nutella scenario uses 11:30 consistently (the source text also mentions 11:40). The ledger
 * retains every residual unit: 33 + 60 - 92 = 1, so the final receipt yields 81 physical units before
 * fulfilling the last 3, rather than silently dropping the residual unit in the illustration.
 */
package com.intermarche.backoffice.engine.stock;
