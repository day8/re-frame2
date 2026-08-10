(ns re-frame.hicasso.presence-cljs-test
  "PRESENCE AS DATA, HEADLESSLY — the machine, the transform, and the
  census-real toast tray ported both ways (rf2-2rtt6.37, HD-025).

  Every row here runs with **no React, no browser and no clock**: `step`
  and `expire` take `now` as an argument, which is itself one of the
  ruling's claims — a phase that is data can be asserted without a
  timeline. `arm1/presence_dom_cljs_test` then proves React drives this
  machine against a real DOM.

  ## The screen, and the diff this file exists to make honest

  The predecessor's own guide worked example, verbatim
  (`docs/core/freehand/host/presence.md`) — a fading toast, with the
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
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.presence :as presence]))

(use-fixtures :each {:before (fn [] (codec/reset-caches!))})

(def ^:private timeout-ms 300)

(defn- toast [id message]
  [:div.toast {:key id
               :re-frame.hicasso/unmounting {:class       "toast--exit"
                                             :inert       true
                                             :aria-hidden true}}
   message])

(defn- state-of
  "Fold a sequence of child-lists into the machine, one render per list,
  with an explicit clock."
  [renders]
  (reduce (fn [s [children now]] (presence/step s children now timeout-ms))
          presence/initial
          renders))

;; ---------------------------------------------------------------------------
;; The machine
;; ---------------------------------------------------------------------------

(deftest a-child-enters-mounting-then-settles-present
  (let [s (presence/step presence/initial [(toast 1 "a")] 0 timeout-ms)]
    (is (= {1 :mounting} (presence/phases s)))
    (is (true? (presence/mounting? s)))
    (let [s (presence/settle s)]
      (is (= {1 :present} (presence/phases s)))
      (is (false? (presence/mounting? s)))
      (testing "and a later render with the same child leaves it alone"
        (is (= {1 :present} (presence/phases
                              (presence/step s [(toast 1 "a")] 50 timeout-ms))))))))

(deftest a-child-that-leaves-the-source-is-retained-as-unmounting
  (let [s (-> (state-of [[[(toast 1 "a") (toast 2 "b")] 0]])
              presence/settle
              (presence/step [(toast 1 "a")] 100 timeout-ms))]
    (is (= {1 :present 2 :unmounting} (presence/phases s))
        "the child is gone from the data and still on screen")
    (is (= 400 (presence/next-deadline s)) "100 + :timeout-ms, as an instant")
    (testing "and it leaves exactly on time, not before"
      (is (= {1 :present 2 :unmounting} (presence/phases (presence/expire s 399))))
      (is (= {1 :present} (presence/phases (presence/expire s 400)))))))

(deftest re-entry-cancels-exit
  (let [s (-> (state-of [[[(toast 1 "a")] 0]])
              presence/settle
              (presence/step [] 100 timeout-ms))]
    (is (= {1 :unmounting} (presence/phases s)))
    (let [back (presence/step s [(toast 1 "a")] 150 timeout-ms)]
      (is (= {1 :present} (presence/phases back))
          "the exit is cancelled rather than finished-and-remounted")
      (is (nil? (presence/next-deadline back)) "and its deadline is gone with it")
      (is (= "a" (nth (first (presence/render back)) 2))))))

(deftest the-deadline-is-a-terminal-bound-and-re-deriving-cannot-extend-it
  (testing "the property `:timeout-ms` is FOR. Deadlines are absolute
            instants stored once, so any number of later renders — a
            neighbour arriving, a neighbour leaving, React re-running the
            body — leave a retained child leaving at the instant it was
            always going to."
    (let [s (-> (state-of [[[(toast 1 "a")] 0]]) presence/settle
                (presence/step [] 100 timeout-ms))]
      (is (= 400 (presence/next-deadline s)))
      (let [busy (-> s
                     (presence/step [(toast 2 "b")] 150 timeout-ms)
                     (presence/step [(toast 2 "b") (toast 3 "c")] 200 timeout-ms)
                     (presence/step [(toast 3 "c")] 250 timeout-ms))]
        (is (= 400 (:deadline (get (:entries busy) 1)))
            "still 400 — not 550, which is what re-deriving would give")))))

(deftest step-is-idempotent-which-is-what-lets-react-adjust-state-in-render
  (testing "the property the React half rides. A second application with
            the same children changes nothing, whatever the clock says —
            so the component's `(when-not (= next state) (set-state next))`
            converges after one extra pass and cannot loop."
    (let [children [(toast 1 "a") (toast 2 "b")]
          once     (presence/step presence/initial children 0 timeout-ms)
          twice    (presence/step once children 99 timeout-ms)]
      (is (= once twice)))
    (let [s     (-> (state-of [[[(toast 1 "a")] 0]]) presence/settle)
          once  (presence/step s [] 100 timeout-ms)
          twice (presence/step once [] 500 timeout-ms)]
      (is (= once twice) "including for a retained child, whose deadline holds"))))

(deftest first-appearance-slots-are-frozen-so-an-exiting-child-does-not-jump
  (let [s (-> (state-of [[[(toast 1 "a") (toast 2 "b") (toast 3 "c")] 0]])
              presence/settle
              (presence/step [(toast 1 "a") (toast 3 "c")] 100 timeout-ms))]
    (is (= [1 2 3] (:order s)) "the middle child holds its slot while it exits")
    (let [s (presence/step s [(toast 1 "a") (toast 3 "c") (toast 4 "d")] 120 timeout-ms)]
      (is (= [1 2 3 4] (:order s)) "and a genuinely new child is appended"))))

(deftest nil-children-are-not-entries-and-unkeyed-children-are-a-loud-error
  (is (= {1 :mounting}
         (presence/phases (presence/step presence/initial
                                         [(toast 1 "a") nil false]
                                         0 timeout-ms))))
  (is (thrown-with-msg? js/Error #"no :key"
                        (presence/step presence/initial [[:div.toast "x"]] 0 timeout-ms)))
  (is (thrown-with-msg? js/Error #"keyed hiccup vector"
                        (presence/step presence/initial ["a string"] 0 timeout-ms))))

(deftest timeout-ms-is-mandatory-and-positive
  (is (= 300 (presence/check-timeout! 300)))
  (doseq [bad [nil 0 -1 "300"]]
    (is (thrown-with-msg? js/Error #"positive :timeout-ms"
                          (presence/check-timeout! bad))
        (str "refused: " (pr-str bad)))))

;; ---------------------------------------------------------------------------
;; The phase transform
;; ---------------------------------------------------------------------------

(deftest a-native-child-takes-the-phases-override-map-and-nothing-else
  (testing ":present — the overrides are stripped and never reach the DOM"
    (is (= [:div.toast {:key 1} "a"] (presence/with-phase (toast 1 "a") :present))))
  (testing ":unmounting — the map is merged, and it WINS, because that is
            what an override is"
    (is (= [:div.toast {:key 1 :class "toast--exit" :inert true :aria-hidden true} "a"]
           (presence/with-phase (toast 1 "a") :unmounting))))
  (testing "an override still cannot reach :key or :ref — the same law :&
            carries, for the same reason: those address node identity, not
            appearance"
    (let [hostile [:div.toast {:key 1
                               :re-frame.hicasso/unmounting
                               {:key "stolen" :ref (fn [_]) :class "x"}}]]
      (is (= [:div.toast {:key 1 :class "x"}]
             (presence/with-phase hostile :unmounting)))))
  (testing "and it cannot reach them under ANY spelling, which is the whole
            of the repair. `\"key\"` and `:x/key` survive a raw `#{:key
            :ref}` dissoc and canonicalise onto React's key — after the
            child's own `:key` has been merged, and at the one moment the
            node must NOT be remounted, because it is being animated out.
            The exclusion is taken on the canonical SLOT, through the very
            filter `:&` uses."
    (doseq [spelling ["key" 'key :x/key]]
      (let [hostile [:div.toast {:key 1
                                 :re-frame.hicasso/unmounting
                                 {spelling "stolen" :class "x"}}]
            out     (presence/with-phase hostile :unmounting)]
        (is (= [:div.toast {:key 1 :class "x"}] out)
            (str "key, spelled " (pr-str spelling)))
        (is (= 1 (presence/child-key out))
            "the retained node's identity is the key the machine retains it
             under, and nothing in an override can move it")))
    (doseq [spelling ["ref" 'ref :x/ref]]
      (let [hostile [:div.toast {:key 1
                                 :re-frame.hicasso/unmounting
                                 {spelling (fn [_]) :class "x"}}]]
        (is (= [:div.toast {:key 1 :class "x"}]
               (presence/with-phase hostile :unmounting))
            (str "ref, spelled " (pr-str spelling))))))
  (testing "a child that carries no override comes back UNTOUCHED, by
            identity — the transform costs nothing on a node that does not
            use it"
    (let [plain [:div.toast {:key 1} "a"]]
      (is (identical? plain (presence/with-phase plain :unmounting))))
    (is (= [:div.toast] (presence/with-phase [:div.toast] :present))
        "including a node with no props map, which is not given one")))

(deftest a-boundary-child-takes-the-phase-as-an-ordinary-prop
  (let [card (codec/mark-boundary! (fn [_] nil))]
    (is (= [card {:key 1 :toast {:id 1} :rf/phase :unmounting}]
           (presence/with-phase [card {:key 1 :toast {:id 1}}] :unmounting))
        "it appears in a structural test's props map, it cannot be read from
         the wrong render scope, and a headless test can supply it")
    (testing "and an attribute override written on a VIEW head is a loud
              error naming the prop, because the boundary cannot see inside
              an opaque child and dropping the map silently is the class of
              failure this ruling deletes"
      (is (thrown-with-msg?
            js/Error #":rf/phase"
            (presence/with-phase [card {:key 1 :re-frame.hicasso/unmounting {:class "x"}}]
                                 :unmounting))))))

;; ---------------------------------------------------------------------------
;; The census-real screen, ported both ways
;; ---------------------------------------------------------------------------

(defn- toast-card-body
  "BEFORE — the predecessor's shape, minus its trap. The child view exists
  only so a per-child phase can be read; here the phase at least arrives
  as a prop rather than as an ambient read, so this rendering is already
  strictly safer than the one the guide teaches. The three
  `(when exiting? …)` attributes are the part HD-025 is about."
  [{:keys [message] phase :rf/phase}]
  (let [exiting? (= :unmounting phase)]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :inert       (when exiting? true)
                 :aria-hidden (when exiting? true)}
     message]))

(def ^:private toast-card (codec/mark-boundary! toast-card-body))

(defn- child-view-tray
  "BEFORE — a keyed child view per toast."
  [toasts]
  (mapv (fn [t] [toast-card {:key (:id t) :message (:message t)}]) toasts))

(defn- inline-tray
  "AFTER — no child view at all, and the three attributes are one map."
  [toasts]
  (mapv (fn [t]
          [:div.toast {:key (:id t)
                       :re-frame.hicasso/unmounting {:class       "toast--exit"
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
  (let [seeded (presence/settle (presence/step presence/initial (tray toasts) 0 timeout-ms))
        state  (if (= :unmounting phase)
                 (presence/step seeded (tray []) 100 timeout-ms)
                 seeded)
        hiccup (presence/render state)]
    ;; A boundary child renders through its body; a native child is already
    ;; the node. Both end at the codec, which is where the comparison is
    ;; taken, because that is the last point before React.
    (mapv (fn [child]
            (codec/as-element
              (if (codec/boundary-head? (nth child 0))
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
    (let [seeded (presence/settle
                   (presence/step presence/initial (inline-tray toasts) 0 timeout-ms))
          gone   (presence/step seeded (inline-tray [(first toasts)]) 100 timeout-ms)
          back   (presence/step gone (inline-tray toasts) 150 timeout-ms)]
      (is (= {1 :present 2 :unmounting} (presence/phases gone)))
      (let [exiting (attrs (codec/as-element (second (presence/render gone))))]
        (is (= "toast toast--exit" (get exiting "className")))
        (is (true? (get exiting "inert")))
        (is (true? (get exiting "aria-hidden"))))
      (is (= {1 :present 2 :present} (presence/phases back)))
      (let [restored (attrs (codec/as-element (second (presence/render back))))]
        (is (= "toast" (get restored "className")))
        (is (nil? (get restored "inert")))
        (is (nil? (get restored "aria-hidden"))))))
  (testing "witness 3, headlessly: the child is gone after :timeout-ms and
            the machine retains nothing"
    (let [seeded (presence/settle
                   (presence/step presence/initial (inline-tray toasts) 0 timeout-ms))
          gone   (presence/step seeded (inline-tray [(first toasts)]) 100 timeout-ms)
          done   (presence/expire gone 400)]
      (is (= {1 :present} (presence/phases done)))
      (is (= [1] (:order done)))
      (is (= 1 (count (:entries done))))
      (is (nil? (presence/next-deadline done))))))
