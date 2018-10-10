(ns drl-control-liftingcast.main
  (:require [cemerick.url :as url]
            [clojure.core.async :as async :refer [<! >!]]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as string]
            [clojure.test :as test]
            [com.ashafa.clutch :as couch]
            [java-time]))


(def meet-id "madkjss3n61g")
(def platform-ids ["psxoihq1ncdm"
                   "piushftxfeut"
                   "poisorct11q1"
                   "pev7h2jn8u12"])

(def platform 1)
(def platform-id (nth platform-ids (dec platform)))

(def referee-positions #{"left" "head" "right"})

(def position->referee-id
  (->> referee-positions
       (map (fn [position] [(keyword position) (str "r" position "-" platform-id)]))
       (into {})))

(def current-attempt-id (atom nil))



(def db-url (url/url "http://couchdb.liftingcast.com" meet-id))
(def username "madkjss3n61g")
(def password "xm4sj4ms")

;; (def db-url (url/url "http://127.0.0.1:5984" (str meet-id "_1")))
;; (def username "test")
;; (def password "test")

(def db (-> db-url
            (assoc :username username
                   :password password)
            couch/get-database))

(def get-document (partial couch/get-document db))

(s/def :couchdb/db map?)

(defn get-all-docs [db]
  (map :doc
       (couch/all-documents db {:include_docs true})))

(s/def :couchdb/doc-seq (s/every (s/keys :req-un [:liftingcast/_id
                                                  :liftingcast/_rev])))
(s/fdef get-all-docs
  :args (s/cat :db :couchdb/db)
  :ret :couchdb/doc-seq)

(defn index-docs-by-id [docs]
  (->> docs
       (map (fn [doc] [(:_id doc) doc]))
       (into {})))

(s/def ::in-memory-db
  (s/every (fn [[k v]] (= k (:_id v))) :kind map?))

(s/fdef index-docs-by-id
  :args (s/cat :docs :couchdb/doc-seq)
  :ret ::in-memory-db)

(s/def :liftingcast/_id string?)

(s/def :liftingcast.platform/_id
  (s/and :liftingcast/_id
         #(string/starts-with? % "p")))

(s/def :liftingcast.lifter/_id
  (s/and :liftingcast/_id
         #(string/starts-with? % "l")))

(defn lifter-ids-on-platform [in-memory-db platform-id]
  (for [[id doc] in-memory-db
        :when (and (string/starts-with? id "l")
                   (= platform-id (:platformId doc)))]
    id))

(s/fdef lifter-ids-on-platform
  :args (s/cat :in-memory-db ::in-memory-db
               :platform-id :liftingcast.platform/_id)
  :ret (s/every :liftingcast.lifter/_id))

(defn attempt-ids-for-lifter [lifter-id]
  (map (fn [i] (str "a" i "-" lifter-id)) (range 1 10)))

(s/fdef attempt-ids-for-lifter
  :args (s/cat :lifter-id :liftingcast.lifter/_id)
  :ret (s/coll-of (s/and :liftingcast/_id
                         #(string/starts-with? % "a"))
                  :count 9
                  :distinct true))

(let [in-memory-db (-> db get-all-docs index-docs-by-id)
      attempt-ids-on-platform (for [lifter-id (lifter-ids-on-platform in-memory-db
                                                                      platform-id)
                                    attempt-id (attempt-ids-for-lifter lifter-id)]
                                attempt-id)]
  (s/def :liftingcast.attempt/_id (set attempt-ids-on-platform)))

(defn fetch-current-attempt-id [db platform-id]
  (-> platform-id get-document :currentAttemptId))

(s/fdef fetch-current-attempt-id
  :args (s/cat :db :couchdb/db :platform-id :liftingcast.platform/_id)
  :ret :liftingcast.attempt/_id)

(defn set-current-attempt-id! [db platform-id]
  (reset! current-attempt-id (fetch-current-attempt-id db platform-id)))

(s/def :liftingcast.referee/_id
  (-> position->referee-id vals set))

(defn valid-decision-from-referee? [[white-light red-card blue-card yellow-card]]
  (not= white-light (or red-card blue-card yellow-card)))

(s/def :drl/decision-token boolean?)

(s/def :drl/decision-from-referee
  (s/and valid-decision-from-referee?
         (s/cat :drl/white-light :drl/decision-token
                :drl/red-card :drl/decision-token
                :drl/blue-card :drl/decision-token
                :drl/yellow-card :drl/decision-token)))

(s/def :drl/decision
  (s/and (s/coll-of boolean? :count 12 into [])
         (s/conformer (partial partition 4))
         (s/cat :drl/left :drl/decision-from-referee
                :drl/head :drl/decision-from-referee
                :drl/right :drl/decision-from-referee)))

(comment
  (s/conform :drl/decision
             [true false false false
              true false false false
              true false false false])
  ;; => #:drl{:left
  ;;          #:drl{:white-light true,
  ;;                :red-card false,
  ;;                :blue-card false,
  ;;                :yellow-card false},
  ;;          :head
  ;;          #:drl{:white-light true,
  ;;                :red-card false,
  ;;                :blue-card false,
  ;;                :yellow-card false},
  ;;          :right
  ;;          #:drl{:white-light true,
  ;;                :red-card false,
  ;;                :blue-card false,
  ;;                :yellow-card false}}
  )


(s/def :liftingcast/_rev string?) ; used in top level of docs
(s/def :liftingcast/rev :liftingcast/_rev) ; used in changes
;; liftingcast/timeStamp could be more specific but I only care that it is
;; present and doesn't change.
(s/def :liftingcast/timeStamp string?)
(s/def :liftingcast/createDate :liftingcast/timeStamp)

(s/def :liftingcast/attribute string?)

(s/def :liftingcast/card boolean?)

(s/def :liftingcast/red :liftingcast/card)
(s/def :liftingcast/blue :liftingcast/card)
(s/def :liftingcast/yellow :liftingcast/card)

(s/def :liftingcast/cards
  (s/nilable
   (s/keys :req-un [:liftingcast/red
                    :liftingcast/blue
                    :liftingcast/yellow])))

(s/def :liftingcast/ruling #{"good" "bad"})
(s/def :liftingcast/decision (s/nilable :liftingcast/ruling))
(s/def :liftingcast.decisions/decision
  (s/keys :req-un [:liftingcast/decision
                   :liftingcast/cards]))
(s/def :liftingcast/left :liftingcast.decisions/decision)
(s/def :liftingcast/head :liftingcast.decisions/decision)
(s/def :liftingcast/right :liftingcast.decisions/decision)
(s/def :liftingcast/decisions
  (s/keys :req-un [:liftingcast/left
                   :liftingcast/head
                   :liftingcast/right]))

(s/def :liftingcast/clock-state #{"initial" "started"})



(s/def :liftingcast/document
  (s/keys :req-un [:liftingcast/_rev
                   :liftingcast/createDate]))

(s/def :liftingcast/change
  (s/keys :req-un [:liftingcast/rev
                   :liftingcast/attribute
                   :liftingcast/timeStamp]))


(defmulti referee-change :attribute)

(s/def :liftingcast.referee.change.cards/value :liftingcast/cards)
(defmethod referee-change "cards" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.referee.change.cards/value])))

(s/def :liftingcast.referee.change.decision/value :liftingcast/decision)
(defmethod referee-change "decision" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.referee.change.decision/value])))

(s/def :liftingcast.referee/changes
  (s/every (s/multi-spec referee-change :attribute)
           :kind vector?
           :max-count 100))

(s/def :liftingcast/platformId :liftingcast.platform/_id)

(s/def :liftingcast/position referee-positions)

(s/def :liftingcast/referee
  (s/merge :liftingcast/document
           (s/keys :req-un [:liftingcast.referee/_id
                            :liftingcast.referee/changes
                            :liftingcast/platformId
                            :liftingcast/position
                            :liftingcast/cards]
                   :opt-un [:liftingcast/decision])))



(defmulti attempt-change :attribute)

(s/def :liftingcast.attempt.change.result/value :liftingcast/ruling)
(defmethod attempt-change "result" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.attempt.change.result/value])))

(s/def :liftingcast.attempt.change.decisions/value :liftingcast/decisions)
(defmethod attempt-change "decisions" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.attempt.change.decisions/value])))

(s/def :liftingcast.attempt/changes
  (s/every (s/multi-spec attempt-change :attribute)
           :kind vector?
           :max-count 100))

(s/def :liftingcast/liftName #{"squat" "bench" "dead"})
(s/def :liftingcast/attemptNumber #{"1" "2" "3"})
(s/def :liftingcast.lifter/_id
  (s/and :liftingcast/_id
         #(string/starts-with? % "l")))
(s/def :liftingcast/lifterId :liftingcast.lifter/_id)
(s/def :liftingcast/weight (s/and number? (complement neg?)))
(s/def :liftingcast/result :liftingcast/ruling)
(s/def :liftingcast/endOfRound (s/nilable boolean?))

;; Liftingcast initially creates all nine attempts for a lifter; an attempt may
;; not have a weight.
;; An attempt doesn't have a result or decision before it is judged.
(s/def :liftingcast/attempt
  (s/merge :liftingcast/document
           (s/keys :req-un [:liftingcast.attempt/_id
                            :liftingcast.attempt/changes
                            :liftingcast/liftName
                            :liftingcast/attemptNumber
                            :liftingcast/lifterId]
                   :opt-un [:liftingcast/weight
                            :liftingcast/result
                            :liftingcast/decisions
                            :liftingcast/endOfRound])))



(defmulti platform-change :attribute)

(s/def :liftingcast.platform.change.current-attempt-id/value :liftingcast/_id)
(defmethod platform-change "currentAttemptId" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.platform.change.current-attempt-id/value])))

(s/def :liftingcast.platform.change.clock-state/value :liftingcast/clock-state)
(defmethod platform-change "clockState" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.platform.change.clock-state/value])))

(s/def :liftingcast.platform/changes
  (s/every (s/multi-spec platform-change :attribute)
           :kind vector?
           :max-count 100))

(s/def :liftingcast/name string?)
(s/def :liftingcast/barAndCollarsWeight pos-int?)
(s/def :liftingcast/clockTimerLength nat-int?)
(s/def :liftingcast/clockState :liftingcast/clock-state)
(s/def :liftingcast/currentAttemptId :liftingcast.attempt/_id)

(s/def :liftingcast/platform
  (s/merge :liftingcast/document
           (s/keys :req-un [:liftingcast.platform/_id
                            :liftingcast.platform/changes
                            :liftingcast/name
                            :liftingcast/barAndCollarsWeight
                            :liftingcast/clockTimerLength
                            :liftingcast/clockState
                            :liftingcast/currentAttemptId])))



(defn drl-decision-from-referee->liftingcast-decision
  [{:keys [drl/white-light]}]
  (if white-light
    "good"
    "bad"))

(s/fdef drl-decision-from-referee->liftingcast-decision
  :args (s/cat :drl-decision-from-referee (s/keys :req [:drl/white-light]))
  :ret :liftingcast/decision)

(defn drl-decision-from-referee->liftingcast-cards
  [{:keys [drl/red-card drl/blue-card drl/yellow-card]}]
  {:red red-card
   :blue blue-card
   :yellow yellow-card})

(s/fdef drl-decision-from-referee->liftingcast-cards
  :args (s/cat :drl-decision-from-referee (s/keys :req [:drl/red-card
                                                        :drl/blue-card
                                                        :drl/yellow-card]))
  :ret :liftingcast/cards)

;; TODO: Use Expound!
(defn drl-decision->liftingcast-decisions [drl-decision]
  (let [conformed (s/conform :drl/decision drl-decision)]
    (if (s/invalid? conformed)
      (do
        (println "INVALID INPUT FROM DRL:")
        (println (s/explain :drl/decision drl-decision)))
      (->> conformed
           (map (fn [[position decision-from-referee]]
                  {(keyword (name position))
                   {:decision (drl-decision-from-referee->liftingcast-decision decision-from-referee)
                    :cards (drl-decision-from-referee->liftingcast-cards decision-from-referee)}}))
           (into {})))))

(s/fdef drl-decision->liftingcast-decisions
  :args (s/cat :drl-decision :drl/decision)
  :ret :liftingcast/decisions)

(defn liftingcast-decisions->liftingcast-result [decisions]
  (let [decision->count (frequencies (map :decision (vals decisions)))
        num-good-decisions (get decision->count "good" 0)]
    (if (<= 2 num-good-decisions)
      "good"
      "bad")))

(s/fdef liftingcast-decisions->liftingcast-result
  :args (s/cat :liftingcast-decisions :liftingcast/decisions)
  :ret :liftingcast/result)

(defn get-referee [in-memory-db position]
  (in-memory-db (position->referee-id (keyword position))))

(s/fdef get-referee
  :args (s/cat :in-memory-db ::in-memory-db
               :position :liftingcast/position)
  :ret :liftingcast/referee)

(s/def :liftingcast/session pos-int?)
(s/def :liftingcast/flight (s/and string?
                                  #(-> % count (= 1))))
(s/def :liftingcast/lot nat-int?)

;; I'm only reading lifters so I'm only going to spec the attributes relevant to
;; the lifting order.
(s/def :liftingcast/lifter
  (s/merge :liftingcast/document
           (s/keys :req-un [:liftingcast.lifter/_id
                            :liftingcast/platformId
                            :liftingcast/session
                            :liftingcast/flight
                            :liftingcast/lot])))

(defn lifter-attempts-on-platform [in-memory-db platform-id]
  (for [lifter-id (lifter-ids-on-platform in-memory-db platform-id)
        attempt-id (attempt-ids-for-lifter lifter-id)]
    [(in-memory-db lifter-id) (in-memory-db attempt-id)]))

(s/fdef lifter-attempts-on-platform
  :args (s/cat :in-memory-db ::in-memory-db
               :platform-id :liftingcast.platform/_id)
  :ret (s/every (s/tuple :liftingcast/lifter :liftingcast/attempt)))

(defn lifting-order [in-memory-db platform-id]
  (let [lifter-attempts (lifter-attempts-on-platform in-memory-db platform-id)
        lifting-order-entries (for [[l a] lifter-attempts
                                    :when (and (:weight a)
                                               (pos? (:weight a))
                                               (nil? (:result a)))]
                                {:attempt-id (:_id a)
                                 :session (:session l)
                                 :lift-order (case (:liftName a)
                                               "squat" 0
                                               "bench" 1
                                               "dead" 2)
                                 :flight (:flight l)
                                 :attempt-number (:attemptNumber a)
                                 :end-of-round (:endOfRound a)
                                 :weight (:weight a)
                                 :lot (:lot l)})]
    (->> lifting-order-entries
         (sort-by (juxt :session
                        :lift-order
                        :flight
                        :attempt-number
                        :end-of-round
                        :weight
                        :lot))
         (map :attempt-id))))

(s/fdef lifting-order
  :args (s/cat :in-memory-db ::in-memory-db
               :platform-id :liftingcast.platform/_id)
  :ret (s/every :liftingcast.attempt/_id))

(defn select-next-attempt-id [in-memory-db platform-id]
  (let [current-attempt-id (get-in in-memory-db [platform-id :currentAttemptId])
        [a0 a1] (lifting-order in-memory-db platform-id)]
    ;; If the user has not changed the current attempt, then we select the
    ;; next attempt in the order.
    (if (= current-attempt-id a0) a1 a0)))

(s/fdef select-next-attempt-id
  :args (s/cat :in-memory-db ::in-memory-db
               :platform-id :liftingcast.platform/_id)
  :ret (s/nilable :liftingcast.attempt/_id))

(defn update-document [doc m changes-spec]
  (let [num-changes-to-keep 100 ; See https://liftingcast.com/static/js/util/pouchActions.js #updateAttributesOnDocument
        timestamp (str (java-time/instant))
        new-changes (mapv (fn [[k v]]
                            {:rev (:_rev doc)
                             :timeStamp timestamp
                             :attribute (name k)
                             :value v})
                          m)]
    (if-not (s/valid? changes-spec new-changes)
      (do
        (println "INVALID CHANGES:")
        (println (s/explain changes-spec new-changes)))
      (update (merge doc m) :changes
              (fn [old new]
                (->> new
                     (into old)
                     (take-last num-changes-to-keep)))
              new-changes))))

(defn update-referee-document [referee m]
  (update-document referee m :liftingcast.referee/changes))

(s/fdef update-referee-document
  :args (s/cat :referee :liftingcast/referee
               :m (s/keys :opt-un [:liftingcast/cards
                                   :liftingcast/decision]))
  :ret :liftingcast/referee)

(defn update-attempt-document [attempt m]
  (update-document attempt m :liftingcast.attempt/changes))

(s/fdef update-attempt-document
  :args (s/cat :attempt :liftingcast/attempt
               :m (s/keys :opt-un [:liftingcast/result
                                   :liftingcast/decisions]))
  :ret :liftingcast/attempt)

(defn update-platform-document [platform m]
  (update-document platform m :liftingcast.platform/changes))

(s/fdef update-platform-document
  :args (s/cat :platform :liftingcast/platform
               :m (s/keys :opt-un [:liftingcast/currentAttemptId
                                   :liftingcast/clockState]))
  :ret :liftingcast/platform)



(set-current-attempt-id! db platform-id)

(def ca (couch/change-agent db))

(add-watch ca :current-attempt-id-on-platform
           (fn [key agent previous-change change]
             (cond
               (= platform-id (:id change))
               (do
                 (println "Change to this platform doc\n"
                          (with-out-str (clojure.pprint/pprint change))
                          "\n")
                 (set-current-attempt-id! db platform-id))

               (s/valid? :liftingcast.referee/_id (:id change))
               (println "Change to referee doc\n"
                        (with-out-str (clojure.pprint/pprint change))
                        "\n")

               (s/valid? :liftingcast.attempt/_id (:id change))
               (println "Change to attempt doc\n"
                        (with-out-str (clojure.pprint/pprint change))
                        "\n")

               :else
               (println "other change\n"
                        (with-out-str (clojure.pprint/pprint change))
                        "\n"))))

(couch/start-changes ca)

(def turn-off-lights-chan (async/chan))
(def liftingcast-lights-on-duration-ms 5000)

(async/go-loop []
  (<! turn-off-lights-chan)
  (let [all-docs (get-all-docs db)
        in-memory-db (index-docs-by-id all-docs)
        [rleft rhead rright] (map get-document (vals position->referee-id))
        reset-data {:cards nil :decision nil}]
    (couch/bulk-update
     db
     [(update-referee-document rleft reset-data)
      (update-referee-document rhead reset-data)
      (update-referee-document rright reset-data)]))
  (recur))

(defn judge-attempt-and-select-next-lifter [drl-decision]
  (let [decisions (drl-decision->liftingcast-decisions drl-decision)
        result (liftingcast-decisions->liftingcast-result decisions)
        in-memory-db (-> db get-all-docs index-docs-by-id)
        left-referee (get-referee in-memory-db "left")
        head-referee (get-referee in-memory-db "head")
        right-referee (get-referee in-memory-db "right")
        current-attempt (in-memory-db @current-attempt-id)
        next-attempt-id (select-next-attempt-id in-memory-db platform-id)]
    (couch/bulk-update
     db
     [(update-referee-document left-referee (:left decisions))
      (update-referee-document head-referee (:head decisions))
      (update-referee-document right-referee (:right decisions))
      (update-attempt-document current-attempt {:result result
                                                :decisions decisions})
      (update-platform-document (in-memory-db platform-id) {:currentAttemptId next-attempt-id
                                                            :clockState "initial"})])
    (async/go
      (<! (async/timeout liftingcast-lights-on-duration-ms))
      (>! turn-off-lights-chan :_))))

(s/fdef judge-attempt-and-select-next-lifter
  :args (s/cat :drl-decision :drl/decision))

(defn start-timer []
  (couch/put-document
   db
   (update-platform-document (get-document platform-id) {:clockState "started"})))

(defn reset-timer []
  (couch/put-document
   db
   (update-platform-document (get-document platform-id) {:clockState "initial"})))

(defn get-input []
  (edn/read-string (read-line)))

(defn go []
  (loop [[f & args] (get-input)]
    (println "f:" f)
    (println "args:" args)
    (case f
      "judge-attempt-and-select-next-lifter"
      (judge-attempt-and-select-next-lifter (first args))

      "start-timer"
      (start-timer)

      "reset-timer"
      (reset-timer)

      (println "No match for" f))
    (recur (get-input))))



(comment
  (stest/instrument)
  )
