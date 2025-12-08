package onelog;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.google.inject.Inject;
import io.pebbletemplates.pebble.PebbleEngine;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class BlogController {
    @Inject
    BlogDao blogDao;
    @Inject
    Settings settings;
    @Inject
    PebbleEngine pebbleEngine;
    @Inject
    MicroGraph graphApi;
    @Inject
    SyncTask syncTask;

    @SneakyThrows
    private String view(String view, Map<String, Object> vars) {
        vars = new HashMap<>(vars);
        vars.put("sections", blogDao.sections());
        var t = pebbleEngine.getTemplate(view);
        var writer = new StringWriter();
        t.evaluate(writer, vars);
        return writer.toString();
    }

    public void actionIndex$(HttpExchange c) {
        var page = c.paramLong("page", 1L);
        var sectionId = c.path(1);

        var pages = blogDao.pageList(sectionId, page.intValue(), settings.getPageSize());
        c.html(view("index.html", Map.of("pages", pages)));
    }

    public void actionPage$(HttpExchange c) {
        var id = c.path(0);
        var page = blogDao.pageGet(id);
        c.html(view("page.html", Map.of("page", page, "pageTitle", page.getTitle())));
    }

    public void actionAuth(HttpExchange c) {
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

    public void actionSync(HttpExchange c) {
        syncTask.execute();
        c.redirect("/");
    }


    @SneakyThrows
    public void actionResources$(HttpExchange c) {
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