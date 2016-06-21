package org.opencb.opencga.app.cli.main.options;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import org.opencb.opencga.app.cli.main.OpencgaCliOptionsParser.OpencgaCommonCommandOptions;

/**
 * Created by sgallego on 6/15/16.
 */
@Parameters(commandNames = {"panels"}, commandDescription = "Panels commands")
public class PanelCommandOptions {

    public CreateCommandOptions createCommandOptions;
    public InfoCommandOptions infoCommandOptions;
    public ShareCommandOptions shareCommandOptions;
    public UnshareCommandOptions unshareCommandOptions;

    public JCommander jCommander;
    public OpencgaCommonCommandOptions commonCommandOptions;

    public PanelCommandOptions(OpencgaCommonCommandOptions commonCommandOptions, JCommander jCommander) {

        this.commonCommandOptions = commonCommandOptions;
        this.jCommander = jCommander;

        this.createCommandOptions = new CreateCommandOptions();
        this.infoCommandOptions = new InfoCommandOptions();
        this.shareCommandOptions = new ShareCommandOptions();
        this.unshareCommandOptions = new UnshareCommandOptions();
    }

    class BasePanelsCommand {

        @ParametersDelegate
        public OpencgaCommonCommandOptions commonOptions = commonCommandOptions;

        @Parameter(names = {"--panel-id"}, description = "Panel id", required = true, arity = 1)
        public String id;
    }

    @Parameters(commandNames = {"create"}, commandDescription = "Create a cohort")
    public class CreateCommandOptions {

        @Parameter(names = {"--study-id"}, description = "Study id", required = true, arity = 1)
        public String studyId;

        @Parameter(names = {"--name"}, description = "Panel name", required = true, arity = 1)
        public String name;

        @Parameter(names = {"--disease"}, description = "Disease", required = true, arity = 1)
        public String disease;

        @Parameter(names = {"--description"}, description = "Panel description", required = false, arity = 1)
        public String description;

        @Parameter(names = {"--genes"}, description = "Genes", required = false, arity = 1)
        public String genes;

        @Parameter(names = {"--regions"}, description = "Regions", required = false, arity = 1)
        public String regions;

        @Parameter(names = {"--variants"}, description = "Variants", required = false, arity = 1)
        public String variants;

    }

    @Parameters(commandNames = {"info"}, commandDescription = "Get cohort information")
    public class InfoCommandOptions extends BasePanelsCommand {
    }

    @Parameters(commandNames = {"share"}, commandDescription = "Share cohort")
    public class ShareCommandOptions {

        @ParametersDelegate
        public OpencgaCommonCommandOptions commonOptions = commonCommandOptions;

        @Parameter(names = {"--panels-ids"}, description = "Panels ids", required = true, arity = 1)
        public String ids;

        @Parameter(names = {"--members"}, description = "Comma separated list of members. Accepts: '{userId}', '@{groupId}' or '*'",
                required = true, arity = 1)
        public String members;

        @Parameter(names = {"--permission"}, description = "Comma separated list of panel permissions", required = false, arity = 1)
        public String permission;

        @Parameter(names = {"--override"}, description = "Boolean indicating whether to allow the change" +
                " of permissions in case any member already had any, default:false", required = false, arity = 0)
        public boolean override;
    }

    @Parameters(commandNames = {"unshare"}, commandDescription = "Unshare cohort")
    public class UnshareCommandOptions {

        @ParametersDelegate
        public OpencgaCommonCommandOptions commonOptions = commonCommandOptions;

        @Parameter(names = {"--panels-ids"}, description = "Panels ids", required = true, arity = 1)
        public String ids;

        @Parameter(names = {"--members"}, description = "Comma separated list of members. Accepts: '{userId}', '@{groupId}' or '*'",
                required = true, arity = 1)
        public String members;

        @Parameter(names = {"--permission"}, description = "Comma separated list of panel permissions", required = false, arity = 1)
        public String permission;


    }


}
