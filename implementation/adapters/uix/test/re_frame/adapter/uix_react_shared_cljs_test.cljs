(ns re-frame.adapter.uix-react-shared-cljs-test
  "UIx entry point for the parameterized React adapter contracts."
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

(def ^:private cfg
  {:adapter          rf.adapter.uix/adapter
   :substrate-kw     :uix
   :name             "UIx"
   :producer-ns      're-frame.adapter.uix
   :wrap-view        rf.adapter.react-test-support/adapter-wrap-view
   :set-emitter!     rf.adapter.uix/set-hiccup-emitter!
   :render-to-string (:render-to-string rf.adapter.uix/adapter)
   ;; Keep this published function roster aligned with spec/api-manifest.edn.
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

(define-react-shared-suite-tests! cfg)
