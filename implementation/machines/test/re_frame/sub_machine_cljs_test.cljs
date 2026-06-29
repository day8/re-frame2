(ns re-frame.sub-machine-cljs-test
  "CLJS coverage for the `sub-machine` reactive-read sugar — a thin fn over
  `(subscribe [:rf/machine machine-id])` returning a reaction over the
  machine's snapshot map `{:state :data :tags}`.

  Concerns covered:
    - happy path: the sugar reads the post-event snapshot and is value-equal
      to the canonical `[:rf/machine id]` subscription vector form;
    - adversarial: an unspawned / unknown machine id yields a nil-snapshot
      reaction;
    - adversarial: the `{:frame f}` opts passthrough resolves the snapshot
      from the named frame, isolated from the ambient default frame.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up.
  Per Spec 005 §Subscribing to machines via the :rf/machine sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; load the optional machines artefact so its `:machines/reg-machine`
            ;; late-bind hook + the `:rf/machine` runtime-db sub are published
            ;; before the reg-machine / sub-machine calls below.
            [re-frame.machines]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private toggle-machine
  "A flat two-state toggle. `:flip` moves off↔on; the off→on edge also runs
  the `:bump` action so the snapshot's `:data` is observable through the sugar."
  {:initial :off
   :data    {:n 0}
   :actions {:bump (fn [{d :data}] {:data (update d :n inc)})}
   :states  {:off {:on {:flip {:target :on :action :bump}}}
             :on  {:on {:flip {:target :off}}}}})

(deftest sub-machine-returns-snapshot-reaction
  (testing "sub-machine reads the post-event snapshot, value-equal to the
            canonical [:rf/machine id] subscription vector"
    (rf/reg-machine :sub/toggle toggle-machine)
    (rf/dispatch-sync [:sub/toggle [:flip]])
    (let [snap @(rf/sub-machine :sub/toggle)]
      (is (= :on (:state snap)) "sugar reads the post-event :state")
      (is (= 1 (:n (:data snap))) "sugar reads the snapshot's :data")
      ;; the sugar resolves through the SAME framework sub as the vector form.
      (is (= @(rf/subscribe [:rf/machine :sub/toggle])
             @(rf/sub-machine :sub/toggle))
          "sub-machine value == [:rf/machine id] subscription value"))))

(deftest sub-machine-unspawned-id-nil-snapshot
  (testing "sub-machine yields a nil snapshot before the machine's first event
            and for an entirely unknown id (adversarial)"
    ;; registered but never dispatched → no snapshot in the runtime-db.
    (rf/reg-machine :sub/never toggle-machine)
    (is (nil? @(rf/sub-machine :sub/never))
        "no snapshot before the first event")
    ;; never even registered → also a nil-snapshot reaction, not an error.
    (is (nil? @(rf/sub-machine :sub/unknown))
        "unknown machine id → nil snapshot")))

(deftest sub-machine-frame-opts-passthrough
  (testing "the {:frame f} opts passthrough resolves the snapshot from the
            named frame, isolated from the ambient default frame (adversarial)"
    (rf/reg-frame :sub-machine/f2 {:doc "second frame for the passthrough test"})
    (rf/reg-machine :sub/scoped toggle-machine)
    ;; run the machine ONLY in :sub-machine/f2 — the default frame never sees it.
    (rf/dispatch-sync [:sub/scoped [:flip]] {:frame :sub-machine/f2})
    (is (= :on (:state @(rf/sub-machine :sub/scoped {:frame :sub-machine/f2})))
        "{:frame f2} reads the snapshot that ran in f2")
    (is (= @(rf/subscribe :sub-machine/f2 [:rf/machine :sub/scoped])
           @(rf/sub-machine :sub/scoped {:frame :sub-machine/f2}))
        "opts passthrough == the 2-arity frame-targeted vector subscription")
    (is (nil? @(rf/sub-machine :sub/scoped))
        "the ambient default frame has no snapshot — frame isolation holds")))
