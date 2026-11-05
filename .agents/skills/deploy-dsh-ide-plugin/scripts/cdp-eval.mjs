// 通过 JCEF 的 CDP 端口在 DSH 页面里执行表达式。
//
// JCEF 的调试端口写在 %LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\jcef_cache\DevToolsActivePort，
// curl http://127.0.0.1:<port>/json/list 能列出页面。这里自动挑出 URL 带 token 的 DSH 页面。
//
// 用法：
//   node cdp-eval.mjs "document.title"
//   node cdp-eval.mjs "localStorage.getItem('dsh.sessions.current')"
//   node cdp-eval.mjs "new Promise(r => setTimeout(() => r('done'), 1000))"
//
// 输出：CDP 的 result 对象（returnByValue），失败时退出码非 0。

import fs from "node:fs";
import path from "node:path";

function readDevToolsPort() {
  const localAppData = process.env.LOCALAPPDATA;
  if (!localAppData) throw new Error("LOCALAPPDATA is not set");
  const jetbrains = path.join(localAppData, "JetBrains");
  const candidates = fs.existsSync(jetbrains)
    ? fs.readdirSync(jetbrains).filter((name) => name.startsWith("IntelliJIdea"))
    : [];
  for (const name of candidates.sort().reverse()) {
    const file = path.join(jetbrains, name, "jcef_cache", "DevToolsActivePort");
    if (fs.existsSync(file)) {
      return fs.readFileSync(file, "utf8").split(/\r?\n/)[0].trim();
    }
  }
  throw new Error("DevToolsActivePort not found; the ZCode tool window may not be open yet");
}

const expression = process.argv[2];
if (!expression) {
  console.error('usage: node cdp-eval.mjs "<javascript expression>"');
  process.exit(2);
}

const port = readDevToolsPort();
const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
// 首次打开带 token=，303 换 cookie 后 URL 只剩 #ideWorkspace；IDEA 里可能同时有 ZCode 等其它 JCEF 页面，
// 它们也带 token/ideWorkspace。优先按 DSH 端口（默认 45231，可用 DSH_PORT 覆盖）选页面。
const localPage = (item) => item.type === "page" && /^http:\/\/127\.0\.0\.1:\d+\//.test(item.url);
const dshPort = process.env.DSH_PORT ?? "45231";
const target = targets.find((item) => localPage(item) && item.url.includes(`:${dshPort}/`))
  ?? targets.find((item) => localPage(item) && item.url.includes("ideWorkspace="))
  ?? targets.find((item) => localPage(item) && item.url.includes("token="))
  ?? targets.find(localPage);
if (!target) {
  console.error("no DSH page target; open the DSH tool window first");
  console.error(targets.map((item) => item.url).join("\n"));
  process.exit(2);
}
console.error("target:", target.url.replace(/token=[^&]+/, "token=***"));

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.addEventListener("open", resolve, { once: true });
  ws.addEventListener("error", reject, { once: true });
});

const response = await new Promise((resolve, reject) => {
  const id = 1;
  ws.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id === id) resolve(message);
  });
  ws.send(
    JSON.stringify({
      id,
      method: "Runtime.evaluate",
      params: { expression, awaitPromise: true, returnByValue: true, userGesture: true },
    }),
  );
  setTimeout(() => reject(new Error("CDP evaluate timed out")), 20000);
});

ws.close();
console.log(JSON.stringify(response.result ?? response, null, 2));
if (response.result?.exceptionDetails) process.exit(1);
