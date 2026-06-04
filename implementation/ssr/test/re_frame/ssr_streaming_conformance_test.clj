(ns re-frame.ssr-streaming-conformance-test
  "Drive the `:ssr/streaming` conformance fixture against the live ssr
  runtime. Per rf2-ojakd / rf2-olb64 (a) — the fixture pins the
  wire-shape contract for `:rf/suspense-boundary`; this test is the
  fixture's executable counterpart.

  Sibling to `re-frame.ssr-conformance-test` which drives the full
  `ssr-*.edn` corpus through the more elaborate runner (handler
  realisation, frame setup, request-result assertions). The streaming
  fixture is a smaller direct-call shape — `:ssr.streaming/render-shell`,
  `:ssr.streaming/render-continuation`, `:ssr.streaming/build-final-payload`
  — and runs cleaner as a dedicated focused test that mirrors the
  conformance fixture step-for-step."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(defn- reset+reg-fixture-handlers
  [test-fn]
  (tf/reset-runtime
    (fn []
      ;; Register the four views the fixture's :fixture/handlers names.
      (rf/reg-view ^{:rf/id :streaming.test/article-list} _al []
        [:ul.articles [:li "Article A"] [:li "Article B"]])
      (rf/reg-view ^{:rf/id :streaming.test/comments-section} _cs []
        [:ul.comments [:li "First comment"] [:li "Nice piece"]])
      (rf/reg-view ^{:rf/id :streaming.test/related} _rl []
        [:ul.related [:li "Related X"] [:li "Related Y"]])
      (rf/reg-view ^{:rf/id :streaming.test/root} _root []
        [:main
         [:h1 "News"]
         [:streaming.test/article-list]
         [:rf/suspense-boundary
          {:id :streaming.test/comments
           :fallback [:p.fallback "Loading comments…"]}
          [:streaming.test/comments-section]]
         [:rf/suspense-boundary
          {:id :streaming.test/related
           :fallback [:p.fallback "Loading related…"]}
          [:streaming.test/related]]
         [:footer "End"]])
      (test-fn))))

(use-fixtures :each reset+reg-fixture-handlers)

(defn- load-streaming-fixture []
  (let [raw   (slurp (io/file "../../spec/conformance/fixtures/ssr-streaming.edn"))
        fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)" ":rf.machine.timer/$1")]
    (edn/read-string fixed)))

(deftest streaming-fixture-shell-walk-matches-pin
  (testing "render-shell emits the shell HTML + 2 continuations the fixture pins"
    (let [fixture (load-streaming-fixture)
          shell-call (->> (:fixture/calls fixture)
                          (filter #(= :ssr.streaming/render-shell (:call %)))
                          first)
          {:keys [shell-html continuations]}
          (ssr/streaming-render-shell (:input shell-call))
          expect (:expect shell-call)]
      (testing "shell-html includes every fixture-pinned substring"
        (doseq [s (:shell-html-includes expect)]
          (is (str/includes? shell-html s)
              (str "shell-html missing: " (pr-str s)))))
      (testing "continuations register in fixture-pinned FIFO order"
        (is (= (mapv :id (:continuations expect))
               (mapv :id continuations)))))))

(deftest streaming-fixture-resolve-continuations-match-pin
  (testing "Each continuation's HTML matches the fixture's :html-includes pin"
    (let [fixture (load-streaming-fixture)
          cont-calls (->> (:fixture/calls fixture)
                          (filter #(= :ssr.streaming/render-continuation (:call %))))
          ;; A frame to drain against — :rf/default. The fixture sets
          ;; :platform :server via :fixture/frame-config; reset-runtime
          ;; creates :rf/default and reset+reg-fixture-handlers wired the
          ;; views. We drain against that frame.
          fid     :rf/default]
      (doseq [{:keys [input expect]} cont-calls]
        (let [out (ssr/streaming-render-continuation fid input)]
          (doseq [s (:html-includes expect)]
            (is (str/includes? (:html out) s)
                (str "continuation " (:id input) " missing: " (pr-str s))))
          (is (= (:failed? expect) (:failed? out))
              (str "continuation " (:id input) " :failed? mismatch")))))))

(deftest streaming-fixture-final-payload-shape-matches-pin
  (testing "build-final-payload emits the four canonical :rf/* keys"
    (let [fixture (load-streaming-fixture)
          fp-call (->> (:fixture/calls fixture)
                       (filter #(= :ssr.streaming/build-final-payload (:call %)))
                       first)
          input   (:input fp-call)
          expect  (:expect fp-call)
          payload (ssr/streaming-build-final-payload
                    :rf/default
                    (:render-hash input)
                    (dissoc input :render-hash))]
      (testing "payload carries every fixture-pinned key"
        (is (clojure.set/subset? (:payload-keys expect)
                                 (set (keys payload)))))
      (testing ":rf/version matches the pin"
        (is (= (:rf/version expect) (:rf/version payload)))))))

(deftest streaming-fixture-wire-order-pinned
  (testing "Fixture's :fixture/wire-order block enumerates the four chunk kinds in spec order"
    (let [fixture (load-streaming-fixture)
          wire    (:fixture/wire-order fixture)
          kinds   (mapv :kind wire)]
      (is (= [:shell :resolved :resolved :final-payload :close] kinds)
          "wire-order pins shell → N resolved → final-payload → close"))))

;; ===========================================================================
;; Nested-boundary fixture — rf2-sgvn6 / rf2-b1v8v. The inner boundary
;; registers DURING the outer continuation render and drains at the FIFO
;; tail. Drives spec/conformance/fixtures/ssr-streaming-nested.edn.
;; ===========================================================================

(defn- load-nested-fixture []
  (edn/read-string
    (slurp (io/file "../../spec/conformance/fixtures/ssr-streaming-nested.edn"))))

(defn- reg-nested-fixture-handlers! []
  (rf/reg-view ^{:rf/id :streaming-nested.test/inner-section} _ni []
    [:div.inner-body "inner body content"])
  (rf/reg-view ^{:rf/id :streaming-nested.test/outer-section} _no []
    [:section.outer
     [:p "outer content above inner"]
     [:rf/suspense-boundary
      {:id :streaming-nested.test/inner
       :fallback [:p.fallback "Loading inner…"]}
      [:streaming-nested.test/inner-section]]])
  (rf/reg-view ^{:rf/id :streaming-nested.test/root} _nr []
    [:main
     [:h1 "Nested"]
     [:rf/suspense-boundary
      {:id :streaming-nested.test/outer
       :fallback [:p.fallback "Loading outer…"]}
      [:streaming-nested.test/outer-section]]
     [:footer "End"]]))

(deftest nested-fixture-shell-registers-only-outer
  (testing "rf2-sgvn6: the shell walk registers ONLY the outer
            continuation; the inner is buried in the unresolved outer
            subtree and its fallback is NOT in the shell"
    (reg-nested-fixture-handlers!)
    (let [fixture    (load-nested-fixture)
          shell-call (->> (:fixture/calls fixture)
                          (filter #(= :ssr.streaming/render-shell (:call %)))
                          first)
          {:keys [shell-html continuations]}
          (ssr/streaming-render-shell (:input shell-call))
          expect     (:expect shell-call)]
      (doseq [s (:shell-html-includes expect)]
        (is (str/includes? shell-html s)
            (str "nested shell-html missing: " (pr-str s))))
      (doseq [s (:shell-html-excludes expect)]
        (is (not (str/includes? shell-html s))
            (str "nested shell-html must NOT carry (inner is buried): " (pr-str s))))
      (is (= (mapv :id (:continuations expect))
             (mapv :id continuations))
          "shell registers exactly the outer continuation"))))

(deftest nested-fixture-outer-drain-registers-inner-fifo
  (testing "rf2-sgvn6 / rf2-b1v8v: draining the outer continuation resolves
            cleanly, carries the inner FALLBACK template inline, defers the
            inner body, and returns the inner continuation on :continuations
            (FIFO tail). Draining the inner then resolves the inner body."
    (reg-nested-fixture-handlers!)
    (let [fixture    (load-nested-fixture)
          cont-calls (->> (:fixture/calls fixture)
                          (filter #(= :ssr.streaming/render-continuation (:call %))))
          outer-call (first cont-calls)
          inner-call (second cont-calls)
          fid        :rf/default]
      ;; Drain the outer.
      (let [out    (ssr/streaming-render-continuation fid (:input outer-call))
            expect (:expect outer-call)]
        (is (= (:failed? expect) (:failed? out))
            "outer resolves cleanly — NOT failed (the buried inner marker
             is recognised by the streaming walker, not thrown on)")
        (doseq [s (:html-includes expect)]
          (is (str/includes? (:html out) s)
              (str "outer chunk missing: " (pr-str s))))
        (doseq [s (:html-excludes expect)]
          (is (not (str/includes? (:html out) s))
              (str "outer chunk must NOT carry the deferred inner body: " (pr-str s))))
        (is (= (mapv :id (:continuations expect))
               (mapv :id (:continuations out)))
            "outer drain returns the inner continuation at the FIFO tail"))
      ;; Drain the inner.
      (let [in     (ssr/streaming-render-continuation fid (:input inner-call))
            expect (:expect inner-call)]
        (is (= (:failed? expect) (:failed? in)))
        (doseq [s (:html-includes expect)]
          (is (str/includes? (:html in) s)
              (str "inner chunk missing: " (pr-str s))))
        (is (empty? (:continuations in))
            "the inner subtree registers no further nested boundaries")))))

(deftest nested-fixture-wire-order-pinned
  (testing "rf2-sgvn6: the nested fixture's wire-order pins shell → outer
            resolved → inner resolved (FIFO tail) → final-payload → close"
    (let [fixture (load-nested-fixture)
          wire    (:fixture/wire-order fixture)
          kinds   (mapv :kind wire)]
      (is (= [:shell :resolved :resolved :final-payload :close] kinds))
      ;; The ord field pins the FIFO: outer (ord 1) before inner (ord 2).
      (let [resolved (filterv #(= :resolved (:kind %)) wire)]
        (is (= [:streaming-nested.test/outer :streaming-nested.test/inner]
               (mapv :id resolved))
            "outer resolved chunk (ord 1) precedes inner resolved chunk
             (ord 2) — the inner registered at the FIFO tail")))))
