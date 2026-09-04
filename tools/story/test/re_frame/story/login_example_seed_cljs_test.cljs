(ns re-frame.story.login-example-seed-cljs-test
  "Regression: every variant of the login example's Story deck
   (examples/core/login/stories.cljs) must seed the login-form slice
   BEFORE its own variant-specific setup runs (rf2-3fc89f.28).

   The live app boots the slice via `:auth.login/initialise-form` (the
   single source of the form defaults). Each Story variant, however, runs
   in its OWN fresh `:preset :story` frame with no application
   `:initial-events`, so the variant's compiled `:setup` program is the
   only application-state seed. Before the fix, four variants (`empty`,
   `filled`, `auth-error`, `locked-out`) never seeded the slice at all, so
   their inputs rendered uncontrolled (nil `:value`) and machine
   transitions ran against a missing `[:auth :login-form]` slice; the
   `submitting` / `success` variants seeded only as a side effect of the
   `:login.story/submit` driver, and `invalid-credentials` carried a
   one-off inline seed.

   The fix registers ONE `:fragment.login/form-base` fragment whose
   `:setup` is `[[:auth.login/initialise-form]]` and composes it into every
   variant. Because the plan compiler appends a composed fragment's `:setup`
   BEFORE the variant's own (spec/017 §`:compose`; re-frame.story.compose-test),
   the compiled `[:world :setup]` of EVERY variant must LEAD with
   `[:auth.login/initialise-form]`. This test compiles each registered
   variant plan (a pure data → data compile, no host) and asserts exactly
   that — so it goes RED if the shared initializer is absent from any
   variant OR ordered after that variant's own setup.

   Scope note: the ids are enumerated explicitly rather than read from
   `rf.story/variants-of :story.login`, because the `login_form` TESTBED deck
   (tools/story/testbeds/login_form) also registers variants under the same
   `:story.login` parent — this test guards only the login EXAMPLE's seven.

   Lives in the Story test tree (not under examples/, which is test-free
   per rf2-8cevm; and not under implementation/, which must not `:require`
   from tools/). It is CLJS-only, so the JVM `clojure -M:test` story gate
   ignores it; it runs under the consolidated `npm run test:cljs` node-test
   build, whose classpath carries both `../tools/story/src` and
   `../examples/core`."
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.plan :as rf.story.plan]
            ;; login.stories transitively requires login.core; require it
            ;; too so this ns is self-sufficient (mirrors the nine-states +
            ;; example-login-form-slice regression namespaces).
            [login.core]
            [login.stories :as login-stories]))

;; Re-fire the login deck's registrations before each test so the variant
;; + fragment side-table entries are the EXAMPLE's (register-all! is
;; idempotent). This also re-asserts the example's `:story.login/submitting`
;; over the login_form testbed's same-id variant. Compiling a plan is pure —
;; no adapter / frame / DOM needed.
(use-fixtures :each {:before (fn [] (login-stories/register-all!))})

(def ^:private raw-seed-step
  "The fragment's authored `:setup` step — a bare event vector naming the
   example's own `:auth.login/initialise-form` (the single source of the
   login-form defaults, the event the live app runs at boot)."
  [:auth.login/initialise-form])

(def ^:private compiled-seed-step
  "The same step as it appears in a compiled `[:world :setup]`: the plan
   compiler coerces every bare event-vector setup step to a tagged
   `[:dispatch <event-vec>]` (re-frame.story.play.runner/coerce-script)."
  [:dispatch raw-seed-step])

(def ^:private example-variant-ids
  "The login EXAMPLE's seven canonical variants (examples/core/login/stories.cljs)."
  [:story.login/empty
   :story.login/filled
   :story.login/submitting
   :story.login/invalid-credentials
   :story.login/auth-error
   :story.login/locked-out
   :story.login/success])

(deftest fragment-is-registered-with-the-single-seed-event
  (testing ":fragment.login/form-base seeds the slice via the example's own
            initialise-form event (no duplicated defaults map in Story)"
    (let [frag (rf.story/handler-meta :fragment :fragment.login/form-base)]
      (is (some? frag) ":fragment.login/form-base is registered")
      (is (= [raw-seed-step] (:setup frag))
          "the fragment's :setup is exactly the initialise-form event — the
           event stays the single source of defaults"))))

(deftest every-login-variant-seeds-the-slice-before-its-own-setup
  (testing "every login-example variant's compiled plan LEADS its setup with
            [:auth.login/initialise-form] (composed fragment first, variant
            setup after) — RED if a variant omits the seed or orders it late"
    (doseq [vid example-variant-ids]
      (is (some? (rf.story/handler-meta :variant vid))
          (str vid " is registered"))
      (let [setup (get-in (rf.story.plan/variant-plan vid) [:world :setup])]
        (is (= compiled-seed-step (first setup))
            (str vid " must seed the login-form slice FIRST; got: "
                 (pr-str (first setup))))))))

(deftest the-seed-appears-exactly-once-per-variant
  (testing "no per-variant duplication: after composing the fragment, each
            variant's compiled setup runs the seed event exactly ONCE (the
            redundant inline / driver seeds were removed)"
    (doseq [vid example-variant-ids]
      (let [setup (get-in (rf.story.plan/variant-plan vid) [:world :setup])
            seeds (filter #(= compiled-seed-step %) setup)]
        (is (= 1 (count seeds))
            (str vid " seeds the slice exactly once (via the composed "
                 "fragment) — no duplicated initialise-form in the setup; got "
                 (count seeds)))))))
