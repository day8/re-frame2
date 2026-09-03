(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the routing artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the routing artefact IS on the classpath here (the test ns
  requires `re-frame.routing`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-k682 (routing split), and the
  prose at the call sites in `re-frame.core`.

  Note (rf2-wad2fl — front-porch shrink): the URL-codec fns `match-url` /
  `route-url` (and `current-url` / `clear-route`) were demoted off the
  `re-frame.core` façade — they are reached through `re-frame.routing`
  now, so the façade artefact-missing contract no longer applies to them.
  rf2-bcjpq5 then deleted the dormant `re-frame.core-routing` wrappers for
  `match-url` / `route-url`, and rf2-sy7zr the `clear-route` / `current-url`
  pair, along with all four late-bind hooks — nothing consumed them. The
  façade surfaces that remain are the `reg-route` registration MACRO
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
          ;; rf2-vvixub — message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/routing-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/routing-artefact-missing] token")
          (is (= :rf.error/routing-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            ;; Per rf2-hoiu the throw lives in `re-frame.core-routing/reg-route`
            ;; — the sibling-namespace fn-form delegate the macro routes
            ;; through. Per rf2-j8icl the `:where` symbol is namespace-
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
    ;; Per rf2-uhv2 the route-link surface is published through the
    ;; :routing/route-link late-bind hook (CLJS → Reagent-wrapped render
    ;; fn; JVM → SSR render fn). Consumers without the routing artefact
    ;; see the hook unregistered; the wrapper in re-frame.core-routing
    ;; raises the documented missing-artefact error.
    (with-hook-as-nil :routing/route-link
      (fn []
        (let [thrown (try (rf/route-link {:to :route/probe})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "route-link throws when the routing artefact is absent")
          ;; rf2-vvixub — message is the human :reason + trailing
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

;; ===========================================================================
;; rf2-bcjpq5 — the demotion is permanent: `match-url` / `route-url` are NOT
;; `re-frame.core` exports.
;;
;; Per the czn2m0 D1 ruling the tiering rule is reg-* macros + primary
;; ergonomic verbs on the `rf/` façade, advanced query/codec functions in
;; their owning namespace. rf2-wad2fl demoted these two; rf2-bcjpq5 deleted
;; the dormant `re-frame.core-routing` wrappers and their `:routing/match-url`
;; / `:routing/route-url` late-bind hooks that no one consumed. This test
;; makes a silent re-promotion fail loudly rather than quietly reopening a
;; second public home.
;; ===========================================================================

(deftest url-codec-fns-are-not-facade-exports-rf2-bcjpq5
  (testing "neither match-url nor route-url is public in re-frame.core"
    (let [facade (ns-publics 're-frame.core)]
      (is (nil? (get facade 'match-url))
          "match-url is NOT a re-frame.core export — call rf.routing/match-url")
      (is (nil? (get facade 'route-url))
          "route-url is NOT a re-frame.core export — call rf.routing/route-url")))
  (testing "both remain public on their owning namespace, re-frame.routing"
    ;; Positive control: proves the assertions above are not vacuously green
    ;; because of a typo or an unloaded namespace.
    (let [owning (ns-publics 're-frame.routing)]
      (is (some? (get owning 'match-url))
          "re-frame.routing/match-url is the canonical home")
      (is (some? (get owning 'route-url))
          "re-frame.routing/route-url is the canonical home"))))

;; ===========================================================================
;; rf2-sy7zr — the same sweep, finished: `clear-route` / `current-url` carried
;; the identical dormancy. They were demoted off the façade by rf2-wad2fl but
;; kept `re-frame.core-routing` wrappers and `:routing/clear-route` /
;; `:routing/current-url` late-bind hooks that NOTHING consumed — dead
;; indirection every reader had to trace before concluding it does nothing.
;;
;; Deleted, no shim. `re-frame.core-routing` now holds exactly the two
;; surfaces that need a core-side wrapper: `reg-route` (the façade macro's
;; fn-form delegate) and `route-link` (no owned-ns peer).
;;
;; Every assertion below is paired with a POSITIVE CONTROL so a typo, an
;; unloaded namespace, or a renamed hook registry cannot make the negative
;; legs vacuously green.
;; ===========================================================================

(deftest clear-route-and-current-url-are-not-facade-exports-rf2-sy7zr
  (testing "neither clear-route nor current-url is public in re-frame.core"
    (let [facade (ns-publics 're-frame.core)]
      (is (nil? (get facade 'clear-route))
          "clear-route is NOT a re-frame.core export — call rf.routing/clear-route")
      (is (nil? (get facade 'current-url))
          "current-url is NOT a re-frame.core export — call rf.routing/current-url")
      ;; Positive control: the façade IS loaded and DOES export the routing
      ;; surfaces that legitimately live there.
      (is (some? (get facade 'reg-route))
          "control — reg-route IS a re-frame.core export (the registration macro stays)")
      (is (some? (get facade 'route-link))
          "control — route-link IS a re-frame.core export (no owned-ns peer)")))
  (testing "both remain public on their owning namespace, re-frame.routing"
    (let [owning (ns-publics 're-frame.routing)]
      (is (some? (get owning 'clear-route))
          "re-frame.routing/clear-route is the canonical home")
      (is (some? (get owning 'current-url))
          "re-frame.routing/current-url is the canonical home"))))

(deftest dormant-core-routing-wrappers-are-gone-rf2-sy7zr
  (testing "the re-frame.core-routing wrapper vars are deleted, not shimmed"
    (let [wrappers (ns-publics 're-frame.core-routing)]
      (doseq [gone '[clear-route current-url match-url route-url]]
        (is (nil? (get wrappers gone))
            (str "re-frame.core-routing/" gone " is GONE — no wrapper, no alias, "
                 "no forwarding shim")))
      ;; Positive control: the namespace IS loaded and the two live wrappers
      ;; still resolve, so the nil assertions above mean "deleted", not
      ;; "namespace never loaded".
      (is (some? (get wrappers 'reg-route))
          "control — re-frame.core-routing/reg-route survives (façade macro delegate)")
      (is (some? (get wrappers 'route-link))
          "control — re-frame.core-routing/route-link survives (no owned-ns peer)"))))

(deftest dormant-routing-late-bind-hooks-are-unpublished-rf2-sy7zr
  (testing "routing publishes no hook for the four demoted surfaces"
    (doseq [hook [:routing/clear-route :routing/current-url
                  :routing/match-url :routing/route-url]]
      (is (nil? (rf.late-bind/get-fn hook))
          (str hook " is unpublished — core has no wrapper to late-bind to")))
    ;; Positive control: routing IS loaded and DOES publish its live hooks, so
    ;; the nils above are real deletions rather than an unloaded artefact.
    (is (some? (rf.late-bind/get-fn :routing/reg-route))
        "control — :routing/reg-route IS published (the routing artefact is loaded)")
    (is (some? (rf.late-bind/get-fn :routing/route-link))
        "control — :routing/route-link IS published")))
