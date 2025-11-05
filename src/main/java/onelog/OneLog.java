package onelog;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.net.url.UrlQuery;
import cn.hutool.core.util.StrUtil;
import cn.hutool.cron.CronUtil;
import cn.hutool.cron.task.Task;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.Server;
import org.jsoup.Jsoup;
import org.v2u.doge.Doge;
import org.v2u.stupidql.StupidQL;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class OneLog {
    Settings settings;
    StupidQL stupidQL;
    PebbleEngine pebbleEngine;
    BlogDao blogDao;
    GraphApi graphApi;
    SyncTask syncTask;

    @SneakyThrows
    public static void main(String[] args) {
        var jsonStr = Files.readString(Paths.get(args[0]));
        var settings = JSONUtil.toBean(jsonStr, Settings.class);
        log.info("Settings: {}", settings);
        new OneLog(settings).start();
    }

    @SneakyThrows
    OneLog(Settings settings) {
        this.settings = settings;
        stupidQL = initStupidQL();
        pebbleEngine = initPebbleEngine();
        blogDao = new BlogDao();
        graphApi = new GraphApi();
        syncTask = new SyncTask();
    }

    void start() {
        //start jobs
        CronUtil.schedule(settings.cronExpr, syncTask);
        CronUtil.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CronUtil::stop));

        //start http server
        var action = new BlogAction();
        var r = new Doge.Server<>(Exchange::new)
          .port(settings.getPort())
          .start();

        r.route("/", action::index);
        r.route("/section/*", action::index);
        r.route("/page/*", action::page);
        r.route("/resources/*", action::resources);
        r.route("/auth", action::auth);
        r.route("/sync", action::sync);
        r.route("/favicon.ico", c -> c.result("NO"));
    }

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

    @SneakyThrows
    StupidQL initStupidQL() {
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
        var stupidQL = StupidQL.init(ds);
        stupidQL.add("runscript from 'classpath:schema.sql'").update();
        return stupidQL;
    }

    public class BlogAction {
        @SneakyThrows
        private String view(String view, Map<String, Object> vars) {
            vars = new HashMap<>(vars);
            vars.put("sections", blogDao.sections());
            var t = pebbleEngine.getTemplate(view);
            var writer = new StringWriter();
            t.evaluate(writer, vars);
            return writer.toString();
        }

        public void index(Exchange c) {
            var page = c.paramLong("page", 1L);
            var sectionId = c.path(0);

            var pages = blogDao.pageList(sectionId, page.intValue(), settings.getPageSize());
            c.html(view("index.html", Map.of("pages", pages)));
        }

        public void page(Exchange c) {
            var id = c.path(0);
            var page = blogDao.pageGet(id);
            c.html(view("page.html", Map.of("page", page)));
        }

        public void auth(Exchange c) {
            var code = c.param("code");
            if (StrUtil.isBlank(code)) {
                c.redirect(graphApi.makeAuthUrl());
                return;
            }

            var accessToken = graphApi.getAccessToken(code);
            var userResp = graphApi.graphGetBytes("/v1.0/me", Map.of(), accessToken.getAccessToken());
            var userJson = JSONUtil.parseObj(userResp);

            if (!settings.getEmail().equalsIgnoreCase(userJson.getStr("mail"))) {
                c.result("invalid user");
                return;
            }

            blogDao.kvSave("graphToken", JSONUtil.toJsonStr(accessToken));
            c.result("ok");
        }

        public void sync(Exchange c) {
            syncTask.execute();
            c.redirect("/");
        }


        @SneakyThrows
        public void resources(Exchange c) {
            var fileId = c.path(0);
            var file = downloadResource(fileId);

            c.header("Cache-Control", "public, max-age=31536000, immutable");
            c.result(new FileInputStream(file));
        }

        private final Map<String, Lock> fileLockMap = new ConcurrentHashMap<>();

        private File downloadResource(String fileId) throws FileNotFoundException {
            var dest = new File("./cache", fileId);
            var tmp = new File("./cache", fileId + ".tmp-" + System.currentTimeMillis());
            if (dest.exists()) {
                return dest;
            }
            Lock fileLock = fileLockMap.computeIfAbsent(fileId, k -> new ReentrantLock());
            fileLock.lock();
            try {
                if (dest.exists()) return dest;

                log.info("下载文件: " + dest);
                dest.getParentFile().mkdirs();
                var path = "/v1.0/me/onenote/resources/" + fileId + "/$value";
                var bytes = graphApi.graphGetBytes(path, Map.of());
                IoUtil.write(new FileOutputStream(tmp), true, bytes);
                //ImgUtil.compress(tmp, tmp, 0.8f);
                tmp.renameTo(dest);
                return dest;
            } finally {
                fileLock.unlock();
                fileLockMap.remove(fileId);
                tmp.deleteOnExit();
            }
        }
    }

    public class BlogDao {
        public static final String t_kv = "t_kv";
        public static final String t_section = "t_section";
        public static final String t_page = "t_page";

        public LocalDateTime pageLastUpdate(String sectionId) {
            var page = stupidQL
              .select(t_page, "section_id = ?", sectionId)
              .add("order by last_modified_date_time desc limit 1")
              .fetchBean(Model.Page.class);

            return page == null ? null : page.getLastModifiedDateTime().toLocalDateTime();
        }

        public Model.Page pageGet(String id) {
            return stupidQL.select(t_page, "id = ?", id).fetchBean(Model.Page.class);
        }

        public Model.Paged<Model.Page> pageList(String sectionId, int page, int limit) {
            var offset = (page - 1) * limit;
            var select = """
                select 
                  p.id, p.title, p.summary, p.cover, p.last_modified_date_time, p.created_date_time, 
                  s.display_name section_name 
              """;

            var q = stupidQL
              .mark(StupidQL.FIELDS, "select count(1)")
              .add("from @{1} p left join @{2} s on p.section_id = s.id where 1 = 1", t_page, t_section)
              .add(StrUtil.isNotBlank(sectionId), "and section_id = ?", sectionId);

            var total = q.fetchScalar(Long.class);
            var paged = new Model.Paged<Model.Page>(limit);
            if (total > 0) {
                var list = q.mark(StupidQL.FIELDS, select)
                  .add("order by created_date_time desc limit ?, ?", offset, limit)
                  .fetchBeans(Model.Page.class);
                paged.setTotal(total);
                paged.setList(list);
            }

            return paged;
        }

        public void pageSave(Model.Page page) {
            var sql = """
              merge into t_page (id, section_id, title, cover, summary, content, created_date_time, last_modified_date_time)
              key (id)
              values (#{id}, #{sectionId}, #{title}, #{cover}, #{summary}, #{content}, #{createdDateTime}, #{lastModifiedDateTime})
              """;
            stupidQL.add(sql, page).insert(String.class);
        }

        public String kvGet(String name) {
            var kv = stupidQL.select(t_kv, "name = ?", name).fetchBean(Model.Kv.class);
            if (kv == null) return null;
            return kv.getVal();
        }

        public <T> T kvGet(String name, Class<T> retType) {
            var kv = stupidQL.select(t_kv, "name = ?", name).fetchBean(Model.Kv.class);
            if (kv == null) return null;
            return kv.decode(retType);
        }

        public List<Model.Section> sections() {
            return stupidQL.select(t_section).fetchBeans(Model.Section.class);
        }

        public void kvSave(String name, String val) {
            var it = new Model.Kv();
            it.setName(name);
            it.setVal(val);
            it.setUpdateTs(Timestamp.from(Instant.now()));

            var sql = "merge into t_kv (name, val) key(name) values (#{name}, #{val})";
            stupidQL.add(sql, it).insert();
        }
    }

    public class SyncTask implements Task {
        private GraphApi.AccessToken token;

        @Override
        public void execute() {
            if (StrUtil.isBlank(settings.getNotebookId())) {
                log.warn("未配置笔记ID");
                return;
            }
            sync(settings.getNotebookId());
        }

        public void sync(String notebookId) {
            var sections = syncSections(notebookId);
            sections.forEach(s -> {
                if(settings.ignoreSections.contains(s.displayName)) {
                    return;
                }
                syncPages(s.getId());
            });
        }

        public void syncPages(String sectionId) {
            var path = "/v1.0/me/onenote/sections/" + sectionId + "/pages";

            var systemZoneId = ZoneId.systemDefault();
            var lastUpdate = blogDao.pageLastUpdate(sectionId);
            var pageNo = 1;
            var limit = 20;
            while (true) {
                var args = new HashMap<String, Object>();
                args.put("$orderby", "lastModifiedDateTime desc");
                args.put("$top", limit);
                args.put("$skip", (pageNo - 1) * limit);

                if (lastUpdate != null) {
                    var timeStr = lastUpdate.atZone(systemZoneId).toInstant().toString();
                    args.put("$filter", "lastModifiedDateTime gt " + timeStr);
                }

                var resp = graphApi.graphGet(path, args);
                var pages = JSONUtil.toBean(resp, SyncTask.Pages.class);

                pageNo++;

                for (var page : pages.value) {
                    var html = getPageContent(page.getId());
                    var doc = Jsoup.parse(html);
                    var imgs = doc.getElementsByTag("img");
                    for (var img : imgs) {
                        var src = img.attr("src");
                        if (!src.startsWith("https://graph.microsoft.com/")) continue;
                        var parts = src.split("/");
                        img.attr("src", "/resources/" + parts[parts.length - 2]);
                    }
                    var cover = imgs.isEmpty() ? "" : imgs.get(0).attr("src");
                    var content = doc.body().html();
                    page.setSummary(StrUtil.sub(doc.body().text(), 0, 800));
                    page.setContent(content);
                    page.setSectionId(sectionId);
                    page.setCover(cover);
                    blogDao.pageSave(page);
                }

                if (pages.value.size() < limit) {
                    break;
                }
            }
        }

        public String getPageContent(String pageId) {
            var path = "/v1.0/me/onenote/pages/" + pageId + "/content";
            return graphApi.graphGet(path, Map.of());
        }

        public List<Model.Section> syncSections(String notebookId) {
            var path = "/v1.0/me/onenote/notebooks/" + notebookId + "/sections";
            var resp = graphApi.graphGet(path, Map.of());
            var sections = JSONUtil.toBean(resp, SyncTask.Sections.class);
            sections.value = sections.value.stream().filter(it -> !it.displayName.startsWith("$")).toList();
            stupidQL.transaction(tx -> {
                stupidQL.delete(BlogDao.t_section, "1 = 1");
                sections.value.forEach(sec -> tx.addInsert(sec).insert(String.class));
                return null;
            });
            return sections.value;
        }


        public static class Sections {
            public List<Model.Section> value;
        }

        public static class Pages {
            public List<Model.Page> value;
        }
    }

    public class GraphApi {
        private static final String TENANT = "consumers";
        private static final String OAUTH_URL = "https://login.microsoftonline.com/" + TENANT;
        private static final String GRAPH_URL = "https://graph.microsoft.com/";
        private final List<String> SCOPES = List.of("offline_access", "user.read", "notes.read");
        private AccessToken token;

        public String makeAuthUrl() {
            var base = OAUTH_URL + "/oauth2/v2.0/authorize?";
            var args = new UrlQuery();
            args.add("client_id", settings.getClientId());
            args.add("response_type", "code");
            args.add("redirect_uri", settings.getCallbackUrl());
            args.add("response_mode", "query");
            args.add("scope", scopeStr());
            args.add("state", System.currentTimeMillis());
            return base + args.toString();
        }

        public GraphApi.AccessToken getAccessToken(String code) {
            var base = OAUTH_URL + "/oauth2/v2.0/token";
            var args = new UrlQuery();
            args.add("client_id", settings.getClientId());
            args.add("scopes", scopeStr());
            args.add("code", code);
            args.add("redirect_uri", settings.getCallbackUrl());
            args.add("grant_type", "authorization_code");
            args.add("client_secret", settings.getSecretKey());

            var req = HttpUtil.createPost(base);
            req.body(args.toString());
            var resp = req.execute();
            if (resp.getStatus() != 200) {
                throw new RuntimeException("获取Token失败: " + resp.body());
            }
            var token = JSONUtil.toBean(resp.body(), GraphApi.AccessToken.class);
            token.setExpireAt(System.currentTimeMillis() / 1000 + token.getExpiresIn());
            return token;
        }

        public AccessToken refreshAccessToken(String refreshToken) {
            var base = OAUTH_URL + "/oauth2/v2.0/token";
            var args = new UrlQuery();
            args.add("client_id", settings.getClientId());
            args.add("scope", scopeStr());
            args.add("refresh_token", refreshToken);
            args.add("grant_type", "refresh_token");
            args.add("client_secret", settings.getSecretKey());

            var req = HttpUtil.createPost(base);
            req.body(args.toString());
            var resp = req.execute();
            if (resp.getStatus() != 200) {
                throw new RuntimeException("刷新Token失败: " + resp.body());
            }
            var token = JSONUtil.toBean(resp.body(), AccessToken.class);
            token.setExpireAt(System.currentTimeMillis() / 1000 + token.getExpiresIn());
            return token;
        }

        public String getNiceAccessToken() {
            if (token == null) {
                token = blogDao.kvGet("graphToken", AccessToken.class);
            }

            if (token == null) {
                throw new RuntimeException("onenote未授权");
            }

            if (!token.isExpired()) {
                return token.getAccessToken();
            } else {
                token = refreshAccessToken(token.getRefreshToken());
                blogDao.kvSave("graphToken", JSONUtil.toJsonStr(token));
                return token.getAccessToken();
            }
        }

        public String graphGet(String path, Map<String, Object> args) {
            return new String(graphGetBytes(path, args));
        }

        public byte[] graphGetBytes(String path, Map<String, Object> args) {
            return graphGetBytes(path, args, getNiceAccessToken());
        }

        public byte[] graphGetBytes(String path, Map<String, Object> args, String accessToken) {
            var query = new UrlQuery(args).toString();
            var req = HttpUtil.createGet(GRAPH_URL + path + "?" + query);
            req.header("Authorization", "Bearer " + accessToken);
            var resp = req.execute();
            if (resp.getStatus() == 200) {
                return resp.bodyBytes();
            }
            throw new RuntimeException("请求graph失败: " + resp.body());
        }

        private String scopeStr() {
            return String.join("%20", SCOPES);
        }

        @Data
        public static class AccessToken {
            int status = 200;
            String tokenType;
            String scope;
            String accessToken;
            String refreshToken;
            Integer expiresIn;
            Integer extExpiresIn;
            Long expireAt;

            public boolean isExpired() {
                return expireAt == null || expireAt < System.currentTimeMillis() / 1000;
            }
        }
    }

    public static class Model {
        @Data
        @StupidQL.Info(name = BlogDao.t_kv)
        public static class Kv {
            @StupidQL.Info(insert = false, update = false)
            private Long id;

            @StupidQL.Info(update = false)
            private String name;
            private String val;
            private Timestamp updateTs;

            public <T> T decode(Class<T> retType) {
                return JSONUtil.toBean(val, retType);
            }
        }

        @Data
        @StupidQL.Info(name = BlogDao.t_section)
        public static class Section {
            private String id;
            private String displayName;
            private Timestamp createdDateTime;
            private Timestamp lastModifiedDateTime;
            private Boolean isDefault;
        }

        @Data
        @StupidQL.Info(name = BlogDao.t_page)
        public static class Page {
            private String id;
            private String sectionId;
            private String title;
            private String cover;
            private String summary;
            private String content;
            private Timestamp createdDateTime;
            private Timestamp lastModifiedDateTime;

            @StupidQL.Info(exists = false)
            private String sectionName;
        }

        @Data
        public static class Paged<T> {
            private List<T> list = new ArrayList<>();
            private Long total = 0L;
            private Long pageSize;

            public Paged(long pageSize) {
                this.pageSize = pageSize;
            }

            public double maxPage() {
                return Math.ceil(total.doubleValue() / pageSize);
            }
        }
    }

    public static class Exchange extends Doge.Exchange {
        private static final Doge.JsonMapper defaultJsonMapper = new Doge.GsonMapper();

        public String[] paths() {
            var extraPath = StrUtil.removePrefix(path(), req.getServletPath()).replaceFirst("^/*", "");
            return extraPath.split("/");
        }

        public String path(int idx) {
            var paths = paths();
            if (idx >= paths.length) return null;
            return paths[idx];
        }

        public Exchange(HttpServletRequest req, HttpServletResponse res) {
            super(req, res);
            jsonMapper(defaultJsonMapper);
        }

        public Long paramLong(String name, Long dfv) {
            try {
                return Long.parseLong(param(name));
            } catch (NumberFormatException e) {
                return dfv;
            }
        }
    }

    @Data
    public static class Settings {
        private boolean debug = false;
        private int port = 8084;
        private Settings.H2 h2;
        private String clientId;
        private String secretKey;
        private String callbackUrl;
        private String cronExpr = "0 * * * *";

        //basics
        private String title;
        private String email;
        private String notebookId;
        private int pageSize = 5;
        private List<String> ignoreSections = new ArrayList<>();

        @Data
        public static class H2 {
            private String user;
            private String pass;
            private int poolSize = 3;

            private String webPort = "7002";
            private String tcpPort = "7001";
            private String baseDir = "./data";
            private String externalNames = "";

            public String getJdbcUrl() {
                return String.format("jdbc:h2:tcp://localhost:%s/onelog", tcpPort);
            }
        }
    }
} 