package org.opencb.opencga.storage.mongodb.variant;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.opencb.biodata.models.variant.VariantSourceEntry;
import org.opencb.biodata.models.variant.stats.VariantStats;
import org.opencb.datastore.core.ComplexTypeConverter;
import org.opencb.opencga.storage.mongodb.utils.MongoCredentials;

/**
 * 
 * @author Cristina Yenyxe Gonzalez Garcia <cyenyxe@ebi.ac.uk>
 */
public class DBObjectToVariantSourceEntryConverter implements ComplexTypeConverter<VariantSourceEntry, DBObject> {

    public final static String FILEID_FIELD = "fid";
    public final static String STUDYID_FIELD = "sid";
    public final static String ALTERNATES_FIELD = "alts";
    public final static String ATTRIBUTES_FIELD = "attrs";
    public final static String FORMAT_FIELD = "fm";
    public final static String SAMPLES_FIELD = "samp";
    public final static String STATS_FIELD = "st";
    
    
    private boolean includeSamples;
    private boolean includeSrc;

    private List<String> samples;
    
    private DBObjectToSamplesConverter samplesConverter;
    private DBObjectToVariantStatsConverter statsConverter;

    /**
     * Create a converter between VariantSourceEntry and DBObject entities when 
     * there is no need to provide a list of samples or statistics.
     */
    public DBObjectToVariantSourceEntryConverter() {
        this.includeSamples = false;
        this.includeSrc = false;
        this.samples = null;
        this.samplesConverter = null;
        this.statsConverter = null;
    }
    
    /**
     * Create a converter from VariantSourceEntry to DBObject entities. A 
     * list of samples and a statistics converter may be provided in case those 
     * should be processed during the conversion.
     * 
     * @param compressSamples Whether to compress samples or not
     * @param samples The list of samples, if any
     * @param statsConverter The object used to convert the file statistics
     */
    public DBObjectToVariantSourceEntryConverter(boolean compressSamples, boolean defaultValue, List<String> samples,
            DBObjectToVariantStatsConverter statsConverter) {
        this.samples = samples;
        this.samplesConverter = new DBObjectToSamplesConverter(compressSamples, defaultValue);
        this.statsConverter = statsConverter;
    }
    
    /**
     * Create a converter from DBObject to VariantSourceEntry entities. A 
     * list of samples and a statistics converter may be provided in case those 
     * should be processed during the conversion.
     * 
     * @param includeSamples Whether to include samples or not
     * @param statsConverter The object used to convert the file statistics
     * @param samples The list of samples, if any
     */
    public DBObjectToVariantSourceEntryConverter(boolean includeSamples, 
            DBObjectToVariantStatsConverter statsConverter, List<String> samples) {
        this.includeSamples = includeSamples;
        this.samples = samples;
        this.samplesConverter = new DBObjectToSamplesConverter(samples);
        this.statsConverter = statsConverter;
    }
    
    /**
     * Create a converter from DBObject to VariantSourceEntry entities. A 
     * statistics converter may be provided in case those should be processed 
     * during the conversion.
     * 
     * If samples are to be included, their names must have been previously 
     * stored in the database and the connection parameters must be provided.
     * 
     * @param includeSamples Whether to include samples or not
     * @param statsConverter The object used to convert the file statistics
     * @param credentials Parameters for connecting to the database
     * @param collectionName Collection that stores the variant sources
     */
    public DBObjectToVariantSourceEntryConverter(boolean includeSamples, DBObjectToVariantStatsConverter statsConverter, 
            MongoCredentials credentials, String collectionName) {
        this.includeSamples = includeSamples;
        this.samplesConverter = new DBObjectToSamplesConverter(credentials, collectionName);
        this.statsConverter = statsConverter;
    }

    public void setIncludeSrc(boolean includeSrc) {
        this.includeSrc = includeSrc;
    }
    
    @Override
    public VariantSourceEntry convertToDataModelType(DBObject object) {
        String fileId = (String) object.get(FILEID_FIELD);
        String studyId = (String) object.get(STUDYID_FIELD);
        VariantSourceEntry file = new VariantSourceEntry(fileId, studyId);
        
        // Alternate alleles
        if (object.containsField(ALTERNATES_FIELD)) {
            List list = (List) object.get(ALTERNATES_FIELD);
            String[] alternatives = new String[list.size()];
            int i = 0;
            for (Object o : list) {
                alternatives[i] = o.toString();
                i++;
            }
            file.setSecondaryAlternates(alternatives);
        }
        
        // Attributes
        if (object.containsField(ATTRIBUTES_FIELD)) {
            file.setAttributes(((DBObject) object.get(ATTRIBUTES_FIELD)).toMap());
            // Unzip the "src" field, if available
            if (((DBObject) object.get(ATTRIBUTES_FIELD)).containsField("src")) {
                byte[] o = (byte[]) ((DBObject) object.get(ATTRIBUTES_FIELD)).get("src");
                try {
                    file.addAttribute("src", org.opencb.commons.utils.StringUtils.gunzip(o));
                } catch (IOException ex) {
                    Logger.getLogger(DBObjectToVariantSourceEntryConverter.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        if (object.containsField(FORMAT_FIELD)) {
            file.setFormat((String) object.get(FORMAT_FIELD));
        }
        
        // Samples
        if (includeSamples && object.containsField(SAMPLES_FIELD)) {
            VariantSourceEntry fileWithSamplesData = samplesConverter.convertToDataModelType(object);
            
            // Add the samples to the Java object, combining the data structures
            // with the samples' names and the genotypes
            for (Map.Entry<String, Map<String, String>> sampleData : fileWithSamplesData.getSamplesData().entrySet()) {
                file.addSampleData(sampleData.getKey(), sampleData.getValue());
            }
        }
        
        // Statistics
        if (statsConverter != null && object.containsField(STATS_FIELD)) {
            Map<String, VariantStats> cohortStats = new LinkedHashMap<>();
            DBObject stats = (DBObject) object.get(STATS_FIELD);
            if (stats instanceof List) {
                List<DBObject> cohortStatsList = ((List) stats);
                for (DBObject vs : cohortStatsList) {
                    VariantStats variantStats = statsConverter.convertToDataModelType(vs);
                    cohortStats.put((String) vs.get(DBObjectToVariantStatsConverter.COHORT_ID), variantStats);
                    file.setCohortStats(cohortStats);
                }
            }
        }
        return file;
    }

    @Override
    public DBObject convertToStorageType(VariantSourceEntry object) {
        BasicDBObject mongoFile = new BasicDBObject(FILEID_FIELD, object.getFileId()).append(STUDYID_FIELD, object.getStudyId());

        // Alternate alleles
        if (object.getSecondaryAlternates().length > 1) {
            mongoFile.append(ALTERNATES_FIELD, object.getSecondaryAlternates());
        }
        
        // Attributes
        if (object.getAttributes().size() > 0) {
            BasicDBObject attrs = null;
            for (Map.Entry<String, String> entry : object.getAttributes().entrySet()) {
                Object value = entry.getValue();
                if (entry.getKey().equals("src")) {
                    if (includeSrc) {
                        try {
                            value = org.opencb.commons.utils.StringUtils.gzip(entry.getValue());
                        } catch (IOException ex) {
                            Logger.getLogger(DBObjectToVariantSourceEntryConverter.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    } else {
                        continue;
                    }
                }

                if (attrs == null) {
                    attrs = new BasicDBObject(entry.getKey(), value);
                } else {
                    attrs.append(entry.getKey(), value);
                }
            }

            if (attrs != null) {
                mongoFile.put(ATTRIBUTES_FIELD, attrs);
            }
        }

        if (samples != null && !samples.isEmpty()) {
            mongoFile.append(FORMAT_FIELD, object.getFormat()); // Useless field if genotypeCodes are not stored
            mongoFile.put(SAMPLES_FIELD, samplesConverter.convertToStorageType(object));
        }
        
        // Statistics
        if (statsConverter != null && object.getCohortStats() != null) {
            Map<String, VariantStats> cohortStats = object.getCohortStats();
            DBObject cohortsObject = new BasicDBObject();
            for (String cohortName : cohortStats.keySet()) {
                cohortsObject.put(cohortName, statsConverter.convertToStorageType(cohortStats.get(cohortName)));
            }
            mongoFile.put(STATS_FIELD, cohortsObject);
        }
        
        return mongoFile;
    }
    
}
