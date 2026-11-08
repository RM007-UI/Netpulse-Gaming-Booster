import fs from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

const html = fs.readFileSync("app/src/main/assets/netpulse/index.html", "utf8");
const activity = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/MainActivity.kt", "utf8");
const gradle = fs.readFileSync("app/build.gradle.kts", "utf8");
const scripts = [...html.matchAll(/<script>([\\s\\S]*?)<\\/script>/g)].map(m => m[1]);
assert.equal(scripts.length, 1, "Expected one bundled application script");
const js = scripts[0];

// PASS 1 — The eight navigation destinations exist exactly once.
const expectedViews = ["overview", "analytics", "devices", "servers", "alerts", "speed", "logs", "settings"];
const viewIds = [...html.matchAll(/<section class="view(?: active)?" id="view-([^"]+)"/g)].map(m => m[1]);
assert.deepEqual(viewIds, expectedViews, "Screen order or screen count changed");
for (const id of expectedViews) {
  assert.equal((html.match(new RegExp('id="view-' + id + '"', "g")) || []).length, 1, `Duplicate or missing view: ${id}`);
}
console.log("PASS 1/5 — all eight screens are present exactly once");

// PASS 2 — Navigation buttons are actually wired to the screen router.
const navIds = [...html.matchAll(/<button[^>]*data-view="([^"]+)"/g)].map(m => m[1]);
assert.deepEqual(navIds, expectedViews, "Navigation buttons do not map one-to-one to screens");
assert.match(js, /querySelectorAll\('\.nav button\[data-view\]'\)\.forEach\(button=>\{button\.addEventListener\('click'/);
assert.match(js, /function go\(key\)\{const target=\$\('#view-'\+key\);if\(!target\)return/);
assert.match(js, /b\.setAttribute\('aria-current','page'\)/);
console.log("PASS 2/5 — all navigation taps route and update selected state");

// PASS 3 — JavaScript parses and every UI-to-native action has a Kotlin handler.
new vm.Script(js, { filename: "netpulse/index.html" });
const actions = ["importWireGuard", "toggleWireGuard", "importSingBox", "toggleSingBox", "applyRefreshRate", "restoreRefreshRate", "checkRoot", "refresh", "testLatency"];
for (const action of actions) {
  assert.ok(js.includes(`nativeAction('${action}'`) || js.includes(`"${action}"`), `UI action missing: ${action}`);
  assert.ok(activity.includes(`"${action}" ->`), `Native bridge handler missing: ${action}`);
}
assert.match(activity, /@JavascriptInterface\s+fun getState\(\): String/);
assert.match(activity, /@JavascriptInterface\s+fun perform\(action: String, value: String\?\)/);
console.log("PASS 3/5 — script syntax and native action bridge are valid");

// PASS 4 — Android 15 edge-to-edge status/navigation bars receive safe insets.
assert.match(gradle, /targetSdk\s*=\s*35/);
assert.match(activity, /WindowCompat\.setDecorFitsSystemWindows\(window, false\)/);
assert.match(activity, /WindowInsetsCompat\.Type\.systemBars\(\) or WindowInsetsCompat\.Type\.displayCutout\(\)/);
assert.match(activity, /view\.setPadding\(bars\.left, bars\.top, bars\.right, bars\.bottom\)/);
assert.match(activity, /isAppearanceLightStatusBars = false/);
assert.match(activity, /isAppearanceLightNavigationBars = false/);
console.log("PASS 4/5 — status bar, gesture/navigation bar and cutout insets are handled");

// PASS 5 — Mobile navigation is reachable and WebView remains locally sandboxed.
assert.match(html, /viewport-fit=cover/);
assert.match(html, /\.nav\{display:flex;overflow-x:auto/);
assert.match(activity, /settings\.javaScriptEnabled = true/);
assert.match(activity, /settings\.allowContentAccess = false/);
assert.match(activity, /settings\.allowUniversalAccessFromFileURLs = false/);
assert.match(activity, /settings\.blockNetworkLoads = true/);
assert.match(activity, /file:\/\/\/android_asset\/netpulse\/index\.html/);
console.log("PASS 5/5 — mobile navigation, bundled assets and WebView isolation verified");
console.log("NetPulse UI regression checks: 5/5 passed");
