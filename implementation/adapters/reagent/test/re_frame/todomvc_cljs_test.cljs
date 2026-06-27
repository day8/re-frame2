(ns re-frame.todomvc-cljs-test
  "Integration test: drives the TodoMVC example (examples/core/todomvc/)
   through its cold-boot + add trajectory and guards the sorted-map id
   invariant.

   The fixture lives HERE (the adapter test tree), not under
   examples/core/todomvc/ — the example source stays test-free per the
   locked test-free-examples policy (rf2-8cevm). The ns requires the
   example's production source (`todomvc.core`, which chains in
   `todomvc.db` / `todomvc.events` / `todomvc.subs` / `todomvc.views`) so
   the events, cofx, fx and subs register at ns-load, then exercises them
   directly.

   COLD-BOOT ID-ALLOCATION REGRESSION (rf2-mzqd4.1)
   ------------------------------------------------
   `:todo/initialise` declares the `:todo.storage/todos` cofx (EP-0017
   `:rf.cofx/requires`), a RECORDABLE coeffect whose registered supplier
   (`db/read-todos-from-storage`) reads `js/globalThis.localStorage` via
   `some->`. Node has no localStorage, so the supplier exercises the
   EMPTY-localStorage / first-run path: `some->` short-circuits to nil and the
   supplier coerces it to an empty `(sorted-map)`. Before the coercion fix, a
   nil clobbered default-db's `(sorted-map)`, leaving `:todos` nil. The first
   `:todo/add` then built a PLAIN PersistentArrayMap (not a sorted-map); once
   it promoted to an unordered PersistentHashMap (>8 entries),
   `allocate-next-id`'s `(last (keys todos))` stopped returning the max id, so
   a new add could reuse an existing id and `assoc-in` would silently
   OVERWRITE a todo.

   The supplier coerces to `(sorted-map)` when localStorage is empty, so
   `:todos` is ALWAYS a sorted-map and the id-allocation invariant holds.
   This test cold-boots with no localStorage and adds well past the
   8-entry ArrayMap→HashMap promotion threshold, asserting: every add
   lands a fresh id, no todo is lost, and `:todos` stays a sorted-map.

   Registrar-baseline note: TodoMVC's events register at THIS ns's load
   time. cljs.test runs every test ns in a single shared bundle, and some
   sibling test ns's `:each` fixture restores the registrar to a snapshot
   that predates this ns's load — which used to strand the TodoMVC
   registrations by the time this deftest runs. As of rf2-7hwnu the shared
   `make-reset-runtime-fixture` pins a stable ns-load baseline at
   fixture-build time and reinstates it before each test's snapshot, so
   this ns no longer needs the bespoke outer `reinstate-*` fixture it once
   carried — run-order independence is now the fixture's contract."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; todomvc.events requires re-frame.routing at load time
            ;; (reg-route). Required transitively via todomvc.core, but
            ;; named here too so this ns is self-sufficient.
            [re-frame.routing]
            [todomvc.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    ;; EP-0002 (rf2-9o48ih): the test spins its OWN top-level frame via
    ;; `make-frame`; opt out of the ambient `:rf/default` scope so the new
    ;; frame's `:initial-events` drain synchronously (top-level boot) rather than
    ;; being treated as a mid-cascade child-frame creation.
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

(defn- todos [frame]
  (get (rf/app-db-value frame) :todos))

(deftest todomvc-cold-boot-id-allocation
  (testing "cold boot with empty localStorage keeps :todos a sorted-map so
            allocate-next-id never collides past the ArrayMap→HashMap
            promotion threshold (rf2-mzqd4.1)"
    ;; Cold boot exactly as todomvc.core/boot! does it: the frame seeds itself
    ;; via `:initial-events [[:todo/initialise]]`, which folds in the saved todos
    ;; supplied by the registered `:todo.storage/todos` recordable coeffect
    ;; (db.cljs). The supplier reads `js/globalThis.localStorage`; node has none,
    ;; so it exercises the empty / first-run path and coerces nil to an empty
    ;; `(sorted-map)`. No manual `:rf.cofx` supply — the registered generator
    ;; provides the fact, which is the whole point of the EP-0017 generator shape.
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:fx-overrides   {:todo.storage/save :rf/no-op}
                          :initial-events [[:todo/initialise]]})]
      ;; The invariant the whole bug hinges on: :todos must be a
      ;; sorted-map immediately after boot, NOT nil.
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
