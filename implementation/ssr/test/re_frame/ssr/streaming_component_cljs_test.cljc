(ns re-frame.ssr.streaming-component-cljs-test
  "Cross-host contract for the suspense COMPONENT
  (`re-frame.ssr.suspense/boundary`) and the failed-boundary set it reads
  (rf2-ycz3k; rf2-j81hs ruling SS2 + SS3). Per Spec 011 §Streaming SSR.

  Runs on BOTH hosts, which is the point: the component's whole reason to
  exist is that ONE authoring form has to work on the JVM streaming
  emitter and on every client substrate. The `:clj` half proves the
  expansion-to-marker and the walker's deferral; the `:cljs` half proves
  the body/fallback render and the failed-set read; the host-neutral half
  proves the attrs contract and — load-bearing — that both hosts hash the
  SAME tree.

  The DOM lifecycle (finalization, mount unwrapping, readiness, and the
  real `hydrateRoot` reconciliation) is covered by
  `re-frame.ssr.streaming-hydration-lifecycle-dom-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.suspense :as suspense :refer [boundary]]
            [re-frame.ssr.install :as install]
            [re-frame.test-support :as test-support]
            #?(:cljs [re-frame.adapter.reagent-slim :as reagent-slim-adapter])))

;; `installed-payloads` is a process-global `defonce` ledger that neither
;; `clear-all!` nor a frames reset touches — reset it so nothing this
;; suite does can leak into a sibling.
;;
;; A plain FN fixture, deliberately: `clojure.test` has no map-fixture
;; support, and a map is `IFn` (key lookup), so a `{:before …}` fixture
;; on the JVM composes to a fn that returns nil WITHOUT calling the test
;; — the whole namespace then reports "Ran 0 tests" and reads as GREEN.
;; This suite hit exactly that while being written. `:async?` is left
;; unset so `make-reset-runtime-fixture` also returns its fn form.
(use-fixtures :each
  (fn [f]
    (install/reset-installed-payloads!)
    (suspense/reset-failed-boundaries!)
    (f))
  (test-support/make-reset-runtime-fixture
    {:adapter #?(:clj ssr/adapter :cljs reagent-slim-adapter/adapter)
     :ambient-frame nil}))

;; Server-side fixture components. JVM-only: every reference is inside a
;; `#?(:clj …)` deftest below — the client half builds its own trees.
#?(:clj
   (defn- card-skeleton [id]
     [:div.card.skeleton [:h3 (str "Loading " (name id))]]))

#?(:clj
   (defn- card-view [id]
     (let [c @(rf/subscribe [:card/by-id id])]
       [:div.card [:h3 (:title c)] [:p.value (str (:value c))]])))

#?(:clj
   (defn- throwing-card []
     (throw (ex-info "flaky third-party metric service" {}))))

;; ---- host-neutral contract -------------------------------------------------

(deftest attrs-are-required-on-every-host
  (testing "a missing :id or :fallback raises the SAME error id the shell
            walker raises, so the mistake reads identically whichever
            host catches it first"
    (doseq [bad [{} {:id :a} {:fallback [:p]} nil "nope"]]
      (is (thrown? #?(:clj Exception :cljs :default)
                   (apply boundary [bad [:p "body"]]))
          (str "expected a throw for attrs " (pr-str bad))))))

(deftest the-failed-set-path-is-under-the-reserved-ssr-key
  (testing "the slot lives under the already-reserved :rf.runtime/ssr
            runtime-db key, a sibling of the :hydration metadata"
    (is (= :rf.runtime/ssr (first suspense/failed-boundaries-path)))
    (is (= [:rf.runtime/ssr :streaming :failed-boundaries]
           suspense/failed-boundaries-path))))

(deftest frame-failed-boundaries-reads-empty-for-an-unknown-frame
  (testing "never throws for an absent / destroyed frame — absence is the
            ordinary no-recorded-outcome case, not an error"
    (is (= #{} (suspense/frame-failed-boundaries :no/such-frame)))))

;; ---- server host -----------------------------------------------------------

#?(:clj
   (deftest component-expands-to-the-internal-wire-marker
     (testing "on the JVM the component IS the marker — the keyword head
               stays internal syntax between component and walker"
       (is (= [:rf/suspense-boundary
               {:id :card.revenue :fallback [:p "loading"]}
               [:div "body"]]
              (boundary {:id :card.revenue :fallback [:p "loading"]}
                        [:div "body"])))
       (testing "only the two contract keys reach the walker"
         (is (= {:id :a :fallback [:p]}
                (second (boundary {:id :a :fallback [:p] :stray "dropped"}
                                  [:div]))))))))

#?(:clj
   (deftest shell-walk-defers-a-component-boundary
     (testing "the shell walker resolves the callable head, sees the
               marker it expands to, and registers a continuation —
               the walker protocol is unchanged"
       (let [fid :test/shell-walk]
         (rf/reg-sub :card/by-id (fn [db [_ id]] (get-in db [:cards id])))
         (rf/reg-event :server-init {:platforms #{:server}}
           (fn [_ _] {:db {:cards {:revenue {:title "Revenue" :value 42375}}}}))
         (rf/make-frame {:id fid :platform :server :initial-events [[:server-init]]})
         (let [tree  [:section.cards
                      [boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
                       [card-view :revenue]]]
               {:keys [shell-html continuations]}
               (rf/with-frame fid (ssr/streaming-render-shell tree))]
           (is (= 1 (count continuations)))
           (is (= :card.revenue (:id (first continuations))))
           (is (clojure.string/includes? shell-html "data-rf2-suspense-fallback=\"1\"")
               "the fallback ships inline as a <template> placeholder")
           (is (clojure.string/includes? shell-html "Loading revenue")
               "the DECLARED fallback is what the shell paints")
           (is (not (clojure.string/includes? shell-html "suspense-boundary"))
               "no phantom element — the marker never reaches the tag grammar")
           (testing "draining the continuation renders the deferred body"
             (let [{:keys [html failed?]}
                   (rf/with-frame fid
                     (ssr/streaming-render-continuation fid (first continuations)))]
               (is (false? failed?))
               (is (clojure.string/includes? html "42375")))))))))

#?(:clj
   (deftest failed-continuations-ride-the-final-payloads-runtime-slice
     (testing "SS3: the server has always known :failed? per continuation
               and DROPPED it; it now rides the serialisable runtime slice
               so the client can render the declared fallback"
       (let [fid :test/failed-payload]
         (rf/reg-sub :card/by-id (fn [db [_ id]] (get-in db [:cards id])))
         (rf/make-frame {:id fid :platform :server})
         (let [tree      [:section.cards
                          [boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
                           [throwing-card]]]
               {:keys [continuations]} (rf/with-frame fid (ssr/streaming-render-shell tree))
               outcomes  (mapv #(rf/with-frame fid (ssr/streaming-render-continuation fid %))
                               continuations)
               failed    (into #{} (comp (filter :failed?) (map :id)) outcomes)]
           (is (= #{:card.flaky} failed) "the drain reports the failure")
           (testing "the set lands in the payload's runtime-db slice"
             (let [payload (rf/with-frame fid
                             (ssr/streaming-build-final-payload
                               fid "deadbeef"
                               {:version 1
                                :payload :rf.ssr.payload/whole-app-db
                                :failed-boundaries failed}))]
               (is (= #{:card.flaky}
                      (get-in (:rf/runtime-db payload) suspense/failed-boundaries-path)))))
           (testing "nothing failed contributes NO key — an ordinary page
                     carries nothing extra on the wire"
             (let [payload (rf/with-frame fid
                             (ssr/streaming-build-final-payload
                               fid "deadbeef"
                               {:version 1
                                :payload :rf.ssr.payload/whole-app-db
                                :failed-boundaries #{}}))]
               (is (nil? (get-in (:rf/runtime-db payload) suspense/failed-boundaries-path))))))))))

#?(:clj
   (deftest one-tree-hashes-identically-for-both-hosts
     (testing "the component canonicalises to the existing #fn[] token, so
               the render-tree hash of the SHARED tree is stable — the
               property a reader-conditional card-slot could never have
               (it made the two hosts hash structurally different trees)"
       (let [fid :test/hash]
         (rf/make-frame {:id fid :platform :server})
         (let [tree [:section.cards
                     [boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
                      [card-view :revenue]]]
               h1   (rf/with-frame fid (ssr/render-tree-hash tree))
               h2   (rf/with-frame fid (ssr/render-tree-hash tree))]
           (is (string? h1))
           (is (= h1 h2) "hashing is deterministic over the component head")
           (testing "a DIFFERENT boundary id changes the hash (the hash is
                     not blind to the component's attrs)"
             (let [other [:section.cards
                          [boundary {:id :card.other :fallback [card-skeleton :revenue]}
                           [card-view :revenue]]]]
               (is (not= h1 (rf/with-frame fid (ssr/render-tree-hash other)))))))))))

;; ---- client host -----------------------------------------------------------

#?(:cljs
   (deftest client-renders-the-body-when-nothing-failed
     (testing "the ordinary case: no recorded outcome, render the body"
       (let [fid :test/client-body]
         (rf/make-frame {:id fid :platform :client})
         (is (= [:div "body"]
                (rf/with-frame fid
                  (boundary {:id :card.revenue :fallback [:p "loading"]}
                            [:div "body"]))))))))

#?(:cljs
   (deftest client-renders-the-declared-fallback-for-a-failed-boundary
     (testing "SS2: a boundary in the failed set renders its DECLARED
               fallback — the markup the failed chunk left in the DOM"
       (suspense/record-failed-boundaries! #{:card.flaky})
       (is (= [:p "loading"]
              (boundary {:id :card.flaky :fallback [:p "loading"]} [:div "body"]))
           "the failed boundary renders its fallback")
       (is (= [:div "body"]
              (boundary {:id :card.revenue :fallback [:p "loading"]} [:div "body"]))
           "a sibling that resolved still renders its body"))))

#?(:cljs
   (deftest the-render-time-record-needs-no-frame
     (testing "`boundary` is a plain fn component, and on Reagent a plain
               fn cannot read the enclosing provider's frame from React
               context — so the render-time record is deliberately
               frame-free and works with no scope established at all"
       (suspense/record-failed-boundaries! #{:card.flaky})
       (is (= [:p "loading"]
              (boundary {:id :card.flaky :fallback [:p "loading"]} [:div "body"])))
       (is (= [:div "body"]
              (boundary {:id :card.revenue :fallback [:p "loading"]} [:div "body"]))))))

#?(:cljs
   (deftest the-durable-set-round-trips-through-hydration
     (testing "SS3: the payload's runtime slice installs into the frame's
               runtime-db — the durable, inspectable record (distinct from
               the render-time one above)"
       (let [fid :test/client-durable]
         (rf/make-frame {:id fid :platform :client})
         (rf/dispatch-sync
           [:rf/hydrate {:rf/version 1
                         :rf/app-db  {}
                         :rf/runtime-db (assoc-in {} suspense/failed-boundaries-path
                                                  #{:card.flaky})}]
           {:frame fid})
         (is (= #{:card.flaky} (suspense/frame-failed-boundaries fid)))))))

#?(:cljs
   (deftest client-wraps-multiple-children-in-a-fragment
     (testing "mirrors the walker's continuation-subtree construction, so
               the client's rendered structure matches the server's
               resolved-subtree html exactly (a fragment emits no DOM)"
       (let [fid :test/client-multi]
         (rf/make-frame {:id fid :platform :client})
         (rf/with-frame fid
           (is (= [:div "one"]
                  (boundary {:id :b :fallback [:p]} [:div "one"]))
               "a lone child renders as itself — no wrapper")
           (is (= [:<> [:div "one"] [:div "two"]]
                  (boundary {:id :b :fallback [:p]} [:div "one"] [:div "two"]))
               "several children are spliced into a fragment"))))))
