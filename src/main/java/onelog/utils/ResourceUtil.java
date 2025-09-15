package onelog.utils;

import lombok.SneakyThrows;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 资源加载工具类
 */
public final class ResourceUtil {

    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * 私有构造函数，防止实例化
     */
    private ResourceUtil() {}

    /**
     * 以输入流的形式获取资源，可自动检索文件系统或classpath。
     *
     * <p><b>查找顺序:</b></p>
     * <ol>
     * <li>如果路径以 "classpath:" 开头，则仅在 classpath 中查找。</li>
     * <li>否则，首先尝试作为文件系统中的路径来加载。</li>
     * <li>如果文件系统中找不到，则回退到在 classpath 中查找。</li>
     * </ol>
     *
     * @param path 资源路径（例如 "config/app.properties", "/home/user/data.txt", 或 "classpath:com/example/config.xml"）
     * @return 一个表示资源的 InputStream。调用者负责关闭此流。
     * @throws FileNotFoundException 如果在任何位置都找不到该资源。
     */
    @SneakyThrows
    public static InputStream getResourceAsStream(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource path must not be null or empty");
        }

        // 1. 检查 "classpath:" 前缀
        if (path.toLowerCase().startsWith(CLASSPATH_PREFIX)) {
            String resourceName = path.substring(CLASSPATH_PREFIX.length());
            // 从 ContextClassLoader 加载，这在 Web 环境下更健壮
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
            if (in == null) {
                throw new FileNotFoundException("Cannot find resource '" + resourceName + "' in classpath");
            }
            return in;
        }

        // 2. 尝试从文件系统加载
        try {
            File file = new File(path);
            if (file.exists() && file.isFile()) {
                return new FileInputStream(file);
            }
        } catch (Exception e) {
            // 忽略异常，继续尝试 classpath
        }

        // 3. 回退到 classpath 加载
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new FileNotFoundException("Cannot find resource '" + path + "' in file system or classpath");
        }
        return in;
    }

    /**
     * 一个辅助方法，用于将 InputStream 方便地读取为字符串。
     * 仅用于演示，对于大文件不推荐。
     *
     * @param inputStream 输入流
     * @return 文件内容的字符串表示
     */
    public static String streamToString(InputStream inputStream) {
        // 使用 try-with-resources 确保流被关闭
        try (Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
            // \A 是正则表达式，匹配输入的开始，所以 next() 会读取整个流
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }

    public static String readStr(String path) {
        return streamToString(getResourceAsStream(path));
    }
}