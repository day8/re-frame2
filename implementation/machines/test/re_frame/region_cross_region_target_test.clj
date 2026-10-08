(ns re-frame.region-cross-region-target-test
  "Per Spec 005 §Cross-region coordination: a region-local `:target` resolves
  within its own region, so one naming a sibling region is refused at
  registration with `:rf.error/machine-unresolved-target` and a message naming
  the sibling region and the sanctioned spellings, while an in-region path
  that merely shadows a sibling's name registers. Also pins the spec's
  sanctioned spellings (a) and (b) and the limit that separates them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]))

;; ---- registration ----------------------------------------------------------

(deftest region-state-cross-region-target-rejected-with-a-region-aware-message
  (try
    (rf.machines/make-machine-handler
      {:type    :parallel
       :regions {:a {:initial :one
                     :states  {:one {:on {:go {:target [:b :two]}}}
                               :two {}}}
                 :b {:initial :one
                     :states  {:one {} :two {}}}}})
    (is false "registration must reject a region-sourced cross-region target")
    (catch clojure.lang.ExceptionInfo e
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data e))))
      (is (every? #(str/includes? (ex-message e) %) ["[:b :two]" "SIBLING REGION" "ancestor fallback"])
          "the message names the target, the sibling region and the ancestor-fallback spelling"))))

(deftest region-root-on-bad-targets-rejected
  ;; Unchecked, the region root :on would commit [:b :two] verbatim as :a's state.
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":rf.error/machine-unresolved-target"
        (rf.machines/make-machine-handler
          {:type    :parallel
           :regions {:a {:initial :one
                         :on      {:go {:target [:b :two]}}
                         :states  {:one {} :two {}}}
                     :b {:initial :one
                         :states  {:one {} :two {}}}}}))))

(deftest in-region-target-shadowing-a-sibling-region-name-still-resolves
  (is (fn? (rf.machines/make-machine-handler
             {:type    :parallel
              :regions {:a {:initial :one
                            :states  {:one {:on {:go {:target [:b :two]}}}
                                      :b   {:initial :two :states {:two {}}}}}
                        :b {:initial :one
                            :states  {:one {} :two {}}}}}))
      "a real in-region path is not a cross-region target because its head shares a region's name"))

;; ---- the sanctioned spellings (a) and (b) ----------------------------------

(def ^:private wizard-helper
  "Spelling (a): the helper region's own root :on, guarded on the wizard's
  frozen pre-event state (`:step2`, while the same event moves it to `:step3`)."
  {:type    :parallel
   :guards  {:wizard-at-step2 (fn [{:keys [all-state]}] (= :step2 (:wizard all-state)))}
   :regions {:wizard {:initial :step2
                      :states  {:step2 {:on {:help {:target :step3}}}
                                :step3 {}}}
             :helper {:initial :closed
                      :on      {:help {:target :hint :guard :wizard-at-step2}}
                      :states  {:closed {} :hint {}}}}})

(defn- help-from-step2
  [spec]
  (:state (:snapshot (rf.machines/machine-transition
                       spec {:state {:wizard :step2 :helper :closed} :data {}} [:help]))))

(deftest guarded-target-region-transition-is-a-sanctioned-cross-region-spelling
  (is (= {:wizard :step3 :helper :hint} (help-from-step2 wizard-helper))))

(deftest targetless-handler-on-the-target-region-suppresses-the-guarded-rewrite
  ;; The leaf handler wins the leaf->root walk, so the region-root :on is never consulted.
  (is (= {:wizard :step3 :helper :closed}
         (help-from-step2 (assoc-in wizard-helper [:regions :helper :states :closed :on] {:help {}})))))

(deftest source-owned-raise-reaches-the-sibling-that-suppression-blocks
  (is (= {:wizard :step3 :helper :hint}
         (help-from-step2
           {:type    :parallel
            :actions {:ask-for-hint (fn [{:keys [data]}]
                                      {:data data :fx [[:raise [:helper/show-hint]]]})}
            :regions {:wizard {:initial :step2
                               :states  {:step2 {:on {:help {:target :step3 :action :ask-for-hint}}}
                                         :step3 {}}}
                      :helper {:initial :closed
                               :states  {:closed {:on {:help             {}
                                                       :helper/show-hint {:target :hint}}}
                                         :hint   {}}}}}))))
