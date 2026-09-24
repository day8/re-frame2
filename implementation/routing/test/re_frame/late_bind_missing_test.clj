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
            [re-frame.routing]
            ;; Required explicitly for the same reason
            ;; `re-frame.core-routing` is — the canonical-home legs call
            ;; `ns-publics` on these two, which THROWS on a namespace that was
            ;; never loaded rather than reading empty, so leaning on the
            ;; transitive load through `re-frame.routing` would make the
            ;; assertion's failure mode an error instead of a miss.
            [re-frame.routing.history]
            [re-frame.routing.subs]))

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

;; ===========================================================================
;; `match-url` / `route-url` are NOT
;; `re-frame.core` exports.
;;
;; The tiering rule is reg-* macros + primary
;; ergonomic verbs on the `rf/` façade, advanced query/codec functions in
;; their owning namespace, so these two have no `re-frame.core-routing`
;; wrappers and no `:routing/match-url`
;; / `:routing/route-url` late-bind hooks. This test
;; makes a silent promotion onto the façade fail loudly rather than quietly
;; opening a second public home.
;; ===========================================================================

(deftest url-codec-fns-are-not-facade-exports-rf2-bcjpq5
  (testing "neither match-url nor route-url is public in re-frame.core"
    (let [facade (ns-publics 're-frame.core)]
      (is (nil? (get facade 'match-url))
          "match-url is NOT a re-frame.core export — call rf.routing/match-url")
      (is (nil? (get facade 'route-url))
          "route-url is NOT a re-frame.core export — call rf.routing/route-url")))
  (testing "both are public on their owning namespace, re-frame.routing"
    ;; Positive control: proves the assertions above are not vacuously green
    ;; because of a typo or an unloaded namespace.
    (let [owning (ns-publics 're-frame.routing)]
      (is (some? (get owning 'match-url))
          "re-frame.routing/match-url is the canonical home")
      (is (some? (get owning 'route-url))
          "re-frame.routing/route-url is the canonical home"))))

;; ===========================================================================
;; `clear-route` / `current-url` are not `re-frame.core` exports either, and
;; there is no `current-url` wrapper in `re-frame.core-routing` and no
;; `:routing/current-url` late-bind hook: an unconsumed wrapper is dead
;; indirection every reader has to trace before concluding it does nothing.
;;
;; No shim. `re-frame.core-routing` holds exactly the
;; surfaces that need a core-side wrapper: `reg-route` (the façade macro's
;; fn-form delegate), `route-link` (no owned-ns peer) and `clear-route` (the
;; `(rf/clear :route id)` delegate).
;;
;; Every assertion below is paired with a POSITIVE CONTROL so a typo, an
;; unloaded namespace, or a renamed hook registry cannot make the negative
;; legs vacuously green.
;; ===========================================================================

(deftest clear-route-and-current-url-are-not-facade-exports-rf2-sy7zr
  (testing "neither clear-route nor current-url is public in re-frame.core"
    (let [facade (ns-publics 're-frame.core)]
      (is (nil? (get facade 'clear-route))
          "clear-route is NOT a re-frame.core export — call (rf/clear :route id)")
      (is (nil? (get facade 'current-url))
          "current-url is NOT a re-frame.core export — call rf.routing.history/current-url")
      ;; Positive control: the façade IS loaded and DOES export the routing
      ;; surfaces that legitimately live there.
      (is (some? (get facade 'reg-route))
          "control — reg-route IS a re-frame.core export (the registration macro)")
      (is (some? (get facade 'route-link))
          "control — route-link IS a re-frame.core export (no owned-ns peer)")))
  ;; NEITHER name is published on the owning namespace either: route
  ;; removal is the one kind-keyed `(rf/clear :route id)`, and
  ;; `current-url` lives at its real home `re-frame.routing.history`.
  (testing "neither clear-route nor current-url survives on re-frame.routing"
    (let [owning (ns-publics 're-frame.routing)]
      (is (nil? (get owning 'clear-route))
          "re-frame.routing/clear-route is absent")
      (is (nil? (get owning 'current-url))
          "re-frame.routing/current-url is absent")
      ;; Positive controls. Both names are absent from the SAME namespace, so
      ;; neither can serve as the other's control. `match-url` / `route-url`
      ;; are the neighbouring re-exports that legitimately live there, so the
      ;; nils above mean "absent" rather than "namespace never loaded".
      (doseq [kept '[match-url route-url]]
        (is (some? (get owning kept))
            (str "control — re-frame.routing/" kept " IS published")))))
  (testing "the public door for route removal is (rf/clear :route id)"
    (is (some? (get (ns-publics 're-frame.core) 'clear))
        "rf/clear is the one registrar inverse"))
  ;; current-url's canonical home is one level down: there is no
  ;; `re-frame.routing` alias, and `re-frame.routing.history` is where
  ;; it lives. Pinned in the deftest below with its own control.
  (testing "current-url's canonical home is re-frame.routing.history"
    (is (some? (get (ns-publics 're-frame.routing.history) 'current-url))
        "re-frame.routing.history/current-url is the canonical home")))

;; ===========================================================================
;; The read/link edge. A `current-url` alias would re-export
;; `history-url-strategy`'s own `:decode` under a general name; a
;; `route-sub-fn` alias would publish a registration detail the facade
;; registers one screen away.
;;
;; SCOPE NOTE, so a reader does not mistake this pin for the whole
;; claim: `ns-publics` on the JVM can only speak for the JVM. Two of the
;; absent names would sit inside `#?(:cljs ...)` arms — a routing-ns
;; `route-link` def and a `route-link-render` alias — so neither could be
;; in this map on the JVM, and a nil assertion for either here would be
;; VACUOUSLY green: it would read identically whether or not the name exists.
;; That is exactly the shape the controls below exist to refuse, so they are
;; deliberately NOT listed in the `gone` vector. Their CLJS side is pinned
;; where it can be seen: nothing dereferences either name (the call sites
;; use `rf.routing.link/route-link-render`, its home), and
;; `route_link_cljs_test` renders through the registered `:route/link`
;; view. `route-link-render-ssr` is the cross-platform control below — it is
;; a real `.cljc` publication, so its `some?` leg does bite on the JVM.
;; ===========================================================================

(deftest trimmed-routing-read-edge-is-gone-rf2-kuky-36
  (testing "current-url and route-sub-fn are not re-frame.routing exports"
    (let [owning (ns-publics 're-frame.routing)]
      (doseq [gone '[current-url route-sub-fn]]
        (is (nil? (get owning gone))
            (str "re-frame.routing/" gone " is absent — no alias, no shim")))
      ;; Positive controls: the namespace IS loaded and the neighbouring
      ;; exports that legitimately live there DO resolve, so the nils above
      ;; mean "absent" rather than "namespace never loaded".
      ;; `clear-route` is deliberately NOT in this control list: there is no
      ;; such re-export either — route removal is `(rf/clear :route id)`.
      (doseq [kept '[route-link-render-ssr match-url route-url
                     history-url-strategy hash-url-strategy with-base-path]]
        (is (some? (get owning kept))
            (str "control — re-frame.routing/" kept " IS published")))))
  (testing "the aliased surfaces are reachable at their real homes"
    (is (some? (get (ns-publics 're-frame.routing.history) 'current-url))
        "current-url lives in re-frame.routing.history")
    (is (some? (get (ns-publics 're-frame.routing.subs) 'route-sub-fn))
        "route-sub-fn lives in re-frame.routing.subs")))

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

(deftest dormant-routing-late-bind-hooks-are-unpublished-rf2-sy7zr
  (testing "routing publishes no hook for current-url, match-url or route-url"
    (doseq [hook [:routing/current-url :routing/match-url :routing/route-url]]
      (is (nil? (rf.late-bind/get-fn hook))
          (str hook " is unpublished — core has no wrapper to late-bind to")))
    ;; :routing/clear-route IS published — (rf/clear :route id) consumes it.
    (is (some? (rf.late-bind/get-fn :routing/clear-route))
        ":routing/clear-route IS published — the (rf/clear :route id) hook")
    ;; Positive control: routing IS loaded and DOES publish its live hooks, so
    ;; the nils above are real absences rather than an unloaded artefact.
    (is (some? (rf.late-bind/get-fn :routing/reg-route))
        "control — :routing/reg-route IS published (the routing artefact is loaded)")
    (is (some? (rf.late-bind/get-fn :routing/route-link))
        "control — :routing/route-link IS published")))
