(ns drl-control-liftingcast.main
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.core.async :as async :refer [<! >!]]
            [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as string]
            [com.ashafa.clutch :as couch]
            [expound.alpha :as expound]
            [java-time]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]))

;; Set s/*explain-out* to expound/printer on all threads.
(set! s/*explain-out* expound/printer)
(alter-var-root #'s/*explain-out* (constantly expound/printer))

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

(s/def :liftingcast/_rev string?) ; used in top level of docs
(s/def :liftingcast/rev :liftingcast/_rev) ; used in changes
;; liftingcast/timeStamp could be more specific but I only care that it is
;; present and doesn't change.
(s/def :liftingcast/timeStamp string?)
(s/def :liftingcast/createDate :liftingcast/timeStamp)

(s/def :liftingcast/attribute string?)


(defn valid-decision-from-referee? [{:keys [decision cards]}]
  (cond
    (= "good" decision)
    (every? false? (vals cards))

    (= "bad" decision)
    (pos? (count (filter true? (vals cards))))

    :else
    false))

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
  (s/and (s/keys :req-un [:liftingcast/decision
                          :liftingcast/cards])
         valid-decision-from-referee?))
(s/def :liftingcast/left :liftingcast.decisions/decision)
(s/def :liftingcast/head :liftingcast.decisions/decision)
(s/def :liftingcast/right :liftingcast.decisions/decision)
(s/def :liftingcast/decisions
  (s/keys :req-un [:liftingcast/left
                   :liftingcast/head
                   :liftingcast/right]))

(s/def :liftingcast/document
  (s/keys :req-un [:liftingcast/_rev
                   :liftingcast/createDate]))

(s/def :liftingcast/change
  (s/keys :req-un [:liftingcast/rev
                   :liftingcast/attribute
                   :liftingcast/timeStamp]))

; See https://liftingcast.com/static/js/util/pouchActions.js #updateAttributesOnDocument
(def liftingcast-document-changes-max-count 100)

(s/def :liftingcast/changes
  (s/every (s/merge :liftingcast/change
                    (s/keys :req-un [:liftingcast/value]))
           :kind vector?
           :max-count liftingcast-document-changes-max-count))



(s/def :liftingcast.referee/_id
  (-> position->referee-id vals set))

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
           :max-count liftingcast-document-changes-max-count))

(s/def :liftingcast/platformId :liftingcast.platform/_id)

(s/def :liftingcast/position referee-positions)

(s/def :liftingcast/referee
  (s/merge :liftingcast/document
           (s/keys :req-un [:liftingcast.referee/_id
                            :liftingcast/changes
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
           :max-count liftingcast-document-changes-max-count))

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
                            :liftingcast/changes
                            :liftingcast/liftName
                            :liftingcast/attemptNumber
                            :liftingcast/lifterId]
                   :opt-un [:liftingcast/weight
                            :liftingcast/result
                            :liftingcast/decisions
                            :liftingcast/endOfRound])))



(s/def :liftingcast/clock-state #{"initial" "started"})

(s/def :liftingcast/clock-timer-length nat-int?)

(defmulti platform-change :attribute)

(s/def :liftingcast.platform.change.current-attempt-id/value :liftingcast/_id)
(defmethod platform-change "currentAttemptId" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.platform.change.current-attempt-id/value])))

(s/def :liftingcast.platform.change.clock-state/value :liftingcast/clock-state)
(defmethod platform-change "clockState" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.platform.change.clock-state/value])))

(s/def :liftingcast.platform.change.clock-timer-length/value
  :liftingcast/clock-timer-length)
(defmethod platform-change "clockTimerLength" [_]
  (s/merge :liftingcast/change
           (s/keys :req-un [:liftingcast.platform.change.clock-timer-length/value])))

(s/def :liftingcast.platform/changes
  (s/every (s/multi-spec platform-change :attribute)
           :kind vector?
           :max-count liftingcast-document-changes-max-count))

(s/def :liftingcast/name string?)
(s/def :liftingcast/barAndCollarsWeight pos-int?)
(s/def :liftingcast/clockTimerLength :liftingcast/clock-timer-length)
(s/def :liftingcast/clockState :liftingcast/clock-state)
(s/def :liftingcast/currentAttemptId :liftingcast.attempt/_id)

(s/def :liftingcast/platform
  (s/merge :liftingcast/document
           (s/keys :req-un [:liftingcast.platform/_id
                            :liftingcast/changes
                            :liftingcast/name
                            :liftingcast/barAndCollarsWeight
                            :liftingcast/clockTimerLength
                            :liftingcast/clockState
                            :liftingcast/currentAttemptId])))

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

(defn update-document [doc m new-changes-spec]
  (let [timestamp (str (java-time/instant))
        new-changes (mapv (fn [[k v]]
                            {:rev (:_rev doc)
                             :timeStamp timestamp
                             :attribute (name k)
                             :value v})
                          m)]
    (if-not (s/valid? new-changes-spec new-changes)
      (do
        (println "INVALID CHANGES:")
        (println (expound/expound new-changes-spec new-changes)))
      (update (merge doc m) :changes
              (fn [old new]
                (->> new
                     (into old)
                     (take-last liftingcast-document-changes-max-count)))
              new-changes))))

(set-current-attempt-id! db platform-id)

(def ca (couch/change-agent db))

(add-watch ca :current-attempt-id-on-platform
           (fn [key agent previous-change change]
             (cond
               (= platform-id (:id change))
               (do
                 (println "Change to this platform doc\n"
                          (with-out-str (pprint change))
                          "\n")
                 (set-current-attempt-id! db platform-id))

               (s/valid? :liftingcast.referee/_id (:id change))
               (println "Change to referee doc\n"
                        (with-out-str (pprint change))
                        "\n")

               (s/valid? :liftingcast.attempt/_id (:id change))
               (println "Change to attempt doc\n"
                        (with-out-str (pprint change))
                        "\n")

               :else
               (println "other change\n"
                        (with-out-str (pprint change))
                        "\n"))))

(couch/start-changes ca)

(defn start-timer [clock-timer-length]
  (couch/put-document
   db
   (update-document (get-document platform-id)
                    {:clockState "started" :clockTimerLength clock-timer-length}
                    :liftingcast.platform/changes)))

(s/fdef start-timer
  :args (s/cat :clock-timer-length pos-int?))

(defn reset-timer [clock-timer-length]
  (couch/put-document
   db
   (update-document (get-document platform-id)
                    {:clockState "initial" :clockTimerLength clock-timer-length}
                    :liftingcast.platform/changes)))

(s/fdef reset-timer
  :args (s/cat :clock-timer-length pos-int?))

(s/def :drl/refLeft :liftingcast.decisions/decision)
(s/def :drl/refHead :liftingcast.decisions/decision)
(s/def :drl/refRight :liftingcast.decisions/decision)

(s/def :drl/clockState #{"STARTED" "RESET"})
(s/def :drl/timerLength :liftingcast/clock-timer-length)

(defmulti drl-output :statusType)
(defmethod drl-output "LIGHTS" [_]
  (s/keys :req-un [:drl/refLeft
                   :drl/refHead
                   :drl/refRight]))
(defmethod drl-output "CLOCK" [_]
  (s/keys :req-un [:drl/clockState
                   :drl/timerLength]))
(s/def :drl/output (s/multi-spec drl-output :statusType))

(defn make-input-handler [liftingcast-lights-on-duration-ms next-attempt-chan]
  (fn [input]
    {:pre [(s/valid? :drl/output input)]}
    (println "Input:")
    (pprint input)
    (case (:statusType input)
      "LIGHTS"
      (let [decisions {:left (:refLeft input)
                       :head (:refHead input)
                       :right (:refRight input)}
            left-referee (get-document (:left position->referee-id))
            head-referee (get-document (:head position->referee-id))
            right-referee (get-document (:right position->referee-id))]
        (couch/bulk-update
         db
         [(update-document left-referee (:left decisions) :liftingcast.referee/changes)
          (update-document head-referee (:head decisions) :liftingcast.referee/changes)
          (update-document right-referee (:right decisions) :liftingcast.referee/changes)])

        (async/go
          (<! (async/timeout liftingcast-lights-on-duration-ms))
          (>! next-attempt-chan decisions)))

      "CLOCK"
      (case (:clockState input)
        "STARTED"
        (start-timer (:timerLength input))

        "RESET"
        (reset-timer (:timerLength input))

        (println "UNRECOGNIZED CLOCK STATE IN INPUT:"
                 (with-out-str (pprint input))))

      (println "UNRECOGNIZED STATUS TYPE IN INPUT:"
               (with-out-str (pprint input))))))

(defn mark-attempt [attempt decisions]
  ;; TODO: retry this on error
  (couch/put-document
   db
   (update-document attempt
                    {:result (liftingcast-decisions->liftingcast-result decisions)
                     :decisions decisions}
                    :liftingcast.attempt/changes)))

(s/fdef mark-attempt
  :args (s/cat :attempt :liftingcast/attempt
               :decisions :liftingcast/decisions))

(defn turn-off-lights [left-referee head-referee right-referee]
  (let [referee-reset-data {:cards nil :decision nil}]
    ;; TODO: retry this on error
   (couch/bulk-update
    db
    [(update-document left-referee
                      referee-reset-data
                      :liftingcast.referee/changes)
     (update-document head-referee
                      referee-reset-data
                      :liftingcast.referee/changes)
     (update-document right-referee
                      referee-reset-data
                      :liftingcast.referee/changes)])))

(s/fdef turn-off-lights
  :args (s/cat :left-referee :liftingcast/referee
               :head-referee :liftingcast/referee
               :right-referee :liftingcast/referee))

(defn select-attempt-and-reset-clock [platform attempt-id]
  ;; TODO: retry this on error
  (couch/put-document
   db
   (update-document platform
                    {:currentAttemptId attempt-id :clockState "initial"}
                    :liftingcast.platform/changes)))

(s/fdef select-attempt-and-reset-clock
  :args (s/cat :platform :liftingcast/platform
               :attempt-id :liftingcast.attempt/_id))

(defn -main [& args]
  (let [input-chan (async/chan 10)
        liftingcast-lights-on-duration-ms 5000
        next-attempt-chan (async/chan)
        input-handler (make-input-handler liftingcast-lights-on-duration-ms
                                          next-attempt-chan)]
    (async/thread
      (jetty/run-jetty
       (-> (fn [request]
             (async/put! input-chan (:params request))
             {:status 202 :headers {"Content-Type" "text/plain"}})
           wrap-keyword-params
           wrap-json-params)
       {:port 3000}))

    (async/go-loop []
      (<! (async/timeout 1000))
      (input-handler (<! input-chan))
      (recur))

    (async/go-loop []
      (try
        (let [decisions (<! next-attempt-chan)
              in-memory-db (-> db get-all-docs index-docs-by-id)]
          (mark-attempt (in-memory-db @current-attempt-id) decisions)
          (turn-off-lights (get-referee in-memory-db "left")
                           (get-referee in-memory-db "head")
                           (get-referee in-memory-db "right"))
          (select-attempt-and-reset-clock
           (in-memory-db platform-id)
           (select-next-attempt-id in-memory-db platform-id)))

        (catch Exception e
          (println "\n\n\n\nException in next-attempt-chan-reader handler:")
          (println e)
          (print "\n\n\n\n")))
      (recur))))



(stest/instrument)
