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
const ikev2Config = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/Ikev2ProfileConfig.kt", "utf8");
const openVpnImporter = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/OpenVpnProfileImporter.kt", "utf8");
const protocolCatalog = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/protocol/ProtocolCatalog.kt", "utf8");
const autoServer = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/AutoServerConfigurator.kt", "utf8");
const wireGuardGame = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/WireGuardGameRoutingConfigurator.kt", "utf8");
const tcpEndpoint = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/TcpEndpointProbe.kt", "utf8");
const udpEchoProbe = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/UdpEchoProbe.kt", "utf8");
const udpEchoCodec = fs.readFileSync("app/src/main/java/com/rmgb/netpulse/UdpEchoPacketCodec.kt", "utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
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

// PASS 3 — JavaScript, native bridge, game profiles, probes and VPN safety controls are wired.
new vm.Script(js, { filename: "netpulse/index.html" });
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
const referencedIds = [...new Set([...js.matchAll(/\$\('#([\w-]+)'\)/g)].map(m => m[1]))];
for (const id of referencedIds) {
  assert.ok(ids.has(id), "HTML selector points to missing element: " + id);
}

const actions = [
  "importWireGuard", "toggleWireGuard", "importSingBox", "toggleSingBox",
  "applyRefreshRate", "restoreRefreshRate", "checkRoot", "refresh",
  "setLiveRefresh", "testLatency", "testUdpRoutes", "testGameServer", "testGameUdpEcho",
  "toggleGamingMode", "saveGameRoutes", "selectServer", "autoSelectServer",
  "toggleAutoGameDetection", "clearProfiles", "configureIkev2", "toggleIkev2"
];
for (const action of actions) {
  assert.ok(html.includes("nativeAction('" + action + "'") || js.includes("nativeAction('" + action + "'"), "UI action missing: " + action);
  assert.ok(activity.includes('"' + action + '" ->'), "Native bridge handler missing: " + action);
}
for (const internalAction of ["importSingBoxText", "saveGamingApps", "requestGameDetection"]) {
  assert.ok(activity.includes('"' + internalAction + '" ->'), "Internal bridge handler missing: " + internalAction);
}

assert.match(activity, /@JavascriptInterface\s+fun getState\(\): String/);
assert.match(activity, /@JavascriptInterface\s+fun getServerCatalog\(\): String/);
assert.match(activity, /@JavascriptInterface\s+fun getGamingAppCatalog\(\): String/);

// Game-only routing applies to both sing-box TUN and WireGuard's native per-app allowlist.
assert.match(gamingConfigurator, /package_name/);
assert.match(gamingConfigurator, /perGameOutbounds/);
assert.match(gamingConfigurator, /"action", "route"/);
assert.match(gamingConfigurator, /"outbound", outboundTag/);
assert.match(gamingConfigurator, /auto_route/);
assert.match(activity, /GamingProfileConfigurator\.withGameOnlyRouting/);
assert.match(activity, /WireGuardGameRoutingConfigurator\.withGameOnlyRouting/);
assert.match(activity, /loadedSingBoxProfile\.isNullOrBlank\(\) && app\.loadedWireGuardConfig == null/);
assert.match(activity, /WireGuard connected · Game-only/);
assert.match(wireGuardGame, /includeApplications\(selectedPackages\.sorted\(\)\)/);
assert.match(wireGuardGame, /addPeers\(config\.getPeers\(\)\)/);

// Automatic relay selection is a real sing-box URLTest group, not a static ping label.
assert.match(autoServer, /"type", "urltest"/);
assert.match(autoServer, /"interval", "3m"/);
assert.match(autoServer, /"tolerance", 50/);
assert.match(autoServer, /route\.put\("final", AUTO_TAG\)/);
assert.match(activity, /private fun autoSelectFastestServer\(\)/);
assert.match(activity, /AutoServerConfigurator\.useFastestAvailable/);

// Foreground-game detection is explicitly opt-in via Usage Access; no app content is read.
assert.match(manifest, /android\.permission\.PACKAGE_USAGE_STATS/);
assert.match(activity, /UsageStatsManager/);
assert.match(activity, /Settings\.ACTION_USAGE_ACCESS_SETTINGS/);
assert.match(activity, /private fun detectForegroundGame\(\)/);
assert.match(activity, /private val gameDetectionTick = object : Runnable/);
assert.match(activity, /optional Usage Access poller must keep running while a game is/);
const onStopBlock = activity.match(/override fun onStop\(\)[\\s\\S]*?override fun onResume\(\)/)?.[0] || "";
assert.ok(onStopBlock.length > 0, "Activity stop/resume lifecycle block must exist");
assert.doesNotMatch(onStopBlock, /removeCallbacks\(gameDetectionTick\)/);
assert.match(html, /const GAME_SESSION_HISTORY_KEY=/);
assert.match(html, /function trackGameSession\(state\)/);
assert.match(html, /function closeGameSession\(reason,endedAtOverride\)/);
assert.match(html, /closeGameSession\('sampling-gap',activeGameSession\.lastSampleAt\)/);
assert.match(html, /state\.activeGameDownloadMbps!==null/);
assert.match(html, /setTimeout\(\(\)=>URL\.revokeObjectURL\(url\),1500\)/);
assert.match(html, /function exportGameSessions\(\)/);
assert.match(html, /nativeAction\('exportGameSessions',payload\)/);
assert.doesNotMatch(html, /createObjectURL\(blob\)/);
assert.match(activity, /private fun exportGameSessionHistory\(rawJson: String\?\)/);
assert.match(activity, /Intent\.ACTION_CREATE_DOCUMENT/);
assert.match(activity, /REQUEST_GAME_SESSION_EXPORT/);
assert.match(activity, /contentResolver\.openOutputStream\(uri, "wt"\)/);
assert.match(html, /function clearGameSessions\(\)/);
assert.match(html, /NetPulse foreground monitoring only/);
assert.match(html, /id="gameSessionHistory"/);
assert.match(html, /id="gameSessionCount"/);
assert.match(html, /state\.activeGameDownloadMbps!==null/);
assert.match(activity, /private fun updateActiveGameTraffic\(\)/);
assert.match(activity, /private fun isUsageAccessGranted\(\)/);
assert.match(activity, /activeGameDownloadBps/);
for (const id of ["gameDetectionButton", "gameDetectionState", "activeGameState", "activeGameTraffic", "gameModeButton", "gamingAppList"]) {
  assert.ok(ids.has(id), "Game detection/routing UI element missing: " + id);
}
assert.match(js, /route\.dataset\.routePackage=app\.packageName/);
assert.match(js, /nativeAction\('saveGameRoutes'/);
assert.match(js, /function renderGameDetectionState\(/);

// Custom TCP endpoint test binds sockets to two explicit Android Network handles.
assert.match(tcpEndpoint, /network\.bindSocket\(socket\)/);
assert.match(tcpEndpoint, /InetSocketAddress\(address, port\)/);
assert.match(activity, /TcpEndpointProbe\.measure/);
assert.match(activity, /private fun runGameServerComparison\(rawTarget: String\?\)/);
assert.match(js, /function onNetPulseGameServerComparison\(/);
assert.match(js, /window\.netPulseGameServerComparison=onNetPulseGameServerComparison/);
for (const id of ["gameServerHost", "gameServerPort", "gameServerProbeButton", "gameDirectMedian", "gameVpnMedian", "gameDirectJitter", "gameVpnJitter"]) {
  assert.ok(ids.has(id), "Custom game-server diagnostics UI element missing: " + id);
}

// Existing measurement labels stay honest: connect failures and STUN timeouts are not game packet loss.
assert.match(html, /packet loss بازی تلقی نکن/);
assert.match(html, /TCP connect/);
assert.match(activity, /UdpStunProbe\.measure/);
assert.match(activity, /TcpProbeSummary\.from/);
assert.match(js, /function onNetPulseLatencyStats\(/);
assert.match(js, /function onNetPulseUdpComparison\(/);
assert.match(js, /direct\.jitterMs/);
assert.match(js, /vpn\.jitterMs/);

// Per-game UDP Echo tests use an explicit, nonce-protected echo contract and both Android Networks.
assert.match(activity, /private fun runGameUdpEchoComparison\(rawValue: String\?\)/);
assert.match(activity, /Alternate direct\/VPN probes/);
assert.match(activity, /val direct = TcpProbeSummary\.from\(directSamples\)/);
assert.match(activity, /val vpn = UdpStunSummary\.from\(vpnSamples\)/);
assert.match(html, /پروب را خود NetPulse می‌فرستد/);
assert.match(activity, /UdpEchoProbe\.measure/);
assert.match(activity, /gamePackage.*selectedGamingPackages/);
assert.match(udpEchoProbe, /network\.bindSocket\(socket\)/);
assert.match(udpEchoProbe, /UdpEchoPacketCodec\.isMatchingEcho/);
assert.match(udpEchoCodec, /SecureRandom/);
assert.match(udpEchoCodec, /MAX_USER_PAYLOAD_BYTES = 48/);
assert.match(udpEchoCodec, /responseLength < 0/);
assert.match(js, /function loadUdpGameTargets\(/);
assert.match(js, /function restoreGameUdpTarget\(\)[\s\S]*?\$\('#gameUdpHost'\)\.value=''/);
assert.match(js, /function runUdpEchoComparison\(/);
assert.match(js, /function onNetPulseGameUdpEchoComparison\(/);
for (const id of ["gameUdpPackage", "gameUdpHost", "gameUdpPort", "gameUdpPayload", "gameUdpProbeButton", "gameUdpDirectMedian", "gameUdpVpnMedian"]) {
  assert.ok(ids.has(id), "Game UDP Echo control missing: " + id);
}

// Existing reliability, secure-storage and VPN platform gates remain covered.
assert.match(activity, /TrafficRateCalculator\(\)/);
assert.match(activity, /trafficRateCalculator\.sample\(rx, tx, now\)/);
assert.match(traffic, /fun sample\(rxBytes: Long, txBytes: Long, atElapsedMs: Long\)/);
assert.match(secureStore, /AndroidKeyStore/);
assert.ok(secureStore.includes("AES/GCM/NoPadding"));
assert.match(secureStore, /context\.noBackupFilesDir/);
assert.match(application, /fun persistProfiles\(\): Boolean/);
assert.match(application, /private fun restoreProfiles\(\)/);
assert.match(application, /fun clearStoredProfiles\(\): Boolean/);
assert.match(activity, /private fun confirmClearSavedProfiles\(\)/);
assert.match(activity, /stopProvisionedVpnProfile\(\)/);
assert.match(vpnService, /EXTRA_ERROR/);
assert.match(vpnService, /pendingSingBoxServiceProfile/);
assert.doesNotMatch(activity, /putExtra\(SingBoxVpnService\.EXTRA_PROFILE/);
assert.match(activity, /REQUEST_IKEV2_PERMISSION/);
assert.match(activity, /provisionVpnProfile\(platformProfile\)/);
assert.match(activity, /startProvisionedVpnProfileSession\(\)/);
assert.match(ikev2Config, /Ikev2VpnProfile\.Builder/);
assert.match(openVpnImporter, /openvpn-client/);
assert.match(protocolCatalog, /openvpn_udp.*EngineState\.AVAILABLE/);
assert.match(protocolCatalog, /ikev2_ipsec.*EngineState\.AVAILABLE/);

assert.match(html, /v0\.6\.2/);
console.log("PASS 3/5 — game routing, foreground detection, TCP/UDP probes, URLTest and VPN safety are wired");

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
assert.match(html, /v0\.6\.2/);
console.log("PASS 5/5 — mobile navigation, local storage fallback and WebView isolation verified");
assert.ok(html.includes("v0.6.2"), "Dashboard release badge must match version 0.6.2");
console.log("NetPulse UI regression checks: 5/5 passed");
