(ns re-frame.ssr-streaming-test
  "Streaming SSR (Spec 011 §Streaming SSR): the shell walk, the continuation
  drain and its failure semantics, the per-subtree hydration delta, the final
  payload and the wire chunk builders.

  The `:rf.ssr/suspense-boundary-failed` and
  `:rf.error/suspense-boundary-duplicate-id` traces are emitted behind
  `interop/debug-enabled?` (read once at namespace load), so their assertions
  sit in `(when rf.interop/debug-enabled? …)` arms; everything else also runs
  under the production gate."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.streaming.constants :as rf.ssr.streaming.constants]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(defn- reset+reg-test-handlers [test-fn]
  (rf.ssr.test-fixture/reset-runtime
    (fn []
      (rf/reg-event :rf.test/noop    (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :rf.test/seed-db (fn [_ [_ new-db]] {:db new-db}))
      (test-fn))))

(use-fixtures :each reset+reg-test-handlers)

(defn- make-frame
  "A per-request server frame whose app-db is seeded by an `:initial-events`
  setup event, so the value lands in the frame's own container."
  [db]
  (let [fid (keyword "rf.frame" (str (gensym "")))]
    (rf/make-frame {:id             fid
                    :platform       :server
                    :initial-events [(if db [:rf.test/seed-db db] [:rf.test/noop])]})
    fid))

(deftest render-shell-fallback-is-inert-template-not-painted-dom
  ;; The fallback rides ONLY inside an inert `<template>`: nothing paints
  ;; until the client runtime materialises it.
  (is (= (str "<main><template data-rf2-suspense-id=\":news/comments\" data-rf2-suspense-fallback=\"1\">"
              "<p class=\"skeleton\">Loading comments…</p></template></main>")
         (:shell-html (rf.ssr.streaming/render-shell
                        [:main
                         [:rf/suspense-boundary
                          {:id :news/comments :fallback [:p.skeleton "Loading comments…"]}
                          [:section.comments "Body"]]])))))

(deftest render-shell-rejects-malformed-boundary
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":rf.error/suspense-boundary-invalid-attrs"
                        (rf.ssr.streaming/render-shell
                          [:rf/suspense-boundary {:id :missing-fallback} [:p "body"]]))))

(deftest render-shell-handles-multiple-boundaries
  (let [tree [:main
              [:rf/suspense-boundary {:id :a :fallback [:p "A loading"]} [:p "A body"]]
              [:rf/suspense-boundary {:id :b :fallback [:p "B loading"]} [:p "B body"]]
              [:rf/suspense-boundary {:id :c :fallback [:p "C loading"]} [:p "C body"]]]]
    (is (= [:a :b :c] (mapv :id (:continuations (rf.ssr.streaming/render-shell tree))))
        "FIFO registration in document order")))

(deftest render-continuation-resolves-and-deltas
  (testing "zero, one and several body children each drain as ONE
            continuation: nil renders empty, several splice as a fragment"
    (let [fid (make-frame {:initial true})]
      (doseq [[children html] [[[] ""]
                               [[[:ul [:li "comment"]]] "<ul><li>comment</li></ul>"]
                               [[[:p "first"] [:p "second"]] "<p>first</p><p>second</p>"]]]
        (let [{:keys [continuations]} (rf.ssr.streaming/render-shell
                                        (into [:rf/suspense-boundary {:id :c :fallback [:p "..."]}]
                                              children))]
          (is (= [{:id :c :html html :delta {} :failed? false :continuations []}]
                 (mapv #(rf.ssr.streaming/render-continuation fid %) continuations))
              (pr-str children)))))))

(deftest render-continuation-delta-ships-full-value-for-changed-nested-key
  ;; Each changed or new top-level key ships its FULL after-db value, so the
  ;; client's top-level `(into existing delta)` merge is lossless.
  (rf/reg-event :rf.test/change-nested
    (fn [{:keys [db]} _] {:db (-> db
                                  (assoc-in [:user :name] "after")
                                  (assoc :new-key :new-value))}))
  (let [fid  (make-frame {:user {:name "before" :role "admin"} :other :unchanged})
        _    (rf/reg-view ^{:rf/id :rf.test/nested-mutator} nested-mutator []
               (rf/dispatch-sync [:rf.test/change-nested] {:frame fid})
               [:p "mutated"])
        {:keys [continuations]} (rf.ssr.streaming/render-shell
                                  [:rf/suspense-boundary {:id :n :fallback [:p "..."]}
                                   [(rf/view :rf.test/nested-mutator)]])]
    (is (= {:user {:name "after" :role "admin"} :new-key :new-value}
           (:delta (rf.ssr.streaming/render-continuation fid (first continuations)))))))

(deftest render-continuation-failure-emits-trace-and-inlines-fallback
  (let [fid    (make-frame {})
        throws (fn [] (throw (ex-info "boom" {})))
        {:keys [continuations]} (rf.ssr.streaming/render-shell
                                  [:rf/suspense-boundary {:id :flaky :fallback [:p "Loading…"]}
                                   [throws]])]
    (with-trace-recorder! [captured]
      ;; The entry is drained as the shell recorded it, so the declared
      ;; `:fallback` must ride from the shell walk.
      (is (= {:id :flaky :html "<p>Loading…</p>" :delta nil :failed? true :continuations []}
             (rf.ssr.streaming/render-continuation fid (first continuations))))
      (when rf.interop/debug-enabled?
        (is (some #(= :rf.ssr/suspense-boundary-failed (:operation %)) @captured))))))

(deftest duplicate-wire-id-collision-emits-trace-and-keeps-last
  ;; Ids that differ as values but collide under `str` (`:a` vs ":a") stamp
  ;; one `data-rf2-suspense-id`, so dedup keys on the wire id: one
  ;; continuation survives, the LAST registration.
  (with-trace-recorder! [captured]
    (let [{:keys [continuations]}
          (rf.ssr.streaming/render-shell
            [:div
             [:rf/suspense-boundary {:id :a :fallback [:p "first"]} [:p "first body"]]
             [:rf/suspense-boundary {:id ":a" :fallback [:p "second"]} [:p "second body"]]])]
      (is (= [{:id ":a" :subtree [:p "second body"] :fallback [:p "second"]}] continuations))
      (when rf.interop/debug-enabled?
        (is (= [[":a" 2 :last-write-wins]]
               (->> @captured
                    (filter #(= :rf.error/suspense-boundary-duplicate-id (:operation %)))
                    (mapv (juxt #(get-in % [:tags :id]) #(get-in % [:tags :count]) :recovery)))))))))

(deftest build-final-payload-shape
  (testing "the canonical payload; the WIRE :rf/frame-id is the supplied
            stable client id, never the per-request projection frame"
    (is (= {:rf/version       7
            :rf/frame-id      :app/main
            :rf/render-hash   "deadbeef"
            :rf/schema-digest "abc123"
            :rf/app-db        {:articles [{:id "a"}]}}
           (rf.ssr.streaming/build-final-payload
             (make-frame {:articles [{:id "a"}]}) "deadbeef"
             {:version         7
              :schema-digest   "abc123"
              :payload         :rf.ssr.payload/whole-app-db
              :client-frame-id :app/main}))))
  (testing "with no :client-frame-id the anonymous request frame is OMITTED,
            and with no :version the SSR-owned protocol constant stamps it"
    (is (= {:rf/version     rf.ssr.payload-policy/pattern-protocol-version
            :rf/render-hash "deadbeef"
            :rf/app-db      {:articles [{:id "a"}]}}
           (rf.ssr.streaming/build-final-payload
             (make-frame {:articles [{:id "a"}]}) "deadbeef"
             {:payload :rf.ssr.payload/whole-app-db})))))

(deftest streaming-wire-attributes-single-sourced
  ;; Each chunk builder stamps the attribute names the client runtime reads
  ;; from the same constants namespace, so a literal hard-coded on one side
  ;; fails here.
  (let [id-attr (str rf.ssr.streaming.constants/attr-suspense-id "=\":card/revenue\"")]
    (doseq [[chunk expected]
            [[(rf.ssr.streaming/fallback-template :card/revenue "<div>fb</div>")
              (str "<template " id-attr " " rf.ssr.streaming.constants/attr-suspense-fallback
                   "=\"1\"><div>fb</div></template>")]
             [(rf.ssr.streaming/resolved-template :card/revenue "<div>ok</div>")
              (str "<template " id-attr " " rf.ssr.streaming.constants/attr-suspense-resolved
                   "=\"1\"><div>ok</div></template>")]
             [(rf.ssr.streaming/failed-template :card/revenue "<div>fb</div>")
              (str "<template " id-attr " " rf.ssr.streaming.constants/attr-suspense-resolved
                   "=\"1\" " rf.ssr.streaming.constants/attr-suspense-failed
                   "=\"1\"><div>fb</div></template>")]
             [(rf.ssr.streaming/hydrate-delta-script :card/revenue "{:k 1}")
              (str "<script " rf.ssr.streaming.constants/attr-suspense-hydrate
                   "=\":card/revenue\" type=\"application/edn\">{:k 1}</script>")]]]
      (is (= expected chunk)))))

(deftest hydrate-delta-script-escapes-breakout-and-round-trips
  (testing "a `</script>` in a string value cannot close the envelope, and a
            keyword token carrying `<` survives, so the delta reads back whole"
    (let [delta  {:public/title "</script><script>alert('xss')</script>"
                  :a<b 1
                  :tag :<}
          script (rf.ssr.streaming/hydrate-delta-script :boundary/x (pr-str delta))
          body   (second (re-find #"type=\"application/edn\">(.*?)</script>" script))]
      (is (not (str/includes? (str/lower-case body) "</script")))
      (is (= delta (edn/read-string body))))))

(deftest escape-edn-script-body-handles-char-literals
  (testing "a char literal for `\"` (printed `\\\"`) does not toggle string
            state, so a later string's `</script>` is escaped rather than
            rejected as a token breakout"
    (let [value {:x (char 34) :y "</script>"}
          body  (rf.ssr.html-helpers/escape-edn-script-body (pr-str value))]
      (is (not (str/includes? (str/lower-case body) "</script")))
      (is (= value (edn/read-string body))))))
