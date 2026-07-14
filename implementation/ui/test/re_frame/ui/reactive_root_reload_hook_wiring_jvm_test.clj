(ns re-frame.ui.reactive-root-reload-hook-wiring-jvm-test
  "rf2-vxgfnd.239 — the LOAD-TIME WIRING of the reload migration, not the
  helper semantics.

  `re-frame.ui.reactive` ends with a top-level `(migrate-legacy-root-cells!)`
  form (rf2-vxgfnd.168). Because `root-cells` is `defonce`, that form is the
  ACTUAL repair a hot reload runs: on a CLJS `:after-load` / JVM
  `require … :reload` the namespace re-evaluates its top-level forms while the
  `defonce` value survives, so the hook converges any pre-weak persistent-set
  entry the previous incarnation left behind — BEFORE the reloaded code touches
  it. The sibling helper suite
  (`reactive-root-reload-migration-cljs-test`) proves the FUNCTION converges a
  legacy entry, but it invokes `migrate-legacy-root-cells!` directly: deleting
  only the production top-level call would leave that suite green, so the
  behaviour reloads actually depend on — the load-time firing — was unproved.

  This fixture drives the REAL reload entry path — `require … :reload`, the
  headless analogue the source comment names alongside `:after-load` — and
  observes the migration WITHOUT any direct call to `migrate-legacy-root-cells!`
  from test code. It is a WIRING/lifecycle test:

    - seed a legacy persistent-set entry (the `defonce` reload survivor's exact
      shape), reload the namespace, and prove the entry converged to the host
      weak representation solely because the top-level hook fired;
    - repeat the reload and prove idempotence — the migrated set is untouched
      (identical object), so repeated HMR cycles are safe;
    - prove the migration is identity-preserving (every seeded member survives)
      and that the converged entry supports the mutable-set operation
      (`.remove`) the weak membership needs — the very op that raises
      `UnsupportedOperationException` on the un-migrated persistent set, i.e.
      the mid-lifecycle crash the hook exists to prevent.

  Members here are opaque identity tokens, not mounted ViewCells: the migration
  is a REPRESENTATION change of the membership set (it never inspects a member
  on the JVM), and real connected/Activity-hidden cell lifecycle across the
  migration is already owned by the direct helper suite. Keeping tokens opaque
  also keeps this reload-in-test free of any deftype/protocol re-def coupling.

  JVM-only (`require … :reload` has no node-test equivalent); runs under
  `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.reactive :as reactive]))

;; The `defonce` registry outlives a reload, so a leaked entry would leak into
;; the next test. `reset-scheduler!` clears it (03 §4) — run it on both sides.
(use-fixtures :each
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- registry
  "The live `root-cells` value (private — a wiring test reads the representation
  the public counts deliberately hide: both a persistent set and the weak set
  report the same `.size`). Double deref: the var holds the `defonce` atom."
  []
  @@#'reactive/root-cells)

(defn- reload-namespace!
  "The REAL reload entry path: re-evaluate every `re-frame.ui.reactive` top-level
  form — including the load-time `(migrate-legacy-root-cells!)` hook — against
  the surviving `defonce` state. The headless analogue of a Shadow `:after-load`
  the source comment names; NOT a direct migration call."
  []
  (require 're-frame.ui.reactive :reload))

(deftest load-time-hook-migrates-a-legacy-entry-through-the-real-reload
  (let [incarnation (reactive/make-root-incarnation)
        member-a    (Object.)
        member-b    (Object.)]
    ;; The `defonce` reload survivor: the pre-rf2-mc62sp namespace left
    ;; `incarnation -> #{member-a member-b}` (a persistent strong set). Seed the
    ;; precondition only — no migration is invoked here.
    (reactive/seed-legacy-root-cells! incarnation [member-a member-b])
    (is (set? (get (registry) incarnation))
        "precondition: the seeded entry is the pre-weak persistent set")

    ;; Fire the ACTUAL load-time hook by re-evaluating the namespace. Test code
    ;; never calls `migrate-legacy-root-cells!`.
    (reload-namespace!)

    (let [migrated (get (registry) incarnation)]
      (testing "the top-level hook converged the entry to the host weak set"
        (is (some? migrated) "the incarnation entry survived the reload")
        (is (not (set? migrated))
            "the persistent set was rebuilt into the mutable weak representation
             — the ONLY code that could have done this is the load-time hook")
        (is (instance? java.util.Set migrated)))

      (testing "migration is identity-preserving — no member was dropped"
        (is (= 2 (.size ^java.util.Set migrated)))
        (is (.contains ^java.util.Set migrated member-a))
        (is (.contains ^java.util.Set migrated member-b)))

      (testing "the converged entry supports the mutable-set op that raises
                UnsupportedOperationException on the un-migrated persistent set
                — the mid-lifecycle crash the hook prevents"
        (is (true? (.remove ^java.util.Set migrated member-a)))
        (is (= 1 (.size ^java.util.Set migrated))
            "the removal took — a weak `java.util.Set`, not a persistent one")))))

(deftest repeat-reload-is-idempotent-and-preserves-the-migrated-entry
  (let [incarnation (reactive/make-root-incarnation)
        member      (Object.)]
    (reactive/seed-legacy-root-cells! incarnation [member])
    (reload-namespace!)
    (let [after-first (get (registry) incarnation)]
      (is (not (set? after-first)) "first reload migrated the entry")

      (testing "a second reload re-runs the hook but leaves the already-weak
                entry untouched — repeated HMR cycles are safe (identical set)"
        (reload-namespace!)
        (let [after-second (get (registry) incarnation)]
          (is (identical? after-first after-second)
              "idempotent: the load-time hook only converges LEGACY entries, so
               the migrated set object is preserved verbatim across reloads")
          (is (.contains ^java.util.Set after-second member)
              "the live member identity survives repeated reloads"))))))

(deftest fresh-registry-reload-is-a-no-op
  ;; The production reality on a cold start / a reload after full teardown:
  ;; `root-cells` is empty, so the top-level hook is one bounded no-op check and
  ;; introduces no entries.
  (is (empty? (registry)) "precondition: no tracked incarnations")
  (reload-namespace!)
  (is (empty? (registry))
      "the load-time hook adds nothing when there is no legacy survivor"))
