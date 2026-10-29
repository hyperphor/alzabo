(ns hyperphor.alzabo.html-test
  (:require [clojure.test :refer :all]
            [hyperphor.alzabo.schema :as schema]
            [me.raynes.fs :as fs]
            [hyperphor.alzabo.html :refer :all]))

;;; Basic smoke test
(deftest test-html-gen
  (let [schema (schema/read-schema "test/resources/schema/rawsugar.edn")
        output-path (str (fs/temp-dir "alzabo") "/")]
    (schema->html schema output-path)
    (let [files (map fs/split-ext (fs/list-dir output-path))]
      (is (= #{["index" ".html"]
               ["operation" ".html"]
               ["row" ".html"]
               ["column" ".html"]
               ["file" ".html"]
               ["project" ".html"]
               ["sheet" ".html"]
               ["cell" ".html"]
               ["schema" ".edn"]
               ["schema" ".dot"]
               ["schema.dot" ".svg"]
               ["schema.dot" ".cmapx"]
               ["alzabo" ".css"]
               ["client" ".js"]
               }
             (set files)))
      ;; TODO file contents
      )))

(deftest test-tuples
  (let [schema (schema/read-schema "test/resources/schema/gxp.edn")
        output-path (str (fs/temp-dir "alzabo") "/")]
    (schema->html schema output-path)
    (let [experiment (slurp (str output-path "experiment.html"))]
      ;; Test that type link rendered properly
      (is (re-find (re-pattern "[<a href=\"gene.html\"> float]") experiment)))))


(deftest test-category-clusters
  (let [schema {:title "t" :version "1"
                :categories {:a {:color "#a9cce3" :label "Alpha"} :b {:color "moccasin"}}
                :render {:clusters? true}
                :kinds {:x {:category :a :fields {:y {:type :y}}}
                        :y {:category :b :fields {:name {:type :string}}}
                        :z {:fields {:x {:type :x}}}}}
        output-path (str (fs/temp-dir "alzabo") "/")]
    (schema->html schema output-path)
    (let [dot (slurp (str output-path "schema.dot"))]
      (is (re-find #"subgraph \"cluster_a\" \{\ngraph \[label=\"Alpha\"[^\]]*fillcolor=\"#a9cce340\"" dot))
      (is (re-find #"subgraph \"cluster_b\" \{\ngraph \[label=\"B\"[^\]]*fillcolor=\"white\"[^\n]*\n\"y\";" dot))
      ;; uncategorized kind stays out of every cluster
      (is (not (re-find #"\n\"z\";\n\}" dot))))
    (schema->html (assoc-in schema [:render :clusters?] false) output-path)
    (is (not (re-find #"subgraph" (slurp (str output-path "schema.dot")))))))

(deftest test-category-node-order
  (let [schema {:title "t" :version "1"
                :categories {:b {:color "#ffffff"} :a {:color "#000000"}}
                :kinds {:p {:category :a :fields {}}
                        :q {:category :b :fields {}}
                        :r {:fields {}}
                        :s {:category :b :fields {}}
                        :o {:category :a :fields {}}}}
        output-path (str (fs/temp-dir "alzabo") "/")]
    (schema->html schema output-path)
    (is (= ["r" "q" "s" "o" "p"]                ;uncategorized (:default) first, as in the index
           (map second (re-seq #"(?m)^\"(\w+)\" \[URL" (slurp (str output-path "schema.dot"))))))))
