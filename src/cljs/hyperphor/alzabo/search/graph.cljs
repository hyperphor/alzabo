(ns hyperphor.alzabo.search.graph
  "DOM side of the index page: highlighting over the inline graph SVG and kind lists, and pan/zoom."
  (:require [hyperphor.alzabo.render :as render]))

(defn by-id [id] (.getElementById js/document id))

(defn- qsa [root sel] (array-seq (.querySelectorAll root sel)))

(defn- add-class! [el & classes]
  (when el (doseq [c classes] (.add (.-classList el) c))))

(defn- css-escape [s] (js/CSS.escape s))

(defn- rows [kind]
  (qsa js/document (str "tr[data-kind=\"" (css-escape (name kind)) "\"]")))

(defn element-kind
  "Kind keyword for the graph node or list row containing `el`, if any."
  [el]
  (when-let [node (and el (.-closest el) (.closest el "g.kind, tr[data-kind]"))]
    (if-let [dk (.getAttribute node "data-kind")]
      (keyword dk)
      (keyword (subs (.-id node) (count (render/node-id "")))))))

(defn highlight!
  "Mark `center` (a kind), its neighbours and incident edges in `adjacency` with .hl, dim the rest; mark `selected`."
  [adjacency center selected]
  (when-let [svg (by-id "az-graph")]
    (doseq [el (qsa js/document ".hl, .az-center, .az-selected")]
      (.remove (.-classList el) "hl" "az-center" "az-selected"))
    (if-let [{:keys [kinds edges]} (and center (get adjacency center {:kinds #{} :edges #{}}))]
      (do
        (add-class! svg "az-dim")
        (add-class! (by-id (render/node-id center)) "hl" "az-center")
        (doseq [r (rows center)] (add-class! r "az-center"))
        (doseq [k kinds]
          (add-class! (by-id (render/node-id k)) "hl")
          (doseq [r (rows k)] (add-class! r "hl")))
        (doseq [e edges] (add-class! (by-id e) "hl")))
      (.remove (.-classList svg) "az-dim"))
    (when selected
      (add-class! (by-id (render/node-id selected)) "az-selected"))))

;;; Pan/zoom by rewriting the SVG viewBox

(defn- view-box [svg]
  (let [b (.-baseVal (.-viewBox svg))]
    [(.-x b) (.-y b) (.-width b) (.-height b)]))

(defn- set-view-box! [svg [x y w h]]
  (.setAttribute svg "viewBox" (str x " " y " " w " " h)))

(defn- client->svg
  "Client coordinates to `svg` user coordinates."
  [svg cx cy]
  (let [pt (.createSVGPoint svg)]
    (set! (.-x pt) cx)
    (set! (.-y pt) cy)
    (.matrixTransform pt (.inverse (.getScreenCTM svg)))))

(defn zoom!
  "Scale the view by `f` (<1 zooms in) around client point cx, cy (default: centre)."
  [svg f & [cx cy]]
  (let [[x y w h] (view-box svg)
        r (.getBoundingClientRect svg)
        p (client->svg svg (or cx (+ (.-left r) (/ (.-width r) 2))) (or cy (+ (.-top r) (/ (.-height r) 2))))
        px (.-x p) py (.-y p)]
    (set-view-box! svg [(- px (* (- px x) f)) (- py (* (- py y) f)) (* w f) (* h f)])))

(defn- scale [svg] (.-a (.getScreenCTM svg)))

(defn reveal!
  "Pan so `kind`'s node is centred, if it is not already fully visible."
  [kind]
  (let [svg (by-id "az-graph")
        node (by-id (render/node-id kind))]
    (when (and svg node)
      (let [nr (.getBoundingClientRect node)
            sr (.getBoundingClientRect svg)]
        (when (or (< (.-left nr) (.-left sr)) (> (.-right nr) (.-right sr))
                  (< (.-top nr) (.-top sr)) (> (.-bottom nr) (.-bottom sr)))
          (let [[x y w h] (view-box svg)
                s (scale svg)
                dx (/ (- (+ (.-left nr) (/ (.-width nr) 2)) (+ (.-left sr) (/ (.-width sr) 2))) s)
                dy (/ (- (+ (.-top nr) (/ (.-height nr) 2)) (+ (.-top sr) (/ (.-height sr) 2))) s)]
            (set-view-box! svg [(+ x dx) (+ y dy) w h])))))))

(defn- zoom-controls [svg home]
  (let [div (.createElement js/document "div")
        button (fn [label title f]
                 (let [b (.createElement js/document "button")]
                   (set! (.-textContent b) label)
                   (set! (.-title b) title)
                   (.addEventListener b "click" f)
                   (.appendChild div b)))]
    (set! (.-className div) "az-zoom")
    (button "+" "Zoom in" #(zoom! svg 0.8))
    (button "−" "Zoom out" #(zoom! svg 1.25))
    (button "⤢" "Fit" #(set-view-box! svg home))
    div))

(defn install-pan-zoom!
  "Wheel zooms, drag pans; a drag doesn't count as a click on a node."
  [svg]
  (let [home (view-box svg)
        drag (atom nil)]
    (.insertBefore (.-parentNode svg) (zoom-controls svg home) svg)
    (.addEventListener svg "wheel"
                       (fn [e]
                         (.preventDefault e)
                         (zoom! svg (js/Math.pow 1.0015 (.-deltaY e)) (.-clientX e) (.-clientY e)))
                       #js {:passive false})
    (.addEventListener svg "pointerdown"
                       (fn [e]
                         (when (zero? (.-button e))
                           (reset! drag {:x (.-clientX e) :y (.-clientY e) :vb (view-box svg) :s (scale svg) :moved? false}))))
    (.addEventListener js/window "pointermove"
                       (fn [e]
                         (when-let [{:keys [x y vb s moved?]} @drag]
                           (let [dx (- (.-clientX e) x) dy (- (.-clientY e) y)]
                             (when (or moved? (> (+ (js/Math.abs dx) (js/Math.abs dy)) 4))
                               (swap! drag assoc :moved? true)
                               (add-class! svg "az-panning")
                               (let [[vx vy w h] vb]
                                 (set-view-box! svg [(- vx (/ dx s)) (- vy (/ dy s)) w h])))))))
    (.addEventListener js/window "pointerup"
                       (fn [_]
                         (.remove (.-classList svg) "az-panning")
                         ;; Let the click that follows this pointerup see the drag, then clear it
                         (js/setTimeout #(reset! drag nil) 0)))
    (.addEventListener svg "click"
                       (fn [e]
                         (when (:moved? @drag)
                           (.preventDefault e)
                           (.stopPropagation e)))
                       true)))
