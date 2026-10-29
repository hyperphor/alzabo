;;; Status: Claude coded but never used 


(ns hyperphor.alzabo.import.linkml
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [hyperphor.alzabo.generate.schema-gen :as sg]))

;;; Convert LinkML schemas (.yaml, with local imports) to Alzabo schemas.
;;; Class/slot/enum names are kept verbatim (CamelCase classes, snake_case slots).
;;; Inheritance maps to :extends; parent slots are not copied down.

;;; LinkML builtin types → Alzabo primitives
(def builtin-types
  {"string" :string "str" :string
   "integer" :long "int" :long
   "boolean" :boolean "bool" :boolean
   "float" :float "double" :float "decimal" :float
   "date" :instant "datetime" :instant "time" :string
   "uri" :string "uriorcurie" :string "curie" :string "ncname" :string
   "objectidentifier" :string "nodeidentifier" :string
   "jsonpointer" :string "jsonpath" :string "sparqlpath" :string})

(defn- read-yaml
  [file]
  (yaml/parse-string (slurp file) :keywords false))

(defn- import-file
  "Resolve a LinkML import against the importing file's directory; nil for CURIE imports like linkml:types."
  [from-file imp]
  (when-not (re-find #"^[A-Za-z_]+:" imp)
    (.getCanonicalFile (io/file (.getParentFile (io/file from-file)) (str imp ".yaml")))))

(defn- load-files
  "Read `file` and its transitive local imports, depth-first, imports before importers. Returns [[file yaml] ...]."
  [file]
  (letfn [(visit [[visited acc] f]
            (if (visited f)
              [visited acc]
              (let [y (read-yaml f)
                    [visited acc] (reduce visit
                                          [(conj visited f) acc]
                                          (keep #(import-file f %) (get y "imports")))]
                [visited (conj acc [f y])])))]
    (second (visit [#{} []] (.getCanonicalFile (io/file file))))))

(defn- merged
  "Merge section `k` (\"classes\", \"slots\", ...) across all files; each entry is tagged with its package name."
  [files k]
  (into {}
        (for [[_ y] files
              [n d] (get y k)]
          [n (assoc (or d {}) ::package (get y "name"))])))

(defn- resolve-type
  [types t]
  (loop [t t seen #{}]
    (cond (builtin-types t) (builtin-types t)
          (or (seen t) (nil? (get types t))) :string
          :else (recur (get-in types [t "typeof"] "string") (conj seen t)))))

(defn- range->type
  [{:keys [classes enums types default-range]} range]
  (let [range (or range default-range "string")]
    (cond (contains? classes range) (keyword range)
          (contains? enums range) (keyword range)
          (or (builtin-types range) (contains? types range)) (resolve-type types range)
          :else (do (println "LinkML import: unknown range" range)
                    (keyword range)))))

(defn- slot->field
  [ctx slot-def]
  (let [{:strs [range any_of multivalued required identifier key description examples]} slot-def
        range (or range (get (first any_of) "range"))]
    (cond-> {:type (range->type ctx range)}
      multivalued (assoc :cardinality :many)
      required (assoc :required? true)
      (or identifier key) (assoc :unique? true)
      description (assoc :doc (s/trim description))
      (seq examples) (assoc :examples (vec (keep #(some-> (get % "value") str) examples))))))

(defn- class->kind
  [{:keys [slots] :as ctx} class-def]
  (let [{:strs [description is_a mixins attributes slot_usage]} class-def
        parents (vec (cons is_a mixins))
        parents (vec (keep identity parents))
        field (fn [n base] [(keyword n) (slot->field ctx (merge base (get slot_usage n)))])]
    (cond-> {:fields (into {}
                           (concat (for [n (get class-def "slots")]
                                     (field n (get slots n)))
                                   (for [[n d] attributes]
                                     (field n d))
                                   ;; slot_usage on an inherited slot becomes a refined local field
                                   (for [n (keys slot_usage)
                                         :when (not (or (some #{n} (get class-def "slots"))
                                                        (contains? attributes n)))]
                                     (field n (get slots n)))))
             :category (keyword (::package class-def))}
      description (assoc :doc (s/trim description))
      (= 1 (count parents)) (assoc :extends (keyword (first parents)))
      (< 1 (count parents)) (assoc :extends (mapv keyword parents)))))

(defn- enum->alzabo
  [{:strs [description permissible_values]}]
  (cond-> {:values (into {}
                         (for [[v d] permissible_values]
                           [(keyword (str v)) (or (some-> (get d "description") s/trim) "")]))}
    description (assoc :doc (s/trim description))))

(defn- categories
  "One category per package that contributes classes, labelled with the package title."
  [files classes]
  (let [used (set (map ::package (vals classes)))]
    (into (array-map)
          (map (fn [[_ y] color]
                 [(keyword (get y "name"))
                  {:label (or (get y "title") (get y "name"))
                   :doc (some-> (get y "description") s/trim)
                   :color color}])
               (filter #(used (get (second %) "name")) files)
               (cycle sg/category-palette)))))

(defn linkml->alzabo
  "Read a LinkML schema file (following local imports) and return an Alzabo schema."
  [file]
  (let [files (load-files file)
        root (second (last files))
        ctx {:classes (merged files "classes")
             :slots (merged files "slots")
             :enums (merged files "enums")
             :types (merged files "types")
             :default-range (get root "default_range")}
        cats (categories files (:classes ctx))]
    (cond-> {:title (or (get root "title") (get root "name"))
             :version (str (or (get root "version") "0"))
             :kinds (into {} (for [[n d] (:classes ctx)]
                               [(keyword n) (cond-> (class->kind ctx d)
                                              (< (count cats) 2) (dissoc :category))]))
             :enums (into {} (for [[n d] (:enums ctx)]
                               [(keyword n) (enum->alzabo d)]))}
      (> (count cats) 1) (assoc :categories cats))))
