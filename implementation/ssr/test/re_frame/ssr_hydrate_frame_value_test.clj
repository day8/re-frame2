(ns re-frame.ssr-hydrate-frame-value-test
  "`hydrate!`, `hydrate-page!` and `verify-hydration!` take their frame as an id
  or as the frame VALUE `rf/make-frame` returns, and both reach the same frame.
  A value that reached the frame-id check, the install ledger, the incarnation
  check or the runtime-db read un-normalised would match no frame record, so
  the seed would land while the mismatch check silently did not run.

  The verify step is read off the `:on-mismatch :hard-error` throw and the
  failed-root record off the always-on error axis, so every assertion holds in
  either posture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private client-tree [:div.app [:span "client-render"]])

(defn- client-target
  "Make a `:client` frame under `id` whose detected mismatch throws, and return
  it as the `:frame` argument in `spelling`: the id, or the frame value."
  [spelling id]
  (let [frame-value (rf/make-frame {:id       id
                                    :platform :client
                                    :ssr      {:on-mismatch :hard-error}})]
    (case spelling
      :id    id
      :value frame-value)))

(defn- server-payload
  "The payload a server builds for frame `id`, stamped with its `:rf/frame-id`."
  [id render-hash]
  (let [policy {:version 1 :payload [:count]}]
    (rf.ssr.payload-policy/build-payload
      id
      (rf.ssr.payload-policy/apply-policy {:count 7} policy)
      render-hash
      policy)))

(defn- mismatch-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (select-keys (ex-data e) [:rf.error/id :frame :server-hash]))))

(deftest hydrate-verifies-a-divergent-render-for-either-spelling
  (testing "hydrate! seeds the named frame and its verify step escalates a
            divergent server hash"
    (doseq [spelling [:id :value]]
      (let [id     (keyword "hydrate-frame-value" (name spelling))
            target (client-target spelling id)]
        (is (= {:rf.error/id :rf.ssr/hydration-mismatch :frame id :server-hash "server00"}
               (mismatch-of #(rf.ssr/hydrate! {:frame          target
                                               :payload        (server-payload id "server00")
                                               :render-tree-fn (constantly client-tree)})))
            (str spelling " target"))))))

(deftest verify-hydration-accepts-either-spelling
  (testing "verify-hydration! reads the named frame's stored server hash"
    (doseq [spelling [:id :value]]
      (let [id     (keyword "verify-frame-value" (name spelling))
            target (client-target spelling id)]
        (rf.ssr/hydrate! {:frame id :payload (server-payload id "server00")})
        (is (= {:rf.error/id :rf.ssr/hydration-mismatch :frame id :server-hash "server00"}
               (mismatch-of #(rf.ssr/verify-hydration! target client-tree)))
            (str spelling " target: a divergent tree escalates"))
        (is (nil? (rf.ssr/verify-hydration! target "server00"))
            (str spelling " target: the matching hash verifies clean"))))))

(deftest hydrate-page-failure-record-names-the-frame-id
  (testing "a root booted with a frame value that fails to mount is reported on
            the always-on axis under the frame's id"
    (let [id      :hydrate-frame-value/page
          records (atom [])]
      (rf.error-emit/register-error-listener! ::root-boot
        (fn [record] (swap! records conj record)))
      (try
        (rf.ssr/hydrate-page!
          [{:frame    (client-target :value id)
            :root-id  :page/main
            :payload  (server-payload id "server00")
            :mount-fn (fn [] (throw (ex-info "mount failed" {})))}])
        (is (= [{:phase :mount :frame id}]
               (for [r @records :when (= :rf.error/root-boot-failed (:error r))]
                 (select-keys r [:phase :frame]))))
        (finally
          (rf.error-emit/unregister-error-listener! ::root-boot))))))
