(ns re-frame.ssr.streaming-component-cljs-test
  "Cross-host contract for the suspense COMPONENT
  (`re-frame.ssr.suspense/boundary`) and the failed-boundary set it reads.
  Per Spec 011 §Streaming SSR.

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
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.suspense :as rf.ssr.suspense :refer [boundary]]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.test-support :as rf.test-support]
            #?(:cljs [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim])))

;; `installed-payloads` is a process-global `defonce` ledger that neither
;; `clear-all!` nor a frames reset touches — reset it so nothing this
;; suite does can leak into a sibling.
;;
;; A plain FN fixture, deliberately: `clojure.test` has no map-fixture
;; support, and a map is `IFn` (key lookup), so a `{:before …}` fixture
;; on the JVM composes to a fn that returns nil WITHOUT calling the test
;; — the whole namespace then reports "Ran 0 tests" and reads as GREEN.
;; `:async?` is left
;; unset so `make-reset-runtime-fixture` also returns its fn form.
(use-fixtures :each
  (fn [f]
    (rf.ssr.install/reset-installed-payloads!)
    (rf.ssr.suspense/reset-failed-boundaries!)
    (f))
  (rf.test-support/make-reset-runtime-fixture
    {:adapter #?(:clj rf.ssr/adapter :cljs rf.adapter.reagent-slim/adapter)
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
    (doseq [bad [{:id :a} {:fallback [:p]} "nope"]]
      (is (= :rf.error/suspense-boundary-invalid-attrs
             (try (apply boundary [bad [:p "body"]])
                  nil
                  (catch #?(:clj Exception :cljs :default) e
                    (:rf.error/id (ex-data e)))))
          (str "attrs " (pr-str bad) " raise the documented error id")))))

(deftest one-tree-hashes-identically-for-both-hosts
  (testing "the component canonicalises to the #fn[] token, so the SHARED
            tree hashes to ONE pinned literal on the JVM and on every client
            host — the property a reader-conditional card-slot could never
            have (it would make the two hosts hash structurally different
            trees)"
    (let [tree [:section.cards
                [boundary {:id :card.revenue :fallback [:p "loading"]}
                 [:div "body"]]]]
      (is (= "1db00ca8" (rf.ssr/render-tree-hash tree))
          "both hosts hash the shared tree to this literal")
      (testing "a DIFFERENT boundary id changes the hash (the hash is
                not blind to the component's attrs)"
        (is (not= (rf.ssr/render-tree-hash tree)
                  (rf.ssr/render-tree-hash
                    [:section.cards
                     [boundary {:id :card.other :fallback [:p "loading"]}
                      [:div "body"]]])))))))

(deftest the-failed-set-path-is-under-the-reserved-ssr-key
  (testing "the slot lives under the already-reserved :rf.runtime/ssr
            runtime-db key, a sibling of the :hydration metadata"
    (is (= [:rf.runtime/ssr :streaming :failed-boundaries]
           rf.ssr.suspense/failed-boundaries-path))))

;; ---- server host -----------------------------------------------------------

#?(:clj
   (deftest shell-walk-defers-a-component-boundary
     (testing "the shell walker resolves the callable head, sees the
               marker it expands to, and registers a continuation —
               the same walker protocol a bare marker takes"
       (let [fid :test/shell-walk]
         (rf/reg-sub :card/by-id (fn [db [_ id]] (get-in db [:cards id])))
         (rf/reg-event :server-init {:platforms #{:server}}
           (fn [_ _] {:db {:cards {:revenue {:title "Revenue" :value 42375}}}}))
         (rf/make-frame {:id fid :platform :server :initial-events [[:server-init]]})
         (let [tree  [:section.cards
                      [boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
                       [card-view :revenue]]]
               {:keys [shell-html continuations]}
               (rf/with-frame fid (rf.ssr/streaming-render-shell tree))]
           (is (= (str "<section class=\"cards\"><template data-rf2-suspense-id=\":card.revenue\" "
                       "data-rf2-suspense-fallback=\"1\"><div class=\"card skeleton\">"
                       "<h3>Loading revenue</h3></div></template></section>")
                  shell-html)
               "the DECLARED fallback ships inline as a <template>, with no phantom element")
           (is (= [{:id      :card.revenue
                    :html    "<div class=\"card\"><h3>Revenue</h3><p class=\"value\">42375</p></div>"
                    :failed? false}]
                  (mapv #(select-keys (rf/with-frame fid (rf.ssr/streaming-render-continuation fid %))
                                      [:id :html :failed?])
                        continuations))
               "the one continuation drains the deferred body"))))))

#?(:clj
   (deftest failed-continuations-ride-the-final-payloads-runtime-slice
     (testing "the server knows :failed? per continuation, and it rides
               the serialisable runtime slice so the client can render the
               declared fallback"
       (let [fid :test/failed-payload]
         (rf/reg-sub :card/by-id (fn [db [_ id]] (get-in db [:cards id])))
         (rf/make-frame {:id fid :platform :server})
         (let [tree      [:section.cards
                          [boundary {:id :card.flaky :fallback [card-skeleton :flaky]}
                           [throwing-card]]]
               {:keys [continuations]} (rf/with-frame fid (rf.ssr/streaming-render-shell tree))
               outcomes  (mapv #(rf/with-frame fid (rf.ssr/streaming-render-continuation fid %))
                               continuations)
               failed    (into #{} (comp (filter :failed?) (map :id)) outcomes)]
           (is (= #{:card.flaky} failed) "the drain reports the failure")
           (testing "the set lands in the payload's runtime-db slice"
             (let [payload (rf/with-frame fid
                             (rf.ssr/streaming-build-final-payload
                               fid "deadbeef"
                               {:version 1
                                :payload :rf.ssr.payload/whole-app-db
                                :failed-boundaries failed}))]
               (is (= #{:card.flaky}
                      (get-in (:rf/runtime-db payload) rf.ssr.suspense/failed-boundaries-path)))))
           (testing "nothing failed contributes NO key — an ordinary page
                     carries nothing extra on the wire"
             (let [payload (rf/with-frame fid
                             (rf.ssr/streaming-build-final-payload
                               fid "deadbeef"
                               {:version 1
                                :payload :rf.ssr.payload/whole-app-db
                                :failed-boundaries #{}}))]
               (is (nil? (get-in (:rf/runtime-db payload) rf.ssr.suspense/failed-boundaries-path))))))))))

;; ---- client host -----------------------------------------------------------

#?(:cljs
   (deftest client-renders-the-declared-fallback-for-a-failed-boundary
     (testing "a boundary in the failed set renders its DECLARED
               fallback — the markup the failed chunk left in the DOM.
               No frame scope is established: `boundary` is a plain fn
               component, and on Reagent a plain fn cannot read the
               enclosing provider's frame from React context, so the
               render-time record is deliberately frame-free"
       (rf.ssr.suspense/record-failed-boundaries! #{:card.flaky})
       (is (= [:p "loading"]
              (boundary {:id :card.flaky :fallback [:p "loading"]} [:div "body"]))
           "the failed boundary renders its fallback")
       (is (= [:div "body"]
              (boundary {:id :card.revenue :fallback [:p "loading"]} [:div "body"]))
           "a sibling that resolved still renders its body"))))

#?(:cljs
   (deftest the-durable-set-round-trips-through-hydration
     (testing "the payload's runtime slice installs into the frame's
               runtime-db — the durable, inspectable record (distinct from
               the render-time one above)"
       (let [fid :test/client-durable]
         (rf/make-frame {:id fid :platform :client})
         (rf/dispatch-sync
           [:rf/hydrate {:rf/version 1
                         :rf/app-db  {}
                         :rf/runtime-db (assoc-in {} rf.ssr.suspense/failed-boundaries-path
                                                  #{:card.flaky})}]
           {:frame fid})
         (is (= #{:card.flaky}
                (get-in (:rf.db/runtime (rf/frame-state-value fid))
                        rf.ssr.suspense/failed-boundaries-path)))))))

#?(:cljs
   (deftest client-wraps-multiple-children-in-a-fragment
     (testing "mirrors the walker's continuation-subtree construction, so
               the client's rendered structure matches the server's
               resolved-subtree html exactly (a fragment emits no DOM)"
       (is (= [:div "one"]
              (boundary {:id :b :fallback [:p]} [:div "one"]))
           "a lone child renders as itself — no wrapper")
       (is (= [:<> [:div "one"] [:div "two"]]
              (boundary {:id :b :fallback [:p]} [:div "one"] [:div "two"]))
           "several children are spliced into a fragment"))))
