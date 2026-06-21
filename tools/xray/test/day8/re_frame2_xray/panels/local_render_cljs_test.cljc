(ns day8.re-frame2-xray.panels.local-render-cljs-test
  "The local-render egress test for Xray's ON-BOX panel value rendering — the
  EP-0015 `:rf.egress/local-redacted` GRADUATING-CONSUMER proof (rf2-t55hxg.12).

  ## The gap this closes

  [spec/015-Data-Classification.md §The graduation gate] requires each of the
  six `:rf.egress/*` profiles be EXERCISED by a real consumer before the names
  lock. `:rf.egress/local-redacted` was the one remaining gap: defined +
  unit-tested in `re-frame.projection` but no on-box dev tool named it as its
  render default. Xray is that consumer — its local panel value rendering now
  routes through `re-frame.core/project-egress` under
  `:rf.egress/local-redacted` (`day8.re-frame2-xray.panels.local-render`). This
  test is the end-to-end exercise the graduation row names: the on-box LOCAL
  render of a value REDACTS a frame-declared sensitive slot by default while
  KEEPING large values (the local-render contract: suppress sensitive display;
  the local operator may see big values).

  ## What's asserted

    1. **DEFAULT redacts sensitive** — a value carrying a sensitive slot at a
       frame-declared sensitive path is projected under the local-render
       default; the sensitive slot becomes `:rf/redacted` WHILE non-sensitive
       siblings + structure ride through.
    2. **DEFAULT keeps large** — a frame-declared `:large` value is NOT elided
       on-box (the operator sees big values; only secrets are withheld).
    3. **per-frame** — projection uses the OBSERVED frame's policy, not a
       borrowed / ambient one; a plain frame ships the same value verbatim.
    4. **`:rf.egress/local-raw` opt-in** — the per-(tool,frame) trusted-local
       grain reveals the sensitive slot (an explicit operator act).
    5. **fail-closed** — an unreachable observed frame redacts the WHOLE value
       under the redacted default rather than ship it raw.
    6. **end-to-end** — the App-DB Diff section model sub
       (`:rf.xray/app-db-state`) redacts a sensitive app-db slot, so what the
       panel hands the edn-inspector is already projected."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.local-render :as local-render]))

;; ---------------------------------------------------------------------------
;; Runtime fixture — mirror the off-box derivation-graph redaction test
;; (rf2-yjarv6): two frames, one with declared :sensitive / :large policy.
;;
;;   :app/secure — declares [:auth :token] sensitive and [:catalog :rows] large.
;;   :app/plain  — no classification (every value renders verbatim).
;; ---------------------------------------------------------------------------

(def secure-frame :app/secure)
(def plain-frame  :app/plain)

(defn- install-policy! []
  ;; EP-0025: durable app-db classification rides the commit-plane
  ;; classification effects (`:source :effect`) — the frame annotation is removed.
  (frame/swap-runtime-db! secure-frame
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:auth :token]]
                :large     [[:catalog :rows]]}))))

(defn- init-fn []
  (rf/reg-frame plain-frame {})
  (rf/reg-frame secure-frame {})
  (install-policy!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     ;; OPT OUT of the default `:rf/default` ambient scope so the fail-closed
     ;; arm asserts a frameless walk redacts (a bound ambient frame would let
     ;; the frameless projection resolve to its empty-policy identity and ship
     ;; raw — masking the fail-closed contract). Mirrors the off-box sibling
     ;; test's fixture.
     :ambient-frame nil
     :init-fn init-fn}))

;; A value with both a frame-declared sensitive slot and a frame-declared
;; large slot, plus plain siblings — the shape an app-db panel renders.
(def ^:private app-db-value
  {:auth    {:token "secret-session-jwt-abc123"
             :user-id 42}
   :catalog {:rows (vec (range 300))}
   :ui      {:open? true :tab :home}})

;; ---------------------------------------------------------------------------
;; 1 + 2. DEFAULT — redact sensitive, KEEP large.
;; ---------------------------------------------------------------------------

(deftest local-render-default-redacts-sensitive-keeps-large
  (let [rendered (local-render/local-render-value app-db-value secure-frame)]
    (testing "(1) the frame-declared sensitive slot is REDACTED under the
              on-box default (:rf.egress/local-redacted)"
      (is (= :rf/redacted (get-in rendered [:auth :token]))
          "the sensitive token must redact in the local render default"))

    (testing "non-sensitive siblings + structure ride through"
      (is (= 42 (get-in rendered [:auth :user-id])) "non-sensitive sibling kept")
      (is (= {:open? true :tab :home} (:ui rendered)) "unrelated subtree kept"))

    (testing "(2) the frame-declared LARGE slot is NOT elided on-box — the
              local operator sees big values; only secrets are withheld
              (the include-large? overlay)"
      (is (= (vec (range 300)) (get-in rendered [:catalog :rows]))
          "a large value renders raw on-box (display-only size bounding is
           the edn-inspector's job, not an egress redaction)"))))

;; ---------------------------------------------------------------------------
;; 3. per-frame — projection uses the OBSERVED frame's policy, not a borrowed one.
;; ---------------------------------------------------------------------------

(deftest local-render-applies-the-observed-frames-policy
  (testing "under the SECURE frame the sensitive token redacts"
    (is (= :rf/redacted
           (get-in (local-render/local-render-value app-db-value secure-frame)
                   [:auth :token]))))
  (testing "under the PLAIN frame (no sensitive decl) the SAME value renders
            verbatim — the policy is per-frame, applied from the observed
            frame, never borrowed or ambient"
    (is (= "secret-session-jwt-abc123"
           (get-in (local-render/local-render-value app-db-value plain-frame)
                   [:auth :token]))
        "no sensitive decl on :app/plain ⇒ the value renders raw")))

;; ---------------------------------------------------------------------------
;; 4. `:rf.egress/local-raw` opt-in — the trusted-local per-(tool,frame) grain.
;; ---------------------------------------------------------------------------

(deftest local-raw-opt-in-reveals-sensitive
  (testing "the redacted default suppresses the sensitive slot"
    (is (= :rf/redacted
           (get-in (local-render/local-render-value app-db-value secure-frame false)
                   [:auth :token]))))
  (testing "the explicit trusted-local :rf.egress/local-raw grain (raw? true)
            reveals it — an operator act, not the process-global default"
    (is (= "secret-session-jwt-abc123"
           (get-in (local-render/local-render-value app-db-value secure-frame true)
                   [:auth :token]))
        "raw? true resolves to :rf.egress/local-raw which includes sensitive"))
  (testing "the profile resolver maps the grain to the named boundary"
    (is (= :rf.egress/local-redacted (local-render/local-render-profile false)))
    (is (= :rf.egress/local-redacted (local-render/local-render-profile nil)))
    (is (= :rf.egress/local-raw (local-render/local-render-profile true)))))

;; ---------------------------------------------------------------------------
;; 5. fail-closed — an UNREACHABLE observed frame redacts the WHOLE value.
;; ---------------------------------------------------------------------------

(deftest local-render-fails-closed-on-unreachable-frame
  (testing "projecting under an unknown / destroyed observed frame redacts the
            whole value to :rf/redacted rather than ship it raw under no policy"
    (is (= :rf/redacted
           (local-render/local-render-value app-db-value :app/does-not-exist))
        "frameless local-redacted render fails closed (no :frame opt ⇒ the
         walker's frameless redact-whole branch)"))
  (testing "a nil observed frame likewise fails closed"
    (is (= :rf/redacted (local-render/local-render-value app-db-value nil))))
  (testing "the live frame is carried as the :frame opt; an unreachable one is
            omitted so the walker fails closed"
    (is (= secure-frame (:frame (local-render/local-render-opts secure-frame))))
    (is (not (contains? (local-render/local-render-opts :app/does-not-exist) :frame)))
    (is (= :rf.egress/local-redacted
           (:rf.egress/profile (local-render/local-render-opts secure-frame))))
    (is (true? (:rf.size/include-large? (local-render/local-render-opts secure-frame)))
        "the keep-large overlay is always present")))

;; ---------------------------------------------------------------------------
;; 6. end-to-end — the App-DB Diff section-model derivation redacts a sensitive
;;    slot, so what the panel hands the edn-inspector is already projected.
;;
;; Replicates the `:rf.xray/app-db-state` sub's body exactly (project the
;; observed-frame value through `local-render-value`, then decompose into the
;; section model via `current-state-sections`) — exercising the integrated
;; project-then-section path the panel renders without standing up the full
;; reactive Xray sub graph.
;; ---------------------------------------------------------------------------

(deftest app-db-state-section-model-redacts-sensitive-under-observed-frame
  (let [observed-frame secure-frame
        ;; The exact projection the `:rf.xray/app-db-state` sub applies.
        projected (local-render/local-render-value app-db-value observed-frame)
        {:keys [top]} (h/current-state-sections projected nil)]
    (testing "the TOP (user-domain) section's sensitive slot is redacted in the
              model the panel hands to the edn-inspector"
      (is (= :rf/redacted (get-in top [:auth :token]))
          "the section model is projected through :rf.egress/local-redacted"))
    (testing "non-sensitive + large slots survive the section-model projection"
      (is (= 42 (get-in top [:auth :user-id])))
      (is (= (vec (range 300)) (get-in top [:catalog :rows]))
          "large stays on-box; only the secret is withheld")))

  (testing "under a plain frame the section model renders the value verbatim"
    (let [projected (local-render/local-render-value app-db-value plain-frame)
          {:keys [top]} (h/current-state-sections projected nil)]
      (is (= "secret-session-jwt-abc123" (get-in top [:auth :token]))
          "no sensitive decl ⇒ section model is unredacted"))))
