(ns re-frame.migration.fresco.sketch-test
  "Round-trip the declarations a migrator is invited to paste."
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

(def ^:private commented-row
  #"(?m)^(\s*);; (\S+)$")

(defn- fill
  [sketch contract]
  (str/replace sketch commented-row (str "$1$2 " (pr-str contract))))

(deftest filling-the-scaffold-mints
  (doseq [{:keys [head defhost event-slots fn-slots]} (components)
          :let [contract (first rf.migration.fresco.dest/callback-contracts)]]
    (testing (str "the sketch for " head " filled with " contract)
      (is (not (string? (edn/read-string head)))
          "a string head is a native tag, which W6 rewrites; there is no host to declare")
      (let [slots (mapv edn/read-string (distinct (concat event-slots fn-slots)))
            cbs   (:callbacks (nth (edn/read-string (fill defhost contract)) 3))]
        (is (= (zipmap slots (repeat contract)) cbs)
            (str "the filled sketch declares " (pr-str (keys cbs)) " where the site uses " (pr-str slots)))
        (is (seq cbs) "a filled scaffold that declared nothing would make every assertion here vacuous")
        (is (not-any? #(contains? #{"key" "ref"} (rf.migration.fresco.dest/canonical-slot %)) (keys cbs))
            "`key` and `ref` are structural slots the door refuses at mint in every spelling")))))

(defn- sketch-for [head]
  (->> (components) (filter #(= head (:head %))) first :defhost))

(deftest the-sketch-reads-the-way-it-is-meant-to
  (testing "ordinary symbol head"
    (is (= (str "(h/defhost bar js/Foo.Bar\n"
                "  {:callbacks {;; each position takes :event or :render — see :caution\n"
                "               ;; :on-change\n"
                "               ;; :on-render-row\n"
                "               }})")
           (sketch-for "js/Foo.Bar"))))

  (testing "expression head"
    (is (= (str "(h/defhost your-host (.-Provider ctx)\n"
                "  {:callbacks {;; each position takes :event or :render — see :caution\n"
                "               ;; :on-close\n"
                "               }})")
           (sketch-for "(.-Provider ctx)")))))
