package onelog.common;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.v2u.doge.Doge;

import javax.annotation.Nullable;
import java.util.Map;


public class Exchange extends Doge.Exchange {
    private static final Doge.JsonMapper defaultJsonMapper = new Doge.GsonMapper();

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