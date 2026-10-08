(ns re-frame.async-reset-fixture-cljs-test
  "The `{:async? true}` map form of `make-reset-runtime-fixture` (Spec 008
  §Test-support). Its `:before` establishes the ambient frame with a
  persistent `set!` of `re-frame.frame/*current-frame*` rather than a
  `binding`, which would unwind the moment `:before` returns, so a bare
  `dispatch-sync` in an `(async done …)` body still resolves `:rf/default` on
  a later tick. A synchronous body under the map runs after the same
  `:before`, so it is served identically."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter
                                               :async?  true}))

(deftest bare-dispatch-sync-drains-in-async-body
  (async done
    (rf/reg-event :ar/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (js/setTimeout
      (fn []
        (rf/dispatch-sync [:ar/inc])
        (is (= 1 (:n (rf/app-db-value :rf/default)))
            "a bare dispatch-sync on a fresh tick landed in :rf/default")
        (done))
      0)))
