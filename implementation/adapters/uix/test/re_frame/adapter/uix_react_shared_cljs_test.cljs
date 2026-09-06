(ns re-frame.adapter.uix-react-shared-cljs-test
  "UIx entry-point for the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`, rf2-sx77q).

  UIx wires its entire public surface out of the
  `spine/make-react-spine` factory, so every spine-shared behaviour is
  asserted once in the shared suite and forwarded here with the UIx
  config. The suite dates from when UIx and Helix were twin spine
  adapters (Helix removed at S7/W13, rf2-d6epb) and stays parameterised:
  any future React-hook adapter picks up the whole surface by adding one
  entry file like this one.

  Per rf2-6hphn the ~50 deftest forwarders (once hand-paired with the
  Helix twin entry file) are generated from a single `test-specs`
  literal in `re-frame.adapter.react-shared-suite-tests` (a `.clj`
  compile-time macro ns, mirroring the conformance-fixtures pattern).
  The entry file reduces to: an adapter `:require`, a fixture, a `cfg`
  map, and one macro call. Adding a new shared assertion is one edit —
  append `[name assert-name]` to `test-specs` and every entry file picks
  it up on next compile.

  Coverage forwarded (see `test-specs` in the macro ns for the
  canonical, in-source list — grep-discoverable from there):
  dispose MUSTs 1–4 + best-effort poison tolerance (G3), source-coord
  DOM stamping incl. the format-shape split (G2), view-id tagging,
  frame-context-corrupted (G4), warn-once fire-once + per-id (G5), the
  write-after-destroy guard (rf2-sx77q); plus, per rf2-p4736, the rest
  of the folded twin clusters: render-time parity (hot-reload
  re-register, anonymous render-key, wrap-view callable), reg-event
  metadata-interceptor warnings, render-to-string + late-bind chain
  wiring, the late-bind hook publication set + directory cross-check,
  chained clear-warn-once-caches!, the routing pipeline, the headless
  runtime slice, :rf.view/rendered, make-derived-value per-arity +
  watch-baseline, managed-HTTP, the cross-Spec headless subset, and
  (rf2-6j09b) the public-surface guard: presence/kind/cross-wiring
  distinctness of the eight re-exported Vars + the adapter-map :kind +
  contract-fn shape, folded from the former uix_public_surface_cljs_test.cljs.

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
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite]
            [re-frame.test-support :as rf.test-support])
  (:require-macros
   [re-frame.adapter.react-shared-suite-tests
    :refer [define-react-shared-suite-tests!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

;; rf2-7kjz8 / rf2-z7hfp / rf2-7kii2 — the frame-provider branch assertions
;; now target the substrate-agnostic spine core
;; (`build-frame-provider-element`) directly, so no `:frame-provider` cfg
;; key is needed here. The native-shell-under-`$` behaviour (including the
;; idiomatic trailing-children call shape) is pinned by the use-subscribe
;; DOM twin + the trailing-children regression test (folded from the prior
;; per-adapter uix_frame_provider_branches_cljs_test.cljs).
(def ^:private cfg
  {:adapter          rf.adapter.uix/adapter
   :substrate-kw     :uix
   :name             "UIx"
   :producer-ns      're-frame.adapter.uix
   :wrap-view        rf.adapter.uix/wrap-view
   :set-emitter!     rf.adapter.uix/set-hiccup-emitter!
   :render-to-string (:render-to-string rf.adapter.uix/adapter)
   ;; rf2-6j09b / rf2-6r9j.36 — the public Vars the suite's public-surface
   ;; guard asserts (presence/kind/distinctness). Substrate-specific because
   ;; each adapter's re-exports are distinct objects the suite cannot name
   ;; directly; folded from the former uix_public_surface_cljs_test.cljs.
   ;;
   ;; The roster IS `spec/api-manifest.edn`'s `re-frame.adapter.uix` rows
   ;; minus `adapter` — eight supported fns; `adapter` is checked by the
   ;; suite's adapter-map assertion off the `:adapter` key above. Keep the
   ;; two in step: a manifest row added here without a row there (or the
   ;; reverse) is the drift this roster exists to catch. It deliberately
   ;; does NOT name the spine's warn-once clear thunk — that is internal,
   ;; carries no manifest row, and is reached through the chained
   ;; `:adapter/clear-warn-once-caches!` hook (rf2-6r9j.36).
   :public-surface-keys [:set-hiccup-emitter! :use-current-frame :frame-provider
                         :frame-root :use-subscribe :use-frame :flush-views!
                         :wrap-view]
   :public-surface   {:set-hiccup-emitter! rf.adapter.uix/set-hiccup-emitter!
                      :use-current-frame   rf.adapter.uix/use-current-frame
                      :frame-provider      rf.adapter.uix/frame-provider
                      :frame-root          rf.adapter.uix/frame-root
                      :use-subscribe       rf.adapter.uix/use-subscribe
                      :use-frame           rf.adapter.uix/use-frame
                      :flush-views!        rf.adapter.uix/flush-views!
                      :wrap-view           rf.adapter.uix/wrap-view}})

;; Emit one (deftest name (re-frame.adapter.react-shared-suite/assert-name cfg))
;; per row in `react-shared-suite-tests/test-specs`. The macro ns owns
;; the single source of truth for the forwarder list; the entry file
;; inlines the generated deftests at compile time.
(define-react-shared-suite-tests! cfg)
