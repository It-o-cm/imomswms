package com.intermarche.backoffice.imports;

import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.StoreGroup;
import io.quarkus.hibernate.orm.panache.Panache;
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
import java.util.stream.Collectors;

/**
 * REST Endpoint for bulk importing or updating Configurations from a CSV file stream.
 * <p>
 * Expected CSV format (6 columns):
 * Name|Type|Priority|Specification|StoreCodes|GroupCodes
 * <p>
 * Example:
 * Main Config|FULFILLMENT|10|{"key":"val"}|0101,0102|GRP_A
 */
@Path("/configurations/import")
@ApplicationScoped
@RunOnVirtualThread
public class ConfigurationCsvResource extends ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationCsvResource.class);

    /**
     * Imports or updates configurations from a CSV stream.
     *
     * @param inputStream The input stream containing CSV data.
     * @return A Response containing a JSON summary of created/updated counts and errors.
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importConfigurations(InputStream inputStream) {
        // 6 mandatory columns: Name, Type, Priority, Spec, StoreCodes, GroupCodes
        return this.importCsvStream(inputStream, 6);
    }

    /**
     * Implements the chunk processing logic for Configurations.
     *
     * @param parsedLines The list of data for the current chunk.
     * @param targetCodes The set of unique keys (ConfigName).
     * @param counters    An array of size 2 to hold [createdCount, updatedCount].
     * @param errors      List to collect definitive error messages.
     */
    @Override
    public Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        if (parsedLines.isEmpty()) return new HashMap<>();

        // Fetch existing configurations by name
        List<Configuration> existingConfigs = Configuration.list("name IN ?1", targetCodes);
        Map<String, Object> configMap = new HashMap<>();
        for (Configuration c : existingConfigs) {
            configMap.put(c.name, c);
        }
        return configMap;
    }

    /**
     * Implements the specific logic for creating or updating a Configuration entity.
     *
     * @param data       The parsed CSV line data.
     * @param entityMap  The map of existing configurations (Key: Name, Value: Configuration).
     * @param counters   An array of size 2 to hold [createdCount, updatedCount].
     */
    @Override
    public void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String[] parts = data.parts;

        // FORMAT ORDER:
        // 0: Name
        // 1: Type
        // 2: Priority
        // 3: Specification
        // 4: StoreCodes (comma separated)
        // 5: GroupCodes (comma separated)

        String configName = safeGet(parts, 0);
        String configType = safeGet(parts, 1);
        int priority = safeParseInt(parts, 2, 0);
        String specification = safeGet(parts, 3);
        String storeCodes = safeGet(parts, 4);
        String groupCodes = safeGet(parts, 5);

        // Resolve Stores (unknown codes are skipped with a warning)
        List<Store> resolvedStores = new ArrayList<>();
        if (storeCodes != null && !storeCodes.isBlank()) {
            for (String code : storeCodes.split(",")) {
                Store s = Store.findByCode(code.trim());
                if (s != null) resolvedStores.add(s);
                else LOGGER.warn("Store code not found: " + code);
            }
        }

        // Resolve Groups (unknown codes are skipped with a warning)
        List<StoreGroup> resolvedGroups = new ArrayList<>();
        if (groupCodes != null && !groupCodes.isBlank()) {
            for (String code : groupCodes.split(",")) {
                StoreGroup g = StoreGroup.findByCode(code.trim());
                if (g != null) resolvedGroups.add(g);
                else LOGGER.warn("Group code not found: " + code);
            }
        }

        Configuration config = (Configuration) entityMap.get(configName);

        if (config == null) {
            config = new Configuration();
            config.name = configName;
            counters[0]++; // Created
        } else {
            int incomingChecksum = computeIncomingChecksum(configName, configType, specification, priority, resolvedStores, resolvedGroups);
            if (config.checksum == incomingChecksum) {
                return; // No change: skip the line entirely
            }
            // Clear existing links for update
            config.stores.clear();
            config.storeGroups.clear();
            counters[1]++; // Updated
        }

        config.type = configType;
        config.priority = priority;
        config.specification = specification;

        // Link Stores
        for (Store s : resolvedStores) {
            config.stores.add(s);
        }

        // Link Groups
        for (StoreGroup g : resolvedGroups) {
            config.storeGroups.add(g);
        }

        Panache.getEntityManager().persist(config);
    }

    /**
     * Computes the checksum of the incoming CSV data.
     * <p>
     * Replicates {@link Configuration#getChecksum()} logic: the linked stores and
     * groups are hashed as sorted sets of their codes, so the value matches the
     * entity checksum computed from the resolved links.
     *
     * @param name         The configuration name.
     * @param type         The configuration type.
     * @param specification The specification JSON string.
     * @param priority     The priority.
     * @param stores       The resolved stores to link.
     * @param groups       The resolved store groups to link.
     * @return The integer hash of the incoming data.
     */
    private static int computeIncomingChecksum(String name, String type, String specification, int priority, List<Store> stores, List<StoreGroup> groups) {
        Set<String> storeCodes = stores.stream().map(s -> s.code).collect(Collectors.toCollection(TreeSet::new));
        Set<String> groupCodes = groups.stream().map(g -> g.code).collect(Collectors.toCollection(TreeSet::new));
        return Objects.hash(name, type, specification, priority, storeCodes, groupCodes);
    }

    /**
     * Implements the specific logic to find a fresh Configuration from the database.
     *
     * @param data The parsed CSV line data.
     * @return The Configuration entity or null if not found.
     */
    @Override
    public Object findEntityForLine(LineData data) {
        String configName = safeGet(data.parts, 0); // Name is at index 0
        return Configuration.find("name", configName).firstResult();
    }

}