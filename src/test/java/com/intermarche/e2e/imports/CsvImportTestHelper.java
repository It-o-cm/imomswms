package com.intermarche.e2e.imports;

import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.backoffice.domain.order.ProductFamily;
import com.intermarche.backoffice.domain.store.Aisle;
import com.intermarche.backoffice.domain.store.AisleWaypoint;
import com.intermarche.backoffice.domain.store.Buffer;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.backoffice.domain.store.PlanNode;
import com.intermarche.backoffice.domain.store.PlanZone;
import com.intermarche.backoffice.domain.store.PlanZoneVertex;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.domain.store.WMSStoreHandle;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.util.function.Supplier;

/**
 * Shared test utilities for the CSV import test classes.
 * <p>
 * All import endpoints commit their data to the shared in-memory H2 database
 * (the import runs in its own transactions). Test classes must therefore clean
 * up not only before each test but also after each test, so that classes with
 * a partial cleanup (e.g. only {@code Store.deleteAll()}) never hit foreign key
 * violations from leftover rows of another class.
 * <p>
 * The cleanup also removes rows created by the background consumers
 * ({@link WMSStoreHandle} and {@link Event}), which reference committed stores
 * and would otherwise block {@code Store.deleteAll()}.
 */
public final class CsvImportTestHelper {

    private CsvImportTestHelper() {
    }

    /**
     * Executes the given logic within a manual JTA transaction.
     * <p>
     * Unlike a naive helper, this one always rolls back the transaction on
     * failure, so a failing cleanup never leaves a dangling transaction that
     * would break subsequent tests with {@code NotSupportedException}.
     *
     * @param tm       The transaction manager.
     * @param runnable The logic to execute.
     * @param <R>      The return type of the logic.
     * @return The result of the execution.
     */
    public static <R> R withTransaction(TransactionManager tm, Supplier<R> runnable) {
        try {
            tm.begin();
            R result = runnable.get();
            tm.commit();
            return result;
        } catch (Throwable t) {
            rollbackQuietly(tm, t);
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }

    /**
     * Rolls back the current transaction if one is still active, suppressing
     * any rollback error onto the original exception.
     *
     * @param tm The transaction manager.
     * @param t  The original exception.
     */
    private static void rollbackQuietly(TransactionManager tm, Throwable t) {
        try {
            if (tm.getStatus() != Status.STATUS_NO_TRANSACTION) {
                tm.rollback();
            }
        } catch (SystemException ex) {
            t.addSuppressed(ex);
        }
    }

    /**
     * Cleans all tables that can hold committed rows referencing Stores or Products.
     * <p>
     * Deletion order respects foreign keys (children first):
     * <ol>
     *   <li>{@link Event} and {@link WMSStoreHandle} (created by background consumers, FK to Store)</li>
     *   <li>{@link Tote} and {@link Employee} (FK to Store)</li>
     *   <li>{@link AisleWaypoint} (FK to Aisle; bulk deletes do not cascade)</li>
     *   <li>{@link Aisle} and {@link Buffer} (FK to Building)</li>
     *   <li>{@link PlanNode} (referenced by Aisle head/tail, FK to Building)</li>
     *   <li>{@link PlanZoneVertex} (FK to PlanZone; bulk deletes do not cascade)</li>
     *   <li>{@link PlanZone} (FK to Building)</li>
     *   <li>Locations (native delete, {@code item_locations} is a single-table hierarchy)</li>
     *   <li>Buildings (native delete, abstract entity + {@code store_id} FK column)</li>
     *   <li>{@link Configuration} and {@link StoreGroup} (ManyToMany join tables to Store)</li>
     *   <li>{@link ProductFamily} (ManyToMany join table to Product + self FK)</li>
     *   <li>{@link Product}</li>
     *   <li>{@link Store}</li>
     * </ol>
     *
     * @param tm The transaction manager.
     */
    public static void cleanDatabase(TransactionManager tm) {
        withTransaction(tm, () -> {
            Event.deleteAll();
            WMSStoreHandle.deleteAll();
            Tote.deleteAll();
            Employee.deleteAll();
            AisleWaypoint.deleteAll();
            Aisle.deleteAll();
            PlanNode.deleteAll();
            PlanZoneVertex.deleteAll();
            PlanZone.deleteAll();
            Buffer.deleteAll();
            Panache.getEntityManager().createNativeQuery("DELETE FROM item_locations").executeUpdate();
            Panache.getEntityManager().createNativeQuery("DELETE FROM buildings").executeUpdate();
            Configuration.deleteAll();
            StoreGroup.deleteAll();
            ProductFamily.deleteAll();
            Product.deleteAll();
            Store.deleteAll();
            return null;
        });
    }
}
