package onelog;

import lombok.Data;

@Data
public class BlogConfig {
    private boolean debug = false;
    private int port = 8084;
    private H2 h2;
    private String scriptRoot = "classpath:scripts";
    private String clientId;
    private String secretKey;
    private String callbackUrl;

    //basics
    private String title;
    private String email;
    private String notebookId;

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