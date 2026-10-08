(ns re-frame.router-carried-frame-cljs-test
  "The router's React-context frame tier (Spec 002 §Frame target resolution):
  with no `*current-frame*` binding and no explicit `:frame` opt, a dispatch
  resolves the enclosing `frame-provider`'s frame through the
  `:adapter/current-frame` late-bind hook, which this suite publishes the way
  an adapter's provider does. Absence and explicit-override precedence are
  platform-neutral and pinned by `re-frame.router-carried-frame-test`'s
  `explicit-frame-overrides-scope` and its no-frame-context tests."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

;; The enclosing provider's frame; nil means no provider.
(def ^:private provider-frame (atom nil))

(defn reset-runtime [test-fn]
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
  (reset! provider-frame nil)
  ;; `set-fn!` invalidates the sticky `get-fn-cached` slot, so resolution
  ;; sees this hook.
  (rf.late-bind/set-fn! :adapter/current-frame
                        (fn [] (or rf.frame/*current-frame* @provider-frame)))
  (try
    (test-fn)
    (finally
      (reset! provider-frame nil)
      (rf.substrate.adapter/dispose-adapter!)
      (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))))

(use-fixtures :each reset-runtime)

(deftest dispatch-under-frame-provider-works
  (rf/make-frame {:id :app/provided :doc "frame the provider supplies"})
  (rf/reg-event :app/inc {:frame :app/provided}
    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (reset! provider-frame :app/provided)
  (binding [rf.frame/*current-frame* nil]
    (rf/dispatch-sync [:app/inc]))
  (is (= 1 (:n (rf/app-db-value :app/provided)))
      "the bare dispatch resolved the provider's frame"))
