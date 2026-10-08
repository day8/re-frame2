(ns re-frame.adapter.uix-react-shared-cljs-test
  "UIx entry-point for the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`). UIx builds its whole public
  surface from `spine/make-react-spine`, so each spine-shared behaviour is
  asserted once in the suite; the forwarders are generated from `test-specs`
  in `re-frame.adapter.react-shared-suite-tests`, which is the coverage list.
  The async and DOM entries live in `uix_dispatch_frame_capture_cljs_test`,
  `uix_after_render_dom_cljs_test` and `uix_use_sub_dom_cljs_test`."
  (:require [cljs.test :refer-macros [use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite]
            [re-frame.adapter.react-test-support :as rf.adapter.react-test-support]
            [re-frame.test-support :as rf.test-support])
  (:require-macros
   [re-frame.adapter.react-shared-suite-tests
    :refer [define-react-shared-suite-tests!]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

;; The frame-provider branch assertions target the substrate-agnostic spine
;; core (`build-frame-provider-element`) directly, so no `:frame-provider`
;; cfg key is needed here. The native-shell-under-`$` behaviour (including
;; the idiomatic trailing-children call shape) is pinned by the use-sub DOM
;; twin's trailing-children test.
(def ^:private cfg
  {:adapter          rf.adapter.uix/adapter
   :substrate-kw     :uix
   :name             "UIx"
   :producer-ns      're-frame.adapter.uix
   ;; The adapter publishes no `wrap-view` Var, so the suite reaches the
   ;; identical fn through the `:adapter/wrap-view` late-bind hook — the door
   ;; `views/reg-view*` uses on every registration.
   :wrap-view        rf.adapter.react-test-support/adapter-wrap-view
   :set-emitter!     rf.adapter.uix/set-hiccup-emitter!
   :render-to-string (:render-to-string rf.adapter.uix/adapter)
   ;; The public Vars the suite's public-surface guard asserts
   ;; (presence/kind/distinctness). Substrate-specific because each
   ;; adapter's re-exports are distinct objects the suite cannot name
   ;; directly.
   ;;
   ;; The roster IS `spec/api-manifest.edn`'s `re-frame.adapter.uix` rows
   ;; minus `adapter` — nine supported fns; `adapter` is checked by the
   ;; suite's adapter-map assertion off the `:adapter` key above. Keep the
   ;; two in step: a manifest row added here without a row there (or the
   ;; reverse) is the drift this roster exists to catch. It deliberately
   ;; does NOT name the spine's warn-once clear thunk — that is internal,
   ;; carries no manifest row, and is reached through the chained
   ;; `:adapter/clear-warn-once-caches!` hook.
   ;;
   ;; There is no `use-current-frame` or `wrap-view` Var: those mechanisms
   ;; live inside the ambient `use-sub` body and behind the
   ;; `:adapter/wrap-view` hook respectively.
   :public-surface-keys [:set-hiccup-emitter! :frame-provider :frame-root
                         :use-sub :use-frame :flush-views!
                         :client-root :render! :unmount!]
   :public-surface   {:set-hiccup-emitter! rf.adapter.uix/set-hiccup-emitter!
                      :frame-provider      rf.adapter.uix/frame-provider
                      :frame-root          rf.adapter.uix/frame-root
                      :use-sub             rf.adapter.uix/use-sub
                      :use-frame           rf.adapter.uix/use-frame
                      :flush-views!        rf.adapter.uix/flush-views!
                      :client-root         rf.adapter.uix/client-root
                      :render!             rf.adapter.uix/render!
                      :unmount!            rf.adapter.uix/unmount!}})

;; Emit one (deftest name (re-frame.adapter.react-shared-suite/assert-name cfg))
;; per row in `react-shared-suite-tests/test-specs`. The macro ns owns
;; the single source of truth for the forwarder list; the entry file
;; inlines the generated deftests at compile time.
(define-react-shared-suite-tests! cfg)
