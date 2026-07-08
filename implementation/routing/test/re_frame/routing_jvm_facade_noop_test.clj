(ns re-frame.routing-jvm-facade-noop-test
  "JVM / SSR no-op contract for the routing facade (rf2-j1p1fv).

  Spec 012 §URL changes are events / §URL strategies: on the server there is no
  `window` and no address bar, so the browser URL-change listener install is a
  no-op and `current-url` reads the SSR root `\"/\"`. The imperative
  `install-url-listener!` / `remove-url-listener!` facade wrappers were FOLDED
  into the `:url-bound?` frame lifecycle (rf2-g8pbwg) — a `:url-bound? true`
  frame installs its strategy listener on CREATE (CLJS) and removes it on
  DESTROY, and the cross-feature hooks the core frame lifecycle consults
  (`:routing/on-frame-registered!`, `:routing/reset-url-listener!`) are
  published CLJS-only, so on the JVM `re-frame.frame`'s `fire-frame-registered-
  hook!` finds no hook and skips (a graceful `when-let` no-op — never
  `:rf.error/routing-artefact-missing`). The one remaining query-shaped export,
  `current-url`, is published on BOTH hosts and returns `\"/\"` server-side.

  These tests pin, with routing PRESENT (required + reloaded by the suite
  fixture, so the late-bind hooks are live on the JVM):

    1. `re-frame.routing/current-url` returns `\"/\"` on the JVM (no throw).
    2. the `re-frame.core-routing/current-url` FACADE wrapper (`:on-absent
       :throw`) returns `\"/\"` — it does NOT raise
       `:rf.error/routing-artefact-missing`, because routing publishes the
       `:routing/current-url` hook on the JVM too (a CLJS-only publication would
       make this facade throw server-side even with routing loaded — the
       regression this test guards).
    3. registering AND destroying a `:url-bound? true` frame on the JVM does
       not throw — the browser listener install/teardown is CLJS-only, so the
       frame-lifecycle hooks are a graceful no-op server-side."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.core-routing :as core-routing]
            [re-frame.frame :as frame]
            [re-frame.routing :as routing]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(defn- outcome
  "Run `thunk`, returning `:ok` on normal return or the thrown Throwable — so a
  test can assert 'did not throw' structurally rather than relying on the
  return value."
  [thunk]
  (try (thunk) :ok (catch Throwable e e)))

(deftest current-url-returns-ssr-root-on-jvm-rf2-j1p1fv
  (testing "re-frame.routing/current-url returns the SSR root \"/\" on the JVM
            (no window.location) without throwing — with routing present"
    (is (= "/" (routing/current-url))
        "current-url reads the SSR root on the JVM")))

(deftest core-routing-facade-current-url-does-not-throw-on-jvm-rf2-j1p1fv
  (testing "the re-frame.core-routing/current-url FACADE wrapper (:on-absent
            :throw) delegates to the JVM-published :routing/current-url hook and
            returns \"/\" — it must NOT raise :rf.error/routing-artefact-missing
            when routing IS present, so the .cljc/SSR no-op contract holds
            (a CLJS-only hook publication would make this throw server-side)"
    (is (= :ok (outcome #(core-routing/current-url)))
        "the facade current-url must not throw on the JVM with routing present")
    (is (= "/" (core-routing/current-url))
        "the facade delegates to the JVM-published hook and returns the SSR root")))

(deftest url-bound-frame-lifecycle-is-a-noop-on-jvm-rf2-j1p1fv
  (testing "registering AND destroying a :url-bound? true frame on the JVM does
            not throw — the browser listener install/teardown is CLJS-only, so
            the :routing/on-frame-registered! / :routing/on-frame-destroyed!
            frame-lifecycle hooks are a graceful no-op server-side (Spec 012 SSR
            listener install is a no-op). A losing duplicate url-binding (the
            suite fixture already binds :rf/default) is REPORTED via a diagnostic
            but must never throw."
    (is (= :ok (outcome #(rf/reg-frame :zz/jvm-url-owner {:url-bound? true})))
        "reg-frame of a url-bound frame returns normally on the JVM — the
         listener-install lifecycle hook is unpublished server-side and skipped")
    (is (= "/" (routing/current-url))
        "current-url still reads the SSR root while a url-bound frame is live")
    (is (= :ok (outcome #(frame/destroy-frame! :zz/jvm-url-owner)))
        "destroy-frame! of the url-bound frame returns normally on the JVM — the
         listener-teardown branch is CLJS-only and skipped server-side")))
