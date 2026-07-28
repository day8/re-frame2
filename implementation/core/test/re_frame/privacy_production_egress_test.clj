(ns re-frame.privacy-production-egress-test
  "rf2-r9bra — the PRODUCTION-SURVIVING half of the privacy surface.

  ## Why this namespace exists

  `re-frame.redact-interceptor-test` and `re-frame.sensitive-stamping-test`
  are the repo's two privacy suites, and both capture EXCLUSIVELY through
  `(rf/register-listener! :trace …)`. That listener is dev-only: under the
  documented production posture (`-Dre-frame.debug=false` / `goog.DEBUG=false`)
  the framework emits no trace events at all, so every assertion in both suites
  reads `nil` and the suites go red in the `jvm-core-prod-gate` lane.

  The redness is test SPELLING — but it left a genuine hole. Between them
  those suites covered ZERO of the redaction that actually ships: the
  always-on error-emit substrate (`re-frame.error-emit`, Spec 009 §What IS
  available in production) survives both gates and carries an `:event` slot
  to off-box shippers (Sentry / Datadog / Honeybadger). THAT is the surface a
  production build can leak from, and nothing asserted against it in either
  posture.

  ## What is pinned here

  Every assertion below is POSTURE-INDEPENDENT and must hold in dev AND under
  the real gate — that is the whole point, so this namespace runs in the
  ordinary `clojure -M:test` suite and in `scripts/test-core-prod-gate.sh`
  (which is an exclusion roster, so a new namespace joins it by default).

  The three independent producers that must all keep feeding the always-on
  record:

    1. EP-0025 commit-plane classification — a frame-declared `:sensitive`
       app-db path overlapping a path-scoped handler's slice. The router
       installs `privacy/schema-redaction-interceptor` UNCONDITIONALLY in
       `prepare-handler-ctx` (no `debug-enabled?` gate); its output rides
       `:rf/redacted-event`, which `privacy/redacted-event-from-ctx` hands to
       `error-emit/emit-error-both!`.
    2. The user-installed `privacy/redact-interceptor`, whose `:before`
       extends the same `:rf/redacted-event` slot.
    3. `elision/elide-wire-value`, run unconditionally inside
       `error-emit/dispatch-on-error!` over the per-frame
       `[:rf.runtime/elision :sensitive-declarations]` registry — the
       belt-and-braces path that redacts even for emission sites that never
       ran an interceptor chain (`:rf.error/no-such-handler` below).

  A regression in ANY of the three is a production data-exposure defect, and
  before this namespace existed none of the three had a test that could
  observe it under the posture where it matters.

  ## Why each producer's observation is UNMASKED (rf2-r6zby)

  The three producers all end at the same place — the `:event` slot of one
  always-on record — so a test that merely watches that slot cannot say WHICH
  producer scrubbed it. `dispatch-on-error!` runs `elide-wire-value` on every
  emission unconditionally, so producer 3 is a standing candidate to mask
  producers 1 and 2, and a masked test would stay green through the removal of
  the very code it claims to pin.

  It does not mask them here, and that is now ASSERTED rather than argued.
  Producers 1 and 2 each open with a CONTROL: the wire-walker is run over the
  same event, in the same frame, in the same registry state, and must return it
  UNCHANGED. Only then is the redaction observed further down attributable to
  the router's `schema-redaction-interceptor` (producer 1) or to the user's
  `redact-interceptor` (producer 2). The controls hold for structural reasons —
  producer 1 classifies an app-db coordinate the walker cannot reach through an
  event vector, and producer 2 declares no app-db classification at all — but
  structure changes, so the controls are checked every run.

  If a control ever goes red, the walker has grown to cover that coordinate and
  the producer beneath it has quietly become unobservable. The fix is to move
  that test onto a coordinate the walker still cannot reach, NOT to delete the
  control and NOT to narrow the walker.

  ## Sentinels

  Each producer's secret is a distinctive string that appears nowhere else in
  the corpus, and each test asserts that string absent from `(pr-str record)` —
  the whole record, every slot, by any route. Naming the expected coordinate is
  not enough: the record carries `:exception`, `:source-coord` and caller
  attribution alongside `:event`, and a secret that arrives through a slot
  nobody thought to check is still a secret on a shipper's wire.

  The sentinels are also placed where a NAIVE redactor would miss them. Producer
  1 hides the secret in a subtree under the classified leaf, in two keys, one of
  which is not named `:password` — a redactor that scrubbed scalars, or known key
  names, would ship the other. Producer 3 hides it at `[:attempts 0 :token]`
  under the declaration `[:attempts :token]` — a `get-in`-based redactor finds
  nothing at all to scrub there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- install-sensitive!
  "Seed the frame's sensitive app-db classification through the EP-0025
  commit-plane classification effect path — the same registry write a
  `reg-event` returning `:sensitive [[…]]` performs."
  [frame-id paths]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

(defn- record-always-on-errors
  "Capture through the ALWAYS-ON registry — NOT `register-listener! :trace`.
  This is the distinction the whole namespace turns on: the trace listener is
  dev-only and sees nothing under the production gate, whereas these records
  are exactly what an off-box shipper receives from a production build."
  [body-fn]
  (let [seen (atom [])]
    (error-emit/register-error-listener! ::rec (fn [r] (swap! seen conj r)))
    (try (body-fn)
         (finally (error-emit/unregister-error-listener! ::rec)))
    @seen))

(defn- record-of [records error-kw]
  (first (filter #(= error-kw (:error %)) records)))

;; ---- sentinels -------------------------------------------------------------
;;
;; One distinctive string per producer, so a failure message names the leaked
;; material rather than reporting an anonymous inequality, and so `leaked?` can
;; scan the whole record without any chance of matching framework text.

(def ^:private chain-secret     "rf2-sentinel-chain-0f3a91")
(def ^:private user-secret      "rf2-sentinel-user-7c21bd")
(def ^:private chainless-secret "rf2-sentinel-chainless-4e88fa")

(defn- leaked?
  "Does `secret` survive anywhere in `record` — any slot, by any route?

  Asserting the expected coordinate redacted proves that coordinate was
  handled. It does not prove the secret left. The record ships `:exception`,
  `:source-coord` and whatever attribution the emitting caller lifted onto it,
  and any of those is a wire an off-box shipper reads. So the question this
  answers is the one that matters to a Sentry payload: is the string GONE."
  [record secret]
  (str/includes? (pr-str record) secret))

(deftest always-on-record-honours-frame-classification
  (testing "producer 1 — an EP-0025-classified sensitive app-db path overlapping
            a path-scoped handler's slice redacts the always-on error record's
            `:event`, in EVERY posture. The handler body still sees the raw
            payload via the `:event` coeffect; only the egress copy is scrubbed."
    (install-sensitive! :rf/default [[:auth :session :credentials]])
    (let [payload {:username    "ada"
                   ;; The secret rides a SUBTREE under the classified leaf, in
                   ;; two keys — one obvious, one not. The framework replaces
                   ;; the whole subtree, so both go; a redactor that scrubbed
                   ;; scalars, or key names it recognised, would ship `:confirm`.
                   :credentials {:password chain-secret
                                 :confirm  chain-secret}}
          event   [:auth/throws payload]
          seen    (atom nil)]
      (rf/reg-event :auth/throws
        {:interceptors [[:rf.interceptor/path [:auth :session]]]}
        (fn [_ [_ p]]
          (reset! seen p)
          (throw (ex-info "boom" {}))))
      (is (= event (elision/elide-wire-value event {:frame :rf/default}))
          (str "CONTROL — the wire-walker (producer 3) leaves this event "
               "untouched: it applies the ABSOLUTE declaration "
               "[:auth :session :credentials] to an event vector that offers no "
               ":auth hop, whereas the router hands the interceptor the path "
               "RELATIVE to the handler's slice. Everything below therefore "
               "observes producer 1 alone. If this line reds, move the test to "
               "a coordinate the walker still cannot reach"))
      (let [err (-> (record-always-on-errors #(rf/dispatch-sync event))
                    (record-of :rf.error/handler-exception))]
        (is (some? err)
            "the always-on error record fired (it is NOT debug-gated)")
        (is (= payload @seen)
            "the handler body received the UNREDACTED payload")
        (is (= :rf/redacted (get-in err [:event 1 :credentials]))
            "the classified subtree is redacted WHOLESALE on the egress record")
        (is (= "ada" (get-in err [:event 1 :username]))
            "an unclassified key is not over-redacted")
        (is (not (leaked? err chain-secret))
            (str "the secret " chain-secret " must not survive anywhere in the "
                 "record an off-box shipper receives"))))))

(deftest always-on-record-honours-user-redact-interceptor
  (testing "producer 2 — a user-installed `redact-interceptor` extends
            `:rf/redacted-event`, and that extension reaches the always-on
            record. Neither the interceptor's installation nor its `:before`
            is `debug-enabled?`-gated."
    (rf/reg-interceptor :rf/redact-interceptor
      (privacy/redact-interceptor [[:password] [:token]]))
    (rf/reg-event :auth/explode
      {:interceptors [:rf/redact-interceptor]}
      (fn [_ _] (throw (ex-info "boom" {}))))
    (let [event [:auth/explode {:username "ada"
                                :password user-secret
                                :token    user-secret}]]
      (is (= event (elision/elide-wire-value event {:frame :rf/default}))
          (str "CONTROL — this frame declares NO sensitive app-db path, so the "
               "wire-walker (producer 3) is the identity transform over this "
               "event and cannot account for the redaction below. A user "
               "`redact-interceptor` declares PAYLOAD paths, which never enter "
               "the frame's classification registry"))
      (let [err (-> (record-always-on-errors #(rf/dispatch-sync event))
                    (record-of :rf.error/handler-exception))]
        (is (some? err) "the always-on error record fired")
        (is (= :rf/redacted (get-in err [:event 1 :password])))
        (is (= :rf/redacted (get-in err [:event 1 :token]))
            "BOTH declared paths scrub — the union, not just the first")
        (is (= "ada" (get-in err [:event 1 :username])))
        (is (not (leaked? err user-secret))
            (str "the secret " user-secret " must not survive anywhere in the "
                 "record an off-box shipper receives"))))))

(deftest always-on-record-honours-wire-walker-without-a-chain
  (testing "producer 3 — `:rf.error/no-such-handler` is emitted before any
            interceptor chain is assembled, so `:rf/redacted-event` was never
            stashed. The record is nonetheless redacted, because
            `dispatch-on-error!` runs `elision/elide-wire-value` over the
            per-frame declarations unconditionally. This is the belt-and-braces
            arm and the one that covers emission sites with no chain at all."
    (install-sensitive! :rf/default [[:attempts :token]])
    (let [payload {:username "ada" :attempts [{:token chainless-secret}]}
          event   [:auth/missing payload]]
      (is (nil? (get-in payload [:attempts :token]))
          (str "the secret sits at [:attempts 0 :token] while the declaration "
               "reads [:attempts :token] — a `get-in`-shaped redactor finds "
               "nothing here to scrub, and ships it. The walker descends "
               "positional containers index-free, so it does not"))
      (let [err (-> (record-always-on-errors
                      #(try (rf/dispatch-sync event)
                            (catch Throwable _ nil)))
                    (record-of :rf.error/no-such-handler))]
        (is (some? err) "the always-on record fired for the chainless category")
        (is (= :rf/redacted (get-in err [:event 1 :attempts 0 :token]))
            "the wire-walker redacted without any interceptor having run")
        (is (= "ada" (get-in err [:event 1 :username]))
            "an unclassified key is not over-redacted")
        (is (not (leaked? err chainless-secret))
            (str "the secret " chainless-secret " must not survive anywhere in "
                 "the record an off-box shipper receives"))))))

(deftest wire-walker-redacts-in-every-posture
  (testing "`elide-wire-value` — the shared redactor under every off-box egress
            surface — is not `debug-enabled?`-gated."
    (install-sensitive! :rf/default [[:auth :password]])
    (let [out (elision/elide-wire-value {:auth {:password "shh" :user "ada"}}
                                        {:frame :rf/default})]
      (is (= :rf/redacted (get-in out [:auth :password])))
      (is (= "ada" (get-in out [:auth :user]))))))
