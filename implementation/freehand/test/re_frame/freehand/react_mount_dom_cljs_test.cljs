(ns re-frame.freehand.react-mount-dom-cljs-test
  "FH-STRUCT-007 — the interpreted React emitter puts real DOM on a real
  page.

  The structural rows prove what the tree SAYS. This one proves what a
  browser DOES with the sibling emitter's output, and the two are not the
  same claim. An element tree can be perfectly shaped around an attribute
  name React declines to set, a class React drops, or a run of children
  React reorders — and no amount of element-tree assertion would notice,
  because the assertion and the bug would share an author.

  So: a real `react-dom/client` root, the SAME declaration
  `tree-parity-cljs-test` renders structurally, and every assertion read
  back off `document`.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer [async deftest is testing]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.tree-views :as views]))

(def struct-007 (conf/fixture :FH-STRUCT-007))
(def struct-009 (conf/fixture :FH-STRUCT-009))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a promise, so the assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- check-row!
  [container {:keys [note selector selector-all tag text attrs] n :count}]
  (if selector-all
    (is (= n (.-length (.querySelectorAll container selector-all))) note)
    (let [el (.querySelector container selector)]
      (is (some? el) (str note " — " selector " matched"))
      (when el
        (when tag  (is (= tag (.-tagName el)) note))
        (when text (is (= text (.-textContent el)) note))
        (doseq [[attr-name expected] attrs]
          (is (= expected (.getAttribute el attr-name))
              (str note " — " attr-name)))))))

(deftest fh-struct-007-the-react-emitter-mounts-real-dom
  (testing "Per FH-STRUCT-007: a declared view mounted through
            `react-dom/client` produces the elements, converted attribute
            names, composed classes, text, and keyed child boundaries the
            conversion table describes — observed in the DOM, not in an
            element tree."
    (is (seq (:dom struct-007)) "the fixture's DOM table loaded")
    (if-not (browser?)
      (is true "a real React mount needs a DOM host — the browser job runs the assertions")
      (async done
        (let [container (js/document.createElement "div")]
          (.appendChild js/document.body container)
          (let [root (rdc/createRoot container)]
            (-> (act #(.render root (fr/element [views/page (:props struct-007)])))
                (.then (fn [_]
                         (doseq [row (:dom struct-007)]
                           (check-row! container row))
                         (act #(.unmount root))))
                (.then (fn [_]
                         (.remove container)
                         (done))
                       (fn [e]
                         (is false (str "mount rejected: " e))
                         (.remove container)
                         (done))))))))))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-009 — React's canonical prop grammar
;; ---------------------------------------------------------------------------

(deftest fh-struct-009-author-spellings-project-to-canonical-react-props
  (testing "Per FH-STRUCT-009: the emitter spells attributes as React's
            own canonical props, through react-dom's
            `possibleStandardNames` vocabulary rather than a second
            table. `data-*`/`aria-*` pass verbatim with their casing, and
            an unrecognized name passes verbatim, which is React 16+'s
            own rule. No namespace context is consulted — a canonical
            prop name is canonical everywhere."
    (is (seq (:prop-names struct-009)) "the fixture's prop-name table loaded")
    (doseq [{:keys [note attr prop]} (:prop-names struct-009)]
      (is (= prop (conv/react-prop-name attr)) note))))

(deftest fh-struct-009-reserved-and-rejected-spellings-never-reach-react
  (testing "Per FH-STRUCT-009: the React emitter refuses the reserved and
            rejected spellings at the walk, with the same diagnostic its
            structural sibling raises. `:children` through the attribute
            path would render content the tree does not carry — and on a
            void element React throws where the structural walk saw
            nothing wrong, which is a divergence produced by the emitter
            rather than by the declaration."
    (is (seq (:rejected struct-009)) "the fixture's rejected table loaded")
    (doseq [{:keys [note form error-id]} (:rejected struct-009)]
      (is (= error-id (conf/caught-id #(fr/element form))) note))))

(defn- capture-console
  "Install a console.error/warn recorder and answer `[messages restore!]`.
  React reports an unrecognized DOM property through `console.error`, so
  what it does NOT say is the observable that a prop was accepted rather
  than merely emitted."
  []
  (let [seen  (atom [])
        err   (.-error js/console)
        warn  (.-warn js/console)
        record (fn [& args] (swap! seen conj (apply str (interpose " " (map str args)))))]
    (set! (.-error js/console) record)
    (set! (.-warn js/console) record)
    [seen (fn [] (set! (.-error js/console) err) (set! (.-warn js/console) warn))]))

(deftest fh-struct-009-canonical-props-mount-without-react-complaining
  (testing "Per FH-STRUCT-009: read off `document` after a real
            `react-dom/client` mount. The DOM rows prove React ACCEPTED
            each prop — including an SVG attribute authored both directly
            under `<svg>` and inside a declared view, which React renders
            as a real component whose body runs with no walk above it.
            The silence row proves React had nothing to complain about,
            and the control mount proves that silence is a fact about the
            props rather than about the build: the same browser, handed
            the non-canonical spelling directly, does complain."
    (is (seq (:dom struct-009)) "the fixture's DOM table loaded")
    (is (true? (:react-console-silent struct-009)))
    (if-not (browser?)
      (is true "a real React mount needs a DOM host — the browser job runs the assertions")
      (async done
        (let [container (js/document.createElement "div")
              control   (:react-warning-control struct-009)
              _         (.appendChild js/document.body container)
              root      (rdc/createRoot container)
              subject   (atom nil)
              ctl       (atom nil)
              restore!  (fn [a] (when-let [r (:restore! @a)] (r)))]
          (-> (js/Promise.resolve)
              (.then (fn [_]
                       (let [[log r] (capture-console)]
                         (reset! subject {:log log :restore! r})
                         (act #(.render root (fr/element [views/props-page {}]))))))
              (.then (fn [_]
                       (restore! subject)
                       (doseq [row (:dom struct-009)]
                         (check-row! container row))
                       (is (= [] @(:log @subject))
                           (str "React accepted every prop silently; it said "
                                (pr-str @(:log @subject))))
                       (let [[log r] (capture-console)]
                         (reset! ctl {:log log :restore! r})
                         (act #(.render root
                                        (react/createElement
                                          (:tag control)
                                          (doto (js-obj)
                                            (aset (:prop control) (:value control)))))))))
              (.then (fn [_]
                       (restore! ctl)
                       (is (seq @(:log @ctl))
                           (str (:note control) " — React said nothing here, which would make "
                                "the silence assertion above prove nothing"))
                       (is (some #(re-find (re-pattern (:names control)) %) @(:log @ctl))
                           (str "and it named the canonical spelling it expected: "
                                (pr-str @(:log @ctl))))
                       (act #(.unmount root))))
              (.then (fn [_]
                       (.remove container)
                       (done))
                     (fn [e]
                       (restore! subject)
                       (restore! ctl)
                       (is false (str "mount rejected: " e))
                       (.remove container)
                       (done)))))))))
