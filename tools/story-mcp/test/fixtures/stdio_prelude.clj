(ns fixtures.stdio-prelude
  "Preloaded by test/stdio-roundtrip.js:

    clojure -M -i test/fixtures/stdio_prelude.clj -m re-frame.story-mcp.server

  The README's \"Loading your project's stories\" launch shape, pointed at a
  fixture. It is a script clojure.main loads before `-m`, not a test: the
  namespace spells its own path (the test-quiet discovery rule, rf2-vruo9)
  and does not end in `-test`, so the JVM suite discovers it but never loads
  it.

  The handlers that print do so AFTER the server is up, under the server's
  own dispatch (rf2-gwye.57): the round-trip asserts those lines reach stderr
  and never stdout. Nothing here prints at load time — that is the README's
  separate \"stdout is the wire\" caveat, not this contract."
  (:require [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(rf/init! rf.substrate.plain-atom/adapter)

(rf.story/reg-story :story.stdio-fixture {:doc "stdio-roundtrip fixture"})

(rf/reg-event :stdio-fixture/quiet
  (fn [{:keys [db]} _]
    {:db (assoc db :ran :quiet)}))

(rf/reg-event :stdio-fixture/print-in-setup
  (fn [{:keys [db]} _]
    (println "STDIO-FIXTURE-SETUP-PRINT")
    {:db (assoc db :ran :setup)}))

(rf/reg-event :stdio-fixture/print-in-script
  (fn [{:keys [db]} _]
    (println "STDIO-FIXTURE-SCRIPT-PRINT")
    {:db (assoc db :ran :script)}))

(rf/reg-event :stdio-fixture/print-then-throw
  (fn [_ _]
    (println "STDIO-FIXTURE-THROW-PRINT")
    (throw (ex-info "stdio fixture: handler failed on purpose" {}))))

(rf.story/reg-variant :story.stdio-fixture/quiet
  {:setup [[:stdio-fixture/quiet]]})

(rf.story/reg-variant :story.stdio-fixture/setup-print
  {:setup [[:stdio-fixture/print-in-setup]]})

(rf.story/reg-variant :story.stdio-fixture/script-print
  {:script [[:dispatch-sync [:stdio-fixture/print-in-script]]]})

(rf.story/reg-variant :story.stdio-fixture/throw-print
  {:setup [[:stdio-fixture/print-then-throw]]})
