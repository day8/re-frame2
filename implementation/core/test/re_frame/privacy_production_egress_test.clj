(ns re-frame.privacy-production-egress-test
  "The PRODUCTION-SURVIVING half of the privacy surface: the always-on
  error-emit record (Spec 009 §What IS available in production) carries an
  `:event` slot to off-box shippers in every build, while the trace-listener
  privacy suites read nothing under `-Dre-frame.debug=false`. Every assertion
  here is posture-independent and runs in both lanes.

  Two independent producers feed that slot, and each is observed alone:

    1. EP-0025 classification: the router's unconditional
       `schema-redaction-interceptor` over a path-scoped handler's slice.
    2. `rf.elision/elide-wire-value`, run inside `dispatch-on-error!` on every
       emission, which covers sites with no interceptor chain.

  Producer 2 runs on every emission, so it could mask 1. Producer 1's test
  opens with a CONTROL showing the walker leaves the same event untouched; if
  it reds, move that test to a coordinate the walker cannot reach rather
  than narrowing the walker.

  Each secret is a distinctive sentinel asserted absent from the WHOLE record,
  because `:exception`, `:source-coord` and caller attribution ride beside
  `:event`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.error-emit/clear-error-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- install-sensitive! [frame-id paths]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

(defn- always-on-error
  "The first always-on record of `error-kw` that `body-fn` fans — what an
  off-box shipper receives from a production build."
  [error-kw body-fn]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! seen conj r)))
    (try (body-fn)
         (finally (rf.error-emit/unregister-error-listener! ::rec)))
    (first (filter #(= error-kw (:error %)) @seen))))

(def ^:private chain-secret     "rf2-sentinel-chain-0f3a91")
(def ^:private chainless-secret "rf2-sentinel-chainless-4e88fa")

(defn- leaked? [record secret]
  (str/includes? (pr-str record) secret))

(deftest always-on-record-honours-frame-classification
  (testing "producer 1 — a classified app-db path overlapping a path-scoped
            handler's slice redacts the record's `:event` wholesale; the
            handler body still sees the raw payload"
    (install-sensitive! :rf/default [[:auth :session :credentials]])
    (let [payload {:username    "ada"
                   ;; Two keys, one not named `:password`: a redactor that
                   ;; scrubbed scalars or known key names would ship `:confirm`.
                   :credentials {:password chain-secret
                                 :confirm  chain-secret}}
          event   [:auth/throws payload]
          seen    (atom nil)]
      (rf/reg-event :auth/throws
        {:interceptors [[:rf.interceptor/path [:auth :session]]]}
        (fn [_ [_ p]]
          (reset! seen p)
          (throw (ex-info "boom" {}))))
      (is (= event (rf.elision/elide-wire-value event {:frame :rf/default}))
          (str "CONTROL — the walker applies the ABSOLUTE declaration to an "
               "event vector with no :auth hop, so it cannot account for the "
               "redaction below"))
      (let [err (always-on-error :rf.error/handler-exception
                                 #(rf/dispatch-sync event))]
        (is (= payload @seen) "the handler body received the unredacted payload")
        (is (= [:auth/throws {:username "ada" :credentials :rf/redacted}]
               (:event err)))
        (is (not (leaked? err chain-secret)))))))

(deftest always-on-record-honours-wire-walker-without-a-chain
  (testing "producer 2 — `:rf.error/no-such-handler` is emitted before any
            chain is assembled, yet the record is redacted by the walker over
            the frame's declarations"
    ;; The secret sits at [:attempts 0 :token] under the declaration
    ;; [:attempts :token]: a `get-in` redactor finds nothing there, while the
    ;; walker descends positional containers index-free.
    (install-sensitive! :rf/default [[:attempts :token]])
    (let [event [:auth/missing {:username "ada" :attempts [{:token chainless-secret}]}]
          err   (always-on-error :rf.error/no-such-handler
                                 #(try (rf/dispatch-sync event)
                                       (catch Throwable _ nil)))]
      (is (= [:auth/missing {:username "ada" :attempts [{:token :rf/redacted}]}]
             (:event err)))
      (is (not (leaked? err chainless-secret))))))
