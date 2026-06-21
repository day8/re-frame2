(ns re-frame.security.reply-envelope-egress-security-cljs-test
  "Security tier — EP-0011 managed-async reply-envelope egress + redaction
  (the rf2-3cfvt adversarial regime, applied to the uniform reply envelope).

  ## The boundary

  Every managed *async* family (HTTP, resources / mutations, machine async
  work, route loaders, and future managed timer / background-job surfaces)
  lowers its completion onto the SHARED reply substrate
  (`re-frame.reply`, Managed-Effects §The uniform reply envelope). A reply
  map can carry decoded user values (`:value`), structured family errors
  (`:error`), correlation / request metadata (`:correlation` / `:meta`),
  cancellation / staleness reasons, and durable causal completion
  timestamps. Property 9 of Managed-Effects requires the reply substrate to:

    - emit trace rows FROM the reply-envelope facts (`trace-summary`), with
      every WIRE-BEARING slot (`:value` / `:error` / `:correlation` /
      `:meta`) routed through the shared `re-frame.elision/elide-wire-value`
      walker — never a family-private elider — so `:sensitive?` / `:large?`
      compose uniformly with the rest of the trace stream;
    - keep the CLOSED status taxonomy (`:ok` / `:partial` / `:error` /
      `:cancelled` / `:stale`) and the closed `:work/status` vocabulary;
    - suppress STALE app deliveries by default (the correctness boundary):
      a superseded completion produces `:status :stale` WITHOUT dispatching
      the app target and WITHOUT carrying `:value`;
    - keep reply maps + durable targets DATA-ONLY: no host handle
      (fn / Promise / AbortController / DOM node / Date / …) survives into
      an egressed reply or a persisted target.

  The pin-and-assert substrate suite (`re-frame.reply-test`) and the
  cross-family vocab gate (`re-frame.reply-vocab-conformance-cljs-test`)
  prove the SHAPES; this tier drives the SECURITY-egress properties — that
  a sentinel-bearing reply value / error / correlation / meta never crosses
  the AI-tool / log boundary raw, and the stale / data-only invariants hold
  under a generated corpus.

  ## Threat model

  AI/MCP boundary + logs ONLY (the rf2-3cfvt / rf2-o69h5 / rf2-zsm03 scope).
  The redaction boundary anchored here is the off-box trace egress site
  (`trace-summary` → `elide-wire-value`) and the off-box record projection
  (`project-egress`). Human-facing on-box egress is out of scope and not
  gold-plated.

  ## Net property (verify-by-revert)

  - Reverting `trace-summary` to keep the wire slots verbatim (drop the
    `elide-wire-value` route) makes `framed-trace-summary-elides-declared-slots`
    and `frameless-trace-summary-fails-closed` go RED — the sentinel rides a
    `:value` / `:error` / `:correlation` / `:meta` slot off-box.
  - Reverting `suppress` to thread the natural `:value` / non-stale status
    makes `stale-suppression-never-delivers-app-target-or-value` go RED.
  - Reverting the `validate-reply` / `durable-target` host-handle walk makes
    `host-handle-never-egresses-in-reply-or-target` go RED.
  Confirmed by temporary local revert + restore (see PR Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            ;; EP-0011 reply substrate (INTERNAL — no `re-frame.core` façade
            ;; export; families consume these helpers directly to build the
            ;; uniform envelope, so the security tier exercises them directly).
            [re-frame.reply :as reply]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [re-frame.security.gen :as gen]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(def ^:private sentinel "S3CR3T-rf2-3cfvt-REPLY-EGRESS-DO-NOT-SHIP")

(defn- contains-sentinel?
  "Deep-walk `x`; true when the sentinel string appears ANYWHERE — as a
  value, inside a collection, nested in a secondary slot, or inside a
  stringified form. The contract is \"the sentinel never survives,\" not
  merely \"the primary slot reads clean.\" Thin wrapper over the shared
  `gen/contains-string?` (rf2-n5bkm7); the sentinel is matched as an EXACT
  string leaf (`exact? true`)."
  [x]
  (gen/contains-string? x sentinel true))

;; ---------------------------------------------------------------------------
;; A frame classifying the reply-VALUE-relative paths the wire slots carry.
;;
;; `trace-summary` elides each wire slot rooted at its OWN value (not at the
;; reply-map root), so the frame's declared `:sensitive` / `:large` :app-db
;; paths are stated relative to the slot value. The same frame policy the
;; durable app-db egress reads (EP-0015 §3) is the one the reply trace egress
;; reads — one classification, one path-based walker — so the declarations
;; must name where each secret sits WITHIN its slot value:
;;   :value slot value       {:token <secret> :doc <big>}        → [:token] / [:doc]
;;   :error slot value       {:kind … :detail {:token <secret>}} → [:detail :token]
;;   :correlation slot value {:partner-key <secret>}             → [:partner-key]
;;   :meta slot value        {:token <secret>}                   → [:token]
;; (A re-keyed copy at a NON-declared slot path is the path-walker's known
;; structural blind spot — `value-elide`'s value-based dual covers that case
;; for derived trees; here the frame correctly declares each slot path.)
;; ---------------------------------------------------------------------------

(def ^:private big-string
  ;; > the 16384-byte default large floor so the marker is deterministic.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! [frame-id]
  (rf/reg-frame frame-id {})
  ;; EP-0025: durable app-db classification rides the commit-plane effect
  ;; path (:source :effect) — no longer a frame annotation.
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:token] [:partner-key] [:detail :token]]
                :large     [[:doc]]}))))

;; ---------------------------------------------------------------------------
;; PROPERTY 1 — framed trace egress: every wire-bearing slot routes through
;; the shared walker; the declared sensitive leaf redacts, the declared large
;; leaf elides, the framework identity facts ride verbatim.
;; ---------------------------------------------------------------------------

(deftest framed-trace-summary-elides-declared-slots
  (testing "trace-summary routes :value/:error/:correlation/:meta through the
            shared elide-wire-value walker under the frame's classification —
            declared-sensitive leaf → :rf/redacted, declared-large leaf →
            marker, identity facts verbatim (Managed-Effects property 9)"
    (mk-frame! :reply/framed)
    (let [reply-map {:status       :partial
                     :value        {:token sentinel :doc big-string :public 7}
                     :error        {:kind :rf.http/server-error
                                    :detail {:token sentinel}}
                     :correlation  {:partner-key sentinel :trace-id "t-1"}
                     :meta         {:token sentinel :note "ok"}
                     :work/id      [:rf.work/http :article/by-id 1]
                     :work/status  :failed
                     :rf.frame/id  :reply/framed
                     :completed-at 1781078400456}
          out (reply/trace-summary reply-map
                {:frame :reply/framed
                 :rf.size/include-sensitive? false
                 :rf.size/include-large?     false})]
      ;; Wire-bearing slots: the declared-sensitive token leaf redacts,
      ;; everywhere it surfaces (value / error / correlation / meta).
      (is (gen/redacted? (get-in out [:value :token])) ":value sensitive leaf redacted")
      (is (gen/large-marker? (get-in out [:value :doc])) ":value large leaf elided")
      (is (= 7 (get-in out [:value :public])) "unmarked sibling rides through")
      (is (gen/redacted? (get-in out [:error :detail :token])) ":error sensitive leaf redacted")
      (is (gen/redacted? (get-in out [:correlation :partner-key])) ":correlation sensitive leaf redacted")
      (is (= "t-1" (get-in out [:correlation :trace-id])) "non-sensitive correlation fact rides")
      (is (gen/redacted? (get-in out [:meta :token])) ":meta sensitive leaf redacted")
      ;; No sentinel survives ANY wire slot.
      (is (not (contains-sentinel? (:value out))))
      (is (not (contains-sentinel? (:error out))))
      (is (not (contains-sentinel? (:correlation out))))
      (is (not (contains-sentinel? (:meta out))))
      ;; Identity / correlation FACTS ride verbatim (framework data, not wire
      ;; data) — the trace summary must keep them for tool correlation.
      (is (= :partial (:status out)))
      (is (= [:rf.work/http :article/by-id 1] (:work/id out)) "canonical :work/id verbatim")
      (is (= :failed (:work/status out)))
      (is (= :reply/framed (:rf.frame/id out)))
      (is (= 1781078400456 (:completed-at out)) "causal completion timestamp verbatim"))))

(deftest trace-summary-delegates-to-shared-walker
  (testing "each wire slot in the trace summary equals elide-wire-value under
            the SAME opts — proving trace-summary delegates to the single
            shared walker, not a family-private elider"
    (mk-frame! :reply/deleg)
    (let [opts {:frame :reply/deleg}
          value {:token sentinel :doc big-string :public 7}
          reply-map {:status :ok :value value
                     :work/id [:rf.work/http :x 1] :work/status :completed}
          out (reply/trace-summary reply-map opts)]
      (is (= (:value out) (elision/elide-wire-value value opts))
          ":value slot is exactly elide-wire-value under the resolved opts"))))

;; ---------------------------------------------------------------------------
;; PROPERTY 2 — frameless trace egress FAILS CLOSED: with no live frame and
;; no opt-out, the shared walker redacts the WHOLE wire slot rather than
;; borrow another frame's policy (EP-0002 / Spec 015 §Direct reads). The
;; identity facts still ride verbatim (they are framework data, not walked).
;; ---------------------------------------------------------------------------

(deftest frameless-trace-summary-fails-closed
  (testing "a reply trace summary built with NO live frame redacts every
            wire-bearing slot to :rf/redacted (fail-closed; no :rf/default
            synthesis) — the sentinel never rides off-box"
    ;; Rebind the ambient frame AWAY so the egress is genuinely frameless
    ;; (the posture an off-box forwarder reading outside any frame scope sees).
    (binding [frame/*current-frame* nil]
      (let [reply-map {:status      :error
                       :error       {:kind :rf.http/cors :detail sentinel}
                       :correlation {:partner-key sentinel}
                       :meta        {:secret sentinel}
                       :work/id     [:rf.work/http :y 2]
                       :work/status :failed}
            out (reply/trace-summary reply-map nil)]
        ;; Every wire slot fails closed to the whole-value sentinel.
        (is (gen/redacted? (:error out)) ":error fails closed (whole slot redacted)")
        (is (gen/redacted? (:correlation out)) ":correlation fails closed")
        (is (gen/redacted? (:meta out)) ":meta fails closed")
        (is (not (contains-sentinel? out)) "no sentinel survives frameless egress")
        ;; Identity facts still ride (framework data is not walked).
        (is (= :error (:status out)))
        (is (= [:rf.work/http :y 2] (:work/id out)))
        (is (= :failed (:work/status out))))
      ;; The deliberate opt-out is the single control point: include-sensitive?
      ;; true gets an identity walk against the empty no-frame policy.
      (let [out (reply/trace-summary {:status :ok :value {:token sentinel}
                                      :work/id [:rf.work/http :z 3]
                                      :work/status :completed}
                  {:rf.size/include-sensitive? true})]
        (is (= sentinel (get-in out [:value :token]))
            "explicit include-sensitive? true is the deliberate frameless opt-out")))))

;; ---------------------------------------------------------------------------
;; PROPERTY 3 (generated) — across a corpus of mixed-status reply maps, the
;; framed trace egress NEVER ships the sentinel and ALWAYS preserves the
;; closed status / work-status taxonomy.
;; ---------------------------------------------------------------------------

(def ^:private gen-status (gen/gen-elem (vec reply/statuses)))

(def ^:private gen-reply
  "A status-correct reply map carrying the sentinel in its wire slots. The
  status drives which value/error slots are present (so the corpus stays
  validate-reply-legal), but every present wire slot plants the sentinel."
  (gen/gen-fmap
    (fn [[status work-status]]
      (let [base {:work/id     [:rf.work/http :gen 1]
                  :work/status work-status
                  :rf.frame/id :reply/corpus
                  :correlation {:partner-key sentinel :trace-id "tc"}
                  :meta        {:token sentinel}}
            err  {:kind :rf.http/server-error :detail {:token sentinel}}]
        (case status
          :ok        (assoc base :status :ok :value {:token sentinel :public 1})
          :partial   (assoc base :status :partial
                                 :value {:token sentinel} :error err)
          :error     (assoc base :status :error :error err :work/status :failed)
          :cancelled (assoc base :status :cancelled
                                 :cancelled? true
                                 :cancel/reason :user-abort
                                 :work/status :cancelled)
          :stale     (-> base
                         (dissoc :value)
                         (assoc :status :stale :stale? true
                                :stale/reason :rf.reply/correlation-mismatch
                                :work/status :suppressed)))))
    (fn [rng]
      (let [[status rng1] (gen/rand-nth rng (vec reply/statuses))
            [ws rng2]     (gen/rand-nth rng1 (vec reply/work-statuses))]
        [[status ws] rng2]))))

(deftest framed-corpus-never-ships-sentinel
  (testing "across 300 generated mixed-status reply maps, the framed trace
            egress never ships the sentinel through any wire slot, and the
            status stays in the closed taxonomy"
    (mk-frame! :reply/corpus)
    (let [result (gen/for-all
                   gen-reply 300 17
                   (fn [reply-map]
                     (let [out (reply/trace-summary reply-map
                                 {:frame :reply/corpus})]
                       (and (not (contains-sentinel? (:value out)))
                            (not (contains-sentinel? (:error out)))
                            (not (contains-sentinel? (:correlation out)))
                            (not (contains-sentinel? (:meta out)))
                            (contains? reply/statuses (:status out))))))]
      (is (nil? result)
          (str "a reply trace summary shipped the sentinel / left the closed "
               "taxonomy: " (pr-str (when result (dissoc result :threw))))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 4 — stale suppression is the correctness boundary: a superseded
;; completion produces :status :stale, does NOT deliver the app target, and
;; carries NO :value (no app mutation). A natural success/error reply threaded
;; as `extra` cannot smuggle a :value or a non-stale status through.
;; ---------------------------------------------------------------------------

(deftest stale-suppression-never-delivers-app-target-or-value
  (testing "suppress on a superseded completion yields :deliver? false,
            :status :stale, :work/status :suppressed, and STRIPS any :value —
            even when a natural success reply is threaded as `extra`"
    (let [carried {:work/id [:rf.work/http :a 1] :generation 1}
          current {:work/id [:rf.work/http :a 1] :generation 2}
          ;; A caller accidentally threads a full natural success reply
          ;; (carrying the secret :value) as `extra`.
          natural {:status :ok :value {:token sentinel}
                   :work/status :completed :work/id [:rf.work/http :a 1]
                   :rf.frame/id :reply/stale :completed-at 1781078400456}
          {:keys [deliver? reply] :as outcome} (reply/suppress nil carried current natural)]
      (is (false? deliver?) "a superseded app reply is NOT delivered")
      (is (= :stale (:status reply)) "the forced status is :stale")
      (is (true? (:stale? reply)) ":stale? marker forced on")
      (is (= :suppressed (:work/status reply)) ":work/status forced to :suppressed")
      (is (not (contains? reply :value)) ":value is STRIPPED — no app mutation can ride")
      (is (not (contains-sentinel? reply)) "the secret value never rides the stale reply")
      ;; Identity facts threaded via `extra` survive (work id / frame / time).
      (is (= [:rf.work/http :a 1] (:work/id reply)) "carried :work/id survives")
      (is (= :reply/stale (:rf.frame/id reply)))
      (is (= 1781078400456 (:completed-at reply)) "causal completion time survives")
      ;; The stale reply itself validates against the shared contract.
      (is (reply/valid-reply? reply) "the suppression reply is contract-valid")
      ;; The suppression trace joins carried + current correlation to the
      ;; canonical :work/id without leaking the sentinel.
      (let [trace (:trace outcome)]
        (is (true? (:rf.reply/suppressed? trace)))
        (is (= carried (:rf.reply/carried trace)))
        (is (= current (:rf.reply/current trace)))
        (is (not (contains-sentinel? trace)) "the suppression trace carries no sentinel")))))

(deftest app-target-cannot-grant-itself-stale-delivery
  (testing "an APP reply target (built from public :rf/reply-to data) that
            sets :dispatch-stale? true WITHOUT the framework/tool capability
            FAILS LOUD — it cannot deliver a stale envelope to app state"
    (let [carried {:work/id [:rf.work/http :a 1] :generation 1}
          current {:work/id [:rf.work/http :a 1] :generation 2}
          app-target {:event [:app/on-reply] :dispatch-stale? true}
          data (try (reply/suppress app-target carried current)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/reply-unauthorized-stale-delivery (:rf.error/id data))
          "an app target overreaching for stale delivery is rejected"))))

;; ---------------------------------------------------------------------------
;; PROPERTY 5 — data-only invariant: a host handle (fn / Promise /
;; AbortController / DOM node / Date) never survives into an egressed reply
;; or a persisted (durable) target. validate-reply flags it; durable-target
;; fails loud.
;; ---------------------------------------------------------------------------

(deftest host-handle-never-egresses-in-reply-or-target
  (testing "validate-reply flags a host handle anywhere in the reply map, and
            durable-target fails loud on a handle in a persisted target"
    ;; A fn is a host handle on BOTH runtimes (no host-specific object needed).
    (let [handle (fn [] :callback)
          reply-with-handle {:status :ok :value {:on-done handle}
                             :work/id [:rf.work/http :h 1] :work/status :completed}
          problems (reply/validate-reply reply-with-handle)]
      (is (some #(= :rf.reply/host-handle (:rf.reply/problem %)) problems)
          "validate-reply flags the host handle in the reply :value"))
    ;; A durable target carrying a host handle in a PUBLIC field fails loud.
    (let [bad-target {:event [:app/on-reply (fn [] :smuggled)] :delivery :append}
          data (try (reply/durable-target bad-target)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/reply-non-data-target (:rf.error/id data))
          "a host handle in a durable target's public field fails loud"))
    ;; A clean data-only reply / target passes.
    (is (reply/valid-reply? {:status :ok :value {:public 1}
                             :work/id [:rf.work/http :h 2] :work/status :completed}))
    (is (reply/data-only-target? {:event [:app/on-reply] :delivery :append}))))

;; ---------------------------------------------------------------------------
;; PROPERTY 6 — the off-box record projection (project-egress) of a reply
;; VALUE redacts the declared-sensitive leaf and elides the declared-large
;; leaf under the frame's classification (the EP-0015 boundary primitive the
;; reply-bearing observability records cross), and fails closed frameless.
;; ---------------------------------------------------------------------------

(deftest reply-value-projects-through-project-egress
  (testing "project-egress of a reply value under an off-box profile redacts
            the declared-sensitive leaf, elides large, and fails closed when
            no frame is known (the EP-0015 reply-bearing-record boundary)"
    (mk-frame! :reply/proj)
    (let [value {:token sentinel :doc big-string :public 7}
          out (rf/project-egress value
                {:frame :reply/proj
                 :rf.egress/profile :rf.egress/off-box-tool})]
      (is (gen/redacted? (:token out)) "off-box-tool redacts the sensitive reply leaf")
      (is (gen/large-marker? (:doc out)) "off-box-tool elides the large reply leaf")
      (is (= 7 (:public out)))
      (is (not (contains-sentinel? out))))
    ;; Frameless project-egress fails closed (no :rf/default synthesis).
    (binding [frame/*current-frame* nil]
      (let [out (rf/project-egress {:token sentinel}
                  {:rf.egress/profile :rf.egress/off-box-observability})]
        (is (gen/redacted? out) "frameless reply-value egress fails closed")))))
