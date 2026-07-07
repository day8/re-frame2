(ns re-frame.adapter.react-shared-suite-tests
  "Compile-time deftest generator for the per-adapter React-shared entry
  files (UIx, Helix) — rf2-6hphn.

  WHY THIS EXISTS. The parameterised shared suite
  (`re-frame.adapter.react-shared-suite`, rf2-sx77q + rf2-p4736) made
  every spine-shared assertion live ONCE as an `assert-*` defn — but the
  per-adapter ENTRY files
  (`uix_react_shared_cljs_test.cljs`,
  `helix_react_shared_cljs_test.cljs`) still hand-paired ~50 `(deftest
  name (suite/assert-name cfg))` lines each. Any new shared assertion
  required two hand-copied edits; the failure mode was silent
  single-adapter coverage if one entry file was forgotten. This macro
  closes the residual drift surface: the test list lives ONCE here and
  both entry files reduce to a single `(define-react-shared-suite-tests!
  cfg)` call.

  WHY A .clj MACRO. The CLJS test files consume this via
  `:require-macros`. The pattern mirrors
  `re-frame.conformance-fixtures` (the conformance-corpus compile-time
  loader) — a sibling `.clj` ns in `core/test/` whose forms are inlined
  into the .cljs caller's bytecode at compile time. No runtime cost; no
  new build wiring required (the file lives under a source-path already
  on the classpath).

  GREP DISCOVERABILITY. `grep -rn 'deftest <name>'` on the codebase will
  NOT find a literal deftest form for the suite-forwarders (they are
  generated at expansion time). To preserve discoverability the
  generated forms appear in this file's `test-specs` literal — grep
  `deftest assert-xyz` against `react_shared_suite.cljs` finds the
  assertion body (the actual source of truth), and grep `assert-xyz`
  against this file finds the deftest-name pairing. The entry files
  carry a header comment listing the generated test names so a code
  reader landing in `uix_react_shared_cljs_test.cljs` sees the surface
  at a glance.

  ADDING A NEW SHARED ASSERTION.

    1. Add `assert-foo` to `react_shared_suite.cljs`.
    2. Append `[foo assert-foo]` (or with a section-comment line) to
       `test-specs` below.
    3. Both UIx and Helix pick the new test up automatically on next
       compile — no entry-file edits, structurally no drift."
  (:refer-clojure :exclude [test-specs]))

;; ---------------------------------------------------------------------------
;; Test-spec literal — the single source of truth for which suite-fns are
;; forwarded into deftest forms. Section markers preserve the cluster
;; layout the entry files used to carry, so a reader can scan the surface
;; at a glance. `:section` rows are documentation only; `:test` rows emit
;; a deftest.
;;
;; The clusters mirror `react_shared_suite.cljs`'s in-source ordering, so a
;; suite-source diff and this spec-list diff align line-for-line.
;; ---------------------------------------------------------------------------

(def ^:private test-specs
  [{:section "dispose lifecycle (Spec 006)"}
   {:test 'dispose-clears-hiccup-emitter
    :fn   'assert-dispose-clears-hiccup-emitter}
   {:test 'dispose-clear-warn-idempotent
    :fn   'assert-clear-warn-idempotent-post-dispose}
   {:test 'dispose-post-delegation-throws
    :fn   'assert-post-dispose-delegation-throws}
   {:test 'dispose-idempotent-no-roots
    :fn   'assert-dispose-idempotent-no-roots}
   {:test 'dispose-clears-sub-caches
    :fn   'assert-dispose-clears-sub-caches}
   {:test 'dispose-walk-best-effort
    :fn   'assert-dispose-walk-best-effort}

   {:section "source-coord DOM stamping (Spec 006)"}
   {:test 'source-coord-annotates-dom-root
    :fn   'assert-source-coord-annotates-dom-root}
   {:test 'source-coord-merges-with-attrs
    :fn   'assert-source-coord-merges-with-attrs}
   {:test 'source-coord-user-supplied-wins
    :fn   'assert-source-coord-user-supplied-wins}
   {:test 'source-coord-fragment-exempt
    :fn   'assert-source-coord-fragment-exempt}
   {:test 'source-coord-format-shape
    :fn   'assert-source-coord-format-shape}

   {:section "view-id (data-rf-view) stamping (Spec 006)"}
   {:test 'view-id-tags-dom-root
    :fn   'assert-view-id-tags-dom-root}
   {:test 'view-id-fragment-exempt
    :fn   'assert-view-id-fragment-exempt}
   {:test 'view-id-user-supplied-wins
    :fn   'assert-view-id-user-supplied-wins}
   {:test 'wrap-view-injects-explicit-coords
    :fn   'assert-wrap-view-injects-explicit-coords}

   {:section "React :key parity (rf2-pt0u2 — follow-up to rf2-1anbp)"}
   {:test 'reg-view-react-key-preserved
    :fn   'assert-reg-view-react-key-preserved}

   {:section "void-element unmount-sentinel (rf2-ghfkkk)"}
   {:test 'void-root-view-sentinel-is-fragment-sibling
    :fn   'assert-void-root-view-sentinel-is-fragment-sibling}

   {:section "frame-context corrupted (Spec 009)"}
   {:test 'frame-context-corrupted
    :fn   'assert-frame-context-corrupted}

   {:section "frame-provider branches (rf2-7kjz8 — folded from helix_frame_provider_children + uix_frame_provider_branches)"}
   {:test 'frame-provider-missing-frame-raises-no-frame-context
    :fn   'assert-frame-provider-missing-frame-raises-no-frame-context}
   {:test 'frame-provider-nil-frame-raises-no-frame-context
    :fn   'assert-frame-provider-nil-frame-raises-no-frame-context}
   {:test 'frame-provider-named-frame-preserved
    :fn   'assert-frame-provider-named-frame-preserved}
   {:test 'frame-provider-single-child-coerced-to-vector
    :fn   'assert-frame-provider-single-child-coerced-to-vector}
   {:test 'frame-provider-sequential-children-preserved
    :fn   'assert-frame-provider-sequential-children-preserved}
   {:test 'frame-provider-js-array-children-spread
    :fn   'assert-frame-provider-js-array-children-spread}

   {:section "warn-once fires-once (Spec 006)"}
   {:test 'warn-once-fires-once
    :fn   'assert-warn-once-fires-once}
   {:test 'warn-once-per-id-not-global
    :fn   'assert-warn-once-per-id-not-global}

   {:section "write-after-destroy guard (rf2-ft2b)"}
   {:test 'write-after-destroy-guard
    :fn   'assert-write-after-destroy-guard}

   {:section "render-time parity (rf2-v1y7 / *_parity)"}
   {:test 'parity-view-re-register-rerender
    :fn   'assert-view-re-register-causes-rerender}
   {:test 'parity-current-render-key-anon
    :fn   'assert-current-render-key-anonymous-fallback}
   {:test 'parity-wrap-view-callable
    :fn   'assert-wrap-view-callable-dispatches-to-user-fn}

   {:section "reg-event metadata-map :interceptors superset (rf2-bpmszk / *_events)"}
   {:test 'events-meta-interceptors-threads-chain
    :fn   'assert-reg-event-meta-interceptors-threads-the-chain}
   {:test 'events-positional-vector-rejected
    :fn   'assert-reg-event-positional-vector-rejected}

   {:section "render-to-string + late-bind chain (*_render_to_string)"}
   {:test 'rts-throws-with-no-emitter
    :fn   'assert-render-to-string-throws-with-no-emitter}
   {:test 'rts-returns-html-after-install
    :fn   'assert-render-to-string-returns-html-after-direct-install}
   {:test 'rts-published-through-chain
    :fn   'assert-set-hiccup-emitter-published-through-chain}

   {:section "late-bind hook publication set (*_late_bind_publication)"}
   {:test 'late-bind-publishes-expected-set
    :fn   'assert-adapter-publishes-expected-hook-set}
   {:test 'late-bind-cross-checked-directory
    :fn   'assert-adapter-hooks-cross-checked-against-directory}

   {:section "copied / wrapped adapter map routes to live hooks (rf2-dkl5z1)"}
   {:test 'copied-adapter-map-routes-to-live-hooks
    :fn   'assert-copied-adapter-map-routes-to-live-hooks}

   {:section "chained clear-warn-once-caches! (*_clear_warn_once_chain)"}
   {:test 'clear-warn-chain-empties-cache
    :fn   'assert-chained-clear-warn-once-empties-cache}
   {:test 'clear-warn-direct-resets-cache
    :fn   'assert-clear-warned-non-dom-roots-resets-directly}

   {:section "routing pipeline (Spec 012 / *_routing)"}
   {:test 'routing-handle-url-change
    :fn   'assert-routing-handle-url-change}
   {:test 'routing-multi-frame
    :fn   'assert-routing-multi-frame}

   {:section "headless runtime slice (*_runtime)"}
   {:test 'runtime-dispatch-sync
    :fn   'assert-dispatch-sync}
   {:test 'runtime-sub-chain
    :fn   'assert-sub-chain}
   {:test 'runtime-with-frame
    :fn   'assert-with-frame-binds-current-frame}
   {:test 'runtime-capture-frame
    :fn   'assert-capture-frame-survives-scope-unwind}
   {:test 'runtime-multi-frame-isolation
    :fn   'assert-multi-frame-state-isolation}
   {:test 'runtime-reactive-sub
    :fn   'assert-reactive-sub-tracks-changes}
   {:test 'runtime-sub-hot-reload
    :fn   'assert-sub-hot-reload}
   {:test 'runtime-machine-transition
    :fn   'assert-machine-transition}
   {:test 'runtime-sub-exception-recovers
    :fn   'assert-sub-exception-recovers-to-nil}

   {:section ":rf.view/rendered op (rf2-25zo2 / *_view_rendered_op)"}
   {:test 'view-rendered-fires-on-render
    :fn   'assert-rf-view-rendered-fires-on-render}
   {:test 'view-rendered-attribution-in-cascade
    :fn   'assert-rf-view-rendered-attribution-in-cascade}
   {:test 'view-rendered-carries-render-args
    :fn   'assert-rf-view-rendered-carries-render-args}
   {:test 'view-rendered-render-args-elided
    :fn   'assert-rf-view-rendered-render-args-elided}

   {:section "make-derived-value per-arity (rf2-eoy63 / *_make_derived_value_arity)"}
   {:test 'derived-value-arities
    :fn   'assert-derived-value-arities}

   {:section "derived-value watch-baseline (rf2-66hb / *_derived_value_baseline)"}
   {:test 'derived-baseline-projections
    :fn   'assert-derived-baseline-projections}
   {:test 'derived-baseline-sequence
    :fn   'assert-derived-baseline-sequence}
   {:test 'derived-baseline-multi-source
    :fn   'assert-derived-baseline-multi-source}

   {:section "two-partition projection-equality invalidation (EP-0001 decision #7 / rf2-0sr0ai)"}
   {:test 'partition-runtime-only-commit-no-rerun-app-subs
    :fn   'assert-runtime-only-commit-does-not-rerun-app-subs}
   {:test 'partition-app-only-commit-no-rerun-runtime-subs
    :fn   'assert-app-only-commit-does-not-rerun-runtime-subs}
   {:test 'partition-real-change-propagates-to-its-subs
    :fn   'assert-real-partition-change-propagates-to-its-subs}

   {:section "derived-value duplicate-source disposal (rf2-he7se finding 2)"}
   {:test 'derived-dispose-releases-duplicate-source-watches
    :fn   'assert-derived-dispose-releases-duplicate-source-watches}

   {:section "managed HTTP (Spec 014 / *_http_managed)"}
   {:test 'http-canned-success-default-reply
    :fn   'assert-http-canned-success-default-reply}
   {:test 'http-canned-failure-on-failure
    :fn   'assert-http-canned-failure-on-failure}
   {:test 'http-canned-success-on-success
    :fn   'assert-http-canned-success-on-success}
   {:test 'http-silenced-reply
    :fn   'assert-http-silenced-reply}
   {:test 'http-with-managed-request-stubs
    :fn   'assert-http-with-managed-request-stubs}
   {:test 'http-with-managed-request-stubs-failure
    :fn   'assert-http-with-managed-request-stubs-failure}
   {:test 'http-multi-frame-reply-isolation
    :fn   'assert-http-multi-frame-reply-isolation}

   {:section "Cross-Spec interactions (headless subset / *_cross_spec)"}
   {:test 'xspec-frame-destroy-active-machines
    :fn   'assert-xspec-frame-destroy-with-active-machines}
   {:test 'xspec-machine-microstep-subscribe
    :fn   'assert-xspec-machine-microstep-subscribe}
   {:test 'xspec-boot-order-adapter-ready
    :fn   'assert-xspec-boot-order-adapter-ready}
   {:test 'xspec-machines-under-ssr
    :fn   'assert-xspec-machines-under-ssr}
   {:test 'xspec-route-not-found-ssr
    :fn   'assert-xspec-route-not-found-ssr}
   {:test 'xspec-headless-frame-resolution
    :fn   'assert-xspec-headless-frame-resolution-chain}
   {:test 'xspec-machine-action-throws
    :fn   'assert-xspec-machine-action-throws}
   {:test 'xspec-machine-fx-handler-throws
    :fn   'assert-xspec-machine-fx-handler-throws}
   {:test 'xspec-hot-reload-machine-action
    :fn   'assert-xspec-hot-reload-machine-action}
   {:test 'xspec-dispatch-sync-from-handler
    :fn   'assert-xspec-dispatch-sync-from-handler-raises}
   {:test 'xspec-time-travel-revert
    :fn   'assert-xspec-time-travel-revert}
   {:test 'xspec-server-error-projection
    :fn   'assert-xspec-server-error-projection}
   {:test 'xspec-hot-reload-sub-mid-cascade
    :fn   'assert-xspec-hot-reload-sub-mid-cascade}
   {:test 'xspec-portable-story-fx-override
    :fn   'assert-xspec-portable-story-fx-override}
   {:test 'xspec-adapter-already-installed
    :fn   'assert-xspec-adapter-already-installed}

   {:section "public surface + adapter-map shape (rf2-6c2sr / rf2-ynjts.4 — folded from uix_public_surface + helix_public_surface, rf2-6j09b)"}
   {:test 'public-vars-present-and-callable
    :fn   'assert-public-vars-present-and-callable}
   {:test 'public-vars-distinct-fns
    :fn   'assert-public-vars-distinct-fns}
   {:test 'public-flush-views-returns-nil-and-node-safe
    :fn   'assert-flush-views-returns-nil-and-is-node-safe}
   {:test 'public-adapter-map-nine-fn-contract
    :fn   'assert-adapter-map-satisfies-nine-fn-contract}])

(def ^{:doc "Public seq of {:test name :fn assert-fn} maps a code reader
  can `(require)` from a JVM REPL to list every generated test name.
  Section rows are filtered out."}
  generated-test-specs
  (filterv :test test-specs))

(defmacro define-react-shared-suite-tests!
  "Emit one `cljs.test/deftest` per row in `test-specs`, forwarding the
  per-adapter `cfg` map to the matching `re-frame.adapter.react-shared-suite/assert-*`
  defn.

  Usage (from a `.cljs` test entry file):

      (:require-macros
        [re-frame.adapter.react-shared-suite-tests
         :refer [define-react-shared-suite-tests!]])

      (def ^:private cfg { ... })

      (define-react-shared-suite-tests! cfg)

  The macro qualifies the assert-fn calls against
  `re-frame.adapter.react-shared-suite` so the entry file only needs
  to `:require` that suite ns (under any alias, or none — the qualifier
  is the full ns symbol)."
  [cfg-sym]
  `(do
     ~@(for [{:keys [test fn]} test-specs
             :when             test]
       `(cljs.test/deftest ~test
          (~(symbol "re-frame.adapter.react-shared-suite" (name fn)) ~cfg-sym)))))
