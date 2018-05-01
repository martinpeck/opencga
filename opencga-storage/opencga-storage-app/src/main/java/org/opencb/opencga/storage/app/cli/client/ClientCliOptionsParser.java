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

package org.opencb.opencga.storage.app.cli.client;

import com.beust.jcommander.JCommander;
import org.opencb.commons.utils.CommandLineUtils;
import org.opencb.opencga.core.common.GitRepositoryState;
import org.opencb.opencga.storage.app.cli.GeneralCliOptions;
import org.opencb.opencga.storage.app.cli.client.options.StorageAlignmentCommandOptions;
import org.opencb.opencga.storage.app.cli.client.options.StorageVariantCommandOptions;

import java.util.Map;

import static org.opencb.opencga.storage.app.cli.client.options.StorageVariantCommandOptions.CreateAnnotationSnapshotCommandOptions.COPY_ANNOTATION_COMMAND;
import static org.opencb.opencga.storage.app.cli.client.options.StorageVariantCommandOptions.DeleteAnnotationSnapshotCommandOptions.DELETE_ANNOTATION_COMMAND;
import static org.opencb.opencga.storage.app.cli.client.options.StorageVariantCommandOptions.FillGapsCommandOptions.FILL_GAPS_COMMAND;
import static org.opencb.opencga.storage.app.cli.client.options.StorageVariantCommandOptions.QueryAnnotationCommandOptions.QUERY_ANNOTATION_COMMAND;
import static org.opencb.opencga.storage.app.cli.client.options.StorageVariantCommandOptions.VariantRemoveCommandOptions.VARIANT_REMOVE_COMMAND;

/**
 * Created by imedina on 02/03/15.
 */
public class ClientCliOptionsParser extends GeneralCliOptions {

    private final ClientCliOptionsParser.IndexCommandOptions indexCommandOptions;
    private final ClientCliOptionsParser.QueryCommandOptions queryCommandOptions;

    private StorageAlignmentCommandOptions alignmentCommandOptions;
    private StorageVariantCommandOptions variantCommandOptions;
//    private FeatureCommandOptions featureCommandOptions;

    public ClientCliOptionsParser() {
        indexCommandOptions = new IndexCommandOptions();
        queryCommandOptions = new QueryCommandOptions();

        alignmentCommandOptions = new StorageAlignmentCommandOptions(this.commonOptions, this.indexCommandOptions, this.queryCommandOptions,
                this.jcommander);
        jcommander.addCommand("alignment", alignmentCommandOptions);
        JCommander alignmentSubCommands = jcommander.getCommands().get("alignment");
        alignmentSubCommands.addCommand("index", alignmentCommandOptions.indexCommandOptions);
        alignmentSubCommands.addCommand("query", alignmentCommandOptions.queryCommandOptions);

        variantCommandOptions = new StorageVariantCommandOptions(this.commonOptions, this.indexCommandOptions, this.queryCommandOptions,
                this.jcommander);
        jcommander.addCommand("variant", variantCommandOptions);
        JCommander variantSubCommands = jcommander.getCommands().get("variant");
        variantSubCommands.addCommand("index", variantCommandOptions.indexVariantsCommandOptions);
        variantSubCommands.addCommand(VARIANT_REMOVE_COMMAND, variantCommandOptions.variantRemoveCommandOptions);
        variantSubCommands.addCommand("query", variantCommandOptions.variantQueryCommandOptions);
        variantSubCommands.addCommand("import", variantCommandOptions.importVariantsCommandOptions);
        variantSubCommands.addCommand("annotate", variantCommandOptions.annotateVariantsCommandOptions);
        variantSubCommands.addCommand(COPY_ANNOTATION_COMMAND, variantCommandOptions.createAnnotationSnapshotCommandOptions);
        variantSubCommands.addCommand(DELETE_ANNOTATION_COMMAND, variantCommandOptions.deleteAnnotationSnapshotCommandOptions);
        variantSubCommands.addCommand(QUERY_ANNOTATION_COMMAND, variantCommandOptions.queryAnnotationCommandOptions);
//        variantSubCommands.addCommand("benchmark", variantCommandOptions.benchmarkCommandOptions);
        variantSubCommands.addCommand("stats", variantCommandOptions.statsVariantsCommandOptions);
        variantSubCommands.addCommand(FILL_GAPS_COMMAND, variantCommandOptions.fillGapsCommandOptions);
        variantSubCommands.addCommand("export", variantCommandOptions.exportVariantsCommandOptions);
        variantSubCommands.addCommand("search", variantCommandOptions.searchVariantsCommandOptions);
    }


    public void printUsage() {
        String parsedCommand = getCommand();
        if (parsedCommand.isEmpty()) {
            System.err.println("");
            System.err.println("Program:     OpenCGA Storage (OpenCB)");
            System.err.println("Version:     " + GitRepositoryState.get().getBuildVersion());
            System.err.println("Git commit:  " + GitRepositoryState.get().getCommitId());
            System.err.println("Description: Big Data platform for processing and analysing NGS data");
            System.err.println("");
            System.err.println("Usage:       opencga-storage.sh [-h|--help] [--version] <command> [options]");
            System.err.println("");
            System.err.println("Commands:");
            printMainUsage();
            System.err.println("");
        } else {
            String parsedSubCommand = getSubCommand();
            if (parsedSubCommand.isEmpty()) {
                System.err.println("");
                System.err.println("Usage:   opencga-storage.sh " + parsedCommand + " [options]");
                System.err.println("");
                System.err.println("Subcommands:");
                printCommands(jcommander.getCommands().get(parsedCommand));
                System.err.println("");
            } else {
                System.err.println("");
                System.err.println("Usage:   opencga-storage.sh " + parsedCommand + " " + parsedSubCommand + " [options]");
                System.err.println("");
                System.err.println("Options:");
                CommandLineUtils.printCommandUsage(jcommander.getCommands().get(parsedCommand).getCommands().get(parsedSubCommand));
                System.err.println("");
            }
        }
    }

    private void printMainUsage() {
        for (String s : jcommander.getCommands().keySet()) {
            System.err.printf("%14s  %s\n", s, jcommander.getCommandDescription(s));
        }
    }

    private void printCommands(JCommander commander) {
        for (Map.Entry<String, JCommander> entry : commander.getCommands().entrySet()) {
            System.err.printf("%14s  %s\n", entry.getKey(), commander.getCommandDescription(entry.getKey()));
        }
    }


    public GeneralOptions getGeneralOptions() {
        return generalOptions;
    }

    public CommonOptions getCommonOptions() {
        return commonOptions;
    }

    public StorageAlignmentCommandOptions getAlignmentCommandOptions() {
        return alignmentCommandOptions;
    }

    public StorageVariantCommandOptions getVariantCommandOptions() {
        return variantCommandOptions;
    }

}
