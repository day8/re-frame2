(ns re-frame.region-slot-refusal-test
  "Registration refuses a parallel REGION-BODY key no runtime consumer reads
  there with `:rf.error/machine-root-slot-not-supported`, naming the region
  under `:path`, per Spec 005 §State nodes (the machine root); a region body's
  `:choice` and a typo'd key keep their own refusals."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

(defn- refusal
  "The ex-data of the registration refusal for `machine`, or nil."
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- with-region-key
  "A two-region parallel machine whose region `:x` body declares `k` `v`."
  [k v]
  {:type    :parallel
   :regions {:x {:initial :x1 k v :states {:x1 {} :x2 {}}}
             :y {:initial :y1 :states {:y1 {}}}}})

(deftest region-body-refuses-each-unread-key
  ;; :spawn and :regions are honoured on a parallel machine root, never on a region body.
  (doseq [[k v] {:spawn   {:machine-id :rs/worker}
                 :regions {:inner {:initial :z :states {:z {}}}}}]
    (is (= {:rf.error/id    :rf.error/machine-root-slot-not-supported
            :offending-keys [k]
            :path           [:regions :x]}
           (select-keys (refusal (with-region-key k v)) [:rf.error/id :offending-keys :path]))
        (str "region body " k))))

(deftest other-region-shapes-keep-their-own-refusal
  (is (= [:rf.error/machine-choice-without-type :rf.error/machine-unknown-node-key]
         (map #(:rf.error/id (refusal (apply with-region-key %)))
              [[:choice [{:target :x1}]] [:invoke {:machine-id :rs/worker}]]))))
