package org.yamcs.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

import org.yamcs.FileBasedConfigurationResolver;
import org.yamcs.YConfiguration;
import org.yamcs.logging.Log;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.PathConverter;

/**
 * Command line utility for doing yamcs stuff.
 *
 * This usage is yamcsadmin &lt;command&gt; [command_specific_options]
 *
 * @author nm
 */
public class YamcsAdminCli extends Command {

    public YamcsAdminCli() {
        super("yamcsadmin", null);
        addSubCommand(new Backup(this));
        addSubCommand(new CheckConfig(this));
        addSubCommand(new ParameterArchiveCli(this));
        addSubCommand(new PasswordHashCli(this));
        addSubCommand(new RocksDbCli(this));
        addSubCommand(new UsersCli(this));
        addSubCommand(new XtceDbCli(this));
    }

    @Parameter(names = "--etc-dir", converter = PathConverter.class, description = "Path to config directory")
    private Path configDirectory = Paths.get("etc").toAbsolutePath();

    @Parameter(names = "--log", description = "Level of verbosity")
    private int verbose = 1;

    @Parameter(names = { "-v", "--version" }, description = "Print version information and quit")
    boolean version;

    @Override
    void validate() throws ParameterException {
        selectedCommand.validate();
    }

    public static void main(String[] args) {
        YamcsAdminCli yamcsCli = new YamcsAdminCli();
        yamcsCli.parse(args);

        Level logLevel;
        switch (yamcsCli.verbose) {
        case 0:
            logLevel = Level.OFF;
            break;
        case 1:
            logLevel = Level.WARNING;
            break;
        case 2:
            logLevel = Level.INFO;
            break;
        case 3:
            logLevel = Level.FINE;
            break;
        default:
            logLevel = Level.ALL;
            break;
        }
        Log.forceStandardStreams(logLevel);

        YConfiguration.setResolver(new FileBasedConfigurationResolver(yamcsCli.configDirectory));

        try {
            yamcsCli.validate();
            yamcsCli.execute();
        } catch (ExecutionException e) {
            System.err.println(e.getCause());
            System.exit(1);
        } catch (Exception e) {
            System.err.println(e);
            System.exit(1);
        }

        System.exit(0);
    }

    public Path getConfigDirectory() {
        return configDirectory;
    }
}
