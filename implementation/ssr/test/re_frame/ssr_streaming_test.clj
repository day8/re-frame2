(ns re-frame.ssr-streaming-test
  "Streaming SSR — `:rf/suspense-boundary` walker, continuation drain,
  failure semantics, per-subtree hydration delta. Per Spec 011 §Streaming
  SSR (rf2-ojakd / rf2-olb64 (a))."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

(defn- reset+reg-test-handlers
  "Reset the runtime via the canonical fixture, then re-register the
  test-local event handlers that the fixture's `clear-all!` step wiped."
  [test-fn]
  (tf/reset-runtime
    (fn []
      (rf/reg-event-db :rf.test/noop     (fn [db _] db))
      (rf/reg-event-db :rf.test/seed-db  (fn [_ [_ new-db]] new-db))
      (test-fn))))

(use-fixtures :each reset+reg-test-handlers)

(defn- with-trace-capture
  "Capture every emitted trace event into `coll-atom` during `(body-fn)`.
  Removes the listener on exit so test isolation holds."
  [coll-atom body-fn]
  (let [k (str (gensym "streaming-test-cb"))]
    (trace/register-trace-cb! k (fn [ev] (swap! coll-atom conj ev)))
    (try (body-fn)
         (finally
           (trace/remove-trace-cb! k)))))

(rf/reg-event-db :rf.test/noop (fn [db _] db))
(rf/reg-event-db :rf.test/seed-db (fn [_ [_ new-db]] new-db))

(defn- make-frame
  "Register a per-request server frame and seed its app-db via the
  `:on-create` event so the value lands inside the frame's container,
  not on :rf/default."
  [{:keys [db on-create]}]
  (let [fid (keyword "rf.frame" (str (gensym "")))]
    (rf/reg-frame fid
      {:doc       "streaming-test frame"
       :platform  :server
       :on-create (or on-create
                      (if db
                        [:rf.test/seed-db db]
                        [:rf.test/noop]))})
    fid))

(deftest render-shell-emits-fallback-template
  (testing "Shell walk emits a `<template>` fallback at the boundary and registers a continuation"
    (let [tree   [:div
                  [:h1 "Header"]
                  [:rf/suspense-boundary
                   {:id :test/comments :fallback [:p "Loading…"]}
                   [:section.comments "Body"]]
                  [:footer "Footer"]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations)) "one continuation registered")
      (is (= :test/comments (-> continuations first :id)) "id propagates")
      (is (str/includes? shell-html "<h1>Header</h1>") "shell content above boundary preserved")
      (is (str/includes? shell-html "<footer>Footer</footer>") "shell content below boundary preserved")
      (is (str/includes? shell-html "data-rf2-suspense-id=\":test/comments\"") "boundary id stamped")
      (is (str/includes? shell-html "data-rf2-suspense-fallback=\"1\"") "fallback marker stamped")
      (is (str/includes? shell-html "<p>Loading…</p>") "fallback hiccup rendered inline"))))

(deftest render-shell-handles-multiple-boundaries
  (testing "Multiple boundaries register multiple continuations in document order"
    (let [tree [:main
                [:rf/suspense-boundary {:id :a :fallback [:p "A loading"]} [:p "A body"]]
                [:rf/suspense-boundary {:id :b :fallback [:p "B loading"]} [:p "B body"]]
                [:rf/suspense-boundary {:id :c :fallback [:p "C loading"]} [:p "C body"]]]
          {:keys [continuations]} (streaming/render-shell tree)]
      (is (= [:a :b :c] (mapv :id continuations)) "FIFO registration in document order"))))

(deftest render-shell-handles-nested-boundaries
  (testing "Boundary nested inside DOM children registers correctly"
    (let [tree [:div
                [:section
                 [:rf/suspense-boundary {:id :nested :fallback [:p "nested loading"]}
                  [:p "nested body"]]]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations)))
      (is (= :nested (-> continuations first :id)))
      (is (str/includes? shell-html "<section>"))
      (is (str/includes? shell-html "data-rf2-suspense-id=\":nested\"")))))

(deftest render-shell-rejects-malformed-boundary
  (testing "Boundary without {:id … :fallback …} attrs throws structurally"
    (let [bad [:rf/suspense-boundary {:id :missing-fallback}
               [:p "body"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/suspense-boundary-invalid-attrs"
                            (streaming/render-shell bad))))))

(deftest render-continuation-resolves-and-deltas
  (testing "Continuation render returns subtree HTML + (empty) delta when db is unchanged across render"
    (let [fid (make-frame {:db {:initial true}})
          tree [:rf/suspense-boundary {:id :c :fallback [:p "..."]}
                [:ul [:li "comment"]]]
          {:keys [continuations]} (streaming/render-shell tree)
          entry (first continuations)
          {:keys [id html delta failed?]} (streaming/render-continuation fid entry)]
      (is (= :c id))
      (is (not failed?))
      (is (= "<ul><li>comment</li></ul>" html))
      (is (map? delta) "delta is a map (possibly empty when no app-db keys changed during render)"))))

(deftest render-continuation-failure-emits-trace-and-inlines-fallback
  (testing "Subtree-render throw → :rf.ssr/suspense-boundary-failed trace + fallback materialised"
    (let [fid    (make-frame {:db {}})
          throws (fn [] (throw (ex-info "boom" {})))
          ;; Attach a fn-headed component that throws during render
          tree   [:rf/suspense-boundary {:id :flaky :fallback [:p "Loading…"]}
                  [throws]]
          {:keys [continuations]} (streaming/render-shell tree)
          entry  (assoc (first continuations) :fallback [:p "Loading…"])
          captured (atom [])
          result (with-trace-capture captured
                   #(streaming/render-continuation fid entry))]
      (is (:failed? result) ":failed? truthy")
      (is (nil? (:delta result)) "delta omitted on failure")
      (is (= "<p>Loading…</p>" (:html result)) "fallback hiccup materialised in place")
      (is (some #(= :rf.ssr/suspense-boundary-failed (:operation %))
                @captured)
          ":rf.ssr/suspense-boundary-failed trace emitted"))))

(deftest duplicate-id-emits-trace-and-keeps-last
  (testing "Two boundaries with the same :id emit :rf.error/suspense-boundary-duplicate-id"
    (let [tree [:div
                [:rf/suspense-boundary {:id :dup :fallback [:p "first"]} [:p "first body"]]
                [:rf/suspense-boundary {:id :dup :fallback [:p "second"]} [:p "second body"]]]
          captured (atom [])
          {:keys [continuations]}
          (with-trace-capture captured #(streaming/render-shell tree))]
      (is (= 1 (count continuations)) "only one continuation survives dedup")
      (is (some #(= :rf.error/suspense-boundary-duplicate-id (:operation %))
                @captured)
          ":rf.error/suspense-boundary-duplicate-id trace emitted"))))

(deftest build-final-payload-shape
  (testing "Final payload carries the canonical :rf/hydration-payload shape"
    (let [fid (make-frame {:db {:articles [{:id "a"}]}})
          ;; rf2-gtgf9: explicit fail-closed payload policy. This test
          ;; pins the shape of the canonical payload — opt in to whole-
          ;; app-db so the existing :rf/app-db assertion still holds.
          payload (streaming/build-final-payload fid "deadbeef"
                                                 {:version       7
                                                  :schema-digest "abc123"
                                                  :payload-policy :rf.ssr.payload/whole-app-db})]
      (is (= 7 (:rf/version payload)))
      (is (= fid (:rf/frame-id payload)))
      (is (= "deadbeef" (:rf/render-hash payload)))
      (is (= "abc123" (:rf/schema-digest payload)))
      (is (= {:articles [{:id "a"}]} (:rf/app-db payload))))))

(deftest late-bind-hooks-published
  (testing "All three :ssr.streaming/* late-bind hooks resolve to the streaming fns"
    (is (= streaming/render-shell        (re-frame.late-bind/get-fn :ssr.streaming/render-shell!)))
    (is (= streaming/render-continuation (re-frame.late-bind/get-fn :ssr.streaming/render-continuation!)))
    (is (= streaming/build-final-payload (re-frame.late-bind/get-fn :ssr.streaming/build-final-payload)))))

(deftest facade-exposes-streaming-surface
  (testing "`re-frame.ssr` re-exports the streaming public surface"
    (is (= streaming/render-shell        ssr/streaming-render-shell))
    (is (= streaming/render-continuation ssr/streaming-render-continuation))
    (is (= streaming/build-final-payload ssr/streaming-build-final-payload))))
