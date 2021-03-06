/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.install;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.dao.util.PsqlDao;
import org.thingsboard.server.dao.util.TimescaleDBTsDao;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;

@Service
@Profile("install")
@Slf4j
@TimescaleDBTsDao
@PsqlDao
public class TimescaleTsDatabaseUpgradeService extends AbstractSqlTsDatabaseUpgradeService implements DatabaseTsUpgradeService {

    @Value("${sql.timescale.chunk_time_interval:86400000}")
    private long chunkTimeInterval;

    private static final String LOAD_FUNCTIONS_SQL = "schema_update_timescale_ts.sql";

    private static final String TENANT_TS_KV_OLD_TABLE = "tenant_ts_kv_old;";

    private static final String CREATE_TS_KV_LATEST_TABLE = "create_ts_kv_latest_table()";
    private static final String CREATE_NEW_TENANT_TS_KV_TABLE = "create_new_tenant_ts_kv_table()";
    private static final String CREATE_TS_KV_DICTIONARY_TABLE = "create_ts_kv_dictionary_table()";
    private static final String INSERT_INTO_DICTIONARY = "insert_into_dictionary()";
    private static final String INSERT_INTO_TENANT_TS_KV = "insert_into_tenant_ts_kv()";
    private static final String INSERT_INTO_TS_KV_LATEST = "insert_into_ts_kv_latest()";

    private static final String CALL_CREATE_TS_KV_LATEST_TABLE = CALL_REGEX + CREATE_TS_KV_LATEST_TABLE;
    private static final String CALL_CREATE_NEW_TENANT_TS_KV_TABLE = CALL_REGEX + CREATE_NEW_TENANT_TS_KV_TABLE;
    private static final String CALL_CREATE_TS_KV_DICTIONARY_TABLE = CALL_REGEX + CREATE_TS_KV_DICTIONARY_TABLE;
    private static final String CALL_INSERT_INTO_DICTIONARY = CALL_REGEX + INSERT_INTO_DICTIONARY;
    private static final String CALL_INSERT_INTO_TS_KV = CALL_REGEX + INSERT_INTO_TENANT_TS_KV;
    private static final String CALL_INSERT_INTO_TS_KV_LATEST = CALL_REGEX + INSERT_INTO_TS_KV_LATEST;

    private static final String DROP_OLD_TENANT_TS_KV_TABLE = DROP_TABLE + TENANT_TS_KV_OLD_TABLE;

    private static final String DROP_FUNCTION_CREATE_TS_KV_LATEST_TABLE = DROP_FUNCTION_IF_EXISTS + CREATE_TS_KV_LATEST_TABLE;
    private static final String DROP_FUNCTION_CREATE_TENANT_TS_KV_TABLE_COPY = DROP_FUNCTION_IF_EXISTS + CREATE_NEW_TENANT_TS_KV_TABLE;
    private static final String DROP_FUNCTION_CREATE_TS_KV_DICTIONARY_TABLE = DROP_FUNCTION_IF_EXISTS + CREATE_TS_KV_DICTIONARY_TABLE;
    private static final String DROP_FUNCTION_INSERT_INTO_DICTIONARY = DROP_FUNCTION_IF_EXISTS + INSERT_INTO_DICTIONARY;
    private static final String DROP_FUNCTION_INSERT_INTO_TENANT_TS_KV = DROP_FUNCTION_IF_EXISTS + INSERT_INTO_TENANT_TS_KV;
    private static final String DROP_FUNCTION_INSERT_INTO_TS_KV_LATEST = DROP_FUNCTION_IF_EXISTS + INSERT_INTO_TS_KV_LATEST;

    @Autowired
    private InstallScripts installScripts;

    @Override
    public void upgradeDatabase(String fromVersion) throws Exception {
        switch (fromVersion) {
            case "2.4.3":
                try (Connection conn = DriverManager.getConnection(dbUrl, dbUserName, dbPassword)) {
                    log.info("Updating timescale schema ...");
                    log.info("Load upgrade functions ...");
                    loadSql(conn);
                    boolean versionValid = checkVersion(conn);
                    if (!versionValid) {
                        log.info("PostgreSQL version should be at least more than 9.6!");
                        log.info("Please upgrade your PostgreSQL and restart the script!");
                    } else {
                        log.info("PostgreSQL version is valid!");
                        log.info("Updating schema ...");
                        executeFunction(conn, CALL_CREATE_TS_KV_LATEST_TABLE);
                        executeFunction(conn, CALL_CREATE_NEW_TENANT_TS_KV_TABLE);

                        executeQuery(conn, "SELECT create_hypertable('tenant_ts_kv', 'ts', chunk_time_interval => " + chunkTimeInterval + ", if_not_exists => true);");

                        executeFunction(conn, CALL_CREATE_TS_KV_DICTIONARY_TABLE);
                        executeFunction(conn, CALL_INSERT_INTO_DICTIONARY);
                        executeFunction(conn, CALL_INSERT_INTO_TS_KV);
                        executeFunction(conn, CALL_INSERT_INTO_TS_KV_LATEST);

                        //executeQuery(conn, "SELECT set_chunk_time_interval('tenant_ts_kv', " + chunkTimeInterval +");");

                        executeDropStatement(conn, DROP_OLD_TENANT_TS_KV_TABLE);

                        executeDropStatement(conn, DROP_FUNCTION_CREATE_TS_KV_LATEST_TABLE);
                        executeDropStatement(conn, DROP_FUNCTION_CREATE_TENANT_TS_KV_TABLE_COPY);
                        executeDropStatement(conn, DROP_FUNCTION_CREATE_TS_KV_DICTIONARY_TABLE);
                        executeDropStatement(conn, DROP_FUNCTION_INSERT_INTO_DICTIONARY);
                        executeDropStatement(conn, DROP_FUNCTION_INSERT_INTO_TENANT_TS_KV);
                        executeDropStatement(conn, DROP_FUNCTION_INSERT_INTO_TS_KV_LATEST);

                        log.info("schema timeseries updated!");
                    }
                }
                break;
            default:
                throw new RuntimeException("Unable to upgrade SQL database, unsupported fromVersion: " + fromVersion);
        }
    }

    protected void loadSql(Connection conn) {
        Path schemaUpdateFile = Paths.get(installScripts.getDataDir(), "upgrade", "2.4.3", LOAD_FUNCTIONS_SQL);
        try {
            loadFunctions(schemaUpdateFile, conn);
            log.info("Upgrade functions successfully loaded!");
        } catch (Exception e) {
            log.info("Failed to load Timescale upgrade functions due to: {}", e.getMessage());
        }
    }
}