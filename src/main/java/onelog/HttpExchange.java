package onelog;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.v2u.doge.DogeExchange;
import org.v2u.doge.JacksonJsonMapper;
import org.v2u.doge.JsonMapper;

public class HttpExchange extends DogeExchange {
    private static final JsonMapper MAPPER = new JacksonJsonMapper();

    public HttpExchange(HttpServletRequest req, HttpServletResponse res) {
        super(req, res);
        jsonMapper(MAPPER);
    }

    public String[] paths() {
        var extraPath = StrUtil.removePrefix(path(), request().getServletPath())
          .replaceFirst("^/*", "");
        return extraPath.split("/");
    }

    public String path(int idx) {
        var paths = paths();
        if (idx >= paths.length) return null;
        return paths[idx];
    }

    public Long paramLong(String name, Long dfv) {
        try {
            return Long.parseLong(param(name));
        } catch (NumberFormatException e) {
            return dfv;
        }
    }
}