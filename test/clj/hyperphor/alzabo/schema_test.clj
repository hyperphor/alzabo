(ns hyperphor.alzabo.schema-test
  (:require [hyperphor.alzabo.schema :as schema]
            [clojure.test :refer :all]))

;;; Regression test for inverse-fields iterating over the schema map itself
;;; instead of (:kinds schema), which made it return {} for every real schema.
(deftest test-inverse-fields
  (let [schema (schema/read-schema "test/resources/schema/rawsugar.edn")
        inv (schema/inverse-fields schema)]
    ;; :sheet is pointed at by :project's :sheet field
    (is (= :project (get-in inv [:sheet :sheet :type])))
    ;; :column is pointed at by :sheet's :columns field
    (is (= :sheet (get-in inv [:column :columns :type])))))
