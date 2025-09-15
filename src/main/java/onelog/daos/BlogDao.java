package onelog.daos;

import cn.hutool.core.util.StrUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import onelog.data.models.Kv;
import onelog.data.models.Page;
import onelog.data.models.Section;
import org.v2u.toy.duck.Duck;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Singleton
public class BlogDao {
    @Inject
    public Duck duck;

    public static final String t_kv = "t_kv";
    public static final String t_section = "t_section";
    public static final String t_page = "t_page";

    public LocalDateTime pageLastUpdate(String sectionId) {
        var page = duck
          .select(t_page, "section_id = ?", sectionId)
          .add("order by last_modified_date_time desc limit 1")
          .fetchBean(Page.class);

        return page == null ? null : page.getLastModifiedDateTime().toLocalDateTime();
    }

    public Page pageGet(String id) {
        return duck.select(t_page, "id = ?", id).fetchBean(Page.class);
    }

    public List<Page> pageList(String sectionId, int page, int limit) {
        var offset = (page - 1) * limit;
        var sql = """
          select 
            p.id, p.title, p.summary, p.cover, p.last_modified_date_time, p.created_date_time, 
            s.display_name section_name 
          from @{page} p left join @{section} s on p.section_id = s.id 
          where 1 = 1
          """;
        return duck.add(sql, Duck.mapOf("page", t_page, "section", t_section))
          .add(StrUtil.isNotBlank(sectionId), "and section_id = ?", sectionId)
          .add("order by created_date_time desc limit ?, ?", offset, limit)
          .fetchBeans(Page.class);
    }

    public void pageSave(Page page) {
        var sql = """
          merge into t_page (id, section_id, title, cover, summary, content, created_date_time, last_modified_date_time)
          key (id)
          values (#{id}, #{sectionId}, #{title}, #{cover}, #{summary}, #{content}, #{createdDateTime}, #{lastModifiedDateTime})
          """;
        duck.add(sql, page).insert(String.class);
    }

    public String kvGet(String name) {
        var kv = duck.select(t_kv, "name = ?", name).fetchBean(Kv.class);
        if(kv == null) return null;
        return kv.getVal();
    }

    public <T> T kvGet(String name, Class<T> retType) {
        var kv = duck.select(t_kv, "name = ?", name).fetchBean(Kv.class);
        if(kv == null) return null;
        return kv.decode(retType);
    }

    public List<Section> sections() {
        return duck.select(t_section).fetchBeans(Section.class);
    }

    public void kvSave(String name, String val) {
        var it = Kv.builder()
          .name(name)
          .val(val)
          .updateTs(Timestamp.from(Instant.now()))
          .build();

        var sql = """
          merge into t_kv (name, val)
          key(name)
          values (#{name}, #{val})
          """;

        duck.add(sql, it).insert();
    }
}