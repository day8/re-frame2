(ns re-frame.ssr.clean-hydration-dom-cljs-test
  "A page whose server bytes are `render-to-string`'s output for the client's
  own tree hydrates silently through a hydrating `render!`: no recoverable
  error, no `:rf.ssr/hydration-mismatch`, and no React development
  `console.error`.

  The control plants one server-only attribute on the same bytes. That is an
  attribute-only divergence: React reports it through its development
  `console.error` and never through `onRecoverableError`, which is why a
  browser check watches both (Spec 011 §Hydration-mismatch detection), and
  why the clean row's silence is evidence rather than an unwatched channel.

  Real DOM, with act OFF, so React hydrates on its own schedule. `:node-test`
  loads this file too and every row exits early without `js/document`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [clojure.string :as str]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the tree -------------------------------------------------------------
;;
;; Plain components, so no frame is involved; the attributes are the ones the
;; emitter converts the way the hydrating client does — a class vector, a
;; keyword value, `:for`, a boolean, a numeric data attribute, a style map.

(defn- field [label value]
  [:label.field {:for "q"} label
   [:input {:id "q" :type "text" :read-only true :default-value value}]])

(defn- page [title n]
  [:main {:class ["page" "dense"] :data-count n :style {:margin-top "4px"}}
   [:h1 title]
   [field "Search" "x"]
   [:ul (for [i (range n)] ^{:key i} [:li {:aria-label (str "row " i)} i])]
   [:button {:type :button :disabled false} "Go"]])

(def ^:private tree [page "Hello" 3])

;; ---- harness --------------------------------------------------------------

(defn- host!
  "A connected container pre-filled with `server-html`."
  [server-html]
  (let [el (.createElement js/document "div")]
    (set! (.-innerHTML el) server-html)
    (.appendChild (.-body js/document) el)
    el))

(defn- act-off! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (fn [] (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))))

(defn- capture-console-errors!
  "Record every `console.error` call as one string. Returns `[atom restore]`."
  []
  (let [seen     (atom [])
        original (.-error js/console)]
    (set! (.-error js/console)
          (fn [& args] (swap! seen conj (str/join " " (map str args)))))
    [seen (fn [] (set! (.-error js/console) original))]))

(defn- listen-for-mismatches! []
  (let [seen (atom [])
        lk   (keyword (gensym "rf.clean-hydration-"))]
    (rf.trace.tooling/register-listener!
      lk (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                    (swap! seen conj ev))))
    [seen lk]))

(defn- committed?
  "Has the root at `el` committed its first tree?"
  [el]
  (some-> (some #(when (.startsWith % "__reactContainer$") (unchecked-get el %))
                (js/Object.keys el))
          .-stateNode .-current .-child some?))

(defn- poll-until [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- hydrate-and-observe!
  "Hydrate `tree` over `server-html`, wait for the first commit plus a settle
  window, then call `k` with what each channel saw."
  [server-html k]
  (let [restore-act        (act-off!)
        [console restore]  (capture-console-errors!)
        [mismatches lk]    (listen-for-mismatches!)
        recoverable        (atom [])
        el                 (host! server-html)
        handle             (rf.adapter.reagent/client-root)
        finish!            (fn []
                             (rf.trace.tooling/unregister-listener! lk)
                             (restore)
                             (try (react-dom/flushSync #(rf.adapter.reagent/unmount! handle))
                                  (catch :default _ nil))
                             (when-let [p (.-parentNode el)] (.removeChild p el))
                             (restore-act))]
    (rf.adapter.reagent/render! handle tree el
                                {:hydrate?             true
                                 :on-recoverable-error (fn [e _] (swap! recoverable conj e))})
    (poll-until #(committed? el)
                (fn []
                  (js/setTimeout
                    (fn []
                      (let [seen {:committed?  (committed? el)
                                  :recoverable @recoverable
                                  :mismatches  @mismatches
                                  :console     @console}]
                        (finish!)
                        (k seen)))
                    100)))))

(defn- attribute-warning? [message]
  (str/includes? message "didn't match"))

;; ---- rows -----------------------------------------------------------------

(deftest correct-server-bytes-hydrate-silently
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test build runs the assertions")
    (async done
      (let [server-html (rf.ssr.emit/render-to-string tree {})]
        (hydrate-and-observe!
          server-html
          (fn [{:keys [committed? recoverable mismatches console]}]
            (is committed? "the root committed its hydration")
            (is (= [[] [] []] [recoverable mismatches console])
                (str "render-to-string's bytes for the client's own tree hydrate with no"
                     " recoverable error, no :rf.ssr/hydration-mismatch and no console.error."
                     " Server bytes: " server-html))
            (done)))))))

(deftest a-server-only-attribute-reaches-only-the-development-console
  (testing "the control: one extra server attribute is an attribute-only divergence"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [server-html (str/replace-first (rf.ssr.emit/render-to-string tree {})
                                             "<main " "<main data-server-only=\"x\" ")]
          (hydrate-and-observe!
            server-html
            (fn [{:keys [committed? recoverable mismatches console]}]
              (is committed? "the root committed its hydration")
              (is (= [[] []] [recoverable mismatches])
                  "React does not report an attribute-only divergence as a recovery")
              (is (some attribute-warning? console)
                  (str "React's development console.error names the attribute divergence."
                       " Saw: " (pr-str console)))
              (done))))))))
