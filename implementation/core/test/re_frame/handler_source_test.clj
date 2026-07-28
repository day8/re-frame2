(ns re-frame.handler-source-test
  "rf2-xgfuy — DEBUG-gated handler form-source capture at `reg-event`.
  Per Spec 009 §`:rf.handler/source` and Xray Spec 021 §11.2 B.7 stretch.

  The `reg-event` macro stamps the whole `(reg-event :id ...)` form
  as a string into the handler's registry metadata under
  `:rf.handler/source` so Xray's Event panel can render the source
  inline. The capture is DEBUG-gated on BOTH platforms — CLJS so
  `:advanced` + `goog.DEBUG=false` DCEs the literal source-string bytes,
  JVM so `-Dre-frame.debug=false` (the documented SSR/production setting)
  gets the same treatment. See `re-frame.core_reg_macros/defreg-
  event-macro` for the emission and `re-frame.events/merge-form-
  source` for the registrar-side merge. EP-0018 (rf2-xhfxcs.14) collapsed
  the former `reg-event-{db,fx,ctx}` macros onto the one `reg-event`.

  (This docstring used to say \"JVM-side is always-on (bundle-size argument
  doesn't apply)\". Measured under the real gate that is false, and the source
  agrees: `with-form-source-form` binds `*pending-form-source*` to
  `(if interop/debug-enabled? <src> nil)` with no platform reader-conditional,
  and `merge-form-source` opens with `(if-not interop/debug-enabled? m …)`.
  Corrected here rather than left to mislead the next reader — rf2-d2841.)

  ## Posture split (rf2-d2841)

  AUTO-capture is therefore dev-only, and every auto-capture assertion is kept
  verbatim inside a `(when interop/debug-enabled? …)` arm.

  The always-on half is that the form-source-capturing MACRO PATH still
  registers a live handler. That is not a formality: the macro's production
  arm is a DIFFERENT expansion from its dev arm, so a broken prod arm would
  drop or mis-shape the registration outright — and this file is where that
  would show. Every deftest keeps an unguarded `handler-meta` witness.

  `user-supplied-source-wins` is UNGUARDED and stays load-bearing under the
  gate: `merge-form-source` returns the metadata map untouched in production,
  so a `:rf.handler/source` the caller authored explicitly (a code-gen pass
  stamping the original site) SURVIVES production while the auto-captured one
  does not. That asymmetry is the contract, and asserting it is only
  meaningful in the production posture.

  The two ABSENCE deftests — `fn-form-call-skips-form-source` and
  `reg-sub-does-not-capture-form-source` — are inside the arm rather than
  outside it. Under the gate `:rf.handler/source` is elided WHOLESALE, so
  \"absent on the fn-form path\" would pass because the key never exists for
  ANY path, not because the fn-form skipped it: the same false-green shape as
  a negative over an empty trace ring."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interceptor :as interceptor]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.subs :as subs]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shared assertions ----------------------------------------------------

(defn- assert-source [kind id macro-name]
  (let [m   (rf/handler-meta kind id)
        src (:rf.handler/source m)]
    ;; Always-on witness (rf2-d2841): the macro path registered, in EITHER
    ;; posture. The prod arm of the expansion is what would break here.
    (is (some? m) (str "handler-meta for " kind " " id " should be present"))
    (is (ifn? (:handler-fn m))
        (str "the registered handler-fn is live for " kind " " id))
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
    (when interop/debug-enabled?
      (is (string? src)
          (str ":rf.handler/source should be a string for " kind " " id))
      (is (str/includes? src macro-name)
          (str ":rf.handler/source should include the macro name '" macro-name "'"))
      (is (str/includes? src (pr-str id))
          (str ":rf.handler/source should include the id literal '" (pr-str id) "'")))))

;; ---- per-kind captures ----------------------------------------------------

(deftest reg-event-captures-form-source
  (testing "EP-0018 C (rf2-xhfxcs.3): the ONE public `reg-event` macro stamps
  :rf.handler/source on JVM — the same whole-form capture the legacy
  reg-event-{db,fx,ctx} macros do (the macro layer consolidated onto
  defreg-event-macro, so `reg-event` rides the identical form-source path)"
    (rf/reg-event :rf2-xhfxcs/event-sample
                  (fn [{:keys [db]} _ev] {:db db}))
    (assert-source :event :rf2-xhfxcs/event-sample "reg-event")
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
    (when interop/debug-enabled?
      (let [src (:rf.handler/source
                 (rf/handler-meta :event :rf2-xhfxcs/event-sample))]
        ;; Whole-form capture: the handler-fn body (the fx-shape return) must
        ;; appear, not just the surface.
        (is (str/includes? src "(fn [{:keys [db]} _ev] {:db db})")
            ":rf.handler/source should include the reg-event handler-fn body")
        (is (str/includes? src ":db")
            ":rf.handler/source should include the effect-map keyword")))))

;; EP-0018 (rf2-xhfxcs.14): the former `reg-event-db` / `reg-event-fx`
;; per-kind capture tests collapsed into `reg-event-captures-form-source`
;; above — the three macros are now one, so a db-shape and an fx-shape body
;; both ride the identical `reg-event` form-source path. The retired-name
;; throwing stubs register nothing, so there is no per-stub capture to pin.

(deftest reg-event-full-context-interceptor-captures-form-source
  (testing "rf2-xgfuy: reg-event with a full-context interceptor stamps :rf.handler/source on JVM"
    (rf/reg-interceptor :rf2-xgfuy/ctx-probe {:before (fn [ctx] ctx)})
    (rf/reg-event :rf2-xgfuy/event-ctx-sample
                  {:interceptors [:rf2-xgfuy/ctx-probe]}
                  (fn [_ _] {}))
    (assert-source :event :rf2-xgfuy/event-ctx-sample "reg-event")))

;; ---- middle slot: metadata-map / metadata :interceptors -------------------
;;
;; The `reg-event` surface accepts a metadata-map middle slot (per
;; re-frame.events/normalise-args). The form-source capture is mechanically
;; `pr-str` of the WHOLE form, so metadata and metadata `:interceptors`
;; round-trip through the slot — no special-casing.

(deftest captures-form-source-with-metadata-map
  (testing "rf2-xgfuy: middle metadata-map round-trips into :rf.handler/source"
    (rf/reg-event :rf2-xgfuy/event-with-meta
                     {:doc "metadata-shape middle slot"}
                     (fn [{:keys [db]} _] {:db db}))
    ;; Always-on witness (rf2-d2841): the metadata-map middle slot registered.
    (is (some? (rf/handler-meta :event :rf2-xgfuy/event-with-meta))
        "the metadata-map middle slot registers in BOTH postures")
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
    (when interop/debug-enabled?
      (let [src (:rf.handler/source
                 (rf/handler-meta :event :rf2-xgfuy/event-with-meta))]
        (is (string? src))
        (is (str/includes? src ":doc"))
        (is (str/includes? src "metadata-shape middle slot"))))))

(deftest captures-form-source-with-metadata-interceptors
  (testing "rf2-xgfuy: metadata :interceptors round-trips into :rf.handler/source"
    ;; The framework `unwrap-interceptor` value was removed (EP-0022 /
    ;; rf2-3qeu38); register a tiny PROJECT-LOCAL `:app/unwrap` interceptor and
    ;; reference it by id, so this round-trip test no longer depends on a
    ;; framework-owned value. Only the metadata `:interceptors` chain needs to
    ;; round-trip into the source string — the interceptor's :before is a no-op.
    (rf/reg-interceptor :app/unwrap
                         (interceptor/->interceptor* :id :app/unwrap
                                                     :before identity))
    (rf/reg-event :rf2-xgfuy/event-with-icpts
                     {:interceptors [:app/unwrap]}
                     (fn [_cofx {:keys [v]}] {:db {:v v}}))
    ;; Always-on witness (rf2-d2841): the metadata `:interceptors` ref
    ;; threaded into the stored chain — the production-visible half of the
    ;; round-trip this deftest is about.
    (is (= [:app/unwrap :rf/event-handler]
           (mapv (fn [e] (if (keyword? e) e (:id e)))
                 (:interceptors (rf/handler-meta :event :rf2-xgfuy/event-with-icpts))))
        "the metadata :interceptors ref sits before the framework wrapper")
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
    (when interop/debug-enabled?
      (let [src (:rf.handler/source
                 (rf/handler-meta :event :rf2-xgfuy/event-with-icpts))]
        (is (string? src))
        (is (str/includes? src "unwrap"))))))

;; ---- programmatic call (bypasses macro) ----------------------------------

(deftest fn-form-call-skips-form-source
  (testing "rf2-xgfuy: calling the underlying fn directly skips form-source capture
  (so programmatic / fixture-synthesised registrations don't carry
  poison strings from inside the framework)"
    ((requiring-resolve 're-frame.events/reg-event)
       :rf2-xgfuy/programmatic
       (fn [{:keys [db]} _] {:db db}))
    (let [m (rf/handler-meta :event :rf2-xgfuy/programmatic)]
      (is (some? m))
      ;; rf2-d2841 — dev-instrumentation arm. A NEGATIVE about a key the gate
      ;; elides WHOLESALE: outside the arm this passes because
      ;; `:rf.handler/source` is never present for ANY path in production, not
      ;; because the fn-form skipped capture.
      (when interop/debug-enabled?
        (is (not (contains? m :rf.handler/source))
            ":rf.handler/source absent on direct fn call")))))

;; ---- non-event reg-* macros DON'T carry :rf.handler/source ---------------

(deftest reg-sub-does-not-capture-form-source
  (testing "rf2-xgfuy: reg-sub is scoped to coord capture only — form-source
  capture is explicit to `reg-event` (Spec 009 §`:rf.handler/source`)"
    (rf/reg-sub :rf2-xgfuy/sub-sample (fn [db _] db))
    (let [m (rf/handler-meta :sub :rf2-xgfuy/sub-sample)]
      (is (some? m))
      ;; rf2-d2841 — dev-instrumentation arm. Same wholesale-elision shape as
      ;; `fn-form-call-skips-form-source` above.
      (when interop/debug-enabled?
        (is (not (contains? m :rf.handler/source))
            ":rf.handler/source absent on reg-sub")))))

;; ---- user-supplied :rf.handler/source override ---------------------------

(deftest user-supplied-source-wins
  (testing "rf2-xgfuy: explicit :rf.handler/source in user metadata overrides
  auto-capture (mirrors source-coords/merge-coords semantics so
  tooling that synthesises registrations can stamp the original
  source-string)"
    (rf/reg-event :rf2-xgfuy/explicit-source
                     {:rf.handler/source "(rf/reg-event :elsewhere ...)"
                      :doc "hand-stamped source from a code-gen pass"}
                     (fn [{:keys [db]} _] {:db db}))
    (let [m (rf/handler-meta :event :rf2-xgfuy/explicit-source)]
      (is (= "(rf/reg-event :elsewhere ...)" (:rf.handler/source m))))))
