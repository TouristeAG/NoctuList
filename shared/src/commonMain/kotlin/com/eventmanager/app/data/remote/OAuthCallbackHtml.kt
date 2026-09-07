package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.InstitutionLogoStore
import com.eventmanager.app.ui.platform.AppAppearanceState
import java.util.Locale

/**
 * Localhost page after the Google OAuth redirect. Visual language matches the public guest
 * form: Nunito, dark card, animated topographic WebGL field from [webform/topo.js].
 */
object OAuthCallbackHtml {
    fun page(
        success: Boolean,
        logoDataUri: String = syncedInstitutionLogoDataUri(),
        languageCode: String = resolvedAppLanguage(),
    ): String {
        val copy = copyFor(languageCode, success)
        val brand = brandMarkup(logoDataUri)
        return HEAD.replaceFirst("<html lang=\"fr\">", "<html lang=\"${copy.htmlLang}\">") +
            (if (success) successMain(brand, copy) else failureMain(brand, copy)) +
            TOPO_SCRIPT +
            "</body></html>"
    }

    /** Language currently selected in the app (not the browser). */
    fun resolvedAppLanguage(): String {
        val raw = AppAppearanceState.localeCode?.trim().orEmpty()
            .ifBlank { Locale.getDefault().toLanguageTag() }
        return normalizeLanguage(raw)
    }

    internal fun normalizeLanguage(raw: String): String {
        val lower = raw.trim().replace('_', '-').lowercase()
        return when {
            lower.startsWith("zh-tw") || lower.startsWith("zh-hant") -> "zh-TW"
            lower.startsWith("zh") -> "zh-CN"
            lower.startsWith("fr") -> "fr"
            lower.startsWith("es") -> "es"
            lower.startsWith("hi") -> "hi"
            lower.startsWith("la") -> "la"
            else -> "en"
        }
    }

    private data class Copy(val htmlLang: String, val title: String, val body: String)

    private fun copyFor(languageCode: String, success: Boolean): Copy {
        val lang = normalizeLanguage(languageCode)
        return if (success) {
            when (lang) {
                "fr" -> Copy("fr", "Connexion réussie", "Vous pouvez revenir à NoctuList. Cet onglet peut être fermé.")
                "es" -> Copy("es", "Sesión iniciada", "Puedes volver a NoctuList. Esta pestaña se puede cerrar.")
                "zh-TW" -> Copy("zh-Hant", "已登入", "可以回到 NoctuList。這個分頁可以關閉。")
                "zh-CN" -> Copy("zh-Hans", "已登录", "可以回到 NoctuList。这个标签页可以关闭。")
                "hi" -> Copy("hi", "साइन इन हो गया", "आप NoctuList पर वापस जा सकते हैं। यह टैब बंद किया जा सकता है।")
                "la" -> Copy("la", "Accessus factus est", "Ad NoctuList redire potes. Haec pagina claudi potest.")
                else -> Copy("en", "Signed in", "You can go back to NoctuList. This tab can be closed.")
            }
        } else {
            when (lang) {
                "fr" -> Copy("fr", "Connexion interrompue", "Google n'a pas terminé la connexion. Fermez cet onglet et réessayez depuis NoctuList.")
                "es" -> Copy("es", "Inicio de sesión interrumpido", "Google no ha terminado el acceso. Cierra esta pestaña e inténtalo de nuevo desde NoctuList.")
                "zh-TW" -> Copy("zh-Hant", "登入未完成", "Google 沒有完成登入。請關閉這個分頁，然後從 NoctuList 再試一次。")
                "zh-CN" -> Copy("zh-Hans", "登录未完成", "Google 没有完成登录。请关闭这个标签页，然后从 NoctuList 再试一次。")
                "hi" -> Copy("hi", "साइन इन अधूरा", "Google ने साइन इन पूरा नहीं किया। यह टैब बंद करें और NoctuList से फिर कोशिश करें।")
                "la" -> Copy("la", "Accessus interruptus", "Google accessionem non complevit. Hanc paginam claude et ex NoctuList denuo conare.")
                else -> Copy("en", "Sign-in didn't finish", "Google did not complete sign-in. Close this tab and try again from NoctuList.")
            }
        }
    }

    /** Synced association logo as a data URI, or empty when none is published on this device. */
    fun syncedInstitutionLogoDataUri(): String {
        val bytes = InstitutionLogoStore.readFromDisk()?.takeIf { it.isNotEmpty() } ?: return ""
        return "data:image/png;base64,${InstitutionLogoStore.encode(bytes)}"
    }

    internal fun sanitizeLogoDataUri(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            trimmed
        } else {
            "data:image/png;base64,$trimmed"
        }
        val allowed = Regex(
            """^data:image/(png|jpeg|jpg|webp);base64,[A-Za-z0-9+/]+=*$""",
            RegexOption.IGNORE_CASE,
        )
        return uri.takeIf { allowed.matches(it) && it.length <= InstitutionLogoStore.MAX_BASE64_LENGTH + 32 }
    }

    private fun brandMarkup(logoDataUri: String): String {
        val safe = sanitizeLogoDataUri(logoDataUri)
        return if (safe != null) {
            """<header class="logos"><div class="logo"><img alt="" src="$safe"/></div></header>"""
        } else {
            """<p class="brand">NoctuList</p>"""
        }
    }

    private fun successMain(brand: String, copy: Copy): String = """
<main class="page">
  <section class="card">
    $brand
    <div class="mark" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none">
        <path d="M5 12.5l4.2 4.2L19 7.2" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </div>
    <h1>${copy.title}</h1>
    <p class="muted">${copy.body}</p>
  </section>
</main>
"""

    private fun failureMain(brand: String, copy: Copy): String = """
<main class="page">
  <section class="card">
    $brand
    <h1>${copy.title}</h1>
    <p class="muted">${copy.body}</p>
  </section>
</main>
"""

    private const val HEAD = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
<title>NoctuList</title>
<link rel="preconnect" href="https://fonts.googleapis.com"/>
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin/>
<link href="https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;700;800&display=swap" rel="stylesheet"/>
<style>
:root {
  --bg: #121212;
  --surface: #1c1b1f;
  --card: rgba(30, 30, 30, 0.88);
  --primary: #b39ddb;
  --on: #e1e1e1;
  --muted: #b3b3b3;
  --line: rgba(179, 157, 219, 0.28);
  --radius: 16px;
}
* { box-sizing: border-box; }
html, body { margin: 0; min-height: 100%; }
body {
  min-height: 100dvh;
  font-family: Nunito, system-ui, sans-serif;
  background:
    radial-gradient(1200px 800px at 20% -10%, rgba(179, 157, 219, 0.16), transparent 60%),
    radial-gradient(1000px 700px at 100% 10%, rgba(179, 157, 219, 0.10), transparent 55%),
    var(--bg);
  background-attachment: fixed;
  color: var(--on);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(16px, env(safe-area-inset-top)) max(16px, env(safe-area-inset-right))
    max(24px, env(safe-area-inset-bottom)) max(16px, env(safe-area-inset-left));
}
#topo {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
  pointer-events: none;
  opacity: 0.6;
}
.page {
  position: relative;
  z-index: 1;
  width: min(100%, 560px);
}
.card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 28px 24px;
  backdrop-filter: blur(10px);
  text-align: center;
}
.brand {
  margin: 0 0 16px;
  color: var(--muted);
  font-weight: 700;
  font-size: 0.95rem;
}
.mark {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  border-radius: 50%;
  border: 1px solid var(--line);
  color: var(--primary);
  display: grid;
  place-items: center;
}
.mark svg { width: 28px; height: 28px; }
h1 {
  margin: 0 0 8px;
  font-size: 1.7rem;
  font-weight: 800;
}
.muted {
  margin: 0;
  color: var(--muted);
  line-height: 1.45;
  font-weight: 600;
}
.logos {
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 0 16px;
}
.logo {
  width: 88px;
  height: 88px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: transparent;
  border-radius: 14px;
}
.logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: transparent;
  display: block;
  border-radius: 14px;
}
@media (min-width: 720px) {
  .card { padding: 36px 32px; }
}
</style>
</head>
<body>
<canvas id="topo" aria-hidden="true"></canvas>
"""

    /** Same WebGL topographic field as `webform/topo.js`. */
    private const val TOPO_SCRIPT = """
<script>
(function () {
  var canvas = document.getElementById("topo");
  if (!canvas) return;
  var gl = canvas.getContext("webgl");
  if (!gl) { canvas.style.display = "none"; return; }
  var vs = "attribute vec2 aPos; void main() { gl_Position = vec4(aPos, 0.0, 1.0); }";
  var fs = [
    "precision mediump float;",
    "uniform float iTime;",
    "uniform float uZoom;",
    "uniform float uBands;",
    "uniform float uEdgeThreshold;",
    "uniform float uTimeScale;",
    "uniform vec2 uRes;",
    "uniform vec4 uColor0;",
    "uniform vec4 uColor1;",
    "uniform vec4 uColor2;",
    "uniform vec4 uColor3;",
    "vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }",
    "vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }",
    "vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }",
    "vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }",
    "float snoise3(vec3 v) {",
    "  const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);",
    "  const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);",
    "  vec3 i = floor(v + dot(v, vec3(C.y)));",
    "  vec3 x0 = v - i + dot(i, vec3(C.x));",
    "  vec3 g = step(x0.yzx, x0.xyz);",
    "  vec3 l = 1.0 - g;",
    "  vec3 i1 = min(g.xyz, l.zxy);",
    "  vec3 i2 = max(g.xyz, l.zxy);",
    "  vec3 x1 = x0 - i1 + C.x;",
    "  vec3 x2 = x0 - i2 + C.y;",
    "  vec3 x3 = x0 - D.y;",
    "  i = mod289(i);",
    "  vec4 p = permute(permute(permute(i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));",
    "  float n_ = 0.142857142857;",
    "  vec3 ns = n_ * D.wyz - D.xzx;",
    "  vec4 j = p - 49.0 * floor(p * ns.z * ns.z);",
    "  vec4 x_ = floor(j * ns.z);",
    "  vec4 y_ = floor(j - 7.0 * x_);",
    "  vec4 x = x_ * ns.x + ns.yyyy;",
    "  vec4 y = y_ * ns.x + ns.yyyy;",
    "  vec4 h = 1.0 - abs(x) - abs(y);",
    "  vec4 b0 = vec4(x.xy, y.xy);",
    "  vec4 b1 = vec4(x.zw, y.zw);",
    "  vec4 s0 = floor(b0) * 2.0 + 1.0;",
    "  vec4 s1 = floor(b1) * 2.0 + 1.0;",
    "  vec4 sh = -step(h, vec4(0.0));",
    "  vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;",
    "  vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;",
    "  vec3 p0 = vec3(a0.xy, h.x);",
    "  vec3 p1 = vec3(a0.zw, h.y);",
    "  vec3 p2 = vec3(a1.xy, h.z);",
    "  vec3 p3 = vec3(a1.zw, h.w);",
    "  vec4 norm = taylorInvSqrt(vec4(dot(p0,p0), dot(p1,p1), dot(p2,p2), dot(p3,p3)));",
    "  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;",
    "  vec4 m = max(0.6 - vec4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);",
    "  m = m * m;",
    "  return 42.0 * dot(m * m, vec4(dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3)));",
    "}",
    "void main() {",
    "  vec3 samplePos = vec3(gl_FragCoord.xy * uZoom, iTime * uTimeScale);",
    "  float raw = snoise3(samplePos);",
    "  float normalized = (raw + 1.0) * 0.5;",
    "  float scaled = uBands * normalized;",
    "  float rounded = ceil(scaled);",
    "  float roundingError = rounded - scaled;",
    "  if (roundingError > uEdgeThreshold) { gl_FragColor = vec4(0.0); return; }",
    "  float band = floor(mod(rounded, 4.0));",
    "  if (band < 0.5) gl_FragColor = uColor0;",
    "  else if (band < 1.5) gl_FragColor = uColor1;",
    "  else if (band < 2.5) gl_FragColor = uColor2;",
    "  else gl_FragColor = uColor3;",
    "}"
  ].join("\n");
  function compile(type, src) {
    var shader = gl.createShader(type);
    gl.shaderSource(shader, src);
    gl.compileShader(shader);
    return shader;
  }
  var program = gl.createProgram();
  gl.attachShader(program, compile(gl.VERTEX_SHADER, vs));
  gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fs));
  gl.linkProgram(program);
  gl.useProgram(program);
  var buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  var loc = gl.getAttribLocation(program, "aPos");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  function u(name) { return gl.getUniformLocation(program, name); }
  gl.uniform1f(u("uZoom"), 0.0028);
  gl.uniform1f(u("uBands"), 9.0);
  gl.uniform1f(u("uEdgeThreshold"), 0.16);
  gl.uniform1f(u("uTimeScale"), 0.009);
  gl.uniform4f(u("uColor0"), 0.70, 0.62, 0.86, 0.52);
  gl.uniform4f(u("uColor1"), 0.69, 0.75, 0.77, 0.46);
  gl.uniform4f(u("uColor2"), 0.97, 0.73, 0.85, 0.40);
  gl.uniform4f(u("uColor3"), 0.70, 0.62, 0.86, 0.36);
  var coarsePointer = window.matchMedia && window.matchMedia("(pointer: coarse)").matches;
  var isMobile = coarsePointer || /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  var renderScale = isMobile ? 0.5 : 0.75;
  var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var iTimeLoc = u("iTime");
  var uResLoc = u("uRes");
  function resize() {
    var w = Math.max(1, Math.round(window.innerWidth * renderScale));
    var h = Math.max(1, Math.round(window.innerHeight * renderScale));
    if (canvas.width === w && canvas.height === h) return;
    canvas.width = w;
    canvas.height = h;
    gl.viewport(0, 0, w, h);
    gl.uniform2f(uResLoc, w, h);
  }
  window.addEventListener("resize", resize);
  resize();
  var start = performance.now();
  var frameBudgetMs = 1000 / 30;
  var lastDraw = 0;
  var rafId = 0;
  function draw(elapsedMs) {
    gl.uniform1f(iTimeLoc, elapsedMs / 1000);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
  function frame(now) {
    rafId = requestAnimationFrame(frame);
    if (now - lastDraw < frameBudgetMs) return;
    lastDraw = now;
    draw(now - start);
  }
  function play() {
    if (rafId || reduceMotion) return;
    lastDraw = 0;
    rafId = requestAnimationFrame(frame);
  }
  function stop() {
    if (rafId) cancelAnimationFrame(rafId);
    rafId = 0;
  }
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) stop();
    else play();
  });
  canvas.addEventListener("webglcontextlost", function (event) {
    event.preventDefault();
    stop();
  }, false);
  if (reduceMotion) draw(0);
  else play();
})();
</script>
"""
}
