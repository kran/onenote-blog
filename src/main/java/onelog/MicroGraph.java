package onelog;

import cn.hutool.core.net.url.UrlQuery;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Singleton
public class MicroGraph {
    private static final String TENANT = "consumers";
    private static final String OAUTH_URL = "https://login.microsoftonline.com/" + TENANT;
    private static final String GRAPH_URL = "https://graph.microsoft.com/";
    private final List<String> SCOPES = List.of("offline_access", "user.read", "notes.read");
    private AccessToken token;

    @Inject
    private Settings settings;
    @Inject
    private BlogDao blogDao;

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

    public AccessToken getAccessToken(String code) {
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
        var token = JSONUtil.toBean(resp.body(), AccessToken.class);
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