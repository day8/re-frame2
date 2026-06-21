(ns re-frame.ssr.test-fixture
  "Shared `:each` reset fixture for ssr-artefact JVM tests. Per rf2-i3qc0
  (audit rf2-asmj1 §TC5).

  Pre-rf2-i3qc0 the same 7-12-line `reset-runtime` body was duplicated
  across seven test files (ssr_end_to_end_test, ssr_head_test,
  ssr_request_cofx_test, ssr_compatibility_checks_test,
  ssr_source_coord_test, ssr_teardown_load_test, ssr_conformance_test).
  Each copy reset the same registrar / frame / flows / schemas /
  side-channel atoms and reloaded the same `routing` / `ssr` /
  `machines` namespaces. Drifting copies (one resets `head-snapshots`,
  another doesn't; one declares the fn `^:private`, another doesn't)
  invited the kind of cross-test-bleed that the side-channel atoms
  exist to prevent.

  The canonical reset is here. Test namespaces call
  `(use-fixtures :each tf/reset-runtime)` and inherit a uniform reset
  semantics that mirror what the runtime's per-request frame teardown
  hook (`re-frame.ssr/on-frame-destroyed!`) does at end-of-request:
  every per-frame side-channel slot is cleared.

  ## What gets reset

  Registrar + frame state — `(registrar/clear-all!)`,
  `(reset! frame/frames {})`, `(flows/reset-flows!)`,
  `(schemas/clear-schemas-by-frame!)`.

  SSR side-channel atoms (Spec 011 §Per-request frame teardown). All
  three slots are keyed by frame-id; stale entries from prior tests
  would otherwise bleed process-wide:
    - `re-frame.ssr.request/request-slots`        — the active HTTP request
    - `re-frame.ssr.response/response-slots`      — the HTTP response accumulator
    - `re-frame.ssr.error-listener/pending-error-traces`
                                                  — per-frame buffer of error trace events
    - `re-frame.ssr.head/head-snapshots`          — per-frame head-model snapshot

  Adapter — `(rf/init! ssr/adapter)` installs the SSR-aware adapter map.

  Namespace-load-time registrations — clear-all! wiped the
  registrations re-frame.routing / re-frame.ssr / re-frame.ssr.head /
  re-frame.machines installed at ns-load. `:reload` re-evaluates the
  ns-body so `:rf/hydrate`, `:rf.route/navigate`, `:rf.server/*` fxs,
  the `:rf.server/request` cofx, the head late-bind hooks, and the
  machine fxs all resurrect.

  ## Why expose this rather than duplicate

  - One source of truth for the reset shape — drift between test files
    is impossible by construction.
  - Adding a new side-channel atom (e.g. when a future rf2-* introduces
    one) touches exactly one file.
  - The reset matches the production teardown shape closely; any
    divergence is an immediate signal that either the tests are
    cheating or the teardown is incomplete."
  (:require [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.head :as head]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]))

(defn reset-runtime
  "The canonical `:each` fixture for ssr-artefact JVM tests. Wipes the
  registrar, every per-frame side-channel atom, and re-installs the
  ns-load-time registrations that `clear-all!` removed.

  Call shape — `(use-fixtures :each reset-runtime)`."
  [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; SSR side-channel atoms — direct refs into the producing sub-ns
  ;; rather than the (private) façade aliases. Same atoms either way;
  ;; this avoids the `(resolve ...)` reflective dance the legacy
  ;; per-file fixtures used to reach the ^:private façade vars.
  (reset! request/request-slots {})
  (reset! response/response-slots {})
  (reset! error-listener/pending-error-traces {})
  (reset! head/head-snapshots {})
  ;; Per rf2-4gvb4 — the flows artefact's per-frame `last-inputs` memo
  ;; table is reset through the public `flows/reset-last-inputs!` seam
  ;; (the atom itself is now private to the flows artefact). Spec 013
  ;; §Flow re-evaluation trigger.
  (flows/reset-last-inputs!)
  (rf/init! ssr/adapter)
  ;; Namespace-load-time registrations get wiped by clear-all!; reload
  ;; so :rf/hydrate, :rf.route/navigate, :rf.server/* fxs, the
  ;; :rf.server/request cofx, the head late-bind hooks, AND the
  ;; machine fxs all resurrect between tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.ssr.head :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`, and
  ;; framework operation + registration surfaces (dispatch / reg-flow /
  ;; reg-app-schema / current-frame-id / …) now require a carried frame
  ;; stamp. Register `:rf/default` explicitly and pin it as the body's
  ;; ambient scope — the carried-invariant equivalent of wrapping every
  ;; test in `(with-frame :rf/default …)`. SSR tests that drive their own
  ;; per-request server frames re-bind via an inner `with-frame` / pass an
  ;; explicit `{:frame …}`; both win over this ambient scope. A top-level
  ;; `reg-frame …:initial-events` still drain synchronously — the lifecycle
  ;; async/sync split keys off `*handler-scope*` (a real cascade), not
  ;; this ambient scope.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))
