# v2 批量统计

- 生成时间：2026-10-31 12:41
- 模型：models/doclayout/PP-DocLayout-S.onnx
- 图片：42 张（v2 成功 33，v2 失败显示原图 4，解码失败 5，审计通过 33，顺序歧义 28）
- 说明：强化重排失败**不再回退 v1**；mode=ORIGINAL + fallback=LAYOUT_FALLBACK 即失败
- 内容完整性：重复 src 0 张 / 0 组，丢块 0 张 / 0 个

| 图片 | 链路 | v2 成功 | 模式 | v2 诊断 | 计划回退 | 区域 | 行 | tile | 覆盖 | 歧义 | 审计 | 高度 | 重复src | 丢块 | 耗时 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 006v119zgy1ii8tu8c5jfj30l10mt4as | DOC_LAYOUT | True | REFLOW | none | NONE | 9 | 12 | 2 | 1 | true | ok | 1763 | 0 | 0 | 2668ms |
| 006v119zgy1iiavd4iffpj30z025sak4 | DOC_LAYOUT | False | ORIGINAL | layout_audit_failed | LAYOUT_FALLBACK | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 1601ms |
| 006v119zgy1iiawhxmyyhj30mq0hw0vy | DOC_LAYOUT | True | REFLOW | none | NONE | 11 | 14 | 1 | 0.9993 | true | ok | 2091 | 0 | 0 | 2586ms |
| 006v119zgy1iic1iab5pqj30jt0ez0ye | DOC_LAYOUT | True | REFLOW | none | NONE | 10 | 16 | 1 | 0.9992 | true | ok | 1861 | 0 | 0 | 2522ms |
| 006v119zgy1iicd03gyc2j30j506qwik | DOC_LAYOUT | True | REFLOW | none | NONE | 5 | 7 | 1 | 0.999 | true | ok | 797 | 0 | 0 | 2662ms |
| 006v119zgy1iidc0d7u9hj30z025sqfz | DOC_LAYOUT | True | REFLOW | none | NONE | 37 | 42 | 3 | 0.9968 | true | ok | 4950 | 0 | 0 | 2998ms |
| 006v119zgy1iidmtwuzihj30oo0jajvp | DOC_LAYOUT | True | REFLOW | none | NONE | 10 | 15 | 1 | 1 | false | ok | 1659 | 0 | 0 | 2618ms |
| 006v119zgy1iiek2wlzp5j30u019vgqd | DOC_LAYOUT | True | ORIGINAL | none | GAIN_TOO_SMALL | 23 | 19 | 2 | 0.9991 | true | ok | 0 | 0 | 0 | 2624ms |
| 006v119zgy1iiex780gkjj30u01fv7bb | DOC_LAYOUT | True | ORIGINAL | none | GAIN_TOO_SMALL | 11 | 21 | 2 | 0.9979 | false | ok | 0 | 0 | 0 | 2615ms |
| 006v119zgy1iifqry1ciaj30z025sk30 | DOC_LAYOUT | True | REFLOW | none | NONE | 27 | 18 | 3 | 1 | true | ok | 4328 | 0 | 0 | 2859ms |
| 006v119zgy1iifu1vsy36j30kt0n0na7 | DOC_LAYOUT | True | REFLOW | none | NONE | 11 | 12 | 2 | 1 | true | ok | 1692 | 0 | 0 | 2660ms |
| 006v119zgy1iigvftsz0mj30y60u1al1 | DOC_LAYOUT | True | REFLOW | none | NONE | 32 | 24 | 1 | 0.9986 | true | ok | 2523 | 0 | 0 | 2577ms |
| 006v119zgy1iihqk46zfij30z025swqq | DOC_LAYOUT | True | REFLOW | none | NONE | 16 | 21 | 3 | 1 | true | ok | 2677 | 0 | 0 | 2884ms |
| 006v119zgy1iihw7je1euj30jg0aimyi | DOC_LAYOUT | True | REFLOW | none | NONE | 8 | 9 | 1 | 0.9985 | false | ok | 1168 | 0 | 0 | 2526ms |
| 006v119zgy1iij1qwcuqlj30z00sugsg | DOC_LAYOUT | True | REFLOW | none | NONE | 7 | 13 | 1 | 0.9998 | true | ok | 1481 | 0 | 0 | 2524ms |
| 006v119zgy1iik2r1f4z0j30j67y9npf | DOC_LAYOUT | True | ORIGINAL | none | GAIN_TOO_SMALL | 67 | 218 | 17 | 0.9999 | true | ok | 0 | 0 | 0 | 4680ms |
| 006v119zgy1iikdtviwztj30p41hcn23 | DOC_LAYOUT | True | REFLOW | none | NONE | 18 | 20 | 3 | 0.9999 | true | ok | 2816 | 0 | 0 | 2763ms |
| 006v119zgy1iiketin8cdj30jf1c21kx | DOC_LAYOUT | True | REFLOW | none | NONE | 29 | 36 | 3 | 1 | true | ok | 4775 | 0 | 0 | 3162ms |
| 008BmKOegy1iifs4jtbapj30zu25oe81 | DOC_LAYOUT | True | ORIGINAL | none | GAIN_TOO_SMALL | 5 | 8 | 3 | 1 | true | ok | 0 | 0 | 0 | 2786ms |
| 008Kltkhly1ii9wlx18opj30j60w70wh | DOC_LAYOUT | True | REFLOW | none | NONE | 14 | 31 | 2 | 1 | false | ok | 3387 | 0 | 0 | 2787ms |
| 143114idywc94ol8yh698k | decode_failed | False | ? | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 219ms |
| 160417kqaa1h3qqs1qzhzt | decode_failed | False | ? | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 207ms |
| 194411bfwrss3acdcra8w3 | decode_failed | False | ? | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 219ms |
| device-1440x2560 | DOC_LAYOUT | False | ORIGINAL | layout_too_few_regions | LAYOUT_FALLBACK | 2 | 0 | 4 | 0 | - | - | 0 | 0 | 0 | 2245ms |
| image (1) | decode_failed | False | ? | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 226ms |
| image | decode_failed | False | ? | - | none | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 228ms |
| Screenshot 2026-10-30 164400 | DOC_LAYOUT | True | REFLOW | none | NONE | 9 | 8 | 1 | 1 | true | ok | 1745 | 0 | 0 | 2560ms |
| Screenshot 2026-10-30 164439 | DOC_LAYOUT | True | REFLOW | none | NONE | 15 | 11 | 1 | 0.9997 | true | ok | 2259 | 0 | 0 | 2681ms |
| Screenshot 2026-10-30 164821 | DOC_LAYOUT | True | REFLOW | none | NONE | 3 | 2 | 2 | 1 | true | ok | 705 | 0 | 0 | 2734ms |
| Screenshot 2026-10-30 164847 | DOC_LAYOUT | True | REFLOW | none | NONE | 36 | 18 | 3 | 0.9971 | true | ok | 4791 | 0 | 0 | 3022ms |
| 01-article-light@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 23 | 26 | 2 | 0.9999 | true | ok | 5500 | 0 | 0 | 2875ms |
| 02-article-paper@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 21 | 19 | 2 | 0.9998 | true | ok | 4027 | 0 | 0 | 2738ms |
| 03-news-columns@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 25 | 34 | 2 | 0.9997 | true | ok | 5836 | 0 | 0 | 2844ms |
| 04-social-feed@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 15 | 13 | 2 | 0.9997 | true | ok | 2476 | 0 | 0 | 2786ms |
| 05-chat@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 19 | 8 | 2 | 0.9995 | true | ok | 2765 | 0 | 0 | 2617ms |
| 06-product@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 33 | 20 | 2 | 0.9998 | true | ok | 5719 | 0 | 0 | 2973ms |
| 07-dashboard@1920 | DOC_LAYOUT | False | ORIGINAL | layout_too_few_regions | LAYOUT_FALLBACK | 2 | 0 | 2 | 0 | - | - | 0 | 0 | 0 | 1329ms |
| 08-dark-article@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 16 | 23 | 2 | 1 | true | ok | 2569 | 0 | 0 | 2613ms |
| 09-poster@1080 | DOC_LAYOUT | True | NO_SPLIT | none | NONE | 12 | 11 | 2 | 1 | true | ok | 4153 | 0 | 0 | 2775ms |
| 10-long-feed@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 104 | 38 | 8 | 0.9995 | true | ok | 13293 | 0 | 0 | 4026ms |
| 11-doc-two-column@1080 | DOC_LAYOUT | True | REFLOW | none | NONE | 24 | 44 | 2 | 1 | false | ok | 5279 | 0 | 0 | 2671ms |
| 12-tight-sensitive@1080 | DOC_LAYOUT | False | ORIGINAL | layout_audit_failed | LAYOUT_FALLBACK | 0 | 0 | 0 | 0 | - | - | 0 | 0 | 0 | 1208ms |

## v2 诊断码分布

- `none`：33
- `-`：5
- `layout_audit_failed`：2
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
- Screenshot 2026-10-30 164821：TEXT:2 TABLE:1
- Screenshot 2026-10-30 164847：FIGURE:24 TEXT:12
- 01-article-light@1080：TEXT:9 FIGURE:8 CHROME:3 TITLE:3
- 02-article-paper@1080：FIGURE:10 TEXT:9 CHROME:1 TITLE:1
- 03-news-columns@1080：TEXT:13 TITLE:4 CHROME:3 FIGURE:3 TABLE:2
- 04-social-feed@1080：TEXT:7 FIGURE:4 CHROME:4
- 05-chat@1080：FIGURE:12 TEXT:5 CHROME:2
- 06-product@1080：FIGURE:16 TEXT:11 TITLE:3 CHROME:2 TABLE:1
- 07-dashboard@1920：
- 08-dark-article@1080：TEXT:9 CHROME:4 TITLE:2 FIGURE:1
- 09-poster@1080：FIGURE:6 TEXT:4 CHROME:1 TITLE:1
- 10-long-feed@1080：FIGURE:72 TEXT:29 CHROME:3
- 11-doc-two-column@1080：TEXT:11 TITLE:6 CHROME:4 FIGURE:2 TABLE:1
- 12-tight-sensitive@1080：

