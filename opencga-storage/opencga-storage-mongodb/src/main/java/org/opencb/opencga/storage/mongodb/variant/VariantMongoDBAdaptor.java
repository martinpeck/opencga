/*
 * Copyright 2015 OpenCB
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

package org.opencb.opencga.storage.mongodb.variant;

import com.mongodb.*;

import java.net.UnknownHostException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opencb.biodata.models.feature.Region;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.VariantSourceEntry;
import org.opencb.biodata.models.variant.annotation.VariantAnnotation;
import org.opencb.biodata.models.variant.stats.VariantStats;
import org.opencb.commons.io.DataWriter;
import org.opencb.datastore.core.Query;
import org.opencb.datastore.core.QueryOptions;
import org.opencb.datastore.core.QueryResult;
import org.opencb.datastore.mongodb.MongoDBCollection;
import org.opencb.datastore.mongodb.MongoDataStore;
import org.opencb.datastore.mongodb.MongoDataStoreManager;
import org.opencb.opencga.storage.core.StudyConfiguration;
import org.opencb.opencga.storage.core.variant.StudyConfigurationManager;
import org.opencb.opencga.storage.core.variant.VariantStorageManager;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBIterator;
import org.opencb.opencga.storage.core.variant.adaptors.VariantSourceDBAdaptor;
import org.opencb.opencga.storage.core.variant.stats.VariantStatsWrapper;
import org.opencb.opencga.storage.mongodb.utils.MongoCredentials;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Ignacio Medina <igmecas@gmail.com>
 * @author Jacobo Coll <jacobo167@gmail.com>
 * @author Cristina Yenyxe Gonzalez Garcia <cyenyxe@ebi.ac.uk>
 */
public class VariantMongoDBAdaptor implements VariantDBAdaptor {

    private final MongoDataStoreManager mongoManager;
    private final MongoDataStore db;
    private final String collectionName;
    private final MongoDBCollection variantsCollection;
    private final VariantSourceMongoDBAdaptor variantSourceMongoDBAdaptor;

    private StudyConfigurationManager studyConfigurationManager;

    @Deprecated
    private DataWriter dataWriter;


    private enum QueryOperation {
        AND, OR
    }

    public static final String OR = ",";
    public static final String AND = ";";
    public static final String IS = ":";

    protected static Logger logger = LoggerFactory.getLogger(VariantMongoDBAdaptor.class);

    public VariantMongoDBAdaptor(MongoCredentials credentials, String variantsCollectionName, String filesCollectionName, StudyConfigurationManager studyConfigurationManager)
            throws UnknownHostException {
        // MongoDB configuration
        mongoManager = new MongoDataStoreManager(credentials.getDataStoreServerAddresses());
        db = mongoManager.get(credentials.getMongoDbName(), credentials.getMongoDBConfiguration());
        variantSourceMongoDBAdaptor = new VariantSourceMongoDBAdaptor(credentials, filesCollectionName);
        collectionName = variantsCollectionName;
        variantsCollection = db.getCollection(collectionName);
        this.studyConfigurationManager = studyConfigurationManager;
    }


    @Override
    @Deprecated
    public void setDataWriter(DataWriter dataWriter) {
        this.dataWriter = dataWriter;
    }


    @Override
    public QueryResult insert(List<Variant> variants, String studyName, QueryOptions options) {
        StudyConfiguration studyConfiguration = studyConfigurationManager.getStudyConfiguration(studyName, options).first();
        // TODO FILE_ID must be in QueryOptions?
        int fileId = options.getInt(VariantStorageManager.Options.FILE_ID.key());
        boolean includeStats = options.getBoolean(VariantStorageManager.Options.INCLUDE_STATS.key(), VariantStorageManager.Options.INCLUDE_STATS.defaultValue());
        boolean includeSrc = options.getBoolean(VariantStorageManager.Options.INCLUDE_SRC.key(), VariantStorageManager.Options.INCLUDE_SRC.defaultValue());
        boolean includeGenotypes = options.getBoolean(VariantStorageManager.Options.INCLUDE_GENOTYPES.key(), VariantStorageManager.Options.INCLUDE_GENOTYPES.defaultValue());
//        boolean compressGenotypes = options.getBoolean(VariantStorageManager.Options.COMPRESS_GENOTYPES.key(), VariantStorageManager.Options.COMPRESS_GENOTYPES.defaultValue());
//        String defaultGenotype = options.getString(MongoDBVariantStorageManager.DEFAULT_GENOTYPE, "0|0");

        DBObjectToVariantConverter variantConverter = new DBObjectToVariantConverter(null, includeStats? new DBObjectToVariantStatsConverter(studyConfigurationManager) : null);
        DBObjectToVariantSourceEntryConverter sourceEntryConverter = new DBObjectToVariantSourceEntryConverter(includeSrc,
                includeGenotypes? new DBObjectToSamplesConverter(studyConfiguration) : null);
        return insert(variants, fileId, variantConverter, sourceEntryConverter, studyConfiguration, null);
    }

    @Override
    public QueryResult delete(Query query, QueryOptions options) {
        QueryBuilder qb = QueryBuilder.start();
        qb = parseQuery(query, qb);
        logger.debug("Delete to be executed: '{}'", qb.get().toString());
        QueryResult queryResult = variantsCollection.remove(qb.get(), options);

        return queryResult;
    }

    @Override
    public QueryResult deleteSamples(String studyName, List<String> sampleNames, QueryOptions options) {
        //TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public QueryResult deleteFile(String studyName, String fileName, QueryOptions options) {
        //TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public QueryResult deleteStudy(String studyName, QueryOptions options) {
        if (options == null) {
            options = new QueryOptions();
        }
        StudyConfiguration studyConfiguration = studyConfigurationManager.getStudyConfiguration(studyName, options).first();
        DBObject query = parseQuery(new Query(VariantQueryParams.STUDIES.key(), studyConfiguration.getStudyId()), new QueryBuilder()).get();

        // { $pull : { files : {  sid : <studyId> } } }
        BasicDBObject update = new BasicDBObject(
                "$pull",
                new BasicDBObject(
                        DBObjectToVariantConverter.STUDIES_FIELD,
                        new BasicDBObject(
                                DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, studyConfiguration.getStudyId()
                        )
                )
        );
        QueryResult<WriteResult> result = variantsCollection.update(query, update, new QueryOptions("multi", true));

        logger.debug("deleteStudy: query = {}", query);
        logger.debug("deleteStudy: update = {}", update);

        if (options.getBoolean("purge", false)) {
            BasicDBObject purgeQuery = new BasicDBObject(DBObjectToVariantConverter.STUDIES_FIELD, new BasicDBObject("$size", 0));
            variantsCollection.remove(purgeQuery, new QueryOptions("multi", true));
        }

        return result;
    }



    @Override
    public QueryResult<Variant> get(Query query, QueryOptions options) {
        if (options == null) {
            options = new QueryOptions();
        }
        QueryBuilder qb = QueryBuilder.start();
//        parseQueryOptions(options, qb);
        parseQuery(query, qb);
//        DBObject projection = parseProjectionQueryOptions(options);
        DBObject projection = createProjection(query, options);
        logger.debug("Query to be executed: '{}'", qb.get().toString());

        QueryResult<Variant> queryResult = variantsCollection.find(qb.get(), projection, getDbObjectToVariantConverter(query, options), options);
        // set query Id?

        return queryResult;
    }

    @Override
    public List<QueryResult<Variant>> get(List<Query> queries, QueryOptions options) {
        List<QueryResult<Variant>> queryResultList = new ArrayList<>(queries.size());
        for (Query query : queries) {
            QueryResult<Variant> queryResult = get(query, options);
            queryResultList.add(queryResult);
        }
        return queryResultList;
    }


    @Override
    public QueryResult<Long> count(Query query) {
        QueryBuilder qb = QueryBuilder.start();
        parseQuery(query, qb);
        logger.debug("Query to be executed: '{}'", qb.get().toString());
        QueryResult<Long> queryResult = queryResult = variantsCollection.count(qb.get());
        return queryResult;
    }

    @Override
    public QueryResult distinct(Query query, String field) {
        String documentPath;
        switch (field) {
            case "gene":
            default:
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.GENE_NAME_FIELD;
                break;
            case "ensemblGene":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.ENSEMBL_GENE_ID_FIELD;
                break;
            case "ensemblTranscript":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.ENSEMBL_TRANSCRIPT_ID_FIELD;
                break;
            case "ct":
            case "consequence_type":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD;
                break;
        }

        QueryBuilder qb = QueryBuilder.start();
        parseQuery(query, qb);
        return variantsCollection.distinct(documentPath, qb.get());
    }

    @Override
    public VariantDBIterator iterator() {
        return iterator(new Query(), new QueryOptions());
    }

    @Override
    public VariantDBIterator iterator(Query query, QueryOptions options) {
        if (options == null) {
            options = new QueryOptions();
        }
        if (query == null) {
            query = new Query();
        }
        QueryBuilder qb = QueryBuilder.start();
//        parseQueryOptions(options, qb);
        qb = parseQuery(query, qb);
//        DBObject projection = parseProjectionQueryOptions(options);
        DBObject projection = createProjection(query, options);
        DBCursor dbCursor = variantsCollection.nativeQuery().find(qb.get(), projection, options);
        dbCursor.batchSize(options.getInt("batchSize", 100));
        return new VariantMongoDBIterator(dbCursor, getDbObjectToVariantConverter(query, options));
    }

    @Override
    public void forEach(Consumer<? super Variant> action) {
        forEach(new Query(), action, new QueryOptions());
    }

    @Override
    public void forEach(Query query, Consumer<? super Variant> action, QueryOptions options) {
        Objects.requireNonNull(action);
        VariantDBIterator variantDBIterator = iterator(query, options);
        while (variantDBIterator.hasNext()) {
            action.accept(variantDBIterator.next());
        }
    }



    @Override
    public QueryResult getFrequency(Query query, Region region, int regionIntervalSize) {
        // db.variants.aggregate( { $match: { $and: [ {chr: "1"}, {start: {$gt: 251391, $lt: 2701391}} ] }},
        //                        { $group: { _id: { $subtract: [ { $divide: ["$start", 20000] }, { $divide: [{$mod: ["$start", 20000]}, 20000] } ] },
        //                                  totalCount: {$sum: 1}}})

        QueryOptions options = new QueryOptions();

        // If interval is not provided is set to the value that returns 200 values
        if (regionIntervalSize <= 0) {
//            regionIntervalSize = options.getInt("interval", (region.getEnd() - region.getStart()) / 200);
            regionIntervalSize = (region.getEnd() - region.getStart()) / 200;
        }

        BasicDBObject start = new BasicDBObject("$gt", region.getStart());
        start.append("$lt", region.getEnd());

        BasicDBList andArr = new BasicDBList();
        andArr.add(new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, region.getChromosome()));
        andArr.add(new BasicDBObject(DBObjectToVariantConverter.START_FIELD, start));

        // Parsing the rest of options
        QueryBuilder qb = new QueryBuilder();
//        DBObject optionsMatch = parseQueryOptions(options, qb).get();
        DBObject optionsMatch = parseQuery(query, qb).get();
        if(!optionsMatch.keySet().isEmpty()) {
            andArr.add(optionsMatch);
        }
        DBObject match = new BasicDBObject("$match", new BasicDBObject("$and", andArr));

//        qb.and("_at.chunkIds").in(chunkIds);
//        qb.and(DBObjectToVariantConverter.END_FIELD).greaterThanEquals(region.getStart());
//        qb.and(DBObjectToVariantConverter.START_FIELD).lessThanEquals(region.getEnd());
//
//        List<String> chunkIds = getChunkIds(region);
//        DBObject regionObject = new BasicDBObject("_at.chunkIds", new BasicDBObject("$in", chunkIds))
//                .append(DBObjectToVariantConverter.END_FIELD, new BasicDBObject("$gte", region.getStart()))
//                .append(DBObjectToVariantConverter.START_FIELD, new BasicDBObject("$lte", region.getEnd()));

        BasicDBList divide1 = new BasicDBList();
        divide1.add("$start");
        divide1.add(regionIntervalSize);

        BasicDBList divide2 = new BasicDBList();
        divide2.add(new BasicDBObject("$mod", divide1));
        divide2.add(regionIntervalSize);

        BasicDBList subtractList = new BasicDBList();
        subtractList.add(new BasicDBObject("$divide", divide1));
        subtractList.add(new BasicDBObject("$divide", divide2));

        BasicDBObject subtract = new BasicDBObject("$subtract", subtractList);
        DBObject totalCount = new BasicDBObject("$sum", 1);
        BasicDBObject g = new BasicDBObject("_id", subtract);
        g.append("features_count", totalCount);
        DBObject group = new BasicDBObject("$group", g);
        DBObject sort = new BasicDBObject("$sort", new BasicDBObject("_id", 1));

//        logger.info("getAllIntervalFrequencies - (>·_·)>");
//        System.out.println(options.toString());
//        System.out.println(match.toString());
//        System.out.println(group.toString());
//        System.out.println(sort.toString());

        long dbTimeStart = System.currentTimeMillis();
        QueryResult output = variantsCollection.aggregate(/*"$histogram", */Arrays.asList(match, group, sort), options);
        long dbTimeEnd = System.currentTimeMillis();

        Map<Long, DBObject> ids = new HashMap<>();
        // Create DBObject for intervals with features inside them
        for (DBObject intervalObj : (List<DBObject>) output.getResult()) {
            Long _id = Math.round((Double) intervalObj.get("_id"));//is double

            DBObject intervalVisited = ids.get(_id);
            if (intervalVisited == null) {
                intervalObj.put("_id", _id);
                intervalObj.put("start", getChunkStart(_id.intValue(), regionIntervalSize));
                intervalObj.put("end", getChunkEnd(_id.intValue(), regionIntervalSize));
                intervalObj.put("chromosome", region.getChromosome());
                intervalObj.put("features_count", Math.log((int) intervalObj.get("features_count")));
                ids.put(_id, intervalObj);
            } else {
                Double sum = (Double) intervalVisited.get("features_count") + Math.log((int) intervalObj.get("features_count"));
                intervalVisited.put("features_count", sum.intValue());
            }
        }

        // Create DBObject for intervals without features inside them
        BasicDBList resultList = new BasicDBList();
        int firstChunkId = getChunkId(region.getStart(), regionIntervalSize);
        int lastChunkId = getChunkId(region.getEnd(), regionIntervalSize);
        DBObject intervalObj;
        for (int chunkId = firstChunkId; chunkId <= lastChunkId; chunkId++) {
            intervalObj = ids.get((long) chunkId);
            if (intervalObj == null) {
                intervalObj = new BasicDBObject();
                intervalObj.put("_id", chunkId);
                intervalObj.put("start", getChunkStart(chunkId, regionIntervalSize));
                intervalObj.put("end", getChunkEnd(chunkId, regionIntervalSize));
                intervalObj.put("chromosome", region.getChromosome());
                intervalObj.put("features_count", 0);
            }
            resultList.add(intervalObj);
        }

        QueryResult queryResult = new QueryResult(region.toString(), ((Long) (dbTimeEnd - dbTimeStart)).intValue(),
                resultList.size(), resultList.size(), null, null, resultList);

        return queryResult;
    }

    @Override
    public QueryResult rank(Query query, String field, int numResults, boolean asc) {
        QueryOptions options = new QueryOptions();
        options.put("limit", numResults);
        options.put("count", true);
        options.put("order", (asc) ? 1 : -1); // MongoDB: 1 = ascending, -1 = descending

        return groupBy(query, field, options);
    }

    @Override
    public QueryResult groupBy(Query query, String field, QueryOptions options) {
        String documentPath;
        String unwindPath;
        int numUnwinds = 2;
        switch (field) {
            case "gene":
            default:
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.GENE_NAME_FIELD;
                unwindPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD;
                break;
            case "ensemblGene":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.ENSEMBL_GENE_ID_FIELD;
                unwindPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD;

                break;
            case "ct":
            case "consequence_type":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD;
                unwindPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD;
                numUnwinds = 3;
                break;
        }

        QueryBuilder qb = QueryBuilder.start();
        parseQuery(query, qb);

        boolean count = options != null && options.getBoolean("count", false);
        int order = options != null ? options.getInt("order", -1) : -1;

        DBObject project;
        DBObject projectAndCount;
        if (count) {
            project = new BasicDBObject("$project", new BasicDBObject("field", "$"+documentPath));
            projectAndCount = new BasicDBObject("$project", new BasicDBObject()
                    .append("id", "$_id")
                    .append("_id", 0)
                    .append("count", new BasicDBObject("$size", "$values")));
        } else {
            project = new BasicDBObject("$project", new BasicDBObject()
                    .append("field", "$" + documentPath)
                            //.append("_id._id", "$_id")
                    .append("_id.start", "$" + DBObjectToVariantConverter.START_FIELD)
                    .append("_id.end", "$" + DBObjectToVariantConverter.END_FIELD)
                    .append("_id.chromosome", "$" + DBObjectToVariantConverter.CHROMOSOME_FIELD)
                    .append("_id.alternate", "$" + DBObjectToVariantConverter.ALTERNATE_FIELD)
                    .append("_id.reference", "$" + DBObjectToVariantConverter.REFERENCE_FIELD)
                    .append("_id.ids", "$" + DBObjectToVariantConverter.IDS_FIELD));
            projectAndCount = new BasicDBObject("$project", new BasicDBObject()
                    .append("id", "$_id")
                    .append("_id", 0)
                    .append("values", "$values")
                    .append("count", new BasicDBObject("$size", "$values")));
        }

        DBObject match = new BasicDBObject("$match", qb.get());
        DBObject unwindField = new BasicDBObject("$unwind", "$field");
        DBObject notNull = new BasicDBObject("$match", new BasicDBObject("field", new BasicDBObject("$ne", null)));
        DBObject groupAndAddToSet = new BasicDBObject("$group", new BasicDBObject("_id", "$field")
                .append("values", new BasicDBObject("$addToSet", "$_id"))); // sum, count, avg, ...?
        DBObject sort = new BasicDBObject("$sort", new BasicDBObject("count", order)); // 1 = ascending, -1 = descending
        DBObject skip = null;
        if (options != null && options.getInt("skip", -1) > 0) {
            skip = new BasicDBObject("$skip", options.getInt("skip", -1));
        }
        DBObject limit = new BasicDBObject("$limit",
                options != null && options.getInt("limit", -1) > 0 ? options.getInt("limit") : 10);

        List<DBObject> operations = new LinkedList<>();
        operations.add(match);
        operations.add(project);
        for (int i = 0; i < numUnwinds; i++) {
            operations.add(unwindField);
        }
        operations.add(notNull);
        operations.add(groupAndAddToSet);
        operations.add(projectAndCount);
        operations.add(sort);
        if (skip != null) {
            operations.add(skip);
        }
        operations.add(limit);
        logger.debug("db." + collectionName + ".aggregate( " + operations + " )");
        QueryResult<DBObject> queryResult = variantsCollection.aggregate(operations, options);

//            List<Map<String, Object>> results = new ArrayList<>(queryResult.getResult().size());
//            results.addAll(queryResult.getResult().stream().map(dbObject -> new ObjectMap("id", dbObject.get("_id")).append("count", dbObject.get("count"))).collect(Collectors.toList()));
        List<Map> results = queryResult.getResult().stream().map(DBObject::toMap).collect(Collectors.toList());

        return new QueryResult<>(queryResult.getId(), queryResult.getDbTime(), queryResult.getNumResults(), queryResult.getNumTotalResults(), queryResult.getWarningMsg(), queryResult.getErrorMsg(), results);
    }

    @Override
    public QueryResult groupBy(Query query, List<String> fields, QueryOptions options) {
        String warningMsg = "Unimplemented VariantMongoDBAdaptor::groupBy list of fields. Using field[0] : '" + fields.get(0) + "'";
        logger.warn(warningMsg);
        QueryResult queryResult = groupBy(query, fields.get(0), options);
        queryResult.setWarningMsg(warningMsg);
        return queryResult;
    }



    @Override
    public QueryResult addStats(List<VariantStatsWrapper> variantStatsWrappers, String studyName, QueryOptions queryOptions) {
        return updateStats(variantStatsWrappers, studyName, queryOptions);
    }

    @Override
    public QueryResult updateStats(List<VariantStatsWrapper> variantStatsWrappers, String studyName, QueryOptions options) {
        return updateStats(variantStatsWrappers, studyConfigurationManager.getStudyConfiguration(studyName, options).first(), options);
    }

    @Override
    public QueryResult updateStats(List<VariantStatsWrapper> variantStatsWrappers, StudyConfiguration studyConfiguration,
                                   QueryOptions options) {
        DBCollection coll = db.getDb().getCollection(collectionName);
        BulkWriteOperation pullBuilder = coll.initializeUnorderedBulkOperation();
        BulkWriteOperation pushBuilder = coll.initializeUnorderedBulkOperation();

        long start = System.nanoTime();
        DBObjectToVariantStatsConverter statsConverter = new DBObjectToVariantStatsConverter(studyConfigurationManager);
//        VariantSource variantSource = queryOptions.get(VariantStorageManager.VARIANT_SOURCE, VariantSource.class);
        DBObjectToVariantConverter variantConverter = getDbObjectToVariantConverter(new Query(), options);
        boolean overwrite = options.getBoolean(VariantStorageManager.Options.OVERWRITE_STATS.key(), false);
        //TODO: Use the StudyConfiguration to change names to ids

        // TODO make unset of 'st' if already present?
        for (VariantStatsWrapper wrapper : variantStatsWrappers) {
            Map<String, VariantStats> cohortStats = wrapper.getCohortStats();
            Iterator<VariantStats> iterator = cohortStats.values().iterator();
            VariantStats variantStats = iterator.hasNext()? iterator.next() : null;
            List<DBObject> cohorts = statsConverter.convertCohortsToStorageType(cohortStats, studyConfiguration.getStudyId());   // TODO remove when we remove fileId
//            List cohorts = statsConverter.convertCohortsToStorageType(cohortStats, variantSource.getStudyId());   // TODO use when we remove fileId

            // add cohorts, overwriting old values if that cid, fid and sid already exists: remove and then add
            // db.variants.update(
            //      {_id:<id>},
            //      {$pull:{st:{cid:{$in:["Cohort 1","cohort 2"]}, fid:{$in:["file 1", "file 2"]}, sid:{$in:["study 1", "study 2"]}}}}
            // )
            // db.variants.update(
            //      {_id:<id>},
            //      {$push:{st:{$each: [{cid:"Cohort 1", fid:"file 1", ... , defaultValue:3},{cid:"Cohort 2", ... , defaultValue:3}] }}}
            // )

            if (!cohorts.isEmpty()) {
                String id = variantConverter.buildStorageId(wrapper.getChromosome(), wrapper.getPosition(),
                        variantStats.getRefAllele(), variantStats.getAltAllele());


                DBObject find = new BasicDBObject("_id", id);
                if (overwrite) {
                    List<BasicDBObject> idsList = new ArrayList<>(cohorts.size());
                    for (DBObject cohort : cohorts) {
                        BasicDBObject ids = new BasicDBObject()
                                .append(DBObjectToVariantStatsConverter.COHORT_ID, cohort.get(DBObjectToVariantStatsConverter.COHORT_ID))
                                .append(DBObjectToVariantStatsConverter.STUDY_ID, cohort.get(DBObjectToVariantStatsConverter.STUDY_ID));
                        idsList.add(ids);
                    }
                    DBObject update = new BasicDBObject("$pull",
                            new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                                    new BasicDBObject("$or", idsList)));

                    pullBuilder.find(find).updateOne(update);
                }

                DBObject push = new BasicDBObject("$push",
                        new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                                new BasicDBObject("$each", cohorts)));

                pushBuilder.find(find).update(push);
            }
        }

        // TODO handle if the variant didn't had that studyId in the files array
        // TODO check the substitution is done right if the stats are already present
        if (overwrite) {
            pullBuilder.execute();
        }
        BulkWriteResult writeResult = pushBuilder.execute();
        int writes = writeResult.getModifiedCount();


        return new QueryResult<>("", ((int) (System.nanoTime() - start)), writes, writes, "", "", Collections.singletonList(writeResult));
    }

    /*

     */

    @Override
    public QueryResult deleteStats(String studyName, String cohortName, QueryOptions options) {
        StudyConfiguration studyConfiguration = studyConfigurationManager.getStudyConfiguration(studyName, options).first();
        int cohortId = studyConfiguration.getCohortIds().get(cohortName);

        // { st : { $elemMatch : {  sid : <studyId>, cid : <cohortId> } } }
        DBObject query = new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                new BasicDBObject("$elemMatch",
                        new BasicDBObject(DBObjectToVariantStatsConverter.STUDY_ID, studyConfiguration.getStudyId())
                                .append(DBObjectToVariantStatsConverter.COHORT_ID, cohortId)));

        // { $pull : { st : {  sid : <studyId>, cid : <cohortId> } } }
        BasicDBObject update = new BasicDBObject(
                "$pull",
                new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                        new BasicDBObject(DBObjectToVariantStatsConverter.STUDY_ID, studyConfiguration.getStudyId())
                                .append(DBObjectToVariantStatsConverter.COHORT_ID, cohortId)
                )
        );
        logger.debug("deleteStats: query = {}", query);
        logger.debug("deleteStats: update = {}", update);

        return variantsCollection.update(query, update, new QueryOptions("multi", true));
    }



    @Override
    public QueryResult addAnnotations(List<VariantAnnotation> variantAnnotations, QueryOptions queryOptions) {
        logger.warn("Unimplemented VariantMongoDBAdaptor::addAnnotations. Using \"VariantMongoDBAdaptor::updateAnnotations\"");
        return updateAnnotations(variantAnnotations, queryOptions);
    }

    @Override
    public QueryResult updateAnnotations(List<VariantAnnotation> variantAnnotations, QueryOptions queryOptions) {
        DBCollection coll = db.getDb().getCollection(collectionName);
        BulkWriteOperation builder = coll.initializeUnorderedBulkOperation();

        long start = System.nanoTime();
        DBObjectToVariantConverter variantConverter = getDbObjectToVariantConverter(new Query(), queryOptions);
        for (VariantAnnotation variantAnnotation : variantAnnotations) {
            String id = variantConverter.buildStorageId(variantAnnotation.getChromosome(), variantAnnotation.getStart(),
                    variantAnnotation.getReferenceAllele(), variantAnnotation.getAlternateAllele());
            DBObject find = new BasicDBObject("_id", id);
            DBObjectToVariantAnnotationConverter converter = new DBObjectToVariantAnnotationConverter();
            DBObject convertedVariantAnnotation = converter.convertToStorageType(variantAnnotation);
            DBObject update = new BasicDBObject("$set", new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD + ".0",
                    convertedVariantAnnotation));
            builder.find(find).updateOne(update);
        }
        BulkWriteResult writeResult = builder.execute();

        return new QueryResult<>("", ((int) (System.nanoTime() - start)), 1, 1, "", "", Collections.singletonList(writeResult));
    }

    @Override
    public QueryResult deleteAnnotation(String annotationId, Query query, QueryOptions queryOptions) {
        if (queryOptions == null) {
            queryOptions = new QueryOptions();
        }
//        queryOptions.put(VariantQueryParams.STUDIES.key(), studyId);
        DBObject dbQuery = parseQuery(query, new QueryBuilder()).get();

//        DBObject update = new BasicDBObject("$unset", new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD, ""));
        DBObject update = new BasicDBObject("$set", new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD + ".0", null));

        logger.debug("deleteAnnotation: query = {}", dbQuery);
        logger.debug("deleteAnnotation: update = {}", update);

        return variantsCollection.update(dbQuery, update, new QueryOptions("multi", true));
    }


    @Override
    public boolean close() {
        mongoManager.close(db.getDatabaseName());
        return true;
    }

    private QueryBuilder parseQuery(Query query, QueryBuilder builder) {
        if (query != null) {

            /** VARIANT PARAMS **/

            if (query.containsKey(VariantQueryParams.REGION.key()) && !query.getString(VariantQueryParams.REGION.key()).isEmpty()) {
                List<String> stringList = query.getAsStringList(VariantQueryParams.REGION.key());
                List<Region> regions = new ArrayList<>(stringList.size());
                for (String reg : stringList) {
                    Region region = Region.parseRegion(reg);
                    regions.add(region);
                }
                getRegionFilter(regions, builder);
            }

            if (query.getString(VariantQueryParams.ID.key()) != null && !query.getString(VariantQueryParams.ID.key()).isEmpty()) {
//                List<String> ids = query.getAsStringList(VariantQueryParams.ID.key());
                String ids = query.getString(VariantQueryParams.ID.key());
                addQueryStringFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, ids, builder, QueryOperation.OR);
                addQueryStringFilter(DBObjectToVariantConverter.IDS_FIELD, ids, builder, QueryOperation.OR);
            }

            if (query.containsKey(VariantQueryParams.GENE.key())) {
                String xrefs = query.getString(VariantQueryParams.GENE.key());
                addQueryStringFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, xrefs, builder, QueryOperation.OR);
//                List<String> xrefs = query.getAsStringList(VariantQueryParams.GENE.key());
//                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
//                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
//                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, xrefs, builder, QueryOperation.OR);
            }

            if (query.containsKey(VariantQueryParams.REFERENCE.key()) && query.getString(VariantQueryParams.REFERENCE.key()) != null) {
                addQueryStringFilter(DBObjectToVariantConverter.REFERENCE_FIELD, query.getString(VariantQueryParams.REFERENCE.key()), builder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.ALTERNATE.key()) && query.getString(VariantQueryParams.ALTERNATE.key()) != null) {
                addQueryStringFilter(DBObjectToVariantConverter.ALTERNATE_FIELD, query.getString(VariantQueryParams.ALTERNATE.key()), builder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.TYPE.key()) && !query.getString(VariantQueryParams.TYPE.key()).isEmpty()) {
                addQueryStringFilter(DBObjectToVariantConverter.TYPE_FIELD, query.getString(VariantQueryParams.TYPE.key()), builder, QueryOperation.AND);
            }


            /** ANNOTATION PARAMS **/

            if (query.containsKey(VariantQueryParams.ANNOTATION_EXISTS.key())) {
                builder.and(DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.ANNOT_ID_FIELD);
                builder.exists(query.getBoolean(VariantQueryParams.ANNOTATION_EXISTS.key()));
            }

            if (query.containsKey(VariantQueryParams.ANNOT_XREF.key())) {
                String xrefs = query.getString(VariantQueryParams.GENE.key());
                addQueryStringFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, xrefs, builder, QueryOperation.OR);
//                List<String> xrefs = query.getAsStringList(VariantQueryParams.ANNOT_XREF.key());
//                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
//                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
//                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, xrefs, builder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key())) {
                String value = query.getString(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key());
                value = value.replace("SO:", "");
                addQueryIntegerFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD, value, builder, QueryOperation.AND);
//                List<String> cts = new ArrayList<>(query.getAsStringList(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key()));
//                List<Integer> ctsInteger = new ArrayList<>(cts.size());
//                for (Iterator<String> iterator = cts.iterator(); iterator.hasNext(); ) {
//                    String ct = iterator.next();
//                    if (ct.startsWith("SO:")) {
//                        ct = ct.substring(3);
//                    }
//                    try {
//                        ctsInteger.add(Integer.parseInt(ct));
//                    } catch (NumberFormatException e) {
//                        logger.error("Error parsing integer ", e);
//                        iterator.remove();  //Remove the malformed query params.
//                    }
//                }
//                query.put(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key(), cts);
//                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
//                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
//                        DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD, ctsInteger, builder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.ANNOT_BIOTYPE.key())) {
                String biotypes = query.getString(VariantQueryParams.ANNOT_BIOTYPE.key());
                addQueryStringFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.BIOTYPE_FIELD, biotypes, builder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.POLYPHEN.key())) {
                addCompQueryFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.POLYPHEN_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SCORE_SCORE_FIELD, query.getString(VariantQueryParams.POLYPHEN.key()), builder);
            }

            if (query.containsKey(VariantQueryParams.SIFT.key())) {
//                System.out.println(query.getString(VariantQueryParams.SIFT.key()));
                addCompQueryFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SIFT_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SCORE_SCORE_FIELD, query.getString(VariantQueryParams.SIFT.key()), builder);
            }

            if (query.containsKey(VariantQueryParams.PROTEIN_SUBSTITUTION.key())) {
                String value = query.getString(VariantQueryParams.PROTEIN_SUBSTITUTION.key());
//                List<String> list = new ArrayList<>(query.getAsStringList(VariantQueryParams.PROTEIN_SUBSTITUTION.key()));
                addScoreFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.PROTEIN_SUBSTITUTION_SCORE_FIELD, value, builder);
            }

            if (query.containsKey(VariantQueryParams.CONSERVATION.key())) {
                String value = query.getString(VariantQueryParams.CONSERVATION.key());
//                List<String> list = new ArrayList<>(query.getAsStringList(VariantQueryParams.CONSERVATION.key()));
                addScoreFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSERVED_REGION_SCORE_FIELD, value, builder);
            }

            if (query.containsKey(VariantQueryParams.ALTERNATE_FREQUENCY.key())) {
                String value = query.getString(VariantQueryParams.ALTERNATE_FREQUENCY.key());
//                List<String> list = new ArrayList<>(query.getAsStringList(VariantQueryParams.ALTERNATE_FREQUENCY.key()));
                addFrequencyFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                                DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCIES_FIELD,
                        DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_ALTERNATE_FREQUENCY_FIELD, value, builder); // Same method addFrequencyFilter is used for reference and allele frequencies. Need to provide the field (reference/alternate) where to check the frequency
            }

            if (query.containsKey(VariantQueryParams.REFERENCE_FREQUENCY.key())) {
                String value = query.getString(VariantQueryParams.REFERENCE_FREQUENCY.key());
//                List<String> list = new ArrayList<>(query.getAsStringList(VariantQueryParams.REFERENCE_FREQUENCY.key()));
                addFrequencyFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                                DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCIES_FIELD,
                        DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_REFERENCE_FREQUENCY_FIELD, value, builder); // Same method addFrequencyFilter is used for reference and allele frequencies. Need to provide the field (reference/alternate) where to check the frequency
            }



            /** STATS PARAMS **/

            if (query.get(VariantQueryParams.STATS_MAF.key()) != null && !query.getString(VariantQueryParams.STATS_MAF.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MAF_FIELD,
                        query.getString(VariantQueryParams.STATS_MAF.key()), builder);
            }

            if (query.get(VariantQueryParams.STATS_MGF.key()) != null && !query.getString(VariantQueryParams.STATS_MGF.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MGF_FIELD,
                        query.getString(VariantQueryParams.STATS_MGF.key()), builder);
            }

            if (query.get(VariantQueryParams.MISSING_ALLELES.key()) != null && !query.getString(VariantQueryParams.MISSING_ALLELES.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MISSALLELE_FIELD,
                        query.getString(VariantQueryParams.MISSING_ALLELES.key()), builder);
            }

            if (query.get(VariantQueryParams.MISSING_GENOTYPES.key()) != null && !query.getString(VariantQueryParams.MISSING_GENOTYPES.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MISSGENOTYPE_FIELD,
                        query.getString(VariantQueryParams.MISSING_GENOTYPES.key()), builder);
            }

            if (query.get("numgt") != null && !query.getString("numgt").isEmpty()) {
                for (String numgt : query.getAsStringList("numgt")) {
                    String[] split = numgt.split(":");
                    addCompQueryFilter(
                            DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.NUMGT_FIELD + "." + split[0],
                            split[1], builder);
                }
            }


            /** STUDIES **/
            QueryBuilder studyBuilder = QueryBuilder.start();

            if (query.containsKey(VariantQueryParams.STUDIES.key())) { // && !options.getList("studies").isEmpty() && !options.getListAs("studies", String.class).get(0).isEmpty()) {
                List<Integer> studyIds = getStudyIds(query.getAsList(VariantQueryParams.STUDIES.key()), null);
                addQueryListFilter(DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, studyIds, studyBuilder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.FILES.key())) { // && !options.getList("files").isEmpty() && !options.getListAs("files", String.class).get(0).isEmpty()) {
                addQueryListFilter(DBObjectToVariantSourceEntryConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILEID_FIELD,
                        query.getAsIntegerList(VariantQueryParams.FILES.key()), studyBuilder, QueryOperation.AND);
            }

            if (query.containsKey(VariantQueryParams.GENOTYPE.key())) {
                String sampleGenotypesCSV = query.getString(VariantQueryParams.GENOTYPE.key());

//                String AND = ",";
//                String OR = ";";
//                String IS = ":";

//                String AND = "AND";
//                String OR = "OR";
//                String IS = ":";



                // we may need to know the study type
//                studyConfigurationManager.getStudyConfiguration(1, null).getResult().get(0).


                String[] sampleGenotypesArray = sampleGenotypesCSV.split(AND);
//                System.out.println("sampleGenotypesArray = " + Arrays.toString(sampleGenotypesArray));

                for (String sampleGenotypes : sampleGenotypesArray) {
                    String[] sampleGenotype = sampleGenotypes.split(IS);
                    if(sampleGenotype.length != 2) {
                        continue;
                    }
                    int sample = Integer.parseInt(sampleGenotype[0]);
                    String[] genotypes = sampleGenotype[1].split(OR);
                    QueryBuilder genotypesBuilder = QueryBuilder.start();
                    for (String genotype : genotypes) {
                        if ("0/0".equals(genotype) || "0|0".equals(genotype)) {
                            QueryBuilder andBuilder = QueryBuilder.start();
                            List<String> otherGenotypes = Arrays.asList(
                                    "0/1", "1/0", "1/1", "-1/-1",
                                    "0|1", "1|0", "1|1", "-1|-1",
                                    "0|2", "2|0", "2|1", "1|2", "2|2",
                                    "0/2", "2/0", "2/1", "1/2", "2/2",
                                    DBObjectToSamplesConverter.UNKNOWN_GENOTYPE);
                            for (String otherGenotype : otherGenotypes) {
                                andBuilder.and(new BasicDBObject(DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD + "." + otherGenotype, new BasicDBObject("$not", new BasicDBObject("$elemMatch", new BasicDBObject("$eq", sample)))));
                            }
                            genotypesBuilder.or(andBuilder.get());
                        } else {
                            String s = DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD + "." +
                                    DBObjectToSamplesConverter.genotypeToStorageType(genotype);
                            //or [ {"samp.0|0" : { $elemMatch : { $eq : <sampleId> } } } ]
                            genotypesBuilder.or(new BasicDBObject(s, new BasicDBObject("$elemMatch", new BasicDBObject("$eq", sample))));
                        }
                    }
                    studyBuilder.and(genotypesBuilder.get());
                }
            }

            // If Study Query is used then we add a elemMatch query
            DBObject studyQuery = studyBuilder.get();
            if (studyQuery.keySet().size() != 0) {
                builder.and(DBObjectToVariantConverter.STUDIES_FIELD).elemMatch(studyQuery);
            }
        }

        logger.info("Find = " + builder.get());
        return builder;
    }

    private DBObject createProjection(Query query, QueryOptions options) {
        DBObject projection = new BasicDBObject();

        if(options == null) {
            options = new QueryOptions();
        }

        if (options.containsKey("sort")) {
            if (options.getBoolean("sort")) {
                options.put("sort", new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, 1).append(DBObjectToVariantConverter.START_FIELD, 1));
            } else {
                options.remove("sort");
            }
        }

        List<String> includeList = options.getAsStringList("include");
        if (!includeList.isEmpty()) { //Include some
            for (String s : includeList) {
                String key = DBObjectToVariantConverter.toShortFieldName(s);
                if (key != null) {
                    projection.put(key, 1);
                } else {
                    logger.warn("Unknown include field: {}", s);
                }
            }
        } else { //Include all
            for (String values : DBObjectToVariantConverter.fieldsMap.values()) {
                projection.put(values, 1);
            }
            if (options.containsKey("exclude")) { // Exclude some
                List<String> excludeList = options.getAsStringList("exclude");
                for (String s : excludeList) {
                    String key = DBObjectToVariantConverter.toShortFieldName(s);
                    if (key != null) {
                        projection.removeField(key);
                    } else {
                        logger.warn("Unknown exclude field: {}", s);
                    }
                }
            }
        }

        if (query.containsKey(VariantQueryParams.RETURNED_FILES.key()) && projection.containsField(DBObjectToVariantConverter.STUDIES_FIELD)) {
            List<Integer> files = query.getAsIntegerList(VariantQueryParams.RETURNED_FILES.key());
            projection.put(
                    DBObjectToVariantConverter.STUDIES_FIELD,
                    new BasicDBObject(
                            "$elemMatch",
                            new BasicDBObject(
                                    DBObjectToVariantSourceEntryConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILEID_FIELD,
                                    new BasicDBObject(
                                            "$in",
                                            files
                                    )
                            )
                    )
            );
        }
        if (query.containsKey(VariantQueryParams.RETURNED_STUDIES.key()) && projection.containsField(DBObjectToVariantConverter.STUDIES_FIELD)) {
            List<Integer> studiesIds = getStudyIds(query.getAsList(VariantQueryParams.RETURNED_STUDIES.key()), options);
//            List<Integer> studies = query.getAsIntegerList(VariantQueryParams.RETURNED_STUDIES.key());
            if (!studiesIds.isEmpty()) {
                projection.put(
                        DBObjectToVariantConverter.STUDIES_FIELD,
                        new BasicDBObject(
                                "$elemMatch",
                                new BasicDBObject(
                                        DBObjectToVariantSourceEntryConverter.STUDYID_FIELD,
                                        new BasicDBObject(
                                                "$in",
                                                studiesIds
                                        )
                                )
                        )
                );
            }
        }

        logger.debug("Projection: {}", projection);
        return projection;
    }

    private List<Integer> getStudyIds(List studiesNames, QueryOptions options) {
        List<Integer> studiesIds;
        studiesIds = new ArrayList<>(studiesNames.size());
        for (Object studyObj : studiesNames) {
            if (studyObj instanceof Integer) {
                studiesIds.add(((Integer) studyObj));
            } else {
                String studyName = studyObj.toString();
                try {
                    studiesIds.add(Integer.parseInt(studyName));
                } catch (NumberFormatException e) {
                    QueryResult<StudyConfiguration> result = studyConfigurationManager.getStudyConfiguration(studyName, options);
                    if (result.getResult().isEmpty()) {
                        throw new IllegalStateException("Study " + studyName + " not found");
                    }
                    studiesIds.add(result.first().getStudyId());
                }
            }
        }
        return studiesIds;
    }


    /**
     * Two steps insertion:
     *      First check that the variant and study exists making an update.
     *      For those who doesn't exist, pushes a study with the file and genotype information
     *
     *      The documents that throw a "dup key" exception are those variants that exist and have the study.
     *      Then, only for those variants, make a second update.
     *
     * *An interesting idea would be to invert this actions depending on the number of already inserted variants.
     *
     * @param loadedSampleIds Other loaded sampleIds EXCEPT those that are going to be loaded
     * @param data  Variants to insert
     */
    QueryResult insert(List<Variant> data, int fileId, DBObjectToVariantConverter variantConverter,
                       DBObjectToVariantSourceEntryConverter variantSourceEntryConverter, StudyConfiguration studyConfiguration, List<Integer> loadedSampleIds) {
        if (data.isEmpty()) {
            return new QueryResult("insertVariants");
        }
        List<DBObject> queries = new ArrayList<>(data.size());
        List<DBObject> updates = new ArrayList<>(data.size());
        Set<String> nonInsertedVariants;
        String fileIdStr = Integer.toString(fileId);

        {
            nonInsertedVariants = new HashSet<>();
            Map missingSamples = Collections.emptyMap();
            String defaultGenotype = studyConfiguration.getAttributes().getString(MongoDBVariantStorageManager.DEFAULT_GENOTYPE, "");
            if (defaultGenotype.equals(DBObjectToSamplesConverter.UNKNOWN_GENOTYPE)) {
                logger.debug("Do not need fill gaps. DefaultGenotype is UNKNOWN_GENOTYPE({}).");
            } else if (!loadedSampleIds.isEmpty()) {
                missingSamples = new BasicDBObject(DBObjectToSamplesConverter.UNKNOWN_GENOTYPE, loadedSampleIds);   // ?/?
            }
            for (Variant variant : data) {
                String id = variantConverter.buildStorageId(variant);
                for (VariantSourceEntry variantSourceEntry : variant.getSourceEntries().values()) {
                    if (!variantSourceEntry.getFileId().equals(fileIdStr)) {
                        continue;
                    }
                    int studyId = studyConfiguration.getStudyId();
                    DBObject study = variantSourceEntryConverter.convertToStorageType(variantSourceEntry);
                    DBObject genotypes = (DBObject) study.get(DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD);
                    if (genotypes != null) {        //If genotypes is null, genotypes are not suppose to be loaded
                        genotypes.putAll(missingSamples);   //Add missing samples
                    }
                    DBObject push = new BasicDBObject(DBObjectToVariantConverter.STUDIES_FIELD, study);
                    BasicDBObject update = new BasicDBObject()
                            .append("$push", push)
                            .append("$setOnInsert", variantConverter.convertToStorageType(variant));
                    if (variant.getIds() != null && !variant.getIds().isEmpty() && !variant.getIds().iterator().next().isEmpty()) {
                        update.put("$addToSet", new BasicDBObject(DBObjectToVariantConverter.IDS_FIELD, new BasicDBObject("$each", variant.getIds())));
                    }
                    // { _id: <variant_id>, "studies.sid": {$ne: <studyId> } }
                    //If the variant exists and contains the study, this find will fail, will try to do the upsert, and throw a duplicated key exception.
                    queries.add(new BasicDBObject("_id", id).append(DBObjectToVariantConverter.STUDIES_FIELD + "." + DBObjectToVariantSourceEntryConverter.STUDYID_FIELD,
                            new BasicDBObject("$ne", studyId)));
                    updates.add(update);
                }
            }
            QueryOptions options = new QueryOptions("upsert", true);
            options.put("multi", false);
            try {
                variantsCollection.update(queries, updates, options);
            } catch (BulkWriteException e) {
                for (BulkWriteError writeError : e.getWriteErrors()) {
                    if (writeError.getCode() == 11000) { //Dup Key error code
                        nonInsertedVariants.add(writeError.getMessage().split("dup key")[1].split("\"")[1]);
                    } else {
                        throw e;
                    }
                }
            }
            queries.clear();
            updates.clear();
        }

        for (Variant variant : data) {
            variant.setAnnotation(null);
            String id = variantConverter.buildStorageId(variant);

            if (nonInsertedVariants != null && !nonInsertedVariants.contains(id)) {
                continue;   //Already inserted variant
            }

            for (VariantSourceEntry variantSourceEntry : variant.getSourceEntries().values()) {
                if (!variantSourceEntry.getFileId().equals(fileIdStr)) {
                    continue;
                }

                DBObject studyObject = variantSourceEntryConverter.convertToStorageType(variantSourceEntry);
                DBObject genotypes = (DBObject) studyObject.get(DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD);
                DBObject push = new BasicDBObject();
                if (genotypes != null) { //If genotypes is null, genotypes are not suppose to be loaded
                    for (String genotype : genotypes.keySet()) {
                        push.put(DBObjectToVariantConverter.STUDIES_FIELD + ".$." + DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD + "." + genotype, new BasicDBObject("$each", genotypes.get(genotype)));
                    }
                } else {
                    push.put(DBObjectToVariantConverter.STUDIES_FIELD + ".$." + DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD, Collections.emptyMap());
                }
                push.put(DBObjectToVariantConverter.STUDIES_FIELD + ".$." + DBObjectToVariantSourceEntryConverter.FILES_FIELD, ((List) studyObject.get(DBObjectToVariantSourceEntryConverter.FILES_FIELD)).get(0));
                BasicDBObject update = new BasicDBObject(new BasicDBObject("$push", push));


                queries.add(new BasicDBObject("_id", id).append(DBObjectToVariantConverter.STUDIES_FIELD + "." + DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, studyConfiguration.getStudyId()));
                updates.add(update);

            }

        }
        if (queries.isEmpty()) {
            return new QueryResult();
        } else {
            QueryOptions options = new QueryOptions("upsert", false);
            options.put("multi", false);
            return variantsCollection.update(queries, updates, options);
        }
    }

    QueryResult<WriteResult> fillFileGaps(int fileId, List<String> chromosomes, List<Integer> fileSampleIds, StudyConfiguration studyConfiguration) {

        // { "studies.sid" : <studyId>, "studies.files.fid" : { $ne : <fileId> } },
        // { $push : {
        //      "studies.$.gt.?/?" : {$each : [ <fileSampleIds> ] }
        // } }

        if (studyConfiguration.getAttributes().getAsStringList(MongoDBVariantStorageManager.DEFAULT_GENOTYPE, "").
                equals(Collections.singletonList(DBObjectToSamplesConverter.UNKNOWN_GENOTYPE))) {
            logger.debug("Do not need fill gaps. DefaultGenotype is UNKNOWN_GENOTYPE({}).", DBObjectToSamplesConverter.UNKNOWN_GENOTYPE);
            return new QueryResult<>();
        }

        DBObject query = new BasicDBObject();
        if (chromosomes != null && !chromosomes.isEmpty()) {
            query.put(DBObjectToVariantConverter.CHROMOSOME_FIELD, new BasicDBObject("$in", chromosomes));
        }

        query.put(DBObjectToVariantConverter.STUDIES_FIELD, new BasicDBObject("$elemMatch",
                new BasicDBObject(
                        DBObjectToVariantSourceEntryConverter.STUDYID_FIELD,
                        studyConfiguration.getStudyId())
                .append(
                        DBObjectToVariantSourceEntryConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILEID_FIELD,
                        new BasicDBObject("$ne", fileId)
                )
        ));

        BasicDBObject update = new BasicDBObject("$push", new BasicDBObject()
                .append(DBObjectToVariantConverter.STUDIES_FIELD + ".$." +
                        DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD + "." +
                        DBObjectToSamplesConverter.UNKNOWN_GENOTYPE, new BasicDBObject("$each", fileSampleIds)));

        QueryOptions queryOptions = new QueryOptions("multi", true);
        logger.debug("FillGaps find : {}", query);
        logger.debug("FillGaps update : {}", update);
        return variantsCollection.update(query, update, queryOptions);
    }


    private DBObjectToVariantConverter getDbObjectToVariantConverter(Query query, QueryOptions options) {
        studyConfigurationManager.setDefaultQueryOptions(options);
        List<Integer> studyIds = getStudyIds(query.getAsList(VariantQueryParams.STUDIES.key()), options);

        DBObjectToSamplesConverter samplesConverter;
        if (studyIds.isEmpty()) {
            samplesConverter = new DBObjectToSamplesConverter(studyConfigurationManager, null);
        } else {
            List<StudyConfiguration> studyConfigurations = new LinkedList<>();
            for (Integer studyId : studyIds) {
                QueryResult<StudyConfiguration> queryResult = studyConfigurationManager.getStudyConfiguration(studyId, options);
                if (queryResult.getResult().isEmpty()) {
                    throw new IllegalStateException("iterator(): couldn't find studyConfiguration for StudyId '" + studyId + "'");
                } else {
                    studyConfigurations.add(queryResult.first());
                }
            }
            samplesConverter = new DBObjectToSamplesConverter(studyConfigurations);
        }
        if (query.containsKey(VariantQueryParams.UNKNOWN_GENOTYPE.key())) {
            samplesConverter.setReturnedUnknownGenotype(query.getString(VariantQueryParams.UNKNOWN_GENOTYPE.key()));
        }
        if (query.containsKey(VariantQueryParams.RETURNED_SAMPLES.key())) {
            samplesConverter.setReturnedSamples(new HashSet<>(query.getAsStringList(VariantQueryParams.RETURNED_SAMPLES.key())));
        }
        DBObjectToVariantSourceEntryConverter sourceEntryConverter = new DBObjectToVariantSourceEntryConverter(
                false,
                query.containsKey(VariantQueryParams.RETURNED_FILES.key()) ? query.getAsIntegerList(VariantQueryParams.RETURNED_FILES.key()) : null,
                samplesConverter
        );
        sourceEntryConverter.setStudyConfigurationManager(studyConfigurationManager);
        return new DBObjectToVariantConverter(sourceEntryConverter, new DBObjectToVariantStatsConverter(studyConfigurationManager));
    }

    @Deprecated
    private QueryBuilder addQueryStringFilter(String key, String value, final QueryBuilder builder) {
        return addQueryStringFilter(key, value, builder, QueryOperation.AND);
    }

    private QueryBuilder addQueryStringFilter(String key, String value, final QueryBuilder builder, QueryOperation op) {
        return this.<String>addQueryFilter(key, value, builder, op, Function.identity());
    }

    private QueryBuilder addQueryIntegerFilter(String key, String value, final QueryBuilder builder, QueryOperation op) {
        return this.<Integer>addQueryFilter(key, value, builder, op, elem -> {
            try {
                return Integer.parseInt(elem);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unable to parse int " + elem, e);
            }
        });
    }

    private <T> QueryBuilder addQueryFilter(String key, String value, final QueryBuilder builder, QueryOperation op, Function<String, T> map) {
        QueryOperation operation = checkOperator(value);

        Function<String[], List<?>> toList = array -> {
            ArrayList<T> list = new ArrayList<>(array.length);
            for (String elem : array) {
                list.add(map.apply(elem));
            }
            return list;
        };

        QueryBuilder _builder;
        if (op == QueryOperation.OR) {
            _builder = QueryBuilder.start(key);
        } else {
            _builder = builder.and(key);
        }

        if (operation == null) {
            _builder.is(map.apply(value));
        } else if (operation == QueryOperation.OR) {
            _builder.in(toList.apply(value.split(OR)));
        } else {
            _builder.all(toList.apply(value.split(AND)));
        }

        if (op == QueryOperation.OR) {
            builder.or(_builder.get());
        }
        return builder;
    }

    @Deprecated
    private QueryBuilder addQueryListFilter(String key, List<?> values, QueryBuilder builder, QueryOperation op) {
        if (values != null)
            if (values.size() == 1) {
                if(op == QueryOperation.AND) {
                    builder.and(key).is(values.get(0));
                } else {
                    builder.or(QueryBuilder.start(key).is(values.get(0)).get());
                }
            } else if (!values.isEmpty()) {
                if(op == QueryOperation.AND) {
                    builder.and(key).in(values);
                } else {
                    builder.or(QueryBuilder.start(key).in(values).get());
                }
            }
        return builder;
    }

    private QueryBuilder addCompQueryFilter(String key, String value, QueryBuilder builder) {
        String op = value.substring(0, 2);
        op = op.replaceFirst("[0-9]", "");
        String obj = value.replaceFirst(op, "");

        switch(op) {
            case "<":
                builder.and(key).lessThan(Double.parseDouble(obj));
                break;
            case "<=":
                builder.and(key).lessThanEquals(Double.parseDouble(obj));
                break;
            case ">":
                builder.and(key).greaterThan(Double.parseDouble(obj));
                break;
            case ">=":
                builder.and(key).greaterThanEquals(Double.parseDouble(obj));
                break;
            case "=":
            case "==":
                builder.and(key).is(Double.parseDouble(obj));
                break;
            case "!=":
                builder.and(key).notEquals(Double.parseDouble(obj));
                break;
            case "~=":
                builder.and(key).regex(Pattern.compile(obj));
                break;
        }
        return builder;
    }

    @Deprecated
    private QueryBuilder addScoreFilter(String key, List<String> list, QueryBuilder builder) {
        return addScoreFilter(key, list.stream().collect(Collectors.joining(",")), builder);
    }

    private QueryBuilder addScoreFilter(String key, String value, QueryBuilder builder) {
        final List<String> list;
        QueryOperation operation = checkOperator(value);
        list = splitValue(value, operation);

        List<DBObject> dbObjects = new ArrayList<>();
        for (String elem : list) {
            String[] populationFrequency = splitKeyValue(elem);
            if (populationFrequency.length != 2) {
                logger.error("Bad score filter: " + elem);
                throw new IllegalArgumentException("Bad score filter: " + elem);
            }
            QueryBuilder scoreBuilder = new QueryBuilder();
            scoreBuilder.and(DBObjectToVariantAnnotationConverter.SCORE_SOURCE_FIELD).is(populationFrequency[0]);
            addCompQueryFilter(DBObjectToVariantAnnotationConverter.SCORE_SCORE_FIELD, populationFrequency[1], scoreBuilder);
            dbObjects.add(new BasicDBObject(key, new BasicDBObject("$elemMatch", scoreBuilder.get())));
        }
        if (!dbObjects.isEmpty()) {
            if (operation == null || operation == QueryOperation.AND) {
                builder.and(dbObjects.toArray(new DBObject[dbObjects.size()]));
            } else {
                builder.and(new BasicDBObject("$or", dbObjects));
            }
        }
        return builder;
    }

    private List<String> splitValue(String value, QueryOperation operation) {
        List<String> list;
        if (operation == null) {
            list = Collections.singletonList(value);
        } else if (operation == QueryOperation.AND) {
            list = Arrays.asList(value.split(AND));
        } else {
            list = Arrays.asList(value.split(OR));
        }
        return list;
    }

    @Deprecated
    private QueryBuilder addFrequencyFilter(String key, String alleleFrequencyField, List<String> list, QueryBuilder builder) {
        return addFrequencyFilter(key, alleleFrequencyField, list.stream().collect(Collectors.joining(OR)), builder);
    }

    /**
     * Accepts filters with the expresion:
     *      {STUDY}:{POPULATION}{OPERATION}{VALUE}
     *
     * @param key                   PopulationFrequency schema field
     * @param alleleFrequencyField  Allele frequency schema field
     * @param value                 Value to parse
     * @param builder               QueryBuilder
     * @return                      QueryBuilder
     */
    private QueryBuilder addFrequencyFilter(String key, String alleleFrequencyField, String value, QueryBuilder builder) {

        final List<String> list;
        QueryOperation operation = checkOperator(value);
        list = splitValue(value, operation);

        List<DBObject> dbObjects = new ArrayList<>();
        for (Iterator<String> iterator = list.iterator(); iterator.hasNext(); ) {
            String elem = iterator.next();
            String[] split = elem.split(IS);
            if (split.length != 2) {
                logger.error("Bad population frequency filter: " + elem);
                throw new IllegalArgumentException("Bad population frequency filter: " + elem);
//                iterator.remove(); //Remove the malformed query params.
            }
            String study = split[0];
            String population = split[1];
            String[] populationFrequency = splitKeyValue(population);
            logger.debug("populationFrequency = " + Arrays.toString(populationFrequency));

            QueryBuilder frequencyBuilder = new QueryBuilder();
            frequencyBuilder.and(DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_STUDY_FIELD).is(study);
            frequencyBuilder.and(DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_POP_FIELD).is(populationFrequency[0]);
            addCompQueryFilter(alleleFrequencyField, populationFrequency[1], frequencyBuilder);
            dbObjects.add(new BasicDBObject(key, new BasicDBObject("$elemMatch", frequencyBuilder.get())));
        }
        if (!dbObjects.isEmpty()) {
            if (operation == null || operation == QueryOperation.AND) {
                builder.and(dbObjects.toArray(new DBObject[dbObjects.size()]));
            } else {
                builder.and(new BasicDBObject("$or", dbObjects));
            }
        }
        return builder;
    }

    private QueryBuilder getRegionFilter(Region region, QueryBuilder builder) {
        List<String> chunkIds = getChunkIds(region);
        builder.and("_at.chunkIds").in(chunkIds);
        builder.and(DBObjectToVariantConverter.END_FIELD).greaterThanEquals(region.getStart());
        builder.and(DBObjectToVariantConverter.START_FIELD).lessThanEquals(region.getEnd());
        return builder;
    }

    private QueryBuilder getRegionFilter(List<Region> regions, QueryBuilder builder) {
        if (regions != null && !regions.isEmpty()) {
            DBObject[] objects = new DBObject[regions.size()];
            int i = 0;
            for (Region region : regions) {
                if (region.getEnd() - region.getStart() < 1000000) {
                    List<String> chunkIds = getChunkIds(region);
                    DBObject regionObject = new BasicDBObject("_at.chunkIds", new BasicDBObject("$in", chunkIds))
                            .append(DBObjectToVariantConverter.START_FIELD, new BasicDBObject("$lte", region.getEnd()))
                            .append(DBObjectToVariantConverter.END_FIELD, new BasicDBObject("$gte", region.getStart()));
                    objects[i] = regionObject;
                } else {
                    DBObject regionObject = new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, region.getChromosome())
                            .append(DBObjectToVariantConverter.START_FIELD, new BasicDBObject("$lte", region.getEnd()))
                            .append(DBObjectToVariantConverter.END_FIELD, new BasicDBObject("$gte", region.getStart()));
                    objects[i] = regionObject;
                }
                i++;
            }
            builder.or(objects);
        }
        return builder;
    }

    /**
     * Checks that the option contains only one type of operations
     */
    private QueryOperation checkOperator(String s) throws IllegalArgumentException {
        boolean containsOr = s.contains(OR);
        boolean containsAnd = s.contains(AND);
        if (containsAnd && containsOr) {
            throw new IllegalArgumentException("Can't merge in the same query filter, AND and OR operators");
        } else if (containsAnd && !containsOr) {
            return QueryOperation.AND;
        } else if (containsOr && !containsAnd) {
            return QueryOperation.OR;
        } else {    // !containsOr && !containsAnd
            return null;
        }
    }

    void createIndexes(QueryOptions options) {
        logger.debug("Start creating indexes");

        DBObject onBackground = new BasicDBObject("background", true);
        DBObject sparse = new BasicDBObject("background", true).append("sparse", true);
        variantsCollection.createIndex(new BasicDBObject("_at.chunkIds", 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, 1)
                .append(DBObjectToVariantConverter.START_FIELD, 1)
                .append(DBObjectToVariantConverter.END_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.IDS_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.STUDIES_FIELD + "." + DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.STUDIES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILEID_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD
                + "." + DBObjectToVariantAnnotationConverter.XREFS_FIELD
                + "." + DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD
                + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD
                + "." + DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD
                + "." + DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCIES_FIELD
                + "." + DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_STUDY_FIELD, 1)
                .append(DBObjectToVariantConverter.ANNOTATION_FIELD
                        + "." + DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCIES_FIELD
                        + "." + DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_POP_FIELD, 1), sparse);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD
                + "." + DBObjectToVariantAnnotationConverter.CLINICAL_DATA_FIELD + ".clinvar.clinicalSignificance", 1), sparse);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MAF_FIELD, 1), onBackground);
        variantsCollection.createIndex(new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MGF_FIELD, 1), onBackground);

        logger.debug("sent order to create indices");
    }


    /**
     * This method split a typical key-value param such as 'sift<=0.2' in an array ["sift", "<=0.2"].
     * This implementation can and probably should be improved.
     * @param keyValue The keyvalue parameter to be split
     * @return An array with 2 positions for the key and value
     */
    private String[] splitKeyValue(String keyValue) {
        String[] keyValueArray = new String[2];
        String[] arr = keyValue.replaceAll("==", " ")
                .replaceAll(">=", " ")
                .replaceAll("<=", " ")
                .replaceAll("!=", " ")
                .replaceAll("~=", " ")
                .replaceAll("=", " ")
                .replaceAll("<", " ")
                .replaceAll(">", " ")
                .split(" ");
        keyValueArray[0] = arr[0];
        keyValueArray[1] = keyValue.replaceAll(arr[0], "");
        return keyValueArray;
    }

    /* *******************
     * Auxiliary methods *
     * *******************/

    private List<String> getChunkIds(Region region) {
        List<String> chunkIds = new LinkedList<>();

        int chunkSize = (region.getEnd() - region.getStart() > VariantMongoDBWriter.CHUNK_SIZE_BIG) ?
                VariantMongoDBWriter.CHUNK_SIZE_BIG : VariantMongoDBWriter.CHUNK_SIZE_SMALL;
        int ks = chunkSize / 1000;
        int chunkStart = region.getStart() / chunkSize;
        int chunkEnd = region.getEnd() / chunkSize;

        for (int i = chunkStart; i <= chunkEnd; i++) {
            String chunkId = region.getChromosome() + "_" + i + "_" + ks + "k";
            chunkIds.add(chunkId);
        }

        return chunkIds;
    }

    private int getChunkId(int position, int chunksize) {
        return position / chunksize;
    }

    private int getChunkStart(int id, int chunksize) {
        return (id == 0) ? 1 : id * chunksize;
    }

    private int getChunkEnd(int id, int chunksize) {
        return (id * chunksize) + chunksize - 1;
    }






    /* OLD METHODS*/

    @Deprecated
    private QueryBuilder parseQueryOptions(QueryOptions options, QueryBuilder builder) {
        if (options != null) {

            if (options.containsKey("sort")) {
                if (options.getBoolean("sort")) {
                    options.put("sort", new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, 1).append(DBObjectToVariantConverter.START_FIELD, 1));
                } else {
                    options.remove("sort");
                }
            }

            /** GENOMIC REGION **/

            if (options.containsKey(VariantQueryParams.REGION.key()) && !options.getString(VariantQueryParams.REGION.key()).isEmpty()) {
                List<String> stringList = options.getAsStringList(VariantQueryParams.REGION.key());
                List<Region> regions = new ArrayList<>(stringList.size());
                for (String reg : stringList) {
                    Region region = Region.parseRegion(reg);
                    regions.add(region);
                }
                getRegionFilter(regions, builder);
            }

//            if (options.containsKey(VariantQueryParams.CHROMOSOME.key())) {
//                List<String> chromosome = options.getAsStringList(VariantQueryParams.CHROMOSOME.key());
//                addQueryListFilter(DBObjectToVariantConverter.CHROMOSOME_FIELD, chromosome, builder, QueryOperation.OR);
//            }

            if (options.containsKey(VariantQueryParams.GENE.key())) {
                List<String> xrefs = options.getAsStringList(VariantQueryParams.GENE.key());
                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, xrefs, builder, QueryOperation.OR);
            }

            if (options.getString(VariantQueryParams.ID.key()) != null && !options.getString(VariantQueryParams.ID.key()).isEmpty()) { //) && !options.getString("id").isEmpty()) {
                List<String> ids = options.getAsStringList(VariantQueryParams.ID.key());
                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, ids, builder, QueryOperation.OR);
                addQueryListFilter(DBObjectToVariantConverter.IDS_FIELD, ids, builder, QueryOperation.OR);
            }


            /** VARIANT **/

            if (options.containsKey(VariantQueryParams.TYPE.key())) { // && !options.getString("type").isEmpty()) {
                addQueryStringFilter(DBObjectToVariantConverter.TYPE_FIELD, options.getString(VariantQueryParams.TYPE.key()), builder);
            }

            if (options.containsKey(VariantQueryParams.REFERENCE.key()) && options.getString(VariantQueryParams.REFERENCE.key()) != null) {
                addQueryStringFilter(DBObjectToVariantConverter.REFERENCE_FIELD, options.getString(VariantQueryParams.REFERENCE.key()), builder);
            }

            if (options.containsKey(VariantQueryParams.ALTERNATE.key()) && options.getString(VariantQueryParams.ALTERNATE.key()) != null) {
                addQueryStringFilter(DBObjectToVariantConverter.ALTERNATE_FIELD, options.getString(VariantQueryParams.ALTERNATE.key()), builder);
            }

            /** ANNOTATION **/

            if (options.containsKey(VariantQueryParams.ANNOTATION_EXISTS.key())) {
                builder.and(DBObjectToVariantConverter.ANNOTATION_FIELD).exists(options.getBoolean(VariantQueryParams.ANNOTATION_EXISTS.key()));
            }

            if (options.containsKey(VariantQueryParams.ANNOT_XREF.key())) {
                List<String> xrefs = options.getAsStringList(VariantQueryParams.ANNOT_XREF.key());
                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREFS_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.XREF_ID_FIELD, xrefs, builder, QueryOperation.AND);
            }

            if (options.containsKey(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key())) {
                List<String> cts = new ArrayList<>(options.getAsStringList(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key()));
                List<Integer> ctsInteger = new ArrayList<>(cts.size());
                for (Iterator<String> iterator = cts.iterator(); iterator.hasNext(); ) {
                    String ct = iterator.next();
                    if (ct.startsWith("SO:")) {
                        ct = ct.substring(3);
                    }
                    try {
                        ctsInteger.add(Integer.parseInt(ct));
                    } catch (NumberFormatException e) {
                        logger.error("Error parsing integer ", e);
                        iterator.remove();  //Remove the malformed query params.
                    }
                }
                options.put(VariantQueryParams.ANNOT_CONSEQUENCE_TYPE.key(), cts); //Replace the QueryOption without the malformed query params
                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD, ctsInteger, builder, QueryOperation.AND);
            }

            if (options.containsKey(VariantQueryParams.ANNOT_BIOTYPE.key())) {
                List<String> biotypes = options.getAsStringList(VariantQueryParams.ANNOT_BIOTYPE.key());
                addQueryListFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.BIOTYPE_FIELD, biotypes, builder, QueryOperation.AND);
            }

            if (options.containsKey(VariantQueryParams.POLYPHEN.key())) {
                addCompQueryFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.POLYPHEN_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SCORE_SCORE_FIELD, options.getString(VariantQueryParams.POLYPHEN.key()), builder);
            }

            if (options.containsKey(VariantQueryParams.SIFT.key())) {
                addCompQueryFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SIFT_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.SCORE_SCORE_FIELD, options.getString(VariantQueryParams.SIFT.key()), builder);
            }

            if (options.containsKey(VariantQueryParams.PROTEIN_SUBSTITUTION.key())) {
                List<String> list = new ArrayList<>(options.getAsStringList(VariantQueryParams.PROTEIN_SUBSTITUTION.key()));
                addScoreFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.PROTEIN_SUBSTITUTION_SCORE_FIELD, list, builder);
                options.put(VariantQueryParams.PROTEIN_SUBSTITUTION.key(), list); //Replace the QueryOption without the malformed query params
            }

            if (options.containsKey(VariantQueryParams.CONSERVATION.key())) {
                List<String> list = new ArrayList<>(options.getAsStringList(VariantQueryParams.CONSERVATION.key()));
                addScoreFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                        DBObjectToVariantAnnotationConverter.CONSERVED_REGION_SCORE_FIELD, list, builder);
                options.put(VariantQueryParams.CONSERVATION.key(), list); //Replace the QueryOption without the malformed query params
            }

            if (options.containsKey(VariantQueryParams.ALTERNATE_FREQUENCY.key())) {
                List<String> list = new ArrayList<>(options.getAsStringList(VariantQueryParams.ALTERNATE_FREQUENCY.key()));
                addFrequencyFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                                DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCIES_FIELD,
                        DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_ALTERNATE_FREQUENCY_FIELD, list, builder); // Same method addFrequencyFilter is used for reference and allele frequencies. Need to provide the field (reference/alternate) where to check the frequency
            }

            if (options.containsKey(VariantQueryParams.REFERENCE_FREQUENCY.key())) {
                List<String> list = new ArrayList<>(options.getAsStringList(VariantQueryParams.REFERENCE_FREQUENCY.key()));
                addFrequencyFilter(DBObjectToVariantConverter.ANNOTATION_FIELD + "." +
                                DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCIES_FIELD,
                        DBObjectToVariantAnnotationConverter.POPULATION_FREQUENCY_REFERENCE_FREQUENCY_FIELD, list, builder); // Same method addFrequencyFilter is used for reference and allele frequencies. Need to provide the field (reference/alternate) where to check the frequency
            }



            /** STATS **/

            if (options.get(VariantQueryParams.STATS_MAF.key()) != null && !options.getString(VariantQueryParams.STATS_MAF.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MAF_FIELD,
                        options.getString(VariantQueryParams.STATS_MAF.key()), builder);
            }

            if (options.get(VariantQueryParams.STATS_MGF.key()) != null && !options.getString(VariantQueryParams.STATS_MGF.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MGF_FIELD,
                        options.getString(VariantQueryParams.STATS_MGF.key()), builder);
            }

            if (options.get(VariantQueryParams.MISSING_ALLELES.key()) != null && !options.getString(VariantQueryParams.MISSING_ALLELES.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MISSALLELE_FIELD,
                        options.getString(VariantQueryParams.MISSING_ALLELES.key()), builder);
            }

            if (options.get(VariantQueryParams.MISSING_GENOTYPES.key()) != null && !options.getString(VariantQueryParams.MISSING_GENOTYPES.key()).isEmpty()) {
                addCompQueryFilter(
                        DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.MISSGENOTYPE_FIELD,
                        options.getString(VariantQueryParams.MISSING_GENOTYPES.key()), builder);
            }

            if (options.get("numgt") != null && !options.getString("numgt").isEmpty()) {
                for (String numgt : options.getAsStringList("numgt")) {
                    String[] split = numgt.split(":");
                    addCompQueryFilter(
                            DBObjectToVariantConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.NUMGT_FIELD + "." + split[0],
                            split[1], builder);
                }
            }

//            if (options.get("freqgt") != null && !options.getString("freqgt").isEmpty()) {
//                for (String freqgt : getStringList(options.get("freqgt"))) {
//                    String[] split = freqgt.split(":");
//                    addCompQueryFilter(
//                            DBObjectToVariantSourceEntryConverter.STATS_FIELD + "." + DBObjectToVariantStatsConverter.FREQGT_FIELD + "." + split[0],
//                            split[1], builder);
//                }
//            }


            /** FILES **/
            QueryBuilder fileBuilder = QueryBuilder.start();

            if (options.containsKey(VariantQueryParams.STUDIES.key())) { // && !options.getList("studies").isEmpty() && !options.getListAs("studies", String.class).get(0).isEmpty()) {
                addQueryListFilter(
                        DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, options.getAsIntegerList(VariantQueryParams.STUDIES.key()),
                        fileBuilder, QueryOperation.AND);
            }

            if (options.containsKey(VariantQueryParams.FILES.key())) { // && !options.getList("files").isEmpty() && !options.getListAs("files", String.class).get(0).isEmpty()) {
                addQueryListFilter(DBObjectToVariantSourceEntryConverter.FILES_FIELD + "." +
                                DBObjectToVariantSourceEntryConverter.FILEID_FIELD, options.getAsIntegerList(VariantQueryParams.FILES.key()),
                        fileBuilder, QueryOperation.AND);
            }

            if (options.containsKey(VariantQueryParams.GENOTYPE.key())) {
                String sampleGenotypesCSV = options.getString(VariantQueryParams.GENOTYPE.key());

//                String AND = ",";
//                String OR = ";";
//                String IS = ":";

//                String AND = "AND";
//                String OR = "OR";
//                String IS = ":";


                String[] sampleGenotypesArray = sampleGenotypesCSV.split(AND);
                for (String sampleGenotypes : sampleGenotypesArray) {
                    String[] sampleGenotype = sampleGenotypes.split(IS);
                    if(sampleGenotype.length != 2) {
                        continue;
                    }
                    int sample = Integer.parseInt(sampleGenotype[0]);
                    String[] genotypes = sampleGenotype[1].split(OR);
                    QueryBuilder genotypesBuilder = QueryBuilder.start();
                    for (String genotype : genotypes) {
                        String s = DBObjectToVariantSourceEntryConverter.GENOTYPES_FIELD + "." +
                                DBObjectToSamplesConverter.genotypeToStorageType(genotype);
                        //or [ {"samp.0|0" : { $elemMatch : { $eq : <sampleId> } } } ]
                        genotypesBuilder.or(new BasicDBObject(s, new BasicDBObject("$elemMatch", new BasicDBObject("$eq", sample))));
                    }
                    fileBuilder.and(genotypesBuilder.get());
                }
            }

            DBObject fileQuery = fileBuilder.get();
            if (fileQuery.keySet().size() != 0) {
                builder.and(DBObjectToVariantConverter.STUDIES_FIELD).elemMatch(fileQuery);
            }
        }

        logger.debug("Find = " + builder.get());
        return builder;
    }

    @Deprecated
    private DBObject parseProjectionQueryOptions(QueryOptions options) {
        DBObject projection = new BasicDBObject();

        if(options == null) {
            return projection;
        }

        List<String> includeList = options.getAsStringList("include");
        if (!includeList.isEmpty()) { //Include some
            for (String s : includeList) {
                String key = DBObjectToVariantConverter.toShortFieldName(s);
                if (key != null) {
                    projection.put(key, 1);
                } else {
                    logger.warn("Unknown include field: {}", s);
                }
            }
        } else { //Include all
            for (String values : DBObjectToVariantConverter.fieldsMap.values()) {
                projection.put(values, 1);
            }
            if (options.containsKey("exclude")) { // Exclude some
                List<String> excludeList = options.getAsStringList("exclude");
                for (String s : excludeList) {
                    String key = DBObjectToVariantConverter.toShortFieldName(s);
                    if (key != null) {
                        projection.removeField(key);
                    } else {
                        logger.warn("Unknown exclude field: {}", s);
                    }
                }
            }
        }

        if (options.containsKey(VariantQueryParams.RETURNED_FILES.key()) && projection.containsField(DBObjectToVariantConverter.STUDIES_FIELD)) {
//            List<String> files = options.getListAs(FILES, String.class);
            int file = options.getInt(VariantQueryParams.RETURNED_FILES.key());
            projection.put(
                    DBObjectToVariantConverter.STUDIES_FIELD,
                    new BasicDBObject(
                            "$elemMatch",
                            new BasicDBObject(
                                    DBObjectToVariantSourceEntryConverter.FILES_FIELD + "." + DBObjectToVariantSourceEntryConverter.FILEID_FIELD,
                                    file
//                                    new BasicDBObject(
//                                            "$in",
//                                            files
//                                    )
                            )
                    )
            );
        }

        logger.debug("Projection: {}", projection);
        return projection;
    }

    @Override
    @Deprecated
    public QueryResult<Variant> getAllVariants(QueryOptions options) {

        QueryBuilder qb = QueryBuilder.start();
        parseQueryOptions(options, qb);
        DBObject projection = parseProjectionQueryOptions(options);
        logger.debug("Query to be executed {}", qb.get().toString());

        return variantsCollection.find(qb.get(), projection, getDbObjectToVariantConverter(new Query(options), options), options);
    }

    @Override
    @Deprecated
    public QueryResult<Variant> getVariantById(String id, QueryOptions options) {

//        BasicDBObject query = new BasicDBObject(DBObjectToVariantConverter.ID_FIELD, id);

        if(options == null) {
            options = new QueryOptions(VariantQueryParams.ID.key(), id);
        } else {
            options.addToListOption(VariantQueryParams.ID.key(), id);
        }

        QueryBuilder qb = QueryBuilder.start();
        parseQueryOptions(options, qb);
        DBObject projection = parseProjectionQueryOptions(options);
        logger.debug("Query to be executed {}", qb.get().toString());

//        return coll.find(query, options, variantConverter);
        QueryResult<Variant> queryResult = variantsCollection.find(qb.get(), projection, getDbObjectToVariantConverter(new Query(options), options), options);
        queryResult.setId(id);
        return queryResult;
    }

    @Override
    @Deprecated
    public List<QueryResult<Variant>> getAllVariantsByIdList(List<String> idList, QueryOptions options) {
        List<QueryResult<Variant>> allResults = new ArrayList<>(idList.size());
        for (String r : idList) {
            QueryResult<Variant> queryResult = getVariantById(r, options);
            allResults.add(queryResult);
        }
        return allResults;
    }

    @Override
    @Deprecated
    public QueryResult<Variant> getAllVariantsByRegion(Region region, QueryOptions options) {

        QueryBuilder qb = QueryBuilder.start();
        getRegionFilter(region, qb);
        parseQueryOptions(options, qb);
        DBObject projection = parseProjectionQueryOptions(options);

        if (options == null) {
            options = new QueryOptions();
        }

        QueryResult<Variant> queryResult = variantsCollection.find(qb.get(), projection, getDbObjectToVariantConverter(new Query(options), options), options);
        queryResult.setId(region.toString());
        return queryResult;
    }

    @Override
    @Deprecated
    public List<QueryResult<Variant>> getAllVariantsByRegionList(List<Region> regionList, QueryOptions options) {
        List<QueryResult<Variant>> allResults;
        if (options == null) {
            options = new QueryOptions();
        }

        // If the users asks to sort the results, do it by chromosome and start
        if (options.getBoolean("sort", false)) {
            options.put("sort", new BasicDBObject("chr", 1).append("start", 1));
        }

        // If the user asks to merge the results, run only one query,
        // otherwise delegate in the method to query regions one by one
        if (options.getBoolean("merge", false)) {
            options.add(VariantQueryParams.REGION.key(), regionList);
            allResults = Collections.singletonList(getAllVariants(options));
        } else {
            allResults = new ArrayList<>(regionList.size());
            for (Region r : regionList) {
                QueryResult queryResult = getAllVariantsByRegion(r, options);
                queryResult.setId(r.toString());
                allResults.add(queryResult);
            }
        }
        return allResults;
    }

    @Deprecated
    public QueryResult getAllVariantsByRegionAndStudies(Region region, List<String> studyId, QueryOptions options) {

        // Aggregation for filtering when more than one study is present
        QueryBuilder qb = QueryBuilder.start(DBObjectToVariantConverter.STUDIES_FIELD + "." + DBObjectToVariantSourceEntryConverter.STUDYID_FIELD).in(studyId);
        getRegionFilter(region, qb);
        parseQueryOptions(options, qb);

        DBObject match = new BasicDBObject("$match", qb.get());
        DBObject unwind = new BasicDBObject("$unwind", "$" + DBObjectToVariantConverter.STUDIES_FIELD);
        DBObject match2 = new BasicDBObject("$match",
                new BasicDBObject(DBObjectToVariantConverter.STUDIES_FIELD + "." + DBObjectToVariantSourceEntryConverter.STUDYID_FIELD,
                        new BasicDBObject("$in", studyId)));

        logger.debug("Query to be executed {}", qb.get().toString());

        return variantsCollection.aggregate(/*"$variantsRegionStudies", */Arrays.asList(match, unwind, match2), options);
    }

    @Override
    @Deprecated
    public QueryResult getVariantFrequencyByRegion(Region region, QueryOptions options) {
        // db.variants.aggregate( { $match: { $and: [ {chr: "1"}, {start: {$gt: 251391, $lt: 2701391}} ] }}, 
        //                        { $group: { _id: { $subtract: [ { $divide: ["$start", 20000] }, { $divide: [{$mod: ["$start", 20000]}, 20000] } ] }, 
        //                                  totalCount: {$sum: 1}}})

        if(options == null) {
            options = new QueryOptions();
        }

        int interval = options.getInt("interval", 20000);

        BasicDBObject start = new BasicDBObject("$gt", region.getStart());
        start.append("$lt", region.getEnd());

        BasicDBList andArr = new BasicDBList();
        andArr.add(new BasicDBObject(DBObjectToVariantConverter.CHROMOSOME_FIELD, region.getChromosome()));
        andArr.add(new BasicDBObject(DBObjectToVariantConverter.START_FIELD, start));

        // Parsing the rest of options
        QueryBuilder qb = new QueryBuilder();
        DBObject optionsMatch = parseQueryOptions(options, qb).get();
        if(!optionsMatch.keySet().isEmpty()) {
            andArr.add(optionsMatch);
        }
        DBObject match = new BasicDBObject("$match", new BasicDBObject("$and", andArr));


//        qb.and("_at.chunkIds").in(chunkIds);
//        qb.and(DBObjectToVariantConverter.END_FIELD).greaterThanEquals(region.getStart());
//        qb.and(DBObjectToVariantConverter.START_FIELD).lessThanEquals(region.getEnd());
//
//        List<String> chunkIds = getChunkIds(region);
//        DBObject regionObject = new BasicDBObject("_at.chunkIds", new BasicDBObject("$in", chunkIds))
//                .append(DBObjectToVariantConverter.END_FIELD, new BasicDBObject("$gte", region.getStart()))
//                .append(DBObjectToVariantConverter.START_FIELD, new BasicDBObject("$lte", region.getEnd()));


        BasicDBList divide1 = new BasicDBList();
        divide1.add("$start");
        divide1.add(interval);

        BasicDBList divide2 = new BasicDBList();
        divide2.add(new BasicDBObject("$mod", divide1));
        divide2.add(interval);

        BasicDBList subtractList = new BasicDBList();
        subtractList.add(new BasicDBObject("$divide", divide1));
        subtractList.add(new BasicDBObject("$divide", divide2));


        BasicDBObject substract = new BasicDBObject("$subtract", subtractList);

        DBObject totalCount = new BasicDBObject("$sum", 1);

        BasicDBObject g = new BasicDBObject("_id", substract);
        g.append("features_count", totalCount);
        DBObject group = new BasicDBObject("$group", g);

        DBObject sort = new BasicDBObject("$sort", new BasicDBObject("_id", 1));

//        logger.info("getAllIntervalFrequencies - (>·_·)>");
//        System.out.println(options.toString());
//
//        System.out.println(match.toString());
//        System.out.println(group.toString());
//        System.out.println(sort.toString());

        long dbTimeStart = System.currentTimeMillis();
        QueryResult output = variantsCollection.aggregate(/*"$histogram", */Arrays.asList(match, group, sort), options);
        long dbTimeEnd = System.currentTimeMillis();

        Map<Long, DBObject> ids = new HashMap<>();
        // Create DBObject for intervals with features inside them
        for (DBObject intervalObj : (List<DBObject>) output.getResult()) {
            Long _id = Math.round((Double) intervalObj.get("_id"));//is double

            DBObject intervalVisited = ids.get(_id);
            if (intervalVisited == null) {
                intervalObj.put("_id", _id);
                intervalObj.put("start", getChunkStart(_id.intValue(), interval));
                intervalObj.put("end", getChunkEnd(_id.intValue(), interval));
                intervalObj.put("chromosome", region.getChromosome());
                intervalObj.put("features_count", Math.log((int) intervalObj.get("features_count")));
                ids.put(_id, intervalObj);
            } else {
                Double sum = (Double) intervalVisited.get("features_count") + Math.log((int) intervalObj.get("features_count"));
                intervalVisited.put("features_count", sum.intValue());
            }
        }

        // Create DBObject for intervals without features inside them
        BasicDBList resultList = new BasicDBList();
        int firstChunkId = getChunkId(region.getStart(), interval);
        int lastChunkId = getChunkId(region.getEnd(), interval);
        DBObject intervalObj;
        for (int chunkId = firstChunkId; chunkId <= lastChunkId; chunkId++) {
            intervalObj = ids.get((long) chunkId);
            if (intervalObj == null) {
                intervalObj = new BasicDBObject();
                intervalObj.put("_id", chunkId);
                intervalObj.put("start", getChunkStart(chunkId, interval));
                intervalObj.put("end", getChunkEnd(chunkId, interval));
                intervalObj.put("chromosome", region.getChromosome());
                intervalObj.put("features_count", 0);
            }
            resultList.add(intervalObj);
        }

        QueryResult queryResult = new QueryResult(region.toString(), ((Long) (dbTimeEnd - dbTimeStart)).intValue(),
                resultList.size(), resultList.size(), null, null, resultList);

        return queryResult;
    }

    @Deprecated
    public QueryResult getAllVariantsByGene(String geneName, QueryOptions options) {
        QueryBuilder qb = QueryBuilder.start();
        if(options == null) {
            options = new QueryOptions(VariantQueryParams.GENE.key(), geneName);
        } else {
            options.addToListOption(VariantQueryParams.GENE.key(), geneName);
        }
        options.put(VariantQueryParams.GENE.key(), geneName);
        parseQueryOptions(options, qb);
        DBObject projection = parseProjectionQueryOptions(options);
        QueryResult<Variant> queryResult = variantsCollection.find(qb.get(), projection, getDbObjectToVariantConverter(new Query(options), options), options);
        queryResult.setId(geneName);
        return queryResult;
    }

    @Override
    @Deprecated
    public QueryResult groupBy(String field, QueryOptions options) {

        String documentPath;
        switch (field) {
            case "gene":
            default:
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.GENE_NAME_FIELD;
                break;
            case "ensemblGene":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.ENSEMBL_GENE_ID_FIELD;
                break;
            case "ct":
            case "consequence_type":
                documentPath = DBObjectToVariantConverter.ANNOTATION_FIELD + "." + DBObjectToVariantAnnotationConverter.CONSEQUENCE_TYPE_FIELD + "." + DBObjectToVariantAnnotationConverter.SO_ACCESSION_FIELD;
                break;
        }

        QueryBuilder qb = QueryBuilder.start();
        parseQueryOptions(options, qb);

        DBObject match = new BasicDBObject("$match", qb.get());
        DBObject project = new BasicDBObject("$project", new BasicDBObject("field", "$"+documentPath));
        DBObject unwind = new BasicDBObject("$unwind", "$field");
        DBObject group = new BasicDBObject("$group", new BasicDBObject("_id", "$field")
//                .append("field", "$field")
                .append("count", new BasicDBObject("$sum", 1))); // sum, count, avg, ...?
        DBObject sort = new BasicDBObject("$sort", new BasicDBObject("count", options != null ? options.getInt("order", -1) : -1)); // 1 = ascending, -1 = descending
        DBObject limit = new BasicDBObject("$limit", options != null && options.getInt("limit", -1) > 0 ? options.getInt("limit") : 10);

        return variantsCollection.aggregate(Arrays.asList(match, project, unwind, group, sort, limit), options);
    }

    @Deprecated
    public QueryResult getMostAffectedGenes(int numGenes, QueryOptions options) {
        return getGenesRanking(numGenes, -1, options);
    }

    @Deprecated
    public QueryResult getLeastAffectedGenes(int numGenes, QueryOptions options) {
        return getGenesRanking(numGenes, 1, options);
    }

    @Deprecated
    private QueryResult getGenesRanking(int numGenes, int order, QueryOptions options) {
        if (options == null) {
            options = new QueryOptions();
        }
        options.put("limit", numGenes);
        options.put("order", order);

        return groupBy("gene", options);
    }

    @Deprecated
    public QueryResult getTopConsequenceTypes(int numConsequenceTypes, QueryOptions options) {
        return getConsequenceTypesRanking(numConsequenceTypes, -1, options);
    }

    @Deprecated
    public QueryResult getBottomConsequenceTypes(int numConsequenceTypes, QueryOptions options) {
        return getConsequenceTypesRanking(numConsequenceTypes, 1, options);
    }

    @Deprecated
    private QueryResult getConsequenceTypesRanking(int numConsequenceTypes, int order, QueryOptions options) {
        if (options == null) {
            options = new QueryOptions();
        }
        options.put("limit", numConsequenceTypes);
        options.put("order", order);

        return groupBy("ct", options);
    }

    @Override
    @Deprecated
    public VariantSourceDBAdaptor getVariantSourceDBAdaptor() {
        return variantSourceMongoDBAdaptor;
    }

    @Override
    public StudyConfigurationManager getStudyConfigurationManager() {
        return studyConfigurationManager;
    }

    @Override
    public void setStudyConfigurationManager(StudyConfigurationManager studyConfigurationManager) {
        this.studyConfigurationManager = studyConfigurationManager;
    }


    @Override
    @Deprecated
    public VariantDBIterator iterator(QueryOptions options) {
        return iterator(new Query(options), options);
    }

    @Deprecated
    public QueryResult insert(List<Variant> variants, StudyConfiguration studyConfiguration, QueryOptions options) {
        int fileId = options.getInt(VariantStorageManager.Options.FILE_ID.key());
        boolean includeStats = options.getBoolean(VariantStorageManager.Options.INCLUDE_STATS.key(), VariantStorageManager.Options.INCLUDE_STATS.defaultValue());
        boolean includeSrc = options.getBoolean(VariantStorageManager.Options.INCLUDE_SRC.key(), VariantStorageManager.Options.INCLUDE_SRC.defaultValue());
        boolean includeGenotypes = options.getBoolean(VariantStorageManager.Options.INCLUDE_GENOTYPES.key(), VariantStorageManager.Options.INCLUDE_GENOTYPES.defaultValue());
//        boolean compressGenotypes = options.getBoolean(VariantStorageManager.Options.COMPRESS_GENOTYPES.key(), VariantStorageManager.Options.COMPRESS_GENOTYPES.defaultValue());
//        String defaultGenotype = options.getString(MongoDBVariantStorageManager.DEFAULT_GENOTYPE, "0|0");

        DBObjectToVariantConverter variantConverter = new DBObjectToVariantConverter(null, includeStats? new DBObjectToVariantStatsConverter(studyConfigurationManager) : null);
        DBObjectToVariantSourceEntryConverter sourceEntryConverter = new DBObjectToVariantSourceEntryConverter(includeSrc,
                includeGenotypes? new DBObjectToSamplesConverter(studyConfiguration) : null);
        return insert(variants, fileId, variantConverter, sourceEntryConverter, studyConfiguration, getLoadedSamples(fileId, studyConfiguration));
    }

    public static List<Integer> getLoadedSamples(int fileId, StudyConfiguration studyConfiguration) {
        List<Integer> loadedSampleIds = new LinkedList<>();
        for (Integer indexedFile : studyConfiguration.getIndexedFiles()) {
            if (indexedFile.equals(fileId)) {
                continue;
            } else {
                loadedSampleIds.addAll(studyConfiguration.getSamplesInFiles().get(indexedFile));
            }
        }
        loadedSampleIds.removeAll(studyConfiguration.getSamplesInFiles().get(fileId));
        return loadedSampleIds;
    }

    @Override
    @Deprecated
    public QueryResult updateStats(List<VariantStatsWrapper> variantStatsWrappers, int studyId, QueryOptions queryOptions) {
        DBCollection coll = db.getDb().getCollection(collectionName);
        BulkWriteOperation builder = coll.initializeUnorderedBulkOperation();

        long start = System.nanoTime();
        DBObjectToVariantStatsConverter statsConverter = new DBObjectToVariantStatsConverter(studyConfigurationManager);
//        VariantSource variantSource = queryOptions.get(VariantStorageManager.VARIANT_SOURCE, VariantSource.class);
        int fileId = queryOptions.getInt(VariantStorageManager.Options.FILE_ID.key());
        DBObjectToVariantConverter variantConverter = getDbObjectToVariantConverter(new Query(queryOptions), queryOptions);
        //TODO: Use the StudyConfiguration to change names to ids

        // TODO make unset of 'st' if already present?
        for (VariantStatsWrapper wrapper : variantStatsWrappers) {
            Map<String, VariantStats> cohortStats = wrapper.getCohortStats();
            Iterator<VariantStats> iterator = cohortStats.values().iterator();
            VariantStats variantStats = iterator.hasNext()? iterator.next() : null;
            List<DBObject> cohorts = statsConverter.convertCohortsToStorageType(cohortStats, studyId);   // TODO remove when we remove fileId
//            List cohorts = statsConverter.convertCohortsToStorageType(cohortStats, variantSource.getStudyId());   // TODO use when we remove fileId

            // add cohorts, overwriting old values if that cid, fid and sid already exists: remove and then add
            // db.variants.update(
            //      {_id:<id>},
            //      {$pull:{st:{cid:{$in:["Cohort 1","cohort 2"]}, fid:{$in:["file 1", "file 2"]}, sid:{$in:["study 1", "study 2"]}}}}
            // )
            // db.variants.update(
            //      {_id:<id>},
            //      {$push:{st:{$each: [{cid:"Cohort 1", fid:"file 1", ... , defaultValue:3},{cid:"Cohort 2", ... , defaultValue:3}] }}}
            // )

            if (!cohorts.isEmpty()) {
                String id = variantConverter.buildStorageId(wrapper.getChromosome(), wrapper.getPosition(),
                        variantStats.getRefAllele(), variantStats.getAltAllele());

                List<String> cohortIds = new ArrayList<>(cohorts.size());
                List<Integer> fileIds = new ArrayList<>(cohorts.size());
                List<Integer> studyIds = new ArrayList<>(cohorts.size());
                for (DBObject cohort : cohorts) {
                    cohortIds.add((String) cohort.get(DBObjectToVariantStatsConverter.COHORT_ID));
//                    fileIds.add((Integer) cohort.get(DBObjectToVariantStatsConverter.FILE_ID));
                    studyIds.add((Integer) cohort.get(DBObjectToVariantStatsConverter.STUDY_ID));
                }

                DBObject find = new BasicDBObject("_id", id);

                DBObject update = new BasicDBObject("$pull",
                        new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                                new BasicDBObject()
                                        .append(
                                                DBObjectToVariantStatsConverter.STUDY_ID,
                                                new BasicDBObject("$in", studyIds))
//                                        .append(
//                                                DBObjectToVariantStatsConverter.FILE_ID,
//                                                new BasicDBObject("$in", fileIds))
                                        .append(
                                                DBObjectToVariantStatsConverter.COHORT_ID,
                                                new BasicDBObject("$in", cohortIds))));

                builder.find(find).updateOne(update);

                DBObject push = new BasicDBObject("$push",
                        new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                                new BasicDBObject("$each", cohorts)));

                builder.find(find).update(push);
            }
        }

        // TODO handle if the variant didn't had that studyId in the files array
        // TODO check the substitution is done right if the stats are already present
        BulkWriteResult writeResult = builder.execute();
        int writes = writeResult.getModifiedCount();

        return new QueryResult<>("", ((int) (System.nanoTime() - start)), writes, writes, "", "", Collections.singletonList(writeResult));
    }

    @Deprecated
    QueryResult deleteStats(int studyId, String cohortId) {

        // { st : { $elemMatch : {  sid : <studyId>, cid : <cohortId> } } }
        DBObject query = new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                new BasicDBObject("$elemMatch",
                        new BasicDBObject(DBObjectToVariantStatsConverter.STUDY_ID, studyId)
                                .append(DBObjectToVariantStatsConverter.COHORT_ID, cohortId)));

        // { $pull : { st : {  sid : <studyId>, cid : <cohortId> } } }
        BasicDBObject update = new BasicDBObject(
                "$pull",
                new BasicDBObject(DBObjectToVariantConverter.STATS_FIELD,
                        new BasicDBObject(DBObjectToVariantStatsConverter.STUDY_ID, studyId)
                                .append(DBObjectToVariantStatsConverter.COHORT_ID, cohortId)
                )
        );
        logger.debug("deleteStats: query = {}", query);
        logger.debug("deleteStats: update = {}", update);

        return variantsCollection.update(query, update, new QueryOptions("multi", true));
    }

    @Deprecated
    public QueryResult deleteStudy(int studyId, QueryOptions queryOptions) {

        if (queryOptions == null) {
            queryOptions = new QueryOptions();
        }
        queryOptions.put(VariantQueryParams.STUDIES.key(), studyId);
        DBObject query = parseQueryOptions(queryOptions, new QueryBuilder()).get();

        // { $pull : { files : {  sid : <studyId> } } }
        BasicDBObject update = new BasicDBObject(
                "$pull",
                new BasicDBObject(
                        DBObjectToVariantConverter.STUDIES_FIELD,
                        new BasicDBObject(
                                DBObjectToVariantSourceEntryConverter.STUDYID_FIELD, studyId
                        )
                )
        );
        QueryResult<WriteResult> result = variantsCollection.update(query, update, new QueryOptions("multi", true));

        logger.debug("deleteStudy: query = {}", query);
        logger.debug("deleteStudy: update = {}", update);

        if (queryOptions.getBoolean("purge", false)) {
            BasicDBObject purgeQuery = new BasicDBObject(DBObjectToVariantConverter.STUDIES_FIELD, new BasicDBObject("$size", 0));
            variantsCollection.remove(purgeQuery, new QueryOptions("multi", true));
        }

        return result;
    }

    @Deprecated
    QueryResult deleteAnnotation(int annotationId, int studyId, QueryOptions queryOptions) {

        if (queryOptions == null) {
            queryOptions = new QueryOptions();
        }
        queryOptions.put(VariantQueryParams.STUDIES.key(), studyId);
        DBObject query = parseQueryOptions(queryOptions, new QueryBuilder()).get();

        DBObject update = new BasicDBObject("$unset", new BasicDBObject(DBObjectToVariantConverter.ANNOTATION_FIELD, ""));

        logger.debug("deleteAnnotation: query = {}", query);
        logger.debug("deleteAnnotation: update = {}", update);

        return variantsCollection.update(query, update, new QueryOptions("multi", true));
    }

}
