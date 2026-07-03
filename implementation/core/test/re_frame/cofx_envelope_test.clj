(ns re-frame.cofx-envelope-test
  "EP-0017 `:rf.cofx` recordable-coeffect envelope core slice (rf2-s9ss0t).

  Pins the `:rf.cofx` envelope + coeffect contract that makes the
  frame fold deterministic with respect to prior frame-state plus the
  causal token (Spec 002 §Recordable coeffects):

    - the router STAMPS `:rf.cofx {:rf/time-ms ...}` when the caller
      omits it (Spec 002 §Dispatch Envelope Stamping);
    - a caller-supplied map is PRESERVED verbatim — including extra slots
      (`:uuid`, `:random`, browser/storage facts) — and the router never
      overwrites a supplied `:rf/time-ms`;
    - a CHILD dispatch (`:fx [[:dispatch ...]]`) gets its OWN map: `:rf/time-ms`
      is NOT inherited from the parent (each is a distinct causal token);
    - the value is visible to handler bodies as the `:rf.cofx`
      coeffect alongside `:db` / `:event` / `:rf.db/runtime` / `:rf.frame/id`
      (Spec 002 §Event Context And Coeffects);
    - it is FILTERED out of the user-cofx trace projection exactly like the
      other framework defaults (`fx/framework-coeffect-keys`);
    - `:dispatched-at` is RETIRED in the same change (rider b) — its
      diagnostic dispatch-time need is the trace event `:time` stamp.

  EP-0017 renamed the EP-0010 envelope field `:rf.world/inputs` to the flat
  `:rf.cofx` map (no alias); this namespace pins the live `:rf.cofx` contract.
  Both `:rf.world/inputs` and `:dispatched-at` only ever named a fact in the
  spec's own DRAFTS, so under the shipped-names-only tombstone rule
  (Conventions §The tombstone rule) they earn NO dedicated retired-name error
  id — supplying either is caught by the generic `:rf.warning/unknown-dispatch-opt`
  surface with a did-you-mean. That retired-draft coverage lives in
  `re-frame.cofx-cljs-test`, `re-frame.unknown-dispatch-opts-warn-test`, and
  `re-frame.event-model-conformance-cljs-test`.

  JVM-only — the stamping path is platform-agnostic (`interop/now-ms`
  realises on both hosts); no CLJS host dependency under test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.recordable :as recordable]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-zq5zj2 / rf2-qwm0a — load the tooling sibling so the
            ;; late-bind hooks behind the listener API resolve (the
            ;; diagnostic-differs test registers a trace listener).
            [re-frame.trace.tooling]
            ;; rf2-zq5zj2 — side-effect require: `re-frame.epoch` publishes
            ;; the `:epoch/settle!` + `:epoch/epoch-history` +
            ;; `:epoch/clear-history!` late-bind hooks at ns-load, so each
            ;; drain-settle commits a `:rf/epoch-record` whose durable
            ;; `:committed-at` the diagnostic-differs test reads via
            ;; `rf/epoch-history`. Available on the core test classpath as the
            ;; epoch test-only dep (core/deps.edn :test alias, rf2-lt4e).
            [re-frame.epoch]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  ;; rf2-zq5zj2 — clear the per-frame epoch rings so the diagnostic-differs
  ;; test reads only its own freshly-committed records (the `:epoch/settle!`
  ;; hook is published once `re-frame.epoch` is loaded, above).
  (when-let [clear-history! (late-bind/get-fn :epoch/clear-history!)]
    (clear-history!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(def ^:private build-envelope
  "The private envelope builder — the dispatch envelope is not exposed to
  user handlers, so stamping/preservation is asserted directly against it."
  #'router/build-envelope)

(defn- capture-coeffects
  "Dispatch `[:capture]` on `frame-id` (threading `opts`) and return the
  coeffects map the handler saw."
  ([frame-id] (capture-coeffects frame-id nil))
  ([frame-id opts]
   (let [captured (atom nil)]
     (rf/reg-interceptor* :capture/probe
       {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
     (rf/reg-event :capture
       {:interceptors [:capture/probe]}
       (fn [_ _] {}))
     (if opts
       (rf/dispatch-sync [:capture] (merge {:frame frame-id} opts))
       (rf/dispatch-sync [:capture] {:frame frame-id}))
     @captured)))

;; ===========================================================================
;; Envelope stamping
;; ===========================================================================

(deftest stamps-time-ms-when-absent
  (testing "the router stamps :rf.cofx {:rf/time-ms ...} when the caller omits it"
    (rf/reg-frame :wi/stamp {:doc "ctx"})
    (let [env   (build-envelope [:noop] {:frame :wi/stamp})
          world (:rf.cofx env)]
      (is (map? world) ":rf.cofx is present on the envelope")
      (is (number? (:rf/time-ms world)) ":rf/time-ms is a stamped epoch-ms number")
      (is (= #{:rf/time-ms} (set (keys world)))
          "only the framework-required :rf/time-ms is stamped — no other keys invented"))))

(deftest preserves-caller-supplied-time-ms
  (testing "a caller-supplied :rf/time-ms is preserved verbatim — the router does NOT overwrite it"
    (rf/reg-frame :wi/supplied {:doc "ctx"})
    (let [env (build-envelope [:noop]
                              {:frame :wi/supplied
                               :rf.cofx {:rf/time-ms 1781078400123}})]
      (is (= 1781078400123 (get-in env [:rf.cofx :rf/time-ms]))
          "the exact supplied :rf/time-ms rides through (replay / SSR / fixtures)"))))

(deftest preserves-caller-supplied-extra-keys-and-fills-time-ms
  (testing "extra recordable-coeffect facts ride through (flat); a missing :rf/time-ms is filled, supplied facts untouched"
    (rf/reg-frame :wi/extra {:doc "ctx"})
    (let [uuid  #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          env   (build-envelope [:noop]
                                {:frame :wi/extra
                                 :rf.cofx
                                 {:todo/id    uuid
                                  :todo/color :green}})
          world (:rf.cofx env)]
      (is (= uuid (:todo/id world)) "supplied :todo/id fact preserved")
      (is (= :green (:todo/color world)) "supplied :todo/color fact preserved")
      (is (number? (:rf/time-ms world))
          "the framework-required :rf/time-ms is filled in alongside the supplied facts"))))

;; ===========================================================================
;; rf2-47lgee / rf2-nftz2s: PUBLIC-boundary validation of a caller-supplied
;; :rf.cofx. A malformed causal token folds into durable writes (the
;; epoch record's :committed-at, resource :settled-at) and breaks the
;; deterministic fold, so build-envelope rejects it with a structured
;; :rf.error/invalid-cofx BEFORE stamping — always-on, prod-survivable,
;; and BEFORE the clock read (a dispatch that cannot proceed never reads the
;; clock). The pair-tool validated this on its own wire; this pins the central
;; core boundary that protects ordinary public dispatch.
;; ===========================================================================

(deftest non-map-cofx-is-a-hard-error
  (testing "a supplied non-map :rf.cofx is a hard error (not silently stamped)"
    (rf/reg-frame :wi/bad-shape {:doc "ctx"})
    (testing "build-envelope throws even with the dev gate OFF (prod-survivable)"
      (with-redefs [interop/debug-enabled? false]
        (is (thrown? clojure.lang.ExceptionInfo
                     (build-envelope [:noop] {:frame :wi/bad-shape
                                              :rf.cofx "now"}))
            "a string :rf.cofx is rejected, not coerced/stamped")))
    (testing "the error carries the structured :rf.error/invalid-cofx id"
      (let [ex   (try
                   (build-envelope [:noop] {:frame :wi/bad-shape
                                            :rf.cofx [:not :a :map]})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
            data (ex-data ex)]
        (is (some? ex) "an exception was thrown")
        (is (= :rf.error/invalid-cofx (:rf.error/id data))
            "the error category is :rf.error/invalid-cofx")
        (is (= [:not :a :map] (:supplied data)) "names the bad supplied value")
        (is (re-find #":rf/time-ms" (:reason data))
            "the message explains :rf/time-ms is the durable causal token")
        (is (= :no-recovery (:recovery data)) "no recovery / no coercion")))))

(deftest non-integer-time-ms-is-a-hard-error
  (testing "a supplied :rf/time-ms that is not an integer is a hard error"
    (rf/reg-frame :wi/bad-time {:doc "ctx"})
    (testing "build-envelope throws on a string :rf/time-ms (even dev-gate OFF)"
      (with-redefs [interop/debug-enabled? false]
        (is (thrown? clojure.lang.ExceptionInfo
                     (build-envelope [:noop] {:frame :wi/bad-time
                                              :rf.cofx {:rf/time-ms "now"}}))
            "a string :rf/time-ms is rejected (the schema requires :int)")))
    (testing "nil :rf/time-ms is also rejected (a present-but-nil causal time is malformed)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (build-envelope [:noop] {:frame :wi/bad-time
                                            :rf.cofx {:rf/time-ms nil}}))
          "a nil :rf/time-ms is not an integer — rejected"))
    (testing "a fractional :rf/time-ms is rejected (epoch ms is an integer)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (build-envelope [:noop] {:frame :wi/bad-time
                                            :rf.cofx {:rf/time-ms 1781078400.5}}))
          "a double :rf/time-ms is not an integer — rejected"))
    (testing "the error names the bad :rf/time-ms and the integer/epoch-ms contract"
      (let [ex   (try
                   (build-envelope [:noop] {:frame :wi/bad-time
                                            :rf.cofx {:rf/time-ms "now"}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
            data (ex-data ex)]
        (is (= :rf.error/invalid-cofx (:rf.error/id data))
            "the error category is :rf.error/invalid-cofx")
        (is (= "now" (:rf/time-ms data)) "names the bad :rf/time-ms value")
        (is (re-find #"INTEGER" (:reason data))
            "the message states the integer/epoch-ms contract")))))

(deftest valid-cofx-shapes-pass
  (testing "the valid shapes the validator must NOT reject"
    (rf/reg-frame :wi/valid {:doc "ctx"})
    (testing "nil :rf.cofx passes (the router stamps a fresh map)"
      (is (number? (get-in (build-envelope [:noop] {:frame :wi/valid
                                                    :rf.cofx nil})
                           [:rf.cofx :rf/time-ms]))
          "a nil supplied value is filled with a stamped :rf/time-ms"))
    (testing "an integer :rf/time-ms passes verbatim"
      (is (= 1781078400123
             (get-in (build-envelope [:noop] {:frame :wi/valid
                                              :rf.cofx {:rf/time-ms 1781078400123}})
                     [:rf.cofx :rf/time-ms]))
          "a valid integer :rf/time-ms rides through preserved"))
    (testing "a map with NO :rf/time-ms passes (the router fills it)"
      (let [cofx (get (build-envelope [:noop]
                                      {:frame :wi/valid
                                       :rf.cofx {:todo/id 1}})
                      :rf.cofx)]
        (is (= 1 (:todo/id cofx)) "the supplied fact rides through")
        (is (number? (:rf/time-ms cofx)) "the missing :rf/time-ms is filled")))))

;; ===========================================================================
;; Structural-EDN-always recordable cofx-value validation (rf2-rmroo4 slice A)
;; ===========================================================================
;;
;; EP-0017:386 — a recordable coeffect value rides the durable causal record
;; (epoch ledger, replay, SSR payload, Xray) and MUST be ordinary EDN data. A
;; host handle (DOM node, Promise, function, atom, Date, JS / Java object)
;; supplied as a recordable coeffect breaks that contract silently. Slice A
;; closes the supplied-value gap at the dispatch boundary, AFTER the map-shape
;; check and BEFORE the per-supplier `:schema` validation. Reuses
;; `:rf.error/cofx-value-invalid` with reason `:non-edn-recordable-value`.

(deftest recordable-predicate-accepts-edn-rejects-host-handles
  (testing "recordable-edn-value? accepts the full EDN data domain"
    (doseq [v [nil true false 0 1 -7 3.14 1/3 1000000000000000000000N
               "s" :k :ns/k 'sym \c
               #uuid "00000000-0000-0000-0000-000000000000"
               #inst "2026-06-14T00:00:00.000Z"
               [] [1 2 3] '(1 2) #{1 2} {:a 1 :b [2 {:c 3}]}
               {:nested {:deep [#{:a :b} {:k "v"}]}}]]
      (is (recordable/recordable-edn-value? v)
          (str (pr-str v) " is recordable EDN data"))))
  (testing "recordable-edn-value? REJECTS non-data host handles"
    (doseq [v [(fn [] 1)                       ;; a function
               +                               ;; a var-bound fn
               (atom 1)                         ;; an atom (IDeref)
               (java.util.regex.Pattern/compile "x") ;; a host object
               (Object.)                        ;; a bare host object
               (java.io.StringWriter.)]]        ;; a host writer
      (is (not (recordable/recordable-edn-value? v))
          (str (type v) " is NOT recordable EDN data"))))
  (testing "a host handle BURIED in a collection is rejected (deep walk)"
    (is (not (recordable/recordable-edn-value? {:ok 1 :bad [:a (atom 2)]}))
        "an atom nested in a vector under a map key is found")
    (is (not (recordable/recordable-edn-value? #{:a (fn [] 1)}))
        "a function inside a set is found")
    (is (not (recordable/recordable-edn-value? {(Object.) :v}))
        "a host-object MAP KEY is found")))

(deftest explain-non-recordable-reports-path-and-type
  (testing "explain-non-recordable returns the failing path + safe type"
    (let [bad (recordable/explain-non-recordable {:ok 1 :nope {:deep (atom 9)}})]
      (is (some? bad) "a descriptor is returned for a non-recordable value")
      (is (= [:nope :deep] (:path bad)) "the path locates the bad leaf")
      (is (string? (:bad-type bad)) "a printable host-type string is reported")
      (is (re-find #"(?i)atom" (:bad-type bad))
          "the type names the atom host class")))
  (testing "explain-non-recordable is nil for clean EDN"
    (is (nil? (recordable/explain-non-recordable {:a [1 2 {:b :c}]}))))
  (testing "safe-preview round-trips EDN and refuses host handles"
    (is (= "{:a 1}" (recordable/safe-preview {:a 1}))
        "a preview of EDN data is its pr-str")
    (is (nil? (recordable/safe-preview (atom 1)))
        "no preview for a non-recordable value (never the raw host object)")))

(deftest jvm-instant-is-not-recordable-only-date-is
  ;; rf2-3az1vn P2: a `java.util.Date` round-trips through pr-str / read-string
  ;; (the EDN reader's default `#inst` reader returns a Date), so it stays
  ;; recordable. A `java.time.Instant` does NOT round-trip — Clojure prints it
  ;; with the `#inst` tag but `read-string` of that form throws
  ;; `No reader function for tag inst` (no data-reader bound), and even with the
  ;; default reader it would come back a Date, not an Instant. So an Instant is
  ;; rejected as a host handle at the SOURCE coeffect (explain-non-recordable),
  ;; not far away at replay / Xray / SSR time — and safe-preview never leaks it.
  (testing "a java.util.Date (#inst literal) IS recordable and round-trips"
    (let [d (java.util.Date. 1781078400123)]
      (is (recordable/recordable-edn-value? d)
          "a java.util.Date is recordable EDN data")
      (is (nil? (recordable/explain-non-recordable d))
          "no failing descriptor for a Date")
      (is (= d (read-string (pr-str d)))
          "a Date round-trips through pr-str / read-string unchanged")))
  (testing "a java.time.Instant is NOT recordable — rejected at the source"
    (let [inst (java.time.Instant/ofEpochMilli 1781078400123)]
      (is (not (recordable/recordable-edn-value? inst))
          "a java.time.Instant is NOT recordable EDN data")
      (let [bad (recordable/explain-non-recordable inst)]
        (is (some? bad) "explain-non-recordable returns a descriptor for an Instant")
        (is (= [] (:path bad)) "the failing path is the root (the value itself)")
        (is (re-find #"(?i)instant" (:bad-type bad))
            "the bad-type names the Instant host class"))
      (is (nil? (recordable/safe-preview inst))
          "safe-preview refuses an Instant — no non-round-tripping preview leak")
      (testing "the round-trip the predicate protects against actually fails"
        (is (thrown? RuntimeException
              (read-string (pr-str inst)))
            "read-string of a printed Instant throws (No reader function for tag inst)"))))
  (testing "an Instant BURIED in a collection is rejected (deep walk)"
    (let [bad (recordable/explain-non-recordable
                {:ok 1 :when {:at (java.time.Instant/ofEpochMilli 0)}})]
      (is (some? bad) "the buried Instant is found")
      (is (= [:when :at] (:path bad)) "the path locates the buried Instant"))))

(deftest supplied-non-edn-cofx-value-is-cofx-value-invalid
  ;; THE adversarial acceptance leg: a non-EDN supplied cofx value throws
  ;; :rf.error/cofx-value-invalid; an EDN one passes.
  (testing "a supplied host-handle cofx value throws cofx-value-invalid (dev mode)"
    (rf/reg-frame :wi/edn-bad {:doc "ctx"})
    (let [ex   (try
                 (build-envelope [:noop] {:frame   :wi/edn-bad
                                          :rf.cofx {:rf/time-ms 1781078400123
                                                    :app/handle (atom :host)}})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))
          data (ex-data ex)]
      (is (some? ex) "an exception was thrown for a non-EDN recordable value")
      (is (= :rf.error/cofx-value-invalid (:rf.error/id data))
          "reuses the EP-0017 cofx error id (not :rf.error/invalid-cofx)")
      (is (= :non-edn-recordable-value (:rf.cofx/value-error data))
          "the structural sub-kind rides :rf.cofx/value-error (:reason is now the human sentence)")
      (is (= :app/handle (:rf.cofx/id data))
          "names the failing recordable fact id")
      (is (= [:app/handle] (:path data))
          "the path is rooted at the failing fact key")
      (is (string? (:bad-type data)) "a safe host-type string is carried")
      (is (re-find #"(?i)atom" (:bad-type data))
          "the bad-type names the host class — NEVER the raw object")
      (is (= :no-recovery (:recovery data)) "no recovery")))
  (testing "the bad value is BURIED — the path locates it inside the fact"
    (rf/reg-frame :wi/edn-buried {:doc "ctx"})
    (let [ex   (try
                 (build-envelope [:noop]
                                 {:frame   :wi/edn-buried
                                  :rf.cofx {:rf/time-ms 1781078400123
                                            :app/blob {:items [{:dom (Object.)}]}}})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))
          data (ex-data ex)]
      (is (= :rf.error/cofx-value-invalid (:rf.error/id data)))
      (is (= [:app/blob :items 0 :dom] (:path data))
          "the path threads the fact id, vector index, and map keys")))
  (testing "an EDN supplied cofx value PASSES the structural check"
    (rf/reg-frame :wi/edn-ok {:doc "ctx"})
    (let [cofx (get (build-envelope [:noop]
                                    {:frame   :wi/edn-ok
                                     :rf.cofx {:rf/time-ms 1781078400123
                                               :user/id    42
                                               :user/prefs {:theme :dark
                                                            :tags  #{:a :b}}
                                               :session/at #inst "2026-06-14T00:00:00.000Z"}})
                    :rf.cofx)]
      (is (= 42 (:user/id cofx)) "an integer fact rides through")
      (is (= {:theme :dark :tags #{:a :b}} (:user/prefs cofx))
          "a nested EDN map fact rides through unchanged")
      (is (inst? (:session/at cofx)) "an #inst fact rides through"))))

(deftest structural-edn-check-is-production-hard
  ;; rf2-q34j26 (EP-0017 Open Issue 9 — structural EDN ALWAYS, hard error in
  ;; production as well as dev): a supplied recordable value that is a host
  ;; handle folds a non-EDN value into the durable causal record (epoch ledger,
  ;; replay, SSR payload, Xray) — corrupt durable state, not a dev nicety. The
  ;; per-value walk is therefore ALWAYS-ON, NOT gated on `interop/debug-enabled?`
  ;; — the same `:dispatched-at` causal-token precedent the map-shape /
  ;; `:rf/time-ms` checks already enforce in production.
  (testing "with the dev gate OFF a non-EDN supplied value IS structurally rejected"
    (rf/reg-frame :wi/edn-prod {:doc "ctx"})
    (with-redefs [interop/debug-enabled? false]
      (let [ex   (try
                   (build-envelope [:noop]
                                   {:frame   :wi/edn-prod
                                    :rf.cofx {:rf/time-ms 1781078400123
                                              :app/handle (atom :host)}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
            data (ex-data ex)]
        (is (some? ex)
            "the structural walk fires in prod — a host handle is rejected even
             with the dev gate OFF (the durable causal-token contract, not a dev guard)")
        (is (= :rf.error/cofx-value-invalid (:rf.error/id data)))
        (is (= :non-edn-recordable-value (:rf.cofx/value-error data))
            "the structural sub-kind is named on its own slot")
        (is (= [:app/handle] (:path data))
            "the path is rooted at the failing fact key"))))
  (testing "the MAP-SHAPE check stays always-on even with the dev gate OFF"
    (rf/reg-frame :wi/edn-prod2 {:doc "ctx"})
    (with-redefs [interop/debug-enabled? false]
      (is (thrown? clojure.lang.ExceptionInfo
                   (build-envelope [:noop] {:frame :wi/edn-prod2
                                            :rf.cofx "not-a-map"}))
          "both the map-shape guard and the structural slice-A guard are always-on"))))

(deftest invalid-cofx-rejected-before-clock-read
  ;; Mirrors retired-dispatched-at-rejected-before-causal-clock-read: the
  ;; validation runs BEFORE the causal-token clock stamp, so an invalid token
  ;; fails fast WITHOUT triggering the always-on epoch-now-ms read for a
  ;; dispatch that cannot proceed. Redefine epoch-now-ms to throw a distinct
  ;; marker; if the clock is read before validation, that marker surfaces.
  (testing "a malformed :rf.cofx throws the validation error WITHOUT
            reading the causal-token clock first"
    (rf/reg-frame :wi/order2 {:doc "ctx"})
    (with-redefs [interop/epoch-now-ms
                  (fn [] (throw (ex-info "clock read before validation"
                                         {::clock-read true})))]
      (let [ex   (try
                   (build-envelope [:noop] {:frame :wi/order2
                                            :rf.cofx {:rf/time-ms "now"}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
            data (ex-data ex)]
        (is (some? ex) "an exception was thrown")
        (is (not (::clock-read data))
            "the causal-token clock was NOT read before validation — failed fast")
        (is (= :rf.error/invalid-cofx (:rf.error/id data))
            "the surfaced error is the validation error, proving it ran first")))))

(deftest invalid-cofx-raised-through-full-dispatch
  (testing "the full dispatch path (not just build-envelope) raises the validation error"
    (rf/reg-frame :wi/dispatch-bad {:doc "ctx"})
    (rf/reg-event :wi/bad-noop (fn [{:keys [db]} _] {:db db}))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rf/dispatch-sync [:wi/bad-noop]
                                   {:frame :wi/dispatch-bad
                                    :rf.cofx {:rf/time-ms "now"}}))
        "dispatch-sync surfaces the malformed-token error synchronously")))

;; ===========================================================================
;; Child dispatch gets its OWN :rf.cofx map (no :rf/time-ms inheritance)
;; ===========================================================================

(deftest child-dispatch-gets-fresh-time-ms
  (testing "a :fx [[:dispatch ...]] child gets its OWN :rf.cofx — :rf/time-ms NOT inherited"
    (rf/reg-frame :wi/cascade {:doc "ctx"})
    (let [envelopes (atom [])]
      ;; A user fx-handler receives the parent dispatch envelope under
      ;; (:envelope m); each handler in the cascade fires its own capture,
      ;; so we read both the parent's and child's stamped :rf.cofx map.
      (rf/reg-fx :wi/capture-env
        (fn [m _args] (swap! envelopes conj (:envelope m))))
      (rf/reg-event :wi/parent
        (fn [_ _]
          {:fx [[:wi/capture-env]
                [:dispatch [:wi/child]]]}))
      (rf/reg-event :wi/child
        (fn [_ _]
          {:fx [[:wi/capture-env]]}))

      ;; Parent supplies an explicit :rf/time-ms; the child must NOT inherit it.
      (rf/dispatch-sync [:wi/parent]
                        {:frame :wi/cascade
                         :rf.cofx {:rf/time-ms 1781078400000}})

      (let [[parent-env child-env] @envelopes
            parent-t (get-in parent-env [:rf.cofx :rf/time-ms])
            child-t  (get-in child-env  [:rf.cofx :rf/time-ms])]
        (is (= [:wi/parent] (:event parent-env)) "first capture is the parent")
        (is (= [:wi/child]  (:event child-env))  "second capture is the child")
        (is (= 1781078400000 parent-t) "parent carries the supplied :rf/time-ms")
        (is (number? child-t) "child has its own stamped :rf/time-ms")
        (is (not= 1781078400000 child-t)
            "child did NOT inherit the parent's :rf/time-ms — distinct causal token (EP-0010)")))))

;; ===========================================================================
;; rf2-irbjjq: a :dispatch-later child gets a FRESH :rf.cofx map stamped at
;; FIRE time (the causal boundary is when the deferred dispatch RUNS, not when
;; it was enqueued) — :rf/time-ms is NOT the parent's, NOT the enqueue-time clock,
;; while the inherited envelope fields (:frame / :trace-id / :origin) still
;; propagate from the parent.
;;
;; EP-0010 §Dispatch Envelope Stamping names BOTH :dispatch and :dispatch-later
;; as child causal-token producers. The existing child-dispatch-gets-fresh-
;; time-ms (above) covers the IMMEDIATE :dispatch child; the deferred path is
;; distinct because `:dispatch-later` wraps the router `dispatch!` in
;; `interop/set-timeout!` (re-frame.fx §reserved-fx-handlers), so the child's
;; `build-envelope` — and thus its `:rf.cofx` stamp — happens inside
;; the timer callback, an arbitrary wall-clock interval after the parent
;; settled. `:rf.cofx` is deliberately ABSENT from
;; `re-frame.fx/inheritable-envelope-keys`, so the deferred child is stamped
;; a fresh `:rf/time-ms` at fire from `interop/epoch-now-ms` rather than copying
;; the parent's — the mechanism the existing :rf.cofx tests pin only for
;; the synchronous case.
;;
;; We make the wall clock ADVANCE between enqueue and fire (a mutable clock
;; redef) and capture the timer thunk via a `set-timeout!` redef so we fire it
;; AFTER advancing — adversarially separating the three candidate stamp times
;; (parent token / enqueue clock / fire clock). A regression that inherited
;; the parent's :rf/time-ms, or that stamped at enqueue time, or that added
;; :rf.cofx to the inheritable set, fails loudly here.
;; ===========================================================================

(deftest dispatch-later-child-gets-fresh-cofx-stamped-at-fire-time
  (testing "a :fx [[:dispatch-later …]] child is stamped a FRESH :rf.cofx at
            FIRE time — :rf/time-ms is the fire-time clock, NOT the parent token,
            NOT the enqueue-time clock; inherited fields still propagate"
    (rf/reg-frame :wi/later {:doc "ctx"})
    (let [;; mutable wall clock — the value `epoch-now-ms` reads moves between
          ;; enqueue (parent dispatch) and fire (deferred thunk run).
          clock          (atom 1000)
          ;; capture the deferred timer thunk so the test fires it MANUALLY
          ;; after advancing the clock (rather than relying on a real timer).
          deferred       (atom nil)
          captured-child (atom nil)]
      ;; The deferred child reads its own envelope off the fx-handler ctx
      ;; (:envelope m) — the same surface child-dispatch-gets-fresh-time-ms
      ;; uses — so we observe the :rf.cofx map the router stamped for it.
      (rf/reg-fx :wi.later/capture-env
        (fn [m _args] (reset! captured-child (:envelope m))))
      (rf/reg-event :wi.later/parent
        (fn [_ _]
          {:fx [[:dispatch-later {:ms 50 :event [:wi.later/child]}]]}))
      (rf/reg-event :wi.later/child
        (fn [_ _]
          {:fx [[:wi.later/capture-env]]}))

      (with-redefs [interop/epoch-now-ms (fn [] @clock)
                    ;; capture the deferred thunk; do NOT run it yet, so the
                    ;; clock can advance before the child's build-envelope runs.
                    interop/set-timeout! (fn [f _ms] (reset! deferred f) :handle)
                    ;; the deferred thunk calls the ASYNC router `dispatch!`,
                    ;; which schedules its drain on `interop/next-tick` (a
                    ;; separate executor thread on the JVM). Run next-tick
                    ;; INLINE so the deferred child's cascade settles
                    ;; synchronously within this test — the clock read under
                    ;; test is at the deferred dispatch's `build-envelope`,
                    ;; which the inline drain reaches deterministically.
                    interop/next-tick   (fn [f] (f) nil)]
        ;; Parent supplies an explicit token :rf/time-ms and rides a distinctive
        ;; :trace-id / :origin so we can assert inheritance onto the child.
        (rf/dispatch-sync [:wi.later/parent]
                          {:frame           :wi/later
                           :trace-id        :wi.later/T
                           :origin          :ui
                           :rf.cofx {:rf/time-ms 1781078400000}})
        ;; ENQUEUE happened at clock=1000; the child's envelope is NOT built yet
        ;; (it is deferred). Advance the wall clock, THEN fire the deferred
        ;; thunk so the child's build-envelope reads the ADVANCED clock.
        (is (some? @deferred) "the :dispatch-later scheduled a deferred thunk")
        (is (nil? @captured-child)
            "the child has not run yet — it is genuinely deferred")
        (reset! clock 5000)            ; wall clock advanced between enqueue and fire
        (@deferred))                   ; fire the deferred dispatch at clock=5000

      (let [child-env @captured-child
            child-t   (get-in child-env [:rf.cofx :rf/time-ms])]
        (is (some? child-env) "the deferred child ran when the thunk fired")
        (is (= [:wi.later/child] (:event child-env)) "captured the child event")
        ;; FRESH at FIRE — the three adversarial candidates are separated:
        (is (= 5000 child-t)
            "child :rf/time-ms is the FIRE-time clock (5000) — stamped when the
             deferred dispatch RAN, not at enqueue")
        (is (not= 1781078400000 child-t)
            "child did NOT inherit the parent token's :rf/time-ms — distinct
             causal token (EP-0010 §Dispatch Envelope Stamping)")
        (is (not= 1000 child-t)
            "child :rf/time-ms is NOT the enqueue-time clock — the stamp is read
             at fire, inside the timer callback, not captured at schedule time")
        ;; INHERITED envelope fields still propagate from the parent.
        (is (= :wi/later (:frame child-env))
            ":frame is inherited onto the deferred child")
        (is (= :wi.later/T (:trace-id child-env))
            ":trace-id is inherited onto the deferred child")
        (is (= :origin (key (find child-env :origin)))
            "the :origin slot is present on the child envelope")
        (is (= :ui (:origin child-env))
            ":origin is inherited onto the deferred child")
        ;; The deferred child's :source reflects its OWN immediate trigger
        ;; (the :dispatch-later fx), NOT inherited (rf2-ejtpd) — a foil that
        ;; confirms the inheritance set is exactly the trace-context keys, not
        ;; "everything", so :rf.cofx being excluded is the same shape.
        (is (= :fx-dispatch-later (:source child-env))
            "the deferred child's :source is its immediate trigger
             (:fx-dispatch-later), stamped by the fx handler — not inherited")))))

;; ===========================================================================
;; Coeffect visibility + trace projection filtering
;; ===========================================================================

(deftest cofx-visible-as-coeffect
  (testing "handlers read :rf.cofx from the coeffect map"
    (rf/reg-frame :wi/cofx {:doc "ctx"})
    (let [cofx (capture-coeffects :wi/cofx
                                  {:rf.cofx {:rf/time-ms 1781078400456}})]
      (is (contains? cofx :rf.cofx)
          ":rf.cofx is a framework coeffect in the initial context")
      (is (= 1781078400456 (get-in cofx [:rf.cofx :rf/time-ms]))
          "the supplied :rf/time-ms is what the handler reads"))))

(deftest cofx-filtered-from-user-cofx-projection
  (testing "fx/user-injected-coeffects strips :rf.cofx like the other framework defaults"
    (is (contains? fx/framework-coeffect-keys :rf.cofx)
        ":rf.cofx is in the framework-coeffect-keys filter set")
    (let [cofx {:db {} :event [:e] :rf.db/runtime {} :rf.frame/id :f
                :rf.cofx {:rf/time-ms 1781078400789}
                :my/cofx 1}]
      (is (= {:my/cofx 1} (fx/user-injected-coeffects cofx))
          ":rf.cofx does NOT appear in the user-cofx trace projection"))))

;; ===========================================================================
;; :dispatched-at is retired
;; ===========================================================================

(deftest dispatched-at-is-gone
  (testing "EP-0010 rider b: :dispatched-at is retired from the envelope (no coexistence)"
    (rf/reg-frame :wi/no-dispatched-at {:doc "ctx"})
    (testing "absent even with the dev gate ON (it is not merely prod-elided — it is gone)"
      (with-redefs [interop/debug-enabled? true]
        (is (not (contains? (build-envelope [:noop] {:frame :wi/no-dispatched-at})
                            :dispatched-at))
            "no :dispatched-at key on the envelope")))
    (testing "the durable causal-time fact is (:rf/time-ms (:rf.cofx env)) instead"
      (let [env (build-envelope [:noop] {:frame :wi/no-dispatched-at})]
        (is (number? (get-in env [:rf.cofx :rf/time-ms]))
            ":rf/time-ms is the replacement for the retired :dispatched-at")))))

(deftest dispatched-at-supplied-is-a-generic-unknown-opt-with-did-you-mean
  ;; rf2-8rtuiq (Mike ruled OPTION A): `:dispatched-at` only ever named a fact
  ;; in the spec's own DRAFTS — it never shipped in a released artefact — so
  ;; under the shipped-names-only tombstone rule (Conventions §The tombstone
  ;; rule) it earns NO dedicated retired-name error id. Supplying it is caught
  ;; by the GENERIC unrecognised-opt surface (`:rf.warning/unknown-dispatch-opt`,
  ;; dev-gated warn), the dispatch PROCEEDS, and the warning message appends a
  ;; did-you-mean naming (:rf/time-ms (:rf.cofx envelope)).
  (testing "supplying :dispatched-at does NOT throw — it is an unrecognised opt"
    (rf/reg-frame :wi/retired-supply {:doc "ctx"})
    (testing "build-envelope does not throw on the retired draft key (it is unknown, not a hard error)"
      (is (map? (build-envelope [:noop] {:frame :wi/retired-supply
                                         :dispatched-at 123}))
          "supplying :dispatched-at yields a normal envelope, not a throw")
      (is (not (contains? (build-envelope [:noop] {:frame :wi/retired-supply
                                                   :dispatched-at 123})
                          :dispatched-at))
          "the unrecognised key is not copied onto the envelope"))
    (testing "the generic unknown-dispatch-opt warning fires with a did-you-mean naming :rf/time-ms / :rf.cofx"
      (rf/reg-event :wi/retired-noop (fn [{:keys [db]} _] {:db db}))
      (let [seen (atom [])]
        (rf/register-listener! :trace ::dispatched-at
          (fn [ev] (swap! seen conj ev)))
        (binding [frame/*current-frame* :wi/retired-supply]
          (rf/dispatch-sync [:wi/retired-noop] {:dispatched-at 123}))
        (rf/unregister-listener! :trace ::dispatched-at)
        (let [warns (filterv (fn [ev]
                               (and (= :warning (:op-type ev))
                                    (= :rf.warning/unknown-dispatch-opt (:operation ev))))
                             @seen)]
          (is (= 1 (count warns))
              "the retired draft key trips exactly one generic unknown-opt warning")
          (let [t (:tags (first warns))]
            (is (contains? (set (:unknown-keys t)) :dispatched-at)
                "the retired key is named as an unknown opt")
            (is (re-find #":rf/time-ms" (:reason t))
                "the warning message references :rf/time-ms as the replacement")
            (is (re-find #":rf\.cofx" (:reason t))
                "the warning message references :rf.cofx as the replacement carrier")
            (is (= :no-recovery (:recovery (first warns)))
                "observational — the dispatch proceeds unchanged")))))))

;; ===========================================================================
;; rf2-sppf0m / rf2-alc1lf: a handler READS owner-qualified facts from the
;; flat :rf.cofx coeffect and WRITES the supplied values into a durable app-db
;; entity.
;;
;; This is the EP-0010 §Validation/Conformance bullet — "random/UUID values
;; supplied by fixtures become durable ids exactly as supplied" — executed
;; against an actual durable WRITE, not merely the envelope pass-through that
;; `preserves-caller-supplied-extra-keys-and-fills-time-ms` (above) pins. Under
;; EP-0017 the recordable coeffects are flat (one fact per owner-qualified key,
;; no grouping sub-maps): a `:todo/create` handler reads `(:todo/id cofx)` +
;; `(:todo/color cofx)` and folds them into app-db so the replay log explains
;; every durable value. No framework generator vocabulary is involved (EP-0017
;; §Non-Goals) — the test scripts the facts directly, exactly as a fixture /
;; replay / SSR-hydration dispatch would, and the handler reads them straight
;; from the flat coeffect map.
;; ===========================================================================

(deftest supplied-uuid-random-become-durable-ids-exactly
  (testing "a handler reads owner-qualified facts from the flat :rf.cofx coeffect
            and the supplied values land in app-db EXACTLY as supplied"
    (rf/reg-frame :wi/todos {:doc "ctx"})
    ;; The durable handler: reads the causal token's scripted id + colour from
    ;; the :rf.cofx coeffect (NOT an ambient `random-uuid` / `rand-nth`) and
    ;; folds them into a durable app-db entity. A `reg-event` handler so we can
    ;; read the `:rf.cofx` framework coeffect off the cofx map.
    (rf/reg-event :todo/create
      (fn [{:keys [db] cofx :rf.cofx} [_ text]]
        (let [id    (:todo/id cofx)
              color (:todo/color cofx)]
          {:db (assoc-in db [:todos id]
                         {:todo/id id :todo/color color :todo/text text})})))
    (let [id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          color :green]
      (rf/dispatch-sync [:todo/create "buy milk"]
                        {:frame :wi/todos
                         :rf.cofx {:todo/id    id
                                   :todo/color color}})
      (let [entity (get-in (rf/app-db-value :wi/todos) [:todos id])]
        (is (some? entity)
            "the entity is keyed in app-db under the EXACT supplied uuid")
        (is (= id (:todo/id entity))
            "the durable :todo/id equals the supplied uuid exactly — the
             replay log explains the durable id (EP-0010 §Conformance)")
        (is (= color (:todo/color entity))
            "the durable :todo/color equals the supplied :random value exactly")
        (is (= "buy milk" (:todo/text entity))
            "the event arg rides through alongside the :rf.cofx ids")))))

(deftest supplied-uuid-replay-stable-where-ambient-would-diverge
  (testing "re-running the SAME causal token reproduces the SAME durable id
            (replay-stable), where an ambient random-uuid / rand-nth would have
            diverged run-to-run (EP-0010 §Restore, Replay, And Hydration)"
    (rf/reg-frame :wi/replay {:doc "ctx"})
    (rf/reg-event :todo/create-from-token
      (fn [{:keys [db] cofx :rf.cofx} _]
        (let [id    (:todo/id cofx)
              color (:todo/color cofx)]
          {:db (assoc db :entity {:todo/id id :todo/color color})})))
    ;; The scripted causal token — the SAME map supplied on both runs, exactly
    ;; as a replay / restore would re-feed it.
    (let [token {:todo/id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
                 :todo/color :blue}
          run!  (fn []
                  (rf/dispatch-sync [:todo/create-from-token]
                                    {:frame :wi/replay :rf.cofx token})
                  (:entity (rf/app-db-value :wi/replay)))
          first-entity  (run!)
          second-entity (run!)]
      (is (= first-entity second-entity)
          "two runs of the same token produce IDENTICAL durable entities —
           replay-stable")
      (is (= (:todo/id token) (:todo/id second-entity))
          "the reproduced durable id is the token's id, not a fresh draw")
      ;; Contrast: an ambient generator (random-uuid / rand-nth) folded into a
      ;; durable write would have produced two DIFFERENT ids across the two
      ;; runs. Pin that this is the failure mode the token-read design avoids —
      ;; two independent ambient draws are (with overwhelming probability)
      ;; distinct, so a handler that read ambient instead of the token would
      ;; NOT be replay-stable.
      (let [ambient-1 (random-uuid)
            ambient-2 (random-uuid)]
        (is (not= ambient-1 ambient-2)
            "two ambient random-uuid draws diverge — the very property a
             durable write must NOT depend on; reading the token avoids it")))))

;; ===========================================================================
;; rf2-zq5zj2: ambient DIAGNOSTIC timestamps may differ without changing
;; durable state.
;;
;; This is the positive half of the EP-0008 / EP-0010 causal-vs-diagnostic
;; split (§Validation/Conformance bullet: "ambient diagnostic timestamps may
;; differ without changing durable state"). The runtime records BOTH a durable
;; CAUSAL time (the token's `:rf.cofx` `:rf/time-ms` → the epoch record's
;; `:committed-at`, read ONCE at the causal boundary from `epoch-now-ms`) AND
;; ambient DIAGNOSTIC times (the trace event `:time`, stamped from the elapsed
;; `interop/now-ms` at every emit). The bullet asserts the ambient ones are
;; free to vary run-to-run while the durable projection stays EQUAL.
;;
;; We run the SAME scripted token (same supplied `:rf/time-ms`) twice under two
;; DIFFERENT ambient clocks and assert:
;;   (a) the durable :committed-at is EQUAL across both runs (it folds the
;;       supplied token time, never the ambient clock), AND
;;   (b) a diagnostic trace `:time` (captured via a trace listener) DIFFERS
;;       across the two runs (it legitimately reads the ambient clock).
;; That EXECUTES the invariant instead of documenting it (the prose at the
;; top of this ns + the inverse-only `:committed-at` clock tests in
;; epoch_test.clj are the prior coverage). `rf/epoch-history` is available on
;; the core test classpath as the epoch test-only dep.
;; ===========================================================================

(deftest ambient-diagnostic-time-differs-while-durable-committed-at-holds
  (testing "same causal token under two different ambient clocks → durable
            :committed-at EQUAL while the diagnostic trace :time DIFFERS"
    (rf/reg-frame :wi/split {:doc "ctx"})
    (rf/reg-event :wi/note (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [token-time   1781078400123     ; the supplied causal token :rf/time-ms
          ;; capture the diagnostic trace :time of THIS frame's :wi/note event
          ;; per run — the trace `:time` is stamped from the ambient
          ;; `interop/now-ms` (re-frame.trace/build-event), the diagnostic
          ;; surface the bullet says is free to vary.
          trace-times  (atom [])
          run!         (fn [ambient-clock]
                         (let [seen (atom nil)]
                           (rf/register-listener! :trace ::split-probe
                             (fn [ev]
                               ;; Capture the diagnostic :time of THIS frame's
                               ;; :wi/note dispatched event — a deterministic
                               ;; single emit per run. The event vector rides
                               ;; under (:rf.event/v :tags) as [event-id args]
                               ;; (re-frame.classification §project-event-tags).
                               (when (and (nil? @seen)
                                          (= :rf.event (:op-type ev))
                                          (= :rf.event/dispatched (:operation ev))
                                          (= :wi/note (first (get-in ev [:tags :rf.event/v]))))
                                 (reset! seen (:time ev)))))
                           ;; Pin BOTH host clocks to the SAME per-run ambient
                           ;; value so the trace :time is deterministic within
                           ;; the run yet DIFFERENT between the two runs, while
                           ;; the SUPPLIED token :rf/time-ms rides through
                           ;; unchanged (the router only fills :rf/time-ms when
                           ;; absent — see preserves-caller-supplied-time-ms).
                           (with-redefs [interop/now-ms       (constantly ambient-clock)
                                         interop/epoch-now-ms (constantly ambient-clock)]
                             (rf/dispatch-sync [:wi/note]
                                               {:frame :wi/split
                                                :rf.cofx {:rf/time-ms token-time}}))
                           (rf/unregister-listener! :trace ::split-probe)
                           (swap! trace-times conj @seen)
                           ;; the durable :committed-at of the just-settled epoch
                           (:committed-at (last (rf/epoch-history :wi/split)))))
          committed-1  (run! 1000)
          committed-2  (run! 9999999)]
      ;; (a) DURABLE side — equal across the two ambient clocks.
      (is (= token-time committed-1)
          "run 1: durable :committed-at folds the supplied token :rf/time-ms")
      (is (= token-time committed-2)
          "run 2: durable :committed-at folds the SAME supplied token :rf/time-ms")
      (is (= committed-1 committed-2)
          "durable :committed-at is EQUAL across runs — wall-clock drift
           between the two commits did not change durable state")
      ;; (b) DIAGNOSTIC side — differs across the two ambient clocks.
      (let [[t1 t2] @trace-times]
        (is (= 1000 t1)
            "run 1: the diagnostic trace :time read the ambient clock (1000)")
        (is (= 9999999 t2)
            "run 2: the diagnostic trace :time read the DIFFERENT ambient clock")
        (is (not= t1 t2)
            "the ambient diagnostic trace :time DIFFERS run-to-run — free to
             vary, exactly as the EP-0010 causal/diagnostic split permits,
             while the durable :committed-at above held equal")))))
