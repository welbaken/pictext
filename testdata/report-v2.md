# v2 批量统计

- 生成时间：2026-10-31 10:17
- 模型：models/doclayout/PP-DocLayout-S.onnx
- 图片：42 张（v2 成功 31，回退 v1 6，审计通过 31，顺序歧义 26）
- 内容完整性：重复 src 0 张 / 0 组，丢块 0 张 / 0 个

| 图片 | 链路 | v2 诊断 | 计划回退 | 区域 | 行 | tile | 覆盖 | 歧义 | 审计 | 高度 | 重复src | 丢块 | 耗时 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 006v119zgy1ii8tu8c5jfj30l10mt4as | DOC_LAYOUT | none | NONE | 9 | 12 | 2 | 1 | true | ok | 1763 | 0 | 0 | 3025ms |
| 006v119zgy1iiavd4iffpj30z025sak4 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 3 | 0 | 0 | - | - | 0 | 0 | 0 | 1907ms |
| 006v119zgy1iiawhxmyyhj30mq0hw0vy | DOC_LAYOUT | none | NONE | 11 | 14 | 1 | 0.9993 | true | ok | 2091 | 0 | 0 | 2974ms |
| 006v119zgy1iic1iab5pqj30jt0ez0ye | DOC_LAYOUT | none | NONE | 10 | 16 | 1 | 0.9992 | true | ok | 1861 | 0 | 0 | 2816ms |
| 006v119zgy1iicd03gyc2j30j506qwik | DOC_LAYOUT | none | NONE | 5 | 7 | 1 | 0.999 | true | ok | 797 | 0 | 0 | 2624ms |
| 006v119zgy1iidc0d7u9hj30z025sqfz | DOC_LAYOUT | none | NONE | 37 | 42 | 3 | 0.9968 | true | ok | 4950 | 0 | 0 | 3174ms |
| 006v119zgy1iidmtwuzihj30oo0jajvp | DOC_LAYOUT | none | NONE | 10 | 15 | 1 | 1 | false | ok | 1659 | 0 | 0 | 2721ms |
| 006v119zgy1iiek2wlzp5j30u019vgqd | DOC_LAYOUT | none | GAIN_TOO_SMALL | 23 | 19 | 2 | 0.9991 | true | ok | 0 | 0 | 0 | 2737ms |
| 006v119zgy1iiex780gkjj30u01fv7bb | DOC_LAYOUT | none | GAIN_TOO_SMALL | 11 | 21 | 2 | 0.9979 | false | ok | 0 | 0 | 0 | 2758ms |
| 006v119zgy1iifqry1ciaj30z025sk30 | DOC_LAYOUT | none | NONE | 27 | 18 | 3 | 1 | true | ok | 4328 | 0 | 0 | 3196ms |
| 006v119zgy1iifu1vsy36j30kt0n0na7 | DOC_LAYOUT | none | NONE | 11 | 12 | 2 | 1 | true | ok | 1692 | 0 | 0 | 2759ms |
| 006v119zgy1iigvftsz0mj30y60u1al1 | DOC_LAYOUT | none | NONE | 32 | 24 | 1 | 0.9986 | true | ok | 2523 | 0 | 0 | 2725ms |
| 006v119zgy1iihqk46zfij30z025swqq | DOC_LAYOUT | none | NONE | 16 | 21 | 3 | 1 | true | ok | 2677 | 0 | 0 | 2915ms |
| 006v119zgy1iihw7je1euj30jg0aimyi | DOC_LAYOUT | none | NONE | 8 | 9 | 1 | 0.9985 | false | ok | 1168 | 0 | 0 | 2515ms |
| 006v119zgy1iij1qwcuqlj30z00sugsg | DOC_LAYOUT | none | NONE | 7 | 13 | 1 | 0.9998 | true | ok | 1481 | 0 | 0 | 2627ms |
| 006v119zgy1iik2r1f4z0j30j67y9npf | DOC_LAYOUT | none | GAIN_TOO_SMALL | 67 | 218 | 17 | 0.9999 | true | ok | 0 | 0 | 0 | 5326ms |
| 006v119zgy1iikdtviwztj30p41hcn23 | DOC_LAYOUT | none | NONE | 18 | 20 | 3 | 0.9999 | true | ok | 2816 | 0 | 0 | 3184ms |
| 006v119zgy1iiketin8cdj30jf1c21kx | DOC_LAYOUT | none | NONE | 29 | 36 | 3 | 1 | true | ok | 4775 | 0 | 0 | 3270ms |
| 008BmKOegy1iifs4jtbapj30zu25oe81 | DOC_LAYOUT | none | GAIN_TOO_SMALL | 5 | 8 | 3 | 1 | true | ok | 0 | 0 | 0 | 3051ms |
| 008Kltkhly1ii9wlx18opj30j60w70wh | DOC_LAYOUT | none | NONE | 14 | 31 | 2 | 1 | false | ok | 3387 | 0 | 0 | 2895ms |
| 143114idywc94ol8yh698k | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 235ms |
| 160417kqaa1h3qqs1qzhzt | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 240ms |
| 194411bfwrss3acdcra8w3 | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 222ms |
| device-1440x2560 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 8 | 4 | 0 | - | - | 0 | 0 | 0 | 2454ms |
| image (1) | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 234ms |
| image | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 224ms |
| Screenshot 2026-10-30 164400 | DOC_LAYOUT | none | NONE | 9 | 8 | 1 | 1 | true | ok | 1745 | 0 | 0 | 2603ms |
| Screenshot 2026-10-30 164439 | DOC_LAYOUT | none | NONE | 15 | 11 | 1 | 0.9997 | true | ok | 2259 | 0 | 0 | 2651ms |
| Screenshot 2026-10-30 164821 | PROJECTION | layout_text_as_figure | TOO_FEW_LONG_LINES | 0 | 7 | 0 | 0 | - | - | 0 | 0 | 0 | 1361ms |
| Screenshot 2026-10-30 164847 | DOC_LAYOUT | none | NONE | 36 | 18 | 3 | 0.9971 | true | ok | 4791 | 0 | 0 | 3056ms |
| 01-article-light@1080 | DOC_LAYOUT | none | NONE | 23 | 26 | 2 | 0.9999 | true | ok | 5500 | 0 | 0 | 3130ms |
| 02-article-paper@1080 | DOC_LAYOUT | none | NONE | 21 | 19 | 2 | 0.9998 | true | ok | 4027 | 0 | 0 | 2805ms |
| 03-news-columns@1080 | DOC_LAYOUT | none | NONE | 25 | 34 | 2 | 0.9997 | true | ok | 5836 | 0 | 0 | 3120ms |
| 04-social-feed@1080 | DOC_LAYOUT | none | NONE | 15 | 13 | 2 | 0.9997 | true | ok | 2476 | 0 | 0 | 2997ms |
| 05-chat@1080 | PROJECTION | layout_text_as_figure | TOO_FEW_LONG_LINES | 0 | 18 | 0 | 0 | - | - | 0 | 0 | 0 | 1296ms |
| 06-product@1080 | DOC_LAYOUT | none | NONE | 33 | 20 | 2 | 0.9998 | true | ok | 5719 | 0 | 0 | 3081ms |
| 07-dashboard@1920 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 1 | 2 | 0 | - | - | 0 | 0 | 0 | 1661ms |
| 08-dark-article@1080 | DOC_LAYOUT | none | NONE | 16 | 23 | 2 | 1 | true | ok | 2569 | 0 | 0 | 2771ms |
| 09-poster@1080 | DOC_LAYOUT | none | NONE | 12 | 11 | 2 | 1 | true | ok | 4153 | 0 | 0 | 3450ms |
| 10-long-feed@1080 | DOC_LAYOUT | none | NONE | 104 | 38 | 8 | 0.9995 | true | ok | 13293 | 0 | 0 | 5151ms |
| 11-doc-two-column@1080 | DOC_LAYOUT | none | NONE | 24 | 44 | 2 | 1 | false | ok | 5279 | 0 | 0 | 3250ms |
| 12-tight-sensitive@1080 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 17 | 0 | 0 | - | - | 0 | 0 | 0 | 1574ms |

## v2 诊断码分布

- `none`：31
- `-`：5
- `layout_audit_failed`：2
- `layout_text_as_figure`：2
- `layout_too_few_regions`：2

## 区域类别分布（regions.json）

- 006v119zgy1ii8tu8c5jfj30l10mt4as：CHROME:3 FIGURE:3 TEXT:3
- 006v119zgy1iiavd4iffpj30z025sak4：
- 006v119zgy1iiawhxmyyhj30mq0hw0vy：FIGURE:6 TEXT:5
- 006v119zgy1iic1iab5pqj30jt0ez0ye：TEXT:7 CHROME:1 FIGURE:1 TITLE:1
- 006v119zgy1iicd03gyc2j30j506qwik：TEXT:3 FIGURE:2
- 006v119zgy1iidc0d7u9hj30z025sqfz：FIGURE:18 TEXT:17 CHROME:1 TITLE:1
- 006v119zgy1iidmtwuzihj30oo0jajvp：FIGURE:4 TEXT:4 CHROME:2
- 006v119zgy1iiek2wlzp5j30u019vgqd：FIGURE:12 TEXT:8 CHROME:2 TITLE:1
- 006v119zgy1iiex780gkjj30u01fv7bb：FIGURE:5 TEXT:5 TITLE:1
- 006v119zgy1iifqry1ciaj30z025sk30：FIGURE:15 TEXT:10 CHROME:1 TITLE:1
- 006v119zgy1iifu1vsy36j30kt0n0na7：TEXT:4 FIGURE:3 CHROME:2 TITLE:2
- 006v119zgy1iigvftsz0mj30y60u1al1：TEXT:18 FIGURE:10 TITLE:4
- 006v119zgy1iihqk46zfij30z025swqq：TEXT:7 FIGURE:5 TITLE:3 CHROME:1
- 006v119zgy1iihw7je1euj30jg0aimyi：TEXT:6 FIGURE:2
- 006v119zgy1iij1qwcuqlj30z00sugsg：TEXT:3 FIGURE:2 TITLE:2
- 006v119zgy1iik2r1f4z0j30j67y9npf：TEXT:43 FIGURE:17 TITLE:4 CHROME:3
- 006v119zgy1iikdtviwztj30p41hcn23：TEXT:11 FIGURE:4 CHROME:1 TABLE:1 TITLE:1
- 006v119zgy1iiketin8cdj30jf1c21kx：TEXT:17 FIGURE:6 CHROME:3 TITLE:3
- 008BmKOegy1iifs4jtbapj30zu25oe81：FIGURE:3 TEXT:2
- 008Kltkhly1ii9wlx18opj30j60w70wh：FIGURE:11 TEXT:2 TITLE:1
- device-1440x2560：
- Screenshot 2026-10-30 164400：TEXT:5 FIGURE:2 CHROME:1 TITLE:1
- Screenshot 2026-10-30 164439：FIGURE:7 TEXT:5 CHROME:2 TITLE:1
- Screenshot 2026-10-30 164821：
- Screenshot 2026-10-30 164847：FIGURE:24 TEXT:12
- 01-article-light@1080：TEXT:9 FIGURE:8 CHROME:3 TITLE:3
- 02-article-paper@1080：FIGURE:10 TEXT:9 CHROME:1 TITLE:1
- 03-news-columns@1080：TEXT:13 TITLE:4 CHROME:3 FIGURE:3 TABLE:2
- 04-social-feed@1080：TEXT:7 FIGURE:4 CHROME:4
- 05-chat@1080：
- 06-product@1080：FIGURE:16 TEXT:11 TITLE:3 CHROME:2 TABLE:1
- 07-dashboard@1920：
- 08-dark-article@1080：TEXT:9 CHROME:4 TITLE:2 FIGURE:1
- 09-poster@1080：FIGURE:6 TEXT:4 CHROME:1 TITLE:1
- 10-long-feed@1080：FIGURE:72 TEXT:29 CHROME:3
- 11-doc-two-column@1080：TEXT:11 TITLE:6 CHROME:4 FIGURE:2 TABLE:1
- 12-tight-sensitive@1080：

