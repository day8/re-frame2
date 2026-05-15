(ns day8.re-frame2-causa.panels.schema-violation-timeline-helpers-cljs-test
  "Pure-data tests for Causa's Schema-violation Timeline helpers
  (Phase 5, rf2-htffa).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `time_travel_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **Recovery → colour mapping** — all five Spec 010 recovery
       modes map to the correct colour; `:re-raised` carries a
       thicker stroke; unknown recoveries fall back to the grey
       defensive colour.
    2. **schema-violation-event?** — classifies trace events.
    3. **project-violation / project-violations** — projects raw
       trace events onto row cells.
    4. **window projection** — `violation-in-window?` +
       `violation-x-position` are total over edge cases.
    5. **project-rows** — registered schemas drive row order; new
       rows surface `:first? true` on empty→non-empty transitions.
    6. **empty-state-kind** — classifies the three empty-state
       branches.
    7. **format-tooltip** — builds the one-line hover summary."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.schema-violation-timeline-helpers :as h]))

;; ---- fixture builders ---------------------------------------------------

(defn- failure-ev
  "Build a minimal `:rf.error/schema-validation-failure` trace event
  fixture. Mirrors the shape Spec 009's error-event catalogue
  documents: `:op-type :error`, top-level `:recovery`,
  `:tags {:where :path :value :schema :explain :frame
  :dispatch-id}`."
  ([id schema-id recovery]
   (failure-ev id schema-id recovery {}))
  ([id schema-id recovery {:keys [where path value explain frame dispatch-id time]
                           :or {where :app-db
                                path  [:auth :email]
                                value nil
                                frame :rf/default
                                time  1000}}]
   (cond-> {:id        id
            :op-type   :error
            :operation :rf.error/schema-validation-failure
            :time      time
            :recovery  recovery
            :tags      (cond-> {:where  where
                                :path   path
                                :value  value
                                :schema schema-id
                                :frame  frame}
                         explain      (assoc :explain explain)
                         dispatch-id  (assoc :dispatch-id dispatch-id))})))

;; ---- (1) recovery → colour mapping --------------------------------------

(deftest recovery-colour-mapping-honours-spec
  (testing "every Spec 010 recovery maps to the spec-defined colour"
    (is (= h/colour-red    (h/recovery->colour :skip-handler)))
    (is (= h/colour-red    (h/recovery->colour :skip-fx)))
    (is (= h/colour-red    (h/recovery->colour :rollback-db)))
    (is (= h/colour-yellow (h/recovery->colour :replaced-with-default)))
    (is (= h/colour-red    (h/recovery->colour :re-raised)))))

(deftest recovery-colour-unknown-recovery-falls-back
  (testing "an unrecognised recovery falls back to the defensive grey"
    (is (= h/colour-unknown (h/recovery->colour :something-new)))
    (is (= h/colour-unknown (h/recovery->colour nil)))))

(deftest re-raised-renders-thicker-stroke
  (testing ":re-raised gets the thicker stroke (the visual differentiator
            per spec §Recovery → colour mapping)"
    (is (= h/default-re-raised-stroke-width
           (h/recovery->stroke-width :re-raised))
        ":re-raised stroke is thicker")
    (doseq [r [:skip-handler :skip-fx :rollback-db :replaced-with-default]]
      (is (= h/default-base-stroke-width
             (h/recovery->stroke-width r))
          (str r " uses base stroke")))))

(deftest recovery-presentation-bundles-fill-and-stroke
  (testing "recovery->presentation projects fill + stroke-width + re-raised? bit"
    (let [p (h/recovery->presentation :re-raised)]
      (is (= h/colour-red (:fill p)))
      (is (= h/default-re-raised-stroke-width (:stroke-width p)))
      (is (true? (:re-raised? p))))
    (let [p (h/recovery->presentation :replaced-with-default)]
      (is (= h/colour-yellow (:fill p)))
      (is (= h/default-base-stroke-width (:stroke-width p)))
      (is (false? (:re-raised? p))))))

(deftest closed-recovery-set-listed-in-vector
  (testing "the spec §The five recovery categories declared order is
            preserved in `recovery-modes`"
    (is (= [:skip-handler :skip-fx :rollback-db
            :replaced-with-default :re-raised]
           h/recovery-modes))))

;; ---- (2) schema-violation-event? ----------------------------------------

(deftest schema-violation-event-classifier
  (testing "schema-validation-failure events are accepted"
    (is (true? (h/schema-violation-event?
                 (failure-ev 1 :s/auth :skip-handler)))))
  (testing "other op-types / operations are rejected"
    (is (false? (h/schema-violation-event?
                  {:id 1 :op-type :event :operation :event/dispatched})))
    (is (false? (h/schema-violation-event?
                  {:id 1 :op-type :error :operation :rf.error/handler-exception})))
    (is (false? (h/schema-violation-event?
                  {:id 1 :op-type :warning
                   :operation :rf.error/schema-validation-failure})))))

;; ---- (3) project-violation / project-violations -------------------------

(deftest project-violation-projects-all-slots
  (testing "every field the panel reads ends up on the row"
    (let [ev  (failure-ev 99 :schema/user-auth :replaced-with-default
                          {:path [:user :email]
                           :value nil
                           :explain {:message "missing required key"}
                           :dispatch-id 42
                           :time 12345})
          row (h/project-violation ev)]
      (is (= 99 (:id row)))
      (is (= 12345 (:time row)))
      (is (= :schema/user-auth (:schema-id row)))
      (is (= :replaced-with-default (:recovery row)))
      (is (= :app-db (:where row)))
      (is (= [:user :email] (:path row)))
      (is (nil? (:value row)))
      (is (= {:message "missing required key"} (:explain row)))
      (is (= :rf/default (:frame row)))
      (is (= 42 (:dispatch-id row)))
      (is (= ev (:raw row))
          "the raw trace event is preserved for deep-dives"))))

(deftest project-violation-falls-back-to-tags-recovery
  (testing "events emitted before the rf2-wfbn3 :recovery hoist (recovery
            under :tags only) still project correctly"
    (let [ev  {:id 1 :op-type :error
               :operation :rf.error/schema-validation-failure
               :tags {:schema :s/x
                      :recovery :skip-handler
                      :where :event}}
          row (h/project-violation ev)]
      (is (= :skip-handler (:recovery row))
          "falls back to (:recovery tags) when top-level :recovery is absent"))))

(deftest project-violation-falls-back-to-recovery-surface-row
  (testing "events missing both :tags :schema and :tags :failing-id bucket
            under a first-class recovery-surface row when :where exists"
    (let [ev  {:id 1 :op-type :error
               :operation :rf.error/schema-validation-failure
               :tags {:where :app-db}}
          row (h/project-violation ev)]
      (is (= [:schema-recovery :app-db] (:schema-id row))))))

(deftest project-violation-synthetic-schema-id-for-fully-malformed-events
  (testing "events missing schema, failing-id, and where still surface under
            the synthetic ::unknown-schema row"
    (let [ev  {:id 1 :op-type :error
               :operation :rf.error/schema-validation-failure
               :tags {}}
          row (h/project-violation ev)]
      (is (= ::h/unknown-schema (:schema-id row))))))

(deftest project-violation-failing-id-as-schema-fallback
  (testing ":tags :failing-id is read as the schema-id when :tags :schema is
            absent (Story's tags shape per Spec 009 §SchemaValidationTags)"
    (let [ev  {:id 1 :op-type :error
               :operation :rf.error/schema-validation-failure
               :tags {:failing-id :s/user-auth
                      :where :event}}
          row (h/project-violation ev)]
      (is (= :s/user-auth (:schema-id row))))))

(deftest project-violations-filters-and-projects
  (testing "non-failure events are dropped; the rest project chronologically"
    (let [evs [(failure-ev 1 :s/a :skip-handler)
               {:id 2 :op-type :event :operation :event/dispatched}
               (failure-ev 3 :s/b :replaced-with-default)
               {:id 4 :op-type :warning :operation :rf.warning/handler-replaced}
               (failure-ev 5 :s/a :rollback-db)]
          rows (h/project-violations evs)]
      (is (= 3 (count rows)))
      (is (= [1 3 5] (mapv :id rows)))
      (is (= [:s/a :s/b :s/a] (mapv :schema-id rows))))))

;; ---- (4) window projection ----------------------------------------------

(deftest violation-in-window-inclusive
  (testing "the window is inclusive at both ends"
    (let [w {:t0 1000 :t1 2000}]
      (is (true?  (h/violation-in-window? w {:time 1000})))
      (is (true?  (h/violation-in-window? w {:time 1500})))
      (is (true?  (h/violation-in-window? w {:time 2000})))
      (is (false? (h/violation-in-window? w {:time 999})))
      (is (false? (h/violation-in-window? w {:time 2001})))
      (is (false? (h/violation-in-window? w {:time nil}))
          "nil time is rejected, not crashed"))))

(deftest violation-x-position-maps-linearly
  (testing "violations at the window endpoints land at 0 and width"
    (let [w {:t0 1000 :t1 2000}]
      (is (= 0   (h/violation-x-position w 100 {:time 1000})))
      (is (= 100 (h/violation-x-position w 100 {:time 2000})))
      (is (= 50  (h/violation-x-position w 100 {:time 1500})))))
  (testing "out-of-window violations return nil"
    (let [w {:t0 1000 :t1 2000}]
      (is (nil? (h/violation-x-position w 100 {:time 500})))
      (is (nil? (h/violation-x-position w 100 {:time 5000})))))
  (testing "edge cases — zero width, nil time, inverted window"
    (is (nil? (h/violation-x-position {:t0 1000 :t1 2000} 0 {:time 1500})))
    (is (nil? (h/violation-x-position {:t0 1000 :t1 2000} 100 {:time nil})))))

(deftest window-spans-ms-defensive
  (testing "defensive against nil / inverted windows — falls back to the
            default 60s span"
    (is (= h/default-window-ms (h/window-spans-ms nil)))
    (is (= h/default-window-ms (h/window-spans-ms {})))
    (is (= h/default-window-ms (h/window-spans-ms {:t0 2000 :t1 1000})))
    (is (= 5000 (h/window-spans-ms {:t0 1000 :t1 6000})))))

(deftest default-window-for-events-uses-trace-time-domain
  (testing "default windows anchor to the newest trace event timestamp"
    (is (= {:t0 (- 42500 h/default-window-ms)
            :t1 42500}
           (h/default-window-for-events [{:time 10}
                                         {:time nil}
                                         {:time 42500}])))))

;; ---- (5) project-rows ---------------------------------------------------

(deftest project-rows-empty-schemas-empty-violations
  (testing "no schemas, no violations → empty row vector"
    (is (= [] (h/project-rows [] [] {:t0 0 :t1 1000} nil)))))

(deftest project-rows-orders-by-registered-schemas
  (testing "row order mirrors the schemas iteration order"
    (let [schemas    [[:auth] [:cart] [:order]]
          window     {:t0 0 :t1 1000}
          violations [(h/project-violation (failure-ev 1 [:cart] :rollback-db
                                                       {:time 500}))]
          rows       (h/project-rows schemas violations window nil)]
      (is (= [[:auth] [:cart] [:order]]
             (mapv :schema-id rows))
          "rows render in the declared order of (rf/app-schemas)")
      (is (= [true false true] (mapv :empty? rows))
          ":auth and :order are empty in-window; :cart has one violation"))))

(deftest project-rows-filters-out-of-window-violations
  (testing "violations outside the window do not appear on their row"
    (let [schemas    [[:auth]]
          window     {:t0 1000 :t1 2000}
          violations (mapv h/project-violation
                           [(failure-ev 1 [:auth] :skip-handler {:time 500})
                            (failure-ev 2 [:auth] :skip-handler {:time 1500})])
          rows       (h/project-rows schemas violations window nil)]
      (is (= 1 (count rows)))
      (is (= 1 (count (:violations (first rows))))
          "only the in-window violation surfaces")
      (is (= 2 (:id (first (:violations (first rows)))))))))

(deftest project-rows-flash-on-first-violation
  (testing "a row that was empty in the previous render and now has a
            violation surfaces :first? true (per spec §Reading the
            timeline at a glance)"
    (let [schemas    [[:auth]]
          window     {:t0 0 :t1 1000}
          ;; Previous render — empty.
          prev-rows  [{:schema-id [:auth] :violations [] :empty? true}]
          violations (mapv h/project-violation
                           [(failure-ev 1 [:auth] :skip-handler {:time 500})])
          rows       (h/project-rows schemas violations window prev-rows)]
      (is (= 1 (count rows)))
      (is (true? (:first? (first rows)))
          ":first? flags the empty→non-empty transition"))))

(deftest project-rows-no-flash-when-previous-non-empty
  (testing "a row that was already non-empty does NOT flash again"
    (let [schemas    [[:auth]]
          window     {:t0 0 :t1 1000}
          prev-rows  [{:schema-id [:auth]
                       :violations [{:id 0 :time 100}]
                       :empty? false}]
          violations (mapv h/project-violation
                           [(failure-ev 1 [:auth] :skip-handler {:time 500})])
          rows       (h/project-rows schemas violations window prev-rows)]
      (is (false? (:first? (first rows)))
          ":first? stays false once the row has fired"))))

(deftest project-rows-orphan-violations-surface
  (testing "a violation whose schema-id is NOT in the registered set still
            surfaces — appended after the registered rows so the user sees
            the failure (per spec §Substrate — Causa renders what the
            buffer remembers)"
    (let [schemas    [[:auth]]
          window     {:t0 0 :t1 1000}
          violations (mapv h/project-violation
                           [(failure-ev 1 :s/orphan :skip-handler {:time 500})])
          rows       (h/project-rows schemas violations window nil)]
      (is (= 2 (count rows)))
      (is (= [:auth]    (:schema-id (first rows))))
      (is (= :s/orphan  (:schema-id (second rows))))
      (is (true?  (:empty? (first rows))))
      (is (false? (:empty? (second rows)))))))

;; ---- (6) empty-state-kind ----------------------------------------------

(deftest empty-state-kind-classifies-three-branches
  (testing "no rows → :no-schemas (the schemas-not-registered pitch)"
    (is (= :no-schemas (h/empty-state-kind []))))
  (testing "every row is empty → :no-violations (the 'all schemas clean'
            positive-result message)"
    (is (= :no-violations
           (h/empty-state-kind
             [{:schema-id [:auth] :violations [] :empty? true}
              {:schema-id [:cart] :violations [] :empty? true}]))))
  (testing "at least one violation → nil (render the timeline normally)"
    (is (nil?
          (h/empty-state-kind
            [{:schema-id [:auth] :violations [{:id 1}] :empty? false}])))))

;; ---- (7) find-violation -------------------------------------------------

(deftest find-violation-by-id
  (testing "find-violation locates a row by trace-event :id"
    (let [vs (mapv h/project-violation
                   [(failure-ev 1 :s/a :skip-handler)
                    (failure-ev 2 :s/b :replaced-with-default)
                    (failure-ev 3 :s/c :re-raised)])]
      (is (= :s/b (:schema-id (h/find-violation vs 2))))
      (is (nil? (h/find-violation vs 999))
          "missing id returns nil"))))

;; ---- (8) format-tooltip -------------------------------------------------

(deftest format-tooltip-builds-one-liner
  (testing "tooltip carries path + value when both present"
    (let [t (h/format-tooltip {:path [:auth :email]
                               :value nil
                               :explain nil})]
      (is (some? t))
      (is (re-find #"\[:auth :email\]" t))
      (is (re-find #"got nil" t))))
  (testing "tooltip surfaces an :explain :message slot when present"
    (let [t (h/format-tooltip {:path [:user]
                               :value 42
                               :explain {:message "expected string"}})]
      (is (re-find #"expected string" t)))))

(deftest schema-row-label-renders-paths-and-keywords
  (testing "vector paths render as their pr-str form"
    (is (= "[:auth :email]" (h/schema-row-label [:auth :email]))))
  (testing "schema recovery fallback rows render as meaningful surfaces"
    (is (= "recovery :fx-args"
           (h/schema-row-label [:schema-recovery :fx-args]))))
  (testing "keyword schema-ids render with the leading colon"
    (is (= ":schema/user-auth" (h/schema-row-label :schema/user-auth))))
  (testing "the synthetic ::unknown-schema renders as :?:"
    (is (= ":?:" (h/schema-row-label ::h/unknown-schema)))))
