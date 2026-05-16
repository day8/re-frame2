(ns re-frame.story-mcp-boundary-test
  "JVM tests closing the docs-promised gap on the Story-side MCP-
  consumer contract.

  Spec coverage (rf2-ub1n4): `tools/story/spec/006-MCP-Surface.md` §
  Story's public read primitives (consumed by MCP), § Story's public
  write primitives (consumed by MCP write surface), § Late-bind
  `reg-story-panel` contract.

  The MCP jar (`tools/story-mcp/`) consumes Story's CLJC public surface
  over the Tool-Pair bridge. The story-mcp tests at
  `tools/story-mcp/test/` cover the MCP side — the wire envelope, the
  per-tool semantics, the write-gate contract. What the bead calls out
  as 'Story MCP boundary not covered' is the **Story side**: the
  public Var surface Story commits to keeping stable for the MCP jar
  to call.

  Per spec/006 the contract is a fixed enumeration of fns + the
  late-bind `reg-story-panel` adapter. Drift on Story's side
  (renaming a fn, removing a Var, changing a return-shape) silently
  breaks every MCP tool the jar wires through it. This namespace
  pins the contract on the Story side.

  Surfaces exercised:

  - **Public read Vars exist + are fns.** Each spec/006-cited
    read primitive resolves on `re-frame.story/<sym>` and is callable.
  - **Public write Vars exist + are fns.** Same for the gated write
    surface.
  - **`*`-suffix helpers honour the contract.** Per spec/006 the MCP
    `register-variant` tool routes through `reg-variant*`; we exercise
    the same path with a clear-cut fixture and confirm it lands.
  - **`unregister!` removes a slot from the side-table.** The MCP
    `unregister-variant` tool consumes this.
  - **`clear-kind!` clears a single kind without affecting siblings.**
    Used by MCP's `clear-variants` / `clear-stories` tools.
  - **`reg-story-panel` is the late-bind hook spec/006 §5 describes.**
    Causa's epoch-view registration is the canonical late-bind
    example: Story ships a stub registration (the SOTA spec's
    five-line snippet); Causa's panel registers under the same
    `:rf.story/causa-epoch` id and the registration replaces the stub.
    This pins the late-bind contract Story exposes to third-party
    tooling — including the MCP jar's hypothetical `register-story-
    panel` write tool (per spec/006 §5).

  Test isolation: each test runs against a clean side-table seeded
  with `install-canonical-vocabulary!` (the canonical seven tags +
  the lifecycle machine)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story            :as story]
            [re-frame.story.registrar  :as registrar]
            [re-frame.story.schemas    :as schemas]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [t]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (t))

(use-fixtures :each reset-story-registry)

;; ===========================================================================
;; PUBLIC READ-PRIMITIVE SURFACE (spec/006 §Story's public read primitives)
;; ===========================================================================
;;
;; Each Var named in spec/006-MCP-Surface.md §Story's public read
;; primitives must resolve to a function on `re-frame.story`. Adding
;; this assertion makes a renaming / removal a build-time failure that
;; lands in the Story corpus rather than only in the MCP jar's test
;; suite (which lags Story's own changes by jar-publication cadence).

(deftest public-read-surface-resolves
  (testing "every spec/006 §Story's public read primitive resolves
            on re-frame.story and is callable"
    (doseq [sym '[registrations handler-meta ids registered?
                  variants-of variants-with-tags
                  variant->edn workspace->edn
                  list-tags list-modes canonical-tags
                  run-variant reset-variant watch-variant
                  snapshot-identity
                  read-assertions assertions-passing?
                  canonical-assertion-ids
                  variant-share-url]]
      (let [v (ns-resolve 're-frame.story sym)]
        (is (some? v)
            (str "expected re-frame.story/" sym " to resolve"))
        (is (or (fn? @v) (set? @v) (coll? @v))
            (str "re-frame.story/" sym " is a callable/value Var"))))))

(deftest public-write-surface-resolves
  (testing "every spec/006 §Story's public write primitive resolves
            on re-frame.story and is callable"
    (doseq [sym '[reg-story* reg-variant* reg-workspace* reg-mode*
                  reg-story-panel* reg-decorator* reg-tag*
                  unregister! clear-kind! clear-all!]]
      (let [v (ns-resolve 're-frame.story sym)]
        (is (some? v)
            (str "expected re-frame.story/" sym " to resolve"))
        (is (fn? @v)
            (str "re-frame.story/" sym " is a fn"))))))

;; ===========================================================================
;; MCP `register-variant` PATH — `reg-variant*` writes + read surface sees it
;; ===========================================================================

(deftest reg-variant-star-round-trips-through-the-read-surface
  (testing "the MCP write tool calls reg-variant* (per spec/006); the read
            tools then call registrations / handler-meta / variants-of /
            variant->edn. All four read surfaces must surface the new
            variant immediately"
    (story/reg-story :story.mcp.boundary {:doc "boundary fixture"})
    (story/reg-variant* :story.mcp.boundary/probe
      {:doc    "probe variant"
       :events [[:probe/init]]
       :args   {:n 7}
       :tags   #{:dev}})
    (is (story/registered? :variant :story.mcp.boundary/probe))
    (is (= "probe variant"
           (:doc (story/handler-meta :variant :story.mcp.boundary/probe))))
    (is (= #{:story.mcp.boundary/probe}
           (story/variants-of :story.mcp.boundary)))
    (let [edn (story/variant->edn :story.mcp.boundary/probe)]
      (is (= [[:probe/init]] (:events edn)))
      (is (= {:n 7} (:args edn))))))

(deftest reg-variant-star-bypasses-macro-source-stamp
  (testing "the runtime helper does NOT stamp :source — that's the macro
            layer's job. The MCP jar passes :source explicitly in the
            body when the agent wants source-coords preserved (per
            spec/006 — the MCP write path is programmatic, no &form
            meta is available)"
    (story/reg-variant* :story.mcp.no-source/probe
      {:events []})
    (let [body (story/handler-meta :variant :story.mcp.no-source/probe)]
      (is (not (contains? body :source))
          "no auto-source-stamp when called programmatically — keeps
           the slot available for MCP-supplied source coords"))))

(deftest reg-variant-star-preserves-mcp-supplied-source
  (testing "an MCP-supplied :source slot survives the registrar's merge.
            Per registrar/merge-coords the *pending-coords* path is
            additive — author-supplied :source wins; the dynamic Var is
            nil under programmatic registration so this case reduces to
            'whatever the caller wrote wins'"
    (story/reg-variant* :story.mcp.src-bring/probe
      {:events []
       :source {:file "agent-supplied.cljs" :line 42}})
    (let [body (story/handler-meta :variant :story.mcp.src-bring/probe)]
      (is (= {:file "agent-supplied.cljs" :line 42}
             (:source body))))))

;; ===========================================================================
;; MCP `unregister-variant` PATH
;; ===========================================================================

(deftest unregister-removes-from-read-surface
  (testing "MCP's unregister-variant tool routes through (unregister!
            :variant id) — after which the read surface no longer surfaces it"
    (story/reg-variant :story.mcp.unreg/probe {:events []})
    (is (story/registered? :variant :story.mcp.unreg/probe))
    (registrar/unregister! :variant :story.mcp.unreg/probe)
    (is (not (story/registered? :variant :story.mcp.unreg/probe))
        "the variant is gone after unregister!")
    (is (not (contains? (story/variants-of :story.mcp.unreg) :story.mcp.unreg/probe)))
    (is (nil? (story/handler-meta :variant :story.mcp.unreg/probe)))))

(deftest clear-kind-leaves-siblings-untouched
  (testing "clear-kind! :variant clears all variants but leaves modes,
            workspaces, and tags untouched — the contract MCP needs for
            a 'clear all variants' tool that doesn't nuke the rest of
            the registry"
    (story/reg-variant :story.kindA/v {:events []})
    (story/reg-variant :story.kindB/w {:events []})
    (story/reg-mode :Mode.theme/dark {:args {:theme :dark}})
    (story/reg-workspace :Workspace.kind/grid
      {:layout :variants-grid})
    (registrar/clear-kind! :variant)
    (is (empty? (story/ids :variant))
        "all variants cleared")
    (is (contains? (story/list-modes) :Mode.theme/dark)
        "modes survive")
    (is (story/registered? :workspace :Workspace.kind/grid)
        "workspaces survive")
    (is (= schemas/canonical-tags (story/list-tags))
        "canonical tags survive")))

;; ===========================================================================
;; LATE-BIND `reg-story-panel` CONTRACT (spec/006 §5)
;; ===========================================================================
;;
;; Per spec/006 §5: "The Causa embed is the canonical late-bind example.
;; Stub view ships with Story; Causa registers the live view under the
;; same `:rf.story.panel/epoch-view` id when present; shell picks
;; Causa's view automatically."
;;
;; The contract on Story's side: a panel id can be re-registered, and
;; the second registration replaces the first slot. That is what lets
;; Causa override Story's stub when the artefact is present, and what
;; lets an MCP `register-story-panel` write tool (if ever exposed) ship
;; a new panel in place.

(deftest reg-story-panel-late-bind-replaces-stub
  (testing "registering a panel under an id, then re-registering under
            the same id, replaces the slot — this is the late-bind
            contract Causa's epoch-view depends on"
    (story/reg-story-panel :rf.story/causa-epoch
      {:doc       "Story-shipped stub"
       :title     "Epochs (stub)"
       :placement :bottom
       :render    :rf.story.stubs/causa-epoch-stub})
    (let [stub-body (story/handler-meta :story-panel :rf.story/causa-epoch)]
      (is (= "Epochs (stub)" (:title stub-body)))
      (is (= :rf.story.stubs/causa-epoch-stub (:render stub-body))))
    ;; Now Causa ships its real view — register under the same id.
    (story/reg-story-panel :rf.story/causa-epoch
      {:doc       "Causa-shipped real view"
       :title     "Epochs (Causa)"
       :placement :bottom
       :render    :day8.re-frame2-causa.panels.time-travel/Panel})
    (let [real-body (story/handler-meta :story-panel :rf.story/causa-epoch)]
      (is (= "Epochs (Causa)" (:title real-body)))
      (is (= :day8.re-frame2-causa.panels.time-travel/Panel
             (:render real-body))
          "Causa's :render slot replaces the stub's"))))

(deftest reg-story-panel-placement-vocabulary
  (testing "the panel :placement slot accepts the five documented values
            (:right :left :bottom :top :modal). The MCP register-story-
            panel write tool feeds these straight through; out-of-vocab
            placements fail the malli schema with :rf.error/story-panel-shape"
    (doseq [placement [:right :left :bottom :top :modal]]
      (let [pid (keyword "rf.story.test" (str "panel-" (name placement)))]
        (story/reg-story-panel pid
          {:title     (str "panel " (name placement))
           :placement placement
           :render    :some/view})
        (is (= placement
               (:placement (story/handler-meta :story-panel pid))))))
    (testing "an off-vocab placement is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"story-panel schema"
                            (story/reg-story-panel :rf.story.test/bad-placement
                              {:title     "bad"
                               :placement :nowhere
                               :render    :x/y}))))))

;; ===========================================================================
;; `registrations` QUERY API — the spec/001-mirror MCP read tools consume
;; ===========================================================================

(deftest handlers-returns-id-to-body-map-per-kind
  (testing "(registrations :variant) returns a `{id → body}` map of every
            registered variant; (registrations :tag) does the same for tags.
            Per spec/006 §Story's public read primitives this mirrors
            spec/001's `re-frame.registrar/registrations` — the shape MCP read
            tools rely on for registry walks"
    (story/reg-story :story.handlers {:doc "h-fixture"})
    (story/reg-variant :story.handlers/a {:events [[:init-a]]})
    (story/reg-variant :story.handlers/b {:events [[:init-b]]})
    (let [variants (story/registrations :variant)]
      (is (map? variants) "registrations returns a {id → body} map")
      (is (= #{:story.handlers/a :story.handlers/b} (set (keys variants))))
      (is (every? map? (vals variants))
          "every body is a map"))
    (let [tags (story/registrations :tag)]
      (is (= (count schemas/canonical-tags) (count tags))
          "the canonical seven tags surface via registrations"))))

(deftest ids-returns-the-id-set-per-kind
  (testing "(ids :kind) returns the set of registered ids for that kind"
    (story/reg-story   :story.ids {:doc "ids fixture"})
    (story/reg-variant :story.ids/a {:events []})
    (story/reg-variant :story.ids/b {:events []})
    (is (= #{:story.ids/a :story.ids/b} (story/ids :variant)))
    (is (contains? (story/ids :tag) :dev)
        "the canonical :dev tag id is in the tag id-set")))
