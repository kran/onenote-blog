package onelog;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import onelog.actions.BlogAction;
import onelog.common.Exchange;
import org.v2u.doge.Doge;

@Slf4j
@Singleton
public class BlogRouter {
    @Inject
    BlogAction blogAction;

    public void init(Doge.Server<Exchange> r) {
        addFilters(r);

        r.route("/auth", blogAction::auth);
        r.route("/sync", blogAction::sync);

        r.route("/", blogAction::index);
        r.route("/section/*", blogAction::index);
        r.route("/page/*", blogAction::page);
        r.route("/resources/*", blogAction::resources);

        r.route("/favicon.ico", c -> c.result("NO"));
    }

    private void addFilters(Doge.Server<Exchange> r) {
        r.filter("/*", (c, next) -> {
           try {
               next.run();
           } catch (Throwable e) {
               log.warn("HTTP请求处理失败", e);
               c.status(400);
               c.result(e.getMessage());
           }
        });
    }

}