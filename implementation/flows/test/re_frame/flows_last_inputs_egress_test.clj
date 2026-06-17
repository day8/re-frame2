(ns re-frame.flows-last-inputs-egress-test
  "EP-0015 (rf2-guga0n): the dirty-check `last-inputs` cache holds the RAW
  flow input values just read from app-db / runtime-db. Those are owner-local
  values and can be sensitive or large. The raw accessor
  (`last-inputs-snapshot`) is an internal/test/rollback seam and is NOT an
  egress boundary; any tool / direct read that crosses a trust boundary must
  go through the PROJECTED accessor (`last-inputs-egress`) which elides each
  cached input under the OWNING frame's policy, keyed by the flow's declared
  input path — and fails closed when no live frame resolves.

  Mirrors the `re-frame.flows/elide-inputs` model the `:rf.flow/computed`
  `:input-values` / `:rf.flow/failed` `:inputs` trace slots already use."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.marks :as marks]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.flows.registry :as registry]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---------------------------------------------------------------------------
;; 1. The projected accessor redacts a sensitive cached input; the raw
;;    accessor still exposes it (raw = internal/test/rollback seam).
;; ---------------------------------------------------------------------------

(deftest last-inputs-egress-redacts-sensitive-cached-input
  (testing "a flow reading a SENSITIVE input caches the raw secret in the
            dirty-check row; `last-inputs-egress` redacts it under the
            owning frame's policy, while `last-inputs-snapshot` (raw) still
            exposes it"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:auth {:token "header.payload.sig"}})}))
    ;; The input slot is sensitive (frame-owned classification).
    (marks/add-marks :rf/default {[:auth :token] :sensitive})
    (rf/reg-flow {:id     :session
                  :inputs [[:auth :token]]
                  :output (fn [_] :ok)
                  :path   [:derived :session]
                  ;; declassify the OUTPUT so we isolate the INPUT-cache leak
                  :rf.egress/output-sensitivity :rf.egress/public})
    (rf/dispatch-sync [:init])
    ;; Precondition: the raw dirty-check row cached the secret verbatim.
    (is (= ["header.payload.sig"]
           (get-in (registry/last-inputs-snapshot) [:session :rf/default]))
        "raw accessor caches the sensitive input verbatim (internal seam)")
    ;; THE FIX: the projected accessor redacts the sensitive cached input.
    (is (= [privacy/redacted-sentinel]
           (get-in (flows/last-inputs-egress) [:session :rf/default]))
        "last-inputs-egress redacts the sensitive cached input value")))

;; ---------------------------------------------------------------------------
;; 2. A non-sensitive cached input rides raw through the projected accessor.
;; ---------------------------------------------------------------------------

(deftest last-inputs-egress-passes-non-sensitive-input
  (testing "an unclassified input rides raw through the projected accessor —
            projection is a no-op when no declaration matches"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 7})}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :double]})
    (rf/dispatch-sync [:init])
    (is (= [7] (get-in (flows/last-inputs-egress) [:double :rf/default]))
        "an unclassified input value is unchanged by projection")))

;; ---------------------------------------------------------------------------
;; 3. Fail-closed: an unresolvable frame redacts the whole cached input.
;; ---------------------------------------------------------------------------

(deftest last-inputs-egress-fails-closed-for-dead-frame
  (testing "a cached row attributed to a frame that is not live FAILS CLOSED —
            the projected accessor redacts rather than shipping a raw value
            under no policy (EP-0015 §13)"
    ;; Seed a dirty-check row for a flow registered against a frame that is
    ;; NOT live. last-inputs-egress has no policy to apply, so it must redact.
    (registry/set-frame-flow-last-inputs! :ghost-frame :ghost-flow ["secret-value"])
    (is (= ["secret-value"]
           (get-in (registry/last-inputs-snapshot) [:ghost-flow :ghost-frame]))
        "raw accessor exposes the orphaned cached value")
    (is (= [privacy/redacted-sentinel]
           (get-in (flows/last-inputs-egress) [:ghost-flow :ghost-frame]))
        "projected accessor fails closed for an unresolvable frame")))
