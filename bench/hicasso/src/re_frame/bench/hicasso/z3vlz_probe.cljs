(ns re-frame.bench.hicasso.z3vlz-probe
  "THE rf2-z3vlz DISCRIMINATOR — one reactivity probe, four bundles.

  rf2-z3vlz records that in HD-008's `:advanced` bench bundle — which
  compiles stock `reagent`, `reagent2` (reagent-slim) AND `uix` together —
  a `reg-view` tree mounted through `reagent2.dom.client` renders
  CORRECTLY at mount and then NEVER re-renders on a write: 78 of 78 writes
  failed the DOM read-back, while the stock-Reagent arm in the identical
  harness passed 78 of 78.

  The bead names three candidates and establishes none:

    (a) a reagent-slim ADAPTER DEFECT — shipped code, users affected;
    (b) a MIXED-BUNDLE ARTEFACT — stock `reagent` and `reagent2`
        coexisting in one `:advanced` bundle;
    (c) a `re-frame.views` LATE-BINDING problem — the late bind resolving
        to the wrong substrate's hooks when both are present.

  THE DISCRIMINATOR IS ONE QUESTION: does a SINGLE-SUBSTRATE reagent-slim
  bundle — reagent-slim ALONE, no stock reagent compiled in — re-render on
  a write? If yes it is (b) or (c) and no user is affected; if no it is (a)
  and the bug is in shipped code.

  So this namespace holds EVERYTHING that is the same across the bundles —
  the subscription, the event, the `reg-view` boundaries, the frame
  provider, the write mechanisms, the drain escalation ladder and the DOM
  read-back accounting — and the four entry namespaces differ ONLY in what
  they `:require`. That is the whole experimental design: the probe is a
  constant and the bundle composition is the variable.

  ## What is measured, and what is deliberately NOT

  Nothing here reads a clock. This is a DIAGNOSIS, not a benchmark: the
  only numbers it publishes are write counts and DOM read-back counts, so
  there is no bar-comparable figure, no arm-to-arm ratio, and therefore
  nothing for the arm-order guard to adjudicate. (Stated rather than
  omitted: a run that quietly skipped the guard while publishing a ratio
  would be exactly the fault the guard exists to catch.)

  ## The instrument disciplines that DO apply, and how

  1. **Every write is read back out of the DOM**, all cells, inside the
     write's own window, and every result is reported as `N unverified of
     M`. That rule is what turned this from a headline into a bead: the
     unverified slim arm was reading 0.16-0.50x the floor while changing
     nothing, which would have published as `reagent-slim narrow write is
     2-6x faster than a top-down re-render`.
  2. **A negative result needs a positive control**, and this probe
     carries TWO, both inside the same bundle and the same harness:
       - the RAW control: a plain substrate component reading a plain
         substrate atom, no re-frame at all. It proves the bundle's
         reactive engine and this harness's drain + read-back can see a
         change AT ALL. A slim arm that fails while its own raw control
         passes is a re-frame-side finding; a slim arm that fails
         alongside its raw control is a harness or engine finding.
       - the app-db witness: `frame-app-db-value` is read after every
         write, so a failed read-back is attributed to the VIEW leg rather
         than left ambiguous with the event leg.
  3. **The drain escalation ladder.** A write that fails its read-back is
     retried through progressively more generous drains — the substrate's
     own synchronous drain again, then a macrotask, then two animation
     frames. `NEVER` and `LATE` are different findings and an instrument
     that cannot tell them apart has not measured the thing in the bead.

  Owner: rf2-z3vlz. Normative context: `docs/design/hicasso/decisions.md`
  HD-008."
  (:require ["react-dom" :as react-dom]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; The shape — small on purpose
;; ---------------------------------------------------------------------------

(def cells-n
  "Eight cells. The bead's arm ran 300; the question here is BINARY (does
  the DOM follow a write, yes or no), and eight cells make every failure
  printable in full rather than summarised."
  8)

(def writes-n
  "Twelve writes per arm, alternating the two mechanisms the bead
  reproduced with. Enough that `0 of 12` and `12 of 12` are both
  unambiguous, few enough that the whole per-write record fits in the run
  log."
  12)

;; ---------------------------------------------------------------------------
;; The re-frame side
;; ---------------------------------------------------------------------------

(rf/reg-sub :z3vlz/cell (fn [db [_ i]] (get-in db [:cells i])))

;; `reg-event-db` is REMOVED in EP-0018 (no alias): the db arrives
;; destructured out of the coeffects map and the return is wrapped in
;; `{:db …}`.
(rf/reg-event :z3vlz/set-all
  (fn [{:keys [db]} [_ v]]
    {:db (assoc db :cells (vec (repeat cells-n v)))}))

(defn seed-db [v] {:cells (vec (repeat cells-n v))})

(reg-view ^{:rf/id :z3vlz/cell-view} cell-view
  "One boundary, one subscription read — the unit whose re-render is in
  question."
  [i]
  (let [v @(rf/subscribe [:z3vlz/cell i])]
    [:li.row
     [:span.lbl "cell "]
     [:span.cell {:data-i i} (str v)]]))

(reg-view ^{:rf/id :z3vlz/list-view} list-view
  [n]
  [:ul.grid {:role "list"}
   (for [i (range n)]
     ^{:key i} [cell-view i])])

(defn root
  "`rf/frame-provider` is how a re-frame2 application supplies the frame a
  `reg-view` boundary resolves `subscribe` against — a plain fn carries no
  `:contextType`, so leaving it out would raise
  `:rf.error/no-frame-context` rather than measure anything."
  [fid n]
  [rf/frame-provider {:frame fid} [list-view n]])

;; ---------------------------------------------------------------------------
;; Yielding
;; ---------------------------------------------------------------------------

(defn- microtask [] (js/Promise.resolve nil))

(defn- macrotask []
  (js/Promise. (fn [res] (js/setTimeout (fn [] (res nil)) 0))))

(defn- two-frames []
  (js/Promise. (fn [res]
                 (js/requestAnimationFrame
                   (fn [] (js/requestAnimationFrame (fn [] (res nil))))))))

;; ---------------------------------------------------------------------------
;; The read-back
;; ---------------------------------------------------------------------------

(defn- cells-text [container]
  (mapv (fn [i] (rf.bench.hicasso.lane/text-at container i)) (range cells-n)))

(defn- all-at?
  "EVERY cell, not one. A stale page can still hold one fresh cell from a
  previous write, so a single-probe check verifies almost nothing."
  [container v]
  (let [want (str v)]
    (every? (fn [i] (= want (rf.bench.hicasso.lane/text-at container i))) (range cells-n))))

;; ---------------------------------------------------------------------------
;; THE THIRD VARIABLE — the ORDER of write against drain
;; ---------------------------------------------------------------------------
;;
;; The first run of this rig made a single-substrate reagent-slim bundle
;; fail 12 of 12, which by the bead's stated logic would have read as
;; candidate (a) — a defect in shipped adapter code. It is not, and the
;; reason is a variable the bead does not name: WHEN the drain runs
;; relative to the write.
;;
;; The two Reagent builds schedule their component queue differently, and
;; that difference is documented in both of them:
;;
;;   - stock Reagent drains on a three-phase `requestAnimationFrame` queue;
;;   - reagent-slim drains on a MICROTASK queue
;;     (`reagent2.impl.batching`: "a microtask-based scheduler ... no
;;     requestAnimationFrame fallback is required").
;;
;; `reagent2.dom.client/flush-render!` is documented as running `(do (f)
;; (batching/flush!))` INSIDE a `react-dom/flushSync` boundary, and that
;; boundary is the load-bearing part: "under React 19 `createRoot`, a bare
;; `forceUpdate` issued from outside React's batching context is subject to
;; automatic batching: React SCHEDULES the re-render rather than committing
;; it synchronously".
;;
;; Put those two facts together. A harness that writes, YIELDS ONE
;; MICROTASK, and only then drains has already let reagent-slim's own
;; microtask scheduler run: the component queue is empty by the time
;; `flush-render!` sees it, and the `forceUpdate` it issued went out
;; OUTSIDE any `flushSync` boundary, so React holds it. `flush-render!`
;; then finds nothing to flush and the DOM read-back reads the old value.
;; Stock Reagent survives the identical harness because its rAF queue has
;; NOT fired after one microtask — the drain still finds the queue full and
;; commits it inside the boundary.
;;
;; So the order is measured rather than assumed. Three of them, every arm,
;; every bundle:

(def orders
  [{:id    :inside-drain
    :doc   (str "the write happens INSIDE `flush-render!`'s `f` — the adapter's own "
                "documented production contract, and the shape the shipped "
                "reagent-slim adapter smoke exercises through clicks")}
   {:id    :drain-immediately
    :doc   (str "write, then drain with NO yield in between. The component queue is "
                "still full, so the drain has something to flush")}
   {:id    :yield-then-drain
    :doc   (str "write, yield ONE microtask, then drain — the shape of "
                "`rf.bench.hicasso.lane/verified-write!`, and therefore of HD-008's arm")}])

;; ---------------------------------------------------------------------------
;; One verified write, with the escalation ladder
;; ---------------------------------------------------------------------------

(defn- verified-write!
  "Perform one write under `order`, then chase it through progressively
  more generous drains until the DOM shows it or the ladder is exhausted.

  Answers a promise of `{:v :order :mechanism :db-followed? :ok? :rung
  :cells}` where `:rung` is the first drain that got the value onto the
  page — `:sync` (the order's own drain, which is what an application
  gets), then `:redrain`, `:macrotask`, `:raf2` — or `:never`.

  The rungs past `:sync` are diagnostic ONLY. A value that needs a
  macrotask is a LATE re-render, which is a different (and far milder)
  finding from a re-render that never happens; collapsing the two would
  lose the distinction the bead turns on."
  [{:keys [drain! drain-with!]} container fid order v mechanism write!]
  (let [rung    (volatile! nil)
        started (case order
                  :inside-drain      (do (drain-with! write!)
                                         (js/Promise.resolve nil))
                  :drain-immediately (do (write!) (drain!)
                                         (js/Promise.resolve nil))
                  :yield-then-drain  (do (write!)
                                         (.then (microtask) (fn [_] (drain!)))))
        db-followed? (when fid
                       (= (vec (repeat cells-n v))
                          (:cells (rf.frame/frame-app-db-value fid))))]
    (-> started
        (.then (fn [_] (when (all-at? container v) (vreset! rung :sync))))
        (.then (fn [_]
                 (when-not @rung
                   (drain!)
                   (when (all-at? container v) (vreset! rung :redrain)))))
        (.then (fn [_] (when-not @rung (macrotask))))
        (.then (fn [_]
                 (when-not @rung
                   (drain!)
                   (when (all-at? container v) (vreset! rung :macrotask)))))
        (.then (fn [_] (when-not @rung (two-frames))))
        (.then (fn [_]
                 (when-not @rung
                   (drain!)
                   (when (all-at? container v) (vreset! rung :raf2)))))
        (.then (fn [_]
                 (cond-> {:v         v
                          :order     order
                          :mechanism mechanism
                          :ok?       (= :sync @rung)
                          :rung      (or @rung :never)}
                   (some? db-followed?) (assoc :db-followed? db-followed?)
                   (not= :sync @rung)   (assoc :cells (cells-text container))))))))

;; ---------------------------------------------------------------------------
;; The two write mechanisms the bead reproduced with
;; ---------------------------------------------------------------------------

(defn- write-fn
  "Alternate `rf.frame/replace-app-db!` and a registered event through
  `dispatch-sync`. The bead reproduced with BOTH, so a probe that used one
  would leave the other unanswered."
  [fid v mechanism]
  (case mechanism
    :replace-app-db (fn [] (rf.frame/replace-app-db! fid (seed-db v)))
    :dispatch-sync  (fn [] (rf/dispatch-sync [:z3vlz/set-all v] {:frame fid}))))

;; ---------------------------------------------------------------------------
;; One arm
;; ---------------------------------------------------------------------------

(defn- mount!
  "Create the root and render INSIDE a `react-dom/flushSync`.

  `root.render` called outside a React event schedules at the default lane
  and does not commit before the next line runs, so a mount read-back taken
  without this boundary reads an EMPTY container — which is what the first
  run of this probe did, in every arm including the raw control. The raw
  control failing beside the arm under test is the whole reason that was a
  ten-minute harness fix rather than a published finding about
  reagent-slim.

  The boundary is React's, not a substrate's, so all three substrates take
  the identical mount and no arm is billed for a commit another arm skipped."
  [{:keys [create-root render]} container tree]
  (let [r (create-root container)]
    (react-dom/flushSync (fn [] (render r tree)))
    r))

(defn- tally
  "`N unverified of M` per ORDER, plus the rung histogram that tells a
  never-re-rendered apart from a late one.

  `:db-followed-all?` is gated on the KEY BEING PRESENT and never on the
  value being truthy. `(some :db-followed? rs)` — what this did — drops
  the slot precisely when every write failed the app-db witness, which is
  the one case it exists to report: the field goes silent exactly when the
  interesting thing has happened, and a reader sees the same absence it
  sees for the raw control, which legitimately has no app-db at all. The
  population is what is asserted (`every?`), not the existence of one
  member of it."
  [results]
  (into {}
        (map (fn [[order rs]]
               [order (cond-> {:of         (count rs)
                               :unverified (count (remove :ok? rs))
                               :summary    (str (count (remove :ok? rs))
                                                " unverified of " (count rs))
                               :by-rung    (frequencies (map :rung rs))}
                        (some :mechanism rs)
                        (assoc :by-mechanism
                               (into {} (map (fn [[m ms]]
                                               [m {:of (count ms)
                                                   :unverified (count (remove :ok? ms))}]))
                                     (group-by :mechanism rs)))
                        (some #(contains? % :db-followed?) rs)
                        (assoc :db-followed-all? (every? :db-followed? rs)
                               :db-followed-of   (str (count (filter :db-followed? rs))
                                                      " of " (count rs))))]))
        (group-by :order results)))

(defn- writes-over-orders!
  "Run [[writes-n]] verified writes under EVERY order, serially, against a
  standing mount. Answers a promise of the flat result vector."
  [substrate container fid write-of]
  (rf.bench.hicasso.lane/chain []
              (for [{:keys [id]} orders, v (range 1 (inc writes-n))] [id v])
              (fn [acc [order v]]
                (let [[mech write!] (write-of v)]
                  (-> (verified-write! substrate container fid order v mech write!)
                      (.then (fn [r] (conj acc r))))))))

(defn- run-subs-arm!
  "The arm under test: `reg-view` boundaries reading `rf/subscribe`,
  mounted through the substrate's own door, written through re-frame."
  [{:keys [unmount] :as substrate} fid]
  (rf/make-frame {:id fid})
  (rf.frame/replace-app-db! fid (seed-db 0))
  (let [container   (rf.bench.hicasso.lane/fresh-container!)
        handle      (mount! substrate container (root fid cells-n))
        mount-cells (cells-text container)
        mount-ok?   (all-at? container 0)]
    (-> (writes-over-orders! substrate container fid
                             (fn [v]
                               (let [mech (if (odd? v) :replace-app-db :dispatch-sync)]
                                 [mech (write-fn fid v mech)])))
        (.then (fn [results]
                 ;; RECORDED, never swallowed. This arm runs BEFORE nothing
                 ;; and AFTER the raw control, in one page, against one
                 ;; document — an unmount that threw leaves this arm's React
                 ;; root, its `reg-view` boundaries and their subscriptions
                 ;; standing, and the next page's arms would be certified on
                 ;; a contaminated document. The container is detached either
                 ;; way; the record is what makes it fatal. A NORMAL return is
                 ;; not taken at its word either (rf2-z3vlz, from the PR #7291
                 ;; audit): `rf.bench.hicasso.lane/container-released!` reads the container
                 ;; before it goes, exactly as `rf.bench.hicasso.lane/release!` does.
                 (when (try (unmount handle)
                            true
                            (catch :default e
                              (rf.bench.hicasso.lane/teardown-failure! "z3vlz subs-arm unmount" e)))
                   (rf.bench.hicasso.lane/container-released! "z3vlz subs-arm unmount" container))
                 (.remove container)
                 {:mount    {:ok? mount-ok? :cells mount-cells}
                  :orders   (tally results)
                  :teardown (rf.bench.hicasso.lane/drain-teardown-failures!)
                  :failed   (vec (take 3 (remove :ok? results)))})))))

(defn- run-raw-control!
  "THE POSITIVE CONTROL, in the same bundle and the same harness: a plain
  substrate component reading a plain substrate atom, with no re-frame
  anywhere on the path.

  Its job is to make a negative result mean something. If the subs arm
  fails while this passes, the substrate's reactivity and this harness's
  drain + read-back both work and the finding is on the re-frame side. If
  both fail, the finding is the harness or the engine, and nothing about
  re-frame has been shown — which is exactly what this control said on the
  first run of this rig, when an unflushed mount was making every arm look
  inert."
  [{:keys [unmount raw-element raw-write!] :as substrate}]
  (raw-write! 0)
  (let [container (rf.bench.hicasso.lane/fresh-container!)
        handle    (mount! substrate container (raw-element))
        mount-ok? (all-at? container 0)]
    (-> (writes-over-orders! substrate container nil
                             (fn [v] [nil (fn [] (raw-write! v))]))
        (.then (fn [results]
                 ;; The control runs FIRST and the arm under test follows it
                 ;; on the same document, so a swallowed failure here is the
                 ;; worse of the two: the arm whose result the bead turns on
                 ;; would be measured on a page still carrying the control's
                 ;; roots and its ratom's watchers. A NORMAL return is not
                 ;; taken at its word either (rf2-z3vlz, from the PR #7291
                 ;; audit): `rf.bench.hicasso.lane/container-released!` reads the container
                 ;; before it goes, exactly as `rf.bench.hicasso.lane/release!` does.
                 (when (try (unmount handle)
                            true
                            (catch :default e
                              (rf.bench.hicasso.lane/teardown-failure! "z3vlz raw-control unmount" e)))
                   (rf.bench.hicasso.lane/container-released! "z3vlz raw-control unmount" container))
                 (.remove container)
                 {:mount    {:ok? mount-ok?}
                  :orders   (tally results)
                  :teardown (rf.bench.hicasso.lane/drain-teardown-failures!)})))))

;; ---------------------------------------------------------------------------
;; The gate payload — the same figures, in a shape a driver can ADJUDICATE
;; ---------------------------------------------------------------------------
;;
;; `rf.bench.hicasso.lane/record!` parks EDN strings, which are for a reader. A driver that
;; had to scrape them with regexes would be a second, drifting expression
;; of the same arithmetic — and a regex that stopped matching would report
;; `?` and pass, which is the fail-open shape this rig is being repaired
;; for. So the probe publishes its own figures once more as a flat JS
;; record with no `?` in any key.
;;
;; A LITERAL `#js` object, deliberately: `clj->js` renders `:ok?` as the
;; key `"ok?"`, and `p0-heap/mount!`'s docstring records the run where a
;; driver reading `verify.ok` saw `undefined` and reported every mount
;; unverified while every mount was fine. `:by-rung`'s keys carry no `?`
;; and are converted.

(defn- order-gate
  "One order's figures. `dbFollowedAll` is `null` when the arm has no
  app-db to witness (the raw control) and a BOOLEAN otherwise — so the
  driver can require `true` of the subs arm and `null` of the control, and
  a slot that went missing reads as a refusal rather than as a pass."
  [orders k]
  (let [o (get orders k)]
    #js {"of"            (:of o)
         "unverified"    (:unverified o)
         "rungs"         (clj->js (:by-rung o))
         "dbFollowedAll" (:db-followed-all? o)}))

(defn- arm-gate [arm]
  #js {"mountOk"  (boolean (:ok? (:mount arm)))
       "teardown" (clj->js (mapv (fn [f] (str (:where f) ": " (:error f)))
                                 (:teardown arm)))
       "orders"   #js {"inside-drain"      (order-gate (:orders arm) :inside-drain)
                       "drain-immediately" (order-gate (:orders arm) :drain-immediately)
                       "yield-then-drain"  (order-gate (:orders arm) :yield-then-drain)}})

(defn- publish-gates!
  "Park the adjudicable record on `window.Z3VLZ_GATES`. Absent means the
  probe did not get this far, and the driver treats that as a failure —
  a page that produced no gates cannot have its contract checked, and an
  unchecked contract is the thing being repaired."
  [control subs]
  (set! (.-Z3VLZ_GATES js/window)
        #js {"raw" (arm-gate control) "subs" (arm-gate subs)})
  nil)

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- run-chain!
  [substrate {:keys [bundle installed compiled-in]} fid]
  (-> (run-raw-control! substrate)
      (.then (fn [control]
               (rf.bench.hicasso.lane/record! :raw-control control)
               (-> (run-subs-arm! substrate fid)
                   (.then (fn [subs] [control subs])))))
      (.then (fn [[control subs]]
               (rf.bench.hicasso.lane/record! :subs-arm subs)
               (publish-gates! control subs)
               (let [o (:orders subs)
                     un (fn [k] (get-in o [k :summary]))]
                 (rf.bench.hicasso.lane/record! :verdict
                   {:bundle         bundle
                    :installed      installed
                    :compiled-in    compiled-in
                    :mount-correct? (:ok? (:mount subs))
                    ;; REACTIVE means: under the substrate's OWN documented
                    ;; drain contract — the write inside `flush-render!` — the
                    ;; DOM followed every write. That is the question the bead
                    ;; asks about the ADAPTER. The `:yield-then-drain` row
                    ;; beside it is the question about the HARNESS.
                    :reactive?      (zero? (:unverified (:inside-drain o)))
                    :unverified-of  {:inside-drain      (un :inside-drain)
                                     :drain-immediately (un :drain-immediately)
                                     :yield-then-drain  (un :yield-then-drain)}
                    :arm-order-guard
                    (str "NOT ENGAGED — this run publishes no timed figure and no "
                         "arm-to-arm ratio, so there is nothing for the guard to "
                         "adjudicate. The only numbers here are write and DOM "
                         "read-back counts.")}))
               (rf.bench.hicasso.lane/done!)
               nil))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (str "the probe threw: " (.-message e) "\n" (.-stack e)))
                (rf.bench.hicasso.lane/done!)
                nil))))

(defn run-probe!
  "Run the probe for one bundle. `substrate` is
  `{:label :create-root :render :unmount :drain! :raw-element :raw-write!}`;
  `manifest` describes the bundle so the record is self-describing.

  Answers a promise of the whole record and parks it under
  `window.HICASSO_RESULTS` via [[re-frame.bench.hicasso.lane/record!]]."
  [substrate manifest fid]
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/record! :manifest (assoc manifest :runtime (rf.bench.hicasso.lane/runtime-label)
                                          :cells cells-n :writes writes-n))
  ;; SYNCHRONOUS throws get the same treatment as rejections. A mount that
  ;; throws before the first `.then` would otherwise escape the chain
  ;; entirely, leaving `HICASSO_DONE` unset and the driver waiting out its
  ;; whole sentinel on what is really a one-line stack.
  (try
    (run-chain! substrate manifest fid)
    (catch :default e
      (rf.bench.hicasso.lane/fail! (str "the probe threw synchronously: " (.-message e) "\n" (.-stack e)))
      (rf.bench.hicasso.lane/done!)
      nil)))
