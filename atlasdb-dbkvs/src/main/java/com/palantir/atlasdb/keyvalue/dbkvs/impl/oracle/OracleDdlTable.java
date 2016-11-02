/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.dbkvs.impl.oracle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.dbkvs.OracleDdlConfig;
import com.palantir.atlasdb.keyvalue.dbkvs.OracleTableNameGetter;
import com.palantir.atlasdb.keyvalue.dbkvs.TableMappingNotFoundException;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.ConnectionSupplier;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.DbDdlTable;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.OverflowMigrationState;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.TableSize;
import com.palantir.atlasdb.table.description.TableMetadata;
import com.palantir.exception.PalantirSqlException;
import com.palantir.nexus.db.sql.AgnosticResultSet;
import com.palantir.util.VersionStrings;

public class OracleDdlTable implements DbDdlTable {
    private static final Logger log = LoggerFactory.getLogger(OracleDdlTable.class);
    private static final String MIN_ORACLE_VERSION = "11.2.0.2.3";
    private static final String ORACLE_NOT_EXISTS_ERROR = "ORA-00942";
    private static final String ORACLE_ALREADY_EXISTS_ERROR = "ORA-00955";
    private static final String ORACLE_UNIQUE_CONSTRAINT_ERROR =  "ORA-00001";

    private final TableReference tableRef;
    private final ConnectionSupplier conns;
    private final OracleDdlConfig config;
    private final OracleTableNameGetter oracleTableNameGetter;

    private OracleDdlTable(
            TableReference tableRef,
            ConnectionSupplier conns,
            OracleDdlConfig config,
            OracleTableNameGetter oracleTableNameGetter) {
        this.tableRef = tableRef;
        this.conns = conns;
        this.config = config;
        this.oracleTableNameGetter = oracleTableNameGetter;
    }

    public static OracleDdlTable create(TableReference tableRef, ConnectionSupplier conns, OracleDdlConfig config) {
        OracleTableNameGetter oracleTableNameGetter = new OracleTableNameGetter(conns, config.tablePrefix(),
                config.overflowTablePrefix(), tableRef);
        return new OracleDdlTable(tableRef, conns, config, oracleTableNameGetter);
    }

    @Override
    public void create(byte[] tableMetadata) {
        if (conns.get().selectExistsUnregisteredQuery(
                "SELECT 1 FROM " + config.metadataTable().getQualifiedName() + " WHERE table_name = ?",
                tableRef.getQualifiedName())) {
            return;
        }

        boolean needsOverflow = false;
        TableMetadata metadata = TableMetadata.BYTES_HYDRATOR.hydrateFromBytes(tableMetadata);
        if (metadata != null) {
            needsOverflow = metadata.getColumns().getMaxValueSize() > 2000;
        }

        String shortTableName = oracleTableNameGetter.generateShortTableName();
        executeIgnoringError(
                "CREATE TABLE " + shortTableName + " ("
                + "  row_name   RAW(" + Cell.MAX_NAME_LENGTH + ") NOT NULL,"
                + "  col_name   RAW(" + Cell.MAX_NAME_LENGTH + ") NOT NULL,"
                + "  ts         NUMBER(20) NOT NULL,"
                + "  val        RAW(2000), "
                + (needsOverflow ? "overflow   NUMBER(38), " : "")
                + "  CONSTRAINT " + getPrimaryKeyConstraintName(shortTableName)
                + " PRIMARY KEY (row_name, col_name, ts) "
                + ") organization index compress overflow",
                ORACLE_ALREADY_EXISTS_ERROR);
        putTableNameMapping(oracleTableNameGetter.getPrefixedTableName(), shortTableName);

        if (needsOverflow && config.overflowMigrationState() != OverflowMigrationState.UNSTARTED) {
            final String shortOverflowTableName = oracleTableNameGetter.generateShortOverflowTableName();
            executeIgnoringError(
                    "CREATE TABLE " + shortOverflowTableName + " ("
                    + "  id  NUMBER(38) NOT NULL, "
                    + "  val BLOB NOT NULL,"
                    + "  CONSTRAINT " + getPrimaryKeyConstraintName(shortOverflowTableName) + " PRIMARY KEY (id)"
                    + ")",
                    ORACLE_ALREADY_EXISTS_ERROR);
            putTableNameMapping(oracleTableNameGetter.getPrefixedOverflowTableName(), shortOverflowTableName);
        }

        conns.get().insertOneUnregisteredQuery(
                "INSERT INTO " + config.metadataTable().getQualifiedName() + " (table_name, table_size) VALUES (?, ?)",
                tableRef.getQualifiedName(),
                needsOverflow ? TableSize.OVERFLOW.getId() : TableSize.RAW.getId());
    }

    private void putTableNameMapping(String fullTableName, String shortTableName) {
        try {
            conns.get().insertOneUnregisteredQuery(
                    "INSERT INTO " + AtlasDbConstants.ORACLE_NAME_MAPPING_TABLE
                            + " (table_name, short_table_name) VALUES (?, ?)",
                    fullTableName,
                    shortTableName);
        } catch (PalantirSqlException ex) {
            if (ex.getMessage().contains(ORACLE_UNIQUE_CONSTRAINT_ERROR)) {
                dropTableInternal(fullTableName, shortTableName);
            }
            log.error(ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public void drop() {
        try {
            dropTableInternal(oracleTableNameGetter.getPrefixedTableName(),
                    oracleTableNameGetter.getInternalShortTableName());
            dropTableInternal(oracleTableNameGetter.getPrefixedOverflowTableName(),
                    oracleTableNameGetter.getInternalShortOverflowTableName());
        } catch (TableMappingNotFoundException ex) {
            // If table does not exist, do nothing
        }

        conns.get().executeUnregisteredQuery(
                "DELETE FROM " + config.metadataTable().getQualifiedName() + " WHERE table_name = ?",
                tableRef.getQualifiedName());
    }

    private void dropTableInternal(String fullTableName, String shortTableName) {
        executeIgnoringError("DROP TABLE " + shortTableName + " PURGE", ORACLE_NOT_EXISTS_ERROR);
        conns.get().executeUnregisteredQuery(
                "DELETE FROM " + AtlasDbConstants.ORACLE_NAME_MAPPING_TABLE + " WHERE table_name = ?", fullTableName);
    }

    @Override
    public void truncate() {
        try {
            executeIgnoringError("TRUNCATE TABLE " + oracleTableNameGetter.getInternalShortTableName(),
                    ORACLE_NOT_EXISTS_ERROR);
            executeIgnoringError("TRUNCATE TABLE " + oracleTableNameGetter.getInternalShortOverflowTableName(),
                    ORACLE_NOT_EXISTS_ERROR);
        } catch (TableMappingNotFoundException e) {
            // If table does not exist, do nothing
        }
    }

    @Override
    public void checkDatabaseVersion() {
        AgnosticResultSet result = conns.get().selectResultSetUnregisteredQuery(
                "SELECT version FROM product_component_version where lower(product) like '%oracle%'");
        String version = result.get(0).getString("version");
        if (VersionStrings.compareVersions(version, MIN_ORACLE_VERSION) < 0) {
            log.error("Your key value service currently uses version "
                    + version
                    + " of oracle. The minimum supported version is "
                    + MIN_ORACLE_VERSION
                    + ". If you absolutely need to use an older version of oracle,"
                    + " please contact Palantir support for assistance.");
        }
    }

    private void executeIgnoringError(String sql, String errorToIgnore) {
        try {
            conns.get().executeUnregisteredQuery(sql);
        } catch (PalantirSqlException e) {
            if (!e.getMessage().contains(errorToIgnore)) {
                log.error(e.getMessage(), e);
                throw e;
            }
        }
    }

    @Override
    public void compactInternally() {
        if (config.enableOracleEnterpriseFeatures()) {
            try {
                conns.get().executeUnregisteredQuery(
                        "ALTER TABLE " + oracleTableNameGetter.getInternalShortTableName() + " MOVE ONLINE");
            } catch (PalantirSqlException e) {
                log.error("Tried to clean up " + tableRef + " bloat after a sweep operation,"
                        + " but underlying Oracle database or configuration does not support this"
                        + " (Enterprise Edition that requires this user to be able to perform DDL operations)"
                        + " feature online. Since this can't be automated in your configuration,"
                        + " good practice would be do to occasional offline manual maintenance of rebuilding"
                        + " IOT tables to compensate for bloat. You can contact Palantir Support if you'd"
                        + " like more information. Underlying error was: " + e.getMessage());
            } catch (TableMappingNotFoundException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    private String getPrimaryKeyConstraintName(String tableName) {
        final String primaryKeyConstraintPrefix = "pk_";
        return primaryKeyConstraintPrefix + tableName.substring(primaryKeyConstraintPrefix.length());
    }
}
