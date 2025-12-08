package onelog;

import cn.hutool.cron.CronUtil;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.Server;
import org.v2u.doge.Doge;
import org.v2u.doge.DogeUtil;
import org.v2u.doge.plugin.CorsPlugin;
import org.v2u.doge.plugin.SimpleTracePlugin;
import org.v2u.stupidql.StupidQL;
import org.v2u.stupidql.interceptor.StupidLogger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

@Slf4j
public class Main extends AbstractModule {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            log.error("配置文件路径缺失");
            return;
        }

        var yamlPath = args[0];
        var config = loadYaml(yamlPath, Settings.class);
        var apiModule = new Main(config);
        var guice = Guice.createInjector(apiModule);

        var ctrl = guice.getInstance(BlogController.class);
        var doge = new Doge<>(HttpExchange::new)
          .idleTimeout(5000)
          .port(config.getPort())
          .install(new SimpleTracePlugin())
          .install(new CorsPlugin())
          .staticDir("/statics", "classpath:/statics")
          .filter("/*", Main::errorHandler)
          .routeStrategy(Main::routeStrategy)
          .attach(List.of(ctrl));

        doge.start();
        doge.dump();

        log.info("web server started on port: {}", doge.port());

        CronUtil.schedule(config.getCronExpr(), guice.getInstance(SyncTask.class));
        CronUtil.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CronUtil::stop));
    }

    private static String routeStrategy(Class<?> ctrl, Method method) {
        var route = DogeUtil.controllerActionRouteStrategy(ctrl, method);
        if (route == null) {
            return null;
        }

        var action = route.split("/", 3)[2];
        if(action.equals("index/*")) {
            return "/*";
        }

        return action;
    }

    private final Settings settings;

    public Main(Settings settings) {
        this.settings = settings;
    }

    @Override
    protected void configure() {
        bind(Settings.class).toInstance(this.settings);
    }

    @Provides
    @Singleton
    PebbleEngine initPebbleEngine() {
        var loader = new ClasspathLoader();
        loader.setPrefix("theme/");

        return new PebbleEngine.Builder()
          .loader(loader)
          .extension(new AbstractExtension() {
              @Override
              public Map<String, Object> getGlobalVariables() {
                  return Map.of("blogTitle", settings.getTitle());
              }
          })
          .cacheActive(!settings.isDebug())
          .build();
    }

    @Provides
    @Singleton
    @SneakyThrows
    public StupidQL initStupidQL() {
        var c = settings.getH2();
        var db = new Server();
        db.runTool(
          "-ifNotExists",
          "-baseDir", c.getBaseDir(),
          "-web", "-webAllowOthers", "-webPort", c.getWebPort(), "-webExternalNames", c.getExternalNames(),
          "-tcp", "-tcpAllowOthers", "-tcpPort", c.getTcpPort()
        );

        Runtime.getRuntime().addShutdownHook(new Thread(db::shutdown));

        var ds = JdbcConnectionPool.create(c.getJdbcUrl(), c.getUser(), c.getPass());
        var stupidQL = StupidQL.init(ds).addInterceptor(new StupidLogger());
        stupidQL.add("runscript from 'classpath:schema.sql'").update();
        return stupidQL;
    }

    private static void errorHandler(HttpExchange exchange, Runnable next) {
        try {
            next.run();
        } catch (Exception e) {
            exchange.html("error: " + e.getMessage());
            log.error("error: {}", e.getMessage(), e);
        }
    }


    @SneakyThrows
    public static <T> T loadYaml(String yamlPath, Class<T> ctype) {
        var is = new FileInputStream(new File(yamlPath));
        var repr = new Representer(new DumperOptions());
        repr.getPropertyUtils().setSkipMissingProperties(true);
        var yaml = new Yaml(repr);
        return yaml.loadAs(is, ctype);
    }
}