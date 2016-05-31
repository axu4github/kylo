/*
 * Copyright (c) 2016. Teradata Inc.
 */

package com.thinkbiganalytics.ingest;


import com.thinkbiganalytics.util.PartitionBatch;
import com.thinkbiganalytics.util.PartitionSpec;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Vector;

/**
 * Merge from a table into a target table. Dedupes and uses partition strategy of the target table
 */
public class TableMergeSupport implements Serializable {

    public static Logger logger = LoggerFactory.getLogger(TableRegisterSupport.class);

    private Connection conn;

    public TableMergeSupport(Connection conn) {
        Validate.notNull(conn);
        this.conn = conn;
    }

    protected TableMergeSupport() {
        // for unit testing
    }

    /**
     * Performs the doMerge and insert into the target table from the source table
     *
     * @param sourceTable      the source table
     * @param targetTable      the target table
     * @param partitionSpec    the partition specification
     * @param feedPartionValue the source processing partition value
     * @param shouldDedupe     whether to perform dedupe during merge
     */
    public List<PartitionBatch> doMerge(String sourceTable, String targetTable, PartitionSpec partitionSpec, String feedPartionValue, boolean shouldDedupe) {

        Validate.notEmpty(sourceTable);
        Validate.notEmpty(targetTable);
        Validate.notNull(partitionSpec);
        Validate.notNull(feedPartionValue);

        String[] selectFields = getSelectFields(sourceTable, targetTable, partitionSpec);
        if (partitionSpec.isNonPartitioned()) {
            String sql = generateDedupeNonPartitionQuery(selectFields, sourceTable, targetTable, feedPartionValue, shouldDedupe);
            doMerge(sql);
        } else {

            List<PartitionBatch> batches = createPartitionBatches(partitionSpec, sourceTable, feedPartionValue);
            if (batches.size() > 0) {
                logger.info("{} batches will be executed", batches.size());
                for (PartitionBatch batch : batches) {
                    String sql = generateDedupePartitionQuery(selectFields, partitionSpec, batch.getPartionValues(), sourceTable, targetTable, feedPartionValue, shouldDedupe);
                    doMerge(sql);
                }
                return batches;
            } else {
                logger.warn("No valid data found.");
            }
        }
        return null;
    }

    /**
     * Generates a query for inserting from a source table into the target table with no partitions
     *
     * @param selectFields the list of fields in the select clause of the source table
     * @param sourceTable  the source table
     * @param targetTable  the target table
     * @param shouldDedupe whether to deduplicate the results
     * @return the sql string
     */
    protected String generateDedupeNonPartitionQuery(String[] selectFields, String sourceTable, String targetTable, String feedPartitionValue, boolean shouldDedupe) {

        String selectSQL = StringUtils.join(selectFields, ",");

        StringBuffer sb = new StringBuffer();
        sb.append("insert overwrite table ").append(targetTable).append(" ");

        if (shouldDedupe) {
            sb.append(" select ").append(selectSQL).append(" from (");
        }

        sb.append(" select ").append(selectSQL)
            .append(" from ").append(sourceTable).append(" where processing_dttm='" + feedPartitionValue + "' ")
            .append(" union ")
            .append(" select ").append(selectSQL)
            .append(" from ").append(targetTable);

        if (shouldDedupe) {
            sb.append(") x group by ").append(selectSQL);
        }

        return sb.toString();
    }

    /**
     * Generates a query for inserting from a source table into the target table adhering to partitions
     *
     * @param selectFields    the list of fields in the select clause of the source table
     * @param spec            the partition specification or null if none
     * @param partitionValues the values containing the distinct partition data to process this iterator
     * @param sourceTable     the source table
     * @param targetTable     the target table
     * @param shouldDedupe    whether to deduplicate the results
     * @return the sql string
     */
    protected String generateDedupePartitionQuery(String[] selectFields, PartitionSpec spec, String[] partitionValues, String sourceTable, String targetTable, String feedPartitionValue, boolean
        shouldDedupe) {

        String selectSQL = StringUtils.join(selectFields, ",");
        String targetSqlWhereClause = spec.toTargetSQLWhere(partitionValues);
        String sourceSqlWhereClause = spec.toSourceSQLWhere(partitionValues);
        String partitionClause = spec.toPartitionSpec(partitionValues);

        StringBuffer sb = new StringBuffer();
        sb.append("insert overwrite table ").append(targetTable).append(" ")
            .append(partitionClause);

        if (shouldDedupe) {
            sb.append(" select ").append(selectSQL).append(" from (");
        }

        sb.append(" select ").append(selectSQL)
            .append(" from ").append(sourceTable).append(" ")
            .append(" where ")
            .append(" processing_dttm='" + feedPartitionValue + "' and ")
            .append(sourceSqlWhereClause)
            .append(" union ")
            .append(" select ").append(selectSQL)
            .append(" from ").append(targetTable).append(" ")
            .append(" where ")
            .append(targetSqlWhereClause);

        if (shouldDedupe) {
            sb.append(") x group by ").append(selectSQL);
        }

        return sb.toString();
    }

    /**
     * Generates a query for merging data from a source table into the target table adhering to partitions and using a last modify date and a primary key
     *
     * @param id              the primary key
     * @param lastModifyField the last modify date field used to determine
     * @param selectFields    the list of fields in the select clause of the source table
     * @param spec            the partition specification or null if none
     * @param partitionValues the values containing the distinct partition data to process this iterator
     * @param sourceTable     the source table
     * @param targetTable     the target table
     * @return the sql string
     */
    protected String generateDedupePartitionQueryPK(String id, String lastModifyField, String[] selectFields, PartitionSpec spec, String[] partitionValues, String sourceTable, String targetTable,
                                                    String feedPartitionValue) {

        String selectSQL = StringUtils.join(selectFields, ",");
        String targetSqlWhereClause = spec.toTargetSQLWhere(partitionValues);
        String sourceSqlWhereClause = spec.toSourceSQLWhere(partitionValues);
        String partitionClause = spec.toPartitionSpec(partitionValues);

        StringBuffer sb = new StringBuffer();

//        CREATE VIEW reconcile_view AS
        sb.append("insert overwrite table ").append(targetTable).append(" ").append(partitionClause)
            .append("SELECT t1.").append(selectSQL).append(" FROM")
            .append("(SELECT ").append(selectSQL).append(" FROM ").append(targetTable).append(" where ").append(targetSqlWhereClause)
            .append("  UNION ALL")
            .append(" SELECT ").append(selectSQL).append(" FROM ").append(sourceTable)
            .append(" where ")
            .append(" processing_dttm='" + feedPartitionValue + "' and ")
            .append(sourceSqlWhereClause).append(" ) t1")
            .append(" JOIN")
            .append("(SELECT ").append(id).append(", max(").append(lastModifyField).append(") max_modified FROM ")
            .append("(SELECT ").append(selectSQL).append(" FROM ").append(targetTable).append(" where ").append(targetSqlWhereClause)
            .append(" UNION ALL ")
            .append(" SELECT ").append(selectSQL).append(" FROM ").append(sourceTable)
            .append(" where ")
            .append(" processing_dttm='" + feedPartitionValue + "' and ")
            .append(sourceSqlWhereClause)
            .append(") t2")
            .append(" GROUP BY ").append(id).append(") s")
            .append(" ON t1.").append(id).append(" = s.").append(id).append(" AND t1.").append(lastModifyField).append(" = ").append("s.max_modified");

        return sb.toString();
    }

    /**
     * Generates a query for merging data from a source table into the target table and using a last modify date and a primary key
     *
     * @param id              the primary key
     * @param lastModifyField the last modify date field used to determine
     * @param selectFields    the list of fields in the select clause of the source table
     * @param sourceTable     the source table
     * @param targetTable     the target table
     * @return the sql string
     */
    protected String generateDedupeNonPartitionQueryPK(String id, String lastModifyField, String[] selectFields, String sourceTable, String targetTable, String feedPartitionValue) {

        String selectSQL = StringUtils.join(selectFields, ",");
        StringBuffer sb = new StringBuffer();

//        CREATE VIEW reconcile_view AS
        sb.append("insert overwrite table ").append(targetTable).append(" ")
            .append("SELECT t1.").append(selectSQL).append(" FROM")
            .append("(SELECT ").append(selectSQL).append(" FROM ").append(targetTable)
            .append("  UNION ALL")
            .append(" SELECT ").append(selectSQL).append(" FROM ").append(sourceTable).append(" ) t1")
            .append(" JOIN")
            .append("(SELECT ").append(id).append(", max(").append(lastModifyField).append(") max_modified FROM ")
            .append("(SELECT ").append(selectSQL).append(" FROM ").append(targetTable)
            .append(" UNION ALL ")
            .append(" SELECT ").append(selectSQL).append(" FROM ").append(sourceTable)
            .append(") t2")
            .append(" GROUP BY ").append(id).append(") s")
            .append(" ON t1.").append(id).append(" = s.").append(id).append(" AND t1.").append(lastModifyField).append(" = ").append("s.max_modified");

        return sb.toString();
    }

    protected void doMerge(String sql) {

        try (final Statement st = conn.createStatement()) {
            logger.info("Executing doMerge batch sql {}", sql);
            st.execute(sql);
        } catch (SQLException e) {
            logger.error("Failed to execute {} with error {}", sql, e);
            throw new RuntimeException("Failed to execute query", e);
        }
    }

    /*
    Generates batches of partitions in the source table
     */
    protected List<PartitionBatch> createPartitionBatches(PartitionSpec spec, String sourceTable, String feedPartition) {
        Vector<PartitionBatch> v = new Vector<>();
        String sql = "";
        try (final Statement st = conn.createStatement()) {
            sql = spec.toDistinctSelectSQL(sourceTable, feedPartition);
            logger.info("Executing batch query [" + sql + "]");
            ResultSet rs = st.executeQuery(sql);
            int count = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String[] values = new String[count];
                for (int i = 1; i <= count; i++) {
                    Object oVal = rs.getObject(i);
                    String sVal = (oVal == null ? "" : oVal.toString());
                    values[i - 1] = StringUtils.defaultString(sVal, "");
                }
                Long numRecords = rs.getLong(count);
                v.add(new PartitionBatch(numRecords, spec, values));
            }
            logger.info("Number of partitions [" + v.size() + "]");
        } catch (SQLException e) {
            logger.error("Failed to select partition batches SQL {} with error {}", sql, e);
            throw new RuntimeException("Failed to select partition batches", e);
        }
        return v;
    }


    protected String[] getSelectFields(String sourceTable, String destTable, PartitionSpec partitionSpec) {
        List<String> srcFields = resolveTableSchema(sourceTable);
        List<String> destFields = resolveTableSchema(destTable);

        // Find common fields
        destFields.retainAll(srcFields);

        // Eliminate any partition columns
        if (partitionSpec != null) {
            destFields.removeAll(partitionSpec.getKeyNames());
        }
        return destFields.toArray(new String[0]);
    }

    protected List<String> resolveTableSchema(String qualifiedTablename) {

        List<String> columnSet = new Vector<>();
        try (final Statement st = conn.createStatement()) {
            // Use default database to resolve ambiguity between schema.table and table.column
            // https://issues.apache.org/jira/browse/HIVE-12184
            st.execute("use default");
            String ddl = "desc " + qualifiedTablename;
            logger.info("Resolving table schema [{}]", ddl);
            ResultSet rs = st.executeQuery(ddl);
            while (rs.next()) {
                // First blank row is start of partition info
                if (StringUtils.isEmpty(rs.getString(1))) {
                    break;
                }
                columnSet.add(rs.getString(1));
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to inspect schema", e);
        }
        return columnSet;
    }

    public static void main(String[] args) {
        TableMergeSupport support = new TableMergeSupport();
        String[] selectFields = new String[]{"id", "name", "company", "zip", "phone", "email", "hired"};
        String sourceTable = "emp_sr5.employee_valid";
        String targetTable = "emp_sr5.employee";
        String processingPartion = "20160119074340";
        String[] partitionValues = new String[]{"USA", "2015"};
        PartitionSpec spec = new PartitionSpec("country|string|country\nyear|int|year(hired)");
        String sql = support.generateDedupeNonPartitionQuery(selectFields, sourceTable, targetTable, processingPartion, true);
        String sql2 = support.generateDedupePartitionQuery(selectFields, spec, partitionValues, sourceTable, targetTable, processingPartion, false);
        System.out.println(sql);

        System.out.println(sql2);
    }

}
