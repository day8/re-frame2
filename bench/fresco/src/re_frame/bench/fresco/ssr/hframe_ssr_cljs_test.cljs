(ns re-frame.bench.fresco.ssr.hframe-ssr-cljs-test
  "Request identity stays out of deterministic markup and ambient carries resolve the request frame."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]
            [re-frame.bench.fresco.ssr.entry :as rf.bench.fresco.ssr.entry]
            [re-frame.bench.fresco.ssr.fixtures :as rf.bench.fresco.ssr.fixtures]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.bench.fresco.arm1.lang :refer [defview]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; `:ambient-frame nil`, where the rest of the SSR suite takes the
     ;; fixture's default — and it is load-bearing rather than tidiness.
     ;; The default root-binds `frame/*current-frame*` to `:rf/default`,
     ;; which is a CARRIED tier-1 stamp; the refusal withdraws the ambient
     ;; FIND and never the carrying, so an ambient carry inside a body
     ;; would resolve `:rf/default` and succeed. That is core behaving
     ;; exactly as specified, and it is not the configuration a server
     ;; request has: `rf.bench.fresco.ssr.entry/render` establishes no scope and a host calls
     ;; it at top of stack. Measuring the refusal against the fixture's own
     ;; stamp would be measuring the fixture. (The mismatched-scope case is
     ;; interesting in its own right and is pinned deliberately, in
     ;; `ssr/entry_cljs_test`.)
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.ssr.fixtures/register!) (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private !ambient
  "What the ambient carry answered inside a server body — the ex-data of
  its refusal, or `::no-throw` with the value it produced."
  (atom ::unset))

(defview discreet
  "Reads the request's frame and keeps it OUT of the markup — the
  documented correct shape. What it renders is a subscription value, so
  the boundary is an ordinary one."
  [_]
  (rf.bench.fresco.front.intent/hframe)
  [:span.row (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))])

(defview indiscreet
  "Renders the per-request id INTO the markup. This is the authorable
  hazard the docstring warns about, written on purpose so the
  determinism check can be watched failing."
  [_]
  [:span.row {:data-frame (str (rf.bench.fresco.front.intent/hframe))}])

(defview carrying
  "Tries the AMBIENT carry — `(rf/capture-frame)`, 0-arity — inside a
  server body, and records what it got. It catches its own throw so the
  render completes and the row can read the payload rather than a
  `renderToString` failure."
  [_]
  (reset! !ambient
          (try [::no-throw (rf/capture-frame)]
               (catch :default e (ex-data e))))
  [:span.row (str (rf.bench.fresco.front.intent/hframe))])

(defn- request [hiccup]
  {:hiccup   hiccup
   :snapshot (:snapshot (rf.bench.fresco.ssr.fixtures/row "dogfood-snapshot"))
   :payload  rf.bench.fresco.ssr.fixtures/dogfood-payload-keys})

(deftest a-body-that-reads-the-id-and-keeps-it-out-of-markup-is-deterministic
  (let [{:keys [identical? differs-at] a :first b :second}
        (rf.bench.fresco.ssr.entry/render-twice (request [discreet {}]))]
    (is (= [true true false]
           [identical? (not= (:frame-id a) (:frame-id b)) (str/includes? (:document a) (name (:frame-id a)))])
        (str "two requests, byte-identical documents, the id nowhere in the markup; first difference at "
             differs-at))))

(deftest a-body-that-renders-the-id-breaks-determinism-and-the-check-says-so
  ;; The row that makes the one above mean something: the render-twice byte
  ;; comparison, watched failing on the authorable hazard.
  (let [{:keys [identical? differs-at] a :first} (rf.bench.fresco.ssr.entry/render-twice (request [indiscreet {}]))]
    (is (= [false true true]
           [identical? (some? differs-at) (str/includes? (:document a) (name (:frame-id a)))]))))

(deftest the-ambient-carry-is-admitted-server-side-to-the-requests-own-frame
  ;; The core answers `(rf/capture-frame)` with the extent's declared frame,
  ;; and `with-frame` declares it over the server render extent too.
  (reset! !ambient ::unset)
  (let [{:keys [frame-id]} (rf.bench.fresco.ssr.entry/render (request [carrying {}]))
        [tag value]        @!ambient]
    (is (= [::no-throw frame-id] [tag (:frame value)])
        (pr-str @!ambient))))
