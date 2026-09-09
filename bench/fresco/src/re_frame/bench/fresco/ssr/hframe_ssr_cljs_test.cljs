(ns re-frame.bench.hicasso.ssr.hframe-ssr-cljs-test
  "`h/frame` ON THE SERVER (rf2-841vn — the design's W8 and W11).

  The body runs on Node through the same shell path, so `h/frame` answers
  the request's own per-request gensym and per-request isolation holds by
  construction. Two things follow, and the second is the interesting one.

  **W8 — the id is process-local identity, and must never reach markup.**
  Two same-process renders take two different gensyms, so a body that
  RENDERS the value makes the document nondeterministic. That is an
  authorable hazard rather than a framework one, and it is already
  instrumented: `rf.bench.hicasso.ssr.entry/render-twice`'s byte comparison is the standing
  determinism check. Both halves are asserted here — a body that reads
  the id and keeps it out of markup renders byte-identical documents, and
  a body that deliberately prints it does not. The second row is what
  stops the first from being a green gate over a check that could not
  fail.

  **W11 — RE-GROUNDED on the post-rf2-2rtt6.122 tree.** As designed the
  row contrasted `h/frame` answering against the ambient chain throwing
  *server-side for a renderer-specific reason*: the raw React-context read
  the adapters publish is client-renderer-only. That contrast no longer
  describes the tree. Since rf2-2rtt6.122 the ambient chain refuses on
  BOTH sides for ONE reason — the arm's own refusal, established by
  `rf.bench.hicasso.front.intent/with-frame` over every render extent, client or server. The
  contrast survives; its explanation changed, and a row asserting a
  renderer-specific server-side throw would now be asserting the wrong
  fact for the wrong reason.

  Runtime: `-cljs-test`, i.e. the consolidated `:node-test` build, which
  is where `react-dom/server` resolves and where the rest of the SSR entry
  is proved (`ssr/entry_cljs_test`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.front.intent :as rf.bench.hicasso.front.intent]
            [re-frame.bench.hicasso.ssr.entry :as rf.bench.hicasso.ssr.entry]
            [re-frame.bench.hicasso.ssr.fixtures :as rf.bench.hicasso.ssr.fixtures]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

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
     ;; request has: `rf.bench.hicasso.ssr.entry/render` establishes no scope and a host calls
     ;; it at top of stack. Measuring the refusal against the fixture's own
     ;; stamp would be measuring the fixture. (The mismatched-scope case is
     ;; interesting in its own right and is pinned deliberately, in
     ;; `arm1/hframe_cljs_test`.)
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.hicasso.ssr.fixtures/register!) (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

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
  (swap! !seen conj (rf.bench.hicasso.front.intent/hframe))
  [:span.row (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/remaining]))])

(defview indiscreet
  "Renders the per-request id INTO the markup. This is the authorable
  hazard the docstring warns about, written on purpose so the
  determinism check can be watched failing."
  [_]
  [:span.row {:data-frame (str (rf.bench.hicasso.front.intent/hframe))}])

(defview carrying
  "Tries the AMBIENT carry — `(rf/capture-frame)`, 0-arity — inside a
  server body, and records what it got. It catches its own throw so the
  render completes and the row can read the payload rather than a
  `renderToString` failure."
  [_]
  (reset! !ambient
          (try [::no-throw (rf/capture-frame)]
               (catch :default e (ex-data e))))
  [:span.row (str (rf.bench.hicasso.front.intent/hframe))])

(defn- request [hiccup]
  {:hiccup   hiccup
   :snapshot (:snapshot (rf.bench.hicasso.ssr.fixtures/row "dogfood-snapshot"))
   :payload  rf.bench.hicasso.ssr.fixtures/dogfood-payload-keys})

;; ---------------------------------------------------------------------------
;; W8 — the per-request id, and the one authorable hazard
;; ---------------------------------------------------------------------------

(deftest a-server-body-reads-the-requests-own-frame
  (testing "the frame `h/frame` answers on the server IS the request's
           per-request gensym, and two requests answer two different ones —
           which is what per-request isolation means when the id is the
           thing being read"
    (reset! !seen [])
    (let [a (rf.bench.hicasso.ssr.entry/render (request [discreet {}]))
          b (rf.bench.hicasso.ssr.entry/render (request [discreet {}]))]
      (is (= 2 (count @!seen)) "one read per request")
      (is (= [(:frame-id a) (:frame-id b)] @!seen)
          "each body read its OWN request's id, in order")
      (is (not= (:frame-id a) (:frame-id b))
          "precondition: the two requests really did take different frames,
           so the row above is not comparing a constant to itself"))))

(deftest a-body-that-reads-the-id-and-keeps-it-out-of-markup-is-deterministic
  (testing "the correct shape. Two renders take two gensyms, and the
           documents are byte-identical anyway — because the value went
           into a read and not into the page"
    (let [{:keys [identical? differs-at] a :first b :second}
          (rf.bench.hicasso.ssr.entry/render-twice (request [discreet {}]))]
      (is identical? (str "the two documents differ at index " differs-at))
      (is (not= (:frame-id a) (:frame-id b))
          "and they were different requests — this is the whole point")
      (is (not (str/includes? (:document a) (name (:frame-id a))))
          "the id is nowhere in the markup"))))

(deftest a-body-that-renders-the-id-breaks-determinism-and-the-check-says-so
  (testing "THE ROW THAT MAKES THE ONE ABOVE MEAN SOMETHING. Rendering the
           per-request id is an authorable hazard, and the claim is that
           the existing render-twice byte comparison catches it. A claim
           about a check that has never been watched failing is not a
           check, so here it is failing"
    (let [{:keys [identical? differs-at] a :first} (rf.bench.hicasso.ssr.entry/render-twice (request [indiscreet {}]))]
      (is (not identical?)
          "a document carrying the per-request gensym cannot be
           deterministic, and the determinism witness must say so")
      (is (some? differs-at) "with a diagnosable diff position")
      (is (str/includes? (:document a) (name (:frame-id a)))
          "and the id is visibly in the markup, which is the mistake"))))

;; ---------------------------------------------------------------------------
;; W11 — the ambient chain refuses on BOTH sides, for ONE reason
;; ---------------------------------------------------------------------------

(deftest the-ambient-carry-is-admitted-server-side-to-the-requests-own-frame
  (testing "FLIPPED under rf2-t32wg, which admits the pure doors. This row
           asserted the arm's refusal for the carry; the shipped core now
           answers `(rf/capture-frame)` the extent's declared frame, and the
           arm's `with-frame` declares it over the server render extent
           exactly as over the client's — so what the server reports is the
           request's own frame, and not a fact about `react-dom/server`"
    (reset! !ambient ::unset)
    (let [{:keys [document frame-id]} (rf.bench.hicasso.ssr.entry/render (request [carrying {}]))
          data                        @!ambient]
      (is (vector? data)
          (str "expected the carry to be admitted; got " (pr-str data)))
      (is (= frame-id (:frame (second data)))
          "locked to the request's own per-request frame")
      (is (str/includes? document "class=\"row\"")
          "and the render completed")))

  (testing "while `h/frame` answers in the same body — the contrast the
           design's W11 exists for, with its explanation updated"
    (reset! !seen [])
    (let [{:keys [frame-id]} (rf.bench.hicasso.ssr.entry/render (request [discreet {}]))]
      (is (= [frame-id] @!seen)))))
