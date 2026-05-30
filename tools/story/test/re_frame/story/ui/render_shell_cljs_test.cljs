(ns re-frame.story.ui.render-shell-cljs-test
  "CLJS-side regression net for render-shell error-boundary recovery
  and slow-loading variant feedback (rf2-g5p99).

  Pairs with the JVM `re-frame.story-runtime-test` (lifecycle phase
  ordering + loader-incomplete record shape) and the CLJS
  `re-frame.story-multi-substrate-cljs-test` (per-substrate try/catch
  cell shape). This namespace pins the scenarios spec/003 §Shell
  lifecycle + spec/015 §Render shell scenarios call out as Deferred
  under bd:rf2-g5p99:

  - **Error-boundary recovery (decorator wrap throws)** — a `:hiccup`
    decorator's `:wrap` fn throws on render; `safe-decorated-view`
    catches the throw and returns a hiccup error block AROUND the
    uncoated variant view rather than letting React unmount the
    shell. Pin the projection's data shape — the error block carries
    the message string, the decorator stack ids, AND the uncoated
    view as a fallback render. This is the rf2-zme7 'never blank the
    canvas' rule.

  - **Error-boundary recovery (no decorators on the stack)** — the
    no-decorator branch must round-trip the view unchanged. Pinning
    so the safety wrapper doesn't accidentally project an error block
    for the happy path.

  - **Loader-incomplete projection** — `loader-incomplete-record`
    builds the `:rf.error/loader-incomplete` projection the canvas
    surfaces when the variant's `:loaders-complete-when` predicate is
    false past the budget. Pin the record's slot shape (the renderer
    reads `:phase`, `:predicate`, `:reason`).

  - **Slow-loading variant render: substrate-portability fallback** —
    when a substrate is unregistered (the slow-loader path the user
    sees when a custom substrate doesn't ship), `multi-substrate/
    safe-render-cell` projects the `unregistered-substrate` error
    cell. Pin the shape so the user sees a loading-affordance-style
    inline message rather than a blank cell."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.plan       :as plan]
            [re-frame.story.render     :as render]
            [re-frame.story.runtime    :as runtime]
            [re-frame.story.ui.canvas  :as canvas]
            [re-frame.story.ui.multi-substrate :as multi-substrate]
            [re-frame.story.ui.state   :as state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/runtime :machines :snapshots machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(defn- hiccup-text-flatten
  "Walk a hiccup tree and collect every string node. Used for substring
  assertions against the error projection (matches the cluster 1
  `toolbar-strip` test's flatten pattern)."
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter string?)))

;; ===========================================================================
;; rf2-g5p99 — error-boundary: decorator wrap throws
;;
;; The contract per IMPL-SPEC §2.2 + §5.5 + rf2-zme7: a throwing
;; decorator must NOT take down the shell. `safe-decorated-view`
;; catches the throw and returns an inline error projection alongside
;; the uncoated view. The user sees both: 'decorator stack <ids>
;; threw — <message>' AND the variant rendered without decorators.
;; ===========================================================================

(deftest decorator-wrap-throw-recovered-as-inline-error
  (testing "spec/003 §Shell lifecycle + IMPL-SPEC §5.5: a :wrap fn that
            throws does NOT unmount the shell. safe-decorated-view
            catches and returns a hiccup error block. The block names
            the offending decorator id AND embeds the uncoated view
            (the 'never blank the canvas' contract from rf2-zme7)"
    (let [boom-dec   {:id   :crashing-wrap
                      :body {:wrap (fn [_body _args]
                                     (throw (ex-info "wrap exploded"
                                                     {:where :under-test})))}}
          view       [:div.user "user view"]
          result     (canvas/safe-decorated-view view [boom-dec] {})
          text-bits  (hiccup-text-flatten result)]
      ;; The result is a hiccup vector, not the thrown exception —
      ;; the shell continues to render this tree.
      (is (vector? result)
          "result is a hiccup vector — render continues, no unmount")
      ;; The error message surfaces.
      (is (some #(re-find #"Decorator wrap threw" %) text-bits)
          "error projection names 'Decorator wrap threw'")
      (is (some #(re-find #"wrap exploded" %) text-bits)
          "the thrown message surfaces in the error projection")
      ;; The decorator id is named so the user can find which one threw.
      (is (some #(re-find #"crashing-wrap" %) text-bits)
          "the decorator id is named in the error projection")
      ;; The uncoated view is embedded — the rf2-zme7 'never blank' rule.
      (is (some #(= "user view" %) text-bits)
          "the user's view renders uncoated below the error block —
           the canvas never blanks per rf2-zme7"))))

(deftest decorator-wrap-throw-multiple-decorators-names-stack
  (testing "when several :hiccup decorators wrap the body and ONE
            throws, the error projection names the FULL stack so the
            user can isolate which one. Pin the id-list in the
            projection — the renderer's `decorators in stack` line"
    (let [good-1     {:id   :outer-wrap
                      :body {:wrap (fn [body _] [:div.outer body])}}
          boom       {:id   :middle-wrap
                      :body {:wrap (fn [_ _]
                                     (throw (ex-info "middle threw" {})))}}
          good-2     {:id   :inner-wrap
                      :body {:wrap (fn [body _] [:div.inner body])}}
          view       [:div.user "view"]
          result     (canvas/safe-decorated-view view [good-1 boom good-2] {})
          text-bits  (hiccup-text-flatten result)]
      ;; All three decorator ids appear in the error projection.
      (doseq [id [:outer-wrap :middle-wrap :inner-wrap]]
        (is (some #(re-find (re-pattern (name id)) %) text-bits)
            (str "decorator id " id " appears in the error projection — "
                 "the user sees the full stack to triangulate the throwing one"))))))

(deftest no-decorators-passes-view-unchanged
  (testing "the happy-path: zero decorators on the stack — safe-
            decorated-view returns the view verbatim. Pin so the safety
            wrapper doesn't accidentally project an error block when
            no decorators throw"
    (let [view   [:div.user "user view"]
          result (canvas/safe-decorated-view view [] {})]
      (is (= view result)
          "no decorators → view passes through verbatim"))))

(deftest one-passing-decorator-wraps-as-expected
  (testing "one decorator, no throws — the wrap result surfaces and the
            error-projection branch is NOT engaged"
    (let [dec    {:id   :ok-wrap
                  :body {:wrap (fn [body _]
                                 [:div.wrapper body])}}
          view   [:div.user "view"]
          result (canvas/safe-decorated-view view [dec] {})]
      ;; The wrapper is present.
      (is (= :div.wrapper (first result))
          "happy-path decorator's wrap is engaged")
      ;; No 'Decorator wrap threw' breadcrumb anywhere — the error
      ;; branch did NOT run.
      (let [text-bits (hiccup-text-flatten result)]
        (is (not-any? #(re-find #"Decorator wrap threw" %) text-bits)
            "no error projection on the happy path")))))

;; ===========================================================================
;; rf2-g5p99 — slow-loading / loader-incomplete projection
;;
;; The contract per spec/003 §Loader feedback + spec/002 §Four-phase
;; lifecycle: when a variant's :loaders-complete-when predicate is
;; false past the budget, runtime records a
;; `:rf.error/loader-incomplete` record on the variant's assertions.
;; The canvas reads this and surfaces a loading-affordance error
;; rather than a blank panel.
;; ===========================================================================

(deftest loader-incomplete-record-shape-pinned
  (testing "runtime/loader-incomplete-record produces a record whose
            slot shape the canvas's renderer reads. Pin the shape so
            a future refactor to the record doesn't silently break
            the canvas's loading-affordance render"
    (let [variant-id   :story.slow.loader/probe
          variant-body {:loaders-complete-when :probe/never}
          record       (#'runtime/loader-incomplete-record
                         variant-id variant-body)]
      (is (= :rf.error/loader-incomplete (:assertion record))
          ":assertion is the canonical error id the canvas matches on")
      (is (= variant-id (:variant-id record)))
      (is (= :phase-1-loaders (:phase record))
          ":phase tells the canvas this is a loader-time failure (not
           render or play)")
      (is (= :probe/never (:predicate record))
          ":predicate slot tells the user which :loaders-complete-when
           predicate didn't settle")
      (is (string? (:reason record))
          ":reason is a human-readable string the canvas surfaces verbatim")
      (is (false? (:passed? record))
          ":passed? is false — the assertion accumulator treats this
           as a failed assertion"))))

(deftest loader-incomplete-record-without-predicate-still-builds
  (testing "the corner case: a variant declares :loaders but no
            :loaders-complete-when (relies on the default-completion
            rule). If that rule never returns true, loader-incomplete-
            record still builds — :predicate slot is nil. The canvas
            renders a generic 'loaders did not complete' message"
    (let [variant-id   :story.slow.loader.no-pred/probe
          variant-body {:loaders [[:probe/start]]}  ; no :loaders-complete-when
          record       (#'runtime/loader-incomplete-record
                         variant-id variant-body)]
      (is (= :rf.error/loader-incomplete (:assertion record)))
      (is (nil? (:predicate record))
          "no :loaders-complete-when → :predicate is nil — the canvas
           generic-message branch engages"))))

;; ===========================================================================
;; rf2-g5p99 — unregistered-substrate inline error (slow-loading
;; substrate path)
;;
;; The user-facing variant of 'slow loading': the variant declares a
;; substrate that hasn't been registered (or hasn't loaded yet — the
;; UIx adapter is a separate npm package). multi-substrate's
;; safe-render-cell returns an inline error cell with actionable
;; guidance instead of a blank cell.
;; ===========================================================================

(deftest unregistered-substrate-renders-inline-error-cell
  (testing "spec/003 §Multi-substrate: an unregistered substrate id
            returns an inline error cell, NOT a blank or thrown.
            Mirrors the loading-affordance contract — the user sees
            a clear 'this substrate isn't registered' message with
            the call to fix it. Pin so a future refactor doesn't
            silently strip the error message.

            We use the in-enum but possibly-unregistered :uix substrate
            (per the :substrates enum #{:reagent :uix :helix}). At
            CLJS test boot the Reagent adapter installs :reagent but
            not :uix (the UIx adapter ships as a separate npm
            package). Sanity-check the precondition then drive the
            error cell"
    ;; First, REMOVE any existing :uix registration so we test the
    ;; not-registered path deterministically.
    (swap! multi-substrate/substrate->render-fn dissoc :uix)
    (is (not (contains? @multi-substrate/substrate->render-fn :uix))
        "precondition: :uix not in the substrate registry")
    (let [variant-id :story.substrate.missing/probe]
      ;; Use :uix from the canonical enum — it parses, but the runtime
      ;; map doesn't carry a render-fn for it.
      (story/reg-variant variant-id
        {:substrates #{:uix}
         :events     []})
      ;; Drive the renderer: multi-substrate-grid is the outer fn the
      ;; canvas dispatches to; the inner safe-render-cell is what
      ;; surfaces the error cell. We render the grid then walk for the
      ;; expected text.
      (let [hiccup    (multi-substrate/multi-substrate-grid variant-id)
            text-bits (hiccup-text-flatten hiccup)]
        (is (some #(re-find #"uix" %) text-bits)
            "the missing substrate id is named in the error cell")
        (is (some #(re-find #"is not registered" %) text-bits)
            "the actionable message names the contract — the user
             can fix it by calling register-substrate!")))))

(deftest registered-substrate-renders-cell-body
  (testing "the happy-path baseline: a substrate IS registered →
            safe-render-cell engages the render-fn, not the error
            branch. Pin so the error branch doesn't fire spuriously.

            Install a stub render-fn under :uix (an in-enum substrate
            slot); the variant uses :uix and the cell renders via the
            stub. The :reagent slot is untouched (the canvas's
            default-substrate baseline)"
    (multi-substrate/register-substrate!
      :uix
      (fn [_vid _view-id _args]
        [:div.stub-cell "rendered via stub"]))
    (let [variant-id :story.substrate.ok/probe]
      (story/reg-variant variant-id
        {:substrates #{:uix}
         :events     []})
      (let [hiccup    (multi-substrate/multi-substrate-grid variant-id)
            text-bits (hiccup-text-flatten hiccup)]
        ;; On the happy-path branch, safe-render-cell wraps the cell
        ;; body in a Reagent class component (r/create-class) so the
        ;; rendered stub hiccup is NOT visible at the top-level walk —
        ;; React resolves it on mount. What we CAN assert at the data
        ;; level: the error-cell branch did NOT engage. The "is not
        ;; registered" text only appears on the error branch.
        (is (not-any? #(re-find #"is not registered" %) text-bits)
            "no error cell rendered on the happy path — the registered
             render-fn took the registered branch")
        ;; And the canonical reagent vector form survives — the grid
        ;; outer wrap renders.
        (is (= :div (first hiccup))
            "grid outer wrap rendered")))))

;; ===========================================================================
;; rf2-hzhmv / rf2-ba86n.8 — render-variant host APPLIES decorators
;;
;; The host-application gap rf2-hzhmv flagged: the render-variant host hook
;; (`canonical/render-host-scope`) used to render the BARE view, dropping the
;; variant's :decorators — so a render-variant render of a decorated variant
;; diverged from the live canvas (which wraps via safe-decorated-view). The
;; consolidation routes BOTH through the shared
;; `multi-substrate/render-decorated-view` seam, so they paint the same tree.
;; These CLJS tests prove the HOST actually applies the decorators — the
;; existing render_test §decorators-are-view-wrapping only pinned that
;; :decorators RIDE render-inputs, never that the host APPLIES them. Uses a
;; REGISTERED variant via the DEFAULT lookup (the production path the
;; rf2-din8u gate mandates).
;; ===========================================================================

(deftest render-decorated-view-wraps-via-shared-seam
  (testing "the shared render-decorated-view seam (the host hook + the canvas
            both call it) wraps the rendered view in the variant's :hiccup
            decorators resolved from the compiled plan's [:world :decorators]"
    (story/reg-decorator :deco/themed
      {:kind :hiccup :wrap (fn [body _] [:div.themed body])})
    (rf/reg-sub :probe/label (fn [db _] (:label db)))
    (rf/reg-view* :views/probe (fn [_] [:span.leaf "leaf"]))
    (story/reg-variant :story.hostdeco/v
      {:component  :views/probe
       :decorators [[:deco/themed]]
       :events     []})
    (let [plan        (plan/variant-plan :story.hostdeco/v)
          deco-refs   (get-in plan [:world :decorators])
          rendered    (multi-substrate/render-decorated-view
                        :reagent :story.hostdeco/v :views/probe {} deco-refs)
          ;; the OUTERMOST node must be the decorator wrap, not the bare view.
          outer       (first rendered)]
      (is (= [[:deco/themed]] deco-refs)
          "the compiled plan carries the decorator refs at [:world :decorators]")
      (is (= :div.themed outer)
          "render-decorated-view wraps the view in the :hiccup decorator —
           the host no longer paints the bare view (rf2-hzhmv)"))))

(deftest render-decorated-view-bare-when-no-decorators
  (testing "a variant with NO :decorators renders bare through the shared
            seam — render-transparent (no spurious wrapper)"
    (rf/reg-view* :views/plain (fn [_] [:span.leaf "leaf"]))
    (story/reg-variant :story.hostnodeco/v
      {:component :views/plain :events []})
    (let [plan      (plan/variant-plan :story.hostnodeco/v)
          deco-refs (get-in plan [:world :decorators])
          rendered  (multi-substrate/render-decorated-view
                      :reagent :story.hostnodeco/v :views/plain {} deco-refs)]
      (is (nil? deco-refs) "no decorators on the plan")
      ;; render-view returns the bare [view eff-args] vector for :reagent.
      (is (not= :div.themed (first rendered))
          "no decorator wrapper engaged — the bare view passes through"))))

(deftest render-variant-and-canvas-resolve-same-inherited-decorators
  (testing "render-variant's render-inputs and the canvas decorator pack
            resolve the SAME :extends-inherited decorator off the compiled
            plan — the registered (DEFAULT-lookup) production path
            (rf2-hzhmv / rf2-ba86n.8 / rf2-g74i9)"
    (story/reg-decorator :deco/parent-themed
      {:kind :hiccup :wrap (fn [body _] [:div.parent-themed body])})
    (story/reg-variant :story.inhdeco/parent
      {:component  :views/probe
       :decorators [[:deco/parent-themed]]
       :events     []})
    ;; child inherits the parent's decorator via :extends, declares none.
    (story/reg-variant :story.inhdeco/child
      {:extends :story.inhdeco/parent
       :events  []})
    (let [prepared    (render/prepare-render :story.inhdeco/child)
          rv-refs     (get-in prepared [:render-inputs :decorators])
          canvas-ids  (mapv :id (:hiccup (story/resolve-decorators :story.inhdeco/child)))]
      (is (= [[:deco/parent-themed]] rv-refs)
          "render-variant carries the INHERITED decorator refs (NOT empty —
           the bare-body read would have dropped the :extends-inherited stack)")
      (is (= [:deco/parent-themed] canvas-ids)
          "the canvas resolves the SAME inherited decorator — both off the
           compiled plan's [:world :decorators]"))))

(deftest render-variant-renders-decorated-variant-end-to-end
  (testing "render-variant returns :rendered for a registered decorated
            variant (the host is installed by the canonical vocabulary), so
            the single render path paints — not :cannot-run"
    (story/reg-decorator :deco/e2e
      {:kind :hiccup :wrap (fn [body _] [:div.e2e body])})
    (rf/reg-view* :views/e2e (fn [_] [:span "v"]))
    (story/reg-variant :story.e2edeco/v
      {:component  :views/e2e
       :decorators [[:deco/e2e]]
       :events     []})
    (let [r (render/render-variant :story.e2edeco/v)]
      (is (= :rendered (:status r))
          "the host is installed (CLJS canonical vocabulary) → :rendered")
      (is (some? (:rendered r))
          "the host returns a render result (the render-host-scope component
           vector) — the single render path paints the decorated tree"))))
