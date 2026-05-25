(ns re-frame.adapter.uix-react-shared-cljs-test
  "UIx entry-point for the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`, rf2-sx77q).

  UIx and Helix wire their entire public surface out of the same
  `spine/make-react-spine` factory, so every spine-shared behaviour is
  asserted once in the shared suite and forwarded here with the UIx
  config. This file is the structural guarantee that UIx and Helix never
  drift on the shared contracts: a gap on one is a gap on both because
  there is exactly one suite source.

  Per rf2-6hphn the ~50 deftest forwarders previously hand-paired
  between this file and `helix_react_shared_cljs_test.cljs` are now
  generated from a single `test-specs` literal in
  `re-frame.adapter.react-shared-suite-tests` (a `.clj` compile-time
  macro ns, mirroring the conformance-fixtures pattern). Both entry
  files reduce to: an adapter `:require`, a fixture, a `cfg` map, and
  one macro call. Adding a new shared assertion no longer requires two
  hand-copied edits — append `[name assert-name]` to `test-specs` and
  both adapters pick it up on next compile.

  Coverage forwarded (see `test-specs` in the macro ns for the
  canonical, in-source list — grep-discoverable from there):
  dispose MUSTs 1–4 + best-effort poison tolerance (G3), source-coord
  DOM stamping incl. the format-shape split (G2), view-id tagging,
  frame-context-corrupted (G4), warn-once fire-once + per-id (G5), the
  write-after-destroy guard (rf2-sx77q); plus, per rf2-p4736, the rest
  of the UIx/Helix twin clusters: render-time parity (hot-reload
  re-register, anonymous render-key, wrap-view callable), reg-event
  metadata-interceptor warnings, render-to-string + late-bind chain
  wiring, the late-bind hook publication set + directory cross-check,
  chained clear-warn-once-caches!, the routing pipeline, the headless
  runtime slice, :rf.view/rendered, make-derived-value per-arity +
  watch-baseline, managed-HTTP, and the cross-Spec headless subset.

  The async *current-frame*-across-dispatch contract (rf2-l5q3) is
  forwarded from a dedicated entry pair carrying a map-form fixture —
  `uix_dispatch_frame_capture_cljs_test.cljs` — because async tests
  require a {:before :after} fixture so :after lands after `done`.

  Remaining UIx twins NOT folded here (DOM/browser — they define
  substrate-specific component vars via `defui`/`$`): `after_render_dom`
  and `use_subscribe_dom`. Splitting those into the shared suite needs a
  node-vs-browser component-element parameterisation; tracked separately.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.adapter.react-shared-suite]
            [re-frame.test-support :as test-support])
  (:require-macros
   [re-frame.adapter.react-shared-suite-tests
    :refer [define-react-shared-suite-tests!]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

(def ^:private cfg
  {:adapter          uix-adapter/adapter
   :substrate-kw     :uix
   :name             "UIx"
   :producer-ns      're-frame.adapter.uix
   :wrap-view        uix-adapter/wrap-view
   :clear-warn!      uix-adapter/clear-warned-non-dom-roots!
   :set-emitter!     uix-adapter/set-hiccup-emitter!
   :render-to-string (:render-to-string uix-adapter/adapter)})

;; Emit one (deftest name (re-frame.adapter.react-shared-suite/assert-name cfg))
;; per row in `react-shared-suite-tests/test-specs`. The macro ns owns
;; the single source of truth for the forwarder list; both UIx and
;; Helix entry files inline the same generated deftests at compile time.
(define-react-shared-suite-tests! cfg)
