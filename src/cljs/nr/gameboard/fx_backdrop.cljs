(ns nr.gameboard.fx-backdrop
  "WebGL backdrop for the enhanced board theme: an endless neon grid floor
   rendered by a single fragment shader. It is tinted with each side's faction
   colour, speeds up while a run is in progress and flashes on game events.
   Falls back to the CSS grid when WebGL is unavailable."
  (:require
   [nr.gameboard.state :refer [game-state]]
   [reagent.core :as r]))

(def ^:private vertex-src
  "attribute vec2 a_pos;
   void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }")

(def ^:private fragment-src
  "precision mediump float;
   uniform vec2 u_res;
   uniform float u_time;
   uniform float u_travel;
   uniform float u_run;
   uniform vec3 u_corp;
   uniform vec3 u_runner;
   uniform vec3 u_pulse_color;
   uniform float u_pulse;

   float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

   void main() {
     vec2 uv = (gl_FragCoord.xy - 0.5 * u_res) / u_res.y;
     float horizon = 0.16;
     float sky = uv.y - horizon;
     vec3 col = vec3(0.012, 0.016, 0.035);

     // faction-tinted haze along the horizon
     vec3 tint = mix(u_corp, u_runner, smoothstep(-0.8, 0.8, uv.x));
     col += tint * 0.10 * exp(-abs(sky) * 5.0);
     col += tint * (0.6 + 0.6 * u_run) * exp(-abs(sky) * 90.0);

     if (sky > 0.0) {
       // drifting data motes in the sky
       vec2 cell = floor(uv * 60.0 + vec2(u_time * 0.4, 0.0));
       float h = hash(cell);
       float tw = 0.5 + 0.5 * sin(u_time * 3.0 + h * 40.0);
       col += tint * step(0.985, h) * tw * 0.7 * smoothstep(0.0, 0.25, sky);
     } else {
       // perspective floor: depth grows towards the horizon
       float d = -sky;
       float z = 0.35 / d;
       vec2 g = vec2(uv.x * z * 1.6, z + u_travel);
       vec2 f = abs(fract(g) - 0.5);
       // keep lines ~1.5px wide on screen: grid units per pixel along each axis
       float px = 1.0 / u_res.y;
       float lwx = min(0.5, 1.5 * z * 1.6 * px);
       float lwy = min(0.5, 1.5 * z * z / 0.35 * px);
       float vline = (1.0 - smoothstep(0.0, lwx, 0.5 - f.x)) * (1.0 - lwx);
       float hline = (1.0 - smoothstep(0.0, lwy, 0.5 - f.y)) * (1.0 - lwy);
       float fog = exp(-z * 0.12);
       col += u_corp * vline * fog * (0.9 + 0.5 * u_run);
       col += u_runner * hline * fog * (0.9 + 0.5 * u_run);
       // packets racing down the lanes during a run
       float lane = floor(g.x);
       float packet = fract(g.y * 0.25 + hash(vec2(lane, 1.0)) - u_time * (0.6 + u_run));
       col += mix(u_runner, vec3(1.0), 0.4) * vline * smoothstep(0.92, 1.0, packet) * fog * u_run * 1.5;
       col += tint * 0.05 * fog;
     }

     // event flash
     col += u_pulse_color * u_pulse * (0.25 + 0.75 * exp(-abs(sky) * 3.0));
     // vignette
     col *= 1.0 - 0.45 * dot(uv * vec2(0.6, 1.0), uv * vec2(0.6, 1.0));
     gl_FragColor = vec4(col, 1.0);
   }")

(defn- hex->rgb [hex]
  (let [n (js/parseInt (subs hex 1) 16)]
    [(/ (bit-and (bit-shift-right n 16) 255) 255)
     (/ (bit-and (bit-shift-right n 8) 255) 255)
     (/ (bit-and n 255) 255)]))

;; Event flash, decays every frame
(defonce ^:private pulse (atom {:amount 0 :color [0 0.9 1]}))

(defn pulse!
  "Flash the backdrop in the given #rrggbb colour."
  [hex strength]
  (reset! pulse {:amount strength :color (hex->rgb hex)}))

(defn- compile-shader [gl type src]
  (let [shader (.createShader gl type)]
    (.shaderSource gl shader src)
    (.compileShader gl shader)
    (if (.getShaderParameter gl shader (.-COMPILE_STATUS gl))
      shader
      (do (js/console.warn "fx backdrop shader:" (.getShaderInfoLog gl shader))
          nil))))

(defn- init-gl [canvas]
  (when-let [gl (or (.getContext canvas "webgl" #js {:antialias false :alpha false})
                    (.getContext canvas "experimental-webgl"))]
    (let [vs (compile-shader gl (.-VERTEX_SHADER gl) vertex-src)
          fs (compile-shader gl (.-FRAGMENT_SHADER gl) fragment-src)]
      (when (and vs fs)
        (let [prog (.createProgram gl)]
          (.attachShader gl prog vs)
          (.attachShader gl prog fs)
          (.linkProgram gl prog)
          (when (.getProgramParameter gl prog (.-LINK_STATUS gl))
            (.useProgram gl prog)
            (let [buf (.createBuffer gl)
                  loc (.getAttribLocation gl prog "a_pos")]
              (.bindBuffer gl (.-ARRAY_BUFFER gl) buf)
              (.bufferData gl (.-ARRAY_BUFFER gl)
                           (js/Float32Array. #js [-1 -1 3 -1 -1 3])
                           (.-STATIC_DRAW gl))
              (.enableVertexAttribArray gl loc)
              (.vertexAttribPointer gl loc 2 (.-FLOAT gl) false 0 0))
            {:gl gl
             :u (into {} (for [n ["u_res" "u_time" "u_travel" "u_run" "u_corp"
                                  "u_runner" "u_pulse_color" "u_pulse"]]
                           [n (.getUniformLocation gl prog n)]))}))))))

(defn- css-color [el prop fallback]
  (let [v (.trim (.getPropertyValue (js/getComputedStyle el) prop))]
    (if (re-matches #"#[0-9a-fA-F]{6}" v) v fallback)))

;; Render at reduced resolution; the backdrop is soft and this keeps it cheap.
(def ^:private resolution-scale 0.5)

(defn- start-loop! [canvas {:keys [gl u]}]
  (let [state (atom {:travel 0 :run 0 :last nil :raf nil :colors-at 0})
        frame (fn frame [now]
                ;; faction colours only change when identities load, so poll them lazily
                (when (> (- now (:colors-at @state)) 1000)
                  (let [view (.closest canvas ".gameview")]
                    (swap! state assoc
                           :colors-at now
                           :corp (hex->rgb (css-color view "--fx-corp" "#00e5ff"))
                           :runner (hex->rgb (css-color view "--fx-runner" "#ff2fd0")))))
                (let [{:keys [last travel run] [cr cg cb] :corp [rr rg rb] :runner} @state
                      dt (if last (min 0.1 (/ (- now last) 1000)) 0)
                      target-run (if (:run @game-state) 1 0)
                      run (+ run (* (- target-run run) (min 1 (* dt 2.5))))
                      travel (+ travel (* dt (+ 0.25 (* 1.4 run))))
                      w (js/Math.max 1 (js/Math.floor (* resolution-scale (.-clientWidth canvas))))
                      h (js/Math.max 1 (js/Math.floor (* resolution-scale (.-clientHeight canvas))))
                      {p :amount [pr pg pb] :color} @pulse]
                  (when (or (not= w (.-width canvas)) (not= h (.-height canvas)))
                    (set! (.-width canvas) w)
                    (set! (.-height canvas) h)
                    (.viewport gl 0 0 w h))
                  (.uniform2f gl (u "u_res") w h)
                  (.uniform1f gl (u "u_time") (/ now 1000))
                  (.uniform1f gl (u "u_travel") travel)
                  (.uniform1f gl (u "u_run") run)
                  (.uniform3f gl (u "u_corp") cr cg cb)
                  (.uniform3f gl (u "u_runner") rr rg rb)
                  (.uniform3f gl (u "u_pulse_color") pr pg pb)
                  (.uniform1f gl (u "u_pulse") p)
                  (.drawArrays gl (.-TRIANGLES gl) 0 3)
                  (swap! pulse update :amount #(max 0 (- % (* dt 1.6))))
                  (swap! state assoc :last now :travel travel :run run
                         :raf (js/requestAnimationFrame frame))))]
    (swap! state assoc :raf (js/requestAnimationFrame frame))
    #(js/cancelAnimationFrame (:raf @state))))

(defn backdrop
  "Canvas element hosting the shader. Renders nothing visible (and leaves the
   CSS grid showing) if WebGL can't be initialised."
  []
  (let [stop (atom nil)
        node (atom nil)
        ok? (r/atom true)]
    (r/create-class
      {:display-name "fx-backdrop"
       :component-did-mount
       (fn [_]
         (let [canvas @node
               reduced? (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))]
           (if-let [ctx (when-not reduced? (init-gl canvas))]
             (reset! stop (start-loop! canvas ctx))
             (reset! ok? false))))
       :component-will-unmount
       (fn [_] (when-let [f @stop] (f)))
       :reagent-render
       (fn []
         [:canvas.fx-backdrop {:ref #(reset! node %)
                               :class (when-not @ok? "unavailable")}])})))
