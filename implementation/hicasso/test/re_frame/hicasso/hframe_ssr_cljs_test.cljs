(ns re-frame.hicasso.hframe-ssr-cljs-test
  "`h/hframe` ON THE SERVER (rf2-wehh0 — the design's W8 and W11,
  re-expressed on the package's own entry).

  ## Why this file exists, and what it is a copy of

  `docs/design/hicasso/product/prototype-suite-triage.md` §(iii) disposed
  of five bench SSR suites as STAYS, with one conditional: two of them
  *\"assert real package behaviour and would be worth re-expressing IF the
  package ever gets its own SSR entry\"*. [[re-frame.hicasso.server]]
  landed, so the condition fired, and this file is the first
  half of the answer — `ssr/hframe_ssr_cljs_test` pointed at
  `server/render` instead of at the bench prototype's
  `re-frame.bench.hicasso.ssr.entry/render`.

  **The re-expression is not a downgrade, and that is worth one sentence
  because the reverse would be easy to ship by accident.** The prototype
  renders `(provider frame (codec/root-element frame hiccup))` — *the
  tree a consumer can spell today* — while `server/render` renders
  [[re-frame.hicasso.impl.mount/tree]] with a hydrating handle, which is
  the Fragment-plus-closer shape `hydrate-root!` adopts. The two disagree
  inside a `useId` (`identifier_prefix_ssr_dom_cljs_test`, and
  `server_render_ssr_dom_cljs_test` §1 pins which one the entry picked),
  and the prototype's lane avoided the disagreement by having no `useId`
  in it at all. Every claim below is about the RUNTIME under a server
  render rather than about the tree's shape, so all four move across
  unchanged and each of them now describes shipped code.

  ## W8 — the id is process-local identity, and must never reach markup

  Two same-process renders take two different gensyms, so a body that
  RENDERS the value makes the document nondeterministic. That is an
  authorable hazard rather than a framework one, and it is already
  instrumented: [[re-frame.hicasso.server/render-twice]]'s byte
  comparison is the standing determinism check. Both halves are asserted
  here — a body that reads the id and keeps it out of markup renders
  byte-identical documents, and a body that deliberately prints it does
  not.

  **The second row is the reason the first is worth having**, and it is
  the one thing the package could not say before. `server_render_ssr_dom`
  has `two-renders-of-one-request-are-the-same-bytes`, and nothing
  anywhere has watched that comparison FAIL — a check that has never been
  seen red is a claim about a check.

  ## W11 — the ambient chain refuses on BOTH sides, for ONE reason

  As designed the row contrasted `h/hframe` answering against the ambient
  chain throwing *server-side for a renderer-specific reason*: the raw
  React-context read the adapters publish is client-renderer-only. That
  stopped being true at rf2-2rtt6.122 — the ambient chain now refuses on
  both sides for ONE reason, the arm's own refusal, established by
  `impl.intent/with-frame` over every render extent. The contrast
  survives and its explanation changed.

  `hframe_cljs_test` already witnesses the carry refusing inside a
  boundary body, but it drives `collector/render-body` directly. This row
  is the same refusal observed through `react-dom/server`, which is what
  makes it a statement about the SERVER rather than about a synthetic
  extent: same operation, same substrate, same extent id.

  Runtime: `-cljs-test`, i.e. the `:node-test-hicasso` build (and the
  always-on `:node-test`), which is where `react-dom/server` resolves and
  where the rest of the entry's string-only rows run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.server :as server]
            [re-frame.test-support :as test-support]))

;; Registered ABOVE `use-fixtures`, for the sibling suites' reason: the
;; reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it is
;; erased before the first row runs.

(rf/reg-sub ::remaining (fn [db _] (:remaining db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `:ambient-frame nil`, where `server_render_ssr_dom` takes the same
     ;; and the DOM suites take the fixture's default — and here it is
     ;; load-bearing rather than tidiness. The default root-binds
     ;; `frame/*current-frame*` to `:rf/default`, which is a CARRIED tier-1
     ;; stamp; the refusal withdraws the ambient FIND and never the
     ;; carrying, so an ambient carry inside a body would resolve
     ;; `:rf/default` and succeed. That is core behaving exactly as
     ;; specified, and it is not the configuration a server request has:
     ;; `server/render` establishes no scope and a host calls it at top of
     ;; stack. Measuring the refusal against the fixture's own stamp would
     ;; be measuring the fixture. (The MISMATCHED-scope case is interesting
     ;; in its own right and is pinned deliberately, in `hframe_cljs_test`'s
     ;; `a-carried-outer-scope-refuses-rather-than-answering-the-wrong-frame`.)
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

(def ^:private !seen
  "Every frame id a server body read, newest last."
  (atom []))

(def ^:private !ambient
  "What the ambient carry answered inside a server body — the ex-data of
  its refusal, or `::no-throw` with the value it produced."
  (atom ::unset))

(h/defview discreet
  "Reads the request's frame and keeps it OUT of the markup — the
  documented correct shape. What it renders is a subscription value, so
  the boundary is an ordinary one."
  [_]
  (swap! !seen conj (h/hframe))
  [:span.row (str (h/sub [::remaining]))])

(h/defview indiscreet
  "Renders the per-request id INTO the markup. This is the authorable
  hazard the module's docstring warns about, written on purpose so the
  determinism check can be watched failing."
  [_]
  [:span.row {:data-frame (str (h/hframe))}])

(h/defview carrying
  "Tries the AMBIENT carry — `(rf/capture-frame)`, 0-arity — inside a
  server body, and records what it got. It catches its own throw so the
  render completes and the row can read the markup rather than a
  `renderToString` failure."
  [_]
  (reset! !ambient
          (try [::no-throw (rf/capture-frame)]
               (catch :default e (ex-data e))))
  [:span.row (str (h/hframe))])

(defn- request [hiccup]
  {:hiccup   hiccup
   :snapshot {:remaining 3}
   :payload  [:remaining]})

;; ---------------------------------------------------------------------------
;; W8 — the per-request id, and the one authorable hazard
;; ---------------------------------------------------------------------------

(deftest a-server-body-reads-the-requests-own-frame
  (testing "the frame `h/hframe` answers on the server IS the request's
           per-request gensym, and two requests answer two different ones —
           which is what per-request isolation means when the id is the
           thing being read"
    (reset! !seen [])
    (let [a (server/render (request [discreet {}]))
          b (server/render (request [discreet {}]))]
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
          (server/render-twice (request [discreet {}]))]
      (is identical? (str "the two documents differ at index " differs-at))
      (is (not= (:frame-id a) (:frame-id b))
          "and they were different requests — this is the whole point")
      (is (not (str/includes? (:document a) (name (:frame-id a))))
          "the id is nowhere in the markup"))))

(deftest a-body-that-renders-the-id-breaks-determinism-and-the-check-says-so
  (testing "THE ROW THAT MAKES THE ONE ABOVE MEAN SOMETHING, and the one
           claim in this family the package could not previously make.
           Rendering the per-request id is an authorable hazard, and the
           claim is that `server/render-twice`'s byte comparison catches
           it. A claim about a check that has never been watched failing is
           not a check, so here it is failing"
    (let [{:keys [identical? differs-at] a :first} (server/render-twice (request [indiscreet {}]))]
      (is (not identical?)
          "a document carrying the per-request gensym cannot be
           deterministic, and the determinism witness must say so")
      (is (some? differs-at) "with a diagnosable diff position")
      (is (str/includes? (:document a) (name (:frame-id a)))
          "and the id is visibly in the markup, which is the mistake"))))

;; ---------------------------------------------------------------------------
;; W11 — the ambient chain refuses on BOTH sides, for ONE reason
;; ---------------------------------------------------------------------------

(deftest the-ambient-carry-refuses-server-side-for-the-substrates-own-reason
  (testing "the designed row contrasted `h/hframe` answering against a
           renderer-specific server-side throw; the arm's refusal now covers
           the server render extent exactly as it covers the client's, so
           what the server reports is the SUBSTRATE's refusal — same
           operation, same substrate, same extent — and not a fact about
           `react-dom/server`"
    (reset! !ambient ::unset)
    (let [{:keys [document]} (server/render (request [carrying {}]))
          data               @!ambient]
      (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
          (str "expected the ambient refusal for the carry; got " (pr-str data)))
      (is (= :capture-frame (:operation data))
          "named as the CARRY, which is the third of the ambient door's three
           consumers and the one with its own recovery sentence (rf2-hnrww)")
      (is (= :hicasso (:substrate data))
          "the substrate refused, not the renderer — which is what makes
           this the same answer the browser gives")
      (is (= 'hicasso/boundary-render (:extent data)))
      (is (str/includes? document "class=\"row\"")
          "and the render completed: the refusal is the carry's, not the
           page's")))

  (testing "while `h/hframe` answers in the same body — the contrast the
           design's W11 exists for, with its explanation updated"
    (reset! !seen [])
    (let [{:keys [frame-id]} (server/render (request [discreet {}]))]
      (is (= [frame-id] @!seen)))))
