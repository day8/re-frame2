(ns re-frame.security.reply-envelope-egress-security-cljs-test
  "Adversarial tests for managed-async reply egress.

  `trace-summary` projects the wire-bearing `:value`, `:error`, `:correlation`,
  and `:meta` slots through the shared elision walker before AI/MCP or log
  egress. Structural reply facts remain available for correlation.

  The suite also pins the stale-reply correctness boundary: a stale reply does
  not deliver an app target or retain a value."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            ;; Families use the internal reply substrate directly.
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.security.gen :as rf.security.gen]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private sentinel "S3CR3T-rf2-3cfvt-REPLY-EGRESS-DO-NOT-SHIP")

(defn- contains-sentinel?
  "True when the sentinel survives anywhere in `x` (substring scan)."
  [x]
  (rf.security.gen/contains-string? x sentinel))

;; CROSS-RECORD SPELLING (Managed-Effects §The reply map). Every
;; top-level REPLY ENVELOPE below single-roots the work identity as
;; `:rf.reply/work-id` / `:rf.reply/work-kind`. The bare `:work/id` spelling is
;; the DURABLE work-ledger / verification-payload fact and survives here only on
;; the data-only carried/current stale-gate maps, which are exactly that layer.
;; `trace-summary` projects the wire slots and otherwise preserves the supplied
;; map verbatim, so a fixture that modelled the ledger layer would let this
;; suite stay green while proving nothing about the identity that actually
;; egresses.
;;
;; `trace-summary` elides each wire slot rooted at its OWN value (not at the
;; reply-map root), so classification paths are relative to each slot value:
;;   :value slot value       {:token <secret> :doc <big>}        → [:token] / [:doc]
;;   :error slot value       {:kind … :detail {:token <secret>}} → [:detail :token]
;;   :correlation slot value {:partner-key <secret>}             → [:partner-key]
;;   :meta slot value        {:token <secret>}                   → [:token]

(def ^:private big-string
  ;; > the 16384-byte default large floor so the marker is deterministic.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! [frame-id]
  (rf/make-frame {:id frame-id})
  ;; Install durable classification through the commit-plane path.
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:token] [:partner-key] [:detail :token]]
                :large     [[:doc]]}))))

(deftest framed-trace-summary-elides-declared-slots
  (testing "trace-summary routes :value/:error/:correlation/:meta through the
            shared elide-wire-value walker under the frame's classification —
            declared-sensitive leaf → :rf/redacted, declared-large leaf →
            marker, and identity facts remain verbatim"
    (mk-frame! :reply/framed)
    (let [reply-map {:status       :partial
                     :value        {:token sentinel :doc big-string :public 7}
                     :error        {:kind :rf.http/server-error
                                    :detail {:token sentinel}}
                     :correlation  {:partner-key sentinel :trace-id "t-1"}
                     :meta         {:token sentinel :note "ok"}
                     :rf.reply/work-id      [:rf.work/http :article/by-id 1]
                     :rf.reply/work-kind    :http
                     :rf.reply/work-status  :failed
                     :rf.frame/id  :reply/framed
                     :completed-at 1781078400456}
          out (rf.reply/trace-summary reply-map
                {:frame :reply/framed
                 :rf.egress/include-sensitive? false
                 :rf.egress/include-large?     false})]
      (is (rf.security.gen/redacted? (get-in out [:value :token])) ":value sensitive leaf redacted")
      (is (rf.security.gen/large-marker? (get-in out [:value :doc])) ":value large leaf elided")
      (is (= 7 (get-in out [:value :public])) "unmarked sibling rides through")
      (is (rf.security.gen/redacted? (get-in out [:error :detail :token])) ":error sensitive leaf redacted")
      (is (rf.security.gen/redacted? (get-in out [:correlation :partner-key])) ":correlation sensitive leaf redacted")
      (is (= "t-1" (get-in out [:correlation :trace-id])) "non-sensitive correlation fact rides")
      (is (rf.security.gen/redacted? (get-in out [:meta :token])) ":meta sensitive leaf redacted")
      (is (not (contains-sentinel? (:value out))))
      (is (not (contains-sentinel? (:error out))))
      (is (not (contains-sentinel? (:correlation out))))
      (is (not (contains-sentinel? (:meta out))))
      ;; Framework identity facts remain available for tool correlation.
      (is (= :partial (:status out)))
      (is (= [:rf.work/http :article/by-id 1] (:rf.reply/work-id out))
          "canonical :rf.reply/work-id verbatim")
      (is (= :http (:rf.reply/work-kind out)) "canonical :rf.reply/work-kind verbatim")
      (is (= :failed (:rf.reply/work-status out)))
      (is (= :reply/framed (:rf.frame/id out)))
      (is (= 1781078400456 (:completed-at out)) "causal completion timestamp verbatim")
      (testing "TOOTH — framed egress preserves the TRANSIENT-ENVELOPE identity
                and grows NO top-level bare ledger alias beside it"
        (is (not (contains? out :work/id))
            "no top-level bare :work/id alias survives framed trace egress")
        (is (not (contains? out :work/kind))
            "no top-level bare :work/kind alias survives framed trace egress")))))

(deftest stale-suppression-never-delivers-app-target-or-value
  (testing "suppress on a superseded completion yields :deliver? false,
            :status :stale, :rf.reply/work-status :suppressed, and STRIPS any :value —
            even when a natural success reply is threaded as `extra`"
    (let [carried {:work/id [:rf.work/http :a 1] :generation 1}
          current {:work/id [:rf.work/http :a 1] :generation 2}
          ;; Model a caller threading a full natural success reply as `extra`.
          ;; It is a REPLY ENVELOPE, so its identity is single-rooted; the
          ;; carried/current gate maps above are ledger data and stay bare.
          natural {:status :ok :value {:token sentinel}
                   :rf.reply/work-status :completed
                   :rf.reply/work-id [:rf.work/http :a 1]
                   :rf.reply/work-kind :http
                   :rf.frame/id :reply/stale :completed-at 1781078400456}
          {:keys [deliver? reply] :as outcome} (rf.reply/suppress nil carried current natural)]
      (is (false? deliver?) "a superseded app reply is NOT delivered")
      (is (= :stale (:status reply)) "the forced status is :stale")
      (is (true? (:stale? reply)) ":stale? marker forced on")
      (is (= :suppressed (:rf.reply/work-status reply)) ":rf.reply/work-status forced to :suppressed")
      (is (not (contains? reply :value)) ":value is STRIPPED — no app mutation can ride")
      (is (not (contains-sentinel? reply)) "the secret value never rides the stale reply")
      (is (= [:rf.work/http :a 1] (:rf.reply/work-id reply))
          "the work identity survives on the envelope spelling")
      (is (= :http (:rf.reply/work-kind reply)) ":rf.reply/work-kind survives")
      (is (not (contains? reply :work/id))
          "TOOTH — suppression grows no top-level bare :work/id alias")
      (is (= :reply/stale (:rf.frame/id reply)))
      (is (= 1781078400456 (:completed-at reply)) "causal completion time survives")
      (is (rf.reply/valid-reply? reply) "the suppression reply is contract-valid")
      ;; Suppression traces retain correlation without carrying the value.
      (let [trace (:trace outcome)]
        (is (true? (:rf.reply/suppressed? trace)))
        ;; The trace reads its work id off `:rf.reply/work-id` on the reply, so
        ;; a fixture spelling the identity the LEDGER way would leave this nil
        ;; while the suite stayed green. This is the tooth that catches it.
        (is (= [:rf.work/http :a 1] (:rf.reply/work-id trace))
            "the suppression trace carries the canonical envelope work id")
        ;; The carried/current gate maps ARE ledger correlation, so the trace
        ;; keeps them verbatim with their bare `:work/id` intact.
        (is (= carried (:rf.reply/carried trace)))
        (is (= current (:rf.reply/current trace)))
        (is (= [:rf.work/http :a 1] (:work/id (:rf.reply/carried trace)))
            "the carried LEDGER gate keeps the bare spelling — not renamed")
        (is (not (contains-sentinel? trace)) "the suppression trace carries no sentinel")))))
