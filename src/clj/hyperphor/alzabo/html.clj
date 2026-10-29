(ns hyperphor.alzabo.html
  (:require [hyperphor.alzabo.schema :as schema]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.graph :as graph]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [me.raynes.fs :as fs]
            [inflections.core :as inflect]
            [clojure.java.io :as io])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute])
  (:use [hiccup.core]))

;;; Rendering options that used to live in config; now read from the schema
;;; itself (with these as fallback defaults) since they're presentation
;;; details of a particular schema, not deployment config.
(def default-graph-options
  {:categories {:default {:color "#a9cce3"}}
   :render {:edge-labels? :minimal
            ;; :width / :height: max graph size in pixels (scales down only)
            :orientation :vertical
            ;; Edge labels as xlabels, placed after layout; regular labels become layout nodes and spread the graph sideways
            :xlabels? true
            ;; :graph-attrs, :node-attrs, :edge-attrs are merged over hyperphor.multitool.graph/default-attrs
            :graph-attrs {:nodesep 0.15 :ranksep 0.6}
            ;; Inheritance edges alone determine layout (parents above children); for ontologies
            :taxonomy? false
            ;; Group each category's kinds into a labeled Graphviz cluster (needs 2+ categories in use)
            :clusters? false
            }})

(defn- with-graph-defaults
  [schema]
  (u/merge* default-graph-options schema))

;;; Bound to the schema for the duration of schema->html, so deeply-nested
;;; renderers (field type links, index tables, etc) can look up a kind's
;;; :icon without threading schema through every function.
(def ^:dynamic *schema* nil)

(defn- kind-icon
  [kind]
  (when (keyword? kind)
    (get-in *schema* [:kinds kind :icon])))

(defn- kind-url
  [kind]
  (str (name kind) ".html"))

(defn- kind-link
  [kind]
  (html [:a.kind {:href (kind-url kind)}
         (when-let [icon (kind-icon kind)] (str icon " "))
         kind]))

;;; TODO links in docs
(defn- kind-html
  [kind]
  (if (keyword? kind)
    (if (get schema/primitives kind)
      (name kind)
      (kind-link kind))
    ;; tuple
    [:span "["
     (interpose
      " "
      (for [k kind]
        (kind-html k)))
     "]"]))

(defn field-spec-html
  [{:keys [type min max] :as fieldprops}]
  (cond (schema/numeric-primitives type)
        (str (name type)
             (when min
               (str [min max])))
        :else
        (kind-html type)))
        

(def primitives #{:long :float :string :boolean :instant :keyword}) ; :ref

(defn- linkify
  [s]
  (when s 
    (s/replace s #"(https?\:\S*[^\s.,;:)])" "<a href=\"$1\">$1</a>")))

;;; can't believe this isn't built into hiccup
(defn- style-arg
  [m]
  (s/join (map (fn [[p v]] (format "%s: %s;" (name p) v)) m)))

(defn boold
  [boolean]
  (if boolean "yes" ""))

;;; TODO someday these might want to be customizable in config
(def kind-metadata-columns
  [{:display (fn [fieldprops] [:span {:style (style-arg {:white-space "nowrap"})} (:id fieldprops)])
    :heading "attribute"}
   {:display field-spec-html
    :heading "type"}
   {:display :cardinality
    :heading "cardinality"}
   {:display (comp boold :unique?)
    :heading "unique?"}
   {:display (comp boold :required?)
    :heading "required?"}
   {:display (fn [fieldprops] (linkify (:doc fieldprops)))
    :heading "doc"}
   {:display (fn [fieldprops]
               (when-let [examples (seq (:examples fieldprops))]
                 [:span {:class "examples"} (s/join ", " examples)]))
    :heading "examples"}])

(def kind-inverse-columns
  [{:display field-spec-html
    :heading "from"}
   {:display (fn [fieldprops] [:span {:style (style-arg {:white-space "nowrap"})} (:id fieldprops)])
    :heading "attribute"}
   {:display :cardinality
    :heading "cardinality"}
   {:display (comp boold :required?)
    :heading "required?"}
   {:display (fn [fieldprops] (linkify (:doc fieldprops)))
    :heading "doc"}])

(defn- field->html
  [field props columns]
  (html
   [:tr
    (for [col columns]
      [:td ((:display col) (assoc props :id field))])]))

(defn- table-headings
  [columns]
  [:tr (for [col columns]
         [:th (:heading col)])])

(defn- backlink
  []
  [:a {:href "index.html"} "← schema"])

;;; Schema is actually just the kinds structure
(defn- kind->html
  [kind raw-schema]
  (let [kind-def (get-in raw-schema [:kinds kind])
        {:keys [unique-id label fields doc extends icon]} kind-def
        parents (schema/get-parents raw-schema kind)
        inherited (schema/inherited-fields raw-schema kind)
        all-fields (schema/all-fields raw-schema kind)]
    (html
     [:div.container
      (backlink)
      [:h1
       (when icon
         [:span {:style (style-arg {:font-size "1.5em" :margin-right "0.2em"})} icon])
       (name kind)]
      (when doc
        [:div {:class "kind_doc"} (linkify doc)])

      ;; Show inheritance
      (when (seq parents)
        [:div {:style "margin: 1em 0; padding: 0.5em; background-color: #f5f5f5; border-left: 3px solid #4CAF50;"}
         [:b "Extends: "]
         (interpose ", " (map kind-link parents))])

      [:h3 "Fields"]
      [:table {:class "table"}
       (table-headings kind-metadata-columns)
       (for [[field props] (into (sorted-map) all-fields)]
         (let [is-inherited (contains? inherited field)
               props-with-note (if is-inherited
                                 (update props :doc #(str (or % "") " [inherited]"))
                                 props)]
           (field->html field props-with-note kind-metadata-columns)))]

      [:h3 "Inverse Relations"]
      [:table {:class "table"}
       (table-headings kind-inverse-columns)
       (for [[field props] (into (sorted-map) (get (schema/inverse-fields raw-schema) kind))]
         (field->html field props kind-inverse-columns))]

      (when unique-id
        [:div
         [:b "Unique ID"] ": " (name unique-id)])
      (when label
        [:div
         [:b "Label"] ": " (name label)])]
     )))

(defn- enum->html
  [enum {:keys [values doc]}]
  (html
   [:div.container
    (backlink)
    [:h1 (name enum)]
    [:span doc]
    [:table {:class "table"}
     [:tr [:th "values"] [:th "doc"]]
     (for [[v doc] values]
       (html [:tr [:td v] [:td doc]]))]]))

;;; This is extremely dangerous and I lost work because of it, so no, don't do this
#_
(defn- clear-directory
  [d]
  (fs/delete-dir d LinkOption/NOFOLLOW_LINKS)
  (fs/mkdirs d))

(defn output-file
  [output-path file]
  (str (fs/file output-path file)))

(def alzabo-link "https://github.com/hyperphor/alzabo")

(defn html-out
  [output-path file title the-html]
  (prn :writing (output-file output-path file))
  (spit (output-file output-path file)
        (html
            ;; should be a template I suppose but this was faster
            [:html
             [:head
              [:title title]
              [:meta {:charset "UTF-8"}]   ;TODO was UTF-16, which broke client.js...why was it that way?
              [:link {:href "https://fonts.googleapis.com/css?family=Lato:400,700"
                      :rel "stylesheet"}]


              [:link {:href "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"
                      :rel "stylesheet"
                      :integrity "sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH"
                      :crossorigin "anonymous"}]
              [:link {:rel "stylesheet"
                      :href "alzabo.css"}]]
             [:body
              [:div
               the-html
               [:div.footer "Generated by " [:a {:href alzabo-link} "Alzabo"]]]]])))


;; yes this should be in css.
(defn- header-style
  [color]
  (style-arg
   {:background color
    :padding-left "6px"
    }))

(defn enum-samples
  [enum]
  (str (s/join ", " (map second (take 3 (:values enum))))
       (when (> (count (:values enum)) 3) "...")))

(defn page-html
  [title body search?]
  (html
      [:div.container
       [:div.header
        [:h1 title]
        (when search? [:div#app])
        [:span "Generated by " [:a {:href alzabo-link} "Alzabo"]]]
       body]))
  

(defn- category-legend
  "Color key for the graph: one swatch per category in `used`."
  [categories used]
  [:div.legend {:style (style-arg {:display "flex" :flex-wrap "wrap" :gap "12px" :margin "8px 0"})}
   (for [cat used
         :let [{:keys [color label doc]} (get categories cat)]]
     [:span {:title (or doc "") :style (style-arg {:display "inline-flex" :align-items "center" :gap "5px"})}
      [:span {:style (style-arg {:display "inline-block" :width "14px" :height "14px"
                                 :border "1px solid #888" :border-radius "3px" :background color})}]
      (or label (inflect/titleize (name cat)))])])

(defn- index->html
  [{:keys [kinds enums version title explanation] :as schema} tag-version output-path]
  (let [categories (:categories (with-graph-defaults schema))
        groups (group-by #(get % :category :default) (vals (u/self-label :id kinds)))
        ;; Categories that actually have kinds, in declared order
        used (filter groups (keys categories))]
    (html
        [:div.container                 ;TODO use page-html
         [:div.header
          [:h1 title " " version]
          [:div#app]
          [:span "Generated by " [:a {:href alzabo-link} "Alzabo"]]]
         (when-let [exp explanation]
           (vec (cons :div.explanation exp))
           )
         [:div.row
          [:div.py-5
           (when (> (count used) 1)
             (category-legend categories used))
           [:img {:src "schema.dot.svg"
                  :usemap "#schema"}]
           (slurp (output-file output-path "schema.dot.cmapx"))
           ]]
         [:div.row
          [:div.m-2
           [:h2 "Entities"]                ;aka Kinds, I suppose this should be configurable
           (for [category-name used]
             (let [kinds (category-name groups)
                   category (category-name categories)]
               [:div
                (when (> (count used) 1)
                  [:h3 {:style (header-style (:color category))}
                   (or (:label category)
                       (inflect/titleize category-name))])
                [:table.table.table-sm
                 (for [kind (sort-by :id kinds)]
                   [:tr
                    [:th (kind-html (:id kind))]
                    [:td (linkify (:doc kind))]]
                   )]
                ]))]]
         [:div.row
          [:div.m-2
           [:h2 "Enums"]
           [:table.table-sm
            (for [enum (sort (keys enums))]
              [:tr
               [:th (kind-html enum)]
               [:td (linkify (:doc (get enums enum)))]
               [:td (enum-samples (get enums enum))]
               ]
              )]]]
         ;; Pass the schema to clojurescript widget inside an invisible div
         ;; See hyperphor.alzabo.search.core/get-schema
         [:div#aschema {:style (style-arg {:display "none"})}
          (str schema)]
         [:script {:src "client.js"}]
         [:script "window.onload = function() { hyperphor.alzabo.search.core.run(); }"]
         ]
      )))

(defn- kind-relations
  "This generates the list of edges in the graph. Given a kind, returns a list of [relation target cardinality] pairs. Expands tuples"
  [kind {:keys [kinds enums]}]
  (filter (fn [[_ type _]]
            (get kinds type))
          (mapcat (fn [[fname {:keys [cardinality type] :as field-def}]]
                    (if (vector? type)  ; handle tuple types
                      (map (fn [tuple-component idx]
                             [(keyword (str (name fname) idx)) tuple-component cardinality])
                           type (range))
                      (list [fname type cardinality])))
                  (get-in kinds [kind :fields]))))


(defn omit-label?
  [label ref]
  (s/includes? (s/lower-case (name ref))
               (s/lower-case (name label))))


(defn- edge-label?
  [edge-labels? label ref]
  (case edge-labels?
    :minimal (omit-label? label ref) #_(not (or (= label ref)
                      (= (name label) (inflect/plural ref))))
    true true
    false))

;;; Graphviz size is inches, width first, and only shrinks (keeping aspect ratio).
;;; Browsers show dot's SVG at 96px/in, which is also the cmapx coordinate scale.
(defn- graph-size
  "Graphviz size attr for a maximum `width`/`height` in pixels; either may be nil."
  [width height]
  (let [in #(if % (/ % 96.0) 10000)]
    (str (in width) "," (in height))))

;;; dot seeds within-rank order from input order, so this tends to put same-category kinds near each other
(defn- category-sorted
  "`kinds` entries sorted by their category's position in `categories`, then by name."
  [kinds categories]
  (let [pos (zipmap (keys categories) (range))]
    (sort-by (fn [[kind {:keys [category] :or {category :default}}]]
               [(get pos category Integer/MAX_VALUE) (name kind)])
             kinds)))

(defn- graph-spec
  "Build a hyperphor.multitool.graph spec (default attrs plus node and edge lists) for `schema`."
  [schema]
  (let [{:keys [kinds categories render]} (with-graph-defaults schema)
        {:keys [orientation width height edge-labels? xlabels? taxonomy? graph-attrs node-attrs edge-attrs]} render
        clean (fn [kind] (s/replace (name kind) \- \_))]
    {:name "schema"                     ;names the cmapx <map>, see index->html's :usemap
     :graph-attrs (merge {:rankdir (case orientation
                                     :horizontal "LR"
                                     :vertical "TB")}
                         (when (or width height)
                           {:size (graph-size width height)})
                         graph-attrs)
     :node-attrs node-attrs
     :edge-attrs edge-attrs
     :nodes (for [[kind {:keys [doc category icon] :as kind-def :or {category :default}}] (category-sorted kinds categories)]
              [(clean kind)
               (merge {:URL (kind-url kind)
                       :label (if icon (str icon " " (name kind)) (name kind))
                       :tooltip (or doc (name kind))
                       :fillcolor (get-in categories [category :color])}
                      (get-in categories [category :node-attrs])
                      (:node-attrs kind-def))])
     :edges (concat
             (for [kind (keys kinds)
                   [label ref cardinality] (kind-relations kind schema)]
               [(clean kind)
                (clean ref)
                (merge {:arrowhead (if (= cardinality :many) "crow" "normal")}
                       (when taxonomy? {:constraint false})
                       (when (edge-label? edge-labels? label ref)
                         ;; Leading space keeps the label off its edge; dot has no edge label margin
                         {(if xlabels? :xlabel :label) (str " " (name label))}))])
             ;; Inheritance edges, dashed (child -> parent); with :taxonomy? laid out parent-first so the tree reads top-down
             (for [kind (keys kinds)
                   parent (schema/get-parents schema kind)]
               (if taxonomy?
                 [(clean parent)
                  (clean kind)
                  {:style "dashed" :dir "back" :arrowtail "empty" :color "#4CAF50" :penwidth "2.0"}]
                 [(clean kind)
                  (clean parent)
                  {:style "dashed"
                   :arrowhead "empty"
                   :color "#4CAF50"
                   :penwidth "2.0"}])))}))

(defn- dot-attrs
  [m]
  (s/join "," (for [[k v] m :when (some? v)]
                (format "%s=%s" (name k) (pr-str (if (keyword? v) (name v) (str v)))))))

;;; Translucent tint of a #rrggbb category color for the cluster background; other color forms get no fill
(defn- cluster-fill
  [color]
  (when (and (string? color) (re-matches #"#[0-9a-fA-F]{6}" color))
    (str color "40")))

(defn- cluster-specs
  "[[cluster-id attrs node-ids]...], one per category in use, if :clusters? and 2+ categories are used.
  Uncategorized kinds stay outside any cluster."
  [schema]
  (let [{:keys [kinds categories render]} (with-graph-defaults schema)
        groups (group-by (fn [[_ kdef]] (:category kdef)) kinds)
        used (filter #(and (not= % :default) (get groups %)) (keys categories))]
    (when (and (:clusters? render) (> (count used) 1))
      (for [cat used
            :let [{:keys [color label cluster-attrs]} (get categories cat)]]
        [(str "cluster_" (name cat))
         (merge {:label (or label (inflect/titleize (name cat)))
                 :style "rounded,filled"
                 :color color
                 :fillcolor (or (cluster-fill color) "white")
                 :penwidth 1.5
                 :fontname "Helvetica-Bold"
                 :fontsize 13
                 :labeljust "l"}
                cluster-attrs)
         (map (fn [[kind _]] (s/replace (name kind) \- \_)) (get groups cat))]))))

;;; multitool's spec->dot has no subgraphs; a node mentioned in a cluster joins it even if declared earlier
(defn- add-clusters
  "Splice `clusters` (as from cluster-specs) into `dot` source as subgraph statements."
  [dot clusters]
  (if (empty? clusters)
    dot
    (let [close (s/last-index-of dot "}")]
      (str (subs dot 0 close)
           (s/join (for [[id attrs nodes] clusters]
                     (format "subgraph %s {\ngraph [%s];\n%s\n}\n"
                             (pr-str id) (dot-attrs attrs)
                             (s/join " " (map #(str (pr-str %) ";") nodes)))))
           (subs dot close)))))

;;; Requires installation of graphviz ('brew install graphviz' on Mac)
(defn- write-graphviz
  "Write `dot-file` for `schema`, plus <dot-file>.svg and <dot-file>.cmapx."
  [schema dot-file]
  (let [dot (add-clusters (graph/spec->dot (graph-spec schema)) (cluster-specs schema))]
    (println "Writing " dot-file)
    (spit dot-file dot)
    (println "Generating .svg")
    (spit (str dot-file ".svg") (graph/dot->format dot "svg"))
    (spit (str dot-file ".cmapx") (graph/dot->format dot "cmapx"))))

;;; the fs/ symlink command won't do the right thing
(defn- make-link
  [path target]
  (Files/createSymbolicLink
   (.toPath (io/file path))
   (.toPath (io/file target))
   (make-array FileAttribute 0)))

(defn schema->html
  "Generate HTML docs for a schema, including .svg and related files, writing them to output-path."
  [{:keys [kinds enums version title] :as schema} output-path]
  (binding [*schema* schema]
    #_ (clear-directory output-path)
    (fs/mkdirs output-path)
    ;; Write out the schema itself – used by autocomplete, enflame, etc
    (with-open [o (clojure.java.io/writer (output-file output-path "schema.edn"))]
      (pp/pprint schema o))
    (doseq [kind (keys kinds)]
      (html-out output-path
                (str (name kind) ".html")
                (format "%s - %s - Alzabo" (name kind) title)
                (kind->html kind schema)
               ))
    (doseq [enum (keys enums)]
      (html-out output-path
                (str (name enum) ".html")
                (format "%s - %s - Alzabo" (name enum) title)
                (enum->html enum (get enums enum))
                ))
    (write-graphviz schema (output-file output-path "schema.dot"))
    (html-out output-path
              "index.html"
              (format "%s - Alzabo" title)
              (index->html schema version output-path)
              #_ version
              )
    #_ (make-link (output-file output-path "js") "../../js") ;argh. Necessary apparently, net infrastructure no longer lets .. work
    #_ (make-link (output-file output-path "alzabo.css") "../../alzabo.css")
    (io/copy (io/reader (io/resource "public/alzabo.css"))
             (io/file (output-file output-path "alzabo.css")))
    (io/copy (io/reader (io/resource "public/jsu/client.js")) ;Copy the uberjar version, which is single-file, so might actually work
             (io/file (output-file output-path "client.js")))
    nil
    ))


