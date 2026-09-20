package com.qinghuan.workbench.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PC 硬件新闻服务：抓取公开 RSS 源并按硬件关键词筛选，全部源失败时降级为内置示例数据
 */
@Slf4j
@Service
public class NewsService {

    /** PC 硬件相关关键词，用于从科技资讯流中筛选硬件新闻 */
    private static final List<String> HARDWARE_KEYWORDS = List.of(
            "显卡", "GPU", "CPU", "处理器", "锐龙", "酷睿", "骁龙", "内存", "DDR", "硬盘", "SSD",
            "固态", "主板", "笔记本", "台式机", "显示器", "电源", "散热", "机箱", "键盘", "鼠标",
            "芯片", "NVIDIA", "AMD", "Intel", "RTX", "Radeon", "GeForce", "苹果", "华为",
            "联想", "华硕", "微星", "技嘉", "戴尔", "惠普", "Mac", "平板", "网卡",
            "路由器", "服务器", "工作站", "准系统", "风扇", "水冷"
    );

    private static final int MAX_ITEMS = 12;
    private static final int MIN_HARDWARE_ITEMS = 4;
    private static final int MAX_REDIRECTS = 3;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 8000;
    /** 抓取结果缓存 30 分钟，避免频繁请求外部源 */
    private static final long CACHE_MILLIS = 30 * 60 * 1000L;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** RSS 源列表（按优先级，可用 workbench.news.sources 配置覆盖，逗号分隔） */
    private final List<String> rssSources;

    private volatile List<Map<String, String>> cache;
    private volatile long cacheAt = 0;
    private volatile boolean cachedFromLive = false;

    public NewsService(
            @Value("${workbench.news.sources:https://www.ithome.com/rss/,https://rss.mydrivers.com/,https://www.tomshardware.com/feeds/all}")
            String sources) {
        this.rssSources = Arrays.stream(sources.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<Map<String, String>> hardwareNews() {
        List<Map<String, String>> cached = cache;
        if (cached != null && System.currentTimeMillis() - cacheAt < CACHE_MILLIS) {
            return cached;
        }
        for (String source : rssSources) {
            try {
                List<Map<String, String>> items = fetchRss(source);
                if (!items.isEmpty()) {
                    cache = items;
                    cacheAt = System.currentTimeMillis();
                    cachedFromLive = true;
                    return items;
                }
            } catch (Exception e) {
                log.warn("RSS 源抓取失败: {} - {}", source, e.getMessage());
            }
        }
        // 全部源失败：已有缓存则继续用缓存，否则降级内置示例数据
        if (cache != null) {
            return cache;
        }
        cache = fallbackNews();
        cacheAt = System.currentTimeMillis();
        cachedFromLive = false;
        return cache;
    }

    /** 当前数据是否来自实时源 */
    public boolean isLive() {
        return cachedFromLive;
    }

    private List<Map<String, String>> fetchRss(String sourceUrl) throws Exception {
        String url = sourceUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = openConnection(url);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isBlank()) {
                    throw new RuntimeException("重定向缺少 Location：" + code);
                }
                url = new URL(new URL(url), location).toString();
                continue;
            }
            if (code != 200) {
                throw new RuntimeException("HTTP " + code);
            }
            return filterHardware(parseFeed(conn.getInputStream()));
        }
        throw new RuntimeException("重定向次数过多");
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        // 自行处理跳转，避免默认跟随策略在不同 JDK 下不一致
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (ITWorkbench/1.0)");
        return conn;
    }

    /**
     * 按硬件关键词筛选；命中过少时返回原始列表，保证首页始终有内容
     */
    List<Map<String, String>> filterHardware(List<Map<String, String>> all) {
        List<Map<String, String>> hardware = all.stream()
                .filter(n -> isHardwareRelated(n.get("title")))
                .limit(MAX_ITEMS)
                .toList();
        return hardware.size() >= MIN_HARDWARE_ITEMS ? hardware : all.stream().limit(MAX_ITEMS).toList();
    }

    private boolean isHardwareRelated(String title) {
        if (title == null) {
            return false;
        }
        String lower = title.toLowerCase();
        return HARDWARE_KEYWORDS.stream().anyMatch(k -> lower.contains(k.toLowerCase()));
    }

    /** 解析 RSS 2.0 / Atom 文档 */
    List<Map<String, String>> parseFeed(InputStream in) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        try (InputStream stream = in) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 防 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(stream);
            NodeList items = doc.getElementsByTagName("item");
            if (items.getLength() == 0) {
                items = doc.getElementsByTagName("entry"); // Atom 兼容
            }
            for (int i = 0; i < items.getLength() && result.size() < MAX_ITEMS * 4; i++) {
                Element item = (Element) items.item(i);
                String title = firstText(item, "title");
                if (title == null || title.isBlank()) {
                    continue;
                }
                String link = firstText(item, "link");
                if (link == null || link.isBlank()) {
                    // Atom 形式：<link href="..."/>，href 挂在 link 子元素上
                    NodeList links = item.getElementsByTagName("link");
                    if (links.getLength() > 0) {
                        link = ((Element) links.item(0)).getAttribute("href");
                    }
                }
                String pubDate = firstText(item, "pubDate");
                if (pubDate == null || pubDate.isEmpty()) {
                    pubDate = firstText(item, "published");
                }
                Map<String, String> news = new LinkedHashMap<>();
                news.put("title", title.trim());
                news.put("link", link == null ? "" : link.trim());
                news.put("pubDate", formatPubDate(pubDate));
                result.add(news);
            }
        }
        return result;
    }

    private String firstText(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        if (nodes.getLength() > 0 && nodes.item(0).getTextContent() != null) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    /** 统一格式化为 yyyy-MM-dd HH:mm（东八区） */
    String formatPubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return "";
        }
        String value = pubDate.trim();
        try {
            // ISO 8601（Atom）
            return DATE_FMT.format(Instant.parse(value).atZone(ZONE_SHANGHAI));
        } catch (Exception ignore) {
            // 继续尝试 RFC 1123 / RFC 822
        }
        try {
            return DATE_FMT.format(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(ZONE_SHANGHAI));
        } catch (Exception ignore) {
            return "";
        }
    }

    /** 降级内置示例数据（实时源不可达时展示） */
    private List<Map<String, String>> fallbackNews() {
        String[][] data = {
                {"示例：AMD 锐龙 9000 系列桌面处理器评测出炉，Zen5 架构能效大幅提升", "https://www.ithome.com"},
                {"示例：NVIDIA 发布新一代 RTX 显卡，DLSS 5.0 带来帧率翻倍", "https://www.ithome.com"},
                {"示例：Intel 酷睿 Ultra 300 系列移动端曝光，全新核显架构", "https://www.ithome.com"},
                {"示例：DDR5 内存价格持续走低，8000MHz 高频条进入主流价位", "https://www.ithome.com"},
                {"示例：PCIe 6.0 规范定稿，带宽再翻倍，首批 SSD 原型亮相", "https://www.ithome.com"},
                {"示例：国产长江存储发布第四代 QLC 闪存，堆叠层数突破 300 层", "https://www.ithome.com"}
        };
        List<Map<String, String>> list = new ArrayList<>();
        for (String[] d : data) {
            Map<String, String> news = new LinkedHashMap<>();
            news.put("title", d[0]);
            news.put("link", d[1]);
            news.put("pubDate", "");
            list.add(news);
        }
        return list;
    }
}
