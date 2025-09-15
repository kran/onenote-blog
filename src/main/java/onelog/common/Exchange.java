package onelog.common;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.v2u.toy.json.GsonMapper;
import org.v2u.toy.json.JsonMapper;

import javax.annotation.Nullable;
import java.util.Map;


public class Exchange extends org.v2u.toy.jetty.Exchange {
    private static final JsonMapper defaultJsonMapper = new GsonMapper();

    public String[] paths() {
        var extraPath = StrUtil.removePrefix(path(), req.getServletPath()).replaceFirst("^/*", "");
        return extraPath.split("/");
    }

    public String path(int idx) {
        var paths = paths();
        if(idx >= paths.length) return null;
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