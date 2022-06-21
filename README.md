# af.fect
## Affective Programming for Functional Inheritance

More docs to come soon, but here's a quick brain dump:

I've been ruminating on this idea for around the last year - a paradigm I'm thinking about calling "affective programming," or "affect oriented programming" where an affect can be both a higher order function or a lower order function, depending on what's passed to it. If you pass it an environment map then, rather than running, it combines and/or actions off the contents of that map to return a new affect, otherwise it executes the lower order function - maybe called a dual order function. Like an optional partial that can accrete new behaviors.

You can then build up the implementation of functions in chains or mixins, similar to object oriented inheritance, but in a functional style. This reduces the amount of copy-paste of concrete implementations we sometimes see in some Clojure code bases (especially common in large frontend code bases). By carrying around a pseudo environment in each function, you can achieve a lot of the magic that the language has with its implicit env it caries around too.

No, I'm not trying to bring object oriented programming to Clojure - [closures and objects are equivalent](https://wiki.c2.com/?ClosuresAndObjectsAreEquivalent)

These are not objects in the object oriented sense. Closures could be seen as a kind of object of one method.

There's a lot more to discuss, regarding the idea, what it is, what it isn't, etc. but let's jump straight to some examples:

```clojure
(defn strings->ints [& string-ints]
  (->> string-ints
       (map str)
       (mapv edn/read-string)))

(def +s
  (af/fect
   {:as ::+s :with mocker
    :op +
    :ef (fn [{:keys [args]}]
          {:args (apply strings->ints args)})
    :mock [[1 "2" 3 4 "5" 6] 21]}))

(+s "1" 2)
;=> 3

(defn vecs->ints [& s]
  (->> s
       (reduce (fn [acc arg]
                 (if (vector? arg)
                   (into acc arg)
                   (conj acc arg)))
               [])))

(def +sv
  (+s
   {:as ::+sv
    :ef (fn [{:keys [args]}]
          {:args (apply vecs->ints args)})
    :mock [[1 [2]] 3]}))

(+sv "1" [2] 3 [4 5])
;=> 15
```

By passing a new environment map to the created function `+s` that has an `:as` key in it, the function knows to create another function derived from it.

`:ef` is an _effector_ function that runs at _effective_ time and will mutate the environment before args are passed to an `:op` operator. Passing an `:af` _affector_ will mutate the environment before prior to it being inherited by a child _affect_.

These _dual order_ functions, containing both affectors and effectors are called _affects_.

And `mocker` above is defined as:

```clojure
(ns af.ex
  (:require
   [af.fect :as af]
   [clojure.edn :as edn]))

(defn failure-message [data input output actual]
  (str "Failure in "   (or (:is data) (:as data))
       " with mock inputs " (pr-str input)
       " when expecting "    (pr-str output)
       " but actually got "     (pr-str actual)))

(def mocker
  (af/fect
   {:as :mock
    :void :mock
    :join #(do {:mocks (vec (concat (:mock %1) (:mock %2)))})
    :af (fn [{:as env :keys [mock]}]
          (when mock
            (let [this-af (af/fect (-> env (dissoc :mock) (assoc :as (:is env))))
                  failures (->> mock
                                (partition 2)
                                (mapv (fn [[in out]]
                                        (assert (coll? in))
                                        (let [result (apply this-af in)]
                                          (when (and result (not= result out))
                                            (failure-message env in out result)))))
                                (filter (complement nil?)))]
              (when (seq failures)
                (->> failures (mapv (fn [er] (throw (ex-info (str er) {})))))))))}))
```

You can combine these affects together via direct implementation inheritence or like mixins via `:with`.

A more involved example where frontend components are derived might look like:

```clojure
#_...impl

(def el
  (af
   {:as ::el :with [add-props classes]
    :env-op form-1})) ; <- env-op also passes the environment to the op

(def grid
  (el
   {:as ::grid
    :props {:comp mui-grid/grid}}))

(def container
  (grid
   {:as ::container
    :props {:container true}}))

(def item
  (grid
   {:as ::item
    :props {:item true}}))

(def btn
  (el
   {:as ::btn
    :props {:model :button
            :comp  mui-grid/button}}))

(def input
  (el
   {:as ::input :with [hide-required use-state validations]
    :props {:comp mui-grid/text-field}}))

(def form-input
  (input 
   {:as ::form-input
    :props {:style {:width "100%"
                    :padding 5}}}))

(def email-input
  (form-input
   {:as ::email-input
    :props {:label "Email"
            :placeholder "john@example.com"
            :helper-text "validating on blur"}
    :validate-on-blur? true
    :valid [#(<= 4 (count %))        "must be at least 4 characters"
            #(= "@" (some #{"@"} %)) "must contain an @ symbol"
            #(= "." (some #{"."} %)) "must contain a domain name (eg \"example.com\")"]}))

(def password ; <- abstract
  (form-input
   {:as ::password-abstract
    :props {:label "Password"
            :type :password}
    :valid [#(<= 8 (count %)) "must be longer than 8 characters"]}))

(def password-input
  (password
   {:as ::password-input
    :props {:validate-on-blur? true}}))

(def second-password-input
  (password
   {:as ::second-password-input :with submission
    :valid    [#(= % (password-input :state))
               "passwords must be equal"]
    :fields   [email-input password-input second-password-input]
    :props {:on-enter (fn [{:as _env :keys [fields]}]
                        (ajax-thing/submit-fields fields))}}))

(def submit-btn
  (btn
   {:as ::submit-btn :with submission
    :fields   [email-input password-input second-password-input]
    :props {:variant  "contained"
            :color    "primary"
            :on-click (fn [{:as _env :keys [fields]}]
                        (ajax-thing/submit-fields fields))}}))

#_...impl

(defn form [{:as props}]
  [container
   {:direction "row"
    :justify   "center"}
   [item {:style {:width "100%"}}
    [container {:direction :column
                :spacing 2
                :style {:padding 50
                        :width "100%"}}
     [item [email-input props]]
     [item [password-input props]]
     [item [second-password-input props]]
     [container {:direction :row
                 :style {:margin 10
                         :padding 10}}
      [item {:xs 8}]
      [item {:xs 4}
       [submit-btn props
        "Submit"]]]]]])
```
That's using an imaginary implementation of a component library build with `af.fect`. I've actually built one that does validations in that way, but I haven't ported the example to the demo component library I currently have yet.

To demonstrate how it works, I've built a todomvc example app.

I'm calling the nascent component library `comp.el`. It currently defaults to using re-frame, material-ui and radiant for css-in-cljs.

I'll be pushing up a new repository for `comp.el` soon. You'll get a better idea of how `af.fect` works by reading the source code for `comp.el` and it's demo todomvc project.

A more thorough introduction to this _"affective programming"_ paradigm and it's rationale will be discussed presented in this readme soon. Stay tuned...
