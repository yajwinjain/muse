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
	public FeatureVector(FeatureDictionary dictionary, String iType, FeatureGenerator[] fgs, Map<String, List<String>>... allFeatures) {
		List<Double> fvList = new ArrayList<Double>();
		featureIndices = new LinkedHashMap<String, Integer>();
		for(int fi=0;fi<allFeatures.length;fi++) {
			Map<String,List<String>> features = allFeatures[fi];
			if(fgs == null || fi>fgs.length || fgs[fi] == null) {
				NER.log.error("Word feature vector not properly initialised as proper feature generators are not supplied");
				return;
			}
			List<Pair<String,Short>> featureTypes = fgs[fi].getFeatureTypes();
			for (Pair<String,Short> ft: featureTypes) {
				String dim = ft.getFirst();
				Short type = ft.getSecond();
				if (features.get(dim) != null) {
					if (type == FeatureDictionary.NOMINAL) {
						double freq = 0, maxfreq = -1;
						int numWords = 0;
						Pair<Integer, Integer> p = null;
						for (String val : features.get(dim)) {
							if (dictionary.features.get(dim)!=null && dictionary.features.get(dim).containsKey(val))
								p = dictionary.features.get(dim).get(val).get(iType);
							if(p!=null) {
								double ratio = (double) p.first / (double) p.second;
								maxfreq = Math.max(maxfreq, ratio);
								freq += ratio;
								numWords++;
							}
						}
						if(numWords>0)
							freq /= numWords;
						featureIndices.put(dim, fvList.size());
						if(!FeatureDictionary.ORGANISATION.equals(iType))
							fvList.add(freq);
						else
							fvList.add(freq);
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
