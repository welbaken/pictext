# v2 批量统计

- 生成时间：2026-10-30 21:46
- 模型：models/doclayout/PP-DocLayout-S.onnx
- 图片：12 张（v2 成功 10，回退 v1 2，审计通过 10，顺序歧义 9）

| 图片 | 链路 | v2 诊断 | 计划回退 | 区域 | 行 | tile | 覆盖 | 歧义 | 审计 | 高度 | 耗时 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 01-article-light@1080 | DOC_LAYOUT | none | NONE | 23 | 26 | 2 | 1 | true | ok | 5866 | 3662ms |
| 02-article-paper@1080 | DOC_LAYOUT | none | NONE | 21 | 19 | 2 | 1 | true | ok | 4129 | 3107ms |
| 03-news-columns@1080 | DOC_LAYOUT | none | NONE | 31 | 36 | 2 | 1 | true | ok | 6302 | 3228ms |
| 04-social-feed@1080 | DOC_LAYOUT | none | NONE | 20 | 13 | 2 | 0.9999 | true | ok | 2839 | 3073ms |
| 05-chat@1080 | DOC_LAYOUT | none | NONE | 28 | 8 | 2 | 0.9997 | true | ok | 3604 | 2920ms |
| 06-product@1080 | DOC_LAYOUT | none | NONE | 32 | 20 | 2 | 1 | true | ok | 5626 | 3418ms |
| 07-dashboard@1920 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 1 | 2 | 0 | - | - | 0 | 1543ms |
| 08-dark-article@1080 | DOC_LAYOUT | none | NONE | 22 | 19 | 2 | 0.9998 | true | ok | 3206 | 2931ms |
| 09-poster@1080 | DOC_LAYOUT | none | NONE | 7 | 5 | 2 | 1 | true | ok | 2593 | 3125ms |
| 10-long-feed@1080 | DOC_LAYOUT | none | NONE | 98 | 26 | 8 | 0.9999 | true | ok | 13821 | 4818ms |
| 11-doc-two-column@1080 | DOC_LAYOUT | none | NONE | 24 | 44 | 2 | 1 | false | ok | 5279 | 3061ms |
| 12-tight-sensitive@1080 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 17 | 0 | 0 | - | - | 0 | 1441ms |

## v2 诊断码分布

- `none`：10
- `layout_too_few_regions`：1
- `layout_audit_failed`：1

## 区域类别分布（regions.json）

- 01-article-light@1080：TEXT:9 FIGURE:8 CHROME:3 TITLE:3
- 02-article-paper@1080：FIGURE:10 TEXT:9 CHROME:1 TITLE:1
- 03-news-columns@1080：TEXT:14 FIGURE:8 TITLE:4 CHROME:3 TABLE:2
- 04-social-feed@1080：FIGURE:9 TEXT:7 CHROME:4
- 05-chat@1080：FIGURE:21 TEXT:5 CHROME:2
- 06-product@1080：FIGURE:15 TEXT:11 TITLE:3 CHROME:2 TABLE:1
- 07-dashboard@1920：
- 08-dark-article@1080：FIGURE:9 TEXT:9 CHROME:2 TITLE:2
- 09-poster@1080：FIGURE:4 TEXT:2 TITLE:1
- 10-long-feed@1080：FIGURE:72 TEXT:23 CHROME:3
- 11-doc-two-column@1080：TEXT:11 TITLE:6 CHROME:4 FIGURE:2 TABLE:1
- 12-tight-sensitive@1080：

