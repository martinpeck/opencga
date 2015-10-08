package org.opencb.opencga.server.ws;

import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.opencb.biodata.models.alignment.Alignment;
import org.opencb.biodata.models.alignment.AlignmentRegion;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.commons.utils.FileUtils;
import org.opencb.datastore.core.ObjectMap;
import org.opencb.datastore.core.QueryOptions;
import org.opencb.datastore.core.QueryResponse;
import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.analysis.AnalysisExecutionException;
import org.opencb.opencga.catalog.CatalogManagerTest;
import org.opencb.opencga.catalog.db.api.CatalogFileDBAdaptor;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.models.File;
import org.opencb.opencga.catalog.models.Job;
import org.opencb.opencga.core.common.IOUtils;
import org.opencb.opencga.storage.core.variant.VariantStorageManager;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Created by jacobo on 13/06/15.
 */
public class FileWSServerTest {

    private WebTarget webTarget;
    private static WSServerTestUtils serverTestUtils;
    private String sessionId;
    private int studyId;
    public static final Path ROOT_DIR = Paths.get("/tmp/opencga-server-FileWSServerTest-folder");

    public FileWSServerTest() {
    }

    void setWebTarget(WebTarget webTarget) {
        this.webTarget = webTarget;
    }

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    static public void initServer() throws Exception {
//        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "debug");
        serverTestUtils = new WSServerTestUtils();
        serverTestUtils.initServer();
    }

    @AfterClass
    static public void shutdownServer() throws Exception {
        serverTestUtils.shutdownServer();
    }

    @Before
    public void init() throws Exception {
        serverTestUtils.setUp();
        webTarget = serverTestUtils.getWebTarget();
        sessionId = OpenCGAWSServer.catalogManager.login("user", CatalogManagerTest.PASSWORD, "localhost").first().getString("sessionId");
        studyId = OpenCGAWSServer.catalogManager.getStudyId("user@1000G:phase1");


        if (ROOT_DIR.toFile().exists()) {
            IOUtils.deleteDirectory(ROOT_DIR);
        }
        Files.createDirectory(ROOT_DIR);
        CatalogManagerTest.createDebugFile(ROOT_DIR.resolve("file1.txt").toString());
        CatalogManagerTest.createDebugFile(ROOT_DIR.resolve("file2.txt").toString());
        Files.createDirectory(ROOT_DIR.resolve("data"));
        CatalogManagerTest.createDebugFile(ROOT_DIR.resolve("data").resolve("file2.txt").toString());
        String fileName = "variant-test-file.vcf.gz";
        Files.copy(this.getClass().getClassLoader().getResourceAsStream(fileName), ROOT_DIR.resolve("data").resolve(fileName));
        fileName = "HG00096.chrom20.small.bam";
        Files.copy(this.getClass().getClassLoader().getResourceAsStream(fileName), ROOT_DIR.resolve("data").resolve(fileName));
    }


    @Test
    public void linkFolderTest() throws IOException {

        String path = "data/newFolder"; //Accepts ending or not ending with "/"
        String json = webTarget.path("files").path("link")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("path", path)
                .queryParam("uri", ROOT_DIR.toUri()).request().get(String.class);

        QueryResponse<QueryResult<File>> response = WSServerTestUtils.parseResult(json, File.class);
        File file = response.getResponse().get(0).first();
        assertEquals(path + "/", file.getPath());
        assertEquals(ROOT_DIR.toUri(), file.getUri());

    }

    @Test
    public void linkFileTest() throws IOException {
        URI fileUri = ROOT_DIR.resolve("file1.txt").toUri();
        String json = webTarget.path("files").path("link")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("path", "data/")
                .queryParam("uri", fileUri).request().get(String.class);

        QueryResponse<QueryResult<File>> response = WSServerTestUtils.parseResult(json, File.class);
        File file = response.getResponse().get(0).first();
        assertEquals("data/file1.txt", file.getPath());
        assertEquals(fileUri, file.getUri());
    }

    @Test
    public void linkFileTest2() throws IOException {
        URI fileUri = ROOT_DIR.resolve("file1.txt").toUri();
        String json = webTarget.path("files").path("link")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("path", "data")
                .queryParam("uri", fileUri).request().get(String.class);

        QueryResponse<QueryResult<File>> response = WSServerTestUtils.parseResult(json, File.class);
        File file = response.getResponse().get(0).first();
        assertEquals("data/file1.txt", file.getPath());
        assertEquals(fileUri, file.getUri());


        fileUri = ROOT_DIR.resolve("file2.txt").toUri();
        json = webTarget.path("files").path(Integer.toString(file.getId())).path("relink")
                .queryParam("sid", sessionId)
                .queryParam("uri", fileUri).request().get(String.class);

        response = WSServerTestUtils.parseResult(json, File.class);
        file = response.getResponse().get(0).first();
        assertEquals("data/file1.txt", file.getPath());
        assertEquals(fileUri, file.getUri());
    }

    @Test
    public void updateFilePOST() throws Exception {
        File file = OpenCGAWSServer.catalogManager.getAllFiles(studyId, new QueryOptions(CatalogFileDBAdaptor.FileFilterOption.type.toString(), "FILE"), sessionId).first();

        FileWSServer.UpdateFile updateFile = new FileWSServer.UpdateFile();
        updateFile.description = "Change description";
        String json = webTarget.path("files").path(Integer.toString(file.getId())).path("update")
                .queryParam("sid", sessionId).request().post(Entity.json(updateFile), String.class);

        QueryResponse<QueryResult<Object>> response = WSServerTestUtils.parseResult(json, Object.class);
        file = OpenCGAWSServer.catalogManager.getFile(file.getId(), sessionId).first();
        assertEquals(updateFile.description, file.getDescription());
    }

    @Test
    public void searchFiles() throws Exception {
        String json = webTarget.path("files").path("search")
                .queryParam("include", "projects.studies.files.id,projects.studies.files.path")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("path", "data/").request().get(String.class);

        QueryResponse<QueryResult<File>> response = WSServerTestUtils.parseResult(json, File.class);
        File file = response.getResponse().get(0).first();
        assertEquals(1, response.getResponse().get(0).getNumResults());
        assertEquals("data/", file.getPath());

        response = WSServerTestUtils.parseResult(webTarget.path("files").path("user@1000G:phase1:data:").path("update")
                .queryParam("include", "projects.studies.files.id,projects.studies.files.path")
                .queryParam("sid", sessionId)
                .request().post(Entity.json(
                                new ObjectMap("attributes",
                                        new ObjectMap("num", 2)
                                                .append("exists", true)
                                                .append("txt", "helloWorld"))),
                        String.class), File.class);

        response = WSServerTestUtils.parseResult(webTarget.path("files").path("user@1000G:phase1:analysis:").path("update")
                .queryParam("include", "projects.studies.files.id,projects.studies.files.path")
                .queryParam("sid", sessionId)
                .request().post(Entity.json(
                                new ObjectMap("attributes",
                                        new ObjectMap("num", 3)
                                                .append("exists", true)
                                                .append("txt", "helloMundo"))),
                        String.class), File.class);

        response = WSServerTestUtils.parseResult(webTarget.path("files").path("search")
                .queryParam("include", "projects.studies.files.id,projects.studies.files.path")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("attributes.txt", "~hello").request().get(String.class), File.class);
        assertEquals(2, response.getResponse().get(0).getNumResults());

        response = WSServerTestUtils.parseResult(webTarget.path("files").path("search")
                .queryParam("include", "projects.studies.files.id,projects.studies.files.path")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("battributes.exists", true).request().get(String.class), File.class);
        assertEquals(2, response.getResponse().get(0).getNumResults());

        response = WSServerTestUtils.parseResult(webTarget.path("files").path("search")
                .queryParam("include", "projects.studies.files.id,projects.studies.files.path")
                .queryParam("sid", sessionId)
                .queryParam("studyId", studyId)
                .queryParam("nattributes.num", "<3").request().get(String.class), File.class);
        assertEquals(1, response.getResponse().get(0).getNumResults());

    }

    public File uploadVcf(int studyId, String sessionId) throws IOException, CatalogException {
        String fileName = "variant-test-file.vcf.gz";
//        String fileName = "10k.chr22.phase3_shapeit2_mvncall_integrated_v5.20130502.genotypes.vcf.gz";
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(fileName);
        return upload(studyId, fileName, File.Bioformat.VARIANT, is, sessionId);
    }

    public File uploadBam(int studyId, String sessionId) throws IOException, CatalogException {
        String fileName = "HG00096.chrom20.small.bam";
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(fileName);

        return upload(studyId, fileName, File.Bioformat.ALIGNMENT, is, sessionId);
    }

    public File upload(int studyId, String fileName, File.Bioformat bioformat, InputStream is, String sessionId) throws IOException, CatalogException {
        System.out.println("\nTesting file upload...");
        System.out.println("------------------------");


//        QueryResult<File> queryResult = OpenCGAWSServer.catalogManager.createFile(studyId, File.Format.PLAIN, File.Bioformat.VARIANT, "data/" + fileName, "", true, -1, sessionId);
//        new CatalogFileUtils(OpenCGAWSServer.catalogManager).upload(is, queryResult.first(), sessionId, false, false, true);

//        return OpenCGAWSServer.catalogManager.getFile(queryResult.first().getId(), sessionId).first();


        int totalSize = is.available();
        int bufferSize = Math.min(totalSize/100+10, 100000);
        byte[] buffer = new byte[bufferSize];
        int size;
        int chunk_id = 0;
        String json = null;
        while((size = is.read(buffer)) > 0) {

            MultiPart multiPart = new MultiPart();
            multiPart.setMediaType(MediaType.MULTIPART_FORM_DATA_TYPE);
            multiPart.bodyPart(new StreamDataBodyPart("chunk_content", new ByteArrayInputStream(buffer, 0, size)));
            multiPart.bodyPart(new FormDataBodyPart("chunk_id", Integer.toString(chunk_id)));
            multiPart.bodyPart(new FormDataBodyPart("chunk_size", Integer.toString(size)));
            multiPart.bodyPart(new FormDataBodyPart("chunk_total", Integer.toString(totalSize)));
            multiPart.bodyPart(new FormDataBodyPart("last_chunk", Boolean.toString(is.available() == 0)));
            multiPart.bodyPart(new FormDataBodyPart("filename", fileName));
            multiPart.bodyPart(new FormDataBodyPart("studyId", Integer.toString(studyId)));
            multiPart.bodyPart(new FormDataBodyPart("fileFormat", File.Format.PLAIN.toString()));
            multiPart.bodyPart(new FormDataBodyPart("bioFormat", bioformat.toString()));
            multiPart.bodyPart(new FormDataBodyPart("relativeFilePath", "data/" + fileName));
            multiPart.bodyPart(new FormDataBodyPart("parents", "true"));

            json = this.webTarget.path("files").path("upload").queryParam("sid", sessionId)
            .request().post(Entity.entity(multiPart, multiPart.getMediaType()), String.class);

            System.out.println("Chunk id " + chunk_id);
            chunk_id++;
        }
        System.out.println("size = " + size);


        System.out.println("\nJSON RESPONSE");
        System.out.println(json);
        QueryResponse<QueryResult<File>> queryResponse = WSServerTestUtils.parseResult(json, File.class);
        assertEquals("Expected [], actual [" + queryResponse.getError() + "]", "", queryResponse.getError());
        File file = queryResponse.getResponse().get(0).first();

        System.out.println("Testing user creation finished");

        return file;

    }


    public Job index(int fileId, String sessionId) throws IOException, AnalysisExecutionException, CatalogException {
        System.out.println("\nTesting file index...");
        System.out.println("---------------------");
        System.out.println("\nINPUT PARAMS");
        System.out.println("\tsid: " + sessionId);
        System.out.println("\tfileId: " + fileId);
        System.out.println("\t" + VariantStorageManager.Options.ANNOTATE.key() + ": " + true);

        String json = webTarget.path("files").path(String.valueOf(fileId)).path("index")
                .queryParam("sid", sessionId)
                .queryParam(VariantStorageManager.Options.ANNOTATE.key(), true)
                .request().get(String.class);

        QueryResponse<QueryResult<Job>> queryResponse = WSServerTestUtils.parseResult(json, Job.class);
        assertEquals("Expected [], actual [" + queryResponse.getError() + "]", "", queryResponse.getError());
        System.out.println("\nOUTPUT PARAMS");
        Job job = queryResponse.getResponse().get(0).first();

        System.out.println("\nJSON RESPONSE");
        System.out.println(json);

        return job;

    }

    public Job calculateVariantStats(int cohortId, int outdirId, String sessionId) throws IOException, AnalysisExecutionException, CatalogException {

        String json = webTarget.path("cohorts").path(String.valueOf(cohortId)).path("stats")
                .queryParam("sid", sessionId)
                .queryParam("calculate", true)
                .queryParam("outdirId", outdirId)
                .queryParam("log", "debug")
                .request().get(String.class);

        QueryResponse<QueryResult<Job>> queryResponse = WSServerTestUtils.parseResult(json, Job.class);
        assertEquals("Expected [], actual [" + queryResponse.getError() + "]", "", queryResponse.getError());
        System.out.println("\nOUTPUT PARAMS");
        Job job = queryResponse.getResponse().get(0).first();

        System.out.println("\nJSON RESPONSE");
        System.out.println(json);

        return job;

    }

    public List<Variant> fetchVariants(int fileId, String sessionId, QueryOptions queryOptions) throws IOException {
        System.out.println("\nTesting file fetch variants...");
        System.out.println("---------------------");
        System.out.println("\nINPUT PARAMS");
        System.out.println("\tsid: " + sessionId);
        System.out.println("\tfileId: " + fileId);

        WebTarget webTarget = this.webTarget.path("files").path(String.valueOf(fileId)).path("fetch")
                .queryParam("sid", sessionId);
        for (Map.Entry<String, Object> entry : queryOptions.entrySet()) {
            webTarget = webTarget.queryParam(entry.getKey(), entry.getValue());
            System.out.println("\t" + entry.getKey() + ": " + entry.getValue());

        }
        System.out.println("webTarget = " + webTarget);
        String json = webTarget.request().get(String.class);
        System.out.println("json = " + json);


        QueryResponse<QueryResult<Variant>> queryResponse = WSServerTestUtils.parseResult(json, Variant.class);
        assertEquals("Expected [], actual [" + queryResponse.getError() + "]", "", queryResponse.getError());
        System.out.println("\nOUTPUT PARAMS");
        List<Variant> variants = queryResponse.getResponse().get(0).getResult();

        System.out.println("\nJSON RESPONSE");
        System.out.println(json);

        return variants;
    }

    public List<ObjectMap> fetchAlignments(int fileId, String sessionId, QueryOptions queryOptions) throws IOException {
        System.out.println("\nTesting file fetch alignments...");
        System.out.println("---------------------");
        System.out.println("\nINPUT PARAMS");
        System.out.println("\tsid: " + sessionId);
        System.out.println("\tfileId: " + fileId);

        WebTarget webTarget = this.webTarget.path("files").path(String.valueOf(fileId)).path("fetch")
                .queryParam("sid", sessionId);
        for (Map.Entry<String, Object> entry : queryOptions.entrySet()) {
            webTarget = webTarget.queryParam(entry.getKey(), entry.getValue());
            System.out.println("\t" + entry.getKey() + ": " + entry.getValue());

        }
        System.out.println("webTarget = " + webTarget);
        String json = webTarget.request().get(String.class);
        System.out.println("json = " + json);


        QueryResponse<QueryResult<ObjectMap>> queryResponse = WSServerTestUtils.parseResult(json, ObjectMap.class);
        assertEquals("Expected [], actual [" + queryResponse.getError() + "]", "", queryResponse.getError());
        System.out.println("\nOUTPUT PARAMS");
        assertEquals("", queryResponse.getError());
        List<ObjectMap> alignments = queryResponse.getResponse().get(0).getResult();

        System.out.println("\nJSON RESPONSE");
        System.out.println(json);

        return alignments;
    }
}