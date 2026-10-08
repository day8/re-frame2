(ns re-frame.trace.facade-buffer-platform-parity-cljs-test
  "`rf/trace-buffer` and `rf/clear-trace-buffer!` exist and work on BOTH
  platforms. Spec 009 §`trace-buffer` API and Tool-Pair §How AI tools attach
  name `(rf/trace-buffer frame-id)` as THE ring reader, so a CLJS REPL must
  find it; behind a `#?(:clj …)` fence this namespace would not compile on
  the `:node-test` build. Production DCE is no reason for such a fence — `npm
  run test:bundle-isolation` (family `trace-tooling`) is the proof.

  Dev-only (`^:requires-debug`): the ring is never allocated under
  `-Dre-frame.debug=false` / `goog.DEBUG=false`. The namespace still LOADS in
  the production-gate lane, so a fence around the defs reddens that job."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id :kuky-51/parity-frame)

(deftest ^:requires-debug facade-trace-buffer-reads-and-clears-the-ring
  ;; The reset fixture leaves the per-frame rings alone; clear them so this
  ;; test reads only its own dispatch.
  (rf.trace.tooling/clear-trace-rings!)
  (is (= [] (rf/trace-buffer :kuky-51/never-registered {:flat true}))
      "an unregistered frame reads []")
  (rf/make-frame {:id frame-id})
  (rf/reg-event :kuky-51/ping (fn [{:keys [db]} _] {:db (assoc db :pinged? true)}))
  (rf/dispatch-sync [:kuky-51/ping] {:frame frame-id})
  (is (= [:kuky-51/ping] (:event (first (rf/trace-buffer frame-id)))))
  (is (seq (rf/trace-buffer frame-id {:flat true})))
  (rf/clear-trace-buffer! frame-id)
  (is (= [] (rf/trace-buffer frame-id))))
