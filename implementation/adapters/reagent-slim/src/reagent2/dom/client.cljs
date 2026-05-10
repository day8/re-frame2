(ns reagent2.dom.client
  "Mount entry + test-flush primitive for the day8/reagent-slim artefact.

  Stage 4-B scope (rf2-6hyy): the `flush-views!` test primitive lands
  here per IMPL-SPEC §2.4 + §4. The mount entries (`create-root`,
  `render`, `unmount`, `hydrate-root`) are scaffolded as deferred so
  Stage 4-D's hiccup interpreter (`reagent2.impl.template`) can wire
  the render path without re-touching this ns. Their final
  implementations land with Stage 4-D / 4-E.

  Public Vars (per IMPL-SPEC §2.4):

    create-root      [container] | [container options]
    render           [root el]
    unmount          [root]
    hydrate-root     [container el] | [container el options]
    flush-views!     []      ;; test-flush primitive — implemented here

  The `flush-views!` contract (IMPL-SPEC §4.6):

    After (flush-views!) returns:
      - All currently-dirty components have re-rendered.
      - All Reactions whose dependencies changed have recomputed.
      - All :after-render callbacks queued before flush-views! fired.
      - React's pending work has committed (act() has run to completion).

  Suspense-ordering choice for `flush-views!` (rf2-w6ef):

    microtask -> act -> microtask

    Step 1. Drain the microtask queue first (await Promise.resolve())
            so any in-flight rea-schedule that's already been queued
            gets to run. This converts pending reactive work into
            React-visible state changes BEFORE we hand off to act.

    Step 2. react/act wraps a synchronous (batching/flush!) call. Inside
            act, React drains its commit phase + any Suspense boundaries
            whose pending Promises have resolved.

    Step 3. Drain the microtask queue once more so post-commit
            after-render hooks + any Reaction recomputes triggered
            during commit observe their final values before
            flush-views! returns.

    Why this order: a dispatch-then-flush test does
    `(rf/dispatch-sync ...) (flush-views!)`. The dispatch may already
    have advanced pending Reactions; step 1 collects that. Step 2
    pumps React itself. Step 3 settles any tail-cascade Reactions
    that ran inside the React commit. With Suspense: a child
    component that throws a Promise during render in step 2
    causes act to await the Promise; on resolution act re-renders
    the now-ready subtree. Step 3 then catches any Reaction
    recomputes triggered by that re-render.

    Determinism guarantee: a test that dispatches an event whose
    handler (a) updates app-db, (b) causes a subscription to recompute,
    (c) re-renders a component, (d) the component throws a Promise
    that resolves synchronously — the test's post-flush-views!
    assertion sees the resolved-tree state. Demonstrated by the
    Suspense test in dom_client_cljs_test.cljs.

  Production cost: zero. `flush-views!` is gated on `js/goog.DEBUG` and
  DCEs entirely under `:advanced` + `goog.DEBUG=false`."
  (:require [reagent2.impl.batching :as batching]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]))

;; ---------------------------------------------------------------------------
;; flush-views! — test-flush primitive (Stage 4-B)
;; ---------------------------------------------------------------------------
;;
;; Implementation note on `react/act`: under React 18.3+ `act` is a
;; top-level export of `react`. Under React 18.2 `act` lived in
;; `react-dom/test-utils`. The artefact targets React 19 (Stage 1
;; commitment); we still feature-detect to stay graceful under the
;; React 18.3 dev tree the implementation/ tests run under, since
;; that ALSO has `react/act`.
;;
;; Per IMPL-SPEC §4.2 `flush-views!` is dev-only: gated on `js/goog.DEBUG`
;; so :advanced + `goog.DEBUG=false` DCEs the body entirely.
;; ---------------------------------------------------------------------------

(defn- resolve-act
  "Look up React's `act`. Returns the fn or nil. Cached across calls
  in dev — re-resolved once per `flush-views!` so a test fixture that
  swaps the React module mid-run sees the swap."
  []
  (or (.-act react)
      ;; Fallback: pre-18.3 lived in react-dom/test-utils. We don't
      ;; require that ns up top because Stage 4-B's React floor is
      ;; 19+; the .-act check on the react module is the only path
      ;; we exercise.
      nil))

(defn- microtask-tick
  "Return a Promise that resolves on the next microtask turn. Awaiting
  this inside `act`'s thunk lets React process pending work that's
  scheduled as a microtask continuation."
  []
  (js/Promise.resolve))

(defn flush-views!
  "Drain pending render work synchronously. Test-only primitive.

  Composes a 3-phase drain: microtask -> act(flush!) -> microtask.
  Returns a Promise so callers can `await` deterministic completion;
  the synchronous side-effects (dirty components forceUpdate'd,
  Reactions recomputed, :after-render hooks fired, React commit
  complete) happen by the time `act`'s callback returns.

  Production cost: zero — the body is gated on `goog.DEBUG` so
  `:advanced` + `goog.DEBUG=false` DCEs it entirely."
  []
  (when ^boolean js/goog.DEBUG
    (let [act (resolve-act)]
      (if (nil? act)
        ;; No `act` available — degrade to a plain synchronous flush.
        ;; Tests running under :node-test (no real React render path)
        ;; still get a valid drain of the rea-queue + dirty-set.
        (do (batching/flush!) nil)
        (act
          (fn []
            ;; Step 1: microtask tick — let any pending rea-schedule
            ;; microtask run before we drive the synchronous drain.
            ;; Step 2: synchronous flush! drains rea-queue + dirty-set.
            ;; Step 3: a second microtask tick lets React's commit-phase
            ;; settle before act returns.
            (-> (microtask-tick)
                (.then (fn [_]
                         (batching/flush!)
                         (microtask-tick))))))))))

;; ---------------------------------------------------------------------------
;; Mount entries — Stage 4-D / 4-E will wire these to the hiccup
;; interpreter. Stage 4-B ships the scaffolds so consumers can
;; `:require` this ns without a load-time error; calls into the
;; mount fns throw a clear "not yet implemented" until 4-D lands.
;; ---------------------------------------------------------------------------

(defn create-root
  "React 19 root constructor. Wraps `react-dom-client/createRoot`.

  Stage 4-B: thin wrapper; Stage 4-D wires this into the render pipeline."
  ([container]
   (react-dom-client/createRoot container))
  ([container options]
   (react-dom-client/createRoot container options)))

(defn render
  "Render hiccup `el` into `root`. Walks `el` via
  `reagent2.impl.template/as-element` (Stage 4-D).

  Stage 4-B placeholder — throws until 4-D lands. The signature is
  pinned now so consumers can compile against it."
  [_root _el]
  (throw (ex-info "reagent2.dom.client/render not implemented until Stage 4-D"
                  {:type :rf.error/not-implemented
                   :stage :4-D})))

(defn unmount
  "Detach `root`. Wraps `(.unmount root)`."
  [^js root]
  (when (and (some? root)
             (some? (.-unmount root)))
    (.unmount root)))

(defn hydrate-root
  "SSR hydration entry. Wraps `react-dom-client/hydrateRoot`.

  Stage 4-B placeholder — throws until 4-D lands."
  ([_container _el]
   (throw (ex-info "reagent2.dom.client/hydrate-root not implemented until Stage 4-D"
                   {:type :rf.error/not-implemented
                    :stage :4-D})))
  ([_container _el _options]
   (throw (ex-info "reagent2.dom.client/hydrate-root not implemented until Stage 4-D"
                   {:type :rf.error/not-implemented
                    :stage :4-D}))))
