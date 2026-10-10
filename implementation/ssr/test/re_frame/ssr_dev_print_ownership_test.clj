(ns re-frame.ssr-dev-print-ownership-test
  "Loading `re-frame.ssr` leaves the two dev prints to whoever is watching.

  SSR captures server-frame errors for status projection through two
  late-bind hooks that core's delivery code calls beside its listener
  fan-out — an always-on one on the error-emit records and a dev one on the
  trace delivery. A hook is not a listener, so it owns neither print: an
  unrouted error still prints its one `[re-frame2]` line and a no-silent-
  swallow warning still prints while no `:trace` listener is registered,
  with SSR loaded exactly as without it. Projection is unchanged: a server
  frame's throwing handler still answers 500.

  Each case binds `*err*` to its own `StringWriter`.

  ## Posture split

  Both prints are dev-only, so under `-Dre-frame.debug=false` every case
  asserts that NOTHING was written. The 500 holds in both postures: under
  the production gate it is the always-on hook's witness."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Both registries are cleared BEFORE the SSR reset, which reloads
;; `re-frame.ssr`: a listener another namespace left behind owns both prints,
;; while whatever `re-frame.ssr` installs at load is still in place when each
;; test runs.
(use-fixtures :each
  (fn [t]
    (rf.error-emit/clear-error-listeners!)
    (rf.trace.tooling/clear-listeners!)
    (rf.ssr.test-fixture/reset-runtime t)))

(defn- captured-err
  "Run `thunk` with `*err*` bound to a fresh writer; return what it wrote."
  [thunk]
  (let [w (java.io.StringWriter.)]
    (binding [*err* w]
      (thunk))
    (str w)))

(defn- printed-lines [s]
  (filterv #(str/starts-with? % "[re-frame2]") (str/split-lines s)))

(defn- expect-one-line
  "In dev, `out` holds exactly one `[re-frame2]` line containing every string
  in `needles`; under the production gate, `out` is empty."
  [out needles]
  (if rf.interop/debug-enabled?
    (let [lines (printed-lines out)]
      (is (= 1 (count lines)) (str "exactly one [re-frame2] line; got " (pr-str out)))
      (doseq [needle needles]
        (is (str/includes? (str (first lines)) needle)
            (str "the line names " needle "; got " (pr-str (first lines))))))
    (is (= "" out) (str "the production gate prints nothing; got " (pr-str out)))))

(defn- ssr-ids
  "The ids in a listener registry that `re-frame.ssr` owns."
  [registry]
  (filterv #(and (keyword? %) (some-> (namespace %) (str/starts-with? "re-frame.ssr")))
           (keys registry)))

(deftest ssr-registers-no-listener
  (is (= [] (ssr-ids @@#'rf.error-emit/listeners)) "the :errors registry")
  (is (= [] (ssr-ids @@#'rf.trace.tooling/listeners)) "the :trace registry"))

(deftest an-unrouted-error-on-a-client-frame-prints
  (rf/make-frame {:id :ownership/client :platform :client})
  (expect-one-line
    (captured-err #(rf/dispatch-sync [:ownership/typo] {:frame :ownership/client}))
    [":rf.error/no-such-handler" "event :ownership/typo" "frame :ownership/client"]))

(deftest a-no-silent-swallow-warning-prints
  (expect-one-line
    (captured-err #(rf/configure! {:epoch-histroy {:depth 5}}))
    [":rf.warning/unknown-configure-key — " ":epoch-histroy"]))

(deftest a-server-frame-error-prints-and-still-projects
  (rf/make-frame {:id       :ownership/server
                  :platform :server
                  :ssr      {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
  (rf/reg-event :ownership/srv-throws
                (fn [_ _] (throw (ex-info "server kaboom" {:cause :test}))))
  (testing "the unrouted error prints its line"
    (expect-one-line
      (captured-err #(rf/dispatch-sync [:ownership/srv-throws] {:frame :ownership/server}))
      [":rf.error/handler-exception" "server kaboom"
       "event :ownership/srv-throws" "frame :ownership/server"]))
  (testing "the projection captured it, in either posture"
    (is (= 500 (:status (:response (rf.ssr/flush-response-result! :ownership/server)))))))
