(ns re-frame.story.ui.dispatch-console-cljs-test
  "Tests for the Dispatch Console panel. The pure helpers run on the JVM and
  on the CLJS node-test build; the dispatch / history / replay rows run on
  CLJS only. The localStorage round-trip lives in
  `re-frame.story.ui.dispatch-console-dom-cljs-test`, because this namespace
  never reaches the browser lane and node has no `window.localStorage`."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.story.ui.dispatch-console :as rf.story.ui.dispatch-console]
            [re-frame.story.ui.dispatch-console-events :as rf.story.ui.dispatch-console-events]
            #?@(:cljs [[re-frame.core :as rf]
                       [re-frame.frame :as rf.frame]
                       [re-frame.registrar :as rf.registrar]
                       [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]])))

;; ---- fixtures (CLJS) -----------------------------------------------------

#?(:cljs
   (defn reset-all! []
     (rf.registrar/clear-all!)
     (reset! rf.frame/frames {})
     (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
     (rf.frame/ensure-default-frame!)
     (reset! rf.story.ui.dispatch-console/input-state {})
     (reset! rf.story.ui.dispatch-console/history-state {})
     ;; Clear localStorage for any keys we might have used in earlier tests.
     (when (and (exists? js/window) (.-localStorage js/window))
       (try (.clear (.-localStorage js/window)) (catch :default _ nil)))))

#?(:cljs
   (use-fixtures :each (fn [t] (reset-all!) (t))))

;; ---- pure: parse-payload -------------------------------------------------

(deftest parse-payload-reads-edn-or-errors
  (are [s out] (= out (rf.story.ui.dispatch-console/parse-payload s))
    "   "    [:ok nil]
    "{:a 1}" [:ok {:a 1}])
  (is (= :error (first (rf.story.ui.dispatch-console/parse-payload "{:bad")))))

(deftest parse-payload-edn-with-a-string-before-a-keyword
  ;; A string VALUE followed by a keyword looks like the JSON heuristic's
  ;; quoted-key-then-colon. It is the ordinary shape of a form payload and
  ;; parses as EDN in both runtimes.
  (is (= [:ok {:email "a@b.c" :password "hunter2"}]
         (rf.story.ui.dispatch-console/parse-payload "{:email \"a@b.c\" :password \"hunter2\"}"))))

#?(:cljs
   (deftest parse-payload-compact-json-stays-json-cljs
     ;; Compact JSON whose values are numbers, booleans or null is ALSO
     ;; readable EDN (`{"id" :7}`), so JSON is tried first when the
     ;; heuristic matches.
     (is (= [:ok {:id 7 :ok true :none nil}]
            (rf.story.ui.dispatch-console/parse-payload "{\"id\":7,\"ok\":true,\"none\":null}")))))

;; ---- pure: build-event-vector --------------------------------------------

(deftest build-event-vector-never-splats
  (are [payload ev] (= ev (rf.story.ui.dispatch-console/build-event-vector :e payload))
    nil         [:e]
    [:a :b :c]  [:e [:a :b :c]]))

;; ---- pure: history shaping -----------------------------------------------

(deftest prepend-history-entry-orders-newest-first
  (is (= [{:event-id :a/new} {:event-id :a/old}]
         (rf.story.ui.dispatch-console/prepend-history-entry
           [{:event-id :a/old}] {:event-id :a/new}))))

(deftest prepend-history-entry-respects-cap
  ;; prepending past the cap evicts the oldest, from the tail
  (let [seed (mapv (fn [i] {:event-id i}) (range rf.story.ui.dispatch-console/history-max))]
    (is (= (into [{:event-id :new}] (pop seed))
           (rf.story.ui.dispatch-console/prepend-history-entry seed {:event-id :new})))))

;; ---- pure: format-history-entry ------------------------------------------

(deftest format-history-entry-renders-id-and-optional-payload
  (are [entry text] (= text (rf.story.ui.dispatch-console/format-history-entry entry))
    ;; no payload: just the id
    {:event-id :counter/inc :payload nil}     ":counter/inc"
    ;; a payload renders after the id
    {:event-id :user/login :payload {:id 7}}  ":user/login {:id 7}"
    ;; no event-id is tolerated and displays an em-dash
    {:event-id nil :payload nil}              "—"))

;; ---- pure: format-timestamp ----------------------------------------------

(deftest format-timestamp-shapes-hh-mm-ss
  (is (re-matches #"\d\d:\d\d:\d\d" (rf.story.ui.dispatch-console/format-timestamp 1700000000000)))
  (is (= "" (rf.story.ui.dispatch-console/format-timestamp nil))
      "an entry with no :time renders blank"))

;; ---- pure: autocomplete --------------------------------------------------

(deftest autocomplete-event-ids-filters-sorts-and-limits
  ;; case-insensitive substring over (pr-str id), sorted, then limited
  (let [ids #{:counter/inc :counter/dec :user/login}]
    (are [prefix limit out]
         (= out (rf.story.ui.dispatch-console/autocomplete-event-ids ids prefix limit))
      ""        8 [:counter/dec :counter/inc :user/login]
      "Counter" 8 [:counter/dec :counter/inc]
      ""        2 [:counter/dec :counter/inc])))

;; ---- pure: cofx-requires-for (EP-0017) -----------------------------------

(deftest cofx-requires-for-reads-declaration
  (let [snap {:ev/needs {:rf.cofx/requires [:rf/time-ms]}}]
    (are [args ids] (= ids (apply rf.story.ui.dispatch-console-events/cofx-requires-for args))
      ;; 1-arity reads one event's meta; a parameterized [id arg] entry surfaces its id
      [{:rf.cofx/requires [:rf/time-ms [:ui/local-theme "theme"]]}] [:rf/time-ms :ui/local-theme]
      [{}]                                                         []
      ;; 2-arity looks the event up in an {id meta} snapshot
      [snap :ev/needs]                                             [:rf/time-ms]
      [snap :ev/unknown]                                           [])))

;; ---- pure: parse-cofx (EP-0017) ------------------------------------------

(deftest parse-cofx-reads-a-map-or-errors
  (are [s out] (= out (rf.story.ui.dispatch-console/parse-cofx s))
    "   "                         [:ok nil]
    "{:rf/time-ms 1781078400123}" [:ok {:rf/time-ms 1781078400123}])
  (are [s] (= :error (first (rf.story.ui.dispatch-console/parse-cofx s)))
    "[:not :a :map]"   ; readable, but not the flat :rf.cofx map
    "{:rf/time-ms 1"))

;; ---- pure: build-dispatch-opts (EP-0017) ---------------------------------

(deftest build-dispatch-opts-shapes
  (are [cofx strict? opts] (= opts (rf.story.ui.dispatch-console/build-dispatch-opts :v cofx strict?))
    ;; no cofx and not strict: just the frame opt
    nil                       false {:frame :v}
    {}                        false {:frame :v}
    ;; a non-empty cofx rides under :rf.cofx
    {:rf/time-ms 1700000000000} false {:frame :v :rf.cofx {:rf/time-ms 1700000000000}}
    ;; strict? adds :rf.cofx/mint-policy :strict (the replay path)...
    {:rf/time-ms 1}           true  {:frame :v :rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict}
    ;; ...even with no cofx token
    nil                       true  {:frame :v :rf.cofx/mint-policy :strict}))

;; ---- pure: build-history-entry carries cofx ------------------------------

(deftest build-history-entry-records-cofx
  (is (= {:event-id :e :payload nil :kind :dispatch :time 7
          :cofx {:rf/time-ms 1700000000000}}
         (rf.story.ui.dispatch-console/build-history-entry :e nil :dispatch 7 {:rf/time-ms 1700000000000})))
  (is (= {:event-id :e :payload nil :kind :dispatch :time 7}
         (rf.story.ui.dispatch-console/build-history-entry :e nil :dispatch 7 nil))
      "no cofx ⇒ no :cofx key, so cofx-free rows stay terse"))

;; ---- pure: selected-event-id ---------------------------------------------

(deftest selected-event-id-resolution
  (are [input id] (= id (rf.story.ui.dispatch-console/selected-event-id input))
    "  :counter/inc  " :counter/inc
    "   "              nil
    "not-a-keyword"    nil))

;; ===========================================================================
;; CLJS-only side-effect coverage
;; ===========================================================================

#?(:cljs
   (deftest cljs-dispatch-event-records-history
     (let [vid :story.history.test/v]
       (rf/make-frame {:id vid})
       (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
       (rf.story.ui.dispatch-console/dispatch-event! vid [:test/noop {:k :v}] :dispatch-sync)
       (is (= [{:event-id :test/noop :payload {:k :v} :kind :dispatch-sync}]
              (mapv #(dissoc % :time) (rf.story.ui.dispatch-console/current-history vid)))))))

#?(:cljs
   (deftest cljs-replay-history-entry-re-fires
     (let [vid :story.replay.test/v]
       (rf/make-frame {:id vid})
       (rf/reg-event :test/inc
                        (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
       (rf/reg-sub :test/counter
                   (fn [db _] (get db :counter 0)))
       (rf.story.ui.dispatch-console/dispatch-event! vid [:test/inc] :dispatch-sync)
       (rf.story.ui.dispatch-console/replay-history-entry!
         vid (first (rf.story.ui.dispatch-console/current-history vid)))
       (is (= 2 (rf/subscribe-once [:test/counter] {:frame vid}))))))

#?(:cljs
   (deftest cljs-dispatch-from-inputs-parse-error-sets-error-and-skips-dispatch
     (let [vid :story.parse.err/v]
       (rf/make-frame {:id vid})
       (rf/reg-event :test/boom (fn [{:keys [db]} _] {:db (assoc db :boomed? true)}))
       (swap! rf.story.ui.dispatch-console/input-state assoc vid
              {:event-id-input ":test/boom"
               :payload-input  "{:bad"})
       (rf.story.ui.dispatch-console/dispatch-from-inputs! vid :dispatch-sync)
       (is (some? (get-in @rf.story.ui.dispatch-console/input-state [vid :error])))
       (is (nil? (:boomed? (rf/app-db-value vid))) "the handler never ran"))))

#?(:cljs
   (deftest cljs-dispatch-from-inputs-missing-id-errors
     (let [vid :story.empty.id/v]
       (rf.story.ui.dispatch-console/dispatch-from-inputs! vid :dispatch)
       (is (some? (get-in @rf.story.ui.dispatch-console/input-state [vid :error]))))))

;; ===========================================================================
;; EP-0017 — a handler requiring a PROVIDED recordable cofx.
;; ===========================================================================

#?(:cljs
   (deftest cljs-console-surfaces-cofx-requires
     ;; the console reads the declaration off the live registrar's metadata
     (rf/reg-cofx :cofx.console/boundary {:recordable? true :provided? true})
     (rf/reg-event :cofx.console/needs
                      {:rf.cofx/requires [:cofx.console/boundary]}
                      (fn [{:keys [db]} _] {:db db}))
     (is (= [:cofx.console/boundary]
            (rf.story.ui.dispatch-console-events/cofx-requires-for
              (rf.story.ui.dispatch-console-events/registered-event-meta)
              :cofx.console/needs)))))

#?(:cljs
   (deftest cljs-dispatch-supplies-provided-cofx
     (let [vid :story.cofx.supply/v]
       (rf/make-frame {:id vid})
       (rf/reg-cofx :cofx.console/boundary {:recordable? true :provided? true})
       (rf/reg-event :cofx.console/needs
                        {:rf.cofx/requires [:cofx.console/boundary]}
                        (fn [{:keys [db] :as cofx} _]
                          {:db (assoc db :seen (:cofx.console/boundary cofx))}))
       (rf/reg-sub :cofx.console/seen (fn [db _] (get db :seen)))
       (swap! rf.story.ui.dispatch-console/input-state assoc vid
              {:event-id-input ":cofx.console/needs"
               :payload-input  ""
               :cofx-input     "{:cofx.console/boundary 99}"})
       (rf.story.ui.dispatch-console/dispatch-from-inputs! vid :dispatch-sync)
       (is (= 99 (rf/subscribe-once [:cofx.console/seen] {:frame vid}))
           "the supplied recordable fact reached the handler")
       (is (= {:cofx.console/boundary 99}
              (:cofx (first (rf.story.ui.dispatch-console/current-history vid))))
           "the supplied cofx is recorded in history for faithful replay"))))

#?(:cljs
   (deftest cljs-omitting-provided-cofx-fails-visibly
     (let [vid    :story.cofx.omit/v
           fired? (atom false)]
       (rf/make-frame {:id vid})
       (rf/reg-cofx :cofx.console/boundary {:recordable? true :provided? true})
       (rf/reg-event :cofx.console/needs
                        {:rf.cofx/requires [:cofx.console/boundary]}
                        (fn [{:keys [db]} _] (reset! fired? true) {:db db}))
       (swap! rf.story.ui.dispatch-console/input-state assoc vid
              {:event-id-input ":cofx.console/needs"
               :payload-input  ""
               :cofx-input     ""})
       (rf.story.ui.dispatch-console/dispatch-from-inputs! vid :dispatch-sync)
       (is (false? @fired?)
           "the handler never ran — missing-required halts the cascade")
       (is (re-find #":rf[.]error/missing-required-cofx"
                    (str (get-in @rf.story.ui.dispatch-console/input-state [vid :error])))
           "the panel error names :rf.error/missing-required-cofx")
       (is (= 0 (count (rf.story.ui.dispatch-console/current-history vid)))
           "a failed dispatch records no history entry"))))

#?(:cljs
   (deftest cljs-replay-reuses-recorded-cofx-strict
     (let [vid :story.cofx.replay/v]
       (rf/make-frame {:id vid})
       (rf/reg-cofx :cofx.console/boundary {:recordable? true :provided? true})
       (rf/reg-event :cofx.console/needs
                        {:rf.cofx/requires [:cofx.console/boundary]}
                        (fn [{:keys [db] :as cofx} _]
                          {:db (assoc db :seen (:cofx.console/boundary cofx))}))
       (rf/reg-sub :cofx.console/seen (fn [db _] (get db :seen)))
       (rf.story.ui.dispatch-console/dispatch-event! vid [:cofx.console/needs] :dispatch-sync
                                                     {:cofx.console/boundary 7} false)
       (rf.story.ui.dispatch-console/replay-history-entry!
         vid (first (rf.story.ui.dispatch-console/current-history vid)))
       (is (= 7 (rf/subscribe-once [:cofx.console/seen] {:frame vid}))
           "replay re-presented the recorded recordable fact")
       (is (= 2 (count (rf.story.ui.dispatch-console/current-history vid)))
           "replay succeeded and recorded a fresh history entry"))))
