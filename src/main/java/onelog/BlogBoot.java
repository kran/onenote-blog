package onelog;

import cn.hutool.cron.CronUtil;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import onelog.common.Exchange;
import onelog.tasks.OneNoteSync;
import org.v2u.doge.Doge;

@Slf4j
public class BlogBoot {
    @Inject
    BlogConfig blogConfig;
    @Inject
    Injector guice;

    public static void main(String[] args) {
        Guice.createInjector(new BlogModule(args[0]))
          .getInstance(BlogBoot.class)
          .start();
    }

    public void start() {
        //startTransferMonitors();
        startHttp();
        log.info("HTTP服务启动: " + blogConfig.getPort());

        CronUtil.schedule("0 * * * *", guice.getInstance(OneNoteSync.class));
        CronUtil.start();
    }

    @SneakyThrows
    public void startHttp() {
        var doge = new Doge.Server<>(Exchange::new)
          .port(blogConfig.getPort())
          .start();

        guice.getInstance(BlogRouter.class).init(doge);
    }
}