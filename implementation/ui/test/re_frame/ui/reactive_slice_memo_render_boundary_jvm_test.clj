(ns re-frame.ui.reactive-slice-memo-render-boundary-jvm-test
  "rf2-vxgfnd.267 — the slice-scoped probe memo must be established at the REAL
  public Tier-1 JVM render boundaries, not only inside `reactive/with-capture`
  (which the JVM path never enters).

  The public headless render surface — `re-frame.ui.tree/render` and
  `ui.test/render`'s view-reference / literal / plan-bearing routes — executes
  the compiled view thunk and probes `sub-read` DIRECTLY. Before this fix the
  JVM slice scope lived only in the reactive host entry `with-capture`, so a
  Tier-1 render ran OUTSIDE any scope and `current-slice-memo` minted a FRESH
  handle for every probe: two cold sibling sites sharing one derived parent
  recomputed it once per site (deterministic multiplicative work), and the
  first-mount fan-out mitigation `rf2-vxgfnd.174` required was absent precisely
  on the public Tier-1 entry.

  These fixtures exercise the ACTUAL public host boundary (unlike the
  synthetic-ViewCell `.174`/`.160` fixtures), counting cold-parent recomputes:

    - within ONE render the sibling probes share ONE memo — the parent computes
      exactly once (fresh-per-call would make it two);
    - a LATER render with UNCHANGED frame/incarnation/registry tags recomputes
      the parent (between-render disposal — the prior slice is unreachable with
      no `reset-scheduler!`/tag change; a surviving global holder would reuse it
      and the parent would NOT recompute);
    - the scope is re-entrant (a nested slice reuses the enclosing one) and a
      bare probe outside any scope is fresh per call;
    - concurrent renders are thread-local-isolated and a sequential executor
      task cannot reuse a prior task's table.

  JVM-only: the memo is a THREAD-LOCAL render scope on the JVM (the CLJS host
  shares its synchronous pass through a module holder). Uses `future` /
  `CyclicBarrier` / a single-thread executor for the isolation proofs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]
            [re-frame.ui.tree :as tree])
  (:import [java.util.concurrent CyclicBarrier Executors TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t] (frames/reset-installed-plans!) (t) (frames/reset-installed-plans!)))

;; ---------------------------------------------------------------------------
;; Fixtures — two sibling subs sharing ONE cold derived parent, whose compute
;; increments a shared counter so a recompute is observable.
;; ---------------------------------------------------------------------------

(def ^:private parent-runs (atom 0))

(defn- reg-slice-subs! []
  (reset! parent-runs 0)
  (rf/reg-sub :slice/parent (fn [db _] (swap! parent-runs inc) (:n db)))
  (rf/reg-sub :slice/a :<- [:slice/parent] (fn [n _] (str "a" n)))
  (rf/reg-sub :slice/b :<- [:slice/parent] (fn [n _] (str "b" n))))

(defview siblings
  "Two sibling `(sub …)` sites reading DISTINCT children of ONE shared cold
  derived parent. Rendered once, the parent computes once iff the sites share a
  slice memo."
  []
  [:div
   [:span (ui/sub [:slice/a])]
   [:span (ui/sub [:slice/b])]])

;; ---------------------------------------------------------------------------
;; Route 1 — `ui.test/render` view reference (render-view* → render-with-opts)
;; ---------------------------------------------------------------------------

(deftest ui-test-render-view-reference-shares-one-slice-memo
  (reg-slice-subs!)
  (let [f (uit/frame {:app-db {:n 5}})]
    (reset! parent-runs 0)
    (uit/render siblings {:frame f})
    (is (= 1 @parent-runs)
        "within ONE Tier-1 view-reference render the sibling probes share one
         memo — the cold derived parent computes ONCE, not once per sibling
         (fresh-per-call, the pre-fix defect, would make it two)")
    (uit/render siblings {:frame f})
    (is (= 2 @parent-runs)
        "a LATER render (unchanged frame/incarnation/registry tags, no
         reset-scheduler!) recomputes the parent exactly once — the prior
         slice's memo was unreachable when the render returned; a surviving
         global holder would reuse the tag-matching table and NOT recompute")))

;; ---------------------------------------------------------------------------
;; Route 2 — `ui.test/render` plan-free literal root form
;; (render-form* → render-with-opts): converges with route 1 through
;; render-with-opts, and route 3 (plan-bearing) through render-plan-bearing.
;; ---------------------------------------------------------------------------

(deftest ui-test-render-literal-form-shares-one-slice-memo
  (reg-slice-subs!)
  (let [f (uit/frame {:app-db {:n 5}})]
    (reset! parent-runs 0)
    (uit/render [siblings {}] {:frame f})
    (is (= 1 @parent-runs)
        "the plan-free literal route shares one slice memo within a render —
         it converges with the view-reference route through render-with-opts")))

(deftest ui-test-render-plan-bearing-shares-one-slice-memo
  (reg-slice-subs!)
  (uit/render [ui/frame-root {:id :slice/plan-a
                              :initial-events [[:rf/set-db {:n 5}]]}
               [siblings {}]])
  (is (= 1 @parent-runs)
      "the plan-bearing Tier-1 route (render-plan-bearing) shares one slice memo
       within a render — the parent computes once across both sibling probes")
  (uit/render [ui/frame-root {:id :slice/plan-b
                              :initial-events [[:rf/set-db {:n 7}]]}
               [siblings {}]])
  (is (= 2 @parent-runs)
      "each plan-bearing render is its OWN slice — a later render recomputes
       (its preflighted frame is a distinct render scope)"))

;; ---------------------------------------------------------------------------
;; Route 3 — `re-frame.ui.tree/render` (the frameless public JVM entry)
;; ---------------------------------------------------------------------------

(deftest tree-render-shares-one-slice-memo
  (reg-slice-subs!)
  (let [fid :slice/tree-frame]
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid {:n 5})
    (reset! parent-runs 0)
    (rf/with-frame fid (tree/render siblings {}))
    (is (= 1 @parent-runs)
        "tree/render establishes one slice memo — the sibling probes share the
         cold derived parent, computing it once")
    (rf/with-frame fid (tree/render siblings {}))
    (is (= 2 @parent-runs)
        "a later tree/render recomputes the parent once (unchanged tags, no
         reset) — between-render disposal on the tree/render boundary too")
    (frame/destroy-frame! fid)))

;; ---------------------------------------------------------------------------
;; Re-entrancy + the bare-probe boundary (AC4)
;; ---------------------------------------------------------------------------

(deftest nested-slice-scope-reuses-the-enclosing-slice
  (reg-slice-subs!)
  (let [fid :slice/nested]
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid {:n 5})
    (reset! parent-runs 0)
    (rf/with-frame fid
      (reactive/with-slice-memo
        (fn []
          (reactive/sub-read [:slice/a])
          ;; a NESTED slice scope must REUSE the enclosing one, never open a
          ;; second table — so the shared parent is NOT recomputed inside it.
          (reactive/with-slice-memo
            (fn [] (reactive/sub-read [:slice/b]))))))
    (is (= 1 @parent-runs)
        "a nested with-slice-memo reuses the enclosing slice (re-entrant) — the
         shared parent computes once across both scopes, so a Tier-1 render and
         a with-capture nested in it share ONE slice")
    (frame/destroy-frame! fid)))

(deftest bare-probe-outside-a-scope-is-fresh-per-call
  (reg-slice-subs!)
  (let [fid :slice/bare]
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid {:n 5})
    (reset! parent-runs 0)
    ;; two bare sub-reads OUTSIDE any render slice: each mints a fresh per-call
    ;; memo, so the shared parent is recomputed each time (no cross-call table).
    (rf/with-frame fid (reactive/sub-read [:slice/a]))
    (rf/with-frame fid (reactive/sub-read [:slice/b]))
    (is (= 2 @parent-runs)
        "outside a Tier-1 render slice each bare probe is fresh per call — the
         parent recomputes (no cross-call/global memo retention on the JVM)")
    (frame/destroy-frame! fid)))

;; ---------------------------------------------------------------------------
;; Thread isolation (AC3) — the memo is a THREAD-LOCAL render scope, never a
;; global holder shared across renders/threads.
;; ---------------------------------------------------------------------------

(deftest concurrent-tier1-renders-do-not-share-a-memo
  ;; Two barrier-interleaved renders on the SAME frame. Each render's two
  ;; siblings share ONE memo internally (parent once per render), and the two
  ;; renders never share a table — total two computes. Fresh-per-call (the
  ;; pre-fix defect) would give four; a global holder shared across threads
  ;; would corrupt the count below two.
  (reg-slice-subs!)
  (let [f     (uit/frame {:app-db {:n 5}})
        _     (reset! parent-runs 0)
        gate  (CyclicBarrier. 2)
        run   (fn [] (future
                       (.await gate 10 TimeUnit/SECONDS)
                       (uit/render siblings {:frame f})))
        r1    (run)
        r2    (run)]
    @r1 @r2
    (is (= 2 @parent-runs)
        "two interleaved Tier-1 renders each compute the shared parent ONCE
         internally (2 total) — the slice memo is thread-local per render, not
         a global holder")))

(deftest sequential-executor-tasks-do-not-reuse-a-prior-table
  ;; A single worker thread runs two render tasks in sequence. The first task's
  ;; slice binding is discarded when its render returns, so the reused thread
  ;; carries NO residual scope into the second task — the parent recomputes
  ;; (total two). A global holder with no per-scope discard would let the
  ;; second task reuse the tag-matching table (total one).
  (reg-slice-subs!)
  (let [f    (uit/frame {:app-db {:n 5}})
        _    (reset! parent-runs 0)
        exec (Executors/newSingleThreadExecutor)]
    (try
      (let [t1 (.submit exec ^Callable (fn [] (uit/render siblings {:frame f})))
            _  (.get t1 10 TimeUnit/SECONDS)
            t2 (.submit exec ^Callable (fn [] (uit/render siblings {:frame f})))]
        (.get t2 10 TimeUnit/SECONDS)
        (is (= 2 @parent-runs)
            "the second sequential task cannot reuse the first's table — each
             render is its own slice, discarded on return"))
      (finally
        (.shutdownNow exec)))))

;; ---------------------------------------------------------------------------
;; Causal identity witness (rf2-vxgfnd.284, AC4/AC5) — observe distinct
;; slice-memo HANDLE IDENTITY per independent top-level render, forcing overlap
;; AT MEMO ACQUISITION rather than inferring isolation from an aggregate
;; recompute count.
;;
;; `concurrent-tier1-renders-do-not-share-a-memo` above synchronises only
;; BEFORE `uit/render` and asserts `parent-runs=2`. That is FALSE-GREEN against
;; a faulty single shared/reset global handle: with only a start barrier the two
;; renders can still run end-to-end sequentially, and a per-render-entry
;; global reset then yields two isolated computes (2) — the exact count a
;; thread-local scope gives. The witness below closes that gap by (1) tripping
;; the barrier INSIDE the shared cold parent's compute, so both renders provably
;; hold an ALREADY-ACQUIRED handle simultaneously, and (2) recording the IDENTITY
;; of every slice-memo handle each thread's probes actually thread — a direct
;; per-render-slice identity proof, internal-only (no public memo token).
;; ---------------------------------------------------------------------------

(defn- with-probe-handle-witness
  "Run `thunk` with `obs/probe` wrapped to record, keyed by the calling thread,
  the IDENTITY of every non-nil slice-memo handle threaded through a probe.
  Returns `{thread-id #{handle …}}`. Internal-only: it observes the handle a
  slice already threads, adding no public token and leaving the runtime design
  untouched. Restores the original `probe` on exit (var-root redef)."
  [thunk]
  (let [seen (atom {})
        orig obs/probe]
    (with-redefs [obs/probe
                  (fn
                    ([target] (orig target))
                    ([target slice-memo]
                     (when (some? slice-memo)
                       (swap! seen update (.getId (Thread/currentThread))
                              (fnil conj #{}) slice-memo))
                     (orig target slice-memo)))]
      (thunk))
    @seen))

(deftest concurrent-tier1-renders-thread-distinct-memo-handle-identity
  ;; Two `uit/render`s on the SAME frame, forced to OVERLAP at memo acquisition:
  ;; the barrier trips inside the shared cold parent's compute, so when it
  ;; releases BOTH renders are mid-slice with a handle already threaded. The
  ;; witness proves each render threaded EXACTLY ONE handle (within-render
  ;; sharing) and the two are DISTINCT identities (thread-local render scope,
  ;; never a shared global holder). A single reset global handle either hands a
  ;; thread two handles across the overlap (its own, then the other thread's
  ;; clobber) or converges both threads on one identity — either fails an
  ;; assertion here, DETERMINISTICALLY under the forced overlap (AC5).
  (reg-slice-subs!)
  (let [f    (uit/frame {:app-db {:n 5}})
        gate (CyclicBarrier. 2)]
    ;; Re-register the shared cold parent so its compute trips the acquisition
    ;; barrier; siblings sharing one slice handle compute it once per render, so
    ;; each render trips the barrier exactly once → the 2-party barrier releases
    ;; only when both renders are simultaneously mid-slice.
    (rf/reg-sub :slice/parent
                (fn [db _]
                  (swap! parent-runs inc)
                  (.await gate 10 TimeUnit/SECONDS)
                  (:n db)))
    (reset! parent-runs 0)
    (let [seen (with-probe-handle-witness
                 (fn []
                   (let [run (fn [] (future (uit/render siblings {:frame f})))
                         r1  (run)
                         r2  (run)]
                     @r1 @r2)))
          thread-handle-sets (vals seen)]
      (is (= 2 (count seen))
          "two distinct render threads each threaded slice-memo handles")
      (is (every? #(= 1 (count %)) thread-handle-sets)
          "each render threaded EXACTLY ONE handle across both sibling probes —
           within-render sharing held even under the forced acquisition overlap")
      (let [[ha hb] (map first thread-handle-sets)]
        (is (not (identical? ha hb))
            "the two OVERLAPPING renders threaded DISTINCT handle identities — a
             thread-local render scope, not a shared/reset global holder"))
      (is (= 2 @parent-runs)
          "each render computed the shared cold parent exactly once (2 total) —
           the aggregate count companion, now backed by the identity witness"))))
