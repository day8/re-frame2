(ns re-frame.ssr-hydrate-frame-value-test
  "`hydrate!` and `hydrate-page!` take their frame as an id or as the frame
  VALUE `rf/make-frame` returns, and both reach the same frame. A value that
  reached the frame-id check, the install ledger or the incarnation check
  un-normalised would match no frame record.

  The failed-root record is read off the always-on error axis, so every
  assertion holds in either posture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- client-target
  "Make a `:client` frame under `id`, and return it as the `:frame` argument in
  `spelling`: the id, or the frame value."
  [spelling id]
  (let [frame-value (rf/make-frame {:id id :platform :client})]
    (case spelling
      :id    id
      :value frame-value)))

(defn- server-payload
  "The payload a server builds for frame `id`, stamped with its `:rf/frame-id`."
  [id]
  (let [policy {:payload [:count]}]
    (rf.ssr.payload-policy/build-payload
      id
      (rf.ssr.payload-policy/apply-policy {:count 7} policy)
      policy)))

(deftest hydrate-seeds-the-named-frame-for-either-spelling
  (testing "hydrate! passes the payload's frame-id check, installs the payload
            and seeds the named frame, whichever spelling names it"
    (doseq [spelling [:id :value]]
      (let [id     (keyword "hydrate-frame-value" (name spelling))
            target (client-target spelling id)
            payload (server-payload id)]
        (is (= [payload {:count 7} {:version 1}]
               [(rf.ssr/hydrate! {:frame target :payload payload})
                (rf/app-db-value id)
                (get-in (:rf.db/runtime (rf/frame-state-value id))
                        [:rf.runtime/ssr :hydration])])
            (str spelling " target"))))))

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
            :payload  (server-payload id)
            :mount-fn (fn [] (throw (ex-info "mount failed" {})))}])
        (is (= [{:phase :mount :frame id}]
               (for [r @records :when (= :rf.error/root-boot-failed (:rf.error/id r))]
                 (select-keys r [:phase :frame]))))
        (finally
          (rf.error-emit/unregister-error-listener! ::root-boot))))))
