(ns org.candelbio.alzabo.datagen
  "Generate example data for Alzabo schemas"
  (:require [org.candelbio.alzabo.schema :as schema]
            [org.candelbio.multitool.core :as u]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [org.candelbio.alzabo.llm :as llm])
  (:import [java.time LocalDate LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

;;; Core data generation framework

(def ^:dynamic *generation-context*
  "Context for data generation including generated entities and configuration"
  {:entities {}           ; Map of kind -> [generated entities]
   :entity-counts {}      ; Map of kind -> target count
   :relationships {}      ; Map of [from-kind to-kind] -> [(from-id to-id)]
   :llm-enabled? true
   :seed 42})

(defn reset-context!
  "Reset the generation context"
  [config]
  (alter-var-root #'*generation-context*
                  (constantly (merge {:entities {}
                                      :entity-counts {}
                                      :relationships {}
                                      :llm-enabled? true
                                      :seed 42}
                                     config))))

(defn add-entity!
  "Add a generated entity to the context"
  [kind entity]
  (alter-var-root #'*generation-context*
                  update-in [:entities kind] (fnil conj []) entity))

(defn get-entities
  "Get all entities of a given kind"
  [kind]
  (get-in *generation-context* [:entities kind] []))

;;; TODO multitool
(defn random-element
  "Get a random element from a collection"
  [coll]
  (when (seq coll)
    (nth coll (rand-int (count coll)))))

;;; Schema introspection utilities

(defn get-schema-kinds
  "Get all kind names from a schema"
  [schema]
  (keys (:kinds schema)))

(defn get-schema-enums
  "Get all enum names from a schema"
  [schema]
  (keys (:enums schema)))

(defn is-reference-type?
  "Check if a type is a reference to another kind"
  [type-name schema]
  (contains? (set (get-schema-kinds schema)) type-name))

(defn is-enum-type?
  "Check if a type is an enum"
  [type-name schema]
  (contains? (set (get-schema-enums schema)) type-name))

;;; Date generation utilities

(defn random-date-between
  "Generate a random date between start and end years"
  [start-year end-year]
  (let [start-day (.toEpochDay (LocalDate/of start-year 1 1))
        end-day (.toEpochDay (LocalDate/of end-year 12 31))
        random-day (+ start-day (rand-int (- end-day start-day)))]
    (LocalDate/ofEpochDay random-day)))

(defn format-date
  "Format a LocalDate as ISO string"
  [date]
  (.format date DateTimeFormatter/ISO_LOCAL_DATE))

;;; Generic data generators

(def common-locations
  ["New York" "Chicago" "Los Angeles" "San Francisco" "Boston" "Seattle"
   "Atlanta" "Denver" "Miami" "Nashville" "Austin" "Portland"])

(def generic-company-suffixes
  ["Inc." "LLC" "Corp." "Company" "Group" "Enterprises" "Solutions" "Systems"])

#_
(defn generate-realistic-name
  "Generate a realistic name using LLM or fallback to generic name"
  [context]
  (or (call-llm (str "Generate a realistic " context " name. Return only the name, nothing else."))
      (str "Sample " (str/capitalize context))))

;;; TODO count, fictional? as parameters
;;; TODO should incorporate schema desc in prompt (maybe as system prompt?)
;;; TODO context object
(defn generate-entities
  [kind schema]
  (let [sdef (schema/kind-def schema kind)]
    (llm/json-query (u/expand-template
                     "Please give me a list of {{count}} *fictional* {{id}} {{description}} in json format. Fore each, include the following fields: {{fieldlist}}. "
                     (-> sdef
                         (assoc :fieldlist (str/join ", " (keys (:fields sdef))))
                         (assoc :count 10))))))
                             
(comment
  (generate-entities :Genre schema))


;;; Field type generators

(defmulti generate-field-value2
  "Generate a value for a specific field type"
  (fn [field-def kind field-name schema]
    (:type field-def)))


;; Default primitive type handlers
(defmethod generate-field-value2 :string
  [field-def kind field-name schema]
  (cond
    (str/includes? (str field-name) "name") (generate-realistic-name (str (name kind) " " (name field-name)))
    (str/includes? (str field-name) "title") (generate-realistic-name (str (name kind) " " (name field-name)))
    (str/includes? (str field-name) "location") (random-element common-locations)
    (str/includes? (str field-name) "website") (str "https://www." (str/lower-case (str (gensym "site"))) ".com")
    (str/includes? (str field-name) "email") (str (str/lower-case (str (gensym "user"))) "@example.com")
    (str/includes? (str field-name) "address") (str (+ 100 (rand-int 9900)) " " (random-element ["Main St." "Oak Ave." "Pine Rd." "First St." "Second Ave."]))
    (str/includes? (str field-name) "description") (str "This is a sample description for " (name field-name))
    :else (str "Sample " (name field-name))))

(defmethod generate-field-value2 :boolean
  [field-def kind field-name schema]
  (rand-nth [true false]))

(defmethod generate-field-value2 :long
  [field-def kind field-name schema]
  (cond
    (str/includes? (str field-name) "year") (+ 1950 (rand-int 75))
    (str/includes? (str field-name) "duration") (+ 60 (rand-int 600)) ; 1-10 minutes in seconds
    (str/includes? (str field-name) "capacity") (+ 50 (rand-int 5000))
    (str/includes? (str field-name) "count") (+ 1 (rand-int 100))
    (str/includes? (str field-name) "size") (+ 1 (rand-int 1000))
    (str/includes? (str field-name) "id") (rand-int 999999)
    :else (rand-int 1000)))

(defmethod generate-field-value2 :bigint
  [field-def kind field-name schema]
  (bigint (rand-int 1000000)))

(defmethod generate-field-value2 :float
  [field-def kind field-name schema]
  (* (rand) 1000.0))

(defmethod generate-field-value2 :double
  [field-def kind field-name schema]
  (* (rand) 1000.0))

(defmethod generate-field-value2 :bigdec
  [field-def kind field-name schema]
  (bigdec (* (rand) 1000.0)))

(defmethod generate-field-value2 :instant
  [field-def kind field-name schema]
  (format-date
   (cond
     (str/includes? (str field-name) "birth") (random-date-between 1950 2005)
     (str/includes? (str field-name) "start") (random-date-between 2000 2020)
     (str/includes? (str field-name) "end") (random-date-between 2010 2025)
     (str/includes? (str field-name) "created") (random-date-between 2010 2025)
     (str/includes? (str field-name) "updated") (random-date-between 2020 2025)
     :else (random-date-between 2000 2025))))

(defmethod generate-field-value2 :uuid
  [field-def kind field-name schema]
  (.toString (UUID/randomUUID)))

(defmethod generate-field-value2 :keyword
  [field-def kind field-name schema]
  (keyword (str "sample-" (name field-name))))

;; Generic reference handler
(defn generate-reference
  "Generate a reference to another entity"
  [target-kind required?]
  (let [entities (get-entities target-kind)]
    (cond
      (and required? (empty? entities))
      (throw (ex-info (str "No entities of kind " target-kind " available for required reference")
                      {:kind target-kind}))
      (empty? entities) nil
      :else (:id (random-element entities)))))

;; Generic enum handler - dispatches on the actual enum type from schema
(defn generate-enum-value
  "Generate a value for any enum type by looking up values in schema"
  [enum-type schema]
  (let [enum-values (get-in schema [:enums enum-type :values])]
    (when enum-values
      (random-element (keys enum-values)))))

;; Generic reference type handler - works for any reference type in schema
(defn generate-reference-value
  "Generate a reference value for any reference type"
  [field-def kind field-name schema]
  (let [field-type (:type field-def)]
    (cond
      ;; Check if it's a primitive type
      (contains? #{:string :boolean :long :bigint :float :double :bigdec :instant :keyword :uuid} field-type)
      (generate-field-value2 field-def kind field-name schema)

      ;; Check if it's an enum
      (is-enum-type? field-type schema)
      (generate-enum-value field-type schema)

      ;; Check if it's a reference to another kind
      (is-reference-type? field-type schema)
      (generate-reference field-type (:required? field-def))

      ;; Handle tuples (sequences of types)
      (and (vector? field-type) (= :tuple (first field-type)))
      (let [tuple-types (second field-type)]
        (mapv #(generate-reference-value {:type %} kind field-name schema) tuple-types))

      ;; Default case - try to generate as string
      :else
      (str "Unknown-" (name field-type)))))

;; Fallback to generic generation based on type alone
(defmethod generate-field-value2 :default
  [field-def kind field-name schema]
  (generate-reference-value field-def kind field-name schema))

;;; Main entity generation

(defn generate-entity
  "Generate a single entity of the given kind"
  [kind kind-def schema]
  (let [fields (:fields kind-def)]
    (reduce
     (fn [entity [field-name field-def]]
       (let [value (if (= :many (:cardinality field-def))
                     ;; For :many cardinality, generate 0-5 values
                     (repeatedly (rand-int 6) #(generate-field-value2 field-def kind field-name schema))
                     ;; For :one cardinality, generate single value
                     (generate-field-value2 field-def kind field-name schema))]
         (if (or (nil? value) (and (coll? value) (empty? value)))
           entity  ; Skip nil/empty values unless required
           (assoc entity field-name value))))
     {}
     fields)))

(defn generate-entities
  "Generate multiple entities of a given kind"
  [kind kind-def schema count]
  (repeatedly count #(generate-entity kind kind-def schema)))

;;; High-level generation orchestration

(defn determine-generation-order
  "Determine the order to generate entities based on dependencies"
  [schema]
  ;; For now, use a simple heuristic: reference entities first, then others
  (let [kinds (keys (:kinds schema))
        reference-kinds (filter #(get-in schema [:kinds % :reference?]) kinds)
        non-reference-kinds (filter #(not (get-in schema [:kinds % :reference?])) kinds)]
    (concat reference-kinds non-reference-kinds)))

(defn generate-sample-data
  "Generate sample data for an entire schema"
  [schema & {:keys [entity-counts llm-enabled? seed]
             :or {entity-counts {}
                  llm-enabled? true
                  seed 42}}]
  (reset-context! {:llm-enabled? llm-enabled? :seed seed})

  ;; Set default entity counts
  (let [default-counts (into {} (map (fn [kind] [kind 5]) (keys (:kinds schema))))
        final-counts (merge default-counts entity-counts)]

    ;; Generate entities in dependency order
    (doseq [kind (determine-generation-order schema)]
      (let [kind-def (get-in schema [:kinds kind])
            count (get final-counts kind 0)]
        (when (> count 0)
          (println (str "Generating " count " " (name kind) " entities..."))
          (doseq [entity (generate-entities kind kind-def schema count)]
            (add-entity! kind entity)))))

    ;; Return the generated data
    (:entities *generation-context*)))
