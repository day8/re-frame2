(ns re-frame.security.classification-fail-open-security-cljs-test
  "Adversarial tests for path-based classification at egress.

  A `:sensitive` effect classifies an app-db path, not a value. The egress
  walker redacts that path, but an identical value copied to an unclassified
  path remains visible. Classification therefore provides egress hygiene, not
  taint tracking or a secrecy guarantee.

  Event redaction has the same constraint: only paths in the event's argument
  map are addressable. Positional arguments cannot be classified and pass
  through unchanged."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; The fixture supplies the default frame required by dispatch and egress.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "S3CR3T-rf2-nk2h6m-DO-NOT-LEAK")

(defn- contains-secret?
  "True when the raw secret survives anywhere in the printed form of `x`."
  [x]
  (str/includes? (pr-str x) secret))

(deftest classified-path-redacts-at-egress
  (testing "a path classified :sensitive by the commit-plane effect redacts to
            :rf/redacted at the egress walker; the app sees the raw value"
    (rf/reg-event :auth/login
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:auth :token] secret)
         :sensitive [[:auth :token]]}))
    (rf/dispatch-sync [:auth/login])
    (is (= secret (get-in (rf.frame/frame-app-db-value :rf/default) [:auth :token]))
        "classification does not alter app-db")
    (let [wire (rf/elide-wire-value (rf.frame/frame-app-db-value :rf/default))]
      (is (= rf.privacy/redacted-sentinel (get-in wire [:auth :token]))
          "the classified path projects :rf/redacted at egress")
      (is (not (contains-secret? wire))
          "the raw secret does not survive anywhere in the projected value"))))

(deftest unclassified-copy-of-secret-ships-raw
  (testing "a byte-identical COPY of a classified secret at a DIFFERENT,
            unclassified path ships RAW at egress — classification is
            path-based and fail-open; there is no value-match redaction"
    (rf/reg-event :auth/login-and-copy
      (fn [{:keys [db]} _]
        {:db        (-> db
                        (assoc-in [:auth :token] secret)
                        (assoc-in [:ui :rendered-token] secret))
         :sensitive [[:auth :token]]}))
    (rf/dispatch-sync [:auth/login-and-copy])
    (let [wire (rf/elide-wire-value (rf.frame/frame-app-db-value :rf/default))]
      (is (= rf.privacy/redacted-sentinel (get-in wire [:auth :token]))
          "the classified path is redacted")
      (is (= secret (get-in wire [:ui :rendered-token]))
          "the unclassified copy remains visible because values are not tracked")
      (is (contains-secret? wire)
          "the secret survives at the unclassified path (the fail-open contract)"))))

(deftest forgotten-classification-ships-raw
  (testing "a secret written with NO classification at all ships raw —
            forgetting to classify is fail-open, not a guarded leak"
    (rf/reg-event :auth/login-unclassified
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:auth :token] secret)}))
    (rf/dispatch-sync [:auth/login-unclassified])
    (let [wire (rf/elide-wire-value (rf.frame/frame-app-db-value :rf/default))]
      (is (= secret (get-in wire [:auth :token]))
          "the unclassified path ships raw — fail-open on omission")
      (is (contains-secret? wire)))))

;; redact-event addresses paths relative to the event's argument map. A
;; positional argument has no declarable path, so authors must use a map payload
;; when an event argument needs classification.

(deftest positional-event-arg-ships-raw
  (testing "a secret in a POSITIONAL event arg passes through redact-event
            UNCHANGED — positional indices are not path-addressable, so the
            path-based redactor cannot reach them (KNOWN fail-open limitation)"
    (let [positional-event [:auth/login "alice" secret]
          redacted         (rf.privacy/redact-event positional-event [[:token]])]
      (is (= positional-event redacted)
          "the positional-arg event passes through redact-event unchanged")
      (is (= secret (nth redacted 2))
          "the secret survives in the positional slot because a
           positional index is not path-redactable")
      (is (contains-secret? redacted)
           "the secret egresses through redact-event (the documented
            positional-arg limitation — prefer the map payload form)")))

  (testing "the MAP payload form of the same event IS path-redactable — the
            authorial remediation: carry sensitive args in the arg-map and
            classify the path"
    (let [map-event [:auth/login {:user "alice" :token secret}]
          redacted  (rf.privacy/redact-event map-event [[:token]])]
      (is (= rf.privacy/redacted-sentinel (get-in redacted [1 :token]))
          "the classified arg-map path redacts to :rf/redacted")
      (is (not (contains-secret? redacted))
          "no raw secret survives when the secret rides a classified arg-map
           path — the recommended shape for sensitive args"))))
