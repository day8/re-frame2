(ns re-frame.hicasso.substrate-react-shared-cljs-test
  "Hicasso's entry point into the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`).

  `re-frame.hicasso.substrate` assembles its whole contract surface out of
  `spine/make-react-spine` and `spine/make-react-adapter`, which is precisely
  the shape the shared suite was parameterised for: its own docstring says
  *any future React-hook adapter picks up the whole surface by adding one
  entry file like this one*. So every spine-shared behaviour — the dispose
  MUSTs, source-coord and view-id stamping, the frame-context-corrupted
  diagnostic, warn-once, the write-after-destroy guard, `render-to-string`
  and its late-bind chain, the hook publication set and its directory
  cross-check, `make-derived-value`'s per-arity and watch-baseline
  contracts, the two-partition invalidation law, managed HTTP, the headless
  cross-Spec subset and the public-surface guard — is asserted here against
  the Hicasso adapter without a line of it being written twice.

  Roughly fifty `deftest` forwarders are generated from the `test-specs`
  literal in `re-frame.adapter.react-shared-suite-tests`; that macro ns owns
  the canonical list, and a new shared assertion appears here on the next
  compile with no edit to this file.

  WHAT THIS FILE DOES NOT COVER, and why that is not a gap: Hicasso's own
  authoring surface. UIx's remaining suites are substrate-NOTATION tests
  (controlled-input defaults, `defui`-shaped providers and hooks) and
  Hicasso's notation is covered at length by its own suites under
  `test/re_frame/hicasso/`. What this file adds is the SUBSTRATE half.

  ## The `:public-surface` rows read the spine map, not re-exported Vars

  Every other React-shaped adapter re-exports its spine surfaces as public
  Vars, and the guard's stated job is to catch one being dropped, renamed or
  cross-wired. Hicasso re-exports NONE of them, deliberately — a body reads
  through `h/sub` and the collector, and a second read path would be a second
  commit discipline — so the rows below read `substrate/spine-fns` directly.
  The assertion that survives the difference is the one that matters here:
  the spine produced all six, each is fn-shaped, and no two are the same
  object (a mis-keyed `:use-current-frame` ← `:use-subscribe` is a live core
  bug class, and it trips exactly as it would for UIx).

  The roster is Hicasso's own (`:public-surface-keys`), not a cross-adapter
  constant: UIx's is the eight fns `spec/api-manifest.edn` rows for it, and
  the two sets differ. Neither names the spine's warn-once clear thunk —
  that seam is internal and is driven through the chained
  `:adapter/clear-warn-once-caches!` hook the reset fixture fires
  (rf2-6r9j.36).

  `:frame-provider` is read back through the contract slot rather than off a
  Var, because that is the only route Hicasso publishes it by.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [use-fixtures]]
            [re-frame.adapter.react-shared-suite]
            [re-frame.hicasso.substrate :as substrate]
            [re-frame.test-support :as test-support])
  (:require-macros
   [re-frame.adapter.react-shared-suite-tests
    :refer [define-react-shared-suite-tests!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter}))

(def ^:private cfg
  {:adapter          substrate/adapter
   :substrate-kw     :hicasso
   :name             "Hicasso"
   :producer-ns      're-frame.hicasso.substrate
   :wrap-view        (:wrap-view substrate/spine-fns)
   :set-emitter!     (:set-hiccup-emitter! substrate/spine-fns)
   :render-to-string (:render-to-string substrate/adapter)
   :public-surface-keys [:set-hiccup-emitter! :use-current-frame :frame-provider
                         :use-subscribe :flush-views! :wrap-view]
   :public-surface   {:set-hiccup-emitter! (:set-hiccup-emitter! substrate/spine-fns)
                      :use-current-frame   (:use-current-frame substrate/spine-fns)
                      ;; The contract slot IS the publication route here: the
                      ;; frame-keyword arg is ignored (the frame lives in the
                      ;; Provider's `:value` at render time), so passing nil
                      ;; asks for the component and nothing else.
                      :frame-provider      ((:register-context-provider substrate/adapter) nil)
                      :use-subscribe       (:use-subscribe substrate/spine-fns)
                      :flush-views!        (:flush-views! substrate/spine-fns)
                      :wrap-view           (:wrap-view substrate/spine-fns)}})

;; Emit one (deftest name (re-frame.adapter.react-shared-suite/assert-name cfg))
;; per row in `react-shared-suite-tests/test-specs`.
(define-react-shared-suite-tests! cfg)
