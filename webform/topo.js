const canvas = document.getElementById("topo");
if (!canvas) throw new Error("missing topo canvas");

const gl = canvas.getContext("webgl");
if (!gl) {
  canvas.style.display = "none";
} else {
  const vs = `
    attribute vec2 aPos;
    void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
  `;
  const fs = `
    precision mediump float;
    uniform float iTime;
    uniform float uZoom;
    uniform float uBands;
    uniform float uEdgeThreshold;
    uniform float uTimeScale;
    uniform vec2 uRes;
    uniform vec4 uColor0;
    uniform vec4 uColor1;
    uniform vec4 uColor2;
    uniform vec4 uColor3;

    vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
    vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
    vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
    vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

    float snoise3(vec3 v) {
      const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
      const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
      vec3 i = floor(v + dot(v, vec3(C.y)));
      vec3 x0 = v - i + dot(i, vec3(C.x));
      vec3 g = step(x0.yzx, x0.xyz);
      vec3 l = 1.0 - g;
      vec3 i1 = min(g.xyz, l.zxy);
      vec3 i2 = max(g.xyz, l.zxy);
      vec3 x1 = x0 - i1 + C.x;
      vec3 x2 = x0 - i2 + C.y;
      vec3 x3 = x0 - D.y;
      i = mod289(i);
      vec4 p = permute(permute(permute(i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));
      float n_ = 0.142857142857;
      vec3 ns = n_ * D.wyz - D.xzx;
      vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
      vec4 x_ = floor(j * ns.z);
      vec4 y_ = floor(j - 7.0 * x_);
      vec4 x = x_ * ns.x + ns.yyyy;
      vec4 y = y_ * ns.x + ns.yyyy;
      vec4 h = 1.0 - abs(x) - abs(y);
      vec4 b0 = vec4(x.xy, y.xy);
      vec4 b1 = vec4(x.zw, y.zw);
      vec4 s0 = floor(b0) * 2.0 + 1.0;
      vec4 s1 = floor(b1) * 2.0 + 1.0;
      vec4 sh = -step(h, vec4(0.0));
      vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
      vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
      vec3 p0 = vec3(a0.xy, h.x);
      vec3 p1 = vec3(a0.zw, h.y);
      vec3 p2 = vec3(a1.xy, h.z);
      vec3 p3 = vec3(a1.zw, h.w);
      vec4 norm = taylorInvSqrt(vec4(dot(p0,p0), dot(p1,p1), dot(p2,p2), dot(p3,p3)));
      p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
      vec4 m = max(0.6 - vec4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);
      m = m * m;
      return 42.0 * dot(m * m, vec4(dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3)));
    }

    void main() {
      vec3 samplePos = vec3(gl_FragCoord.xy * uZoom, iTime * uTimeScale);
      float raw = snoise3(samplePos);
      float normalized = (raw + 1.0) * 0.5;
      float scaled = uBands * normalized;
      float rounded = ceil(scaled);
      float roundingError = rounded - scaled;
      if (roundingError > uEdgeThreshold) {
        gl_FragColor = vec4(0.0);
        return;
      }
      float band = floor(mod(rounded, 4.0));
      if (band < 0.5) gl_FragColor = uColor0;
      else if (band < 1.5) gl_FragColor = uColor1;
      else if (band < 2.5) gl_FragColor = uColor2;
      else gl_FragColor = uColor3;
    }
  `;

  function compile(type, src) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, src);
    gl.compileShader(shader);
    return shader;
  }
  const program = gl.createProgram();
  gl.attachShader(program, compile(gl.VERTEX_SHADER, vs));
  gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fs));
  gl.linkProgram(program);
  gl.useProgram(program);
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(program, "aPos");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const u = (name) => gl.getUniformLocation(program, name);
  gl.uniform1f(u("uZoom"), 0.0028);
  gl.uniform1f(u("uBands"), 9.0);
  gl.uniform1f(u("uEdgeThreshold"), 0.16);
  gl.uniform1f(u("uTimeScale"), 0.009);
  gl.uniform4f(u("uColor0"), 0.70, 0.62, 0.86, 0.52);
  gl.uniform4f(u("uColor1"), 0.69, 0.75, 0.77, 0.46);
  gl.uniform4f(u("uColor2"), 0.97, 0.73, 0.85, 0.40);
  gl.uniform4f(u("uColor3"), 0.70, 0.62, 0.86, 0.36);

  // The topographic field is soft, so a down-scaled buffer stretched by CSS looks identical
  // while doing a fraction of the per-pixel work. This is what lets phones render it at all and
  // stops a warm laptop from lagging harder the longer the page stays open.
  const coarsePointer = window.matchMedia && window.matchMedia("(pointer: coarse)").matches;
  const isMobile = coarsePointer || /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  const renderScale = isMobile ? 0.5 : 0.75;
  const reduceMotion = window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const iTimeLoc = u("iTime");
  const uResLoc = u("uRes");

  function resize() {
    const w = Math.max(1, Math.round(window.innerWidth * renderScale));
    const h = Math.max(1, Math.round(window.innerHeight * renderScale));
    if (canvas.width === w && canvas.height === h) return;
    canvas.width = w;
    canvas.height = h;
    gl.viewport(0, 0, w, h);
    gl.uniform2f(uResLoc, w, h);
  }
  window.addEventListener("resize", resize);
  resize();

  const start = performance.now();
  const frameBudgetMs = 1000 / 30; // ~30 fps keeps the GPU cool during long sessions
  let lastDraw = 0;
  let rafId = 0;

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

  // Never keep painting for a tab nobody is looking at.
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) stop();
    else play();
  });

  // A lost GL context (common on mobile under memory pressure) would otherwise freeze the page.
  canvas.addEventListener("webglcontextlost", (event) => {
    event.preventDefault();
    stop();
  }, false);

  if (reduceMotion) {
    draw(0); // a single static topographic frame, no continuous animation
  } else {
    play();
  }
}
