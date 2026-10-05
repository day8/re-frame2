(ns re-frame.dispatch-family-facade-shrink-test
  "JVM coverage of the facade's macro-fn twins.

  There is no `dispatch*` / `dispatch-sync*` / `subscribe*` /
  `reg-interceptor*` on the `re-frame.core` facade: each macro's own name
  ALSO carries a plain-fn value on CLJS (Convention A, per
  spec/Conventions.md §Convention A), mirroring `reg-event` / `reg-sub` /
  etc. This suite pins the ABSENCES, which nothing else would notice.

  The presences need no pin here: `reg-view*` IS on the facade (the
  `reg-view*` + view lane is not folded into the app-facing lane — see
  spec/Cross-Spec-Interactions.md §21 Family asymmetry), and every
  `reg-view` expansion calls `re-frame.core/reg-view*`; the macros and the
  owning-ns fns they delegate to run in every dispatching test; and the
  facade's lack of `->interceptor*` is pinned on both hosts by
  `re-frame.facade-internal-constructors-cljs-test`.

  Mirrors the `ns-resolve` idiom `core_api_additions_test.clj`'s
  `renamed-facade-exports-resolve-old-names-gone` uses for facade-presence /
  absence checks."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest retired-macro-fn-twins-gone-from-facade
  (testing "dispatch* / dispatch-sync* / subscribe* / reg-interceptor* are
            absent from re-frame.core — there is no alias — and reg-machine*
            is reached via re-frame.machines, never the facade"
    (doseq [sym ['dispatch* 'dispatch-sync* 'subscribe* 'reg-interceptor* 'reg-machine*]]
      (is (nil? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must not resolve (there is no alias)")))))
