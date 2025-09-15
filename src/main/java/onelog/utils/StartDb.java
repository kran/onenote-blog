package onelog.utils;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import onelog.BlogConfig;
import org.h2.tools.Server;

import java.sql.SQLException;

@Singleton
public class StartDb {
    @Inject
    public void start(BlogConfig blogConfig) throws SQLException {
        var c = blogConfig.getH2();
        var db = new Server();
        db.runTool(
          "-ifNotExists",
          "-baseDir", c.getBaseDir(),
          "-web", "-webAllowOthers", "-webPort", c.getWebPort(), "-webExternalNames", c.getExternalNames(),
          "-tcp", "-tcpAllowOthers", "-tcpPort", c.getTcpPort()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(db::shutdown));
    }
}