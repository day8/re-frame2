(ns re-frame.late-bind-missing-test
  "Assert the documented missing-artefact error contract for
  the routing artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The prose documents the contract; this
  test pins the runtime behaviour.

  Strategy: the routing artefact IS on the classpath here (the test ns
  requires `re-frame.routing`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam and the
  prose at the call sites in `re-frame.core`.

  Note: the URL-codec fns `match-url` / `route-url` are not on the
  `re-frame.core` façade — they are reached through `re-frame.routing`,
  so the façade artefact-missing contract does not apply to them; nor are
  `current-url` (its home is `re-frame.routing.history`) or `clear-route`
  (route removal is `(rf/clear :route id)`). `re-frame.core-routing`
  carries no wrapper or late-bind hook for `match-url` / `route-url` /
  `current-url`. It does carry the `clear-route` wrapper and its hook,
  because `(rf/clear :route id)` consumes them and route removal must go
  through the owning fn to emit the `:rf.route/cleared` trace; there is no
  public `clear-route` NAME. The façade surfaces are the `reg-route` registration MACRO
  (source-coord capture) and `route-link` (no owned-ns peer); their
  missing-artefact contracts are tested below."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            ;; Required explicitly (rather than relying on the transitive load
            ;; through `re-frame.core`) so the wrapper-deletion assertions read
            ;; a genuinely loaded namespace.
            [re-frame.core-routing]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading routing registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.routing]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest reg-route-raises-when-routing-artefact-missing
  (testing "rf/reg-route (macro) raises :rf.error/routing-artefact-missing when the :routing/reg-route hook is nil"
    (with-hook-as-nil :routing/reg-route
      (fn []
        (let [thrown (try (rf/reg-route :route/probe {} "/probe")
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-route throws when the routing artefact is absent")
          ;; The message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/routing-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/routing-artefact-missing] token")
          (is (= :rf.error/routing-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            ;; The throw lives in `re-frame.core-routing/reg-route`
            ;; — the sibling-namespace fn-form delegate the macro routes
            ;; through. The `:where` symbol is namespace-
            ;; qualified to the user-facing surface so users greping for
            ;; the symbol find `rf/reg-route` call sites.
            (is (= 'rf/reg-route (:where data))
                "ex-data carries :where = 'rf/reg-route")
            (is (= :route/probe (:route-id data))
                "ex-data carries :route-id from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest route-link-raises-when-routing-artefact-missing
  (testing "rf/route-link raises :rf.error/routing-artefact-missing when the :routing/route-link hook is nil"
    ;; The route-link surface is published through the
    ;; :routing/route-link late-bind hook. CLJS publishes the ELEMENT-
    ;; emitting `routing/route-link-element`, NOT the registered view head:
    ;; `rf/route-link` is a `defwrapper`, so the hook value is CALLED, and a
    ;; head that is called never becomes a component that can read the frame
    ;; context. JVM publishes the SSR render fn directly. Either
    ;; way, consumers without the routing artefact see the hook unregistered
    ;; and the wrapper in re-frame.core-routing raises the documented
    ;; missing-artefact error — which is what this JVM test pins.
    (with-hook-as-nil :routing/route-link
      (fn []
        (let [thrown (try (rf/route-link {:to :route/probe})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "route-link throws when the routing artefact is absent")
          ;; The message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/routing-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/routing-artefact-missing] token")
          (is (= :rf.error/routing-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            (is (= 'rf/route-link (:where data))
                "ex-data carries :where = 'rf/route-link")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest dormant-core-routing-wrappers-are-gone-rf2-sy7zr
  (testing "the re-frame.core-routing wrapper vars are absent, not shimmed"
    (let [wrappers (ns-publics 're-frame.core-routing)]
      (doseq [gone '[current-url match-url route-url]]
        (is (nil? (get wrappers gone))
            (str "re-frame.core-routing/" gone " is absent — no wrapper, no alias, "
                 "no forwarding shim")))
      ;; clear-route's wrapper exists, deliberately: `(rf/clear :route id)`
      ;; consumes it, and must route through the OWNING lifecycle fn (which
      ;; emits `:rf.route/cleared`) rather than short-cutting to
      ;; `re-frame.registrar/unregister!`. A wrapper is not a public
      ;; NAME: `re-frame.routing/clear-route` is absent (above).
      (is (some? (get wrappers 'clear-route))
          "re-frame.core-routing/clear-route is the (rf/clear :route id) delegate")
      ;; Positive control: the namespace IS loaded and the live wrappers
      ;; resolve, so the nil assertions above mean "absent", not
      ;; "namespace never loaded".
      (is (some? (get wrappers 'reg-route))
          "control — re-frame.core-routing/reg-route survives (façade macro delegate)")
      (is (some? (get wrappers 'route-link))
          "control — re-frame.core-routing/route-link survives (no owned-ns peer)"))))
