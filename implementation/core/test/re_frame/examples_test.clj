(ns re-frame.examples-test
  "Integration tests against the example apps in ../examples/. Each test
  exercises the full event → state → render pipeline as a real user would
  wire it, catching API ergonomics regressions that pure unit tests miss.

  Per rf2-kx74 examples are grouped per substrate; the example sources
  (`ssr.core`, `ssr-streaming.core`, `state-machine-walkthrough.core`)
  live under
  ../examples/reagent/{ssr,ssr_streaming,state_machine_walkthrough}/ on
  disk. The example source a learner reads is pure demonstrative code —
  the example tree is test-free by convention (rf2-8cevm: no test/ or
  *.spec.cjs under examples/).

  rf2-cd2zo folded each example's former sibling test ns (`ssr.core-test`,
  `ssr-streaming.core-test`, `state-machine-walkthrough.core-test`) INLINE
  here as the `deftest` bodies, retiring the cross-`examples/` requires +
  the `examples/.../test/` source dirs. Each test re-`require`s only the
  production example source (so its ns-load registrations fire against the
  reset registrar) and exercises it directly."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines.result :as result]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.ssr :as ssr]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; clear-all! also drops the framework-shipped fxs that register at
  ;; namespace load time (e.g. :rf.http/managed and its canned-stub
  ;; siblings). Reload the relevant ns so the toplevel reg-fx forms run
  ;; again and the framework substrate is back in place — the examples
  ;; routinely route :rf.http/managed via :fx-overrides to those stubs
  ;; (Spec 014 §Testing).
  (require 're-frame.http-managed :reload)
  ;; rf2-cdmle — the canned-stub fxs (`:rf.http/managed-canned-success`,
  ;; `:rf.http/managed-canned-failure`) register from
  ;; re-frame.http-test-support, NOT re-frame.http-managed. Reload to
  ;; re-fire the registration body after clear-all!. The transitive
  ;; require from each example ns wouldn't re-evaluate the body (Clojure
  ;; require is idempotent without :reload-all), so reload here.
  (require 're-frame.http-test-support :reload)
  ;; clear-all! also drops the framework-shipped `:rf/machine` /
  ;; `:rf/machine-has-tag?` subs, which register at re-frame.machines load
  ;; time (see re-frame.machines §framework-shipped subs). The state-machine-
  ;; walkthrough example's `:auth.login/state` / `:auth.login/error` named
  ;; subs CHAIN off `:rf/machine` (`:<- [:rf/machine :auth.login/flow]`), so
  ;; their input sub must be present in the registrar. The transitive require
  ;; from the example ns wouldn't re-fire re-frame.machines' toplevel reg-sub
  ;; forms once the ns is already loaded (Clojure require is idempotent), so
  ;; reload here — exactly as the machines ns header anticipates. Without
  ;; this, whether the smw drain test is green depends on whether some
  ;; earlier-loaded ns happened to (re-)register `:rf/machine` since the last
  ;; clear-all! — an ordering-dependent flake.
  (require 're-frame.machines :reload)
  ;; Drop any cached require of the example namespaces so each test
  ;; re-evaluates their namespace-level handlers against a fresh registrar.
  (remove-ns 'ssr.core)
  (remove-ns 'ssr-streaming.core)
  (remove-ns 'state-machine-walkthrough.core)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ============================================================================
;; ssr — exercises the server flow (per-request frame → :rf/server-init →
;; managed-HTTP via the canned stub → render to string → render-hash).
;; JVM-only: the server render path runs under Clojure. Formerly the
;; `ssr.core-test/ssr-tests` fixture (rf2-ee38b.25); folded inline by
;; rf2-cd2zo.
;; ============================================================================

(deftest ssr-example-runs-end-to-end
  (testing "examples/reagent/ssr — the server flow renders the loaded articles"
    (require 'ssr.core :reload)
    ;; Boot the runtime (idempotent) — installs the SSR adapter and the
    ;; :rf/default frame. `re-frame.ssr` exports its own `adapter` var
    ;; (the JVM-side counterpart of reagent/uix/helix adapters); pass it
    ;; explicitly.
    (rf/init! ssr/adapter)
    ;; Stub `:rf.http/managed` so the test doesn't make real network
    ;; calls. The per-frame `:fx-overrides` redirect `:rf.http/managed`
    ;; to a per-test stub that delegates to the framework-shipped
    ;; `:rf.http/managed-canned-success` (Spec 014 §Testing) with a
    ;; canned `:value` payload — the same reply shape a live request
    ;; would produce.
    (rf/reg-fx :ssr.http/canned-articles
      {:platforms #{:server :client}}
      (fn [frame-ctx args-map]
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx
                (assoc args-map
                       :value [{:id "a" :title "Article A" :body "Body A"}
                               {:id "b" :title "Article B" :body "Body B"}])))))
    (let [fid          (keyword "rf.frame" (str (gensym "")))
          _            (ssr/set-request! fid {:uri "/articles"})
          f            (rf/reg-frame fid
                         {:doc          "ssr-example test frame"
                          :platform     :server
                          :on-create    [:rf/server-init]
                          :fx-overrides {:rf.http/managed :ssr.http/canned-articles}})
          final-db     (rf/app-db-value f)
          ;; The root view's body invokes the articles-page render fn,
          ;; which calls (rf/subscribe-once [:articles]). Both run
          ;; INSIDE render-to-string's tree walk; with-frame binds
          ;; *current-frame* across that walk so the sub reads from f
          ;; and not from :rf/default.
          hiccup      ((rf/view :app/root))
          html        (rf/with-frame f
                        (rf/render-to-string hiccup {:emit-hash? true}))
          render-hash (rf/render-tree-hash hiccup)]
      ;; State was loaded.
      (is (= 2 (count (:articles final-db))))
      ;; HTML contains the article titles.
      (is (clojure.string/includes? html "Article A"))
      (is (clojure.string/includes? html "Article B"))
      ;; HTML round-trips via render-to-string without needing React/JSDOM.
      (is (clojure.string/includes? html "<h1>"))
      ;; render-hash is a structural marker (lowercase-hex FNV-1a per
      ;; Spec 011); the client recomputes it and the runtime emits a
      ;; :rf.ssr/hydration-mismatch trace event on disagreement.
      (is (re-matches #"[0-9a-f]{8}" render-hash))
      (is (clojure.string/includes? html "data-rf-render-hash")))))

;; ============================================================================
;; ssr_streaming — exercises the server stream (shell render → per-card
;; resolved chunks → final payload). JVM-only. Formerly the
;; `ssr-streaming.core-test/streaming-tests` fixture; folded inline.
;; ============================================================================

(deftest ssr-streaming-example-runs-end-to-end
  (testing "examples/reagent/ssr_streaming — the server stream produces shell + chunks + payload"
    (require 'ssr-streaming.core :reload)
    (rf/init! ssr/adapter)
    (let [handle-request (resolve 'ssr-streaming.core/handle-request)
          result         (handle-request {:uri "/dashboard"})]
      ;; Shell carries the static header content + four template fallbacks.
      (is (clojure.string/includes? (:shell result) "<h1>Dashboard</h1>"))
      (is (clojure.string/includes? (:shell result) "data-rf2-suspense-fallback=\"1\""))
      (is (= 4 (count (:resolved-chunks result)))
          "four boundaries → four resolved chunks")
      ;; Three cards rendered successfully; one (flaky) ships the
      ;; fallback HTML with data-rf2-suspense-failed.
      (let [failed-chunks (filter :failed? (:resolved-chunks result))]
        (is (= 1 (count failed-chunks))
            "one boundary (flaky) ships the failed template")
        (is (clojure.string/includes? (:template (first failed-chunks))
                                       "data-rf2-suspense-failed=\"1\"")))
      ;; Successful cards carry the rendered card body.
      (let [ok-chunks (remove :failed? (:resolved-chunks result))]
        (is (= 3 (count ok-chunks)))
        (doseq [c ok-chunks]
          (is (clojure.string/includes? (:template c)
                                         "data-rf2-suspense-resolved=\"1\""))))
      ;; Final payload carries the canonical :rf/* keys.
      (is (= 1 (:rf/version (:final-payload result))))
      (is (some? (:rf/render-hash (:final-payload result))))
      (is (= 3 (count (:cards (:rf/app-db (:final-payload result)))))
          "three cards' state in the final payload (revenue, signups, latency); the flaky card has no app-db slice because it threw before its data fetched"))))

;; ============================================================================
;; state-machine-walkthrough — chapter §Headless testing. Two flavours:
;; pure machine-transition (no frame/app-db) and drain-level (frame +
;; :fx-overrides canned stub). JVM-runnable. Formerly the
;; `state-machine-walkthrough.core-test/smoke-tests` fixture; folded inline.
;; The `:auth.login/canned-success` / `:auth.login/canned-failure` stubs the
;; drain tests use are registered in `state-machine-walkthrough.core` so the
;; browser demo and the tests share one registration point.
;; ============================================================================

(deftest state-machine-walkthrough-runs-headless
  (require 'state-machine-walkthrough.core :reload)
  (let [login-flow @(resolve 'state-machine-walkthrough.core/login-flow)]
    (testing "pure happy path — the transition table drives :idle → :submitting → :authed"
      ;; Drives the transition table directly via machine-transition. No
      ;; frame, no app-db.
      (let [s0 {:state :idle :data {:attempts 0 :error nil}}
            {s1 ::result/snap fx1 ::result/fx}
            (rf/machine-transition login-flow s0
                                   [:auth.login/submit
                                    {:email "a@b.com" :password "secret"}])]
        (is (= :submitting (:state s1)))
        ;; Entering :submitting fires the :issue-request action's :fx.
        (is (= 1 (count fx1)) "one :rf.http/managed fx")
        (is (= :rf.http/managed (ffirst fx1)))
        (let [{s2 ::result/snap} (rf/machine-transition login-flow s1
                                                        [:auth.login/success {:value {:token "t"}}])]
          (is (= :authed (:state s2))))))

    (testing "pure lockout — at the retry limit the second :failure clause's :locked-out target wins"
      ;; Once :data :attempts reaches the retry limit, the
      ;; :under-retry-limit guard fails and the :locked-out target wins.
      ;; The guard checks the snapshot BEFORE the action runs;
      ;; :record-error then bumps the counter on hits, so attempts=3 is
      ;; the first counter value at which the guard rejects.
      (let [snapshot {:state :submitting :data {:attempts 3 :error nil}}
            {s ::result/snap}
            (rf/machine-transition login-flow snapshot
                                   [:auth.login/failure
                                    {:failure {:kind :rf.http/http-4xx
                                               :message "bad creds"}}])]
        (is (= :locked-out (:state s)) "expected :locked-out at attempts=3")))

    (testing "drain happy path — full drain lands the app-db at :authed via the canned-success stub"
      ;; Full drain: registers the machine, dispatches into it, asserts the
      ;; app-db landed at :authed. Uses the `:fx-overrides` seam to swap
      ;; `:rf.http/managed` for the per-test canned-success stub.
      (let [f (rf/make-frame {:fx-overrides {:rf.http/managed :auth.login/canned-success}})]
        (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                              {:email "a@b.com"
                                               :password "secret"}]]
                          {:frame f})
        (is (= :authed (rf/compute-sub [:auth.login/state] (rf/app-db-value f)))
            "expected :authed after canned success")))

    (testing "drain retry-then-lockout — three failures cycle, the fourth :submit lands at :locked-out"
      ;; Three failures cycle :submitting → :error-shown → :idle ×3, then
      ;; a fourth :submit fails the guard and lands at :locked-out. Uses
      ;; `rf/with-fx-overrides` — the lexical-scope counterpart to the
      ;; per-frame `:fx-overrides` opt on `make-frame`.
      (let [f (rf/make-frame {})]
        (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
          (dotimes [_ 3]
            (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                                  {:email "x@y.z" :password "wrong"}]]
                              {:frame f})
            (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))
          (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                                {:email "x@y.z" :password "wrong"}]]
                            {:frame f}))
        (is (= :locked-out (rf/compute-sub [:auth.login/state] (rf/app-db-value f)))
            "expected :locked-out on 4th attempt")))))
