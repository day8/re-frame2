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
  (:require [clojure.set]
            [clojure.string]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.frame :as frame]
            [re-frame.machines.result :as result]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            ;; The resources artefact (Spec 016) — TEST-ONLY dep on the core
            ;; test classpath (deps.edn `:test` alias). Loading it registers
            ;; the resource registrar kind, the `:rf.resource/*` events/subs,
            ;; and the late-bound SSR runtime-db projection + hydration
            ;; reconcile hooks the `resources-ssr` example drives.
            [re-frame.resources]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as ssr-error-listener]
            [re-frame.ssr.request :as ssr-request]
            [re-frame.ssr.response :as ssr-response]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; clear-all! also drops the framework's ONE built-in coeffect registration
  ;; (`:rf/time-ms` — recordable, provided; registered by a toplevel `reg-cofx`
  ;; form in re-frame.cofx). The resources example's `handle-request` dispatches
  ;; `:rf.resource/ensure`, whose handler now DECLARES `:rf.cofx/requires
  ;; [:rf.resource/generation-allocation :rf/time-ms]` (rf2-601ife) — so the
  ;; framework cofx must be present in the registrar or declared-only delivery
  ;; raises `:rf.error/unregistered-cofx`. Transitive require is idempotent once
  ;; loaded, so reload here to re-fire the registration body (mirrors the
  ;; http/machines/ssr reloads below).
  (require 're-frame.cofx :reload)
  ;; clear-all! also drops the framework-shipped fxs that register at
  ;; namespace load time (e.g. :rf.http/managed and its canned-stub
  ;; siblings). Reload the relevant ns so the toplevel reg-fx forms run
  ;; again and the framework substrate is back in place — the examples
  ;; routinely route :rf.http/managed via :fx-overrides to those stubs
  ;; (Spec 014 §Testing).
  (require 're-frame.http.managed :reload)
  ;; rf2-cdmle — the canned-stub fxs (`:rf.http/managed-canned-success`,
  ;; `:rf.http/managed-canned-failure`) register from
  ;; re-frame.http.test-support, NOT re-frame.http.managed. Reload to
  ;; re-fire the registration body after clear-all!. The transitive
  ;; require from each example ns wouldn't re-evaluate the body (Clojure
  ;; require is idempotent without :reload-all), so reload here.
  (require 're-frame.http.test-support :reload)
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
  ;; clear-all! also drops the SSR registrations that fire at re-frame.ssr
  ;; load time — the `:rf/hydrate` event, the `:rf.ssr/check-*` fxs, the six
  ;; `:rf.server/*` fxs, the `:rf.server/request` cofx — AND the late-bind
  ;; hooks (`:ssr/on-frame-destroyed`, `:ssr/render-to-string`,
  ;; `:ssr/render-tree-hash`). The ssr example's client-hydration tests
  ;; dispatch `:rf/hydrate` and its lifecycle tests rely on `destroy-frame!`
  ;; firing `:ssr/on-frame-destroyed` to release the per-request request slot;
  ;; both need these resurrected. Transitive require from the example ns is
  ;; idempotent (won't re-fire the toplevel forms once loaded), so reload here
  ;; — mirroring the http / machines reloads above (rf2-kb7zis).
  (require 're-frame.ssr :reload)
  ;; clear-all! also drops the resources artefact's registrar kind +
  ;; `:rf.resource/*` events/subs AND its late-bind hooks
  ;; (`:ssr/extend-runtime-db-projection`, `:resources/hydrate-runtime-db`)
  ;; — the `resources-ssr` example's `handle-request` ensures + drains a
  ;; resource and its client hydrate reconciles the resource projection, so
  ;; both surfaces need resurrecting. Transitive require from the example ns
  ;; is idempotent once loaded, so reload here (mirrors the http/machines/ssr
  ;; reloads above, rf2-2pjgiq).
  (require 're-frame.resources :reload)
  ;; Reset the SSR per-frame side-channel atoms (request slots, response
  ;; accumulators, pending error traces) between tests. These are `defonce`
  ;; tables keyed by frame-id, OUTSIDE app-db, so neither `clear-all!` nor
  ;; the `frame/frames` reset touches them. Tests that drive the server flow
  ;; without destroying the frame (e.g. `ssr-example-runs-end-to-end` sets a
  ;; request slot and never tears the frame down) leak a slot that would
  ;; otherwise bleed into the per-request-lifecycle teardown assertions
  ;; (rf2-kb7zis). Clearing here gives each test a clean side-channel slate.
  (reset! ssr-request/request-slots {})
  (reset! ssr-response/response-slots {})
  (reset! ssr-error-listener/pending-error-traces {})
  ;; Drop any cached require of the example namespaces so each test
  ;; re-evaluates their namespace-level handlers against a fresh registrar.
  (remove-ns 'ssr.core)
  (remove-ns 'ssr-streaming.core)
  (remove-ns 'resources-ssr.core)
  (remove-ns 'state-machine-walkthrough.core)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win. A top-level
  ;; `reg-frame …:on-create` still drains synchronously — the lifecycle
  ;; async/sync split keys off `*handler-scope*` (a real cascade), not
  ;; this ambient scope.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

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
                          :initial-events [[:rf/server-init]]
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
;; ssr — per-request frame lifecycle (rf2-kb7zis). The example's
;; `handle-request` wraps its render in `try`/`finally` and calls
;; `rf/destroy-frame!` so a long-running server leaks neither the
;; generated per-request frame nor its SSR request side-channel slot.
;; Spec 011 §Per-request frame teardown contract: the destroy step is
;; load-bearing for memory hygiene. The pre-rf2-kb7zis example skipped it
;; (`examples_test`'s render-only assertions were a false-green for
;; lifecycle correctness), so these tests pin teardown on BOTH the success
;; and throw paths.
;; ============================================================================

(defn- install-canned-articles-stub!
  "Register the per-test `:rf.http/managed` redirect the ssr example's
  `handle-request` routes through `:fx-overrides` — a canned-success stub
  yielding two articles. Mirrors `ssr-example-runs-end-to-end`."
  []
  (rf/reg-fx :ssr.http/canned-articles
    {:platforms #{:server :client}}
    (fn [frame-ctx args-map]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx
              (assoc args-map
                     :value [{:id "a" :title "Article A" :body "Body A"}
                             {:id "b" :title "Article B" :body "Body B"}]))))))

(deftest ssr-example-handle-request-tears-down-per-request-frame
  (testing "examples/reagent/ssr — handle-request leaves no per-request frame
            in the registry and no SSR request slot after it returns
            (Spec 011 §Per-request frame teardown contract)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (install-canned-articles-stub!)
    ;; The example's `:rf/server-init` fires `:rf.http/managed`; redirect it
    ;; to the canned stub via the lexical-scope `with-fx-overrides` so the
    ;; per-request frame's `:on-create` drain (which runs synchronously
    ;; inside `reg-frame`, inside this dynamic scope) routes through the stub
    ;; — no real network traffic. (The same seam the state-machine example
    ;; test uses; we don't redefine the `reg-frame` macro.)
    (let [handle-request (resolve 'ssr.core/handle-request)
          frames-before  (set (keys @frame/frames))
          resp           (rf/with-fx-overrides
                           {:rf.http/managed :ssr.http/canned-articles}
                           (handle-request {:uri "/articles"}))]
      ;; The request still rendered correctly (state loaded, HTML emitted).
      (is (= 200 (:status resp)))
      (is (clojure.string/includes? (:body resp) "Article A"))
      ;; …and the per-request frame + its request slot are GONE.
      (let [frames-after (set (keys @frame/frames))
            new-frames   (clojure.set/difference frames-after frames-before)]
        (is (empty? new-frames)
            (str "handle-request must destroy its per-request frame; "
                 "leaked frames: " (pr-str new-frames)))
        ;; The request side-channel slot table holds no leftover entry (the
        ;; :ssr/on-frame-destroyed hook drops the per-frame slot on
        ;; destroy-frame!). request-slots is keyed by frame-id; an empty
        ;; table proves the gensym'd per-request slot was released.
        (is (empty? @ssr-request/request-slots)
            (str "no request slot survives for the per-request frame; "
                 "leftover slots: " (pr-str (keys @ssr-request/request-slots))))))))

(deftest ssr-example-handle-request-tears-down-on-throw
  (testing "examples/reagent/ssr — handle-request destroys the per-request
            frame even when the render path throws, so a failing request
            leaks nothing (Spec 011 §Per-request frame teardown contract —
            cleanup runs on the throw path too)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (install-canned-articles-stub!)
    (let [handle-request (resolve 'ssr.core/handle-request)
          frames-before  (set (keys @frame/frames))]
      (with-redefs [;; Force the render to throw AFTER the frame + request
                    ;; slot were allocated, exercising the `finally` path.
                    rf/render-to-string
                    (fn [& _] (throw (ex-info "boom — render failure" {})))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (rf/with-fx-overrides
                       {:rf.http/managed :ssr.http/canned-articles}
                       (handle-request {:uri "/articles"})))
            "the render throw propagates (the example does not swallow it)")
        (let [frames-after (set (keys @frame/frames))
              new-frames   (clojure.set/difference frames-after frames-before)]
          (is (empty? new-frames)
              (str "the `finally` must destroy the per-request frame on the "
                   "throw path; leaked frames: " (pr-str new-frames)))
          (is (empty? @ssr-request/request-slots)
              (str "the throw-path teardown must release the request slot; "
                   "leftover slots: " (pr-str (keys @ssr-request/request-slots)))))))))

;; ============================================================================
;; ssr — per-request schema validation (rf2-i6p308, carved from rf2-9wc2ed).
;;
;; The fix held the app schema as a value (`ArticlesSchema`) and registered it
;; explicitly against EACH frame family — the per-request server frame in
;; `handle-request` (BEFORE `:on-create` fires `:rf/server-init`) and the
;; fixed client hydration frame in `run`. The earlier bare ns-load
;; registration either raised `:rf.error/no-frame-context` or (under a naive
;; `with-frame :rf/default`) bound the schema to the client frame ONLY,
;; leaving the per-request SERVER frame — where the server-side `:articles`
;; commit actually validates — UNSCHEMA'd, so server-side validation silently
;; never ran (the bug was masked precisely because no schema applied).
;;
;; The existing handle-request tests exercise this implicitly. This test
;; locks it EXPLICITLY: the per-request server frame carries the `:articles`
;; schema (NOT `:rf/default`), the server-init commit's articles PASS
;; validation on THAT frame, and a malformed `:articles` value FAILS
;; validation on THAT frame — proving validation is live + frame-scoped on
;; the per-request frame, not routed around. Requiring `re-frame.schemas`
;; (above) wires the default Malli validator (rf2-v96fh), so validation is
;; genuinely live here.
;; ============================================================================

(deftest ssr-example-per-request-frame-carries-and-validates-articles-schema
  (testing "examples/reagent/ssr — the per-request SERVER frame carries the
            :articles schema (the rf2-9wc2ed per-request registration) and
            validation runs ON THAT FRAME, not on :rf/default (rf2-i6p308)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (install-canned-articles-stub!)
    (let [articles-schema @(resolve 'ssr.core/ArticlesSchema)
          ;; Drive a per-request server frame exactly as handle-request does:
          ;; register the schema against the gensym frame BEFORE :on-create
          ;; fires :rf/server-init (which commits the canned articles).
          fid             (keyword "rf.frame" (str (gensym "f")))
          _               (ssr/set-request! fid {:uri "/articles"})
          _               (rf/reg-app-schema [:articles] {:schema articles-schema :frame fid})
          f               (rf/with-fx-overrides
                            {:rf.http/managed :ssr.http/canned-articles}
                            (rf/reg-frame fid
                              {:doc       "ssr-example per-request validation frame"
                               :platform  :server
                               :initial-events [[:rf/server-init]]}))
          final-db        (rf/app-db-value f)]
      ;; 1. The schema is bound to the PER-REQUEST frame — explicitly.
      (is (= articles-schema (schemas/app-schema-at [:articles] {:frame fid}))
          "the :articles schema is registered against the per-request server frame")
      ;; 2. …and NOT on :rf/default (the masking-default frame). The fixture
      ;; pins :rf/default as ambient scope but the example never registered
      ;; the SSR schema there — per-request scoping, not a default floor.
      (is (nil? (schemas/app-schema-at [:articles] {:frame :rf/default}))
          "the SSR :articles schema is NOT bound to :rf/default — it is
           per-request frame-scoped (the rf2-9wc2ed contract)")
      ;; 3. The server-init commit landed two valid articles…
      (is (= 2 (count (:articles final-db)))
          "precondition: the canned server-init commit loaded two articles")
      ;; …and they PASS validation ON the per-request frame (the [:maybe]
      ;; schema accepts both the pre-load nil and the loaded vector).
      ;; `interop/debug-enabled?` is true by default on the JVM (the dev/prod
      ;; gate), so `validate-app-schema!` actually runs — and requiring
      ;; `re-frame.schemas` wired the default Malli validator (rf2-v96fh).
      (is (true? (schemas/validate-app-schema! final-db :rf/server-init fid))
          "the server-init :articles commit validates on the per-request frame")
      ;; 4. A MALFORMED :articles value FAILS validation on the per-request
      ;; frame — proving the validator is genuinely live + frame-scoped
      ;; there (not a soft-pass no-op). A non-vector :articles violates
      ;; ArticlesSchema ([:maybe [:vector …]]).
      (let [bad-db (assoc final-db :articles "not-a-vector-of-articles")]
        (is (false? (schemas/validate-app-schema! bad-db :rf/server-init fid))
            "a malformed :articles commit FAILS validation on the per-request
             frame — validation is live and frame-scoped (this is the gap
             the per-request registration + [:maybe] schema closed)"))
      ;; 5. The same malformed value validates TRUE against :rf/default —
      ;; because no SSR schema is registered there — confirming the failure
      ;; above is attributable to the PER-REQUEST frame's schema specifically.
      (let [bad-db (assoc final-db :articles "not-a-vector-of-articles")]
        (is (true? (schemas/validate-app-schema! bad-db :rf/server-init :rf/default))
            ":rf/default carries no SSR schema, so the same bad value
             soft-passes there — the per-request frame is where the
             contract lives"))
      ;; Teardown the per-request frame + its request slot (hygiene; mirrors
      ;; the example's handle-request finally).
      (rf/destroy-frame! f))))

;; ============================================================================
;; ssr — client hydration path (rf2-kb7zis). The example now boots the
;; client via the framework `ssr/hydrate!` helper (it relies on the
;; framework-registered `:rf/hydrate`, no longer a stale local copy). These
;; tests pin the contract the example depends on, against the example's own
;; registrations: a payload carrying `:rf/render-hash` stashes the server
;; hash under [:rf.runtime/ssr :hydration :server-hash]; a matching client
;; render-tree-fn is silent; a divergent one emits :rf.ssr/hydration-mismatch;
;; and a malformed payload does NOT replace app-db (fail-closed). JVM-driven
;; with an explicit :payload on a :client-platform frame (no DOM to read).
;; ============================================================================

(defn- capture-traces!
  "Run f under a trace listener; return the captured event vector."
  [f]
  (let [traces (atom [])
        cb-id  (gensym "::examples-ssr-capture-")]
    (rf/register-listener! :trace cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace cb-id)))
    @traces))

(deftest ssr-example-client-hydration-stashes-server-hash-and-seeds-db
  (testing "examples/reagent/ssr — ssr/hydrate! against the example's
            framework-owned :rf/hydrate stashes the payload's :rf/render-hash
            under [:rf.runtime/ssr :hydration :server-hash] and replaces app-db
            with the :rf/app-db slice"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-example client frame"
                                       :platform :client})
          payload      {:rf/version     1
                        :rf/render-hash "abc12345"
                        :rf/app-db      {:articles [{:id "a" :title "A" :body "ba"}]}
                        :rf/runtime-db  {}}
          returned     (ssr/hydrate! {:frame client-frame :payload payload})]
      (is (= payload returned)
          "hydrate! returns the applied payload")
      (is (= [{:id "a" :title "A" :body "ba"}]
             (:articles (rf/app-db-value client-frame)))
          ":rf/hydrate replaced app-db with the server slice")
      (is (= "abc12345"
             (get-in (rf/runtime-db-value client-frame)
                     [:rf.runtime/ssr :hydration :server-hash]))
          "the server render-hash is stashed for the verify step"))))

(deftest ssr-example-client-hydration-matching-hash-is-silent
  (testing "examples/reagent/ssr — a client render-tree whose hash MATCHES the
            payload's :rf/render-hash emits NO :rf.ssr/hydration-mismatch"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-example verify-match frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          client-tree  [:div.page [:h1 "Recent articles"]]
          matched-hash (rf/render-tree-hash client-tree)
          payload      {:rf/version 1 :rf/render-hash matched-hash
                        :rf/app-db {:articles []} :rf/runtime-db {}}
          traces       (capture-traces!
                         (fn []
                           (ssr/hydrate! {:frame          client-frame
                                          :payload        payload
                                          :render-tree-fn (fn [] client-tree)})))]
      (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
          (str "matching hashes → no mismatch trace; saw: "
               (pr-str (mapv :operation traces)))))))

(deftest ssr-example-client-hydration-divergent-hash-fires-mismatch
  (testing "examples/reagent/ssr — a client render-tree whose hash DIVERGES
            from the payload's :rf/render-hash emits :rf.ssr/hydration-mismatch
            (the verify step the example's `run` wires via :render-tree-fn)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-example verify-divergent frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          payload      {:rf/version 1
                        :rf/render-hash "server00"   ;; != the client tree hash
                        :rf/app-db {:articles []} :rf/runtime-db {}}
          traces       (capture-traces!
                         (fn []
                           (ssr/hydrate!
                             {:frame          client-frame
                              :payload        payload
                              :render-tree-fn (fn [] [:div.page [:h1 "Recent articles"]])})))]
      (is (some #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
          (str "divergent hash → mismatch trace; saw: "
               (pr-str (mapv :operation traces)))))))

(deftest ssr-example-client-hydration-malformed-payload-does-not-replace-db
  (testing "examples/reagent/ssr — a MALFORMED payload (a present-but-non-map
            :rf/app-db slice) is rejected fail-closed: app-db is left
            unchanged (Spec 011 §The :rf/hydrate event — both partitions
            validate fail-closed before installation)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-example fail-closed frame"
                                       :platform :client})]
      ;; Seed a known app-db value first so we can prove it survives.
      (rf/dispatch-sync [:articles/loaded {:value [{:id "keep" :title "Keep" :body "b"}]}]
                        {:frame client-frame})
      (is (= [{:id "keep" :title "Keep" :body "b"}]
             (:articles (rf/app-db-value client-frame)))
          "precondition: app-db seeded")
      ;; A non-map :rf/app-db slice is the malformed shape.
      (let [bad-payload {:rf/version 1 :rf/app-db "not-a-map"}]
        (ssr/hydrate! {:frame client-frame :payload bad-payload})
        (is (= [{:id "keep" :title "Keep" :body "b"}]
               (:articles (rf/app-db-value client-frame)))
            "malformed payload rejected — app-db unchanged (fail-closed)")))))

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
;; SSR examples — dynamic payload path round-trip (rf2-2pjgiq).
;;
;; The acceptance gate the review (rf2-2pjgiq) named: feed the ACTUAL dynamic
;; example payload — the plain `handle-request` HTML payload, the resources
;; `handle-request` HTML payload, and the streaming `final-payload` — into the
;; framework `ssr/hydrate!` against the example's OWN client frame
;; (`:rf/default`) and assert NO `:rf.error/hydration-frame-id-mismatch` plus
;; the expected hydrated state. The earlier example tests covered server and
;; client separately with hand-built payloads that omitted `:rf/frame-id`;
;; these drive the real server→client wire so a drift back to stamping the
;; per-request server gensym (which would conflict with the fixed
;; `:rf/default` client frame) fails loud here.
;;
;; The fix (rf2-2pjgiq): the dynamic example payloads deliberately OMIT
;; `:rf/frame-id` (an absent frame-id is no conflict; the explicit client
;; target stands — Spec 011 §The hydration payload), and the manual payload
;; `<script>` emission routes through the EDN-aware `escape-edn-script-body`
;; so a server-provided `</script>` can't close the envelope.
;; ============================================================================

(defn- extract-payload-edn
  "Pull the `__rf_payload` EDN string out of an SSR example's HTML body and
  read it. `clojure.edn/read-string` decodes the `\\u003c` reader escapes
  `escape-edn-script-body` emits inside string literals, so the parsed value
  is the exact payload the server built."
  [html-body]
  (let [m (re-find #"(?s)id='__rf_payload' type='application/edn'>(.*?)</script>"
                   html-body)]
    (some-> (second m) edn/read-string)))

(deftest ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch
  (testing "examples/reagent/ssr — the payload the dynamic `handle-request`
            emits feeds into `ssr/hydrate!` against the example's `:rf/default`
            client frame with NO `:rf.error/hydration-frame-id-mismatch` (the
            payload omits the per-request server frame-id) and seeds the
            client app-db with the server's articles (rf2-2pjgiq)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    (install-canned-articles-stub!)
    (let [handle-request (resolve 'ssr.core/handle-request)
          resp           (rf/with-fx-overrides
                           {:rf.http/managed :ssr.http/canned-articles}
                           (handle-request {:uri "/articles"}))
          payload        (extract-payload-edn (:body resp))]
      (is (= 200 (:status resp)))
      (is (some? payload) "the __rf_payload EDN parsed out of the HTML body")
      (is (not (contains? payload :rf/frame-id))
          (str "the dynamic payload OMITS :rf/frame-id (server gensym frame "
               "would conflict with the fixed :rf/default client frame)"))
      ;; Hydrate the example's OWN client frame (:rf/default, a :client frame).
      (let [client-frame @(resolve 'ssr.core/app-frame)
            _            (rf/reg-frame client-frame
                           {:doc "ssr-example client frame" :platform :client})
            returned     (ssr/hydrate! {:frame client-frame :payload payload})]
        (is (= payload returned)
            "hydrate! applied the payload (no frame-id conflict thrown)")
        (is (= [{:id "a" :title "Article A" :body "Body A"}
                {:id "b" :title "Article B" :body "Body B"}]
               (:articles (rf/app-db-value client-frame)))
            "the client app-db carries the server's articles after hydration")))))

(deftest ssr-example-payload-script-escapes-script-breakout
  (testing "examples/reagent/ssr — a server-provided string containing
            `</script>` (round-tripped through app-db) is escaped by the
            EDN-aware `<script>`-body encoder, so it CANNOT close the
            `__rf_payload` envelope, and the payload still round-trips through
            the client EDN reader unchanged (security audit 2026-05-14 §P1,
            rf2-7ksyr / rf2-2pjgiq)"
    (require 'ssr.core :reload)
    (rf/init! ssr/adapter)
    ;; A canned stub whose article title carries a `</script>` breakout
    ;; precursor — the exact XSS shape the EDN-aware escaper exists to defang.
    (rf/reg-fx :ssr.http/canned-evil
      {:platforms #{:server :client}}
      (fn [frame-ctx args-map]
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx
                (assoc args-map
                       :value [{:id "x"
                                :title "</script><script>alert('xss')</script>"
                                :body "b"}])))))
    (let [handle-request (resolve 'ssr.core/handle-request)
          resp           (rf/with-fx-overrides
                           {:rf.http/managed :ssr.http/canned-evil}
                           (handle-request {:uri "/x"}))
          body           (:body resp)]
      (is (= 200 (:status resp)))
      ;; The raw breakout must NOT appear verbatim — the `<` inside the EDN
      ;; string literal is escaped to the `<` reader escape.
      (is (not (clojure.string/includes?
                 body "</script><script>alert('xss')</script>"))
          "the raw </script> breakout must not survive into the HTML")
      (is (clojure.string/includes? body "\\u003c")
          "the breakout `<` is escaped to the \\u003c EDN reader escape")
      ;; And the escaped payload still parses back to the exact server value —
      ;; the EDN reader decodes < inside the string literal.
      (let [payload (extract-payload-edn body)]
        (is (= "</script><script>alert('xss')</script>"
               (-> payload :rf/app-db :articles first :title))
            "the payload round-trips through the EDN reader unchanged")))))

(deftest ssr-streaming-example-final-payload-hydrates-without-frame-id-mismatch
  (testing "examples/reagent/ssr_streaming — the dynamic `handle-request`
            `:final-payload` feeds into `ssr/hydrate!` against the example's
            `:rf/default` client frame with NO frame-id mismatch (it omits the
            per-request server frame-id) and seeds the client app-db with the
            three streamed cards (rf2-2pjgiq)"
    (require 'ssr-streaming.core :reload)
    (rf/init! ssr/adapter)
    (let [handle-request (resolve 'ssr-streaming.core/handle-request)
          result         (handle-request {:uri "/dashboard"})
          payload        (:final-payload result)]
      (is (not (contains? payload :rf/frame-id))
          "the streaming final-payload OMITS :rf/frame-id")
      ;; The example's `app-frame` is `:cljs`-only (the streaming client boot
      ;; is browser-side), so reference its value (`:rf/default`) directly
      ;; here — the JVM cannot resolve a reader-conditional `:cljs` def.
      (let [client-frame :rf/default
            _            (rf/reg-frame client-frame
                           {:doc "ssr-streaming-example client frame"
                            :platform :client})
            returned     (ssr/hydrate! {:frame client-frame :payload payload})]
        (is (= payload returned)
            "hydrate! applied the final-payload (no frame-id conflict thrown)")
        (is (= 3 (count (:cards (rf/app-db-value client-frame))))
            "the client app-db carries the three streamed cards after hydration")))))

(deftest resources-ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch
  (testing "examples/reagent/resources_ssr — the payload the dynamic
            `handle-request` emits (under the valid
            `:rf.ssr.payload/whole-app-db` policy, NOT the invalid empty
            `[]`) feeds into `ssr/hydrate!` against the example's `:rf/default`
            client frame with NO frame-id mismatch and installs the SSR-
            preloaded resource entry into the client `:rf.runtime/resources`
            slice (Spec 016 §SSR client hydration, rf2-2pjgiq)"
    (require 'resources-ssr.core :reload)
    (rf/init! ssr/adapter)
    ;; Stub the resource's managed-HTTP fetch with a canned-success reply so
    ;; the example's blocking drain settles the page resource synchronously
    ;; (no real network; mirrors the plain-SSR canned-articles stub).
    (rf/reg-fx :resources-ssr.http/canned
      {:platforms #{:server :client}}
      (fn [frame-ctx args-map]
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx
                (assoc args-map
                       :value [{:slug "welcome"   :title "Welcome to re-frame2"}
                               {:slug "resources" :title "Server-state as resources"}])))))
    (let [handle-request (resolve 'resources-ssr.core/handle-request)
          resp           (rf/with-fx-overrides
                           {:rf.http/managed :resources-ssr.http/canned}
                           (handle-request {:uri "/articles"}))
          payload        (extract-payload-edn (:body resp))]
      (is (= 200 (:status resp))
          (str "the dynamic handle-request returned 200 (valid payload policy "
               "— an empty `[]` policy would have thrown "
               ":rf.error/ssr-missing-payload-policy)"))
      (is (some? payload))
      (is (not (contains? payload :rf/frame-id))
          "the resources payload OMITS :rf/frame-id")
      ;; The runtime-db projection carries the loaded resource entry (the
      ;; allowed `:entries`, not the indexes).
      (let [entries (get-in payload [:rf/runtime-db :rf.runtime/resources :entries])]
        (is (= 1 (count entries)) "one resource entry rides :rf/runtime-db")
        (is (= :loaded (-> entries vals first :status))
            "the SSR-preloaded resource settled :loaded before render"))
      ;; Hydrate the example's OWN :rf/default client frame.
      (let [client-frame @(resolve 'resources-ssr.core/app-frame)
            _            (rf/reg-frame client-frame
                           {:doc "resources-ssr-example client frame"
                            :platform :client})
            returned     (ssr/hydrate! {:frame client-frame :payload payload})]
        (is (= payload returned)
            "hydrate! applied the payload (no frame-id conflict thrown)")
        ;; The client runtime-db carries the reconciled resource entry — the
        ;; reverse indexes are recomputed from `:entries` on install (Spec 016).
        (let [client-entries (get-in (rf/runtime-db-value client-frame)
                                     [:rf.runtime/resources :entries])]
          (is (= 1 (count client-entries))
              "the hydrated resource entry installed into the client frame")
          (is (= :loaded (-> client-entries vals first :status))
              "the hydrated entry is :loaded (renders immediately, no refetch)"))))))

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
            (machines/machine-transition login-flow s0
                                   [:auth.login/submit
                                    {:email "a@b.com" :password "secret"}])]
        (is (= :submitting (:state s1)))
        ;; Entering :submitting fires the :issue-request action's :fx.
        (is (= 1 (count fx1)) "one :rf.http/managed fx")
        (is (= :rf.http/managed (ffirst fx1)))
        (let [{s2 ::result/snap} (machines/machine-transition login-flow s1
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
            (machines/machine-transition login-flow snapshot
                                   [:auth.login/failure
                                    {:failure {:kind :rf.http/http-4xx
                                               :message "bad creds"}}])]
        (is (= :locked-out (:state s)) "expected :locked-out at attempts=3")))

    (testing "drain happy path — full drain lands the app-db at :authed via the canned-success stub"
      ;; Full drain: registers the machine, dispatches into it, asserts the
      ;; app-db landed at :authed. Uses the `:fx-overrides` seam to swap
      ;; `:rf.http/managed` for the per-test canned-success stub.
      (let [f (frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed :auth.login/canned-success}})]
        (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                              {:email "a@b.com"
                                               :password "secret"}]]
                          {:frame f})
        (is (= :authed (rf/compute-sub [:auth.login/state] (rf/frame-state-value f)))
            "expected :authed after canned success")))

    (testing "drain retry-then-lockout — three failures cycle, the fourth :submit lands at :locked-out"
      ;; Three failures cycle :submitting → :error-shown → :idle ×3, then
      ;; a fourth :submit fails the guard and lands at :locked-out. Uses
      ;; `rf/with-fx-overrides` — the lexical-scope counterpart to the
      ;; per-frame `:fx-overrides` opt on `make-frame`.
      (let [f (frame/make-anon-frame-record! {})]
        (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
          (dotimes [_ 3]
            (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                                  {:email "x@y.z" :password "wrong"}]]
                              {:frame f})
            (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))
          (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                                {:email "x@y.z" :password "wrong"}]]
                            {:frame f}))
        (is (= :locked-out (rf/compute-sub [:auth.login/state] (rf/frame-state-value f)))
            "expected :locked-out on 4th attempt")))))
