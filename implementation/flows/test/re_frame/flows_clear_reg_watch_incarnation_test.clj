(ns re-frame.flows-clear-reg-watch-incarnation-test
  "rf2-vxgfnd.155 — exact-incarnation fence for the callback-bearing flow
  registry lifecycle ops.

  `clear-flow` and `reg-flow` REPLACEMENT each perform callback-bearing
  container writes (the output-mark refresh via `swap-elision-slot!`, and — in
  the non-drain path — the app-db path vacation via `swap-frame-db!`) BEFORE
  their bare-id registry / dirty-check / commit-epoch tail. A synchronous
  container watch fired during one of those writes can destroy incarnation A
  and publish a same-id B. Before the fix the stale A tail then dissociated B's
  flow row, dropped B's dirty-check cache, and bumped B's commit epoch by bare
  id — while the reserved-fx postcheck only observed the loss AFTER the whole
  handler returned.

  After the fix each callback-bearing write is exact-incarnation aware (the
  commit-epoch bump is fenced once A is lost) and each lifecycle op rechecks A's
  live continuation after the callback, aborting before any bare-id registry /
  cache / dedup mutation reaches B. Only A's write that physically linearized
  before the loss stands; B's stores stay byte-identical.

  The watch is driven SYNCHRONOUSLY on the single JVM test thread (the watch
  destroys A + publishes B reentrantly), so the cross-incarnation ordering is
  fully deterministic without threads. A one-shot `armed?` CAS makes the watch
  fire exactly once, during the lifecycle op under test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- install-watching-adapter!
  "Install a plain-atom-backed adapter whose `replace-container!` runs `on-write`
  (a 0-arg thunk) exactly once, the first time a container write happens while
  `armed?` holds true. The physical write always lands FIRST so A's write that
  linearized before the loss stands."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

;; ---------------------------------------------------------------------------
;; clear-flow — the output-mark write's watch loses A; the stale tail must not
;; dissociate B's flow row, drop B's dirty-check cache, or bump B's commit epoch.
;; ---------------------------------------------------------------------------

(deftest clear-flow-output-mark-watch-loss-does-not-corrupt-successor
  ;; rf2-vxgfnd.155 (red before fix). Clearing A's flow removes its output-mark
  ;; declaration through a container write; that write's synchronous watch
  ;; destroys A and publishes same-id B (with B's own flow row + dirty-check
  ;; cache + output-mark declaration). Before the fix A's stale clear-flow tail
  ;; then dissociated B's flow row and dropped B's dirty-check cache, and the
  ;; bare-id mark write bumped B's commit epoch.
  (let [id            :flow.incarnation/clear-loss
        armed?        (atom false)
        b-flow-row    (atom ::unset)
        b-dirty       (atom ::unset)
        b-commit      (atom ::unset)
        b-sensitive   (atom ::unset)
        b-token       (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        ;; The mark-write watch: destroy A, publish same-id B with its own flow
        ;; state, and snapshot B's stores for the byte-identical assertions.
        (rf.frame/destroy-frame! id)
        (rf/make-frame {:id id})
        (rf/reg-flow :flow.incarnation/f
          {:frame id :inputs [[:bn]] :output-path [:bout] :sensitive [[:bout]]}
          (fn [n] (or n 0)))
        (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/f [::b-input])
        (reset! b-token (rf.frame/frame-incarnation-token id))
        (reset! b-flow-row (get-in (rf.flows.registry/flows-snapshot)
                                   [id :flow.incarnation/f]))
        (reset! b-dirty (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/f))
        (reset! b-commit (rf.frame/frame-commit-epoch id))
        (reset! b-sensitive (rf.elision/sensitive-declarations id))))
    (try
      (rf/make-frame {:id id})
      ;; A's flow declares an output classification so clearing it produces a
      ;; real elision-registry (runtime-db) container write — the watch seam.
      (rf/reg-flow :flow.incarnation/f
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      (reset! armed? true)
      (is (nil? (rf/clear :flow :flow.incarnation/f {:frame id}))
          "clear-flow returns nil; its stale tail is aborted after the watch")
      (let [token-b @b-token]
        (is (some? token-b) "the mark-write watch published a same-id B")
        (is (identical? token-b (rf.frame/frame-incarnation-token id))
            "B remains the live incarnation")
        (is (= @b-flow-row (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/f]))
            "A's stale clear-flow tail never dissociates B's flow row")
        (is (= @b-dirty
               (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/f))
            "A's stale clear-flow tail never drops B's dirty-check cache")
        (is (= @b-commit (rf.frame/frame-commit-epoch id))
            "A's stale mark write never bumps B's commit epoch")
        (is (= @b-sensitive (rf.elision/sensitive-declarations id))
            "A's stale clear-flow tail never rewrites B's output-mark declaration"))
      (finally
        (restore-plain-adapter!)))))

;; ---------------------------------------------------------------------------
;; reg-flow REPLACEMENT — the output-mark refresh write's watch loses A; the
;; stale post-mark tail (dirty-check drop + dedup trace + commit-epoch bump)
;; must not reach B.
;; ---------------------------------------------------------------------------

(deftest reg-flow-replacement-mark-watch-loss-does-not-corrupt-successor
  ;; rf2-vxgfnd.155 (red before fix). Replacing A's flow refreshes its output
  ;; marks through a container write; that write's synchronous watch destroys A
  ;; and publishes same-id B (with B's own flow row + dirty-check cache). Before
  ;; the fix A's stale post-mark tail then dropped B's dirty-check cache (and
  ;; emitted a bare-id dedup trace), and the bare-id mark write bumped B's
  ;; commit epoch.
  (let [id            :flow.incarnation/reg-loss
        armed?        (atom false)
        b-flow-row    (atom ::unset)
        b-dirty       (atom ::unset)
        b-commit      (atom ::unset)
        b-token       (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        (rf.frame/destroy-frame! id)
        (rf/make-frame {:id id})
        (rf/reg-flow :flow.incarnation/g
          {:frame id :inputs [[:bn]] :output-path [:bout] :sensitive [[:bout]]}
          (fn [n] (or n 0)))
        (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/g [::b-input])
        (reset! b-token (rf.frame/frame-incarnation-token id))
        (reset! b-flow-row (get-in (rf.flows.registry/flows-snapshot)
                                   [id :flow.incarnation/g]))
        (reset! b-dirty (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/g))
        (reset! b-commit (rf.frame/frame-commit-epoch id))))
    (try
      (rf/make-frame {:id id})
      (rf/reg-flow :flow.incarnation/g
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      ;; Prime a dirty-check row for A so the (unfixed) stale
      ;; drop-frame-flow-last-inputs!
      ;; would have something to drop off the successor.
      (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/g [::a-input])
      (reset! armed? true)
      ;; Replacement registration: refreshes marks (the watch seam), then runs
      ;; the post-mark dirty-cache + dedup tail.
      (rf/reg-flow :flow.incarnation/g
        {:frame id :inputs [[:n2]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (* 2 (or n 0))))
      (let [token-b @b-token]
        (is (some? token-b) "the mark-refresh watch published a same-id B")
        (is (identical? token-b (rf.frame/frame-incarnation-token id))
            "B remains the live incarnation")
        (is (= @b-flow-row (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/g]))
            "A's stale reg-flow tail never rewrites B's flow row")
        (is (= @b-dirty
               (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/g))
            "A's stale post-mark tail never drops B's dirty-check cache")
        (is (= @b-commit (rf.frame/frame-commit-epoch id))
            "A's stale mark write never bumps B's commit epoch"))
      (finally
        (restore-plain-adapter!)))))

;; ---------------------------------------------------------------------------
;; Green control — when A retains ownership through a NON-destroying watch, the
;; ordinary clear-flow behaviour (row removal, cache drop, mark removal, commit-
;; epoch bump) is preserved. A wrongly-fencing exact path would silently no-op.
;; ---------------------------------------------------------------------------

(deftest clear-flow-with-live-owner-still-clears-row-cache-and-marks
  ;; rf2-vxgfnd.155 mutation tooth. The exact-incarnation writes must NOT
  ;; suppress the normal clear when A stays live: the flow row and dirty-check
  ;; cache are removed, the output-mark declaration is cleared, and the mark
  ;; write's commit epoch advances.
  (let [id          :flow.incarnation/clear-live
        armed?      (atom false)
        watch-runs  (atom 0)]
    (install-watching-adapter!
      armed?
      (fn [] (swap! watch-runs inc)))          ; observe only — A stays live
    (try
      (rf/make-frame {:id id})
      (rf/reg-flow :flow.incarnation/h
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/h [::a-input])
      (let [epoch-before (rf.frame/frame-commit-epoch id)]
        (reset! armed? true)
        (is (nil? (rf/clear :flow :flow.incarnation/h {:frame id}))
            "clear-flow completes normally against the live owner")
        (is (= 1 @watch-runs) "the container watch fired on the mark write")
        (is (nil? (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/h]))
            "the flow row is removed for the live owner")
        (is (nil? (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/h))
            "the dirty-check cache row is dropped for the live owner")
        (is (empty? (rf.elision/sensitive-declarations id))
            "the flow's output-mark declaration is cleared for the live owner")
        (is (> (rf.frame/frame-commit-epoch id) epoch-before)
            "the mark write advanced the live owner's commit epoch"))
      (finally
        (restore-plain-adapter!)))))

;; ===========================================================================
;; rf2-mybhk3 — the APP-DB PATH-VACATION counterpart.
;;
;; The three fixtures above drive their loss watch through the runtime-db
;; output-MARK write: the flow output leaf is never materialized, so
;; `vacate-output-path!`'s `swap-frame-db-exact!` sees `new-db` identical to
;; `db` and issues no container write — the FIRST container write while armed is
;; the mark refresh, and the direct app-db vacation helper (its exact
;; commit-epoch fence + its post-vacation liveness recheck) was never executed
;; by a merged fixture. A regression to the bare-id `swap-frame-db!`, an
;; unfenced id-keyed epoch bump, or a dropped post-vacation recheck could then
;; corrupt a same-id B while every merged fixture stayed green.
;;
;; These fixtures MATERIALIZE A's output leaf first, so clear-flow's / the
;; reg-flow output-path MOVE's FIRST callback-bearing write IS the app-db path
;; vacation. That write's synchronous watch destroys A and publishes same-id B.
;; A's vacating write physically linearized into A's captured (now-detached)
;; container; the exact helper's fenced commit-epoch bump plus the lifecycle
;; op's post-vacation liveness recheck keep B's app-db leaf, flow row,
;; dirty-check cache, commit epoch, and output-mark declaration byte-identical.
;; ===========================================================================

(defn- a-detached-app-db
  "Read A's detached physical container's app-db partition. Destroying A leaves
  the `:frame-state` atom (captured before the loss) holding the last-installed
  value — only the projection reactions are disposed — so the vacation A wrote
  before losing ownership is observable HERE, isolated from B's fresh container."
  [container]
  (get (rf.substrate.adapter/read-container container) rf.frame/app-partition-key))

;; ---------------------------------------------------------------------------
;; clear-flow — the app-db path-VACATION write's watch loses A; the stale tail
;; must not touch B's app-db leaf, flow row, dirty-check cache, commit epoch, or
;; output-mark declaration, and the vacation must land in A's detached container.
;; ---------------------------------------------------------------------------

(deftest clear-flow-app-db-vacation-watch-loss-does-not-corrupt-successor
  ;; rf2-mybhk3 (red before fix). A's output leaf is materialized, so clearing A
  ;; issues a real app-db path vacation via `swap-frame-db-exact!` — the FIRST
  ;; container write. Its synchronous watch destroys A and publishes same-id B
  ;; (with B's own app-db leaf + flow row + dirty-check cache + output marks).
  ;; A's vacation stands only in A's detached container; the fence keeps B's
  ;; stores byte-identical. Bare-id vacation would bump B's id-keyed commit
  ;; epoch; a dropped post-vacation recheck would let the stale tail dissociate
  ;; B's flow row / drop B's dirty cache / rewrite B's marks.
  (let [id            :flow.incarnation/clear-vacate-loss
        armed?        (atom false)
        a-container   (atom nil)
        b-flow-row    (atom ::unset)
        b-dirty       (atom ::unset)
        b-commit      (atom ::unset)
        b-app-db      (atom ::unset)
        b-sensitive   (atom ::unset)
        b-token       (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        ;; The vacation-write watch: destroy A, publish same-id B with its own
        ;; materialized output leaf + flow state, and snapshot B's stores.
        (rf.frame/destroy-frame! id)
        (rf/make-frame {:id id})
        (rf.frame/swap-frame-db! id assoc :bout ::b-sentinel)
        (rf/reg-flow :flow.incarnation/f
          {:frame id :inputs [[:bn]] :output-path [:bout] :sensitive [[:bout]]}
          (fn [n] (or n 0)))
        (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/f [::b-input])
        (reset! b-token (rf.frame/frame-incarnation-token id))
        (reset! b-flow-row (get-in (rf.flows.registry/flows-snapshot)
                                   [id :flow.incarnation/f]))
        (reset! b-dirty (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/f))
        (reset! b-commit (rf.frame/frame-commit-epoch id))
        (reset! b-app-db (rf.frame/frame-app-db-value id))
        (reset! b-sensitive (rf.elision/sensitive-declarations id))))
    (try
      (rf/make-frame {:id id})
      (rf/reg-flow :flow.incarnation/f
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      ;; Materialize A's output leaf so clearing it is a REAL app-db path
      ;; vacation (not the no-op the unmaterialized loss fixtures hit) — the
      ;; watch then fires on `swap-frame-db-exact!`, before the mark write.
      (rf.frame/swap-frame-db! id assoc :out ::a-output)
      ;; Capture A's physical container to prove the vacation linearized HERE.
      (reset! a-container (:frame-state (rf.frame/frame id)))
      (reset! armed? true)
      (is (nil? (rf/clear :flow :flow.incarnation/f {:frame id}))
          "clear-flow returns nil; its stale tail is aborted after the vacation watch")
      (let [token-b @b-token]
        (is (some? token-b) "the app-db-vacation watch published a same-id B")
        (is (identical? token-b (rf.frame/frame-incarnation-token id))
            "B remains the live incarnation")
        (is (not (contains? (a-detached-app-db @a-container) :out))
            "A's vacation physically linearized into A's own detached container")
        (is (= @b-app-db (rf.frame/frame-app-db-value id))
            "A's exact-incarnation vacation never touches B's app-db")
        (is (= ::b-sentinel (get (rf.frame/frame-app-db-value id) :bout))
            "B's own materialized output leaf stands untouched")
        (is (= @b-flow-row (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/f]))
            "A's stale clear-flow tail never dissociates B's flow row")
        (is (= @b-dirty
               (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/f))
            "A's stale clear-flow tail never drops B's dirty-check cache")
        (is (= @b-commit (rf.frame/frame-commit-epoch id))
            "A's fenced exact vacation never bumps B's commit epoch")
        (is (= @b-sensitive (rf.elision/sensitive-declarations id))
            "A's stale clear-flow tail never rewrites B's output-mark declaration"))
      (finally
        (restore-plain-adapter!)))))

;; ---------------------------------------------------------------------------
;; reg-flow output-path MOVE — vacating the OLD (materialized) path is the
;; callback-bearing write; its watch loses A. The stale post-vacation tail must
;; not reach B, and the vacation must land in A's detached container.
;; ---------------------------------------------------------------------------

(deftest reg-flow-move-app-db-vacation-watch-loss-does-not-corrupt-successor
  ;; rf2-mybhk3 (red before fix). Re-registering A with a MOVED output-path
  ;; vacates the old (materialized) leaf via `swap-frame-db-exact!` — the FIRST
  ;; container write. Its synchronous watch destroys A and publishes same-id B.
  ;; A's vacation stands only in A's detached container; the fence keeps B's
  ;; stores byte-identical. Bare-id vacation would bump B's id-keyed commit
  ;; epoch; a dropped post-vacation recheck would let the stale tail drop B's
  ;; dirty cache / emit a bare-id dedup trace against B.
  (let [id            :flow.incarnation/reg-move-loss
        armed?        (atom false)
        a-container   (atom nil)
        b-flow-row    (atom ::unset)
        b-dirty       (atom ::unset)
        b-commit      (atom ::unset)
        b-app-db      (atom ::unset)
        b-token       (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        (rf.frame/destroy-frame! id)
        (rf/make-frame {:id id})
        (rf.frame/swap-frame-db! id assoc :bout ::b-sentinel)
        (rf/reg-flow :flow.incarnation/g
          {:frame id :inputs [[:bn]] :output-path [:bout] :sensitive [[:bout]]}
          (fn [n] (or n 0)))
        (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/g [::b-input])
        (reset! b-token (rf.frame/frame-incarnation-token id))
        (reset! b-flow-row (get-in (rf.flows.registry/flows-snapshot)
                                   [id :flow.incarnation/g]))
        (reset! b-dirty (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/g))
        (reset! b-commit (rf.frame/frame-commit-epoch id))
        (reset! b-app-db (rf.frame/frame-app-db-value id))))
    (try
      (rf/make-frame {:id id})
      (rf/reg-flow :flow.incarnation/g
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      ;; Materialize A's OLD output leaf so the path move actually vacates it.
      (rf.frame/swap-frame-db! id assoc :out ::a-output)
      (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/g [::a-input])
      (reset! a-container (:frame-state (rf.frame/frame id)))
      (reset! armed? true)
      ;; Replacement with a MOVED output-path: the old-path vacation is the watch
      ;; seam, then the post-vacation dirty-cache drop + dedup trace tail.
      (rf/reg-flow :flow.incarnation/g
        {:frame id :inputs [[:n2]] :output-path [:out2] :sensitive [[:out2]]}
        (fn [n] (* 2 (or n 0))))
      (let [token-b @b-token]
        (is (some? token-b) "the path-move vacation watch published a same-id B")
        (is (identical? token-b (rf.frame/frame-incarnation-token id))
            "B remains the live incarnation")
        (is (not (contains? (a-detached-app-db @a-container) :out))
            "A's old-path vacation physically linearized into A's own detached container")
        (is (= @b-app-db (rf.frame/frame-app-db-value id))
            "A's exact-incarnation vacation never touches B's app-db")
        (is (= ::b-sentinel (get (rf.frame/frame-app-db-value id) :bout))
            "B's own materialized output leaf stands untouched")
        (is (= @b-flow-row (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/g]))
            "A's stale reg-flow tail never rewrites B's flow row")
        (is (= @b-dirty
               (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/g))
            "A's stale post-vacation tail never drops B's dirty-check cache")
        (is (= @b-commit (rf.frame/frame-commit-epoch id))
            "A's fenced exact vacation never bumps B's commit epoch"))
      (finally
        (restore-plain-adapter!)))))

;; ---------------------------------------------------------------------------
;; Green control — when A retains ownership through a NON-destroying watch, the
;; exact-incarnation app-db vacation MUST still remove the output leaf and
;; advance the commit epoch. A wrongly-fencing exact path would silently strand
;; the derived value.
;; ---------------------------------------------------------------------------

(deftest clear-flow-app-db-vacation-with-live-owner-still-vacates-leaf-and-bumps-epoch
  ;; rf2-mybhk3 mutation tooth. The exact-incarnation vacation must NOT suppress
  ;; the normal leaf removal + commit-epoch advance when A stays live.
  (let [id          :flow.incarnation/clear-vacate-live
        armed?      (atom false)
        watch-runs  (atom 0)]
    (install-watching-adapter!
      armed?
      (fn [] (swap! watch-runs inc)))          ; observe only — A stays live
    (try
      (rf/make-frame {:id id})
      (rf/reg-flow :flow.incarnation/k
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      (rf.frame/swap-frame-db! id assoc :out ::a-output)
      (let [epoch-before (rf.frame/frame-commit-epoch id)]
        (reset! armed? true)
        (is (nil? (rf/clear :flow :flow.incarnation/k {:frame id}))
            "clear-flow completes normally against the live owner")
        (is (= 1 @watch-runs) "the container watch fired on the app-db vacation write")
        (is (not (contains? (rf.frame/frame-app-db-value id) :out))
            "the materialized output leaf is vacated from the live owner's app-db")
        (is (> (rf.frame/frame-commit-epoch id) epoch-before)
            "the exact-incarnation vacation advanced the live owner's commit epoch")
        (is (nil? (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/k]))
            "the flow row is removed for the live owner"))
      (finally
        (restore-plain-adapter!)))))
