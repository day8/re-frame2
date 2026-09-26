(ns re-frame.ssr-hydrate-frame-value-test
  "The client hydration entry points take their frame as a frame id OR as the
  frame VALUE `rf/make-frame` returns, and both spellings reach the same frame.

  `hydrate!`, `hydrate-page!` and `verify-hydration!` normalise the target
  through `re-frame.frame/frame-target->id` before anything keys on it: the
  payload `:rf/frame-id` comparison, the install ledger, the incarnation check
  that gates the verify step, the verify step's own runtime-db read, and the
  failed-root record. A value that reached any of those un-normalised would
  match no frame record, so the seed would land (dispatch normalises) while
  the mismatch check silently did not run.

  Every assertion here is posture-independent. The verify step is witnessed
  on a frame carrying `:ssr {:on-mismatch :hard-error}`, where a detected
  mismatch THROWS in every build, so a verify step that did not run is a
  missing throw rather than a missing trace; and the failed-root record is
  read off the always-on error axis."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private client-tree [:div.app [:span "client-render"]])

(def ^:private policy {:version 1 :payload [:count]})

(defn- client-target
  "Make a `:client` frame under `id` whose detected mismatch throws, and return
  the `:frame` argument in the requested `spelling`: the id itself, or the
  frame value `make-frame` returned."
  [spelling id]
  (let [frame-value (rf/make-frame {:id       id
                                    :platform :client
                                    :ssr      {:detect-mismatch? true
                                               :on-mismatch      :hard-error}})]
    (case spelling
      :id    id
      :value frame-value)))

(defn- server-payload
  "The payload a server builds for frame `id` with body hash `render-hash`.
  `:stamped` carries `:rf/frame-id`; `:unstamped` omits it, which is the
  documented shape for an anonymous per-request server frame."
  [stamping id render-hash]
  (rf.ssr.payload-policy/build-payload
    (when (= :stamped stamping) id)
    (rf.ssr.payload-policy/apply-policy {:count 7} policy)
    render-hash
    policy))

(def ^:private cases
  "Every spelling of the target against every stamping of the payload."
  (for [spelling [:id :value]
        stamping [:stamped :unstamped]]
    [spelling stamping]))

(defn- case-id
  "A distinct frame id per case, so no case meets another's install claim."
  [spelling stamping]
  (keyword "hydrate-frame-value" (str (name spelling) "-" (name stamping))))

(deftest hydrate-verifies-a-divergent-render-for-either-spelling
  (testing "hydrate! given a frame id or a frame value runs the verify step
            and escalates a divergent server hash under :hard-error"
    (doseq [[spelling stamping] cases]
      (let [id     (case-id spelling stamping)
            target (client-target spelling id)
            calls  (atom 0)
            thrown (try (rf.ssr/hydrate!
                          {:frame          target
                           :payload        (server-payload stamping id "server00")
                           :render-tree-fn (fn [] (swap! calls inc) client-tree)})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
        (testing (str spelling " target, " stamping " payload")
          (is (= :rf.ssr/hydration-mismatch (:rf.error/id (ex-data thrown)))
              (str "the verify step ran and escalated; got "
                   (pr-str (:rf.error/id (ex-data thrown)))))
          (is (= id (:frame (ex-data thrown)))
              "the mismatch names the frame by its id")
          (is (= "server00" (:server-hash (ex-data thrown)))
              "the payload's server hash reached the comparison")
          (is (= 1 @calls)
              ":render-tree-fn was called exactly once")
          (is (= 7 (:count (rf/app-db-value id)))
              "the seed landed in the frame the target names"))))))

(deftest hydrate-verifies-a-faithful-render-clean-for-either-spelling
  (testing "hydrate! given a frame id or a frame value verifies a faithful
            payload without escalating, and records the server hash the
            verify step read"
    (let [matched-hash (rf.ssr/render-tree-hash client-tree)]
      (doseq [[spelling stamping] cases]
        (let [id       (case-id spelling stamping)
              target   (client-target spelling id)
              payload  (server-payload stamping id matched-hash)
              calls    (atom 0)
              returned (rf.ssr/hydrate!
                         {:frame          target
                          :payload        payload
                          :render-tree-fn (fn [] (swap! calls inc) client-tree)})]
          (testing (str spelling " target, " stamping " payload")
            (is (= payload returned)
                "hydrate! returns the applied payload")
            (is (= 1 @calls)
                ":render-tree-fn was called exactly once, so verify ran")
            (is (= matched-hash
                   (get-in (rf.frame/frame-runtime-db-value id)
                           [:rf.runtime/ssr :hydration :server-hash]))
                "the server hash is stored on the frame the target names")
            (is (= 7 (:count (rf/app-db-value id)))
                "the seed landed in the frame the target names")))))))

(deftest verify-hydration-accepts-either-spelling
  (testing "verify-hydration! given a frame id or a frame value reads that
            frame's stored server hash"
    (doseq [spelling [:id :value]]
      (let [id     (keyword "verify-frame-value" (name spelling))
            target (client-target spelling id)]
        (rf.ssr/hydrate! {:frame   id
                          :payload (server-payload :stamped id "server00")})
        (testing (str spelling " target")
          (let [thrown (try (rf.ssr/verify-hydration! target client-tree)
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (= :rf.ssr/hydration-mismatch (:rf.error/id (ex-data thrown)))
                (str "a divergent tree escalates; got "
                     (pr-str (:rf.error/id (ex-data thrown)))))
            (is (= id (:frame (ex-data thrown)))
                "the mismatch names the frame by its id"))
          (is (nil? (rf.ssr/verify-hydration! target "server00"))
              "the matching hash verifies clean"))))))

(deftest hydrate-page-failure-record-names-the-frame-id
  (testing "a root booted with a frame value that fails to mount is reported
            on the always-on axis under the frame's id"
    (let [id      :hydrate-frame-value/page
          target  (client-target :value id)
          records (atom [])]
      (rf.error-emit/register-error-listener! ::root-boot
        (fn [record] (swap! records conj record)))
      (try
        (let [[outcome] (rf.ssr/hydrate-page!
                          [{:frame    target
                            :root-id  :page/main
                            :payload  (server-payload :stamped id "server00")
                            :mount-fn (fn [] (throw (ex-info "mount failed" {})))}])
              record    (first (filter #(= :rf.error/root-boot-failed (:error %))
                                       @records))]
          (is (= :failed (:status outcome)))
          (is (= :mount (:phase record))
              "the root hydrated and failed in its mount")
          (is (= id (:frame record))
              (str "the record names the frame by its id; got "
                   (pr-str (:frame record)))))
        (finally
          (rf.error-emit/unregister-error-listener! ::root-boot))))))
