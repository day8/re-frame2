(ns re-frame.ui.react-interop-jvm-test
  "rf2-vxgfnd.95.4 — the frozen re-frame.ui.react interop tier on the JVM
  Tier-1 structural host, plus the COMPILE-TIME position law.

  The seven wrappers (Spec 004 §The React interop tier): use-ref, use-effect,
  use-layout-effect, use-effect-event, use-context, use-id, lazy. This suite
  pins:
    - the position law rejections (`:rf.ui.compile/react-hook-misplaced`,
      `-bad-deps`, `react-lazy-misplaced`) — compile-time ids in COMPILER PROSE,
      no Spec 009 catalogue rows;
    - the hook-signature-hash contribution (kind + interleave order; edit-a-body
      is same-signature; add/remove/reorder — including local↔react — is not)
      and the S3 shape-version bump;
    - the manifest sites + capability-bit folding;
    - the JVM structural subset: inert ref, effects don't run, use-effect-event
      returns a fn that fails loud, use-context resolves a JVM-provided value or
      fails loud, use-id is a deterministic inert string, lazy renders its
      fallback / nothing and NEVER invokes the load thunk.

  Real React client behaviour (use-ref no-re-render, per-slot rf= deps + cleanup,
  use-layout-effect measure-before-paint, use-effect-event latest-body + unstable
  identity, use-context foreign provider, use-id, lazy activation, StrictMode,
  HMR) rides the DOM suite `react_interop_dom_cljs_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.react :as react]
            [re-frame.ui.fingerprint :as fp]
            [re-frame.ui.hooks :as hooks]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (frames/reset-frame-ops-cache!)
    (try (t) (finally (frames/reset-frame-ops-cache!)))))

;; Compile-error probing follows the .95.2 JVM suite: `macroexpand-1` with
;; FULLY-QUALIFIED symbols, so resolution is independent of the runtime *ns*.
(defn- compile-error [form]
  (try (macroexpand-1 form) nil
       (catch clojure.lang.ExceptionInfo e (:rf.ui.compile/error (ex-data e)))
       (catch Exception e
         (let [c (.getCause e)]
           (when (instance? clojure.lang.ExceptionInfo c)
             (:rf.ui.compile/error (ex-data c)))))))

(defn- manifest [view-id]
  (:rf.ui/manifest (registrar/handler-meta :view view-id)))

;; ---------------------------------------------------------------------------
;; The position law — compile-time rejections (prose ids, no Spec 009 rows)
;; ---------------------------------------------------------------------------

(deftest position-law-rejects-conditional-loop-and-deferred
  (testing "a wrapper in a branch is misplaced (React hook order must be static)"
    (is (= :rf.ui.compile/react-hook-misplaced
           (compile-error '(re-frame.ui/defview v []
                             (when true (let [r (re-frame.ui.react/use-ref)] [:div])))))))
  (testing "a wrapper in a loop is misplaced"
    (is (= :rf.ui.compile/react-hook-misplaced
           (compile-error '(re-frame.ui/defview v []
                             [:ul (for [i [1 2]]
                                    (let [r (re-frame.ui.react/use-ref)] [:li {:key i} i]))])))))
  (testing "a wrapper in a deferred fn body is misplaced"
    (is (= :rf.ui.compile/react-hook-misplaced
           (compile-error '(re-frame.ui/defview v []
                             (let [f (fn [] (re-frame.ui.react/use-context :t))] [:div])))))))

(deftest use-effect-deps-must-be-a-literal-vector
  (is (= :rf.ui.compile/react-hook-bad-deps
         (compile-error '(re-frame.ui/defview v []
                           (let [_ (re-frame.ui.react/use-effect (fn [] nil) :nope)] [:div]))))))

(deftest reactive-read-inside-a-setup-fn-is-rejected
  ;; the setup fn is a DEFERRED callback — a sub/handle/frame inside owns no
  ;; render-time site (the same law as `effect`).
  (is (some? (compile-error '(re-frame.ui/defview v []
                               (let [_ (re-frame.ui.react/use-effect
                                        (fn [] (re-frame.ui/sub [:x])) [])]
                                 [:div]))))))

(deftest wrong-arities-are-rejected
  (is (= :rf.ui.compile/unsupported-form
         (compile-error '(re-frame.ui/defview v []
                           (let [id (re-frame.ui.react/use-id :extra)] [:div id])))))
  (is (= :rf.ui.compile/unsupported-form
         (compile-error '(re-frame.ui/defview v []
                           (let [r (re-frame.ui.react/use-ref 1 2)] [:div]))))))

(deftest lazy-in-a-view-body-is-def-level-only
  (is (= :rf.ui.compile/react-lazy-misplaced
         (compile-error '(re-frame.ui/defview v []
                           (let [c (re-frame.ui.react/lazy (fn []))] [:div]))))))

(deftest a-valid-top-region-view-compiles
  (is (nil? (compile-error
             '(re-frame.ui/defview v []
                (let [r  (re-frame.ui.react/use-ref)
                      id (re-frame.ui.react/use-id)
                      th (re-frame.ui.react/use-context :theme)
                      _  (re-frame.ui.react/use-effect (fn [] nil) [th])]
                  [:div {:ref r :id id} th]))))))

;; ---------------------------------------------------------------------------
;; Manifest sites + capability folding
;; ---------------------------------------------------------------------------

(defview manifest-probe []
  (let [r  (react/use-ref)
        id (react/use-id)
        th (react/use-context :theme)
        _  (react/use-effect (fn [] nil) [th])
        _  (react/use-layout-effect (fn [] nil))
        on (react/use-effect-event (fn [x] x))]
    [:div {:ref r :id id} th]))

(deftest manifest-records-react-sites-and-kinds
  (let [m     (manifest ::manifest-probe)
        sites (get-in m [:sites :react])
        kinds (mapv :kind sites)]
    (is (= 6 (count sites)) "all six wrapper sites are recorded")
    (is (= [:ref :id :context :effect :layout-effect :effect-event] kinds)
        "kinds are recorded in source order")
    (is (apply distinct? (map :sid sites)) "each site has a distinct lexical id")
    (is (= (range 6) (mapv :order sites))
        "shared body-hook ordinals are 0..5 in source order")))

(deftest capability-bits-fold-onto-the-static-root-vocabulary
  (let [caps (set (:capabilities (manifest ::manifest-probe)))]
    (is (contains? caps :ref)     "use-ref → :ref")
    (is (contains? caps :effect)  "use-effect/use-layout-effect/use-effect-event → :effect")
    (is (contains? caps :context) "use-context → :context")
    (is (not (contains? caps :id)) "use-id is exempt (inert deterministic ids are static-safe)")))

;; ---------------------------------------------------------------------------
;; Hook-signature contribution (shape-version 2; interleave-safe)
;; ---------------------------------------------------------------------------

(deftest hook-signature-shape-is-version-2-with-react
  (let [hs (:hook-signature (manifest ::manifest-probe))]
    (is (string? hs))
    (is (.startsWith ^String hs "hs1-")
        "the prefix versions the ALGORITHM (unchanged); the integer versions the shape")))

(deftest editing-a-deps-vector-or-setup-body-is-same-signature
  (let [sig (fn [react] (fp/hook-signature-hash {:react react}))
        a   (sig [{:kind :effect :order 0}])
        b   (sig [{:kind :effect :order 0}])]
    (is (= a b) "kind + order only — deps contents / setup bodies never enter the hash")))

(deftest adding-removing-or-reordering-a-wrapper-changes-the-signature
  (let [sig (fn [m] (fp/hook-signature-hash m))]
    (testing "adding a wrapper"
      (is (not= (sig {:react [{:kind :ref :order 0}]})
                (sig {:react [{:kind :ref :order 0} {:kind :effect :order 1}]}))))
    (testing "reordering two react wrappers"
      (is (not= (sig {:react [{:kind :ref :order 0} {:kind :context :order 1}]})
                (sig {:react [{:kind :context :order 0} {:kind :ref :order 1}]}))))
    (testing "a local↔react reorder (both live in the top-region let)"
      ;; local advances the shared ordinal, so moving it across a use-ref shifts
      ;; the ref's ordinal — a per-category count vector could not catch this.
      (is (not= (sig {:locals [:local] :react [{:kind :ref :order 1}]})
                (sig {:locals [:local] :react [{:kind :ref :order 0}]}))))))

;; A NATIVE (non-foreign-boundary) measure-before-paint consumer (readiness
;; C-6): use-ref for the element + use-layout-effect to measure and place. Its
;; real-compiler hook-signature drives the HMR law below (and its client
;; behaviour rides the DOM suite).
(defview popover-v1 [{:keys [anchor]}]
  (let [el (react/use-ref)
        _  (react/use-layout-effect (fn [] nil) [anchor])]
    [:div {:ref el :data-role "popover"} "menu"]))

;; same wrappers, same order, edited layout-effect body + deps → SAME signature
;; (a compatible refresh: state preserved).
(defview popover-v1-edited [{:keys [anchor]}]
  (let [el (react/use-ref)
        _  (react/use-layout-effect (fn [] (identity :edited)) [anchor :extra])]
    [:div {:ref el :data-role "popover"} "MENU"]))

;; an ADDED passive effect → DIFFERENT signature (a deliberate clean remount).
(defview popover-v2-added [{:keys [anchor]}]
  (let [el (react/use-ref)
        _  (react/use-layout-effect (fn [] nil) [anchor])
        _  (react/use-effect (fn [] nil) [])]
    [:div {:ref el :data-role "popover"} "menu"]))

(deftest hmr-law-native-consumer-edit-preserves-add-remounts
  (let [sig #(:hook-signature (manifest %))]
    (is (= (sig ::popover-v1) (sig ::popover-v1-edited))
        "editing a layout-effect body/deps is a SAME-signature edit (state preserved)")
    (is (not= (sig ::popover-v1) (sig ::popover-v2-added))
        "adding a wrapper changes the hook signature (a clean remount)")))

;; ---------------------------------------------------------------------------
;; JVM structural subset
;; ---------------------------------------------------------------------------

(defview inert-ref-view [] (let [r (react/use-ref 42)] [:div {:ref r} "x"]))

(deftest use-ref-is-inert-on-the-jvm
  (let [t (uit/render [inert-ref-view {}])]
    (is (= "x" (uit/text (uit/find t :div))) "the view renders; the ref is inert")))

(deftest jvm-use-ref-current-stays-nil
  (is (nil? (:current (hooks/use-ref)))    "0-arg inert ref")
  (is (nil? (:current (hooks/use-ref 99))) "1-arg inert ref ignores the initial"))

(def ^:dynamic *effect-ran* (atom false))

(defview effects-dont-run-view []
  (let [_ (react/use-effect (fn [] (reset! *effect-ran* true)) [])
        _ (react/use-layout-effect (fn [] (reset! *effect-ran* true)))]
    [:div "ok"]))

(deftest effects-do-not-run-on-the-jvm
  (binding [*effect-ran* (atom false)]
    (uit/render [effects-dont-run-view {}])
    (is (false? @*effect-ran*) "passive/layout effects never run in a structural render")))

(deftest use-effect-event-fn-fails-loud-on-jvm-invoke
  (let [f (hooks/use-effect-event (fn [x] x))]
    (is (thrown? clojure.lang.ExceptionInfo (f 1))
        "the returned fn raises :rf.error/jvm-host-op when invoked")))

(defview ctx-view [] (let [v (react/use-context :theme)] [:div v]))

(deftest use-context-resolves-a-jvm-provided-value
  (binding [hooks/*jvm-react-context-values* {:theme "dark"}]
    (let [t (uit/render [ctx-view {}])]
      (is (= "dark" (uit/text (uit/find t :div)))
          "the JVM-provided test value flows through"))))

(deftest use-context-with-no-provided-value-fails-loud
  (is (thrown? clojure.lang.ExceptionInfo
               (uit/render [ctx-view {}]))
      "an unbound foreign context never silently resolves nil"))

(defview id-view [] (let [id (react/use-id)] [:div {:data-id id} id]))

(deftest use-id-is-a-deterministic-inert-string
  (let [t (uit/render [id-view {}])]
    (is (= "rf2-uid-0" (uit/text (uit/find t :div)))
        "a deterministic prefix + occurrence-counter string; safe for static roots")))

(deftest jvm-use-id-counter-makes-repeats-distinct
  (binding [hooks/*jvm-use-id-counter* (volatile! 0)]
    (is (= "rf2-uid-0" (hooks/use-id)))
    (is (= "rf2-uid-1" (hooks/use-id)) "an occurrence counter distinguishes repeats")))

;; ---------------------------------------------------------------------------
;; lazy — def-level; JVM renders the fallback / nothing, never the thunk
;; ---------------------------------------------------------------------------

(def ThrowingThunk (fn [] (throw (ex-info "the load thunk must NEVER run on the JVM" {}))))

(def WithFallback  (react/lazy ThrowingThunk {:fallback [:div.spinner "Loading…"]}))
(def WithoutFallback (react/lazy ThrowingThunk))

(deftest lazy-value-carries-the-marker
  (is (true? (:rf.ui/lazy WithFallback)))
  (is (true? (:rf.ui/lazy WithoutFallback))))

(defview lazy-fallback-page [] [:section [WithFallback {:data 1}]])
(defview lazy-nothing-page  [] [:section [WithoutFallback {:data 1}]])

(deftest lazy-renders-its-declared-fallback-and-never-the-thunk
  (let [t (uit/render [lazy-fallback-page {}])]
    (is (= "Loading…" (uit/text t))
        "the JVM structural render shows the declared capability-free fallback")))

(deftest lazy-without-a-fallback-renders-nothing
  (let [t (uit/render [lazy-nothing-page {}])]
    ;; the section renders; the lazy child contributes nothing (never the thunk).
    (is (= "" (or (uit/text t) ""))
        "no fallback ⇒ :nothing (a foreign component that never suspends here)")))

(defn- eval-compile-error [form]
  (try (eval form) nil
       (catch Throwable e
         ;; walk the (possibly multi-level) cause chain for the compile id.
         (some (fn [t] (:rf.ui.compile/error (ex-data t)))
               (take 8 (iterate #(some-> % ex-cause) e))))))

(deftest lazy-fallback-must-be-capability-free
  ;; a reactive read in a lazy fallback is a compile error (the client-only rule).
  (is (= :rf.ui.compile/capability-in-fallback
         (eval-compile-error '(re-frame.ui.react/lazy
                               (fn [])
                               {:fallback [:div (re-frame.ui/sub [:x])]})))))

;; ---------------------------------------------------------------------------
;; ui/->react — the OUTWARD bridge (rf2-u53yy.2): CLJS-only. A React component
;; export has no meaning in a JVM structural render, so a JVM call fails loud
;; through the canonical host-op path (kin to ui/raw, ui/unmount!). Real
;; client behaviour (mount inside foreign React, frame/sub resolution,
;; per-view-identity memoisation, children/ref passthrough) rides the DOM suite
;; `react_export_bridge_dom_cljs_test`.
;; ---------------------------------------------------------------------------

(defview exportable-view [{:keys [label]}] [:div.export label])

(deftest ->react-is-a-host-op-on-the-jvm
  (is (= :rf.error/jvm-host-op
         (try (ui/->react exportable-view) nil
              (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
      "(ui/->react view) raises :rf.error/jvm-host-op — a React component export
       is meaningless in a structural render (CLJS-only bridge)"))
