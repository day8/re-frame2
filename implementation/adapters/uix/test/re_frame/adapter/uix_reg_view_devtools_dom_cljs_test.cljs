(ns re-frame.adapter.uix-reg-view-devtools-dom-cljs-test
  "DevTools sees the registered view name on the committed React fiber."
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite :as rf.adapter.react-shared-suite]
            [re-frame.adapter.react-test-support :as rf.adapter.react-test-support]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(def ^:private cfg
  {:adapter      rf.adapter.uix/adapter
   :substrate-kw :uix
   :name         "UIx"
   :wrap-view    rf.adapter.react-test-support/adapter-wrap-view})

(deftest mounted-display-name-is-devtools-visible-uix
  (rf.adapter.react-shared-suite/assert-mounted-display-name-is-devtools-visible cfg))
