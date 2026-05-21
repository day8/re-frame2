(ns re-frame.test-helpers-app-fixture-cljs-test
  "Coverage for `re-frame.test-helpers/with-app-fixture` + the
  `expect-text` / `wait-until` trio (rf2-wy1ac).

  Dual-runtime: the file is named `*_cljs_test.cljc` so both the JVM
  test runner and the shadow-cljs `:node-test` build pick it up. The
  hiccup-walk helpers run identically under both runtimes; the
  `wait-until`-CLJS-promise branch is gated under a reader-conditional
  and exercised on Node only."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures async]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as ts]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh registrar + plain-atom adapter per test.
;;
;; with-app-fixture creates / destroys frames; the snapshot/restore
;; pattern rolls back any reg-event-db calls the test's :install hook
;; makes.
;;
;; Per cljs.test: async tests in this ns (the wait-until CLJS Promise
;; coverage) require fixtures supplied as `{:before ... :after ...}`
;; — the fn-form fixture doesn't suspend across the async body. Mirror
;; the pattern in re-frame.dispatch-frame-capture-cljs-test.
;; ---------------------------------------------------------------------------

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (ts/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (ts/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

;; clojure.test/use-fixtures takes only the fn-form; cljs.test/use-fixtures
;; also takes a {:before :after} map (needed for async tests, which the
;; fn-form cannot suspend across). Branch by runtime.
#?(:clj
   (use-fixtures :each
     (fn [t]
       (before!)
       (try (t) (finally (after!))))))

#?(:cljs
   (use-fixtures :each
     {:before before! :after after!}))

;; ---------------------------------------------------------------------------
;; Test view fns + install hooks
;; ---------------------------------------------------------------------------

(defn- counter-install!
  "Tiny app — :counter/inc bumps :n; :counter/init seeds to zero."
  []
  (rf/reg-event-db ::counter-init (fn [db _]   (assoc db :n 0)))
  (rf/reg-event-db ::counter-inc  (fn [db _]   (update db :n inc)))
  (rf/dispatch-sync [::counter-init]))

(defn- counter-view
  "Form-1 view that reads `:n` straight off `app-db` via
  `get-frame-db` — keeps the test self-contained without reaching
  for `subscribe` / reactive caches."
  []
  (let [n (:n (rf/get-frame-db (rf/current-frame)))]
    [:div {:data-testid "counter-root"}
     [:span {:data-testid "counter-display"} (str n)]]))

;; ---------------------------------------------------------------------------
;; with-app-fixture — Shape 1 (named frame-id)
;; ---------------------------------------------------------------------------

(deftest with-app-fixture-creates-named-frame-and-binds-it
  (testing "Shape 1: explicit :test-app frame-id is created, pinned as
            *current-frame* for the body, and destroyed on exit"
    (let [seen-frame (atom nil)]
      (th/with-app-fixture {:install counter-install!} :test-app
        (reset! seen-frame (rf/current-frame))
        (is (= :test-app @seen-frame)
            "with-frame binding kicks in for the body")
        (is (= 0 (:n (rf/get-frame-db :test-app)))
            ":install ran inside the frame and dispatched :counter-init"))
      (is (= :test-app @seen-frame))
      (is (nil? (get @frame/frames :test-app))
          "frame is destroyed when the body exits"))))

(deftest with-app-fixture-without-install-still-creates-frame
  (testing "Shape 1 with no :install — fixture creates / binds / destroys
            the frame even when the opts map is empty"
    (th/with-app-fixture {} :test-no-install
      (is (= :test-no-install (rf/current-frame))))
    (is (nil? (get @frame/frames :test-no-install)))))

(deftest with-app-fixture-destroys-frame-on-exception
  (testing "Shape 1: body throwing does NOT leak the frame — try/finally
            in the macro expansion ensures destroy-frame!"
    (let [thrown (atom nil)]
      (try
        (th/with-app-fixture {:install counter-install!} :test-throws
          (throw (ex-info "boom" {:rf/test :expected})))
        (catch #?(:clj Throwable :cljs :default) e
          (reset! thrown e)))
      (is (some? @thrown) "the body's exception propagated")
      (is (nil? (get @frame/frames :test-throws))
          "even though body threw, the frame was destroyed"))))

;; ---------------------------------------------------------------------------
;; with-app-fixture — Shape 2 (anonymous gensym'd frame-id)
;; ---------------------------------------------------------------------------

(deftest with-app-fixture-anonymous-frame-id
  (testing "Shape 2: omitting the frame-id gensym's an anonymous id
            under :rf.frame/* and uses it for the body"
    (let [seen-frame (atom nil)]
      (th/with-app-fixture {:install counter-install!}
        (reset! seen-frame (rf/current-frame))
        (is (keyword? @seen-frame))
        (is (= "rf.frame" (namespace @seen-frame))
            "anonymous id sits in the framework's :rf.frame/* namespace"))
      (is (nil? (get @frame/frames @seen-frame))
          "anonymous frame is destroyed on exit"))))

;; ---------------------------------------------------------------------------
;; with-app-fixture — :root-view stash for expect-text
;; ---------------------------------------------------------------------------

(deftest with-app-fixture-stashes-root-view
  (testing ":root-view in the opts is bound in *current-root-view* for
            the body's dynamic extent"
    (th/with-app-fixture {:install   counter-install!
                          :root-view counter-view}
                         :test-root-view
      (is (= counter-view th/*current-root-view*)
          ":root-view is reachable from the body")
      (is (= [] th/*current-root-view-args*)
          ":root-view-args defaults to empty"))
    (is (nil? th/*current-root-view*)
        "*current-root-view* is unbound after the body")))

(deftest with-app-fixture-passes-root-view-args
  (testing ":root-view-args, when supplied, ride into the bound view-args slot"
    (let [view-fn (fn [props] [:div {:data-testid "argy"} (:label props)])]
      (th/with-app-fixture {:root-view      view-fn
                            :root-view-args [{:label "hi"}]}
                           :test-view-args
        (is (= [{:label "hi"}] th/*current-root-view-args*)
            ":root-view-args is bound for the body")))))

;; ---------------------------------------------------------------------------
;; expect-text
;; ---------------------------------------------------------------------------

(deftest expect-text-asserts-on-fixture-root-view
  (testing "the 2-arity (testid expected) reads the fixture-stashed
            root view, renders, and asserts text"
    (th/with-app-fixture {:install   counter-install!
                          :root-view counter-view}
                         :test-expect
      (rf/dispatch-sync [::counter-inc])
      (rf/dispatch-sync [::counter-inc])
      (is (true? (th/expect-text :counter-display "2"))
          "expect-text returned true and reported a pass"))))

(deftest expect-text-three-arity-walks-explicit-tree
  (testing "the 3-arity (tree testid expected) form accepts an explicit
            hiccup tree — useful for view-only tests that don't need a
            full fixture"
    (let [tree [:div [:span {:data-testid "label"} "hello"]]]
      (is (true? (th/expect-text tree "label" "hello"))))))

(deftest expect-text-string-and-keyword-testids
  (testing "testid may be a keyword (sugar) or a string (raw)"
    (let [tree [:span {:data-testid "tag"} "x"]]
      (is (true? (th/expect-text tree :tag "x")))
      (is (true? (th/expect-text tree "tag" "x"))))))

(deftest expect-text-throws-without-fixture-when-no-tree
  (testing "calling 2-arity expect-text outside a with-app-fixture body
            (and without a :root-view) raises a clear error — not a
            mysterious NPE"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (th/expect-text :anything "x")))))

#?(:clj
   (deftest expect-text-fails-loudly-on-missing-testid
     (testing "no node matching the testid → reports :fail via do-report
               and returns false. Capture the report so this unit test
               does not itself fail when the diagnostic fires. JVM-only
               because `with-redefs` on `clojure.test/report` is the
               idiomatic capture path (matches re-frame.test-support's
               own test suite)."
       (let [tree     [:div]
             recorded (atom [])
             result   (with-redefs [clojure.test/report
                                    (fn [m] (swap! recorded conj (:type m)))]
                        (th/expect-text tree :missing "x"))]
         (is (false? result)
             "missing testid → expect-text returned false")
         (is (some #(= :fail %) @recorded)
             "a :fail report was emitted by expect-text")))))

;; ---------------------------------------------------------------------------
;; wait-until — predicate form
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest wait-until-pred-returns-truthy-value
     (testing "JVM: a pred that goes truthy synchronously is returned"
       (is (= :ok (th/wait-until (constantly :ok) {:timeout-ms 200}))))))

#?(:clj
   (deftest wait-until-pred-throws-on-timeout
     (testing "JVM: pred that never goes truthy throws ex-info with
               :rf.error/id :rf.error/wait-until-timeout"
       (let [e (try (th/wait-until (constantly false)
                                   {:timeout-ms 30 :interval-ms 5
                                    :label "never"})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
         (is (some? e) "timeout threw")
         (is (= :rf.error/wait-until-timeout (:rf.error/id (ex-data e))))
         (is (= "never" (:label (ex-data e))))))))

#?(:clj
   (deftest wait-until-testid-form-asserts-text
     (testing "JVM: (wait-until testid expected) under a fixture polls
               the root view until the text matches"
       (th/with-app-fixture {:install   counter-install!
                             :root-view counter-view}
                            :test-wait-jvm
         (rf/dispatch-sync [::counter-inc])
         (is (= "1" (th/wait-until :counter-display "1"
                                   {:timeout-ms 200})))))))

#?(:cljs
   (deftest wait-until-pred-returns-promise
     (testing "CLJS: wait-until on a fn returns a js/Promise that
               resolves with the truthy value"
       (async done
         (-> (th/wait-until (constantly :ok) {:timeout-ms 200})
             (.then (fn [v]
                      (is (= :ok v))
                      (done)))
             (.catch (fn [e]
                       (is false (str "unexpected reject: "
                                      (#?(:cljs .-message :clj nil) e)))
                       (done))))))))

#?(:cljs
   (deftest wait-until-pred-rejects-on-timeout
     (testing "CLJS: wait-until rejects with :rf.error/id
               :rf.error/wait-until-timeout on deadline elapse"
       (async done
         (-> (th/wait-until (constantly false)
                            {:timeout-ms 20 :interval-ms 5
                             :label "never"})
             (.then (fn [v]
                      (is false (str "unexpected resolve: " v))
                      (done)))
             (.catch (fn [e]
                       (let [data (ex-data e)]
                         (is (= :rf.error/wait-until-timeout (:rf.error/id data)))
                         (is (= "never" (:label data))))
                       (done))))))))
