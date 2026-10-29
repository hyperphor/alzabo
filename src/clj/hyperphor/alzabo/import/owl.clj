(ns hyperphor.alzabo.import.owl
  (:require [clojure.string :as s]
            [camel-snake-kebab.core :as csk]
            [hyperphor.alzabo.generate.schema-gen :as sg]))

;;; Convert OWL 2 ontologies in functional syntax (.ofn) to Alzabo schemas.
;;; Classes → kinds (named superclass → :extends); object properties → fields on their domain.
;;; Union/intersection domains and ranges are approximated by the nearest common named
;;; superclass, with the exact expression noted in the field :doc.
;;; Only one direction of each inverse pair becomes a field, since Alzabo derives inverses.

;;; Parsing

(def ^:private token-re
  #"\s+|#[^\n]*|\(|\)|<[^>]*>|\"(?:[^\"\\]|\\.)*\"(?:@[A-Za-z-]+|\^\^[^\s()]+)?|[^\s()<>\"]+")

(defn- tokens
  [text]
  (remove #(or (s/blank? %) (s/starts-with? % "#")) (re-seq token-re text)))

(defn- parse-forms
  "Nested [:Functor & args] vectors from OFN tokens; atoms stay raw token strings."
  [toks]
  (loop [[t & more :as toks] toks
         stack [[]]]
    (cond (empty? toks) (peek stack)
          (= t "(") (let [top (peek stack)]
                      (recur more (conj (pop stack) (pop top) [(keyword (peek top))])))
          (= t ")") (let [form (peek stack)
                          stack (pop stack)]
                      (recur more (conj (pop stack) (conj (peek stack) form))))
          :else (recur more (conj (pop stack) (conj (peek stack) t))))))

(defn- unwrap-iri
  [tok]
  (subs tok 1 (dec (count tok))))

(defn- clean-literal
  "Unescape, and drop the angle brackets around <http://...> so they don't read as HTML."
  [v]
  (-> v
      (s/replace #"\\(.)" "$1")
      (s/replace #"<(https?://[^>\s]+)>" "$1")))

(defn- atom-value
  "IRIs and prefixed names → full IRI strings; literals → {:literal string}."
  [prefixes tok]
  (cond (s/starts-with? tok "<") (unwrap-iri tok)
        (s/starts-with? tok "\"") {:literal (clean-literal (second (re-matches #"(?s)\"(.*)\"(?:@.*|\^\^.*)?" tok)))}
        :else (if-let [[_ p l] (re-matches #"([^:]*):(.*)" tok)]
                (if-let [ns (get prefixes p)] (str ns l) tok)
                tok)))

(defn- strip-annotations
  [form]
  (if (vector? form)
    (into [(first form)]
          (comp (remove #(and (vector? %) (= :Annotation (first %))))
                (map strip-annotations))
          (rest form))
    form))

(defn read-ofn
  "Parse an OFN file into {:iri :version-iri :annotations [[prop value]...] :axioms [form...]}."
  [file]
  (let [forms (parse-forms (tokens (slurp file)))
        prefixes (into {} (for [[f p iri] forms :when (= f :Prefix)]
                            [(s/replace p #":=$" "") (unwrap-iri iri)]))
        expand (fn expand [x]
                 (cond (vector? x) (into [(first x)] (map expand) (rest x))
                       :else (atom-value prefixes x)))
        [_ & body] (expand (first (filter #(= :Ontology (first %)) forms)))
        [iris body] (split-with string? body)]
    {:iri (first iris)
     :version-iri (second iris)
     :annotations (for [[f p v] body :when (= f :Annotation)] [p v])
     :axioms (map strip-annotations (remove #(= :Annotation (first %)) body))}))

;;; Ontology model

(def ^:private rdfs "http://www.w3.org/2000/01/rdf-schema#")
(def ^:private skos "http://www.w3.org/2004/02/skos/core#")
(def ^:private dc "http://purl.org/dc/terms/")
(def ^:private owl "http://www.w3.org/2002/07/owl#")

(defn- literal
  [v]
  (if (map? v) (:literal v) v))

(defn- model
  "Index axioms by subject: annotations, class parents, restrictions, property characteristics."
  [axioms]
  (let [by (group-by first axioms)
        ax #(map rest (get by %))
        declared (fn [t] (for [[[dt iri]] (ax :Declaration) :when (= dt t)] iri))]
    {:classes (sort (declared :Class))
     :properties (sort (declared :ObjectProperty))
     :annotations (reduce (fn [m [p subj v]] (update-in m [subj p] (fnil conj []) (literal v)))
                          {} (ax :AnnotationAssertion))
     :parents (reduce (fn [m [sub sup]] (update m sub (fnil conj []) sup))
                      {} (filter #(every? string? %) (ax :SubClassOf)))
     :restrictions (reduce (fn [m [sub sup]] (update m sub (fnil conj []) sup))
                           {} (filter #(and (string? (first %)) (vector? (second %))) (ax :SubClassOf)))
     :disjoints (map set (ax :DisjointClasses))
     :domain (into {} (map vec (ax :ObjectPropertyDomain)))
     :range (into {} (map vec (ax :ObjectPropertyRange)))
     :inverses (map vec (ax :InverseObjectProperties))
     :super-properties (reduce (fn [m [sub sup]] (update m sub (fnil conj []) sup))
                               {} (ax :SubObjectPropertyOf))
     :functional (set (map first (ax :FunctionalObjectProperty)))
     :transitive (set (map first (ax :TransitiveObjectProperty)))}))

(defn- annotation
  [m iri prop]
  (get-in m [:annotations iri prop]))

(defn- label
  [m iri]
  (or (first (annotation m iri (str rdfs "label")))
      (last (s/split iri #"[#/]"))))

(defn- kind-key
  [m iri]
  (keyword (csk/->PascalCase (label m iri))))

(defn- field-key
  [m iri]
  (keyword (csk/->camelCase (label m iri))))

(defn- chain
  "`iri` followed by its ancestors, following the first named superclass."
  [m iri]
  (take-while some? (iterate #(first (get-in m [:parents %])) iri)))

(defn- common-ancestor
  [m iris]
  (let [others (map #(set (chain m %)) (rest iris))]
    (first (filter (fn [a] (every? #(% a) others)) (chain m (first iris))))))

(defn- named-approximation
  "Nearest named class that subsumes class expression `ce`, or nil."
  [m ce]
  (if (string? ce)
    ce
    (let [[f & args] ce
          approx (map #(named-approximation m %) args)]
      (case f
        :ObjectUnionOf (when (every? some? approx) (common-ancestor m approx))
        ;; most specific positive conjunct
        :ObjectIntersectionOf (let [pos (filter some? approx)]
                                (or (first (filter (fn [a] (every? #(some #{%} (chain m a)) pos)) pos))
                                    (first pos)))
        nil))))

(defn- ce-text
  "Readable rendering of class expression `ce`, using labels."
  [m ce]
  (if (string? ce)
    (label m ce)
    (let [[f & args] ce
          join #(str "(" (s/join % (map (partial ce-text m) args)) ")")]
      (case f
        :ObjectUnionOf (join " or ")
        :ObjectIntersectionOf (join " and ")
        :ObjectComplementOf (str "not " (ce-text m (first args)))
        :ObjectAllValuesFrom (str (label m (first args)) " only " (ce-text m (second args)))
        :ObjectSomeValuesFrom (str (label m (first args)) " some " (ce-text m (second args)))
        (pr-str ce)))))

(defn- sentences
  "Join non-blank doc fragments, ending each with a period, then append `iri` (unpunctuated, so it linkifies)."
  [iri & parts]
  (->> parts
       (remove s/blank?)
       (map #(if (re-find #"[.?!]$" %) % (str % ".")))
       (s/join " ")
       (#(str % " " iri))))

(defn- labelled
  [prefix vals]
  (when (seq vals) (str prefix (s/join "; " vals))))

;;; Inverse pairs: keep the direction that reads as a forward reference (functional, not "has x"/"x by"/"x of").
(defn- direction-score
  [m p]
  (let [l (label m p)]
    (cond-> 0
      ((:functional m) p) (+ 3)
      (re-find #"^(has|is) " l) (- 2)
      (re-find #" by$" l) (- 2)
      (re-find #" of$" l) (- 1))))

(defn- dropped-inverses
  "Map from each dropped property to the inverse kept in its place."
  [m]
  (into {} (for [[a b] (:inverses m)
                 :let [[keep drop] (if (>= (direction-score m a) (direction-score m b)) [a b] [b a])]]
             [drop keep])))

(defn- root
  [m]
  (let [roots (remove #(get-in m [:parents %]) (:classes m))]
    (when (= 1 (count roots)) (first roots))))

(defn- property->field
  [m inverse-of p]
  (let [dom (get-in m [:domain p])
        rng (get-in m [:range p])
        rng-named (or (named-approximation m rng) (root m))
        a #(annotation m p (str skos %))]
    [(kind-key m (or (named-approximation m dom) (root m)))
     (field-key m p)
     (cond-> {:type (kind-key m rng-named)
              :cardinality (if ((:functional m) p) :one :many)
              :doc (sentences p
                              (first (a "definition"))
                              (labelled "Also known as: " (a "altLabel"))
                              (when (and dom (not (string? dom))) (str "Domain: " (ce-text m dom)))
                              (when (and rng (not (string? rng))) (str "Range: " (ce-text m rng)))
                              (some->> (get inverse-of p) (label m) (str "Inverse: "))
                              (labelled "Sub-property of: " (map #(label m %) (get-in m [:super-properties p])))
                              (when ((:transitive m) p) "Transitive")
                              (labelled "Note: " (a "scopeNote")))}
       (seq (a "example")) (assoc :examples (a "example")))]))

(defn- class->kind
  [m iri fields]
  (let [a #(annotation m iri (str skos %))
        parent (first (get-in m [:parents iri]))
        disjoint (->> (:disjoints m)
                      (filter #(% iri))
                      (mapcat #(disj % iri))
                      (map #(label m %))
                      sort)]
    (cond-> {:uri iri
             :fields (or fields {})
             :doc (sentences iri
                             (first (a "definition"))
                             (labelled "Also known as: " (a "altLabel"))
                             (labelled "Examples: " (a "example"))
                             (labelled "Note: " (a "scopeNote"))
                             (labelled "Disjoint with: " disjoint)
                             (labelled "Restrictions: " (map #(ce-text m %) (get-in m [:restrictions iri]))))}
      parent (assoc :extends (kind-key m parent)))))

;;; Categories: the ancestor `depth` levels below the root; shallower classes go in :default
;;; (reusing the renderer's default key keeps the category map small enough to stay ordered).
(defn- category-of
  [m depth iri]
  (let [c (reverse (chain m iri))]
    (if (> (count c) depth)
      (kind-key m (nth c depth))
      :default)))

(defn- categories
  [m depth]
  (let [cats (->> (:classes m)
                  (sort-by #(s/join "/" (map (partial label m) (reverse (chain m %))))) ;tree order
                  (map #(category-of m depth %))
                  distinct)
        iri-of (into {} (for [c (:classes m)] [(kind-key m c) c]))]
    (into (array-map)
          (map (fn [cat color]
                 [cat {:label (if (= cat :default) "Upper level" (s/capitalize (label m (iri-of cat))))
                       :color color}])
               cats
               (cycle sg/category-palette)))))

(defn owl->alzabo
  "Read an OWL functional-syntax file and return an Alzabo schema.
  Options: :category-depth (default 2) picks which tree level defines categories."
  [file & {:keys [category-depth] :or {category-depth 2}}]
  (let [{:keys [iri version-iri annotations axioms]} (read-ofn file)
        m (model axioms)
        header (reduce (fn [h [p v]] (update h p (fnil conj []) (literal v))) {} annotations)
        inverse-of (dropped-inverses m)
        kept-inverse (into {} (map (fn [[d k]] [k d]) inverse-of))
        fields (reduce (fn [acc [kind field fdef]] (assoc-in acc [kind field] fdef))
                       {}
                       (for [p (:properties m) :when (not (inverse-of p))]
                         (property->field m kept-inverse p)))
        cats (categories m category-depth)]
    {:title (or (first (header (str dc "title"))) (first (header (str rdfs "label"))) iri)
     :version (or (first (header (str owl "versionInfo")))
                  (some->> version-iri (re-find #"/(\d[^/]*)/") second)
                  "1")
     :explanation (vec (concat (for [d (concat (header (str dc "description"))
                                               (header (str rdfs "comment")))]
                                 [:p d])
                               [[:p "Imported from " [:a {:href (or version-iri iri)} (or version-iri iri)]
                                 (when-let [lic (first (header (str dc "license")))]
                                   (list " (license: " [:a {:href lic} lic] ")"))
                                 ". Dashed arrows are subclass links; other arrows are object properties, placed on their domain."]]))
     :kinds (into {} (for [c (:classes m)
                           :let [k (kind-key m c)]]
                       [k (assoc (class->kind m c (get fields k))
                                 :category (category-of m category-depth c))]))
     :categories cats
     :render {:edge-labels? true :taxonomy? true}}))
