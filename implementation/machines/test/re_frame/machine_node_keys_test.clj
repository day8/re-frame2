(ns re-frame.machine-node-keys-test
  "rf2-0gtjde — no-silent-swallow on machine state-node / spawn-spec keys, and
  the `:tags` shape.

  From the 2026-07-03 self-consistency design review (finding 2): unknown BARE
  keys on ordinary state nodes and on spawn specs were silently IGNORED — an
  XState-trained author writes `:invoke` (XState's `:spawn`) or `:on-entry` (its
  `:entry`), registration succeeds, and the child silently never spawns / the
  action never runs. Inconsistent with siblings: `:type :history` / `:type
  :choice` / `:schemas` nodes already hard-reject unknown keys. And `:tags`
  silently COERCED a vector / single keyword to a set while its sibling
  `:internal-events` hard-rejects exactly that non-set shape.

  This suite pins the fail-loud behaviour:
    - an unknown BARE key on a state node → `:rf.error/machine-unknown-node-key`;
    - an unknown BARE key on a `:spawn` / `:spawn-all` child spec →
      `:rf.error/machine-unknown-spawn-key`;
    - a non-set `:tags` slot → `:rf.error/machine-bad-tags`;
    - a NAMESPACED user key passes (the open extension carve-out);
    - a valid machine still registers cleanly, and a valid set-form `:tags`
      still round-trips through the runtime tag projection.

  A NEW file (not sharing a namespace with any sibling fixture) per the
  rf2-0gtjde worker-lane split."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load the machines facade so `rf/reg-machine` routes through its
            ;; late-bind hook (`:machines/reg-machine`).
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

(defn- reg-error-id
  "Register `machine` under a fresh id, returning the thrown
  `:rf.error/id` (or nil when registration succeeds)."
  [machine]
  (try (rf/reg-machine (keyword "nk" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; ---- (1) unknown BARE state-node keys signal ------------------------------

(deftest unknown-bare-node-key-rejected
  (testing "an unknown BARE key on an ordinary state node fails loud — the
            XState :invoke footgun (registration used to succeed silently)"
    (is (= :rf.error/machine-unknown-node-key
           (reg-error-id {:initial :idle
                          :states {:idle {:invoke {:machine-id :child}
                                          :on {:go :done}}
                                   :done {}}}))
        ":invoke (XState's spelling of :spawn) is a bare typo — rejected")
    (is (= :rf.error/machine-unknown-node-key
           (reg-error-id {:initial :idle
                          :states {:idle {:on-entry :log
                                          :on {:go :done}}
                                   :done {}}}))
        ":on-entry (XState's spelling of :entry) is a bare typo — rejected")))

(deftest unknown-bare-node-key-on-root-rejected
  (testing "an unknown BARE key on the MACHINE ROOT is rejected (a top-level
            typo like :innitial must not slip through)"
    (is (= :rf.error/machine-unknown-node-key
           (reg-error-id {:innitial :idle          ;; typo of :initial
                          :initial :idle
                          :states {:idle {}}})))))

(deftest unknown-bare-node-key-name-and-vocab-in-ex-data
  (testing "the ex-data names the offending key + the valid vocabulary — the
            diagnostic an author reads to fix the typo"
    (let [e (try (rf/reg-machine :nk/diag
                   {:initial :idle
                    :states {:idle {:invoke :x :on {:go :done}}
                             :done {}}})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))
          d (ex-data e)]
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id d)))
      (is (= [:invoke] (:offending-keys d)) "names the offending bare key")
      (is (contains? (:valid-keys d) :spawn)
          "surfaces the valid vocabulary (which includes the intended :spawn)")
      (is (= :idle (:state d)) "names the declaring state"))))

(deftest namespaced-node-key-passes
  (testing "a NAMESPACED user key on a state node passes — the open extension
            carve-out (only BARE unknown keys are typos)"
    (is (nil? (reg-error-id {:initial :idle
                             :states {:idle {:my.app/note "annotation"
                                             :on {:go :done}}
                                      :done {}}})))))

;; ---- (2) unknown BARE spawn-spec keys signal ------------------------------

(deftest unknown-bare-spawn-key-rejected
  (testing "an unknown BARE key on a :spawn spec fails loud — a misspelt
            :machine (for :machine-id) would leave the spawn under-specified"
    (is (= :rf.error/machine-unknown-spawn-key
           (reg-error-id {:initial :idle
                          :states {:idle {:spawn {:machine :child}  ;; :machine, not :machine-id
                                          :on {:go :done}}
                                   :done {}}})))))

(deftest unknown-bare-spawn-all-child-key-rejected
  (testing "an unknown BARE key on a :spawn-all CHILD spec fails loud"
    (is (= :rf.error/machine-unknown-spawn-key
           (reg-error-id
             {:initial :idle
              :states {:idle {:spawn-all {:children [{:id :c1
                                                      :machine-id :child
                                                      :bogus true}]  ;; unknown bare key
                                          :on-child-done :cd
                                          :on-child-error :ce
                                          :on-all-complete [:all]}
                              :on {:go :done}}
                       :done {}}})))))

(deftest namespaced-spawn-key-passes
  (testing "a NAMESPACED key on a :spawn spec passes (the runtime itself stamps
            :rf/parent-id / :rf/invoke-id — namespaced, always allowed)"
    (is (nil? (reg-error-id {:initial :idle
                             :states {:idle {:spawn {:machine-id :child
                                                     :my.app/tag :x}
                                             :on {:go :done}}
                                      :done {}}})))))

;; ---- (3) malformed :tags shape signals ------------------------------------

(deftest vector-tags-rejected
  (testing "a VECTOR :tags (the shape the runtime USED to silently coerce) now
            fails loud — mirroring :internal-events' set-form rejection"
    (is (= :rf.error/machine-bad-tags
           (reg-error-id {:initial :idle
                          :states {:idle {:tags [:busy]  ;; vector, not #{}
                                          :on {:go :done}}
                                   :done {}}})))))

(deftest single-keyword-tags-rejected
  (testing "a SINGLE-KEYWORD :tags (also formerly coerced) fails loud"
    (is (= :rf.error/machine-bad-tags
           (reg-error-id {:initial :idle
                          :states {:idle {:tags :busy   ;; keyword, not #{}
                                          :on {:go :done}}
                                   :done {}}})))))

(deftest non-keyword-tags-member-rejected
  (testing "a SET with a non-keyword member fails loud (strict [:set :keyword])"
    (is (= :rf.error/machine-bad-tags
           (reg-error-id {:initial :idle
                          :states {:idle {:tags #{:busy "idle"}
                                          :on {:go :done}}
                                   :done {}}})))))

(deftest bad-tags-names-offender-in-ex-data
  (testing "the :rf.error/machine-bad-tags ex-data names the state + offending value"
    (let [e (try (rf/reg-machine :nk/bad-tags
                   {:initial :idle
                    :states {:idle {:tags [:busy] :on {:go :done}}
                             :done {}}})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))
          d (ex-data e)]
      (is (= :rf.error/machine-bad-tags (:rf.error/id d)))
      (is (= :idle (:state d)))
      (is (= [:busy] (:tags d)) "names the offending non-set value"))))

;; ---- (4) valid machines still register (no false positives) ---------------

(deftest valid-machine-with-set-tags-registers-and-projects
  (testing "a valid machine with a set-form :tags registers cleanly AND the
            runtime still projects the tag union onto the snapshot"
    (is (nil? (reg-error-id {:initial :busy
                             :states {:busy {:tags #{:loading :network}
                                             :on {:done :idle}}
                                      :idle {}}})))
    ;; End-to-end: the projected :tags survive to the settled snapshot.
    (rf/reg-machine :nk/tagged
      {:initial :busy
       :states {:busy {:tags #{:loading :network}
                       :on {:done :idle}}
                :idle {}}})
    (rf/dispatch-sync [:nk/tagged [:rf.machine/start]])
    (is (= #{:loading :network} (:tags (snapshot :nk/tagged)))
        "the set-form :tags project onto the snapshot at :busy")))

(deftest valid-full-featured-machine-registers
  (testing "a machine exercising many KNOWN state-node + spawn keys registers
            cleanly — the known-key vocabulary is complete enough for real specs"
    (is (nil? (reg-error-id
                {:initial :idle
                 :data {:n 0}
                 :guards {:ok? (fn [_] true)}
                 :actions {:log (fn [_] {})}
                 :on {:reset :idle}
                 :states {:idle {:entry :log
                                 :tags #{:waiting}
                                 :on {:go {:target :running :guard :ok?}}}
                          :running {:spawn {:machine-id :worker
                                            :on-done (fn [{:keys [data]}] data)}
                                    :after {1000 {:target :idle}}
                                    :on {:stop :idle}}}})))))

(deftest valid-choice-and-history-nodes-not-double-rejected
  (testing "a :type :choice node and a :type :history node — which carry keys
            OUTSIDE the ordinary vocabulary but are validated by their OWN
            closed-key-set validators — are NOT rejected by the node-key walk"
    ;; :type :choice carries :choice (not an ordinary-node key) — must pass here.
    (is (nil? (reg-error-id
                {:initial :route
                 :states {:route {:type :choice
                                  :choice [{:guard (constantly true) :target :a}
                                           {:target :b}]}
                          :a {} :b {}}})))
    ;; :type :history carries :deep? / :default-target — must pass here.
    (is (nil? (reg-error-id
                {:initial :outer
                 :states {:outer {:initial :one
                                  :states {:one {:on {:go :two}}
                                           :two {}
                                           :hist {:type :history :deep? true
                                                  :default-target :one}}}}})))))
