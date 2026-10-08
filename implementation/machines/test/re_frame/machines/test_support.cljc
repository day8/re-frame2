(ns re-frame.machines.test-support
  "Shared helpers for the machines test suite: the reset-runtime fixture
  (re-exported from core's `re-frame.test-support`), runtime-db and snapshot
  lookup through the canonical `re-frame.machines.paths` constructors, and
  trace capture that always unregisters its listener, so a thrown assertion
  never leaks one into the next test. Test-only: it requires core's
  test-support."
  (:require [re-frame.core :as rf]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- reset-runtime fixture (re-export) ------------------------------------

(def make-reset-runtime-fixture
  "Core's `make-reset-runtime-fixture`, re-exported so a machines test
  requires ONE support ns. Same options (`{:adapter …}`). See
  `re-frame.test-support/make-reset-runtime-fixture`."
  rf.test-support/make-reset-runtime-fixture)

;; ---- runtime-db + snapshot lookup -----------------------------------------

(defn runtime-db
  "The frame's runtime-db VALUE (where machine snapshots live, per
  Conventions §Reserved runtime-db keys). Defaults to the `:rf/default`
  frame."
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn snapshot
  "The machine snapshot for `machine-id` (an actor / singleton id) in
  `frame-id` (default `:rf/default`), or nil when the actor has no snapshot
  (never spawned, or already destroyed)."
  ([machine-id] (snapshot :rf/default machine-id))
  ([frame-id machine-id]
   (get-in (runtime-db frame-id) (rf.machines.paths/snapshot-path machine-id))))

(defn machine-state
  "The `:state` of `machine-id`'s snapshot (nil if absent)."
  ([machine-id] (machine-state :rf/default machine-id))
  ([frame-id machine-id] (:state (snapshot frame-id machine-id))))

(defn machine-data
  "The `:data` of `machine-id`'s snapshot (nil if absent)."
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
  first)."
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
  ALWAYS unregisters in a `finally`. Compose with the reset-runtime fixture:

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
        test-support/trace-capture-fixture)

  Read the captured stream via `captured-events` / `events-of`."
  [f]
  (let [a  (atom [])
        id ::trace-capture]
    (binding [*captured* a]
      (rf.trace.tooling/register-listener! id (fn [ev] (swap! a conj ev)))
      (try (f)
           (finally (rf.trace.tooling/unregister-listener! id))))))

#?(:clj
   (defmacro with-trace-capture
     "Run `body` with a trace listener that appends every emitted event
     to a fresh atom bound to `binding-sym`, ALWAYS unregistering in a
     `finally`. The scoped counterpart to `trace-capture-fixture` for a
     single block:

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
          (rf.trace.tooling/register-listener! id# (fn [ev#] (swap! ~binding-sym conj ev#)))
          (try ~@body
               (finally (rf.trace.tooling/unregister-listener! id#)))))))
