(ns day8.re-frame2-causa.export.cascade-cljs-test
  "Pure-data tests for per-cascade structured export (rf2-0us27,
  v1.1).

  Covers:

    1. Schema envelope — version + stable key set surfaces every
       documented slot even when the cascade is nil / empty.
    2. Domino projection — dispatched / handler / fx / effects / subs
       / renders / other carry through verbatim.
    3. Coeffects hoist — pulled out of the handler trace event onto
       the top-level `:coeffects` slot.
    4. Timing envelope — started/ended/duration computed from trace
       event `:time` slots; nil-times case yields nil bounds + a
       non-nil event-count.
    5. App-DB block — `:before` / `:after` / `:diff` populated from
       the supplied epoch record; diff covers added / removed /
       modified at the top map level.
    6. Issues hoist — `:error` / `:warning` op-types in `:other`
       surface as a flat `:issues` vector.
    7. `to-edn-string` round-trips via `pr-str`.
    8. `suggested-filename` sanitises the dispatch-id + timestamp.

  CLJC — `cascade/project-cascade` is JVM-runnable, so the test runs
  under both `clojure -M:test` and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [day8.re-frame2-causa.export.cascade :as export]))

(defn- read-edn [s]
  #?(:clj (edn/read-string s)
     :cljs (reader/read-string s)))

;; ---- fixtures -----------------------------------------------------------

(def sample-dispatched
  {:op-type :event :operation :event/dispatched
   :id 10 :time 100
   :tags {:event [:cart/add-item :sku-1]
          :dispatch-id 7 :frame :rf/default}})

(def sample-handler
  {:op-type :event :operation :event/run-end
   :id 11 :time 110
   :coeffects {:event [:cart/add-item :sku-1]
               :db {:cart {:items []}}}
   :tags {:event-id :cart/add-item :handler-id :cart/add-item-handler}})

(def sample-fx
  {:op-type :event :operation :event/do-fx
   :id 12 :time 115
   :tags {:dispatch-id 7}})

(def sample-effect
  {:op-type :fx :operation :db
   :id 13 :time 116
   :tags {:fx-id :db :dispatch-id 7}})

(def sample-sub
  {:op-type :sub :operation :sub/run
   :id 14 :time 120
   :tags {:query [:cart/total] :dispatch-id 7}})

(def sample-render
  {:op-type :view :operation :view/render
   :id 15 :time 122
   :tags {:component :cart/Item :dispatch-id 7}})

(def sample-error
  {:op-type :error :operation :handler/threw
   :id 16 :time 125
   :message "boom"
   :tags {:dispatch-id 7}})

(def sample-cascade
  {:dispatch-id 7
   :frame       :rf/default
   :event       [:cart/add-item :sku-1]
   :dispatched  sample-dispatched
   :handler     sample-handler
   :fx          sample-fx
   :effects     [sample-effect]
   :subs        [sample-sub]
   :renders     [sample-render]
   :other       [sample-error]})

(def sample-epoch
  {:epoch-id  :epoch-42
   :db-before {:cart {:items []}}
   :db-after  {:cart {:items [:sku-1]}
               :ui   {:flash "Added"}}
   :trace-events [sample-dispatched sample-handler sample-fx
                  sample-effect sample-sub sample-render]})

;; ---- 1. schema envelope -------------------------------------------------

(deftest schema-version-present
  (let [out (export/project-cascade nil)]
    (is (= 1 (:rf.causa.export/version out))
        "version is always present")))

(deftest nil-cascade-still-produces-stable-key-set
  (let [out (export/project-cascade nil)
        expected-keys #{:rf.causa.export/version
                        :exported-at :epoch-id :dispatch-id :frame
                        :event :dispatched :handler :fx :coeffects
                        :effects :subs :renders :other :timing
                        :app-db :trace-events :issues}]
    (doseq [k expected-keys]
      (is (contains? out k) (str "expected key " k " in export map")))))

(deftest exported-at-passes-through-from-opts
  (let [out (export/project-cascade sample-cascade
                                    {:exported-at "2026-05-21T01:23:45.000Z"})]
    (is (= "2026-05-21T01:23:45.000Z" (:exported-at out)))))

;; ---- 2. domino projection -----------------------------------------------

(deftest dispatch-id-and-frame-flow-through
  (let [out (export/project-cascade sample-cascade)]
    (is (= 7 (:dispatch-id out)))
    (is (= :rf/default (:frame out)))
    (is (= [:cart/add-item :sku-1] (:event out)))))

(deftest domino-slots-preserved-verbatim
  (let [out (export/project-cascade sample-cascade)]
    (is (= sample-dispatched (:dispatched out)))
    (is (= sample-handler    (:handler out)))
    (is (= sample-fx         (:fx out)))
    (is (= [sample-effect]   (:effects out)))
    (is (= [sample-sub]      (:subs out)))
    (is (= [sample-render]   (:renders out)))
    (is (= [sample-error]    (:other out)))))

(deftest empty-domino-slots-default-to-empty-vec
  (let [out (export/project-cascade {:dispatch-id 1 :event [:x]})]
    (is (= [] (:effects out)))
    (is (= [] (:subs out)))
    (is (= [] (:renders out)))
    (is (= [] (:other out)))
    (is (= [] (:trace-events out)))))

;; ---- 3. coeffects hoist -------------------------------------------------

(deftest coeffects-hoisted-from-handler
  (let [out (export/project-cascade sample-cascade)]
    (is (= {:event [:cart/add-item :sku-1]
            :db {:cart {:items []}}}
           (:coeffects out)))))

(deftest coeffects-nil-when-handler-absent
  (let [out (export/project-cascade (dissoc sample-cascade :handler))]
    (is (nil? (:coeffects out)))))

;; ---- 4. timing envelope -------------------------------------------------

(deftest timing-spans-min-to-max-event-time
  (let [out (export/project-cascade sample-cascade)
        t   (:timing out)]
    (is (= 100 (:started-ms t)))
    (is (= 125 (:ended-ms t)))
    (is (= 25  (:duration-ms t)))
    (is (pos? (:event-count t)))))

(deftest timing-event-count-folds-every-slot
  (let [out (export/project-cascade sample-cascade)]
    ;; 1 dispatched + 1 handler + 1 fx + 1 effect + 1 sub + 1 render + 1 other = 7
    (is (= 7 (get-in out [:timing :event-count])))))

(deftest timing-nil-when-no-event-times
  (let [out (export/project-cascade {:dispatch-id 1})
        t   (:timing out)]
    (is (nil? (:started-ms t)))
    (is (nil? (:ended-ms t)))
    (is (nil? (:duration-ms t)))
    (is (zero? (:event-count t)))))

;; ---- 5. app-db block ----------------------------------------------------

(deftest app-db-uses-epoch-before-and-after
  (let [out (export/project-cascade sample-cascade {:epoch sample-epoch})]
    (is (= {:cart {:items []}} (get-in out [:app-db :before])))
    (is (= {:cart {:items [:sku-1]} :ui {:flash "Added"}}
           (get-in out [:app-db :after])))
    (is (= :epoch-42 (:epoch-id out)))))

(deftest app-db-diff-detects-added-modified-removed
  (let [epoch {:epoch-id :e1
               :db-before {:a 1 :b 2 :c 3}
               :db-after  {:a 1 :b 99 :d 4}}
        out (export/project-cascade {:dispatch-id 1} {:epoch epoch})
        diff (get-in out [:app-db :diff])
        by-op (group-by :op diff)]
    (is (some #(= [:b] (:path %)) (:modified by-op)) ":b modified")
    (is (some #(= [:c] (:path %)) (:removed by-op))  ":c removed")
    (is (some #(= [:d] (:path %)) (:added by-op))    ":d added")))

(deftest app-db-diff-empty-when-before-equals-after
  (let [epoch {:epoch-id :e1
               :db-before {:a 1}
               :db-after  {:a 1}}
        out (export/project-cascade {:dispatch-id 1} {:epoch epoch})]
    (is (= [] (get-in out [:app-db :diff])))))

(deftest app-db-nil-when-no-epoch-supplied
  (let [out (export/project-cascade sample-cascade)]
    (is (nil? (get-in out [:app-db :before])))
    (is (nil? (get-in out [:app-db :after])))
    (is (= [] (get-in out [:app-db :diff])))))

(deftest trace-events-pulled-from-epoch
  (let [out (export/project-cascade sample-cascade {:epoch sample-epoch})]
    (is (= 6 (count (:trace-events out)))
        "all six raw trace events on the epoch flow through")))

;; ---- 6. issues hoist ----------------------------------------------------

(deftest issues-surface-errors-and-warnings
  (let [warn  {:op-type :warning :operation :flow/circular
               :id 20 :message "circular" :tags {:dispatch-id 7}}
        cascade (assoc sample-cascade :other [sample-error warn])
        out (export/project-cascade cascade)
        issues (:issues out)]
    (is (= 2 (count issues)))
    (is (= #{:error :warning} (set (map :severity issues))))
    (is (some #(= "boom" (:message %)) issues))))

(deftest issues-empty-when-no-errors
  (let [out (export/project-cascade (assoc sample-cascade :other []))]
    (is (= [] (:issues out)))))

;; ---- 7. EDN round-trip --------------------------------------------------

(deftest to-edn-string-roundtrips
  (let [out (export/project-cascade sample-cascade {:epoch sample-epoch
                                                    :exported-at "2026-05-21T00:00:00.000Z"})
        s   (export/to-edn-string out)
        back (read-edn s)]
    (is (string? s))
    (is (= (:dispatch-id out) (:dispatch-id back)))
    (is (= (:event out)       (:event back)))
    (is (= (:rf.causa.export/version out)
           (:rf.causa.export/version back)))))

(deftest to-edn-string-does-not-truncate
  ;; pr-str under non-bound *print-length* would truncate deep colls;
  ;; the export's binding lifts that limit.
  (let [big-cascade {:dispatch-id 1
                     :effects (vec (repeat 200 sample-effect))}
        out (export/project-cascade big-cascade)
        s   (export/to-edn-string out)
        back (read-edn s)]
    (is (= 200 (count (:effects back))))))

;; ---- 8. filename sanitisation ------------------------------------------

(deftest suggested-filename-uses-dispatch-id-and-timestamp
  (let [name (export/suggested-filename
               {:dispatch-id 42
                :exported-at "2026-05-21T01:23:45.000Z"})]
    (is (clojure.string/starts-with? name "causa-cascade-42-"))
    (is (clojure.string/ends-with?   name ".edn"))))

(deftest suggested-filename-strips-illegal-chars
  (let [name (export/suggested-filename
               {:dispatch-id "abc/def:ghi"
                :exported-at nil})]
    (is (not (clojure.string/includes? name "/")))
    (is (not (clojure.string/includes? name ":")))
    (is (clojure.string/ends-with? name ".edn"))))

(deftest suggested-filename-handles-nil-dispatch-id
  (let [name (export/suggested-filename {:dispatch-id nil :exported-at nil})]
    (is (clojure.string/includes? name "cascade"))
    (is (clojure.string/ends-with? name ".edn"))))
