# v2 批量统计

- 生成时间：2026-10-30 22:40
- 模型：models/doclayout/PP-DocLayout-S.onnx
- 图片：42 张（v2 成功 30，回退 v1 7，审计通过 30，顺序歧义 26）

| 图片 | 链路 | v2 诊断 | 计划回退 | 区域 | 行 | tile | 覆盖 | 歧义 | 审计 | 高度 | 耗时 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 006v119zgy1ii8tu8c5jfj30l10mt4as | DOC_LAYOUT | none | NONE | 11 | 12 | 2 | 1 | true | ok | 1876 | 3194ms |
| 006v119zgy1iiavd4iffpj30z025sak4 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 3 | 0 | 0 | - | - | 0 | 2481ms |
| 006v119zgy1iiawhxmyyhj30mq0hw0vy | DOC_LAYOUT | none | NONE | 14 | 17 | 1 | 0.9999 | true | ok | 2365 | 2923ms |
| 006v119zgy1iic1iab5pqj30jt0ez0ye | DOC_LAYOUT | none | NONE | 10 | 17 | 1 | 0.9992 | true | ok | 1940 | 3246ms |
| 006v119zgy1iicd03gyc2j30j506qwik | DOC_LAYOUT | none | NONE | 7 | 11 | 1 | 0.9999 | true | ok | 1005 | 3060ms |
| 006v119zgy1iidc0d7u9hj30z025sqfz | DOC_LAYOUT | none | NONE | 20 | 29 | 3 | 0.9999 | true | ok | 4767 | 4577ms |
| 006v119zgy1iidmtwuzihj30oo0jajvp | DOC_LAYOUT | none | NONE | 7 | 15 | 1 | 0.9997 | false | ok | 1470 | 2834ms |
| 006v119zgy1iiek2wlzp5j30u019vgqd | DOC_LAYOUT | none | GAIN_TOO_SMALL | 21 | 19 | 2 | 0.9998 | true | ok | 0 | 2717ms |
| 006v119zgy1iiex780gkjj30u01fv7bb | DOC_LAYOUT | none | GAIN_TOO_SMALL | 14 | 21 | 2 | 0.9995 | false | ok | 0 | 2600ms |
| 006v119zgy1iifqry1ciaj30z025sk30 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 3 | 18 | 3 | 0 | - | - | 0 | 1582ms |
| 006v119zgy1iifu1vsy36j30kt0n0na7 | DOC_LAYOUT | none | NONE | 15 | 22 | 2 | 0.9999 | true | ok | 2381 | 2804ms |
| 006v119zgy1iigvftsz0mj30y60u1al1 | DOC_LAYOUT | none | NONE | 34 | 27 | 1 | 0.9991 | true | ok | 2858 | 2557ms |
| 006v119zgy1iihqk46zfij30z025swqq | DOC_LAYOUT | none | NONE | 14 | 34 | 3 | 1 | true | ok | 4681 | 3021ms |
| 006v119zgy1iihw7je1euj30jg0aimyi | DOC_LAYOUT | none | NONE | 8 | 11 | 1 | 0.9992 | true | ok | 1168 | 2447ms |
| 006v119zgy1iij1qwcuqlj30z00sugsg | DOC_LAYOUT | none | NONE | 6 | 13 | 1 | 1 | true | ok | 1521 | 2464ms |
| 006v119zgy1iik2r1f4z0j30j67y9npf | DOC_LAYOUT | none | GAIN_TOO_SMALL | 69 | 224 | 17 | 0.9999 | true | ok | 0 | 4665ms |
| 006v119zgy1iikdtviwztj30p41hcn23 | DOC_LAYOUT | none | NONE | 18 | 22 | 3 | 1 | true | ok | 2853 | 2802ms |
| 006v119zgy1iiketin8cdj30jf1c21kx | DOC_LAYOUT | none | NONE | 49 | 70 | 3 | 1 | true | ok | 7681 | 3362ms |
| 008BmKOegy1iifs4jtbapj30zu25oe81 | DOC_LAYOUT | none | GAIN_TOO_SMALL | 17 | 41 | 3 | 1 | true | ok | 0 | 2988ms |
| 008Kltkhly1ii9wlx18opj30j60w70wh | DOC_LAYOUT | none | NONE | 15 | 31 | 2 | 1 | false | ok | 3446 | 2887ms |
| 143114idywc94ol8yh698k | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 226ms |
| 160417kqaa1h3qqs1qzhzt | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 235ms |
| 194411bfwrss3acdcra8w3 | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 254ms |
| device-1440x2560 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 8 | 4 | 0 | - | - | 0 | 2275ms |
| image (1) | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 243ms |
| image | decode_failed | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 231ms |
| Screenshot 2026-10-30 164400 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 7 | 0 | 0 | - | - | 0 | 1228ms |
| Screenshot 2026-10-30 164439 | DOC_LAYOUT | none | NONE | 11 | 11 | 1 | 1 | true | ok | 1845 | 2779ms |
| Screenshot 2026-10-30 164821 | DOC_LAYOUT | none | NONE | 4 | 7 | 2 | 1 | true | ok | 995 | 2848ms |
| Screenshot 2026-10-30 164847 | DOC_LAYOUT | none | NONE | 31 | 21 | 3 | 0.9997 | true | ok | 4280 | 3032ms |
| 01-article-light@1080 | DOC_LAYOUT | none | NONE | 23 | 26 | 2 | 1 | true | ok | 5866 | 3089ms |
| 02-article-paper@1080 | DOC_LAYOUT | none | NONE | 21 | 19 | 2 | 1 | true | ok | 4129 | 2848ms |
| 03-news-columns@1080 | DOC_LAYOUT | none | NONE | 31 | 36 | 2 | 1 | true | ok | 6326 | 3068ms |
| 04-social-feed@1080 | DOC_LAYOUT | none | NONE | 20 | 13 | 2 | 0.9999 | true | ok | 2839 | 3035ms |
| 05-chat@1080 | PROJECTION | layout_text_as_figure | TOO_FEW_LONG_LINES | 0 | 18 | 0 | 0 | - | - | 0 | 1403ms |
| 06-product@1080 | DOC_LAYOUT | none | NONE | 32 | 20 | 2 | 1 | true | ok | 5626 | 3251ms |
| 07-dashboard@1920 | PROJECTION | layout_too_few_regions | TOO_FEW_LONG_LINES | 2 | 1 | 2 | 0 | - | - | 0 | 1535ms |
| 08-dark-article@1080 | DOC_LAYOUT | none | NONE | 22 | 19 | 2 | 0.9998 | true | ok | 3187 | 2807ms |
| 09-poster@1080 | DOC_LAYOUT | none | NONE | 7 | 5 | 2 | 1 | true | ok | 2593 | 2874ms |
| 10-long-feed@1080 | DOC_LAYOUT | none | NONE | 98 | 26 | 8 | 0.9999 | true | ok | 13821 | 4166ms |
| 11-doc-two-column@1080 | DOC_LAYOUT | none | NONE | 24 | 44 | 2 | 1 | false | ok | 5279 | 2834ms |
| 12-tight-sensitive@1080 | PROJECTION | layout_audit_failed | WEAK_CUT | 0 | 17 | 0 | 0 | - | - | 0 | 1275ms |

## v2 诊断码分布

- `none`：30
- `-`：5
- `layout_audit_failed`：3
- `layout_too_few_regions`：3
- `layout_text_as_figure`：1

## 区域类别分布（regions.json）

- 006v119zgy1ii8tu8c5jfj30l10mt4as：FIGURE:5 CHROME:3 TEXT:3
- 006v119zgy1iiavd4iffpj30z025sak4：
- 006v119zgy1iiawhxmyyhj30mq0hw0vy：FIGURE:8 TEXT:6
- 006v119zgy1iic1iab5pqj30jt0ez0ye：TEXT:7 CHROME:1 FIGURE:1 TITLE:1
- 006v119zgy1iicd03gyc2j30j506qwik：TEXT:4 FIGURE:3
- 006v119zgy1iidc0d7u9hj30z025sqfz：TEXT:11 FIGURE:7 TABLE:1 TITLE:1
- 006v119zgy1iidmtwuzihj30oo0jajvp：TEXT:4 CHROME:2 FIGURE:1
- 006v119zgy1iiek2wlzp5j30u019vgqd：FIGURE:10 TEXT:8 CHROME:2 TITLE:1
- 006v119zgy1iiex780gkjj30u01fv7bb：FIGURE:8 TEXT:5 TITLE:1
- 006v119zgy1iifqry1ciaj30z025sk30：
- 006v119zgy1iifu1vsy36j30kt0n0na7：TEXT:6 FIGURE:5 CHROME:2 TITLE:2
- 006v119zgy1iigvftsz0mj30y60u1al1：TEXT:19 FIGURE:11 TITLE:4
- 006v119zgy1iihqk46zfij30z025swqq：TEXT:8 FIGURE:3 TITLE:3
- 006v119zgy1iihw7je1euj30jg0aimyi：TEXT:7 FIGURE:1
- 006v119zgy1iij1qwcuqlj30z00sugsg：TEXT:3 TITLE:2 FIGURE:1
- 006v119zgy1iik2r1f4z0j30j67y9npf：TEXT:45 FIGURE:17 TITLE:4 CHROME:3
- 006v119zgy1iikdtviwztj30p41hcn23：TEXT:12 FIGURE:3 CHROME:1 TABLE:1 TITLE:1
- 006v119zgy1iiketin8cdj30jf1c21kx：TEXT:31 FIGURE:12 CHROME:3 TITLE:3
- 008BmKOegy1iifs4jtbapj30zu25oe81：TEXT:12 FIGURE:4 CHROME:1
- 008Kltkhly1ii9wlx18opj30j60w70wh：FIGURE:12 TEXT:2 TITLE:1
- device-1440x2560：
- Screenshot 2026-10-30 164400：
- Screenshot 2026-10-30 164439：TEXT:5 FIGURE:3 CHROME:2 TITLE:1
- Screenshot 2026-10-30 164821：TEXT:3 TABLE:1
- Screenshot 2026-10-30 164847：FIGURE:17 TEXT:14
- 01-article-light@1080：TEXT:9 FIGURE:8 CHROME:3 TITLE:3
- 02-article-paper@1080：FIGURE:10 TEXT:9 CHROME:1 TITLE:1
- 03-news-columns@1080：TEXT:14 FIGURE:8 TITLE:4 CHROME:3 TABLE:2
- 04-social-feed@1080：FIGURE:9 TEXT:7 CHROME:4
- 05-chat@1080：
- 06-product@1080：FIGURE:15 TEXT:11 TITLE:3 CHROME:2 TABLE:1
- 07-dashboard@1920：
- 08-dark-article@1080：FIGURE:9 TEXT:9 CHROME:2 TITLE:2
- 09-poster@1080：FIGURE:4 TEXT:2 TITLE:1
- 10-long-feed@1080：FIGURE:72 TEXT:23 CHROME:3
- 11-doc-two-column@1080：TEXT:11 TITLE:6 CHROME:4 FIGURE:2 TABLE:1
- 12-tight-sensitive@1080：

