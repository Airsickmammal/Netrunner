(ns nr.gameboard.fx-motion
  "Animates cards travelling between zones. Each card frame carries its cid
   (data-cid); when a card is re-mounted somewhere else on the board, a ghost
   of how it last looked flies from the old spot to the new one, and the real
   card is revealed when the ghost lands.

   The ghost only ever shows an image the viewer could already see, so no
   hidden information is revealed.")

(def ^:private flight-ms 420)
;; ignore re-mounts that barely move (re-keying, stacking changes)
(def ^:private min-distance 40)

(defonce ^:private snapshot (atom {}))
(defonce ^:private observer (atom nil))
(defonce ^:private scheduled? (atom false))

(defn- card-image-src [el]
  (some-> (.querySelector el "img.card") .-src))

(defn- read-cards [root]
  (into {}
        (keep (fn [el]
                (let [cid (.getAttribute el "data-cid")
                      card (.querySelector el ".card")
                      r (when card (.getBoundingClientRect card))]
                  (when (and (seq cid) r (pos? (.-width r)))
                    [cid {:el el :card card :rect r :src (card-image-src el)}]))))
        (array-seq (js/Array.from (.querySelectorAll root "[data-cid]")))))

(defn- center [r]
  [(+ (.-left r) (/ (.-width r) 2)) (+ (.-top r) (/ (.-height r) 2))])

(defn- turned?
  "ICE is drawn rotated, so its slot is landscape."
  [r]
  (> (.-width r) (.-height r)))

(defn- fly! [layer {:keys [rect src]} {:keys [card] new-rect :rect}]
  (let [origin (.getBoundingClientRect layer)
        ;; the ghost is always an upright card; rotation is part of the animation
        w (min (.-width rect) (.-height rect))
        h (max (.-width rect) (.-height rect))
        [x0 y0] (center rect)
        [x1 y1] (center new-rect)
        dx (- x1 x0)
        dy (- y1 y0)
        rot0 (if (turned? rect) 90 0)
        rot1 (if (turned? new-rect) 90 0)
        scale (/ (min (.-width new-rect) (.-height new-rect)) w)
        transform (fn [x y rot sc] (str "translate(" x "px," y "px) rotate(" rot "deg) scale(" sc ")"))
        ghost (.createElement js/document "div")
        img (.createElement js/document "img")]
    (set! (.-className ghost) "fx-card-ghost")
    (set! (.. ghost -style -left) (str (- x0 (/ w 2) (.-left origin)) "px"))
    (set! (.. ghost -style -top) (str (- y0 (/ h 2) (.-top origin)) "px"))
    (set! (.. ghost -style -width) (str w "px"))
    (set! (.. ghost -style -height) (str h "px"))
    (set! (.-src img) src)
    (.appendChild ghost img)
    (.appendChild layer ghost)
    (set! (.. card -style -visibility) "hidden")
    (let [anim (.animate ghost
                         (clj->js [{:transform (transform 0 0 rot0 1)}
                                   {:transform (transform (* dx 0.5) (- (* dy 0.5) 30)
                                                          (/ (+ rot0 rot1) 2) (* 1.2 (/ (+ 1 scale) 2)))
                                    :offset 0.5}
                                   {:transform (transform dx dy rot1 scale)}])
                         #js {:duration flight-ms :easing "cubic-bezier(0.3, 0.7, 0.2, 1)"})
          land (fn []
                 (set! (.. card -style -visibility) "")
                 (.remove ghost))]
      (set! (.-onfinish anim) land)
      (set! (.-oncancel anim) land))))

(defn- scan! [root layer]
  (reset! scheduled? false)
  (when (.-isConnected root)
    (let [prev @snapshot
          now (read-cards root)]
      (doseq [[cid {:keys [el rect] :as cur}] now
              :let [{old-el :el old-rect :rect :as old} (get prev cid)]
              :when (and old
                         (not (identical? el old-el))
                         (:src old)
                         (> (js/Math.hypot (- (.-left rect) (.-left old-rect))
                                           (- (.-top rect) (.-top old-rect)))
                            min-distance))]
        (fly! layer old cur))
      (reset! snapshot now))))

(defn start!
  "Begin watching root for cards changing place. layer hosts the ghosts."
  [root layer]
  (when (and root layer (exists? js/MutationObserver)
             (not (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))))
    (when-let [o @observer] (.disconnect o))
    (reset! snapshot (read-cards root))
    (let [o (js/MutationObserver.
              (fn [_]
                ;; coalesce a render's worth of mutations into one scan, after layout
                (when-not @scheduled?
                  (reset! scheduled? true)
                  (js/requestAnimationFrame #(scan! root layer)))))]
      (.observe o root #js {:childList true :subtree true})
      (reset! observer o))))

(defn stop! []
  (when-let [o @observer] (.disconnect o))
  (reset! observer nil)
  (reset! snapshot {}))
