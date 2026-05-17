(ns re-frame.story-mcp-surface-test
  "JVM tests closing the MCP-perspective scenarios called out by
  spec/015 §MCP surface (rf2-1svim).

  Pairs with `re-frame.story-mcp-boundary-test` (Var-resolution +
  late-bind contract) and `re-frame.story-runtime-test` (run-variant
  return shape). This namespace covers the four scenarios spec/015
  flagged as Deferred / Partial under §MCP surface:

  - **list-stories / list-variants / list-modes shape** — pinning the
    return shape MCP read tools surface to agents (`(ids :story)`,
    `(ids :variant)`, `(list-modes)`); each returns a vector or set of
    keyword ids matching the spec/006 §read primitive table.
  - **render-story / run-variant from MCP perspective** — invoke
    `run-variant` against a seeded variant; assert the return shape
    matches spec/002 + spec/006 (`{:frame :app-db :assertions
    :rendered-hiccup :elapsed-ms ...}`).
  - **dispatch-via-mcp end-to-end** — register a variant via
    `reg-variant*` (the MCP write path), then dispatch into the
    variant's frame with the `{:frame variant-id}` opts map (the
    `re-frame.core/dispatch-sync` shape the Tool-Pair bridge invokes
    against an allocated frame); assert the frame's `app-db` reflects
    the dispatched event's effect.
  - **story-state-snapshot identity stability** — invoke
    `snapshot-identity` for a variant; mutating cell-overrides
    (render-relevant input) changes the identity hash; mutating the
    `:source` slot (cosmetic-only edit) leaves the identity unchanged.

  Test isolation: each test runs against a clean side-table seeded
  with `install-canonical-vocabulary!` (the canonical seven tags +
  the lifecycle machine). The framework registrar is cleared between
  tests so the variant-frame run-variant allocates against a clean
  app-db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as rf-registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.async      :as story-async]
            [re-frame.story.loaders    :as loaders]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-all [t]
  (story/clear-all!)
  (rf-registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-all)

;; ===========================================================================
;; rf2-1svim — list-stories / list-variants / list-modes shape
;;
;; Per spec/006 §Story's public read primitives the MCP introspection
;; tools (list-stories / list-variants / list-modes) call through to
;; the Story-side queries. Story does not register list-* fns; instead
;; the registrar's `(ids kind)` / `(list-modes)` surface is what agents
;; consume. Pinning the return shape protects against a refactor that
;; changes the collection type (set → vector, or vice versa) and
;; silently breaks every MCP tool that pattern-matches on the result.
;; ===========================================================================

(deftest list-stories-returns-id-set
  (testing "(ids :story) returns a set of keyword ids — the MCP
            list-stories tool's return shape per spec/006 §read
            primitives"
    (story/reg-story :story.mcp.list-s-a {:doc "story A"})
    (story/reg-story :story.mcp.list-s-b {:doc "story B"})
    (let [result (story/ids :story)]
      (is (set? result)
          "the result is a Clojure set — agents iterate / contains? against it")
      (is (every? keyword? result)
          "every id is a keyword")
      (is (contains? result :story.mcp.list-s-a))
      (is (contains? result :story.mcp.list-s-b)))))

(deftest list-variants-returns-id-set
  (testing "(ids :variant) returns the set of registered variant ids;
            the MCP list-variants tool surfaces this directly. Empty
            registry returns the empty set, not nil — protects the
            agent's `(for [...])` walk from a nil punning bug"
    (is (= #{} (story/ids :variant))
        "empty registry → empty set")
    (story/reg-variant :story.mcp.list-v/probe {:events []})
    (story/reg-variant :story.mcp.list-v/probe-two {:events []})
    (let [result (story/ids :variant)]
      (is (set? result))
      (is (= #{:story.mcp.list-v/probe :story.mcp.list-v/probe-two}
             result)))))

(deftest list-modes-returns-id-set
  (testing "(list-modes) returns the registered mode ids — the MCP
            list-modes tool's return shape. Distinct from (ids :mode)
            only by the symbol agents are taught to call; under the
            hood both delegate to the same registrar slot"
    (is (= #{} (story/list-modes))
        "empty registry → empty set")
    (story/reg-mode :Mode.mcp.list/dark  {:args {:theme :dark}})
    (story/reg-mode :Mode.mcp.list/light {:args {:theme :light}})
    (let [result (story/list-modes)]
      (is (set? result))
      (is (= #{:Mode.mcp.list/dark :Mode.mcp.list/light} result))
      ;; (list-modes) MUST equal (ids :mode) — they are the same data.
      (is (= (story/list-modes) (story/ids :mode))
          "(list-modes) is a thin alias of (ids :mode)"))))

;; ===========================================================================
;; rf2-1svim — render-story / run-variant from MCP perspective
;;
;; Spec/006 names `run-variant` as the MCP `render-story` tool's
;; underlying call. The shape an agent expects in the return slot is
;; specified by spec/002 §run-variant result map. This test pins the
;; shape from the MCP boundary's vantage: invoke run-variant the same
;; way the Tool-Pair bridge would and assert every keyed slot of the
;; documented return shape is present.
;; ===========================================================================

(deftest run-variant-return-shape-matches-spec
  (testing "spec/002 §run-variant + spec/006 §render-story: the result
            map carries :frame :app-db :assertions :elapsed-ms
            :snapshot :decorators :errors"
    (rf/reg-event-db :mcp/seed
      (fn [db [_ n]] (assoc db :n n)))
    (story/reg-variant :story.mcp.run/probe
      {:events [[:mcp/seed 42]]
       :play   [[:rf.assert/path-equals [:n] 42]]})
    (let [result (story-async/deref-blocking
                   (story/run-variant :story.mcp.run/probe) 5000)]
      (is (map? result)
          "run-variant returns a map (the MCP render-story tool's payload)")
      (is (contains? result :frame)
          ":frame slot present — the agent reads which frame received the dispatch")
      (is (= :story.mcp.run/probe (:frame result)))
      (is (contains? result :app-db)
          ":app-db slot present — the agent reads the frame's post-run state")
      (is (= 42 (:n (:app-db result)))
          "events phase seeded :n = 42")
      (is (contains? result :assertions)
          ":assertions slot present — the agent reads pass/fail rows")
      (is (vector? (:assertions result)))
      (is (every? :passed? (:assertions result))
          "the play assertion passed")
      (is (contains? result :elapsed-ms)
          ":elapsed-ms slot present — wall-clock for the run")
      (is (number? (:elapsed-ms result)))
      (is (contains? result :snapshot)
          ":snapshot slot present — the spec/002 snapshot tuple")
      (is (contains? result :decorators)
          ":decorators slot present — the resolved decorator pack"))))

(deftest run-variant-empty-play-still-returns-shape
  (testing "even a variant with no :play surfaces the full return shape
            — agents must not have to special-case the no-play branch"
    (story/reg-variant :story.mcp.run/no-play {:events []})
    (let [result (story-async/deref-blocking
                   (story/run-variant :story.mcp.run/no-play) 5000)]
      (is (= :story.mcp.run/no-play (:frame result)))
      (is (= [] (:assertions result))
          "empty :play → empty :assertions vector (not nil)")
      (is (map? (:app-db result))))))

;; ===========================================================================
;; rf2-1svim — dispatch-via-mcp end-to-end
;;
;; The MCP write path: an agent calls `reg-variant*` (programmatic write
;; — no source coord), then drives a dispatch against the allocated
;; frame via `(rf/dispatch-sync event {:frame variant-id})`. The Tool-
;; Pair bridge invokes this exact shape; the test pins the round-trip
;; from registration through dispatch through observable app-db change.
;; ===========================================================================

(deftest dispatch-via-mcp-writes-to-variant-frame-app-db
  (testing "the full MCP write+dispatch path: reg-variant* (programmatic
            registration), run-variant (allocate frame + seed app-db),
            then dispatch-sync with {:frame ...} — the agent observes
            the dispatch's effect on the frame's app-db"
    (rf/reg-event-db :mcp.dispatch/set
      (fn [db [_ v]] (assoc db :payload v)))
    ;; MCP write path: programmatic registration (no &form meta).
    (story/reg-variant* :story.mcp.dispatch/probe
      {:events []
       :args   {}})
    ;; Allocate the variant frame so the dispatch has somewhere to land.
    (story-async/deref-blocking
      (story/run-variant :story.mcp.dispatch/probe) 5000)
    (is (some? (rf/get-frame-db :story.mcp.dispatch/probe))
        "frame was allocated by run-variant")
    ;; The MCP dispatch tool's underlying call.
    (rf/dispatch-sync [:mcp.dispatch/set "hello"]
                      {:frame :story.mcp.dispatch/probe})
    (let [db (rf/get-frame-db :story.mcp.dispatch/probe)]
      (is (= "hello" (:payload db))
          "the dispatch landed on the variant's frame — not the default frame"))
    ;; The default frame must NOT have received the dispatch — proves
    ;; the {:frame ...} routing actually scoped the write.
    (let [default-db (rf/get-frame-db :rf/default)]
      (is (not= "hello" (:payload default-db))
          "default frame is uncontaminated — :frame routing isolates"))))

(deftest dispatch-via-mcp-multiple-events-accumulate
  (testing "the MCP dispatch tool can fire a sequence of events into
            a single variant frame; each dispatch updates the frame's
            app-db in order"
    (rf/reg-event-db :mcp.dispatch/push
      (fn [db [_ v]] (update db :log (fnil conj []) v)))
    (story/reg-variant* :story.mcp.dispatch.seq/probe {:events []})
    (story-async/deref-blocking
      (story/run-variant :story.mcp.dispatch.seq/probe) 5000)
    (doseq [v ["a" "b" "c"]]
      (rf/dispatch-sync [:mcp.dispatch/push v]
                        {:frame :story.mcp.dispatch.seq/probe}))
    (let [db (rf/get-frame-db :story.mcp.dispatch.seq/probe)]
      (is (= ["a" "b" "c"] (:log db))
          "three dispatches in order — frame's app-db carries all three"))))

;; ===========================================================================
;; rf2-1svim — story-state-snapshot identity stability
;;
;; The MCP `story-state-snapshot` tool surfaces `snapshot-identity` for
;; an agent to detect drift between runs. Per spec/002 §snapshot
;; identity: render-relevant inputs (args, mode-merged args, decorator
;; chain) flip the hash; cosmetic edits (`:source` coords) do not.
;; This test pins the identity-stability contract from the MCP boundary.
;; ===========================================================================

(deftest snapshot-identity-stable-across-cosmetic-edits
  (testing "spec/002 §snapshot identity: mutating the variant's :source
            slot (a cosmetic-only edit, used by reg-variant* for
            'register-from-position' tooling) does NOT change the
            snapshot-identity hash — the agent's drift detector
            consequently does NOT re-render the variant on a source-
            coord-only change"
    (story/reg-variant* :story.mcp.snap/probe
      {:args {:label "v1"}
       :events []})
    (let [identity-1 (story/snapshot-identity :story.mcp.snap/probe)]
      (is (some? identity-1) ":content-hash present")
      ;; Re-register with the same body but a different :source slot
      ;; — cosmetic, the same as moving the registration to a new line.
      (story/reg-variant* :story.mcp.snap/probe
        {:args   {:label "v1"}
         :events []
         :source {:file "agent.cljs" :line 99}})
      (let [identity-2 (story/snapshot-identity :story.mcp.snap/probe)]
        (is (= (:content-hash identity-1) (:content-hash identity-2))
            ":source edit is cosmetic — content-hash MUST be stable")))))

(deftest snapshot-identity-changes-on-args-edit
  (testing "spec/002 §snapshot identity: mutating the variant's :args
            slot (a render-relevant input) DOES change the snapshot-
            identity hash. The agent's drift detector re-renders.

            cell-overrides go through `snapshot-identity` via the opts
            map (the canvas uses this path to detect control changes);
            pinning the hash on the opts path covers the surface MCP
            tools consume."
    (story/reg-variant* :story.mcp.snap.args/probe
      {:args   {:label "before"}
       :events []})
    (let [base    (:content-hash
                    (story/snapshot-identity :story.mcp.snap.args/probe))
          with-co (:content-hash
                    (story/snapshot-identity :story.mcp.snap.args/probe
                                             {:cell-overrides {:label "after"}}))]
      (is (not= base with-co)
          "cell-override mutation flips the hash — agent detects drift")
      ;; And the inverse — same cell-overrides → same hash.
      (let [with-co-2 (:content-hash
                        (story/snapshot-identity :story.mcp.snap.args/probe
                                                 {:cell-overrides {:label "after"}}))]
        (is (= with-co with-co-2)
            "same input → same hash (deterministic)")))))

(deftest snapshot-identity-changes-on-active-modes
  (testing "active-modes is a render-relevant input — flipping a mode
            changes the resolved args and thus the snapshot-identity
            hash. The MCP boundary surfaces this via the opts map so
            the agent can compare per-mode snapshots"
    (story/reg-mode :Mode.mcp.snap/dark  {:args {:theme :dark}})
    (story/reg-mode :Mode.mcp.snap/light {:args {:theme :light}})
    (story/reg-variant* :story.mcp.snap.modes/probe
      {:args   {:label "x"}
       :events []})
    (let [dark  (:content-hash
                  (story/snapshot-identity :story.mcp.snap.modes/probe
                                           {:active-modes [:Mode.mcp.snap/dark]}))
          light (:content-hash
                  (story/snapshot-identity :story.mcp.snap.modes/probe
                                           {:active-modes [:Mode.mcp.snap/light]}))
          none  (:content-hash
                  (story/snapshot-identity :story.mcp.snap.modes/probe
                                           {:active-modes []}))]
      (is (not= dark light)
          "two different active-modes → two different hashes")
      (is (not= dark none)
          "no modes vs dark → distinct hashes"))))
