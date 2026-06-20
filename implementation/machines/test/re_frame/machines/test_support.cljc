(ns re-frame.machines.test-support
  "Shared test-support helpers for the machines artefact's test suite.

  This namespace is the suite's ONE home for the fixtures and probes the
  machines tests share — a single `frame-db` (`rf/runtime-db-value`), a
  single `snapshot` (`get-in db [:rf.runtime/machines :snapshots id]`), and a
  single trace-capture register/unregister helper — so machine storage,
  frame handling, and trace-listener cleanup have one place to track rather
  than dozens of local copies that could drift out of sync.

  It gives the suite ONE home for:

    - the **reset-runtime fixture** (re-exported from core's
      `re-frame.test-support`, so a test ns requires one support ns);
    - **runtime-db / snapshot lookup** routed through the canonical
      `re-frame.machines.paths` constructors rather than a hardcoded
      `[:rf.runtime/machines :snapshots …]` vector, so a future
      runtime-db restructure touches `paths` + here, not 30 tests;
    - **trace capture with guaranteed unregister** — both a `:each`
      fixture (`trace-capture-fixture`) feeding a dynamic var and a
      scoped `with-trace-capture` macro, each unregistering in a
      `finally` so a thrown assertion never leaks a listener into the
      next test.

  Scope: `test/` only — it requires core's test-support, which is a
  test-only dep. Tests that intentionally verify the RAW runtime-db
  storage shape or RAW listener registration still touch those surfaces
  directly; everything else reaches for these helpers."
  (:require [re-frame.core :as rf]
            [re-frame.machines.paths :as paths]
            [re-frame.test-support :as core-test-support]
            [re-frame.trace :as trace]))

;; ---- reset-runtime fixture (re-export) ------------------------------------

(def make-reset-runtime-fixture
  "Core's `make-reset-runtime-fixture`, re-exported so a machines test
  requires ONE support ns. Same options (`{:adapter …}`). See
  `re-frame.test-support/make-reset-runtime-fixture`."
  core-test-support/make-reset-runtime-fixture)

;; ---- runtime-db + snapshot lookup -----------------------------------------

(defn runtime-db
  "The frame's runtime-db VALUE (where machine snapshots live, per
  EP-0001 / Conventions §Reserved runtime-db keys). Defaults to the
  `:rf/default` frame. The single home for the `frame-db` helper the
  machines tests repeated locally."
  ([] (runtime-db :rf/default))
  ([frame-id] (rf/runtime-db-value frame-id)))

(defn snapshot
  "The machine snapshot for `machine-id` (an actor / singleton id) in
  `frame-id` (default `:rf/default`), read through the canonical
  `paths/snapshot-path` constructor rather than a hardcoded path vector.
  Returns nil when the actor has no snapshot (never spawned, or already
  destroyed)."
  ([machine-id] (snapshot :rf/default machine-id))
  ([frame-id machine-id]
   (get-in (runtime-db frame-id) (paths/snapshot-path machine-id))))

(defn machine-state
  "Convenience: the `:state` of `machine-id`'s snapshot (nil if absent)."
  ([machine-id] (machine-state :rf/default machine-id))
  ([frame-id machine-id] (:state (snapshot frame-id machine-id))))

(defn machine-data
  "Convenience: the `:data` of `machine-id`'s snapshot (nil if absent)."
  ([machine-id] (machine-data :rf/default machine-id))
  ([frame-id machine-id] (:data (snapshot frame-id machine-id))))

;; ---- trace capture --------------------------------------------------------

(def ^:dynamic *captured*
  "The atom holding the trace events captured by `trace-capture-fixture`
  for the current test, or nil outside the fixture's scope."
  nil)

(defn captured-events
  "All trace events captured so far by the in-scope
  `trace-capture-fixture` (a vector, oldest first)."
  []
  (when *captured* @*captured*))

(defn events-of
  "The captured events whose `:operation` equals `operation` (oldest
  first). Convenience over `(filter #(= operation (:operation %)) …)`,
  the shape the suite filtered by hand."
  [operation]
  (filterv #(= operation (:operation %)) (or (captured-events) [])))

(defn reset-captured!
  "Empty the in-scope capture atom — for tests that drive several
  macrosteps and assert on each step's emits independently."
  []
  (when *captured* (reset! *captured* [])))

(defn trace-capture-fixture
  "A `:each` fixture that registers a trace listener appending every
  emitted event to `*captured*` for the duration of one test, then
  ALWAYS unregisters in a `finally` (a thrown assertion never leaks the
  listener into the next test). Compose with the reset-runtime fixture:

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
        test-support/trace-capture-fixture)

  Read the captured stream via `captured-events` / `events-of`."
  [f]
  (let [a  (atom [])
        id ::trace-capture]
    (binding [*captured* a]
      (trace/register-listener! id (fn [ev] (swap! a conj ev)))
      (try (f)
           (finally (trace/unregister-listener! id))))))

#?(:clj
   (defmacro with-trace-capture
     "Run `body` with a trace listener that appends every emitted event
     to a fresh atom bound to `binding-sym`, ALWAYS unregistering in a
     `finally`. The scoped counterpart to `trace-capture-fixture` for a
     single block (e.g. probing one transition's emits inside a larger
     test):

         (with-trace-capture captured
           (machines/machine-transition m snap [:go])
           (is (some #(= :rf.machine/action-ran (:operation %)) @captured)))

     The listener id is gensym-unique per expansion so nested captures
     don't collide."
     [binding-sym & body]
     (let [id-sym (gensym "trace-capture-")]
       `(let [~binding-sym (atom [])
              id#          (keyword "re-frame.machines.test-support"
                                    (name '~id-sym))]
          (trace/register-listener! id# (fn [ev#] (swap! ~binding-sym conj ev#)))
          (try ~@body
               (finally (trace/unregister-listener! id#)))))))
