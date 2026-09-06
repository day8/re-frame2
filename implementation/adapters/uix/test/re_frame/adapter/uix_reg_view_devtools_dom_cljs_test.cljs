(ns re-frame.adapter.uix-reg-view-devtools-dom-cljs-test
  "UIx DOM/browser entry-point for the React-DevTools display-name
  assertion of the parameterised React-adapter suite
  (`re-frame.adapter.react-shared-suite`) — rf2-976bw.

  The headless half (`display-name-matches-render-measure`) is generated
  into `uix_react_shared_cljs_test.cljs` from the suite's `test-specs`
  list and runs under `:node-test`. The mounted half needs a real
  `createRoot` commit before a fiber exists to read, so it lives here:
  the ns ends in `-dom-cljs-test`, which is what shadow-cljs's
  `:browser-test` build discovers. `:node-test` loads this file too
  (matches `cljs-test$`); the assertion self-gates on `(browser?)` via
  the suite's `with-browser-act` and no-ops cleanly there.

  What it proves that the headless half cannot: the name React DevTools
  SHOWS for the component, read off the committed fiber's `type` rather
  than off the fn property the framework stamped."
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.react-shared-suite :as rf.adapter.react-shared-suite]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(def ^:private cfg
  {:adapter      rf.adapter.uix/adapter
   :substrate-kw :uix
   :name         "UIx"
   :wrap-view    rf.adapter.uix/wrap-view})

(deftest mounted-display-name-is-devtools-visible-uix
  (rf.adapter.react-shared-suite/assert-mounted-display-name-is-devtools-visible cfg))
