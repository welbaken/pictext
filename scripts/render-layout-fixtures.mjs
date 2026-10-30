#!/usr/bin/env node
/**
 * 图读 PicText · V2 版面夹具渲染器
 *
 * 用本地 Chromium/Edge 的 DevTools Protocol 把 HTML 渲染成手机/桌面尺寸的整页 PNG，
 * 并同时导出 DOM 真值（语义块、文本行、阅读顺序）。不需要 Playwright/Puppeteer，
 * 也不访问网络。
 *
 * 用法：
 *   node scripts/render-layout-fixtures.mjs
 *   node scripts/render-layout-fixtures.mjs --only 01-article-light,03-news-columns
 *   node scripts/render-layout-fixtures.mjs --width 720
 *   node scripts/render-layout-fixtures.mjs --check
 *
 * 参数：
 *   --browser <path>    浏览器可执行文件；默认自动查找 Edge/Chrome/Chromium
 *   --fixtures <path>   夹具清单，默认 testdata/layout/fixtures.json
 *   --out <dir>         输出根目录，默认 testdata/layout
 *   --only <ids>        只渲染指定 id（逗号分隔）
 *   --width <px>        覆盖清单里的宽度（调试用）
 *   --scale <n>         设备像素比，默认 1
 *   --check             只校验已有 PNG/真值，不启动浏览器
 *   --quiet             少打印
 *
 * 依赖：Node.js >= 22（内置 WebSocket / fetch），本地 Edge/Chrome。
 * 不依赖任何 npm 包。
 */

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  readFileSync,
} from "node:fs";
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import http from "node:http";
import net from "node:net";
import { tmpdir } from "node:os";
import {
  basename,
  dirname,
  join,
  resolve,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..");

const args = process.argv.slice(2);
const arg = (name, fallback = null) => {
  const i = args.indexOf(name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
};
const has = (name) => args.includes(name);
const quiet = has("--quiet");

const fixturesPath = resolve(ROOT, arg("--fixtures", "testdata/layout/fixtures.json"));
const outDir = resolve(ROOT, arg("--out", "testdata/layout"));
const only = (arg("--only", "") || "").split(",").map((s) => s.trim()).filter(Boolean);
const widthOverride = arg("--width") ? Number(arg("--width")) : null;
const scale = Math.max(1, Number(arg("--scale", "1")) || 1);
const checkOnly = has("--check");

const log = (...a) => {
  if (!quiet) console.log(...a);
};
const fail = (msg) => {
  console.error(`[error] ${msg}`);
  process.exitCode = 1;
};

/* ------------------------------------------------------------------ */
/* 浏览器发现                                                          */
/* ------------------------------------------------------------------ */

function findBrowser() {
  const explicit = arg("--browser") || process.env.PIC_TEXT_BROWSER;
  const candidates = [
    explicit,
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    `${process.env.LOCALAPPDATA || ""}\\Google\\Chrome\\Application\\chrome.exe`,
    "/usr/bin/google-chrome",
    "/usr/bin/google-chrome-stable",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  ].filter(Boolean);
  return candidates.find((p) => existsSync(p)) || null;
}

/* ------------------------------------------------------------------ */
/* 小工具                                                              */
/* ------------------------------------------------------------------ */

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function getFreePort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolvePort(port));
    });
  });
}

function getJson(port, path, timeoutMs = 1500) {
  return new Promise((resolveJson, reject) => {
    const req = http.get({ host: "127.0.0.1", port, path }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (c) => (data += c));
      res.on("end", () => {
        try {
          resolveJson(JSON.parse(data));
        } catch (e) {
          reject(e);
        }
      });
    });
    req.on("error", reject);
    req.setTimeout(timeoutMs, () => req.destroy(new Error(`timeout ${path}`)));
  });
}

function sha256(buf) {
  return createHash("sha256").update(buf).digest("hex");
}

function pngSize(buf) {
  if (buf.length < 24 || buf.toString("ascii", 1, 4) !== "PNG") return null;
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timeout: ${label}`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/* ------------------------------------------------------------------ */
/* CDP 客户端                                                          */
/* ------------------------------------------------------------------ */

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 1;
    this.pending = new Map();
    this.events = [];
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve: ok, reject: bad, timer } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        clearTimeout(timer);
        if (msg.error) bad(new Error(JSON.stringify(msg.error)));
        else ok(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
  }

  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((ok, bad) => {
      ws.addEventListener("open", ok, { once: true });
      ws.addEventListener("error", bad, { once: true });
    });
    return new Cdp(ws);
  }

  send(method, params = {}, timeoutMs = 20000) {
    const id = this.nextId++;
    const p = new Promise((ok, bad) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        bad(new Error(`CDP timeout: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve: ok, reject: bad, timer });
    });
    this.ws.send(JSON.stringify({ id, method, params }));
    return p;
  }

  async waitEvent(method, timeoutMs = 20000) {
    const started = Date.now();
    while (Date.now() - started < timeoutMs) {
      const idx = this.events.findIndex((e) => e.method === method);
      if (idx >= 0) return this.events.splice(idx, 1)[0];
      await sleep(20);
    }
    throw new Error(`timeout waiting ${method}`);
  }

  close() {
    try {
      this.ws.close();
    } catch {
      /* ignore */
    }
  }
}

/* ------------------------------------------------------------------ */
/* DOM 真值表达式                                                      */
/* ------------------------------------------------------------------ */

const GT_EXPRESSION = String.raw`(() => {
  const round = (v) => Math.round(v * 10) / 10;
  const LINE_KINDS = new Set(["text", "title", "caption"]);

  function lineRects(el) {
    const raw = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      const value = node.nodeValue || "";
      if (!value.trim()) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      for (const r of range.getClientRects()) {
        if (r.width > 2 && r.height > 2) {
          raw.push({ x: r.x, y: r.y, w: r.width, h: r.height });
        }
      }
    }
    raw.sort((a, b) => a.y - b.y || a.x - b.x);
    const lines = [];
    for (const r of raw) {
      const cy = r.y + r.h / 2;
      let hit = null;
      for (const l of lines) {
        const lcy = l.y + l.h / 2;
        if (Math.abs(cy - lcy) < Math.max(3, Math.min(l.h, r.h) * 0.45)) { hit = l; break; }
      }
      if (hit) {
        const x1 = Math.min(hit.x, r.x);
        const y1 = Math.min(hit.y, r.y);
        const x2 = Math.max(hit.x + hit.w, r.x + r.w);
        const y2 = Math.max(hit.y + hit.h, r.y + r.h);
        hit.x = x1; hit.y = y1; hit.w = x2 - x1; hit.h = y2 - y1;
      } else {
        lines.push({ ...r });
      }
    }
    return lines;
  }

  const regions = [];
  for (const el of document.querySelectorAll("[data-region]")) {
    const cs = getComputedStyle(el);
    if (cs.display === "none" || cs.visibility === "hidden") continue;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    const kind = el.dataset.kind || "text";
    const lines = LINE_KINDS.has(kind) ? lineRects(el) : [];
    let inkBox = null;
    if (lines.length) {
      const x1 = Math.min(...lines.map((l) => l.x));
      const y1 = Math.min(...lines.map((l) => l.y));
      const x2 = Math.max(...lines.map((l) => l.x + l.w));
      const y2 = Math.max(...lines.map((l) => l.y + l.h));
      inkBox = { x: round(x1), y: round(y1), w: round(x2 - x1), h: round(y2 - y1) };
    }
    regions.push({
      id: el.dataset.region,
      kind,
      order: Number(el.dataset.order || 9999),
      sensitive: el.dataset.sensitive === "1",
      box: { x: round(r.x), y: round(r.y), w: round(r.width), h: round(r.height) },
      inkBox,
      lines: lines.map((l) => ({ x: round(l.x), y: round(l.y), w: round(l.w), h: round(l.h) })),
    });
  }
  regions.sort((a, b) => a.order - b.order || a.box.y - b.box.y || a.box.x - b.box.x);
  return {
    viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
    doc: {
      w: Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0),
      h: Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0),
    },
    generated: document.documentElement.dataset.generated === "1",
    regions,
  };
})()`;

/* ------------------------------------------------------------------ */
/* 渲染                                                                */
/* ------------------------------------------------------------------ */

async function renderAll() {
  if (Number(process.versions.node.split(".")[0]) < 22) {
    throw new Error(`需要 Node.js >= 22（当前 ${process.versions.node}）：内置 WebSocket`);
  }
  const browser = findBrowser();
  if (!browser) {
    throw new Error("找不到 Edge/Chrome/Chromium；用 --browser 指定路径，或设置 PIC_TEXT_BROWSER");
  }
  const fixtures = JSON.parse(readFileSync(fixturesPath, "utf8"));
  const jobs = [];
  for (const f of fixtures) {
    if (only.length && !only.includes(f.id)) continue;
    const widths = widthOverride ? [widthOverride] : f.widths || [1080];
    for (const width of widths) jobs.push({ fixture: f, width: Number(width) });
  }
  if (!jobs.length) throw new Error("没有匹配的夹具");

  // --only / --width 是增量渲染：保留清单里没被本次覆盖的旧条目，
  // 避免只重画一张就把其它夹具从 manifest 里删掉。
  const manifestPath = join(outDir, "manifest.json");
  let previousJobs = [];
  if ((only.length || widthOverride) && existsSync(manifestPath)) {
    try {
      previousJobs = JSON.parse(readFileSync(manifestPath, "utf8")).fixtures || [];
    } catch {
      previousJobs = [];
    }
  }
  const attempted = new Set(jobs.map((j) => `${j.fixture.id}@${j.width}`));

  const port = await getFreePort();
  const profile = await mkdtemp(join(tmpdir(), "pictext-edge-"));
  const child = spawn(browser, [
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--disable-background-networking",
    "--disable-component-update",
    "--disable-sync",
    "--metrics-recording-only",
    "--mute-audio",
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    "about:blank",
  ], { stdio: "ignore" });

  const cleanup = async () => {
    try {
      child.kill();
    } catch {
      /* ignore */
    }
    await sleep(250);
    await rm(profile, { recursive: true, force: true }).catch(() => {});
  };
  process.once("SIGINT", async () => {
    await cleanup();
    process.exit(130);
  });

  let version = null;
  for (let i = 0; i < 120; i++) {
    try {
      version = await getJson(port, "/json/version");
      break;
    } catch {
      await sleep(100);
    }
  }
  if (!version) {
    await cleanup();
    throw new Error("DevTools 未启动（端口被占用或浏览器拒绝 headless）");
  }
  const browserName = version.Browser || basename(browser);
  log(`browser: ${browserName}`);

  const targets = await getJson(port, "/json/list");
  const page = targets.find((t) => t.type === "page");
  if (!page?.webSocketDebuggerUrl) {
    await cleanup();
    throw new Error("没有可用的 page target");
  }
  const cdp = await Cdp.connect(page.webSocketDebuggerUrl);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  try {
    await cdp.send("Emulation.setScrollbarsHidden", { hidden: true });
  } catch {
    /* 老版本不支持，忽略 */
  }

  await mkdir(join(outDir, "screens"), { recursive: true });
  await mkdir(join(outDir, "ground-truth"), { recursive: true });

  const manifestJobs = [];
  for (const job of jobs) {
    const { fixture, width } = job;
    const htmlPath = resolve(dirname(fixturesPath), fixture.html);
    if (!existsSync(htmlPath)) {
      fail(`缺少 HTML：${htmlPath}`);
      continue;
    }
    const label = `${fixture.id}@${width}`;
    try {
      await cdp.send("Emulation.setDeviceMetricsOverride", {
        width,
        height: 900,
        deviceScaleFactor: scale,
        mobile: false,
        screenWidth: width,
        screenHeight: 900,
      });

      const nav = await cdp.send("Page.navigate", { url: pathToFileURL(htmlPath).href });
      if (nav?.errorText) throw new Error(`navigate: ${nav.errorText}`);
      await cdp.waitEvent("Page.loadEventFired");

      const ready = await withTimeout(
        cdp.send("Runtime.evaluate", {
          expression: `(async () => {
            await document.fonts.ready;
            const imgs = Array.from(document.images || []);
            await Promise.all(imgs.map((img) => img.complete ? 0 : new Promise((r) => {
              img.addEventListener('load', r, { once: true });
              img.addEventListener('error', r, { once: true });
            })));
            await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
            return true;
          })()`,
          awaitPromise: true,
          returnByValue: true,
        }),
        15000,
        `${label} ready`,
      );
      if (!ready?.result?.value) throw new Error(`${label} 未就绪`);

      const metrics = await cdp.send("Runtime.evaluate", {
        expression: `(() => {
          const h = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
          const w = Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0);
          return { w, h, generated: document.documentElement.dataset.generated === '1' };
        })()`,
        returnByValue: true,
      });
      const height = Math.ceil(metrics.result.value.h);
      const docWidth = Math.ceil(metrics.result.value.w);
      if (!(height > 0)) throw new Error(`${label} 高度为 0`);
      if (docWidth > width + 1) throw new Error(`${label} 横向溢出：doc=${docWidth} viewport=${width}`);
      if (fixture.id === "10-long-feed" && !metrics.result.value.generated) {
        throw new Error(`${label} 长信息流脚本没有生成卡片`);
      }

      const shot = await withTimeout(
        cdp.send("Page.captureScreenshot", {
          format: "png",
          fromSurface: true,
          captureBeyondViewport: true,
          clip: { x: 0, y: 0, width, height, scale: 1 },
        }, 30000),
        45000,
        `${label} screenshot`,
      );
      const png = Buffer.from(shot.data, "base64");

      const gt = await cdp.send("Runtime.evaluate", {
        expression: GT_EXPRESSION,
        returnByValue: true,
      });
      if (!gt?.result?.value) throw new Error(`${label} 真值为空`);
      const groundTruth = gt.result.value;

      const pngName = `${fixture.id}@${width}.png`;
      const gtName = `${fixture.id}@${width}.json`;
      const pngPath = join(outDir, "screens", pngName);
      const gtPath = join(outDir, "ground-truth", gtName);
      await writeFile(pngPath, png);
      await writeFile(
        gtPath,
        JSON.stringify(
          {
            schema: 1,
            id: fixture.id,
            html: fixture.html,
            width,
            height,
            scale,
            tags: fixture.tags || [],
            orderStrict: fixture.orderStrict !== false,
            note: fixture.note || "",
            viewport: groundTruth.viewport,
            doc: { w: docWidth, h: height },
            regions: groundTruth.regions,
          },
          null,
          2,
        ) + "\n",
      );

      const size = pngSize(png);
      if (!size || size.width !== width * scale || size.height !== height * scale) {
        throw new Error(`${label} PNG 尺寸异常：${JSON.stringify(size)}`);
      }

      manifestJobs.push({
        id: fixture.id,
        html: fixture.html,
        width,
        height,
        scale,
        png: `screens/${pngName}`,
        groundTruth: `ground-truth/${gtName}`,
        pngSha256: sha256(png),
        tags: fixture.tags || [],
        orderStrict: fixture.orderStrict !== false,
        note: fixture.note || "",
        regionCount: groundTruth.regions.length,
      });
      log(`  ${label}: ${width}x${height}, ${groundTruth.regions.length} regions, ${(png.length / 1024).toFixed(0)} KB`);
    } catch (e) {
      fail(`${label}: ${e.message}`);
    }
  }

  cdp.close();
  await cleanup();

  const mergedJobs = [
    ...previousJobs.filter((p) => !attempted.has(`${p.id}@${p.width}`)),
    ...manifestJobs,
  ].sort((a, b) => a.id.localeCompare(b.id) || a.width - b.width);
  const manifest = {
    schema: 1,
    generatedAt: new Date().toISOString(),
    generator: "scripts/render-layout-fixtures.mjs",
    browser: browserName,
    node: process.versions.node,
    scale,
    fixtures: mergedJobs,
  };
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + "\n");
  log(`manifest: ${manifestPath} (rendered ${manifestJobs.length}, total ${mergedJobs.length})`);
  if (!manifestJobs.length) process.exitCode = 1;
}

/* ------------------------------------------------------------------ */
/* 校验                                                                */
/* ------------------------------------------------------------------ */

async function checkAll() {
  const manifestPath = join(outDir, "manifest.json");
  if (!existsSync(manifestPath)) throw new Error(`没有 manifest：${manifestPath}（先跑一次渲染）`);
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  let bad = 0;
  for (const f of manifest.fixtures) {
    const pngPath = join(outDir, f.png);
    const gtPath = join(outDir, f.groundTruth);
    if (!existsSync(pngPath)) {
      fail(`${f.id}@${f.width}: 缺少 ${f.png}`);
      bad++;
      continue;
    }
    if (!existsSync(gtPath)) {
      fail(`${f.id}@${f.width}: 缺少 ${f.groundTruth}`);
      bad++;
      continue;
    }
    const png = await readFile(pngPath);
    const size = pngSize(png);
    const expectW = f.width * (f.scale || 1);
    const expectH = f.height * (f.scale || 1);
    if (!size || size.width !== expectW || size.height !== expectH) {
      fail(`${f.id}@${f.width}: PNG ${JSON.stringify(size)} != ${expectW}x${expectH}`);
      bad++;
    }
    if (sha256(png) !== f.pngSha256) {
      fail(`${f.id}@${f.width}: PNG 哈希与 manifest 不一致（HTML 改过？重新渲染）`);
      bad++;
    }
    const gt = JSON.parse(await readFile(gtPath, "utf8"));
    const ids = new Set();
    for (const r of gt.regions || []) {
      if (!r.id || ids.has(r.id)) {
        fail(`${f.id}@${f.width}: 区域 id 重复/为空 ${r.id}`);
        bad++;
      }
      ids.add(r.id);
      if (r.box.x < -1 || r.box.y < -1 || r.box.x + r.box.w > f.width + 1 || r.box.y + r.box.h > f.height + 1) {
        fail(`${f.id}@${f.width}: 区域越界 ${r.id}`);
        bad++;
      }
      for (const l of r.lines || []) {
        if (l.x < -1 || l.y < -1 || l.x + l.w > f.width + 1 || l.y + l.h > f.height + 1) {
          fail(`${f.id}@${f.width}: 文本行越界 ${r.id}`);
          bad++;
        }
      }
    }
    if (!(gt.regions || []).length) {
      fail(`${f.id}@${f.width}: 没有区域`);
      bad++;
    }
  }
  const total = manifest.fixtures.length;
  log(`checked ${total} fixtures, ${bad} problems`);
  if (bad) process.exitCode = 1;
}

/* ------------------------------------------------------------------ */

try {
  if (checkOnly) await checkAll();
  else await renderAll();
} catch (e) {
  fail(e.stack || e.message);
  process.exitCode = 1;
}
