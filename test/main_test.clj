(ns main-test
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
            [drl-control-liftingcast.main :refer :all]
            [kaocha.repl :as run]))

(def possible-decisions-from-referee
  #{[true  false false false]
    [false false false true]
    [false false true  false]
    [false false true  true]
    [false true  false false]
    [false true  false true]
    [false true  true  false]
    [false true  true  true]})

(run/run
  (deftest valid-decision-from-referee
    (let [xs (map (fn [d] [(valid-decision-from-referee? d) d])
                  possible-decisions-from-referee)
          all-valid (map vector (repeat true) possible-decisions-from-referee)]
      (is (empty? (set/difference
                   (set all-valid)
                   (set xs)))))))
