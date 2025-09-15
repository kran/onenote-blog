package onelog;

import cn.hutool.setting.yaml.YamlUtil;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import lombok.SneakyThrows;
import onelog.daos.BlogDao;
import onelog.data.view.GlobalViewVars;
import onelog.utils.GraphApi;
import onelog.utils.StartDb;
import org.v2u.toy.duck.Duck;

import java.io.File;
import java.io.FileInputStream;

public class BlogModule extends AbstractModule {
    private final BlogConfig config;

    @SneakyThrows
    public BlogModule(String yamlPath) {
        var is = new FileInputStream(new File(yamlPath));
        config = YamlUtil.load(is, BlogConfig.class);
    }

    @Override
    protected void configure() {
        bind(BlogConfig.class).toInstance(config);
    }

    @Provides
    @Singleton
    Duck provideDuck(StartDb startDb) {
        var c = config.getH2();
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(c.getJdbcUrl());
        hikariConfig.setUsername(c.getUser());
        hikariConfig.setPassword(c.getPass());
        hikariConfig.setMaximumPoolSize(c.getPoolSize());
        hikariConfig.setMinimumIdle(0);

        var duck = Duck.init(new HikariDataSource(hikariConfig));
        duck.add("runscript from 'classpath:schema.sql'").update();
        return duck;
    }

    @Provides
    @Singleton
    GraphApi graphApi(BlogDao blogDao) {
        return new GraphApi(config.getClientId(), config.getSecretKey(), config.getCallbackUrl(), blogDao);
    }

    @Provides
    @Singleton
    PebbleEngine pebbleEngine(GlobalViewVars globalViewVars) {
        var loader = new ClasspathLoader();
        loader.setPrefix("theme/");

        return new PebbleEngine.Builder()
          .loader(loader)
          .extension(globalViewVars)
          .cacheActive(!config.isDebug())
          .build();
    }
}