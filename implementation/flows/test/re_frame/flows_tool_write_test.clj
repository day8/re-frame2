(ns re-frame.flows-tool-write-test
  "An epoch tool write installs frame-state outside any drain, so afterwards
  the frame's flow memo no longer vouches for the inputs it cached: every flow
  re-claims its output paths and recomputes on the next event. The memo still
  accounts for the output paths the frame holds, so a flow cleared or moved
  after the write has its former path vacated on that event (Spec 013
  §Re-registration)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; `replace-frame-state!` is the epoch artefact's tool write; it
            ;; resets the frame's flow memo through `:flows/restore-last-inputs!`.
            [re-frame.epoch :as rf.epoch]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- db [] (rf/app-db-value :t/f))

(defn- run-flow!
  "Make `:t/f` run `:out`, registered with `metadata` and `derive`, and seed
  `{:n 3}`."
  [metadata derive]
  (rf/reg-event :seed (fn [_ _] {:db {:n 3}}))
  (rf/reg-event :touch (fn [{:keys [db]} _] {:db db}))
  (rf/reg-flow :out metadata derive)
  (rf/make-frame {:id :t/f :flows [:out]})
  (rf/dispatch-sync [:seed] {:frame :t/f}))

(defn- tool-write!
  "Install `frame-state` on `:t/f` through the epoch tool write, and return
  whether it succeeded."
  [frame-state]
  (:ok? (rf.epoch/replace-frame-state! :t/f frame-state)))

(defn- touch!
  "Dispatch an event that changes nothing, and return the committed app-db."
  []
  (rf/dispatch-sync [:touch] {:frame :t/f})
  (db))

(defn- after-a-tool-write
  "Run `inc` from `[:n]` to `[:old]`, install the committed app-db plus
  `:tool-write true` through a tool write, call `change!`, and return the
  app-db the next event commits."
  [change!]
  (run-flow! {:inputs [[:n]] :output-path [:old]} inc)
  (is (= {:n 3 :old 4} (db)))
  (is (tool-write! {:rf.db/app (assoc (db) :tool-write true)}))
  (change!)
  (touch!))

(deftest clearing-a-flow-after-a-tool-write-vacates-its-output
  (is (= {:n 3 :tool-write true}
         (after-a-tool-write #(rf/clear :flow :out)))))

(deftest moving-an-output-after-a-tool-write-vacates-the-former-path
  (is (= {:n 3 :tool-write true :new 4}
         (after-a-tool-write
           #(rf/reg-flow :out {:inputs [[:n]] :output-path [:new]} inc)))))

(deftest an-unchanged-flow-recomputes-from-the-installed-state
  (let [runs (atom 0)]
    (run-flow! {:inputs [[:n]] :output-path [:old]}
               (fn [n] (swap! runs inc) (inc n)))
    (touch!)
    (is (= 1 @runs) "control: an event that changes no input skips the flow")
    (is (tool-write! {:rf.db/app (assoc (db) :old 99)}))
    (is (= {:n 3 :old 4} (touch!)))
    (is (= 2 @runs) "the event after the write recomputes rather than trusting the memo")))

(deftest a-tool-write-that-rewinds-the-elision-registry-gets-the-claims-back
  ;; The elision registry lives in runtime-db, which a tool write can replace,
  ;; so the next event re-claims the output before anything reads it.
  (run-flow! {:inputs [[:n]] :output-path [:old] :sensitive [[]]}
             (fn [_] "flow-secret"))
  (let [redacted? #(= :rf/redacted
                      (rf.elision/elide-wire-value (:old (db))
                                                   {:frame :t/f :path [:old]}))]
    (is (redacted?) "precondition: the committed output is classified")
    (is (tool-write! {:rf.db/app (db) :rf.db/runtime {}}))
    (is (not (redacted?)) "precondition: the write removed the claim")
    (touch!)
    (is (redacted?) "the next event re-claims the output")))
