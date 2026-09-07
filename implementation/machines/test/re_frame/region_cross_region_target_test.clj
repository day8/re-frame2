(ns re-frame.region-cross-region-target-test
  "Per Spec 005 §Cross-region coordination — a region-local `:target` NEVER
  names a sibling region.

  A `:type :parallel` machine drives each region through a SYNTHETIC
  single-machine spec built from that region's body alone
  (`parallel/build-region-machine`), so a region-local `:target` resolves
  strictly WITHIN the declaring region — a region name is not addressable from
  inside a region. Only the parallel ROOT's own `:on` / `:after` (the ancestor
  fallback) takes region-qualified targets.

  Two registration sites therefore have to reject a region-sourced
  cross-region target, and before rf2-ovhj only the first of them did:

    - a region STATE-NODE's `:on` — already rejected (the target resolves
      against the region's own `:states` and lands nowhere), but with a
      GENERIC \"does not resolve\" message that left the author to work out
      why a target they can see in the machine map does not exist; and
    - the region BODY's own root `:on` — the region ancestor fallback — which
      `validate-transition-targets!` never walked at all. ANY target
      registered cleanly there, cross-region or plainly missing, and the
      runtime then committed the unresolved vector verbatim into the region's
      state slot: `{:a [:b :two], :b :one}` — a nonsense configuration, no
      error, no trace.

  Both now fail loud at registration with `:rf.error/machine-unresolved-target`
  (the existing taxonomy member — a well-shaped target resolving to no declared
  state is exactly what it names), and the message NAMES the sibling region and
  the two sanctioned spellings for cross-region movement.

  The controls matter as much as the rejections: a legitimate in-region target
  of the SAME SHAPE — a nested `[:b :two]` path where `:b` is a real state of
  the declaring region that happens to SHADOW a sibling region's name — must
  still register and still resolve."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- 1. a region STATE's :on cross-region target ---------------------------

(deftest region-state-cross-region-target-rejected-with-a-region-aware-message
  (testing "the rejection names the sibling region and the root ancestor fallback"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one
                           :states  {:one {:on {:go {:target [:b :two]}}}
                                     :two {}}}
                       :b {:initial :one
                           :states  {:one {} :two {}}}}}
          e (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #":rf.error/machine-unresolved-target"
                  (rf.machines/make-machine-handler m)))]
      (is (string? (ex-message e)))))

  (testing "the message says WHY — :b is a sibling REGION, not a state of :a"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one
                           :states  {:one {:on {:go {:target [:b :two]}}}
                                     :two {}}}
                       :b {:initial :one
                           :states  {:one {} :two {}}}}}]
      (try
        (rf.machines/make-machine-handler m)
        (is false "registration must reject a region-sourced cross-region target")
        (catch clojure.lang.ExceptionInfo e
          (let [msg (ex-message e)]
            (is (re-find #"SIBLING REGION" msg)
                "the message must say the head names a sibling region")
            (is (re-find #"\[:b :two\]" msg)
                "the message must NAME the offending target")
            (is (re-find #":b" msg)
                "the message must name the sibling region")
            (is (re-find #"ancestor fallback" msg)
                "the message must point at the root :on / :after ancestor fallback — what the author probably meant")
            (is (= #{:a :b} (:regions (ex-data e)))
                "ex-data carries the declared region names")))))))

;; ---- 2. the region BODY's own root :on ------------------------------------

(deftest region-root-on-cross-region-target-rejected
  (testing "a cross-region target on the REGION ROOT's :on fails registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-unresolved-target"
          (rf.machines/make-machine-handler
            {:type    :parallel
             :data    {}
             :regions {:a {:initial :one
                           :on      {:go {:target [:b :two]}}
                           :states  {:one {} :two {}}}
                       :b {:initial :one
                           :states  {:one {} :two {}}}}}))
        "the region ancestor fallback resolves within its own region")))

(deftest region-root-on-unresolved-target-rejected
  (testing "a plainly unresolved target on the REGION ROOT's :on fails registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-unresolved-target"
          (rf.machines/make-machine-handler
            {:type    :parallel
             :data    {}
             :regions {:a {:initial :one
                           :on      {:go {:target [:nowhere]}}
                           :states  {:one {} :two {}}}
                       :b {:initial :one
                           :states  {:one {} :two {}}}}}))
        "the region root :on was the unwalked slot — any target registered cleanly")))

(deftest region-root-on-bare-keyword-unresolved-target-rejected
  (testing "a bare-keyword region-root :on target that names no state is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-unresolved-target"
          (rf.machines/make-machine-handler
            {:type    :parallel
             :data    {}
             :regions {:a {:initial :one
                           :on      {:go :nowhere}
                           :states  {:one {} :two {}}}
                       :b {:initial :one
                           :states  {:one {}}}}})))))

;; ---- 3. CONTROLS — the rejection must not be too broad --------------------

(def shadowing-control
  "Region `:a` declares a real compound state whose key SHADOWS sibling region
  `:b`'s name. `[:b :two]` is a legitimate in-region absolute path here — same
  shape as the rejected target, different meaning — and must keep working."
  {:type    :parallel
   :data    {}
   :regions {:a {:initial :one
                 :states  {:one {:on {:go {:target [:b :two]}}}
                           :b   {:initial :two :states {:two {}}}}}
             :b {:initial :one
                 :states  {:one {} :two {}}}}})

(deftest in-region-target-shadowing-a-sibling-region-name-still-resolves
  (testing "registration accepts it"
    (is (fn? (rf.machines/make-machine-handler shadowing-control))
        "a real in-region path is not a cross-region target just because its head shares a region's name"))

  (testing "and it still moves the declaring region, not the sibling"
    (let [{snap :snapshot} (rf.machines/machine-transition
                             shadowing-control
                             {:state {:a :one :b :one} :data {}}
                             [:go])]
      (is (= {:a [:b :two] :b :one} (:state snap))
          "region :a walked into its own nested [:b :two]; region :b is untouched"))))

(deftest region-root-on-with-a-resolvable-target-still-registers-and-fires
  (testing "the region ancestor fallback is a supported slot — only bad targets are rejected"
    (let [m {:type    :parallel
             :data    {}
             :regions {:a {:initial :one
                           :on      {:go {:target [:two]}}
                           :states  {:one {} :two {}}}
                       :b {:initial :one
                           :states  {:one {} :two {}}}}}]
      (is (fn? (rf.machines/make-machine-handler m)))
      (let [{snap :snapshot} (rf.machines/machine-transition
                               m {:state {:a :one :b :one} :data {}} [:go])]
        ;; The region ancestor fallback stamps the VECTOR path form (`[:two]`)
        ;; rather than the bare keyword a state-node `:on` would leave — a
        ;; pre-existing normalisation detail, pinned here so the control cannot
        ;; be read as asserting the keyword form.
        (is (= [:two] (:a (:state snap)))
            "the region-root :on still fires")
        (is (= :one (:b (:state snap)))
            "and the sibling region is untouched")))))

(deftest root-region-qualified-target-is-the-sanctioned-cross-region-spelling
  (testing "the spelling the rejection message points at keeps working"
    (let [m {:type    :parallel
             :data    {}
             :on      {:go {:target [:b :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}]
      (is (fn? (rf.machines/make-machine-handler m)))
      (let [{snap :snapshot} (rf.machines/machine-transition
                               m {:state {:a :one :b :one} :data {}} [:go])]
        (is (= {:a :one :b :two} (:state snap))
            "the root :on ancestor fallback moves the named region")))))
