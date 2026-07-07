(ns re-frame.frame-provider-context-dom-cljs-test
  "Frame-provider runtime React-context test coverage (rf2-22ds).

  Per Spec 002 §Reading the frame from React context and Spec 006
  §Frame-provider via React context: the resolution chain at a CLJS
  subscribe / dispatch call site is

    1. `re-frame.frame/*current-frame*` (dynamic var; set by `with-frame`
       / `bind-fn`)
    2. closest enclosing `frame-provider` via React context
    3. nil — no scope. EP-0002 (rf2-69r7ui): there is NO `:rf/default`
       floor. The createContext default is the no-provider sentinel, the
       reader returns nil, and a public frame-scoped op (subscribe /
       dispatch / current-frame-id) raises `:rf.error/no-frame-context`.

  PR #195 (rf2-d4sf) made the React-context tier the *canonical* path
  for `(rf/subscribe ...)` and `(rf/dispatch ...)` from inside a
  rendered tree (subscribe / the dispatch envelope's `:frame` default
  consult `:adapter/current-frame` through the late-bind hook). This
  ns covers the seven runtime scenarios called out by the bead's
  audit (rf2-o423):

    1. Nested-provider inheritance — inner provider wins over outer.
    2. No-provider → no-frame-context. EP-0002 (rf2-69r7ui): a view
       rendered outside any `frame-provider` resolves to nil and a
       subscribe / current-frame-id raises `:rf.error/no-frame-context`
       (NOT a silent `:rf/default`). Establish an explicit provider to
       scope a frame.
    3. Context-not-present error path — corrupted / non-keyword context
       value should surface diagnostically.
    4. Cross-frame subscribe resolution — subscribe routes against the
       wrapped frame.
    5. Cross-frame dispatch resolution — dispatch routes against the
       wrapped frame.
    6. React-19 strict-mode composition — frame-provider + reg-view'd
       descendants render correctly under React.StrictMode.
    7. React-19 concurrent-rendering / suspense — provider survives
       across re-renders + suspense boundaries (act-wrapped).

  Browser-only — every scenario requires a real React render so the
  React-context tier actually pushes the Provider's value. The
  `-dom-cljs-test$` suffix (rf2-2hrj8) opts this file into the
  `:browser-test` build; `:node-test` still loads it (matches
  `cljs-test$`) and the DOM-mounting branches gate on `(browser?)`
  and exit early under :node-test where `js/document` is absent.

  Adapter target: stock Reagent (the artefact on main). The
  reagent-slim track is in flight; once it lands the same scenarios
  re-validate against that adapter without changes here.

  Some overlap with cross_spec_cljs_test.cljs §rf2-d4sf is
  intentional — that suite covers the cross-spec interactions of
  PR #195 broadly; this suite covers the seven-scenario surface
  contract in one place.

  Frame-id naming convention: the seven seven-scenario tests below
  use unnamespaced frame keywords (e.g. `:rf-22ds-1-outer`) — they
  pre-date the namespace-preservation contract and are kept as-is
  so the diff stays focused. The
  `namespaced-frame-id-survives-react-context-round-trip` regression
  test pins the contract that `rf/frame-provider` with a namespaced
  frame keyword (e.g. `:tenant/admin`) preserves the namespace across
  the React-context round trip — the canonical surface mounts the
  Provider via Reagent's `:r>` interop head, which bypasses
  `convert-prop-value`. A raw-hiccup mount via
  `[:> (.-Provider frame-context) {:value :foo/bar}]` (NOT via
  `rf/frame-provider`) still drops the namespace under the classic
  adapter because that path passes through stock Reagent's
  `convert-prop-value`; the shared
  `re-frame.adapter.context/coerce-context-value` is the defensive
  cover for that raw-hiccup case.

  Scenario-3 asserts the structured `:rf.error/frame-context-corrupted`
  trace event fires on a corrupted `_currentValue` read (rf2-8q66
  closed). EP-0002 (rf2-69r7ui): recovery is now `:no-frame-context` —
  the reader returns nil (NOT a synthesised `:rf/default`); a public
  frame-scoped op reading that nil then raises
  `:rf.error/no-frame-context`. The corruption error event is its own
  distinct diagnostic surface."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.views]
            [re-frame.views.owned-frame :as owned-frame])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` OPTS OUT of the fixture's default
;; ambient `*current-frame*` :rf/default scope. EVERY render-based test in this
;; suite pins the React-context tier (tier 2) of the resolution chain — a
;; reg-view inside a `frame-provider` must resolve the provider's frame, and a
;; reg-view with NO provider must fail closed with `:rf.error/no-frame-context`.
;; The React renders below run SYNCHRONOUSLY inside `flushSync`, i.e. still
;; inside the test body's dynamic extent; an ambient :rf/default scope would
;; satisfy `current-frame-id` / `subscribe` / dispatch at tier 1 and the
;; React-context tier under test would never be consulted. Opting the whole
;; suite out keeps the resolution honest. (Scenario-3 + the prop-stringified
;; cases additionally `(binding [*current-frame* nil] …)` for belt-and-braces;
;; that is idempotent here.)
;; MAP-FORM fixture (not the fn-form `make-reset-runtime-fixture`): cljs.test
;; requires `:each` fixtures to be `{:before … :after …}` maps when the ns
;; contains ANY `async` test (a fn-form fixture's wrapper returns — running
;; its teardown — before the async body's `done` fires). Scenario-6-ensure (the
;; hot-reload gate) + the genuine-unmount test below are async (they advance
;; macrotask windows across a real React lifecycle). The before/after pair replicates
;; the runtime reset `make-reset-runtime-fixture` performs for THIS suite:
;; snapshot + restore the registrar, reset the frame registry + live-frame
;; index, dispose/reinstall the Reagent adapter, clear trace listeners,
;; ensure `:rf/default`. There is NO ambient `*current-frame*` binding —
;; this suite pins the React-context tier (the `:ambient-frame nil` opt-out
;; the fn-form fixture carried); an ambient :rf/default would shadow tier 2.
;; Mirrors the established async-DOM fixture idiom in
;; react_click_handler_frame_routing_cljs_test / dispatch_frame_capture.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (trace-tooling/clear-listeners!)
  (substrate-adapter/install-adapter! reagent-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "Return React's act() if available, else nil. React 18 ships act in
  react-dom/test-utils; React 19 promotes it to the React namespace
  proper. Either is fine for our purposes — both are sync-or-async-
  promise compatible with the same call shape. Used by Scenario 7
  (concurrent re-render flush) and the ENSURE StrictMode / hot-reload scenarios
  (act drives React's effect double-invoke deterministically — `flushSync`
  does NOT run the StrictMode passive-effect cleanup/re-setup synchronously,
  so the StrictMode reuse-no-reseed contract only surfaces under act)."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

;; ---- Scenario 1: nested-provider inheritance ------------------------------
;;
;; Per Spec 002 §What `frame-provider` is: a `frame-provider` scopes a
;; frame keyword to its subtree via React context. Per React's
;; createContext semantics, the closest enclosing Provider wins —
;; nested providers shadow outer ones in their own subtree. This test
;; pins that property end-to-end: a `:outer` provider wraps an
;; `:inner` provider wraps a reg-view'd probe; the probe must resolve
;; to `:inner`, and a subscribe inside it must read `:inner`'s app-db.

(deftest scenario-1-nested-provider-inner-wins
  "Scenario 1 — nested-provider inheritance.

   A `[rf/frame-provider {:frame :outer}]` wrapping a
   `[rf/frame-provider {:frame :inner}]` wrapping a reg-view'd probe:
   the probe sees `:inner`, and `(rf/subscribe ...)` inside resolves
   against `:inner`'s app-db (not `:outer`'s, not `:rf/default`'s)."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [outer :rf-22ds-1-outer
          inner :rf-22ds-1-inner]
      (rf/reg-frame outer {:doc "outer scenario-1 frame"})
      (rf/reg-frame inner {:doc "inner scenario-1 frame"})
      (rf/reg-event :seed-1 (fn [{:keys [db]} [_ v]] {:db {:v v}}))
      ;; Each frame's app-db carries a distinct value so the subscribe
      ;; tells us unambiguously which frame served the read.
      (rf/dispatch-sync [:seed-1 :outer-app-db] {:frame outer})
      (rf/dispatch-sync [:seed-1 :inner-app-db] {:frame inner})
      ;; EP-0002 (rf2-69r7ui): no bare `:rf/default` seed — every dispatch
      ;; carries an explicit frame. The inner-wins assertion compares
      ;; against the two scoped frames, which is the whole contract here.
      (rf/reg-sub :scenario-1/v (fn [db _] (:v db)))

      (let [resolved-frame (atom nil)
            resolved-value (atom nil)]
        (rf/reg-view* :rf.22ds-1/probe
                      (fn probe-impl []
                        (reset! resolved-frame (rf/current-frame-id))
                        (reset! resolved-value @(rf/subscribe [:scenario-1/v]))
                        [:div "probe"]))
        (let [render-fn  (rf/view :rf.22ds-1/probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root
                            ;; Outer provider wraps inner provider wraps probe.
                            [rf/frame-provider {:frame outer}
                             [rf/frame-provider {:frame inner}
                              [render-fn]]])))
            (is (= inner @resolved-frame)
                "current-frame inside the doubly-wrapped subtree resolves to the INNER provider's frame")
            (is (= :inner-app-db @resolved-value)
                "subscribe routes against the inner frame's app-db, not outer's, not :rf/default's")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---- Scenario 2: no-provider → no-frame-context ---------------------------
;;
;; EP-0002 (rf2-69r7ui): the createContext default is the no-provider
;; sentinel, NOT `:rf/default`. A reg-view rendered with NO enclosing
;; `frame-provider` resolves to nil — there is no ambient floor — so a
;; `subscribe` / `current-frame-id` raises `:rf.error/no-frame-context`.
;; The single-frame-app baseline is now ONE explicit root
;; `frame-provider` (or `with-frame`); inside that scope every call stays
;; ergonomic. This scenario pins BOTH halves: (a) no-provider →
;; no-frame-context, (b) an explicit provider scopes the frame correctly.

(deftest scenario-2-no-provider-raises-no-frame-context
  "Scenario 2 — no-provider → no-frame-context.

   A reg-view rendered outside any `frame-provider` resolves to nil; a
   subscribe / current-frame-id inside it raises
   `:rf.error/no-frame-context` (no silent `:rf/default`). Wrapping the
   same probe in an explicit `[rf/frame-provider {:frame …}]` makes the
   calls resolve correctly."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target :rf-22ds-2-scope]
      (rf/reg-frame target {:doc "scenario-2 explicit-scope frame"})
      (rf/reg-event :seed-2 (fn [{:keys [db]} _] {:db {:n 99}}))
      (rf/dispatch-sync [:seed-2] {:frame target})
      (rf/reg-sub :scenario-2/n (fn [db _] (:n db)))
      ;; (a) No provider → the probe's current-frame-id / subscribe raise
      ;; no-frame-context. Capture the error the render surfaces.
      (let [render-error (atom nil)]
        (rf/reg-view* :rf.22ds-2/probe-no-provider
                      (fn probe-no-provider-impl []
                        (try
                          (rf/current-frame-id)
                          (catch :default e (reset! render-error e)))
                        [:div "no-provider"]))
        (let [render-fn  (rf/view :rf.22ds-2/probe-no-provider)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                ;; No frame-provider in the tree.
                (rdc/render root [render-fn])))
            (is (some? @render-error)
                "no provider in the tree → current-frame-id raised (no :rf/default floor)")
            (is (= :rf.error/no-frame-context
                   (:rf.error/id (ex-data @render-error)))
                "the raised error is :rf.error/no-frame-context")
            (finally
              (try (rdc/unmount root) (catch :default _ nil))))))
      ;; (b) An explicit provider scopes the frame — the probe resolves
      ;; correctly and the subscribe routes against the scoped frame.
      (let [resolved-frame (atom nil)
            resolved-value (atom nil)]
        (rf/reg-view* :rf.22ds-2/probe-scoped
                      (fn probe-scoped-impl []
                        (reset! resolved-frame (rf/current-frame-id))
                        (reset! resolved-value @(rf/subscribe [:scenario-2/n]))
                        [:div "scoped"]))
        (let [render-fn  (rf/view :rf.22ds-2/probe-scoped)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target}
                                  [render-fn]])))
            (is (= target @resolved-frame)
                "an explicit frame-provider scopes the frame the probe resolves to")
            (is (= 99 @resolved-value)
                "subscribe routes against the explicitly-scoped frame's app-db")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---- Scenario 3: context-not-present error path ---------------------------
;;
;; Per the bead: "if the React-context boundary is corrupted (component
;; rendered through an unwrapped portal? misuse case), the failure is
;; observable + diagnostic — emits a structured error event, not a
;; silent fallback."
;;
;; rf2-8q66 closed: when `_currentValue` is a shape
;; `coerce-context-value` cannot resolve to a frame keyword (nil,
;; false, number, JS object, empty string), the runtime emits
;; `:rf.error/frame-context-corrupted` (op-type `:error`). EP-0002
;; (rf2-69r7ui): recovery is now `:no-frame-context` and the reader
;; returns **nil** (NOT a synthesised `:rf/default`) — a public
;; frame-scoped op reading that nil then raises
;; `:rf.error/no-frame-context`. The corruption event is its own distinct
;; diagnostic surface, never silently folded into ordinary 'no scope'.

(defn- corruption-traces [traces]
  (filter #(= :rf.error/frame-context-corrupted (:operation %)) @traces))

(deftest scenario-3-context-corrupted-emits-structured-error
  "Scenario 3 — context-not-present / corrupted error path.

   Asserts the rf2-8q66 + EP-0002 (rf2-69r7ui) contract: a non-coercible
   `_currentValue` on the shared frame-context emits
   `:rf.error/frame-context-corrupted` (op-type `:error`, recovery
   `:no-frame-context`) and the reader returns nil — NOT a synthesised
   `:rf/default`. The error event is the diagnostic surface; the nil
   return is what makes a public frame-scoped op raise
   `:rf.error/no-frame-context` rather than scope to a default.

   Direct test against the function-component-shape resolver because
   the only ways to corrupt `_currentValue` involve either bypassing
   React's normal Provider machinery (which the user-facing surface
   does not allow) or directly poking the field — the latter is what
   we do here, since it is the substrate-level seam the resolver
   reads."
  ;; EP-0002 (rf2-9o48ih): the reset-runtime fixture establishes an ambient
  ;; `*current-frame*` :rf/default scope (the carried-invariant equivalent of
  ;; wrapping every adapter test in `(with-frame :rf/default …)`). The
  ;; React-context corruption tier is the SECOND tier of
  ;; `function-component-current-frame` — only consulted when no dynamic scope
  ;; is bound. Clear the ambient scope so the `_currentValue` read (and its
  ;; corruption detection) is actually exercised; otherwise the dynamic-var
  ;; tier shadows it and the read resolves to :rf/default before the context
  ;; is ever inspected.
  (binding [frame/*current-frame* nil]
  (let [original (.-_currentValue ^js adapter-context/frame-context)]
    (with-trace-recorder! [traces]
      (try
        (testing "nil _currentValue: error trace fires; resolves to nil (no-frame-context)"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) nil)
          (is (nil? (adapter-context/function-component-current-frame))
              "returns nil — no synthesised :rf/default (EP-0002 carried invariant)")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one :rf.error/frame-context-corrupted event fired")
            (is (= :error (:op-type (first errs)))
                ":op-type is :error per Spec 009 §Error contract")
            (is (= :no-frame-context (:recovery (first errs)))
                ":recovery is :no-frame-context — no synthesised default")
            (is (= :nil (-> errs first :tags :type))
                ":tags :type names the corrupted shape")
            (is (contains? (-> errs first :tags) :received)
                ":tags :received carries the offending value")))
        (testing "false _currentValue: error trace fires; resolves to nil"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) false)
          (is (nil? (adapter-context/function-component-current-frame))
              "returns nil")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one error trace per corrupted read")
            (is (= :boolean (-> errs first :tags :type))
                ":tags :type identifies false as a boolean shape")))
        (testing "numeric _currentValue: error trace fires; resolves to nil"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) 42)
          (is (nil? (adapter-context/function-component-current-frame))
              "returns nil")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one error trace per corrupted read")
            (is (= :number (-> errs first :tags :type))
                ":tags :type identifies the number shape")
            (is (= 42 (-> errs first :tags :received))
                ":tags :received echoes the offending value")))
        (testing "JS object _currentValue: error trace fires; resolves to nil"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) #js {:not "a frame"})
          (is (nil? (adapter-context/function-component-current-frame))
              "returns nil")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one error trace per corrupted read")
            (is (= :js-object (-> errs first :tags :type))
                ":tags :type identifies the JS object shape")))
        (testing "empty-string _currentValue: error trace fires; resolves to nil"
          (reset! traces [])
          (set! (.-_currentValue ^js adapter-context/frame-context) "")
          (is (nil? (adapter-context/function-component-current-frame))
              "returns nil")
          (let [errs (corruption-traces traces)]
            (is (= 1 (count errs))
                "one error trace per corrupted read")
            (is (= :empty-string (-> errs first :tags :type))
                ":tags :type identifies empty-string distinctly from string")))
        (finally
          (set! (.-_currentValue ^js adapter-context/frame-context) original)))))))

;; ---- Scenario 4: cross-frame subscribe resolution -------------------------
;;
;; Per Spec 006 §706 / rf2-d4sf: `(rf/subscribe ...)` inside a wrapped
;; view consults the React-context tier and resolves the query against
;; the wrapped frame's app-db. This is also covered by
;; cross_spec_cljs_test/subscribe-routes-via-react-context-under-non-
;; default-frame; pinned here as the canonical seven-scenario surface.

(deftest scenario-4-subscribe-routes-against-wrapped-frame
  "Scenario 4 — cross-frame subscribe resolution.

   `(rf/subscribe ...)` from inside a wrapped reg-view resolves
   against the wrapped frame's app-db, not :rf/default."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target :rf-22ds-4-wrapped]
      (rf/reg-frame target {:doc "scenario-4 wrapped frame"})
      (rf/reg-event :seed-4 (fn [{:keys [db]} [_ v]] {:db {:s v}}))
      ;; Seed the wrapped frame explicitly. EP-0002 (rf2-69r7ui): no bare
      ;; `:rf/default` seed — the assertion is that the wrapped-frame
      ;; subscribe resolves to the wrapped value, which the single scoped
      ;; seed establishes.
      (rf/dispatch-sync [:seed-4 :wrapped] {:frame target})
      (rf/reg-sub :scenario-4/s (fn [db _] (:s db)))

      (let [resolved (atom nil)]
        (rf/reg-view* :rf.22ds-4/probe
                      (fn []
                        (reset! resolved @(rf/subscribe [:scenario-4/s]))
                        [:div "probe"]))
        (let [render-fn  (rf/view :rf.22ds-4/probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target}
                                  [render-fn]])))
            (is (= :wrapped @resolved)
                "subscribe routes against the wrapped frame, not :rf/default")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---- Scenario 5: cross-frame dispatch resolution --------------------------
;;
;; Per Spec 006 §rf2-d4sf: the dispatch envelope's `:frame` default is
;; built via the same `:adapter/current-frame` hook as subscribe, so a
;; dispatch from inside a wrapped reg-view targets the wrapped frame's
;; app-db. Covered also by
;; cross_spec_cljs_test/dispatch-default-frame-routes-via-react-context;
;; pinned here as the canonical seven-scenario surface.

(deftest scenario-5-dispatch-routes-against-wrapped-frame
  "Scenario 5 — cross-frame dispatch resolution.

   `(rf/dispatch ...)` (dispatch-sync here, for synchronous
   observability) from inside a wrapped reg-view targets the wrapped
   frame; the wrapped frame's app-db is mutated. A SIBLING registered
   frame (no provider above it) is NOT stamped — the dispatch resolved
   the wrapped frame via the provider, not some ambient default."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target  :rf-22ds-5-wrapped
          sibling :rf-22ds-5-sibling]
      (rf/reg-frame target {:doc "scenario-5 wrapped frame"})
      ;; EP-0002 (rf2-69r7ui): no `:rf/default` floor — use an explicit
      ;; sibling frame to prove the dispatch did NOT leak outside the
      ;; provider scope.
      (rf/reg-frame sibling {:doc "scenario-5 sibling (no provider above)"})
      (rf/reg-event :scenario-5/stamp (fn [{:keys [db]} _] {:db (assoc db :stamped :here)}))

      (rf/reg-view* :rf.22ds-5/probe
                    (fn []
                      (rf/dispatch-sync [:scenario-5/stamp])
                      [:div "probe"]))
      (let [render-fn  (rf/view :rf.22ds-5/probe)
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync
            (fn []
              (rdc/render root [rf/frame-provider {:frame target}
                                [render-fn]])))
          (is (= :here (:stamped (rf/app-db-value target)))
              "the wrapped frame's app-db carries the stamp — dispatch routed there")
          (is (not= :here (:stamped (rf/app-db-value sibling)))
              "the sibling frame's app-db is NOT stamped — the dispatch resolved the provider's frame, not an ambient default")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

;; ---- Scenario 6: React StrictMode composition -----------------------------
;;
;; React.StrictMode double-invokes function bodies (and certain
;; lifecycle phases) in development to surface unsafe side effects.
;; A reg-view'd component is a class-component-shape (Reagent) whose
;; render fn must remain pure — the wrapper's per-render machinery
;; (mint instance token, emit render trace, walk hiccup for source-
;; coord) must tolerate double invocation without producing
;; observably-broken output. The frame-provider's React.Context
;; Provider also gets double-rendered; the resolution chain MUST land
;; on the same frame keyword across both invocations.
;;
;; The bead lists this as "React-19 strict-mode composition"; under
;; React 18 (the test infra's installed version, package.json
;; pinning) StrictMode produces the same double-invoke contract and
;; is sufficient to validate the property.

(deftest scenario-6-strict-mode-composition
  "Scenario 6 — React StrictMode composition.

   Wrap the test tree in `<React.StrictMode>`. The reg-view'd probe
   is invoked twice per render (StrictMode's intentional double-
   invoke). The probe must observe the wrapped frame on every
   invocation (resolution chain is a pure read of React context;
   double-invocation does not break it), and the subscribe must
   return the wrapped frame's app-db value."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target :rf-22ds-6-strict]
      (rf/reg-frame target {:doc "scenario-6 strict-mode frame"})
      (rf/reg-event :seed-6 (fn [{:keys [db]} _] {:db {:s :strict-mode-app-db}}))
      (rf/dispatch-sync [:seed-6] {:frame target})
      (rf/reg-sub :scenario-6/s (fn [db _] (:s db)))

      (let [observed-frames  (atom [])
            observed-values  (atom [])
            invocation-count (atom 0)]
        (rf/reg-view* :rf.22ds-6/probe
                      (fn []
                        (swap! invocation-count inc)
                        (swap! observed-frames conj (rf/current-frame-id))
                        (swap! observed-values conj @(rf/subscribe [:scenario-6/s]))
                        [:div "strict"]))
        (let [render-fn  (rf/view :rf.22ds-6/probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                ;; Wrap in React.StrictMode via Reagent's `:>` interop
                ;; marker. Children are passed through; StrictMode
                ;; double-invokes its descendants' render bodies in
                ;; development.
                (rdc/render root
                            [:> (.-StrictMode React)
                             [rf/frame-provider {:frame target}
                              [render-fn]]])))
            ;; The probe should have been invoked at least once. Under
            ;; StrictMode in development React 18+ invokes function
            ;; components twice; we don't pin the exact count (Reagent
            ;; can class-ify, behaviour can vary by mode) — we pin the
            ;; observability contract: every invocation saw the same
            ;; frame, and every subscribe returned the same value.
            (is (>= @invocation-count 1)
                "the probe rendered at least once")
            (is (every? #(= target %) @observed-frames)
                (str "every render observed the wrapped frame; got "
                     (pr-str @observed-frames)))
            (is (every? #(= :strict-mode-app-db %) @observed-values)
                (str "every subscribe returned the wrapped frame's app-db value; got "
                     (pr-str @observed-values)))
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---- Scenario 6 (ENSURE): StrictMode + hot-reload preserve durable state ---
;;
;; HOT-RELOAD GATE (Mike-required, empirical). EP-0024 amendment: the merged
;; `rf/frame-provider {:id …}` is the ENSURE shape — create-if-absent,
;; reuse-if-present WITHOUT re-seeding, NO destroy-on-unmount. Scenario 6 above
;; covers the SCOPE-only `{:frame …}` shape under StrictMode — a pure context
;; read, so the double-invoke is harmless. This ENSURE counterpart pins the
;; reuse-no-reseed contract empirically under a real React lifecycle:
;;
;;   mount → effect-setup → effect-CLEANUP → effect-setup-AGAIN (StrictMode)
;;   then a SIMULATED :dev/after-load REMOUNT (hot reload) with a re-seeding opts
;;
;; on the SAME fiber for StrictMode, and a fresh element tree for the hot-reload
;; remount. The ensure component (`re-frame.views.owned-frame/ensure-frame-fc`)
;; ensures the frame in the RENDER phase (idempotent `make-frame`), gated on
;; `id-ref.current` being nil; there is NO destroy effect. The contract this
;; gate locks: across BOTH the StrictMode dev cycle AND a hot-reload remount
;; that passes a DIFFERENT `:initial-events`, the existing frame is REUSED, its
;; durable app-db survives, and `:initial-events` are NOT re-run (no re-seed).

(deftest scenario-6-ensure-strict-mode-and-hot-reload-reuse-without-reseed
  "Scenario 6 (ENSURE) — the HOT-RELOAD GATE. StrictMode double-invoke AND a
   simulated `:dev/after-load` remount must REUSE the existing frame WITHOUT
   re-seeding (EP-0024 amended ENSURE).

   1. Mount `[rf/frame-provider {:id … :initial-events [[:rf/set-db {:n 7}]]}]`
      inside `React.StrictMode`. Advance the macrotask window. The frame must
      be live with `{:n 7}` (StrictMode double-invoke did not corrupt it, and —
      ensure has no destroy effect — nothing tore it down).
   2. Mutate durable state (`{:n 7}` → `{:n 42}`) through a dispatch.
   3. Simulate a hot-reload remount: render a FRESH ensure element under the
      SAME id with a RE-SEEDING `:initial-events [[:rf/set-db {:n 999}]]`. The
      existing frame must be REUSED — durable `{:n 42}` survives, NOT re-seeded
      to `{:n 999}` — because `make-frame` is idempotent replacement and
      `reg-frame` re-records-but-does-not-replay `:initial-events`."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (async done
      (let [target     :test/ensure-strict
            done?      (atom false)
            ;; `done`-once guard: the act() thenable + nested macrotask chain
            ;; has several exit paths (success, a thrown assertion, a promise
            ;; rejection). Calling cljs.test's `done` twice aborts the suite,
            ;; so funnel every exit through one guarded call.
            done!      (fn [] (when (compare-and-set! done? false true) (done)))
            mount-node (make-mount-node!)
            ;; PURE React mount via `react-dom/client createRoot` (NOT Reagent's
            ;; `rdc/create-root`): Reagent's own render scheduling commits the
            ;; embedded `ensure-frame-fc` on a Reagent reaction tick OUTSIDE
            ;; React's StrictMode subtree pass, so React's StrictMode effect
            ;; double-invoke never fires for the FC. Mounting a NATIVE React
            ;; element tree — `StrictMode > ensure-frame-react-element` — puts
            ;; the FC directly under StrictMode, which is exactly the UIx/Helix
            ;; substrate path (they build the ensure provider via
            ;; `ensure-frame-react-element`, raw `createElement`). The shared
            ;; `ensure-frame-fc` is common to all React-shaped adapters.
            root       (react-dom-client/createRoot mount-node)
            cleanup!   (fn []
                         (try (.unmount root) (catch :default _ nil))
                         (try (frame/destroy-frame! target) (catch :default _ nil)))
            child      (React/createElement "div" #js {} "ensure-strict")
            ensure-el  (owned-frame/ensure-frame-react-element
                         {:id target :initial-events [[:rf/set-db {:n 7}]]}
                         child
                         'scenario-6-ensure-strict-mode-and-hot-reload-reuse-without-reseed)
            tree       (React/createElement (.-StrictMode React) nil ensure-el)
            act-fn     (get-act)]
        (if (nil? act-fn)
          (do (is true "act() not reachable from this runner; scenario-6-ensure skipped")
              (done!))
          ;; `act` flushes passive effects + drives StrictMode's effect
          ;; double-invoke on the same fiber. Await the returned thenable so the
          ;; StrictMode passive passes flush, then advance two macrotasks before
          ;; asserting (parity with the original timing; ensure has no deferred
          ;; destroy, but the macrotask window keeps the gate robust against any
          ;; scheduled React work). Every exit funnels through the guarded
          ;; `done!`.
          (-> (js/Promise.resolve (act-fn (fn [] (.render root tree))))
              (.then
                (fn [_]
                  ;; (1) Sanity: the frame was created + durable state seeded.
                  (is (some? (frame/frame target))
                      "ensure frame-provider created the frame on mount")
                  (is (= {:n 7} (rf/app-db-value target))
                      ":initial-events seeded the durable app-db")
                  (js/Promise.resolve
                    (js/Promise.
                      (fn [resolve _]
                        (js/setTimeout
                          (fn [] (js/setTimeout (fn [] (resolve nil)) 4))
                          4))))))
              (.then
                (fn [_]
                  ;; After the StrictMode cycle + macrotask window the frame
                  ;; survives (ensure has no destroy effect; StrictMode did not
                  ;; corrupt it).
                  (is (some? (frame/frame target))
                      (str "the ensure frame is STILL LIVE after the StrictMode "
                           "cycle — ENSURE has no destroy-on-unmount"))
                  (is (= {:n 7} (rf/app-db-value target))
                      (str "durable app-db survived the StrictMode cycle intact (got "
                           (pr-str (rf/app-db-value target)) ")"))
                  ;; (2) Mutate durable state so the hot-reload remount has
                  ;; something distinct to preserve.
                  (rf/dispatch-sync [:rf/set-db {:n 42}] {:frame target})
                  (is (= {:n 42} (rf/app-db-value target)) "durable state advanced to {:n 42}")
                  ;; (3) Simulate a :dev/after-load hot-reload remount: a FRESH
                  ;; ensure element under the SAME id, with a RE-SEEDING
                  ;; :initial-events. Reuse must preserve {:n 42}, NOT re-seed.
                  (let [reload-child (React/createElement "div" #js {} "ensure-reload")
                        reload-el    (owned-frame/ensure-frame-react-element
                                       {:id target :initial-events [[:rf/set-db {:n 999}]]}
                                       reload-child
                                       'scenario-6-ensure-strict-mode-and-hot-reload-reuse-without-reseed)
                        reload-tree  (React/createElement (.-StrictMode React) nil reload-el)]
                    (js/Promise.resolve (act-fn (fn [] (.render root reload-tree)))))))
              (.then
                (fn [_]
                  (is (some? (frame/frame target))
                      "the frame is REUSED across the hot-reload remount (still live)")
                  (is (= {:n 42} (rf/app-db-value target))
                      (str "REUSE-NO-RESEED: durable {:n 42} survived the hot-reload "
                           "remount — the re-seeding :initial-events [[:rf/set-db "
                           "{:n 999}]] was RE-RECORDED but NOT replayed (got "
                           (pr-str (rf/app-db-value target)) ")"))
                  (cleanup!)
                  (done!)))
              (.catch
                (fn [err]
                  (is false (str "scenario-6-ensure threw: " (pr-str err)))
                  (cleanup!)
                  (done!)))))))))

;; ---- Scenario 6 (ENSURE): genuine unmount LEAVES the frame live -----------
;;
;; EP-0024 amendment: the ensure provider has NO destroy-on-unmount. A genuine
;; unmount (no remount) must LEAVE the frame live — the inverse of the retired
;; owned provider's destroy-on-unmount. True ownership (teardown) is now
;; explicit (`make-frame` + `destroy-frame!` inside a `create-class`), never the
;; provider's job. This pins that a real unmount does not tear the frame down.

(deftest scenario-6-ensure-genuine-unmount-leaves-frame-live
  "Scenario 6 (ENSURE) — a genuine unmount LEAVES the ensure frame live
   (EP-0024 amended; owned destroy-on-unmount retired). Mount (no StrictMode),
   unmount, advance past any macrotask window, and assert the frame is STILL
   LIVE with its durable app-db intact."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (async done
      (let [target     :test/ensure-survives
            done?      (atom false)
            done!      (fn [] (when (compare-and-set! done? false true) (done)))
            mount-node (make-mount-node!)
            ;; Pure React mount (see scenario-6-ensure for why — Reagent's render
            ;; scheduling otherwise commits the FC outside React's effect passes).
            root       (react-dom-client/createRoot mount-node)
            cleanup!   (fn [] (try (frame/destroy-frame! target) (catch :default _ nil)))
            child      (React/createElement "div" #js {} "ensure-survives")
            ensure-el  (owned-frame/ensure-frame-react-element
                         {:id target :initial-events [[:rf/set-db {:n 3}]]}
                         child
                         'scenario-6-ensure-genuine-unmount-leaves-frame-live)
            act-fn     (get-act)]
        (if (nil? act-fn)
          (do (is true "act() not reachable from this runner; genuine-unmount scenario skipped")
              (done!))
          ;; Mount under act so the FC commits, then unmount under act. There is
          ;; no destroy effect, so the unmount is a no-op for the frame; advance
          ;; the macrotask window anyway to prove no deferred teardown lurks.
          (-> (js/Promise.resolve (act-fn (fn [] (.render root ensure-el))))
              (.then
                (fn [_]
                  (is (some? (frame/frame target)) "frame created on mount")
                  (is (= {:n 3} (rf/app-db-value target)) ":initial-events seeded app-db")
                  ;; Genuine unmount — the ensure provider does NOT destroy.
                  (js/Promise.resolve (act-fn (fn [] (.unmount root))))))
              (.then
                (fn [_]
                  (js/setTimeout
                    (fn []
                      (js/setTimeout
                        (fn []
                          (is (some? (frame/frame target))
                              "genuine unmount LEFT the frame live (ENSURE has no destroy-on-unmount)")
                          (is (= {:n 3} (rf/app-db-value target))
                              "durable app-db survived the unmount intact")
                          (cleanup!)
                          (done!))
                        4))
                    4)))
              (.catch
                (fn [err]
                  (is false (str "ensure genuine-unmount scenario threw: " (pr-str err)))
                  (cleanup!)
                  (done!)))))))))

;; ---- Scenario 7: concurrent rendering / suspense + act --------------------
;;
;; React 18+ concurrent rendering schedules renders asynchronously by
;; default. Tests that drive renders need to wrap them in `act()` so
;; React's pending work commits before assertions run. This scenario
;; pins that the frame-provider survives across re-renders driven
;; through `act` — a subscribe held inside a reg-view'd component
;; reflects a post-dispatch app-db change after act flushes pending
;; renders, and the resolution chain still lands on the wrapped frame.
;;
;; React's `act()` is exposed via `react-dom/test-utils` (React 18)
;; and on `react` directly (React 19). The harness uses whichever is
;; available; if neither is reachable the test SKIPS and files a bead
;; (per the bead's "no new test infrastructure" rule). `get-act` lives
;; in the helpers section above (shared with the owned-frame StrictMode
;; scenarios, which also need act() to drive React's effect double-invoke).

(deftest scenario-7-concurrent-renders-survive-act-flush
  "Scenario 7 — React concurrent rendering survives across re-renders.

   Drive an initial render under `act()`, then dispatch an event that
   updates the wrapped frame's app-db, then drive another render
   under `act()`. The subscribe held inside the reg-view'd component
   reflects the post-dispatch value, AND the resolution still lands
   on the wrapped frame (the provider boundary held across both
   renders).

   No real Suspense boundary is mounted because the bead's
   suspend-able primitive (a real-Suspense data-fetcher) doesn't ship
   with this test infrastructure; the act-wrapped re-render is the
   minimally-sufficient signal that pending React work commits
   without corrupting the provider chain."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target :rf-22ds-7-concurrent
          act-fn (get-act)]
      (if (nil? act-fn)
        ;; Harness gap — no act() reachable. Per the bead, file and
        ;; skip rather than yak-shave a new harness primitive.
        (is true (str "act() not reachable from this test runner; "
                      "scenario-7 skipped — bead filed (see suite docstring)."))
        (let [_ (rf/reg-frame target {:doc "scenario-7 concurrent frame"})
              _ (rf/reg-event :seed-7 (fn [{:keys [db]} _] {:db {:n 1}}))
              _ (rf/reg-event :inc-7  (fn [{:keys [db]} _] {:db (update db :n inc)}))
              _ (rf/dispatch-sync [:seed-7] {:frame target})
              _ (rf/reg-sub :scenario-7/n (fn [db _] (:n db)))
              observed-frames (atom [])
              observed-values (atom [])
              _ (rf/reg-view* :rf.22ds-7/probe
                              (fn []
                                (swap! observed-frames conj (rf/current-frame-id))
                                (swap! observed-values conj @(rf/subscribe [:scenario-7/n]))
                                [:div "concurrent"]))
              render-fn  (rf/view :rf.22ds-7/probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; Render 1 — wrap the call in act() so pending React work
            ;; commits before we read the observed-* atoms.
            (act-fn (fn []
                      (rdc/render root [rf/frame-provider {:frame target}
                                        [render-fn]])))
            (is (some #{target} @observed-frames)
                "first render saw the wrapped frame")
            (is (some #{1} @observed-values)
                "first render saw the seeded value n=1")
            ;; Mutate the wrapped frame's app-db. The reaction held by
            ;; the probe should pick up the change; act() drains the
            ;; resulting render.
            (rf/dispatch-sync [:inc-7] {:frame target})
            (act-fn (fn []
                      ;; Force a re-render by re-rendering the same
                      ;; tree; Reagent's reaction tracking would
                      ;; normally kick a render automatically — the
                      ;; explicit re-render makes the test
                      ;; deterministic across reactivity-flush timing
                      ;; differences in the harness.
                      (rdc/render root [rf/frame-provider {:frame target}
                                        [render-fn]])))
            (is (= target (last @observed-frames))
                "post-act render still observes the wrapped frame — provider boundary held")
            (is (some #{2} @observed-values)
                "post-dispatch re-render observes the incremented value n=2")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

;; ---- harness sanity: provider element shape -------------------------------
;;
;; A non-mounting headless sanity check — the provider hiccup composes
;; the way the seven scenarios depend on. This catches a regression
;; where the provider component shape drifts (e.g. a bad refactor of
;; build-frame-provider) BEFORE any of the mount-based scenarios run,
;; which makes per-scenario failures easier to read.

(deftest harness-sanity-provider-element-shape
  "Sanity — `[rf/frame-provider {:frame :x} child]` composes to a
  React Context Provider element with the expected `:value`. Sister
  to the existing `frame-provider-emits-provider-hiccup` in
  runtime_cljs_test; pinned here so a regression in the provider
  shape surfaces alongside this suite's failures, not three suites
  away."
  ;; The merged provider's SCOPE-only `{:frame …}` shape fails loud if the
  ;; frame is absent, so register it live before composing the element.
  (rf/reg-frame :rf-22ds-sanity-x {})
  (let [child       [:span "x"]
        tree        (rf/frame-provider {:frame :rf-22ds-sanity-x} child)
        head        (first tree)
        value       (second tree)
        rest-args   (drop 2 tree)]
    (is (fn? head)
        "head is a fn (the Reagent component)")
    (is (= :rf-22ds-sanity-x value)
        "the frame keyword threads through as the first invocation arg")
    (is (= [child] rest-args)
        "children follow the frame keyword unchanged")))

;; ---- Regression: namespaced frame-ids survive the React-context round trip ---
;;
;; Stock Reagent's `convert-prop-value` (reagent.impl.template) calls
;; `(name kw)` on named prop values, dropping the namespace. A naive
;; `[:> Provider {:value :tenant/admin}]` mount under the classic
;; adapter therefore reaches the read side as `:admin` (namespace
;; gone). The canonical user-facing surface (`rf/frame-provider`)
;; works around this by mounting the Provider via Reagent's `:r>`
;; interop head — the props map flows to React as a raw JS object,
;; `convert-prop-value` is bypassed entirely, and the keyword reaches
;; React unchanged.
;;
;; This test pins the namespace-preservation contract end-to-end:
;; mount a frame-provider with a namespaced frame-id, render a
;; reg-view'd probe under it, and assert that `(rf/current-frame-id)`
;; from inside the probe returns the FULL namespaced keyword.

(deftest namespaced-frame-id-survives-react-context-round-trip
  "Regression — `rf/frame-provider` with a namespaced frame-id
   (`:tenant/admin`) preserves the namespace across the React-context
   round trip. Without the `:r>` bypass the classic Reagent adapter
   would strip the namespace via `(name kw)` in `convert-prop-value`
   and the probe would observe `:admin` instead."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target :rf-22ds-ns/tenant-admin]
      (rf/reg-frame target {:doc "namespaced frame-id regression"})
      (rf/reg-event :rf-22ds-ns/seed (fn [{:keys [db]} [_ v]] {:db {:tag v}}))
      (rf/dispatch-sync [:rf-22ds-ns/seed :wrapped-value] {:frame target})
      (rf/reg-sub :rf-22ds-ns/tag (fn [db _] (:tag db)))

      (let [observed-frame (atom nil)
            observed-value (atom nil)]
        (rf/reg-view* :rf-22ds-ns/probe
                      (fn []
                        (reset! observed-frame (rf/current-frame-id))
                        (reset! observed-value @(rf/subscribe [:rf-22ds-ns/tag]))
                        [:div "probe"]))
        (let [render-fn  (rf/view :rf-22ds-ns/probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target}
                                  [render-fn]])))
            (is (= target @observed-frame)
                (str "current-frame inside the wrapped subtree resolves to the FULL "
                     "namespaced keyword (got " (pr-str @observed-frame) ")"))
            (is (= :tenant-admin (-> @observed-frame name keyword))
                "sanity: the unqualified part matches the namespaced keyword's name")
            (is (= "rf-22ds-ns" (namespace @observed-frame))
                "sanity: the namespace survived (would be nil if prop-conversion stripped it)")
            (is (= :wrapped-value @observed-value)
                "subscribe routes against the namespaced frame's app-db, not :rf/default's")
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))
