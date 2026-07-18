(ns re-frame.ui.spread-rejected-prop-cljs-test
  "rf2-5pr75 — the RUNTIME half of the rejected-prop-spelling deny.

  `rules/rejected-prop-spellings` (\"one spelling per name, and it is a node
  variant\") was enforced only on LITERAL props, by the analyzer. A
  `(ui/spread base overrides)` map is assembled at RUNTIME, so the compiler
  cannot see it, and `react-prop-name` passes an unrecognized name VERBATIM —
  which meant `{:dangerouslySetInnerHTML #js {:__html s}}` reached React's
  raw-markup slot with NO `(ui/html …)` trust assertion anywhere in the source.
  That is an injection path in any app that forwards a user-influenced attr map.

  The deny now lives at the ONE runtime seam both hosts fold a spread map
  through, so this suite runs UNDER BOTH `clojure -M:test` (JVM) and the node
  `-cljs-test` build, and pins:

    1. the LAW (`rules/spread-rejected-key?` / `assert-spread-prop-key!`) —
       canonical-SLOT comparison, so every alias of React's raw-markup prop is
       denied and no legitimate prop collides;
    2. the SEAM per host — `runtime/spread->props` (CLJS) and `tree/element`'s
       `:dyn` fold (JVM), reached via `base` AND via `overrides`, plus the
       `ui/spread-safe` caller map, which shares the seam;
    3. the END-TO-END injection proof — a compiled view rendered through real
       react-dom/server must not emit the hostile markup (CLJS only: the JVM
       emitter has no React);
    4. the POSITIVE CONTROLS — `(ui/html …)` still renders raw markup, both
       alone and alongside a `ui/spread` on the same element (the rf2-29s75
       composition), on both hosts.

  Advanced-build reachability (the deny is NOT `goog.DEBUG`-gated) rides
  spread_rejected_prop_elision_prod_test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.rules :as rules]
            #?@(:clj  [[re-frame.ui.tree :as tree]]
                :cljs [["react-dom/server" :as rds]
                       [re-frame.ui.runtime :as rt]])))

;; ---------------------------------------------------------------------------
;; Fixtures — compiled views (both emitters compile this file)
;; ---------------------------------------------------------------------------

(defview spread-only
  "A spread element with NO positional children — the shape where a smuggled
  \"children\" slot is not overwritten by the jsx child attach either."
  [{:keys [extra]}]
  [:div.card (ui/spread {:data-a "b"} extra)])

(defview spread-base-only
  "The same element with the hostile map arriving as `base` rather than
  `overrides` — both spread arguments merge into the one runtime map."
  [{:keys [hostile]}]
  [:div.card (ui/spread hostile {:data-a "b"})])

(defview spread-trusted-control
  "POSITIVE CONTROL — `ui/spread` and `ui/html` COMPOSE (rf2-29s75). The deny
  must not touch the compiler-owned raw-markup prop, which is attached at the
  visible `(ui/html …)` site, outside the runtime prop map."
  [{:keys [extra markup]}]
  [:div.raw (ui/spread {:data-a "b"} extra) (ui/html markup)])

(defview trusted-control
  "POSITIVE CONTROL — `ui/html` alone, the sanctioned escaping bypass."
  [{:keys [markup]}]
  [:div.content (ui/html markup)])

(def hostile-markup "<img src=x onerror=alert(1)>")

(def rejected-spellings
  "The literal author keywords the analyzer rejects — every one must be denied
  in a runtime map too."
  [:class-name :html-for :dangerously-set-inner-html :dangerouslySetInnerHTML
   :inner-html :children])

(def dangerous-aliases
  "Alternate spellings that all canonicalize to React's ACTUAL raw-markup slot.
  These are the security-load-bearing rows: the slot is what reaches the DOM."
  [:dangerouslySetInnerHTML
   "dangerouslySetInnerHTML"
   'dangerouslySetInnerHTML
   :caller/dangerouslySetInnerHTML])

;; ---------------------------------------------------------------------------
;; 1. The law
;; ---------------------------------------------------------------------------

(defn- denied-data [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

(defn- denied? [k]
  (= :rf.error/ui-tree-malformed
     (:rf.error/id (denied-data #(rules/assert-spread-prop-key! k)))))

(deftest every-rejected-spelling-is-denied-in-a-runtime-map
  (testing "the analyzer's literal deny set is denied at runtime too"
    (doseq [k rejected-spellings]
      (is (rules/spread-rejected-key? k) (str "rejected: " (pr-str k)))
      (is (denied? k) (str "throws: " (pr-str k))))))

(deftest the-deny-compares-canonical-emitted-slots
  (testing "every alias of React's raw-markup SLOT is denied"
    (doseq [k dangerous-aliases]
      (is (= "dangerouslySetInnerHTML" (rules/caller-key-slot k))
          (str "canonical slot of " (pr-str k)))
      (is (denied? k) (str "denied: " (pr-str k)))))
  (testing "every alias of the children slot is denied"
    (doseq [k [:children "children" 'children :x/children]]
      (is (denied? k) (str "denied: " (pr-str k)))))
  (testing "a spelling that canonicalizes ELSEWHERE cannot reach React's slot"
    ;; React prop names are case-sensitive, so these are inert attributes, not
    ;; the raw-markup slot — denying them would be theatre, not safety.
    (doseq [k [:dangerouslysetinnerhtml :DangerouslySetInnerHTML]]
      (is (not= "dangerouslySetInnerHTML" (rules/caller-key-slot k)))
      (is (not (denied? k))))))

(deftest legitimate-props-are-untouched
  (testing "no legitimate prop collides with a rejected slot"
    ;; :class slots to className and :for to htmlFor — distinct from the
    ;; rejected :class-name / :html-for, so the deny cannot false-positive.
    (doseq [k [:class :for :style :title :key :ref :value :checked
               :on-click :on-change :data-x :aria-label :tab-index :href :id]]
      (is (not (rules/spread-rejected-key? k)) (str "allowed: " (pr-str k)))
      (is (not (denied? k)) (str "allowed: " (pr-str k)))))
  (testing "a non-nameable key has no slot and is not denied HERE"
    (doseq [k [nil false 5]]
      (is (not (rules/spread-rejected-key? k)))
      (is (not (denied? k))))))

(deftest the-deny-is-on-key-presence-not-value
  (testing "a nil value does not excuse an illegal spelling"
    (is (denied? :dangerouslySetInnerHTML))
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (denied-data
                          #(rules/assert-spread-prop-key! :dangerouslySetInnerHTML)))))))

(deftest the-message-names-the-escape
  (let [d (denied-data #(rules/assert-spread-prop-key! :dangerouslySetInnerHTML))]
    (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
    (is (= 're-frame.ui/spread (:where d)))
    (is (str/includes? (:reason d) "(ui/html ...)"))
    (is (= :dangerouslySetInnerHTML (:key d))))
  (testing ":children names positional children"
    (is (str/includes? (:reason
                        (denied-data #(rules/assert-spread-prop-key! :children)))
                       "positional children")))
  (testing ":class-name names :class"
    (is (str/includes? (:reason
                        (denied-data #(rules/assert-spread-prop-key! :class-name)))
                       ":class"))))

;; ---------------------------------------------------------------------------
;; 2. The host seams
;; ---------------------------------------------------------------------------

(defn- convert
  "Fold a runtime prop map through THIS host's spread seam, exactly as the
  emitted code does. Returns nil on success (we only assert deny/allow here)."
  [m]
  #?(:clj  (do (tree/element :div {:static {:class "card"} :dyn m}) nil)
     :cljs (do (rt/spread->props "div" nil m nil [:site "p"] nil) nil)))

(defn- convert-safe
  "Fold a `ui/spread-safe` CALLER map through this host's seam — the sibling
  spread form shares `convert-prop-map!` / `fold-dyn-entry`."
  [caller]
  #?(:clj  (do (tree/element :div {:static {:class "card"}
                                   :spread-safe {:caller caller
                                                 :owned-handler-keys #{}}})
               nil)
     :cljs (do (rt/spread-safe->props "div" caller #{} [:site "p"] nil) nil)))

(defn- seam-denied? [f m]
  (= :rf.error/ui-tree-malformed (:rf.error/id (denied-data #(f m)))))

(deftest the-seam-denies-every-rejected-spelling
  (testing "ui/spread — the shared runtime converter"
    (doseq [k rejected-spellings]
      (is (seam-denied? convert {k hostile-markup})
          (str "spread seam denies " (pr-str k)))))
  (testing "ui/spread-safe caller map — same seam"
    (doseq [k rejected-spellings]
      (is (seam-denied? convert-safe {k hostile-markup})
          (str "spread-safe seam denies " (pr-str k))))))

(deftest the-seam-denies-the-dangerous-aliases
  (doseq [k dangerous-aliases]
    (is (seam-denied? convert {k hostile-markup})
        (str "spread seam denies alias " (pr-str k)))))

(deftest the-seam-passes-a-legitimate-map
  (testing "an ordinary spread map still converts"
    (is (nil? (convert {:title "t" :class "c" :data-a "b" :tab-index 3})))
    (is (nil? (convert-safe {:title "t" :class "c" :aria-label "a"})))))

;; ---------------------------------------------------------------------------
;; 3. End-to-end — through the real emitters
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- props-obj [m]
     (reduce-kv (fn [o k v] (unchecked-set o (subs (str k) 1) v) o) #js {} m)))

#?(:cljs
   (defn- render-html [view m]
     (rds/renderToStaticMarkup (rt/jsx2 view (props-obj m)))))

(defn- render-denied?
  "Render a compiled view on THIS host with the given props; did the deny fire?"
  [view m]
  (= :rf.error/ui-tree-malformed
     (:rf.error/id (denied-data
                    #?(:clj  #(tree/render view m)
                       :cljs #(render-html view m))))))

(deftest a-compiled-spread-cannot-smuggle-raw-markup
  (testing "via `overrides`"
    (is (render-denied? spread-only
                        {:extra {:dangerouslySetInnerHTML
                                 #?(:clj  {:__html hostile-markup}
                                    :cljs #js {:__html hostile-markup})}})))
  (testing "via `base`"
    (is (render-denied? spread-base-only
                        {:hostile {:dangerouslySetInnerHTML
                                   #?(:clj  {:__html hostile-markup}
                                      :cljs #js {:__html hostile-markup})}})))
  (testing "via a string-keyed alias"
    (is (render-denied? spread-only
                        {:extra {"dangerouslySetInnerHTML"
                                 #?(:clj  {:__html hostile-markup}
                                    :cljs #js {:__html hostile-markup})}})))
  (testing "a smuggled `children` slot cannot displace positional children"
    (is (render-denied? spread-only {:extra {:children "smuggled"}}))))

#?(:cljs
   (deftest the-rendered-markup-carries-no-injection
     ;; The security assertion proper: whatever the emitter does, the hostile
     ;; markup must never appear in the served HTML.
     (doseq [k dangerous-aliases]
       (let [html (try (render-html spread-only
                                    {:extra {k #js {:__html hostile-markup}}})
                       (catch :default _ ""))]
         (is (not (str/includes? html "<img src=x"))
             (str "no injection through " (pr-str k) " — got: " html))
         (is (not (str/includes? html "onerror"))
             (str "no injection through " (pr-str k)))))))

;; ---------------------------------------------------------------------------
;; 4. Positive controls — the sanctioned path still works
;; ---------------------------------------------------------------------------

(def trusted-markup "<b>bold &amp; raw</b>")

#?(:cljs
   (deftest ui-html-still-renders-raw-markup
     (testing "ui/html alone"
       (let [html (render-html trusted-control {:markup trusted-markup})]
         (is (str/includes? html "<b>bold &amp; raw</b>")
             (str "trust assertion honoured — got: " html))))
     (testing "ui/html COMPOSED with ui/spread on the same element (rf2-29s75)"
       (let [html (render-html spread-trusted-control
                               {:extra {} :markup trusted-markup})]
         (is (str/includes? html "<b>bold &amp; raw</b>")
             (str "spread + html compose — got: " html))
         (is (str/includes? html "data-a=\"b\"")
             (str "the spread props still land — got: " html))))
     (testing "a live spread map alongside ui/html is unaffected"
       (let [html (render-html spread-trusted-control
                               {:extra {:title "t"} :markup trusted-markup})]
         (is (str/includes? html "<b>bold &amp; raw</b>"))
         (is (str/includes? html "title=\"t\""))))))

#?(:clj
   (defn- rendered-root
     "`tree/render` returns the view-boundary wrapper; the element is its
     sole child."
     [view props]
     (get-in (tree/render view props) [:children 0])))

#?(:clj
   (deftest ui-html-still-renders-raw-markup-on-the-jvm
     (testing "ui/html alone"
       (is (= [{:html trusted-markup}]
              (:children (rendered-root trusted-control {:markup trusted-markup})))))
     (testing "ui/html COMPOSED with ui/spread on the same element"
       (let [t (rendered-root spread-trusted-control
                              {:extra {:title "t"} :markup trusted-markup})]
         (is (= [{:html trusted-markup}] (:children t)))
         (is (= "t" (get-in t [:attrs :title])))
         (is (= "b" (get-in t [:attrs :data-a])))))))
