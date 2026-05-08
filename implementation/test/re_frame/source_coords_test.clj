(ns re-frame.source-coords-test
  "Per Spec 001 §Source-coordinate capture and Tool-Pair §Source-mapping:
  every reg-* registration's metadata carries :ns / :line / :file
  auto-supplied at compile time. This test registers one handler per
  registry kind via the public re-frame.core macro surface and asserts
  the resulting handler-meta carries non-nil :ns / :line / :file (rf2-k84s).

  The capture mechanism (see re-frame.source-coords) wraps each public
  reg-* macro at the re-frame.core boundary. (meta &form) supplies
  :line / :column; *ns* / *file* supply the namespace symbol and source
  filename. The macro binds re-frame.source-coords/*pending-coords*
  around the underlying registration fn, which merges the coords into
  the registry slot's metadata.

  Verification is JVM-only here because the source-coord-capture
  *macro* path is JVM-side per the current commit (CLJS source-coord
  capture rides a future re-frame.core-macros companion ns; the
  underlying merge mechanism in source-coords/merge-coords is shared).

  Coverage matches Spec 001 §Per-kind index and the bead's deliverable:
    reg-event-db   reg-event-fx   reg-event-ctx
    reg-sub        reg-fx         reg-cofx
    reg-frame      reg-view       reg-machine
    reg-flow       reg-route      reg-app-schema
    reg-error-projector"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shared assertion helper ---------------------------------------------

(defn- assert-coords [meta kind id]
  (is (some? meta) (str "handler-meta for " kind " " id " should be present"))
  (is (some? (:ns meta))
      (str "handler-meta for " kind " " id " should carry :ns"))
  (is (some? (:line meta))
      (str "handler-meta for " kind " " id " should carry :line"))
  (is (some? (:file meta))
      (str "handler-meta for " kind " " id " should carry :file"))
  ;; :ns is a symbol per Spec 001 §The metadata map.
  (is (symbol? (:ns meta))
      (str "handler-meta for " kind " " id " :ns should be a symbol"))
  ;; :line is an integer per Spec 001.
  (is (integer? (:line meta))
      (str "handler-meta for " kind " " id " :line should be an integer"))
  ;; :file is a string per Spec 001.
  (is (string? (:file meta))
      (str "handler-meta for " kind " " id " :file should be a string")))

;; ---- one assertion per reg-* kind ----------------------------------------

(deftest source-coords-on-reg-event-db
  (testing "reg-event-db stamps :ns / :line / :file"
    (rf/reg-event-db :rf2-k84s/reg-event-db-sample
                     (fn [db _] db))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-db-sample)
                   :event :rf2-k84s/reg-event-db-sample)))

(deftest source-coords-on-reg-event-fx
  (testing "reg-event-fx stamps :ns / :line / :file"
    (rf/reg-event-fx :rf2-k84s/reg-event-fx-sample
                     (fn [_ _] {}))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-fx-sample)
                   :event :rf2-k84s/reg-event-fx-sample)))

(deftest source-coords-on-reg-event-ctx
  (testing "reg-event-ctx stamps :ns / :line / :file"
    (rf/reg-event-ctx :rf2-k84s/reg-event-ctx-sample
                      (fn [ctx] ctx))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-ctx-sample)
                   :event :rf2-k84s/reg-event-ctx-sample)))

(deftest source-coords-on-reg-sub
  (testing "reg-sub stamps :ns / :line / :file"
    (rf/reg-sub :rf2-k84s/reg-sub-sample
                (fn [db _] db))
    (assert-coords (rf/handler-meta :sub :rf2-k84s/reg-sub-sample)
                   :sub :rf2-k84s/reg-sub-sample)))

(deftest source-coords-on-reg-fx
  (testing "reg-fx stamps :ns / :line / :file"
    (rf/reg-fx :rf2-k84s/reg-fx-sample
               (fn [_ _] nil))
    (assert-coords (rf/handler-meta :fx :rf2-k84s/reg-fx-sample)
                   :fx :rf2-k84s/reg-fx-sample)))

(deftest source-coords-on-reg-cofx
  (testing "reg-cofx stamps :ns / :line / :file"
    (rf/reg-cofx :rf2-k84s/reg-cofx-sample
                 (fn [ctx] ctx))
    (assert-coords (rf/handler-meta :cofx :rf2-k84s/reg-cofx-sample)
                   :cofx :rf2-k84s/reg-cofx-sample)))

(deftest source-coords-on-reg-frame
  (testing "reg-frame stamps :ns / :line / :file"
    (rf/reg-frame :rf2-k84s/reg-frame-sample {:doc "smoke"})
    (assert-coords (rf/handler-meta :frame :rf2-k84s/reg-frame-sample)
                   :frame :rf2-k84s/reg-frame-sample)))

(deftest source-coords-on-reg-view
  (testing "reg-view stamps :ns / :line / :file"
    ;; Per Spec 004 §reg-view, defn-shape with explicit id-meta override.
    ;; ^{:rf/id ...} keeps the legacy keyword for the assertion.
    (rf/reg-view ^{:rf/id :rf2-k84s/reg-view-sample} reg-view-sample []
      [:div "hi"])
    (assert-coords (rf/handler-meta :view :rf2-k84s/reg-view-sample)
                   :view :rf2-k84s/reg-view-sample)))

(deftest source-coords-on-reg-machine
  (testing "reg-machine stamps :ns / :line / :file (under :event kind, since
  reg-machine wraps the machine as an event handler per Spec 005 §Registration)"
    (rf/reg-machine :rf2-k84s/reg-machine-sample
                    {:initial :a :states {:a {} :b {}}})
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-machine-sample)
                   :event :rf2-k84s/reg-machine-sample)))

(deftest source-coords-on-reg-flow
  (testing "reg-flow stamps :ns / :line / :file"
    (rf/reg-flow {:id     :rf2-k84s/reg-flow-sample
                  :inputs [[:source]]
                  :output (fn [v] v)
                  :path   [:dest]})
    (assert-coords (rf/handler-meta :flow :rf2-k84s/reg-flow-sample)
                   :flow :rf2-k84s/reg-flow-sample)))

(deftest source-coords-on-reg-route
  (testing "reg-route stamps :ns / :line / :file"
    (rf/reg-route :rf2-k84s/reg-route-sample {:path "/k84s"})
    (assert-coords (rf/handler-meta :route :rf2-k84s/reg-route-sample)
                   :route :rf2-k84s/reg-route-sample)))

(deftest source-coords-on-reg-app-schema
  (testing "reg-app-schema stamps :ns / :line / :file"
    (rf/reg-app-schema [:rf2-k84s/reg-app-schema-sample] :int)
    (assert-coords (rf/handler-meta :app-schema [:rf2-k84s/reg-app-schema-sample])
                   :app-schema [:rf2-k84s/reg-app-schema-sample])))

(deftest source-coords-on-reg-error-projector
  (testing "reg-error-projector stamps :ns / :line / :file"
    (rf/reg-error-projector :rf2-k84s/reg-error-projector-sample
                            (fn [_]
                              {:status     500
                               :code       :internal-error
                               :message    "x"
                               :retryable? false}))
    (assert-coords (rf/handler-meta :error-projector
                                    :rf2-k84s/reg-error-projector-sample)
                   :error-projector :rf2-k84s/reg-error-projector-sample)))

;; ---- user-supplied :ns / :line / :file override auto-capture --------------

(deftest user-supplied-coords-win
  (testing "explicit :ns / :line / :file in user metadata override auto-capture"
    (rf/reg-event-db :rf2-k84s/explicit-coords
                     {:ns 'my.ns :line 42 :file "elsewhere.cljc"
                      :doc "hand-stamped coords from a code-gen pass"}
                     (fn [db _] db))
    (let [meta (rf/handler-meta :event :rf2-k84s/explicit-coords)]
      (is (= 'my.ns                  (:ns meta)))
      (is (= 42                      (:line meta)))
      (is (= "elsewhere.cljc"        (:file meta)))
      (is (= "hand-stamped coords from a code-gen pass" (:doc meta))))))

;; ---- programmatic call (bypasses macro) -----------------------------------

(deftest fn-form-call-skips-coord-capture
  (testing "calling the underlying fn directly skips coord capture
  (so programmatic / fixture-synthesised registrations don't carry
  meaningless coords from inside the framework)"
    (let [reg-fn (requiring-resolve 're-frame.subs/reg-sub)]
      (reg-fn :rf2-k84s/no-coords (fn [db _] db)))
    (let [meta (rf/handler-meta :sub :rf2-k84s/no-coords)]
      (is (some? meta))
      (is (nil? (:ns   meta)) ":ns absent on direct fn call")
      (is (nil? (:line meta)) ":line absent on direct fn call")
      (is (nil? (:file meta)) ":file absent on direct fn call"))))
