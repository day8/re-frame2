(ns re-frame.todomvc-cljs-test
  "Integration test: drives the TodoMVC example (examples/reagent/todomvc/)
   through its cold-boot + add trajectory and guards the sorted-map id
   invariant.

   The fixture lives HERE (the adapter test tree), not under
   examples/reagent/todomvc/ — the example source stays test-free per the
   locked test-free-examples policy (rf2-8cevm). The ns requires the
   example's production source (`todomvc.core`, which chains in
   `todomvc.db` / `todomvc.events` / `todomvc.subs` / `todomvc.views`) so
   the events, cofx, fx and subs register at ns-load, then exercises them
   directly.

   COLD-BOOT ID-ALLOCATION REGRESSION (rf2-mzqd4.1)
   ------------------------------------------------
   `:todo/initialise` injects the `:todo.storage/todos` cofx, which reads
   `js/globalThis.localStorage` via `some->`. Node has no localStorage, so
   the cofx exercises the EMPTY-localStorage / first-run path: `some->`
   short-circuits to nil. Before the fix, that nil clobbered default-db's
   `(sorted-map)`, leaving `:todos` nil. The first `:todo/add` then built
   a PLAIN PersistentArrayMap (not a sorted-map); once it promoted to an
   unordered PersistentHashMap (>8 entries), `allocate-next-id`'s
   `(last (keys todos))` stopped returning the max id, so a new add could
   reuse an existing id and `assoc-in` would silently OVERWRITE a todo.

   The cofx now coerces to `(sorted-map)` when localStorage is empty, so
   `:todos` is ALWAYS a sorted-map and the id-allocation invariant holds.
   This test cold-boots with no localStorage and adds well past the
   8-entry ArrayMap→HashMap promotion threshold, asserting: every add
   lands a fresh id, no todo is lost, and `:todos` stays a sorted-map.

   Registrar-baseline note: TodoMVC's events register at THIS ns's load
   time. cljs.test runs every test ns in a single shared bundle, and some
   sibling test ns's `:each` fixture restores the registrar to a snapshot
   that predates this ns's load — stranding the TodoMVC registrations by
   the time this deftest runs. The conformance-corpus test handles the
   same hazard by capturing a baseline at deftest entry; we capture this
   ns's registrations once at ns-load (`ns-load-registrar`) and reinstate
   them in an OUTER `:each` fixture that wraps the standard reset fixture,
   so the inner fixture snapshots a populated registrar and restores it
   intact."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; todomvc.events requires re-frame.routing at load time
            ;; (reg-route). Required transitively via todomvc.core, but
            ;; named here too so this ns is self-sufficient.
            [re-frame.routing]
            [todomvc.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; Capture the registrar AT THIS NS'S LOAD — TodoMVC's events / cofx / fx
;; are registered by now (todomvc.core's require chain ran above). The
;; outer fixture below reinstates this baseline so a sibling test ns's
;; pre-todomvc registrar snapshot can't strand these registrations.
(def ^:private ns-load-registrar (test-support/snapshot-registrar))

(defn- reinstate-todomvc-registrations
  "Outer :each fixture — ensure the TodoMVC ns-load registrations are
   present before the standard reset fixture snapshots the registrar.
   Merge (don't replace) so any framework registrations that landed after
   this ns loaded survive too."
  [test-fn]
  (let [before @registrar/kind->id->metadata]
    (reset! registrar/kind->id->metadata
            (merge-with merge before ns-load-registrar))
    (try
      (test-fn)
      (finally
        (reset! registrar/kind->id->metadata before)))))

(use-fixtures :each
  reinstate-todomvc-registrations
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- todos [frame]
  (get (rf/app-db-value frame) :todos))

(deftest todomvc-cold-boot-id-allocation
  (testing "cold boot with empty localStorage keeps :todos a sorted-map so
            allocate-next-id never collides past the ArrayMap→HashMap
            promotion threshold (rf2-mzqd4.1)"
    ;; Cold boot via :on-create, mirroring todomvc.core/run (which fires
    ;; [:todo/initialise] at boot). On node js/globalThis.localStorage is
    ;; absent, so the :todo.storage/todos cofx exercises the
    ;; empty-localStorage path.
    (with-new-frame [f (rf/make-frame
                         {:on-create    [:todo/initialise]
                          :fx-overrides {:todo.storage/save :rf/no-op}})]

      ;; The invariant the whole bug hinges on: :todos must be a
      ;; sorted-map immediately after init, NOT nil.
      (is (sorted? (todos f))
          ":todos must be a sorted-map after cold-boot init, not nil")
      (is (= 0 (count (todos f)))
          "fresh boot starts with no todos")

      ;; Add well past the 8-entry PersistentArrayMap→PersistentHashMap
      ;; promotion threshold. After each add assert the map stays sorted
      ;; and the count strictly increases (i.e. nothing was overwritten).
      (let [n 15]
        (dotimes [i n]
          (rf/dispatch-sync [:todo/add (str "todo-" i)] {:frame f})
          (is (sorted? (todos f))
              (str ":todos must remain a sorted-map after add #" (inc i)))
          (is (= (inc i) (count (todos f)))
              (str "add #" (inc i)
                   " must grow the map (no id collision / overwrite)")))

        ;; Final invariants: distinct ids, every title preserved, and the
        ;; keys are the contiguous 1..n that allocate-next-id should yield
        ;; off a sorted-map.
        (let [m (todos f)]
          (is (= n (count m)))
          (is (= n (count (distinct (keys m))))
              "every todo has a distinct id — no collision overwrote a todo")
          (is (= (set (map #(str "todo-" %) (range n)))
                 (set (map :title (vals m))))
              "every added todo survives — none was silently overwritten")
          (is (= (range 1 (inc n)) (sort (keys m)))
              "ids are the contiguous 1..n that a sorted-map allocator yields"))))))
