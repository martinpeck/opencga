/*
 * Copyright 2015-2017 OpenCB
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

package org.opencb.opencga.storage.core.manager.variant;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.opencb.biodata.models.clinical.pedigree.Pedigree;
import org.opencb.biodata.models.commons.Phenotype;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.tools.pedigree.ModeOfInheritance;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryParam;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.db.api.CohortDBAdaptor;
import org.opencb.opencga.catalog.db.api.FileDBAdaptor;
import org.opencb.opencga.catalog.db.api.ProjectDBAdaptor;
import org.opencb.opencga.catalog.db.api.SampleDBAdaptor;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.AbstractManager;
import org.opencb.opencga.catalog.managers.CatalogManager;
import org.opencb.opencga.catalog.managers.FamilyManager;
import org.opencb.opencga.catalog.managers.ResourceManager;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.storage.core.manager.CatalogUtils;
import org.opencb.opencga.storage.core.variant.adaptors.VariantField;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryException;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.opencb.commons.datastore.core.QueryOptions.INCLUDE;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam.*;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils.*;

/**
 * Created on 28/02/17.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class VariantCatalogQueryUtils extends CatalogUtils {

    public static final String SAMPLE_ANNOTATION_DESC =
            "Selects some samples using metadata information from Catalog. e.g. age>20;phenotype=hpo:123,hpo:456;name=smith";
    public static final QueryParam SAMPLE_ANNOTATION
            = QueryParam.create("sampleAnnotation", SAMPLE_ANNOTATION_DESC, QueryParam.Type.TEXT_ARRAY);
    public static final String PROJECT_DESC = "Project [user@]project where project can be either the ID or the alias";
    public static final QueryParam PROJECT = QueryParam.create("project", PROJECT_DESC, QueryParam.Type.TEXT_ARRAY);

    public static final String FAMILY_DESC = "Filter variants where any of the samples from the given family contains the variant "
            + "(HET or HOM_ALT)";
    public static final QueryParam FAMILY =
            QueryParam.create("family", FAMILY_DESC, QueryParam.Type.TEXT);
    public static final String FAMILY_PHENOTYPE_DESC = "Specify the phenotype to use for the mode  of inheritance";
    public static final QueryParam FAMILY_PHENOTYPE =
            QueryParam.create("familyPhenotype", FAMILY_PHENOTYPE_DESC, QueryParam.Type.TEXT);
    public static final String MODE_OF_INHERITANCE_DESC = "Filter by mode of inheritance from a given family. Accepted values: "
            + "[ monoallelic, monoallelicIncompletePenetrance, biallelic, "
            + "biallelicIncompletePenetrance, XlinkedBiallelic, XlinkedMonoallelic, Ylinked ]";
    public static final QueryParam MODE_OF_INHERITANCE =
            QueryParam.create("modeOfInheritance", MODE_OF_INHERITANCE_DESC, QueryParam.Type.TEXT);
    public static final String PANEL_DESC = "Filter by genes from the given disease panel";
    public static final QueryParam PANEL =
            QueryParam.create("panel", PANEL_DESC, QueryParam.Type.TEXT);

    private final StudyFilterValidator studyFilterValidator;
    private final FileFilterValidator fileFilterValidator;
    private final SampleFilterValidator sampleFilterValidator;
    private final GenotypeFilterValidator genotypeFilterValidator;
    private final CohortFilterValidator cohortFilterValidator;
    //    public static final QueryParam SAMPLE_FILTER_GENOTYPE = QueryParam.create("sampleFilterGenotype", "", QueryParam.Type.TEXT_ARRAY);

    public VariantCatalogQueryUtils(CatalogManager catalogManager) {
        super(catalogManager);
        studyFilterValidator = new StudyFilterValidator();
        fileFilterValidator = new FileFilterValidator();
        sampleFilterValidator = new SampleFilterValidator();
        genotypeFilterValidator = new GenotypeFilterValidator();
        cohortFilterValidator = new CohortFilterValidator();
    }

    public static VariantQueryException wrongReleaseException(VariantQueryParam param, String value, int release) {
        return new VariantQueryException("Unable to have '" + value + "' within '" + param.key() + "' filter. "
                + "Not part of release " + release);
    }

    /**
     * Transforms a high level Query to a query fully understandable by storage.
     * @param query     High level query. Will be modified by the method.
     * @param sessionId User's session id
     * @return          Modified input query (same instance)
     * @throws CatalogException if there is any catalog error
     */
    public Query parseQuery(Query query, String sessionId) throws CatalogException {
        if (query == null) {
            // Nothing to do!
            return null;
        }
        List<String> studies = getStudies(query, sessionId);
        String defaultStudyStr = getDefaultStudyId(studies);
        Integer release = getReleaseFilter(query, sessionId);

        studyFilterValidator.processFilter(query, VariantQueryParam.STUDY, release, sessionId, defaultStudyStr);
        studyFilterValidator.processFilter(query, VariantQueryParam.INCLUDE_STUDY, release, sessionId, defaultStudyStr);
        sampleFilterValidator.processFilter(query, VariantQueryParam.SAMPLE, release, sessionId, defaultStudyStr);
        sampleFilterValidator.processFilter(query, VariantQueryParam.INCLUDE_SAMPLE, release, sessionId, defaultStudyStr);
        genotypeFilterValidator.processFilter(query, VariantQueryParam.GENOTYPE, release, sessionId, defaultStudyStr);
        fileFilterValidator.processFilter(query, VariantQueryParam.FILE, release, sessionId, defaultStudyStr);
        fileFilterValidator.processFilter(query, VariantQueryParam.INCLUDE_FILE, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.COHORT, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.STATS_MAF, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.STATS_MGF, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.MISSING_ALLELES, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.MISSING_GENOTYPES, release, sessionId, defaultStudyStr);

        if (release != null) {
            // If no list of included files is specified:
            if (VariantQueryUtils.isIncludeFilesDefined(query, Collections.singleton(VariantField.STUDIES_FILES))) {
                List<String> includeFiles = new ArrayList<>();
                QueryOptions fileOptions = new QueryOptions(INCLUDE, FileDBAdaptor.QueryParams.UID.key());
                Query fileQuery = new Query(FileDBAdaptor.QueryParams.RELEASE.key(), "<=" + release)
                        .append(FileDBAdaptor.QueryParams.INDEX_STATUS_NAME.key(), FileIndex.IndexStatus.READY);

                for (String study : studies) {
                    for (File file : catalogManager.getFileManager().get(study, fileQuery, fileOptions, sessionId)
                            .getResult()) {
                        includeFiles.add(file.getName());
                    }
                }
                query.append(VariantQueryParam.INCLUDE_FILE.key(), includeFiles);
            }
            // If no list of included samples is specified:
            if (!VariantQueryUtils.isIncludeSamplesDefined(query, Collections.singleton(VariantField.STUDIES_SAMPLES_DATA))) {
                List<String> includeSamples = new ArrayList<>();
                Query sampleQuery = new Query(SampleDBAdaptor.QueryParams.RELEASE.key(), "<=" + release);
                QueryOptions sampleOptions = new QueryOptions(INCLUDE, SampleDBAdaptor.QueryParams.UID.key());

                for (String study : studies) {
                    Query cohortQuery = new Query(CohortDBAdaptor.QueryParams.ID.key(), StudyEntry.DEFAULT_COHORT);
                    QueryOptions cohortOptions = new QueryOptions(INCLUDE, CohortDBAdaptor.QueryParams.SAMPLE_UIDS.key());
                    // Get default cohort. It contains the list of indexed samples. If it doesn't exist, or is empty, do not include any
                    // sample from this study.
                    QueryResult<Cohort> result = catalogManager.getCohortManager().get(study, cohortQuery, cohortOptions,
                            sessionId);
                    if (result.first() != null || result.first().getSamples().isEmpty()) {
                        Set<String> sampleIds = result
                                .first()
                                .getSamples()
                                .stream()
                                .map(Sample::getId)
                                .collect(Collectors.toSet());
                        for (Sample s : catalogManager.getSampleManager().get(study, sampleQuery, sampleOptions,
                                sessionId).getResult()) {
                            if (sampleIds.contains(s.getId())) {
                                includeSamples.add(s.getId());
                            }
                        }
                    }
                }
                query.append(VariantQueryParam.INCLUDE_SAMPLE.key(), includeSamples);
            }
        }

        if (isValidParam(query, SAMPLE_ANNOTATION)) {
            String sampleAnnotation = query.getString(SAMPLE_ANNOTATION.key());
            Query sampleQuery = parseSampleAnnotationQuery(sampleAnnotation, SampleDBAdaptor.QueryParams::getParam);
            sampleQuery.append(SampleDBAdaptor.QueryParams.STUDY_UID.key(), defaultStudyStr);
            QueryOptions options = new QueryOptions(INCLUDE, SampleDBAdaptor.QueryParams.UID);
            List<String> sampleIds = catalogManager.getSampleManager().get(defaultStudyStr, sampleQuery, options, sessionId)
                    .getResult()
                    .stream()
                    .map(Sample::getId)
                    .collect(Collectors.toList());

            if (sampleIds.isEmpty()) {
                throw new VariantQueryException("Could not found samples with this annotation: " + sampleAnnotation);
            }

            String genotype = query.getString("sampleAnnotationGenotype");
//            String genotype = query.getString(VariantDBAdaptor.VariantQueryParams.GENOTYPE.key());
            if (StringUtils.isNotBlank(genotype)) {
                StringBuilder sb = new StringBuilder();
                for (String sampleId : sampleIds) {
                    sb.append(sampleId).append(IS)
                            .append(genotype)
                            .append(AND); // TODO: Should this be an AND (;) or an OR (,)?
                }
                query.append(VariantQueryParam.GENOTYPE.key(), sb.toString());
                if (!isValidParam(query, VariantQueryParam.INCLUDE_SAMPLE)) {
                    query.append(VariantQueryParam.INCLUDE_SAMPLE.key(), sampleIds);
                }
            } else {
                query.append(VariantQueryParam.SAMPLE.key(), sampleIds);
            }
        }


        if (isValidParam(query, FAMILY)) {
            String familyId = query.getString(FAMILY.key());
            if (StringUtils.isEmpty(defaultStudyStr)) {
                throw VariantQueryException.missingStudyFor("family", familyId, null);
            }
            Family family = catalogManager.getFamilyManager().get(defaultStudyStr, familyId, null, sessionId).first();

            if (family.getMembers().isEmpty()) {
                throw VariantQueryException.malformedParam(FAMILY, familyId, "Empty family");
            }

            Set<Long> indexedSampleUids = catalogManager.getCohortManager()
                    .get(defaultStudyStr, StudyEntry.DEFAULT_COHORT,
                            new QueryOptions(INCLUDE, CohortDBAdaptor.QueryParams.SAMPLE_UIDS.key()), sessionId)
                    .first()
                    .getSamples()
                    .stream()
                    .map(Sample::getUid).collect(Collectors.toSet());

            boolean multipleSamplesPerIndividual = false;
            List<Long> sampleUids = new ArrayList<>();
            for (Individual member : family.getMembers()) {
                int numSamples = 0;
                for (Sample sample : member.getSamples()) {
                    long uid = sample.getUid();
                    if (indexedSampleUids.contains(uid)) {
                        numSamples++;
                        sampleUids.add(uid);
                    }
                }
                multipleSamplesPerIndividual |= numSamples > 1;
            }
            if (sampleUids.size() == 1) {
                throw VariantQueryException.malformedParam(FAMILY, familyId, "Only one member of the family is indexed in storage");
            } else if (sampleUids.isEmpty()) {
                throw VariantQueryException.malformedParam(FAMILY, familyId, "Family not indexed in storage");
            }

            List<Sample> samples = catalogManager.getSampleManager()
                    .get(defaultStudyStr,
                            new Query(SampleDBAdaptor.QueryParams.UID.key(), sampleUids),
                            new QueryOptions(INCLUDE, Arrays.asList(
                                    SampleDBAdaptor.QueryParams.ID.key(),
                                    SampleDBAdaptor.QueryParams.UID.key())), sessionId)
                    .getResult();

            // If filter FAMILY is among with MODE_OF_INHERITANCE, fill the list of genotypes.
            // Otherwise, add the samples from the family to the SAMPLES query param.
            if (isValidParam(query, MODE_OF_INHERITANCE)) {
                if (isValidParam(query, GENOTYPE)) {
                    throw VariantQueryException.malformedParam(MODE_OF_INHERITANCE, query.getString(MODE_OF_INHERITANCE.key()),
                            "Can not be used along with filter \"" + GENOTYPE.key() + '"');
                }
                if (isValidParam(query, SAMPLE)) {
                    throw VariantQueryException.malformedParam(MODE_OF_INHERITANCE, query.getString(MODE_OF_INHERITANCE.key()),
                            "Can not be used along with filter \"" + SAMPLE.key() + '"');
                }
                if (family.getPhenotypes().isEmpty()) {
                    throw VariantQueryException.malformedParam(FAMILY, familyId, "Family doesn't have phenotypes");
                }
                if (multipleSamplesPerIndividual) {
                    throw VariantQueryException.malformedParam(FAMILY, familyId,
                            "Some individuals from this family have multiple indexed samples");
                }
                Phenotype phenotype;
                if (isValidParam(query, FAMILY_PHENOTYPE)) {
                    String phenotypeId = query.getString(FAMILY_PHENOTYPE.key());
                    phenotype = family.getPhenotypes()
                            .stream()
                            .filter(familyPhenotype -> familyPhenotype.getId().equals(phenotypeId))
                            .findFirst()
                            .orElse(null);
                    if (phenotype == null) {
                        throw VariantQueryException.malformedParam(FAMILY_PHENOTYPE, phenotypeId,
                                "Available phenotypes: " + family.getPhenotypes()
                                        .stream()
                                        .map(Phenotype::getId)
                                        .collect(Collectors.toList()));
                    }

                } else {
                    if (family.getPhenotypes().size() > 1) {
                        throw VariantQueryException.missingParam(FAMILY_PHENOTYPE,
                                "More than one phenotype found for the family \"" + familyId + "\". "
                                        + "Available phenotypes: " + family.getPhenotypes()
                                        .stream()
                                        .map(Phenotype::getId)
                                        .collect(Collectors.toList()));
                    }
                    phenotype = family.getPhenotypes().get(0);
                }
                Pedigree pedigree = FamilyManager.getPedigreeFromFamily(family);

                String moiString = query.getString(MODE_OF_INHERITANCE.key());

                Map<String, List<String>> genotypes;
                switch (moiString) {
                    case "MONOALLELIC":
                    case "monoallelic":
                    case "dominant":
                        genotypes = ModeOfInheritance.dominant(pedigree, phenotype, false);
                        break;
                    case "MONOALLELIC_INCOMPLETE_PENETRANCE":
                    case "monoallelicIncompletePenetrance":
                        genotypes = ModeOfInheritance.dominant(pedigree, phenotype, true);
                        break;
                    case "BIALLELIC":
                    case "biallelic":
                    case "recesive":
                        genotypes = ModeOfInheritance.recessive(pedigree, phenotype, false);
                        break;
                    case "BIALLELIC_INCOMPLETE_PENETRANCE":
                    case "biallelicIncompletePenetrance":
                        genotypes = ModeOfInheritance.recessive(pedigree, phenotype, true);
                        break;
                    case "XLINKED_MONOALLELIC":
                    case "XlinkedMonoallelic":
                        genotypes = ModeOfInheritance.xLinked(pedigree, phenotype, true);
                        break;
                    case "XLINKED_BIALLELIC":
                    case "XlinkedBiallelic":
                        genotypes = ModeOfInheritance.xLinked(pedigree, phenotype, false);
                        break;
                    case "YLINKED":
                    case "Ylinked":
                        genotypes = ModeOfInheritance.yLinked(pedigree, phenotype);
                        break;
                    default:
                        throw VariantQueryException.malformedParam(MODE_OF_INHERITANCE, moiString);
                }

                StringBuilder sb = new StringBuilder();

                Map<String, Long> individualToSampleUid = new HashMap<>();
                for (Individual member : family.getMembers()) {
                    for (Sample sample : member.getSamples()) {
                        long uid = sample.getUid();
                        if (indexedSampleUids.contains(uid)) {
                            individualToSampleUid.put(member.getId(), uid);
                        }
                    }
                }
                Map<Long, String> samplesUidToId = new HashMap<>();
                for (Sample sample : samples) {
                    samplesUidToId.put(sample.getUid(), sample.getId());
                }

                Map<String, String> individualToSample = new HashMap<>();
                for (Map.Entry<String, Long> entry : individualToSampleUid.entrySet()) {
                    individualToSample.put(entry.getKey(), samplesUidToId.get(entry.getValue()));
                }

                boolean firstSample = true;
                for (Map.Entry<String, List<String>> entry : genotypes.entrySet()) {
                    if (firstSample) {
                        firstSample = false;
                    } else {
                        sb.append(AND);
                    }
                    sb.append(individualToSample.get(entry.getKey())).append(IS);

                    boolean firstGenotype = true;
                    for (String gt : entry.getValue()) {
                        if (firstGenotype) {
                            firstGenotype = false;
                        } else {
                            sb.append(OR);
                        }
                        sb.append(gt);
                    }
                }

                query.put(GENOTYPE.key(), sb.toString());

            } else {
                if (isValidParam(query, FAMILY_PHENOTYPE)) {
                    throw VariantQueryException.malformedParam(FAMILY_PHENOTYPE, query.getString(FAMILY_PHENOTYPE.key()),
                            "Require parameter \"" + FAMILY.key() + "\" and \"" + MODE_OF_INHERITANCE.key() + "\" to use \""
                                    + FAMILY_PHENOTYPE.key() + "\".");
                }

                List<String> sampleIds = new ArrayList<>();
                if (isValidParam(query, VariantQueryParam.SAMPLE)) {
                    Pair<QueryOperation, List<String>> pair = splitValue(query.getString(VariantQueryParam.SAMPLE.key()));
                    if (pair.getKey().equals(QueryOperation.AND)) {
                        throw VariantQueryException.malformedParam(VariantQueryParam.SAMPLE, familyId,
                                "Can not be used along with filter \"" + FAMILY.key() + "\" with operator AND (" + AND + ").");
                    }
                    sampleIds.addAll(pair.getValue());
                }

                for (Sample sample : samples) {
                    sampleIds.add(sample.getId());
                }

                query.put(VariantQueryParam.SAMPLE.key(), String.join(OR, sampleIds));
            }
        } else if (isValidParam(query, MODE_OF_INHERITANCE)) {
            throw VariantQueryException.malformedParam(MODE_OF_INHERITANCE, query.getString(MODE_OF_INHERITANCE.key()),
                    "Require parameter \"" + FAMILY.key() + "\" to use \"" + MODE_OF_INHERITANCE.toString() + "\".");
        } else if (isValidParam(query, FAMILY_PHENOTYPE)) {
            throw VariantQueryException.malformedParam(FAMILY_PHENOTYPE, query.getString(FAMILY_PHENOTYPE.key()),
                    "Require parameter \"" + FAMILY.key() + "\" and \"" + MODE_OF_INHERITANCE.key() + "\" to use \""
                            + FAMILY_PHENOTYPE.toString() + "\".");
        }

        if (isValidParam(query, PANEL)) {
            String panelId = query.getString(PANEL.key());
            if (StringUtils.isEmpty(defaultStudyStr)) {
                throw VariantQueryException.missingStudyFor("panel", panelId, null);
            }
            Panel panel = catalogManager.getPanelManager().get(defaultStudyStr, panelId, null, sessionId).first();

            List<String> geneNames = new ArrayList<>(panel.getDiseasePanel().getGenes().size());
            for (org.opencb.biodata.models.clinical.interpretation.DiseasePanel.GenePanel genePanel : panel.getDiseasePanel().getGenes()) {
                geneNames.add(genePanel.getName());
            }

            if (isValidParam(query, GENE)) {
                geneNames.addAll(query.getAsStringList(GENE.key()));
            }
            query.put(GENE.key(), geneNames);

        }

        return query;
    }

    public String getDefaultStudyId(Collection<String> studies) throws CatalogException {
        final String defaultStudyId;
        if (studies.size() == 1) {
            defaultStudyId = studies.iterator().next();
        } else {
            defaultStudyId = null;
        }
        return defaultStudyId;
    }

    public Integer getReleaseFilter(Query query, String sessionId) throws CatalogException {
        Integer release;
        if (isValidParam(query, VariantQueryParam.RELEASE)) {
            release = query.getInt(VariantQueryParam.RELEASE.key(), -1);
            if (release <= 0) {
                throw VariantQueryException.malformedParam(VariantQueryParam.RELEASE, query.getString(VariantQueryParam.RELEASE.key()));
            }
            Project project = getProjectFromQuery(query, sessionId,
                    new QueryOptions(INCLUDE, ProjectDBAdaptor.QueryParams.CURRENT_RELEASE.key()));
            int currentRelease = project.getCurrentRelease();
            if (release > currentRelease) {
                throw VariantQueryException.malformedParam(VariantQueryParam.RELEASE, query.getString(VariantQueryParam.RELEASE.key()));
            } else if (release == currentRelease) {
                // Using latest release. We don't need to filter by release!
                release = null;
            } // else, filter by release

        } else {
            release = null;
        }
        return release;
    }

    public abstract class FilterValidator {
        protected final QueryOptions RELEASE_OPTIONS = new QueryOptions(INCLUDE, Arrays.asList(
                FileDBAdaptor.QueryParams.ID.key(),
                FileDBAdaptor.QueryParams.NAME.key(),
                FileDBAdaptor.QueryParams.INDEX.key(),
                FileDBAdaptor.QueryParams.RELEASE.key()));

        /**
         * Splits the value from the query (if any) and translates the IDs to numerical Ids.
         * If a release value is given, checks that every element is part of that release.
         * @param query        Query with the data
         * @param param        Param to modify
         * @param release      Release filter, if any
         * @param sessionId    SessionId
         * @param defaultStudy Default study
         * @throws CatalogException if there is any catalog error
         */
        protected void processFilter(Query query, VariantQueryParam param, Integer release, String sessionId, String defaultStudy)
                throws CatalogException {
            if (VariantQueryUtils.isValidParam(query, param)) {
                String valuesStr = query.getString(param.key());
                // Do not try to transform ALL or NONE values
                if (isNoneOrAll(valuesStr)) {
                    return;
                }
                QueryOperation queryOperation = getQueryOperation(valuesStr);
                List<String> rawValues = splitValue(valuesStr, queryOperation);
                List<String> values = getValuesToValidate(rawValues);
                List<String> validatedValues = validate(defaultStudy, values, release, param, sessionId);

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rawValues.size(); i++) {
                    String rawValue = rawValues.get(i);
                    String value = values.get(i);
                    String validatedValue = validatedValues.get(i);
                    if (sb.length() > 0) {
                        sb.append(queryOperation.separator());
                    }

                    if (!value.equals(validatedValue)) {
                        sb.append(StringUtils.replace(rawValue, value, validatedValue, 1));
                    } else {
                        sb.append(rawValue);
                    }

                }
                query.put(param.key(), sb.toString());
            }
        }

        protected QueryOperation getQueryOperation(String valuesStr) {
            QueryOperation queryOperation = VariantQueryUtils.checkOperator(valuesStr);
            if (queryOperation == null) {
                queryOperation = QueryOperation.OR;
            }
            return queryOperation;
        }

        protected List<String> splitValue(String valuesStr, QueryOperation queryOperation) {
            return VariantQueryUtils.splitValue(valuesStr, queryOperation);
        }

        protected List<String> getValuesToValidate(List<String> rawValues) {
            return rawValues.stream()
                    .map(value -> {
                        value = isNegated(value) ? removeNegation(value) : value;
                        String[] strings = VariantQueryUtils.splitOperator(value);
                        boolean withComparisionOperator = strings[0] != null;
                        if (withComparisionOperator) {
                            value = strings[0];
                        }
                        return value;
                    })
                    .collect(Collectors.toList());
        }


        protected abstract List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                                 String sessionId)
                throws CatalogException;

        protected final void checkRelease(Integer release, int resourceRelease, VariantQueryParam param, String value) {
            if (release != null && resourceRelease > release) {
                throw wrongReleaseException(param, value, release);
            }
        }

        protected final <T extends PrivateStudyUid> List<String> validate(String defaultStudyStr, List<String> values, Integer release,
                                                                          VariantQueryParam param, ResourceManager<T> manager,
                                                                          Function<T, String> getId, Function<T, Integer> getRelease,
                                                                          Consumer<T> valueValidator, String sessionId)
                throws CatalogException {
            List<QueryResult<T>> queryResults = manager.get(defaultStudyStr, values, null, RELEASE_OPTIONS, sessionId);
            List<String> validatedValues = new ArrayList<>(values.size());
            for (QueryResult<T> queryResult : queryResults) {
                T value = queryResult.first();
                if (valueValidator != null) {
                    valueValidator.accept(value);
                }
                String id = getId.apply(value);
                validatedValues.add(id);
                checkRelease(release, getRelease.apply(value), param, id);
            }
            return validatedValues;
        }
    }


    public class StudyFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId) throws CatalogException {
            if (release == null) {
                String userId = catalogManager.getUserManager().getUserId(sessionId);
                List<Study> studies = catalogManager.getStudyManager().resolveIds(values, userId);
                return studies.stream().map(Study::getFqn).collect(Collectors.toList());
            } else {
                List<String> validatedValues = new ArrayList<>(values.size());
                List<QueryResult<Study>> queryResults = catalogManager.getStudyManager().get(values, RELEASE_OPTIONS, false, sessionId);
                for (QueryResult<Study> queryResult : queryResults) {
                    Study study = queryResult.first();
                    validatedValues.add(study.getFqn());
                    checkRelease(release, study.getRelease(), param, study.getFqn());
                }
                return validatedValues;
            }
        }
    }

    public class FileFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId)
                throws CatalogException {
            if (release == null) {
                AbstractManager.MyResources<File> uids = catalogManager.getFileManager().getUids(values, defaultStudyStr, sessionId);
                return uids.getResourceList().stream().map(File::getName).collect(Collectors.toList());
            } else {
                return validate(defaultStudyStr, values, release, param, catalogManager.getFileManager(), File::getName,
                        file -> ((int) file.getIndex().getRelease()), file -> {
                            if (file.getIndex() == null
                                    || file.getIndex().getStatus() == null
                                    || file.getIndex().getStatus().getName() == null
                                    || !file.getIndex().getStatus().getName().equals(Status.READY)) {
                                throw new VariantQueryException("File '" + file.getName() + "' is not indexed");
                            }
                        },
                        sessionId);

            }
        }
    }

    public class SampleFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId) throws CatalogException {
            if (release == null) {
                AbstractManager.MyResources<Sample> uids = catalogManager.getSampleManager().getUids(values, defaultStudyStr, sessionId);
                return uids.getResourceList().stream().map(Sample::getId).collect(Collectors.toList());
            } else {
                return validate(defaultStudyStr, values, release, param, catalogManager.getSampleManager(),
                        Sample::getId, Sample::getRelease, null, sessionId);
            }
        }
    }

    public class GenotypeFilterValidator extends SampleFilterValidator {

        @Override
        protected QueryOperation getQueryOperation(String valuesStr) {
            Map<Object, List<String>> genotypesMap = new HashMap<>();
            return VariantQueryUtils.parseGenotypeFilter(valuesStr, genotypesMap);
        }

        @Override
        protected List<String> splitValue(String valuesStr, QueryOperation queryOperation) {
            Map<Object, List<String>> genotypesMap = new LinkedHashMap<>();
            VariantQueryUtils.parseGenotypeFilter(valuesStr, genotypesMap);

            return genotypesMap.entrySet().stream().map(entry -> entry.getKey() + ":" + String.join(",", entry.getValue()))
                    .collect(Collectors.toList());
        }

        @Override
        protected List<String> getValuesToValidate(List<String> rawValues) {
            return rawValues.stream().map(value -> value.split(":")[0]).collect(Collectors.toList());
        }
    }

    public class CohortFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId)
                throws CatalogException {
            if (release == null) {
                AbstractManager.MyResources<Cohort> uids = catalogManager.getCohortManager().getUids(values, defaultStudyStr, sessionId);
                return uids.getResourceList().stream().map(Cohort::getId).collect(Collectors.toList());
            } else {
                return validate(defaultStudyStr, values, release, param, catalogManager.getCohortManager(),
                        Cohort::getId, Cohort::getRelease, null, sessionId);
            }
        }
    }

}
