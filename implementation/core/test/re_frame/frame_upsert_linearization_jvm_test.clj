(ns re-frame.frame-upsert-linearization-jvm-test
  "rf2-vxgfnd.76 / rf2-vxgfnd.197 — linearize frame-id construction.

  THE WINDOW (JVM-only; CLJS is single-threaded). Before the A-prime fix
  `upsert-frame!` read `(get @frames
  id)` and THEN wrote (`swap! frames assoc id …` on create / `swap! frames update
  id assoc …` on re-register) as two separate steps. Two actors racing in the
  read→write window both read the id absent and both `assoc` — a LAST-WRITER
  CLOBBER that orphaned the loser's container / drain-lock / durable state; and a
  re-registration racing a concurrent `destroy-frame!` did a bare `(update m id
  assoc …)` on a dissoc'd id, RESURRECTING a partial `{:config … :generation …}`
  zombie with no state container and no `:drain-lock`.

  The original fix serialized only cold creation. The construction transaction
  now reserves a frame id before adapter callbacks and retains that ownership
  through publication or exact rollback. A same-id contender fails promptly;
  disjoint ids remain independent. Plus an internal create-exclusive mode
  (`:rf.frame/must-create?`) that throws typed `:rf.error/frame-id-taken` on a
  taken id — the primitive `ui.test` rests its fresh-isolated-frame contract on.

  These fixtures open construction windows DETERMINISTICALLY via the
  `frame/*upsert-decide-probe*` JVM linearization seam (a `nil`-in-production
  dynamic hook fired once after reservation and before the authoritative
  registry path),
  conveyed into the racing thread by `future` binding-conveyance — NO sleeps.
  Each asserts the fail-fast transaction contract without sleeps."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as substrate]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Loads the trace-tooling artefact so its
            ;; The trace-tooling retention-policy late-bind is published
            ;; before these tests run (the retention override is otherwise a
            ;; silent no-op) and so the per-frame retention store is observable.
            [re-frame.trace.tooling :as trace-tooling])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  ;; Clear the process-global trace-policy stores so a frame-scoped no-emit /
  ;; retention override written by one test never leaks into the next
  ;; (rf2-umsyo9 — these stores are SEPARATE from `frames`, which the reset
  ;; above does unwind).
  (trace/clear-frame-no-emit!)
  (trace-tooling/clear-trace-rings!)
  (rf/init! plain-atom/adapter)
  (test-fn))

;; The per-frame retention cap the winning config's `:rf.trace/events-retained`
;; override installed, read straight from the trace-tooling ring store (a
;; white-box read of the process-global store config publication must linearize).
;; nil when no override ring was written for the frame.
(defn- retained-cap [frame-id]
  ;; Double deref: `#'…/trace-rings` is the VAR, its value is the store ATOM,
  ;; and the atom's value is the rings map.
  (get-in @@#'re-frame.trace.tooling/trace-rings [frame-id :events-retained]))

(use-fixtures :each reset-runtime)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; A probe that trips a "reached the window" latch for the target id, then blocks
;; on a "release" latch — the deterministic reserved-construction window opener.
(defn- window-probe [target reached release]
  (fn [id]
    (when (= id target)
      (.countDown ^CountDownLatch reached)
      (.await ^CountDownLatch release 10 TimeUnit/SECONDS))))

;; ===========================================================================
;; Create/create: the transaction owner is the only actor allowed to publish.
;; ===========================================================================

(deftest same-id-contender-loses-before-owner-installs
  ;; A owns the id and pauses before installation. B fails promptly instead of
  ;; entering callbacks, allocating a container, or adopting A's future record.
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :race/x reached release)]
            (future (frame/upsert-frame! :race/x {:tags #{:a}})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A owns the id before B runs")
    (is (= :rf.error/frame-construction-in-progress
           (err-id #(frame/upsert-frame! :race/x {:tags #{:b}})))
        "same-id contender loses with the typed construction conflict")
    (is (nil? (frame/frame :race/x))
        "the owner's unpublished construction is invisible")
    (.countDown release)
    (is (= :race/x @a) "the reservation owner completes")
    (is (= #{:a} (get-in (frame/frame :race/x) [:config :tags]))
        "only the owner's config is published")
    (is (some? (frame/frame-state-container :race/x))
        "the published record has a real state container")))

(deftest losing-creator-does-not-allocate-an-unowned-state-container
  ;; A pauses before allocation. B fails at reservation admission and therefore
  ;; cannot allocate an opaque adapter value that nobody owns.
  (let [reached       (CountDownLatch. 1)
        release       (CountDownLatch. 1)
        allocations   (atom 0)
        original-make substrate/make-state-container]
    (with-redefs [substrate/make-state-container
                  (fn [initial]
                    (swap! allocations inc)
                    (original-make initial))]
      (let [a (binding [frame/*upsert-decide-probe*
                        (window-probe :cc/owned reached release)]
                (future (frame/upsert-frame! :cc/owned {:tags #{:a}})))]
        (is (.await reached 10 TimeUnit/SECONDS)
            "A reached the pre-create barrier without allocating")
        (is (= :rf.error/frame-construction-in-progress
               (err-id #(frame/upsert-frame! :cc/owned {:tags #{:b}})))
            "B loses before allocation")
        (is (zero? @allocations) "neither actor has allocated while A is paused")
        (.countDown release)
        (is (= :cc/owned @a) "A installs as the reservation owner")
        (is (= 1 @allocations)
            "exactly the installed frame's state container was allocated")))))

;; ===========================================================================
;; Re-register vs destroy: construction retains same-id ownership through
;; publication, so teardown cannot turn the provisional row into a zombie.
;; ===========================================================================

(deftest reregister-owner-rejects-concurrent-destroy-no-zombie
  (frame/upsert-frame! :zombie/x {:tags #{:orig}})
  (let [token-orig (frame/frame-incarnation-token :zombie/x)
        reached    (CountDownLatch. 1)
        release    (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :zombie/x reached release)]
            (future (frame/upsert-frame! :zombie/x {:tags #{:reregister}})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A owns the re-registration transaction")
    (is (nil? (frame/destroy-frame! :zombie/x))
        "same-id destroy loses promptly without disturbing the transaction")
    (.countDown release)
    (is (= :zombie/x @a) "A completes and returns the id")
    (is (identical? token-orig (frame/frame-incarnation-token :zombie/x))
        "the original incarnation survives")
    (is (= #{:reregister} (get-in (frame/frame :zombie/x) [:config :tags]))
        "the owner's metadata is published")
    (is (some? (frame/frame-state-container :zombie/x))
        "the record remains full, never a partial zombie")))

;; ===========================================================================
;; must-create (create-exclusive) — the ui.test primitive.
;; ===========================================================================

(deftest must-create-throws-typed-collision-on-an-already-live-id
  (frame/upsert-frame! :mc/taken {})
  (is (= :rf.error/frame-id-taken
         (err-id #(frame/upsert-frame! :mc/taken {:rf.frame/must-create? true})))
      "must-create against a LIVE id throws the typed :rf.error/frame-id-taken —
       it never adopts or surgically refreshes the pre-existing frame")
  (is (some? (frame/frame-state-container :mc/taken))
      "the pre-existing frame is untouched by the rejected exclusive construction"))

(deftest must-create-installs-cleanly-on-a-free-id
  (is (= :mc/free (frame/upsert-frame! :mc/free {:rf.frame/must-create? true}))
      "must-create on a free id installs normally and returns the id")
  (is (some? (frame/frame-state-container :mc/free)) "a full record was installed")
  (is (false? (contains? (:config (frame/frame :mc/free)) :rf.frame/must-create?))
      "the construction-only :rf.frame/must-create? key is stripped from stored config"))

(deftest must-create-owner-rejects-ordinary-same-id-contender
  ;; Exclusive and ordinary construction share the same per-id admission rule.
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :mc/race reached release)]
            (future (frame/upsert-frame! :mc/race {:rf.frame/must-create? true})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A owns the exclusive transaction")
    (is (= :rf.error/frame-construction-in-progress
           (err-id #(frame/upsert-frame! :mc/race {:tags #{:b}})))
        "ordinary same-id construction loses at admission")
    (.countDown release)
    (is (= :mc/race @a) "the must-create owner completes")
    (is (some? (frame/frame-state-container :mc/race))
        "the owner's full record is published")))

;; ===========================================================================
;; rf2-umsyo9 — a same-id loser must not overwrite the OWNER's frame-scoped
;; trace policy.
;;
;; `upsert-frame!`'s two frame-scoped TRACE POLICY writes — the `set-frame-
;; no-emit!` suppression flag (always written) and the `:rf.trace/events-
;; retained` retention override — live in process-global stores SEPARATE from
;; the `frames` registry. The per-id transaction keeps registry and auxiliary
;; publication under one owner, including rollback.
;; ===========================================================================

(deftest exclusive-create-loser-must-not-overwrite-winner-trace-policy
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a       (binding [frame/*upsert-decide-probe*
                          (window-probe :tp/race reached release)]
                  (future
                    (frame/upsert-frame! :tp/race
                                         {:rf.frame/must-create? true
                                          :rf.trace/frame-no-emit? false
                                          :rf.trace/events-retained 10})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A owns the id before policy publication")
    (is (= :rf.error/frame-construction-in-progress
           (err-id #(frame/upsert-frame! :tp/race
                                         {:rf.trace/frame-no-emit? true
                                          :rf.trace/events-retained 99})))
        "B loses before it can mutate either auxiliary policy store")
    (is (false? (trace/frame-trace-disabled? :tp/race))
        "no suppression policy is published while A is paused")
    (is (nil? (retained-cap :tp/race))
        "no retention policy is published while A is paused")
    (.countDown release)
    (is (= :tp/race @a) "the owner completes")
    (is (false? (trace/frame-trace-disabled? :tp/race))
        "the owner's no-emit policy is final")
    (is (= 10 (retained-cap :tp/race))
        "the owner's retention policy is final")))

(deftest reregister-owner-rejects-newer-policy-contender
  ;; A stages a provisional re-registration and pauses before policy publication.
  ;; B cannot become a "newer winner" while A owns the transaction.
  (frame/upsert-frame! :tp/successful
                       {:tags #{:initial}
                        :rf.trace/frame-no-emit? true
                        :rf.trace/events-retained 5})
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a       (binding [frame/*upsert-policy-probe*
                          (window-probe :tp/successful reached release)]
                  (future
                    (frame/upsert-frame! :tp/successful
                                         {:tags #{:a}
                                          :rf.trace/frame-no-emit? false
                                          :rf.trace/events-retained 10})))]
    (is (.await reached 10 TimeUnit/SECONDS) "A reached the pre-policy barrier")
    (is (= :rf.error/frame-construction-in-progress
           (err-id #(frame/upsert-frame! :tp/successful
                                         {:tags #{:b}
                                          :rf.trace/frame-no-emit? true
                                          :rf.trace/events-retained 99})))
        "B loses at same-id admission")
    (.countDown release)
    (is (= :tp/successful @a) "A returns successfully")
    (is (= #{:a} (get-in (frame/frame :tp/successful) [:config :tags]))
        "the authoritative frame record is A")
    (is (false? (trace/frame-trace-disabled? :tp/successful))
        "the no-emit auxiliary store is A")
    (is (= 10 (retained-cap :tp/successful))
        "the retention auxiliary store is A")))

(deftest construction-transaction-rejects-destroy-before-policy-publication
  ;; A owns a provisional re-registration through auxiliary policy publication,
  ;; so B cannot make that raw row lifecycle-dead underneath it.
  (frame/upsert-frame! :tp/destroy-race
                       {:rf.trace/frame-no-emit? false
                        :rf.trace/events-retained 5})
  (let [policy-reached (CountDownLatch. 1)
        release-policy (CountDownLatch. 1)
        a (binding [frame/*upsert-policy-probe*
                    (window-probe :tp/destroy-race policy-reached release-policy)]
            (future
              (frame/upsert-frame! :tp/destroy-race
                                   {:rf.trace/frame-no-emit? true
                                    :rf.trace/events-retained 77})))]
    (is (.await policy-reached 10 TimeUnit/SECONDS)
        "A staged config and paused before policy publication")
    (is (nil? (frame/destroy-frame! :tp/destroy-race))
        "B's same-id destroy loses promptly")
    (.countDown release-policy)
    (is (= :tp/destroy-race @a) "A completes")
    (is (some? (frame/frame :tp/destroy-race)) "the frame remains live")
    (is (true? (trace/frame-trace-disabled? :tp/destroy-race))
        "A publishes its no-emit policy")
    (is (= 77 (retained-cap :tp/destroy-race))
        "A publishes its retention policy")))

(deftest omitting-retention-on-reregistration-clears-frame-override
  (rf/configure! {:trace-buffer {:events-retained 7}})
  (frame/upsert-frame! :tp/inherit {:rf.trace/events-retained 99})
  (is (= 99 (retained-cap :tp/inherit))
      "precondition: the first config installed an explicit frame override")
  (frame/upsert-frame! :tp/inherit {:tags #{:override-removed}})
  (is (= 7 (retained-cap :tp/inherit))
      "omission restores the current process default instead of retaining 99")
  (is (false? (get-in @@#'re-frame.trace.tooling/trace-rings
                       [:tp/inherit :override?]))
      "the ring is marked inherited, so later process-default changes follow")
  (rf/configure! {:trace-buffer {:events-retained 3}})
  (is (= 3 (retained-cap :tp/inherit))
      "a later process-default change reaches the now-inherited frame"))
