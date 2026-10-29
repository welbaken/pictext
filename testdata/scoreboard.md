# 真实样例评分表（自动生成 + 人工填分）

> 自动部分由 `scripts/score-samples.ps1` 生成：目录 `testdata/samples2`，输出宽 1080，共 18 张。
> 评分列（可用 / 勉强 / 不可用）需要人工看图填写；发布门槛见 docs/05 §6。

自动统计：可重排 7 / 18，勉强 4，回退 7，审计失败 0。

| # | 样例 | 尺寸 | 行 | 图块 | 角色 | 切分列 | clean | scale | gain | 模式 | 丢弃 | 审计 | 自动结论 | 人工评分 | 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `005vnhZYgy1iii4ph2z9dj60n4094q5u02` | 690x272 | 6 | 0 | {"UI":2,"BODY":4} | 352 | 0.00 | 1.000 | 0.000 | ORIGINAL/WEAK_CUT | 0 | OK | 回退原图（WEAK_CUT） | 可用 | 百度贴吧卡片；文字行 4 条，回退原图后仍清晰（gain 0） |
| 2 | `0066CcwRgy1iihqaic8rlj30xi07nwfr` | 690x157 | 3 | 0 | {"BODY":3} | 345 | 1.00 | 1.000 | 0.000 | ORIGINAL/GAIN_TOO_SMALL | 0 | OK | 回退原图（GAIN_TOO_SMALL） | 可用 | 短评论条 690x157，本来就只有 3 行字，回退合理 |
| 3 | `006oy5WUgy1iigsddqxenj31540o47d7` | 690x405 | 4 | 1 | {"BODY":4} | 346 | 1.00 | 2.153 | 1.375 | NO_SPLIT/NONE | 0 | OK | 勉强（gain 1.38） | 可用 | 声明卡片；NO_SPLIT 整页 2.15×，红色印章与正文都完整 |
| 4 | `006v119zgy1iigzkp8qu2j30u00yxn1q` | 690x803 | 9 | 1 | {"UI":1,"CAPTION":2,"BODY":6} | 336 | 1.00 | 1.000 | 0.000 | ORIGINAL/GAIN_TOO_SMALL | 0 | OK | 回退原图（GAIN_TOO_SMALL） | 可用 | 知乎式图文；正文已够大，回退原图，照片完整 |
| 5 | `006v119zgy1iihql95nobj30z025sgya` | 690x1533 | 8 | 1 | {"UI":3,"CAPTION":2,"BODY":3} | 276 | 0.00 | 1.000 | 0.000 | ORIGINAL/WEAK_CUT | 0 | OK | 回退原图（WEAK_CUT） | 勉强 | 深色 X 长图；只有 3 条 BODY、cleanRatio 0，回退原图（可双指放大） |
| 6 | `006v119zgy1iihrmnobtwj30g00fowll` | 576x564 | 7 | 1 | {"UI":1,"BODY":5,"CAPTION":1} | 260 | 1.00 | 2.813 | 1.500 | REFLOW/NONE | 0 | OK | 可重排（gain 1.50） | 可用 | 576x564 短图文；REFLOW 2.81×，正文与图块都正常 |
| 7 | `006v119zgy1iihu3t25jwj30qo0wl17j` | 690x843 | 18 | 1 | {"CAPTION":2,"UI":4,"BODY":12} | 384 | 1.00 | 2.813 | 1.797 | REFLOW/NONE | 0 | OK | 可重排（gain 1.80） | 可用 | 深色 X 长文；18 行、2.81×，缩进引用与正文一致 |
| 8 | `006v119zgy1iihu3tp9jfj30pq0a9n03` | 690x275 | 6 | 0 | {"UI":4,"BODY":1,"CAPTION":1} | -1 | 0.00 | 1.000 | 0.000 | ORIGINAL/TOO_FEW_LONG_LINES | 0 | OK | 回退原图（TOO_FEW_LONG_LINES） | 勉强 | X 短帖 690x275；只有 1 条 BODY 长行不足，回退原图 |
| 9 | `006v119zgy1iihw7je1euj30jg0aimyi` | 690x373 | 10 | 0 | {"HEADING":1,"BODY":9} | 354 | 1.00 | 3.000 | 1.917 | REFLOW/NONE | 0 | OK | 可重排（gain 1.92） | 可用 | 知乎回答；标题按 HEADING 切分后字高 ≥ 正文（审计 1.24） |
| 10 | `006v119zgy1iij1qwcuqlj30z00sugsg` | 690x568 | 11 | 1 | {"BODY":11} | 356 | 1.00 | 2.079 | 1.329 | REFLOW/NONE | 0 | OK | 勉强（gain 1.33） | 可用 | 知乎故事；2.08×，段落间距正常 |
| 11 | `007YjO1pgy1iiin0y1zlgj30vy1efniy` | 690x1089 | 14 | 1 | {"UI":1,"CAPTION":2,"BODY":11} | 367 | 1.00 | 2.277 | 1.455 | REFLOW/NONE | 0 | OK | 勉强（gain 1.46） | 可用 | X 长推文；2.28×，头像/按钮行未被切开 |
| 12 | `0084Nct6ly1iifp2fqzqej30ob0zf7r2` | 690x1005 | 11 | 1 | {"HEADING":1,"CAPTION":1,"BODY":9} | 347 | 1.00 | 2.391 | 1.528 | REFLOW/NONE | 0 | OK | 可重排（gain 1.53） | 勉强 | 希伯来语 RTL 帖子；左右半边的阅读顺序会反（未处理 RTL），中文译注部分正常 |
| 13 | `屏幕截图_2026-10-29_115125` | 631x700 | 14 | 0 | {"HEADING":1,"UI":2,"BODY":11} | 310 | 1.00 | 2.517 | 1.471 | REFLOW/NONE | 0 | OK | 勉强（gain 1.47） | 可用 | 吃瓜日报风格长图；2.52×，标题与列表都清楚 |
| 14 | `屏幕截图_2026-10-29_140335` | 629x387 | 6 | 2 | {"BODY":5,"UI":1} | 343 | 1.00 | 2.989 | 1.741 | REFLOW/NONE | 0 | OK | 可重排（gain 1.74） | 可用 | 微博图文；2.99×，两张配图完整 |
| 15 | `屏幕截图_2026-10-29_140421` | 1172x281 | 7 | 0 | {"BODY":7} | 570 | 1.00 | 1.781 | 1.933 | REFLOW/NONE | 0 | OK | 可重排（gain 1.93） | 可用 | 1172x281 横向长条；1.78×，字确实变大但行很短 |
| 16 | `屏幕截图_2026-10-29_140827` | 606x387 | 10 | 0 | {"UI":2,"BODY":7,"HEADING":1} | -1 | 0.00 | 1.000 | 0.000 | ORIGINAL/TOO_FEW_LONG_LINES | 0 | OK | 回退原图（TOO_FEW_LONG_LINES） | 勉强 | 微博短帖 606x387；长行不足回退原图 |
| 17 | `屏幕截图_2026-10-29_140905` | 588x172 | 6 | 0 | {"UI":2,"BODY":4} | 310 | 1.00 | 2.813 | 1.532 | REFLOW/NONE | 0 | OK | 可重排（gain 1.53） | 可用 | 韩语推文 588x172；2.81×，谚文字符按字格切开无劈字 |
| 18 | `屏幕截图_2026-10-29_140958` | 589x180 | 6 | 0 | {"UI":2,"BODY":4} | 303 | 0.00 | 1.000 | 0.000 | ORIGINAL/WEAK_CUT | 0 | OK | 回退原图（WEAK_CUT） | 可用 | 英语推文 589x180；拉丁行拒绝切分 → 回退原图（符合设计） |

## 产物

- `005vnhZYgy1iii4ph2z9dj60n4094q5u02`：[after](out2/005vnhZYgy1iii4ph2z9dj60n4094q5u02/after.png) · [debug](out2/005vnhZYgy1iii4ph2z9dj60n4094q5u02/debug.png) · [plan.json](out2/005vnhZYgy1iii4ph2z9dj60n4094q5u02/plan.json)
- `0066CcwRgy1iihqaic8rlj30xi07nwfr`：[after](out2/0066CcwRgy1iihqaic8rlj30xi07nwfr/after.png) · [debug](out2/0066CcwRgy1iihqaic8rlj30xi07nwfr/debug.png) · [plan.json](out2/0066CcwRgy1iihqaic8rlj30xi07nwfr/plan.json)
- `006oy5WUgy1iigsddqxenj31540o47d7`：[after](out2/006oy5WUgy1iigsddqxenj31540o47d7/after.png) · [debug](out2/006oy5WUgy1iigsddqxenj31540o47d7/debug.png) · [plan.json](out2/006oy5WUgy1iigsddqxenj31540o47d7/plan.json)
- `006v119zgy1iigzkp8qu2j30u00yxn1q`：[after](out2/006v119zgy1iigzkp8qu2j30u00yxn1q/after.png) · [debug](out2/006v119zgy1iigzkp8qu2j30u00yxn1q/debug.png) · [plan.json](out2/006v119zgy1iigzkp8qu2j30u00yxn1q/plan.json)
- `006v119zgy1iihql95nobj30z025sgya`：[after](out2/006v119zgy1iihql95nobj30z025sgya/after.png) · [debug](out2/006v119zgy1iihql95nobj30z025sgya/debug.png) · [plan.json](out2/006v119zgy1iihql95nobj30z025sgya/plan.json)
- `006v119zgy1iihrmnobtwj30g00fowll`：[after](out2/006v119zgy1iihrmnobtwj30g00fowll/after.png) · [debug](out2/006v119zgy1iihrmnobtwj30g00fowll/debug.png) · [plan.json](out2/006v119zgy1iihrmnobtwj30g00fowll/plan.json)
- `006v119zgy1iihu3t25jwj30qo0wl17j`：[after](out2/006v119zgy1iihu3t25jwj30qo0wl17j/after.png) · [debug](out2/006v119zgy1iihu3t25jwj30qo0wl17j/debug.png) · [plan.json](out2/006v119zgy1iihu3t25jwj30qo0wl17j/plan.json)
- `006v119zgy1iihu3tp9jfj30pq0a9n03`：[after](out2/006v119zgy1iihu3tp9jfj30pq0a9n03/after.png) · [debug](out2/006v119zgy1iihu3tp9jfj30pq0a9n03/debug.png) · [plan.json](out2/006v119zgy1iihu3tp9jfj30pq0a9n03/plan.json)
- `006v119zgy1iihw7je1euj30jg0aimyi`：[after](out2/006v119zgy1iihw7je1euj30jg0aimyi/after.png) · [debug](out2/006v119zgy1iihw7je1euj30jg0aimyi/debug.png) · [plan.json](out2/006v119zgy1iihw7je1euj30jg0aimyi/plan.json)
- `006v119zgy1iij1qwcuqlj30z00sugsg`：[after](out2/006v119zgy1iij1qwcuqlj30z00sugsg/after.png) · [debug](out2/006v119zgy1iij1qwcuqlj30z00sugsg/debug.png) · [plan.json](out2/006v119zgy1iij1qwcuqlj30z00sugsg/plan.json)
- `007YjO1pgy1iiin0y1zlgj30vy1efniy`：[after](out2/007YjO1pgy1iiin0y1zlgj30vy1efniy/after.png) · [debug](out2/007YjO1pgy1iiin0y1zlgj30vy1efniy/debug.png) · [plan.json](out2/007YjO1pgy1iiin0y1zlgj30vy1efniy/plan.json)
- `0084Nct6ly1iifp2fqzqej30ob0zf7r2`：[after](out2/0084Nct6ly1iifp2fqzqej30ob0zf7r2/after.png) · [debug](out2/0084Nct6ly1iifp2fqzqej30ob0zf7r2/debug.png) · [plan.json](out2/0084Nct6ly1iifp2fqzqej30ob0zf7r2/plan.json)
- `屏幕截图_2026-10-29_115125`：[after](out2/屏幕截图_2026-10-29_115125/after.png) · [debug](out2/屏幕截图_2026-10-29_115125/debug.png) · [plan.json](out2/屏幕截图_2026-10-29_115125/plan.json)
- `屏幕截图_2026-10-29_140335`：[after](out2/屏幕截图_2026-10-29_140335/after.png) · [debug](out2/屏幕截图_2026-10-29_140335/debug.png) · [plan.json](out2/屏幕截图_2026-10-29_140335/plan.json)
- `屏幕截图_2026-10-29_140421`：[after](out2/屏幕截图_2026-10-29_140421/after.png) · [debug](out2/屏幕截图_2026-10-29_140421/debug.png) · [plan.json](out2/屏幕截图_2026-10-29_140421/plan.json)
- `屏幕截图_2026-10-29_140827`：[after](out2/屏幕截图_2026-10-29_140827/after.png) · [debug](out2/屏幕截图_2026-10-29_140827/debug.png) · [plan.json](out2/屏幕截图_2026-10-29_140827/plan.json)
- `屏幕截图_2026-10-29_140905`：[after](out2/屏幕截图_2026-10-29_140905/after.png) · [debug](out2/屏幕截图_2026-10-29_140905/debug.png) · [plan.json](out2/屏幕截图_2026-10-29_140905/plan.json)
- `屏幕截图_2026-10-29_140958`：[after](out2/屏幕截图_2026-10-29_140958/after.png) · [debug](out2/屏幕截图_2026-10-29_140958/debug.png) · [plan.json](out2/屏幕截图_2026-10-29_140958/plan.json)

