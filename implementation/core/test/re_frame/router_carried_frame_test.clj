(ns re-frame.router-carried-frame-test
  "The router's frame resolution (Spec 002 §Frame target resolution — the
  carried invariant; EP-0002 §Dispatch And Router):

    1. an explicit `{:frame …}` opt wins;
    2. otherwise `rf.frame/require-current-frame!` reads the scope/hold stamp
       (`with-frame`, a `frame-provider` (SCOPE) or a `frame-root`
       (ENSURE) boundary, or a captured `*current-frame*` binding);
    3. no frame raises `:rf.error/no-frame-context` at envelope-build time,
       before any frame-registry lookup. There is no `:rf/default` floor.

  An explicit target naming no registered frame is a registry-lookup failure
  (`:rf.error/frame-destroyed`), a different category from absence. The
  React-context tier is pinned by `re-frame.router-carried-frame-cljs-test`.

  Both error categories reach the always-on `:errors` stream, so every
  assertion here also holds under `-Dre-frame.debug=false`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- thrown-ex-data
  "Run `f`; return the ex-data of the ExceptionInfo it throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- record-errors!
  "Attach an always-on `:errors` listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf.error-emit/register-error-listener! listener-id (fn [rec] (swap! a conj rec)))
    a))

(defn- errors-of
  [recorded error-id]
  (filter #(= error-id (:error %)) @recorded))

(deftest bare-dispatch-outside-context-raises-no-frame-context
  (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
  (let [errs (record-errors! ::bare)]
    (binding [rf.frame/*current-frame* nil]
      (is (= {:rf.error/id :rf.error/no-frame-context :operation :dispatch}
             (select-keys (thrown-ex-data #(rf/dispatch [:app/noop]))
                          [:rf.error/id :operation]))))
    (rf.error-emit/unregister-error-listener! ::bare)
    (is (= 1 (count (errors-of errs :rf.error/no-frame-context)))
        "exactly one always-on :rf.error/no-frame-context error fired")))

(deftest bare-dispatch-sync-outside-context-raises-no-frame-context
  (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
  (binding [rf.frame/*current-frame* nil]
    (is (= :rf.error/no-frame-context
           (:rf.error/id (thrown-ex-data #(rf/dispatch-sync [:app/noop])))))))

(deftest no-frame-error-precedes-registry-lookup
  ;; An event with no handler, dispatched under no scope, still raises the
  ;; absence error: resolution never reached a registry.
  (binding [rf.frame/*current-frame* nil]
    (is (= :rf.error/no-frame-context
           (:rf.error/id (thrown-ex-data #(rf/dispatch-sync [:never/registered])))))))

(deftest explicit-default-unregistered-is-frame-destroyed-not-no-frame-context
  ;; :rf/default is not registered. A carried target that names no frame is
  ;; recover-but-emit: no throw, an always-on :rf.error/frame-destroyed.
  (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
  (let [errs (record-errors! ::bad-explicit)]
    (binding [rf.frame/*current-frame* nil]
      (rf/dispatch-sync [:app/noop] {:frame :rf/default}))
    (rf.error-emit/unregister-error-listener! ::bad-explicit)
    (is (= 1 (count (errors-of errs :rf.error/frame-destroyed)))
        "a bad explicit target emits :rf.error/frame-destroyed")
    (is (empty? (errors-of errs :rf.error/no-frame-context))
        "not :rf.error/no-frame-context — a stamp was carried, just a bad one")))

(deftest explicit-frame-overrides-scope
  (rf/make-frame {:id :app/main :doc "scope frame"})
  (rf/make-frame {:id :app/other :doc "override target"})
  (rf/reg-event :app/inc {:frame :app/other}
    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/with-frame :app/main
    (rf/dispatch-sync [:app/inc] {:frame :app/other}))
  (is (= 1 (:n (rf/app-db-value :app/other)))
      "the dispatch landed on the explicit frame, not the with-frame scope"))
