import fs from "node:fs";
import vm from "node:vm";
import assert from "node:assert/strict";

const html = fs.readFileSync("app/src/main/assets/netpulse/index.html", "utf8");
const activity = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/MainActivity.kt", "utf8");
const gradle = fs.readFileSync("app/build.gradle.kts", "utf8");
const traffic = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/TrafficRateCalculator.kt", "utf8");
const secureStore = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/SecureProfileStore.kt", "utf8");
const application = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/NetPulseApplication.kt", "utf8");
const vpnService = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/SingBoxVpnService.kt", "utf8");
const gamingConfigurator = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/GamingProfileConfigurator.kt", "utf8");
const scripts = html.split("<script>").slice(1).map(part => part.split("</script>")[0]);
assert.equal(scripts.length, 1, "Expected one bundled application script");
const js = scripts[0];

// PASS 1 — All screen containers are present once, in the approved order.
const expectedViews = ["overview", "analytics", "devices", "servers", "alerts", "speed", "logs", "settings"];
const viewIds = [...html.matchAll(/<section class="view(?: active)?" id="view-([^"]+)"/g)].map(m => m[1]);
assert.deepEqual(viewIds, expectedViews, "Screen order or screen count changed");
for (const id of expectedViews) {
  assert.equal((html.match(new RegExp('id="view-' + id + '"', "g")) || []).length, 1, `Duplicate or missing view: ${id}`);
}
console.log("PASS 1/5 — all eight screens are present exactly once");

// PASS 2 — Catch the exact regression that broke navigation: querySelector returns one node.
const navIds = [...html.matchAll(/<button[^>]*data-view="([^"]+)"/g)].map(m => m[1]);
assert.deepEqual(navIds, expectedViews, "Navigation buttons do not map one-to-one to screens");
assert.match(js, /function go\(key\)\{[\s\S]*?Array\.from\(document\.querySelectorAll\('\.view'\)\)\.forEach/);
assert.match(js, /Array\.from\(document\.querySelectorAll\('\.nav button\[data-view\]'\)\)\.forEach/);
assert.doesNotMatch(js, /\$\('[^']+'\)\.forEach/);
assert.match(js, /primaryNav\.addEventListener\('click'/);
assert.match(js, /event\.target\.closest\('button\[data-view\]'\)/);
assert.match(js, /nav\.scrollTo\(\{left/);
console.log("PASS 2/5 — multi-element routing and delegated navigation are correctly wired");

// PASS 3 — JavaScript parses, DOM references exist, and bridge actions match Kotlin.
new vm.Script(js, { filename: "netpulse/index.html" });
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
const referencedIds = [...new Set([...js.matchAll(/\$\('#([\w-]+)'\)/g)].map(m => m[1]))];
for (const id of referencedIds) assert.ok(ids.has(id), `HTML selector points to missing element: ${id}`);
const actions = ["importWireGuard", "toggleWireGuard", "importSingBox", "toggleSingBox", "applyRefreshRate", "restoreRefreshRate", "checkRoot", "refresh", "testLatency", "testUdpRoutes", "toggleGamingMode", "saveGamingApps", "selectServer", "setLiveRefresh"];
for (const action of actions) {
  assert.ok(html.includes(`nativeAction('${action}'`) || js.includes(`nativeAction('${action}'`), `UI action missing: ${action}`);
  assert.ok(activity.includes(`"${action}" ->`), `Native bridge handler missing: ${action}`);
}
assert.match(activity, /@JavascriptInterface\s+fun getState\(\): String/);
assert.match(activity, /@JavascriptInterface\s+fun perform\(action: String, value: String\?\)/);
assert.match(activity, /@JavascriptInterface\s+fun getGamingAppCatalog\(\): String/);
assert.match(activity, /@JavascriptInterface\s+fun getServerCatalog\(\): String/);
assert.match(activity, /SingBoxServerCatalog\.select/);
assert.match(activity, /private fun setLiveRefresh\(/);
assert.match(activity, /liveRefreshEnabled\) refreshHandler\.postDelayed\(this, 1000L\)/);
assert.match(js, /function loadServers\(/);
assert.ok(js.includes("drawLiveChart('analyticsDown',historyDown)"));
assert.ok(js.includes("drawLiveChart('analyticsUp',historyUp)"));
assert.match(html, /LIVE · داده واقعی دستگاه/);
assert.match(html, /data-pref="motion"/);
assert.match(html, /data-pref="notifications"/);
assert.match(js, /function applyUiPreferences\(/);
assert.match(js, /readPref\('np-pref-notifications'\)/);
for (const id of ["serverCatalog", "serverCatalogCount", "serverCatalogHint", "analyticsDown", "analyticsUp"]) {
  assert.ok(ids.has(id), `Server/analytics UI element missing: ${id}`);
}
for (const id of ["gameModeButton", "gamingAppList", "selectedGameCount", "probeMin", "probeMedian", "probeMax", "probeVariation", "probeFailures"]) {
  assert.ok(ids.has(id), `Gaming/diagnostic UI element missing: ${id}`);
}
assert.match(activity, /GamingProfileConfigurator\.withGameOnlyRouting/);
assert.match(activity, /TrafficStats\.getUidRxBytes/);
assert.match(activity, /TCP_PROBE_ATTEMPTS = 6/);
assert.match(js, /function loadGamingApps\(/);
assert.match(js, /function onNetPulseLatencyStats\(/);
assert.ok(activity.includes("UdpStunProbe.measure"));
assert.ok(activity.includes("physicalNetwork.getAllByName"));
assert.ok(activity.includes("val vpnStats = UdpStunProbe.measure("));
assert.ok(js.includes("function runUdpComparison("));
assert.ok(js.includes("function onNetPulseUdpComparison("));
for (const id of ["startUdpCompare", "udpComparisonStatus", "udpDirectMedian", "udpVpnMedian", "udpDelta", "udpDirectLoss", "udpVpnLoss"]) {
  assert.ok(ids.has(id), `UDP comparison UI element missing: ${id}`);
}
assert.match(activity, /TrafficRateCalculator\(\)/);
assert.match(activity, /trafficRateCalculator\.sample\(rx, tx, now\)/);
assert.doesNotMatch(activity, /if \(!::networkSummary\.isInitialized\) return/);
assert.match(traffic, /fun sample\(rxBytes: Long, txBytes: Long, atElapsedMs: Long\)/);
assert.match(secureStore, /AndroidKeyStore/);
assert.match(secureStore, /AES\/GCM\/NoPadding/);
assert.match(secureStore, /context\.noBackupFilesDir/);
assert.match(secureStore, /file\.finishWrite\(output\)/);
assert.match(application, /fun persistProfiles\(\): Boolean/);
assert.match(application, /private fun restoreProfiles\(\)/);
assert.match(application, /Libbox\.checkConfig\(raw\)/);
assert.match(gamingConfigurator, /auto_route/);
assert.match(vpnService, /Android 12 and earlier cannot enforce safely/);
assert.match(vpnService, /cannot combine include_package and exclude_package/);
assert.match(vpnService, /EXTRA_ERROR/);
console.log("PASS 3/5 — JavaScript bridge, live telemetry, encrypted profile storage and VPN safety gates are wired");

// PASS 4 — Android 15 system bars and cutout insets are applied to the WebView viewport.
assert.match(gradle, /targetSdk\s*=\s*35/);
assert.match(activity, /WindowCompat\.setDecorFitsSystemWindows\(window, false\)/);
assert.match(activity, /WindowInsetsCompat\.Type\.systemBars\(\) or WindowInsetsCompat\.Type\.displayCutout\(\)/);
assert.match(activity, /view\.setPadding\(bars\.left, bars\.top, bars\.right, bars\.bottom\)/);
assert.match(activity, /ViewCompat\.requestApplyInsets\(webView\)/);
assert.match(activity, /isAppearanceLightStatusBars = false/);
assert.match(activity, /isAppearanceLightNavigationBars = false/);
console.log("PASS 4/5 — status bar, navigation bar and display cutout insets are handled");

// PASS 5 — Mobile navigation fits a horizontally scrollable bottom bar; WebView storage is fault-tolerant.
assert.match(html, /viewport-fit=cover/);
assert.match(html, /\.nav\{display:flex;overflow-x:auto/);
assert.match(js, /function readPref\(key\)\{try\{return window\.localStorage\.getItem\(key\)\}catch/);
assert.match(js, /function writePref\(key,value\)\{[\s\S]*?catch\(error\)\{\}\}/);
assert.match(activity, /settings\.javaScriptEnabled = true/);
assert.match(activity, /settings\.allowContentAccess = false/);
assert.match(activity, /settings\.allowUniversalAccessFromFileURLs = false/);
assert.match(activity, /settings\.blockNetworkLoads = true/);
assert.match(activity, /file:\/\/\/android_asset\/netpulse\/index\.html/);
console.log("PASS 5/5 — mobile navigation, local storage fallback and WebView isolation verified");
console.log("NetPulse UI regression checks: 5/5 passed");
