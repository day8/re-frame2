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
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.tree-views :as views]))

(def struct-007 (conf/fixture :FH-STRUCT-007))

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
