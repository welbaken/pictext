# v2 区域检测评分（对 HTML 真值）

- 生成时间：2026-10-30 23:36，网格 8px，类别同族才算覆盖
- 只统计 v2 真正接管的 9/12 张（回退 v1 的页不计入均值）
- 平均召回 **63.6%**，平均准确 **90.9%**，平均行覆盖 **91.9%**，最低召回 41.6%

| 夹具 | 链路 | GT 区域 | 检出区域 | 召回（面积） | 准确（面积） | 行覆盖 | 顺序歧义 |
|---|---|---|---|---|---|---|---|
| 01-article-light@1080 | DOC_LAYOUT | 17 | 23 | 72% | 96% | 100% | True |
| 02-article-paper@1080 | DOC_LAYOUT | 13 | 21 | 53% | 99% | 100% | True |
| 03-news-columns@1080 | DOC_LAYOUT | 19 | 31 | 73% | 94% | 100% | True |
| 04-social-feed@1080 | DOC_LAYOUT | 13 | 20 | 75% | 97% | 90% | True |
| 05-chat@1080 | PROJECTION | 10 | 0 | 0% | 0% | 0% |  |
| 06-product@1080 | DOC_LAYOUT | 16 | 32 | 70% | 97% | 79% | True |
| 07-dashboard@1920 | PROJECTION | 14 | 0 | 0% | 0% | 0% |  |
| 08-dark-article@1080 | DOC_LAYOUT | 13 | 17 | 42% | 99% | 100% | True |
| 09-poster@1080 | DOC_LAYOUT | 7 | 12 | 56% | 47% | 92% | True |
| 10-long-feed@1080 | DOC_LAYOUT | 58 | 98 | 72% | 95% | 71% | True |
| 11-doc-two-column@1080 | DOC_LAYOUT | 21 | 24 | 60% | 94% | 95% | False |
| 12-tight-sensitive@1080 | PROJECTION | 15 | 0 | 0% | 0% | 0% |  |

