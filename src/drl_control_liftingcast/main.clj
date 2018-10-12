(ns drl-control-liftingcast.main
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.core.async :as async :refer [<! >!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [com.ashafa.clutch :as couch]
            [expound.alpha :as expound]
            [java-time]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [taoensso.timbre :as timbre
             :refer [log trace debug info warn error fatal report]]))

;; Set s/*explain-out* to expound/printer on all threads.
(set! s/*explain-out* expound/printer)
(alter-var-root #'s/*explain-out* (constantly expound/printer))

(def current-attempt-id (atom nil))

(defn get-all-docs [db]
  (map :doc
       (couch/all-documents db {:include_docs true})))

(s/def :couchdb/db map?)

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

(s/def :liftingcast.attempt/_id
  (s/and :liftingcast/_id
         #(string/starts-with? % "a")))

(s/fdef attempt-ids-for-lifter
  :args (s/cat :lifter-id :liftingcast.lifter/_id)
  :ret (s/coll-of :liftingcast.attempt/_id
                  :count 9
                  :distinct true))

(defn fetch-current-attempt-id [db platform-id]
  (->> platform-id (couch/get-document db) :currentAttemptId))

(s/fdef fetch-current-attempt-id
  :args (s/cat :db :couchdb/db
               :platform-id :liftingcast.platform/_id)
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


(def referee-positions #{"left" "head" "right"})

(s/def :liftingcast/position referee-positions)

(s/def :liftingcast.referee/_id
  (s/and :liftingcast/_id
         #(string/starts-with? % "r")))

(defn referee-id [platform-id position]
  (str "r" (name position) "-" platform-id))

(s/fdef referee-id
  :args (s/cat :platform-id :liftingcast.platform/_id
               :position :liftingcast/position)
  :ret :liftingcast.referee/_id)

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
        (warn "INVALID CHANGES:")
        (warn (expound/expound new-changes-spec new-changes)))
      (update (merge doc m) :changes
              (fn [old new]
                (->> new
                     (into old)
                     (take-last liftingcast-document-changes-max-count)))
              new-changes))))

(defn start-timer [db platform clock-timer-length]
  ;; TODO: retry this on error
  (couch/put-document
   db
   (update-document platform
                    {:clockState "started" :clockTimerLength clock-timer-length}
                    :liftingcast.platform/changes)))

(s/fdef start-timer
  :args (s/cat :db :couchdb/db
               :platform :liftingcast/platform
               :clock-timer-length pos-int?))

(defn reset-timer [db platform clock-timer-length]
  ;; TODO: retry this on error
  (couch/put-document
   db
   (update-document platform
                    {:clockState "initial" :clockTimerLength clock-timer-length}
                    :liftingcast.platform/changes)))

(s/fdef reset-timer
  :args (s/cat :db :couchdb/db
               :platform :liftingcast/platform
               :clock-timer-length pos-int?))

(defn input-handler [db platform-id lights-duration-ms next-attempt-chan input]
  (debug "Input:\n" (with-out-str (pprint input)))
  (case (:statusType input)
    "LIGHTS"
    (let [decisions {:left (:refLeft input)
                     :head (:refHead input)
                     :right (:refRight input)}
          left-referee (couch/get-document db (referee-id platform-id "left"))
          head-referee (couch/get-document db (referee-id platform-id "head"))
          right-referee (couch/get-document db (referee-id platform-id "right"))
          turn-on-liftingcast-lights? (pos? lights-duration-ms)]
      ;; TODO: retry this on error
      (when turn-on-liftingcast-lights?
        (couch/bulk-update
         db
         [(update-document left-referee (:left decisions) :liftingcast.referee/changes)
          (update-document head-referee (:head decisions) :liftingcast.referee/changes)
          (update-document right-referee (:right decisions) :liftingcast.referee/changes)]))

      (async/go
        (when turn-on-liftingcast-lights?
          (<! (async/timeout lights-duration-ms)))
        (>! next-attempt-chan decisions)))

    "CLOCK"
    (case (:clockState input)
      "STARTED"
      (let [platform (couch/get-document db platform-id)]
        (start-timer db platform (:timerLength input)))

      "RESET"
      (let [platform (couch/get-document db platform-id)]
        (reset-timer db platform (:timerLength input)))

      (debug "UNRECOGNIZED CLOCK STATE IN INPUT:" (with-out-str (pprint input))))

    (debug "UNRECOGNIZED STATUS TYPE IN INPUT:" (with-out-str (pprint input)))))

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

(s/fdef input-handler
  :args (s/cat :db :couchdb/db
               :platform-id :liftingcast.platform/_id
               :lights-duration-ms (s/int-in 0 10001)
               :next-attempt-chan any?
               :input :drl/output))

(defn liftingcast-decisions->liftingcast-result [decisions]
  (let [decision->count (frequencies (map :decision (vals decisions)))
        num-good-decisions (get decision->count "good" 0)]
    (if (<= 2 num-good-decisions)
      "good"
      "bad")))

(s/fdef liftingcast-decisions->liftingcast-result
  :args (s/cat :liftingcast-decisions :liftingcast/decisions)
  :ret :liftingcast/result)

(defn mark-attempt [db attempt decisions]
  ;; TODO: retry this on error
  (couch/put-document
   db
   (update-document attempt
                    {:result (liftingcast-decisions->liftingcast-result decisions)
                     :decisions decisions}
                    :liftingcast.attempt/changes)))

(s/fdef mark-attempt
  :args (s/cat :db :couchdb/db
               :attempt :liftingcast/attempt
               :decisions :liftingcast/decisions))

(defn turn-off-lights [db left-referee head-referee right-referee]
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
  :args (s/cat :db :couchdb/db
               :left-referee :liftingcast/referee
               :head-referee :liftingcast/referee
               :right-referee :liftingcast/referee))

(defn select-attempt-and-reset-clock [db platform attempt-id]
  ;; TODO: retry this on error
  (couch/put-document
   db
   (update-document platform
                    {:currentAttemptId attempt-id :clockState "initial"}
                    :liftingcast.platform/changes)))

(s/fdef select-attempt-and-reset-clock
  :args (s/cat :db :couchdb/db
               :platform :liftingcast/platform
               :attempt-id :liftingcast.attempt/_id))



(defn parse-meet-data [file day-number platform-number]
  (debug "Parsing" (.getAbsolutePath file) "for meet credentials and platform id.")
  (let [meet-data (-> file slurp json/parse-string)
        d (str day-number)
        p (str platform-number)]
    {:meet-id (get-in meet-data ["credentials" d "meetId"])
     :password (get-in meet-data ["credentials" d "password"])
     :platform-id (get-in meet-data ["platformIds" d p])}))

(s/def ::meet-id (s/and string?
                        #(string/starts-with? % "m")))
(s/def ::password (s/and string?
                         #(-> % count pos?)))
(s/def ::platform-id :liftingcast.platform/_id)

(s/fdef parse-meet-data
  :args (s/cat :file (fn [f]
                       (try
                         (.exists f)

                         (catch Exception e
                           false)))
               :day-number (s/int-in 1 5)
               :platform-number (s/int-in 1 5))
  :ret (s/keys :req-un [::meet-id ::password ::platform-id]))

(defn init [day-number platform-number lights-duration-ms]
  (let [port 3000
        meet-data (io/file "liftingcast-creds-and-platform-ids.json")
        {:keys [meet-id password platform-id]} (parse-meet-data meet-data
                                                                day-number
                                                                platform-number)]
    (assert (s/valid? ::meet-id meet-id)
            (str meet-id " is not a valid meet ID; Liftingcast meet IDs are double-quoted strings that start with the letter \"m\".
Get the meet ID from the URL for the meet page on Liftingcast and correct the entry in " (.getAbsolutePath meet-data) "\n\n"))
    (assert (s/valid? ::password password)
            (if (and (string? password)
                     (zero? (count password)))
              (str "There is no password for day " day-number
                   " in " (.getAbsolutePath meet-data)
                   "\nEdit the file and run this program again.\n\n")
              (str password " is not a valid password. The password must be a double-quoted string in " (.getAbsolutePath meet-data)
                   "\nEdit the file and run this program again.\n\n")))
    (assert (s/valid? ::platform-id platform-id)
            (str meet-id " is not a valid platform ID; Liftingcast platform IDs are double-quoted strings that start with the letter \"p\".
Get the platform ID from the URL for the meet page for platform " platform-number " on Liftingcast and correct the entry in " (.getAbsolutePath meet-data) "\n\n"))
    (println (str "\n\n\n\nGood morning, Scott. It's day #" day-number ".\n\n"))
    (println "Meet ID:             " meet-id)
    (println "Password:            " password)
    (println "Platform ID:         " platform-id)
    (println "Webserver port:      " port)
    (println "Lights duration (ms):" lights-duration-ms)
    (println "")
    (info "\nThe webserver output and liftingcast database changes feed will display below.\n\n")

    (let [db (-> (url/url "http://couchdb.liftingcast.com" meet-id)
                 (assoc :username meet-id
                        :password password)
                 couch/get-database)
          input-chan (async/chan 10)
          next-attempt-chan (async/chan)]
      (set-current-attempt-id! db platform-id)

      (def ca (couch/change-agent db))

      (add-watch ca :current-attempt-id-on-platform
                 (fn [key agent previous-change change]
                   (cond
                     (= platform-id (:id change))
                     (do
                       (debug "Change to this platform doc\n"
                              (with-out-str (pprint change))
                              "\n")
                       (set-current-attempt-id! db platform-id))

                     (s/valid? :liftingcast.referee/_id (:id change))
                     (debug "Change to referee doc\n"
                            (with-out-str (pprint change))
                            "\n")

                     (s/valid? :liftingcast.attempt/_id (:id change))
                     (debug "Change to attempt doc\n"
                            (with-out-str (pprint change))
                            "\n")

                     :else
                     (debug "other change\n"
                            (with-out-str (pprint change))
                            "\n"))))

      (couch/start-changes ca)
      (debug "Connected to Liftingcast database and monitoring the current attempt on the platform.")

      (async/thread
        (jetty/run-jetty
         (-> (fn [request]
               (let [input (:params request)]
                 (debug (str "Received input from DRL:\n" (with-out-str (pprint input)) "\n\n"))
                 (async/put! input-chan input))
               {:status 202 :headers {"Content-Type" "text/plain"}})
             wrap-keyword-params
             wrap-json-params)
         {:port port}))
      (debug "The webserver is listening for input (via HTTP requests) from DRL on port" port)

      (async/go-loop []
        (<! (async/timeout 1000))
        (try
          (input-handler db
                         platform-id
                         lights-duration-ms
                         next-attempt-chan
                         (<! input-chan))

          (catch Exception e
            (warn "\n\n\n\nException in next-attempt-chan-reader handler:")
            (warn e)
            (warn "\n\n\n\n")))
        (recur))

      (async/go-loop []
        (try
          (let [decisions (<! next-attempt-chan)
                in-memory-db (-> db get-all-docs index-docs-by-id)
                current-attempt (in-memory-db @current-attempt-id)
                left-referee (in-memory-db (referee-id platform-id "left"))
                head-referee (in-memory-db (referee-id platform-id "head"))
                right-referee (in-memory-db (referee-id platform-id "right"))
                platform (in-memory-db platform-id)
                next-attempt-id (select-next-attempt-id in-memory-db platform-id)]
            (mark-attempt db current-attempt decisions)
            (turn-off-lights db left-referee head-referee right-referee)
            (select-attempt-and-reset-clock db platform next-attempt-id))

          (catch Exception e
            (warn "\n\n\n\nException in next-attempt-chan-reader handler:")
            (warn e)
            (warn "\n\n\n\n")))
        (recur)))))

(s/fdef init
  :args (s/cat :day-number (s/int-in 1 5)
               :platform-number (s/int-in 1 5)
               :lights-duration-ms (s/int-in 0 10001)))



(def cli-options
  [["-d" "--day-number DAY-NUMBER"
    "The day number of the meet: 1 for Thursday, 2 for Friday, 3 for Saturday, 4 for Sunday."
    :parse-fn edn/read-string
    :validate [#(s/int-in-range? 1 5 %)
               "Must be 1 for Thursday, 2 for Friday, 3 for Saturday, or 4 for Sunday."]]
   ["-p" "--platform-number PLATFORM-NUMBER"
    "The number of the platform, as entered into Liftingcast."
    :parse-fn edn/read-string
    :validate [#(s/int-in-range? 1 5 %)
               "Must be 1, 2, 3, or 4."]]
   ["-l" "--lights-duration-ms LIGHTS-DURATION-MS" "Duration in MILLISECONDS to show the lights. Default 5000, min 0, max 10000."
    :default 5000
    :parse-fn edn/read-string
    :validate [#(s/int-in-range? 0 10001 %)
               "Must be an integer between 0 and 10000."]]
   ["-v" "--verbose VERBOSE"
    "Whether to print to the console the Liftingcast database changes feed and the inputs from DRL. Default false."
    :default false
    :parse-fn edn/read-string
    :validate [boolean?
               "Must be true or false."]]
   ["-h" "--help"
    "Prints a description of the program and its usage."]])

(defn usage [options-summary]
  (str
   "
This program relays input from DRL (clock commands, referee lights) to
Liftingcast.

It listens for HTTP requests from DRL on port 3000.
It requires an internet connection to send data to Liftingcast.

The program reads in the liftingcast credentials and platform id from a JSON
file called `<project root>/liftingcast-creds-and-platform-ids`.


PROGRAM USAGE
=============
Specify the day number and platform id where the program will be running.
Optionally, specify the duration in MILLISECONDS to show the Liftingcast lights
and whether to print events to the console:\n\n"
   options-summary))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join "\n" errors)))

(defn validate-args [args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        args-set (set args)]
    (if (:help options)
      {:exit-msg (usage summary) :exit-ok? true}
      (let [all-errors (cond-> errors
                         (empty? (set/intersection #{"-d" "--day-number"} args-set))
                         (conj "-d or --day-number must be provided as: 1 for Thursday, 2 for Friday, 3 for Saturday, or 4 for Sunday.")

                         (empty? (set/intersection #{"-p" "--platform-number"} args-set))
                         (conj "-p or --platform-number must be provided as: 1, 2, 3, or 4."))]
        (if all-errors
          {:exit-msg (error-msg all-errors)}
          (select-keys options [:day-number
                                :platform-number
                                :lights-duration-ms
                                :verbose]))))))

(defn exit [status msg]
  (println msg)
  (System/exit status))



(defn -main [& args]
  (let [{:keys [exit-msg exit-ok? day-number platform-number lights-duration-ms verbose]}
        (validate-args args)]
    (if exit-msg
      (exit (if exit-ok? 0 1) exit-msg)
      (do
        (when-not verbose (timbre/set-level! :info))
        (init day-number platform-number lights-duration-ms)))))



(stest/instrument)
