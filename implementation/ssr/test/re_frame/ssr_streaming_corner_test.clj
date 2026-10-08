(ns re-frame.ssr-streaming-corner-test
  "Composition corners of the streaming shell walk and continuation drain —
  fragments, a double throw, a drain against a destroyed frame — plus the
  request/response side-channel privacy invariants. The common shapes are in
  `re-frame.ssr-streaming-test`; nested-boundary draining is pinned by the
  `ssr-streaming-nested` conformance fixture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(defn- reset+reg [test-fn]
  (rf.ssr.test-fixture/reset-runtime
    (fn []
      (rf/reg-event :rf.test/noop (fn [{:keys [db]} _] {:db db}))
      (test-fn))))

(use-fixtures :each reset+reg)

(defn- make-server-frame
  ([] (make-server-frame [:rf.test/noop]))
  ([initial-event]
   (let [fid (keyword "rf.frame" (str (gensym "")))]
     (rf/make-frame {:id fid :platform :server :initial-events [initial-event]})
     fid)))

(deftest streaming-and-non-streaming-fragments-agree-byte-for-byte
  ;; A boundary-free tree holds nothing the streaming walker is FOR, so its
  ;; shell must equal the non-streaming emitter's bytes, which
  ;; `re-frame.ssr-emit-test/fragment-props-map-is-not-a-child` pins.
  (doseq [tree [[:<> {:key "k"} [:div "x"]]
                [:<> {} [:div "x"]]
                [:<> {:class "nope" :id "nope" :onClick "alert(1)"} [:div "x"]]
                [:<> [:span "a"] [:div "b"]]
                [:<> (list [:div "a"]) [:div "b"]]
                [:<>]
                [:main [:<> {:key "k"} [:span "a"] [:span "b"]]]]]
    (is (= (rf.ssr.emit/render-to-string tree {})
           (:shell-html (rf.ssr.streaming/render-shell tree)))
        (pr-str tree))))

(deftest fragment-props-map-does-not-displace-a-suspense-boundary
  ;; The walker skips a fragment's props slot and still reaches the boundary
  ;; behind it, with no props EDN in the shell.
  (let [{:keys [shell-html continuations]}
        (rf.ssr.streaming/render-shell
          [:<> {:key "k"}
           [:h1 "header"]
           [:rf/suspense-boundary {:id :inside/frag :fallback [:p "loading"]} [:p "body"]]])]
    (is (= [:inside/frag] (mapv :id continuations)))
    (is (= (str "<h1>header</h1><template data-rf2-suspense-id=\":inside/frag\" "
                "data-rf2-suspense-fallback=\"1\"><p>loading</p></template>")
           shell-html))))

(deftest render-continuation-fallback-render-throw-emits-empty-html
  (testing "subtree AND fallback both throw: the drain still returns, failed,
            with empty html the client treats as a no-op"
    (is (= {:id :double-throw :html "" :delta nil :failed? true :continuations []}
           (rf.ssr.streaming/render-continuation
             (make-server-frame)
             {:id       :double-throw
              :subtree  [(fn [] (throw (ex-info "subtree boom" {})))]
              :fallback [(fn [] (throw (ex-info "fallback boom" {})))]})))))

(deftest render-continuation-after-frame-destroy-still-fails-soft
  (testing "a drain against a frame the host already destroyed returns a
            result instead of escaping"
    (let [fid (make-server-frame)
          {:keys [continuations]} (rf.ssr.streaming/render-shell
                                    [:rf/suspense-boundary {:id :after-destroy :fallback [:p "loading"]}
                                     [:p "body"]])]
      (rf/destroy-frame! fid)
      (is (= #{:id :html :delta :failed? :continuations}
             (set (keys (rf.ssr.streaming/render-continuation fid (first continuations)))))))))

;; The request slot and the response accumulator live off app-db, so neither
;; can default-leak into the hydration payload (Spec 011 §Request / §Response
;; storage substrate).

(deftest response-accumulator-not-on-app-db-privacy-invariant
  (rf/reg-event :test/server-write
    {:platforms #{:server}}
    (fn [_ _]
      {:fx [[:rf.server/set-header {:name "X-Internal-Token" :value "secret"}]
            [:rf.server/set-cookie {:name "session" :value "sess-abc"}]]}))
  (is (= {} (rf.frame/frame-app-db-value (make-server-frame [:test/server-write])))))

(deftest request-slot-not-on-app-db-privacy-invariant
  (let [fid (make-server-frame)]
    (rf.ssr.request/set-request! fid {:uri            "/secret"
                                      :request-method :get
                                      :headers        {"authorization" "Bearer SECRET_TOKEN"
                                                       "cookie"        "session=hot"}})
    (is (= {} (rf.frame/frame-app-db-value fid)))))
