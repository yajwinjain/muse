package edu.stanford.muse.ner.model;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import edu.stanford.muse.Config;
import edu.stanford.muse.index.IndexUtils;
import edu.stanford.muse.ner.dictionary.EnglishDictionary;
import edu.stanford.muse.ner.featuregen.FeatureDictionary;
import edu.stanford.muse.ner.featuregen.FeatureGenerator;
import edu.stanford.muse.ner.featuregen.WordSurfaceFeature;
import edu.stanford.muse.ner.featuregen.FeatureDictionary.MU;
import edu.stanford.muse.ner.tokenizer.CICTokenizer;
import edu.stanford.muse.ner.tokenizer.POSTokenizer;
import edu.stanford.muse.util.*;
import opennlp.tools.formats.Conll03NameSampleStream;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.util.Span;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Created by vihari on 07/09/15.
 * This is a Bernoulli Mixture model, every word or pattern is considered a mixture. Does the parameter learning (mu, pi) for every mixture and assigns probabilities to every phrase.
 * An EM algorithm is used to estimate the params. The implementation can handle training size of order 100K. It is sometimes desired to train over a larger training files.
 * Consider implementing online EM based param estimation -- see http://cs.stanford.edu/~pliang/papers/online-naacl2009.pdf
 * It is beneficial to include Address-book in training. Names can have an uncommon first or last name --
 * for example a model trained on one-fifth of DBPedia instance types, that is 300K entries assigns 3E-7 score to {Sudheendra Hangal, PERSON}, which is understandable since the DBpedia list contains only one entry with Sudheendra
 */
public class SequenceModel implements NERModel, Serializable {
    public FeatureDictionary dictionary;
    public static String modelFileName = "SeqModel.ser";
    private static final long serialVersionUID = 1L;
    static Log log = LogFactory.getLog(SequenceModel.class);
    //public static final int MIN_NAME_LENGTH = 3, MAX_NAME_LENGTH = 100;
    public static FileWriter fdw = null;
    public static CICTokenizer tokenizer = new CICTokenizer();
    public Map<String, String> dbpedia;
    public static Map<Short, Short[]>mappings = new LinkedHashMap<>();

    static{
        mappings.put(FeatureDictionary.PERSON, new Short[]{FeatureDictionary.PERSON});
        mappings.put(FeatureDictionary.PLACE, new Short[]{FeatureDictionary.AIRPORT, FeatureDictionary.HOSPITAL,FeatureDictionary.BUILDING, FeatureDictionary.PLACE, FeatureDictionary.RIVER, FeatureDictionary.ROAD, FeatureDictionary.MOUNTAIN,
                FeatureDictionary.ISLAND, FeatureDictionary.MUSEUM, FeatureDictionary.BRIDGE,
                FeatureDictionary.THEATRE, FeatureDictionary.LIBRARY,FeatureDictionary.MONUMENT});
        mappings.put(FeatureDictionary.ORGANISATION, new Short[]{FeatureDictionary.COMPANY,FeatureDictionary.UNIVERSITY, FeatureDictionary.ORGANISATION,
                FeatureDictionary.AIRLINE, FeatureDictionary.GOVAGENCY, FeatureDictionary.AWARD, FeatureDictionary.LEGISTLATURE, FeatureDictionary.LAWFIRM,
                FeatureDictionary.PERIODICAL_LITERATURE
        });
    }

    public SequenceModel(FeatureDictionary dictionary, CICTokenizer tokenizer) {
        this.dictionary = dictionary;
        this.tokenizer = tokenizer;
    }

    public SequenceModel() {
    }

    /**
     * @param other boolean if is of other type
     */
    public static double getLikelihoodWithOther(String phrase, boolean other) {
        phrase = phrase.replaceAll("^\\W+|\\W+$", "");
        if (phrase.length() == 0) {
            if (other)
                return 1;
            else
                return 1.0 / Double.MAX_VALUE;
        }

        String[] tokens = phrase.split("\\s+");
        double p = 1;
        for (String token : tokens) {
            String orig = token;
            token = token.toLowerCase();
            List<String> noise = Arrays.asList("P.M", "P.M.", "A.M.", "today", "saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december", "thanks");
            if (noise.contains(token)) {
                if (other)
                    p *= 1;
                else
                    p *= 1.0 / Double.MAX_VALUE;
                continue;
            }
            Map<String, Pair<Integer, Integer>> map = EnglishDictionary.getDictStats();
            Pair<Integer, Integer> pair = map.get(token);

            if (pair == null) {
                //log.warn("Dictionary does not contain: " + token);
                if (orig.length() == 0) {
                    if (other)
                        p *= 1;
                    else
                        p *= 1.0 / Double.MAX_VALUE;
                }
                if (orig.charAt(0) == token.charAt(0)) {
                    if (other)
                        p *= 1;
                    else
                        p *= 1.0 / Double.MAX_VALUE;
                } else {
                    if (other)
                        p *= 1.0 / Double.MAX_VALUE;
                    else
                        p *= 1.0;
                }
                continue;
            }
            double v = (double) pair.getFirst() / (double) pair.getSecond();
            if (v > 0.25) {
                if (other)
                    return 1.0 / Double.MAX_VALUE;
                else
                    return 1.0;
            } else {
                if (token.charAt(0) == orig.charAt(0)) {
                    if (other)
                        return 1;
                    else
                        return 1.0 / Double.MAX_VALUE;
                } else {
                    if (other)
                        return 1.0 / Double.MAX_VALUE;
                    else
                        return 1.0;
                }
                //return 1;
            }
        }
        return p;
    }

    String lookup(String phrase) {
        if (dbpedia == null) {
            Map<String, String> orig = EmailUtils.readDBpedia();
            dbpedia = new LinkedHashMap<>();
            for (String str : orig.keySet())
                dbpedia.put(str.toLowerCase(), orig.get(str));
        }

        //if the phrase is from CIC Tokenizer, it won't start with an article
        //enough with the confusion between [New York Times, The New York Times], [Giant Magellan Telescope, The Giant Magellan Telescope]
        Set<String> vars = new LinkedHashSet<>();
        vars.add(phrase);
        vars.add("The "+phrase);
        String dbpediaType = null;
        for(String var: vars) {
            dbpediaType = dbpedia.get(var.toLowerCase());
            if(dbpediaType!=null) {
                //log.info("Found a match for: "+phrase+" -- "+dbpediaType);
                return dbpediaType;
            }
        }
        return null;
    }

    /**
     * Does sequence labeling of a phrase -- a dynamic programming approach
     * The complexity of this method has quadratic dependence on number of words in the phrase, hence should be careful with the length (a phrase with more than 7 words is rejected)
     * O(T*W^2) where W is number of tokens in the phrase and T is number of possible types
     * Since the word features that we are using are dependent on the boundary of the phrase i.e. the left and right semantic types, features on dictionary lookup e.t.c.
     * @note This method only returns the entities from the best labeled sequence.
     * @param phrase - String that is to be sequence labelled, keep this short; The string will be rejected if it contains more than 15 words
     * @return all the entities along with their types and quality score found in the phrase
    */
    public Map<String, Pair<Short, Double>> seqLabel(String phrase) {
        Map<String, Pair<Short, Double>> segments = new LinkedHashMap<>();
        String dbpediaType = lookup(phrase);
        Short ct = FeatureDictionary.codeType(dbpediaType);

        if (dbpediaType != null && ct >= 0 && (phrase.contains(" ") || dbpediaType.endsWith("Country|PopulatedPlace|Place"))) {
            segments.put(phrase, new Pair<>(ct, 1.0));
            return segments;
        }
        if (log.isDebugEnabled() && dbpediaType != null)
            log.debug("Found match: Coded type: " + ct + "- Phrase:" + phrase + " - DBpedia type: " + dbpediaType);

        //This step of uncanonicalizing phrases helps merging things that have different capitalization and in lookup
        phrase = EmailUtils.uncanonicaliseName(phrase);
        //phrase = clean(phrase);
        if (phrase == null || phrase.length() == 0)//||!phrase.contains(" "))
            return new LinkedHashMap<>();
        phrase = phrase.replaceAll("^\\W+|\\W+^", "");

        String[] tokens = phrase.split("\\s+");

        /**
         * In TW's sub-archive with ~65K entities scoring more than 0.001. The stats on frequency of #tokens per word is as follows
         * Freq  #tokens
         * 36520 2
         * 15062 3
         * 5900  4
         * 2645  5
         * 2190  1
         * 1301  6
         * 721   7
         * 18    8
         * 9     9
         * 2     10
         * 1     11
         * Total: 64,369 -- hence the cutoff below
         */
        if (tokens.length > 7) {
            return new LinkedHashMap<>();
        }
        //since there can be large number of types every token can take
        //we restrict the number of possible types we consider to top 5
        //see the complexity of the method
        Set<Short> cands = new LinkedHashSet<>();
        for (String token : tokens) {
            Map<Short, Double> candTypes = new LinkedHashMap<>();
            if (token.length() != 2 || token.charAt(1) != '.')
                token = token.replaceAll("^\\W+|\\W+$", "");
            token = token.toLowerCase();
            FeatureDictionary.MU mu = dictionary.features.get(token);
            if (token.length() < 2 || mu == null || mu.numMixture == 0)
                continue;
            for (Short type : FeatureDictionary.allTypes) {
                double val = mu.getLikelihoodWithType(type);
                if (!candTypes.containsKey(type))
                    candTypes.put(type, 0.0);
                candTypes.put(type, candTypes.get(type) + val);
            }
            List<Pair<Short, Double>> scands = Util.sortMapByValue(candTypes);
            int si = 0, MAX = 5;
            for (Pair<Short, Double> p : scands)
                if (si++ < MAX)
                    cands.add(p.getFirst());
//            if (log.isDebugEnabled())
//                log.debug("Token: " + token + " - cands: " + scands);
        }
//        if (log.isDebugEnabled())
//            log.debug("Candidate types for phrase: " + phrase + " -- " + cands);
        //This is just a standard dynamic programming algo. used in HMMs, with the difference that
        //at every word we are checking for the every possible segment
        short OTHER = -2;
        cands.add(OTHER);
        Map<Integer, Triple<Double, Integer, Short>> tracks = new LinkedHashMap<>();
        Map<Integer,Integer> numSegmenation = new LinkedHashMap<>();

        for (int ti = 0; ti < tokens.length; ti++) {
            double max = -1, bestValue = -1;
            int bi = -1;
            short bt = -10;
            for (short t : cands) {
                int tj = Math.max(ti - 6, 0);
                //don't allow multi word phrases with these types
                if (t == OTHER || t == FeatureDictionary.OTHER)
                    tj = ti;
                for (; tj <= ti; tj++) {
                    double val = 1;
                    if (tj > 0)
                        val *= tracks.get(tj - 1).first;
                    String segment = "";
                    for (int k = tj; k < ti + 1; k++) {
                        segment += tokens[k];
                        if (k != ti)
                            segment += " ";
                    }

                    if (t != OTHER)
                        val *= getConditional(segment, t) * getLikelihoodWithOther(segment, false);
                    else
                        val *= getLikelihoodWithOther(segment, true);

                    double ov = val;
                    int numSeg = 1;
                    if(tj>0)
                        numSeg += numSegmenation.get(tj-1);
                    val = Math.pow(val, 1f/numSeg);
                    if (val > max) {
                        max = val;
                        bestValue = ov;
                        bi = tj - 1;
                        bt = t;
                    }
                }
            }
            numSegmenation.put(ti, ((bi>=0)?numSegmenation.get(bi):0)+1);
            tracks.put(ti, new Triple<>(bestValue, bi, bt));
        }
        System.err.println("Tracks: "+tracks);
        System.err.println("numSegs: "+numSegmenation);

        //the backtracking step
        int start = tokens.length - 1;
        while (true) {
            Triple<Double, Integer, Short> t = tracks.get(start);
            String seg = "";
            for (int ti = t.second + 1; ti <= start; ti++)
                seg += tokens[ti] + " ";
            seg = seg.substring(0,seg.length()-1);
            double val;
            if(t.getThird() != OTHER)
                val = getConditional(seg, t.getThird()) * getLikelihoodWithOther(seg, false);
            else
                val = getLikelihoodWithOther(seg, true);

            segments.put(seg, new Pair<>(t.getThird(), val));
            start = t.second;
            if (t.second == -1)
                break;
        }
        return segments;
    }

    public double getConditional(String phrase, Short type) {
        Map<String, FeatureDictionary.MU> features = dictionary.features;
        Map<String, List<String>> tokenFeatures = dictionary.generateFeatures2(phrase, type);
        String[] tokens = phrase.split("\\s+");
        if(FeatureDictionary.sws.contains(tokens[0]) || FeatureDictionary.sws.contains(tokens[tokens.length-1]))
            return 0;

        double sorg = 0;
        String dbpediaType = lookup(phrase);
        short ct = FeatureDictionary.codeType(dbpediaType);

        if(dbpediaType!=null && ct==type){
            if(dbpediaType.endsWith("Country|PopulatedPlace|Place"))
                return 1;
            else if (phrase.contains(" "))
                return 1;
        }

        String[] patts = FeatureDictionary.getPatts(phrase);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int si = 0; si < patts.length; si++) {
            String patt = patts[si];
            map.put(patt, si);
        }

        for (String mid : tokenFeatures.keySet()) {
            Double d;
            MU mu = features.get(mid);
            //Do not even consider the contribution from this mixture if it does not have a good affinity with this type
            if(mu!=null && mu.getLikelihoodWithType(type)<0.1)
                continue;

            int THRESH = 0;
            //imposing the frequency constraint on numMixture instead of numSeen can benefit in weeding out terms that are ambiguous, which could have appeared many times, but does not appear to have common template
            //TODO: this check for "new" token is to reduce the noise coming from lowercase words starting with the word "new"
            if (mu != null && ((type!=FeatureDictionary.PERSON && mu.numMixture>THRESH)||(type==FeatureDictionary.PERSON && mu.numMixture>0)) && !mid.equals("new") && !mid.equals("first") && !mid.equals("open"))
                d = mu.getLikelihood(tokenFeatures.get(mid), dictionary);
            else
                //a likelihood that assumes nothing
                d = MU.getMaxEntProb();
            double val = d;

            double freq = 0;
            if (d > 0) {
                if (features.get(mid) != null)
                    freq = features.get(mid).getPrior();
                val *= freq;
            }

            if(log.isDebugEnabled()) {
                if(mu!=null) {
                    List<String> tfs = tokenFeatures.get(mid);
                    //log.debug("Features for: " + mid + " in " + phrase + ", " + tfs + " score: " + d + " - " + mu.getPrior() + ", type: " + type);
                    for(String ft: tfs) {
                        String dim = ft.substring(0,ft.indexOf(':'));
                        float alpha_k = 0, alpha_k0 = 0;
                        if(mu.alpha.containsKey(ft))
                            alpha_k = mu.alpha.get(ft);
                        if(mu.alpha_0.containsKey(dim))
                            alpha_k0 = mu.alpha_0.get(dim);
                        double v = MU.getNumberOfSymbols(ft);
                        double mvp = 0;
                        if(mu.muVectorPositive.containsKey(ft))
                            mvp = mu.muVectorPositive.get(ft);
                       // log.debug(ft+"--"+(mvp+alpha_k+1)/(mu.numMixture+alpha_k0+v));
                    }
                }
                else
                    ;//log.debug("Features for: " + mid + " in " + phrase + ", " + tokenFeatures.get(mid) + " score: " + d + " - " + freq + ", type: " + type + " MU: " + features.get(mid));
            }
            //Should actually use logs here, not sure how to handle sums with logarithms
            sorg += val;
        }
        return sorg;
    }

    public double score(String phrase, Short type) {
        //if contains "The" in the beginning, score it without "The"
        if(phrase.startsWith("The "))
            phrase = phrase.replaceAll("^The ","");
        List<String> commonWords = Arrays.asList("as", "because", "just", "in", "by", "for", "and", "to", "on", "of", "dear", "according", "think", "a", "an", "if", "at", "but", "the", "is");
        //what the candidate starts or ends with is important
        String[] swords = phrase.split("\\s+");
        String fw = swords[0].toLowerCase();
        fw = FeatureDictionary.endClean.matcher(fw).replaceAll("");
        String sw = null;
        if (swords.length > 1) {
            sw = swords[swords.length - 1].toLowerCase();
            sw = FeatureDictionary.endClean.matcher(sw).replaceAll("");
        }
        //the first word should not just be a word of special chars
        if (commonWords.contains(fw) || commonWords.contains(sw) || fw.equals(""))
            return 0.0;

        String[] scores = new String[FeatureDictionary.allTypes.length];
        Short bt = FeatureDictionary.OTHER;
        double bs = -1;
        for(int ti=0;ti<FeatureDictionary.allTypes.length;ti++){
            Short t = FeatureDictionary.allTypes[ti];
            double s = 0;
            String frags = "";
            {
                Map<String, List<String>> tokenFeatures = dictionary.generateFeatures2(phrase, type);
                for (String mid : tokenFeatures.keySet()) {
                    Double d;
                    if (dictionary.features.get(mid) != null)
                        d = dictionary.features.get(mid).getLikelihood(tokenFeatures.get(mid), dictionary);
                    else
                        d = 0.0;//(1.0/MU.WORD_LABELS.length)*(1.0/MU.WORD_LABELS.length)*(1.0/MU.TYPE_LABELS.length)*(1.0/MU.POSITION_LABELS.length)*(1.0/MU.ADJ_LABELS.length)*(1.0/MU.ADV_LABELS.length)*(1.0/MU.DICT_LABELS.length)*(1.0/MU.PREP_LABELS.length)*(1.0/MU.V_LABELS.length)*(1.0/MU.PN_LABELS.length);
                    if (Double.isNaN(d))
                        log.warn("Conditional NaN for mixture ID: " + mid);
                    double val = d;
                    if (val > 0) {
                        double freq = 0;
                        if (dictionary.features.get(mid) != null)
                            freq = dictionary.features.get(mid).getPrior();
                        val *= freq;
                    }
                    s += val;//*dictionary.getMarginal(word);
                    frags += val+" ";
                }
            }
            scores[ti] = frags;
            if(s>bs) {
                bt = t;
                bs = s;
            }
        }
        if (fdw != null) {
            try {
                String str = "";
                for(int si=0;si<scores.length;si++)
                    str += FeatureDictionary.allTypes[si]+":<"+scores[si]+"> ";
                String[] words = phrase.split("[\\s,]+");
                String labelStr = "";
                for(String word: words) {
                    Pair<String,Double> p = dictionary.getLabel(word, dictionary.features);
                    FeatureDictionary.MU mu = dictionary.features.get(word);
                    String label;
                    if(mu == null)
                        label = p.getFirst();
                    else{
                        if(mu.getLikelihoodWithType(FeatureDictionary.OTHER)>p.getSecond())
                            label = ""+FeatureDictionary.OTHER;
                        else
                            label = p.getFirst();
                    }
                    labelStr += word+":"+label+" ";
                }
                fdw.write(labelStr+"\n");
                fdw.write(dictionary.generateFeatures2(phrase, type).toString()+"\n");
                fdw.write("String: " + phrase + " - " + str + "\n");
            }catch(IOException e){
                e.printStackTrace();
            }
        }
        return bs*((bt==type)?1:-1);
    }

    public static Map<Short,List<String>> mergeTypes(Map<Short,Map<String,Double>> entities){
        Map<Short,List<String>> mTypes = new LinkedHashMap<>();
        Short[] types = new Short[]{FeatureDictionary.PERSON, FeatureDictionary.ORGANISATION, FeatureDictionary.PLACE};
        for(Short type: types)
            mTypes.put(type, new ArrayList<>());

        for(Short gt: types){
            for(Short ft: mappings.get(gt))
                if(entities.containsKey(ft))
                    mTypes.get(gt).addAll(entities.get(ft).keySet());
        }
        return mTypes;
    }

    public Pair<Map<Short,Map<String,Double>>, List<Triple<String, Integer, Integer>>> find (String content){
        Map<Short, Map<String,Double>> maps = new LinkedHashMap<>();
        List<Triple<String,Integer,Integer>> offsets = new ArrayList<>();

        for(Short at: FeatureDictionary.allTypes)
            maps.put(at, new LinkedHashMap<>());

        String[] sents = NLPUtils.tokeniseSentence(content);
        for(String sent: sents) {
            List<Triple<String, Integer, Integer>> toks = tokenizer.tokenize(sent, false);
            for (Triple<String, Integer, Integer> t : toks) {
                //this should never happen
                if(t==null || t.first == null)
                    continue;

                Map<String,Pair<Short,Double>> entities = seqLabel(t.getFirst());
                for(String e: entities.keySet()){
                    Pair<Short,Double> p = entities.get(e);
                    //A new type is assigned to some words, which is of value -2
                    if(p.first<0)
                        continue;

                    if(p.first!=FeatureDictionary.OTHER && p.second>0) {
                        //System.err.println("Segment: "+t.first+", "+t.second+", "+t.third+", "+sent.substring(t.second,t.third));
                        offsets.add(new Triple<>(e, t.second + t.first.indexOf(e), t.second + t.first.indexOf(e) + e.length()));
                        maps.get(p.getFirst()).put(e, p.second);
                    }
                }
            }
        }
        return new Pair<>(maps, offsets);
    }

    public synchronized void writeModel(File modelFile) throws IOException{
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(modelFile));
        oos.writeObject(this);
        oos.close();
    }

    public static synchronized SequenceModel loadModel(String modelPath) throws IOException{
        ObjectInputStream ois;
        try {
            ois = new ObjectInputStream(Config.getResourceAsStream(modelPath));
            SequenceModel model = (SequenceModel) ois.readObject();
            ois.close();
            return model;
        } catch (Exception e) {
            Util.print_exception("Exception while trying to load model from: " + modelPath, e, log);
            return null;
        }
    }

    //samples [fraction] fraction of entries from dictionary supplied and splices the supplied dict
    public static Pair<Map<String,String>,Map<String,String>> split(Map<String,String> dict, float fraction){
        Map<String,String> dict1 = new LinkedHashMap<>(), dict2 = new LinkedHashMap<>();
        Random rand = new Random();
        for(String str: dict.keySet()){
            if(rand.nextFloat()<fraction){
                dict1.put(str, dict.get(str));
            }else{
                dict2.put(str, dict.get(str));
            }
        }
        System.err.println("Sliced " + dict.size() + " entries into " + dict1.size() + " and " + dict2.size());
        return new Pair<>(dict1, dict2);
    }

    public static void testDBpedia(NERModel nerModel){
        //when testing remember to change
        //1. lookup method, disable the lookup
        System.err.println("DBpedia scoring check starts");
        String twl = System.getProperty("user.home")+File.separator+"epadd-settings"+File.separator+"SeqModel-test.en.txt.bz2";
        //clear the cache
        EmailUtils.dbpedia = null;
        Map<String,String> dbpedia = EmailUtils.readDBpedia(1, twl);
        //NOther == Not OTHER
        //number of things shown (NON-OTHER) and number of things that should be shown
        int ne = 0, neShown = 0, neShouldShown = 0;
        //number of entries assigned to wrong type and number missed because they are assigned OTHER
        int missAssigned=0, missSegmentation = 0, missNoEvidence = 0;
        int correct = 0;
        //these are the entries which are not completely tagged as OTHER by NER, but may have some segments that are not OTHER, hence visible
        double CUTOFF = 0;
        Map<Short,Map<Short,Integer>> confMat = new LinkedHashMap<>();
        Map<Short, Integer> freqs = new LinkedHashMap<>();
        String[] badSuffixTypes = new String[]{"MusicalWork|Work","Sport", "Film|Work", "Band|Group|Organisation", "Food",
                "EthnicGroup","RadioStation|Broadcaster|Organisation", "MeanOfTransportation", "TelevisionShow|Work",
                "Play|WrittenWork|Work","Language", "Book|WrittenWork|Work","Genre|TopicalConcept", "InformationAppliance|Device",
                "SportsTeam|Organisation", "Eukaryote|Species","Software|Work", "TelevisionEpisode|Work", "Comic|WrittenWork|Work",
                "Mayor", "Website|Work", "Cartoon|Work"
        };
        ol:
        for(String entry: dbpedia.keySet()){
            if(!entry.contains(" "))
                continue;
            String fullType = dbpedia.get(entry);
            Short type = FeatureDictionary.codeType(dbpedia.get(entry));

            if(fullType.equals("Agent"))
                type = FeatureDictionary.PERSON;
            else
                for (String bst: badSuffixTypes)
                    if(fullType.endsWith(bst))
                        continue ol;

            entry = EmailUtils.uncanonicaliseName(entry);
            if(entry.length()>=15)
                continue;
            Pair<Map<Short,Map<String,Double>>, List<Triple<String,Integer,Integer>>> p = nerModel.find(entry);
            Map<Short, Map<String,Double>> es = p.getFirst();
            Map<Short, Map<String,Double>> temp = new LinkedHashMap<>();
            for(Short t: es.keySet()) {
                if(es.get(t).size()==0)
                    continue;
                temp.put(t, new LinkedHashMap<>());
                for (String str : es.get(t).keySet())
                    if(es.get(t).get(str)>CUTOFF)
                        temp.get(t).put(str, es.get(t).get(str));
            }
            es = temp;

            short assignedTo = type;
            boolean shown = false;
            //we should not bother about segmentation in the case of OTHER
            if(!(es.containsKey(FeatureDictionary.OTHER) && es.size()==1)) {
                shown = true;
                boolean any;
                if (type!=FeatureDictionary.OTHER && es.containsKey(type) && es.get(type).containsKey(entry))
                    correct++;
                else {
                    any = false;
                    boolean found = false;
                    assignedTo = -1;
                    for (Short t : es.keySet()) {
                        if (es.get(t).containsKey(entry)) {
                            found = true;
                            assignedTo = t;
                            break;
                        }
                        if (es.get(t).size() > 0)
                            any = true;
                    }
                    if (found) {
                        missAssigned++;
                        System.err.println("Wrong assignment miss\nExpected: " + entry + " - " + fullType + " found: " + assignedTo + "\n" + p.getFirst() + "--------");
                    } else if (any) {
                        System.err.println("Segmentation miss\nExpected: " + entry + " - " + fullType + "\n" + p.getFirst() + "--------");
                        missSegmentation++;
                    } else {
                        missNoEvidence++;
                        System.err.println("Not enough evidence for: " + entry + " - " + fullType);
                    }
                }
            }
            if(shown)
                neShown++;
            if(type!=FeatureDictionary.OTHER)
                neShouldShown++;


            if(ne++%100 == 0)
                System.err.println("Done testing on "+ne+" of "+dbpedia.size());
            if(!confMat.containsKey(type))
                confMat.put(type, new LinkedHashMap<>());
            if(!confMat.get(type).containsKey(assignedTo))
                confMat.get(type).put(assignedTo, 0);
            confMat.get(type).put(assignedTo, confMat.get(type).get(assignedTo)+1);

            if(!freqs.containsKey(type))
                freqs.put(type, 0);
            freqs.put(type, freqs.get(type)+1);
        }
        List<Short> allTypes = new ArrayList<>();
        for(Short type: confMat.keySet())
            allTypes.add(type);
        Collections.sort(allTypes);
        allTypes.add((short)-1);
        System.err.println("Tested on "+ne+" entries");
        System.err.println("------------------------");
        String ln = "  ";
        for(Short type: allTypes)
            ln += String.format("%5s",type);
        System.err.println(ln);
        for(Short t1: allTypes){
            ln = String.format("%2s",t1);
            for(Short t2: allTypes) {
                if(confMat.containsKey(t1) && confMat.get(t1).containsKey(t2) && freqs.containsKey(t1))
                    ln += String.format("%5s", new DecimalFormat("#.##").format((double)confMat.get(t1).get(t2)/freqs.get(t1)));//new DecimalFormat("#.##").format((double) confMat.get(t1).get(t2) / freqs.get(t1)));
                else
                    ln += String.format("%5s","-");
            }
            System.err.println(ln);
        }
        System.err.println("------------------------\n");
        double precision = (double)(correct)/(neShown);
        double recall = (double)correct/neShouldShown;
        //miss and misAssigned are number of things we are missing we are missing, but for different reasons, miss is due to segmentation problem, assignment to OTHER; misAssigned is due to wrong type assignment
        //visible = ne - number of entries that are assigned OTHER label and hence visible
        System.err.println("Missed #"+missAssigned+" due to improper assignment\n#"+missSegmentation+"due to improper segmentation\n" +
                "#"+missNoEvidence+" due to single word or no evidence");
        System.err.println("Precision: "+precision+"\nRecall: "+recall);
    }

    public static SequenceModel train(){
        SequenceModel nerModel = new SequenceModel();
        Map<String,String> dbpedia = EmailUtils.readDBpedia(1.0/5);
        //This split is essential to isolate some entries that trained model has not seen
        Pair<Map<String,String>,Map<String,String>> p = split(dbpedia, 0.8f);
        Map<String,String> train = p.getFirst();
        Map<String, String> test = p.getSecond();

        //split the dictionary into train and test sets
        Set<String> fts = new LinkedHashSet<>();
        fts.add(WordSurfaceFeature.WORDS);
        FeatureDictionary dictionary = new FeatureDictionary(train);
        nerModel.dictionary = dictionary;
        nerModel.dictionary.EM(train);
        try {
            String mwl = System.getProperty("user.home")+File.separator+"epadd-settings"+File.separator;
            String modelFile = mwl + SequenceModel.modelFileName;
            nerModel.writeModel(new File(modelFile));
            //also write the test split
            String twl = System.getProperty("user.home")+File.separator+"epadd-settings"+File.separator+"SeqModel-test.en.txt.bz2";
            if(!new File(twl).exists()) {
                OutputStreamWriter osw = new OutputStreamWriter(new BZip2CompressorOutputStream(new FileOutputStream(new File(twl))));
                int numTest = 0;
                for (String str : test.keySet()) {
                    String orig = str;
                    str = str.replaceAll(" ", "_");
                    osw.write(str + " " + test.get(orig) + "\n");
                    numTest++;
                }
                osw.close();
                System.err.println("Wrote "+numTest+" records in test split to: "+twl);
            }
        }catch(IOException e){
            e.printStackTrace();
        }
        return nerModel;
    }

    public static void main1(String[] args){
        try {
            String content = "Bob,\n" +
                    "Are you back from Maine: how is your sister?\n" +
                    "One piece of business: the edition from Tamarind Insititute has not\n" +
                    "arrived here. Do you know of some delay, or should I just get on the matter\n" +
                    "my self.\n" +
                    "I think you knew that ND was to publish the Duncan/Levertov letters,\n" +
                    "but when the book got too big they were happy enough not to do it. Last week\n" +
                    "the volume was accepted by the editorial board at Stanford Univ. Press. It\n" +
                    "all comes out as a fine collaboration with Al Gelpi, half the letters here,\n" +
                    "half the letters at Stanford. Out in about a year, so I am told. To replace\n" +
                    "the ND book I have given them another book called \"Robert Duncan's Ezra\n" +
                    "Pound,\" which has both sides of the correspondence in a narrative with other\n" +
                    "docs. Plus poems and unpublished essays. Then a new edtion of Letters, RD\n" +
                    "title, from a small press in St Louis. I've finished the Olson/Duncan\n" +
                    "letters, but am now struggling with the transcriptions of RD lectures of CO.\n" +
                    "They so resist being reading texts. I'll have more on that soon. Letters and\n" +
                    "Lectures to go to Wisconsin. Or, the snow at Christmas kept me off the\n" +
                    "streets and at my desk. In that sense it was a moral snow.\n" +
                    "If you're in Buffalo could you stand a stop after work?\n" +
                    "My best, " +
                    "National Bank some. National Kidney Foundation some . University Commencement.\n" +
                    "Address of Amuse Labs.OUT HOUSE, 19/1, Ramchandra Kripa, Mahishi Road, Malmaddi Dharwad.Address of US stay.483, Fulton Street, Palo Alto";
            System.err.println("Tokens: "+new POSTokenizer().tokenize(content));

            //String userDir = System.getProperty("user.home") + File.separator + ".muse" + File.separator + "user-creeley";
            String mwl = System.getProperty("user.home") + File.separator + "epadd-settings" + File.separator;
            String modelFile = mwl + SequenceModel.modelFileName;
            if (fdw == null) {
                try {
                    fdw = new FileWriter(new File(System.getProperty("user.home") + File.separator + "epadd-settings" + File.separator + "cache" + File.separator + "features.dump"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            System.err.println("Loading model...");
            SequenceModel nerModel = null;
            try{nerModel = SequenceModel.loadModel(modelFile);}
            catch(IOException e){e.printStackTrace();}
            if(nerModel == null)
                nerModel = train();
//            int di =0;
//            for(Document doc: docs) {
//                String content = archive.getContents(doc, true);
//                System.err.println("Content: "+content);
//                Pair<Map<Short,List<String>>, List<Triple<String,Integer,Integer>>> mapsAndOffsets = nerModel.find(content);
//                for(Short type: mapsAndOffsets.getFirst().keySet())
//                    System.err.println(type +" : "+mapsAndOffsets.getFirst().get(type));
//                if(di++>10)
//                    break;
//            }
            Pair<Map<Short,Map<String,Double>>, List<Triple<String, Integer, Integer>>> mapsandoffsets = nerModel.find(content);
            Map<Short, List<String>> map = SequenceModel.mergeTypes(mapsandoffsets.first);
            for(Short type: map.keySet())
                System.out.println(type + " : "+mapsandoffsets.first.get(type)+"<br>");
            System.err.println(nerModel.dictionary.getConditional("Robert Creeley", FeatureDictionary.PERSON, fdw));
            System.err.println(nerModel.dictionary.getConditional("Robert Creeley", FeatureDictionary.OTHER, fdw));
            String[] check = new String[]{"California State Route 1", "New York Times", "Goethe Institute of Prague", "Venice high school students","Denver International Airport",
                    "New York International Airport", "Ramchandra Kripa, Mahishi Road"};
            for(String c: check) {
                System.err.println(c + ", " + nerModel.seqLabel(c));
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    //we are missing F.C's like F.C. La Valletta
    /**
     * Tested on 28th Jan. 2016 on what is believed to be the testa.dat file of original CONLL.
     * I procured this data-set from a prof's (UMass Prof., don't remember the name) home page where he provided the test files for a homework, guess who topped the assignment :)
     * (So, don't use this data to report results at any serious venue)
     * The results on multi-word names is as follows.
     * Note that the test only considered PERSON, LOCATION and ORG; Also, it does not distinguish between the types because the type assigned by Sequence Labeler is almost always right. And, importantly this will avoid any scuffle over the mapping from fine-grained type to the coarse types.
     *  -------------
     *  Found: 8861 -- Total: 7781 -- Correct: 6675
     *  Precision: 0.75330096
     *  Recall: 0.8578589
     *  F1: 0.80218726
     *  ------------
     * I went through 2691 sentences of which only 200 had any unrecognised entities and identified various sources of error.
     * The sources of missing names are as follows in decreasing order of their contribution (approximately), I have put some examples with the sources. The example phrases are recognized as one chunk with a type.
     * Obviously, this list is not exhaustive, USE IT WITH CAUTION!
     *  1. Bad segmentation -- which is minor for ePADD and depends on training data and principles.
     *     For example: "Overseas Development Minister <PERSON>Lynda Chalker</PERSON>",Czech <PERSON>Daniel Vacek</PERSON>, "Frenchman <PERSON>Cedric Pioline</PERSON>"
     *     "President <PERSON>Nelson Mandela</PERSON>","<BANK>Reserve Bank of India</BANK> Governor <PERSON>Chakravarty Rangarajan</PERSON>"
     *     "Third-seeded <PERSON>Wayne Ferreira</PERSON>",
     *     Hong Kong Newsroom -- we got only Hong Kong, <BANK>Hong Kong Interbank</BANK> Offered Rate, Privately-owned <BANK>Bank Duta</BANK>
     *     [SERIOUS]
     *  2. Bad training data -- since our training data (DBpedia instances) contain phrases like "of Romania" a lot
     *     Ex: <PERSON>Yayuk Basuki</PERSON> of Indonesia, <PERSON>Karim Alami</PERSON> of Morocco
     *     This is also leading to errors like when National Bank of Holand is segmented as National Bank
     *     [SERIOUS]
     *  3. Some unknown names, mostly personal -- we see very weird names in CONLL; Hopefully, we can avoid this problem in ePADD by considering the address book of the archive.
     *     Ex: NOVYE ATAGI, Hans-Otto Sieg, NS Kampfruf, Marie-Jose Perec, Billy Mayfair--Paul Goydos--Hidemichi Tanaki
     *     we miss many (almost all) names of the form "M. Dowman" because of uncommon or unknown last name.
     *  4. Bad segmentation due to limitations of CIC
     *     Ex: Hassan al-Turabi, National Democratic party, Department of Humanitarian affairs, Reserve bank of India, Saint of the Gutters, Queen of the South, Queen's Park
     *  5. Very Long entities -- we refrain from seq. labelling if the #tokens>7
     *     Ex: National Socialist German Workers ' Party Foreign Organisation
     *  6. We are missing OCEANs?!
     *     Ex: Atlantic Ocean, Indian Ocean
     *  7. Bad segments -- why are some segments starting with weird chars like '&'
     *     Ex: Goldman Sachs & Co Wertpapier GmbH -> {& Co Wertpapier GmbH, Goldman Sachs}
     *  8. We are missing Times of London?! We get nothing that contains "Newsroom" -- "Amsterdam Newsroom", "Hong Kong News Room"
     *     Why are we getting "Students of South Korea" instead of "South Korea"?
     *
     * 06 Feb 00:18:01 SequenceModel INFO  - -------------
     * 06 Feb 00:18:01 SequenceModel INFO  - Found: 4119 -- Total: 4236 -- Correct: 3392 -- Missed due to wrong type: 323
     * 06 Feb 00:18:01 SequenceModel INFO  - Precision: 0.8235009
     * 06 Feb 00:18:01 SequenceModel INFO  - Recall: 0.80075544
     * 06 Feb 00:18:01 SequenceModel INFO  - F1: 0.81196886
     * 06 Feb 00:18:01 SequenceModel INFO  - ------------
     *
     * When the model is trained on one fiftieth of DBpedia, the results are
     * 06 Feb 19:11:45 SequenceModel INFO  - -------------
     * 06 Feb 19:11:45 SequenceModel INFO  - Found: 3750 -- Total: 4236 -- Correct: 3039 -- Missed due to wrong type: 329
     * 06 Feb 19:11:45 SequenceModel INFO  - Precision: 0.8104
     * 06 Feb 19:11:45 SequenceModel INFO  - Recall: 0.71742207
     * 06 Feb 19:11:45 SequenceModel INFO  - F1: 0.7610819
     * 06 Feb 19:11:45 SequenceModel INFO  - ------------
     * */
    public static void test(SequenceModel seqModel){
        try {
            InputStream in = new FileInputStream(new File(System.getProperty("user.home")+File.separator+"epadd-ner"+File.separator+"ner-benchmarks"+File.separator+"umasshw"+File.separator+"testaspacesep.txt"));
            //7==0111 PER, LOC, ORG
            Conll03NameSampleStream sampleStream = new Conll03NameSampleStream(Conll03NameSampleStream.LANGUAGE.EN, in, 7);
            int numCorrect = 0, numFound = 0, numReal = 0, numWrongType = 0;
            Set<String> correct = new LinkedHashSet<>(), found = new LinkedHashSet<>(), real = new LinkedHashSet<>(), wrongType = new LinkedHashSet<>();
            Multimap<String,String> matchMap = ArrayListMultimap.create();
            Map<String, String> foundTypes = new LinkedHashMap<>(), benchmarkTypes = new LinkedHashMap<>();

            //only multi-word are considered
            boolean onlyMW = false;
            //use ignoreSegmentation=true only with onlyMW=true it is not tested otherwise
            boolean ignoreSegmentation = true;
            NameSample sample = sampleStream.read();
            CICTokenizer tokenizer = new CICTokenizer();
            while (sample != null) {
                String[] words = sample.getSentence();
                String sent = "";
                for(String s: words)
                    sent += s+" ";
                sent = sent.substring(0,sent.length()-1);

                Map<String,String> names = new LinkedHashMap<>();
                Span[] nspans = sample.getNames();
                for(Span nspan: nspans) {
                    String n = "";
                    for (int si = nspan.getStart(); si < nspan.getEnd(); si++) {
                        if (si < words.length - 1 && words[si+1].equals("'s"))
                            n += words[si];
                        else
                            n += words[si] + " ";
                    }
                    if(n.endsWith(" "))
                        n = n.substring(0, n.length()-1);
                    if(!onlyMW || n.contains(" "))
                        names.put(n, nspan.getType());
                }
                Pair<Map<Short, Map<String, Double>>, List<Triple<String, Integer, Integer>>> p = seqModel.find(sent);
                Map<String,String> foundSample = new LinkedHashMap<>();
                Map<Short,Map<String,Double>> temp = p.getFirst();
                if(temp!=null)
                    for(Short ct: mappings.keySet()) {
                        String typeText;
                        if(ct==FeatureDictionary.PERSON)
                            typeText = "person";
                        else if(ct == FeatureDictionary.PLACE)
                            typeText = "location";
                        else
                            typeText = "organization";
                        Short[] sts = mappings.get(ct);
                        for(Short st: sts)
                            for(String str: temp.get(st).keySet()) {
                                double s = temp.get(st).get(str);
                                if (s>0 && (!onlyMW || str.contains(" ")))
                                    foundSample.put(str, typeText);
                            }
                    }

                Set<String> foundNames = new LinkedHashSet<>();
                for (Map.Entry<String,String> entry : foundSample.entrySet()) {
                    foundTypes.put(entry.getKey(), entry.getValue());
                    boolean foundEntry = false;
                    String foundType = null;
                    if(!ignoreSegmentation) {
                        foundEntry = names.containsKey(entry.getKey());
                        foundType = names.get(entry.getKey());
                        matchMap.put(entry.getKey(), entry.getKey());
                    }
                    else {
                        for (String name : names.keySet()) {
                            String cname = EmailUtils.uncanonicaliseName(name).toLowerCase();
                            String ek = entry.getKey().toLowerCase();
                            if (cname.equals(ek) || cname.startsWith(ek+" ") || cname.endsWith(" "+ek) || ek.startsWith(cname+" ") || ek.endsWith(" "+cname)) {
                                foundEntry = true;
                                foundType = names.get(name);
                                matchMap.put(entry.getKey(), name);
                                break;
                            }
                        }
                    }

                    if (foundEntry) {
                        if (entry.getValue().equals(foundType)) {
                            numCorrect++;
                            foundNames.add(entry.getKey());
                            correct.add(entry.getKey());
                        } else {
                            wrongType.add(entry.getKey());
                            numWrongType++;
                        }
                    }
                }

                log.info("CIC tokens: "+tokenizer.tokenizeWithoutOffsets(sent,false));
                log.info(temp);
                String fn = "Found names:";
                for (String f : foundNames)
                    fn += f + "[" + foundSample.get(f) + "]" + "--";
                if(fn.endsWith("--"))
                    log.info(fn);

                String extr = "Extra names: ";
                for (String f: foundSample.keySet())
                    if (!matchMap.containsKey(f))
                        extr += f +"[" + foundSample.get(f) + "]--";
                if(extr.endsWith("--"))
                    log.info(extr);
                String miss = "Missing names: ";
                for (String name : names.keySet())
                    if (!matchMap.values().contains(name))
                        miss += name +"[" + names.get(name) + "]--";
                if(miss.endsWith("--"))
                    log.info(miss);

                String misAssign = "Mis-assigned Types: ";
                for(String f: foundSample.keySet())
                    if(matchMap.containsKey(f)) {
                          //this can happen since matchMap is a global var. and an entity that is tagged in one place is untagged in other
                          //if (names.get(matchMap.get(f)) == null)
                          //  log.warn("This is not expected: " + f + " in matchMap not found names -- " + names);
                        if (names.get(matchMap.get(f)) != null && !names.get(matchMap.get(f)).equals(foundSample.get(f)))
                            misAssign += f + "[" + foundSample.get(f) + "] Expected [" + names.get(matchMap.get(f)) + "]--";
                    }
                if(misAssign.endsWith("--"))
                    log.info(misAssign);

                log.info(sent + "\n------------------");
                for(String name: names.keySet())
                    benchmarkTypes.put(name, names.get(name));

                numReal += names.size();
                numFound += foundSample.size();
                real.addAll(names.keySet());
                found.addAll(foundSample.keySet());
                sample = sampleStream.read();
            }
            float prec = (float)correct.size()/(float)found.size();
            float recall = (float)correct.size()/(float)real.size();
            log.info("----Correct names----");
            for(String str: correct)
                log.info(str + " with "+new LinkedHashSet<>(matchMap.get(str)));
            log.info("----Missed names----");
            for(String str: real)
                if(!matchMap.values().contains(str))
                    log.info(str);
            log.info("---Extra names------");
            for(String str: found)
                if(!matchMap.keySet().contains(str))
                    log.info(str);

            log.info("---Assigned wrong type------");
            for(String str: wrongType) {
                Set<String> bMatches = new LinkedHashSet<>(matchMap.get(str));
                for (String bMatch : bMatches) {
                    String ft = foundTypes.get(str);
                    String bt = benchmarkTypes.get(bMatch);
                    if(!ft.equals(bt))
                        log.info(str + "[" + ft + "] expected [" + bt + "]");
                }
            }

            log.info("-------------");
            log.info("Found: "+found.size()+" -- Total: "+real.size()+" -- Correct: "+correct.size()+" -- Missed due to wrong type: "+(wrongType.size()));
            log.info("Precision: "+prec);
            log.info("Recall: "+recall);
            log.info("F1: "+(2*prec*recall/(prec+recall)));
            log.info("------------");
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        //Map<String,String> dbpedia = EmailUtils.readDBpedia(1.0/5);
        String modelFile = SequenceModel.modelFileName;
        if (fdw == null) {
            try {
                fdw = new FileWriter(new File(System.getProperty("user.home") + File.separator + "epadd-settings" + File.separator + "cache" + File.separator + "features.dump"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.err.println("Loading model...");
        SequenceModel nerModel = null;
        try{nerModel = SequenceModel.loadModel(modelFile);}
        catch(IOException e){e.printStackTrace();}
        if(nerModel == null)
            nerModel = train();

        MU mu = nerModel.dictionary.features.get("ltd");
        System.err.println(mu.getLikelihoodWithType(FeatureDictionary.COMPANY));
        System.err.println(mu);
        if (nerModel != null) {
//            nerModel.dictionary.getConditional("Washington University",FeatureDictionary.UNIVERSITY, null);
//            nerModel.dictionary.getConditional("Chartered Bank of India, Australia and China",FeatureDictionary.COMPANY, null);
//            nerModel.dictionary.getConditional("Jadavpur University",FeatureDictionary.UNIVERSITY, null);
//            nerModel.dictionary.getConditional("Beijing Normal University",FeatureDictionary.UNIVERSITY, null);
//            nerModel.dictionary.getConditional("Beijing Arbitration Commission",FeatureDictionary.ORGANISATION, null);
//            nerModel.dictionary.getConditional("Beijing DeTao Masters Academy",FeatureDictionary.UNIVERSITY, null);
//            nerModel.dictionary.getConditional("Southern New England Telecommunciations Corp", FeatureDictionary.COMPANY, null);
//            nerModel.dictionary.getConditional("Southern New England Telecommunciations Corp", FeatureDictionary.PLACE, null);
//            System.err.println(nerModel.seqLabel("Bank of France"));
//            System.err.println(nerModel.seqLabel("Prime Minister"));
//            System.err.println(nerModel.seqLabel("Prime Minister John Oliver"));
//            System.err.println(nerModel.seqLabel("John Oliver"));
//            System.err.println(nerModel.seqLabel("Found Page"));
//            System.err.println(nerModel.seqLabel("Emergency Grant"));
//            System.err.println(nerModel.getConditional("prime",FeatureDictionary.OTHER)+", "+getLikelihoodWithOther("prime",false));
//            System.err.println(nerModel.getConditional("minister",FeatureDictionary.OTHER)+", "+getLikelihoodWithOther("minister",false));
//
            test(nerModel);
        }

    }
}
