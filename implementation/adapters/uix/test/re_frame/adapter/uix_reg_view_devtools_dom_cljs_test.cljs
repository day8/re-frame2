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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

(def ^:private cfg
  {:adapter      uix-adapter/adapter
   :substrate-kw :uix
   :name         "UIx"
   :wrap-view    uix-adapter/wrap-view})

(deftest mounted-display-name-is-devtools-visible-uix
  (suite/assert-mounted-display-name-is-devtools-visible cfg))
