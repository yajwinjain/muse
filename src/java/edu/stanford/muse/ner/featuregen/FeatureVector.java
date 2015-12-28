package edu.stanford.muse.ner.featuregen;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.stanford.muse.ner.NER;
import edu.stanford.muse.util.Pair;
import libsvm.svm_node;

public class FeatureVector implements Serializable {
	private static final long	serialVersionUID	= 1L;
	public Double[]					fv;
	//feature name -> feature index in fv vector
	public Map<String, Integer> featureIndices ;
	public Integer NUM_DIM;

	/**
     * @param dictionary feature dictionary
     * @param iType
     * @param fgs feature generators used to generate allFeatures
     * @param allFeatures features generated by various feature generators  */
	public FeatureVector(FeatureDictionary dictionary, Short iType, FeatureGenerator[] fgs, Map<String, List<String>>... allFeatures) {
		List<Double> fvList = new ArrayList<Double>();
		featureIndices = new LinkedHashMap<String, Integer>();
		for(int fi=0;fi<allFeatures.length;fi++) {
			Map<String,List<String>> features = allFeatures[fi];
			if(fgs == null || fi>fgs.length || fgs[fi] == null) {
				NER.log.error("Feature vector not properly initialised as proper feature generators are not supplied");
				return;
			}
			List<Pair<String,Short>> featureTypes = fgs[fi].getFeatureTypes();
			for (Pair<String,Short> ft: featureTypes) {
				String dim = ft.getFirst();
				Short type = ft.getSecond();
				if (features.get(dim) != null) {
					if (type == FeatureDictionary.NOMINAL) {
						double freq = 0, maxfreq = -1, minfreq = 2;
						int numWords = 0;
                        //count corresponding to the maxpfreq
                        double maxcount = 0;
						Pair<Double, Double> p = null;
						for (String val : features.get(dim)) {
							if (dictionary.features.get(dim)!=null && dictionary.features.containsKey(dim)) {
                                p = new Pair<>(0.0, 0.0);
                                FeatureDictionary.MU mu = dictionary.features.get(val);
                                p.first = mu.getLikelihoodWithType(type) * mu.getPrior();
                                p.second = mu.numMixture;
                            }

                            //There can be pairs like <0,0> as we try to emit person like features and organisation like features for an entity
                            if(p!=null && p.second>0) {
								double ratio = (double) p.first / (double) p.second;
								maxfreq = Math.max(maxfreq, ratio);
                                minfreq = Math.min(minfreq, ratio);
                                if(maxfreq==ratio)
                                    maxcount = p.second;
								freq += ratio;
								numWords++;
							}
                            else minfreq = 0;
						}
                        if(numWords == 0 || maxcount == 0) {
                            fvList = null;
                            featureIndices = null;
                            return;
                        }
                        if(numWords>0)
							freq /= numWords;
						featureIndices.put(dim, fvList.size());
						if(!(FeatureDictionary.ORGANISATION == iType))
							fvList.add(freq);
						else {
                            if("words".equals(dim)) {
                                fvList.add(maxfreq);

                                featureIndices.put("freq", fvList.size());
                                //2 million is just a normalizing factor, number of entries in DBpedia instance file
                                //considered logarithm for scaling
                                if(maxcount>0)
                                    fvList.add((double)maxcount/2000000);
                                else
                                    fvList.add(0.0);

                            }
                            else
                                fvList.add(freq);
                        }
					}
					//pass as is
					else {
						if (features.get(dim).size() > 1)
							NER.log.warn("Found list while looking for non-nominal type. Just considering the first element");
						String val = features.get(dim).get(0);
						featureIndices.put(dim, fvList.size());
						fvList.add((double) (val.equals("no") ? -1 : 1));
					}
				}
				else
					fvList.add(0.0);
			}
		}

		NUM_DIM = fvList.size();
		fv = fvList.toArray(new Double[fvList.size()]);
	}

	public svm_node[] getSVMNode() {
        if(fv == null || featureIndices == null)
            return null;
		int numDim = fv.length;
		svm_node[] x = new svm_node[numDim];
		for (int i = 0; i < numDim; i++) {
			x[i] = new svm_node();
			x[i].index = i + 1;
			x[i].value = fv[i];
		}
		return x;
	}

	@Override
	public String toString() {
        if(fv == null || featureIndices == null)
            return null;
		int numDim = fv.length;
		String str = "";
		for (String dim: featureIndices.keySet()) {
			int i = featureIndices.get(dim);
			str += dim + ":" + fv[i];
			if (i < (numDim - 1))
				str += " ";
		}
		return str;
	}

	public String toVector(){
        if(fv == null || featureIndices == null)
            return null;
		String str = "";
		int numDim = fv.length;
		for (int i = 0; i < numDim; i++) {
			str += i + 1 + ":" + fv[i];
			if(i < (numDim - 1))
				str += " ";
		}
		return str;
	}
}
