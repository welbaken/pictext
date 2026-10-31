# v1 vs v2（强化重排，失败显示原图）收益对比

- 生成时间：2026-10-31 12:43，样本 37 张
- v2 = 阅读页打开「强化重排」；**v2 失败不再回退 v1，而是显示原图**（mode=ORIGINAL）

## 汇总

| 指标 | 数值 |
|---|---|
| v2 成功 | 33/37（其余 4 张失败显示原图） |
| 字号明显变大（≥1.05×） | 15 张 |
| 字号基本持平（0.98–1.05×） | 15 张 |
| 字号变小（≤0.98×，**回归**） | 3 张 |
| 字号放大中位数 | **1.008×**（均值 1.52×） |
| 重排文字行净增 | 209 行 |
| v1 只能看原图、v2 成功重排 | 11/18 张 |
| 平均耗时 v1 → v2 | 1030ms → 2902ms |

## 逐张（按字号增益降序）

| 图 | v2 成功 | 失败码 | v1 行高 | v2 行高 | 增益 | 行数 v1→v2 | 模式 |
|---|---|---|---|---|---|---|---|
| Screenshot 2026-10-30 164847 | ✓ | none | 6.3px | 47.8px | 7.56 | 16→18 | ORIGINAL→REFLOW |
| Screenshot 2026-10-30 164821 | ✓ | none | 7.7px | 47.8px | 6.21 | 7→2 | ORIGINAL→REFLOW |
| 10-long-feed@1080 | ✓ | none | 18.0px | 47.8px | 2.66 | 43→38 | NO_SPLIT→REFLOW |
| 05-chat@1080 | ✓ | none | 21.0px | 47.8px | 2.28 | 18→8 | ORIGINAL→REFLOW |
| Screenshot 2026-10-30 164400 | ✓ | none | 29.0px | 47.8px | 1.65 | 7→8 | ORIGINAL→REFLOW |
| 006v119zgy1ii8tu8c5jfj30l10mt4as | ✓ | none | 30.5px | 47.8px | 1.57 | 10→12 | ORIGINAL→REFLOW |
| 006v119zgy1iifu1vsy36j30kt0n0na7 | ✓ | none | 31.3px | 47.8px | 1.53 | 11→12 | ORIGINAL→REFLOW |
| 09-poster@1080 | ✓ | none | 28.0px | 39.0px | 1.39 | 5→11 | ORIGINAL→NO_SPLIT |
| 006v119zgy1iikdtviwztj30p41hcn23 | ✓ | none | 34.4px | 47.8px | 1.39 | 17→20 | ORIGINAL→REFLOW |
| 04-social-feed@1080 | ✓ | none | 37.1px | 47.8px | 1.29 | 15→13 | REFLOW |
| 11-doc-two-column@1080 | ✓ | none | 37.7px | 47.8px | 1.27 | 33→44 | REFLOW |
| 006v119zgy1iik2r1f4z0j30j67y9npf | ✓ | none | 39.0px | 47.0px | 1.20 | 87→218 | NO_SPLIT→ORIGINAL |
| 06-product@1080 | ✓ | none | 42.0px | 47.8px | 1.14 | 12→20 | REFLOW |
| 006v119zgy1iic1iab5pqj30jt0ez0ye | ✓ | none | 40.9px | 45.0px | 1.10 | 16→16 | REFLOW |
| 03-news-columns@1080 | ✓ | none | 44.9px | 47.8px | 1.06 | 27→34 | REFLOW |
| 02-article-paper@1080 | ✓ | none | 46.0px | 47.8px | 1.04 | 17→19 | REFLOW |
| 006v119zgy1iicd03gyc2j30j506qwik | ✓ | none | 41.7px | 42.0px | 1.01 | 7→7 | REFLOW |
| 006v119zgy1iigvftsz0mj30y60u1al1 | ✓ | none | 38.7px | 39.0px | 1.01 | 24→24 | REFLOW |
| 07-dashboard@1920 | — | layout_too_few_regions | 506.2px | 0.0px | 1.00 | 1→0 | ORIGINAL |
| Screenshot 2026-10-30 164439 | ✓ | none | 47.8px | 47.8px | 1.00 | 7→11 | REFLOW |
| 01-article-light@1080 | ✓ | none | 47.8px | 47.8px | 1.00 | 15→26 | REFLOW |
| 08-dark-article@1080 | ✓ | none | 47.8px | 47.8px | 1.00 | 20→23 | REFLOW |
| 008BmKOegy1iifs4jtbapj30zu25oe81 | ✓ | none | 153.4px | 40.7px | 1.00 | 1→8 | ORIGINAL |
| 008Kltkhly1ii9wlx18opj30j60w70wh | ✓ | none | 47.8px | 47.8px | 1.00 | 34→31 | REFLOW |
| 006v119zgy1iiketin8cdj30jf1c21kx | ✓ | none | 47.8px | 47.8px | 1.00 | 37→36 | REFLOW |
| 006v119zgy1iij1qwcuqlj30z00sugsg | ✓ | none | 47.8px | 47.8px | 1.00 | 11→13 | REFLOW |
| 006v119zgy1iihw7je1euj30jg0aimyi | ✓ | none | 45.0px | 45.0px | 1.00 | 10→9 | REFLOW |
| 006v119zgy1iiex780gkjj30u01fv7bb | ✓ | none | 43.8px | 43.8px | 1.00 | 21→21 | ORIGINAL |
| 006v119zgy1iiek2wlzp5j30u019vgqd | ✓ | none | 41.5px | 42.3px | 1.00 | 18→19 | ORIGINAL |
| 006v119zgy1iidmtwuzihj30oo0jajvp | ✓ | none | 47.8px | 47.8px | 1.00 | 15→15 | REFLOW |
| 006v119zgy1iiawhxmyyhj30mq0hw0vy | ✓ | none | 47.8px | 47.8px | 1.00 | 16→14 | REFLOW |
| 006v119zgy1iiavd4iffpj30z025sak4 | — | layout_audit_failed | 43.8px | 0.0px | 1.00 | 3→0 | ORIGINAL |
| device-1440x2560 | — | layout_too_few_regions | 39.8px | 0.0px | 1.00 | 8→0 | ORIGINAL |
| 12-tight-sensitive@1080 | — | layout_audit_failed | 23.0px | 0.0px | 1.00 | 17→0 | ORIGINAL |
| 006v119zgy1iidc0d7u9hj30z025sqfz | ✓ | none | 50.9px | 45.7px | 0.90 | 20→42 | ORIGINAL→REFLOW |
| 006v119zgy1iifqry1ciaj30z025sk30 | ✓ | none | 79.8px | 47.8px | 0.60 | 18→18 | ORIGINAL→REFLOW |
| 006v119zgy1iihqk46zfij30z025swqq | ✓ | none | 92.3px | 42.0px | 0.46 | 7→21 | ORIGINAL→REFLOW |

