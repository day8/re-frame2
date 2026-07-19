(ns re-frame.ui.rules-reload-linearizability-jvm-test
  "JVM barrier-controlled linearizability proof for the custom-element reload
  ledger (rf2-vxgfnd.144).

  The reentrancy fix must hold under REAL concurrency, not only the deterministic
  single-threaded reentrancy a watch drives. These tests race a
  `register-custom-element!` against a `commit-reload!` (and against a
  `notify-reload!`) on separate threads, released together by a `CyclicBarrier`,
  over many rounds. Every observed outcome must equal a LEGAL SERIAL execution of
  the two operations — the registration is never lost, never doubled, never
  written into a closed cycle, and never left in an orphan cycle.

  A mutation control proves the tests have teeth: reverting the ledger to the old
  snapshot-then-`dissoc` shape (two independent atoms, publish while the cycle is
  still open) reintroduces the loss, so a green run here is load-bearing.

  JVM-only: `future`/`CyclicBarrier` real threads. CLJS is single-threaded; its
  reentrancy proof lives in `re-frame.ui.rules-cljs-test`.

  The explicit `build-id` arguments throughout this suite are TEST-STATE PARTITION
  TOKENS, not a topology claim (rf2-4vm19). They keep a round's rows and cycles
  from colliding in the one shared ledger, so each race has an unambiguous legal
  serial outcome. They do NOT assert that two dev builds can share one copy of
  `re-frame.ui.rules`: the shipped boundary is ONE Shadow dev build per CLJS global
  namespace root (see §THE ISOLATION UNIT in `rules.cljc`)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.rules :as rules])
  (:import [java.util.concurrent CyclicBarrier]))

(defn- await-barrier [^CyclicBarrier b]
  (.await b))

(defn- declared
  "The live aggregate with each entry's owner provenance stripped
  (rf2-vxgfnd.143 stamps the declarer `[build-id ns-sym]` set onto every entry in
  the same swap as the declaration — rf2-7uyl9). These rounds assert WHICH
  declarations are live under a race, which is orthogonal to who owns them;
  provenance and the conflict law it anchors are pinned by
  `re-frame.ui.rules-custom-element-conflict-cljs-test`."
  []
  (into {}
        (map (fn [[tag entry]] [tag (dissoc entry :re-frame.ui.rules/owners)]))
        @rules/custom-elements))

(deftest register-races-commit-equals-a-legal-serial-execution
  ;; Build :x has a cycle open with a staged reload of :seed-el. Concurrently a
  ;; FRESH declaration (:race-el, its own source) is registered while the commit
  ;; runs. The two legal serial executions are:
  ;;   (a) register-then-commit: :race-el stages into the cycle, commit applies
  ;;       both -> both live via the cycle;
  ;;   (b) commit-then-register: commit closes the cycle applying :seed-el, then
  ;;       :race-el write-through's live.
  ;; In BOTH, the final ledger is exactly {:seed-el s2, :race-el r}. No
  ;; interleaving may lose :race-el or strand an orphan cycle.
  (dotimes [round 400]
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :seed-el {:properties #{:s}} 'app.seed :x)
    (rules/notify-reload! :x)
    (rules/register-custom-element! :seed-el {:properties #{:s2}} 'app.seed :x) ; staged reload
    (let [barrier (CyclicBarrier. 2)
          committer (future (await-barrier barrier)
                            (rules/commit-reload! :x))
          registrar (future (await-barrier barrier)
                            (rules/register-custom-element!
                             :race-el {:properties #{:r}} 'app.race :x))]
      @committer
      @registrar
      ;; :race-el may still be staged if the register won the race and the cycle
      ;; is not yet committed for it — but there is exactly one commit here, so it
      ;; must have flushed. Assert the terminal, quiescent state.
      (is (= #{:s2} (rules/custom-element-properties :seed-el))
          (str "round " round ": the committing source's reload applied"))
      (is (= #{:r} (rules/custom-element-properties :race-el))
          (str "round " round
               ": the concurrently-registered declaration is never lost"))
      (is (= {:seed-el {:properties #{:s2}}
              :race-el {:properties #{:r}}}
             (declared))
          (str "round " round
               ": the published aggregate equals a legal serial execution — no "
               "double-apply, no clobbered row"))
      (is (empty? (rules/in-flight-cycles))
          (str "round " round ": no orphan cycle survives the race")))))

(deftest register-races-notify-open-leaves-a-coherent-projection
  ;; A registration racing the OPENING of a cycle (notify-reload!). This race has
  ;; a legitimate GENERATION ambiguity — a registration that wins the race writes
  ;; through to the live generation; one that loses stages into the fresh cycle —
  ;; so `:race-el`'s final visibility is order-dependent and is NOT asserted here.
  ;; What linearizability DOES require regardless of order: the published
  ;; aggregate is always exactly `aggregate(:sources)` (never a torn projection,
  ;; never a leaked staged row), and the settled ledger has no orphan cycle.
  (dotimes [round 400]
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :base-el {:properties #{:b}} 'app.base :y)
    (let [barrier (CyclicBarrier. 2)
          opener  (future (await-barrier barrier)
                          (rules/notify-reload! :y))
          registrar (future (await-barrier barrier)
                            (rules/register-custom-element!
                             :race-el {:properties #{:r}} 'app.race :y))]
      @opener
      @registrar
      ;; Whatever generation :race-el landed in, the mid-race aggregate must be a
      ;; coherent projection of the ledger — no half-applied or leaked row.
      (is (= (rules/projected-aggregate) @rules/custom-elements)
          (str "round " round
               ": the published aggregate is exactly the ledger projection "
               "mid-race — no torn or leaked staged row"))
      ;; Close the cycle notify opened (base re-declared this cycle) and settle.
      (rules/register-custom-element! :base-el {:properties #{:b}} 'app.base :y)
      (rules/commit-reload! :y)
      (is (= #{:b} (rules/custom-element-properties :base-el))
          (str "round " round ": base survives the reload"))
      (is (= (rules/projected-aggregate) @rules/custom-elements)
          (str "round " round ": aggregate equals the ledger projection when settled"))
      (is (empty? (rules/in-flight-cycles))
          (str "round " round ": no orphan cycle survives"))
      ;; rf2-84173: the raced registration's MID-race visibility is genuinely
      ;; order-dependent, but its SETTLED visibility is not — both legal
      ;; serializations retain it. Write-through-then-notify puts :race-el in
      ;; ::sources under an untouched source, which the commit keeps;
      ;; notify-then-write stages it into the cycle, which the commit applies.
      ;; So asserting the settled manifest costs nothing and closes a real gap:
      ;; without it this test tolerated losing :race-el entirely.
      (is (= {:base-el {:properties #{:b}}
              :race-el {:properties #{:r}}}
             (declared))
          (str "round " round
               ": the raced registration is present EXACTLY once when settled — "
               "never lost by either serialization, never doubled")))))

(deftest notify-races-commit-closes-only-the-live-cycle
  ;; rf2-84173 AC: notify-vs-commit, the interleaving the original suite never
  ;; raced. Build :z has a cycle open with :seed-el staged at :s2. Concurrently
  ;; the cycle's own after-load commits while the NEXT reload's before-load
  ;; opens. Both operations transition the SAME atom, so they linearize into
  ;; exactly two legal serial executions:
  ;;   (a) commit-then-notify — the commit applies :s2 and closes; notify then
  ;;       opens a fresh EMPTY cycle. One live cycle; :seed-el at :s2.
  ;;   (b) notify-then-commit — notify supersedes the cycle (discarding its
  ;;       staging, the documented and diagnosed loss); the commit then closes
  ;;       that fresh empty cycle. No live cycle; :seed-el back at :s.
  ;; Neither may orphan a cycle, tear the projection, or leave a half-applied
  ;; row — and in NEITHER may a commit close a cycle it did not find live.
  (dotimes [round 400]
    (rules/reset-custom-elements!)
    (rules/register-custom-element! :seed-el {:properties #{:s}} 'app.seed :z)
    (rules/notify-reload! :z)
    (rules/register-custom-element! :seed-el {:properties #{:s2}} 'app.seed :z)
    (let [barrier   (CyclicBarrier. 2)
          committer (future (await-barrier barrier) (rules/commit-reload! :z))
          notifier  (future (await-barrier barrier) (rules/notify-reload! :z))]
      @committer
      @notifier
      (is (contains? #{#{} #{:z}} (rules/in-flight-cycles))
          (str "round " round ": at most ONE live cycle for the build — a race "
               "never accumulates cycles"))
      (is (contains? #{#{:s} #{:s2}} (rules/custom-element-properties :seed-el))
          (str "round " round
               ": :seed-el is at one of the two legal serial values, never a "
               "half-applied third"))
      (is (= (rules/projected-aggregate) @rules/custom-elements)
          (str "round " round ": the aggregate is exactly the ledger projection "
               "mid-race — never torn"))
      ;; settle: close whatever cycle the race left live, then assert quiescence
      (when (seq (rules/in-flight-cycles))
        (rules/commit-reload! :z))
      (is (empty? (rules/in-flight-cycles))
          (str "round " round ": settles with no orphan cycle"))
      (is (= (rules/projected-aggregate) @rules/custom-elements)
          (str "round " round ": settled aggregate equals the ledger projection")))))

(deftest conflicting-write-throughs-linearize-across-ledger-and-live-registry
  ;; rf2-u25hr. Two DIFFERENT sources write-through a CONTRADICTORY declaration of
  ;; ONE tag with no cycle open. The admission must be ONE decision across the
  ;; ledger `::sources` and the live `custom-elements` aggregate: exactly one is
  ;; admitted, the rejected row is ABSENT from `::sources`, and the ledger
  ;; projection equals the live registry.
  ;;
  ;; THE LEVER is a `CyclicBarrier` redef'd into the live-registry write
  ;; `write-element!`, forcing BOTH registrations past their ledger step before
  ;; EITHER live write — the exact interleaving the pre-fix code mis-serialised:
  ;; both prechecked the still-EMPTY live aggregate `@custom-elements` in their
  ;; ledger swap, so both entered `::sources`; then one live write won and the
  ;; other threw, stranding the loser's row in `::sources`. The projection then
  ;; went nil (a ledger holding a contradiction) while the live aggregate held the
  ;; winner — the two atoms torn apart. Post-fix the verdict is decided INSIDE the
  ;; single `ledger-state` swap against the ledger's own projection, so the second
  ;; write-through sees the first's `::sources` row and is rejected there, never
  ;; reaching a live write — and the lever, which only trips inside `write-element!`
  ;; (unused by the dev write-through path), is simply inert.
  (dotimes [round 200]
    (rules/reset-custom-elements!)
    (let [gate  (CyclicBarrier. 2)
          start (CyclicBarrier. 2)
          orig  @#'rules/write-element!
          [ra rb]
          (with-redefs-fn
            {#'rules/write-element!
             (fn [& args] (.await gate) (apply orig args))}
            (fn []
              (let [a (future (await-barrier start)
                              (try (rules/register-custom-element!
                                    :x-el {:properties #{:a}} 'a.ns :b1)
                                   :admitted
                                   (catch Exception e (:rf.error/id (ex-data e)))))
                    b (future (await-barrier start)
                              (try (rules/register-custom-element!
                                    :x-el {:properties #{:z}} 'z.ns :b2)
                                   :admitted
                                   (catch Exception e (:rf.error/id (ex-data e)))))]
                [@a @b])))]
      (is (= #{:admitted :rf.error/custom-element-conflict} (set [ra rb]))
          (str "round " round ": exactly one admitted, one conflict-rejected"))
      (is (= 1 (count @rules/custom-elements))
          (str "round " round ": the live aggregate holds exactly one winner"))
      (is (contains? #{#{:a} #{:z}} (rules/custom-element-properties :x-el))
          (str "round " round ": the winner is one of the two declarations"))
      ;; THE cross-atom consistency proof: the ledger projects to EXACTLY the live
      ;; aggregate. A rejected row left in `::sources` would make the projection a
      ;; contradiction (nil) that diverges from the non-nil live winner — the
      ;; pre-fix failure this asserts against.
      (is (= (rules/projected-aggregate) @rules/custom-elements)
          (str "round " round
               ": the ledger projection EQUALS the live aggregate — the rejected "
               "write-through left no row in ::sources")))))

(deftest concurrent-write-throughs-on-distinct-builds-compose
  ;; Two DISJOINT ledger partitions, no cycles: concurrent initial-load
  ;; registrations must both land in the aggregate — the publication path never
  ;; drops one under contention. The distinct build-ids are partition tokens that
  ;; keep the two rows independent (see the ns docstring), never a claim that two
  ;; co-loaded dev builds share this namespace.
  (dotimes [round 400]
    (rules/reset-custom-elements!)
    (let [barrier (CyclicBarrier. 2)
          a (future (await-barrier barrier)
                    (rules/register-custom-element! :pa {:properties #{:a}} 'app.a :ba))
          b (future (await-barrier barrier)
                    (rules/register-custom-element! :pb {:properties #{:b}} 'app.b :bb))]
      @a @b
      (is (= #{:a} (rules/custom-element-properties :pa))
          (str "round " round ": build a's row is live"))
      (is (= #{:b} (rules/custom-element-properties :pb))
          (str "round " round ": build b's row is live — neither write-through "
               "clobbered the other")))))
