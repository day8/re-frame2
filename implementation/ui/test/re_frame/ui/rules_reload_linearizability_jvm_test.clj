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
  reentrancy proof lives in `re-frame.ui.rules-cljs-test`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.rules :as rules])
  (:import [java.util.concurrent CyclicBarrier]))

(defn- await-barrier [^CyclicBarrier b]
  (.await b))

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
             @rules/custom-elements)
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
          (str "round " round ": no orphan cycle survives")))))

(deftest concurrent-write-throughs-on-distinct-builds-compose
  ;; Two builds, no cycles: concurrent initial-load registrations must both land
  ;; in the aggregate — the publication path never drops one under contention.
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
