package edu.stanford.muse.memory;

import edu.stanford.muse.index.EmailDocument;
import edu.stanford.muse.index.Indexer;
import edu.stanford.muse.util.Util;
import edu.stanford.muse.xword.Clue;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Set;

public class MemoryQuestion implements Comparable<MemoryQuestion>, java.io.Serializable {
	private static final long serialVersionUID = 1L;
	public static Log log = LogFactory.getLog(MemoryStudy.class);

	MemoryStudy study; /* link back to full study for details about user id etc */
	
	public Clue clue;
	public String correctAnswer;
	public String type;
	public String lengthDescr;
	public String length;

	public String userAnswerBeforeHint, userAnswer;
	public UserAnswerStats stats;
	
	static public class UserAnswerStats implements java.io.Serializable {
		
		public String uid;
		public int num;
		private static final long serialVersionUID = 1L;
		
		public int nMessagesWithAnswer; // # messages in which the answer appears at least once		
		public long millis = -1; // millisecs from when the question was shown to submit button clicked
		public boolean hintused; // was hint button clicked? (can be set only if hint button was shown, i.e. millis must be > millis to show hint)
		public boolean userAnswerCorrect; // was the answer judged to be correct
		// potentially add edit distance

		int certainty = -1;
		int memoryType = -1;
		public int recency = -1;

		// stats computed when answer is wrong
		public boolean letterCountCorrect; // (only populated if the answer is wrong)
		public boolean userAnswerPartOfAnyAddressBookName; // (only populated if the answer is wrong)
		int wrongAnswerReason = -1; // only populated if the answer is wrong
		public int nMessagesWithUserAnswer = -1; // original content only
		public int userAnswerAssociationWithCorrectAnswer = -1; // # messages in which the user answer and the correct answer appear together (only populated if the answer is wrong)
		public String toString() { return Util.fieldsToString(this, true); }
	}
	
	/** Constructor is used in and designed for MemoryStudy.generateQuestions */
	public MemoryQuestion(MemoryStudy study, String correctAnswer, Clue clue, int times, String lengthString){
		this.study = study;
		this.correctAnswer = correctAnswer;
		this.clue = clue;
		stats = new UserAnswerStats();
		stats.uid = study.stats.userid; // redundant, but useful in the spreadsheet to have uid with every question
		stats.nMessagesWithAnswer = times;
		lengthDescr = lengthString;
	}
	
	public int compareTo(MemoryQuestion other){
		if ((other.clue.clueStats.finalScore - this.clue.clueStats.finalScore) > 0)
			return 1;
		if ((other.clue.clueStats.finalScore - this.clue.clueStats.finalScore) < 0)
			return -1;
		if ((other.clue.clueStats.finalScore - this.clue.clueStats.finalScore) == 0)
			return 0;
		return 0;
	}
	
	public void setWrongAnswerReason(int o) {
		this.stats.wrongAnswerReason = o;
 	}
	
	public void setQuestionNum(int n) { stats.num = n; } 
	
	/** separates out the blanks and inserts first letter of the blanks. e.g. input: "I went to ______", output: "I went to G _ _ _ _ _ _" */

	public Clue getClueToShow() { 
		if (clue != null)
			return clue;
		else
			return null;
	}
	
	public String getPreHintQuestion() {
		String originalClue = clue.getClue();
		// do some slight reformatting... "______ Dumpty sat on a wall" to "_ _ _ _ _ _ Dumpty sat on a wall"
		String correctanswer = this.correctAnswer;
		String blanksToReplace = "", blanksPlusSpace = "";
		for (int i = 0; i < correctanswer.length(); i++){
			blanksPlusSpace = blanksPlusSpace + "_ ";
			blanksToReplace +="_";
		}
		return originalClue.replaceAll(blanksToReplace, blanksPlusSpace);
	}
	
	public void recordUserResponse(String userAnswer, String userAnswerBeforeHint, long millis, boolean hintused, int certainty, int memoryType, int recency) {
		this.userAnswer = userAnswer;
		this.userAnswerBeforeHint = userAnswerBeforeHint;
		
		this.stats.userAnswerCorrect = isUserAnswerCorrect();
		this.stats.certainty = certainty;
		this.stats.memoryType = memoryType;
		this.stats.recency = recency;
		this.stats.hintused = hintused;
		this.stats.millis = millis;

		boolean userAnswerPartOfAnyAddressBookName = study.archive.addressBook.isStringPartOfAnyAddressBookName(userAnswer);
		this.stats.userAnswerPartOfAnyAddressBookName = userAnswerPartOfAnyAddressBookName;
		String cAnswer = Util.canonicalizeSpaces(userAnswer.trim().toLowerCase());
		
		stats.letterCountCorrect = (cAnswer.length() == correctAnswer.length());

		if (!correctAnswer.toLowerCase().equals(cAnswer)) {
			// do further lookups on user answer if its wrong
			try {
				Indexer li = study.archive.indexer;
				Set<EmailDocument> docs = li.luceneLookupDocs("\"" + cAnswer + "\"", edu.stanford.muse.index.Indexer.QueryType.ORIGINAL); // look up inside double quotes since answer may contain blanks
				stats.nMessagesWithUserAnswer = docs.size();
				Set<EmailDocument> correctAnswerDocs = li.luceneLookupDocs("\"" + correctAnswer.toLowerCase() + "\"", edu.stanford.muse.index.Indexer.QueryType.ORIGINAL); // look up inside double quotes since answer may contain blanks
				docs.retainAll(correctAnswerDocs);
				stats.userAnswerAssociationWithCorrectAnswer = docs.size();
			} catch (Exception e) { Util.print_exception("error looking up stats for incorrect answer", e, log); }			
		} 
	}
	
	public String getPostHintQuestion() {
		String correctanswer = this.correctAnswer;
		String hint = Character.toString(correctanswer.charAt(0));
		hint += " ";
		String blanksToReplace = "_";
		for (int i = 1; i < correctanswer.length(); i++){
			hint = hint + "_ ";
			blanksToReplace +="_";
		}
		return clue.getClue().replaceAll(blanksToReplace, hint);	
	}

	/** case and space normalize first */
	public String normalizeAnswer(String s) {
		return (s == null) ? "" : s.toLowerCase().replaceAll("\\s", "");
	}
	
	public boolean isUserAnswerCorrect() {
		String s1 = normalizeAnswer(userAnswer);
		String s2 = normalizeAnswer(correctAnswer);
		return s1.equals(s2);
	}
	
	public String getCorrectAnswer() { 
		if (correctAnswer != null)
			return correctAnswer;
		else
			return null;
	}
	
	public String getUserAnswer(){
		if (userAnswer != null)
			return userAnswer;
		else
			return null;
	}
	
	public String detailsToHTMLString()
	{
		return Util.fieldsToString(clue.clueStats) + stats.nMessagesWithAnswer + "," + Util.fieldsToString(stats);
	}
	
	public String toString() { 	return Util.fieldsToString(this, false) + " " + Util.fieldsToString(stats, false); }

}