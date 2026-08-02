(ns re-frame.bench.hicasso.coldmount-views
  "THE COLD-MOUNT DOUBLE BUILD, PRICED ON THE CLOCK — the views, the sub
  layers, the hook transcriptions and the counting witnesses
  (rf2-2rtt6.15, epic rf2-2rtt6 / EP-0038; decision input for the
  rf2-2rtt6.14 ruling, and since rf2-2rtt6.25 the FORCED-SYNCHRONOUS
  MECHANISM ARM for the hand-off that ruling adopted).

  ## THE SCHEDULE THIS PAGE MEASURES — read this before quoting a row

  Every mount this page provokes runs inside `react-dom/flushSync` — the
  timed ones through `lane/mount-arm!` and `lane/mount-batch!`, the
  counted ones in `witness!` below. That forces React's passive
  `useSyncExternalStore` subscribe to run before control returns, which
  is precisely the ordering the rf2-2rtt6.25 hand-off needs in order to
  reach its adoption before its own `setTimeout 0` reaper fires. On the
  public client mount path — `re-frame.substrate.adapter/render`, a bare
  `createRoot(…).render(…)` with nothing forcing the schedule — THE
  REAPER WINS: the escrowed reference is released, the commit misses, and
  the cold read builds twice, `bodyRuns` 2.00N measured at N = 1 and
  N = 300 (rf2-2rtt6.25, merged-PR audit of #7305; browser assertion
  `use-subscribe-public-mount-schedule-rebuilds`).

  So the `shipped` rows below are the MECHANISM measured where it is
  allowed to run to completion. They are not an acceptance witness for
  shipped mount performance and must not be quoted as one. Nothing here
  is unsound: Spec 006 §Render-phase provisional acquisition and commit
  adoption requires that correctness never depend on the reaper losing
  the race, and it does not — the lost race costs a construction and the
  steady state is one durable reference either way. The reap horizon that
  would recover the saving on the public schedule is an OPERATOR DECISION
  (rf2-2rtt6.14 ruled the primitive), and no measurement window in this
  file was altered to repair this wording.

  ## The term that was priced, and the schedule on which it is recovered

  `re-frame.substrate.spine/use-subscribe` USED TO take a BALANCED
  render-phase round trip — `subs/subscribe` immediately followed by
  `subs/unsubscribe` (the rf2-es09qq net-zero rule) — so a render that
  never commits retained no ref-count. For a query with NO live cache
  entry that is `0 -> 1 -> 0`, and `1 -> 0` is the disposal edge:
  `re-frame.subs.cache/unsubscribe!` disposes in-tick with no grace
  period. The commit-owned `subscribe-fn` then missed the cache and
  REBUILT. Two reactions per cold read; the COUNT was exact and landed
  (`bodyRuns = 2.00N` vs Reagent's `1.00N`, rf2-2rtt6.12,
  `docs/design/hicasso/studio/uix-spine-per-read-decomposition.md`), and
  this instrument priced the CLOCK of that second construction — the
  second sub-body run, the `:<-` input-chain re-deref, and the reaction
  allocation on the commit-phase rebuild — at >= 20% of the mount
  red-zone in every round at layers 1, 2 and 3.

  rf2-2rtt6.14 ruled ADOPT on that evidence, and rf2-2rtt6.25 landed the
  hook-scoped provisional hand-off. So the `handoff` arm below became a
  prediction about shipped code ON THE SCHEDULE THIS PAGE FORCES, and the
  `shipped` arm exists to check it there: same counts, same clock band.
  `xcript` stays as the double-build reference — still what a consumer
  mount pays today, and what the delta is measured against.

  ## The single-variable ablation

  Two transcriptions of the hook, differing in exactly one property —
  HOW MANY reactions a cold read constructs:

  | arm | render phase | commit phase | constructions |
  |---|---|---|---|
  | `xcript`  | subscribe + unsubscribe (build #1, dispose #1) | subscribe (build #2) | **2** |
  | `handoff` | subscribe (build #1, +1 held provisionally)    | subscribe (hit) + unsubscribe (2 -> 1) | **1** |

  Both make exactly TWO `subs/subscribe` and TWO `subs/unsubscribe` calls
  per mounted read (render, commit, unmount); both install the same
  watch, the same refs, the same six hooks. `xcript - handoff` is
  therefore the build/dispose/rebuild churn ALONE — the clock the shipped
  hand-off recovers WHEN IT RUNS TO COMPLETION, i.e. on the schedule this
  page forces and not on the public one (schedule section above).

  `handoff` remains a MEASUREMENT ARM and not a copy of the shipped code:
  it holds its provisional +1 with no reaper, so a render abandoned
  before commit would strand it. The shipped hook's reaper (a host
  macrotask armed at acquisition, `unsubscribe-if-reaction`-guarded) is
  what makes the same shape safe, and it is deliberately absent here so
  the arm stays a one-property ablation. Nothing in this file edits
  production code; the `shipped` rows read the real hook.

  ## The sub layers

  The `:<-` input-chain re-deref lives at layer 2+, so the witnesses run
  at three depths over the converged witness set's own layer-1 sub:

      layer 1   [:p0/cell i]                  (rf2-2rtt6.2's, unchanged)
      layer 2   [:cm/l2 i]  :<-  [:p0/cell i]  (parametric input-fn)
      layer 3   [:cm/l3 i]  :<-  [:cm/l2 i]

  The chain fns are identity, deliberately: the sub BODIES here are the
  witness set's near-zero bodies, so the layer ladder isolates the
  per-hop `:<-` machinery (input subscribe, input reaction construction,
  input re-deref) rather than user compute.

  ## The counting witnesses

  A displayed count that is not adjudicated is decoration, so the
  construction counts are PREDICTED and CHECKED per page before any clock
  is read: counter-instrumented sub chains (`:cm/w1` / `:cm/w2` /
  `:cm/w3`) behind counter-instrumented copies of both transcriptions AND
  behind the SHIPPED hook. For N reads at page layer L:

      xcript    commits N   rebuilt N   bodyRuns 2N at every layer <= L
      handoff   commits N   rebuilt 0   bodyRuns  N at every layer <= L
      shipped   commits N   rebuilt 0   bodyRuns  N at every layer <= L
      reagent   commits 0   rebuilt 0   bodyRuns  N at every layer <= L

  `rebuilt = 0` is what makes the ablation single-variable: the
  commit-phase acquire hands back the IDENTICAL reaction the render
  built. `shipped` reading the same row as `handoff` adjudicates that the
  MECHANISM completes — the shipped hook converging onto what the
  transcription measured, on the flushSync schedule `witness!` forces. It
  is SCHEDULE-CONDITIONAL and not an acceptance witness for shipped mount
  performance: on the public `createRoot().render()` path the same hook
  reads the `xcript` row instead. A failure here is therefore a broken
  mechanism, not a lost race. The witness components never appear in a
  timed window and the timed arms never touch a counter.

  ## How the SHIPPED arm is counted without a counter

  The transcriptions increment `commits` / `rebuilt` from inside their own
  `subscribe-fn`; the shipped hook has no such seam, and adding one would
  make the arm a fourth transcription rather than the code under test. So
  the shipped arm reads the same two integers off OBSERVABLE STATE, with
  the same meanings and no spy on `subs/subscribe` (a spy that substituted
  the reaction would defeat the hand-off's own identity guard and measure
  the instrument):

    * during render, right after `use-subscribe` returns, the witness
      records WHICH REACTION the sub-cache holds for that query — the
      render-phase materialisation if it survived the render, `nil` if the
      balanced round trip disposed it;
    * after the mount, `commits` counts the reads whose slot is tenanted
      (the commit took its durable reference) and `rebuilt` counts the
      reads whose post-commit tenant is NOT `identical?` to what the
      render observed — including every read that observed nothing.

  On the pre-hand-off spine every render observes `nil` and every read
  counts as rebuilt, which is the same `rebuilt = N` the `xcript` arm
  reports from its own counter. The two measurements are independent and
  must agree.

  Driven by `coldmount_app.cljs` / `coldmount_run.cjs` on the
  `:hicasso-bench` lane. Markup is byte-for-byte the converged set's
  (`p0_reagent_views.cljs` / `p0_uix_views.cljs`); the canonical-DOM
  parity gate proves it rather than this docstring."
  (:require ["react" :as react]
            [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [reagent.dom.client :as rdc]
            [uix.core :refer [$ defui]]
            [uix.dom :as uix-dom]
            [uix.hooks.alpha :as uix-hooks]
            ["react-dom" :as react-dom])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; The witness counters
;; ---------------------------------------------------------------------------

(def witness-counters
  "Plain counters for the adjudicated counting witness.

    body1/2/3 — sub-body invocations per layer of the witness chain
    commits   — commit-phase `subscribe-fn` calls (reads that mounted)
    rebuilt   — commit-phase acquisitions whose reaction is NOT
                `identical?` to the render-phase handle the fiber holds"
  (js-obj "body1" 0 "body2" 0 "body3" 0 "commits" 0 "rebuilt" 0))

(defn- reset-witness-counters! []
  (doseq [k ["body1" "body2" "body3" "commits" "rebuilt"]]
    (aset witness-counters k 0)))

;; ---------------------------------------------------------------------------
;; The layered subs the TIMED arms read
;; ---------------------------------------------------------------------------

(defn register!
  "The full sub graph for one segment: the converged set's layer-1
  `:p0/cell` (via `v/register!`, unchanged), the identity `:<-` chain the
  layer-2/3 rows read, and the counter-instrumented witness chain.
  Re-registering overwrites with identical handlers, so the per-segment
  re-register is idempotent (the converged arm's own pattern)."
  []
  (v/register!)
  ;; Parametric `:<-` producers — the index rides the query vector, which
  ;; the static literal `:<-` form cannot express. A parametric single
  ;; input is delivered as `[value]` (Spec 006 §Single input contract).
  (rf/reg-sub :cm/l2
    (fn [qv] [[:p0/cell (second qv)]])
    (fn [[cell] _] cell))
  (rf/reg-sub :cm/l3
    (fn [qv] [[:cm/l2 (second qv)]])
    (fn [[cell] _] cell))
  ;; -- the witness chain: identical shape, plus one counter per body ------
  (rf/reg-sub :cm/w1
    (fn [db [_ i]]
      (aset witness-counters "body1" (inc (aget witness-counters "body1")))
      (nth (:cells db) (mod i v/cells-n))))
  (rf/reg-sub :cm/w2
    (fn [qv] [[:cm/w1 (second qv)]])
    (fn [[cell] _]
      (aset witness-counters "body2" (inc (aget witness-counters "body2")))
      cell))
  (rf/reg-sub :cm/w3
    (fn [qv] [[:cm/w2 (second qv)]])
    (fn [[cell] _]
      (aset witness-counters "body3" (inc (aget witness-counters "body3")))
      cell))
  nil)

;; ---------------------------------------------------------------------------
;; The two transcriptions
;; ---------------------------------------------------------------------------
;;
;; `use-sub-xcript` is `re-frame.substrate.spine`'s `use-subscribe-2` body,
;; copied — the same transcription rf2-2rtt6.12 published and validated
;; (its heap fidelity landed 0.332% from the shipped hook). The only
;; cosmetic differences are the ones that arm recorded: hooks named
;; directly (`uix.hooks.alpha/use-memo`, which is exactly what the UIx
;; adapter passes into `make-react-spine`) and the watch-key namespace
;; spelled here. The CLOCK fidelity control in `coldmount_app.cljs` is
;; what ties it to the shipped arm on this axis.

(def ^:private watch-ns "rf-cm-use-sub")

(defn- stable-key-of
  "The `useRef` + `=` memo-by-value from the spine (rf2-mwft2), shared by
  every transcribed arm so the stable-key term cancels in the ablation."
  [key-ref frame-kw query-v]
  (let [prev    (.-current key-ref)
        new-key #js [frame-kw query-v]]
    (if (and prev
             (= (aget prev 0) frame-kw)
             (= (aget prev 1) query-v))
      prev
      (do (set! (.-current key-ref) new-key)
          new-key))))

(defn- use-sub-xcript [frame-kw query-v]
  (let [key-ref       (react/useRef nil)
        committed-ref (react/useRef nil)
        stable-key      (stable-key-of key-ref frame-kw query-v)
        stable-frame-kw (aget stable-key 0)
        stable-query-v  (aget stable-key 1)
        ;; The shipped BALANCED round trip: for a cold query this is
        ;; 0 -> 1 -> 0 — build, then dispose + evict, inside the render.
        reaction
        (uix-hooks/use-memo
          (fn []
            (let [r (subs/subscribe stable-query-v {:frame stable-frame-kw})]
              (subs/unsubscribe stable-frame-kw stable-query-v)
              r))
          #js [stable-key])
        get-snap
        (uix-hooks/use-callback
          (fn []
            (let [stored (.-current committed-ref)
                  r      (if (and stored
                                  (identical? (aget stored 0) stable-key))
                           (aget stored 1)
                           reaction)]
              (when r @r)))
          #js [stable-key reaction])
        subscribe-fn
        (uix-hooks/use-callback
          (fn [on-change]
            ;; Cold cache (the render round trip disposed the entry), so
            ;; this MISSES and constructs the SECOND reaction.
            (let [committed (subs/subscribe stable-query-v
                                            {:frame stable-frame-kw})]
              (set! (.-current committed-ref) #js [stable-key committed])
              (let [k (keyword watch-ns (str (gensym "watch-")))]
                (when committed
                  (add-watch committed k (fn [_ _ _ _] (on-change))))
                (fn unsubscribe []
                  (when committed (remove-watch committed k))
                  (let [stored (.-current committed-ref)]
                    (when (and stored (identical? (aget stored 1) committed))
                      (set! (.-current committed-ref) nil)))
                  (subs/unsubscribe stable-frame-kw stable-query-v)))))
          #js [stable-key])]
    (react/useSyncExternalStore subscribe-fn get-snap get-snap)))

;; ONE property differs from `xcript`: the render-phase acquire is NOT
;; balanced in-render. The +1 is held PROVISIONALLY; the commit-phase
;; `subscribe-fn` takes its own durable +1 (a cache HIT — the entry is
;; still live at ref-count 1) and then releases the provisional one,
;; 2 -> 1, never crossing the disposal edge. One construction per read,
;; the same four cache-API calls per read as `xcript`, everything else
;; character-for-character identical.
;;
;; This is the hook-scoped provisional hand-off SHAPE, as a measurement
;; arm. It is deliberately not safe to ship: a render abandoned before
;; commit strands the provisional +1 (nothing ever balances it), which is
;; exactly the reaping question the rf2-2rtt6.14 ruling owns. The
;; instrument's residue gate (`lane/residue` back to baseline after every
;; round) is what proves the arm balanced in THIS plan, where every
;; render commits.

(defn- use-sub-handoff [frame-kw query-v]
  (let [key-ref       (react/useRef nil)
        committed-ref (react/useRef nil)
        stable-key      (stable-key-of key-ref frame-kw query-v)
        stable-frame-kw (aget stable-key 0)
        stable-query-v  (aget stable-key 1)
        reaction
        (uix-hooks/use-memo
          (fn [] (subs/subscribe stable-query-v {:frame stable-frame-kw}))
          #js [stable-key])
        get-snap
        (uix-hooks/use-callback
          (fn []
            (let [stored (.-current committed-ref)
                  r      (if (and stored
                                  (identical? (aget stored 0) stable-key))
                           (aget stored 1)
                           reaction)]
              (when r @r)))
          #js [stable-key reaction])
        subscribe-fn
        (uix-hooks/use-callback
          (fn [on-change]
            ;; Live entry at ref-count 1, so this HITS: `committed` is
            ;; `identical?` the render-phase handle. Adopt, then release
            ;; the provisional +1 (2 -> 1).
            (let [committed (subs/subscribe stable-query-v
                                            {:frame stable-frame-kw})]
              (set! (.-current committed-ref) #js [stable-key committed])
              (subs/unsubscribe stable-frame-kw stable-query-v)
              (let [k (keyword watch-ns (str (gensym "watch-")))]
                (when committed
                  (add-watch committed k (fn [_ _ _ _] (on-change))))
                (fn unsubscribe []
                  (when committed (remove-watch committed k))
                  (let [stored (.-current committed-ref)]
                    (when (and stored (identical? (aget stored 1) committed))
                      (set! (.-current committed-ref) nil)))
                  (subs/unsubscribe stable-frame-kw stable-query-v)))))
          #js [stable-key])]
    (react/useSyncExternalStore subscribe-fn get-snap get-snap)))

;; ---------------------------------------------------------------------------
;; The timed M1 cells — 3 variants x 3 layers, unrolled
;; ---------------------------------------------------------------------------
;;
;; Unrolled per variant per layer for the ladder's reason: these are hooks,
;; the call sequence must be identical on every render by construction,
;; and a reader pricing hook calls should be able to count them. Markup is
;; `p0_uix_views/m1-cell`'s, byte for byte; the parity gate checks it.
;;
;; The SHIPPED arms read through the ambient 1-arg `use-subscribe` under
;; `frame-provider` — exactly the converged M1 arm, because they carry the
;; red-zone denominator role. The transcriptions pin the frame explicitly,
;; as `use-subscribe-2` (which the ambient form resolves into) does; the
;; per-boundary difference is one context read, far below the clock
;; quantum, and the fidelity control adjudicates it rather than this
;; comment.

(defui s-m1-l1 [{:keys [i]}]
  (let [cell (uixa/use-subscribe [:p0/cell i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui s-m1-l2 [{:keys [i]}]
  (let [cell (uixa/use-subscribe [:cm/l2 i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui s-m1-l3 [{:keys [i]}]
  (let [cell (uixa/use-subscribe [:cm/l3 i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui x-m1-l1 [{:keys [i]}]
  (let [cell (use-sub-xcript v/subs-frame [:p0/cell i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui x-m1-l2 [{:keys [i]}]
  (let [cell (use-sub-xcript v/subs-frame [:cm/l2 i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui x-m1-l3 [{:keys [i]}]
  (let [cell (use-sub-xcript v/subs-frame [:cm/l3 i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui h-m1-l1 [{:keys [i]}]
  (let [cell (use-sub-handoff v/subs-frame [:p0/cell i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui h-m1-l2 [{:keys [i]}]
  (let [cell (use-sub-handoff v/subs-frame [:cm/l2 i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

(defui h-m1-l3 [{:keys [i]}]
  (let [cell (use-sub-handoff v/subs-frame [:cm/l3 i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str cell)))))

;; ---------------------------------------------------------------------------
;; The timed M2 fields — 3 variants x 2 layers
;; ---------------------------------------------------------------------------

(defui s-m2-l1 [{:keys [i]}]
  (let [cell (uixa/use-subscribe [:p0/cell i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (v/field-value i cell)
                      :read-only true})
       ($ :p.err (v/field-error i)))))

(defui s-m2-l2 [{:keys [i]}]
  (let [cell (uixa/use-subscribe [:cm/l2 i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (v/field-value i cell)
                      :read-only true})
       ($ :p.err (v/field-error i)))))

(defui x-m2-l1 [{:keys [i]}]
  (let [cell (use-sub-xcript v/subs-frame [:p0/cell i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (v/field-value i cell)
                      :read-only true})
       ($ :p.err (v/field-error i)))))

(defui x-m2-l2 [{:keys [i]}]
  (let [cell (use-sub-xcript v/subs-frame [:cm/l2 i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (v/field-value i cell)
                      :read-only true})
       ($ :p.err (v/field-error i)))))

(defui h-m2-l1 [{:keys [i]}]
  (let [cell (use-sub-handoff v/subs-frame [:p0/cell i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (v/field-value i cell)
                      :read-only true})
       ($ :p.err (v/field-error i)))))

(defui h-m2-l2 [{:keys [i]}]
  (let [cell (use-sub-handoff v/subs-frame [:cm/l2 i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (v/field-value i cell)
                      :read-only true})
       ($ :p.err (v/field-error i)))))

;; ---------------------------------------------------------------------------
;; The UIx grids and root
;; ---------------------------------------------------------------------------

(defui m1-grid [{:keys [cell n]}]
  ($ :ul.grid {:role "list"}
     (for [i (range n)]
       ($ cell {:key i :i i}))))

(defui m2-grid [{:keys [cell n]}]
  ($ :form.p0form
     ($ :fieldset.fields
        (for [i (range n)]
          ($ cell {:key i :i i})))
     ($ :button.submit {:type "submit" :disabled true} "Submit")))

(defn uix-root
  "One root for every UIx arm: `frame-provider` scopes the segment's frame
  (the ambient shipped arms resolve through it; the pinned transcriptions
  ignore it) and renders no DOM of its own, so it cannot move the parity
  gate."
  [grid cell n]
  ($ uixa/frame-provider {:frame v/subs-frame}
     ($ grid {:cell cell :n n})))

(def uix-m1-cells
  {:uix-subs    {1 s-m1-l1 2 s-m1-l2 3 s-m1-l3}
   :uix-xcript  {1 x-m1-l1 2 x-m1-l2 3 x-m1-l3}
   :uix-handoff {1 h-m1-l1 2 h-m1-l2 3 h-m1-l3}})

(def uix-m2-cells
  {:uix-subs    {1 s-m2-l1 2 s-m2-l2}
   :uix-xcript  {1 x-m2-l1 2 x-m2-l2}
   :uix-handoff {1 h-m2-l1 2 h-m2-l2}})

;; ---------------------------------------------------------------------------
;; The Reagent layer-2/3 counterparts
;; ---------------------------------------------------------------------------
;;
;; Layer 1 is `p0_reagent_views`' own `m1-subs` / `m2-subs`, untouched.
;; These are the same markup over the layered queries.

(reg-view ^{:rf/id :cm/m1-cell-l2} rg-m1-cell-l2
  [i]
  (let [cell @(rf/subscribe [:cm/l2 i])]
    [:li.row
     [:span.lbl "cell "]
     [:span.cell {:data-i i} (str cell)]]))

(reg-view ^{:rf/id :cm/m1-l2} rg-m1-l2
  [n]
  [:ul.grid {:role "list"}
   (for [i (range n)]
     ^{:key i} [rg-m1-cell-l2 i])])

(reg-view ^{:rf/id :cm/m1-cell-l3} rg-m1-cell-l3
  [i]
  (let [cell @(rf/subscribe [:cm/l3 i])]
    [:li.row
     [:span.lbl "cell "]
     [:span.cell {:data-i i} (str cell)]]))

(reg-view ^{:rf/id :cm/m1-l3} rg-m1-l3
  [n]
  [:ul.grid {:role "list"}
   (for [i (range n)]
     ^{:key i} [rg-m1-cell-l3 i])])

(reg-view ^{:rf/id :cm/m2-field-l2} rg-m2-field-l2
  [i]
  (let [cell @(rf/subscribe [:cm/l2 i])]
    [:div.field
     [:label.lbl {:for (str "f" i)} (str "Field " i)]
     [:input.inp {:id        (str "f" i)
                  :name      (str "f" i)
                  :type      "text"
                  :value     (v/field-value i cell)
                  :read-only true}]
     [:p.err (v/field-error i)]]))

(reg-view ^{:rf/id :cm/m2-l2} rg-m2-l2
  [fields]
  [:form.p0form
   [:fieldset.fields
    (for [i (range fields)]
      ^{:key i} [rg-m2-field-l2 i])]
   [:button.submit {:type "submit" :disabled true} "Submit"]])

(def reagent-m1-views {1 v/m1-subs 2 rg-m1-l2 3 rg-m1-l3})
(def reagent-m2-views {1 v/m2-subs 2 rg-m2-l2})

;; ---------------------------------------------------------------------------
;; THE COUNTING WITNESS — the same claim, settled exactly
;; ---------------------------------------------------------------------------
;;
;; Counter-instrumented copies of BOTH transcriptions (the counters are
;; the only additions), read against the counter-instrumented `:cm/w*`
;; chain, mounted OUTSIDE every timed window over disjoint cell ranges,
;; and adjudicated against the predictions in the namespace docstring.
;; A `rebuilt` of 0 on `xcript` or of N on `handoff` falsifies the
;; ablation outright, and the run refuses to publish.

(defn- use-sub-witness-xcript [frame-kw query-v]
  (let [key-ref       (react/useRef nil)
        committed-ref (react/useRef nil)
        stable-key      (stable-key-of key-ref frame-kw query-v)
        stable-frame-kw (aget stable-key 0)
        stable-query-v  (aget stable-key 1)
        reaction
        (uix-hooks/use-memo
          (fn []
            (let [r (subs/subscribe stable-query-v {:frame stable-frame-kw})]
              (subs/unsubscribe stable-frame-kw stable-query-v)
              r))
          #js [stable-key])
        get-snap
        (uix-hooks/use-callback
          (fn []
            (let [stored (.-current committed-ref)
                  r      (if (and stored
                                  (identical? (aget stored 0) stable-key))
                           (aget stored 1)
                           reaction)]
              (when r @r)))
          #js [stable-key reaction])
        subscribe-fn
        (uix-hooks/use-callback
          (fn [on-change]
            (let [committed (subs/subscribe stable-query-v
                                            {:frame stable-frame-kw})]
              (aset witness-counters "commits"
                    (inc (aget witness-counters "commits")))
              ;; `reaction` is the render-phase handle the fiber holds;
              ;; `committed` is what the commit-phase acquire returned.
              ;; Not `identical?` means the render-phase one was disposed
              ;; and a SECOND was constructed between the two calls.
              (when-not (identical? committed reaction)
                (aset witness-counters "rebuilt"
                      (inc (aget witness-counters "rebuilt"))))
              (set! (.-current committed-ref) #js [stable-key committed])
              (let [k (keyword watch-ns (str (gensym "watch-")))]
                (when committed
                  (add-watch committed k (fn [_ _ _ _] (on-change))))
                (fn unsubscribe []
                  (when committed (remove-watch committed k))
                  (let [stored (.-current committed-ref)]
                    (when (and stored (identical? (aget stored 1) committed))
                      (set! (.-current committed-ref) nil)))
                  (subs/unsubscribe stable-frame-kw stable-query-v)))))
          #js [stable-key reaction])]
    (react/useSyncExternalStore subscribe-fn get-snap get-snap)))

(defn- use-sub-witness-handoff [frame-kw query-v]
  (let [key-ref       (react/useRef nil)
        committed-ref (react/useRef nil)
        stable-key      (stable-key-of key-ref frame-kw query-v)
        stable-frame-kw (aget stable-key 0)
        stable-query-v  (aget stable-key 1)
        reaction
        (uix-hooks/use-memo
          (fn [] (subs/subscribe stable-query-v {:frame stable-frame-kw}))
          #js [stable-key])
        get-snap
        (uix-hooks/use-callback
          (fn []
            (let [stored (.-current committed-ref)
                  r      (if (and stored
                                  (identical? (aget stored 0) stable-key))
                           (aget stored 1)
                           reaction)]
              (when r @r)))
          #js [stable-key reaction])
        subscribe-fn
        (uix-hooks/use-callback
          (fn [on-change]
            (let [committed (subs/subscribe stable-query-v
                                            {:frame stable-frame-kw})]
              (aset witness-counters "commits"
                    (inc (aget witness-counters "commits")))
              (when-not (identical? committed reaction)
                (aset witness-counters "rebuilt"
                      (inc (aget witness-counters "rebuilt"))))
              (set! (.-current committed-ref) #js [stable-key committed])
              (subs/unsubscribe stable-frame-kw stable-query-v)
              (let [k (keyword watch-ns (str (gensym "watch-")))]
                (when committed
                  (add-watch committed k (fn [_ _ _ _] (on-change))))
                (fn unsubscribe []
                  (when committed (remove-watch committed k))
                  (let [stored (.-current committed-ref)]
                    (when (and stored (identical? (aget stored 1) committed))
                      (set! (.-current committed-ref) nil)))
                  (subs/unsubscribe stable-frame-kw stable-query-v)))))
          #js [stable-key reaction])]
    (react/useSyncExternalStore subscribe-fn get-snap get-snap)))

;; -- witness cells: 3 reads each, per variant per layer, unrolled -----------

(defn- wq [layer i] [(case layer 1 :cm/w1 2 :cm/w2 3 :cm/w3) i])

(defui wx-cell-1 [{:keys [base]}]
  (let [a (use-sub-witness-xcript v/subs-frame (wq 1 (+ base 0)))
        b (use-sub-witness-xcript v/subs-frame (wq 1 (+ base 1)))
        c (use-sub-witness-xcript v/subs-frame (wq 1 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui wx-cell-2 [{:keys [base]}]
  (let [a (use-sub-witness-xcript v/subs-frame (wq 2 (+ base 0)))
        b (use-sub-witness-xcript v/subs-frame (wq 2 (+ base 1)))
        c (use-sub-witness-xcript v/subs-frame (wq 2 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui wx-cell-3 [{:keys [base]}]
  (let [a (use-sub-witness-xcript v/subs-frame (wq 3 (+ base 0)))
        b (use-sub-witness-xcript v/subs-frame (wq 3 (+ base 1)))
        c (use-sub-witness-xcript v/subs-frame (wq 3 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui wh-cell-1 [{:keys [base]}]
  (let [a (use-sub-witness-handoff v/subs-frame (wq 1 (+ base 0)))
        b (use-sub-witness-handoff v/subs-frame (wq 1 (+ base 1)))
        c (use-sub-witness-handoff v/subs-frame (wq 1 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui wh-cell-2 [{:keys [base]}]
  (let [a (use-sub-witness-handoff v/subs-frame (wq 2 (+ base 0)))
        b (use-sub-witness-handoff v/subs-frame (wq 2 (+ base 1)))
        c (use-sub-witness-handoff v/subs-frame (wq 2 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui wh-cell-3 [{:keys [base]}]
  (let [a (use-sub-witness-handoff v/subs-frame (wq 3 (+ base 0)))
        b (use-sub-witness-handoff v/subs-frame (wq 3 (+ base 1)))
        c (use-sub-witness-handoff v/subs-frame (wq 3 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

;; -- the SHIPPED witness: the real hook, counted off observable state -------
;;
;; No transcription and no spy. The cell calls `re-frame.adapter.uix/
;; use-subscribe` — the 2-arity explicit-frame form, which is exactly what the
;; ambient form resolves into — and then reads the sub-cache to record which
;; reaction (if any) is tenanted for that query at the instant the render-phase
;; read returned. See the namespace docstring's "How the SHIPPED arm is counted
;; without a counter".

(def ^:private shipped-render-observed
  "query-v -> the reaction tenanted in the sub-cache when the render-phase read
  returned, or nil. FIRST observation per query wins: React may render a
  boundary more than once, and only the first render is the cold one."
  (atom {}))

(defn- sub-cache-atom [] (:sub-cache (frame/frame v/subs-frame)))

(defn- tenant-of [query-v]
  (get-in @(sub-cache-atom) [query-v :reaction]))

(defn- use-shipped-witness
  "The shipped hook, plus one render-phase observation. Reading an atom during
  render is a bench affordance, not a pattern: it records, it does not decide."
  [query-v]
  (let [value (uixa/use-subscribe v/subs-frame query-v)]
    (swap! shipped-render-observed
           (fn [m] (if (contains? m query-v) m (assoc m query-v (tenant-of query-v)))))
    value))

(defui ws-cell-1 [{:keys [base]}]
  (let [a (use-shipped-witness (wq 1 (+ base 0)))
        b (use-shipped-witness (wq 1 (+ base 1)))
        c (use-shipped-witness (wq 1 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui ws-cell-2 [{:keys [base]}]
  (let [a (use-shipped-witness (wq 2 (+ base 0)))
        b (use-shipped-witness (wq 2 (+ base 1)))
        c (use-shipped-witness (wq 2 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defui ws-cell-3 [{:keys [base]}]
  (let [a (use-shipped-witness (wq 3 (+ base 0)))
        b (use-shipped-witness (wq 3 (+ base 1)))
        c (use-shipped-witness (wq 3 (+ base 2)))]
    ($ :span.wcell {:data-i base} (str (+ a b c)))))

(defn- shipped-witness-counts
  "`{:commits n :rebuilt n}` for the shipped arm, read off the sub-cache after
  the mount and before the unmount. `commits` = reads whose slot is tenanted;
  `rebuilt` = reads whose tenant is not the one the render observed."
  [layer n offset]
  (let [queries (for [i (range n) k (range 3)] (wq layer (+ offset (* i 3) k)))
        observed @shipped-render-observed]
    (reduce (fn [acc q]
              (let [now (tenant-of q)]
                (-> acc
                    (update :commits (if (some? now) inc identity))
                    (update :rebuilt (if (identical? now (get observed q)) identity inc)))))
            {:commits 0 :rebuilt 0}
            queries)))

(def ^:private uix-witness-cells
  {:xcript  {1 wx-cell-1 2 wx-cell-2 3 wx-cell-3}
   :handoff {1 wh-cell-1 2 wh-cell-2 3 wh-cell-3}
   :shipped {1 ws-cell-1 2 ws-cell-2 3 ws-cell-3}})

(defui w-grid [{:keys [cell n offset]}]
  ($ :div.wab
     (for [i (range n)]
       ($ cell {:key i :base (+ offset (* i 3))}))))

;; -- the Reagent witness ----------------------------------------------------

(defn- w-rg-cell [layer base]
  (let [total (loop [k 0 acc 0]
                (if (< k 3)
                  (recur (inc k)
                         (+ acc @(rf/subscribe (wq layer (+ base k))
                                               {:frame v/subs-frame})))
                  acc))]
    [:span.wcell {:data-i base} (str total)]))

(defn- w-rg-grid [layer n offset]
  (into [:div.wab]
        (map (fn [i] ^{:key i} [w-rg-cell layer (+ offset (* i 3))]))
        (range n)))

(defn witness!
  "Mount `n` witness boundaries of 3 reads each — `variant` in
  `#{:xcript :handoff :shipped :reagent}` at `layer` — read the counters
  back, unmount, and answer them beside the read count N they are
  adjudicated against. Fresh counters per call; a fresh subtree of cells
  per call (`offset`) so no call can be served by another's cache entries;
  never inside a timed window. The caller adjudicates.

  `:shipped` mounts the real hook and derives `commits` / `rebuilt` from
  the sub-cache rather than from a counter — same meanings, no seam cut
  into production code (namespace docstring).

  SCHEDULE, STAMPED HERE BECAUSE THIS IS WHERE THE NUMBERS ARE MADE
  (rf2-2rtt6.25, audit of #7326): the mount and the unmount below are
  bracketed by `react-dom/flushSync`, which forces React's passive
  `useSyncExternalStore` subscribe before control returns. The `:shipped`
  arm therefore reports the hand-off RUNNING TO COMPLETION. On the public
  `createRoot().render()` path the `setTimeout 0` reaper fires first and
  the same hook reports the `:xcript` row. The `flushSync` is NOT here to
  flatter the hand-off — it is what makes a counted mount a single
  bounded window at all, and it predates the hand-off — so it stays; what
  it costs is the right to read these rows as consumer-mount performance."
  [variant layer n offset]
  (reset-witness-counters!)
  (reset! shipped-render-observed {})
  (let [container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (case variant
                    :reagent
                    (let [rt (rdc/create-root container)]
                      (react-dom/flushSync
                        (fn [] (rdc/render rt [w-rg-grid layer n offset])))
                      rt)
                    ;; :xcript / :handoff / :shipped
                    (let [rt (uix-dom/create-root container)]
                      (react-dom/flushSync
                        (fn []
                          (uix-dom/render-root
                            ($ w-grid {:cell (get-in uix-witness-cells [variant layer])
                                       :n n :offset offset})
                            rt)))
                      rt))
        elements  (.-length (.querySelectorAll container ".wcell"))
        counts    (if (= :shipped variant)
                    (shipped-witness-counts layer n offset)
                    {:commits (aget witness-counters "commits")
                     :rebuilt (aget witness-counters "rebuilt")})
        out       {:variant  variant
                   :layer    layer
                   :offset   offset
                   :reads    (* n 3)
                   :elements elements
                   :expected n
                   :ok?      (= elements n)
                   :commits  (:commits counts)
                   :rebuilt  (:rebuilt counts)
                   :body1    (aget witness-counters "body1")
                   :body2    (aget witness-counters "body2")
                   :body3    (aget witness-counters "body3")}]
    (react-dom/flushSync
      (fn [] (case variant
               :reagent (rdc/unmount root)
               (uix-dom/unmount-root root))))
    (.remove container)
    out))
