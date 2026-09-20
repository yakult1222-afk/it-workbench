package com.qinghuan.workbench.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NewsServiceTest {

    private final NewsService service = new NewsService("https://example.com/rss");

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>测试源</title>
                <item>
                  <title>NVIDIA 发布新一代 RTX 5090 显卡，显存翻倍</title>
                  <link>https://example.com/news/1</link>
                  <pubDate>Sun, 20 Sep 2026 08:57:00 GMT</pubDate>
                </item>
                <item>
                  <title>某地天气转凉，市民出行注意保暖</title>
                  <link>https://example.com/news/2</link>
                  <pubDate>Sun, 20 Sep 2026 09:30:00 GMT</pubDate>
                </item>
                <item>
                  <title>长江存储新一代 SSD 主控亮相</title>
                  <link>https://example.com/news/3</link>
                  <pubDate>Sun, 20 Sep 2026 10:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>AMD 锐龙新品处理器跑分曝光</title>
                  <link>https://example.com/news/4</link>
                  <pubDate>Sun, 20 Sep 2026 10:20:00 GMT</pubDate>
                </item>
                <item>
                  <title>Intel 酷睿 Ultra 移动平台续航提升</title>
                  <link>https://example.com/news/5</link>
                  <pubDate>Sun, 20 Sep 2026 10:40:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """;

    private List<Map<String, String>> parse(String xml) throws Exception {
        return service.parseFeed(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 解析RSS条目并提取标题链接与时间() throws Exception {
        List<Map<String, String>> items = parse(RSS);

        assertEquals(5, items.size());
        assertEquals("NVIDIA 发布新一代 RTX 5090 显卡，显存翻倍", items.get(0).get("title"));
        assertEquals("https://example.com/news/1", items.get(0).get("link"));
        // GMT 08:57 → 东八区 16:57
        assertEquals("2026-09-20 16:57", items.get(0).get("pubDate"));
    }

    @Test
    void 硬件关键词筛选过滤掉非硬件资讯() throws Exception {
        List<Map<String, String>> filtered = service.filterHardware(parse(RSS));

        assertEquals(4, filtered.size());
        assertTrue(filtered.stream().noneMatch(n -> n.get("title").contains("天气")));
    }

    @Test
    void 硬件命中过少时回退为完整列表() throws Exception {
        String sparse = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item><title>今日股市收评</title><link>https://example.com/a</link></item>
                  <item><title>RTX 显卡开售</title><link>https://example.com/b</link></item>
                </channel></rss>
                """;

        List<Map<String, String>> filtered = service.filterHardware(parse(sparse));

        assertEquals(2, filtered.size(), "命中不足 4 条时应回退为完整列表");
    }

    @Test
    void 兼容Atom格式() throws Exception {
        String atom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>DDR5 内存价格回落</title>
                    <link href="https://example.com/atom/1"/>
                    <published>2026-09-20T02:15:00Z</published>
                  </entry>
                </feed>
                """;

        List<Map<String, String>> items = parse(atom);

        assertEquals(1, items.size());
        assertEquals("DDR5 内存价格回落", items.get(0).get("title"));
        assertEquals("https://example.com/atom/1", items.get(0).get("link"));
        // UTC 02:15 → 东八区 10:15
        assertEquals("2026-09-20 10:15", items.get(0).get("pubDate"));
    }

    @Test
    void 非法或缺失时间不抛异常() {
        assertEquals("", service.formatPubDate(null));
        assertEquals("", service.formatPubDate("  "));
        assertEquals("", service.formatPubDate("时间不详"));
    }
}
