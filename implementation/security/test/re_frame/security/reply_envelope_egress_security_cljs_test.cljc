(ns re-frame.security.reply-envelope-egress-security-cljs-test
  "Adversarial tests for managed-async reply egress.

  `trace-summary` projects the wire-bearing `:value`, `:error`, `:correlation`,
  and `:meta` slots through the shared elision walker before AI/MCP or log
  egress. Structural reply facts remain available for correlation.

  The suite also pins two independent correctness boundaries: stale replies do
  not deliver an app target or retain a value, and replies plus durable targets
  contain data rather than host handles."
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

;; CROSS-RECORD SPELLING (rf2-l7s7b7, Managed-Effects §The reply map). Every
;; top-level REPLY ENVELOPE below single-roots the work identity as
;; `:rf.reply/work-id` / `:rf.reply/work-kind`. The bare `:work/id` spelling is
;; the DURABLE work-ledger / verification-payload fact and survives here only on
;; the data-only carried/current stale-gate maps, which are exactly that layer.
;; `trace-summary` projects the wire slots and otherwise preserves the supplied
;; map verbatim, so a fixture that modelled the ledger layer would let this
;; suite stay green while proving nothing about the identity that actually
;; egresses (rf2-xy3g).
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
                 :rf.size/include-sensitive? false
                 :rf.size/include-large?     false})]
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
                and grows NO top-level bare ledger alias beside it (rf2-xy3g)"
        (is (not (contains? out :work/id))
            "no top-level bare :work/id alias survives framed trace egress")
        (is (not (contains? out :work/kind))
            "no top-level bare :work/kind alias survives framed trace egress")))))

(deftest trace-summary-delegates-to-shared-walker
  (testing "each wire slot in the trace summary equals elide-wire-value under
            the SAME opts — proving trace-summary delegates to the single
            shared walker, not a family-private elider"
    (mk-frame! :reply/deleg)
    (let [opts {:frame :reply/deleg}
          value {:token sentinel :doc big-string :public 7}
          reply-map {:status :ok :value value
                     :rf.reply/work-id [:rf.work/http :x 1]
                     :rf.reply/work-status :completed}
          out (rf.reply/trace-summary reply-map opts)]
      (is (= (:value out) (rf.elision/elide-wire-value value opts))
          ":value slot is exactly elide-wire-value under the resolved opts"))))

(deftest frameless-trace-summary-fails-closed
  (testing "a reply trace summary built with NO live frame redacts every
            wire-bearing slot to :rf/redacted (fail-closed; no :rf/default
            synthesis) — the sentinel never rides off-box"
    ;; Remove ambient scope so no other frame's policy can be borrowed.
    (binding [rf.frame/*current-frame* nil]
      (let [reply-map {:status      :error
                       :error       {:kind :rf.http/cors :detail sentinel}
                       :correlation {:partner-key sentinel}
                       :meta        {:secret sentinel}
                       :rf.reply/work-id [:rf.work/http :y 2]
                       :rf.reply/work-status :failed}
            out (rf.reply/trace-summary reply-map nil)]
        (is (rf.security.gen/redacted? (:error out)) ":error fails closed (whole slot redacted)")
        (is (rf.security.gen/redacted? (:correlation out)) ":correlation fails closed")
        (is (rf.security.gen/redacted? (:meta out)) ":meta fails closed")
        (is (not (contains-sentinel? out)) "no sentinel survives frameless egress")
        (is (= :error (:status out)))
        (is (= [:rf.work/http :y 2] (:rf.reply/work-id out)))
        (is (= :failed (:rf.reply/work-status out)))
        (is (not (contains? out :work/id))
            "TOOTH — frameless egress grows no top-level bare :work/id alias (rf2-xy3g)"))
      ;; Explicit sensitive inclusion is the trusted-local opt-out.
      (let [out (rf.reply/trace-summary {:status :ok :value {:token sentinel}
                                      :rf.reply/work-id [:rf.work/http :z 3]
                                      :rf.reply/work-status :completed}
                  {:rf.size/include-sensitive? true})]
        (is (= sentinel (get-in out [:value :token]))
            "explicit include-sensitive? true is the deliberate frameless opt-out")))))

(def ^:private gen-reply
  "A status-correct reply map carrying the sentinel in its wire slots. The
  status drives which value/error slots are present (so the corpus stays
  validate-reply-legal), but every present wire slot plants the sentinel."
  (rf.security.gen/gen-fmap
    (fn [[status work-status]]
      (let [base {:rf.reply/work-id   [:rf.work/http :gen 1]
                  :rf.reply/work-kind :http
                  :rf.reply/work-status work-status
                  :rf.frame/id :reply/corpus
                  :correlation {:partner-key sentinel :trace-id "tc"}
                  :meta        {:token sentinel}}
            err  {:kind :rf.http/server-error :detail {:token sentinel}}]
        (case status
          :ok        (assoc base :status :ok :value {:token sentinel :public 1})
          :partial   (assoc base :status :partial
                                 :value {:token sentinel} :error err)
          :error     (assoc base :status :error :error err :rf.reply/work-status :failed)
          :cancelled (assoc base :status :cancelled
                                 :cancelled? true
                                 :rf.reply/cancel-reason :user-abort
                                 :rf.reply/work-status :cancelled)
          :stale     (-> base
                         (dissoc :value)
                         (assoc :status :stale :stale? true
                                :rf.reply/stale-reason :rf.reply/correlation-mismatch
                                :rf.reply/work-status :suppressed)))))
    (fn [rng]
      (let [[status rng1] (rf.security.gen/rand-nth rng (vec rf.reply/statuses))
            [ws rng2]     (rf.security.gen/rand-nth rng1 (vec rf.reply/work-statuses))]
        [[status ws] rng2]))))

(deftest framed-corpus-never-ships-sentinel
  (testing "across 300 generated mixed-status reply maps, the framed trace
            egress never ships the sentinel through any wire slot, and the
            status stays in the closed taxonomy"
    (mk-frame! :reply/corpus)
    (let [result (rf.security.gen/for-all
                   gen-reply 300 17
                   (fn [reply-map]
                     (let [out (rf.reply/trace-summary reply-map
                                 {:frame :reply/corpus})]
                       (and (not (contains-sentinel? (:value out)))
                            (not (contains-sentinel? (:error out)))
                            (not (contains-sentinel? (:correlation out)))
                            (not (contains-sentinel? (:meta out)))
                            (contains? rf.reply/statuses (:status out))
                            ;; rf2-xy3g — across every status the canonical
                            ;; TRANSIENT-ENVELOPE identity survives egress and
                            ;; no bare ledger alias appears beside it.
                            (= [:rf.work/http :gen 1] (:rf.reply/work-id out))
                            (= :http (:rf.reply/work-kind out))
                            (not (contains? out :work/id))
                            (not (contains? out :work/kind))))))]
      (is (nil? result)
          (str "a reply trace summary shipped the sentinel / left the closed "
               "taxonomy / lost the canonical envelope identity: "
               (pr-str (when result (dissoc result :threw))))))))

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
          "TOOTH — suppression grows no top-level bare :work/id alias (rf2-xy3g)")
      (is (= :reply/stale (:rf.frame/id reply)))
      (is (= 1781078400456 (:completed-at reply)) "causal completion time survives")
      (is (rf.reply/valid-reply? reply) "the suppression reply is contract-valid")
      ;; Suppression traces retain correlation without carrying the value.
      (let [trace (:trace outcome)]
        (is (true? (:rf.reply/suppressed? trace)))
        ;; The trace reads its work id off `:rf.reply/work-id` on the reply, so
        ;; a fixture spelling the identity the LEDGER way left this nil while
        ;; the suite stayed green (rf2-xy3g). This is that tooth.
        (is (= [:rf.work/http :a 1] (:rf.reply/work-id trace))
            "the suppression trace carries the canonical envelope work id")
        ;; The carried/current gate maps ARE ledger correlation, so the trace
        ;; keeps them verbatim with their bare `:work/id` intact.
        (is (= carried (:rf.reply/carried trace)))
        (is (= current (:rf.reply/current trace)))
        (is (= [:rf.work/http :a 1] (:work/id (:rf.reply/carried trace)))
            "the carried LEDGER gate keeps the bare spelling — not renamed")
        (is (not (contains-sentinel? trace)) "the suppression trace carries no sentinel")))))

(deftest app-target-cannot-obtain-stale-delivery
  (testing "rf2-j538f7.14 — NO app reply target (built from public :rf/reply-to
            data) can make a superseded completion deliver: `suppress` is
            UNIVERSALLY non-delivering. A plain target, one spelling the removed
            :dispatch-stale? flag, and one forging a truthy authority datum of
            any spelling all yield :deliver? false — a stale envelope never
            reaches app state, and there is NO app-callable issuer to obtain
            delivery authority (`with-stale-authority` / `stale-authority?` /
            `StaleDeliveryCapability` are deleted, not merely non-façade)."
    (let [carried {:work/id [:rf.work/http :a 1] :generation 1}
          current {:work/id [:rf.work/http :a 1] :generation 2}]
      (doseq [app-target [[:app/on-reply]
                          {:event [:app/on-reply]}
                          {:event [:app/on-reply] :dispatch-stale? true}
                          {:event [:app/on-reply] :dispatch-stale? true
                           :re-frame.reply/stale-authority true}
                          {:event [:app/on-reply] :dispatch-stale? true
                           :re-frame.reply/stale-authority "trusted"}]]
        (let [{:keys [deliver? reply]} (rf.reply/suppress app-target carried current)]
          (is (false? deliver?)
              (str "app target " (pr-str app-target) " must NOT deliver a stale envelope"))
          (is (= :stale (:status reply)) "the outcome is still a well-formed stale reply")
          (is (not (contains? reply :value))
              "no :value can ride a stale reply into app state"))))))

(deftest host-handle-never-egresses-in-reply-or-target
  (testing "validate-reply flags a host handle anywhere in the reply map, and
            durable-target fails loud on a handle in a persisted target"
    ;; A function is a host handle on both runtimes.
    (let [handle (fn [] :callback)
          reply-with-handle {:status :ok :value {:on-done handle}
                             :rf.reply/work-id [:rf.work/http :h 1]
                             :rf.reply/work-status :completed}
          problems (rf.reply/validate-reply reply-with-handle)]
      (is (some #(= :rf.reply/host-handle (:rf.reply/problem %)) problems)
          "validate-reply flags the host handle in the reply :value"))
    (let [bad-target {:event [:app/on-reply (fn [] :smuggled)] :delivery :append}
          data (try (rf.reply/durable-target bad-target)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/reply-non-data-target (:rf.error/id data))
          "a host handle in a durable target's public field fails loud"))
    (is (rf.reply/valid-reply? {:status :ok :value {:public 1}
                             :rf.reply/work-id [:rf.work/http :h 2]
                             :rf.reply/work-status :completed}))
    (is (rf.reply/data-only-target? {:event [:app/on-reply] :delivery :append}))))
