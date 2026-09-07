(ns re-frame.routing-jvm-facade-noop-test
  "JVM / SSR no-op contract for the routing facade (rf2-j1p1fv).

  Spec 012 §URL changes are events / §URL strategies: on the server there is no
  `window` and no address bar, so the browser URL-change listener install is a
  no-op and `current-url` reads the SSR root `\"/\"`. The imperative
  `install-url-listener!` / `remove-url-listener!` facade wrappers were FOLDED
  into the `:url-bound?` frame lifecycle (rf2-g8pbwg) — a `:url-bound? true`
  frame installs its strategy listener on CREATE (CLJS) and removes it on
  DESTROY. Per rf2-h1vqa4 `:routing/on-frame-registered!` is published on BOTH
  hosts (its url-bound exclusivity / claim-order leg is host-agnostic); the
  BROWSER-LISTENER leg inside its body is CLJS-only, so on the JVM the hook
  runs the claim maintenance and skips the listener work (a graceful no-op —
  never `:rf.error/routing-artefact-missing`). `:routing/reset-url-listener!`
  stays CLJS-only.

  The query-shaped `current-url` lives in `re-frame.routing.history` and is
  no longer re-exported anywhere (rf2-wad2fl demoted it off `re-frame.core`;
  rf2-sy7zr deleted the dormant `re-frame.core-routing` wrapper and its
  `:routing/current-url` late-bind hook, which nothing consumed; rf2-kuky.36
  deleted the `re-frame.routing/current-url` alias, whose only callers were
  this file and the facade pin in `late_bind_missing_test`). The SSR contract
  is unchanged and still pinned below, now against the HOME namespace:
  `re-frame.routing.history/current-url` must be callable on the JVM and read
  the SSR root — a CLJS-only definition would break `.cljc` server-side
  rendering.

  These tests pin, with routing PRESENT (required + reloaded by the suite
  fixture, so the late-bind hooks are live on the JVM):

    1. `re-frame.routing.history/current-url` returns `\"/\"` on the JVM (no throw).
    2. registering AND destroying a `:url-bound? true` frame on the JVM does
       not throw — the browser listener install/teardown is CLJS-only, so the
       frame-lifecycle hooks are a graceful no-op server-side."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Required FOR EFFECT: this suite's whole subject is the JVM
            ;; behaviour of routing's registrations and late-bind hooks, so the
            ;; facade must be loaded even though rf2-kuky.36 left it with no
            ;; qualified call site here.
            [re-frame.routing]
            [re-frame.routing.history :as rf.routing.history]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- outcome
  "Run `thunk`, returning `:ok` on normal return or the thrown Throwable — so a
  test can assert 'did not throw' structurally rather than relying on the
  return value."
  [thunk]
  (try (thunk) :ok (catch Throwable e e)))

(deftest current-url-returns-ssr-root-on-jvm-rf2-j1p1fv
  (testing "re-frame.routing.history/current-url returns the SSR root \"/\" on the JVM
            (no window.location) without throwing — with routing present. The
            no-throw leg is asserted STRUCTURALLY (rf2-sy7zr): a CLJS-only
            definition of current-url would make every .cljc caller blow up
            server-side, which is the regression this guards"
    (is (= :ok (outcome #(rf.routing.history/current-url)))
        "current-url must not throw on the JVM")
    (is (= "/" (rf.routing.history/current-url))
        "current-url reads the SSR root on the JVM")))

(deftest url-bound-frame-lifecycle-is-a-noop-on-jvm-rf2-j1p1fv
  (testing "registering AND destroying a :url-bound? true frame on the JVM does
            not throw — the browser listener install/teardown legs inside the
            :routing/on-frame-registered! / :routing/on-frame-destroyed!
            frame-lifecycle hooks are CLJS-only and skipped server-side, while
            the host-agnostic claim maintenance still runs (Spec 012 SSR
            listener install is a no-op). A losing duplicate url-binding (the
            suite fixture already binds :rf/default) is REPORTED via a diagnostic
            but must never throw."
    (is (= :ok (outcome #(rf/make-frame {:id :zz/jvm-url-owner :url-bound? true})))
        "make-frame of a url-bound frame returns normally on the JVM — the
         listener-install leg is CLJS-only and skipped server-side")
    (is (= "/" (rf.routing.history/current-url))
        "current-url still reads the SSR root while a url-bound frame is live")
    (is (= :ok (outcome #(rf.frame/destroy-frame! :zz/jvm-url-owner)))
        "destroy-frame! of the url-bound frame returns normally on the JVM — the
         listener-teardown branch is CLJS-only and skipped server-side")))
