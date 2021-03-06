package edu.stanford.muse.xword;

import edu.stanford.muse.email.Contact;
import edu.stanford.muse.index.Archive;
import edu.stanford.muse.index.IndexUtils;
import edu.stanford.muse.ner.model.NERModel;
import edu.stanford.muse.util.DictUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

/**
 * Created by vihari on 21/12/15.
 * Just like ClueEvaluator but decides on if a clue answer combination is allowed.
 */
public class ClueFilter {
    public static Log log						= LogFactory.getLog(ClueFilter.class);
    public static List<ClueFilter> getDefaultFilters(ArchiveCluer.QuestionType mode){
        List<ClueFilter> filters = new ArrayList<>();
        if(mode== ArchiveCluer.QuestionType.GUESS_CORRESPONDENT) {
            filters.add(new AnswerFilter());
            filters.add(new TextFilter());
        }
        filters.add(new SelfFilter());
        return filters;
    }

    public boolean filter(Clue clue, ArchiveCluer.QuestionType mode, String answer, Date startDate, Date endDate, Set<String> tabooNamesSet, NERModel nerModel, Archive archive){
        return true;
    }

    /**
     * Checks if the answer looks valid
     * mode: 1 - if the name really looks like a person name*/
    public static class AnswerFilter extends ClueFilter{
        @Override
        public boolean filter(Clue clue, ArchiveCluer.QuestionType mode, String answer, Date startDate, Date endDate, Set<String> tabooNamesSet, NERModel nerModel, Archive archive) {
            if(DictUtils.hasDictionaryWord(answer)) {
                log.info("Filtering "+answer+" based on dictionary");
                return false;
            }
            String[] badsubs = new String[]{"@","http:","www.","AM","PM"," list"};
            for(String bs: badsubs)
                if(answer.contains(bs)) {
                    log.info("Rejecting "+answer+" because of bad sub-string "+bs);
                    return false;
                }
            return true;
        }
    }

    /**
     * Checks if the answer is not one of the self name*/
    public static class SelfFilter extends ClueFilter{
        @Override
        public boolean filter(Clue clue, ArchiveCluer.QuestionType mode, String answer, Date startDate, Date endDate, Set<String> tabooNamesSet, NERModel nerModel, Archive archive) {
            Contact sc = archive.addressBook.getContactForSelf();
            return !sc.names.contains(answer);
        }
    }

    /**
     * Checks if the clue text does not contains any give aways*/
    public static class TextFilter extends ClueFilter{
        @Override
        public boolean filter(Clue clue, ArchiveCluer.QuestionType mode, String answer, Date startDate, Date endDate, Set<String> tabooNamesSet, NERModel nerModel, Archive archive){
            String s = clue.fullSentenceOriginal.toLowerCase();
            Set<String> answers = new LinkedHashSet<>();
            answers.add(answer.toLowerCase());
            //for this type of question, the answer is the correspondent name
            if(mode == ArchiveCluer.QuestionType.GUESS_CORRESPONDENT){
                Contact c = archive.addressBook.lookupByName(answer);
                if(c!=null && c.names!=null)
                    for(String n: c.names)
                        answers.add(n.toLowerCase());
            }
            Set<String> giveAways = IndexUtils.computeAllSubstrings(answers);
            //dont want to miss things like "hi vishal!"
            String[] tokens = s.split("\\W+");
            for(String tok: tokens)
                if(tok.length()>=3 && giveAways.contains(tok.toLowerCase())){
                    log.info("Rejecting answer: " + answer + "\nclue: " + s);
                    return false;
                }

            return true;
        }
    }

    /**
     * Checks for self instance in the clue text*/
    public static class SelfTextFilter extends ClueFilter{
        @Override
        public boolean filter(Clue clue, ArchiveCluer.QuestionType mode, String answer, Date startDate, Date endDate, Set<String> tabooNamesSet, NERModel nerModel, Archive archive){
            String s = clue.fullSentenceOriginal.toLowerCase();
            Contact c = archive.addressBook.getContactForSelf();
            Set<String> snames = c.names;
            Set<String> all = new LinkedHashSet<>();
            for(String sname: snames)
                all.addAll(IndexUtils.computeAllSubstrings(sname.toLowerCase()));
            String[] tokens = s.split("\\W+");
            for(String tok: tokens)
                if(tok.length()>2 && all.contains(tok)) {
                    log.info("Rejecting answer: " + answer + "\nclue: " + s);
                    return false;
                }
            return true;
        }
    }
}
