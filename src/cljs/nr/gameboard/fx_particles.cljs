(ns nr.gameboard.fx-particles
  "Canvas particle layer for the enhanced board theme: credit glyphs, sparks
   and shockwave rings. The animation loop only runs while particles are alive."
  (:require
   [reagent.core :as r]))

(defonce ^:private canvas-ref (atom nil))
(defonce ^:private particles (atom []))
(defonce ^:private running? (atom false))

(defn- rand-between [lo hi] (+ lo (* (rand) (- hi lo))))

(defn- canvas-origin []
  (when-let [c @canvas-ref]
    (.getBoundingClientRect c)))

(defn element-center
  "Centre of the first element matching selector, in canvas coordinates."
  [selector]
  (when-let [origin (canvas-origin)]
    (when-let [el (.querySelector js/document selector)]
      (let [r (.getBoundingClientRect el)]
        (when (pos? (.-width r))
          [(- (+ (.-left r) (/ (.-width r) 2)) (.-left origin))
           (- (+ (.-top r) (/ (.-height r) 2)) (.-top origin))])))))

(defn screen-center []
  (when-let [c @canvas-ref]
    [(/ (.-clientWidth c) 2) (/ (.-clientHeight c) 2)]))

(defn- step! [dt]
  (swap! particles
         (fn [ps]
           (into []
                 (comp (map (fn [{:keys [vx vy gravity drag] :as p}]
                              (let [k (js/Math.pow drag (* dt 60))]
                                (-> p
                                    (update :life - dt)
                                    (update :x + (* vx dt))
                                    (update :y + (* vy dt))
                                    (assoc :vx (* vx k)
                                           :vy (+ (* vy k) (* gravity dt)))))))
                       (filter #(pos? (:life %))))
                 ps))))

(defn- draw! [ctx w h]
  (.clearRect ctx 0 0 w h)
  (set! (.-globalCompositeOperation ctx) "lighter")
  (doseq [{:keys [kind x y vx vy life max-life size color glyph]} @particles
          :let [t (/ life max-life)]]
    (set! (.-globalAlpha ctx) (min 1 (* 1.6 t)))
    (case kind
      :glyph
      (do (set! (.-font ctx) (str "bold " (js/Math.round size) "px Consolas, monospace"))
          (set! (.-fillStyle ctx) color)
          (set! (.-shadowColor ctx) color)
          (set! (.-shadowBlur ctx) 10)
          (.fillText ctx glyph x y)
          (set! (.-shadowBlur ctx) 0))
      :spark
      (do (set! (.-strokeStyle ctx) color)
          (set! (.-lineWidth ctx) size)
          (.beginPath ctx)
          (.moveTo ctx x y)
          (.lineTo ctx (- x (* vx 0.04)) (- y (* vy 0.04)))
          (.stroke ctx))
      :ring
      (do (set! (.-strokeStyle ctx) color)
          (set! (.-lineWidth ctx) (* 3 t))
          (.beginPath ctx)
          (.arc ctx x y (* size (- 1 t)) 0 (* 2 js/Math.PI))
          (.stroke ctx))
      :bit
      (do (set! (.-fillStyle ctx) color)
          (.fillRect ctx (- x (/ size 2)) (- y (/ size 2)) size size))))
  (set! (.-globalAlpha ctx) 1))

(defn- ensure-running! []
  (when (and @canvas-ref (not @running?))
    (reset! running? true)
    (let [last (atom nil)
          frame (fn frame [now]
                  (when-let [c @canvas-ref]
                    (let [dt (if @last (min 0.05 (/ (- now @last) 1000)) 0.016)
                          w (.-clientWidth c)
                          h (.-clientHeight c)]
                      (reset! last now)
                      (when (or (not= w (.-width c)) (not= h (.-height c)))
                        (set! (.-width c) w)
                        (set! (.-height c) h))
                      (step! dt)
                      (draw! (.getContext c "2d") w h)))
                  (if (and @canvas-ref (seq @particles))
                    (js/requestAnimationFrame frame)
                    (do (when-let [c @canvas-ref]
                          (.clearRect (.getContext c "2d") 0 0 (.-width c) (.-height c)))
                        (reset! running? false))))]
      (js/requestAnimationFrame frame))))

(defn- emit! [ps]
  (when-not (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))
    ;; hard cap so a flurry of events can't bog the page down
    (swap! particles #(vec (take-last 600 (into % ps))))
    (ensure-running!)))

;; ---------------------------------------------------------------------------
;; Effect recipes

(defn credits!
  "Credit glyphs rising from (gain) or dropping off (loss) a point."
  [[x y] delta]
  (let [n (min 24 (* 3 (js/Math.abs delta)))
        gain? (pos? delta)]
    (emit! (for [_ (range n)]
             {:kind :glyph :glyph (if gain? "¢" "·")
              :x (+ x (rand-between -40 40)) :y (+ y (rand-between -10 10))
              :vx (rand-between -30 30)
              :vy (if gain? (rand-between -160 -60) (rand-between 20 80))
              :gravity (if gain? 40 160) :drag 0.97
              :size (rand-between 12 20)
              :color (if gain? "#ffc23a" "#ff4d5e")
              :life 1.2 :max-life 1.2}))))

(defn sparks!
  "Radial burst of streaking sparks."
  [[x y] color n speed]
  (emit! (for [_ (range n)
               :let [a (rand-between 0 (* 2 js/Math.PI))
                     v (rand-between (* 0.3 speed) speed)]]
           {:kind :spark :x x :y y
            :vx (* v (js/Math.cos a)) :vy (* v (js/Math.sin a))
            :gravity 260 :drag 0.95 :size (rand-between 1 2.5)
            :color color :life 0.9 :max-life 0.9})))

(defn bits!
  "Square data fragments drifting outwards."
  [[x y] color n]
  (emit! (for [_ (range n)
               :let [a (rand-between 0 (* 2 js/Math.PI))
                     v (rand-between 20 140)]]
           {:kind :bit :x x :y y
            :vx (* v (js/Math.cos a)) :vy (* v (js/Math.sin a))
            :gravity 0 :drag 0.96 :size (rand-between 2 5)
            :color color :life 1.4 :max-life 1.4})))

(defn ring!
  "Expanding shockwave ring."
  [[x y] color radius]
  (emit! [{:kind :ring :x x :y y :vx 0 :vy 0 :gravity 0 :drag 1
           :size radius :color color :life 0.8 :max-life 0.8}]))

(defn particle-canvas []
  [:canvas.fx-particles {:ref #(reset! canvas-ref %)}])
