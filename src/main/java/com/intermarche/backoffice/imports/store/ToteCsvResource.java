package com.intermarche.backoffice.imports.store;

import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Tote;
import com.intermarche.backoffice.imports.ImporterCsvResource;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;

/**
 * REST Endpoint for bulk importing or updating Totes.
 * <p>
 * Expected CSV format (4 columns):
 * Code, StoreCode, Volume, ToteType
 */
@Path("/totes/import")
@ApplicationScoped
@RunOnVirtualThread
public class ToteCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ToteCsvResource.class);

    /**
     * Imports or updates totes from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importTotes(InputStream inputStream) {
        // 4 columns expected
        return this.importCsvStream(inputStream, 4);
    }

    /**
     * Prepares the context for a chunk.
     * Fetches existing Totes and referenced Stores.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> context = new HashMap<>();

        // 1. Fetch existing Totes by Code
        List<Tote> totes = Tote.list("code IN ?1", targetCodes);
        for (Tote t : totes) {
            context.put(t.code, t);
        }

        // 2. Fetch referenced Stores
        Set<String> storeCodes = new HashSet<>();
        for (LineData d : parsedLines) {
            String storeCode = safeGet(d.parts, 1);
            if (storeCode != null) storeCodes.add(storeCode);
        }

        if (!storeCodes.isEmpty()) {
            List<Store> stores = Store.list("code IN ?1", storeCodes);
            for (Store s : stores) {
                context.put("STORE_" + s.code, s);
            }
        }

        return context;
    }

    /**
     * Processes a single line to create or update a Tote.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;
        String code = data.code; // Col 0
        String storeCode = safeGet(parts, 1); // Col 1

        // 1. Resolve Store
        Store store = (Store) entityMap.get("STORE_" + storeCode);
        if (store == null) {
            LOGGER.error("Store with code " + storeCode + " not found for tote " + code);
            throw new IllegalArgumentException("Store with code '" + storeCode + "' not found.");
        }

        // 2. Resolve Tote
        Tote tote = (Tote) entityMap.get(code);
        boolean isUpdate = (tote != null);

        if (!isUpdate) {
            // Creation
            tote = new Tote();
            tote.code = code;
            tote.store = store; // Attach store immediately
            populateTote(parts, tote);
            tote.persist();
            entityMap.put(code, tote);
            counters[0]++; // Created
        } else {
            // Update Check
            int incomingChecksum = computeChecksum(parts, code, store.id);
            if (tote.checksum == incomingChecksum) {
                return; // No change needed
            }
            counters[1]++; // Updated
            tote.store = store; // Update store in case it changed
            populateTote(parts, tote);
        }
    }

    /**
     * Finds a single Tote entity for fallback processing.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Tote.findByCode(data.code);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Populates the Tote entity from CSV parts.
     */
    private void populateTote(String[] parts, Tote tote) {
        // Col 2: Volume
        tote.volume = safeParseBigDecimal(parts, 2);

        // Col 3: ToteType
        String typeStr = safeGet(parts, 3);
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                tote.toteType = Tote.ToteType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Unknown ToteType '" + typeStr + "' for tote " + tote.code);
            }
        }
    }

    /**
     * Computes checksum for validation.
     */
    private int computeChecksum(String[] parts, String code, Long storeId) {
        String typeStr = safeGet(parts, 3);
        Object typeObj = typeStr;
        try {
            if (typeStr != null) {
                typeObj = Tote.ToteType.valueOf(typeStr.toUpperCase());
            }
        } catch (IllegalArgumentException e) {
            // Keep string if invalid enum
        }

        return Objects.hash(
                code,                    // code
                safeParseBigDecimal(parts, 2), // volume
                typeObj,                 // toteType
                storeId                  // store.id
        );
    }
}