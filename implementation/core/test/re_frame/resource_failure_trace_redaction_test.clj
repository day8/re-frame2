(ns re-frame.resource-failure-trace-redaction-test
  "Per rf2-7qbxbm (originally rf2-zljx0j restore) — the ON-BOX
  (DCE'd-in-production) leg of the resource / mutation FAILURE trace egress.

  The `:rf.resource/failed` / `:rf.resource/page-failed` / `:rf.mutation/failed`
  traces carry the `:rf.http/*` failure ENVELOPE — the raw server response
  (`:body` / `:body-text` / `:detail`) echoing submitted form fields — under
  `:error` / `:page-error`. Without a clause for it, that envelope would pass
  through the on-box dev-trace chokepoint
  (`re-frame.classification/project-trace-event`, formerly
  `re-frame.marks/project-trace-event`) verbatim to on-box listeners / epoch
  capture. `project-trace-event`'s resource-failure clause summarizes the
  envelope slot to `:rf/redacted`.

  This is the ON-BOX sibling of the OFF-BOX coverage in
  `re-frame.epoch.epoch-egress-resource-trace-test` (which exercises the
  `project-egress` corpus leg). These assertions originally lived in
  `re-frame.marks-test` (PR #4849, rf2-7qbxbm); the EP-0025 marks purge deleted
  `marks_test.clj`, dropping them. They are restored here against the current
  registry-read classification model — note the clause is OPERATION-keyed (not
  registry-derived), so it does not depend on the removed marks re-derivation.

  Production elision of the whole projection cost is pinned separately by the
  `re-frame.elision-probe` + `scripts/check-elision.cjs` gate."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.classification :as classification]
            [re-frame.privacy :as privacy]))

;; ---- rf2-7qbxbm: resource/mutation FAILURE :error / :page-error envelope ---

(def ^:private http-failure-envelope
  "The raw `:rf.http/*` failure reply echoing a submitted form field. The
  `:body-text` carries `on-box-secret-PII` — the leak vector the redaction
  closes."
  {:status    422
   :body-text "{\"auth-token\":\"on-box-secret-PII\"}"
   :detail    :rf.http/http-4xx})

(defn- envelope-contains-secret?
  "Deep scan for the secret marker in any string anywhere within `v` — proves
  no raw response-body fragment survives on the projected trace (pins the leak
  vector so the redaction assertion is not vacuous)."
  [v]
  (boolean
    (cond
      (string? v) (.contains ^String v "on-box-secret")
      (map? v)    (or (some envelope-contains-secret? (keys v))
                      (some envelope-contains-secret? (vals v)))
      (coll? v)   (some envelope-contains-secret? v)
      :else       false)))

(deftest resource-failed-error-envelope-redacted-on-box
  (testing "rf2-7qbxbm (on-box leg): a :rf.resource/failed trace's :error HTTP
            failure envelope is summarized to :rf/redacted; structural status
            tags ride; no raw secret survives"
    (let [projected (classification/project-trace-event
                      {:operation :rf.resource/failed
                       :op-type   :rf.event
                       :tags      {:rf.frame/id    :rf/default
                                   :frame          :rf/default
                                   :status-before  :loading
                                   :status-after   :error
                                   :error          http-failure-envelope}})]
      (is (= privacy/redacted-sentinel (get-in projected [:tags :error]))
          "the :error HTTP failure envelope is redacted on-box")
      (is (= :loading (get-in projected [:tags :status-before]))
          "the structural status-before tag rides verbatim")
      (is (= :error (get-in projected [:tags :status-after]))
          "the structural status-after tag rides verbatim")
      (is (not (envelope-contains-secret? (:tags projected)))
          "no raw response-body secret survives on the projected trace"))))

(deftest resource-page-failed-page-error-envelope-redacted-on-box
  (testing "rf2-7qbxbm (on-box leg): a :rf.resource/page-failed trace's
            :page-error envelope (load-more third error channel) is redacted"
    (let [projected (classification/project-trace-event
                      {:operation :rf.resource/page-failed
                       :op-type   :rf.event
                       :tags      {:rf.frame/id :rf/default
                                   :frame       :rf/default
                                   :page-error  http-failure-envelope}})]
      (is (= privacy/redacted-sentinel (get-in projected [:tags :page-error]))
          "the :page-error envelope is redacted on-box")
      (is (not (envelope-contains-secret? (:tags projected)))
          "no raw secret survives"))))

(deftest mutation-failed-error-envelope-redacted-on-box
  (testing "rf2-7qbxbm (on-box leg): a :rf.mutation/failed trace's :error
            envelope is redacted; the mutation id structural tag rides"
    (let [projected (classification/project-trace-event
                      {:operation :rf.mutation/failed
                       :op-type   :rf.event
                       :tags      {:rf.frame/id :rf/default
                                   :frame       :rf/default
                                   :mutation    :m/save
                                   :instance    7
                                   :error       http-failure-envelope}})]
      (is (= privacy/redacted-sentinel (get-in projected [:tags :error]))
          "the mutation failure envelope is redacted on-box")
      (is (= :m/save (get-in projected [:tags :mutation]))
          "the mutation id structural tag rides verbatim")
      (is (not (envelope-contains-secret? (:tags projected)))
          "no raw secret survives"))))

(deftest unrelated-trace-with-scalar-error-keyword-untouched
  (testing "rf2-7qbxbm guard: the resource-failure clause is keyed on the
            FAILURE ops, so an UNRELATED trace carrying a scalar :error status
            keyword (not an HTTP envelope) is NOT swept up — no over-redaction"
    (let [projected (classification/project-trace-event
                      {:operation :rf.resource/work-completed
                       :op-type   :rf.event
                       :tags      {:rf.frame/id :rf/default
                                   :frame       :rf/default
                                   :status      :error}})]
      (is (= :error (get-in projected [:tags :status]))
          "a scalar :error status on a non-failure op rides verbatim"))))
