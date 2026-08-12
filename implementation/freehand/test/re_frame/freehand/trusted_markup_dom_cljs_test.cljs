(ns re-frame.freehand.trusted-markup-dom-cljs-test
  "rf2-rrosy — `v/html` on the two REACT rendering paths, proven against a
  real DOM.

  This is the half of the acceptance the manifest cannot give. `v/html` was
  recognised, validated, recorded on the compiled `:html-sites` roster and
  named by every refused `dangerouslySetInnerHTML` diagnostic — and lowered by
  neither emitter, so an analyzer or manifest assertion about it would have
  passed throughout. The only oracle that separates a working bypass from a
  recognised-and-dropped one is the DOM: `<b>` has to be an ELEMENT node,
  not text.

  Both React paths run, and both are new:

    - INTERPRETED React — `react/element-node` reads the sole-child position
      and writes `dangerouslySetInnerHTML`. Freehand's paved path
      (`{:compiled true}` is opt-in), and the donor port covered it not at
      all: the interpreted browser walker rejected an arbitrary map child.
    - COMPILED React — `emit-react` emits a `compiled-react/html!` write
      that calls the SAME interpreted writer, so the string check and the
      element refusals cannot diverge between the tiers.

  Two lanes, like the sibling slot suite: in the node suites the compiled
  declarations still EXPAND, and expansion is what a missing emitter arm used
  to fail; in the browser job the same declarations mount and the claims are
  read off `document`.

  The host-neutral lane carries one more thing, and it is there because the
  DOM cannot see it: EVALUATION ORDER. A compiled element writes its key and
  its trusted markup into different slots of one props object, so an emitter
  that ran the child expression before the props map produced byte-identical
  DOM — and diverged only in side-effect order and in which expression throws
  first. Those rows run the declaration's body directly and read a log
  (rf2-rrosy, #6980 audit).

  The structural counterpart, over the same declarations, is
  `re-frame.freehand.trusted-markup-ssr-jvm-test`."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.trusted-markup-views :as views]
            [re-frame.test-support :as test-support]))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(def ^:private markup "<b>bold</b> &amp; <i>italic</i>")

;; ---------------------------------------------------------------------------
;; The host-neutral lane — the compiled declarations EXPAND
;; ---------------------------------------------------------------------------

(deftest the-compiled-trusted-markup-declarations-expand
  (testing "Before the React emitter had an `:html` write the compiled
            declarations were not wrong, they were unlowered — and an
            unlowered admitted op is a `case` with no matching clause, thrown
            inside macro expansion. So reaching this test at all is the
            assertion; the lowering each declaration reports is asserted
            beside it, so one that quietly fell back to the interpreted walk
            could not pass."
    (doseq [k [:markup-body-compiled :markup-with-props-compiled
               :keyed-markup-compiled :literal-markup-compiled
               :markup-nested-compiled]]
      (let [view (get views/by-name k)]
        (is (= :compiled (:lowering (v/describe view)))
            (str k " — declared compiled"))
        (is (contains? (:capabilities (v/manifest view)) :html)
            (str k " — and its manifest names the trusted-markup capability"))))))

;; ---------------------------------------------------------------------------
;; The host-neutral lane — evaluation ORDER on the two React paths
;; ---------------------------------------------------------------------------

;; What the DOM cannot see. Every mounted row below reads the RESULT, and the
;; result is the same whichever of the element's two expressions ran first —
;; `key` and `dangerouslySetInnerHTML` are different slots on the props object
;; and neither write reads the other. So a compiled emitter that evaluated the
;; child before the props map produced identical DOM and diverged only in
;; side-effect order and in which expression throws first (rf2-rrosy, #6980
;; audit). These rows make that observable: the declaration's key and its
;; markup are opaque calls, and the suite hands it thunks that record.
;;
;; No DOM is needed to see it, so these run in the node suites too: the
;; declaration's body is what evaluates, and `descriptor/react-body` /
;; `descriptor/render-body` are the two fns a React mount would have called.

(defn- ran
  "Run one census declaration's BODY on the React path its lowering names —
  the compiled twin's emitted React realisation, or the interpreted twin's
  render body — and answer the log of what its two expressions did.

  Calling the body is what a React mount does with it; going through the
  mount as well would add a DOM the claim does not need, and a React error
  boundary between the throw and the assertion."
  [view-name props]
  (let [view (get views/by-name view-name)]
    ((or (descriptor/react-body view) (descriptor/render-body view)) props)))

(deftest an-element-evaluates-its-key-before-its-trusted-markup
  (testing "THE ORDER ROW. `:key` is authored inside the props map, so the
            interpreted path evaluates it — with the rest of the map — before
            it looks at the child position holding the markup. The compiled
            twin owes the same order, and used to invert it: the emitter
            conjoined the `html!` write before the `unchecked-set` of the key.
            Both paths, one declaration, and each expression exactly once."
    (doseq [k [:keyed-markup :keyed-markup-compiled]]
      (let [[log props] (views/recorder)]
        (ran k props)
        (is (= [:key :html] @log)
            (str k " — the key ran first, and each expression ran once"))))))

(deftest a-throwing-key-pre-empts-the-trusted-markup
  (testing "The first exception prefix. A key expression that throws means
            the markup expression never runs at all — which is what the
            author wrote, and what the interpreted path does. With the markup
            emitted first the compiled twin ran it anyway, so a failed render
            had performed a side effect the source says it could not reach."
    (doseq [k [:keyed-markup :keyed-markup-compiled]]
      (let [[log props] (views/recorder {:k #(throw (ex-info "key boom" {}))})
            thrown      (try (ran k props) ::no-throw
                             (catch :default e (ex-message e)))]
        (is (= "key boom" thrown)
            (str k " — the authored key expression is what threw"))
        (is (= [] @log)
            (str k " — and the trusted-markup expression never ran"))))))

(deftest a-throwing-trusted-markup-runs-after-the-key
  (testing "The other prefix, and the one that fails the other way round: a
            markup expression that throws throws AFTER the key has been
            evaluated, so the key's side effect is already recorded when the
            render fails. Compiled, the markup used to throw first and the key
            expression never ran."
    (doseq [k [:keyed-markup :keyed-markup-compiled]]
      (let [[log props] (views/recorder {:m #(throw (ex-info "markup boom" {}))})
            thrown      (try (ran k props) ::no-throw
                             (catch :default e (ex-message e)))]
        (is (= "markup boom" thrown)
            (str k " — the authored markup expression is what threw"))
        (is (= [:key] @log)
            (str k " — and the key had already been evaluated, once"))))))

(deftest the-order-rows-run-one-react-path-each
  (testing "The non-vacuity pin for the three rows above. They are a claim
            about the COMPILED React lowering and its interpreted twin, so a
            compiled declaration that quietly had no React realisation —
            `descriptor/react-body` nil, the interpreted body run twice —
            would satisfy every one of them while proving nothing."
    (is (some? (descriptor/react-body (views/by-name :keyed-markup-compiled)))
        "the compiled twin carries a React realisation, and that is what ran")
    (is (nil? (descriptor/react-body (views/by-name :keyed-markup)))
        "and the interpreted twin carries none, so its own body ran")))

;; ---------------------------------------------------------------------------
;; The browser lane
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- unmount! [container mounted]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (when (some? mounted)
    (.unmount (.-react-root ^root/Root mounted)))
  (.remove container)
  nil)

(defn- mounted!
  "Mount `form`, run `check` against the container, tear the root down.

  The rejection handler sits UPSTREAM of the step that calls `done`, and the
  single `done` sits at the tail with nothing after it (rf2-o0n1). `done` runs
  the whole remainder of the run synchronously, so a `.catch` downstream of it
  would claim a later namespace's throw as this row's failure and fire `done` a
  second time."
  [form check done]
  (let [container (host-node!)]
    (-> (act #(v/mount form container))
        (.then (fn [m]
                 (check container)
                 ;; ASYMMETRIC, so it stays put: `m` is what the mount resolved
                 ;; with, and the rejection arm never had a root to unmount — it
                 ;; can only detach the host node, which it does below.
                 (unmount! container m)))
        (.catch (fn [e]
                  (is false (str "trusted-markup mount rejected: " e))
                  (.remove container)
                  nil))
        (.then (fn [_] (done))))))

(defn- each-mode
  "Run `check` over the interpreted and compiled members of one pair, one
  mount each, and only then finish."
  [[interpreted compiled] props check done]
  (let [run (fn [k next]
              (mounted! [(get views/by-name k) props]
                        (fn [c] (check k c))
                        next))]
    (run interpreted #(run compiled done))))

(deftest trusted-markup-becomes-real-dom-elements-on-both-react-paths
  (testing "THE ROW THE BEAD TURNS ON. `<b>bold</b>` inside the string has to
            be an ELEMENT NODE in the document — that is the difference
            between a bypass that works and one that was recognised, recorded
            and dropped. Asserted on both React paths, because the interpreted
            one is the paved path and had no lowering at all."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (each-mode [:markup-body :markup-body-compiled] {:markup markup}
                   (fn [k c]
                     (let [body (.querySelector c "article.body")]
                       (is (some? (.querySelector body "b"))
                           (str k " — <b> is a real element node"))
                       (is (= "bold" (.-textContent (.querySelector body "b")))
                           (str k " — carrying the author's text"))
                       (is (some? (.querySelector body "i"))
                           (str k " — and so is <i>"))
                       (is (= "bold & italic" (.-textContent body))
                           (str k " — the already-escaped &amp; decoded once, "
                                "not twice: nothing re-escaped it"))))
                   done)))))

(deftest the-same-string-as-an-ordinary-child-is-text-not-markup
  (testing "The control that makes the row above a claim about `v/html`. One
            declaration differs from its neighbour by the call alone, so the
            call is the entire difference in the DOM: here the angle brackets
            are TEXT, and there is no element inside the article at all."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (each-mode [:escaped-body :escaped-body-compiled] {:markup markup}
                   (fn [k c]
                     (let [body (.querySelector c "article.body")]
                       (is (nil? (.querySelector body "b"))
                           (str k " — no call, no bypass: nothing was parsed"))
                       (is (= 0 (.-length (.-children body)))
                           (str k " — the article has no element children"))
                       (is (= markup (.-textContent body))
                           (str k " — the string is the text, verbatim"))))
                   done)))))

(deftest ordinary-props-reach-the-element-that-carries-the-markup
  (testing "The content channel and the props channel are separate. On the
            compiled path the attributes are a build-time props object and the
            markup a render-time write onto it, so this is the row that would
            catch a write clobbering the object or landing on the wrong one."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (each-mode [:markup-with-props :markup-with-props-compiled]
                   {:markup markup :lang "en"}
                   (fn [k c]
                     (let [el (.querySelector c "section#post.prose")]
                       (is (some? el) (str k " — class sugar and #id both landed"))
                       (is (= "en" (.getAttribute el "lang"))
                           (str k " — the runtime attribute landed"))
                       (is (= "body" (.getAttribute el "data-kind"))
                           (str k " — and the data-* pass-through"))
                       (is (some? (.querySelector el "b"))
                           (str k " — with the trusted markup still parsed"))))
                   done)))))

(deftest the-bypass-is-scoped-to-the-element-that-owns-it
  (testing "Trusted markup changes nothing about its siblings. The two
            ordinary strings beside it are text in the same render that parses
            the markup — which is what makes the set of bypasses on a page
            exactly the set of visible calls."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (each-mode [:markup-nested :markup-nested-compiled] {:markup markup}
                   (fn [k c]
                     (is (some? (.querySelector c "article.body > b"))
                         (str k " — the owning element parsed its markup"))
                     (is (= "<not markup>"
                            (.-textContent (.querySelector c "h1.title")))
                         (str k " — the sibling before it is text"))
                     (is (= 0 (.-length (.-children (.querySelector c "footer.foot"))))
                         (str k " — and the sibling after it parsed nothing")))
                   done)))))

(deftest a-literal-trusted-markup-site-renders-through-the-hoisted-element
  (testing "A DISTINCT compiled code path. An element whose props and whose
            trusted-markup argument are all literal is `:static?`, so the React
            emitter builds it ONCE at module level and shares the element across
            renders — the props object, and with it the
            `dangerouslySetInnerHTML` value, are hoisted. That is the one
            emitted shape where a stale or shared markup object would show up,
            so the literal site gets its own mount rather than riding the
            dynamic rows."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (each-mode [:literal-markup :literal-markup-compiled] {}
                   (fn [k c]
                     (let [host (.querySelector c "div.static")]
                       (is (some? (.querySelector host "em"))
                           (str k " — the literal markup parsed into an element"))
                       (is (= "fixed" (.-textContent host))
                           (str k " — carrying its text"))))
                   done)))))

(deftest a-hostile-string-is-inserted-verbatim-because-there-is-no-sanitizer
  (testing "THE ROW THAT MUST NOT GO GREEN BY ACCIDENT. Freehand does not
            sanitise — no allowlist, no tag or attribute filter, no
            `javascript:`-scheme gate — and the DOM says so: an inline handler
            attribute, a `javascript:` href and a whole `<script>` element all
            reach the document exactly as written. A slice that ADDS a filter
            has to come here and change this test, which is the review such an
            addition deserves (004D §Trusted markup — what `v/html` does not
            do).

            The constructs are chosen to be INERT in a test runner while still
            being present: `innerHTML` does not execute an inserted `<script>`,
            and an `onclick` nobody clicks does not fire. Asserting the
            attributes is the honest oracle — the claim is that nothing
            filtered them, not that something ran."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [hostile (str "<span id=\"hostile-span\" onclick=\"steal()\">s</span>"
                           "<a id=\"hostile-link\" href=\"javascript:steal()\">go</a>"
                           "<script id=\"hostile-script\">steal()</script>")]
          (each-mode [:markup-body :markup-body-compiled] {:markup hostile}
                     (fn [k c]
                       (is (= "steal()"
                              (.getAttribute (.querySelector c "#hostile-span") "onclick"))
                           (str k " — an inline handler attribute reached the DOM "
                                "as written"))
                       (is (= "javascript:steal()"
                              (.getAttribute (.querySelector c "#hostile-link") "href"))
                           (str k " — and so did the javascript: href"))
                       (is (some? (.querySelector c "#hostile-script"))
                           (str k " — and the <script> element itself: the verb "
                                "ASSERTS trust, it does not establish it")))
                     done))))))
