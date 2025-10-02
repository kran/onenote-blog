package onelog.tasks;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import onelog.BlogConfig;
import onelog.daos.BlogDao;
import onelog.data.models.Page;
import onelog.data.models.Section;
import onelog.utils.AbstractTask;
import onelog.utils.GraphApi;
import org.jsoup.Jsoup;
import org.v2u.stupidql.StupidQL;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Singleton
public class OneNoteSync extends AbstractTask {
    @Inject
    GraphApi graphApi;
    @Inject
    BlogDao blogDao;
    @Inject
    StupidQL stupidQL;
    @Inject
    BlogConfig blogConfig;

    private GraphApi.AccessToken token;

    @Override
    public void run() {
        if(StrUtil.isBlank(blogConfig.getNotebookId())) {
            log.warn("未配置笔记ID");
            return;
        }
        sync(blogConfig.getNotebookId());
    }

    public void sync(String notebookId) {
        var sections = syncSections(notebookId);
        sections.forEach(s -> syncPages(s.getId()));
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

            if(lastUpdate != null) {
                var timeStr = lastUpdate.atZone(systemZoneId).toInstant().toString();
                args.put("$filter", "lastModifiedDateTime gt " + timeStr);
            }

            var resp = graphApi.graphGet(path, args);
            var pages = JSONUtil.toBean(resp, Pages.class);

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

    public List<Section> syncSections(String notebookId) {
        var path = "/v1.0/me/onenote/notebooks/" + notebookId + "/sections";
        var resp = graphApi.graphGet(path, Map.of());
        var sections = JSONUtil.toBean(resp, Sections.class);
        stupidQL.transaction(tx -> {
            stupidQL.delete(BlogDao.t_section, "1 = 1");
            sections.value.forEach(sec -> tx.addInsert(sec).insert(String.class));
            return null;
        });
        return sections.value;
    }


    public static class Sections {
        public List<Section> value;
    }

    public static class Pages {
        public List<Page> value;
    }
}