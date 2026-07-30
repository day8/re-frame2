(ns re-frame.freehand.bench.spine-ablation
  "THE UIx SPINE PER-READ ABLATION — what the 3,550 bytes a UIx
  subscription read costs are actually made of.

  Wave-0 instrument for EP-0038 (bead `rf2-2rtt6.12`, epic `rf2-2rtt6`).
  Reports into `docs/design/hicasso/studio/uix-spine-per-read-decomposition.md`.

  ## The question

  `rf2-2rtt6.5`'s reads-per-boundary ladder priced a re-frame2
  subscription read at **3,550 B** on the UIx spine against **942 B** on
  Reagent — 3.77x — and its reader C attributed the whole difference to
  object COUNT: **128.5 objects per read against 36.2**, at ~27 B per
  object on both arms. One `useSyncExternalStore`, two `useRef`s, one
  `useMemo` and two `useCallback`s do not come to 128 objects, so the
  spine is holding something the hook stack does not account for.

  Reading `re-frame.substrate.spine/use-subscribe` says what. The render
  phase takes a BALANCED round trip — `subs/subscribe` immediately
  followed by `subs/unsubscribe` — so that a render which never commits
  retains no ref-count (rf2-es09qq). For a query with no prior cache
  entry that round trip is `0 -> 1 -> 0`, and 1 -> 0 is the DISPOSAL
  edge: the reaction it just built is disposed and its cache slot
  evicted (`re-frame.subs.cache/unsubscribe!`, no grace period). The
  commit-phase `subscribe-fn` then misses the cache again and builds a
  SECOND reaction. Two reactions are built per read — and the first one
  does not go away, because `use-memo` returns it into the fiber's hook
  slot and the `get-snap` callback closes over it as its pre-commit
  fallback. A disposed, unreachable-by-any-verb reaction is retained for
  the lifetime of the component.

  That is a hypothesis about retained bytes, so it is settled by a
  retention measurement and not by reading.

  ## The ablation

  Four UIx arms, differing by ONE term each, all reading the same
  `[:abl/cell i]` subs in the same frame at 1,200 boundaries:

  | arm        | what it is                                                  |
  |------------|-------------------------------------------------------------|
  | `uix`      | the SHIPPED `re-frame.adapter.uix/use-subscribe`             |
  | `xcript`   | a transcription of the shipped hook, term for term           |
  | `noretain` | the transcription with the render-phase reaction NOT held    |
  | `hooks`    | the same six hooks over a trivial JS store — no re-frame     |

  `xcript` is the FIDELITY CONTROL and it is the reason the other two
  mean anything: an ablation is only attributable if the unablated
  transcription reproduces the shipped hook. If `xcript` does not land on
  `uix` within the round-to-round range, the transcription is wrong and
  every delta taken from it is void. The report says so in as many words
  and the driver refuses to call the decomposition attributable.

  The differences then read off directly:

      uix - noretain   the retained render-phase reaction
      noretain - hooks one live subscription as the spine pays for it
      hooks  - floor   React's own six-hook stack, per read
      reagent          the subscription half BOTH substrates pay

  `noretain` differs from `xcript` in exactly one expression: its
  render-phase `use-memo` derefs the reaction and returns the VALUE
  rather than the reaction itself, and `get-snap`'s pre-commit fallback
  reads that value. Every `subs/subscribe` / `subs/unsubscribe` call, every
  hook, every deps array and every watch is otherwise identical — so the
  build/dispose/rebuild CHURN is still paid on both sides and the delta
  isolates RETENTION alone. `noretain` is a MEASUREMENT ARM, not a
  proposed patch: it drops the live-reread property of the current
  fallback (see the studio page).

  ## What this namespace does NOT do

  It does not decide when a heap reading is taken. The page mounts and
  holds; the driver (`spine_ablation_run.cjs`) owns the collector, all
  three readers and the in-situ control. Nothing here counts allocations
  — V8's CDP *sampling* heap profiler drops the samples of collected
  objects, and that fault has already produced one wrong table on this
  surface.

  Every mount is verified at the DOM: [[mount!]] counts the boundary
  elements the arm should have produced and answers the count beside the
  expectation, because an arm that silently rendered nothing would
  otherwise read as the cheapest variant in the table.

  ## Where this file lives, and why

  Beside B7 and `reads-ladder`, in the bench lane, because it RIDES their
  collector design and because `freehand/test` is the only
  `:advanced`-capable browser source path on the classpath until the
  Hicasso lane (`rf2-2rtt6.2`, PR #7259) merges. It requires nothing from
  `re-frame.freehand`. When the Hicasso lane exists this file moves there
  unchanged.

  Normative owner: bead `rf2-2rtt6.12`; programme
  `docs/design/hicasso/charter.md`."
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [reagent.dom.client :as rdc]
            [uix.core :refer [$ defui]]
            [uix.dom :as uix-dom]
            [uix.hooks.alpha :as uix-hooks]))

;; ---------------------------------------------------------------------------
;; The ladder's shape
;; ---------------------------------------------------------------------------

(def reads-rungs
  "Four rungs, so a straight line through them is a claim that can fail.
  `0` is the boundary shell — a component per boundary that reads nothing
  — and is the intercept anchor, never a source of reactive inference.
  20 is dropped relative to `rf2-2rtt6.5`'s ladder: this instrument runs
  FOUR variants where that one ran two, and the slope over 0/1/3/7 is
  already over-determined."
  [0 1 3 7])

(def boundaries
  "Fixed, because this instrument regresses against READS only. 1,200 is
  `rf2-2rtt6.5`'s Curve B count, so the `uix` arm here is directly
  comparable with the 3,550 B/read that page published."
  1200)

(def frame-id
  "ONE frame behind every boundary of every arm."
  :abl/frame)

(def ^:private cells-needed
  "Every boundary reads DISTINCT cells, so no two boundaries share a
  reaction and the per-read figure carries the reaction as well as the
  hook. Matches the ladder, and it is the shape that makes the
  double-build visible: a distinct query is a cache MISS on the
  render-phase round trip AND again on the commit-phase acquire."
  (* boundaries (apply max reads-rungs)))

;; ---------------------------------------------------------------------------
;; The witness counters
;; ---------------------------------------------------------------------------

(def ^:private witness
  "Plain counters for the WITNESS probe. Declared here because the witness
  sub body (below) increments one of them.

    bodyRuns  — sub-body invocations
    commits   — commit-phase `subscribe-fn` calls, i.e. reads that mounted
    rebuilt   — commit-phase acquisitions whose reaction is NOT `identical?`
                to the render-phase handle the same read is holding"
  (js-obj "bodyRuns" 0 "commits" 0 "rebuilt" 0))

;; ---------------------------------------------------------------------------
;; The store
;; ---------------------------------------------------------------------------

;; Every cell is ZERO, for the reason the ladder gives: a boundary renders
;; the sum of its reads, so with zeros every arm and every floor renders the
;; identical one-character text node "0" at every rung, and no arm carries
;; per-boundary string data the floor does not. The reads are still live —
;; `use-subscribe` and `subs/subscribe` are calls with registrar side effects
;; and their results reach the DOM — so nothing here is elidable under
;; `:advanced`.
(rf/reg-event :abl/seed
  (fn [_ctx [_ n]] {:db {:cells (vec (repeat n 0))}}))

(rf/reg-sub :abl/cell
  (fn [db [_ i]] (nth (:cells db) i)))

;; ---- the witness sub (see the WITNESS section at the foot of this file) ----
;;
;; Identical body to `:abl/cell`, plus a counter. It exists so the
;; double-registration claim can be settled by COUNTING rather than by
;; weighing: a count is immune to every fault a heap instrument has, and
;; fifteen instrument faults have been caught on these surfaces. It is
;; never read by an arm in the heap table — a sub body with a side effect
;; has no business on a page that is pricing retention.
(rf/reg-sub :abl/witness-cell
  (fn [db [_ i]]
    (aset witness "bodyRuns" (inc (aget witness "bodyRuns")))
    (nth (:cells db) i)))

(defn ensure-frame! []
  (rf/make-frame {:id frame-id :initial-events [[:abl/seed cells-needed]]}))

;; ---------------------------------------------------------------------------
;; The floor — plain React, no component per boundary, no reactivity
;; ---------------------------------------------------------------------------

(defn- el [tag props children] (apply react/createElement tag props children))

(defn floor-grid
  "`n` boundary elements and the container, and NOTHING else. `arm - floor`
  is therefore the variant's own standing cost and not a figure dominated
  by React fibers and DOM handles every arm pays for. Same shape as the
  ladder's floor, so the R=0 rungs here are comparable with its."
  [n]
  (el "div" #js {:className "abl"}
      (into-array
        (map (fn [i] (el "span" #js {:key i :className "cell" :data-i i} "0"))
             (range n)))))

;; ---------------------------------------------------------------------------
;; ARM 2 — `xcript`: the shipped hook, transcribed term for term
;; ---------------------------------------------------------------------------
;;
;; This is `re-frame.substrate.spine`'s `use-subscribe-2` body, copied. The
;; ONLY differences are cosmetic: the hook fns are named directly
;; (`uix.hooks.alpha/use-memo` — which is exactly what the UIx adapter passes
;; into `make-react-spine`) rather than reached through the factory's closure,
;; and the watch-key namespace string is spelled here instead of derived from
;; `:gensym-prefix-use-sub`. It is a copy on purpose: an ablation that does not
;; start from a faithful copy prices something else and says nothing about the
;; shipped adapter. The `xcript` vs `uix` comparison in the report is what
;; makes that claim falsifiable rather than asserted.

(def ^:private watch-ns "rf-uix-use-sub")

(defn- stable-key-of
  "The `useRef` + `=` memo-by-value from the spine (rf2-mwft2), shared by
  all three transcribed arms so the stable-key term is identical across
  them and cancels in every difference."
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
        ;; Balanced, net-zero render-phase fetch of the reaction HANDLE
        ;; (rf2-es09qq). For a query with no live cache entry this is
        ;; 0 -> 1 -> 0: build, then dispose + evict. The handle is
        ;; returned, so React's hook slot keeps it.
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

;; ---------------------------------------------------------------------------
;; ARM 3 — `noretain`: the transcription, minus the retained reaction
;; ---------------------------------------------------------------------------
;;
;; ONE expression differs from `xcript`. The render-phase `use-memo` still
;; performs the identical balanced round trip — same `subs/subscribe`, same
;; `subs/unsubscribe`, same build + dispose + commit-phase rebuild — but it
;; DEREFS the handle and returns the VALUE, so nothing holds the disposed
;; reaction once the factory returns. `get-snap`'s pre-commit fallback reads
;; that value.
;;
;; Everything else — both refs, the stable key, both `use-callback`s, the
;; watch, `useSyncExternalStore` — is character-for-character `xcript`. The
;; CHURN is therefore on both sides of the subtraction and only RETENTION
;; is isolated.
;;
;; This is a measurement arm and not a proposed patch. Freezing the fallback
;; snapshot gives up one property the live handle has: today's fallback
;; `@r` on the disposed reaction still recomputes (the derived value is
;; pull-based), so an app-db write landing between render and the
;; commit-phase watch install is observed by React's store-consistency
;; check. A frozen value is not. The studio page prices the fix that keeps
;; that property; this arm exists to price the term, not to ship it.

(defn- use-sub-noretain [frame-kw query-v]
  (let [key-ref       (react/useRef nil)
        committed-ref (react/useRef nil)
        stable-key      (stable-key-of key-ref frame-kw query-v)
        stable-frame-kw (aget stable-key 0)
        stable-query-v  (aget stable-key 1)
        snapshot
        (uix-hooks/use-memo
          (fn []
            (let [r (subs/subscribe stable-query-v {:frame stable-frame-kw})
                  v (when r @r)]
              (subs/unsubscribe stable-frame-kw stable-query-v)
              v))
          #js [stable-key])
        get-snap
        (uix-hooks/use-callback
          (fn []
            (let [stored (.-current committed-ref)]
              (if (and stored (identical? (aget stored 0) stable-key))
                (let [r (aget stored 1)] (when r @r))
                snapshot)))
          #js [stable-key snapshot])
        subscribe-fn
        (uix-hooks/use-callback
          (fn [on-change]
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

;; ---------------------------------------------------------------------------
;; ARM 4 — `hooks`: the same six hooks, no re-frame at all
;; ---------------------------------------------------------------------------
;;
;; Two `useRef`s, the stable-key derivation (INCLUDING the `[:abl/cell i]`
;; query vector, so the query-v allocation cancels in `noretain - hooks`
;; and that difference is the subscription machinery alone), one `useMemo`,
;; two `useCallback`s and `useSyncExternalStore` over a trivial JS store.
;; No `subs/subscribe`, no reaction, no cache entry.
;;
;; The store is a shared `js/Set` of listeners and never notifies, which is
;; the same standing shape the real arms have on this page: every cell is
;; zero, so no subscriber is ever called there either.

(def ^:private hooks-listeners (js/Set.))

(defn- use-sub-hooks [frame-kw query-v]
  (let [key-ref       (react/useRef nil)
        committed-ref (react/useRef nil)
        stable-key (stable-key-of key-ref frame-kw query-v)
        handle     (uix-hooks/use-memo (fn [] hooks-listeners) #js [stable-key])
        get-snap   (uix-hooks/use-callback
                     (fn [] (if (and handle stable-key) 0 0))
                     #js [stable-key handle])
        subscribe-fn
        (uix-hooks/use-callback
          (fn [on-change]
            (set! (.-current committed-ref) #js [stable-key on-change])
            (.add hooks-listeners on-change)
            (fn unsubscribe []
              (.delete hooks-listeners on-change)
              (let [stored (.-current committed-ref)]
                (when (and stored (identical? (aget stored 1) on-change))
                  (set! (.-current committed-ref) nil)))))
          #js [stable-key])]
    (react/useSyncExternalStore subscribe-fn get-snap get-snap)))

;; ---------------------------------------------------------------------------
;; The read shims, one per variant
;; ---------------------------------------------------------------------------

(defn- r-uix      [i] (uix-adapter/use-subscribe frame-id [:abl/cell i]))
(defn- r-xcript   [i] (use-sub-xcript   frame-id [:abl/cell i]))
(defn- r-noretain [i] (use-sub-noretain frame-id [:abl/cell i]))
(defn- r-hooks    [i] (use-sub-hooks    frame-id [:abl/cell i]))

;; ---------------------------------------------------------------------------
;; The components — UNROLLED, per variant, per rung
;; ---------------------------------------------------------------------------
;;
;; UNROLLED for the ladder's reason: these are hooks, React's rule is that
;; the call sequence is identical on every render of an instance, and a loop
;; would satisfy that in fact but not by construction. Writing the calls out
;; is also the honest thing for a page pricing hook calls — the reader can
;; count them. Four variants x four rungs is sixteen components and no
;; abstraction over them, deliberately: an abstraction that routed the hook
;; through a prop would be the one thing a reader could not verify by eye.

(defui c-uix-0 [{:keys [base]}] ($ :span.cell {:data-i base} "0"))

(defui c-uix-1 [{:keys [base]}]
  (let [v (r-uix (+ base 0))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-uix-3 [{:keys [base]}]
  (let [v (+ (r-uix (+ base 0)) (r-uix (+ base 1)) (r-uix (+ base 2)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-uix-7 [{:keys [base]}]
  (let [v (+ (r-uix (+ base 0)) (r-uix (+ base 1)) (r-uix (+ base 2))
             (r-uix (+ base 3)) (r-uix (+ base 4)) (r-uix (+ base 5))
             (r-uix (+ base 6)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-xcript-0 [{:keys [base]}] ($ :span.cell {:data-i base} "0"))

(defui c-xcript-1 [{:keys [base]}]
  (let [v (r-xcript (+ base 0))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-xcript-3 [{:keys [base]}]
  (let [v (+ (r-xcript (+ base 0)) (r-xcript (+ base 1)) (r-xcript (+ base 2)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-xcript-7 [{:keys [base]}]
  (let [v (+ (r-xcript (+ base 0)) (r-xcript (+ base 1)) (r-xcript (+ base 2))
             (r-xcript (+ base 3)) (r-xcript (+ base 4)) (r-xcript (+ base 5))
             (r-xcript (+ base 6)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-noretain-0 [{:keys [base]}] ($ :span.cell {:data-i base} "0"))

(defui c-noretain-1 [{:keys [base]}]
  (let [v (r-noretain (+ base 0))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-noretain-3 [{:keys [base]}]
  (let [v (+ (r-noretain (+ base 0)) (r-noretain (+ base 1))
             (r-noretain (+ base 2)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-noretain-7 [{:keys [base]}]
  (let [v (+ (r-noretain (+ base 0)) (r-noretain (+ base 1))
             (r-noretain (+ base 2)) (r-noretain (+ base 3))
             (r-noretain (+ base 4)) (r-noretain (+ base 5))
             (r-noretain (+ base 6)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-hooks-0 [{:keys [base]}] ($ :span.cell {:data-i base} "0"))

(defui c-hooks-1 [{:keys [base]}]
  (let [v (r-hooks (+ base 0))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-hooks-3 [{:keys [base]}]
  (let [v (+ (r-hooks (+ base 0)) (r-hooks (+ base 1)) (r-hooks (+ base 2)))]
    ($ :span.cell {:data-i base} (str v))))

(defui c-hooks-7 [{:keys [base]}]
  (let [v (+ (r-hooks (+ base 0)) (r-hooks (+ base 1)) (r-hooks (+ base 2))
             (r-hooks (+ base 3)) (r-hooks (+ base 4)) (r-hooks (+ base 5))
             (r-hooks (+ base 6)))]
    ($ :span.cell {:data-i base} (str v))))

(def ^:private component-table
  {:uix      {0 c-uix-0      1 c-uix-1      3 c-uix-3      7 c-uix-7}
   :xcript   {0 c-xcript-0   1 c-xcript-1   3 c-xcript-3   7 c-xcript-7}
   :noretain {0 c-noretain-0 1 c-noretain-1 3 c-noretain-3 7 c-noretain-7}
   :hooks    {0 c-hooks-0    1 c-hooks-1    3 c-hooks-3    7 c-hooks-7}})

(defui uix-grid [{:keys [variant n r]}]
  ($ :div.abl
     (let [c (get-in component-table [(keyword variant) r])]
       (for [i (range n)]
         ($ c {:key i :base (* i r)})))))

;; ---------------------------------------------------------------------------
;; The Reagent arm — the shared-subscription floor
;; ---------------------------------------------------------------------------
;;
;; A loop rather than an unrolled body, because Reagent has no hook-order
;; rule: a form-1 component may deref a computed number of reactions and
;; Reagent's `deref`-capture learns all of them. The asymmetry with the UIx
;; components above is in the substrates, not in the measurement. Same shape
;; as `rf2-2rtt6.5`'s Reagent arm, so its 942 B/read is directly comparable.

(defn- rg-cell [base r]
  (let [v (loop [k 0 acc 0]
            (if (< k r)
              (recur (inc k)
                     (+ acc @(rf/subscribe [:abl/cell (+ base k)]
                                           {:frame frame-id})))
              acc))]
    [:span.cell {:data-i base} (str v)]))

(defn- rg-grid [n r]
  (into [:div.abl]
        (map (fn [i] ^{:key i} [rg-cell (* i r) r]))
        (range n)))

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- floor-arm [n]
  {:boundaries  n
   :selector    ".cell"
   :mount-one   (fn [c]
                  (let [rt (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render rt (floor-grid n))))
                    rt))
   :unmount-one (fn [rt] (react-dom/flushSync (fn [] (.unmount rt))))})

(defn- uix-arm [variant n r]
  {:boundaries  n
   :selector    ".cell"
   :mount-one   (fn [c]
                  (let [rt (uix-dom/create-root c)]
                    (react-dom/flushSync
                      (fn []
                        (uix-dom/render-root
                          ($ uix-grid {:variant (name variant) :n n :r r}) rt)))
                    rt))
   :unmount-one (fn [rt] (react-dom/flushSync (fn [] (uix-dom/unmount-root rt))))})

(defn- reagent-arm [n r]
  {:boundaries  n
   :selector    ".cell"
   :mount-one   (fn [c]
                  (let [rt (rdc/create-root c)]
                    (react-dom/flushSync (fn [] (rdc/render rt [rg-grid n r])))
                    rt))
   :unmount-one (fn [rt] (react-dom/flushSync (fn [] (rdc/unmount rt))))})

(def uix-variants
  "The four UIx-page arms, in decomposition order."
  [:uix :xcript :noretain :hooks])

(defn- arm-id [variant r n]
  (keyword (str (name variant) "/r" r "-b" n)))

(defn arms
  "The arm table for one page. `rf/init!` keeps the first adapter installed
  in a JS context, so the UIx variants and the Reagent baseline cannot share
  a page; each gets its own load and its own floor, and no subtraction ever
  crosses the page boundary."
  [substrate]
  (into {}
        (concat
          [[(arm-id :floor 0 boundaries) (floor-arm boundaries)]]
          (case substrate
            :uix (for [v uix-variants r reads-rungs]
                   [(arm-id v r boundaries) (uix-arm v boundaries r)])
            :reagent (for [r reads-rungs]
                       [(arm-id :reagent r boundaries)
                        (reagent-arm boundaries r)])))))

;; ---------------------------------------------------------------------------
;; Holding, and letting go
;; ---------------------------------------------------------------------------

(defonce ^:private installed (atom nil))
(defonce ^:private held (atom nil))

(defn- release!* []
  (when-some [{:keys [arm handle container]} @held]
    (let [{:keys [unmount-one]} (get @installed arm)]
      (try (unmount-one handle) (catch :default _ nil)))
    (.remove container)
    (reset! held nil))
  nil)

(defn mount!
  "Mount `arm-id` and KEEP it. Answers `{:elements :expected :ok
  :boundaries}` — the DOM read-back over the boundary class the arm should
  have produced.

  A literal `#js` object rather than `clj->js`: `clj->js` renders `:ok?` as
  the key `\"ok?\"` and a driver reading `verify.ok` sees `undefined`, so
  every mount reports unverified while every mount is in fact fine. That
  happened once already, on B7's first run."
  [arm-id]
  (release!*)
  (let [{:keys [mount-one selector boundaries]} (get @installed arm-id)
        container (container!)
        handle    (mount-one container)
        elements  (.-length (.querySelectorAll js/document selector))]
    (reset! held {:arm arm-id :handle handle :container container})
    #js {:elements   elements
         :expected   boundaries
         :boundaries boundaries
         :ok         (= elements boundaries)}))

;; ---------------------------------------------------------------------------
;; THE WITNESS — the same claim, settled by counting
;; ---------------------------------------------------------------------------
;;
;; Everything above is a heap measurement, and a heap measurement on this
;; surface is guilty until it has produced a control. The central claim —
;; that a UIx read with no live cache entry BUILDS TWO REACTIONS and keeps
;; the first — is not really a claim about bytes at all. It is a claim about
;; COUNTS, and counts can be read exactly.
;;
;; So: mount a small grid whose reads go through a transcription that is
;; `use-sub-xcript` PLUS three counter increments, and read the counters
;; back. The witness components never appear in the heap table (a sub body
;; with a side effect has no business on a page pricing retention) and the
;; heap arms never touch the witness sub. The two halves of this file check
;; each other and share nothing but the frame.
;;
;; PREDICTED BEFORE THE RUN, for `n` boundaries x `r` reads = N reads:
;;
;;   uix      commits N,  rebuilt N,  bodyRuns 2N
;;   reagent  commits 0,  rebuilt 0,  bodyRuns  N
;;
;; `rebuilt = N` is the whole finding: EVERY read's commit-phase acquire
;; hands back a DIFFERENT reaction object from the one the render phase
;; already built and the fiber is still holding. `bodyRuns = 2N` is its
;; shadow — the second reaction's first deref re-runs the user's sub body.
;; A `rebuilt` of 0 would falsify the finding outright.

(defn- use-sub-witness [frame-kw query-v]
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
              (aset witness "commits" (inc (aget witness "commits")))
              ;; THE WITNESS. `reaction` is the handle the render phase built
              ;; and the fiber is still holding; `committed` is what the
              ;; commit-phase acquire just returned. Not `identical?` means
              ;; the render-phase one was disposed and evicted between the
              ;; two calls and a second reaction was built to replace it.
              (when-not (identical? committed reaction)
                (aset witness "rebuilt" (inc (aget witness "rebuilt"))))
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

(defn- w [i] (use-sub-witness frame-id [:abl/witness-cell i]))

(defui w-cell [{:keys [base]}]
  (let [v (+ (w (+ base 0)) (w (+ base 1)) (w (+ base 2)))]
    ($ :span.wcell {:data-i base} (str v))))

(defui w-grid [{:keys [n offset]}]
  ($ :div.wab
     (for [i (range n)]
       ($ w-cell {:key i :base (+ offset (* i 3))}))))

(defn- w-rg-cell [base]
  (let [v (loop [k 0 acc 0]
            (if (< k 3)
              (recur (inc k)
                     (+ acc @(rf/subscribe [:abl/witness-cell (+ base k)]
                                           {:frame frame-id})))
              acc))]
    [:span.wcell {:data-i base} (str v)]))

(defn- w-rg-grid [n offset]
  (into [:div.wab]
        (map (fn [i] ^{:key i} [w-rg-cell (+ offset (* i 3))]))
        (range n)))

(defn witness!
  "Mount `n` witness boundaries of 3 reads each on `substrate`, read the
  counters, unmount, and answer the counts beside the read count they are
  to be compared against. Fresh counters per call, and a fresh subtree of
  cells per call (`offset`) so no call can be served by another's cache
  entries."
  [substrate n offset]
  (aset witness "bodyRuns" 0)
  (aset witness "commits" 0)
  (aset witness "rebuilt" 0)
  (let [c  (container!)
        rt (case (keyword substrate)
             :uix     (let [rt (uix-dom/create-root c)]
                        (react-dom/flushSync
                          (fn [] (uix-dom/render-root
                                   ($ w-grid {:n n :offset offset}) rt)))
                        rt)
             :reagent (let [rt (rdc/create-root c)]
                        (react-dom/flushSync
                          (fn [] (rdc/render rt [w-rg-grid n offset])))
                        rt))
        elements (.-length (.querySelectorAll js/document ".wcell"))
        out #js {:reads    (* n 3)
                 :offset   offset
                 :elements elements
                 :expected n
                 :ok       (= elements n)
                 :bodyRuns (aget witness "bodyRuns")
                 :commits  (aget witness "commits")
                 :rebuilt  (aget witness "rebuilt")}]
    (react-dom/flushSync
      (fn [] (case (keyword substrate)
               :uix     (uix-dom/unmount-root rt)
               :reagent (rdc/unmount rt))))
    (.remove c)
    out))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defonce ^:private control-cell (atom nil))

(defn control!
  "Hold a dense JS array of exactly `n` doubles and answer its length, so
  the allocation cannot be proved dead and elided.

  V8 stores a packed double array as `n` unboxed 8-byte slots behind a small
  header, so the retained cost is **8n bytes, PREDICTED** — a figure the
  instrument has to hit, not one it gets to report. It rides every round,
  in situ, on the same readers as the arms. Deliberately the same shape and
  the same 587,500 doubles `rf2-2rtt6.5` used, so the control error here is
  directly comparable with the +0.001% that page recorded."
  [n]
  (let [a (js/Array. n)]
    (dotimes [i n] (aset a i (+ i 0.5)))
    (reset! control-cell a)
    (.-length a)))

(defn control-release! [] (reset! control-cell nil) 0)

;; ---------------------------------------------------------------------------
;; The page-side door
;; ---------------------------------------------------------------------------

(defn install!
  "Publish the instrument on `window.ABL` for the CDP driver, which owns the
  collector and all three heap readers. The page mounts; it never decides
  when a reading is taken."
  [substrate]
  (reset! installed (arms substrate))
  (set! (.-ABL js/window)
        #js {:mount          (fn [arm] (mount! (keyword arm)))
             :release        (fn [] (release!*) true)
             :control        (fn [n] (control! n))
             :controlRelease (fn [] (control-release!))
             :witness        (fn [n offset] (witness! substrate n offset))
             :perfMem        (fn []
                               (if-some [m (.-memory js/performance)]
                                 (.-usedJSHeapSize m)
                                 -1))
             :substrate      (name substrate)
             :plan           (clj->js {:boundaries boundaries
                                       :reads      reads-rungs
                                       :cells      cells-needed})
             ;; `(subs (str kw) 1)`, NOT `name` — `name` drops the namespace,
             ;; so every arm would reach the driver with the variant half
             ;; missing and nothing to parse.
             :arms           (into-array (map #(subs (str %) 1)
                                              (keys @installed)))})
  nil)
