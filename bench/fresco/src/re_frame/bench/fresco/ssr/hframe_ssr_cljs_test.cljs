(ns re-frame.bench.fresco.ssr.hframe-ssr-cljs-test
  "`h/frame` ON THE SERVER (the design's W8 and W11).

  The body runs on Node through the same shell path, so `h/frame` answers
  the request's own per-request gensym and per-request isolation holds by
  construction. Two things follow, and the second is the interesting one.

  **W8 — the id is process-local identity, and must never reach markup.**
  Two same-process renders take two different gensyms, so a body that
  RENDERS the value makes the document nondeterministic. That is an
  authorable hazard rather than a framework one, and it is already
  instrumented: `rf.bench.fresco.ssr.entry/render-twice`'s byte comparison is the standing
  determinism check. Both halves are asserted here — a body that reads
  the id and keeps it out of markup renders byte-identical documents, and
  a body that deliberately prints it does not. The second row is what
  stops the first from being a green gate over a check that could not
  fail.

  **W11 — the ambient carry answers the same frame as `h/frame`.** The
  raw React-context read the adapters publish is client-renderer-only,
  so a row contrasting `h/frame` against the ambient chain throwing
  *server-side for a renderer-specific reason* would assert the wrong
  fact for the wrong reason. The core answers `(rf/capture-frame)` with
  the extent's declared frame, and
  `rf.bench.fresco.front.intent/with-frame` declares it over every render
  extent, client or server — so on the server the ambient carry answers
  the request's own frame, and `h/frame` answers the same one in the same
  body.

  Runtime: Node, where `react-dom/server` resolves — the same home as
  `ssr/entry_cljs_test`; `npm run check` in bench/fresco/ compiles it."
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
     ;; `arm1/hframe_cljs_test`.)
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.ssr.fixtures/register!) (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private !seen
  "Every frame id a server body read, newest last."
  (atom []))

(def ^:private !ambient
  "What the ambient carry answered inside a server body — the ex-data of
  its refusal, or `::no-throw` with the value it produced."
  (atom ::unset))

(defview discreet
  "Reads the request's frame and keeps it OUT of the markup — the
  documented correct shape. What it renders is a subscription value, so
  the boundary is an ordinary one."
  [_]
  (swap! !seen conj (rf.bench.fresco.front.intent/hframe))
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

(deftest a-server-body-reads-the-requests-own-frame
  ;; Two requests, two per-request gensyms, and each body read its own.
  (reset! !seen [])
  (let [a (rf.bench.fresco.ssr.entry/render (request [discreet {}]))
        b (rf.bench.fresco.ssr.entry/render (request [discreet {}]))]
    (is (= [[(:frame-id a) (:frame-id b)] true]
           [@!seen (not= (:frame-id a) (:frame-id b))]))))

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
