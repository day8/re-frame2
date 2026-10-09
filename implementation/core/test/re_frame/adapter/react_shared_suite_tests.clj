(ns re-frame.adapter.react-shared-suite-tests
  "Compile-time deftest generator for the per-adapter React-shared entry
  files (UIx, and fresco's React substrate). The list of forwarded
  `re-frame.adapter.react-shared-suite/assert-*` fns lives once, in
  `test-specs`, so each entry file is a single
  `(define-react-shared-suite-tests! cfg)` call and cannot drop a row.

  A `.clj` macro ns consumed through `:require-macros`, like
  `re-frame.conformance-fixtures`: the generated forms are inlined into the
  `.cljs` caller at compile time. The generated deftest names appear only in
  `test-specs` below, not as literal `deftest` forms.

  To add a shared assertion: add `assert-foo` to `react_shared_suite.cljs`
  and a `{:test 'foo :fn 'assert-foo}` row here.")

;; `:section` rows are documentation only; `:test` rows emit a deftest. The
;; clusters follow `react_shared_suite.cljs`'s source order.
(def ^:private test-specs
  [{:section "dispose lifecycle (Spec 006)"}
   {:test 'dispose-clears-hiccup-emitter
    :fn   'assert-dispose-clears-hiccup-emitter}
   {:test 'dispose-walk-best-effort
    :fn   'assert-dispose-walk-best-effort}

   {:section "source-coord DOM stamping (Spec 006)"}
   {:test 'source-coord-merges-with-attrs
    :fn   'assert-source-coord-merges-with-attrs}
   {:test 'source-coord-user-supplied-wins
    :fn   'assert-source-coord-user-supplied-wins}
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

   {:section "React DevTools display-name (Spec 006 item 1)"}
   {:test 'display-name-matches-render-measure
    :fn   'assert-display-name-matches-render-measure}

   {:section "React :key parity"}
   {:test 'reg-view-react-key-preserved
    :fn   'assert-reg-view-react-key-preserved}

   {:section "void-element unmount-sentinel"}
   {:test 'void-root-view-sentinel-is-fragment-sibling
    :fn   'assert-void-root-view-sentinel-is-fragment-sibling}

   {:section "frame-context corrupted (Spec 009)"}
   {:test 'frame-context-corrupted
    :fn   'assert-frame-context-corrupted}

   {:section "frame-provider branches"}
   {:test 'frame-provider-missing-frame-raises-no-frame-context
    :fn   'assert-frame-provider-missing-frame-raises-no-frame-context}
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

   {:section "render-time parity"}
   {:test 'parity-view-re-register-rerender
    :fn   'assert-view-re-register-causes-rerender}
   {:test 'parity-wrap-view-callable
    :fn   'assert-wrap-view-callable-dispatches-to-user-fn}

   {:section "render-to-string + late-bind chain"}
   {:test 'rts-throws-with-no-emitter
    :fn   'assert-render-to-string-throws-with-no-emitter}
   {:test 'rts-returns-html-after-install
    :fn   'assert-render-to-string-returns-html-after-direct-install}
   {:test 'rts-published-through-chain
    :fn   'assert-set-hiccup-emitter-published-through-chain}

   {:section "element-slot CLJS-data guard"}
   {:test 'render-rejects-cljs-data-render-tree
    :fn   'assert-render-rejects-cljs-data-render-tree}

   {:section "copied / wrapped adapter map routes to live hooks"}
   {:test 'copied-adapter-map-routes-to-live-hooks
    :fn   'assert-copied-adapter-map-routes-to-live-hooks}

   {:section "chained clear-warn-once-caches!"}
   {:test 'clear-warn-chain-empties-cache
    :fn   'assert-chained-clear-warn-once-empties-cache}

   {:section "with-frame through the spine-routed current-frame read"}
   {:test 'runtime-with-frame
    :fn   'assert-with-frame-binds-current-frame}

   {:section ":rf.view/rendered op"}
   {:test 'view-rendered-carries-render-args
    :fn   'assert-rf-view-rendered-carries-render-args}

   {:section "make-derived-value per-arity"}
   {:test 'derived-value-arities
    :fn   'assert-derived-value-arities}

   {:section "derived-value watch-baseline"}
   {:test 'derived-baseline-projections
    :fn   'assert-derived-baseline-projections}
   {:test 'derived-baseline-sequence
    :fn   'assert-derived-baseline-sequence}
   {:test 'derived-baseline-multi-source
    :fn   'assert-derived-baseline-multi-source}

   {:section "two-partition projection-equality invalidation (EP-0001 decision #7)"}
   {:test 'partition-runtime-only-commit-no-rerun-app-subs
    :fn   'assert-runtime-only-commit-does-not-rerun-app-subs}
   {:test 'partition-app-only-commit-no-rerun-runtime-subs
    :fn   'assert-app-only-commit-does-not-rerun-runtime-subs}
   {:test 'partition-real-change-propagates-to-its-subs
    :fn   'assert-real-partition-change-propagates-to-its-subs}

   {:section "schema-rejected candidate — zero sub notifications"}
   {:test 'schema-rejection-zero-sub-notifications
    :fn   'assert-schema-rejection-zero-sub-notifications}

   {:section "derived-value duplicate-source disposal"}
   {:test 'derived-dispose-releases-duplicate-source-watches
    :fn   'assert-derived-dispose-releases-duplicate-source-watches}

   {:section "managed HTTP (Spec 014)"}
   {:test 'http-multi-frame-reply-isolation
    :fn   'assert-http-multi-frame-reply-isolation}

   {:section "Cross-Spec interactions (headless subset)"}
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
   {:test 'xspec-portable-story-fx-override
    :fn   'assert-xspec-portable-story-fx-override}
   {:test 'xspec-adapter-already-installed
    :fn   'assert-xspec-adapter-already-installed}

   {:section "public surface + adapter-map shape"}
   {:test 'public-vars-present-and-callable
    :fn   'assert-public-vars-present-and-callable}
   {:test 'public-vars-distinct-fns
    :fn   'assert-public-vars-distinct-fns}
   {:test 'public-flush-views-returns-nil-and-node-safe
    :fn   'assert-flush-views-returns-nil-and-is-node-safe}
   {:test 'public-adapter-map-nine-fn-contract
    :fn   'assert-adapter-map-satisfies-nine-fn-contract}])

(defmacro define-react-shared-suite-tests!
  "Emit one `cljs.test/deftest` per row in `test-specs`, forwarding the
  per-adapter `cfg` map to the matching `re-frame.adapter.react-shared-suite/assert-*`
  defn. The calls are fully qualified, so the entry file only needs to
  `:require` the suite ns.

      (:require-macros
        [re-frame.adapter.react-shared-suite-tests
         :refer [define-react-shared-suite-tests!]])

      (define-react-shared-suite-tests! cfg)"
  [cfg-sym]
  `(do
     ~@(for [{:keys [test fn]} test-specs
             :when             test]
       `(cljs.test/deftest ~test
          (~(symbol "re-frame.adapter.react-shared-suite" (name fn)) ~cfg-sym)))))
