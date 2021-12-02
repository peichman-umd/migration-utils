/*
 * Copyright 2015 DuraSpace, Inc.
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
package org.fcrepo.migration;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.fcrepo.migration.foxml.FoxmlInputStreamFedoraObjectProcessor;
import org.fcrepo.migration.foxml.HttpClientURLFetcher;
import org.fcrepo.migration.foxml.InternalIDResolver;
import org.fcrepo.migration.foxml.URLFetcher;
import org.fcrepo.migration.handlers.Fedora2ExportStreamingFedoraObjectHandler;
import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A class that performs an export of Fedora 2 objects.
 *
 * @author wallberg@umd.edu
 */
public class MigratorFedora2Export {

    private static final Logger LOGGER = getLogger(MigratorFedora2Export.class);

    private BufferedReader jsonReader;

    private Fedora2ExportStreamingFedoraObjectHandler handler;

    protected InternalIDResolver resolver;

    protected String localFedoraServer;

    protected URLFetcher fetcher;

    protected ExportWriter exportWriter;

    protected File targetDir;

    /**
     * Constructor.
     *
     * @param targetDir
     *          the directory to write the output to
     * @param exportWriter
     *          the ExportWriter to use to generate the export metadata file
     * @param handler
     *          the Fedora2ExportStreamingFedoraObjectHandler that exports
     *          the object information
     * @param jsonReader
     *          the BufferedReader that reads the JSON file generated by
     *          the "scripts/filter.py" script.
     * @param resolver
     *          the InternalIDResolver for resolving fedora/FOXML IDs
     * @param localFedoraServer
     *          the host and port where the content is exposed
     */
    public MigratorFedora2Export(final File targetDir, final ExportWriter exportWriter,
            final Fedora2ExportStreamingFedoraObjectHandler handler,
            final BufferedReader jsonReader, final InternalIDResolver resolver,
            final String localFedoraServer) {
        this.targetDir = targetDir;
        this.exportWriter = exportWriter;
        this.handler = handler;
        this.jsonReader = jsonReader;
        this.resolver = resolver;
        this.fetcher = new HttpClientURLFetcher();
        this.localFedoraServer = localFedoraServer;
    }

    /**
     * Created and returns the FoxmlInputStreamFedoraObjectProcessor instance
     * for processing the given UMDM file.
     *
     * @param umdmFile
     *             the UMDM file to process
     * @return the FoxmlInputStreamFedoraObjectProcessor instance for processing
     *         the given UMDM file.
     * @throws XMLStreamException
     * @throws FileNotFoundException
     */
    protected FoxmlInputStreamFedoraObjectProcessor createProcessor(final File umdmFile)
            throws XMLStreamException, FileNotFoundException {
        return new FoxmlInputStreamFedoraObjectProcessor(
                umdmFile, new FileInputStream(umdmFile), fetcher, resolver, localFedoraServer);
    }

    /**
     * the run method for migrator.
     *
     * @throws XMLStreamException
     *             xml stream exception
     */
    public void run() throws XMLStreamException {

        final ObjectMapper mapper = new ObjectMapper();

        try {
            // Read one JSON document for each UMDM object
            String line;
            while ((line = jsonReader.readLine()) != null) {

                final UMDM umdm = mapper.readValue(line, UMDM.class);

                exportWriter.exportUmdm(umdm);
                processUmdm(umdm);

                if (umdm.hasPart != null) {
                    for (UMAM umam : umdm.hasPart) {
                        exportWriter.exportUmam(umdm, umam);
                        processUmam(umdm, umam);
                    }
                }
            }

        } catch (Exception ex) {
            final String message = "Error in MigratorFedora2Export.run()";
            LOGGER.error(message, ex);
            throw new RuntimeException(message, ex);
        }
    }

    protected void processUmdm(final UMDM umdm) throws FileNotFoundException, XMLStreamException {
        LOGGER.info("Processing UMDM=" + umdm.pid + " @ " + umdm.foxml);

        final File umdmFile = new File(umdm.foxml);
        final FoxmlInputStreamFedoraObjectProcessor processor = createProcessor(umdmFile);

        final File umdmDir = new File(targetDir, umdm.getDirectoryName());
        handler.setObjectDir(umdmDir);
        processor.processObject(handler);
    }

    protected void processUmam(final UMDM umdm, final UMAM umam) throws FileNotFoundException, XMLStreamException {
        LOGGER.info("Processing UMDM=" + umdm.pid + ", UMAM=" + umam.pid + " @ " + umam.foxml);
        final File umamFile = new File(umam.foxml);
        final FoxmlInputStreamFedoraObjectProcessor processor = createProcessor(umamFile);

        final File umamDir = new File(targetDir, umdm.getDirectoryName() + "/" + umam.getDirectoryName());
        handler.setObjectDir(umamDir);
        processor.processObject(handler);
    }

    /**
     * POJO for mapping input JSON object: UMDM.
     */
    @JsonIgnoreProperties({ "type", "state", "label", "createdDate", "lastModifiedDate", "contentModel", "ds" })
    public static class UMDM {

        public String pid;
        public String foxml;
        public List<UMAM> hasPart;
        public String title;
        public String handle;

        public String getDirectoryName() {
            return pid.replace(":", "_");
        }
    }

    /**
     * POJO for mapping input JSON object: UMAM.
     */
    @JsonIgnoreProperties({ "type", "state", "label", "createdDate", "lastModifiedDate", "contentModel", "ds",
            "title" })
    public static class UMAM {

        public String pid;
        public String foxml;

        public String getDirectoryName() {
            return pid.replace(":", "_");
        }
    }

    /**
     * Interface for writing the export metadata file, such as "export.csv"
     */
    public static interface ExportWriter extends Closeable {
        public void exportUmdm(UMDM umdm) throws IOException;

        public void exportUmam(UMDM umdm, UMAM umam) throws IOException;
    }

    /**
     * ExportWriter implementation for writing out the export metadata in
     * CSV format.
     */
    public static class CSVExportWriter implements ExportWriter {
        private CSVPrinter csvWriter;

        public CSVExportWriter(final String csvOutputFile) throws IOException {
            this.csvWriter = new CSVPrinter(new FileWriter(csvOutputFile),
                    CSVFormat.DEFAULT.withHeader("umdm", "umam", "location", "title", "handle"));

        }

        @Override
        public void exportUmdm(final UMDM umdm) throws IOException {
            String title = umdm.title;
            title = title.replaceAll("\n", " ");
            csvWriter.printRecord(umdm.pid, "", umdm.getDirectoryName(), title, umdm.handle);
            csvWriter.flush();
        }

        @Override
        public void exportUmam(final UMDM umdm, final UMAM umam) throws IOException {
            csvWriter.printRecord(umdm.pid, umam.pid,
                    umdm.getDirectoryName() + "/" + umam.getDirectoryName(), "");
            csvWriter.flush();
        }

        @Override
        public void close() throws IOException {
            if (csvWriter != null) {
                csvWriter.close();
            }
        }
    }
}