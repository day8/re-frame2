(ns re-frame.hot-reload-cljs-test
  "Spec 001 §Hot-reload semantics, rule 4: view re-registration re-renders
  mounted views. The JVM pins the other rules (`hot_reload_test.clj`). With
  no DOM, the contract is read where the substrate reads it: after a
  re-register, `re-frame.core/view` (the render cycle's lookup by id)
  returns the new render fn, and per-version render counters show which
  body ran."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- (4) :view re-register flips the next render to the new body --------

(deftest view-re-register-causes-rerender
  (testing "after re-registering a view, the next render uses the new body"
    (let [v1-renders (atom 0)
          v2-renders (atom 0)
          v1-fn (fn [n]
                  (swap! v1-renders inc)
                  [:span.v1 "v1-" n])
          v2-fn (fn [n]
                  (swap! v2-renders inc)
                  [:strong.v2 "v2-" n])]
      ;; The fn form, because the reg-view macro rejects a non-literal-fn
      ;; body (Spec 001 §Allowed forms of the middle slot); what matters
      ;; here is that the registrar's :view slot is replaced.
      (rf/reg-view* :rf.hot-reload-test/widget v1-fn)
      ;; The wrapper splices :data-rf2-source-coord into the root attrs
      ;; (Spec 006 §Source-coord annotation), so read the tag and children.
      (let [out ((rf/view :rf.hot-reload-test/widget) 7)]
        (is (= [:span.v1 ["v1-" 7] 1 0] [(first out) (drop 2 out) @v1-renders @v2-renders])
            "the first render ran v1's body, once"))
      (rf/reg-view* :rf.hot-reload-test/widget v2-fn)
      (let [out ((rf/view :rf.hot-reload-test/widget) 7)]
        (is (= [:strong.v2 ["v2-" 7] 1 1] [(first out) (drop 2 out) @v1-renders @v2-renders])
            "the post-rereg lookup ran v2's body once, and did NOT re-invoke v1")))))

(deftest view-re-register-via-macro-also-flips
  (testing "the reg-view MACRO path also installs the new render fn into
            the registry — verifying the contract holds for both surfaces"
    ;; Re-evaluating with the SAME `:rf/id` is the macro-side hot-reload
    ;; path; two local syms keep the macro's auto-`def` out of CLJS's
    ;; `:redef-in-file` lane, since the contract is the registry slot.
    ;; EP-0002: the macro injects a render-time capture-frame, which needs an
    ;; established scope, hence `with-frame`.
    (rf/make-frame {:id :rf.hot-reload-test/banner-frame :doc "render scope for the macro hot-reload test"})
    (let [observed (atom nil)]
      (reg-view ^{:rf/id :rf.hot-reload-test/banner} banner-v1 [t]
        (reset! observed [:m1 t])
        [:h1.m1 t])
      (with-frame :rf.hot-reload-test/banner-frame
        ((rf/view :rf.hot-reload-test/banner) "hello"))
      (is (= [:m1 "hello"] @observed))
      (reg-view ^{:rf/id :rf.hot-reload-test/banner} banner-v2 [t]
        (reset! observed [:m2 t])
        [:h2.m2 t])
      (with-frame :rf.hot-reload-test/banner-frame
        ((rf/view :rf.hot-reload-test/banner) "world"))
      (is (= [:m2 "world"] @observed)
          "post-rereg lookup invokes the new macro-installed body"))))
