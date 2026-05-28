(ns day8.re-frame2-xray.drop-in-cljs-test
  "Drop-in mode tests (rf2-1o9cq, v1.1).

  Verifies the three attach modes (`:push`, `:atom`, `:sub`) all
  funnel host events through `collect-from-host!` into Xray's
  trace buffer, plus the `normalise-event` defaults (auto-`:id`,
  auto-`:time`) and the `detach!` lifecycle.

  CLJC so the contract runs under both `clojure -M:test` (JVM) and
  the CLJS node-runtime suite. The `:atom` mode is CLJS-only (the
  `add-watch` watch path needs the CLJS atom protocol layered on
  top of clojure.lang.IAtom; the test for that mode is reader-
  conditional)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-xray.drop-in :as drop-in]
            #?(:cljs [day8.re-frame2-xray.trace-collector :as trace-collector])))

;; ---- fixtures ------------------------------------------------------------

(defn- reset-each [test-fn]
  #?(:cljs (trace-collector/reset-for-test!))
  (drop-in/reset-for-test!)
  (test-fn)
  (drop-in/reset-for-test!)
  #?(:cljs (trace-collector/reset-for-test!)))

(use-fixtures :each reset-each)

;; ---- normalisation ------------------------------------------------------

(deftest normalise-event-fills-missing-id
  (testing "normalise-event assigns a monotonic :id when the host omits it"
    (let [e1 (drop-in/normalise-event {:operation :host/foo :op-type :rf.event})
          e2 (drop-in/normalise-event {:operation :host/bar :op-type :rf.event})]
      (is (number? (:id e1)) ":id is a number")
      (is (number? (:id e2)))
      (is (< (:id e1) (:id e2))
          "successive normalise calls produce monotonically increasing :ids"))))

(deftest normalise-event-preserves-host-id
  (testing "host-supplied :id wins over the drop-in counter"
    (let [e (drop-in/normalise-event {:id 42 :operation :host/foo :op-type :rf.event})]
      (is (= 42 (:id e)) "host :id is preserved"))))

(deftest normalise-event-fills-missing-time
  (testing "normalise-event assigns a wall-clock :time when the host omits it"
    (let [e (drop-in/normalise-event {:operation :host/foo :op-type :rf.event})]
      (is (number? (:time e)) ":time is a number (host clock)"))))

(deftest normalise-event-preserves-host-time
  (testing "host-supplied :time wins over the drop-in default"
    (let [e (drop-in/normalise-event {:time 12345 :operation :host/foo :op-type :rf.event})]
      (is (= 12345 (:time e)) "host :time is preserved"))))

(deftest normalise-event-does-not-default-operation
  (testing "normalise-event does NOT default :operation / :op-type — host bugs surface"
    (let [e (drop-in/normalise-event {:tags {:frame :host/main}})]
      (is (nil? (:operation e)) ":operation stays nil")
      (is (nil? (:op-type e))   ":op-type stays nil"))))

;; ---- push mode ----------------------------------------------------------

#?(:cljs
   (deftest emit!-pushes-into-xray-buffer
     (testing "emit! lands a host event in the Xray frameless secondary ring"
       (drop-in/attach! {:mode :push})
       (drop-in/emit! {:operation :host/login
                       :op-type   :rf.event
                       :tags      {:rf.trace/event-id :user/login}})
       (let [buf (trace-collector/buffer-for-test)]
         (is (= 1 (count buf)) "one event in the buffer after one emit!")
         (is (= :host/login (:operation (first buf))))
         (is (= :rf.event   (:op-type   (first buf))))
         (is (number?       (:id        (first buf))) ":id filled by drop-in")
         (is (number?       (:time      (first buf))) ":time filled by drop-in")))))

#?(:cljs
   (deftest emit!-without-attach-throws
     (testing "rf2-lvqmw — push-mode emit! is strict: throws when called before attach!"
       (is (thrown? js/Error
                    (drop-in/emit! {:operation :host/foo :op-type :rf.event}))
           "emit! before attach! raises :rf.error/xray-emit-before-attach")
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "no event reached the buffer — the throw fired before collect"))))

#?(:cljs
   (deftest emit!-after-detach-throws
     (testing "rf2-lvqmw — emit! after detach! re-asserts the strict posture"
       (drop-in/attach! {:mode :push})
       (drop-in/emit! {:operation :host/before :op-type :rf.event})
       (drop-in/detach!)
       (is (thrown? js/Error
                    (drop-in/emit! {:operation :host/after :op-type :rf.event}))
           "post-detach emit! throws the same as a pre-attach emit!")
       (let [ops (mapv :operation (trace-collector/buffer-for-test))]
         (is (= [:host/before] ops)
             "only the attached emit landed; the detached one did not")))))

#?(:cljs
   (deftest emit!-drops-non-map-events
     (testing "non-map events are silently dropped (cf. valid-event?)"
       (drop-in/attach! {:mode :push})
       (drop-in/emit! "not-a-map")
       (drop-in/emit! nil)
       (drop-in/emit! 42)
       (drop-in/emit! [:vector :is :not :a :map])
       (is (= 0 (count (trace-collector/buffer-for-test)))
           "every non-map emit was dropped at valid-event?"))))

#?(:cljs
   (deftest multiple-emits-preserve-order
     (testing "buffer ordering tracks emit order"
       (drop-in/attach! {:mode :push})
       (drop-in/emit! {:operation :host/a :op-type :rf.event})
       (drop-in/emit! {:operation :host/b :op-type :rf.event})
       (drop-in/emit! {:operation :host/c :op-type :rf.event})
       (let [ops (mapv :operation (trace-collector/buffer-for-test))]
         (is (= [:host/a :host/b :host/c] ops)
             "events land in the buffer in the order emit! was called")))))

;; ---- attach! / detach! lifecycle ----------------------------------------

(deftest attach-then-detach-cycle
  (testing "attach! sets attached?; detach! clears it"
    (is (false? (boolean (drop-in/attached?))) "not attached before attach!")
    (drop-in/attach! {:mode :push})
    (is (true? (drop-in/attached?)) "attached after attach!")
    (is (= :push (drop-in/current-mode)))
    (drop-in/detach!)
    (is (false? (boolean (drop-in/attached?))) "detached after detach!")
    (is (nil? (drop-in/current-mode)))))

(deftest detach-without-attach-is-noop
  (testing "detach! before any attach! does not throw"
    (drop-in/detach!)
    (is (false? (boolean (drop-in/attached?))))))

(deftest attach-replaces-prior-source
  (testing "calling attach! while already attached replaces the prior source"
    (drop-in/attach! {:mode :push})
    (is (= :push (drop-in/current-mode)))
    (drop-in/attach! {:mode :sub
                      :trace-source (fn [_emit-cb] (fn []))})
    (is (= :sub (drop-in/current-mode))
        "second attach replaced the first")))

(deftest attach-rejects-unknown-mode
  (testing "attach! with an unknown :mode throws (no silent fallback)"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (drop-in/attach! {:mode :bogus})))))

;; ---- subscribe mode -----------------------------------------------------

#?(:cljs
   (deftest sub-mode-fans-events-through-register-fn
     (testing ":sub mode: register-fn is called with our emit-cb; cb pushes into buffer"
       (let [captured-cb (atom nil)]
         (drop-in/attach!
           {:mode :sub
            :trace-source (fn [emit-cb]
                            (reset! captured-cb emit-cb)
                            ;; unregister fn — no-op for this test
                            (fn []))})
         (is (fn? @captured-cb)
             "register-fn received the drop-in's collector callback")
         ;; Host fires the cb as if its source had emitted an event.
         (@captured-cb {:operation :sub-mode/event :op-type :rf.event})
         (is (= 1 (count (trace-collector/buffer-for-test))))
         (is (= :sub-mode/event (:operation (first (trace-collector/buffer-for-test)))))))))

(deftest sub-mode-detach-invokes-host-unregister
  (testing ":sub mode: detach! invokes the host's unregister fn"
    (let [unregister-called? (atom false)]
      (drop-in/attach!
        {:mode :sub
         :trace-source (fn [_emit-cb]
                         (fn [] (reset! unregister-called? true)))})
      (is (false? @unregister-called?) "unregister-fn not called yet")
      (drop-in/detach!)
      (is (true? @unregister-called?)
          "detach! invoked the host-supplied unregister-fn"))))

(deftest sub-mode-host-throws-in-unregister-are-isolated
  (testing ":sub mode: detach! swallows exceptions from a misbehaving host unregister-fn"
    (drop-in/attach!
      {:mode :sub
       :trace-source (fn [_emit-cb]
                       (fn [] (throw (ex-info "host bug" {}))))})
    ;; Must not throw — detach! catches.
    (drop-in/detach!)
    (is (false? (boolean (drop-in/attached?)))
        "attachment was cleared despite the host's unregister-fn throwing")))

;; ---- atom mode (CLJS only) ---------------------------------------------

#?(:cljs
   (deftest atom-mode-pumps-current-contents-on-attach
     (testing ":atom mode: seed contents pump into the buffer on attach!"
       (let [host-log (atom [{:operation :seed/a :op-type :rf.event}
                             {:operation :seed/b :op-type :rf.event}])]
         (drop-in/attach! {:mode :atom :trace-source host-log})
         (is (= [:seed/a :seed/b]
                (mapv :operation (trace-collector/buffer-for-test)))
             "atom contents at attach time landed in the buffer")))))

#?(:cljs
   (deftest atom-mode-pumps-new-tail-on-conj
     (testing ":atom mode: subsequent conjs pump only the new tail entries"
       (let [host-log (atom [])]
         (drop-in/attach! {:mode :atom :trace-source host-log})
         (swap! host-log conj {:operation :tick/one :op-type :rf.event})
         (swap! host-log conj {:operation :tick/two :op-type :rf.event})
         (swap! host-log conj {:operation :tick/three :op-type :rf.event})
         (is (= [:tick/one :tick/two :tick/three]
                (mapv :operation (trace-collector/buffer-for-test)))
             "every conj appended one event to the buffer")))))

#?(:cljs
   (deftest atom-mode-detach-removes-watch
     (testing ":atom mode: detach! removes the watch so subsequent conjs are ignored"
       (let [host-log (atom [])]
         (drop-in/attach! {:mode :atom :trace-source host-log})
         (swap! host-log conj {:operation :before-detach :op-type :rf.event})
         (drop-in/detach!)
         (swap! host-log conj {:operation :after-detach :op-type :rf.event})
         (let [ops (mapv :operation (trace-collector/buffer-for-test))]
           (is (= [:before-detach] ops)
               "only the pre-detach event reached the buffer"))))))

#?(:cljs
   (deftest atom-mode-handles-batch-swap
     (testing ":atom mode: a swap! that grows the log by N pumps all N events"
       (let [host-log (atom [])]
         (drop-in/attach! {:mode :atom :trace-source host-log})
         (swap! host-log into [{:operation :batch/a :op-type :rf.event}
                               {:operation :batch/b :op-type :rf.event}
                               {:operation :batch/c :op-type :rf.event}])
         (is (= 3 (count (trace-collector/buffer-for-test)))
             "all three events pumped from the batch swap!")))))

;; ---- shape contract ----------------------------------------------------
;;
;; Post-rf2-43koh: the consumer-side filter vocabulary
;; (`trace-bus/filter-events`) is retired alongside the rest of the
;; separate Xray ring. The framework's `(rf/trace-buffer fid opts)`
;; exposes the same 13-axis filter vocabulary directly; the framework's
;; own `re-frame.trace-buffer-test` covers every axis. Drop-in's
;; contract is that host events ARE valid `:rf/trace-event` shapes —
;; that contract is covered by the push / atom / sub mode tests above
;; plus the framework's own filter coverage.
