(ns nr.gameboard.fx-audio
  "Short synthesised stingers that accompany the enhanced theme's event
   banners. Generated with WebAudio at play time, so no sound assets are
   needed. They sit quietly underneath the regular game sounds."
  (:require
   [jinteki.utils :refer [str->int]]
   [nr.appstate :refer [app-state]]))

(defonce ^:private ctx (atom nil))

(defn- audio-context []
  (or @ctx
      (when-let [AC (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
        (reset! ctx (AC.)))))

(defn- enabled? []
  (let [opts (:options @app-state)]
    (and (:sounds opts)
         (:visual-fx opts)
         (get opts :visual-fx-sounds true))))

(defn- master-gain []
  (let [v (str->int (get-in @app-state [:options :sounds-volume] 50))]
    (* 0.22 (/ (if (js/isNaN v) 50 v) 100))))

(defn- envelope
  "Gain node with a fast attack and exponential decay, connected to out."
  [ac out t0 peak attack decay]
  (let [g (.createGain ac)]
    (.setValueAtTime (.-gain g) 0.0001 t0)
    (.exponentialRampToValueAtTime (.-gain g) peak (+ t0 attack))
    (.exponentialRampToValueAtTime (.-gain g) 0.0001 (+ t0 attack decay))
    (.connect g out)
    g))

(defn- tone
  "Oscillator from f0 to f1 Hz over dur seconds."
  [ac out {:keys [type f0 f1 t dur peak attack] :or {type "sine" peak 0.5 attack 0.01 t 0}}]
  (let [now (+ (.-currentTime ac) t)
        osc (.createOscillator ac)
        g (envelope ac out now peak attack dur)]
    (set! (.-type osc) type)
    (.setValueAtTime (.-frequency osc) f0 now)
    (when f1
      (.exponentialRampToValueAtTime (.-frequency osc) f1 (+ now dur)))
    (.connect osc g)
    (.start osc now)
    (.stop osc (+ now attack dur 0.05))))

(defn- noise
  "Filtered white noise burst; the band-pass sweeps from f0 to f1."
  [ac out {:keys [f0 f1 t dur peak q] :or {peak 0.4 t 0 q 1}}]
  (let [now (+ (.-currentTime ac) t)
        len (js/Math.ceil (* (.-sampleRate ac) (+ dur 0.1)))
        buf (.createBuffer ac 1 len (.-sampleRate ac))
        data (.getChannelData buf 0)
        src (.createBufferSource ac)
        filt (.createBiquadFilter ac)
        g (envelope ac out now peak 0.005 dur)]
    (dotimes [i len] (aset data i (dec (* 2 (rand)))))
    (set! (.-buffer src) buf)
    (set! (.-type filt) "bandpass")
    (set! (.. filt -Q -value) q)
    (.setValueAtTime (.-frequency filt) f0 now)
    (.exponentialRampToValueAtTime (.-frequency filt) (or f1 f0) (+ now dur))
    (.connect src filt)
    (.connect filt g)
    (.start src now)
    (.stop src (+ now dur 0.1))))

(def ^:private recipes
  {:run (fn [ac out]
          (tone ac out {:type "sawtooth" :f0 70 :f1 700 :dur 0.55 :peak 0.25 :attack 0.05})
          (noise ac out {:f0 300 :f1 4000 :dur 0.5 :peak 0.15 :q 3}))
   :success (fn [ac out]
              (doseq [[i f] (map-indexed vector [523 659 784 1047])]
                (tone ac out {:type "triangle" :f0 f :t (* i 0.07) :dur 0.35 :peak 0.3})))
   :fail (fn [ac out]
           (tone ac out {:type "sawtooth" :f0 400 :f1 60 :dur 0.6 :peak 0.3})
           (noise ac out {:f0 2000 :f1 200 :dur 0.4 :peak 0.25 :q 2}))
   :rez (fn [ac out]
          (tone ac out {:type "square" :f0 1800 :f1 2400 :dur 0.12 :peak 0.12})
          (tone ac out {:type "sine" :f0 2400 :f1 1200 :t 0.05 :dur 0.5 :peak 0.2}))
   :score (fn [ac out]
            (doseq [f [392 494 587 784]]
              (tone ac out {:type "triangle" :f0 f :dur 1.1 :peak 0.18 :attack 0.08})))
   :steal (fn [ac out]
            (doseq [[i f] (map-indexed vector [784 622 784 932])]
              (tone ac out {:type "square" :f0 f :t (* i 0.08) :dur 0.25 :peak 0.12})))
   :damage (fn [ac out]
             (tone ac out {:type "sine" :f0 140 :f1 40 :dur 0.35 :peak 0.6})
             (noise ac out {:f0 1200 :f1 300 :dur 0.25 :peak 0.35 :q 0.8}))
   :tag (fn [ac out]
          (doseq [[i f] (map-indexed vector [880 660 880 660])]
            (tone ac out {:type "square" :f0 f :t (* i 0.12) :dur 0.1 :peak 0.12})))
   :purge (fn [ac out]
            (noise ac out {:f0 6000 :f1 150 :dur 0.9 :peak 0.3 :q 4}))
   :end (fn [ac out]
          (tone ac out {:type "sawtooth" :f0 220 :f1 30 :dur 1.8 :peak 0.25 :attack 0.1})
          (tone ac out {:type "sine" :f0 55 :dur 1.8 :peak 0.4 :attack 0.1}))})

(defn play!
  "Play the stinger for an event kind, if there is one and sounds are on."
  [kind]
  (when (enabled?)
    (when-let [recipe (recipes kind)]
      (when-let [ac (audio-context)]
        (when (= "suspended" (.-state ac)) (.resume ac))
        (let [out (.createGain ac)]
          (set! (.. out -gain -value) (master-gain))
          (.connect out (.-destination ac))
          (recipe ac out))))))
