(ns day8.re-frame2-xray.panels.trace-helpers-cljs-test
  "Pure-data tests for Xray's Trace panel helpers (the whole-epoch trace
  arc — spec/023-Trace-Panel.md).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `issues_ribbon_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **Per-row projection** — `project-row` populates the row shape
       (area · area-badge · phase · verb · target · op-family ·
       outcome-tier) from raw trace events.
    2. **Area / phase / verb / target classification** — the spec/023
       §3 / §4 / §5 vocabulary.
    3. **Band projection** — `build-bands` shapes the rows into the
       epoch envelope + 4 phase bands (spec/023 §2), with empty bands
       always present (spec/023 §13).
    4. **Epoch-scoped feed** — `project-feed-from-epoch` projects the
       focused epoch record's `:trace-events` into the view shape and
       classifies the empty state across the focus-resolver statuses
       (spec/018 §6)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.epoch.badge :as epoch-badge]
            [day8.re-frame2-xray.panels.trace-helpers :as h]
            [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- fixture builders ---------------------------------------------------

(defn- ev
  "Build a Spec 009-shaped trace event for the tests. The axis slots
  pull from both top-level slots and `:tags`."
  [{:keys [id op-type operation time source origin frame event-id
           handler-id dispatch-id tags coord]
    :or {time 1000 tags {}}}]
  (cond-> {:id        id
           :op-type   op-type
           :operation operation
           :time      time
           :tags      (cond-> tags
                       origin       (assoc :rf.event/origin origin)
                       frame        (assoc :frame frame)
                       event-id     (assoc :rf.trace/event-id event-id)
                       handler-id   (assoc :handler-id handler-id)
                       dispatch-id  (assoc :rf.trace/dispatch-id dispatch-id))}
    source (assoc :source source)
    coord  (assoc :rf.trace/trigger-handler {:source-coord coord})))

;; ---- (1) per-row projection --------------------------------------------

(deftest project-row-populates-the-row-shape
  (let [e (ev {:id 7 :op-type :rf.event :operation :rf.event/dispatched
               :time 500 :source :ui :origin :app
               :frame :rf/default :event-id :counter/inc
               :handler-id :counter/inc-handler
               :dispatch-id 42
               :tags {:rf.event/v [:counter/inc]}
               :coord {:file "src/foo.cljs" :line 12}})
        row (h/project-row e)]
    (is (= 7 (:id row)))
    (is (= 500 (:time row)))
    (is (= :rf.event (:op-type row)))
    (is (= :rf.event/dispatched (:operation row)))
    (is (= :event (:area row)))
    (is (= "EVENT" (:area-badge row)))
    (is (= :dispatch (:phase row)))
    (is (= "dispatched" (:verb row)))
    (is (= "[:counter/inc]" (:target row)))
    (is (= :ui (:source row)))
    (is (= :app (:origin row)))
    (is (= :rf/default (:frame row)))
    (is (= 42 (:dispatch-id row)))
    (is (= "src/foo.cljs:12" (:source-coord row)))
    (is (= e (:raw row)))))

(deftest project-row-severity-derived-from-op-type
  (testing ":severity is the Spec 009 synonym axis"
    (is (= :error   (:severity (h/project-row
                                  (ev {:id 1 :op-type :error
                                       :operation :rf.error/x})))))
    (is (= :warning (:severity (h/project-row
                                  (ev {:id 1 :op-type :warning
                                       :operation :rf.warning/x})))))
    (is (= :info    (:severity (h/project-row
                                  (ev {:id 1 :op-type :info
                                       :operation :rf.http/x})))))
    (is (nil? (:severity (h/project-row
                           (ev {:id 1 :op-type :rf.event
                                :operation :rf.event/dispatched})))))))

(deftest project-rows-preserves-chronological-order
  (let [evs  [(ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched :time 100})
              (ev {:id 2 :op-type :rf.fx    :operation :rf.fx/handled    :time 200})
              (ev {:id 3 :op-type :rf.sub :operation :rf.sub/run        :time 300})]
        rows (h/project-rows evs)]
    (is (= [1 2 3] (mapv :id rows))
        "project-rows keeps the events' oldest-first order")))

(deftest project-rows-drops-nil-id-events
  (testing "rf2-wh33n — a nil-:id event is a pathological, malformed
            envelope with no stable identity; project-rows filters it
            out so it never reaches `row-key` (where two such rows would
            both key `t:nil`, a React-key collision) nor selection /
            expansion (both match on :id)"
    (let [evs  [(ev {:id 1   :op-type :rf.event :operation :rf.event/dispatched :time 100})
                (ev {:id nil :op-type :rf.event :operation :rf.event/dispatched :time 200})
                (ev {:id 2   :op-type :rf.fx    :operation :rf.fx/handled       :time 300})
                (ev {:id nil :op-type :rf.fx    :operation :rf.fx/handled       :time 400})]
          rows (h/project-rows evs)]
      (is (= [1 2] (mapv :id rows))
          "both nil-:id rows are dropped; the well-formed rows survive in order")
      (is (every? (comp some? :id) rows)
          "no projected row carries a nil :id")
      (let [keys (mapv h/row-key rows)]
        (is (= ["t:1" "t:2"] keys)
            "surviving rows key on their stable :id")
        (is (= (count rows) (count (distinct keys)))
            "no React-key collision among the projected rows")))))

;; ---- (2) area-badge classification — spec/023 §3 / §5 ------------------

(deftest area-classifies-the-full-vocabulary
  (testing "the area badge vocabulary (spec/023 §3)"
    (is (= :event    (h/area {:op-type :rf.event :operation :rf.event/dispatched})))
    (is (= :db       (h/area {:op-type :rf.event :operation :rf.event/db-changed})))
    (is (= :coeffect (h/area {:op-type :rf.event :operation :rf.cofx/run})))
    (is (= :flow     (h/area {:op-type :rf.event :operation :rf.flow/computed})))
    (is (= :fx       (h/area {:op-type :rf.fx :operation :rf.fx/handled})))
    (is (= :sub      (h/area {:op-type :rf.sub :operation :rf.sub/run})))
    (is (= :view     (h/area {:op-type :rf.view :operation :rf.view/render})))
    (is (= :machine  (h/area {:op-type :rf.machine :operation :rf.machine/transition})))
    (is (= :routing  (h/area {:op-type :rf.event :operation :rf.route/activated})))
    (is (= :resource (h/area {:op-type :rf.event :operation :rf.resource/succeeded})))
    (is (= :epoch    (h/area {:op-type :rf.epoch :operation :rf.epoch/snapshotted})))
    (is (= :error    (h/area {:op-type :error :operation :rf.error/x})))
    (is (= :warning  (h/area {:op-type :warning :operation :rf.warning/x}))))
  (testing "resource ops are RESOURCE, not the generic EVENT they used to
            fall through to (rf2-uqwbhr — :rf.resource/* emits at op-type
            :rf.event but is discriminated by namespace)"
    (is (= :resource (h/area {:op-type :rf.event :operation :rf.resource/registered})))
    (is (= :resource (h/area {:op-type :rf.event :operation :rf.resource/cache-hit})))
    (is (= :resource (h/area {:op-type :rf.event :operation :rf.resource/gc-fired})))
    (is (= :resource (h/area {:op-type :rf.event :operation :rf.resource/restored})))
    ;; the :warning-level clock-skew rows are cross-cutting WARNING, not a
    ;; positive RESOURCE row (severity wins — spec/023 §7)
    (is (= :warning  (h/area {:op-type :warning :operation :rf.resource/hydrate-clock-skew})))
    (is (= :warning  (h/area {:op-type :warning :operation :rf.resource/restore-clock-skew})))
    ;; namespace fallback when :op-type isn't stamped
    (is (= :resource (h/area {:operation :rf.resource/owner-attached}))))
  (testing "namespace fallback when :op-type isn't stamped"
    (is (= :epoch (h/area {:operation :rf.epoch/outcome})))
    (is (= :routing (h/area {:operation :rf.route/deactivated})))
    (is (= :routing (h/area {:operation :rf.route.nav-token/allocated})))
    (is (= :machine (h/area {:operation :rf.machine.timer/scheduled}))))
  (testing "the whole rf.machine* family classifies MACHINE by prefix, not
            an enumerated set — the four sub-families the enumerated set
            omitted (rf2-99f7eq: spawn-all / event / history / start) all
            emit under their own sub-namespace and MUST NOT fall through to
            a bare EVENT"
    (is (= :machine (h/area {:operation :rf.machine.spawn-all/started})))
    (is (= :machine (h/area {:operation :rf.machine.spawn-all/completed})))
    (is (= :machine (h/area {:operation :rf.machine.event/received})))
    (is (= :machine (h/area {:operation :rf.machine.history/recorded})))
    (is (= :machine (h/area {:operation :rf.machine.start/started}))))
  (testing "unknown ops fall back to :event-adjacent neutral"
    (is (= :event (h/area {:op-type :totally-made-up})))))

(deftest nav-token-allocated-is-routing-not-a-bare-event
  ;; rf2-409jka — every navigation emits `:rf.route.nav-token/allocated`
  ;; at op-type :rf.event under the `rf.route.nav-token` SUB-namespace
  ;; (implementation/routing/.../events.cljc). The prior exact
  ;; `= "rf.route"` match let it fall through to the generic :rf.event
  ;; branch: badged EVENT (not ROUTING), staged HANDLER (not
  ;; SIDE-EFFECTS → wrong left-edge colour), and `target-detail` took the
  ;; :event branch (rendering the absent `:rf.event/v` → em-dash instead
  ;; of the route-id). Prefix-matching the `rf.route*` family fixes all
  ;; three symptoms at once.
  (let [row (ev {:id 1 :op-type :rf.event
                 :operation :rf.route.nav-token/allocated
                 :tags {:route-id :dashboard :nav-token 7}})]
    (testing "classifies as ROUTING (not the bare EVENT it fell through to)"
      (is (= :routing (h/area row)))
      (is (= "ROUTING" (h/area-badge row))))
    (testing "stages effect-side — SIDE-EFFECTS + :effects phase"
      (is (= :SIDE-EFFECTS (h/stage row)))
      (is (= :effects (h/phase row))))
    (testing "target-detail renders the route-id (not an em-dash)"
      (is (= ":dashboard" (h/target-detail row))))))

(deftest machine-sub-namespace-ops-are-machine-not-a-bare-event
  ;; rf2-99f7eq — when a machine op reaches `area` WITHOUT its `:rf.machine`
  ;; op-type stamp it falls to the namespace-discrimination fallback (the
  ;; same path the existing `:rf.machine.timer/scheduled` test above rides).
  ;; That fallback used an ENUMERATED set of namespaces ({"rf.machine"
  ;; "rf.machine.microstep" "rf.machine.timer" "rf.machine.spawn"
  ;; "rf.machine.lifecycle" "rf.machine.registrar"}) which OMITTED
  ;; `rf.machine.spawn-all` (reply.cljc / join.cljc / spawn.cljc),
  ;; `rf.machine.event` (parallel.cljc / transition.cljc),
  ;; `rf.machine.history` (transition.cljc) and `rf.machine.start`
  ;; (registration.cljc). Those four sub-families fell through to `:else
  ;; :event` — badged EVENT (not MACHINE), and `op-family` resolved
  ;; :dispatch not :machine (wrong left-edge colour). Same class as
  ;; rf2-409jka / rf2-uxp0u5 (PR #5357, the routing prefix fix). A
  ;; `str/starts-with? "rf.machine"` prefix match fixes the whole family at
  ;; once and is future-proof to new sub-families. `:op-type` is left
  ;; unstamped (nil) so the assertion exercises the namespace fallback —
  ;; the branch the enumerated set lived in.
  (doseq [op [:rf.machine.spawn-all/started
              :rf.machine.spawn-all/completed
              :rf.machine.spawn-all/failed
              :rf.machine.event/received
              :rf.machine.history/recorded
              :rf.machine.start/started]]
    (let [row (ev {:id 1 :operation op
                   :tags {:actor-id :ws/conn :from :idle :to :active}})]
      (testing (str op " classifies MACHINE (not the bare EVENT it fell through to)")
        (is (= :machine (h/area row)))
        (is (= "MACHINE" (h/area-badge row))))
      (testing (str op " rides the machine op-family (left-edge colour)")
        (is (= :machine (h/op-family row))))
      (testing (str op " stages EVENT-HANDLER, not the :event dispatch path")
        (is (= :HANDLER (h/stage row)))
        (is (= :event-handling (h/phase row)))))))

(deftest area-badge-renders-uppercase-text
  (is (= "EVENT" (h/area-badge {:op-type :rf.event :operation :rf.event/dispatched})))
  (is (= "DB" (h/area-badge {:op-type :rf.event :operation :rf.event/db-changed})))
  (is (= "FX" (h/area-badge {:op-type :rf.fx :operation :rf.fx/handled})))
  (is (= "SUB" (h/area-badge {:op-type :rf.sub :operation :rf.sub/run})))
  (is (= "MACHINE" (h/area-badge {:op-type :rf.machine :operation :rf.machine/transition})))
  (is (= "RESOURCE" (h/area-badge {:op-type :rf.event :operation :rf.resource/succeeded})))
  (is (= "ERROR" (h/area-badge {:op-type :error :operation :rf.error/x}))))

(deftest project-row-carries-area-badge
  (let [row (h/project-row (ev {:id 1 :op-type :rf.event
                                :operation :rf.event/db-changed}))]
    (is (= :db (:area row)))
    (is (= "DB" (:area-badge row)))))

;; ---- (3) phase / band placement — spec/023 §4 -------------------------

(deftest phase-places-ops-into-arc-bands
  (testing "envelope — the epoch-lifecycle ops"
    (is (= :envelope (h/phase {:op-type :rf.epoch :operation :rf.epoch/snapshotted})))
    (is (= :envelope (h/phase {:op-type :rf.epoch :operation :rf.epoch/outcome}))))
  (testing "① DISPATCH — the event dispatched"
    (is (= :dispatch (h/phase {:op-type :rf.event :operation :rf.event/dispatched}))))
  (testing "② EVENT HANDLING — coeffects / handler / flows / db-changed / machine"
    (is (= :event-handling (h/phase {:op-type :rf.event :operation :rf.cofx/run})))
    (is (= :event-handling (h/phase {:op-type :rf.event :operation :rf.event/run-end})))
    (is (= :event-handling (h/phase {:op-type :rf.event :operation :rf.flow/computed})))
    (is (= :event-handling (h/phase {:op-type :rf.event :operation :rf.event/db-changed})))
    (is (= :event-handling (h/phase {:op-type :rf.machine :operation :rf.machine/transition}))))
  (testing "③ EFFECTS / FX — fx + routing nav + resource lifecycle"
    (is (= :effects (h/phase {:op-type :rf.fx :operation :rf.fx/handled})))
    (is (= :effects (h/phase {:op-type :rf.event :operation :rf.route/activated})))
    (is (= :effects (h/phase {:op-type :rf.event :operation :rf.resource/work-started}))))
  (testing "④ REACTIVE RENDERING — subs + views"
    (is (= :reactive (h/phase {:op-type :rf.sub :operation :rf.sub/run})))
    (is (= :reactive (h/phase {:op-type :rf.view :operation :rf.view/render})))))

(deftest band-order-is-the-canonical-arc-order
  (is (= [:dispatch :event-handling :effects :reactive] h/band-order)
      "the four phase bands run in arc order (spec/023 §2)"))

;; ---- (4) what-happened verb — spec/023 §5 -----------------------------

(deftest what-happened-builds-the-verb
  (testing "explicit verb overrides"
    (is (= "dispatched" (h/what-happened {:operation :rf.event/dispatched})))
    (is (= "handler ran" (h/what-happened {:operation :rf.event/run-end})))
    (is (= "changed" (h/what-happened {:operation :rf.event/db-changed})))
    (is (= "computed" (h/what-happened {:operation :rf.flow/computed})))
    (is (= "snapshotted" (h/what-happened {:operation :rf.epoch/snapshotted}))))
  (testing "name-based default — the operation's terminal segment"
    (is (= "run" (h/what-happened {:operation :rf.sub/run})))
    (is (= "scheduled" (h/what-happened {:operation :rf.machine.timer/scheduled}))))
  (testing "dashes fold to spaces (spec/023 §5 readable forms)"
    (is (= "skipped on platform"
           (h/what-happened {:operation :rf.cofx/skipped-on-platform}))))
  (testing "no operation → em-dash"
    (is (= "—" (h/what-happened {})))))

;; ---- (5) target / detail — spec/023 §3 / §5 ---------------------------

(deftest target-detail-renders-the-subject
  (testing "event → the event vector"
    (is (= "[:counter/inc]"
           (h/target-detail (ev {:id 1 :op-type :rf.event
                                 :operation :rf.event/dispatched
                                 :tags {:rf.event/v [:counter/inc]}})))))
  (testing "db → [path] old → new"
    (is (= "[:counter]  1 → 2"
           (h/target-detail (ev {:id 1 :op-type :rf.event
                                 :operation :rf.event/db-changed
                                 :tags {:rf.db/path [:counter]
                                        :rf.db/old 1 :rf.db/new 2}})))))
  (testing "fx → fx-id → arg"
    (is (= ":http-xhrio → \"GET /api\""
           (h/target-detail (ev {:id 1 :op-type :rf.fx :operation :rf.fx/handled
                                 :tags {:rf.fx/id :http-xhrio
                                        :rf.fx/arg "GET /api"}})))))
  (testing "sub → sub-id"
    (is (= ":app/counter"
           (h/target-detail (ev {:id 1 :op-type :rf.sub :operation :rf.sub/run
                                 :tags {:rf.sub/id :app/counter}})))))
  (testing "machine → machine-id from → to (states without colons)"
    (is (= ":title/flow idle → loading"
           (h/target-detail (ev {:id 1 :op-type :rf.machine
                                 :operation :rf.machine/transition
                                 :tags {:machine-id :title/flow
                                        :from :idle :to :loading}})))))
  (testing "flow → flow-id → path"
    (is (= ":totals → [:totals]"
           (h/target-detail (ev {:id 1 :op-type :rf.event :operation :rf.flow/computed
                                 :tags {:rf.flow/id :totals
                                        :rf.flow/path [:totals]}})))))
  (testing "resource → resource-id (off the scoped key) + gen (rf2-uqwbhr)"
    ;; lifecycle rows carry the [scope resource-id params] scoped key
    (is (= ":article/by-slug  gen 3"
           (h/target-detail (ev {:id 1 :op-type :rf.event
                                 :operation :rf.resource/succeeded
                                 :tags {:resource/key [:rf.scope/global :article/by-slug {:slug "x"}]
                                        :generation 3}}))))
    ;; registered carries the bare :resource-id (no scoped key yet)
    (is (= ":article/by-slug"
           (h/target-detail (ev {:id 1 :op-type :rf.event
                                 :operation :rf.resource/registered
                                 :tags {:resource-id :article/by-slug}})))))
  (testing "coeffect → cofx-id → PRODUCED value (rf2-sepqgg)"
    ;; `:rf.cofx/value` is the produced value; `:rf.cofx/arg` is the
    ;; requirement arg. The one-liner surfaces the produced value, not
    ;; the arg — mirroring `:fx`'s `fx-id → arg`.
    (is (= ":session → {:user-id 42}"
           (h/target-detail (ev {:id 1 :op-type :rf.event
                                 :operation :rf.cofx/run
                                 :tags {:rf.cofx/id    :session
                                        :rf.cofx/value {:user-id 42}
                                        :rf.cofx/arg   :auth-token}})))))
  (testing "an op with no recognised subject → nil (view renders em-dash)"
    (is (nil? (h/target-detail (ev {:id 1 :op-type :rf.event
                                    :operation :rf.event/run-end :tags {}}))))))

;; ---- (6) outcome tier — spec/023 §8 -----------------------------------

(deftest outcome-tier-distinguishes-states
  (testing "active — created / changed / recalculated / mounted / ran"
    (is (= :active (h/outcome-tier {:operation :rf.sub/run})))
    (is (= :active (h/outcome-tier {:operation :rf.view/render})))
    (is (= :active (h/outcome-tier {:operation :rf.event/db-changed}))))
  (testing "inert — cache-hit / unchanged / skipped"
    (is (= :inert (h/outcome-tier {:operation :rf.sub/skip})))
    (is (= :inert (h/outcome-tier {:operation :rf.view/skip}))))
  (testing "gone — disposed / unmounted / cleared / cancelled"
    (is (= :gone (h/outcome-tier {:operation :rf.sub/dispose})))
    (is (= :gone (h/outcome-tier {:operation :rf.view/unmounted})))
    (is (= :gone (h/outcome-tier {:operation :rf.flow/cleared}))))
  (testing "error / warning keep their semantic tier"
    (is (= :error (h/outcome-tier {:op-type :error :operation :rf.error/x})))
    (is (= :warning (h/outcome-tier {:op-type :warning :operation :rf.warning/x})))))

(deftest project-row-carries-outcome-tier-and-verb-and-target
  (let [row (h/project-row (ev {:id 1 :op-type :rf.sub :operation :rf.sub/dispose
                                :tags {:rf.sub/id :cart/preview}}))]
    (is (= :gone (:outcome-tier row)))
    (is (= "dispose" (:verb row)))
    (is (= ":cart/preview" (:target row)))))

;; ---- (7) op-family band colour — retained left-border -----------------

(deftest op-family-classifies-the-band-buckets
  (is (= :dispatch (h/op-family {:op-type :rf.event :operation :rf.event/dispatched})))
  (is (= :db (h/op-family {:op-type :rf.event :operation :rf.event/db-changed})))
  (is (= :fx (h/op-family {:op-type :rf.fx :operation :rf.fx/handled})))
  ;; resource lifecycle rides the effect-side :fx band (like routing)
  (is (= :fx (h/op-family {:op-type :rf.event :operation :rf.resource/work-started})))
  (is (= :reactive (h/op-family {:op-type :rf.sub :operation :rf.sub/run})))
  (is (= :reactive (h/op-family {:op-type :rf.view :operation :rf.view/render})))
  (is (= :machine (h/op-family {:op-type :rf.machine :operation :rf.machine/transition})))
  (is (= :error (h/op-family {:op-type :error :operation :rf.error/x})))
  (is (= :warning (h/op-family {:op-type :warning :operation :rf.warning/x}))))

(deftest op-family-colour-maps-each-family-to-a-distinct-token
  ;; Colours are CSS-variable strings (`tokens/tokens`); compare against
  ;; the var-map so the test pins the indirection.
  (is (= (:accent tokens/tokens)
         (h/op-family-colour {:op-type :rf.event :operation :rf.event/dispatched})))
  (is (= (:info tokens/tokens)
         (h/op-family-colour {:op-type :rf.event :operation :rf.event/db-changed})))
  (is (= (:warning tokens/tokens)
         (h/op-family-colour {:op-type :rf.fx :operation :rf.fx/handled})))
  (is (= (:dim tokens/tokens)
         (h/op-family-colour {:op-type :rf.sub :operation :rf.sub/run})))
  (is (= (:green tokens/tokens)
         (h/op-family-colour {:op-type :rf.machine :operation :rf.machine/transition})))
  (is (= (:red tokens/tokens)
         (h/op-family-colour {:op-type :error :operation :rf.error/x})))
  (testing "the five band families resolve to distinct colours"
    (let [bands (mapv (fn [[ot op]] (h/op-family-colour {:op-type ot :operation op}))
                      [[:rf.event :rf.event/dispatched]
                       [:rf.event :rf.event/db-changed]
                       [:rf.fx :rf.fx/handled]
                       [:rf.sub :rf.sub/run]
                       [:rf.machine :rf.machine/transition]])]
      (is (= 5 (count (distinct bands)))
          "dispatch / db / fx / reactive / machine bands are all distinct"))))

(deftest outcome-colour-tints-the-verb-column
  (is (= (:text-primary tokens/tokens)
         (h/outcome-colour {:operation :rf.sub/run})))
  (is (= (:text-tertiary tokens/tokens)
         (h/outcome-colour {:operation :rf.sub/skip})))
  (is (= (:dim tokens/tokens)
         (h/outcome-colour {:operation :rf.sub/dispose})))
  (is (= (:red tokens/tokens)
         (h/outcome-colour {:op-type :error :operation :rf.error/x}))))

;; ---- (7b) pipeline stage — flat list (rf2-aqusw) ----------------------

(deftest stage-maps-ops-to-the-epoch-pipeline-steps
  (testing "rf2-aqusw: each trace op classifies to one of the 7 Epoch
            pipeline steps — DISPATCH / COEFFECT / HANDLER / FLOW /
            SIDE-EFFECTS / SUBSCRIPTIONS / VIEWS"
    (is (= :DISPATCH (h/stage {:op-type :rf.event :operation :rf.event/dispatched}))
        "the dispatched event is the DISPATCH trigger")
    (is (= :HANDLER (h/stage {:op-type :rf.event :operation :rf.event/run-end}))
        "a non-dispatched event op (the handler body) is HANDLER")
    (is (= :COEFFECT (h/stage {:op-type :rf.event :operation :rf.cofx/run})))
    (is (= :FLOW (h/stage {:op-type :rf.event :operation :rf.flow/computed})))
    (is (= :HANDLER (h/stage {:op-type :rf.machine :operation :rf.machine/transition}))
        "a machine-as-handler op is HANDLER")
    (is (= :SIDE-EFFECTS (h/stage {:op-type :rf.event :operation :rf.event/db-changed}))
        "the :db commit is a SIDE EFFECT (the Epoch SIDE-EFFECTS :db sub-step)")
    (is (= :SIDE-EFFECTS (h/stage {:op-type :rf.fx :operation :rf.fx/handled})))
    (is (= :SIDE-EFFECTS (h/stage {:op-type :rf.event :operation :rf.route/activated})))
    (is (= :SIDE-EFFECTS (h/stage {:op-type :rf.event :operation :rf.resource/work-started}))
        "resource lifecycle is effect-side — SIDE-EFFECTS (rf2-uqwbhr)")
    (is (= :SUBSCRIPTIONS (h/stage {:op-type :rf.sub :operation :rf.sub/run})))
    (is (= :VIEWS (h/stage {:op-type :rf.view :operation :rf.view/render})))
    (is (= :DISPATCH (h/stage {:op-type :rf.epoch :operation :rf.epoch/snapshotted}))
        "epoch-lifecycle ops ride the DISPATCH step's muted grey")))

(deftest stage-cross-cutting-error-warning-classify-by-occurrence
  (testing "rf2-aqusw: error / warning ops still classify to a stage so
            the column labels their phase; the view rides the severity
            colour on the edge (spec/023 §7)"
    (is (= :HANDLER (h/stage {:op-type :error :operation :rf.error/x})))
    (is (= :HANDLER (h/stage {:op-type :warning :operation :rf.warning/x})))))

(deftest stage-label-reuses-the-epoch-badge-label
  (testing "rf2-aqusw: the stage column label IS the Epoch panel's own
            badge label (DRY via panels.epoch.badge)"
    (is (= "DISPATCH"
           (h/stage-label {:op-type :rf.event :operation :rf.event/dispatched})))
    (is (= "EFFECT HANDLERS"
           (h/stage-label {:op-type :rf.fx :operation :rf.fx/handled}))
        "SIDE-EFFECTS renders the Epoch label 'EFFECT HANDLERS'")
    (is (= "SUBSCRIPTIONS"
           (h/stage-label {:op-type :rf.sub :operation :rf.sub/run})))
    (is (= "VIEWS"
           (h/stage-label {:op-type :rf.view :operation :rf.view/render})))))

(deftest stage-colour-reuses-the-epoch-badge-colour
  (testing "rf2-aqusw: the colour-coded left edge IS the Epoch step's
            badge colour (reused, not a parallel palette)"
    (is (= (epoch-badge/colour :DISPATCH)
           (h/stage-colour {:op-type :rf.event :operation :rf.event/dispatched})))
    (is (= (epoch-badge/colour :SIDE-EFFECTS)
           (h/stage-colour {:op-type :rf.fx :operation :rf.fx/handled})))
    (is (= (epoch-badge/colour :SUBSCRIPTIONS)
           (h/stage-colour {:op-type :rf.sub :operation :rf.sub/run})))
    (is (= (epoch-badge/colour :VIEWS)
           (h/stage-colour {:op-type :rf.view :operation :rf.view/render})))
    (testing "the 7 stage colours match the Epoch step palette exactly"
      (doseq [[ot op stage] [[:rf.event :rf.event/dispatched :DISPATCH]
                             [:rf.event :rf.cofx/run :COEFFECT]
                             [:rf.event :rf.event/run-end :HANDLER]
                             [:rf.event :rf.flow/computed :FLOW]
                             [:rf.fx :rf.fx/handled :SIDE-EFFECTS]
                             [:rf.sub :rf.sub/run :SUBSCRIPTIONS]
                             [:rf.view :rf.view/render :VIEWS]]]
        (is (= (epoch-badge/colour stage)
               (h/stage-colour {:op-type ot :operation op})))))))

(deftest project-row-carries-stage-label-and-colour
  (testing "rf2-aqusw: project-row stamps :stage / :stage-label /
            :stage-colour for the flat list's stage column + edge"
    (let [row (h/project-row (ev {:id 1 :op-type :rf.fx
                                  :operation :rf.fx/handled
                                  :tags {:rf.fx/id :http-xhrio}}))]
      (is (= :SIDE-EFFECTS (:stage row)))
      (is (= "EFFECT HANDLERS" (:stage-label row)))
      (is (= (epoch-badge/colour :SIDE-EFFECTS) (:stage-colour row))))))

;; ---- (8) band projection — spec/023 §2 / §13 --------------------------

(defn- domino-trail-epoch
  "A fixture `:rf/epoch-record` whose `:trace-events` carry the COMPLETE
  domino trail for one event — folding both the synchronous event-side
  rows (dispatch-id N) AND the async reactive rows (`:rf.sub/run` /
  `:rf.view/render`, nil dispatch-id), plus the epoch envelope ops."
  []
  {:epoch-id 17
   :trace-events
   [;; ---- envelope ----
    (ev {:id 0 :op-type :rf.epoch :operation :rf.epoch/snapshotted :time 99})
    ;; ---- ① DISPATCH ----
    (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
         :time 100 :dispatch-id 42 :event-id :counter/inc
         :tags {:rf.event/v [:counter/inc]}})
    ;; ---- ② EVENT HANDLING ----
    (ev {:id 2 :op-type :rf.event :operation :rf.event/run-end
         :time 101 :dispatch-id 42})
    (ev {:id 3 :op-type :rf.event :operation :rf.event/db-changed
         :time 102 :dispatch-id 42})
    ;; ---- ③ EFFECTS / FX ----
    (ev {:id 4 :op-type :rf.fx :operation :rf.fx/handled
         :time 103 :dispatch-id 42})
    ;; ---- ④ REACTIVE RENDERING ----
    (ev {:id 5 :op-type :rf.sub :operation :rf.sub/run
         :time 110 :tags {:rf.sub/id :app/counter}})
    (ev {:id 6 :op-type :rf.sub :operation :rf.sub/run
         :time 111 :tags {:rf.sub/id :app/derived}})
    (ev {:id 7 :op-type :rf.view :operation :rf.view/render :time 120})
    ;; ---- close ----
    (ev {:id 8 :op-type :rf.epoch :operation :rf.epoch/outcome
         :time 121 :tags {:rf.epoch/outcome :ok}})]})

(deftest build-bands-shapes-the-arc
  (testing "the rows shape into the epoch envelope + 4 phase bands in
            arc order (spec/023 §2 / §4)"
    (let [rows  (h/with-rel-times (h/project-rows (:trace-events (domino-trail-epoch))))
          {:keys [envelope outcome bands]} (h/build-bands rows)]
      (testing "envelope carries the :rf.epoch/* ops"
        (is (= #{0 8} (set (map :id envelope)))))
      (testing "outcome is read from the :rf.epoch/outcome op"
        (is (= :ok outcome)))
      (testing "every band in band-order is present (spec/023 §13)"
        (is (= [:dispatch :event-handling :effects :reactive]
               (mapv :id bands))))
      (let [by-id (into {} (map (juxt :id identity) bands))]
        (is (= [1] (mapv :id (:rows (:dispatch by-id))))
            "① DISPATCH — the dispatched row")
        (is (= [2 3] (mapv :id (:rows (:event-handling by-id))))
            "② EVENT HANDLING — run-end + db-changed in fire order")
        (is (= [4] (mapv :id (:rows (:effects by-id))))
            "③ EFFECTS / FX — the fx row")
        (is (= [5 6 7] (mapv :id (:rows (:reactive by-id))))
            "④ REACTIVE RENDERING — the subs + view in fire order")))))

(deftest build-bands-empty-bands-always-present
  (testing "a no-op event (only ② populated) keeps ③④ present + empty
            (spec/023 §13 — empty bands render dimmed `(none)`, never
            hidden)"
    (let [rows  (h/with-rel-times
                  (h/project-rows
                    [(ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                          :time 100})
                     (ev {:id 2 :op-type :rf.event :operation :rf.event/run-end
                          :time 101})]))
          {:keys [bands]} (h/build-bands rows)
          by-id (into {} (map (juxt :id identity) bands))]
      (is (= 4 (count bands)) "all four bands present")
      (is (false? (:empty? (:dispatch by-id))))
      (is (false? (:empty? (:event-handling by-id))))
      (is (true? (:empty? (:effects by-id))) "③ EFFECTS empty for a no-op")
      (is (true? (:empty? (:reactive by-id))) "④ REACTIVE empty for a no-op")
      (is (zero? (:count (:effects by-id))))
      (is (= 1 (:count (:dispatch by-id)))))))

(deftest epoch-outcome-reads-the-outcome-op
  (is (= :ok (h/epoch-outcome
               (h/project-rows
                 [(ev {:id 1 :op-type :rf.epoch :operation :rf.epoch/outcome
                       :tags {:rf.epoch/outcome :ok}})]))))
  (is (= :blocked (h/epoch-outcome
                    (h/project-rows
                      [(ev {:id 1 :op-type :rf.epoch :operation :rf.epoch/outcome
                            :tags {:rf.epoch/outcome :blocked}})]))))
  (testing "no outcome op → nil (epoch still in-flight)"
    (is (nil? (h/epoch-outcome
                (h/project-rows
                  [(ev {:id 1 :op-type :rf.event
                        :operation :rf.event/dispatched})]))))))

;; ---- (9) epoch-scoped feed projection — spec/018 §6 -------------------

(deftest project-feed-from-epoch-folds-the-complete-domino-trail
  (testing "scoping by the focused epoch's `:trace-events` renders the
            WHOLE arc — both the synchronous event-side rows AND the
            async nil-dispatch-id reactive tail (subs ran + view
            rendered) + the envelope ops"
    (let [epoch (domino-trail-epoch)
          feed  (h/project-feed-from-epoch epoch :focused)]
      (is (= 9 (:total feed))
          ":total = every trace event in the focused epoch")
      (is (= 9 (:rendered feed))
          ":rendered = :total (no filtering)")
      (is (= #{0 1 2 3 4 5 6 7 8} (set (map :id (:rows feed))))
          "rows are the WHOLE trail — including the nil-dispatch-id
           reactive rows and the envelope ops")
      (is (some #(and (nil? (:dispatch-id %)) (= :rf.sub (:op-type %)))
                (:rows feed))
          "the async :rf.sub/run rows (nil dispatch-id) are present")
      (is (some #(= :rf.view (:op-type %)) (:rows feed))
          "the async :rf.view/render row is present")
      (is (= 17 (:epoch-id feed)))
      (is (= :ok (:outcome feed)) "the epoch outcome is exposed")
      (is (nil? (:empty-kind feed))))))

(deftest project-feed-from-epoch-rows-oldest-first
  (testing "rows render OLDEST-first (chronological) so the arc reads
            top-down — EPOCH OPEN → ① DISPATCH → … → ④ REACTIVE"
    (let [epoch (domino-trail-epoch)
          feed  (h/project-feed-from-epoch epoch :focused)]
      (is (= [0 1 2 3 4 5 6 7 8] (mapv :id (:rows feed)))))))

(deftest project-feed-from-epoch-exposes-bands
  (testing "the feed carries the structural arc the view paints —
            envelope + 4 phase bands + outcome (spec/023 §2)"
    (let [feed (h/project-feed-from-epoch (domino-trail-epoch) :focused)]
      (is (contains? feed :envelope))
      (is (contains? feed :bands))
      (is (contains? feed :outcome))
      (is (= [:dispatch :event-handling :effects :reactive]
             (mapv :id (:bands feed))))
      (is (= #{0 8} (set (map :id (:envelope feed)))))
      (is (= :ok (:outcome feed))))))

(deftest project-feed-from-epoch-no-events
  (testing "a focused epoch with empty :trace-events → :no-events"
    (let [feed (h/project-feed-from-epoch {:epoch-id 3 :trace-events []}
                                          :focused)]
      (is (zero? (:total feed)))
      (is (zero? (:rendered feed)))
      (is (= [] (:rows feed)))
      (is (= 3 (:epoch-id feed)))
      (is (= :no-events (:empty-kind feed)))
      (testing "even an empty epoch still exposes all four bands (empty)"
        (is (= 4 (count (:bands feed))))
        (is (every? :empty? (:bands feed)))))))

(deftest project-feed-from-epoch-no-focus
  (testing "focus-status :no-focus → :no-focus empty-kind, no rows"
    (let [feed (h/project-feed-from-epoch nil :no-focus)]
      (is (= :no-focus (:empty-kind feed)))
      (is (zero? (:total feed)))
      (is (zero? (:rendered feed)))
      (is (= [] (:rows feed)))
      (is (nil? (:epoch-id feed))))))

(deftest project-feed-from-epoch-epoch-evicted
  (testing "focus-status :epoch-evicted → :epoch-evicted empty-kind"
    (let [feed (h/project-feed-from-epoch nil :epoch-evicted)]
      (is (= :epoch-evicted (:empty-kind feed)))
      (is (zero? (:total feed)))
      (is (zero? (:rendered feed)))
      (is (= [] (:rows feed))))))

(deftest project-feed-from-epoch-ignores-record-unless-focused
  (testing "only :focused status reads the record's :trace-events"
    (let [epoch (domino-trail-epoch)]
      (is (zero? (:total (h/project-feed-from-epoch epoch :no-focus))))
      (is (zero? (:total (h/project-feed-from-epoch epoch :epoch-evicted)))))))

(deftest project-feed-from-epoch-shape-keys
  (testing "the feed shape carries NO filtering keys"
    (let [feed (h/project-feed-from-epoch (domino-trail-epoch) :focused)]
      (is (contains? feed :rows))
      (is (contains? feed :total))
      (is (contains? feed :rendered))
      (is (contains? feed :epoch-id))
      (is (contains? feed :empty-kind))
      (is (contains? feed :bands))
      (is (contains? feed :envelope))
      (is (contains? feed :outcome))
      (doseq [k [:filters :any-filter? :distinct :counts
                 :active-filters :cascade-dispatch-id]]
        (is (not (contains? feed k))
            (str "removed filtering key " k " must not be in the feed shape"))))))

;; ---- (10) relative timing + duration — spec/023 §3 / §6 ---------------

(deftest relative-time-figma-form
  (testing "epoch-t0 is the earliest row time (EPOCH OPEN)"
    (is (= 100 (h/epoch-t0 [{:time 300} {:time 100} {:time 200}])))
    (is (nil? (h/epoch-t0 [{:time nil} {:time nil}]))))
  (testing "format-rel-time renders the Δt '+N.N' offset"
    (is (= "+0.0" (h/format-rel-time 100 100)))
    (is (= "+2.0" (h/format-rel-time 102 100)))
    (is (nil? (h/format-rel-time nil 100))))
  (testing "with-rel-times stamps :rel-time on every row"
    (let [rows (h/with-rel-times [{:time 100} {:time 103}])]
      (is (= ["+0.0" "+3.0"] (mapv :rel-time rows))))))

(deftest duration-ms-reads-canonical-per-area-elapsed-tags
  ;; rf2-k7vtri — the substrate stamps the CANONICAL per-area namespaced
  ;; elapsed tag (`:rf.fx/elapsed-ms`, `:rf.sub/elapsed-ms`, …), NOT a bare
  ;; `:elapsed-ms`, on each op family's run-end / handled / rendered emit.
  ;; Drive the canonical builders (the same shape `trace/emit!` stamps) so a
  ;; reader that only read the non-canonical `:elapsed-ms` is caught: before
  ;; the fix every one of these resolved to nil (the panel rendered `—`).
  (testing "FX duration — :rf.fx/elapsed-ms (spec/009 §241)"
    (is (= 12.0 (h/duration-ms (teb/fx-handled-ev :http/post {:url "/x"} 12.0)))))
  (testing "SUB duration — :rf.sub/elapsed-ms (spec/009 §251)"
    (is (= 0.7 (h/duration-ms (teb/sub-run-ev [:items] true nil [1 2 3] 0.7)))))
  (testing "VIEW duration — :rf.view/elapsed-ms (spec/009 §281)"
    (is (= 3.4 (h/duration-ms (teb/view-rendered-ev :app/root [[:items]] 3.4)))))
  (testing "COEFFECT duration — :rf.cofx/elapsed-ms (spec/009 §243)"
    (is (= 0.6 (h/duration-ms (teb/cofx-run-ev :session {:user-id 42} 0.6)))))
  (testing "HANDLER duration — :rf.event/elapsed-ms (re-frame.router emit-run-end)"
    (is (= 4.2 (h/duration-ms (teb/run-end-ev 4.2)))))
  (testing "FLOW duration — bare :elapsed-ms tag (re-frame.flows)"
    (is (= 0.9 (h/duration-ms (teb/flow-recomputed-ev :total [:total] 1 2 0.9)))))
  (testing "point-in-time emits carry no elapsed → nil (renders —)"
    (is (nil? (h/duration-ms (teb/sub-run-ev [:items] true nil [1 2 3]))))
    (is (nil? (h/duration-ms (teb/cofx-run-ev :session {:user-id 42}))))))

(deftest format-duration-figma-form
  (is (= "0.4 ms" (h/format-duration 0.4)))
  (is (= "12.0 ms" (h/format-duration 12)))
  (is (nil? (h/format-duration nil)))
  (is (nil? (h/format-duration "nope"))))

;; ---- (11) format-time --------------------------------------------------

(deftest format-time-renders-hms-with-millis
  (testing "format-time returns nil on non-numeric input"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not a number"))))
  (testing "format-time returns a HH:MM:SS.mmm-shaped string"
    (let [s (h/format-time 12345)]
      (is (string? s))
      (is (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}$" s)))))

;; ---- (12) find-row -----------------------------------------------------

(deftest find-row-by-id
  (let [rows [{:id 1} {:id 2} {:id 3}]]
    (is (= {:id 2} (h/find-row rows 2)))
    (is (nil? (h/find-row rows 99)))))

;; ---- (13) source-coord -------------------------------------------------

(deftest source-coord-projection
  (testing "source-coord pulls file:line from :rf.trace/trigger-handler"
    (is (= "src/foo.cljs:42"
           (h/source-coord
             {:id 1 :op-type :rf.event
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"
                                                        :line 42}}}))))
  (testing "missing trigger-handler returns nil"
    (is (nil? (h/source-coord {:id 1 :op-type :rf.event}))))
  (testing "missing :line returns just the file"
    (is (= "src/foo.cljs"
           (h/source-coord
             {:id 1 :op-type :rf.event
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"}}})))))

;; ---- (14) readable-description (legacy cross-panel line) ---------------

(deftest readable-description-never-blank
  (testing "dispatch → 'dispatched <event-vec>'"
    (is (= "dispatched [:counter/inc]"
           (h/readable-description
             (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                  :tags {:rf.event/v [:counter/inc]}})))))
  (testing "sub → 'sub ran <id>'"
    (is (= "sub run :app/counter"
           (h/readable-description
             (ev {:id 1 :op-type :rf.sub :operation :rf.sub/run
                  :tags {:rf.sub/id :app/counter}})))))
  (testing "unknown op falls back to short-description (never blank)"
    (is (= ":rf.event/run-end"
           (h/readable-description
             (ev {:id 1 :op-type :rf.event :operation :rf.event/run-end
                  :tags {}}))))))

;; ---- (15) short-description --------------------------------------------

(deftest short-description-priority-order
  (testing "event vector is preferred"
    (is (re-find #"counter/inc"
                 (h/short-description
                   (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                        :tags {:rf.event/v [:counter/inc]}})))))
  (testing "reason is used when no event vec"
    (is (re-find #"because"
                 (h/short-description
                   (ev {:id 1 :op-type :error :operation :rf.error/x
                        :tags {:reason "because"}})))))
  (testing "fallback is the operation keyword alone"
    (is (= ":rf.event/dispatched"
           (h/short-description
             (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                  :tags {}}))))))

;; ---- (16) row-key — stable per-trace-event identity --------------------

(deftest row-key-uses-stable-trace-id
  (testing "row-key reads only :id — stable per emit"
    (is (= "t:7" (h/row-key {:id 7})))
    (is (= "t:42" (h/row-key {:id 42}))))
  (testing "row-key is unique per distinct trace id"
    (let [ids  (range 1 200)
          keys (mapv #(h/row-key {:id %}) ids)]
      (is (= (count ids) (count (distinct keys)))))))

(deftest project-feed-from-epoch-rows-carry-no-row-index-slot
  (testing "rows MUST NOT carry a :row-index slot (a footgun inviting
            positional React keys)"
    (let [feed (h/project-feed-from-epoch (domino-trail-epoch) :focused)]
      (doseq [row (:rows feed)]
        (is (not (contains? row :row-index))
            (str "row " (:id row) " must not carry :row-index"))))))

;; ---- (10) per-path db-changed diff — rf2-b3zw2 / rf2-8q8i4 = (b) --------
;;
;; The `:rf.event/db-changed` trace event carries no per-path diff (it
;; only ships `:event` + `:frame`). The Trace panel derives the diff
;; PANEL-SIDE from the focused epoch record's `:db-before` /
;; `:db-after` slots — the rf2-8q8i4 Mike-decided shape — via
;; `db-changed-diff-triples` (route through `app-db-diff-helpers/diff-paths`).
;; `project-feed-from-epoch` attaches the resulting triples to every
;; `:rf.event/db-changed` row's `:db-diff` slot so the view stays
;; dumb-and-pure.

(defn- diff-epoch
  "A minimal epoch record carrying a non-trivial `:db-before` /
  `:db-after` and a single `:rf.event/db-changed` row. The other rows
  in the trail are noise the test ignores."
  [db-before db-after]
  {:epoch-id     71
   :db-before    db-before
   :db-after     db-after
   :trace-events
   [(ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
         :time 100 :dispatch-id 42 :tags {:rf.event/v [:counter/inc]}})
    (ev {:id 2 :op-type :rf.event :operation :rf.event/db-changed
         :time 102 :dispatch-id 42})]})

(deftest db-changed-diff-triples-routes-through-diff-paths
  (testing "the helper returns the canonical diff-paths triples for the
            epoch's :db-before / :db-after"
    (let [triples (h/db-changed-diff-triples
                    {:db-before {:counter 1}
                     :db-after  {:counter 2}})]
      (is (= [{:op :modified :path [:counter] :before 1 :after 2}]
             triples)))))

(deftest db-changed-diff-triples-empty-when-no-changes
  (testing "db-before == db-after → empty diff (the no-changes case
            per spec/023 §APP-DB CHANGES)"
    (let [db      {:counter 1 :user {:name "Ada"}}
          triples (h/db-changed-diff-triples
                    {:db-before db :db-after db})]
      (is (= [] triples)))))

(deftest db-changed-diff-triples-nested-and-top-level-paths
  (testing "the diff covers both top-level and nested-key changes"
    (let [before  {:counter 1 :user {:name "Ada" :age 30}}
          after   {:counter 2 :user {:name "Ada" :age 31}
                   :last-seen :now}
          triples (h/db-changed-diff-triples
                    {:db-before before :db-after after})
          by-path (into {} (map (juxt :path identity)) triples)]
      (is (contains? by-path [:counter])
          "top-level :counter shows")
      (is (= :modified (:op (get by-path [:counter]))))
      (is (contains? by-path [:user :age])
          "nested [:user :age] shows")
      (is (= :modified (:op (get by-path [:user :age]))))
      (is (= 30 (:before (get by-path [:user :age]))))
      (is (= 31 (:after  (get by-path [:user :age]))))
      (is (contains? by-path [:last-seen]))
      (is (= :added (:op (get by-path [:last-seen])))))))

(deftest project-feed-attaches-db-diff-to-db-changed-rows
  (testing "the db-changed row carries the derived diff under :db-diff;
            other rows do NOT carry a :db-diff slot"
    (let [feed   (h/project-feed-from-epoch
                   (diff-epoch {:counter 1} {:counter 2 :flag true})
                   :focused)
          by-id  (into {} (map (juxt :id identity)) (:rows feed))
          db-row (get by-id 2)
          ev-row (get by-id 1)]
      (is (contains? db-row :db-diff)
          "the :rf.event/db-changed row carries :db-diff")
      (let [paths (set (map :path (:db-diff db-row)))]
        (is (= #{[:counter] [:flag]} paths)
            "the diff covers both modified + added paths"))
      (is (not (contains? ev-row :db-diff))
          "the non-db-changed row carries NO :db-diff slot"))))

(deftest project-feed-empty-diff-attached-as-empty-vec
  (testing "when db-before == db-after the db-changed row still carries
            :db-diff, but the vector is empty — the view renders no
            per-path sub-list (spec/023 §APP-DB CHANGES — empty-diff)"
    (let [feed  (h/project-feed-from-epoch
                  (diff-epoch {:counter 1} {:counter 1})
                  :focused)
          by-id (into {} (map (juxt :id identity)) (:rows feed))]
      (is (= [] (:db-diff (get by-id 2)))))))

(deftest project-feed-no-db-changed-row-no-attachment
  (testing "an epoch with NO :rf.event/db-changed row in :trace-events
            (e.g. a no-op event) attaches no :db-diff to any row"
    (let [epoch  {:epoch-id     91
                  :db-before    {:counter 1}
                  :db-after     {:counter 1}
                  :trace-events
                  [(ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                        :time 100 :dispatch-id 42})]}
          feed   (h/project-feed-from-epoch epoch :focused)]
      (doseq [row (:rows feed)]
        (is (not (contains? row :db-diff))
            (str "row " (:id row) " must not carry :db-diff"))))))

(deftest project-feed-flow-having-and-flow-less-epoch-shapes
  (testing "the diff projection works the same for a flow-less event
            (handler writes :db only) and a flow-having event (flow
            writes one path after the handler) — the diff is derived
            from db-before/db-after which are net-of-flows on both"
    (testing "flow-less event — handler writes [:counter] only"
      (let [feed (h/project-feed-from-epoch
                   (diff-epoch {:counter 1} {:counter 2})
                   :focused)
            db-row (some #(when (= 2 (:id %)) %) (:rows feed))]
        (is (= [{:op :modified :path [:counter] :before 1 :after 2}]
               (:db-diff db-row)))))
    (testing "flow-having event — handler writes [:counter], flow
              writes [:totals :sum]; net diff covers both"
      (let [feed (h/project-feed-from-epoch
                   (diff-epoch {:counter 1 :totals {:sum 1}}
                               {:counter 2 :totals {:sum 2}})
                   :focused)
            db-row (some #(when (= 2 (:id %)) %) (:rows feed))
            by-path (into {} (map (juxt :path identity)) (:db-diff db-row))]
        (is (contains? by-path [:counter]))
        (is (contains? by-path [:totals :sum]))))))
