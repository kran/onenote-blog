package onelog;

import cn.hutool.json.JSONUtil;
import lombok.Data;
import org.v2u.stupidql.StupidQL;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class Models {
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