(ns re-frame.bench.hicasso.adoption-witness-app
  "THE ADOPTION WITNESS — does the commit adopt the render-phase build on a
  PUBLIC mount schedule, on a page whose clock is fast enough to say?
  (rf2-2rtt6.80; the ruling on rf2-2rtt6.71's ruling (a).)

  ## What this is, and what it deliberately is not

  It is a DIAGNOSTIC, run on demand:

      node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs

  It is NOT a gate. Nothing in CI invokes it, and that is the ruling, not an
  omission. rf2-2rtt6.71 wanted a standing tripwire and the browser suite could
  not be one: that runner's render-to-passive-flush gap measures > 128 ms, two
  to three orders of magnitude past any shippable reap horizon, so its row
  witnesses the RUNNER's schedule and can say nothing about a consumer's. The
  obvious repair — gate a representative page instead — was examined and
  refused, because at drift an oversized gap has two indistinguishable causes
  (a React scheduling change, or a loaded host) and the regression itself
  manifests AS a bigger gap. A gate whose measurement is confounded with the
  thing it guards either reds on load or declines to adjudicate exactly when
  the signal arrives. And the threat is discrete rather than ambient: `react`,
  `react-dom` and `playwright` are EXACT pins (`implementation/package.json`;
  Playwright pins its own Chromium), so the only events that can move this race
  are reviewed lock or posture changes. A standing gate would re-test a pinned
  constant for zero information between upgrades while paying flake risk on
  every run.

  So: committed, one command, self-reporting — and never in a required check.

  ## RE-RUN IT WHEN THE PINS MOVE

  Whenever `react` / `react-dom` / `playwright` change in
  `implementation/package.json`, or the browser posture changes, run this and
  read the gap. That is the whole revalidation protocol. Spec 006
  §Render-phase provisional acquisition and commit adoption records it too.

  ## THE ORDER IS THE POINT: gap first, integers second

  The reap horizon is `re-frame.substrate.spine/provisional-horizon-ms`
  (`setTimeout 4`, ruled by rf2-2rtt6.71). Adoption happens iff React's passive
  `useSyncExternalStore` subscribe reaches the escrowed reference before that
  timer fires. So the ONLY quantity that makes an adoption reading meaningful
  is the page's own render-to-passive-flush gap.

  This page therefore measures that gap FIRST, on cold single-boundary mounts
  through the public render slot, reading NO adoption integer while it does.
  Only if every measured gap sits comfortably under the horizon does it mount
  again and read the integers. If the gap cannot be bounded, it prints REFUSED
  and the measured gap — never pass, never fail. A noisy box costs a printout.

  That asymmetry is deliberate and it is what the whole instrument is for. This
  repository has been repairing fail-open instruments all week — gates that
  could not fail on the thing they claimed to cover. The failure mode available
  to a timing diagnostic is the mirror image: DEGRADING, quietly widening its
  own tolerance until any page passes. A horizon in the hundreds of
  milliseconds would have greened the browser row; it would also have bought
  the green by holding every abandoned render's reactive graph for a quarter of
  a second. The refusal path exists so that trade is never available here.

  THE GAP IS THE PRIMARY DATUM; the verdict is downstream of it. Gap statistics
  print on EVERY run, including refusals — a maintainer re-running this after a
  React upgrade wants the number even when (especially when) no verdict can be
  reached from it.

  ## The three verdicts

    ADOPTED       every trial's gap qualified, and on every trial the commit's
                  subscription HIT the entry the render escrowed: one sub-body
                  run, the render's reaction is still the cache's tenant at the
                  first post-subscribe instant, one durable reference.
    NOT-ADOPTED   the gaps qualified — the page's clock was fast enough to see
                  the race — and the mechanism still did not adopt. On a
                  qualifying page this is a MECHANISM regression (the escrow,
                  the identity adoption, the cache key, the release guard), not
                  a scheduling one: a scheduling change shows up as a bigger
                  gap, i.e. as REFUSED.
    REFUSED       the gap could not be bounded under the horizon with margin.
                  Nothing is adjudicated. Read the printed gap.

  ## The reading is CAUSAL, not timed (rf2-2rtt6.71's probe design)

  Every integer is read from the probe's OWN mount `use-effect`, declared after
  the `use-subscribe` call. React pushes `useSyncExternalStore`'s
  `subscribeToStore` passive effect while the hook runs and pushes this one
  after it, and a fiber's passive effects run in push order within one flush —
  so the callback is the first instant after the commit-owned subscribe,
  whenever the host chooses to get there. Nothing is concluded from a timer.

  The two clock readings are the exception, and they are the measurement: `t0`
  is taken in the render body immediately after `use-subscribe` returns (the
  instant the escrow token is minted and the reaper armed) and `t1` in that
  effect. `performance.now()` is clamped to 100 µs in Chrome, which is two
  decimal orders below the qualifying ceiling, so the clamp is not load-bearing
  here — but it is why gaps print at four decimals rather than being dressed up
  as exact.

  ## The public schedule, and nothing forcing it

  Mounts go through `re-frame.substrate.adapter/render` — the Spec 006 client
  mount entry, a bare `createRoot(…).render(…)`. No `act` (the lane leaves
  React's act environment before anything runs), no `flushSync`. That is the
  difference from the coldmount instrument, whose every mount is bracketed by
  `flushSync` and which therefore prices the MECHANISM rather than the
  schedule.

  ## What it does NOT prove

  A margin, never a contract. React documents no maximum render-to-subscribe
  interval, so an ADOPTED verdict is a measurement of React 19 on this box on
  this day, not a guarantee. Correctness is not at stake in either direction:
  Spec 006 requires that correctness MUST NOT depend on the reaper losing the
  race, and it does not — a lost race costs a construction and nothing else,
  which is exactly what makes a timed horizon acceptable at all.

  ## PHASE 3 — THE HYDRATION SCHEDULE (rf2-2rtt6.87, X3)

  The same question, asked of the door an SSR route takes: `(render tree
  mount {:hydrate? true})`, the Spec 006 client mount entry's hydrate
  arm, over markup `react-dom/server` produced for this very boundary.
  Nothing about the method changes — **the gap is still measured first
  and the integers are still unreachable until it qualifies** — because
  the reason the order exists does not change either: a reaper that beats
  the passive subscribe evicts the entry whether the root was created or
  adopted.

  What phase 3 adds is the row X3 asks for: **refcount parity with a cold
  mount, read after a settle past the reap horizon**. The
  first-post-subscribe reading says the commit adopted the render's
  build; the SETTLED reading says the reaper then left it alone. A
  hydrated mount that adopts and is reaped a moment later would pass the
  first and fail the second, and the difference is exactly the defect the
  0 → 4 ms horizon was moved for.

  Phase 3 refuses on its own gap, independently of phase 2, and a
  refusal publishes no figure for it.

  Owner: rf2-2rtt6.80; phase 3 rf2-2rtt6.87."
  (:require [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [uix.core :as uix :refer-macros [defui $]]
            ["react-dom/server" :as rdom-server]))

;; ---------------------------------------------------------------------------
;; The plan
;; ---------------------------------------------------------------------------

;; Discarded, and printed anyway. The first mount on a fresh page pays React's
;; own lazy initialisation, and a warm-up whose reading is hidden is a warm-up
;; nobody can audit.
(def ^:private warmup-trials 2)

;; Enough that one unlucky turn cannot carry the verdict, few enough that the
;; whole run is seconds. Each trial is a fresh frame, a fresh container and a
;; fresh root, so every one of them is a genuinely COLD read.
(def ^:private gap-trials 12)
(def ^:private adoption-trials 5)
(def ^:private hydration-trials 5)

;; The page must be idle at each mount, and the previous trial's reap horizon
;; must be behind us before the next one arms its own.
(def ^:private settle-between-ms 32)

;; A probe that never commits is a failed run, not a slow one.
(def ^:private commit-budget-ms 2000)

;; Past the reap horizon with room, and the suite's own crossing constant
;; (`settle-past-the-horizon!`, 24 ms). The SETTLED ref-count is read here
;; — outside the measured gap, which ends at the mount effect — so a mount
;; that adopted and was then reaped is a different reading from one that
;; adopted and kept its entry. rf2-2rtt6.87, X3.
(def ^:private settle-past-horizon-ms 24)

(def ^:private query-v [::cell])

;; ---------------------------------------------------------------------------
;; The boundary
;; ---------------------------------------------------------------------------

;; `defui` mints a Var and cannot sit in a `let`, so the trial talks to the
;; component through side channels — the browser suite's own arrangement.

(def ^:private builds        (atom 0))
(def ^:private target-frame  (atom nil))
(def ^:private at-render     (atom nil))
(def ^:private at-commit     (atom nil))

(defui Boundary []
  ;; ONE subscribing boundary. The `use-effect` is declared AFTER the read on
  ;; purpose: that ordering, and not a timer, is what places the snapshot at
  ;; the first instant after the commit-owned subscribe.
  (let [f @target-frame
        v (uixa/use-subscribe f query-v)]
    (when-let [g @at-render] (g))
    (uix/use-effect
      (fn [] (when-let [g @at-commit] (g)) js/undefined)
      [])
    ($ :span (str "m=" v))))

(defn- register! []
  (rf/reg-event ::seed (fn [_ _] {:db {:m 7}}))
  (rf/reg-sub (first query-v) (fn [db _] (swap! builds inc) (:m db)))
  nil)

;; ---------------------------------------------------------------------------
;; Turns
;; ---------------------------------------------------------------------------

(defn- sleep
  "A promise resolved from a host macrotask `ms` from now."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout (fn [] (resolve nil)) ms))))

(defn- timeout-after [ms v]
  (js/Promise. (fn [resolve _] (js/setTimeout (fn [] (resolve v)) ms))))

;; ---------------------------------------------------------------------------
;; One trial
;; ---------------------------------------------------------------------------

(def ^:private server-markup
  "The bytes a server would send for ONE [[Boundary]], rendered once at
  boot by [[render-server-markup!]] and reused by every hydration trial.

  Once, and from a THROWAWAY frame, because that is the real shape: the
  server's render happens elsewhere and each request's client arrives
  cold. Rendering it per trial would tenant the very cache entry the
  trial has to find empty, and `:cold?` would go false on every hydration
  row."
  (atom nil))

(defn- render-server-markup!
  "`renderToString` over the same boundary, from a frame that is destroyed
  before it returns.

  The spine's `use-subscribe` passes its snapshot as BOTH the client and
  the server snapshot (`spine.cljs` — `useSyncExternalStore` is called
  with three arguments), so React never calls `subscribe` here and the
  read is the mutation-free one. The probe hooks are cleared first: a
  server render that fired `at-render` would stamp a clock reading that
  belongs to no trial."
  []
  (let [fid ::server-render]
    (reset! at-render nil)
    (reset! at-commit nil)
    (rf/make-frame {:id fid :doc "rf2-2rtt6.87 adoption-witness server render"})
    (rf/dispatch-sync [::seed] {:frame fid})
    (reset! target-frame fid)
    (let [html (rdom-server/renderToString ($ Boundary))]
      (try (rf/destroy-frame! fid) (catch :default _ nil))
      (reset! target-frame nil)
      (reset! builds 0)
      (reset! server-markup html)
      html)))

(defn- run-trial!
  "Mount ONE cold subscribing boundary through the public render slot; answer a
  promise of the trial record.

  `read-integers?` is the ordering rule in one argument. When it is false this
  is the GAP PHASE and the trial reads nothing but its two clock stamps — no
  tenant, no build count, no ref-count. The adoption integers are unreachable
  until a gap has qualified, by construction rather than by discipline.

  `hydrate?` selects the door: `{:hydrate? true}` over the server's markup
  (phase 3) or an ordinary render into an empty container (phases 1 and 2).
  Both are `re-frame.substrate.adapter/render` — the Spec 006 client mount
  entry — so the two phases differ in the ARM of one public door and in
  nothing else."
  ([n read-integers?] (run-trial! n read-integers? false))
  ([n read-integers? hydrate?]
  (let [frame-id      (keyword "rf.adoptwit" (str "trial-" n))
        container     (lane/fresh-container!)
        t0            (volatile! nil)
        t1            (volatile! nil)
        render-tenant (volatile! nil)
        snap          (volatile! nil)
        resolve!      (volatile! nil)
        committed     (js/Promise. (fn [resolve _] (vreset! resolve! resolve)))]
    (rf/make-frame {:id frame-id :doc "rf2-2rtt6.80 adoption-witness trial frame"})
    (rf/dispatch-sync [::seed] {:frame frame-id})
    (reset! builds 0)
    (reset! target-frame frame-id)
    (let [cache (:sub-cache (frame/frame frame-id))
          cold? (nil? (get @cache query-v))]
      (reset! at-render
              (fn []
                (vreset! t0 (lane/now-ms))
                (when read-integers?
                  ;; The render's escrowed +1 is what keeps this entry
                  ;; tenanted, so the reaction read HERE is the render's.
                  (vreset! render-tenant (get-in @cache [query-v :reaction])))))
      (reset! at-commit
              (fn []
                (when (nil? @t1)
                  (vreset! t1 (lane/now-ms))
                  (when read-integers?
                    (let [tenant (get-in @cache [query-v :reaction])]
                      (vreset! snap
                               {:builds    @builds
                                :adopted?  (and (some? @render-tenant)
                                                (identical? @render-tenant tenant))
                                :ref-count (or (get-in @cache [query-v :ref-count]) 0)
                                :dom       (.-textContent container)})))
                  ;; Hand the turn back only AFTER every reading is taken: the
                  ;; continuation runs as a microtask off this resolve, and a
                  ;; reading taken there would sit behind the rest of the
                  ;; effect queue instead of at the first post-subscribe
                  ;; instant.
                  (@resolve! :committed))))
      (when hydrate?
        ;; The page as it arrives, before any JavaScript has adopted it.
        (set! (.-innerHTML container) @server-markup))
      (let [unmount (substrate-adapter/render ($ Boundary) container
                                              (if hydrate? {:hydrate? true} {}))]
        (-> (js/Promise.race #js [committed (timeout-after commit-budget-ms :timeout)])
            ;; The SETTLED read happens one horizon-crossing later, and
            ;; outside the measured gap: the gap ends at the mount effect
            ;; and this is about what the REAPER did afterwards.
            (.then (fn [outcome] (.then (sleep settle-past-horizon-ms)
                                        (fn [_] outcome))))
            (.then
              (fn [outcome]
                (let [settled (when (and (not= outcome :timeout) read-integers?)
                                (or (get-in @cache [query-v :ref-count]) 0))
                      rec (cond-> {:trial      n
                                   :hydrated?  (boolean hydrate?)
                                   :cold?      cold?
                                   ;; A trial that never RENDERED and one that
                                   ;; rendered and never committed are
                                   ;; different faults; the record separates
                                   ;; them rather than reporting a shared nil.
                                   :rendered?  (some? @t0)
                                   :committed? (not= outcome :timeout)}

                            (= outcome :timeout)
                            (assoc :dom-at-timeout (.-textContent container))
                            (not= outcome :timeout)
                            (assoc :gap-ms (lane/round4 (- @t1 @t0)))

                            (and (not= outcome :timeout) read-integers?)
                            (merge @snap)

                            (some? settled)
                            (assoc :ref-count-settled settled))]
                  (reset! at-render nil)
                  (reset! at-commit nil)
                  (try (unmount) (catch :default _ nil))
                  (.remove container)
                  (try (rf/destroy-frame! frame-id) (catch :default _ nil))
                  rec)))))))))

(defn- trial-ids
  "`phase`-prefixed ids, so a phase-2 trial never re-uses a phase-1 frame id
  and every mount is cold against a registry that has never seen it."
  [phase n]
  (mapv (fn [i] (str phase i)) (range 1 (inc n))))

(defn- run-trials!
  "Run `ids` in sequence — one page, one mount at a time — answering a promise
  of the records. Sequential because a second live root would put the thing
  being measured (React's own scheduling) under load from the measurement."
  ([ids read-integers?] (run-trials! ids read-integers? false))
  ([ids read-integers? hydrate?]
   (reduce (fn [chain n]
             (.then chain
                    (fn [acc]
                      (-> (run-trial! n read-integers? hydrate?)
                          (.then (fn [rec]
                                   (.then (sleep settle-between-ms)
                                          (fn [_] (conj acc rec)))))))))
           (js/Promise.resolve [])
           ids)))

;; ---------------------------------------------------------------------------
;; Adjudication
;; ---------------------------------------------------------------------------

(defn- gap-summary [rows]
  (let [gaps (keep :gap-ms rows)]
    (merge {:trials (count rows) :committed (count gaps)}
           (some-> (lane/summarise gaps)
                   (update :min lane/round4)
                   (update :max lane/round4)
                   (update :p50 lane/round4)))))

(defn- disqualifiers
  "Every reason `rows` cannot carry an adoption reading, in the order a reader
  would want them. Empty means the phase qualified."
  [rows ceiling-ms]
  (let [uncommitted (remove :committed? rows)
        cold-broken (remove :cold? rows)
        over        (filter (fn [r] (and (:gap-ms r) (> (:gap-ms r) ceiling-ms))) rows)]
    (cond-> []
      (seq cold-broken)
      (conj (str (count cold-broken) " trial(s) were not COLD — the entry was already "
                 "tenanted before the mount, so no render-phase acquisition happened"))

      (seq uncommitted)
      (conj (str (count uncommitted) " trial(s) never reached their mount effect within "
                 commit-budget-ms " ms"))

      (seq over)
      (conj (str (count over) " of " (count rows) " trial(s) exceeded the qualifying "
                 "ceiling of " ceiling-ms " ms: gaps "
                 (pr-str (mapv :gap-ms over)) " ms")))))

(defn- adoption-faults [rows]
  (reduce
    (fn [acc {:keys [trial builds adopted? ref-count ref-count-settled dom]}]
      (cond-> acc
        (not= 1 ref-count-settled)
        (conj (str "trial " trial ": " ref-count-settled " durable reference(s) "
                   settle-past-horizon-ms " ms after the commit — expected exactly "
                   "1. The commit adopted and the reaper then took it, which the "
                   "first-post-subscribe reading alone cannot see"))

        (not= 1 builds)
        (conj (str "trial " trial ": the sub body ran " builds " time(s) for one cold "
                   "mount — expected exactly 1 (the pre-hand-off reading is 2)"))

        (not adopted?)
        (conj (str "trial " trial ": the cache's tenant at the first post-subscribe "
                   "instant is NOT the reaction the render built — the escrowed "
                   "reference was released and the commit rebuilt"))

        (not= 1 ref-count)
        (conj (str "trial " trial ": " ref-count " durable reference(s) after the "
                   "commit — expected exactly 1"))

        (not= "m=7" dom)
        (conj (str "trial " trial ": the boundary rendered " (pr-str dom)
                   " — it did not read the subscribed value, so nothing above it counts"))))
    []
    rows))

(defn- settled-refcounts [rows] (mapv :ref-count-settled rows))

(defn- parity-faults
  "PHASE 3's own row (rf2-2rtt6.87, X3): the hydrated mounts' settled
  ref-counts against the COLD mounts'.

  Stated as a comparison rather than as a second copy of the constant `1`
  — the claim X3 makes is `parity with a cold mount`, and a hydration
  that held two references would be a fault whether or not a cold mount
  held one."
  [cold-rows hyd-rows]
  (let [c (set (settled-refcounts cold-rows))
        h (set (settled-refcounts hyd-rows))]
    (cond-> []
      (not= c h)
      (conj (str "settled ref-counts DIFFER between the two schedules: cold "
                 (pr-str (settled-refcounts cold-rows)) " vs hydrated "
                 (pr-str (settled-refcounts hyd-rows))
                 " — the adopted subscription is not the cold mount's equal "
                 (str settle-past-horizon-ms) " ms after the commit")))))

;; ---------------------------------------------------------------------------
;; Parameters — supplied, never invented
;; ---------------------------------------------------------------------------

(defn- params
  "The horizon and the qualifying ceiling come from the DRIVER, which reads the
  horizon out of `spine.cljs` itself. This page does not carry a copy of the
  shipped number: a second copy is a second authority, and the one that drifts
  is always the copy.

  Two refusals here, both asymmetric on purpose. A missing or unreadable
  parameter refuses. A ceiling ABOVE the horizon refuses — that direction would
  let an unrepresentative page adjudicate, which is the fail-open this
  instrument exists to avoid. A ceiling BELOW is always allowed: it can only
  make the instrument decline more often, never pass more easily."
  []
  (let [q       (js/URLSearchParams. (.. js/window -location -search))
        horizon (js/parseFloat (or (.get q "horizon") ""))
        ceiling (js/parseFloat (or (.get q "ceiling") ""))]
    (cond
      (not (and (js/isFinite horizon) (pos? horizon)))
      {:error "no usable ?horizon= — the driver must supply the spine's reap horizon"}

      (not (and (js/isFinite ceiling) (pos? ceiling)))
      {:error "no usable ?ceiling= — the driver must supply the qualifying ceiling"}

      (> ceiling horizon)
      {:error (str "?ceiling=" ceiling " exceeds ?horizon=" horizon
                   " — a ceiling above the horizon would let a page that cannot see "
                   "the race adjudicate it; refusing to run")}

      :else {:horizon-ms horizon :ceiling-ms ceiling})))

;; ---------------------------------------------------------------------------
;; Publication
;; ---------------------------------------------------------------------------

(defn- verdict! [v why]
  (set! (.-ADOPTWIT_VERDICT js/window) (name v))
  (js/console.log (str ";; ==== ADOPTION WITNESS VERDICT: " (name v) " ===="))
  (doseq [line why] (js/console.log (str ";;   " line)))
  v)

(defn- print-gap! [label summary ceiling-ms horizon-ms]
  (js/console.log (str ";; ==== ADOPTION WITNESS GAP (" label ") ===="))
  (js/console.log (str ";;   render -> passive-flush, ms; performance.now() is "
                       "clamped to 0.0001 ms in Chrome"))
  (js/console.log (str ";;   n " (:committed summary) "/" (:trials summary)
                       "  min " (:min summary)
                       "  p50 " (:p50 summary)
                       "  max " (:max summary)))
  (js/console.log (str ";;   horizon " horizon-ms " ms (spine provisional-horizon-ms)"
                       "   qualifying ceiling " ceiling-ms " ms")))

(defn- trial-line [r]
  (str ";;   trial " (:trial r)
       "  gap " (:gap-ms r) " ms"
       "  builds " (:builds r)
       "  adopted? " (:adopted? r)
       "  refs " (:ref-count r)
       "  refs@" settle-past-horizon-ms "ms " (:ref-count-settled r)
       "  dom " (pr-str (:dom r))))

;; ---------------------------------------------------------------------------
;; The two phases
;; ---------------------------------------------------------------------------

(defn- adjudicate-hydration!
  "PHASE 3 (rf2-2rtt6.87, X3) — the same question on the HYDRATE arm of the
  same public door, reached only from an ADOPTED phase 2.

  Gap first, exactly as phase 2: an over-ceiling hydration mount publishes
  no figure and says which trials disqualified it. `cold-rows` is phase 2's
  record, and it is what `parity` is stated against."
  [cold-rows horizon-ms ceiling-ms]
  (-> (run-trials! (trial-ids "h" hydration-trials) true true)
      (.then
        (fn [rows]
          (let [summary (gap-summary rows)
                bad     (disqualifiers rows ceiling-ms)]
            (lane/record! :hydration {:horizon-ms horizon-ms
                                      :ceiling-ms ceiling-ms
                                      :summary    summary
                                      :rows       rows
                                      :cold-settled (settled-refcounts cold-rows)
                                      :hydrated-settled (settled-refcounts rows)})
            (print-gap! "phase 3 — HYDRATION mounts" summary ceiling-ms horizon-ms)
            (doseq [r rows] (js/console.log (trial-line r)))
            (if (seq bad)
              (verdict! :REFUSED
                        (into ["phases 1 and 2 qualified but a HYDRATION mount did not, so its"
                               "integers were discarded unread. No X3 figure is published."]
                              bad))
              (let [faults (into (adoption-faults rows) (parity-faults cold-rows rows))]
                (if (seq faults)
                  (verdict! :NOT-ADOPTED
                            (into ["every gap qualified — this page CAN see the race — and the"
                                   "HYDRATED commit still did not hold what a cold mount holds."]
                                  faults))
                  (verdict! :ADOPTED
                            [(str adoption-trials " of " adoption-trials " COLD mounts and "
                                  hydration-trials " of " hydration-trials " HYDRATION mounts: "
                                  "one sub-body run, the render's reaction still the cache's "
                                  "tenant at the first post-subscribe instant, one durable "
                                  "reference — and the same settled ref-count "
                                  settle-past-horizon-ms " ms later on both schedules "
                                  (pr-str (settled-refcounts rows)) ".")
                             (str "A MARGIN, NOT A CONTRACT: React documents no maximum "
                                  "render-to-subscribe interval. Re-run when the react / "
                                  "react-dom / playwright pins move.")]))))
            (lane/done!))))))

(defn- adjudicate-adoption!
  "PHASE 2 — reached only from a qualifying phase-1 gap, and refusing again if
  its own mounts do not qualify. `adoption-faults` is not called on a
  disqualified set: a NOT-ADOPTED read off a slow mount is a noise artefact
  wearing a regression's clothes."
  [horizon-ms ceiling-ms]
  (-> (run-trials! (trial-ids "a" adoption-trials) true)
      (.then
        (fn [rows]
          (let [summary (gap-summary rows)
                bad     (disqualifiers rows ceiling-ms)]
            (lane/record! :adoption {:horizon-ms horizon-ms
                                     :ceiling-ms ceiling-ms
                                     :summary    summary
                                     :rows       rows})
            (print-gap! "phase 2 — adoption mounts" summary ceiling-ms horizon-ms)
            (doseq [r rows] (js/console.log (trial-line r)))
            (if (seq bad)
              (do (verdict! :REFUSED
                            (into ["phase 1 qualified but an adoption mount did not, so its"
                                   "integers were discarded unread."]
                                  bad))
                  (lane/done!)
                  (js/Promise.resolve nil))
              (let [faults (adoption-faults rows)]
                (if (seq faults)
                  (do (verdict! :NOT-ADOPTED
                                (into ["every gap qualified — this page CAN see the race — and the"
                                       "commit still did not adopt the render's build. On a qualifying"
                                       "page that is a MECHANISM regression, not a scheduling one."]
                                      faults))
                      (lane/done!)
                      (js/Promise.resolve nil))
                  ;; Phase 2 held, so the hydration schedule is worth asking
                  ;; about — and phase 2's records are what its parity row is
                  ;; stated against.
                  (adjudicate-hydration! rows horizon-ms ceiling-ms)))))))))

(defn- adjudicate-gap!
  "PHASE 1 — the schedule, and nothing else. Answers a promise so the caller's
  chain is the same shape whichever way this goes."
  [rows horizon-ms ceiling-ms]
  (let [summary (gap-summary rows)
        bad     (disqualifiers rows ceiling-ms)]
    (lane/record! :gap {:horizon-ms horizon-ms
                        :ceiling-ms ceiling-ms
                        :summary    summary
                        :rows       rows})
    (print-gap! "phase 1 — schedule" summary ceiling-ms horizon-ms)
    (if (seq bad)
      (do (verdict! :REFUSED
                    (into ["this page cannot bound its render-to-passive-flush gap under the"
                           "reap horizon with margin, so the adoption integers were NOT read."
                           "This is not a failure of the adoption; it is a refusal to"
                           "adjudicate it here."]
                          bad))
          (lane/done!)
          (js/Promise.resolve nil))
      (adjudicate-adoption! horizon-ms ceiling-ms))))

(defn ^:export -main []
  (rf/init! uixa/adapter)
  ;; Every reading here is taken outside React's act queue, so the commit
  ;; measured is the commit a consumer's page performs.
  (lane/leave-act-environment!)
  (let [{:keys [horizon-ms ceiling-ms error]} (params)]
    (if error
      (do (lane/fail! error) (lane/done!))
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (register!) (render-server-markup!)))
          ;; WARM-UP: printed, never counted. The first mount on a fresh page
          ;; pays React's own lazy initialisation.
          (.then (fn [_] (run-trials! (trial-ids "w" warmup-trials) false)))
          (.then (fn [warm]
                   (lane/record! :warmup {:rows warm})
                   (js/console.log (str ";; warm-up gaps (discarded): "
                                        (pr-str (mapv :gap-ms warm)) " ms"))
                   (run-trials! (trial-ids "g" gap-trials) false)))
          (.then (fn [rows] (adjudicate-gap! rows horizon-ms ceiling-ms)))
          (.catch (fn [e]
                    (lane/fail! (or (some-> e .-message) (str e)))
                    (lane/done!)))))))
