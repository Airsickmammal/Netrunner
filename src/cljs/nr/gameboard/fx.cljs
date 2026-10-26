(ns nr.gameboard.fx
  "Optional visual effects layer for the game board: faction-tinted HUD colours,
   a perspective grid floor and short full-screen overlays (glitch banners,
   flashes, screen shake) triggered by game events.

   Purely cosmetic: everything here reads game-state, nothing writes to it."
  (:require
   [clojure.string :as str]
   [nr.appstate :refer [app-state]]
   [nr.gameboard.state :refer [game-state]]
   [reagent.core :as r]))

(defn enabled? []
  (get-in @app-state [:options :visual-fx] false))

(defn tilt? []
  (and (enabled?)
       (get-in @app-state [:options :visual-fx-tilt] true)))

(def faction-colors
  {"Haas-Bioroid" "#8a5cc7"
   "Jinteki" "#c2185b"
   "NBN" "#ffd400"
   "Weyland Consortium" "#3fa66b"
   "Anarch" "#ff6a1f"
   "Criminal" "#3f6bff"
   "Shaper" "#5fd35a"
   "Adam" "#c9b94a"
   "Apex" "#d4262c"
   "Sunny Lebeau" "#b8b8b8"
   "Neutral" "#00e5ff"})

(defn- side-color [side]
  (let [faction (get-in @game-state [side :identity :faction])]
    (get faction-colors faction (if (= side :corp) "#00e5ff" "#ff2fd0"))))

(defn theme-style
  "CSS custom properties exposing each side's faction colour to the stylesheet."
  []
  {"--fx-corp" (side-color :corp)
   "--fx-runner" (side-color :runner)})

;; ---------------------------------------------------------------------------
;; Event overlays

(defonce overlays (r/atom []))
(defonce shake (r/atom nil))
(defonce ^:private next-id (atom 0))

(defn- remove-overlay! [id]
  (swap! overlays (fn [os] (vec (remove #(= id (:id %)) os)))))

(defn trigger!
  "Show a transient overlay. kind picks the colour scheme in the stylesheet."
  [{:keys [kind duration shake?] :or {duration 1600} :as fx}]
  (when (enabled?)
    (let [id (swap! next-id inc)]
      ;; keep at most a couple of banners on screen at once
      (swap! overlays (fn [os] (conj (vec (take-last 2 os)) (assoc fx :id id))))
      (js/setTimeout #(remove-overlay! id) duration)
      (when shake?
        (reset! shake (str (name kind) "-" id))
        (js/setTimeout #(when (= @shake (str (name kind) "-" id)) (reset! shake nil)) 450)))))

(def sfx->fx
  {"agenda-score" {:kind :score :text "AGENDA SCORED" :duration 2200}
   "agenda-steal" {:kind :steal :text "AGENDA STOLEN" :duration 2200}
   "click-run" {:kind :run :text "JACKING IN" :duration 1200}
   "run-successful" {:kind :success :text "ACCESS GRANTED" :duration 1600}
   "run-unsuccessful" {:kind :fail :text "CONNECTION TERMINATED" :duration 1600 :shake? true}
   "rez-ice" {:kind :rez :text "ICE REZZED" :duration 1100}
   "virus-purge" {:kind :purge :text "VIRUS PURGE" :duration 1600}
   "game-end" {:kind :end :text "SYSTEM HALT" :duration 2600}})

(defonce ^:private sfx-last-seen (atom nil))

(defn- handle-sfx [{:keys [sfx sfx-current-id]}]
  (let [last-seen @sfx-last-seen]
    ;; skip the backlog when (re)joining a game, same as the audio does
    (when (some? last-seen)
      (doseq [{:keys [id name]} (sort-by :id sfx)
              :when (> id last-seen)]
        (when-let [fx (sfx->fx name)]
          (trigger! fx))))
    (when sfx-current-id
      (reset! sfx-last-seen sfx-current-id))))

(def ^:private damage-re #"(?i)\b(\d+)\s+(net|meat|core|brain)\s+damage")

(defonce ^:private log-seen (atom nil))

(defn- handle-log [log]
  (let [n (count log)
        seen @log-seen]
    (when (and (some? seen) (> n seen))
      (doseq [{:keys [text]} (subvec (vec log) seen)
              :when (string? text)]
        (when-let [[_ amount kind] (re-find damage-re text)]
          (when-not (re-find #"(?i)prevent" text)
            (trigger! {:kind :damage
                       :text (str amount " " (str/upper-case kind) " DAMAGE")
                       :duration 1400
                       :shake? true})))))
    (reset! log-seen n)))

(defonce ^:private tags-seen (atom nil))

(defn- handle-tags [tags]
  (let [seen @tags-seen]
    (when (and (some? seen) (some? tags) (> tags seen))
      (trigger! {:kind :tag :text "TRACE COMPLETE // TAGGED" :duration 1400}))
    (reset! tags-seen tags)))

(defonce ^:private watch-sfx
  (r/track! #(handle-sfx (select-keys @game-state [:sfx :sfx-current-id]))))

(defonce ^:private watch-log
  (r/track! #(handle-log (:log @game-state))))

(defonce ^:private watch-tags
  (r/track! #(handle-tags (get-in @game-state [:runner :tag :base]))))

(defn reset-tracking!
  "Forget what has been seen, so joining a game doesn't replay old events."
  []
  (reset! sfx-last-seen nil)
  (reset! log-seen nil)
  (reset! tags-seen nil)
  (reset! overlays []))

;; ---------------------------------------------------------------------------
;; Components

(defn grid-floor []
  [:div.fx-grid-floor
   [:div.fx-grid-plane]
   [:div.fx-horizon]])

(defn overlay-layer []
  [:div.fx-overlay-layer
   [:div.fx-scanlines]
   [:div.fx-vignette]
   (doall
     (for [{:keys [id kind text]} @overlays]
       ^{:key id}
       [:div.fx-event {:class (str "fx-" (name kind))}
        [:div.fx-flash]
        (when text
          [:div.fx-banner {:data-text text} text])]))])
