(ns re-frame.always-on-axis-conformance-cljs-test
  "Spec 009 §Error event catalogue: every `always-on` category is exercised
  through the `register-error-listener!` substrate in at least one test. This
  drives each category in `always-on-categories` through the always-on axis
  and asserts the listener fan-out; the per-site emit tests live with their
  emit sites. `re-frame.error-catalogue-channel-conformance-test` holds the
  literal equal to the catalogue's always-on set."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def always-on-categories
  "Every category Spec 009 §Error event catalogue marks `always-on`. Membership
  is the channel cell, not the namespace: the two `:rf.ssr/*` members belong."
  #{:rf.error/handler-exception
    :rf.error/coeffect-exception
    :rf.error/interceptor-exception
    :rf.error/machine-action-exception
    :rf.error/fx-handler-exception
    :rf.error/sub-exception
    :rf.error/no-such-sub
    :rf.error/sub-input-fn-exception
    :rf.error/sub-input-fn-bad-return
    :rf.error/no-such-handler
    :rf.error/no-frame-context
    :rf.error/bad-frame-provider-arg
    :rf.error/ambient-frame-refused
    :rf.error/override-fallthrough
    :rf.error/reserved-fx-override
    :rf.error/no-such-fx
    :rf.error/frame-destroyed
    :rf.error/write-after-destroy
    :rf.error/flow-eval-exception
    :rf.error/http-reply-tail-failed
    :rf.error/frame-teardown-failed
    :rf.error/on-destroy-handler-exception
    :rf.error/ssr-render-failed
    :rf.error/ssr-streaming-writer-failed
    :rf.error/malformed-hydration-payload
    :rf.error/ssr-head-resolution-failed
    :rf.error/sanitised-on-projection
    :rf.error/ssr-ring-error-view-failed
    :rf.error/ssr-ring-response-status-invalid
    :rf.error/hydration-frame-id-mismatch
    :rf.error/root-boot-failed
    :rf.error/unregistered-cofx
    :rf.error/missing-required-cofx
    :rf.error/cofx-value-invalid
    :rf.error/inject-cofx-removed
    :rf.error/reg-event-db-removed
    :rf.error/reg-event-fx-removed
    :rf.error/reg-event-ctx-removed
    :rf.error/machine-spawn-unregistered-type
    :rf.error/drain-depth-exceeded
    :rf.error/unsupported-scroll-strategy
    :rf.error/safe-redirect-invalid-url
    :rf.error/safe-redirect-scheme-rejected
    :rf.error/safe-redirect-host-disallowed
    :rf.error/schema-validation-failure
    :rf.error/classification-effect-shape
    :rf.error/legacy-runtime-root
    :rf.error/effect-map-shape
    :rf.ssr/hydration-mismatch
    :rf.ssr/suspense-boundary-failed})

;; The frame-teardown row rides the bounded one-record-per-destroy report.
(def ^:private report-categories
  #{:rf.error/frame-teardown-failed})

;; Non-event facts (SSR, page lifecycle, fx-time policy, drain halt, boundary
;; rejection) ride the general union-record helper `dispatch-error-record!`;
;; every other category rides the per-event `dispatch-on-error!`.
(def ^:private record-categories
  #{:rf.error/ssr-render-failed
    :rf.error/ssr-streaming-writer-failed
    :rf.error/malformed-hydration-payload
    :rf.error/ssr-head-resolution-failed
    :rf.error/sanitised-on-projection
    :rf.error/ssr-ring-error-view-failed
    :rf.error/ssr-ring-response-status-invalid
    :rf.error/hydration-frame-id-mismatch
    :rf.error/drain-depth-exceeded
    :rf.error/root-boot-failed
    :rf.error/safe-redirect-invalid-url
    :rf.error/safe-redirect-scheme-rejected
    :rf.error/safe-redirect-host-disallowed
    :rf.error/schema-validation-failure
    :rf.ssr/hydration-mismatch
    :rf.ssr/suspense-boundary-failed})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(defn- drive-category!
  "Push one synthetic record for `cat` through the always-on axis. The payload
  is not the contract here; the per-site tests pin payload shapes."
  [cat]
  (cond
    (contains? report-categories cat)
    ;; non-empty, or the report fn no-ops
    (rf.error-emit/dispatch-frame-teardown-report!
      :conformance/frame
      [{:hook :ssr/on-frame-destroyed
        :exception (ex-info "teardown hook threw" {})
        :where :safe-call-hook!}]
      0)

    (contains? record-categories cat)
    (rf.error-emit/dispatch-error-record!
      {:error     cat
       :frame     :conformance/frame
       :time      0
       :exception (ex-info "conformance" {})
       :recovery  :no-recovery})

    :else
    ;; [error-kw event event-id frame-id exception elapsed-ms time]
    (rf.error-emit/dispatch-on-error!
      cat
      [:conformance/event]
      :conformance/event
      :conformance/frame
      (ex-info "conformance" {})
      0
      0)))

(deftest each-category-fans-out-exactly-once-and-carries-its-category
  (doseq [cat always-on-categories]
    (rf.error-emit/clear-error-listeners!)
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener!
        :conformance/recorder
        (fn [record] (swap! seen conj record)))
      (drive-category! cat)
      (is (= [cat] (mapv :error @seen))
          (str cat ": exactly one always-on record, carrying its category")))))
