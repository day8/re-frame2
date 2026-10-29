(ns re-frame.flows-tool-write-test
  "An epoch tool write installs frame-state outside any drain, and the next
  event's flow pass settles each flow against it: changed inputs recompute,
  and equal inputs re-assert the remembered output, which is what `:derive`
  returns for them (Spec 013 §Dirty-check semantics). The memo still accounts
  for the output paths the frame holds, so a flow cleared or moved after the
  write has its former path vacated on that event (Spec 013
  §Re-registration)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
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

(deftest an-overwritten-output-is-restored-on-the-next-event
  (run-flow! {:inputs [[:n]] :output-path [:old]} inc)
  (is (tool-write! {:rf.db/app (assoc (db) :old 99)}))
  (is (= {:n 3 :old 99} (db)) "precondition: the write installed the edited output")
  (is (= {:n 3 :old 4} (touch!))))

(deftest an-input-set-back-to-its-pre-write-value-restores-its-output
  ;; The write installs an input and an output that agree; the event that sets
  ;; the input back meets the memo's inputs and re-asserts the memo's output.
  (run-flow! {:inputs [[:n]] :output-path [:old]} inc)
  (is (tool-write! {:rf.db/app {:n 10 :old 11}}))
  (rf/reg-event :set-n (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf/dispatch-sync [:set-n 3] {:frame :t/f})
  (is (= {:n 3 :old 4} (db))))

(deftest a-tool-write-that-empties-runtime-db-leaves-the-output-classified
  ;; A flow output's classification is a declaration the frame's policy
  ;; resolves, not frame-state, so no tool write can drop it (Spec 015
  ;; §Static app-db classification).
  (run-flow! {:inputs [[:n]] :output-path [:old] :sensitive [[]]}
             (fn [_] "flow-secret"))
  (let [redacted? #(= :rf/redacted
                      (rf.elision/elide-wire-value (:old (db))
                                                   {:frame :t/f :path [:old]}))]
    (is (redacted?) "precondition: the committed output is classified")
    (is (tool-write! {:rf.db/app (db) :rf.db/runtime {}}))
    (is (redacted?) "the write leaves the claim standing")
    (touch!)
    (is (redacted?) "and so does the next event")))
