(ns tasks.import-decks
  "Bulk-import NetrunnerDB decklists into one user's decks.

  Usage: lein import-decks <username> <file> [format]

  <file> lists one NetrunnerDB decklist per line, as a URL or a bare decklist
  id. A line may end with ` | Deck name` to rename the imported deck. Blank
  lines and lines starting with # are ignored. [format] defaults to eternal,
  so older decks aren't marked illegal just because of rotation.

  Decks already imported from the same NetrunnerDB list are skipped, so the
  task can be re-run after adding lines to the file."
  (:require
   [cljc.java-time.instant :as inst]
   [clojure.string :as str]
   [jinteki.validator :refer [calculate-deck-status]]
   [monger.collection :as mc]
   [tasks.setup :refer [connect disconnect]]
   [web.decks :refer [prepare-deck-for-db update-deck]]
   [web.mongodb :refer [->object-id]]
   [web.nrdb :refer [download-public-decklist]]))

;; be polite to NetrunnerDB
(def ^:private request-delay-ms 500)

(defn- parse-line [line]
  (let [line (str/trim line)]
    (when-not (or (str/blank? line) (str/starts-with? line "#"))
      (let [[input deck-name] (map str/trim (str/split line #"\s+\|\s+" 2))]
        {:input input :name deck-name}))))

(defn- already-imported? [db username notes]
  (some? (mc/find-one-as-map db "decks" {:username username :notes notes})))

(defn- import-one!
  "Returns :imported, :skipped or :failed."
  [db username format {:keys [input name]}]
  (let [deck (download-public-decklist db input)]
    (cond
      (not (every? #(some? (get deck %)) [:name :identity :cards]))
      (do (println "FAILED  " input "- could not download or parse the decklist")
          :failed)

      (already-imported? db username (:notes deck))
      (do (println "SKIPPED " input "- already imported")
          :skipped)

      :else
      (let [db-deck (cond-> (assoc deck
                                   :_id (->object-id)
                                   :date (inst/now)
                                   :format format)
                      name (assoc :name name))
            status (calculate-deck-status (update-deck db-deck))]
        (mc/insert db "decks" (prepare-deck-for-db db-deck username status))
        (println "IMPORTED" input "-" (:name db-deck))
        :imported))))

(defn command
  [& [username file format]]
  (if-not (and username file)
    (println "Usage: lein import-decks <username> <file> [format]")
    (let [entries (keep parse-line (str/split-lines (slurp file)))
          format (or format "eternal")
          {{:keys [db]} :mongodb/connection :as system} (connect)]
      (try
        (if-not (mc/find-one-as-map db "users" {:username username})
          (println "ERROR: no user named" username "- register the account first")
          (let [results (doall
                          (for [entry entries]
                            (let [result (try
                                           (import-one! db username format entry)
                                           (catch Exception e
                                             (println "FAILED  " (:input entry) "-" (.getMessage e))
                                             :failed))]
                              (Thread/sleep request-delay-ms)
                              result)))
                {:keys [imported skipped failed] :or {imported 0 skipped 0 failed 0}} (frequencies results)]
            (println (str "\n" imported " imported, " skipped " skipped, " failed " failed"))))
        (finally (disconnect system))))))
