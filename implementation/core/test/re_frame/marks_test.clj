(ns re-frame.marks-test
  "Unit + integration tests for Spec 015 — Data Classification.

  Covers:
    - `reg-marks` API + replace-not-merge semantics
    - per-registration mark extraction across the 7 reg-* sites
    - emit-time trace projection (event / fx / cofx / sub)
    - sub auto-propagation + opt-out
    - schema-attached marks union with `reg-marks`"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.marks :as marks]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace-tooling/clear-trace-cbs!)
  (marks/clear-marks!)
  (marks/clear-sub-output-marks!)
  (elision/clear-warning-cache!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (trace-tooling/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- reg-marks API ------------------------------------------------------

(deftest reg-marks-writes-into-elision-registry
  (rf/reg-marks :rf/default
    {:sensitive [[:user :ssn] [:auth :token]]
     :large     [[:docs :csv-upload]]})
  (let [decls-s (elision/sensitive-declarations :rf/default)
        decls-l (elision/declarations :rf/default)]
    (is (contains? decls-s [:user :ssn]))
    (is (contains? decls-s [:auth :token]))
    (is (= :reg-marks (:source (get decls-s [:user :ssn]))))
    (is (contains? decls-l [:docs :csv-upload]))
    (is (= :reg-marks (:source (get decls-l [:docs :csv-upload]))))))

(deftest reg-marks-returns-frame-id
  (is (= :rf/default (rf/reg-marks :rf/default {:sensitive [[:x]]})))
  (rf/reg-frame :other {:doc "test"})
  (is (= :other (rf/reg-marks :other {:sensitive [[:y]]}))))

(deftest reg-marks-replaces-not-merges
  ;; Spec 015 §reg-marks: a second call against the same frame REPLACES
  (rf/reg-marks :rf/default {:sensitive [[:a] [:b]]})
  (rf/reg-marks :rf/default {:sensitive [[:c]]})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:c]))
    (is (not (contains? decls [:a])))
    (is (not (contains? decls [:b])))))

(deftest reg-marks-preserves-schema-sourced
  ;; Schema declarations have :source :schema; reg-marks must not drop them
  (rf/reg-app-schema [:auth]
                     [:map
                      [:password {:sensitive? true} :string]])
  (rf/populate-sensitive-from-schemas!)
  (rf/reg-marks :rf/default {:sensitive [[:user :ssn]]})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:user :ssn]) "reg-marks path present")
    (is (contains? decls [:auth :password]) "schema-sourced path preserved")
    (is (= :schema (:source (get decls [:auth :password]))))))

(deftest reg-marks-second-call-preserves-schema
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]])
  (rf/populate-sensitive-from-schemas!)
  (rf/reg-marks :rf/default {:sensitive [[:a]]})
  (rf/reg-marks :rf/default {:sensitive [[:b]]})
  (let [decls (elision/sensitive-declarations :rf/default)]
    (is (contains? decls [:b]))
    (is (not (contains? decls [:a])))
    (is (contains? decls [:auth :password]))))

;; ---- per-registration mark extraction -----------------------------------

(deftest reg-event-db-stashes-marks
  (rf/reg-event-db :evt
    {:sensitive [[:password]] :large [[:upload]]}
    (fn [db _] db))
  (let [m (marks/marks-for :event :evt)]
    (is (= [[:password]] (:sensitive m)))
    (is (= [[:upload]] (:large m)))))

(deftest reg-event-fx-stashes-marks
  (rf/reg-event-fx :evt
    {:sensitive [[:totp]]}
    (fn [_ _] {}))
  (is (= [[:totp]] (:sensitive (marks/marks-for :event :evt)))))

(deftest reg-event-ctx-stashes-marks
  (rf/reg-event-ctx :evt
    {:large [[:body :blob]]}
    (fn [ctx] ctx))
  (is (= [[:body :blob]] (:large (marks/marks-for :event :evt)))))

(deftest reg-sub-stashes-marks-and-overrides
  (rf/reg-sub :s1
    {:sensitive [[:hashed]] :sensitive? false}
    (fn [_ _] {:hashed "abc"}))
  (let [m (marks/marks-for :sub :s1)]
    (is (= [[:hashed]] (:sensitive m)))
    (is (false? (:sensitive? m)))))

(deftest reg-fx-stashes-marks
  (rf/reg-fx :myfx
    {:sensitive [[:body :password]] :large [[:body :upload]]}
    (fn [_ _]))
  (let [m (marks/marks-for :fx :myfx)]
    (is (= [[:body :password]] (:sensitive m)))
    (is (= [[:body :upload]] (:large m)))))

(deftest reg-cofx-stashes-marks-whole-value
  (rf/reg-cofx :auth/jwt
    {:sensitive [[]]}
    (fn [ctx] ctx))
  (is (= [[]] (:sensitive (marks/marks-for :cofx :auth/jwt)))))

;; ---- emit-time projection ----------------------------------------------

(deftest event-arg-sensitive-path-redacts-in-trace
  ;; Spec 015 conformance fixture #1
  (rf/reg-event-fx :auth/log-in
    {:sensitive [[:password]]}
    (fn [_ _] {}))
  (let [traces (collect-traces! :evt-redact)]
    (rf/dispatch-sync [:auth/log-in {:email "a@b.c" :password "secret"}])
    (let [dispatched (filter #(= :event/dispatched (:operation %)) @traces)]
      (is (seq dispatched))
      (let [ev (-> dispatched first :tags :event)]
        (is (= :rf/redacted (get-in ev [1 :password])))
        (is (= "a@b.c" (get-in ev [1 :email])))))
    (trace-tooling/remove-trace-cb! :evt-redact)))

(deftest fx-args-sensitive-path-redacts-in-trace
  (rf/reg-fx :myfx
    {:sensitive [[:headers :authorization]]}
    (fn [_ _]))
  (rf/reg-event-fx :send
    (fn [_ _] {:fx [[:myfx {:headers {:authorization "Bearer xyz"
                                      :other "ok"}}]]}))
  (let [traces (collect-traces! :fx-redact)]
    (rf/dispatch-sync [:send])
    (let [handled (filter #(= :rf.fx/handled (:operation %)) @traces)]
      (is (seq handled))
      (let [args (-> handled first :tags :fx-args)]
        (is (= :rf/redacted (get-in args [:headers :authorization])))
        (is (= "ok" (get-in args [:headers :other])))))
    (trace-tooling/remove-trace-cb! :fx-redact)))

(deftest large-path-emits-marker
  (rf/reg-event-fx :evt
    {:large [[:blob]]}
    (fn [_ _] {}))
  (let [traces (collect-traces! :large-marker)
        big    (apply str (repeat 500 "X"))]
    (rf/dispatch-sync [:evt {:blob big :other "small"}])
    (let [dispatched (filter #(= :event/dispatched (:operation %)) @traces)
          ev         (-> dispatched first :tags :event)
          slot       (get-in ev [1 :blob])]
      (is (elision/marker? slot))
      (is (= "small" (get-in ev [1 :other])))
      (is (= [:blob] (get-in slot [:rf.size/large-elided :path])))
      (is (pos-int? (get-in slot [:rf.size/large-elided :bytes]))))
    (trace-tooling/remove-trace-cb! :large-marker)))

(deftest empty-path-redacts-whole-arg
  (rf/reg-event-fx :evt
    {:sensitive [[]]}
    (fn [_ _] {}))
  (let [traces (collect-traces! :whole)]
    (rf/dispatch-sync [:evt {:a 1 :b 2}])
    (let [ev (-> (filter #(= :event/dispatched (:operation %)) @traces)
                 first :tags :event)]
      (is (= :rf/redacted (nth ev 1))))
    (trace-tooling/remove-trace-cb! :whole)))

;; ---- redact-with-paths primitive ---------------------------------------

(deftest redact-with-paths-walks-nested
  (let [v {:user {:ssn "123-45-6789" :name "Alice"}
           :auth {:token "abc" :method :jwt}}
        out (marks/redact-with-paths v [[:user :ssn] [:auth :token]] [])]
    (is (= :rf/redacted (get-in out [:user :ssn])))
    (is (= "Alice" (get-in out [:user :name])))
    (is (= :rf/redacted (get-in out [:auth :token])))
    (is (= :jwt (get-in out [:auth :method])))))

(deftest redact-with-paths-noop-when-no-paths
  (let [v {:a 1 :b 2}]
    (is (identical? v (marks/redact-with-paths v [] [])))))

(deftest redact-with-paths-empty-path-whole-value
  (is (= :rf/redacted (marks/redact-with-paths {:x 1} [[]] []))))

(deftest redact-with-paths-missing-path-noop
  (let [v {:user {:name "Alice"}}
        out (marks/redact-with-paths v [[:user :ssn] [:nope :nope]] [])]
    ;; Missing leaves are no-op per Spec 015 §What gets a sentinel
    (is (= "Alice" (get-in out [:user :name])))
    (is (nil? (get-in out [:user :ssn])))))

;; ---- sub auto-propagation ----------------------------------------------

(deftest sub-auto-propagates-when-app-db-has-sensitive-marks
  ;; Spec 015 conformance fixture #3 (variant — propagation flag set)
  ;; NB: handlers that REPLACE app-db wipe `:rf/elision`, mirroring the
  ;; existing schema-driven elision constraint — seed first, then mark.
  (rf/reg-event-db :seed (fn [_ _] {:user {:ssn "X" :name "A"}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-marks :rf/default {:sensitive [[:user :ssn]]})
  (rf/reg-sub :all-users (fn [db _] (:user db)))
  @(rf/subscribe [:all-users])
  ;; Layer-1 sub with sensitive declarations present → propagation flag set
  (is (true? (marks/sub-output-sensitive? :rf/default :all-users))))

(deftest sub-explicit-opt-out-clears-propagation
  ;; Spec 015 conformance fixture #4
  (rf/reg-event-db :seed (fn [_ _] {:user {:ssn "X" :name "A"}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-marks :rf/default {:sensitive [[:user :ssn]]})
  (rf/reg-sub :safe
    {:sensitive? false}
    (fn [db _] (get-in db [:user :name])))
  @(rf/subscribe [:safe])
  (is (false? (marks/sub-output-sensitive? :rf/default :safe))))

(deftest sub-force-marked
  ;; :sensitive? true forces even with no upstream
  (rf/reg-sub :synthetic
    {:sensitive? true}
    (fn [_ _] "public-input-derived-secret"))
  @(rf/subscribe [:synthetic])
  (is (true? (marks/sub-output-sensitive? :rf/default :synthetic))))

;; ---- composed: subs propagation through layer-2 ------------------------

(deftest layer-2-inherits-from-input-sub
  (rf/reg-event-db :seed (fn [_ _] {:user {:ssn "X" :name "A"}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-marks :rf/default {:sensitive [[:user :ssn]]})
  (rf/reg-sub :u (fn [db _] (:user db)))
  (rf/reg-sub :uname
    :<- [:u]
    (fn [u _] (:name u)))
  @(rf/subscribe [:uname])
  ;; Layer-2 picks up sensitive from layer-1 input :u
  (is (true? (marks/sub-output-sensitive? :rf/default :u)))
  (is (true? (marks/sub-output-sensitive? :rf/default :uname))))

;; ---- per-sub-path declaration --------------------------------------------

(deftest per-sub-path-declaration-via-projection
  ;; The `:sub/run` trace shape carries `{:sub-id :query-v :frame}` — not
  ;; the computed value (sub recompute keeps the value in the reaction).
  ;; Per-sub-path declarations apply downstream: wherever a tool lifts the
  ;; sub's value into an observable surface (Causa sub panel, MCP wire),
  ;; the consumer calls `marks/redact-with-paths` with the sub's declared
  ;; paths. This test exercises that contract via the public primitive.
  (rf/reg-sub :compound
    {:sensitive [[:hashed]]}
    (fn [_ _] {:hashed "secret" :public "ok"}))
  (let [m (marks/marks-for :sub :compound)
        v {:hashed "secret" :public "ok"}
        out (marks/redact-with-paths v (:sensitive m) (or (:large m) []))]
    (is (= :rf/redacted (:hashed out)))
    (is (= "ok" (:public out)))))

;; ---- cofx -------------------------------------------------------------

(deftest cofx-injected-value-redacted-in-coeffects
  (rf/reg-cofx :auth/jwt
    {:sensitive [[]]}
    (fn [ctx] (assoc-in ctx [:coeffects :auth/jwt] "real-jwt")))
  (rf/reg-event-fx :uses-jwt
    [(rf/inject-cofx :auth/jwt)]
    (fn [_ _] {}))
  ;; Cofx mark stashed
  (is (= [[]] (:sensitive (marks/marks-for :cofx :auth/jwt))))
  ;; Trace projection covers tags-with-:coeffects; verified through the
  ;; redact-with-paths primitive — the integration over a real trace
  ;; event also exercises this path.
  (let [coeffects-tag {:auth/jwt "real-jwt"}
        projected (marks/project-trace-event
                    {:operation :event/some-emit
                     :op-type :event
                     :tags {:coeffects coeffects-tag :frame :rf/default}})]
    (is (= :rf/redacted (get-in projected [:tags :coeffects :auth/jwt])))))

;; ---- registrar interaction ---------------------------------------------

(deftest re-registration-clears-prior-marks
  (rf/reg-event-fx :evt {:sensitive [[:a]]} (fn [_ _] {}))
  (is (= [[:a]] (:sensitive (marks/marks-for :event :evt))))
  ;; Re-register without marks — clears stashed entry
  (rf/reg-event-fx :evt (fn [_ _] {}))
  (is (nil? (marks/marks-for :event :evt))))

(deftest re-registration-replaces-marks
  (rf/reg-event-fx :evt {:sensitive [[:a]]} (fn [_ _] {}))
  (rf/reg-event-fx :evt {:sensitive [[:b]]} (fn [_ _] {}))
  (is (= [[:b]] (:sensitive (marks/marks-for :event :evt)))))
