(ns re-frame.ui.viewcell-ambient-frame-binding-dom-cljs-test
  "rf2-4rwtd — a compiled ViewCell binds its React-context frame into
  `re-frame.frame/*current-frame*` while evaluating its body, so ambient
  `(sub …)` resolution hits the precedence-first DYNAMIC tier —
  correct under ANY adapter reader the `:adapter/current-frame` hook routes to.

  ## The defect this hardens

  `re-frame.ui.viewcell/use-frame-context!` reads the ViewCell's frame from React
  context (via `function-component-current-frame` → `_currentValue`) and — before
  the fix — DISCARDED it in the sub wrappers. The compiled body's ambient
  `(sub …)` then resolved its frame through `frame/require-current-frame!` →
  `resolve-current-frame` → the INSTALLED adapter's `:adapter/current-frame`
  reader. The ratom-family (Reagent / reagent-slim) readers return nil for a ui
  FUNCTION component — their `(.-context cmp)` / provider-ratom shape does not
  apply to a plain React function component — so a compiled view's sub reads
  raised `:rf.error/no-frame-context` under a Reagent-installed host. (ui's own
  event / `(frame)` paths thread the frame directly and were immune — a
  sub-read-only asymmetry.) The fix binds the retained frame into
  `*current-frame*` around the body eval, so ambient resolution finds it on the
  dynamic tier that EVERY reader consults first.

  ## Faithful reproduction WITHOUT a cross-artefact Reagent dep

  The ui test artefact carries only core + ui on its classpath, not the Reagent
  adapter. But the defect is not Reagent-specific: it is that the routed reader
  observes ONLY the dynamic tier (no `_currentValue` fallback for a ui function
  component). We reproduce that exact topology by routing `:adapter/current-frame`
  to a DYNAMIC-TIER-ONLY stand-in — `#(frame/current-frame)` — which is what the
  Reagent class-component reader `re-frame.views.provider/current-frame`
  (`(or frame/*current-frame* (.-context cmp)…)`) REDUCES to inside a ui function
  component, where `(reagent current-component)` is nil. `use-frame-context!`
  reads `_currentValue` DIRECTLY (not through the hook), so the ViewCell still
  learns its frame; the fix carries it onto the dynamic tier the stand-in reader
  reads. Under the plain reactive adapter (whose reader DOES read `_currentValue`)
  the fix is a behavioural no-op, so the stand-in reader is what makes the
  before/after decisive.

  Naming: `-dom-cljs-test$` opts this file into `:browser-test`; the headless
  mechanism tests run cross-runtime (node + browser), and the mounted DOM bodies
  gate on `(browser?)` and exit early under `:node-test`."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]     ;; loads re-frame.ui.substrate (the routed reader)
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

;; ---------------------------------------------------------------------------
;; The ratom-family stand-in reader (see ns docstring)
;;
;; Dynamic-tier ONLY — no `_currentValue` read. This is what the Reagent
;; class-component reader reduces to inside a ui function component. Installed
;; over the routed `:adapter/current-frame` hook for the whole file and restored
;; in the fixture `:after`, so a mounted compiled sub resolves EXCLUSIVELY via
;; `frame/*current-frame*` — the tier the fix populates.
;; ---------------------------------------------------------------------------

(defn- ratom-family-reader [] (frame/current-frame))

(def ^:private saved-reader (atom nil))

(defn- install-ratom-family-reader! []
  (reset! saved-reader (late-bind/get-fn :adapter/current-frame))
  (late-bind/set-fn! :adapter/current-frame ratom-family-reader))

(defn- restore-reader! []
  (when-let [f @saved-reader]
    (late-bind/set-fn! :adapter/current-frame f))
  (reset! saved-reader nil))

;; ---------------------------------------------------------------------------
;; React act() helpers — the repository's established native-React pattern
;; (mirrors re-frame.ui.frame-scope-resolve-dom-cljs-test).
;; ---------------------------------------------------------------------------

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act! [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter       ui/adapter
                                            :ambient-frame nil
                                            :async?        true})
  (let [prior-act-env (atom nil)]
    {:before
     (fn []
       (when (browser?)
         (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
       (enable-react-act-env!)
       ;; Route the ambient-frame reader to the dynamic-tier-only stand-in for
       ;; the ratom-family topology (see ns docstring). Installed AFTER the
       ;; runtime-reset fixture installed the ui adapter, restored in `:after`.
       (install-ratom-family-reader!)
       (reactive/reset-scheduler!)
       (client/reset-live-roots!)
       (frames/reset-installed-plans!))
     :after
     (fn []
       (restore-reader!)
       (reactive/reset-scheduler!)
       (client/reset-live-roots!)
       (frames/reset-installed-plans!)
       (when (browser?)
         (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))}))

(defn- container [] (js/document.createElement "div"))

(defn- assert-torn-down! [root]
  (act! #(some-> root ui/unmount!))
  (is (= #{} (client/live-root-ids))
      "the root's claim is released — no live-root survives the test")
  (is (empty? (reactive/current-live-cells))
      "the mounted ViewCell was torn down — no connected cell leaks"))

(defn- reg! []
  (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
  (rf/reg-sub :scope/n (fn [db _] (:n db))))

;; n-view SUBS ambiently — a descendant view boundary whose ViewCell render
;; reads the enclosing provider scope. Sub-bearing → the `render-subs` wrapper,
;; the exact path the fix binds `*current-frame*` around.
(defview n-view [] [:div.n (str "n=" (sub [:scope/n]))])

(defview provider-wrap [{:keys [frame-id]}]
  [:div.wrap [frame-provider {:frame frame-id} [n-view]]])

;; one ViewCell render pass — the REAL sub-read path (obs/resolve-target →
;; frame/require-current-frame! → the installed :adapter/current-frame reader),
;; recorded into a live cell's capture. NOT a direct sub-read.
(defn- render-cell! [cell queries]
  (first (reactive/with-capture
          cell (fn [] (mapv (fn [i q] (reactive/sub-read [:probe/site i] q))
                            (range) queries)))))

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Mechanism + vacuity (cross-runtime — node + browser)
;; ---------------------------------------------------------------------------

(deftest stand-in-reader-is-dynamic-tier-only
  ;; VACUITY GUARD: the installed reader observes ONLY the dynamic tier. A green
  ;; mounted resolution below can therefore come ONLY from the fix's
  ;; `*current-frame*` binding — never a React-context fallback the reader does
  ;; not perform.
  (is (nil? (ratom-family-reader))
      "no dynamic binding → the stand-in reader resolves nil (it never reads React context)")
  (is (= :some/frame
         (binding [frame/*current-frame* :some/frame] (ratom-family-reader)))
      "the stand-in reader returns the dynamic-tier frame when *current-frame* is bound"))

(deftest ambient-sub-under-ratom-reader-with-no-binding-fails-loud
  ;; RED-BEFORE mechanism: under the ratom-family reader, an ambient ViewCell
  ;; sub-read with NO `*current-frame*` binding throws no-frame-context — exactly
  ;; the failure a compiled view hit under a Reagent host before the fix, because
  ;; the reader has no `_currentValue` fallback.
  (reg!)
  (let [cell (reactive/make-cell ::no-binding)]
    (is (= :rf.error/no-frame-context
           (throws-id #(render-cell! cell [[:scope/n]])))
        (str "with the ratom-family reader routed and no dynamic scope, the "
             "ambient sub-read fails loud — the pre-fix symptom under a Reagent "
             "host (rf2-4rwtd)"))))

(deftest ambient-sub-under-ratom-reader-with-binding-resolves
  ;; AFTER mechanism: the SAME reader resolves the ambient sub once
  ;; `*current-frame*` carries the frame — which is precisely what the fix's
  ;; `with-current-frame` establishes around the compiled body.
  (reg!)
  (rf/make-frame {:id :app/a})
  (frame/replace-app-db! :app/a {:n 42})
  (let [cell (reactive/make-cell ::bound)]
    (is (= [42]
           (binding [frame/*current-frame* :app/a]
             (render-cell! cell [[:scope/n]])))
        (str "with `*current-frame*` bound to :app/a, the ambient sub-read "
             "resolves :app/a's app-db through the ratom-family reader — the "
             "dynamic tier the fix populates from React context"))))

;; ---------------------------------------------------------------------------
;; End-to-end through the compiled WRAPPER on a real react-dom root (browser)
;; ---------------------------------------------------------------------------

(deftest mounted-viewcell-resolves-owning-frame-under-ratom-reader
  ;; The wrapper proof: a compiled ViewCell mounted under a `frame-provider` for
  ;; :app/a — with the dynamic-tier-only ratom-family reader routed — resolves
  ;; :app/a's app-db and repaints on dispatch. Green here is attributable ONLY to
  ;; the fix binding `use-frame-context!`'s React-context frame into
  ;; `*current-frame*` (the reader reads no `_currentValue`). Without the fix the
  ;; render throws :rf.error/no-frame-context and React aborts the commit — the
  ;; container never shows n=42 (red-before).
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/a})
    (rf/dispatch-sync [:test/set-db {:n 42}] {:frame :app/a})
    (let [c    (container)
          root (act! #(ui/mount [provider-wrap {:frame-id :app/a}] c
                                {:root-id :ratom-scope/a}))]
      (is (re-find #"n=42" (.-innerHTML c))
          (str "the ambient (sub …) resolved the frame-provider-scoped :app/a "
               "under a dynamic-tier-only reader — the ViewCell carried its "
               "React-context frame onto `*current-frame*` (rf2-4rwtd)"))
      ;; reads-a-sub → dispatches → repaints: drive a post-mount update through
      ;; the framework's awaited-act flush idiom and assert the ViewCell repaints.
      (async done
        (-> (js/Promise.resolve)
            (.then (fn [] (uit/flush! #(rf/dispatch-sync [:test/set-db {:n 43}] {:frame :app/a}))))
            (.then
             (fn []
               (is (re-find #"n=43" (.-innerHTML c))
                   (str "the post-mount dispatch drove the ambient (sub …) "
                        "through observation → reactive flush → React rerender; "
                        "the mounted ViewCell repainted :app/a's new value"))))
            (.catch
             (fn [e]
               (is false (str "post-mount flush! rejected: " (some-> e .-message)))))
            (.then (fn [] (assert-torn-down! root) (done))))))))

(deftest mounted-viewcell-frame-binding-is-per-viewcell-not-leaked
  ;; ISOLATION pin: the SAME compiled view mounted under a provider for :app/b
  ;; resolves :app/b — never :app/a. The `*current-frame*` binding is per-ViewCell
  ;; (scoped to that ViewCell's body eval), so it cannot leak one frame's identity
  ;; into a sibling ViewCell's ambient resolution. Frames are isolated contexts.
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/a})
    (rf/make-frame {:id :app/b})
    (rf/dispatch-sync [:test/set-db {:n 42}] {:frame :app/a})
    (rf/dispatch-sync [:test/set-db {:n 7}]  {:frame :app/b})
    (let [c    (container)
          root (act! #(ui/mount [provider-wrap {:frame-id :app/b}] c
                                {:root-id :ratom-scope/b}))]
      (try
        (is (re-find #"n=7" (.-innerHTML c))
            (str "the ViewCell under the :app/b provider resolved :app/b's "
                 "app-db (n=7) — its `*current-frame*` binding is its OWN frame"))
        (is (not (re-find #"n=42" (.-innerHTML c)))
            (str "the :app/a value never leaked into the :app/b-scoped ViewCell — "
                 "the binding is per-ViewCell, not ambient across cells (rf2-4rwtd)"))
        (finally
          (assert-torn-down! root))))))
