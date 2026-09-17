(ns re-frame.subs-render-owned-frame-slot-cljs-test
  "The render-owned reference (Spec 006 §Ratom-family lifetime, rf2-ty246) is
  ONE reference per (owning reaction, SLOT). This namespace pins the SLOT half
  of that pair — the half rf2-kk986 found missing.

  THE DEFECT. `re-frame.subs/claim-render-owned-ref!` recorded the owning
  reaction's holdings in a map keyed by the cache key alone. A sub-cache is PER
  FRAME, and `subscribe` takes an explicit `{:frame target}`, so ONE render may
  read the same query from two frames: two different cached reactions under one
  identical cache key. The second claim overwrote the first, and on every
  re-render each frame's reaction failed the identity guard against the other's
  — so neither duplicate bump was released, both `:ref-count`s climbed with
  RENDERS rather than readers, and one more release callback was registered on
  the owner per render per frame. That is precisely the render-tally the
  mechanism exists to abolish, surviving on the multi-frame path.

  WHICH DIRECTION EACH TEST PINS. The key has to be exactly as fine as a cache
  slot — no finer, no coarser — and the two ways of getting it wrong fail in
  opposite directions, so both are pinned:

    * `...-across-frames` is the RED row. It fails when the key is too COARSE
      (the shipped `k`-alone keying collapsed two frames into one holding).
      Cross-frame only: every assertion in it is about two frames.

    * `...-same-frame-controls` is GREEN IN BOTH DIRECTIONS by construction and
      is NOT a control for the cross-frame fix — it pins backward compatibility
      and the OTHER failure direction. Its re-render and duplicate-read rows go
      red if the key is too FINE (a per-read key would never match the identity
      guard, so every read would look new and the count would climb again), and
      its two-queries-one-frame row goes red if the key is too coarse in the
      other axis (keying by frame alone collapses two queries exactly as key
      alone collapsed two frames). It is also the scoping evidence: the
      single-frame path every existing caller is on today must not move, and
      these rows are what says it did not.

  WHY THIS LANE. `claim-render-owned-ref!` is `#?(:cljs ...)`-only and fires
  only when the `:adapter/reactive-owner` late-bind hook resolves, which the
  ratom family alone publishes — so no JVM artefact can reach this code at all.
  Both ratom adapters publish that hook as `(fn [] ratom/*ratom-context*)`, and
  both are exercised below through one shared scenario body.

  A `make-reaction` driven by `activate!`/`run` and `flush!` IS the owning
  render reaction: a component's render Reaction is the same object reached the
  same way, and `re-frame.subs` knows nothing else about it. Each scenario
  drives re-renders off a plain ratom the test owns rather than off a
  subscription invalidation, so a render is one hop from the test and cannot be
  confused with re-frame's own propagation — and each asserts its render
  COUNTER moved before it believes any count that did not, since a body that
  silently failed to re-run would leave every `:ref-count` at 1 and read green
  for the wrong reason.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.ratom :as stock-ratom]
            [reagent2.ratom :as slim-ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; ---- per-adapter configuration --------------------------------------------
;;
;; The two ratom substrates differ only in which library supplies the four
;; primitives a render needs: a source container, a reaction, the first
;; capture run, and the queue drain that performs subsequent ones. reagent2
;; dropped stock Reagent's `IRunnable` and spells the first capture run
;; `activate!`; everything else lines up name for name.

(def ^:private slim-config
  {:label     "reagent-slim"
   :adapter   rf.adapter.reagent-slim/adapter
   :ratom     slim-ratom/atom
   :reaction  slim-ratom/make-reaction
   :start!    slim-ratom/activate!
   :flush!    slim-ratom/flush!
   :release!  slim-ratom/dispose!})

(def ^:private stock-config
  {:label     "reagent"
   :adapter   rf.adapter.reagent/adapter
   :ratom     stock-ratom/atom
   :reaction  stock-ratom/make-reaction
   :start!    stock-ratom/run
   :flush!    stock-ratom/flush!
   :release!  stock-ratom/dispose!})

;; ---- harness --------------------------------------------------------------

(def ^:private frame-a :kk986/frame-a)
(def ^:private frame-b :kk986/frame-b)
(def ^:private query-n [:kk986/n])
(def ^:private query-m [:kk986/m])

(defn- with-adapter
  "Cold-start `adapter`, run `body-fn`, then tear the whole lifecycle back
  down so the next scenario (and the next adapter) starts from a
  never-installed state. Mirrors the fixture in
  `re-frame.adapter.reagent-slim-dispose-sub-cache-walk-cljs-test`."
  [adapter body-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! adapter)
  (rf.frame/ensure-default-frame!)
  (try
    (body-fn)
    (finally
      (when (rf.substrate.adapter/current-adapter)
        (rf.substrate.adapter/dispose-adapter!))
      (reset! rf.frame/frames {})
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))

(defn- ref-count
  "The `:ref-count` `frame-id`'s sub-cache currently records for `query-v`,
  or nil when that frame holds no slot for it. Reads the cache entry map
  directly, the same way the sub-cache walk tests do."
  [frame-id query-v]
  (some-> (get @rf.frame/frames frame-id)
          :sub-cache
          deref
          (get query-v)
          :ref-count))

(defn- register-fixtures!
  "Two frames, one seed event, two single-source subs. Registration is
  global; the frames are per-scenario because `with-adapter` wipes the
  frame registry around every scenario."
  []
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/reg-event :kk986/seed (fn [{:keys [db]} [_ n m]] {:db (assoc db :n n :m m)}))
  (rf/reg-sub :kk986/n (fn [db _] (:n db)))
  (rf/reg-sub :kk986/m (fn [db _] (:m db)))
  (rf/dispatch-sync [:kk986/seed 1 11] {:frame frame-a})
  (rf/dispatch-sync [:kk986/seed 2 22] {:frame frame-b}))

(defn- render-harness
  "Build an owning reaction whose body is `read-fn` (a thunk that derefs
  subscriptions and returns their values), plus the two levers a test
  needs: `render!` performs one more render, and `renders` / `last-value`
  report what the body actually did.

  The owner watches a plain ratom the harness owns, so one `render!` is
  exactly one re-run of the body under capture — no dependence on
  re-frame's own invalidation, which is the thing under test."
  [{:keys [ratom reaction start! flush! release!]} read-fn]
  (let [tick       (ratom 0)
        renders    (volatile! 0)
        last-value (volatile! nil)
        owner      (reaction (fn []
                               @tick
                               (vswap! renders inc)
                               (vreset! last-value (read-fn))))]
    (start! owner)
    {:renders    renders
     :last-value last-value
     :render!    (fn []
                   (swap! tick inc)
                   (flush!))
     :dispose!   (fn [] (release! owner))}))

;; ---- the cross-frame row (rf2-kk986) --------------------------------------

(defn- cross-frame-scenario
  "ONE owner reads ONE query from TWO frames. Both slots must sit at exactly
  one reference for this owner, however many times it renders, and final
  disposal must release each of them once.

  RED before the frame half of the slot key landed: the two frames' cached
  reactions share a cache key, so under key-alone keying each render's claim
  overwrote the other's holding, neither duplicate bump was released, and both
  counts climbed 1, 2, 3, ... with renders."
  [{:keys [label] :as config}]
  (testing (str label ": one owner reading one query from TWO frames holds "
                "exactly one reference in each")
    (register-fixtures!)
    (let [{:keys [renders last-value render! dispose!]}
          (render-harness config
                          (fn []
                            [@(rf/subscribe query-n {:frame frame-a})
                             @(rf/subscribe query-n {:frame frame-b})]))]
      (is (= 1 @renders)
          (str label ": precondition — the owner ran its body once, under capture"))
      (is (= [1 2] @last-value)
          (str label ": precondition — the owner really read BOTH frames, and "
               "the two frames really hold different values"))
      (is (= 1 (ref-count frame-a query-n))
          (str label ": frame A holds ONE reference after render 1"))
      (is (= 1 (ref-count frame-b query-n))
          (str label ": frame B holds ONE reference after render 1"))

      (dotimes [n 3]
        (let [render-no (+ 2 n)]
          (render!)
          (is (= render-no @renders)
              (str label ": precondition — the owner re-ran for render " render-no
                   " (a body that did not re-run would leave every count at 1 "
                   "and read green for the wrong reason)"))
          (is (= 1 (ref-count frame-a query-n))
              (str label ": frame A STILL holds one reference after render "
                   render-no " — the count is readers, not renders"))
          (is (= 1 (ref-count frame-b query-n))
              (str label ": frame B STILL holds one reference after render "
                   render-no " — the count is readers, not renders"))))

      (dispose!)
      (is (nil? (ref-count frame-a query-n))
          (str label ": frame A's slot is gone after the owner disposes — its "
               "one reference was released once"))
      (is (nil? (ref-count frame-b query-n))
          (str label ": frame B's slot is gone after the owner disposes — its "
               "one reference was released once")))))

;; ---- the same-frame rows --------------------------------------------------
;;
;; Green in BOTH directions by construction. They are not controls for the
;; cross-frame fix; they pin the single-frame path that every caller is on
;; today, and they are the rows that go red if the slot key is made too fine
;; (the re-render and duplicate-read rows) or too coarse along the other axis
;; (the two-queries row).

(defn- same-frame-re-render-scenario
  [{:keys [label] :as config}]
  (testing (str label ": one owner re-reading one query in ONE frame still "
                "holds exactly one reference")
    (register-fixtures!)
    (let [{:keys [renders render!]}
          (render-harness config (fn [] @(rf/subscribe query-n {:frame frame-a})))]
      (is (= 1 @renders) (str label ": precondition — one capture run"))
      (is (= 1 (ref-count frame-a query-n))
          (str label ": single-frame read is one reference after render 1"))
      (dotimes [n 3]
        (let [render-no (+ 2 n)]
          (render!)
          (is (= render-no @renders)
              (str label ": precondition — the owner re-ran for render " render-no))
          (is (= 1 (ref-count frame-a query-n))
              (str label ": single-frame read is STILL one reference after render "
                   render-no)))))))

(defn- same-frame-duplicate-read-scenario
  [{:keys [label] :as config}]
  (testing (str label ": one owner reading the SAME query twice in ONE render "
                "releases the duplicate immediately")
    (register-fixtures!)
    (let [{:keys [renders last-value render!]}
          (render-harness config
                          (fn []
                            [@(rf/subscribe query-n {:frame frame-a})
                             @(rf/subscribe query-n {:frame frame-a})]))]
      (is (= [1 1] @last-value)
          (str label ": precondition — both reads resolved in the same frame"))
      (is (= 1 (ref-count frame-a query-n))
          (str label ": two reads of one slot in one render are one reference"))
      (render!)
      (is (= 2 @renders)
          (str label ": precondition — the owner re-ran for render 2"))
      (is (= 1 (ref-count frame-a query-n))
          (str label ": two reads of one slot are STILL one reference after "
               "render 2")))))

(defn- same-frame-two-queries-scenario
  [{:keys [label] :as config}]
  (testing (str label ": one owner reading TWO queries in ONE frame holds them "
                "as separate slots")
    (register-fixtures!)
    (let [{:keys [renders last-value render!]}
          (render-harness config
                          (fn []
                            [@(rf/subscribe query-n {:frame frame-a})
                             @(rf/subscribe query-m {:frame frame-a})]))]
      (is (= [1 11] @last-value)
          (str label ": precondition — the two queries really resolve to "
               "different values"))
      (is (= 1 (ref-count frame-a query-n))
          (str label ": first query is one reference after render 1"))
      (is (= 1 (ref-count frame-a query-m))
          (str label ": second query is one reference after render 1"))
      (dotimes [n 3]
        (let [render-no (+ 2 n)]
          (render!)
          (is (= render-no @renders)
              (str label ": precondition — the owner re-ran for render " render-no))
          (is (= 1 (ref-count frame-a query-n))
              (str label ": first query is STILL one reference after render "
                   render-no))
          (is (= 1 (ref-count frame-a query-m))
              (str label ": second query is STILL one reference after render "
                   render-no)))))))

;; ---- entries --------------------------------------------------------------

(deftest render-owned-refs-do-not-collide-across-frames-reagent-slim
  (with-adapter (:adapter slim-config) #(cross-frame-scenario slim-config)))

(deftest render-owned-refs-do-not-collide-across-frames-reagent
  (with-adapter (:adapter stock-config) #(cross-frame-scenario stock-config)))

(deftest render-owned-refs-same-frame-controls-reagent-slim
  (with-adapter (:adapter slim-config) #(same-frame-re-render-scenario slim-config))
  (with-adapter (:adapter slim-config) #(same-frame-duplicate-read-scenario slim-config))
  (with-adapter (:adapter slim-config) #(same-frame-two-queries-scenario slim-config)))

(deftest render-owned-refs-same-frame-controls-reagent
  (with-adapter (:adapter stock-config) #(same-frame-re-render-scenario stock-config))
  (with-adapter (:adapter stock-config) #(same-frame-duplicate-read-scenario stock-config))
  (with-adapter (:adapter stock-config) #(same-frame-two-queries-scenario stock-config)))
