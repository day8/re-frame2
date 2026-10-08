(ns re-frame.story.login-example-seed-cljs-test
  "Every variant of the login example's Story deck
   (examples/core/login/stories.cljs) seeds the login-form slice with
   `[:auth.login/initialise-form]` BEFORE its own setup, exactly once.

   Each Story variant runs in its own fresh frame with no application
   `:initial-events`, so its compiled `:setup` is the only seed of the
   slice; without it the inputs render uncontrolled and the machine runs
   against a missing `[:auth :login-form]`.

   The ids are enumerated rather than read from `rf.story/variants-of`
   because the login_form TESTBED deck registers variants under the same
   `:story.login` parent. CLJS-only: the `:node-test` classpath carries
   both `../tools/story/src` and `../examples/core`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.story.plan :as rf.story.plan]
            [login.stories :as login-stories]))

;; Re-fire the deck's registrations (idempotent) so the example's
;; `:story.login/submitting` wins over the testbed's same-id variant.
(use-fixtures :each {:before (fn [] (login-stories/register-all!))})

(def ^:private seed-step
  "The fragment's `[:auth.login/initialise-form]` setup step as a compiled
   `[:world :setup]` carries it."
  [:dispatch [:auth.login/initialise-form]])

(def ^:private example-variant-ids
  [:story.login/empty
   :story.login/filled
   :story.login/submitting
   :story.login/invalid-credentials
   :story.login/auth-error
   :story.login/locked-out
   :story.login/success])

(deftest every-login-variant-seeds-the-slice-first-and-exactly-once
  (doseq [vid example-variant-ids]
    (let [setup (get-in (rf.story.plan/variant-plan vid) [:world :setup])]
      (is (= seed-step (first setup))
          (str vid " must seed the login-form slice FIRST; got: "
               (pr-str (first setup))))
      (is (= 1 (count (filter #(= seed-step %) setup)))
          (str vid " seeds the slice exactly once")))))
