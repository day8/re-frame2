(ns re-frame.routing-test-support
  "Shared JVM test fixtures + helpers for the routing artefact's split
  concern-test namespaces (rf2-u8qe7y finding 3).

  The routing implementation is split into per-concern siblings under
  `re-frame.routing.*` (see `re-frame.routing`'s docstring). The JVM test
  surface mirrors that split: `routing-registry-test`,
  `routing-navigation-test`, `routing-can-leave-test`,
  `routing-nav-token-test`, `routing-scroll-test`, `routing-url-bound-test`,
  `routing-subs-test`, `routing-on-match-error-test`, and the pure
  `routing-plan-test`. Each requires this namespace for the shared
  `reset-runtime` fixture so the registrar/runtime reset + façade `:reload`
  recovery lives in ONE place rather than copied per file.

  Per the long-established consumer-test pattern: `reset-runtime` wipes the
  registrar (`clear-all!`), re-inits the plain-atom substrate, and
  `(require 're-frame.routing :reload)` re-runs the façade's
  `reg-event-fx` / `reg-fx` / `reg-sub` / hook / listener wires on the
  fresh registrar. The `:rf.test/simulate-http-resolution` fixture event
  (`re-frame.routing.test-support`) and `re-frame.ssr`'s ns-load
  registrations are re-seated the same way."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime
  "`:each` fixture: wipe the registrar + runtime state, re-init the
  plain-atom substrate, and re-`require` the routing / ssr / test-support
  namespaces with `:reload` so their ns-load-time registrations resurrect
  after `(registrar/clear-all!)`."
  [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`, and
  ;; routing/nav fxs now require a carried frame stamp. Register `:rf/default`
  ;; explicitly as the conventional single app frame. URL ownership is now an
  ;; EXPLICIT declaration (no `:rf/default`-owns-by-default floor — `url-owner-
  ;; frame-id` returns nil from absence), so this suite's URL-owning frame opts
  ;; in via `{:url-bound? true}`; the body runs with `:rf/default` pinned as
  ;; the ambient scope (the carried-invariant equivalent of wrapping every
  ;; test in `(with-frame :rf/default …)`). Explicit `{:frame …}` opts and
  ;; inner `with-frame`/`reg-frame` in the bodies still win.
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "Routing-suite default app frame (explicit URL owner)."})
  ;; Framework events / fx (routing.cljc, ssr.cljc) are registered at
  ;; ns-load; clear-all! wiped them. Reload to resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; rf2-dbiv8: re-seat the test-only `:rf.test/simulate-http-resolution`
  ;; fixture event after clear-all! (it lives in the test-support ns, not
  ;; the production façade).
  (require 're-frame.routing.test-support :reload)
  (routing/reset-counters!)
  ;; rf2-1hncp2: the scroll-position cache is a module-level host atom (not
  ;; runtime-db), so `clear-all!` / frame reset does not touch it — drop it
  ;; explicitly so a saved position never leaks across tests.
  (routing/reset-scroll-cache!)
  (rf/with-frame :rf/default
    (test-fn)))

(defn with-stub-validator
  "Install a tiny stub validator + explainer that interprets schemas as
  Clojure predicates `(fn [v] truthy?)`. Lets the schema-validation tests
  assert the validation path without dragging in Malli / spec.alpha.
  Returns a cleanup fn for the caller to invoke.

  Captures the prior bundle through the encapsulated
  `snapshot-schema-fns` / `restore-schema-fns!` pair (rf2-l4ljvr) rather
  than reaching the raw `validator-fn` / `explainer-fn` atoms — the
  snapshot also preserves the printer, so the cleanup restores the full
  prior bundle."
  []
  (let [snap     (schemas/snapshot-schema-fns)
        validate (fn [schema value] (boolean (schema value)))
        explain  (fn [_schema value] {:reason :stub-explainer :value value})]
    (schemas/set-schema-fns! {:validate validate :explain explain})
    (fn [] (schemas/restore-schema-fns! snap))))

(defn over-cap-url
  "Build a `/search?...` URL one unique query-key OVER
  `routing/default-max-decoded-keys` — the smallest URL that trips the
  keyword-interning DoS guard's throw (rf2-3k3o7 / rf2-6t1xb)."
  []
  (let [n (inc routing/default-max-decoded-keys)
        q (str/join "&" (map #(str "k" % "=v") (range n)))]
    (str "/search?" q)))
