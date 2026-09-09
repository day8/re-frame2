(ns re-frame.hicasso.presence-cljs-test
  "PRESENCE AS DATA, HEADLESSLY — the machine, the transform, and the
  census-real toast tray ported both ways (HD-025).

  Every row here runs with **no React, no browser and no clock**: `step`
  and `expire` take `now` as an argument, which is itself one of the
  ruling's claims — a phase that is data can be asserted without a
  timeline. `arm1/presence_dom_cljs_test` then proves React drives this
  machine against a real DOM.

  ## The screen, and the diff this file exists to make honest

  The predecessor's own guide worked example, verbatim (that guide retired
  with its substrate) — a fading toast, with the
  a11y obligation its Accessibility section spells out:

      (v/defview toast-card [{:keys [toast]}]
        (let [exiting? (= :unmounting (v/presence-phase))]
          [:div.toast {:class       (when exiting? \"toast--exit\")
                       :inert       (when exiting? true)
                       :aria-hidden (when exiting? true)}
           (:message toast)]))

      (v/defview toast-tray [_]
        (v/presence {:timeout-ms 300}
          (for [t (v/sub [:toasts/visible])]
            [toast-card {:key (:id t) :toast t}])))

  Two views, an ambient read, and three `(when exiting? …)` attributes —
  and the child view exists **only** so a dynamic var resolves against
  the right child, because that guide records that reading the phase in
  markup written inline in the parent silently yields the parent's phase.

  Both Hicasso spellings are built below and their rendered attributes
  asserted **identical**, so the diff in the pull request is about
  authoring and not about behaviour. The design review that produced
  HD-025 stated its own risk plainly — that all four of its proposals are
  taste rulings dressed as deletions — and this is the instrument that
  answers it for this one."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.presence :as rf.hicasso.impl.presence]))

(use-fixtures :each {:before (fn [] (rf.hicasso.impl.codec/reset-caches!))})

(def ^:private timeout-ms 300)

(defn- toast [id message]
  [:div.toast {:key id
               :re-frame.hicasso.motion/unmounting {:class       "toast--exit"
                                             :inert       true
                                             :aria-hidden true}}
   message])

(defn- state-of
  "Fold a sequence of child-lists into the machine, one render per list,
  with an explicit clock."
  [renders]
  (reduce (fn [s [children now]] (rf.hicasso.impl.presence/step s children now timeout-ms))
          rf.hicasso.impl.presence/initial
          renders))

;; ---------------------------------------------------------------------------
;; The machine
;; ---------------------------------------------------------------------------

(deftest a-child-enters-mounting-then-settles-present
  (let [s (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial [(toast 1 "a")] 0 timeout-ms)]
    (is (= {1 :mounting} (rf.hicasso.impl.presence/phases s)))
    (is (true? (rf.hicasso.impl.presence/mounting? s)))
    (let [s (rf.hicasso.impl.presence/settle s)]
      (is (= {1 :present} (rf.hicasso.impl.presence/phases s)))
      (is (false? (rf.hicasso.impl.presence/mounting? s)))
      (testing "and a later render with the same child leaves it alone"
        (is (= {1 :present} (rf.hicasso.impl.presence/phases
                              (rf.hicasso.impl.presence/step s [(toast 1 "a")] 50 timeout-ms))))))))

(deftest a-child-that-leaves-the-source-is-retained-as-unmounting
  (let [s (-> (state-of [[[(toast 1 "a") (toast 2 "b")] 0]])
              rf.hicasso.impl.presence/settle
              (rf.hicasso.impl.presence/step [(toast 1 "a")] 100 timeout-ms))]
    (is (= {1 :present 2 :unmounting} (rf.hicasso.impl.presence/phases s))
        "the child is gone from the data and still on screen")
    (is (= 400 (rf.hicasso.impl.presence/next-deadline s)) "100 + :timeout-ms, as an instant")
    (testing "and it leaves exactly on time, not before"
      (is (= {1 :present 2 :unmounting} (rf.hicasso.impl.presence/phases (rf.hicasso.impl.presence/expire s 399))))
      (is (= {1 :present} (rf.hicasso.impl.presence/phases (rf.hicasso.impl.presence/expire s 400)))))))

(deftest re-entry-cancels-exit
  (let [s (-> (state-of [[[(toast 1 "a")] 0]])
              rf.hicasso.impl.presence/settle
              (rf.hicasso.impl.presence/step [] 100 timeout-ms))]
    (is (= {1 :unmounting} (rf.hicasso.impl.presence/phases s)))
    (let [back (rf.hicasso.impl.presence/step s [(toast 1 "a")] 150 timeout-ms)]
      (is (= {1 :present} (rf.hicasso.impl.presence/phases back))
          "the exit is cancelled rather than finished-and-remounted")
      (is (nil? (rf.hicasso.impl.presence/next-deadline back)) "and its deadline is gone with it")
      (is (= "a" (nth (first (rf.hicasso.impl.presence/render back)) 2))))))

(deftest the-deadline-is-a-terminal-bound-and-re-deriving-cannot-extend-it
  (testing "the property `:timeout-ms` is FOR. Deadlines are absolute
            instants stored once, so any number of later renders — a
            neighbour arriving, a neighbour leaving, React re-running the
            body — leave a retained child leaving at the instant it was
            always going to."
    (let [s (-> (state-of [[[(toast 1 "a")] 0]]) rf.hicasso.impl.presence/settle
                (rf.hicasso.impl.presence/step [] 100 timeout-ms))]
      (is (= 400 (rf.hicasso.impl.presence/next-deadline s)))
      (let [busy (-> s
                     (rf.hicasso.impl.presence/step [(toast 2 "b")] 150 timeout-ms)
                     (rf.hicasso.impl.presence/step [(toast 2 "b") (toast 3 "c")] 200 timeout-ms)
                     (rf.hicasso.impl.presence/step [(toast 3 "c")] 250 timeout-ms))]
        (is (= 400 (:deadline (get (:entries busy) 1)))
            "still 400 — not 550, which is what re-deriving would give")))))

(deftest step-is-idempotent-which-is-what-lets-react-adjust-state-in-render
  (testing "the property the React half rides. A second application with
            the same children changes nothing, whatever the clock says —
            so the component's `(when-not (= next state) (set-state next))`
            converges after one extra pass and cannot loop."
    (let [children [(toast 1 "a") (toast 2 "b")]
          once     (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial children 0 timeout-ms)
          twice    (rf.hicasso.impl.presence/step once children 99 timeout-ms)]
      (is (= once twice)))
    (let [s     (-> (state-of [[[(toast 1 "a")] 0]]) rf.hicasso.impl.presence/settle)
          once  (rf.hicasso.impl.presence/step s [] 100 timeout-ms)
          twice (rf.hicasso.impl.presence/step once [] 500 timeout-ms)]
      (is (= once twice) "including for a retained child, whose deadline holds"))))

(deftest first-appearance-slots-are-frozen-so-an-exiting-child-does-not-jump
  (let [s (-> (state-of [[[(toast 1 "a") (toast 2 "b") (toast 3 "c")] 0]])
              rf.hicasso.impl.presence/settle
              (rf.hicasso.impl.presence/step [(toast 1 "a") (toast 3 "c")] 100 timeout-ms))]
    (is (= [1 2 3] (:order s)) "the middle child holds its slot while it exits")
    (let [s (rf.hicasso.impl.presence/step s [(toast 1 "a") (toast 3 "c") (toast 4 "d")] 120 timeout-ms)]
      (is (= [1 2 3 4] (:order s)) "and a genuinely new child is appended"))))

(defn- refusal-data
  "Run `f` and answer the ex-data of the refusal it raised — or a marker
  keyword, so a row that fails says WHICH way it failed rather than
  reading `nil` off a success."
  [f]
  (try (f) ::returned-without-refusing
       (catch :default e (ex-data e))))

(deftest nil-children-are-not-entries-and-unkeyed-children-are-a-loud-error
  (is (= {1 :mounting}
         (rf.hicasso.impl.presence/phases (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial
                                         [(toast 1 "a") nil false]
                                         0 timeout-ms))))
  (is (thrown-with-msg? js/Error #"with a :key"
                        (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial [[:div.toast "x"]] 0 timeout-ms)))
  (is (thrown-with-msg? js/Error #"with a :key"
                        (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial ["a string"] 0 timeout-ms)))
  (testing "one id for both — presence cannot identify the child — carrying
            the offending child, which is what a tool branches on"
    (let [unkeyed (refusal-data
                    #(rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial [[:div.toast "x"]] 0 timeout-ms))]
      (is (= :rf.error/hicasso-presence-child-unkeyed (:rf.error/id unkeyed)))
      (is (= [:div.toast "x"] (:child unkeyed))))
    (let [not-hiccup (refusal-data
                       #(rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial ["a string"] 0 timeout-ms))]
      (is (= :rf.error/hicasso-presence-child-unkeyed (:rf.error/id not-hiccup)))
      (is (= "a string" (:child not-hiccup))))))

(deftest timeout-ms-is-mandatory-and-positive
  (is (= 300 (rf.hicasso.impl.presence/check-timeout! 300)))
  (doseq [bad [nil 0 -1 "300"]]
    (is (thrown-with-msg? js/Error #"positive :timeout-ms"
                          (rf.hicasso.impl.presence/check-timeout! bad))
        (str "refused: " (pr-str bad)))
    (let [data (refusal-data #(rf.hicasso.impl.presence/check-timeout! bad))]
      (is (= :rf.error/hicasso-presence-timeout-required (:rf.error/id data))
          (str "with its own id, for " (pr-str bad)))
      (is (= bad (:timeout-ms data))
          "and the offending value, so a red names what was written"))))

;; ---------------------------------------------------------------------------
;; The phase transform
;; ---------------------------------------------------------------------------

(deftest a-native-child-takes-the-phases-override-map-and-nothing-else
  (testing ":present — the overrides are stripped and never reach the DOM"
    (is (= [:div.toast {:key 1} "a"] (rf.hicasso.impl.presence/with-phase (toast 1 "a") :present))))
  (testing ":unmounting — the map is merged, and it WINS, because that is
            what an override is"
    (is (= [:div.toast {:key 1 :class "toast--exit" :inert true :aria-hidden true} "a"]
           (rf.hicasso.impl.presence/with-phase (toast 1 "a") :unmounting))))
  (testing ":mounting — the enter map is merged the same way. This is the
            arm the exit rows cannot witness: an `override-for` answering
            nil at :mounting still STRIPS the override keys, so every
            strip assertion in this file stays green while enter styling
            dies wholesale — the positive merge is the only pin"
    (is (= [:div.toast {:key 1 :class "toast--enter"} "a"]
           (rf.hicasso.impl.presence/with-phase
             [:div.toast {:key 1
                          :re-frame.hicasso.motion/mounting {:class "toast--enter"}}
              "a"]
             :mounting))
        "the mounting map's contents arrive on the element, with the
         override key itself gone"))
  (testing "an override still cannot reach :key or :ref — those address
            node identity, not appearance"
    (let [hostile [:div.toast {:key 1
                               :re-frame.hicasso.motion/unmounting
                               {:key "stolen" :ref (fn [_]) :class "x"}}]]
      (is (= [:div.toast {:key 1 :class "x"}]
             (rf.hicasso.impl.presence/with-phase hostile :unmounting)))))
  (testing "and it cannot reach them under ANY spelling, which is the whole
            of the repair. `\"key\"` and `:x/key` survive a raw `#{:key
            :ref}` dissoc and canonicalise onto React's key — after the
            child's own `:key` has been merged, and at the one moment the
            node must NOT be remounted, because it is being animated out.
            The exclusion is taken on the canonical SLOT, through the
            codec's structural-slot filter."
    (doseq [spelling ["key" 'key :x/key]]
      (let [hostile [:div.toast {:key 1
                                 :re-frame.hicasso.motion/unmounting
                                 {spelling "stolen" :class "x"}}]
            out     (rf.hicasso.impl.presence/with-phase hostile :unmounting)]
        (is (= [:div.toast {:key 1 :class "x"}] out)
            (str "key, spelled " (pr-str spelling)))
        (is (= 1 (rf.hicasso.impl.presence/child-key out))
            "the retained node's identity is the key the machine retains it
             under, and nothing in an override can move it")))
    (doseq [spelling ["ref" 'ref :x/ref]]
      (let [hostile [:div.toast {:key 1
                                 :re-frame.hicasso.motion/unmounting
                                 {spelling (fn [_]) :class "x"}}]]
        (is (= [:div.toast {:key 1 :class "x"}]
               (rf.hicasso.impl.presence/with-phase hostile :unmounting))
            (str "ref, spelled " (pr-str spelling))))))
  (testing "a child that carries no override comes back UNTOUCHED, by
            identity — the transform costs nothing on a node that does not
            use it"
    (let [plain [:div.toast {:key 1} "a"]]
      (is (identical? plain (rf.hicasso.impl.presence/with-phase plain :unmounting))))
    (is (= [:div.toast] (rf.hicasso.impl.presence/with-phase [:div.toast] :present))
        "including a node with no props map, which is not given one")))

(deftest a-legal-override-reaches-the-element-as-appearance-and-never-as-an-attribute
  (testing "the docstring's own claim, MEASURED at the element rather than
            asserted in prose (rf2-34a7). `with-phase` runs on a tray's
            direct children, so this is the only route by which an override
            reaches the codec at all — and what arrives there is an
            ordinary attribute map with the two keys already gone"
    (doseq [phase [:present :mounting :unmounting]]
      (let [child [:div {:key 1
                         :class "toast"
                         :re-frame.hicasso.motion/mounting   {:class "toast--enter"}
                         :re-frame.hicasso.motion/unmounting {:class "toast--exit"}}]
            names (set (js/Object.keys (.-props (rf.hicasso.impl.codec/as-element
                                                  (rf.hicasso.impl.presence/with-phase child phase)))))]
        (is (not (contains? names "mounting")) (str "at " phase))
        (is (not (contains? names "unmounting")) (str "at " phase)))))

  (testing "and the same child written where NO tray can reach it is
            skipped by the codec's walk rather than emitted, which is the
            other half of the sentence: between the two there is no route
            to the DOM"
    (let [names (set (js/Object.keys (.-props (rf.hicasso.impl.codec/as-element
                                                 [:div {:re-frame.hicasso.motion/mounting {:class "toast--enter"}}]))))]
      (is (not (contains? names "mounting"))))))

(deftest a-boundary-child-takes-the-override-map-as-ordinary-props
  (let [card (rf.hicasso.impl.codec/mark-boundary! (fn [_] nil))]
    (is (= [card {:key 1 :toast {:id 1} :exiting? true}]
           (rf.hicasso.impl.presence/with-phase [card {:key 1 :toast {:id 1}
                                       :re-frame.hicasso.motion/unmounting {:exiting? true}}]
                                :unmounting))
        "the same merge an element gets (HD-030): the phase's map lands in
         the view's props under the names its author chose, it appears in a
         structural test's props map, and a headless test can supply it")
    (testing "and in a phase the child declared nothing for, the view is
              handed exactly what it was written with — no phase value, no
              reserved key"
      (is (= [card {:key 1 :toast {:id 1}}]
             (rf.hicasso.impl.presence/with-phase [card {:key 1 :toast {:id 1}
                                         :re-frame.hicasso.motion/unmounting {:exiting? true}}]
                                  :present))))
    (testing "a view that declares no override comes back untouched, by
              identity, like an element"
      (let [plain [card {:key 1 :toast {:id 1}}]]
        (is (identical? plain (rf.hicasso.impl.presence/with-phase plain :unmounting)))))
    (testing "and an override on a view head cannot reach `:key` either"
      (is (= [card {:key 1 :exiting? true}]
             (rf.hicasso.impl.presence/with-phase [card {:key 1 :re-frame.hicasso.motion/unmounting
                                         {:key "stolen" :exiting? true}}]
                                  :unmounting))))))

;; ---------------------------------------------------------------------------
;; The census-real screen, ported both ways
;; ---------------------------------------------------------------------------

(defn- toast-card-body
  "BEFORE — the predecessor's shape, minus its trap. The child view exists
  only so a per-child exiting flag can be read; here the flag at least
  arrives as a prop the tray merged rather than as an ambient read, so
  this rendering is already strictly safer than the one the guide
  teaches. The three `(when exiting? …)` attributes are the part HD-025
  is about."
  [{:keys [message exiting?]}]
  [:div.toast {:class       (when exiting? "toast--exit")
               :inert       (when exiting? true)
               :aria-hidden (when exiting? true)}
   message])

(def ^:private toast-card (rf.hicasso.impl.codec/mark-boundary! toast-card-body))

(defn- child-view-tray
  "BEFORE — a keyed child view per toast, declaring the one prop it
  branches on under the same override key an element would carry."
  [toasts]
  (mapv (fn [t] [toast-card {:key (:id t) :message (:message t)
                             :re-frame.hicasso.motion/unmounting {:exiting? true}}])
        toasts))

(defn- inline-tray
  "AFTER — no child view at all, and the three attributes are one map."
  [toasts]
  (mapv (fn [t]
          [:div.toast {:key (:id t)
                       :re-frame.hicasso.motion/unmounting {:class       "toast--exit"
                                                     :inert       true
                                                     :aria-hidden true}}
           (:message t)])
        toasts))

(defn- attrs
  "An element's props with nil-valued entries dropped — `nil` and absent
  are the same attribute to React, and the two renderings differ on
  exactly that: `(when exiting? …)` writes the key with a nil value where
  an override simply does not write the key."
  [e]
  (into (sorted-map)
        (remove (fn [[_ v]] (nil? v)))
        (dissoc (js->clj (.-props e)) "children")))

(defn- rendered
  "Drive one tray through the machine to `phase`, then through the codec,
  and read the toast elements back."
  [tray toasts phase]
  (let [seeded (rf.hicasso.impl.presence/settle (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial (tray toasts) 0 timeout-ms))
        state  (if (= :unmounting phase)
                 (rf.hicasso.impl.presence/step seeded (tray []) 100 timeout-ms)
                 seeded)
        hiccup (rf.hicasso.impl.presence/render state)]
    ;; A boundary child renders through its body; a native child is already
    ;; the node. Both end at the codec, which is where the comparison is
    ;; taken, because that is the last point before React.
    (mapv (fn [child]
            (rf.hicasso.impl.codec/as-element
              (if (rf.hicasso.impl.codec/boundary-head? (nth child 0))
                (toast-card-body (nth child 1))
                child)))
          hiccup)))

(def ^:private toasts [{:id 1 :message "Saved"} {:id 2 :message "Copied"}])

(deftest the-inline-tray-renders-what-the-child-view-tray-renders
  (doseq [phase [:present :unmounting]]
    (testing (str "phase " phase)
      (let [before (rendered child-view-tray toasts phase)
            after  (rendered inline-tray toasts phase)]
        (is (= 2 (count before) (count after)))
        (doseq [[b a] (map vector before after)]
          (is (= "div" (.-type b) (.-type a)))
          (is (= (attrs b) (attrs a))))))))

(deftest the-exit-attributes-arrive-while-unmounting-and-go-on-re-entry
  (testing "witness 1: a toast written INLINE — no child view — gets the exit
            attributes while unmounting, and loses them when it comes back."
    (let [seeded (rf.hicasso.impl.presence/settle
                   (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial (inline-tray toasts) 0 timeout-ms))
          gone   (rf.hicasso.impl.presence/step seeded (inline-tray [(first toasts)]) 100 timeout-ms)
          back   (rf.hicasso.impl.presence/step gone (inline-tray toasts) 150 timeout-ms)]
      (is (= {1 :present 2 :unmounting} (rf.hicasso.impl.presence/phases gone)))
      (let [exiting (attrs (rf.hicasso.impl.codec/as-element (second (rf.hicasso.impl.presence/render gone))))]
        (is (= "toast toast--exit" (get exiting "className")))
        (is (true? (get exiting "inert")))
        (is (true? (get exiting "aria-hidden"))))
      (is (= {1 :present 2 :present} (rf.hicasso.impl.presence/phases back)))
      (let [restored (attrs (rf.hicasso.impl.codec/as-element (second (rf.hicasso.impl.presence/render back))))]
        (is (= "toast" (get restored "className")))
        (is (nil? (get restored "inert")))
        (is (nil? (get restored "aria-hidden"))))))
  (testing "witness 3, headlessly: the child is gone after :timeout-ms and
            the machine retains nothing"
    (let [seeded (rf.hicasso.impl.presence/settle
                   (rf.hicasso.impl.presence/step rf.hicasso.impl.presence/initial (inline-tray toasts) 0 timeout-ms))
          gone   (rf.hicasso.impl.presence/step seeded (inline-tray [(first toasts)]) 100 timeout-ms)
          done   (rf.hicasso.impl.presence/expire gone 400)]
      (is (= {1 :present} (rf.hicasso.impl.presence/phases done)))
      (is (= [1] (:order done)))
      (is (= 1 (count (:entries done))))
      (is (nil? (rf.hicasso.impl.presence/next-deadline done))))))
