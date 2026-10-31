# v2 批量统计

- 生成时间：2026-10-31 09:34
- 模型：models/doclayout/PP-DocLayout-S.onnx
- 图片：42 张（v2 成功 30，回退 v1 7，审计通过 30，顺序歧义 24）
- 内容完整性：重复 src 0 张 / 0 组，丢块 0 张 / 0 个

| 图片 | 链路 | v2 诊断 | 计划回退 | 区域 | 行 | tile | 覆盖 | 歧义 | 审计 | 高度 | 重复src | 丢块 | 耗时 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 006v119zgy1ii8tu8c5jfj30l10mt4as | DOC_LAYOUT | none | NONE | 10 | 12 | 2 | 1 | true | ok | 1821 | 0 | 0 | 2722ms |
| 006v119zgy1iiavd4iffpj30z025sak4 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 3 | 0 | 0 | - | - | 0 | 0 | 0 | 2232ms |
| 006v119zgy1iiawhxmyyhj30mq0hw0vy | DOC_LAYOUT | none | NONE | 14 | 14 | 1 | 0.9998 | true | ok | 2223 | 0 | 0 | 2809ms |
| 006v119zgy1iic1iab5pqj30jt0ez0ye | DOC_LAYOUT | none | NONE | 10 | 16 | 1 | 1 | true | ok | 1875 | 0 | 0 | 2629ms |
| 006v119zgy1iicd03gyc2j30j506qwik | DOC_LAYOUT | none | NONE | 4 | 7 | 1 | 1 | true | ok | 760 | 0 | 0 | 2400ms |
| 006v119zgy1iidc0d7u9hj30z025sqfz | DOC_LAYOUT | none | GAIN_TOO_SMALL | 19 | 36 | 3 | 0.9998 | true | ok | 0 | 0 | 0 | 2696ms |
| 006v119zgy1iidmtwuzihj30oo0jajvp | DOC_LAYOUT | none | NONE | 7 | 15 | 1 | 1 | false | ok | 1470 | 0 | 0 | 2488ms |
| 006v119zgy1iiek2wlzp5j30u019vgqd | DOC_LAYOUT | none | GAIN_TOO_SMALL | 21 | 19 | 2 | 0.9998 | true | ok | 0 | 0 | 0 | 2558ms |
| 006v119zgy1iiex780gkjj30u01fv7bb | DOC_LAYOUT | none | GAIN_TOO_SMALL | 14 | 21 | 2 | 0.9995 | false | ok | 0 | 0 | 0 | 2542ms |
| 006v119zgy1iifqry1ciaj30z025sk30 | DOC_LAYOUT | none | NONE | 28 | 18 | 3 | 1 | true | ok | 4443 | 0 | 0 | 2901ms |
| 006v119zgy1iifu1vsy36j30kt0n0na7 | DOC_LAYOUT | none | NONE | 10 | 12 | 2 | 0.9997 | true | ok | 1647 | 0 | 0 | 2649ms |
| 006v119zgy1iigvftsz0mj30y60u1al1 | DOC_LAYOUT | none | NONE | 32 | 25 | 1 | 0.9989 | true | ok | 2591 | 0 | 0 | 2554ms |
| 006v119zgy1iihqk46zfij30z025swqq | DOC_LAYOUT | none | NONE | 16 | 21 | 3 | 1 | true | ok | 2692 | 0 | 0 | 2797ms |
| 006v119zgy1iihw7je1euj30jg0aimyi | DOC_LAYOUT | none | NONE | 7 | 9 | 1 | 1 | false | ok | 1095 | 0 | 0 | 2419ms |
| 006v119zgy1iij1qwcuqlj30z00sugsg | DOC_LAYOUT | none | NONE | 6 | 13 | 1 | 1 | true | ok | 1521 | 0 | 0 | 2483ms |
| 006v119zgy1iik2r1f4z0j30j67y9npf | DOC_LAYOUT | none | GAIN_TOO_SMALL | 68 | 224 | 17 | 1 | true | ok | 0 | 0 | 0 | 5410ms |
| 006v119zgy1iikdtviwztj30p41hcn23 | DOC_LAYOUT | none | NONE | 17 | 20 | 3 | 1 | false | ok | 2738 | 0 | 0 | 2955ms |
| 006v119zgy1iiketin8cdj30jf1c21kx | DOC_LAYOUT | none | NONE | 31 | 36 | 3 | 0.9999 | true | ok | 4888 | 0 | 0 | 3343ms |
| 008BmKOegy1iifs4jtbapj30zu25oe81 | DOC_LAYOUT | none | GAIN_TOO_SMALL | 5 | 8 | 3 | 1 | true | ok | 0 | 0 | 0 | 2906ms |
| 008Kltkhly1ii9wlx18opj30j60w70wh | DOC_LAYOUT | none | NONE | 15 | 31 | 2 | 1 | false | ok | 3446 | 0 | 0 | 2793ms |
| 143114idywc94ol8yh698k | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 230ms |
| 160417kqaa1h3qqs1qzhzt | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 218ms |
| 194411bfwrss3acdcra8w3 | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 253ms |
| device-1440x2560 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 8 | 4 | 0 | - | - | 0 | 0 | 0 | 2590ms |
| image (1) | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 236ms |
| image | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 228ms |
| Screenshot 2026-10-30 164400 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 7 | 0 | 0 | - | - | 0 | 0 | 0 | 1528ms |
| Screenshot 2026-10-30 164439 | DOC_LAYOUT | none | NONE | 11 | 11 | 1 | 1 | true | ok | 1845 | 0 | 0 | 2832ms |
| Screenshot 2026-10-30 164821 | PROJECTION | layout_text_as_figure | TOO_FEW_LONG_LINES | 0 | 7 | 0 | 0 | - | - | 0 | 0 | 0 | 1468ms |
| Screenshot 2026-10-30 164847 | DOC_LAYOUT | none | NONE | 30 | 20 | 3 | 0.9997 | true | ok | 4145 | 0 | 0 | 3218ms |
| 01-article-light@1080 | DOC_LAYOUT | none | NONE | 22 | 26 | 2 | 1 | true | ok | 5601 | 0 | 0 | 3080ms |
| 02-article-paper@1080 | DOC_LAYOUT | none | NONE | 20 | 19 | 2 | 1 | true | ok | 3873 | 0 | 0 | 2825ms |
| 03-news-columns@1080 | DOC_LAYOUT | none | NONE | 25 | 35 | 2 | 0.9999 | true | ok | 5849 | 0 | 0 | 3007ms |
| 04-social-feed@1080 | DOC_LAYOUT | none | NONE | 20 | 13 | 2 | 0.9999 | true | ok | 2839 | 0 | 0 | 3026ms |
| 05-chat@1080 | PROJECTION | layout_text_as_figure | TOO_FEW_LONG_LINES | 0 | 18 | 0 | 0 | - | - | 0 | 0 | 0 | 1358ms |
| 06-product@1080 | DOC_LAYOUT | none | NONE | 31 | 20 | 2 | 1 | true | ok | 5553 | 0 | 0 | 3224ms |
| 07-dashboard@1920 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 1 | 2 | 0 | - | - | 0 | 0 | 0 | 1602ms |
| 08-dark-article@1080 | DOC_LAYOUT | none | NONE | 16 | 23 | 2 | 1 | true | ok | 2569 | 0 | 0 | 2761ms |
| 09-poster@1080 | DOC_LAYOUT | none | NONE | 11 | 11 | 2 | 1 | true | ok | 4005 | 0 | 0 | 2989ms |
| 10-long-feed@1080 | DOC_LAYOUT | none | NONE | 89 | 26 | 8 | 0.9999 | true | ok | 12064 | 0 | 0 | 4159ms |
| 11-doc-two-column@1080 | DOC_LAYOUT | none | NONE | 24 | 44 | 2 | 1 | false | ok | 5279 | 0 | 0 | 2866ms |
| 12-tight-sensitive@1080 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 17 | 0 | 0 | - | - | 0 | 0 | 0 | 1507ms |

## v2 诊断码分布

- `none`：30
- `-`：5
- `layout_audit_failed`：3
- `layout_text_as_figure`：2
- `layout_too_few_regions`：2

## 区域类别分布（regions.json）

- 006v119zgy1ii8tu8c5jfj30l10mt4as：FIGURE:4 CHROME:3 TEXT:3
- 006v119zgy1iiavd4iffpj30z025sak4：
- 006v119zgy1iiawhxmyyhj30mq0hw0vy：FIGURE:9 TEXT:5
- 006v119zgy1iic1iab5pqj30jt0ez0ye：TEXT:7 CHROME:1 FIGURE:1 TITLE:1
- 006v119zgy1iicd03gyc2j30j506qwik：TEXT:3 FIGURE:1
- 006v119zgy1iidc0d7u9hj30z025sqfz：TEXT:13 FIGURE:4 CHROME:1 TITLE:1
- 006v119zgy1iidmtwuzihj30oo0jajvp：TEXT:4 CHROME:2 FIGURE:1
- 006v119zgy1iiek2wlzp5j30u019vgqd：FIGURE:10 TEXT:8 CHROME:2 TITLE:1
- 006v119zgy1iiex780gkjj30u01fv7bb：FIGURE:8 TEXT:5 TITLE:1
- 006v119zgy1iifqry1ciaj30z025sk30：FIGURE:16 TEXT:10 CHROME:1 TITLE:1
- 006v119zgy1iifu1vsy36j30kt0n0na7：TEXT:4 CHROME:2 FIGURE:2 TITLE:2
- 006v119zgy1iigvftsz0mj30y60u1al1：TEXT:19 FIGURE:9 TITLE:4
- 006v119zgy1iihqk46zfij30z025swqq：TEXT:7 FIGURE:5 TITLE:3 CHROME:1
- 006v119zgy1iihw7je1euj30jg0aimyi：TEXT:6 FIGURE:1
- 006v119zgy1iij1qwcuqlj30z00sugsg：TEXT:3 TITLE:2 FIGURE:1
- 006v119zgy1iik2r1f4z0j30j67y9npf：TEXT:45 FIGURE:16 TITLE:4 CHROME:3
- 006v119zgy1iikdtviwztj30p41hcn23：TEXT:11 FIGURE:3 CHROME:1 TABLE:1 TITLE:1
- 006v119zgy1iiketin8cdj30jf1c21kx：TEXT:17 FIGURE:8 CHROME:3 TITLE:3
- 008BmKOegy1iifs4jtbapj30zu25oe81：FIGURE:3 TEXT:2
- 008Kltkhly1ii9wlx18opj30j60w70wh：FIGURE:12 TEXT:2 TITLE:1
- device-1440x2560：
- Screenshot 2026-10-30 164400：
- Screenshot 2026-10-30 164439：TEXT:5 FIGURE:3 CHROME:2 TITLE:1
- Screenshot 2026-10-30 164821：
- Screenshot 2026-10-30 164847：FIGURE:17 TEXT:13
- 01-article-light@1080：TEXT:9 FIGURE:7 CHROME:3 TITLE:3
- 02-article-paper@1080：FIGURE:9 TEXT:9 CHROME:1 TITLE:1
- 03-news-columns@1080：TEXT:14 TITLE:4 CHROME:3 FIGURE:2 TABLE:2
- 04-social-feed@1080：FIGURE:9 TEXT:7 CHROME:4
- 05-chat@1080：
- 06-product@1080：FIGURE:14 TEXT:11 TITLE:3 CHROME:2 TABLE:1
- 07-dashboard@1920：
- 08-dark-article@1080：TEXT:9 CHROME:4 TITLE:2 FIGURE:1
- 09-poster@1080：FIGURE:5 TEXT:4 CHROME:1 TITLE:1
- 10-long-feed@1080：FIGURE:63 TEXT:23 CHROME:3
- 11-doc-two-column@1080：TEXT:11 TITLE:6 CHROME:4 FIGURE:2 TABLE:1
- 12-tight-sensitive@1080：

