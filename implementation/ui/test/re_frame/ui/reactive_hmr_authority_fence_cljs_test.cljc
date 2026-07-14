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
  and the publish. If the body authority moved, only the newly staged leases are
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
          "the staged lease was released — the node has zero owners (disposed)")
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
;; leases (reverse order, exactly once)
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
    (let [lease-a (reactive/committed-lease cell (tk [:r/a]))
          rev0    (reactive/revision cell)]
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count [:r/a])) "prior committed lease installed")
      (testing "a later same-generation render retains :a and stages :b + :c,
                then a re-registration fences the publish"
        (let [cap    (capture-of cell [[::a [:r/a]] [::b [:r/b]] [::c [:r/c]]])
              result (binding [reactive/*commit-barrier*
                               (reregister-barrier vid :post-acquire hs0)]
                       (reactive/commit! cell cap))]
          (is (= :stale result) "the stale-body publish is fenced")
          (testing "prior committed :a is wholly untouched"
            (is (identical? lease-a (reactive/committed-lease cell (tk [:r/a])))
                "the retained lease keeps its exact identity")
            (is (= 1 (ref-count [:r/a])) ":a never re-acquired or released")
            (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell))
                "the published dependency set is unchanged — no staged site leaked")
            (is (= :connected (reactive/lifecycle cell)) "lifecycle unchanged")
            (is (= rev0 (reactive/revision cell)) "revision unchanged"))
          (testing "BOTH staged leases (:b, :c) were released exactly once"
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
      (let [lease-a (reactive/committed-lease cell (tk [:r/a]))]
        (testing "a second unchanged-authority render retains the exact lease"
          (reactive/commit! cell (capture-of cell [[::a [:r/a]]]))
          (is (identical? lease-a (reactive/committed-lease cell (tk [:r/a])))
              "acquire-before-release kept the live lease untouched")
          (is (= 1 (ref-count [:r/a])) "no churn on a no-op reconcile"))))))
