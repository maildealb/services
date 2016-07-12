/*
 * Copyright (C) 2014 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.sync.service.logic;

import android.os.RemoteException;

import org.opendatakit.aggregate.odktables.rest.ElementDataType;
import org.opendatakit.aggregate.odktables.rest.KeyValueStoreConstants;
import org.opendatakit.aggregate.odktables.rest.entity.Column;
import org.opendatakit.aggregate.odktables.rest.entity.TableDefinitionResource;
import org.opendatakit.aggregate.odktables.rest.entity.TableResource;
import org.opendatakit.aggregate.odktables.rest.entity.TableResourceList;
import org.opendatakit.common.android.data.ColumnList;
import org.opendatakit.common.android.data.OrderedColumns;
import org.opendatakit.common.android.data.TableDefinitionEntry;
import org.opendatakit.common.android.provider.FormsColumns;
import org.opendatakit.common.android.utilities.ODKFileUtils;
import org.opendatakit.common.android.utilities.PropertiesFileUtils;
import org.opendatakit.common.android.utilities.WebLogger;
import org.opendatakit.common.android.utilities.WebLoggerIf;
import org.opendatakit.database.service.KeyValueStoreEntry;
import org.opendatakit.database.service.OdkDbHandle;
import org.opendatakit.services.R;
import org.opendatakit.sync.service.*;
import org.opendatakit.sync.service.exceptions.SchemaMismatchException;
import org.opendatakit.sync.service.logic.Synchronizer.OnTablePropertiesChanged;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Isolate the app-level and table-level synchronization steps
 * into this class, reducing the size of the original SyncProcessor.
 * 
 * @author mitchellsundt@gmail.com
 *
 */
public class ProcessAppAndTableLevelChanges {

  private static final String TAG = ProcessAppAndTableLevelChanges.class.getSimpleName();

  private WebLoggerIf log;
  
  private SyncExecutionContext sc;
  private ProcessManifestContentAndFileChanges manifestProcessor;

  public ProcessAppAndTableLevelChanges(SyncExecutionContext sc) {
    this.sc = sc;
    this.log = WebLogger.getLogger(sc.getAppName());
    this.manifestProcessor = new ProcessManifestContentAndFileChanges(sc);
  }

  private class TablePropertiesReloader implements OnTablePropertiesChanged {
    Exception reloadingException = null;

    @Override
    public void onTablePropertiesChanged(String tableId) {
      try {
        PropertiesFileUtils.DataTableDefinition dtd =
            PropertiesFileUtils.readPropertiesFromCsv(sc.getAppName(), tableId);

        // Go through the KVS list and replace all the choiceList entries with their choiceListId
        for ( KeyValueStoreEntry entry : dtd.kvsEntries ) {
          if ( entry.partition.equals(KeyValueStoreConstants.PARTITION_COLUMN) &&
              entry.key.equals(KeyValueStoreConstants.COLUMN_DISPLAY_CHOICES_LIST) ) {
            // stored type is a string -- the choiceListId
            entry.type = ElementDataType.string.name();
            if ((entry.value != null) && (entry.value.trim().length() != 0)) {
              String choiceListId = sc.getDatabaseService().setChoiceList(sc.getAppName(),
                  sc.getDatabase(), entry.value);
              entry.value = choiceListId;
            } else {
              entry.value = null;
            }
          }
        }

        sc.getDatabaseService().createOrOpenDBTableWithColumnsAndProperties(sc.getAppName(),
            sc.getDatabase(), tableId, dtd.columnList, dtd.kvsEntries, true);
      } catch (Exception e) {
        this.reloadingException = e;
      }
    }
  };

  public void verifyServerConfiguration() throws RemoteException {
    log.i(TAG, "entered verifyServerConfiguration()");

    if (OdkSyncService.possiblyWaitForSyncServiceDebugger()) {
      log.i(TAG, "running under debugger: verifyServerConfiguration()");
    }

    sc.updateNotification(SyncProgressState.STARTING,
            R.string.sync_verifying_app_name_on_server, null, 0.0, false);

    // fail if the server is not configured for this appName.
    // we test this before updating privileges to reduce the likelihood
    // that a screwed-up server settings will cause the user's privileges
    // to be downgraded to not_verified status.
    try {
      sc.getSynchronizer().verifyServerSupportsAppName();
    } catch (Exception e) {
      log.e(TAG,
              "[verifyServerConfiguration] exception verifying support of appName exception: "
                      + e.toString());
      sc.setAppLevelSyncOutcome(sc.exceptionEquivalentOutcome(e));
      return;
    }

    // TODO: clear existing privileges

    sc.updateNotification(SyncProgressState.STARTING,
            R.string.sync_obtaining_user_permissions_from_server, null, 0.0, false);

    ArrayList<String> roleList;
    try {
      roleList = sc.getSynchronizer().getUserRoles();
    } catch (Exception e) {
      log.e(TAG,
              "[verifyServerConfiguration] exception obtaining user roles exception: "
                      + e.toString());
      sc.setAppLevelSyncOutcome(sc.exceptionEquivalentOutcome(e));
      return;
    }

    // TODO: store reported privileges

    StringBuilder b = new StringBuilder();
    boolean first = true;
    for ( String role : roleList ) {
      if ( !first ) {
        b.append(", ");
      }
      first = false;
      b.append(role);
    }
    log.i(TAG, "[verifyServerConfiguration] roles from server: " + b.toString());

    // don't set app-level outcome -- indicating we have no errors
    return;
  }

  /**
   * Synchronize all app-level files and all data table schemas and table-level
   * files.
   *
   * This synchronization sets the stage for data row synchronization. The two
   * modes are either to pull all this configuration down from the server and
   * enforce that the client contain all files and tables on the server or to
   * enforce that the server contains all files and tables that are on the
   * client.
   *
   * When pulling down (the normal mode of operation), we reload the local
   * properties from the tables/tableId/properties.csv that has been pulled down
   * from the server.
   *
   * This does not process zip files; it is unclear whether we should do
   * anything for those or just leave them as zip files locally.
   * @throws RemoteException 
   */
  public List<TableResource> synchronizeConfigurationAndContent(boolean pushToServer) throws RemoteException {
    log.i(TAG, "entered synchronizeConfigurationAndContent()");

    boolean issueDeletes = false;
    if (OdkSyncService.possiblyWaitForSyncServiceDebugger()) {
      issueDeletes = true;
    }

    /**
     * Verify that the server configuration is good and
     * update this user's permissions to correspond to those on that server.
     */
    verifyServerConfiguration();

    // if the verification failed, it will have set the app-level outcome.
    // otherwise, the outcome will remain in the WORKING state and we can proceed.
    if ( sc.getAppLevelSyncOutcome() != SyncOutcome.WORKING ) {
      return new ArrayList<>();
    }

    // Everything was successful-enough to warrant deleting any sync
    // ETags from syncing to a different server. This ensures that we
    // only ever have the sync etags from the current server in case
    // the user is switching servers for some reason.
    sc.getSynchronizer().deleteAllSyncETagsExceptForCurrentServer();

    sc.updateNotification(SyncProgressState.STARTING,
            R.string.sync_retrieving_tables_list_from_server, null, 0.0, false);

    // working list of tables -- the list we will construct and return...
    List<TableResource> workingListOfTables = new ArrayList<TableResource>();

    // get tables from server
    TableResourceList tableList;
    List<TableResource> tables = new ArrayList<TableResource>();
    // For now, repeatedly do this until we get all of the tables on the server.
    // This will likely need to change if we actually have 1000's of them...
    String webSafeResumeCursor = null;
    for (;;) {
      try {
        tableList = sc.getSynchronizer().getTables(webSafeResumeCursor);
        if (tableList != null && tableList.getTables() != null) {
          tables.addAll(tableList.getTables());
        }
      } catch (Exception e) {
        log.e(TAG,
            "[synchronizeConfigurationAndContent] exception getting server table list exception: "
                + e.toString());
        sc.setAppLevelSyncOutcome(sc.exceptionEquivalentOutcome(e));
        return new ArrayList<TableResource>();
      }
      if ( !tableList.isHasMoreResults() ) {
        break;
      }
      webSafeResumeCursor = tableList.getWebSafeResumeCursor();
    }

    // get the tables on the local device
    List<String> localTableIds;
    OdkDbHandle db = null;
    try {
      db = sc.getDatabase();
      localTableIds = sc.getDatabaseService().getAllTableIds(sc.getAppName(), db);
    } catch (Exception e) {
      sc.setAppLevelSyncOutcome(sc.exceptionEquivalentOutcome(e));
      log.e(TAG,
          "[synchronizeConfigurationAndContent] exception getting local tableId list exception: "
              + e.toString());
      return new ArrayList<TableResource>();
    } finally {
      if ( db != null ) {
        try {
          sc.releaseDatabase(db);
        } finally {
          db = null;
        }
      }
    }

    // Fail if we are pushing up to the server and we have no local tables
    // (i.e., the device is "clean").
    if ( pushToServer && (localTableIds.isEmpty() ||
         (localTableIds.size() == 1 && localTableIds.contains(FormsColumns.COMMON_BASE_FORM_ID)) )) {
      sc.setAppLevelSyncOutcome(SyncOutcome.NO_LOCAL_TABLES_TO_RESET_ON_SERVER);
      log.e(TAG,
          "[synchronizeConfigurationAndContent] push to server from device without any table "
              + "definitions");
      return new ArrayList<TableResource>();
    }

    // Fail if we are syncing (pulling) and there are no tables on the server
    if ( !pushToServer && tables.isEmpty() ) {
      sc.setAppLevelSyncOutcome(SyncOutcome.NO_TABLES_ON_SERVER_TO_SYNC);
      log.e(TAG,
          "[synchronizeConfigurationAndContent] server has no tables -- did you mean to reset the server?");
      return new ArrayList<TableResource>();
    }

    // Figure out how many major steps there are to the sync
    {
      Set<String> uniqueTableIds = new HashSet<String>();
      uniqueTableIds.addAll(localTableIds);
      for (TableResource table : tables) {
        uniqueTableIds.add(table.getTableId());
      }
      // when pushing, we never drop tables on the server (but never pull those
      // either).
      // i.e., pushing only adds to the set of tables on the server.
      //
      // when pulling, we drop all local tables that do not match the server,
      // and pull
      // everything from the server.
      int nMajorSyncSteps = 1 + (pushToServer ? 2 * localTableIds.size()
          : (uniqueTableIds.size() + tables.size()));
      
      sc.resetMajorSyncSteps(nMajorSyncSteps);
    }

    // TODO: fix sync sequence
    // TODO: fix sync sequence
    // TODO: fix sync sequence
    // TODO: fix sync sequence
    // TODO: fix sync sequence
    // TODO: fix sync sequence
    // Intermediate deployment failures can leave the client in a bad state.
    // The actual sync protocol should probably be:
    //
    // (1) pull down all the table-id level file changes and new files
    // (2) pull down all the app-level file changes and new files
    // (3) delete the table-id level files locally
    // (4) delete the app-level files locally
    //
    // We also probably want some critical files to be pulled last. e.g.,
    // config/tables/tableid/index.html , config/assets/index.html ?
    // so that we know that all supporting files are present before we
    // update these files.
    //
    // As long as form changes are done via completely new form ids, and
    // push as new form id files, this enables the sync to pull the new forms,
    // then presumably the table-level files would control the launching of
    // those forms, and the app-level files would launch the table-level files
    //

    // First we're going to synchronize the app level files.
    try {
      manifestProcessor.syncAppLevelFiles(pushToServer, tableList.getAppLevelManifestETag(), sc);
      sc.setAppLevelSyncOutcome(SyncOutcome.SUCCESS);
    } catch (Exception e) {
      // TODO: update a synchronization result to report back to them as well.
      log.e(TAG,
          "[synchronizeConfigurationAndContent] exception while trying to synchronize app-level files.");
      sc.setAppLevelSyncOutcome(sc.exceptionEquivalentOutcome(e));
      return new ArrayList<TableResource>();
    }

    // done with app-level file synchronization
    sc.incMajorSyncStep();

    if (pushToServer) {
      Set<TableResource> serverTablesToDelete = new HashSet<TableResource>();
      serverTablesToDelete.addAll(tables);
      // ///////////////////////////////////////////
      // / UPDATE SERVER CONTENT
      // / UPDATE SERVER CONTENT
      // / UPDATE SERVER CONTENT
      // / UPDATE SERVER CONTENT
      // / UPDATE SERVER CONTENT
      for (String localTableId : localTableIds) {
        TableResource matchingResource = null;
        for (TableResource tr : tables) {
          if (tr.getTableId().equals(localTableId)) {
            matchingResource = tr;
            break;
          }
        }
        log.i(TAG, "[synchronizeConfigurationAndContent] synchronizing table " + localTableId);

        // do not sync the framework table
        if (!localTableId.equals(FormsColumns.COMMON_BASE_FORM_ID)) {
          TableDefinitionEntry entry;
          OrderedColumns orderedDefns;
          try {
            db = sc.getDatabase();
            entry = sc.getDatabaseService().getTableDefinitionEntry(sc.getAppName(), db, localTableId);
            orderedDefns = sc.getDatabaseService().getUserDefinedColumns(sc.getAppName(), db, localTableId);
          } finally {
            try {
              sc.releaseDatabase(db);
            } finally {
              db = null;
            }
          }

          if (matchingResource != null) {
            serverTablesToDelete.remove(matchingResource);
          }

          TableResource updatedResource = synchronizeTableConfigurationAndContent(entry,
              orderedDefns, matchingResource, true);
          if (updatedResource != null) {
            // there were no errors sync'ing the table-level info.
            // allow client to sync instance-level data...
            workingListOfTables.add(updatedResource);
          }
        }

        sc.updateNotification(SyncProgressState.TABLE_FILES,
            R.string.sync_table_level_file_sync_complete, new Object[] { localTableId }, 100.0,
            false);
        sc.incMajorSyncStep();
      }

      // TODO: make this configurable?
      // Generally should not allow this, as it is very dangerous
      // delete any other tables
      if (issueDeletes) {
        for (TableResource tableToDelete : serverTablesToDelete) {
          try {
            sc.getSynchronizer().deleteTable(tableToDelete);
          } catch (Exception e) {
            log.e(TAG,
                "[synchronizeConfigurationAndContent] exception while trying to delete tables.");
            sc.setAppLevelSyncOutcome(sc.exceptionEquivalentOutcome(e));
            return new ArrayList<TableResource>();
          }
        }
      }
    } else {
      // //////////////////////////////////////////
      // MIMIC SERVER CONTENT
      // MIMIC SERVER CONTENT
      // MIMIC SERVER CONTENT
      // MIMIC SERVER CONTENT
      // MIMIC SERVER CONTENT
      // MIMIC SERVER CONTENT

      List<String> localTableIdsToDelete = new ArrayList<String>();
      localTableIdsToDelete.addAll(localTableIds);
      // do not remove the framework table
      localTableIdsToDelete.remove(FormsColumns.COMMON_BASE_FORM_ID);

      boolean firstTime = true;
      for (TableResource table : tables) {
        if ( !firstTime ) {
          sc.incMajorSyncStep();
        }
        firstTime = false;

        OrderedColumns orderedDefns = null;

        String serverTableId = table.getTableId();

        TableLevelResult tableLevelResult = sc.getTableLevelResult(serverTableId);

        boolean doesNotExistLocally = true;
        boolean isLocalMatch = false;
        TableDefinitionEntry entry = null;

        if (localTableIds.contains(serverTableId)) {
          localTableIdsToDelete.remove(serverTableId);
          doesNotExistLocally = false;

          sc.updateNotification(SyncProgressState.TABLE_FILES,
              R.string.sync_verifying_table_schema_on_server, new Object[] { serverTableId },
              0.0,
              false);

          // see if the schemaETag matches. If so, we can skip a lot of steps...
          // no need to verify schema match -- just sync files...
          try {
            db = sc.getDatabase();
            entry = sc.getDatabaseService().getTableDefinitionEntry(sc.getAppName(), db, serverTableId);
            orderedDefns = sc.getDatabaseService().getUserDefinedColumns(sc.getAppName(), db, serverTableId);
            if (table.getSchemaETag().equals(entry.getSchemaETag())) {
              isLocalMatch = true;
            }
          } catch (Exception e) {
            exception("synchronizeConfigurationAndContent - database exception", serverTableId, e, tableLevelResult);
            continue;
          } finally {
            if ( db != null ) {
              try {
                sc.releaseDatabase(db);
              } finally {
                db = null;
              }
            }
          }
        }

        if (!isLocalMatch) {
          sc.updateNotification(SyncProgressState.TABLE_FILES,
              R.string.sync_creating_local_table, new Object[] { serverTableId },
              0.0,
              false);

          try {
            TableDefinitionResource definitionResource = sc.getSynchronizer().getTableDefinition(table
                .getDefinitionUri());

            try {
              db = sc.getDatabase();
              orderedDefns = addTableFromDefinitionResource(db, definitionResource, doesNotExistLocally);
              // NOTE: get entry after addTableFromDefinitionResource() because that may update tableSchemaETag
              entry = sc.getDatabaseService().getTableDefinitionEntry(sc.getAppName(), db, serverTableId);
            } finally {
              if (db != null) {
                try {
                  sc.releaseDatabase(db);
                } finally {
                  db = null;
                }
              }
            }
          } catch (Exception e) {
            exception("synchronizeConfigurationAndContent - exception while verifying table definition",
                serverTableId, e, tableLevelResult);
            continue;
          }
        }

        // only sync the files if we don't yet have a failure on this tableId
        if ( tableLevelResult.getSyncOutcome().equals(SyncOutcome.WORKING) ) {
          // Sync the local media files with the server if the table
          // existed locally before we attempted downloading it.

          TableResource updatedResource = synchronizeTableConfigurationAndContent(entry,
              orderedDefns, table, false);
          if (updatedResource != null) {
            // there were no errors sync'ing the table-level info.
            // allow client to sync instance-level data...
            workingListOfTables.add(updatedResource);
          }
          sc.updateNotification(SyncProgressState.TABLE_FILES,
              R.string.sync_table_level_file_sync_complete, new Object[] { serverTableId }, 100.0,
              false);
        } else {
          // otherwise leave our local configuration. We need to resolve earlier failures first.
          sc.updateNotification(SyncProgressState.TABLE_FILES,
              R.string.sync_table_level_sync_failed, new Object[] { serverTableId }, 100.0,
              false);
        }
      }
      sc.incMajorSyncStep();

      // and now loop through the ones to delete...
      for (String localTableId : localTableIdsToDelete) {
        sc.updateNotification(SyncProgressState.TABLE_FILES, R.string.sync_dropping_local_table,
            new Object[] { localTableId }, 0.0, false);
        // eventually might not be true if there are multiple syncs running
        // simultaneously...
        TableLevelResult tableLevelResult = sc.getTableLevelResult(localTableId);
        boolean tableLevelDeleteSuccess = false;
        try {
          db = sc.getDatabase();
          // this is an atomic action -- no need to gain transaction before invoking it
          sc.getDatabaseService().deleteDBTableAndAllData(sc.getAppName(), db, localTableId);
          tableLevelDeleteSuccess = true;
        } catch (Exception e) {
          exception("synchronizeConfigurationAndContent - exception while deleting table definition",
              localTableId, e, tableLevelResult);
        } finally {
          if ( tableLevelDeleteSuccess ) {
            // we will never process anything on this again, so we
            // should transition to SUCCESS if we deleted it successfully.
            tableLevelResult.setSyncOutcome(SyncOutcome.SUCCESS);
          }
          if (db != null) {
            try {
              sc.releaseDatabase(db);
            } finally {
              db = null;
            }
          }
        }
        sc.incMajorSyncStep();
      }
    }
    // be sure we sort them alphabetically...
    Collections.sort(workingListOfTables);
    return workingListOfTables;
  }

  /**
   * Synchronize the table represented by the given TableProperties with the
   * cloud.
   * <p>
   * Note that if the db changes under you when calling this method, the tp
   * parameter will become out of date. It should be refreshed after calling
   * this method.
   * <p>
   * This method does NOT synchronize the framework files. The management of the
   * contents of the framework directory is managed by the individual APKs
   * themselves.
   *
   * @param tp
   *          the table to synchronize
   * @param pushLocalTableLevelFiles
   *          true if local table-level files should be pushed up to the server.
   *          e.g. any html files on the device should be pushed to the server
   * @param pushLocalInstanceFiles
   *          if local media files associated with data rows should be pushed up
   *          to the server. The data files on the server are always pulled
   *          down.
   * @return null if there is an error, otherwise a new or updated table
   *         resource
   * @throws RemoteException 
   * @throws InvalidAuthTokenException 
   * @throws ClientWebException 
   */
   /**
    *
    * @param tde the table to synchronize
    * @param orderedDefns the user-defined columns in the table
    * @param resource the structure returned from the server
    * @param pushLocalTableLevelFiles
    * @return
    * @throws RemoteException
    */
  private TableResource synchronizeTableConfigurationAndContent(TableDefinitionEntry tde,
      OrderedColumns orderedDefns, TableResource resource,
      boolean pushLocalTableLevelFiles) throws RemoteException {

    // used to get the above from the ACTIVE store. if things go wonky, maybe
    // check to see if it was ACTIVE rather than SERVER for a reason. can't
    // think of one. one thing is that if it fails you'll see a table but won't
    // be able to open it, as there won't be any KVS stuff appropriate for it.
    boolean success = false;
    // Prepare the tableLevelResult. We'll start it as failure, and only update it
    // if we're successful at the end.

    String tableId = tde.getTableId();

    sc.updateNotification(SyncProgressState.TABLE_FILES,
        R.string.sync_verifying_table_schema_on_server, new Object[] { tableId }, 0.0, false);
    final TableLevelResult tableLevelResult = sc.getTableLevelResult(tableId);
    String displayName;
    displayName = sc.getTableDisplayName(tableId);
    tableLevelResult.setTableDisplayName(displayName);

    OdkDbHandle db = null;
    try {
      String schemaETag = tde.getSchemaETag();

      if (resource == null) {
        // exists locally but not on server...

        if (!pushLocalTableLevelFiles) {
          throw new IllegalStateException("This code path should no longer be followed!");
        }

        // the insert of the table was incomplete -- try again
        sc.getSynchronizer().updateTableSchemaETagAndPurgePotentiallyChangedDocumentETags(tableId, null, schemaETag);

        /**************************
         * PART 1A: CREATE THE TABLE First we need to create the table on the
         * server. This comes in two parts--the definition and the properties.
         **************************/
        // First create the table definition on the server.
        try {
          resource = sc.getSynchronizer().createTable(tableId, schemaETag,
              orderedDefns.getColumns());
        } catch (Exception e) {
          exception("synchronizeTableConfigurationAndContent - createTable on server", tableId, e, tableLevelResult);
          return null;
        }

        schemaETag = resource.getSchemaETag();
        try {
          db = sc.getDatabase();
          // update schemaETag to that on server (dataETag is null already).
          sc.getDatabaseService().updateDBTableETags(sc.getAppName(), db, tableId, schemaETag, null);
        } finally {
          sc.releaseDatabase(db);
          db = null;
        }
      }

      // we found the matching resource on the server and we have set up our
      // local table to be ready for any data merge with the server's table.

      /**************************
       * PART 1A: UPDATE THE TABLE SCHEMA. This should generally not happen. But
       * we allow a server wipe and re-install by another user with the same
       * physical schema to match ours even when our schemaETag differs. IN this
       * case, we need to mark our data as needing a full re-sync.
       **************************/
      if (!resource.getSchemaETag().equals(schemaETag)) {
        // the server was re-installed by a different device.
        // verify that our table definition is identical to the
        // server, and, if it is, update our schemaETag to match
        // the server's.

        log.d(TAG, "updateDbFromServer setServerHadSchemaChanges(true)");
        tableLevelResult.setServerHadSchemaChanges(true);

        // fetch the table definition
        TableDefinitionResource definitionResource;
        try {
          definitionResource = sc.getSynchronizer().getTableDefinition(resource.getDefinitionUri());
        } catch (Exception e) {
          exception("synchronizeTableConfigurationAndContent - get table definition from server", tableId, e, tableLevelResult);
          return null;
        }

        // record that we have pulled it
        tableLevelResult.setPulledServerSchema(true);
        try {
          db = sc.getDatabase();
          // apply changes
          // this also updates the data rows so they will sync
          orderedDefns = addTableFromDefinitionResource(db, definitionResource, false);
          // NOTE: get tde after addTableFromDefinitionResource() because that may update tableSchemaETag
          tde = sc.getDatabaseService().getTableDefinitionEntry(sc.getAppName(), db, tableId);

          log.w(TAG,
              "database schema has changed. Structural modifications, if any, were successful.");
        } catch (Exception e) {
          exception("synchronizeTableConfigurationAndContent - create table locally", tableId, e, tableLevelResult);
          return null;
        } finally {
          if (db != null) {
            sc.releaseDatabase(db);
            db = null;
          }
        }
      }

      // OK. we have the schemaETag matching.

      // write our properties and definitions files.
      // write the current schema and properties set.
      try {
        db = sc.getDatabase();
        File definitionCsv = new File(ODKFileUtils.getTableDefinitionCsvFile(sc.getAppName(), tableId));
        File propertiesCsv = new File(ODKFileUtils.getTablePropertiesCsvFile(sc.getAppName(), tableId));
        /**
         * Since the md5Hash of the file identifies identical properties, ensure
         * that the list of KVS entries is in alphabetical order.
         */
        List<KeyValueStoreEntry> kvsEntries =
            sc.getDatabaseService().getDBTableMetadata(sc.getAppName(), db, tableId, null, null, null);

        for (int i = 0; i < kvsEntries.size(); i++) {
          KeyValueStoreEntry entry = kvsEntries.get(i);

          // replace all the choiceList entries with their choiceListJSON
          if (entry.partition.equals(KeyValueStoreConstants.PARTITION_COLUMN) && entry.key
              .equals(KeyValueStoreConstants.COLUMN_DISPLAY_CHOICES_LIST)) {
            // exported type is an array -- the choiceListJSON
            entry.type = ElementDataType.array.name();
            if ((entry.value != null) && (entry.value.trim().length() != 0)) {
              String choiceListJSON =
                  sc.getDatabaseService().getChoiceList(sc.getAppName(), db, entry.value);
              entry.value = choiceListJSON;
            } else {
              entry.value = null;
            }
          }
        }

        PropertiesFileUtils
            .writePropertiesIntoCsv(sc.getAppName(), tableId, orderedDefns, kvsEntries,
                definitionCsv, propertiesCsv);
      } finally {
        sc.releaseDatabase(db);
        db = null;
      }

      try {
        // capture any exception during the reloading of the properties from the server copy
        TablePropertiesReloader propertiesReloader = new TablePropertiesReloader();
        manifestProcessor.syncTableLevelFiles(tableId, resource.getTableLevelManifestETag(),
            propertiesReloader, pushLocalTableLevelFiles, sc);
        // and if there was an exception, raise it
        if ( propertiesReloader.reloadingException != null ) {
          exception("synchronizeTableConfigurationAndContent - error while uploading config files", tableId,
              propertiesReloader.reloadingException, tableLevelResult);
        }
      } catch (Exception e) {
        // Would it be more appropriate to exit here
        log.e(TAG, "synchronizeTableConfigurationAndContent");
        exception("synchronizeTableConfigurationAndContent - error while uploading config files", tableId,
            e, tableLevelResult);
        return null;
      }

      // we found the matching resource on the server and we have set up our
      // local table to be ready for any data merge with the server's table.

      // we should be up-to-date on the schema and properties
      success = true;
    } finally {
      if (success && tableLevelResult.getSyncOutcome() != SyncOutcome.WORKING) {
        log.e(TAG, "tableLevelResult status for table: " + tableId + " was "
            + tableLevelResult.getSyncOutcome().name()
            + ", and yet success returned true. This shouldn't be possible.");
      }
    }

    // this should be non-null at this point...
    return resource;
  }


  /**
   * Update the database to reflect the new structure.
   *
   * This multi-step interaction can safely be called outside of a transaction.
   * The atomic steps combine to be idempotent.
   * <p>
   * This should be called when downloading a table from the server, which is
   * why the syncTag is separate.
   *
   * @param db
   * @param definitionResource the resource that specifies the table syncTag (for revision
   *                           management).
   * @param doesNotExistLocally
   * @return the new {@link OrderedColumns} for the table.
   * @throws SchemaMismatchException
   * @throws RemoteException
   */
  private OrderedColumns addTableFromDefinitionResource(OdkDbHandle db,
      TableDefinitionResource definitionResource, boolean doesNotExistLocally)
      throws SchemaMismatchException, RemoteException {

    // old URI prefix to instance content if schemaETag has changed or has been reset
    String oldTableInstanceFilesUriString = null;

    if (doesNotExistLocally) {
      OrderedColumns orderedDefns;
      orderedDefns = sc.getDatabaseService().createOrOpenDBTableWithColumns(sc.getAppName(), db,
          definitionResource.getTableId(), new ColumnList(definitionResource.getColumns()));

       // and update the schema, removing the old URI string
      sc.getDatabaseService().serverTableSchemaETagChanged(sc.getAppName(),
           db, definitionResource.getTableId(), definitionResource.getSchemaETag(),
           oldTableInstanceFilesUriString);

      return orderedDefns;

    } else {
      OrderedColumns localColumnDefns = sc.getDatabaseService().getUserDefinedColumns(
          sc.getAppName(), db, definitionResource.getTableId());
      List<Column> serverColumns = definitionResource.getColumns();
      List<Column> localColumns = localColumnDefns.getColumns();
      if (localColumns.size() != serverColumns.size()) {
        throw new SchemaMismatchException("Server schema differs from local schema");
      }

      for (int i = 0; i < serverColumns.size(); ++i) {
        Column server = serverColumns.get(i);
        Column local = localColumns.get(i);
        if (!local.equals(server)) {
          throw new SchemaMismatchException("Server schema differs from local schema");
        }
      }

      TableDefinitionEntry te = sc.getDatabaseService().getTableDefinitionEntry(
          sc.getAppName(), db,
          definitionResource.getTableId());

      String schemaETag = te.getSchemaETag();
      if (schemaETag == null || definitionResource.getSchemaETag() == null ||
          !schemaETag.equals(definitionResource.getSchemaETag())) {
        // server has changed its schema

        // construct old URI prefix to instance content
        sc.getSynchronizer().updateTableSchemaETagAndPurgePotentiallyChangedDocumentETags(definitionResource.getTableId(), definitionResource.getSchemaETag(), schemaETag);
      }
      // NOTE: te has an out-of-date schemaETag at this point.

      return localColumnDefns;
    }
  }

  /**
   * Common error reporting...
   * 
   * @param method
   * @param tableId
   * @param e
   * @param tableLevelResult
   */
  private void exception(String method, String tableId, Exception e, TableLevelResult tableLevelResult) {
    String msg = e.getMessage();
    if (msg == null) {
      msg = e.toString();
    }

    String fmtMsg = String.format("Exception in %s on table: %s exception: %s", method, tableId,
        msg);

    log.e(TAG, fmtMsg);
    log.printStackTrace(e);

    SyncOutcome outcome = sc.exceptionEquivalentOutcome(e);
    tableLevelResult.setSyncOutcome(outcome);
    tableLevelResult.setMessage(fmtMsg);
  }

}
