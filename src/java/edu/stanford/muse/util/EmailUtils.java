/*
 * Copyright (C) 2012 The Stanford MobiSocial Laboratory
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.stanford.muse.util;

import edu.stanford.muse.datacache.Blob;
import edu.stanford.muse.datacache.BlobStore;
import edu.stanford.muse.email.*;
import edu.stanford.muse.index.*;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.net.QuotedPrintableCodec;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class EmailUtils {
	public static Log					log				= LogFactory.getLog(EmailUtils.class);
	public static Map<String, String>	dbpedia			= null;
	public static long					MILLIS_PER_DAY	= 1000L * 3600 * 24;

    static class DBpediaTypes {
        //these are types identified from DBpedia that may contain some predictable tokens and omitting types with any possible tokens like TVShows and Bands
        //also omitting types that are not very different from other types like, Company and AutomobileEngine|Device
        public static List<String> allowedTypes = Arrays.asList(
                "Settlement|PopulatedPlace|Place",
                "Person|Agent",
                "Village|Settlement|PopulatedPlace|Place",
                "SoccerPlayer|Athlete|Person|Agent",
                "Company|Organisation|Agent",
                "Town|Settlement|PopulatedPlace|Place",
                "Building|ArchitecturalStructure|Place",
                "MusicalArtist|Artist|Person|Agent",
                "AdministrativeRegion|PopulatedPlace|Place",
                "OfficeHolder|Person|Agent",
                "School|EducationalInstitution|Organisation|Agent",
                "River|Stream|BodyOfWater|NaturalPlace|Place",
                "MilitaryPerson|Person|Agent",
                "Station|Infrastructure|ArchitecturalStructure|Place",
                "BaseballPlayer|Athlete|Person|Agent",
                "Road|RouteOfTransportation|Infrastructure|ArchitecturalStructure|Place",
                "City|Settlement|PopulatedPlace|Place",
                "SoccerClub|SportsTeam|Organisation|Agent",
                "University|EducationalInstitution|Organisation|Agent",
                "Fish|Animal|Eukaryote|Species",
                "Writer|Artist|Person|Agent",
                "MilitaryUnit|Organisation|Agent",
                "Scientist|Person|Agent",
                "SoccerManager|Person|Agent",
                "Mountain|NaturalPlace|Place",
                "Airport|Infrastructure|ArchitecturalStructure|Place",
                "AmericanFootballPlayer|GridironFootballPlayer|Athlete|Person|Agent",
                "IceHockeyPlayer|Athlete|Person|Agent",
                "Cricketer|Athlete|Person|Agent",
                "Athlete|Person|Agent",
                "Lake|BodyOfWater|NaturalPlace|Place",
                "RugbyPlayer|Athlete|Person|Agent",
                "Politician|Person|Agent",
                "Artist|Person|Agent",
                "Stadium|Building|ArchitecturalStructure|Place",
                "GridironFootballPlayer|Athlete|Person|Agent",
                "ProtectedArea|Place",
                "HistoricPlace|Place",
                "Organisation|Agent",
                "CollegeCoach|Person|Agent",
                "Drug",
                "SoccerClubSeason|SportsTeamSeason|Organisation|Agent",
                "ComicsCharacter|FictionalCharacter|Person|Agent",
                "FictionalCharacter|Person|Agent",
                "MemberOfParliament|Politician|Person|Agent",
                "Newspaper|PeriodicalLiterature|WrittenWork|Work",
                "AcademicJournal|PeriodicalLiterature|WrittenWork|Work",
                "Election|Event",
                "Animal|Eukaryote|Species",
                "HistoricBuilding|Building|ArchitecturalStructure|Place",
                "ChristianBishop|Cleric|Person|Agent",
                "Magazine|PeriodicalLiterature|WrittenWork|Work",
                "Cyclist|Athlete|Person|Agent",
                "PoliticalParty|Organisation|Agent",
                "Island|PopulatedPlace|Place",
                "Museum|Building|ArchitecturalStructure|Place",
                "Bridge|RouteOfTransportation|Infrastructure|ArchitecturalStructure|Place",
                "Airline|Company|Organisation|Agent",
                "Non-ProfitOrganisation|Organisation|Agent",
                "Country|PopulatedPlace|Place",
                "GaelicGamesPlayer|Athlete|Person|Agent",
                "TennisPlayer|Athlete|Person|Agent",
                "GovernmentAgency|Organisation|Agent",
                "Saint|Cleric|Person|Agent",
                "MartialArtist|Athlete|Person|Agent",
                "Website|Work",
                "BasketballPlayer|Athlete|Person|Agent",
                "Congressman|Politician|Person|Agent",
                "GolfPlayer|Athlete|Person|Agent",
                "Governor|Politician|Person|Agent",
                "FigureSkater|Athlete|Person|Agent",
                "ComicsCreator|Artist|Person|Agent",
                "Boxer|Athlete|Person|Agent",
                "RecordLabel|Company|Organisation|Agent",
                "Monarch|Person|Agent",
                "President|Politician|Person|Agent",
                "ShoppingMall|Building|ArchitecturalStructure|Place",
                "RailwayLine|RouteOfTransportation|Infrastructure|ArchitecturalStructure|Place",
                "Hospital|Building|ArchitecturalStructure|Place",
                "AustralianRulesFootballPlayer|Athlete|Person|Agent",
                "FootballMatch|SportsEvent|Event",
                "Wrestler|Athlete|Person|Agent",
                "AnatomicalStructure",
                "MountainRange|NaturalPlace|Place",
                "PowerStation|Infrastructure|ArchitecturalStructure|Place",
                "AdultActor|Actor|Artist|Person|Agent",
                "Judge|Person|Agent",
                "Award",
                "LunarCrater|NaturalPlace|Place",
                "PrimeMinister|Politician|Person|Agent",
                "TradeUnion|Organisation|Agent",
                "Park|ArchitecturalStructure|Place",
                "Criminal|Person|Agent",
                "Lighthouse|Building|ArchitecturalStructure|Place",
                //"PublicTransitSystem|RouteOfTransportation|Infrastructure|ArchitecturalStructure|Place",
                "Model|Person|Agent",
                "Philosopher|Person|Agent",
                "Architect|Person|Agent",
                "BroadcastNetwork|Broadcaster|Organisation|Agent",
                "WorldHeritageSite|Place",
                "Mayor|Politician|Person|Agent",
                "WrestlingEvent|SportsEvent|Event",
                "PlayboyPlaymate|Person|Agent",
                "Senator|Politician|Person|Agent",
                "Comedian|Artist|Person|Agent",
                "Hotel|Building|ArchitecturalStructure|Place",
                "MusicFestival|Event",
                "Theatre|Building|ArchitecturalStructure|Place",
                "FormulaOneRacer|Athlete|Person|Agent",
                "Actor|Artist|Person|Agent",
                "Volcano|NaturalPlace|Place",
                "Legislature|Organisation|Agent",
                "NascarDriver|Athlete|Person|Agent",
                "BasketballTeam|SportsTeam|Organisation|Agent",
                //697 MixedMartialArtsEvent|SportsEvent|Event
                "Place",
                "Astronaut|Person|Agent",
                "Library|EducationalInstitution|Organisation|Agent|Building|ArchitecturalStructure|Place",
                "PokerPlayer|Person|Agent",
                "NationalCollegiateAthleticAssociationAthlete|Athlete|Person|Agent",
                "SkiArea|Place",
                "Brain|AnatomicalStructure",
                "Cardinal|Cleric|Person|Agent",
                "Convention|Event",
                "SiteOfSpecialScientificInterest|Place",
                "Race|SportsEvent|Event",
                "SpaceMission|Event",
                "Restaurant|Building|ArchitecturalStructure|Place",
                "Bone|AnatomicalStructure",
                "FilmFestival|Event",
                "BadmintonPlayer|Athlete|Person|Agent",
                "LawFirm|Company|Organisation|Agent",
                "Ambassador|Person|Agent",
                "Artery|AnatomicalStructure",
                "Nerve|AnatomicalStructure",
                "WineRegion|Place",
                "Muscle|AnatomicalStructure",
                //"BasketballLeague|SportsLeague|Organisation|Agent",
                "Canal|Stream|BodyOfWater|NaturalPlace|Place",
                "SnookerPlayer|Athlete|Person|Agent",
                "Cave|NaturalPlace|Place",
                "Vein|AnatomicalStructure",
                "Embryology|AnatomicalStructure",
                //"IceHockeyLeague|SportsLeague|Organisation|Agent",
                //176 BaseballLeague|SportsLeague|Organisation|Agent
                //173 SportsLeague|Organisation|Agent
                //169 SoccerLeague|SportsLeague|Organisation|Agent
                //156 Sport|Activity
                //126 RugbyLeague|SportsLeague|Organisation|Agent
                "Valley|NaturalPlace|Place",
                "Chancellor|Politician|Person|Agent",
                "Lymph|AnatomicalStructure",
                //79 SpeedwayTeam|SportsTeam|Organisation|Agent
                "LaunchPad|Infrastructure|ArchitecturalStructure|Place",
                "College|EducationalInstitution|Organisation|Agent",
                //69 AmericanFootballLeague|SportsLeague|Organisation|Agent
                //61 HockeyTeam|SportsTeam|Organisation|Agent
                "Olympics|SportsEvent|Event",
                "WomensTennisAssociationTournament|SportsEvent|Event",
                //54 YearInSpaceflight|Event
                //52 VolleyballLeague|SportsLeague|Organisation|Agent
                //SpaceStation|MeanOfTransportation
                //30 Tunnel|RouteOfTransportation|Infrastructure|ArchitecturalStructure|Place
                "Stream|BodyOfWater|NaturalPlace|Place",
                "SnookerChamp|SnookerPlayer|Athlete|Person|Agent",
                //MotorcycleRacingLeague|SportsLeague|Organisation|Agent
                "SpaceShuttle|MeanOfTransportation",
                //FieldHockeyLeague|SportsLeague|Organisation|Agent
                //CanadianFootballTeam|SportsTeam|Organisation|Agent
                //WaterwayTunnel|Tunnel|RouteOfTransportation|Infrastructure|ArchitecturalStructure|Place
//        18 LacrosseLeague|SportsLeague|Organisation|Agent
//        18 InlineHockeyLeague|SportsLeague|Organisation|Agent
//        17 SoftballLeague|SportsLeague|Organisation|Agent
                "BodyOfWater|NaturalPlace|Place",
                //HandballLeague|SportsLeague|Organisation|Agent
                "Continent|PopulatedPlace|Place",
                //"AutoRacingLeague|SportsLeague|Organisation|Agent",
                "Journalist|Person|Agent",
                //9 GolfLeague|SportsLeague|Organisation|Agent
                //9 CricketLeague|SportsLeague|Organisation|Agent
                //8 PoloLeague|SportsLeague|Organisation|Agent
                //6 TennisLeague|SportsLeague|Organisation|Agent
                "HumanGene|Gene|Biomolecule",
                //VideogamesLeague|SportsLeague|Organisation|Agent
                //SpeedwayLeague|SportsLeague|Organisation|Agent
                "Monument|Place",
                "HumanGeneLocation|GeneLocation",
                //CanadianFootballLeague|SportsLeague|Organisation|Agent
                //AustralianFootballLeague|SportsLeague|Organisation|Agent
                "MouseGene|Gene|Biomolecule",
                "MouseGeneLocation|GeneLocation",
                //"AmericanFootballTeam|SportsTeam|Organisation|Agent
//        "URI:|URI:
//                2 Ginkgo|Plant|Eukaryote|Species
                "Skyscraper|Building|ArchitecturalStructure|Place"
//        1 MixedMartialArtsLeague|SportsLeague|Organisation|Agent
        );
    }

	public static String tabooEmailNames[] = new String[]{"paypal member", "info@evite.com", "evite.com"}; // could consider adding things ending in clients, members, etc.
	/**
	 * best effort to toString something about the given message.
	 * use only for diagnostics, not for user-visible messages.
	 * treads defensively, this can be called to report on a badly formatted message.
	 */
	public static String formatMessageHeader(MimeMessage m) throws MessagingException, AddressException
	{
		StringBuilder sb = new StringBuilder();
		sb.append("To: ");
		try {
			Address[] tos = m.getAllRecipients();
			for (Address a : tos)
				sb.append(a.toString() + " ");
			sb.append("\n");
		} catch (Exception e) {
		}

		sb.append("From: ");
		try {
			Address[] froms = m.getFrom();
			for (Address a : froms)
				sb.append(a.toString() + " ");
		} catch (Exception e) {
		}

		try {
			sb.append("Subject: " + m.getSubject());
			sb.append("Message-ID: " + m.getMessageID());
		} catch (Exception e) {
		}
		return sb.toString();
	}

	// clean the dates
	public static void cleanDates(Collection<? extends Document> docs)
	{
		Date now = new Date();
		long time = now.getTime() + 1000 * 24 * 60 * 60L; // allow 1 day after current time 
		Date cutoff = new Date(time);

		Date earliestDate = new Date();
		Date latestDate = null;

		// compute earliest and latest dates. ignore dates after cutoff
		for (Document d : docs)
		{
			if (d instanceof DatedDocument)
			{
				DatedDocument dd = (DatedDocument) d;
				if (dd.date == null || dd.date.after(cutoff))
					continue;

				if (dd.date.before(earliestDate))
					earliestDate = dd.date;

				if (latestDate == null)
					latestDate = dd.date;
				else if (dd.date.after(latestDate))
					latestDate = dd.date;
			}
		}

		for (Document d : docs)
		{
			if (d instanceof DatedDocument)
			{
				DatedDocument dd = (DatedDocument) d;
				if (dd.date == null)
					dd.date = earliestDate;
				if (dd.date.after(cutoff))
				{
					log.warn("Warning: date is beyond current time " + CalendarUtil.formatDateForDisplay(dd.date) + ", changing to last reasonable date, " + CalendarUtil.formatDateForDisplay(latestDate));
					dd.date = latestDate;
				}
			}
		}
	}

	// strips the Re: of subjects
	public static String normalizedSubject(String subject)
	{
		String originalSubject = subject.toLowerCase();
		String result = originalSubject;
		// Strip all variations of re: Re: RE: etc... by converting subject to lowercase
		// but don't lose case on original subject
		subject = subject.toLowerCase();

		if (subject.startsWith("re ") && subject.length() > "re ".length())
			result = originalSubject.substring("re ".length());
		if (subject.startsWith("re: ") && subject.length() > "re: ".length())
			result = originalSubject.substring("re: ".length());
		return result;
	}

	public static String emailAddrsToString(String[] addrs)
	{
		StringBuilder sb = new StringBuilder(addrs.length + " addresses: ");
		for (String s : addrs)
			sb.append(s + " ");
		return sb.toString();
	}

	// removes re: fwd etc from a subject line
	public static String cleanupSubjectLine(String subject)
	{
		if (subject == null)
			return null;
		// first strip mailing list name if any, at the beginning of the subject,
		// otherwise terms like [prpl-devel] appear very frequently
		StringTokenizer st = new StringTokenizer(subject);
		String firstToken = "";

		// firstToken is empty or the first non [mailing-list] first token
		while (st.hasMoreTokens())
		{
			firstToken = st.nextToken();
			String f = firstToken.toLowerCase();
			// these are the common patterns i've seen in subject lines
			if ("re".equals(f) || "re:".equals(f) || "fwd".equals(f) || "fwd:".equals(f) || "[fwd".equals(f) || "[fwd:".equals(f))
				continue;
			// strip mailing list stuff as well
			if (f.startsWith("[") && f.endsWith("]") && f.length() < Indexer.MAX_MAILING_LIST_NAME_LENGTH)
				continue;

			break;
		}

		// firstToken is empty or the first non [mailing-list], non re/fwd/etc token
		String result = firstToken + " ";
		while (st.hasMoreTokens())
			result += st.nextToken() + " ";

		return result;
	}

	/** returns just the first message for each thread */
	public static List<EmailDocument> threadHeaders(List<EmailDocument> emails)
	{
		// map maps normalized subject to a list of threads that all have that thread
		Map<String, List<EmailThread>> map = new LinkedHashMap<String, List<EmailThread>>();

		for (EmailDocument email : emails)
		{
			String normalizedSubject = normalizedSubject(email.getSubjectWithoutTitle());

			List<EmailThread> threads = map.get(normalizedSubject);
			if (threads == null)
			{
				threads = new ArrayList<EmailThread>();
				map.put(normalizedSubject, threads);
			}

			EmailThread existingThread = null;
			for (EmailThread thread : threads)
			{
				if (thread.belongsToThisThread(email))
				{
					existingThread = thread;
					break;
				}
			}

			if (existingThread != null)
				existingThread.addMessage(email);
			else
				threads.add(new EmailThread(email, normalizedSubject)); // new emailthread based on this message
		}

		System.out.println(map.keySet().size() + " unique subjects");

		List<EmailDocument> result = new ArrayList<EmailDocument>();

		for (String normalizedSubject : map.keySet())
		{
			List<EmailThread> threads = map.get(normalizedSubject);
			System.out.print("Subject: " + normalizedSubject.replaceAll("\n", "") + ": ");
			System.out.print(threads.size() + " thread(s), : ");
			for (EmailThread et : threads)
			{
				System.out.print(et.size() + " ");
				result.add(et.emails.get(0));
			}
			System.out.println();
		}

		System.out.println(emails.size() + " emails reduced to " + result.size() + " threads");

		return result;
	}

	//	From - Tue Sep 29 11:38:30 2009
	static SimpleDateFormat	sdf1	= new SimpleDateFormat("EEE MMM dd hh:mm:ss yyyy");
	// Date: Wed, 2 Apr 2003 11:53:17 -0800 (PST)
	static SimpleDateFormat	sdf2	= new SimpleDateFormat("EEE, dd MMM yyyy hh:mm:ss");
	static Base64			base64encoder;
	public static Random	rng		= new Random(0);

	static {
		// if file separator is /, then newline must be \n, otherwise \r\n
		byte b[] = "/".equals(File.separator) ? new byte[] { (byte) 10 } : new byte[] { (byte) 13, (byte) 10 };
		base64encoder = new Base64(76, b);
	}

	public static void printHeaderToMbox(EmailDocument ed, PrintWriter mbox) throws IOException, GeneralSecurityException
	{
		/* http://www.ietf.org/rfc/rfc1521.txt is the official ref. */
		Date d = ed.date != null ? ed.date : new Date();
		String s = sdf1.format(d);
		mbox.println("From - " + s);
		mbox.println("Date: " + sdf2.format(d) + " +0000 GMT"); // watch out, this might not be the right date format
		mbox.println("From: " + ed.getFromString());
		mbox.println("To: " + ed.getToString());
		String cc = ed.getCcString();
		if (!Util.nullOrEmpty(cc))
			mbox.println("Cc: " + cc);
		String bcc = ed.getBccString();
		if (!Util.nullOrEmpty(bcc))
			mbox.println("Bcc: " + bcc);

		mbox.println("Subject: " + ed.description);
		mbox.println("Message-ID: " + ed.messageID);
		mbox.println("X-Muse-Folder: " + ed.folderName);
		if (!Util.nullOrEmpty(ed.comment))
		{
			String comment = ed.comment;
			comment = comment.replaceAll("\n", " ");
			comment = comment.replaceAll("\r", " ");
			mbox.println("X-Muse-Comment: " + comment);
		}
		if (ed.isLiked())
			mbox.println("X-Muse-Liked: 1");
	}

	public static void dumpMessagesToDir(Archive archive, Collection<EmailDocument> docs, String dir) throws IOException, GeneralSecurityException, ClassCastException, ClassNotFoundException
	{
		File f = new File(dir);
		f.mkdirs();
		int i = 0;
		NER.initialize();
		for (EmailDocument ed : docs)
		{
			try {
				PrintWriter pw = new PrintWriter(new FileOutputStream(dir + File.separatorChar + i + ".full"));
				String m = ed.description + "\n\n" + archive.getContents(ed, false /* full message */);
				pw.println(m);
				pw.close();

				pw = new PrintWriter(new FileOutputStream(dir + File.separatorChar + i + ".names"));

				NERTokenizer t = (NERTokenizer) NER.parse(m);
				String s = "";
				while (t.hasMoreTokens())
				{
					Pair<String, String> tokenAndType = t.nextTokenAndType();
					String token = tokenAndType.getFirst().trim().toLowerCase();
					// drop dictionary words
					if (DictUtils.fullDictWords.contains(token))
						continue;
					// drop "terms" with | -- often used as a separator on the web, and totally confuses our indexer's phrase lookup
					if (token.indexOf("|") >= 0)
						continue;
					s += token.replaceAll(" ", "_") + " ";
				}
				pw.println(s);
				pw.close();
			} catch (Exception e)
			{
				log.warn("Unable to save contents of message: " + ed);
				Util.print_exception(e, log);
			}
			i++;
		}
	}

	/*
	 * header is printed in mbox format, then each of the given contents sequentially
	 * if blobStore is null, attachments are not printed. could make this better by allowing text/html attachments.
	 */
	public static void printToMbox(Archive archive, EmailDocument ed, PrintWriter mbox, BlobStore blobStore, boolean stripQuoted)
	{
		String contents = "";
		try {
			printHeaderToMbox(ed, mbox);
			contents = archive.getContents(ed, stripQuoted);
			printBodyAndAttachmentsToMbox(contents, ed, mbox, blobStore);
		} catch (Exception e) {
			Util.print_exception(e, log);
		}
	}

	public static void printBodyAndAttachmentsToMbox(String contents, EmailDocument ed, PrintWriter mbox, BlobStore blobStore) throws IOException, GeneralSecurityException
	{
		String frontier = "----=_Part_";
		List<Blob> attachments = null;
		if (ed != null)
			attachments = ed.attachments;
		boolean hasAttachments = !Util.nullOrEmpty(attachments) && blobStore != null;
		boolean isI18N = Util.isI18N(contents);

		if (!hasAttachments && !isI18N)
		{
			mbox.println();
			mbox.println(contents);
			mbox.println();
		}
		else
		{
			/*
			 * This is a multi-part message in MIME format.
			 * 
			 * ------=_Part_
			 * Content-Type: text/plain;
			 * charset="iso-8859-1"
			 * Content-Transfer-Encoding: 7bit
			 */

			mbox.println("Content-Type: multipart/mixed; boundary=\"" + frontier + "\"\n"); // blank line

			mbox.println("This is a multi-part message in MIME format.\n"); // blank line

			mbox.println("--" + frontier);
			mbox.println("Content-Type: text/plain; charset=\"UTF-8\"");
			mbox.println("Content-Encoding: quoted-printable\n"); // need blank line after this
			try {
				byte encodedBytes[] = QuotedPrintableCodec.decodeQuotedPrintable(contents.getBytes());
				for (byte by : encodedBytes)
					mbox.print((char) by);
				mbox.println();
			} catch (DecoderException de) {
				log.warn("Exception trying to toString contents!" + de);
				mbox.println(contents);
				mbox.println();
			}
//			mbox.println("--" + frontier + "--");

			// probably need to fix: other types of charset, encodings
			if (blobStore != null)
			{
				for (Blob b : attachments)
				{
					mbox.println("--" + frontier);
					mbox.println("Content-type: " + b.contentType);
					mbox.println("Content-transfer-encoding: base64");
					mbox.println("Content-Disposition: attachment;filename=\"" + b.filename + "\"\n");

					byte bytes[] = blobStore.getDataBytes(b);
					byte encodedBytes[] = Base64.encodeBase64(bytes, true);
					for (byte by : encodedBytes)
						mbox.print((char) by);
				}
				// note: the --frontier-- line is needed only at the very end, after all attachments -- NOT after each attachment.
				// this used to be a bug.
				mbox.println("--" + frontier + "--\n");
			}
		}
	}

	/** gets the part before @ in the address */
	public static String getLoginFromEmailAddress(String emailAddress)
	{
		if (emailAddress == null)
			return null;
		int idx = emailAddress.indexOf("@");
		if (idx < 0)
			return emailAddress;
		return emailAddress.substring(0, idx);
	}

	private static Pattern	parensPattern		= Pattern.compile("\\(.*\\)");
	private static Pattern	sqBracketsPattern	= Pattern.compile("\\[.*\\]");

	// hopefully people don't have any of these words in their names... note, should be lowercase

	/**
	 * normalizes the given person name.
	 * strips whitespace at either end
	 * returns null if not a valid name
	 */
	public static String cleanPersonName(String name)
	{
		if (name == null)
			return null;
		if (name.indexOf("@") >= 0) // an email addr, not a real name -- we dunno what's happening, just return it as is
			return name.toLowerCase();
		if ("user".equals(name))
			return null;

		// a surprising number of names in email headers start and end with a single quote
		// if so, strip it.
		if (name.startsWith("'") && name.endsWith("'"))
			name = name.substring(1, name.length() - 1);

		// Strip stuff inside parens, e.g. sometimes names are like:
		// foo bar (at home) - or -
		// foo bar [some Dept]
		Matcher m1 = parensPattern.matcher(name);
		name = m1.replaceAll("");
		Matcher m2 = sqBracketsPattern.matcher(name);
		name = m2.replaceAll("");
		name = name.trim();

		// normalize spaces
		// return null if name has banned words - e.g. ben shneiderman's email has different people with the "name" (IPM Return requested)
		String s = "";
		StringTokenizer st = new StringTokenizer(name);
		while (st.hasMoreTokens())
		{
			String t = st.nextToken();
			if (DictUtils.bannedWordsInPeopleNames.contains(t.toLowerCase()))
				return null;
			s += t + " ";
		}

		s = s.trim(); // very important, we've added a space after the last token above

		for (String bannedString : DictUtils.bannedStringsInPeopleNames)
			if (name.toLowerCase().indexOf(bannedString) >= 0)
				return null;

		return s;
	}

	// removes dups from the input list
	public static List<String> removeMailingLists(List<String> in)
	{
		List<String> result = new ArrayList<String>();
		for (String s : in)
		{
			s = s.toLowerCase();
			if (s.indexOf("@yahoogroups") >= 0 || s.indexOf("@googlegroups") >= 0 || s.indexOf("@lists.") >= 0 || s.indexOf("@mailman") >= 0 || s.indexOf("no-reply") >= 0 || s.indexOf("do-not-reply") >= 0)
			{
				log.debug("Dropping mailing list or junk address: " + s);
				continue;
			}
			result.add(s);
		}
		return result;
	}

	// removes obviously bad names from the input list
	public static List<String> removeIncorrectNames(List<String> in)
	{

		List<String> result = new ArrayList<String>();
		for (String s : in)
		{
			s = EmailUtils.cleanPersonName(s);
			if (s.startsWith("undisclosed-recipients"))
			{
				log.info("Dropping undisclosed recipients: " + s);
				continue;
			}

			for (String taboo: tabooEmailNames) {
				if (taboo.equals(s))
					continue;
			}
			if (s.toLowerCase().equals("user"))
			{
				log.info("Dropping bad name: " + s);
				continue;
			}
			result.add(s);
		}
		return result;
	}

	/** try to get the last name */
	public static String getLastName(String fullName)
	{
		StringTokenizer st = new StringTokenizer(fullName);
		String lastToken = null;
		int nRealTokens = 0;
		while (st.hasMoreTokens())
		{
			String token = st.nextToken();
			if ("jr".equalsIgnoreCase(token))
				continue;
			// i don't actually know anyone with these suffixes, but in honor william gates III
			if ("i".equalsIgnoreCase(token) || "ii".equalsIgnoreCase(token) || "iii".equalsIgnoreCase(token) || "iv".equalsIgnoreCase(token) || "v".equalsIgnoreCase(token))
				continue;
			lastToken = token;
			nRealTokens++;
		}

		if (nRealTokens < 2)
			return null; // not two names
		if (lastToken.length() < 2)
			return null; // don't know what is happening
		return lastToken;
	}

	private final static Set<String>	secondLevelDomains		= new LinkedHashSet<String>();
	private final static String[]		secondLevelDomainsArray	= new String[] { "ac", "co" };
	private final static String[]		serviceProvidersArray	= new String[] { "hotmail", "gmail", "yahoo", "live", "msn", "pacbell", "vsnl", "comcast", "rediffmail" };
	private final static Set<String>	serviceProviders		= new LinkedHashSet<String>();
	static {
		for (String s : secondLevelDomainsArray)
			secondLevelDomains.add(s);
		for (String s : serviceProvidersArray)
			serviceProviders.add(s);
	}

	/** try to get the last name */
	public static String getOrg(String email)
	{
		if (!email.contains("@"))
			return null;

		StringTokenizer st = new StringTokenizer(email, "@. !");
		List<String> tokens = new ArrayList<String>();

		while (st.hasMoreTokens())
			tokens.add(st.nextToken());
		if (tokens.size() < 3)
			return null; // must have at least 3 tokens : a@b.org
		String org = tokens.get(tokens.size() - 2).toLowerCase();
		if (secondLevelDomains.contains(org))
			org = tokens.get(tokens.size() - 3);
		if (serviceProviders.contains(org))
			return null;

		return org;
	}

	public static void main1(String args[]) throws IOException
	{
		testGetOriginalContent();

		String test[] = new String[] { "a@abc.com", "b@b@b.com", "c@cambridge.ac.uk", "d@hotmail.com", "e#live.com", "e@live.com" };
		for (String s : test)
			System.out.println(s + " org is: " + getOrg(s));
		String test1[] = new String[] { "bill gates iii", "Bill gates", "William H. Gates", "Ian Vo", "Ian V" };
		for (String s : test1)
			System.out.println(s + " lastname is: " + getLastName(s));
	}

	public static Pair<Date, Date> getFirstLast(Collection<? extends DatedDocument> allDocs) { return getFirstLast(allDocs, false); }

	// compute the begin and end date of the corpus
	public static Pair<Date, Date> getFirstLast(Collection<? extends DatedDocument> allDocs, boolean ignoreInvalidDates)
	{
		// compute the begin and end date of the corpus
		Date first = null;
		Date last = null;

		if (allDocs == null)
			return null;

		for (DatedDocument ed : allDocs)
		{
			Date d = ed.date;
			if (d == null)
			{
				// drop this $ed$
				EmailUtils.log.warn("Warning: null date on email: " + ed.getHeader());
				continue;
			}

			// ignore invalid date if asked
			if (ignoreInvalidDates)
				if (EmailFetcherThread.INVALID_DATE.equals(d))
					continue;

			if (first == null || d.before(first))
				first = d;
			if (last == null || d.after(last))
				last = d;
		}

		return new Pair<Date, Date>(first, last);
	}

	public static <T extends Comparable<? super T>> List<T> removeDupsAndSort(List<T> docs)
	{
		log.info("-----------------------Detecting duplicates-------------------");
		Set<T> set = new LinkedHashSet<T>();
		set.addAll(docs);

		// maintain a map so when we find a duplicate, we can toString both the dup and the original
		Map<T, T> map = new LinkedHashMap<T, T>();
		for (T ed : docs)
		{
			if (map.get(ed) != null)
				log.info("Duplicate messages:\n\t" + ed + "\n\t" + map.get(ed));
			map.put(ed, ed);
		}

		List<T> result = new ArrayList<T>();
		result.addAll(set);
		Collections.sort(result);
		//		for (EmailDocument ed: result)
		//			System.out.println ("doc: " + ed);

		log.info("Removed duplicates from " + docs.size() + " messages: " + (docs.size() - result.size()) + " removed, " + result.size() + " left");

		return result;
	}

	public static boolean allDocsAreDatedDocs(Collection<? extends Document> ds)
	{
		for (Document d : ds)
			if (!(d instanceof DatedDocument))
				return false;
		return true;
	}

	public static List<LinkInfo> getLinksForDocs(Collection<? extends Document> ds)
	{
		// extract links from the given docs
		List<LinkInfo> links = new ArrayList<LinkInfo>();
		if (ds == null)
			return links;

		for (Document d : ds)
			if (d.links != null)
				links.addAll(d.links);
		return links;
	}

	public static int countAttachmentsInDocs(Collection<EmailDocument> docs)
	{
		int count = 0;
		if (docs == null)
			return 0;
		for (EmailDocument ed : docs)
			if (ed.attachments != null)
				count += ed.attachments.size();
		return count;
	}

	public static int countImageAttachmentsInDocs(Collection<EmailDocument> docs)
	{
		int count = 0;
		if (docs == null)
			return 0;
		for (EmailDocument ed : docs)
			if (ed.attachments != null)
				for (Blob b : ed.attachments)
					if (Util.is_image_filename(b.filename)) // consider looking at b.contentType as well
						count++;
		return count;
	}

	public static int countDocumentAttachmentsInDocs(Collection<EmailDocument> docs)
	{
		int count = 0;
		if (docs == null)
			return 0;
		for (EmailDocument ed : docs)
			if (ed.attachments != null)
				for (Blob b : ed.attachments)
					if (Util.is_doc_filename(b.filename)) // consider looking at b.contentType as well
						count++;
		return count;
	}

	public static int getMessageCount(List<Pair<String, Integer>> foldersAndCounts)
	{
		int totalMessageCount = 0;
		for (Pair<String, Integer> p : foldersAndCounts)
			if (!"[Gmail]/All Mail".equals(p.getFirst())) // don't count special case of gmail/all mail
				totalMessageCount += p.getSecond();
		return totalMessageCount;
	}

	public static List<Date> datesForDocs(Collection<? extends DatedDocument> c)
	{
		List<Date> result = new ArrayList<Date>();
		for (DatedDocument d : c)
			result.add(d.date);
		return result;
	}

	public static void maskEmailDomain(Collection<EmailDocument> docs, AddressBook ab)
	{
		ab.maskEmailDomain();
		for (EmailDocument e : docs) {
			e.maskEmailDomain(ab);
		}
	}

	/** returns set of all messages that have one of these attachments */
	public static Set<? super EmailDocument> getDocsForAttachments(Collection<EmailDocument> docs, Collection<Blob> blobs)
	{
		Set<EmailDocument> result = new LinkedHashSet<EmailDocument>();
		if (docs == null || blobs == null)
			return result;

		for (EmailDocument ed : docs)
		{
			if (ed.attachments == null)
				continue;
			for (Blob b : ed.attachments)
			{
				if (blobs.contains(b)) {
					result.add(ed);
					break; // no need to check its other attachments
				}
			}
		}
		return result;
	}

	/** given a set of emailAddress's, returns a map of email address -> docs containing it, from within the given docs.
     * return value also contains email addresses with 0 hits in the archive
	 * emailAddress should all be lower case. */
    public static Map<String, Set<Document>> getDocsForEAs(Collection<Document> docs, Set<String> emailAddresses){
        Map<String, Set<Document>> map = new LinkedHashMap<String, Set<Document>>();
        if (emailAddresses == null)
            return map;

        for (String email: emailAddresses)
            map.put(email, new LinkedHashSet<Document>());

        for(Document doc: docs){
            if(!(doc instanceof EmailDocument))
                continue;
            EmailDocument ed = (EmailDocument) doc;
            List<String> docAddrs = ed.getAllAddrs();
            for(String addr: docAddrs) {
				String cAddr = addr.toLowerCase(); // canonical addr
                if (emailAddresses.contains(cAddr)) {
					Set<Document> set = map.get(cAddr); // can't be null as we've already created a hash entry for each address
					set.add(doc);
                }
            }
        }

        return map;
    }

	/**
	 * little util method get an array of all own addrs, given 1 addr and some alternate ones.
	 * alternateaddrs could have multiple addrs, separated by whitespace or commas
	 * either of the inputs could be null
	 */
	public static Set<String> parseAlternateEmailAddrs(String alternateAddrs)
	{
		Set<String> result = new LinkedHashSet<String>();
		if (Util.nullOrEmpty(alternateAddrs))
			return result;

		StringTokenizer st = new StringTokenizer(alternateAddrs, "\t ,");
		while (st.hasMoreTokens())
			result.add(st.nextToken().toLowerCase());

		// log own addrs
		StringBuilder sb = new StringBuilder();
		for (String s : result)
			sb.append(s + " ");
		AddressBook.log.info(result.size() + " own email addresses: " + sb);
		return result;
	}

	/**
	 * returns contact -> {in_dates, out_dates} map.
	 * not responsible for dups, i.e. dedup should have been done before.
	 * we can consider caching this on contacts directly if it becomes a performance problem.
	 */
	public static Map<Contact, Pair<List<Date>, List<Date>>> computeContactToDatesMap(AddressBook ab, Collection<EmailDocument> list)
	{
		Map<Contact, Pair<List<Date>, List<Date>>> result = new LinkedHashMap<Contact, Pair<List<Date>, List<Date>>>();

		// note that we'll add the same date twice if the same contact has 2 different email addresses present on the message.
		// consider changing this if needed.
		for (EmailDocument ed : list)
		{
			String senderEmail = ed.getFromEmailAddress();
			if (Util.nullOrEmpty(senderEmail))
				senderEmail = "------ <NONE> -------"; // dummy, we want to process the other addresses even if sender is not available

			List<String> allEmails = ed.getAllAddrs();
			for (String email : allEmails)
			{
				if (ed.date == null)
					continue; // no date, no point processing

				Contact c = ab.lookupByEmail(email);
				if (c == null)
					continue; // shouldn't happen, but defensive

				Pair<List<Date>, List<Date>> p = result.get(c);
				if (p == null)
				{
					p = new Pair<List<Date>, List<Date>>(new ArrayList<Date>(), new ArrayList<Date>()); // not seen this contact before
					result.put(c, p);
				}

				if (senderEmail.equals(email))
					p.getSecond().add(ed.date);
				else
					p.getFirst().add(ed.date);
			}
		}
		return result;
	}

	/** normalizeNewlines should already have been called on text */
	public static String getOriginalContent(String text) throws IOException
	{
		StringBuilder result = new StringBuilder();
		BufferedReader br = new BufferedReader(new StringReader(text));

		// stopper for the tokenizer when we meet a line that needs to be ignored
		String stopper = " . ";

		// we'll maintain line and nextLine for lookahead. needed e.g. for the "on .... wrote:" detection below
		String originalLine = null;
		String nextLine = br.readLine();

		while (true)
		{
			originalLine = nextLine;
			if (originalLine == null)
				break;
			nextLine = br.readLine();

			/*
			 * Yahoo replies look like this. they don't use the quote character.
			 * --
			 * This is another test. Another president's name is Barack Obama.
			 * ________________________________
			 * From: Sudheendra Hangal <hangal@cs.stanford.edu>
			 * To: sd sdd <s_hangal@yahoo.com>
			 * etc.
			 * --
			 * so lets eliminate everything after a line that goes ________________________________
			 * i.e. at least 20 '_' chars, and the following line has a colon
			 */

			// do all checking ops on effective line, but don't modify original line, because we want to use it verbatim in the result.
			// effectiveLine is taken before replacing everything after a >, because we may want to look for a marker like "forwarded message" after a > as well.
			String effectiveLine = originalLine.trim();

			// nuke everything after >. this is the only modification to the original line
			originalLine = originalLine.replaceAll("^\\s*>.*$", stopper); // 

			// now a series of checks to stop copying of the input to result
			if (effectiveLine.length() > 20 && Util.hasOnlyOneChar(effectiveLine, '_') && nextLine != null && nextLine.indexOf(":") > 0)
				break;

			// eliminate everything after the line "On Wed, Jul 3, 2013 at 2:06 PM, Sudheendra Hangal <hangal@gmail.com> wrote:"
			// however, sometimes this line has wrapped around on two lines, so we use the nextLine lookahead if it's present
			if ((effectiveLine.startsWith("On ") && effectiveLine.endsWith("wrote:")) ||
					(effectiveLine.startsWith("On ") && effectiveLine.length() < 80 && (nextLine != null && nextLine.trim().endsWith("wrote:"))))
			{
				break;
			}

			// look for forward separator
			String lowercaseLine = effectiveLine.toLowerCase();
			if (lowercaseLine.startsWith("Begin forwarded message:"))
				break;
			if (effectiveLine.startsWith("---") && effectiveLine.endsWith("---") && (lowercaseLine.contains("forwarded message") || lowercaseLine.contains("original message")))
				break;

			result.append(originalLine);

			// be careful, need to ensure that result is same as original text if nothing was stripped.
			// this is important because indexer will avoid redoing NER etc. if stripped content is exactly the same as the original.
			if (nextLine == null)
			{
				// we're at end of input, append \n only if original text had it.
				if (text.endsWith("\n"))
					result.append("\n");
			}
			else
				result.append("\n");
		}

		return result.toString();
	}

	public static void testGetOriginalContent() throws IOException
	{
		String x = "abc\r\nrxyz\rde\n";
		System.out.println("s = " + x + "\n" + x.length());

		x = x.replaceAll("\r\n", "\n");
		System.out.println("s = " + x + "\n" + x.length());
		x = x.replaceAll("\r", "\n");
		System.out.println("s = " + x + "\n" + x.length());

		String s = "This is a test\nsecond line\n"; // testing with 4 trailing newlines, this was causing a problem
		String s1 = getOriginalContent(s);
		System.out.println("s = " + s);
		System.out.println("s1 = " + s1);
		System.out.println("equals = " + s.equals(s1));
		if (!s.equals(s1))
			throw new RuntimeException();
	}

	// text is an email message, returns a sanitized version of it
	// after stripping away signatures etc.
	public static String cleanupEmailMessage(String text) throws IOException
	{
		StringBuilder result = new StringBuilder();
		BufferedReader br = new BufferedReader(new StringReader(text));
		while (true)
		{
			String line = br.readLine();
			if (line == null)
				break;
			line = line.trim();
			if (line.equals("--")) // strip all lines including and after signature
				break;

			if (line.contains("Original message") || line.contains("Forwarded message"))
			{
				int idx1 = line.indexOf("-----");
				int idx2 = line.lastIndexOf("-----");
				if (idx1 >= 0 && idx2 >= 0 && idx1 != idx2)
					break;
			}

			if (line.startsWith("On ") && line.endsWith("wrote:"))
				break;

			result.append(line + "\n");
		}

		return result.toString();
	}

	public static List<String> emailAddrs(Address[] as) {
		List<String> result = new ArrayList<String>();
		if (as == null)
			return result;

		for (Address a : as)
		{
			String email = ((InternetAddress) a).getAddress();
			if (!Util.nullOrEmpty(email))
				result.add(email.toLowerCase());
		}
		return result;
	}

	public static List<String> personalNames(Address[] as) {
		List<String> result = new ArrayList<String>();
		if (as == null)
			return result;

		for (Address a : as)
		{
			String name = ((InternetAddress) a).getPersonal();
			if (!Util.nullOrEmpty(name))
				result.add(name.toLowerCase());
		}
		return result;
	}

	/** thread a collection of emails */
	public static Collection<Collection<EmailDocument>> threadEmails(Collection<EmailDocument> docs)
	{
		Map<String, Collection<EmailDocument>> map = new LinkedHashMap<String, Collection<EmailDocument>>();
		for (EmailDocument ed : docs)
		{
			// compute a canonical thread id, based on the cleaned up subject line (removing re: fwd: etc) and the description.
			// in the future, could consider date ranges too, e.g. don't consider messages more than N days apart as the same thread
			// even if they have the same subject and recipients
			// for gmail only -- there is a thread id directly in gmail which can potentially be used 
			String canonicalSubject = cleanupSubjectLine(ed.description);
			// we prob. don't care about canonical case for subject

			List<String> addrs = emailAddrs(ed.to);
			addrs.addAll(emailAddrs(ed.cc));
			addrs.addAll(emailAddrs(ed.from));
			Collections.sort(addrs);
			String threadId = canonicalSubject + " " + Util.join(addrs, ",");
			// canonical id for the thread.
			Collection<EmailDocument> messagesForThisThread = map.get(threadId);
			if (messagesForThisThread == null)
			{
				messagesForThisThread = new ArrayList<EmailDocument>();
				map.put(threadId, messagesForThisThread);
			}
			messagesForThisThread.add(ed);
		}
		return map.values();
	}

	/** returns a histogram of the dates, bucketed in quantum of quantum, going backwards from endTime */
	public static List<Integer> histogram(List<Date> dates, long endTime, long quantumMillis)
	{
		ArrayList<Integer> result = new ArrayList<Integer>();

		// input dates may not be sorted, but doesn't matter
		for (Date d : dates)
		{
			int slot = (int) ((endTime - d.getTime()) / quantumMillis);

			// ensure list has enough capacity because there may be gaps
			while (result.size() <= slot)
				result.add(0);

			// result.size() is at least slot+1    
			Integer I = result.get(slot);
			result.set(slot, I + 1);
		}

		return result;
	}

	/*
	 * normalizes names.
	 * orders words in the name alphabetically.
	 * this is different from normalizePersonName, which actually
	 * changes the display name shown to the user. e.g. in that method
	 * "'Seng Keat'" becomes "Seng Keat" (stripping the quotes) and
	 * "Teh, Seng Keat" becomes "Seng Keat Teh"
	 * in contrast this method changes the (internal) name **for the purposes of lookup only**
	 * it mangles the name, so it should never be displayed to the user.
	 * it canonicalizes the name by ordering all the words in the name alphabetically
	 * there is exactly one space between each word in the output, with no spaces at the end.
	 * e.g.
	 * Wetterwald Julien becomes julien wetterwald
	 * both names are still retained in the contact.
	 * if there are at least 2 names with multiple letters, then single letter initials are dropped.
	 * Obama Barack becomes barack obama (all hail the chief!)
	 * Barack H Obama also becomes barack obama
	 * 
	 * we assume these forms are really the same person, so they must be looked up consistently.
	 * this is somewhat aggressive entity resolution. reconsider if it is found to relate unrelated people...
	 */
	public static String normalizePersonNameForLookup(String name)
	{
		if (name == null)
			return null;

		String originalName = name;

		name = cleanPersonName(name);
		if (name == null)
			return null;
		// remove all periods and commas
		// in future: consider removing all non-alpha, non-number chars.
		// but should we also remove valid quote chars in names like O'Melveny
		// also be careful of foreign names
		name = name.replaceAll("\\.", " "); // make sure to escape the period, replaceAll's first param is a regex!
		name = name.replaceAll(",", " ");

		StringTokenizer st = new StringTokenizer(name);
		if (st.countTokens() <= 1)
			return name;

		// gather all the words in the name into tokens and sort it
		List<String> tokens = new ArrayList<String>();
		while (st.hasMoreTokens())
			tokens.add(st.nextToken().toLowerCase());

		if (tokens.size() > 2)
		{
			int nOneLetterTokens = 0, nMultiLetterTokens = 0;
			for (String t : tokens)
			{
				if (t.length() > 1)
					nMultiLetterTokens++;
				else
					nOneLetterTokens++;
			}

			// if we have at least 2 multi-letter names, then ignore initials
			if (nMultiLetterTokens >= 2 && nOneLetterTokens >= 1)
				for (Iterator<String> it = tokens.iterator(); it.hasNext();)
				{
					String t = it.next();
					if (t.length() == 1)
						it.remove(); // this is an initial, remove it.
				}
		}

		Collections.sort(tokens);

		// cat all the tokens, one space in between, no space at the end
		String cname = Util.join(tokens, " ");
		/*
		 * StringBuilder sb = new StringBuilder();
		 * for (int i = 0; i < tokens.size(); i++)
		 * {
		 * sb.append(tokens.get(i));
		 * if (i < tokens.size()-1)
		 * sb.append (" ");
		 * }
		 */

		if (Util.nullOrEmpty(cname))
		{
			// unlikely case, but can happen if input string was "" or just periods and commas
			// we don't know whats going in in that case, just be safe and return the original name
			// better to return the original name than cause merging and confusion with an empty normalized name
			return originalName;
		}

		return cname;
	}

	public static String uncanonicaliseName(String str) {
		if (str == null)
			return null;
		List<String> sws = Arrays.asList("but", "be", "with", "such", "then", "for", "no", "will", "not", "are", "and", "their", "if", "this", "on", "into", "a", "there", "in", "that", "they", "was", "it", "an", "the", "as", "at", "these", "to", "of" );

		String res = "";
		String[] tokens = str.split("\\s+");
		for (int i = 0; i < tokens.length; i++) {
			String token = tokens[i];
			if (sws.contains(token.toLowerCase())) {
				res += token;
			}
			else {
				if (token.length() > 1)
					res += token.toUpperCase().substring(0, 1) + token.substring(1);
				else
					res += token.toUpperCase();
			}
			if (i < (tokens.length - 1))
				res += " ";
		}
		return res;
	}

	/** normalizes email address: strips away blanks and quotes, lower cases. must be used before put/get into AddressBook.emailToContact.
	 * we often see junk like 'hangal@cs.stanford.edu' (including the start and end quote characters), which this method strips away */
	public static String cleanEmailAddress(String e) {
		if (e == null)
			return null;

		e = e.trim().toLowerCase();
		while (e.startsWith("'") || e.startsWith("\"")) {
			e = e.substring(1);
			e = e.trim();
		}
		while (e.endsWith("'") || e.endsWith("\"")) {
			e = e.substring(0, e.length()-1);
			e = e.trim();
		}

		return e;
	}

	/**
	 * Similar to normalizePersonNameForLookup but return a list of tokens in the name.
	 * 
	 * @param name
	 * @return list of tokens in the name
	 */
	public static List<String> normalizePersonNameForLookupAsList(String name)
	{
		if (name == null)
			return null;

		name = cleanPersonName(name);
		if (name == null)
			return null;
		// remove all periods and commas
		// in future: consider removing all non-alpha, non-number chars.
		// but should we also remove valid quote chars in names like O'Melveny
		// also be careful of foreign names
		name = name.replaceAll("\\.", " "); // make sure to escape the period, replaceAll's first param is a regex!
		name = name.replaceAll(",", " ");

		StringTokenizer st = new StringTokenizer(name);

		// gather all the words in the name into tokens and sort it
		List<String> tokens = new ArrayList<String>();
		while (st.hasMoreTokens())
			tokens.add(st.nextToken().toLowerCase());

		if (tokens.size() > 2)
		{
			int nOneLetterTokens = 0, nMultiLetterTokens = 0;
			for (String t : tokens)
			{
				if (t.length() > 1)
					nMultiLetterTokens++;
				else
					nOneLetterTokens++;
			}

			// if we have at least 2 multi-letter names, then ignore initials
			if (nMultiLetterTokens >= 2 && nOneLetterTokens >= 1)
				for (Iterator<String> it = tokens.iterator(); it.hasNext();)
				{
					String t = it.next();
					if (t.length() == 1)
						it.remove(); // this is an initial, remove it.
				}
		}

		return tokens;
	}

	/** Filters the contact for specifically people. */
	public static List<Contact> getPeople(Archive archive) {
		AddressBook ab = archive.addressBook;
		List<Document> docs = archive.getAllDocs();
		//recognise as mailing list when none of the owner addresses is in the from or to addresses.
		Set<String> ownEAs = archive.ownerEmailAddrs;
		System.err.println("OwnEAs: " + ownEAs);

		//list of mailing list addresses.
		Set<String> maillists = new HashSet<String>();
		for (Document doc : docs) {
			EmailDocument ed = (EmailDocument) doc;
			List<String> addrs = ed.getAllAddrs();
			boolean notmaillist = false;
			if (addrs != null)
				for (String ea : addrs)
					if (ownEAs.contains(ea)) {
						notmaillist = true;
						break;
					}
			if (!notmaillist) {
				System.err.println("Adding ea to mailing lists: " + ed.getToString());
				maillists.add(ed.getToString());
			}
		}

		//sometimes mailing lists may also contain the owner email address.
		//should there be one more filter for corporate?  

		List<Contact> contacts = new ArrayList<Contact>();
		for (Contact c : ab.allContacts()) {
			boolean ml = false;
			for (String email : c.emails)
				if (maillists.contains(email)) {
					ml = true;
					break;
				}
			if (!ml)
				contacts.add(c);
		}
		return contacts;
	}

	/**
	 * Cleans and returns names from contacts.
	 * For svm model training file generation, an additional filtering step of ptype score over dbpedia>0.7 is employed.
	 * 
	 * Note: this is not efficient call, dont call it often. As it has to read and analyse dbpedia for better filtering of names.
	 * 
	 * @return name -> type, type in the case of addressbook names, type is only person.
	 */
	public static Map<String, String> getNames(List<Contact> contacts) {
		int allsize = contacts.size();
		Map<String, String> names = new HashMap<String, String>();
		for (Contact c : contacts) {
			if (c.names != null)
				for (String n : c.names) {
					//dont trust single word names, may contain phrases like Mom, Dad
					if (n == null || n.split("\\s+").length == 1)
						continue;
					String cn = edu.stanford.muse.util.Util.cleanName(n);
					if (cn != null)
						names.put(cn, "Person");
				}
		}
		System.err.println("All contacts size: " + allsize + ", good-names: " + names.size());

		return names;
	}

	public static String cleanEmailContent(String content) {
		String[] lines = content.split("\\n");
		String cont = "";
		for (String line : lines) {
			if (line.contains("wrote:")) {
				break;
			}
			cont += line + "\n";
		}

		content = cont;
		//only double line breaks are considered EOS.
		content = content.replaceAll("^>+.*", "");
		content = content.replaceAll("\\n\\n", ". ");
		content = content.replaceAll("\\n", " ");
		return content;
	}

	public static Map<String, String> readDBpedia() {
        String typesFile = "instance_types_en.nt1.gz";
        if (dbpedia != null)
            return dbpedia;
        dbpedia = new LinkedHashMap<String, String>();
        int d = 0, numPersons = 0, lines = 0;
        try {
            LineNumberReader lnr = new LineNumberReader(new InputStreamReader(new GZIPInputStream(EmailUtils.class.getClassLoader().getResourceAsStream(typesFile)), "UTF-8"));//EmailUtils.class.getClassLoader().getResourceAsStream(typesFile)), "UTF-8"));
            while (true) {
                String line = lnr.readLine();
                if (line == null)
                    break;
                if (lines++ % 500000 == 0)
                    log.info("Processed " + lines + " lines of approx. 2.35M in " + typesFile);
                if (lines > 1000000)
                    break;

                if (line.contains("GivenName"))
                    continue;

                String[] words = line.split("\\s+");
                String r = words[0];

                /**
                 * The types file contains lines like this:
                 * National_Bureau_of_Asian_Research Organisation|Agent
                 * National_Bureau_of_Asian_Research__1 PersonFunction
                 * National_Bureau_of_Asian_Research__2 PersonFunction
                 * Which leads to classifying "National_Bureau_of_Asian_Research" as PersonFunction and not Org.
                 */
                if (r.contains("__")) {
                    d++;
                    continue;
                    //					r = r.replaceAll("\\_\\_.$", "");
                    //					r = r.replaceAll("^.+?\\_\\_", "");
                }
                //if it still contains this, is a bad title.
                if (r.equals("") || r.contains("__")) {
                    d++;
                    continue;
                }
                r = r.replaceAll("_\\(.*?\\)", "");
                String title = r.replaceAll("_"," ");
                String type = words[1];

                boolean allowed = DBpediaTypes.allowedTypes.contains(type);
                if(!allowed)
                    continue;

                String badSuffix = "|Agent";
                if (type.endsWith(badSuffix) && type.length() > badSuffix.length())
                    type = type.substring(0, type.length() - badSuffix.length());
                if (type.contains("|Person"))
                    numPersons++;
                type = type.intern(); // type strings are repeated very often, so intern
                dbpedia.put(title, type);
            }
			lnr.close();
		} catch (Exception e) {
			e.printStackTrace();
		}

		log.info("Read " + dbpedia.size() + " names from DBpedia, " + numPersons + " people name. dropped: " + d);
		log.info("Read " + dbpedia.size() + " names from DBpedia, " + numPersons + " people name. dropped: " + d);

		return dbpedia;
	}

    public static void main(String[] args){
        Map<String,String> dbpedia = readDBpedia();
        System.err.println(dbpedia.containsKey("Samsung"));
    }
}
