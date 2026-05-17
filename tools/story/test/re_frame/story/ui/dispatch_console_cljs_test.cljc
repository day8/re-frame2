(ns re-frame.story.ui.dispatch-console-cljs-test
  "Tests for the Dispatch Console panel (rf2-q9kv5).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build. Mirrors the actions panel's
  coverage-layer split:

  - **Pure data** (JVM + CLJS): `parse-payload`, `build-event-vector`,
    `clamp-history`, `prepend-history-entry`, `format-history-entry`,
    `format-timestamp`, `autocomplete-event-ids`,
    `registered-event-ids` (1-arity).
  - **CLJS-only side-effects**: localStorage round-trip via
    `save-history!` / `load-history!`, `dispatch-event!` against a
    live re-frame frame, input state mutations, replay-from-history."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.ui.dispatch-console :as dc]
            [re-frame.story.ui.dispatch-console-events :as dce]
            #?@(:cljs [[re-frame.core :as rf]
                       [re-frame.frame :as frame]
                       [re-frame.registrar :as registrar]
                       [re-frame.substrate.plain-atom :as plain-atom]])))

#?(:cljs
   (defn- browser?
     "True iff `js/window.localStorage` is available — mirrors the gate
     in `story_help_cljs_test`. The node-runtime test target returns
     false; browser-runner returns true. localStorage round-trip tests
     skip silently when this is false."
     []
     (boolean
       (and (exists? js/window) (.-localStorage js/window)))))

;; ---- fixtures (CLJS) -----------------------------------------------------

#?(:cljs
   (defn reset-all! []
     (registrar/clear-all!)
     (reset! frame/frames {})
     (try (rf/init! plain-atom/adapter) (catch :default _ nil))
     (frame/ensure-default-frame!)
     (reset! dc/input-state {})
     (reset! dc/history-state {})
     ;; Clear localStorage for any keys we might have used in earlier tests.
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.clear (.-localStorage js/window)) (catch :default _ nil)))))

#?(:cljs
   (use-fixtures :each {:before reset-all!}))

;; ---- pure: parse-payload -------------------------------------------------

(deftest parse-payload-empty
  (testing "blank and nil payloads parse to nil"
    (is (= [:ok nil] (dc/parse-payload nil)))
    (is (= [:ok nil] (dc/parse-payload "")))
    (is (= [:ok nil] (dc/parse-payload "   ")))))

(deftest parse-payload-edn
  (testing "EDN payloads parse via clojure.edn"
    (is (= [:ok {:a 1}]   (dc/parse-payload "{:a 1}")))
    (is (= [:ok [1 2 3]]  (dc/parse-payload "[1 2 3]")))
    (is (= [:ok :keyword] (dc/parse-payload ":keyword")))
    (is (= [:ok 42]       (dc/parse-payload "42")))
    (is (= [:ok "string"] (dc/parse-payload "\"string\"")))))

(deftest parse-payload-error
  (testing "invalid EDN returns [:error <msg>]"
    (let [[tag _] (dc/parse-payload "{:bad")]
      (is (= :error tag)))))

#?(:cljs
   (deftest parse-payload-json-cljs
     (testing "JSON-shaped payloads parse via JSON.parse on CLJS"
       (let [[tag v] (dc/parse-payload "{\"a\":1,\"b\":\"two\"}")]
         (is (= :ok tag))
         (is (= {:a 1 :b "two"} v))))))

;; ---- pure: build-event-vector --------------------------------------------

(deftest build-event-vector-nil-payload
  (testing "nil payload produces [id]"
    (is (= [:counter/inc]
           (dc/build-event-vector :counter/inc nil)))))

(deftest build-event-vector-with-payload
  (testing "payload is the second slot — never splatted"
    (is (= [:user/login {:id 7}]
           (dc/build-event-vector :user/login {:id 7})))
    (is (= [:user/login [:a :b :c]]
           (dc/build-event-vector :user/login [:a :b :c])))))

;; ---- pure: history shaping -----------------------------------------------

(deftest clamp-history-respects-max
  (testing "histories above the cap keep the HEAD entries (newest-first orientation)"
    ;; Story convention: newest entries live at index 0 (head); the tail
    ;; is the oldest and gets evicted. clamp-history is the low-level
    ;; helper — prepend-history-entry is the consumer that respects the
    ;; ordering.
    (let [thirty (mapv (fn [i] {:event-id (keyword "e" (str "i" i))})
                       (range 30))
          capped (dc/clamp-history thirty)]
      (is (= dc/history-max (count capped)))
      ;; The first dc/history-max entries (index 0..max-1) survive.
      (is (= :e/i0 (:event-id (first capped))))
      (is (= (keyword "e" (str "i" (dec dc/history-max)))
             (:event-id (last capped)))))))

(deftest clamp-history-passes-short
  (testing "histories at or under the cap pass through"
    (is (= [] (dc/clamp-history [])))
    (is (= [{:a 1}] (dc/clamp-history [{:a 1}])))))

(deftest prepend-history-entry-orders-newest-first
  (testing "the freshest entry lands at the head of the vector"
    (let [h0 [{:event-id :a/old}]
          h1 (dc/prepend-history-entry h0 {:event-id :a/new})]
      (is (= :a/new (:event-id (first h1))))
      (is (= :a/old (:event-id (second h1)))))))

(deftest prepend-history-entry-respects-cap
  (testing "prepending past the cap evicts from the tail"
    (let [seed (mapv (fn [i] {:event-id (keyword "e" (str "i" i))})
                     (range dc/history-max))
          h    (dc/prepend-history-entry seed {:event-id :e/new})]
      (is (= dc/history-max (count h)))
      (is (= :e/new (:event-id (first h)))))))

;; ---- pure: format-history-entry ------------------------------------------

(deftest format-history-entry-renders-id-only
  (testing "an entry with no payload renders just the id"
    (is (= ":counter/inc"
           (dc/format-history-entry {:event-id :counter/inc :payload nil})))))

(deftest format-history-entry-renders-id-and-payload
  (testing "an entry with a payload renders both"
    (is (= ":user/login {:id 7}"
           (dc/format-history-entry {:event-id :user/login
                                     :payload  {:id 7}})))))

(deftest format-history-entry-handles-missing-id
  (testing "no event-id is tolerated (display em-dash)"
    (is (= "—"
           (dc/format-history-entry {:event-id nil :payload nil})))))

;; ---- pure: format-timestamp ----------------------------------------------

(deftest format-timestamp-edge-cases
  (testing "nil / non-numeric / negative produces empty string"
    (is (= "" (dc/format-timestamp nil)))
    (is (= "" (dc/format-timestamp "not-a-number")))
    (is (= "" (dc/format-timestamp -1)))))

(deftest format-timestamp-shapes-hh-mm-ss
  (testing "a real epoch produces an HH:MM:SS-shaped string"
    (let [out (dc/format-timestamp 1700000000000)]
      (is (string? out))
      (is (= 8 (count out)))
      (is (re-matches #"\d\d:\d\d:\d\d" out)))))

;; ---- pure: autocomplete --------------------------------------------------

(deftest autocomplete-empty-prefix-returns-all
  (testing "an empty prefix returns the full id set sorted"
    (let [ids #{:counter/inc :counter/dec :user/login}
          out (dc/autocomplete-event-ids ids "")]
      (is (= 3 (count out)))
      ;; Sorted by pr-str.
      (is (= [:counter/dec :counter/inc :user/login] out)))))

(deftest autocomplete-filters-by-substring
  (testing "matches are case-insensitive substring on (pr-str id)"
    (let [ids #{:counter/inc :counter/dec :user/login}
          out (dc/autocomplete-event-ids ids "counter")]
      (is (= 2 (count out)))
      (is (every? #(re-find #"counter" (str %)) out)))))

(deftest autocomplete-respects-limit
  (testing "limit clamps the returned vector length"
    (let [ids (set (map #(keyword "e" (str "i" %)) (range 100)))
          out (dc/autocomplete-event-ids ids "" 5)]
      (is (= 5 (count out))))))

;; ---- pure: registered-event-ids 1-arity ----------------------------------

(deftest registered-event-ids-from-snapshot
  (testing "the 1-arity returns the snapshot key-set"
    (is (= #{:a :b :c}
           (dce/registered-event-ids {:a {} :b {} :c {}})))
    (is (= #{}
           (dce/registered-event-ids nil)))
    (is (= #{}
           (dce/registered-event-ids {})))))

;; ===========================================================================
;; CLJS-only side-effect coverage
;; ===========================================================================

#?(:cljs
   (deftest cljs-input-state-roundtrip
     (testing "reset-inputs! clears the per-variant inputs"
       (let [vid :story.x/y]
         (swap! dc/input-state assoc-in [vid :event-id-input] ":hello")
         (swap! dc/input-state assoc-in [vid :payload-input]  "{:a 1}")
         (is (= ":hello" (get-in @dc/input-state [vid :event-id-input])))
         (dc/reset-inputs! vid)
         (is (= "" (get-in @dc/input-state [vid :event-id-input])))
         (is (= "" (get-in @dc/input-state [vid :payload-input])))))))

#?(:cljs
   (deftest cljs-history-localstorage-roundtrip
     (testing "save-history! → load-history! survives across reset"
       (when (browser?)
         (let [vid    :story.persist/v
               entry  (dc/build-history-entry :counter/inc nil :dispatch
                                              1700000000000)
               one    [entry]]
           (dc/save-history! vid one)
           ;; Drop in-memory state to simulate a reload.
           (reset! dc/history-state {})
           (let [loaded (dc/load-history! vid)]
             (is (= 1 (count loaded)))
             (is (= :counter/inc (:event-id (first loaded))))))))))

#?(:cljs
   (deftest cljs-current-history-hydrates-once
     (testing "current-history hydrates from localStorage on first access"
       (when (browser?)
         (let [vid   :story.hyd/v
               entry (dc/build-history-entry :ev/x nil :dispatch 17)]
           (dc/save-history! vid [entry])
           (reset! dc/history-state {})
           (let [h (dc/current-history vid)]
             (is (= 1 (count h)))
             (is (= :ev/x (:event-id (first h))))))))))

#?(:cljs
   (deftest cljs-clear-history-drops-storage
     (testing "clear-history! removes both ratom and localStorage state"
       (let [vid :story.clear/v
             entry (dc/build-history-entry :ev/x nil :dispatch 1)]
         (dc/append-history! vid entry)
         (is (= 1 (count (dc/current-history vid))))
         (dc/clear-history! vid)
         (is (= 0 (count (dc/current-history vid))))
         (when (browser?)
           ;; Drop in-memory state and confirm storage was actually wiped.
           (reset! dc/history-state {})
           (is (= [] (dc/load-history! vid))))))))

#?(:cljs
   (deftest cljs-dispatch-event-changes-app-db
     (testing "dispatching against a frame writes app-db via dispatch-sync"
       (let [vid :story.dispatch.test/v]
         (rf/reg-frame vid {})
         (rf/reg-event-db :test/inc
                          (fn [db _] (update db :counter (fnil inc 0))))
         (rf/reg-sub :test/counter
                     (fn [db _] (get db :counter 0)))
         (dc/dispatch-event! vid [:test/inc] :dispatch-sync)
         (is (= 1 (rf/subscribe-once vid [:test/counter])))
         (dc/dispatch-event! vid [:test/inc] :dispatch-sync)
         (is (= 2 (rf/subscribe-once vid [:test/counter])))))))

#?(:cljs
   (deftest cljs-dispatch-event-records-history
     (testing "every dispatch lands a history entry"
       (let [vid :story.history.test/v]
         (rf/reg-frame vid {})
         (rf/reg-event-db :test/noop (fn [db _] db))
         (dc/dispatch-event! vid [:test/noop {:k :v}] :dispatch-sync)
         (let [h (dc/current-history vid)]
           (is (= 1 (count h)))
           (is (= :test/noop (:event-id (first h))))
           (is (= {:k :v}     (:payload  (first h))))
           (is (= :dispatch-sync (:kind   (first h)))))))))

#?(:cljs
   (deftest cljs-replay-history-entry-re-fires
     (testing "click-replay re-dispatches the recorded event"
       (let [vid :story.replay.test/v]
         (rf/reg-frame vid {})
         (rf/reg-event-db :test/inc
                          (fn [db _] (update db :counter (fnil inc 0))))
         (rf/reg-sub :test/counter
                     (fn [db _] (get db :counter 0)))
         (dc/dispatch-event! vid [:test/inc] :dispatch-sync)
         (is (= 1 (rf/subscribe-once vid [:test/counter])))
         (let [h (first (dc/current-history vid))]
           (dc/replay-history-entry! vid h))
         (is (= 2 (rf/subscribe-once vid [:test/counter])))
         ;; Replay added a new history entry.
         (is (= 2 (count (dc/current-history vid))))))))

#?(:cljs
   (deftest cljs-dispatch-from-inputs-parse-error-keeps-app-db
     (testing "a bad payload sets :error and does not dispatch"
       (let [vid :story.parse.err/v]
         (rf/reg-frame vid {})
         (rf/reg-event-db :test/boom (fn [db _] (assoc db :boomed? true)))
         (swap! dc/input-state assoc vid
                {:event-id-input ":test/boom"
                 :payload-input  "{:bad"})
         (dc/dispatch-from-inputs! vid :dispatch-sync)
         (is (some? (get-in @dc/input-state [vid :error])))
         (is (= 0 (count (dc/current-history vid))))))))

#?(:cljs
   (deftest cljs-dispatch-from-inputs-missing-id-errors
     (testing "an empty event-id input sets :error"
       (let [vid :story.empty.id/v]
         (rf/reg-frame vid {})
         (swap! dc/input-state assoc vid
                {:event-id-input ""
                 :payload-input  ""})
         (dc/dispatch-from-inputs! vid :dispatch)
         (is (some? (get-in @dc/input-state [vid :error])))))))

#?(:cljs
   (deftest cljs-autocomplete-against-live-registrar
     (testing "registered-event-ids 0-arity surfaces registered handlers"
       (rf/reg-event-db :ac.test/one (fn [db _] db))
       (rf/reg-event-db :ac.test/two (fn [db _] db))
       (let [ids (dce/registered-event-ids)]
         (is (contains? ids :ac.test/one))
         (is (contains? ids :ac.test/two))))))
