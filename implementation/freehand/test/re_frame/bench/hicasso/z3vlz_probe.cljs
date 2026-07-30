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
  (:require [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.frame :as frame])
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

(rf/reg-event-db :z3vlz/set-all (fn [db [_ v]] (assoc db :cells (vec (repeat cells-n v)))))

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
  (mapv (fn [i] (lane/text-at container i)) (range cells-n)))

(defn- all-at?
  "EVERY cell, not one. A stale page can still hold one fresh cell from a
  previous write, so a single-probe check verifies almost nothing."
  [container v]
  (let [want (str v)]
    (every? (fn [i] (= want (lane/text-at container i))) (range cells-n))))

;; ---------------------------------------------------------------------------
;; One verified write, with the escalation ladder
;; ---------------------------------------------------------------------------

(defn- verified-write!
  "Perform one write, then chase it through progressively more generous
  drains until the DOM shows it or the ladder is exhausted.

  Answers a promise of
  `{:v :mechanism :db-followed? :ok? :rung :cells}` where `:rung` is the
  first drain that got the value onto the page — `:sync` (the substrate's
  own drain after one microtask, which is what an application gets),
  `:redrain`, `:macrotask`, `:raf2` — or `:never`.

  The rungs past `:sync` are diagnostic ONLY. A value that needs a
  macrotask is a LATE re-render, which is a different (and far milder)
  finding from a re-render that never happens; collapsing the two would
  lose the distinction the bead turns on."
  [{:keys [drain!]} container fid v mechanism write!]
  (write!)
  (let [db-followed? (= (vec (repeat cells-n v))
                        (:cells (frame/frame-app-db-value fid)))
        rung         (volatile! nil)]
    (-> (microtask)
        (.then (fn [_]
                 (drain!)
                 (when (all-at? container v) (vreset! rung :sync))))
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
                 {:v            v
                  :mechanism    mechanism
                  :db-followed? db-followed?
                  :ok?          (= :sync @rung)
                  :rung         (or @rung :never)
                  :cells        (when-not (= :sync @rung) (cells-text container))})))))

;; ---------------------------------------------------------------------------
;; The two write mechanisms the bead reproduced with
;; ---------------------------------------------------------------------------

(defn- write-fn
  "Alternate `frame/replace-app-db!` and a registered event through
  `dispatch-sync`. The bead reproduced with BOTH, so a probe that used one
  would leave the other unanswered."
  [fid v mechanism]
  (case mechanism
    :replace-app-db (fn [] (frame/replace-app-db! fid (seed-db v)))
    :dispatch-sync  (fn [] (rf/dispatch-sync [:z3vlz/set-all v] {:frame fid}))))

;; ---------------------------------------------------------------------------
;; One arm
;; ---------------------------------------------------------------------------

(defn- mount!
  [{:keys [create-root render]} container tree]
  (let [r (create-root container)]
    (render r tree)
    r))

(defn- tally [results]
  {:of         (count results)
   :unverified (count (remove :ok? results))
   :by-rung    (frequencies (map :rung results))
   :by-mechanism
   (into {} (map (fn [[m rs]]
                   [m {:of (count rs) :unverified (count (remove :ok? rs))}]))
         (group-by :mechanism results))
   :db-followed-all? (every? :db-followed? results)})

(defn- run-subs-arm!
  "The arm under test: `reg-view` boundaries reading `rf/subscribe`,
  mounted through the substrate's own door, written through re-frame."
  [{:keys [unmount] :as substrate} fid]
  (rf/make-frame {:id fid})
  (frame/replace-app-db! fid (seed-db 0))
  (let [container (lane/fresh-container!)
        handle    (mount! substrate container (root fid cells-n))
        mount-cells (cells-text container)
        mount-ok?   (all-at? container 0)]
    (-> (lane/chain []
                    (range 1 (inc writes-n))
                    (fn [acc v]
                      (let [mech (if (odd? v) :replace-app-db :dispatch-sync)]
                        (-> (verified-write! substrate container fid v mech
                                             (write-fn fid v mech))
                            (.then (fn [r] (conj acc r)))))))
        (.then (fn [results]
                 (try (unmount handle) (catch :default _ nil))
                 (.remove container)
                 {:mount  {:ok? mount-ok? :cells mount-cells}
                  :writes (tally results)
                  :detail (mapv #(dissoc % :cells) results)
                  :failed (vec (take 3 (remove :ok? results)))})))))

(defn- run-raw-control!
  "THE POSITIVE CONTROL, in the same bundle and the same harness: a plain
  substrate component reading a plain substrate atom, with no re-frame
  anywhere on the path.

  Its job is to make a negative result mean something. If the subs arm
  fails while this passes, the substrate's reactivity and this harness's
  drain + read-back both work and the finding is on the re-frame side. If
  both fail, the finding is the harness or the engine, and nothing about
  re-frame has been shown."
  [{:keys [unmount raw-element raw-write!] :as substrate}]
  (raw-write! 0)
  (let [container (lane/fresh-container!)
        handle    (mount! substrate container (raw-element))
        mount-ok? (all-at? container 0)]
    (-> (lane/chain []
                    (range 1 (inc writes-n))
                    (fn [acc v]
                      (-> (verified-write! substrate container nil v :raw-atom
                                           (fn [] (raw-write! v)))
                          (.then (fn [r] (conj acc (dissoc r :db-followed?)))))))
        (.then (fn [results]
                 (try (unmount handle) (catch :default _ nil))
                 (.remove container)
                 {:mount  {:ok? mount-ok?}
                  :writes (dissoc (tally results) :db-followed-all? :by-mechanism)})))))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn run!
  "Run the probe for one bundle. `substrate` is
  `{:label :create-root :render :unmount :drain! :raw-element :raw-write!}`;
  `manifest` describes the bundle so the record is self-describing.

  Answers a promise of the whole record and parks it under
  `window.HICASSO_RESULTS` via [[re-frame.bench.hicasso.lane/record!]]."
  [substrate {:keys [bundle installed compiled-in] :as manifest} fid]
  (lane/leave-act-environment!)
  (lane/record! :manifest (assoc manifest :runtime (lane/runtime-label)
                                          :cells cells-n :writes writes-n))
  (-> (run-raw-control! substrate)
      (.then (fn [control]
               (lane/record! :raw-control control)
               (run-subs-arm! substrate fid)))
      (.then (fn [subs]
               (lane/record! :subs-arm subs)
               (lane/record! :verdict
                 {:bundle        bundle
                  :installed     installed
                  :compiled-in   compiled-in
                  :mount-correct? (:ok? (:mount subs))
                  :reactive?     (zero? (:unverified (:writes subs)))
                  :unverified-of (str (:unverified (:writes subs)) " unverified of "
                                      (:of (:writes subs)))
                  :arm-order-guard
                  (str "NOT ENGAGED — this run publishes no timed figure and no "
                       "arm-to-arm ratio, so there is nothing for the guard to "
                       "adjudicate. The only numbers here are write and DOM "
                       "read-back counts.")})
               (lane/done!)
               nil))
      (.catch (fn [e]
                (lane/fail! (str "the probe threw: " (.-message e) "\n" (.-stack e)))
                (lane/done!)
                nil))))
