(ns re-frame.ui.migrator-test
  "Fixture tests for the W1 Reagent -> re-frame.ui migrator.

  Coverage: at least one fixture per MIG rule (MIG-01..35) asserting the
  input->output transform (M rules) or the prepared flag / reject (D/R rules),
  plus adversarial / negative cases (malformed input, data-vector-not-hiccup,
  gating whole-view law) and an idempotence sweep.

  The rule ids and tiers are the tool<->W2-skill contract; a rule's disappearance
  or tier change should fail a test here first."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.ui.migrator :as m]
            [re-frame.ui.migrator.rules :as rules]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- out [s] (:source (m/rewrite-string s)))
(defn- findings [s] (m/scan-string s))
(defn- rule-ids [s] (into #{} (map :rule) (findings s)))
(defn- finding-for [s rule] (first (filter #(= rule (:rule %)) (m/scan-string s))))
(defn- has? [s rule] (contains? (rule-ids s) rule))
(defn- includes? [s sub] (str/includes? s sub))

;; ---------------------------------------------------------------------------
;; MIG-01 - Form-1/reg-view -> defview; params -> map; call sites atomic
;; ---------------------------------------------------------------------------

(deftest mig-01-form1-and-call-site
  (testing "defn view + positional params + vector call site convert atomically"
    (let [src "(defn price [amt cur] [:span amt cur])\n(defn app [] [:div [price 1 2]])"
          o   (out src)]
      (is (includes? o "(ui/defview price [{:keys [amt cur]}]"))
      (is (includes? o "[price {:amt 1 :cur 2}]"))
      (is (has? src "MIG-01")))))

(deftest mig-01-reg-view-unwrap
  (testing "reg-view unwraps to ui/defview"
    (let [src "(ns a (:require [re-frame.core :refer [reg-view]]))\n(reg-view greeter [n] [:h1 n])"
          o (out src)]
      (is (includes? o "(ui/defview greeter [{:keys [n]}]")))))

(deftest mig-01-zero-param-call-site
  (testing "zero-param view -> [{}] and call site emits the explicit empty map"
    (let [src "(defn status-pill [] [:span])\n(defn app [] [:div [status-pill]])"
          o (out src)]
      (is (includes? o "[status-pill {}]")))))

(deftest mig-01-fn-call-site
  (testing "(view a c) hiccup-returning fn-call site -> [view {..}]"
    (let [src "(defn filter-link [showing txt] [:a txt])\n(defn app [] [:ul (filter-link :all :All)])"
          o (out src)]
      (is (includes? o "[filter-link {:showing :all :txt :All}]")))))

;; ---------------------------------------------------------------------------
;; MIG-02 - @(subscribe ..) -> (sub ..)
;; ---------------------------------------------------------------------------

(deftest mig-02-deref-drop
  (let [src "(ns a (:require [re-frame.core :refer [subscribe]]))\n(defn f [] [:span @(subscribe [:total])])"
        o (out src)]
    (is (includes? o "(sub [:total])"))
    ;; the deref'd subscribe CALL is gone (the :refer stays until MIG-24's
    ;; unused-require drop, which is out of the mechanical body scope)
    (is (not (includes? o "@(subscribe")))
    (is (not (includes? o "(subscribe [")))))

;; ---------------------------------------------------------------------------
;; MIG-03 - explicit-frame op => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-03-explicit-frame
  (let [src "(ns a (:require [re-frame.core :as rf]))\n(defn f [] [:div @(rf/subscribe [:q] {:frame ff})])"]
    (is (has? src "MIG-03"))
    (is (= :flag (:action (finding-for src "MIG-03"))))
    (is (= src (out src)) "gated view left unconverted")))

;; ---------------------------------------------------------------------------
;; MIG-04/05/06 - handler lifting
;; ---------------------------------------------------------------------------

(deftest mig-04-dispatch-lift
  (let [src "(ns a (:require [re-frame.core :refer [dispatch]]))\n(defn f [] [:button {:on-click #(dispatch [:go 1])} :x])"]
    (is (includes? (out src) "{:on-click [:go 1]}"))))

(deftest mig-05-placeholder-extraction
  (let [src "(ns a (:require [re-frame.core :refer [dispatch]]))\n(defn f [] [:input {:on-input #(dispatch [:typed (-> % .-target .-value)])}])"]
    (is (includes? (out src) "{:on-input [:typed :rf.ui/value]}"))))

(deftest mig-06-prevent-default-options-map
  (let [src "(ns a (:require [re-frame.core :refer [dispatch]]))\n(defn f [] [:form {:on-submit (fn [e] (.preventDefault e) (dispatch [:save]))} :c])"]
    (is (includes? (out src) "{:event [:save] :prevent-default true}"))))

;; ---------------------------------------------------------------------------
;; MIG-07 - key-meta -> prop
;; ---------------------------------------------------------------------------

(deftest mig-07-key-meta
  (let [src "(defn item [t] [:li t])\n(defn f [] [:ul (for [t ts] ^{:key (:id t)} [item t])])"
        o (out src)]
    (is (includes? o "[item {:key (:id t)"))
    (is (not (includes? o "^{:key")))))

;; ---------------------------------------------------------------------------
;; MIG-08 - loop-body legality => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-08-unkeyed-for
  (let [src "(defn f [] [:ul (for [t ts] [:li t])])"]
    (is (has? src "MIG-08"))
    (is (= src (out src)) "unkeyed for gates the whole view")))

;; ---------------------------------------------------------------------------
;; MIG-09 - foreign heads
;; ---------------------------------------------------------------------------

(deftest mig-09-interop-head
  (let [src "(defn f [] [:> Button {:label :x}])"]
    (is (includes? (out src) "[Button {:label :x}]"))
    (is (not (includes? (out src) ":>")))))

(deftest mig-09-adapt-react-class
  (let [src "(ns a (:require [reagent.core :as r]))\n(defn f [] [(r/adapt-react-class Widget) {:p 1}])"
        o (out src)]
    (is (includes? o "[Widget {:p 1}]"))
    (is (not (includes? o "adapt-react-class")))))

;; ---------------------------------------------------------------------------
;; MIG-10 - fn prop at foreign boundary => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-10-foreign-fn-prop
  (let [src "(defn f [] [:> Picker {:on-select (fn [x] x)}])"]
    (is (has? src "MIG-10"))
    (is (= src (out src)) "foreign fn prop gates the view")))

;; ---------------------------------------------------------------------------
;; MIG-11 - DOM prop name respelling
;; ---------------------------------------------------------------------------

(deftest mig-11-respell
  (let [src "(defn f [] [:label {:className :c :htmlFor :x :tabIndex 1} :t])"
        o (out src)]
    (is (includes? o ":class"))
    (is (includes? o ":for"))
    (is (includes? o ":tab-index"))
    (is (not (includes? o ":className")))))

(deftest mig-11-onclick-respell-and-lift
  (testing "camelCase :onClick both respells AND lifts the dispatch"
    (let [src "(ns a (:require [re-frame.core :refer [dispatch]]))\n(defn f [] [:a {:onClick #(dispatch [:go])} :t])"
          o (out src)]
      (is (includes? o ":on-click [:go]")))))

;; ---------------------------------------------------------------------------
;; MIG-12 - doall strip
;; ---------------------------------------------------------------------------

(deftest mig-12-doall-strip
  (let [src "(defn f [] [:ul (doall (for [t ts] ^{:key t} [:li t]))])"
        o (out src)]
    (is (not (includes? o "doall")))
    (is (includes? o "(for [t ts]"))))

;; ---------------------------------------------------------------------------
;; MIG-13 - markup-returning map => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-13-map-markup
  (let [src "(defn f [] [:ul (map (fn [t] [:li t]) ts)])"]
    (is (has? src "MIG-13"))
    (is (some? (:suggest (finding-for src "MIG-13"))))
    (is (= src (out src)))))

;; ---------------------------------------------------------------------------
;; MIG-14 - hiccup pass-through + sub-rules
;; ---------------------------------------------------------------------------

(deftest mig-14-plain-hiccup-passthrough
  (testing "plain hiccup structure is unchanged apart from the header"
    (let [src "(defn plain [] [:div.wrap#main [:span \"hi\"] [:p 42]])"
          o (out src)]
      (is (includes? o "[:div.wrap#main [:span \"hi\"] [:p 42]]")))))

(deftest mig-14-duplicate-id-sugar
  (testing "two #id segments -> keep the first (M)"
    (let [src "(defn f [] [:div#a#b :x])"
          o (out src)]
      (is (has? src "MIG-14"))
      (is (includes? o ":div#a")))))

;; ---------------------------------------------------------------------------
;; MIG-15 - mount
;; ---------------------------------------------------------------------------

(deftest mig-15-mount
  (let [src "(ns a (:require [reagent.dom :as rdom]))\n(defn init! [] (rdom/render [app] el))"
        o (out src)]
    (is (includes? o "(ui/mount [ui/frame-root"))
    (is (includes? o "[app {}]"))))

;; ---------------------------------------------------------------------------
;; MIG-16/17 - local state / lifecycle => D flag (gate)
;; ---------------------------------------------------------------------------

(deftest mig-16-with-let-gates
  (let [src "(ns a (:require [reagent.core :as r]))\n(defn dd [] (r/with-let [o (r/atom false)] [:div @o]))"]
    (is (has? src "MIG-16"))
    (is (= src (out src)) "Form-2 state gates the whole view")
    (is (includes? (:suggest (finding-for src "MIG-16")) "local"))))

(deftest mig-17-create-class
  (let [src "(ns a (:require [reagent.core :as r]))\n(defn f [] (r/create-class {:reagent-render (fn [] [:div])}))"]
    (is (has? src "MIG-17"))
    (is (= src (out src)))))

;; ---------------------------------------------------------------------------
;; MIG-18 - non-conforming handler => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-18-mixed-handler
  (let [src "(ns a (:require [re-frame.core :refer [dispatch]]))\n(defn f [] [:button {:on-click (fn [] (thing!) (dispatch [:x]))} :t])"]
    (is (has? src "MIG-18"))
    (is (= src (out src)))))

;; ---------------------------------------------------------------------------
;; MIG-19 - derived state => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-19-track
  (let [src "(ns a (:require [reagent.core :as r]))\n(defn f [] (let [x (r/track compute a)] [:div @x]))"]
    (is (has? src "MIG-19"))
    (is (= src (out src)))))

;; ---------------------------------------------------------------------------
;; MIG-20/21/22 - rejects
;; ---------------------------------------------------------------------------

(deftest mig-20-ratom-store-add-watch
  (let [src "(defn f [] (add-watch some-ratom :k (fn [_ _ _ v] v)))"]
    (is (has? src "MIG-20"))
    (is (= :reject (:action (finding-for src "MIG-20"))))))

(deftest mig-21-dynamic-head
  (let [src "(defn f [big?] [(if big? :h1 :h2) :title])"]
    (is (has? src "MIG-21"))
    (is (= :reject (:action (finding-for src "MIG-21"))))
    (is (= src (out src)))))

(deftest mig-22-recom
  (let [src "(ns a (:require [re-com.core :as rc]))\n(defn f [] [rc/single-dropdown {:choices cs}])"]
    (is (has? src "MIG-22"))
    (is (= :reject (:action (finding-for src "MIG-22"))))))

;; ---------------------------------------------------------------------------
;; MIG-23 - SSR (staged S5) => flag only
;; ---------------------------------------------------------------------------

(deftest mig-23-ssr
  (let [src "(ns a (:require [reagent.dom.server :as s]))\n(defn r [] (s/render-to-string [app]))"]
    (is (has? src "MIG-23"))
    (is (= src (out src)) "staged S5 emits no rewrite")))

;; ---------------------------------------------------------------------------
;; MIG-24 - ns requires fixup
;; ---------------------------------------------------------------------------

(deftest mig-24-adds-require
  (let [src "(ns a (:require [reagent.core :as r]))\n(defn f [] [:span 1])"
        o (out src)]
    (is (includes? o "[re-frame.ui :as ui :refer [defview sub]]"))))

;; ---------------------------------------------------------------------------
;; MIG-25 - effectful sub body => reject
;; ---------------------------------------------------------------------------

(deftest mig-25-sub-body-effect
  (let [src "(ns a (:require [re-frame.core :refer [reg-sub dispatch]]))\n(reg-sub :q (fn [db] (dispatch [:log]) (:x db)))"]
    (is (has? src "MIG-25"))
    (is (= :reject (:action (finding-for src "MIG-25"))))))

;; ---------------------------------------------------------------------------
;; MIG-26 - ambient ops in a plain fn => D flag, body not rewritten
;; ---------------------------------------------------------------------------

(deftest mig-26-plain-fn-ambient
  (let [src "(ns a (:require [re-frame.core :as rf]))\n(defn helper [] (let [v @(rf/subscribe [:q])] (str v)))"]
    (is (has? src "MIG-26"))
    (is (= src (out src)) "a plain fn is not a view -> its body is not rewritten")))

;; ---------------------------------------------------------------------------
;; MIG-27 - internal-view fn prop => D flag, NON-gating (view still converts)
;; ---------------------------------------------------------------------------

(deftest mig-27-internal-view-fn-prop-nongating
  (let [src "(ns a (:require [re-frame.core :refer [dispatch]]))\n(defn app [] [todo-input {:on-change (fn [x] (dispatch [:edit x]))}])"
        o (out src)]
    (is (has? src "MIG-27"))
    (is (includes? o "(ui/defview app") "C-13a: MIG-27 does not gate")))

;; ---------------------------------------------------------------------------
;; MIG-28 - computed props map -> ui/spread (rewrite + flag)
;; ---------------------------------------------------------------------------

(deftest mig-28-spread
  (let [src "(defn f [props] [:input (merge props {:type :text})])"
        o (out src)]
    (is (has? src "MIG-28"))
    (is (includes? o "(ui/spread (merge props {:type :text}))"))))

;; ---------------------------------------------------------------------------
;; MIG-29 - callback ref -> ui/raw-fn
;; ---------------------------------------------------------------------------

(deftest mig-29-ref
  (let [src "(defn f [] [:input {:ref (fn [n] (when n (.focus n)))}])"
        o (out src)]
    (is (includes? o "(ui/raw-fn (fn [n]"))))

;; ---------------------------------------------------------------------------
;; MIG-30 - runtime-built markup helper => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-30-runtime-markup
  (let [src "(defn render-body [ast] (reduce conj [:div] (walk ast)))\n(defn article [] [:section (render-body a)])"]
    (is (has? src "MIG-30"))))

;; ---------------------------------------------------------------------------
;; MIG-31 - capture-frame -> (frame)
;; ---------------------------------------------------------------------------

(deftest mig-31-capture-frame
  (let [src "(ns a (:require [re-frame.core :as rf]))\n(defn f [] (let [h (rf/capture-frame)] [:div :x]))"
        o (out src)]
    (is (includes? o "(let [h (ui/frame)]"))))

;; ---------------------------------------------------------------------------
;; MIG-32 - route-link => D flag (gates)
;; ---------------------------------------------------------------------------

(deftest mig-32-route-link
  (let [src "(ns a (:require [re-frame.core :as rf]))\n(defn nav [] [rf/route-link {:to :home} :Home])"]
    (is (has? src "MIG-32"))
    (is (= src (out src)))))

;; ---------------------------------------------------------------------------
;; MIG-33 - adapter boot
;; ---------------------------------------------------------------------------

(deftest mig-33-init
  (let [src "(ns a (:require [re-frame.core :as rf] [re-frame.adapter.reagent :as ra]))\n(rf/init! ra/adapter)"
        o (out src)]
    (is (includes? o "(rf/init! ui/adapter)"))))

;; ---------------------------------------------------------------------------
;; MIG-34 - dangerouslySetInnerHTML -> ui/html
;; ---------------------------------------------------------------------------

(deftest mig-34-html
  (let [src "(defn f [] [:div {:dangerouslySetInnerHTML {:__html s}}])"
        o (out src)]
    (is (includes? o "[:div (ui/html s)]"))
    (is (not (includes? o "dangerouslySetInnerHTML")))))

;; ---------------------------------------------------------------------------
;; MIG-35 - introspection / scheduler => reject
;; ---------------------------------------------------------------------------

(deftest mig-35-introspection
  (let [src "(ns a (:require [reagent.core :as r]))\n(defn f [] [:div (str (r/current-component))])"]
    (is (has? src "MIG-35"))
    (is (= :reject (:action (finding-for src "MIG-35"))))))

;; ---------------------------------------------------------------------------
;; adversarial / negative / structural
;; ---------------------------------------------------------------------------

(deftest data-vectors-are-not-hiccup
  (testing "event/query vectors passed to dispatch/subscribe are not element-mangled"
    (let [src "(ns a (:require [re-frame.core :refer [subscribe dispatch]]))\n(defn f [] [:button {:on-click #(dispatch [:buy 1])} @(subscribe [:total])])"
          o (out src)]
      (is (not (has? src "MIG-28")) "[:buy 1] is not a computed props map")
      (is (includes? o "{:on-click [:buy 1]}"))
      (is (includes? o "(sub [:total])")))))

(deftest child-content-not-treated-as-props
  (testing "[:li item] - a bare symbol child is content, not a spread props map"
    (let [src "(defn item [t] [:li {:key t} t])\n(defn f [] [:ul (for [t ts] ^{:key t} [item t])])"]
      (is (not (has? src "MIG-28"))))))

(deftest malformed-input-graceful
  (testing "unparseable source is left unchanged and reported, never thrown"
    (let [src "(defn broken [ [:div"
          {:keys [source findings]} (m/rewrite-string src)]
      (is (= src source))
      (is (= "PARSE" (:rule (first findings)))))))

(deftest gating-is-whole-view
  (testing "a view with any gating hit is left entirely unconverted (no half-migrated body)"
    (let [src "(ns a (:require [re-frame.core :refer [subscribe dispatch] :as rf] [reagent.core :as r]))\n(defn dd [] (r/with-let [o (r/atom false)] [:span {:on-click #(dispatch [:x])} @(subscribe [:q])]))"
          o (out src)]
      (is (= src o) "the sub/handler inside a gated view are NOT rewritten")
      (is (some :held? (findings src)) "findings inside a gated view are marked held"))))

(deftest idempotence-sweep
  (testing "re-running the migrator over migrated output is a no-op"
    (doseq [src ["(ns app (:require [re-frame.core :refer [subscribe dispatch]]))\n(defn price [a] [:span {:on-click #(dispatch [:buy a])} @(subscribe [:t])])\n(defn app [] [:div [price 1]])"
                 "(defn f [] [:> Button {:label :x}])"
                 "(defn f [] [:div {:dangerouslySetInnerHTML {:__html s}}])"
                 "(ns a (:require [reagent.dom :as rdom]))\n(defn init! [] (rdom/render [app] el))"]]
      (let [once (out src)
            twice (out once)]
        (is (= once twice) (str "not idempotent: " src))))))

(deftest every-rule-has-a-registry-entry
  (testing "MIG-01..35 are all present in the registry with a tier"
    (is (= 35 (count rules/registry)))
    (doseq [n (range 1 36)]
      (let [id (format "MIG-%02d" n)]
        (is (contains? (set (map :id rules/registry)) id) (str "missing " id))
        (is (#{:M :D :R} (rules/tier id)))))))
