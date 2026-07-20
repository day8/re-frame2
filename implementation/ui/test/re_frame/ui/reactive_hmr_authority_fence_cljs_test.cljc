(ns re-frame.ui.reactive-hmr-authority-fence-cljs-test
  "rf2-vxgfnd.214 — the ViewCell commit fences PUBLICATION against an HMR
  body-authority change that lands DURING the commit.

  #5817 made `commit!` reject a capture whose authoritative body revision was
  already stale at step 1. But step 1 samples the authority ONCE, before the
  callback-capable staging window: the `:pre-acquire` barrier, `obs/acquire!` +
  sub-cache construction, and the `:post-acquire` barrier can each SYNCHRONOUSLY
  register a newer body revision (a same-shell re-registration in the
  render→commit gap). Before this bead the revision-0 capture published anyway,
  so the stale render temporarily OWNED dependencies — violating the HMR
  contract (03 §10) that a stale capture obtains no ownership.

  The fix is one FINAL authority fence at the narrowest publication boundary —
  after all staging, evidence reads, and callback-capable hooks, immediately
  before the state publication, with nothing callback-capable between the fence
  and the publish. If the body authority moved, only the newly staged handles are
  released (reverse acquisition order), the prior committed set / values /
  revision / lifecycle stay untouched, and the commit returns `:stale` exactly
  as step 1 does.

  Staging is deterministic and host-agnostic via the `reactive/*commit-barrier*`
  test seam, which fires synchronously INSIDE `commit!` at `:pre-acquire`,
  `:post-stage-acquire` (the acquisition/cache-callback window), and
  `:post-acquire`. The fixtures re-register the same view at each of those
  points, so no threads are needed; `.cljc` ending `-cljs-test` graft-checks on
  node (`test:cljs`) AND JVM (`clojure -M:test`) against the REAL observation
  port + sub-cache (plain-atom adapter).

  MUTATION TOOTH: every fence case asserts `commit!` returned `:stale` and that
  NO staged ownership survived. Deleting or moving the final fence (so the
  revision-0 capture publishes) flips each such assertion red — the fence is the
  sole thing standing between a mid-commit re-registration and a stale publish."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.fingerprint        :as fp]
            [re-frame.ui.reactive           :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (reactive/reset-scheduler!)
    (try (t) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)
(def ^:private hs0 (fp/hook-signature-hash {:locals [] :effects []}))
(def ^:private hs1 (fp/hook-signature-hash {:locals ['draft] :effects []}))

(defn- sub-cache [] (:sub-cache (frame/frame fid)))
(defn- entry [q] (get @(sub-cache) q))
(defn- ref-count [q] (:ref-count (entry q)))
(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- tk [q] [:sub fid q])

(defn- capture-of
  "A fresh render capture for `sites` (a vector of [sid query]) at the cell's
  current generation — ownership-free, exactly what React would carry into the
  layout effect."
  [cell sites]
  (second
    (rf/with-frame fid
      (reactive/with-capture
        cell (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites))))))

(defn- reregister-barrier
  "A `*commit-barrier*` that, at `at-phase`, re-registers `vid` at `hook-sig`
  (advancing the authoritative body revision under the running commit)."
  [vid at-phase hook-sig]
  (fn [phase _cell]
    (when (= phase at-phase)
      (reactive/register-view-generation! vid hook-sig))))

;; ===========================================================================
;; A mid-commit re-registration at EACH callback-capable phase is fenced
;; ===========================================================================

(defn- fences-at-phase
  "Register `vid` at revision 0, render a revision-0 capture with one sub site,
  then re-register at `at-phase` (with `hook-sig`) during the commit. The commit
  must return `:stale` and touch no ownership."
  [at-phase hook-sig]
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::fence-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)
        cap  (capture-of cell [[::site [:r/a]]])]
    (is (= 0 (:generation cap)) "capture formed at body revision 0")
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (let [result (binding [reactive/*commit-barrier*
                           (reregister-barrier vid at-phase hook-sig)]
                   (reactive/commit! cell cap))]
      (is (= :stale result)
          (str "a re-registration at " at-phase " fences the stale-body publish"))
      (is (= :fresh (reactive/lifecycle cell))
          "a fenced candidate never connects")
      (is (empty? (reactive/committed-sites cell))
          "no dependency set published")
      (is (nil? (entry [:r/a]))
          "the staged handle was released — the node has zero owners (disposed)")
      (is (= 0 (reactive/revision cell))
          "no revision advance — the shell's re-registration drives the re-render"))))

(deftest reregistration-at-pre-acquire-is-fenced
  (testing "same-signature body replacement at :pre-acquire"
    (fences-at-phase :pre-acquire hs0)))

(deftest reregistration-during-acquisition-is-fenced
  (testing "same-signature body replacement in the acquisition/cache window"
    (fences-at-phase :post-stage-acquire hs0)))

(deftest reregistration-at-post-acquire-is-fenced
  (testing "same-signature body replacement at :post-acquire (the last hook)"
    (fences-at-phase :post-acquire hs0)))

(deftest changed-signature-remount-registration-is-fenced
  (testing "a CHANGED hook signature (remount generation) still advances body
            revision, so its mid-commit registration is fenced too"
    (fences-at-phase :post-acquire hs1)))

;; ===========================================================================
;; The fence preserves the prior committed state and releases ONLY the staged
;; handles (reverse order, exactly once)
;; ===========================================================================

(deftest fence-preserves-prior-committed-and-releases-only-staged
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (rf/reg-sub :r/c (fn [db _] (:c db)))
  (seed! {:a 1 :b 2 :c 3})
  (let [vid  ::preserve-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)]
    ;; A clean revision-0 commit installs the prior committed set (site :a).
    (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))
    (let [handle-a (reactive/committed-handle cell (tk [:r/a]))
          rev0    (reactive/revision cell)]
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count [:r/a])) "prior committed handle installed")
      (testing "a later same-generation render retains :a and stages :b + :c,
                then a re-registration fences the publish"
        (let [cap    (capture-of cell [[::a [:r/a]] [::b [:r/b]] [::c [:r/c]]])
              result (binding [reactive/*commit-barrier*
                               (reregister-barrier vid :post-acquire hs0)]
                       (reactive/commit! cell cap))]
          (is (= :stale result) "the stale-body publish is fenced")
          (testing "prior committed :a is wholly untouched"
            (is (identical? handle-a (reactive/committed-handle cell (tk [:r/a])))
                "the retained handle keeps its exact identity")
            (is (= 1 (ref-count [:r/a])) ":a never re-acquired or released")
            (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell))
                "the published dependency set is unchanged — no staged site leaked")
            (is (= :connected (reactive/lifecycle cell)) "lifecycle unchanged")
            (is (= rev0 (reactive/revision cell)) "revision unchanged"))
          (testing "BOTH staged handles (:b, :c) were released exactly once"
            (is (nil? (entry [:r/b])) ":b staged then released → zero-owner node")
            (is (nil? (entry [:r/c])) ":c staged then released → zero-owner node")))))))

;; ===========================================================================
;; The ordinary unchanged-authority commit is unaffected — it publishes once
;; ===========================================================================

(deftest unchanged-authority-commit-publishes-once
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::normal-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)]
    (testing "no mid-commit re-registration ⇒ the capture publishes and connects"
      (let [result (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))]
        (is (identical? cell result) "a clean commit returns the cell, not :stale")
        (is (= :connected (reactive/lifecycle cell)))
        (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell)))
        (is (= 1 (ref-count [:r/a])) "exactly one owner acquired")
        (is (= 1 (:value (reactive/committed-site cell ::a)))))
      (let [handle-a (reactive/committed-handle cell (tk [:r/a]))]
        (testing "a second unchanged-authority render retains the exact handle"
          (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))
          (is (identical? handle-a (reactive/committed-handle cell (tk [:r/a])))
              "acquire-before-release kept the live handle untouched")
          (is (= 1 (ref-count [:r/a])) "no churn on a no-op reconcile"))))))

;; ===========================================================================
;; rf2-vxgfnd.260 — COMPLETE the final-fence proof: both commit-authority axes
;; + a byte-for-byte rollback that leaves the prior committed set untouched.
;;
;; The fixtures above pin ONE of the two authorities `stale-body-authority?`
;; consults — the registered HMR slot revision. The suite below completes the
;; obligation: the cell-LOCAL generation arm (a direct/headless cell), an
;; instrumented reverse-order/exactly-once staged rollback, a non-empty
;; to-release drop preserved byte-for-byte, and a re-registration landing INSIDE
;; the acquire/cache-construction window.
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Axis 1 — the cell-LOCAL generation arm (a direct/unregistered cell)
;; ---------------------------------------------------------------------------
;; `stale-body-authority?` re-reads the cell's OWN `:generation` at the
;; publication boundary. A direct/headless cell has no registered slot, so the
;; registry arm no-ops and this cell-local re-read is the SOLE catch: a
;; synchronous `advance-generation!` (a sync/remount) landing mid-commit —
;; after step 1 sampled the generation — must still fence the revision-0 publish.
;; Removing the `(not= cap-gen state-gen)` arm lets the stale capture connect.

(deftest direct-cell-local-generation-advance-mid-commit-is-fenced
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::direct-view                         ;; deliberately NEVER registered
        cell (reactive/make-cell vid 0)
        cap  (capture-of cell [[::a [:r/a]]])]
    (is (= 0 (:generation cap)) "capture formed at cell body generation 0")
    (is (nil? (reactive/registered-view-revision vid))
        "precondition: no registered HMR slot — the registry authority arm no-ops")
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (let [result (binding [reactive/*commit-barrier*
                           (fn [phase c]
                             (when (= phase :post-acquire)
                               ;; a sync/remount advances the cell's OWN body
                               ;; generation AFTER step 1 sampled it
                               (reactive/advance-generation! c 1)))]
                   (reactive/commit! cell cap))]
      (is (= :stale result)
          "the fresh cell-local generation re-read fences the revision-0 publish")
      (is (= :fresh (reactive/lifecycle cell)) "a fenced candidate never connects")
      (is (empty? (reactive/committed-sites cell)) "no dependency set published")
      (is (nil? (entry [:r/a])) "the staged handle was released — zero-owner node")
      (is (= 1 (reactive/generation cell)) "the cell carries the advanced body generation")
      (is (= 0 (reactive/revision cell))
          "no revision advance — the sync/remount drives the re-render"))))

;; ---------------------------------------------------------------------------
;; Rollback: a DROPPED prior site (non-empty to-release) survives byte-for-byte
;; ---------------------------------------------------------------------------
;; When the fence fires, step 7 (release the dropped/retargeted prior handles)
;; must NOT have run — the prior committed record keeps its exact handle, query,
;; and value, and its node keeps its single owner. Releasing to-release ahead of
;; the final fence would strand the dropped site's node at zero owners.

(deftest fence-preserves-a-dropped-prior-site-byte-for-byte
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [vid  ::drop-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)]
    (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))
    (let [prior   (reactive/committed-site cell ::a)
          handle-a (:handle prior)
          value-a (:value prior)
          query-a (:query prior)
          rev0    (reactive/revision cell)]
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count [:r/a])) "prior committed handle installed")
      (testing "a later render DROPS :a (→ to-release) and stages :b; a
                re-registration then fences the publish"
        (let [cap    (capture-of cell [[::b [:r/b]]])   ;; :a absent, :b new
              result (binding [reactive/*commit-barrier*
                               (reregister-barrier vid :post-acquire hs0)]
                       (reactive/commit! cell cap))]
          (is (= :stale result) "the stale-body publish is fenced")
          (testing "the DROPPED :a is preserved byte-for-byte"
            (let [now (reactive/committed-site cell ::a)]
              (is (identical? handle-a (:handle now))
                  "prior handle identity unchanged — step 7 never released it")
              (is (identical? query-a (:query now)) "prior query object unchanged")
              (is (= value-a (:value now)) "prior value unchanged"))
            (is (= 1 (ref-count [:r/a]))
                ":a keeps its single owner — a pre-fence release would zero it")
            (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell))
                "the published dependency set is unchanged — the staged site never leaked")
            (is (= :connected (reactive/lifecycle cell)) "lifecycle unchanged")
            (is (= rev0 (reactive/revision cell)) "revision unchanged"))
          (testing "the staged :b was released"
            (is (nil? (entry [:r/b])) ":b staged then released → zero-owner node")))))))

;; ---------------------------------------------------------------------------
;; Rollback ordering: staged handles release EXACTLY ONCE, in REVERSE order
;; ---------------------------------------------------------------------------
;; A zero-owner read (`entry` nil) cannot distinguish one release from two, nor
;; forward order from reverse. Instrument the observation port: the fence must
;; release the newly staged :b/:c in reverse acquisition order (log [:c :b]),
;; each exactly once, and must never touch the RETAINED prior :a.

(deftest fence-releases-staged-handles-in-reverse-order-exactly-once
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (rf/reg-sub :r/c (fn [db _] (:c db)))
  (seed! {:a 1 :b 2 :c 3})
  (let [vid  ::order-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)]
    (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))   ;; connect owning :a
    (let [acq          (atom [])           ;; [handle query] in acquisition order
          rel          (atom [])           ;; query in release order
          real-acquire obs/acquire!
          real-release obs/release!
          handle->query (fn [handle]
                         (some (fn [[l q]] (when (identical? l handle) q)) @acq))
          cap          (capture-of cell [[::a [:r/a]] [::b [:r/b]] [::c [:r/c]]])]
      (with-redefs [obs/acquire! (fn [target on-change]
                                   (let [handle (real-acquire target on-change)]
                                     (swap! acq conj [handle (:query target)])
                                     handle))
                    obs/release! (fn [handle]
                                   (swap! rel conj (handle->query handle))
                                   (real-release handle))]
        (let [result (binding [reactive/*commit-barrier*
                               (reregister-barrier vid :post-acquire hs0)]
                       (reactive/commit! cell cap))]
          (is (= :stale result) "the stale-body publish is fenced")
          (is (= [[:r/b] [:r/c]] (mapv second @acq))
              "only the NEW sites acquire (:a retained, never re-acquired)")
          (is (= [[:r/c] [:r/b]] @rel)
              "the fence released the staged handles in REVERSE acquisition order")
          (is (= 2 (count @rel)) "each staged handle released EXACTLY once")
          (is (not (some #{[:r/a]} @rel)) "the RETAINED prior :a was never released"))))))

;; ---------------------------------------------------------------------------
;; A re-registration landing INSIDE the acquire/cache-construction window
;; ---------------------------------------------------------------------------
;; The `:post-stage-acquire` barrier fires AFTER every acquisition returns, so
;; it cannot prove the fence catches an authority change that lands WHILE a
;; handle is being acquired (a sub-cache construction hook synchronously
;; re-registering the same view). Inject it through a port wrapper instead.

(deftest reregistration-inside-the-acquire-window-is-fenced
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid          ::acquire-window-view
        _            (reactive/register-view-generation! vid hs0)
        cell         (reactive/make-cell vid 0)
        cap          (capture-of cell [[::a [:r/a]]])
        real-acquire obs/acquire!
        fired        (atom 0)]
    (is (= 0 (:generation cap)))
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (with-redefs [obs/acquire!
                  (fn [target on-change]
                    ;; a cache-construction hook re-registers the same view
                    ;; WHILE the handle is acquired — advancing body revision
                    (swap! fired inc)
                    (reactive/register-view-generation! vid hs0)
                    (real-acquire target on-change))]
      (let [result (reactive/commit! cell cap)]
        (is (= 1 @fired) "the re-registration fired inside the single acquire")
        (is (= :stale result)
            "a re-registration landing mid-acquire advances body revision and is fenced")
        (is (= :fresh (reactive/lifecycle cell)) "a fenced candidate never connects")
        (is (empty? (reactive/committed-sites cell)) "no dependency set published")
        (is (nil? (entry [:r/a])) "the staged handle was released — zero-owner node")))))

;; ===========================================================================
;; rf2-77pb08 — SETTLE the final body-authority validation WITH publication
;; across TWO axes of DIFFERING STRENGTH.
;;
;; PR #5938 (.214/.260) proved the fence catches an authority change landing at
;; or before the final SAMPLE. But that fence sampled the authority and then
;; performed an INDEPENDENT `swap!` to publish — a check-to-use gap: a
;; concurrent `advance-generation!` (cell-local axis) or same-view
;; re-registration (registry axis) landing AFTER the sample but BEFORE the swap
;; published a stale generation-0 capture that connected and owned handles.
;;
;; `publish-commit!` closes that gap ASYMMETRICALLY — each axis carries the
;; strength it can on re-frame2's single-threaded CLJS host:
;;   - CELL-LOCAL axis — a GENUINE single-atom CAS linearization: the capture
;;     generation is validated against the SAME cell-state snapshot the publish
;;     `compare-and-set!` commits, so validation and publication are ONE atomic
;;     transition.
;;   - REGISTRY axis — a BEST-EFFORT slot-identity check, NOT fused into the CAS:
;;     the registered slot is sampled once as a token and rechecked `identical?`
;;     IMMEDIATELY BEFORE the CAS. Sound on the single-threaded host (nothing
;;     preempts between the recheck and the CAS), but it is not a single-atom
;;     linearization.
;; The `:pre-publish` seam fires ONCE, in EXACTLY that former gap — between an
;; attempt's authority sample and its publish CAS — so these fixtures land the
;; advance where nothing could catch it before, on BOTH axes, and prove nothing
;; publishes.
;;
;; MUTATION TOOTH: revert `publish-commit!` to a sample-then-independent-swap
;; (so the `:pre-publish` advance lands between the two) and every assertion
;; below flips red — the interposed advance then publishes and connects.
;; `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
;; (`clojure -M:test`); the accompanying `-race-jvm-test` exercises the same
;; publish path — both axes — under GENUINE two-thread concurrency.
;; ===========================================================================

;; ---- Registry axis: a re-registration before the final slot-identity check --
;; The registry axis is a BEST-EFFORT identity check, NOT a single-atom
;; linearization. The view's HMR slot is sampled once as a token; a
;; `register-view-generation!` landing at `:pre-publish` (BEFORE the final
;; `identical?` recheck) swaps `view-generations` to a fresh slot object, so the
;; recheck fails and the loop re-reads a MOVED revision and rejects. This
;; exercises the recheck→re-validate path only — it does NOT interpose in the
;; residual token-recheck→cell-CAS window (a single-fire barrier cannot, and that
;; window is unreachable on the single-threaded CLJS host anyway).

(deftest pre-publish-registration-is-identity-checked-registry-axis
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::linz-registry-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)
        cap  (capture-of cell [[::site [:r/a]]])]
    (is (= 0 (:generation cap)) "capture formed at body revision 0")
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (let [result (binding [reactive/*commit-barrier*
                           (reregister-barrier vid :pre-publish hs0)]
                   (reactive/commit! cell cap))]
      (is (= :stale result)
          "a re-registration before the final slot-identity check is rejected — the moved token fails the recheck")
      (is (= :fresh (reactive/lifecycle cell)) "a fenced candidate never connects")
      (is (empty? (reactive/committed-sites cell)) "no dependency set published")
      (is (nil? (entry [:r/a])) "the staged handle was released — zero-owner node")
      (is (= 0 (reactive/revision cell))
          "no revision advance — the shell's re-registration drives the re-render"))))

;; ---- Cell-local axis: an advance-generation! in the sample→publish window ---
;; A direct/unregistered cell: the registry arm no-ops, so the publish CAS over
;; the sampled cell state is the SOLE catch. The interposed advance swaps `st`,
;; so the compare-and-set! over the stale snapshot fails and the loop re-reads a
;; MOVED generation and rejects.

(deftest pre-publish-generation-advance-is-linearized-cell-local-axis
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::linz-cell-local-view          ;; deliberately NEVER registered
        cell (reactive/make-cell vid 0)
        cap  (capture-of cell [[::a [:r/a]]])]
    (is (= 0 (:generation cap)) "capture formed at cell body generation 0")
    (is (nil? (reactive/registered-view-revision vid))
        "precondition: no registered slot — the cell-local CAS is the sole catch")
    (is (nil? (entry [:r/a])) "precondition: nothing owns the node yet")
    (let [result (binding [reactive/*commit-barrier*
                           (fn [phase c]
                             (when (= phase :pre-publish)
                               (reactive/advance-generation! c 1)))]
                   (reactive/commit! cell cap))]
      (is (= :stale result)
          "the interposed advance fails the publish compare-and-set! → :stale")
      (is (= :fresh (reactive/lifecycle cell)) "a fenced candidate never connects")
      (is (empty? (reactive/committed-sites cell)) "no dependency set published")
      (is (nil? (entry [:r/a])) "the staged handle was released — zero-owner node")
      (is (= 1 (reactive/generation cell)) "the cell carries the advanced generation")
      (is (= 0 (reactive/revision cell))
          "no revision advance — the sync/remount drives the re-render"))))

;; ---- Rollback in the sample→publish window: reverse order, exactly once -----
;; A retained prior :a plus staged :b/:c: a mid-window re-registration must
;; release ONLY :b/:c, in reverse acquisition order, exactly once, and preserve
;; the retained :a and the prior committed set byte-for-byte.

(deftest pre-publish-fence-releases-staged-in-reverse-exactly-once
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (rf/reg-sub :r/c (fn [db _] (:c db)))
  (seed! {:a 1 :b 2 :c 3})
  (let [vid  ::linz-order-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)]
    (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))   ;; connect owning :a
    (let [handle-a      (reactive/committed-handle cell (tk [:r/a]))
          rev0         (reactive/revision cell)
          acq          (atom [])
          rel          (atom [])
          real-acquire obs/acquire!
          real-release obs/release!
          handle->query (fn [handle]
                         (some (fn [[l q]] (when (identical? l handle) q)) @acq))
          cap          (capture-of cell [[::a [:r/a]] [::b [:r/b]] [::c [:r/c]]])]
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count [:r/a])) "prior committed handle installed")
      (with-redefs [obs/acquire! (fn [target on-change]
                                   (let [handle (real-acquire target on-change)]
                                     (swap! acq conj [handle (:query target)])
                                     handle))
                    obs/release! (fn [handle]
                                   (swap! rel conj (handle->query handle))
                                   (real-release handle))]
        (let [result (binding [reactive/*commit-barrier*
                               (reregister-barrier vid :pre-publish hs0)]
                       (reactive/commit! cell cap))]
          (is (= :stale result) "the interposed re-registration fences the publish")
          (is (= [[:r/b] [:r/c]] (mapv second @acq))
              "only the NEW sites acquire (:a retained, never re-acquired)")
          (is (= [[:r/c] [:r/b]] @rel)
              "the staged handles released in REVERSE acquisition order")
          (is (= 2 (count @rel)) "each staged handle released EXACTLY once")
          (is (not (some #{[:r/a]} @rel)) "the RETAINED prior :a was never released")))
      (testing "prior committed :a survives byte-for-byte"
        (is (identical? handle-a (reactive/committed-handle cell (tk [:r/a])))
            "the retained handle keeps its exact identity")
        (is (= 1 (ref-count [:r/a])) ":a never re-acquired or released")
        (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell))
            "the published dependency set is unchanged — no staged site leaked")
        (is (= :connected (reactive/lifecycle cell)) "lifecycle unchanged")
        (is (= rev0 (reactive/revision cell)) "revision unchanged"))
      (testing "BOTH staged handles were released"
        (is (nil? (entry [:r/b])) ":b staged then released → zero-owner node")
        (is (nil? (entry [:r/c])) ":c staged then released → zero-owner node")))))

;; ---- The ordinary commit still publishes once through the linearized path ---

(deftest pre-publish-clean-commit-publishes-once-through-cas
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::linz-clean-view
        _    (reactive/register-view-generation! vid hs0)
        cell (reactive/make-cell vid 0)]
    (testing "no interposed authority advance ⇒ the compare-and-set! publishes once"
      (let [result (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))]
        (is (identical? cell result) "a clean commit returns the cell, not :stale")
        (is (= :connected (reactive/lifecycle cell)))
        (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell)))
        (is (= 1 (ref-count [:r/a])) "exactly one owner acquired")
        (is (= 1 (:value (reactive/committed-site cell ::a))))))))
