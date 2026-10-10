(ns re-frame.migration.fresco.sketch-test
  "**The suggested declaration, round-tripped.**

  The report's `:defhost` sketch is the one thing in the artefact a migrator
  is invited to PASTE, and the golden corpus asserts the report's `:entries`
  and stops there. This namespace builds the real report artefact over every
  corpus case and round-trips every sketch it finds.

  `mint-host!` is `.cljs` and this JVM cannot call it, so the door is
  asserted through its rules rather than through its code: FILLING the
  scaffold — uncommenting every position and giving it a contract — must
  read as one form whose `:callbacks` map holds exactly this site's
  positions, each mapped to a contract in
  [[rf.migration.fresco.dest/callback-contracts]] (which `shared-rule-test`
  holds equal to the door's own roster) and none of them a structural slot."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.codemod :as rf.migration.fresco.codemod]
            [re-frame.migration.fresco.dest :as rf.migration.fresco.dest]
            [re-frame.migration.fresco.report :as rf.migration.fresco.report]))

(defn- corpus-inputs []
  (->> (.listFiles (io/file "test" "corpus"))
       (filter #(.isDirectory ^java.io.File %))
       (sort-by #(.getName ^java.io.File %))
       (keep (fn [dir]
               (first (filter #(str/starts-with? (.getName ^java.io.File %) "input.")
                              (.listFiles ^java.io.File dir)))))))

(defn- components
  "The suggestion components of the artefact the CLI writes, over the whole
  corpus at once — so this suite sees the sketches exactly as a migrator does."
  []
  (let [results (mapv #(rf.migration.fresco.codemod/scan-string (slurp %) (str "src/app/" (.getName ^java.io.File %)))
                      (corpus-inputs))]
    (get-in (rf.migration.fresco.report/build {:entries          (vec (mapcat :entries results))
                                               :suggestions      (vec (mapcat :suggestions results))
                                               :files-scanned    (count results)
                                               :files-changed    0
                                               :sites-total      (reduce + 0 (map :sites results))
                                               :sites-left-alone (reduce + 0 (map :left-alone results))})
            [:suggestions :components])))

(def ^:private contracts (set rf.migration.fresco.dest/callback-contracts))

(def ^:private commented-row
  "A scaffold row: an indented `;;` carrying exactly one token, which is a
  position. The roster sentence on the first line carries several words
  and never matches."
  #"(?m)^(\s*);; (\S+)$")

(defn- fill
  "What a migrator does to the sketch: uncomment every position row and
  give each one a contract."
  [sketch contract]
  (str/replace sketch commented-row (str "$1$2 " (pr-str contract))))

(deftest filling-the-scaffold-mints
  ;; `fill` writes the same contract into every row, so which one it writes
  ;; changes nothing the reads below can see. `clojure.edn`, not
  ;; `read-string`: a report is text from a consumer's tree and nothing here
  ;; should be able to run.
  (doseq [{:keys [head defhost event-slots fn-slots]} (components)
          :let [contract (first rf.migration.fresco.dest/callback-contracts)]]
    (testing (str "the sketch for " head " filled with " contract)
      (is (not (string? (edn/read-string head)))
          "a string head is a native tag, which W6 rewrites; there is no host to declare")
      (let [slots (mapv edn/read-string (distinct (concat event-slots fn-slots)))
            cbs   (:callbacks (nth (edn/read-string (fill defhost contract)) 3))]
        (is (= (set slots) (set (keys cbs)))
            (str "the filled sketch declares " (pr-str (keys cbs)) " where the site uses " (pr-str slots)))
        (is (seq cbs) "a filled scaffold that declared nothing would make every assertion here vacuous")
        (is (every? contracts (vals cbs)) "every filled position carries a contract the door accepts")
        (is (not-any? #(contains? #{"key" "ref"} (rf.migration.fresco.dest/canonical-slot %)) (keys cbs))
            "`key` and `ref` are structural slots the door refuses at mint in every spelling")))))

(defn- sketch-for [head]
  (->> (components) (filter #(= head (:head %))) first :defhost))

(deftest the-sketch-reads-the-way-it-is-meant-to
  (testing "an ordinary symbol head"
    (is (= (str "(h/defhost bar js/Foo.Bar\n"
                "  {:callbacks {;; each position takes :event or :render — see :caution\n"
                "               ;; :on-change\n"
                "               ;; :on-render-row\n"
                "               }})")
           (sketch-for "js/Foo.Bar"))))

  (testing "an expression head takes the placeholder name, and the
            component position is the head verbatim"
    (is (= (str "(h/defhost your-host (.-Provider ctx)\n"
                "  {:callbacks {;; each position takes :event or :render — see :caution\n"
                "               ;; :on-close\n"
                "               }})")
           (sketch-for "(.-Provider ctx)")))))
