(ns day8.re-frame2-xray.panels.epoch.hard-machine-fidelity-cljs-test
  "Rendering-FIDELITY assertions for the canonical HARD machine
  (`:hvac/controller`, the `machine_epochs` testbed's MACHINE 4 — rf2-k08ay).

  ## Why this test exists — the gap it closes

  The no-op render bug (rf2-e6q97 · #2841) was a devtools-NARRATION miss: the
  engine semantics were correct and unit-tested, but Xray rendered them
  misleadingly (a spurious `{X}→{X}` 0-microstep transition row beside the
  benign no-op). Our machine tests assert what the ENGINE PRODUCES; nothing
  asserted what the TOOL DISPLAYS. This test closes that thin spot.

  It is the COMPLEMENT to the SCXML semantic corpus (rf2-rkkag · #2842 —
  `re_frame.scxml_conformance_cljs_test`): that proves the engine's
  SEMANTICS (external self-transition fires exit+entry, internal fires
  action-only, the LCA cascade orders deepest-exit-first / shallowest-entry-
  first); THIS proves the DEVTOOLS RENDER those semantics legibly. Different
  layers.

  ## What it drives + asserts

  It registers the SAME hard machine the testbed mounts (a self-contained
  copy so the test is independent of the testbed build), drives it through
  the LIVE machine substrate (`reg-machine` / `dispatch-sync` — the surface
  real apps use), captures the trace stream Xray's ring buffer recorded, and
  feeds it through the rendering layers that FEED the devtools render:

    - `proj/machine-cascade-rows` — the Epoch panel's per-epoch machine
      cascade (the exit/action/entry rows; the canonical sort; the
      no-op-transition suppression).
    - `mih/project-focused-event-transitions` — the Machine Inspector's
      focused-event view-model (one record per transition; parallel → ≥2).
    - `topo/parse-definition` — the inspector's topology projection
      (compound nesting + parallel regions rendered legibly).
    - `diff/project` — the snapshot diff (member-level set diff for `:tags`,
      rf2-l0us2).

  Asserting the projected ROWS / LABELS / records are unambiguous is the
  testable proxy for 'the devtools render legibly' — this is a Causa/Story-
  style CLJS unit test (per `feedback_causa_story_cljs_unit_tests_not_playwright`),
  NOT a Playwright spec. The render facts are pinned against REALITY: drive
  the substrate, read what the projection produces.

  ## The four hard cases (one coherent machine)

    1. DEEP COMPOUND NESTING — `:climate` region four levels deep
       (`[:running :conditioning :heating]`).
    2. PARALLEL / ORTHOGONAL REGIONS — `:type :parallel`; `:hvac/power-cycle`
       handled by BOTH `:climate` and `:fan` simultaneously.
    3. ALL ACTION KINDS WITH OBSERVABLE LCA ORDERING — every exit/action/entry
       appends a `<phase>:<state>` tag to a shared `:trail`; the cascade
       order is the trail order.
    4. INTERNAL vs EXTERNAL SELF-TRANSITIONS — external (`:target :same-state`
       + `:reenter? true`, rf2-eicq0) fires exit+entry; internal (omit
       `:target`, or a self-target without `:reenter?`) fires action-only;
       neither emits a spurious no-op transition row (#2841)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.test-helpers :as th]
            [day8.re-frame2-xray.diff.engine :as diff]
            [day8.re-frame2-xray.panels.epoch.format :as fmt]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.epoch.view :as view]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as mih]
            [day8.re-frame2-xray.panels.machines.topology :as topo]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ============================================================================
;; The hard machine — a self-contained copy of the testbed's :hvac/controller.
;; ============================================================================
;;
;; Kept verbatim-equivalent to `machine_epochs/core.cljs` MACHINE 4 (the
;; trail-action factory + the parallel/compound/self-transition shape). The
;; testbed's copy is what an operator SEES; this copy is what the test DRIVES.
;; A drift between them is acceptable only if the rendered facts asserted
;; below still hold — those facts are the contract, not the literal source.

(defn- trail-action
  "Append one labeled tag to `[:data :trail]` — the cascade-order recorder.
  `label` is `<phase>:<state>`."
  [nm label]
  (with-meta
    (fn [{data :data}]
      {:data (update data :trail (fnil conj []) label)})
    {:name nm}))

(def hvac-controller-machine
  {:type :parallel
   :data {:trail []}
   :regions
   {:climate
    {:initial :idle
     :states
     {:idle
      {:tags #{:climate/idle}
       :on   {:hvac/power-cycle {:target :running :action :enter-running}}}
      :running
      {:tags    #{:climate/running}
       :initial :conditioning
       :entry   :enter-running-level
       :exit    :exit-running-level
       :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
       :states
       {:conditioning
        {:tags    #{:climate/conditioning}
         :initial :heating
         :entry   :enter-conditioning
         :exit    :exit-conditioning
         :states
         {:heating
          {:tags  #{:climate/heating}
           :entry :enter-heating
           :exit  :exit-heating
           :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}
          :cooling
          {:tags  #{:climate/cooling}
           :entry :enter-cooling
           :exit  :exit-cooling
           :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}
    :fan
    {:initial :off
     :states
     {:off
      {:tags #{:fan/off}
       :on   {:hvac/power-cycle {:target :on :action :fan-on}}}
      :on
      {:tags  #{:fan/on}
       :entry :enter-fan-on
       :exit  :exit-fan-on
       :on    {:hvac/power-cycle {:target :off :action :fan-off}
               ;; :reenter? true — external self-transition (rf2-eicq0 v5 flip)
               :hvac/nudge {:target :same-state :reenter? true :action :nudge-fan}
               :hvac/tweak {:action :tweak-fan}}}}}}
   :actions
   {:enter-running       (trail-action 'enter-running       :action:power-on)
    :enter-running-level (trail-action 'enter-running-level :entry:running)
    :exit-running-level  (trail-action 'exit-running-level  :exit:running)
    :back-to-idle        (trail-action 'back-to-idle        :action:power-off)
    :enter-conditioning  (trail-action 'enter-conditioning  :entry:conditioning)
    :exit-conditioning   (trail-action 'exit-conditioning   :exit:conditioning)
    :enter-heating       (trail-action 'enter-heating       :entry:heating)
    :exit-heating        (trail-action 'exit-heating        :exit:heating)
    :enter-cooling       (trail-action 'enter-cooling       :entry:cooling)
    :exit-cooling        (trail-action 'exit-cooling        :exit:cooling)
    :swap-mode           (trail-action 'swap-mode           :action:swap-mode)
    :fan-on              (trail-action 'fan-on              :action:fan-on)
    :fan-off             (trail-action 'fan-off             :action:fan-off)
    :enter-fan-on        (trail-action 'enter-fan-on        :entry:fan-on)
    :exit-fan-on         (trail-action 'exit-fan-on         :exit:fan-on)
    :nudge-fan           (trail-action 'nudge-fan           :action:nudge)
    :tweak-fan           (trail-action 'tweak-fan           :action:tweak)}})

;; ============================================================================
;; fixture
;; ============================================================================

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn xray-init!}))

(defn- setup! []
  (registry/register-xray-handlers!)
  ;; Activate the trace-collector so machine `trace/emit!` calls land in
  ;; Xray's ring buffer (the same surface the Epoch panel reads).
  (preload/register-trace-collector!)
  (rf/reg-machine :hvac/controller hvac-controller-machine)
  ;; Start to the initial parallel configuration; clear any start trace so
  ;; the per-case capture below contains only that case's macrostep.
  (rf/dispatch-sync [:hvac/controller [:rf.machine/start]]))

(defn- snapshot
  "Read the live snapshot for `:hvac/controller` off the default frame.
  EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state."
  []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/machines :snapshots :hvac/controller]))

(defn- drive!
  "Dispatch one event into the hard machine and return the trace stream it
  produced as an epoch-record-shaped map. Resets the trace buffer first so
  the captured cascade is exactly this macrostep."
  [event-v]
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [:hvac/controller event-v])
  {:event-id     :hvac/controller
   :trigger-event [:hvac/controller event-v]
   :trace-events (vec (trace-collector/buffer-for-test))})

(defn- drive-other!
  "Like `drive!` but for an arbitrary machine event-id (rf2-iu3no — the
  bootstrap-scope guard drives a SECOND machine so the captured macrostep
  is exactly its birth)."
  [machine-id event-v]
  (trace-collector/reset-for-test!)
  (rf/dispatch-sync [machine-id event-v])
  {:event-id      machine-id
   :trigger-event [machine-id event-v]
   :trace-events  (vec (trace-collector/buffer-for-test))})

(defn- cascade [record]
  (proj/machine-cascade-rows (:trace-events record)))

(defn- rows-of-kind [rows kind]
  (filterv #(= kind (:kind %)) rows))

;; rf2-52u5n — the STRUCTURED transition cascade now rides the LIVE
;; `:rf.machine/transition` trace (rf2-n9f4z); the transition cascade row
;; threads it through. These read it back off the projected row so the
;; fidelity test pins what the EVENT HANDLER render shows.
(defn- structured-cascade-of [record]
  (-> (cascade record) (rows-of-kind :transition) first :cascade))

;; ============================================================================
;; CASE 2 — PARALLEL regions: one event, BOTH regions render
;; ============================================================================

(deftest power-cycle-renders-parallel-broadcast-legibly
  (testing "rf2-k08ay case 2 — `:hvac/power-cycle` is handled by BOTH the
            `:climate` and `:fan` regions in ONE macrostep. A parallel machine
            commits ONE snapshot per macrostep, so the Epoch cascade renders a
            SINGLE aggregate transition row — but its before/after `:state` is
            a region→state MAP that shows BOTH regions moved, and the action
            cascade carries action rows from BOTH regions. That is how the
            operator reads 'one event, both regions advanced'; the chart
            highlights an active leaf in each region. Pinned against the LIVE
            substrate."
    (setup!)
    (let [record (drive! [:hvac/power-cycle])
          rows   (cascade record)
          tx     (rows-of-kind rows :transition)
          tx-row (first tx)
          action-ids (set (map :action-id (rows-of-kind rows :action)))
          ;; the inspector view-model the chart + focused-lens consume
          inspector (mih/project-focused-event-transitions
                      (:trace-events record)
                      {:hvac/controller hvac-controller-machine})]
      ;; ONE aggregate transition row — the parallel macrostep commits once.
      (is (= 1 (count tx))
          "a parallel machine renders ONE aggregate transition row per
           macrostep (one snapshot commit)")
      ;; …whose before/after `:state` is a region→state MAP showing BOTH
      ;; regions moved — the legible parallel render the chart highlights.
      (is (= {:climate :idle :fan :off} (:from-state tx-row))
          "the transition's FROM renders both regions' prior leaves")
      (is (= {:climate [:running :conditioning :heating] :fan :on}
             (:to-state tx-row))
          "the transition's TO renders both regions' new leaves — climate
           descended its full initial cascade to the deepest leaf, fan swung
           to :on, in one event")
      ;; Action rows from BOTH regions surface in the cascade (the broadcast
      ;; hit both): climate's :fan-on counterpart + fan's entry.
      (is (contains? action-ids :enter-heating)
          "climate region's deep entry action renders in the cascade")
      (is (contains? action-ids :enter-fan-on)
          "fan region's entry action renders in the cascade — proof the one
           event broadcast to both regions")
      ;; No `:no-op` row — both regions handled the event (a genuine parallel
      ;; broadcast is NOT an unhandled no-op).
      (is (empty? (rows-of-kind rows :no-op))
          "a handled parallel broadcast renders NO benign-no-op notice")
      ;; The inspector's focused-event lens projects the macrostep's single
      ;; transition record — its :after snapshot carries BOTH regions so the
      ;; chart highlights a leaf in each.
      (is (= 1 (count inspector))
          "the inspector projects the macrostep's single transition record")
      (is (= {:climate [:running :conditioning :heating] :fan :on}
             (get-in (first inspector) [:after :state]))
          "the inspector record's :after snapshot carries both regions' leaves
           — the chart highlights an active leaf in EACH region")
      ;; The live configuration landed in both regions' target leaves.
      (is (= {:climate [:running :conditioning :heating] :fan :on}
             (:state (snapshot)))
          "the committed snapshot moved both regions in one macrostep"))))

;; ============================================================================
;; rf2-52u5n — the STRUCTURED entry/exit cascade renders legibly under
;; EVENT HANDLER (the headline render this bead adds)
;; ============================================================================

(deftest power-cycle-renders-structured-cascade-not-just-summary
  (testing "rf2-52u5n — the `[:hvac/power-cycle]` macrostep projects the
            ORDERED structured cascade (exit/action/entry steps + per-region)
            matching the engine's actual cascade — NOT just `{from}→{to} +
            count`. This is the gap rf2-52u5n closes: pre-bead, the operator
            saw one opaque row + '0 microsteps' with no sign of the 8-step
            entry cascade. Pinned against the LIVE substrate (rf2-n9f4z emits
            the `:cascade` tag; the projection threads it through)."
    (setup!)
    (let [record    (drive! [:hvac/power-cycle])
          structured (structured-cascade-of record)
          regions    (proj/cascade-regions structured)]
      ;; The structured cascade is present + non-empty (RED before n9f4z +
      ;; this bead: the transition row had only :before/:after/:microsteps).
      (is (vector? structured) "the structured :cascade rides the transition row")
      (is (seq structured) "the cascade is non-empty (not just a count)")
      ;; It is a COMPLETE configuration walk — the action-free :idle / :off
      ;; exits the per-EMIT stream cannot show (they declare no :exit action).
      (is (proj/parallel-cascade? structured)
          "the cascade carries both regions (parallel broadcast)")
      (is (= [:climate :fan] (mapv :region regions))
          "regions group in declaration order — climate before fan")
      (let [climate (:steps (first regions))
            fan     (:steps (second regions))]
        (is (= [:exit :action :entry :entry :entry] (mapv :kind climate))
            ":climate — action-free :idle exit → action @ LCA → 3-level descent")
        (is (= [nil :enter-running :enter-running-level :enter-conditioning :enter-heating]
               (mapv :action climate))
            ":climate action-ids in cascade order (leading nil = action-free exit)")
        (is (= [[:idle] [:idle] [:running] [:running :conditioning]
                [:running :conditioning :heating]]
               (mapv :state climate))
            ":climate state paths render the deep-compound descent")
        (is (= [:exit :action :entry] (mapv :kind fan))
            ":fan — action-free :off exit → action → single entry")
        (is (= [nil :fan-on :enter-fan-on] (mapv :action fan))
            ":fan action-ids in cascade order"))
      ;; The per-step :data delta is the minimal contribution (the trail key
      ;; only) — proof the render shows what each step changed, not the whole
      ;; :data map.
      (let [enter-heating (->> (:steps (first regions))
                               (filter #(= :enter-heating (:action %)))
                               first)]
        (is (contains? (:data-delta enter-heating) :trail)
            "the entry step carries its :data delta (the trail key)"))
      ;; rf2-akvfe — the VIEW no longer renders the up/down structured-cascade
      ;; BLOCK inside the transition row (it duplicated the EVENT HANDLER
      ;; pipeline). The structured `:cascade` stays the ORDER ORACLE for the
      ;; projection assertions above; the per-EMIT exit/entry ACTION rows ARE
      ;; the canonical pipeline render, and the up/down block testid is GONE.
      ;; The transition body embeds edn-inspectors that subscribe against the
      ;; surrounding frame — render under `:rf/xray`.
      (frame/reg-frame :rf/xray {})
      (let [rows   (cascade record)
            tx-row (first (rows-of-kind rows :transition))
            sp     (str "rf-xray-epoch-machine-cascade-structured-" (:step tx-row))
            tree   (rf/with-frame :rf/xray
                     (view/render-handler-step
                       {:step :handler :badge :HANDLER :step-number 3
                        :flavour :reg-machine :event-id :hvac/controller
                        :fx [] :machine {:cascade rows
                                         :transition nil :guards []
                                         :lifecycle [] :timers []}}))]
        (is (nil? (th/find-by-testid tree sp))
            "rf2-akvfe — the up/down structured-cascade block no longer renders")
        ;; rf2-2hj0h item 2 — the akvfe nested-pipeline RAIL is REMOVED; the
        ;; rows render as a flat numbered stack (the ordinal chips carry the
        ;; pipeline reading). The rows host + the orientation line remain.
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-machine-cascade-rail"))
            "the nested-pipeline rail is removed (rf2-2hj0h)")
        (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-cascade-rows"))
            "the flat rows host still renders")
        (is (some? (th/find-by-testid tree "rf-xray-epoch-event-handler-orientation"))
            "the EVENT HANDLER orientation line renders")
        ;; No-info-loss: the per-EMIT exit/entry action rows survive and carry
        ;; their action verbs (the cascade the removed block restated).
        (is (some? (th/find-by-testid tree (str "rf-xray-epoch-machine-cascade-row-"
                                                 (:step tx-row))))
            "the transition row survives in the pipeline")))))

;; ============================================================================
;; CASE 1 + 3 — DEEP COMPOUND nesting + the multi-level LCA cascade ORDER
;; ============================================================================

(deftest mode-toggle-renders-lca-cascade-in-canonical-order
  (testing "rf2-k08ay cases 1+3 — `:hvac/mode-toggle` (`:heating` → `:cooling`)
            crosses the LCA `:conditioning`. Per Spec 005 §Level 2 the action
            group fires exit (deepest-first) → transition `:action` @ LCA →
            entry (shallowest-first). The Epoch cascade RE-SORTS rows into the
            canonical `guard → exit → TRANSITION → entry` rank (rf2-tjqd8); the
            rendered order must read as the statechart does. The shared
            `:trail` is that order made visible — the snapshot `:data` Δ the
            panel renders shows it directly."
    (setup!)
    ;; Get into :heating first (power-cycle descends the initial cascade).
    (drive! [:hvac/power-cycle])
    (let [record (drive! [:hvac/mode-toggle])
          rows   (cascade record)
          ;; the rendered cascade order, by kind+phase
          rendered (mapv (fn [r] [(:kind r) (:phase r)]) rows)
          tx-idx   (->> rendered (keep-indexed (fn [i kp] (when (= :transition (first kp)) i))) first)
          exit-idxs  (->> rendered (keep-indexed (fn [i [k p]] (when (and (= :action k) (= :exit p)) i))))
          entry-idxs (->> rendered (keep-indexed (fn [i [k p]] (when (and (= :action k) (= :entry p)) i))))]
      ;; Structural render order: every exit row sorts BEFORE the transition
      ;; row, every entry row sorts AFTER it (the canonical statechart read).
      (is (some? tx-idx) "a transition row renders")
      (when (and tx-idx (seq exit-idxs))
        (is (every? #(< % tx-idx) exit-idxs)
            "exit rows render BEFORE the transition row (leave the old state first)"))
      (when (and tx-idx (seq entry-idxs))
        (is (every? #(> % tx-idx) entry-idxs)
            "entry rows render AFTER the transition row (enter the new state last)"))
      ;; The trail (the snapshot :data Δ) is the cascade order, made legible.
      ;; mode-toggle's macrostep appends exactly: exit:heating, action:swap-mode,
      ;; entry:cooling (LCA :conditioning is NOT exited/entered — it stays
      ;; active, so :exit-conditioning / :enter-conditioning do NOT fire).
      (let [trail-before (get-in (snapshot) [:data :trail])]
        ;; snapshot is already post-toggle; recompute the per-macrostep delta
        ;; off the transition row's :data-before → :data-after.
        (let [tx-row     (first (rows-of-kind rows :transition))
              data-before (:data-before tx-row)
              data-after  (:data-after tx-row)
              delta       (vec (drop (count (:trail data-before)) (:trail data-after)))]
          (is (= [:exit:heating :action:swap-mode :entry:cooling] delta)
              "the macrostep's trail delta IS the LCA cascade order: exit the
               deepest leaf → the transition action at the LCA → enter the new
               leaf. :conditioning (the LCA) stays active, so its exit/entry do
               NOT fire.")
          (is (vector? trail-before)))))
    ;; The live configuration toggled to :cooling under the same compound path.
    (is (= [:running :conditioning :cooling] (:climate (:state (snapshot))))
        "the deep compound leaf moved heating → cooling, parent path intact")))

(deftest topology-projection-renders-compound-and-parallel-legibly
  (testing "rf2-k08ay case 1 — the Machine Inspector's topology projection
            (`topo/parse-definition`, the layer that feeds the chart) must
            render the deep compound nesting AND the parallel regions without
            contradiction: every region's leaves appear, the deepest compound
            level is reachable, and the parallel root has no single initial
            path (each region carries its own)."
    (let [{:keys [nodes edges initial-path]} (topo/parse-definition hvac-controller-machine)
          paths (set (map :path nodes))]
      ;; Parallel root → no single initial path (each region owns its own).
      (is (nil? initial-path)
          "a parallel machine has no single initial path — the chart shows
           each region's own initial leaf")
      ;; Both regions' leaves are present, REGION-QUALIFIED (rf2-uo0rc.4):
      ;; each region's states are projected under its region-id prefix, so
      ;; same-named cross-region states stay collision-free and the region
      ;; grouping is addressable. The :climate region's leaves carry the
      ;; [:climate ...] prefix; the :fan region's carry [:fan ...].
      (is (contains? paths [:climate :idle])    "climate :idle leaf rendered")
      (is (contains? paths [:climate :running]) "climate :running compound rendered")
      (is (contains? paths [:climate :running :conditioning])
          "the mid compound level rendered")
      (is (contains? paths [:climate :running :conditioning :heating])
          "the DEEPEST compound leaf rendered — the four-level path is legible")
      (is (contains? paths [:climate :running :conditioning :cooling])
          "its sibling leaf rendered")
      (is (contains? paths [:fan :off]) "fan :off leaf rendered")
      (is (contains? paths [:fan :on])  "fan :on leaf rendered")
      ;; The compound parent is flagged compound so the chart nests it.
      (let [running (first (filter #(= [:climate :running] (:path %)) nodes))
            heating (first (filter #(= [:climate :running :conditioning :heating] (:path %)) nodes))]
        (is (:compound? running) ":running renders as a compound (nestable) node")
        (is (not (:compound? heating)) ":heating renders as a leaf"))
      ;; The mode-toggle edge between the deep leaves is present + labeled,
      ;; both endpoints region-qualified within :climate.
      (is (some (fn [e] (and (= [:climate :running :conditioning :heating] (:from e))
                             (= [:climate :running :conditioning :cooling] (:to e))
                             (= :hvac/mode-toggle (:event e))))
                edges)
          "the heating→cooling toggle edge renders with its event label"))))

;; ============================================================================
;; CASE 4 — INTERNAL vs EXTERNAL self-transitions (the case #2843 fixed)
;; ============================================================================

(deftest external-self-transition-renders-exit-and-entry
  (testing "rf2-k08ay case 4 (external) — `:hvac/nudge` is an external
            self-transition (`:target :same-state` + `:reenter? true`). Per
            Spec 005 §Self-transitions (rf2-46ban + rf2-eicq0: external is now
            the `:reenter?` opt-in) it re-enters the state: `:exit`
            THEN action THEN `:entry` fire, configuration unchanged. The Epoch
            cascade must render BOTH an exit row and an entry row (so the
            operator sees the re-entry), and — critically per rf2-e6q97
            (#2841) — must NOT render a spurious `{:on}→{:on}` no-op transition
            row (a genuine self-transition is a REAL transition, not an
            unhandled no-op)."
    (setup!)
    (drive! [:hvac/power-cycle]) ; fan → :on
    (let [record (drive! [:hvac/nudge])
          rows   (cascade record)
          exit-rows  (filterv #(and (= :action (:kind %)) (= :exit (:phase %))) rows)
          entry-rows (filterv #(and (= :action (:kind %)) (= :entry (:phase %))) rows)]
      (is (seq exit-rows)
          "external self-transition renders an EXIT row (the state is left)")
      (is (seq entry-rows)
          "external self-transition renders an ENTRY row (the state is re-entered)")
      (is (empty? (rows-of-kind rows :no-op))
          "a genuine self-transition is NOT a no-op — no benign-no-op notice")
      ;; The trail delta for this macrostep is exit → action → entry (the
      ;; re-entry made visible) — the foil to the internal case below.
      (let [tx-row      (first (rows-of-kind rows :transition))
            data-before (:data-before tx-row)
            data-after  (:data-after tx-row)
            delta       (vec (drop (count (:trail data-before)) (:trail data-after)))]
        (is (= [:exit:fan-on :action:nudge :entry:fan-on] delta)
            "external self-transition's trail delta: exit → action → entry —
             the state re-enters itself"))
      ;; Configuration unchanged (still :on), even though exit+entry fired.
      (is (= :on (:fan (:state (snapshot))))
          "external self-transition leaves the configuration at :on"))))

(deftest internal-self-transition-renders-action-only
  (testing "rf2-k08ay case 4 (internal) — `:hvac/tweak` omits `:target`: an
            internal self-transition. Per Spec 005 the action runs but `:exit`
            / `:entry` do NOT. The Epoch cascade must render the action row and
            NO exit/entry rows (the foil to `:hvac/nudge`), and — per rf2-e6q97
            (#2841) — NO spurious no-op transition row. The two self-transition
            renders together pin the distinction the wave debated."
    (setup!)
    (drive! [:hvac/power-cycle]) ; fan → :on
    (let [record (drive! [:hvac/tweak])
          rows   (cascade record)
          action-rows (filterv #(and (= :action (:kind %)) (= :transition (:phase %))) rows)
          exit-rows   (filterv #(and (= :action (:kind %)) (= :exit (:phase %))) rows)
          entry-rows  (filterv #(and (= :action (:kind %)) (= :entry (:phase %))) rows)]
      (is (seq action-rows)
          "internal self-transition renders the transition ACTION row")
      (is (empty? exit-rows)
          "internal self-transition renders NO exit row (the state is not left)")
      (is (empty? entry-rows)
          "internal self-transition renders NO entry row (the state is not re-entered)")
      (is (empty? (rows-of-kind rows :no-op))
          "internal self-transition is a REAL transition — no benign-no-op notice")
      ;; The trail delta is the action ONLY — no exit/entry tags.
      (let [tx-row      (first (rows-of-kind rows :transition))
            data-before (:data-before tx-row)
            data-after  (:data-after tx-row)
            delta       (vec (drop (count (:trail data-before)) (:trail data-after)))]
        (is (= [:action:tweak] delta)
            "internal self-transition's trail delta: the action ONLY — the
             distinction from `:hvac/nudge`'s exit→action→entry"))
      (is (= :on (:fan (:state (snapshot))))
          "internal self-transition leaves the configuration at :on"))))

(deftest neither-self-transition-emits-spurious-no-op-transition-row
  (testing "rf2-k08ay / rf2-e6q97 (#2841) regression guard — the no-op
            transition-row suppression must NOT over-fire: a GENUINE self-
            transition (external or internal) carries a real transition row,
            so the suppression (which drops the substrate's unconditional
            `{X}→{X}` emit ONLY when a `:no-op` row is present) must leave the
            transition row intact. Both self-transitions render exactly one
            transition row and zero no-op rows — the inverse of the unhandled-
            event case the suppression targets."
    (setup!)
    (drive! [:hvac/power-cycle])
    (doseq [ev [[:hvac/nudge] [:hvac/tweak]]]
      (let [rows (cascade (drive! ev))]
        (is (= 1 (count (rows-of-kind rows :transition)))
            (str ev " renders exactly ONE transition row (a genuine self-"
                 "transition is a real transition — the row survives)"))
        (is (empty? (rows-of-kind rows :no-op))
            (str ev " renders no benign-no-op notice"))))))

;; ============================================================================
;; SNAPSHOT DIFF — member-level set diff (rf2-l0us2) on the machine's `:tags`
;; ============================================================================

(deftest snapshot-tags-diff-renders-member-level-set-changes
  (testing "rf2-k08ay / rf2-l0us2 — the snapshot diff the Xray panel renders
            must show machine state changes at MEMBER level for sets. The
            machine's `:tags` snapshot slot is a set; a transition swaps its
            members. Diffing the before/after snapshot via the panel's diff
            engine must surface per-member `:added` / `:removed` ops (not a
            single wholly-replaced blob) so the operator reads exactly which
            tags joined + left."
    (setup!)
    (drive! [:hvac/power-cycle]) ; climate → ... :heating ; fan → :on
    (let [before (proj/machine-logical-state (snapshot))
          _      (drive! [:hvac/mode-toggle])
          after  (proj/machine-logical-state (snapshot))
          {:keys [path-ops]} (diff/project before after)
          ;; member-level set ops live at paths whose final segment is the
          ;; set MEMBER (rf2-l0us2 member-keyed scheme).
          added   (->> path-ops (filter (fn [[_ v]] (= :added (:op v)))) (map first))
          removed (->> path-ops (filter (fn [[_ v]] (= :removed (:op v)))) (map first))
          member-of (fn [tag paths] (some (fn [p] (= tag (last p))) paths))]
      ;; mode-toggle: :climate/heating tag leaves, :climate/cooling joins.
      (is (member-of :climate/cooling added)
          "the joining tag (:climate/cooling) renders as a member-level :added —
           NOT a wholly-replaced set blob (rf2-l0us2)")
      (is (member-of :climate/heating removed)
          "the leaving tag (:climate/heating) renders as a member-level :removed")
      ;; The unchanged tags (fan/on, climate/running, climate/conditioning) do
      ;; NOT churn — only the two members that actually moved appear.
      (is (not (member-of :fan/on added))
          "an unchanged member does NOT render as added (no spurious churn)")
      (is (not (member-of :fan/on removed))
          "an unchanged member does NOT render as removed"))))

;; ============================================================================
;; rf2-iu3no — the benign no-op cell: scope guard + the live no-op render
;; ============================================================================

;; A minimal machine whose INITIAL state carries an entry action, so its
;; birth observably runs the `:initial-entry` cascade (the hvac machine's
;; initial leaves `:idle` / `:off` have no entry handlers, so they'd run
;; zero action rows on bootstrap — no positive signal to assert against).
(def initial-entry-machine
  {:initial :booting
   :data    {}
   :states  {:booting {:entry :on-boot}}
   :actions {:on-boot (fn [{data :data}] {:data (assoc data :booted? true)})}})

(deftest bootstrap-renders-initial-entry-not-no-op
  (testing "rf2-iu3no / rf2-t4582 (#2846) / rf2-gl588 regression guard — a
            machine's BIRTH (`[:rf.machine/start]`) runs its `:initial-entry`
            cascade and is NOT classified as an unhandled-user-event no-op.
            The collapsed `[NO OP] staying in {state}` cell renders the
            CONSEQUENCE of NO state change — it would read FALSE for the
            machine's birth (which ENTERED its initial config, it did not
            'stay'). So the no-op cell must NEVER be reached for the start:
            the boot cascade carries the `:initial-entry` phase and ZERO
            `:no-op` rows. (Per F‴ the eager start is a PURE init-kick — it
            runs the `:initial-entry` actions then STOPS, so there is no
            `before == after` transition row at all; the `:initial-entry`
            action rows still render.) A regression on t4582 surfaces RED."
    (registry/register-xray-handlers!)
    (preload/register-trace-collector!)
    (rf/reg-machine :iu3no/boot initial-entry-machine)
    ;; The start macrostep is the machine's birth — capture exactly it.
    (let [record  (drive-other! :iu3no/boot [:rf.machine/start])
          rows    (cascade record)
          phases  (set (map :phase (rows-of-kind rows :action)))]
      (is (empty? (rows-of-kind rows :no-op))
          "the start renders NO benign-no-op cell — it is the machine's
           birth, not an ignored event (rf2-t4582)")
      (is (contains? phases :initial-entry)
          "the start runs its :initial-entry cascade — the boot action
           row carries the :initial-entry phase, NOT a 'staying in {state}'
           no-op notice")
      (is (= :booting (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                          (get-in [:rf.runtime/machines :snapshots :iu3no/boot :state])))
          "the machine ENTERED its initial config (:booting) — it did not
           'stay' (the no-op cell's premise would read FALSE for a birth)"))))

(deftest genuine-unknown-user-event-renders-no-op-staying-in-state
  (testing "rf2-iu3no — a GENUINE unknown user event (one this machine's
            current configuration matches no transition for) renders the
            collapsed `[NO OP] staying in {state}` cell against the LIVE
            substrate. In the initial config (climate :idle / fan :off),
            `:hvac/mode-toggle` is handled only in the deep :heating /
            :cooling leaves — so from rest it matches no transition: a
            benign no-op. ONE machine in play → the machine name is DROPPED
            (the verb is the bare consequence)."
    (setup!) ; leaves climate :idle / fan :off
    (let [record   (drive! [:hvac/mode-toggle])
          rows     (cascade record)
          no-ops   (rows-of-kind rows :no-op)
          no-op    (first no-ops)]
      (is (= 1 (count no-ops))
          "the unknown user event renders exactly ONE benign no-op cell")
      (is (= :hvac/controller (:machine-id no-op)))
      (is (false? (:show-machine-name? no-op))
          "ONE machine in play → drop the machine name")
      (is (= "staying in :idle"
             (fmt/cascade-row-label (assoc no-op :state :idle :show-machine-name? false)))
          "the collapsed verb is the bare consequence — `staying in {state}`")
      ;; The verb off the LIVE row (whatever the live :state shape is) carries
      ;; no prefix/echo/suffix and no machine name.
      (let [verb (fmt/cascade-row-label no-op)]
        (is (string/starts-with? verb "staying in ") "verb leads with 'staying in '")
        (is (not (string/includes? verb "no-op")) "no 'no-op —' prefix")
        (is (not (string/includes? verb "received")) "no 'received [event]' echo")
        (is (not (string/includes? verb "transition")) "no ', no transition' suffix")
        (is (not (string/includes? verb ":hvac/controller"))
            "single-machine case drops the machine name"))
      ;; Benign — no transition row beside it, no state change committed.
      (is (empty? (rows-of-kind rows :transition))
          "a no-op carries no transition row (rf2-e6q97 suppression)"))))
