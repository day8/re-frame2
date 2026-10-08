(ns re-frame.scxml-irp-semantic-core-cljs-test
  "W3C SCXML IRP (https://www.w3.org/Voice/2013/scxml-irp/) semantic-core
  conformance: the coverage matrix below maps each mandatory semantic-core
  test id to its cover, and this namespace carries the ids marked HERE.
  XState v5 is the parity reference; where re-frame2 diverges from the raw
  SCXML shape, the case asserts the re-frame2 behaviour and names the
  divergence. `scxml-*` names are deftests in `scxml_conformance_cljs_test`;
  `fixture …` names are Mode-B fixtures in `spec/conformance/fixtures/`.

  =========================================================================
  ## W3C SCXML IRP SEMANTIC-CORE COVERAGE MATRIX
  =========================================================================

  Rows are COVERED (a fixture or deftest elsewhere), HERE (a case in this
  namespace), or OUT-OF-SCOPE with a reason.

  Initial / default-entry:
    355  initial absent ⇒ first child in document order   COVERED
                                  (scxml-initial-cascade-* — first-child default)
    364  enter compound ⇒ enter its default initial child COVERED
                                  (scxml-initial-cascade-enters-every-level-*)
    412  default-entry runs the compound's initial child   COVERED (same)
    413  machine placed in the initial-specified config    COVERED
                                  (scxml-initial-cascade-*, initial_entry_test)
    576  initial attribute present ⇒ enter those states    COVERED (scxml-irp-test415-embedded-*, initial_*)

  History:
    387  default history target before first visit         COVERED (fixture scxml-history-test387-*)
    388  stored history config restored after first visit  COVERED (scxml-history-test388-*)
    579  default history fires only when nothing recorded  COVERED (fixture scxml-history-test579-*)
    580  history pseudo-state never in active config /
         per-region shallow                                COVERED (fixture scxml-history-test580-*)

  Entry/exit handlers + ordering:
    375  onentry handlers run in document order            COVERED — re-frame2 has ONE
                                  `:entry` per node; the cross-node entry cascade
                                  order is the 375 rule it expresses
                                  (scxml-initial-cascade-enters-every-level-shallowest-first).
    376  each onentry is an independent block              OUT-OF-SCOPE (divergence) — no
                                  sibling blocks to isolate; a throwing `:entry`
                                  yields a failure Result (no partial commit).
                                  Asserted in
                                  scxml-irp-test376-378-onentry-throw-is-failure-result-divergence.
    377  onexit handlers run in document order             COVERED (exit twin of 375;
                                  scxml-lca-cascade-exit-deepest-first-enter-shallowest-first).
    378  each onexit is an independent block               OUT-OF-SCOPE (divergence, as 376).
    407  run a state's onexit when it is exited            COVERED (scxml-lca-cascade-*)
    409  state removed from config after its onexit runs   COVERED (cascade order ⇒ membership timing)
    411  state added to config + onentry on entry          COVERED (scxml-lca-cascade-*, scxml-initial-cascade-*)

  Event-descriptor matching:
    396  match transition event attr against event name    HERE (scxml-irp-test396-exact-event-name-match)
    399  descriptor matching = exact OR token-prefix       HERE — SCXML's dot-prefix
                                  token descriptor is the keyword NAMESPACE
                                  wildcard `:ns/*`; a bare event has no token
                                  tier (scxml-irp-test399-*).

  done / final / parallel-done:
    372  done.state.id raised after onentry, before onexit COVERED (scxml-compound-done-*)
    415  final child of <scxml> root halts processing      HERE — top-level `:final?` is
                                  whole-machine finality
                                  (scxml-irp-test415-top-level-final-is-machine-final).
    416  done.state.id for a <final> child of a compound   COVERED (scxml-compound-done-*, scxml-irp-test415-embedded-*)
    417  done.state.id for <parallel> when all final       COVERED (scxml-parallel-done-*)
    570  parallel done.state when all regions final        COVERED (scxml-parallel-done-when-every-region-final)

  Transition selection / conflict / optimal set / exit-entry / LCCA:
    403  optimal enabled set; descendant priority; doc ord COVERED (fixture hierarchical-parent-fallthrough,
                                  scxml-transition-selection-*)
    404  exit the exit set first                           COVERED (scxml-lca-cascade-*)
    405  transition content after exits, before entries    COVERED (scxml-lca-cascade-*)
    406  enter the entry set after transition content      COVERED (scxml-lca-cascade-*)
    503  targetless transition ⇒ EMPTY exit set           COVERED — the action fires with
                                  no exit/entry, descendants preserved
                                  (scxml-targetless-on-compound-preserves-active-descendants).
    504  external transition exit set = LCCA descendants   COVERED (scxml-lca-cascade-*)
    505  internal transition (compound source): target's
         descendants in the exit set                       COVERED — re-frame2 / XState v5
                                  flip the SCXML default: an explicit on-path
                                  target re-resolves descendants; external
                                  restart is opt-in `:reenter? true`
                                  (scxml-explicit-current-compound-target-re-resolves-to-initial).
    506  internal treated as external when not applicable  COVERED — a disjoint-subtree
                                  target is always external
                                  (fixture hierarchical-compound-transition,
                                  scxml-lca-cascade-exit-deepest-first-enter-shallowest-first).
    533  internal transition is external for a NON-compound
         (atomic) source                                   COVERED — an explicit self-target
                                  on an atomic leaf without `:reenter?` is a config
                                  no-op (scxml-default-self-transition-is-internal).

  Eventless / internal queue / microstep / macrostep / raise:
    144  raised events FIFO on the internal queue          HERE (scxml-irp-test144-internal-raise-fifo)
    158  executable-content block runs in document order   COVERED — exit→action→entry
                                  across the boundary (scxml-lca-cascade-*).
    419  after a stable config, run the optimal NULL
         (eventless) transition set                        COVERED — eventless fires at a
                                  stable config within the same macrostep
                                  (fixture always-settles-before-raise).
    421  process the internal queue (microstep) before the
         next external event                               HERE
                                  (scxml-irp-test421-internal-queue-drains-before-return).
    423  wait for an external event, then run the enabled
         set as a microstep (macrostep boundary)           COVERED — every step is one
                                  macrostep; an unhandled event is a no-op
                                  (scxml-unhandled-event-is-noop-not-error).

  OUT-OF-SCOPE families (the same line xstate draws):
    147-156, 159, 525  <if>/<foreach>/<else>/cond-expr      DATAMODEL — guards/actions
                                  are host fns, no string cond.
    172-253, 521, 527-554  <send>/<cancel>/<invoke> I-O      SEND-INVOKE-IO — effects-as-data
                                  + declarative `:spawn`, not the SCXML wire model.
    301-354, 487-501, 550-578 (most)  datamodel / system
         variables (_event/_sessionid/_ioprocessors) / I-O  DATAMODEL / IO.
    310, 436  In() state predicate via cond-expression      DATAMODEL — a host-fn guard
                                  reads the configuration directly.
  ========================================================================="
  (:require
   #?(:clj  [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer-macros [deftest is]])
   [re-frame.machines :as rf.machines]
   [re-frame.machines.transition :as rf.machines.transition]))

(defn- step
  "The post-macrostep snapshot of `event` applied to `snapshot`."
  [machine snapshot event]
  (:snapshot (rf.machines/machine-transition machine snapshot event)))

;; ---- Event descriptors (test396 / test399): exact > :ns/* > :* per level ----

(deftest scxml-irp-test396-exact-event-name-match
  (let [m {:initial :a :data {}
           :states  {:a {:on {:error/parse :exact
                               :error/*     :ns-wild
                               :*           :total}}
                     :exact {} :ns-wild {} :total {}}}]
    (is (= :exact (:state (step m {:state :a :data {}} [:error/parse]))))))

(deftest scxml-irp-test399-token-prefix-descriptor-catches-family
  (let [m {:initial :a :data {}
           :states  {:a {:on {:error/parse :exact
                               :error/*     :family
                               :*           :total}}
                     :exact {} :family {} :total {}}}]
    (is (= [:family :total]
           [(:state (step m {:state :a :data {}} [:error/timeout]))
            (:state (step m {:state :a :data {}} [:plain]))])
        ":error/* catches :error/timeout; a bare event has no token tier, so only :* catches it")))

(deftest scxml-irp-test399-token-prefix-leaf-shadows-parent-exact
  (let [m {:initial :p :data {}
           :states  {:p {:on      {:mouse/down :parent-exact}
                         :initial :c
                         :states  {:c {:on {:mouse/* :leaf-token}}
                                   :leaf-token {} :parent-exact {}}}}}]
    (is (= [:p :leaf-token] (:state (step m {:state [:p :c] :data {}} [:mouse/down])))
        "deepest-wins: the leaf's :mouse/* beats the parent's exact :mouse/down")))

;; ---- test376/378 divergence: one :entry per node, a throw fails the Result ----

(deftest scxml-irp-test376-378-onentry-throw-is-failure-result-divergence
  (let [m {:initial :a :data {}
           :states {:a {:on {:go :b}}
                    :b {:entry (fn [_] (throw (ex-info "onentry boom" {})))}}}]
    (is (= :error (:status (rf.machines/machine-transition m {:state :a :data {}} [:go]))))))

;; ---- test415: a top-level :final? leaf is machine-final, an embedded one is not ----

(deftest scxml-irp-test415-top-level-final-is-machine-final
  (let [m {:initial :run :data {}
           :states {:run {:on {:finish :done}}
                    :done {:final? true}}}
        s (step m {:state :run :data {}} [:finish])]
    (is (= [:done true false]
           [(:state s)
            (rf.machines.transition/top-level-final? m (:state s))
            (rf.machines.transition/top-level-final? m :run)]))))

(deftest scxml-irp-test415-embedded-final-is-not-machine-final
  ;; The engine keeps the machine resting in the embedded final leaf.
  (let [m {:initial :work :data {}
           :states {:work {:initial :step1
                           :states {:step1 {:on {:finish :step-done}}
                                    :step-done {:final? true}}}}}
        s (step m {:state [:work :step1] :data {}} [:finish])]
    (is (= [[:work :step-done] false]
           [(:state s) (rf.machines.transition/top-level-final? m (:state s))]))))

;; ---- Internal queue (test144 / test421) --------------------------------------

(deftest scxml-irp-test144-internal-raise-fifo
  (let [log (atom [])
        la  (fn [label & raises]
              (fn [{:keys [data]}]
                (swap! log conj label)
                (when (seq raises)
                  {:data data :fx (mapv (fn [ev] [:raise ev]) raises)})))
        m {:initial :hub :data {}
           :actions {:go (la :go [:b] [:c])
                     :b  (la :b [:d])
                     :c  (la :c)
                     :d  (la :d)}
           :states {:hub {:on {:go {:action :go}
                               :b  {:action :b}
                               :c  {:action :c}
                               :d  {:action :d}}}}}]
    (step m {:state :hub :data {}} [:go])
    (is (= [:go :b :c :d] @log)
        "FIFO: the nested raise :d queues behind its pending sibling :c")))

(deftest scxml-irp-test421-internal-queue-drains-before-return
  (let [m {:initial :a :data {:n 0}
           :actions {:kick  (fn [{d :data}] {:data d :fx [[:raise [:one]] [:raise [:two]]]})
                     :one   (fn [{d :data}] {:data (update d :n inc) :fx [[:raise [:three]]]})
                     :two   (fn [{d :data}] {:data (update d :n inc)})
                     :three (fn [{d :data}] {:data (update d :n inc)})}
           :states  {:a {:on {:kick  {:action :kick}
                              :one   {:action :one}
                              :two   {:action :two}
                              :three {:action :three}}}}}
        s (step m {:state :a :data {:n 0}} [:kick])]
    (is (= [:a 3] [(:state s) (get-in s [:data :n])])
        "every raised event's :data lands in the one returned snapshot")))
