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
package edu.stanford.muse.index;

import edu.stanford.muse.datacache.Blob;
import edu.stanford.muse.datacache.BlobStore;
import edu.stanford.muse.datacache.FileBlobStore;
import edu.stanford.muse.email.*;
import edu.stanford.muse.groups.SimilarGroup;
import edu.stanford.muse.ie.NameInfo;
import edu.stanford.muse.ner.*;
import edu.stanford.muse.ner.NER;
import edu.stanford.muse.ner.tokenizer.Tokenizer;
import edu.stanford.muse.util.EmailUtils;
import edu.stanford.muse.util.Pair;
import edu.stanford.muse.util.Util;
import edu.stanford.muse.webapp.ModeConfig;
import edu.stanford.muse.webapp.SimpleSessions;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.LockObtainFailedException;

import java.io.*;
import java.util.*;

/**
 * Core data structure that represents an archive. Conceptually, an archive is a
 * collection of indexed messages (which can be incrementally updated), along
 * with a blob store. It also has addressbooks, group assigner etc, which are
 * second order properties -- they may be updated independently of the docs (in
 * the future). allDocs is the indexed docs, NOT the ones in the current
 * filter... need to work this out. An archive should be capable of being loaded
 * up in multiple sessions simultaneously. one problem currently is that
 * summarizer is stored in indexer -- however, we should pull it out into
 * per-session state.
 */
public class Archive implements Serializable {

	public static class Entity {
		public Map<String, Short>	ids;
		//person,places,orgs, personcustom
		public String				name;
		Set<String>					types	= new HashSet<String>();

		public Entity(String name, Map<String, Short> ids, Set<String> types) {
			this.name = name;
			this.ids = ids;
			this.types = types;
		}

		@Override
		public String toString() {
			return types.toString();
		}
	}

	private static Log			log					= LogFactory.getLog(Archive.class);
	private final static long	serialVersionUID	= 1L;

	public static final String	BLOBS_SUBDIR		= "blobs";
	public static final String	IMAGES_SUBDIR		= "images";
	public static final String	INDEXES_SUBDIR		= "indexes";
	public static final String	SESSIONS_SUBDIR		= "sessions";						// original idea was that there would be different sessions on the same archive (index). but in practice we only have one session
	public static final String	LEXICONS_SUBDIR		= "lexicons";
    public static final String  FEATURES_SUBDIR     = "features";

	// these fields are used in the library setting
	static public class ProcessingMetadata implements java.io.Serializable {
		private final static long	serialVersionUID				= 6304656466358754945L; // compatibility
		public String				institution, repository, collectionTitle, collectionID, accessionID, findingAidLink, catalogRecordLink, contactEmail;
		public long					timestamp;
		public TimeZone				tz;
		public int					nDocs, nBlobs;																	// this is just a cache so we don't have to read the archive
		public String				ownerName, about;
		//will be set by method that computes epadd-ner
		public Map<String, Integer>	entityCounts;
		public int					numPotentiallySensitiveMessages	= -1;
		public int					numLexicons						= -1;

		private static String mergeField(String a, String b) {
			if (a == null)
				return b;
			if (b == null)
				return a;
			if (a.equals(b))
				return a;
			else
				return a + "+" + b;
		}

		public void merge(ProcessingMetadata other) {
			mergeField(this.institution, other.institution);
			mergeField(this.repository, other.repository);
			mergeField(this.collectionTitle, other.collectionTitle);
			mergeField(this.collectionID, other.collectionID);
			mergeField(this.accessionID, other.accessionID);
			mergeField(this.findingAidLink, other.findingAidLink);
			mergeField(this.catalogRecordLink, other.catalogRecordLink);
			mergeField(this.contactEmail, other.contactEmail);
			// mergeField(this.tz, other.tz);
		}
	}

	/** all of these things don't change based on the current filter */
	public Indexer										indexer;
	private IndexOptions								indexOptions;
	public BlobStore									blobStore;
	public AddressBook									addressBook;
	public GroupAssigner								groupAssigner;
	transient private Map<String, Lexicon>				lexiconMap				= null;
	private List<Document>								allDocs;													// this
																													// is
																													// the
																													// equivalent
																													// of
																													// fullEmailDocs
																													// earlier
	transient private Set<Document>						allDocsAsSet			= null;
	private Set<FolderInfo>								fetchedFolderInfos		= new LinkedHashSet<FolderInfo>();	// keep
																													// this
																													// private
																													// since
																													// its
																													// updated
																													// in
																													// a
																													// controlled
																													// way
	transient private LinkedHashMap<String, FolderInfo>	fetchedFolderInfosMap	= null;
	public Set<String>									ownerNames				= new LinkedHashSet<String>(), ownerEmailAddrs = new LinkedHashSet<String>();
	Map<String, NameInfo>								nameMap;

	public ProcessingMetadata							processingMetadata		= new ProcessingMetadata();
	public List<String>									allAccessions			= new ArrayList<String>();
	public List<FetchStats> allStats = new ArrayList<FetchStats>(); // multiple stats because usually there is 1 per import

	/*
	 * baseDir is used loosely... it may not be fully reliable, e.g. when the
	 * archive moves.
	 */
	public String										baseDir;

	public void setBaseDir(String dir)
	{
		baseDir = dir;
		((FileBlobStore) blobStore).setDir(dir + File.separator + BLOBS_SUBDIR);
	}

	public void setNameMap(Map<String, NameInfo> nameMap) {
		this.nameMap = nameMap;
	}

	public class SentimentStats implements Serializable { // this is a placeholder
		// right now.. its
		// essentially storing
		// archive cluer's stats
		private final static long	serialVersionUID	= 1L;
		public Map<String, Integer>	sentimentCounts;
	}

	public SentimentStats stats	= new SentimentStats();

	transient private List<Map.Entry<String, Integer>> topNames = null;

	// clusters are somewhat ephemeral and not necessarily a core part of the
	// Archive struct. consider moving it elsewhere.
	List<MultiDoc>										docClusters;

	public void setBlobStore(BlobStore blobStore) {
		this.blobStore = blobStore;
	}

	public void setGroupAssigner(GroupAssigner groupAssigner) {
		this.groupAssigner = groupAssigner;
	}

	public void setAddressBook(AddressBook ab) {
		addressBook = ab;
	}

	public BlobStore getBlobStore() {
		return blobStore;
	}

	public AddressBook getAddressBook() {
		return addressBook;
	}

	// private constructor
	private Archive() {
	}

	public static Archive createArchive()
	{
		return new Archive();
	}

	public synchronized void openForRead()
	{
		log.info("Opening archive read only");
		indexer.setupForRead();
	}

	public synchronized void openForWrite() throws CorruptIndexException, LockObtainFailedException, IOException
	{
		log.info("Opening archive for write");

		indexer.setupForWrite();
		if (allDocs != null)
		{
			// we already have some docs in the index, verify it to make
			// sure the archive's idea of #docs is the same as the index's.
			int docsInIndex = indexer.nDocsInIndex();
			log.info(docsInIndex + " doc(s) in index, " + allDocs.size() + " doc(s) in archive");
			Util.warnIf(indexer.nDocsInIndex() != allDocs.size(),
					"Warning: archive nDocsInIndex is not the same as Archive alldocs (possible if docs have been deleted?)", log);
		}
	}

	public synchronized void close()
	{
		log.info("Closing archive");
		if (indexer != null)
			indexer.close();
		try {
			if (blobStore != null)
				blobStore.pack(); // ideally, do this only if its dirty
		} catch (Exception e) {
			Util.print_exception(e, log);
		}
	}

	// create a new/empty archive.
	// baseDir is for specifying base location of Indexer's file-based
	// directories
	public void setup(String baseDir, String args[]) throws IOException
	{
		prepareBaseDir(baseDir);
		lexiconMap = createLexiconMap(baseDir);
		indexOptions = new IndexOptions();
		indexOptions.parseArgs(args);
		log.info("Index options are: " + indexOptions);
		indexer = new Indexer(baseDir, indexOptions);
	}

	/** clear all fields, use when indexer needs to be completely cleared */
	public void clear()
	{
		if (indexer != null)
			indexer.clear();
		if (allDocs != null)
			allDocs.clear();
		if (allDocsAsSet != null)
			allDocsAsSet.clear();
		groupAssigner = null;
		ownerEmailAddrs.clear();
		ownerNames.clear();
		addressBook = null;
	}

	/*
	 * should happen rarely, only while exporting session. fragile operation,
	 * make sure blobStore etc are updated consistently
	 */
	public void setAllDocs(List<Document> docs)
	{
		log.info("Updating archive's alldocs to new list of " + docs.size() + " docs");
		allDocs = docs;
		allDocsAsSet = null;
	}

	public NameInfo nameLookup(String name) {
		String ctitle = name.toLowerCase().replaceAll(" ", "_");
		if (nameMap != null)
			return nameMap.get(ctitle);
		else
			return null;
	}

	public void addOwnerName(String name) {
		ownerNames.add(name);
		processingMetadata.ownerName = name;
	}

	public void addOwnerEmailAddrs(Collection<String> emailAddrs) {
		ownerEmailAddrs.addAll(emailAddrs);
	}

	public void addOwnerEmailAddr(String emailAddr) {
		ownerEmailAddrs.add(emailAddr);
	}

	/** This should be the only place that creates the cache dir. */
	public static void prepareBaseDir(String dir)
	{
		dir = dir + File.separatorChar + LEXICONS_SUBDIR;
		File f_dir = new File(dir);
		if (f_dir.exists())
			return; // lexicons dir already exists = do not overwrite.

		f_dir.mkdirs();
		// copy lexicons over to the muse dir
		String[] lexicons = { "sensitive.english.lex.txt", "general.english.lex.txt", "default.english.lex.txt" }; // unfortunately, hard-coded because we are loading as a ClassLoader resource and not as a file, so we can't use Util.filesWithSuffix()
		log.info(lexicons.length + " lexicons copied to " + dir);
		for (String l : lexicons)
		{
			try {
				InputStream is = EmailUtils.class.getClassLoader().getResourceAsStream("lexicon/" + l);
				if (is != null)
					Util.copy_stream_to_file(is, dir + File.separator + l);
				else
					log.info ("lexicon " + l + " not found");
			} catch (Exception e) {
				Util.print_exception(e, log);
			}
		}
	}

	public static void clearCache(String baseDir, String rootDir)
	{
		log.info("Clearing archive with baseDir: " + baseDir + " rootDir: " + rootDir);
		if (!Util.nullOrEmpty(baseDir))
		{
			// delete only indexes, blobs, sessions
			// keep sentiment stuff around
			Util.deleteDir(baseDir);
			/*
			Util.deleteDir(baseDir + File.separatorChar + INDEXES_SUBDIR);
			Util.deleteDir(baseDir + File.separatorChar + SESSIONS_SUBDIR); // could
			Util.deleteDir(baseDir + File.separatorChar + LEXICONS_SUBDIR); // could
			Util.deleteDir(baseDir + File.separatorChar + MODELS_SUBDIR); // could
																			// also
																			// call
																			// sessions.deleteallsessions,
																			// but
																			// lazy...
			*/
			// prepare cache dir anew
			prepareBaseDir(baseDir);
		}

		// rootdir is used only for webapp/<user> (piclens etc) we'll get rid of
		// it in future
		if (!Util.nullOrEmpty(rootDir))
		{
			Util.deleteDir(rootDir);
			new File(rootDir + File.separator).mkdirs();
		}
	}

	/**
	 * returns the final, sorted, deduped version of allDocs that this driver
	 * worked on in its last run
	 */
	public List<Document> getAllDocs()
	{
		if (allDocs == null) {
			synchronized (this) {
				if (allDocs == null) {
					allDocs = new ArrayList<Document>();
					allDocsAsSet = new LinkedHashSet<Document>();
				}
			}
		}
		return allDocs;
	}

	public Set<Document> getAllDocsAsSet()
	{
		// allDocsAsSet is lazily computed
		if (allDocsAsSet == null) {
			synchronized (this) {
				if (allDocsAsSet == null) {
					allDocsAsSet = new LinkedHashSet<Document>(getAllDocs());
					Util.softAssert(allDocs.size() == allDocsAsSet.size());
				}
			}
		}
		return allDocsAsSet;
	}

	public int nDocsInCluster(int i)
	{
		if (i < 0 || i >= docClusters.size())
			return -1;
		return docClusters.get(i).getDocs().size();
	}

	public int nClusters()
	{
		return docClusters.size();
	}

	// work in progress - status provider
	public StatusProvider getStatusProvider() {
		return indexer;
	}

	public Map<String, Set<Document>> getSentimentMap(Lexicon lex, boolean originalContentOnly, String... captions)
	{
		return lex.getEmotions(indexer, getAllDocsAsSet(), false /* doNota */, originalContentOnly, captions);
	}

	/** gets original content only! */
	public String getContents(Document d, boolean originalContentOnly)
	{
		return indexer.getContents(d, originalContentOnly);
	}

	private void setupAddressBook(List<Document> docs)
	{
		// in this case, we don't care whether email addrs are incoming or
		// outgoing,
		// so the ownAddrs can be just a null string
		if (addressBook == null)
			addressBook = new AddressBook((String[]) null, (String[]) null);
		log.info("Setting up address book for " + docs.size() + " messages (indexing driver)");
		for (Document d : docs)
			if (d instanceof EmailDocument)
				addressBook.processContactsFromMessage((EmailDocument) d);

		addressBook.organizeContacts();
	}

	public List<LinkInfo> extractLinks(Collection<Document> docs) throws Exception
	{
		prepareAllDocs(docs, indexOptions);
		indexer.clear();
		indexer.extractLinks(docs);
		return EmailUtils.getLinksForDocs(docs);
	}

	public Collection<DatedDocument> docsInDateRange(Date start, Date end)
	{
		List<DatedDocument> result = new ArrayList<DatedDocument>();
		if (Util.nullOrEmpty(allDocs))
			return result;

		for (Document d : allDocs)
		{
			try {
				DatedDocument dd = (DatedDocument) d;
				if ((dd.date.after(start) && dd.date.before(end)) || dd.date.equals(start) || dd.date.equals(end))
					result.add(dd);
			} catch (Exception e) {
			}
		}
		return result;
	}

	public boolean containsDoc(Document doc)
	{
		return getAllDocsAsSet().contains(doc);
	}

	/**
	 * use with caution. pseudo-adds a doc to the archive, but without any
	 * subject and without any contents. useful only when doing quick screening
	 * to check of emails for memory tests, etc.
	 */
	public synchronized boolean addDocWithoutContents(Document doc)
	{
		if (containsDoc(doc))
			return false;

		getAllDocsAsSet().add(doc);
		getAllDocs().add(doc);

		String subject = "", contents = "";

		indexer.indexSubdoc(subject, contents, doc, blobStore);

		if (getAllDocs().size() % 100 == 0)
			log.info("Memory status after " + getAllDocs().size() + " emails: " + Util.getMemoryStats());

		return true;
	}

	/**
	 * core method, adds a single doc to the archive. remember to call
	 * postProcess at the end of any series of calls to add docs
	 */
	public synchronized boolean addDoc(Document doc, String contents)
	{
		if (containsDoc(doc))
			return false;

		getAllDocsAsSet().add(doc);
		getAllDocs().add(doc);

		String subject = doc.getSubjectWithoutTitle();
		subject = EmailUtils.cleanupSubjectLine(subject);

		indexer.indexSubdoc(subject, contents, doc, blobStore);

		if (getAllDocs().size() % 100 == 0)
			log.info("Memory status after " + getAllDocs().size() + " emails: " + Util.getMemoryStats());

		return true;
	}

	/**
	 * prepares all docs for indexing, incl. applying filters, removing dups and
	 * sorting
	 * 
	 * @throws Exception
	 */
	private void prepareAllDocs(Collection<Document> docs, IndexOptions io) throws Exception
	{
		allDocs = new ArrayList<Document>();
		allDocs.addAll(docs);
		allDocs = EmailUtils.removeDupsAndSort(allDocs);
		log.info(allDocs.size() + " documents after removing duplicates");

		if (addressBook == null && !io.noRecipients)
		{
			log.warn("no address book previously set up!");
			setupAddressBook(allDocs); // set up without the benefit of ownaddrs
		}

		if (io.filter != null && addressBook != null)
		{
			Contact ownCI = addressBook.getContactForSelf(); // may return null
																// if we don't
																// have own info
			io.filter.setOwnContactInfo(ownCI);
		}

		// if no filter, accept doc (default)
		List<Document> newAllDocs = new ArrayList<Document>();
		for (Document d : allDocs)
			if (io.filter == null || (io.filter != null && io.filter.matches(d)))
				newAllDocs.add(d);

		EmailUtils.cleanDates(newAllDocs);

		log.info(newAllDocs.size() + " documents after filtering");

		allDocs = newAllDocs;
		Collections.sort(allDocs); // may not be essential
		allDocsAsSet = null;
	}

	/** set up doc clusters by group or by time */
	public void prepareDocClusters(List<SimilarGroup<String>> groups)
	{
		/** by default, we only use month based clusters right now */
		if (indexOptions.categoryBased)
		{
			docClusters = IndexUtils.partitionDocsByCategory(allDocs);
		}
		else
		{
			if (groups != null)
			{
				Map<String, Set<EmailDocument>> groupsToDocsMap = IndexUtils.partitionDocsByGroup((Collection) allDocs, groups, addressBook, true);
				int i = 0;
				for (String groupName : groupsToDocsMap.keySet())
				{
					MultiDoc md = new MultiDoc(Integer.toString(i++), groupName);
					docClusters.add(md);
					for (EmailDocument d : groupsToDocsMap.get(groupName))
						md.add(d);
				}
			}
			else
				docClusters = IndexUtils.partitionDocsByInterval((List) allDocs, indexOptions.monthsNotYears);
		}

		log.info(docClusters.size() + " clusters of documents");

		// outputPrefix = io.outputPrefix;
		log.info(allDocs.size() + " documents in " + docClusters.size() + " time clusters, " + indexer.nonEmptyTimeClusterMap.size() + " non-empty");
	}

	private String getFolderInfosMapKey(String accountKey, String longName)
	{
		return accountKey + "..." + longName;
	}

	private void setupFolderInfosMap()
	{
		if (fetchedFolderInfosMap == null)
			fetchedFolderInfosMap = new LinkedHashMap<String, FolderInfo>();
		for (FolderInfo fi : fetchedFolderInfos)
		{
			fetchedFolderInfosMap.put(getFolderInfosMapKey(fi.accountKey, fi.longName), fi);
		}
	}

	/**
	 * adds a collection of folderinfo's to the archive, updating existing ones
	 * as needed
	 */
	public void addFetchedFolderInfos(Collection<FolderInfo> fis)
	{
		// if a folderinfo with the same accountKey and longname already exists,
		// its lastSeenUID may need to be updated.

		// first organize a key -> folder info map in case we have a large # of
		// folders
		setupFolderInfosMap();

		for (FolderInfo fi : fis)
		{
			String key = getFolderInfosMapKey(fi.accountKey, fi.longName);
			FolderInfo existing_fi = fetchedFolderInfosMap.get(key);
			if (existing_fi != null)
			{
				if (existing_fi.lastSeenUID < fi.lastSeenUID)
					existing_fi.lastSeenUID = fi.lastSeenUID;
			}
			else
			{
				fetchedFolderInfos.add(fi);
				fetchedFolderInfosMap.put(key, fi);
			}
		}
	}

	public FolderInfo getFetchedFolderInfo(String accountID, String fullFolderName)
	{
		setupFolderInfosMap();
		return fetchedFolderInfosMap.get(getFolderInfosMapKey(accountID, fullFolderName));
	}

	/**
	 * returns last seen UID for the specified folder, -1 if its not been seen
	 * before
	 */
	public long getLastUIDForFolder(String accountID, String fullFolderName)
	{
		FolderInfo existing_fi = getFetchedFolderInfo(accountID, fullFolderName);
		if (existing_fi != null)
			return existing_fi.lastSeenUID;
		else
		{
			return -1L;
		}
	}

	public List<LinkInfo> postProcess()
	{
		return postProcess(allDocs, null);
	}

	/**
	 * should be called at the end of a series of calls to add doc to the
	 * archive. returns links. splits by groups if not null, otherwise by time.
	 * 
	 * @throws Exception
	 */
	public synchronized List<LinkInfo> postProcess(Collection<Document> docs, List<SimilarGroup<String>> groups)
	{
		// should we sort the messages by time here?

		log.info(indexer.computeStats());
		log.info(indexer.getLinks().size() + " links");
		// prepareAllDocs(docs, io);
		prepareDocClusters(groups);
		// TODO: should we recomputeCards? call nukeCards for now to invalidate
		// cards since archive may have been modified.
		indexer.summarizer.nukeCards();

		List<LinkInfo> links = indexer.getLinks();
		return links;
	}

	public synchronized void rollbackIndexWrites() throws IOException
	{
			indexer.rollbackWrites();
	}

	// replace subject with extracted names
	private static void replaceDescriptionWithNames(Collection<? extends Document> allDocs, Archive archive) throws Exception
	{
		for (Document d : allDocs) {
			if (!Util.nullOrEmpty(d.description)) {
				//log.info("Replacing description for docId = " + d.getUniqueId());
				// List<String> names =
				// Indexer.extractNames(d.description);
				// Collections.sort(names);
				// d.description = Util.join(names,
				// Indexer.NAMES_FIELD_DELIMITER);
              	d.description = edu.stanford.muse.ner.NER.retainOnlyNames(d.description, edu.stanford.muse.ner.NER.getNameOffsets(d, archive, false));
			}
		}
	}

	/**
	 * export archive with just the given docs to prepare for public mode.
	 * docsToExport should be a subset of what's already in the archive. returns
	 * true if successful.
	 */
	/*
	 * public boolean trimArchive(Collection<EmailDocument> docsToRetain) throws
	 * Exception { if (docsToRetain == null) return true; // return without
	 * doing anything
	 * 
	 * // exports messages in current filter (allEmailDocs) //HttpSession
	 * session = request.getSession(); Collection<Document> fullEmailDocs =
	 * this.getAllDocs(); Indexer indexer = sthis.indexer;
	 * 
	 * // compute which docs to remove vs. keep Set<Document> docsToKeep = new
	 * LinkedHashSet<Document>(docsToRetain); Set<Document> docsToRemove = new
	 * LinkedHashSet<Document>(); for (Document d: fullEmailDocs) if
	 * (!docsToKeep.contains(d)) docsToRemove.add(d);
	 * 
	 * // remove unneeded docs from the index
	 * indexer.removeEmailDocs(docsToRemove); // CAUTION: permanently change the
	 * index! this.setAllDocs(new ArrayList<Document>(docsToRetain)); return
	 * true; }
	 */

	/**
	 * a fresh archive is created under out_dir. name is the name of the session
	 * under it. blobs are exported into this archive dir. destructive! but
	 * should be so only in memory. original files on disk should be unmodified.
	 * 
	 * @param retainedDocs
	 * @throws Exception
	 */
	public synchronized String export(Collection<? extends Document> retainedDocs, final boolean exportInPublicMode, String out_dir, String name) throws Exception
	{
		if (Util.nullOrEmpty(out_dir))
			return null;
		File dir = new File(out_dir);
		if (dir.exists() && dir.isDirectory())
			log.warn("Overwriting existing directory '" + out_dir + "' (it may already exist)");
		else if (!dir.mkdirs()) {
			log.warn("Unable to create directory: " + out_dir);
			return null;
		}
		Archive.prepareBaseDir(out_dir);
		if (!exportInPublicMode && new File(baseDir + File.separator + LEXICONS_SUBDIR).exists())
			FileUtils.copyDirectory(new File(baseDir + File.separator + LEXICONS_SUBDIR), new File(out_dir + File.separator + LEXICONS_SUBDIR));
		if (new File(baseDir + File.separator + IMAGES_SUBDIR).exists())
			FileUtils.copyDirectory(new File(baseDir + File.separator + IMAGES_SUBDIR), new File(out_dir + File.separator + IMAGES_SUBDIR));
        //internal disambiguation cache
        if (new File(baseDir + File.separator + FEATURES_SUBDIR).exists())
            FileUtils.copyDirectory(new File(baseDir + File.separator + FEATURES_SUBDIR), new File(out_dir + File.separator + FEATURES_SUBDIR));
        if(new File(baseDir + File.separator + edu.stanford.muse.Config.AUTHORITY_ASSIGNER_FILENAME).exists())
            FileUtils.copyFile(new File(baseDir + File.separator + edu.stanford.muse.Config.AUTHORITY_ASSIGNER_FILENAME), new File(out_dir + File.separator + edu.stanford.muse.Config.AUTHORITY_ASSIGNER_FILENAME));

		// save the states that may get modified
		List<Document> savedAllDocs = allDocs;

		allDocs = new ArrayList<Document>(retainedDocs);
        if(exportInPublicMode)
            replaceDescriptionWithNames(allDocs, this);

		// copy index and if for public mode, also redact body and remove title
		// fields
		final boolean redact_body_instead_of_remove = true;
		Set<String> docIdSet = new LinkedHashSet<String>();
		for (Document d : allDocs)
			docIdSet.add(d.getUniqueId());
		final Set<String> retainedDocIds = docIdSet;
		Indexer.FilterFunctor filter = new Indexer.FilterFunctor() {
			@Override
			public boolean filter(org.apache.lucene.document.Document doc) {
				if (!retainedDocIds.contains(doc.get("docId")))
					return false;

				if (exportInPublicMode) {
					String text = null;
					if (redact_body_instead_of_remove) {
                        text = doc.get("body");
					}
					doc.removeFields("body");
                    doc.removeFields("body_original");

                    if (text != null) {
                  		String redacted_text = edu.stanford.muse.ner.NER.retainOnlyNames(text, edu.stanford.muse.ner.NER.getNameOffsets(doc, true));
                    	doc.add(new Field("body", redacted_text, Indexer.full_ft)); // this
																								// uses
																								// standard
																								// analyzer,
																								// not
																								// stemming
																								// because
																								// redacted
																								// bodys
																								// only
																								// have
																								// names.
					}
                    String title = doc.get("title");
                    doc.removeFields("title");
                    if(title!=null){
                        String redacted_title = edu.stanford.muse.ner.NER.retainOnlyNames(text, edu.stanford.muse.ner.NER.getNameOffsets(doc, true));
                        doc.add(new Field("title", redacted_title, Indexer.full_ft));
                    }
				}
				return true;
			}
		};
		if (exportInPublicMode) {
			List<Document> docs = this.getAllDocs();
			List<EmailDocument> eds = new ArrayList<EmailDocument>();
			for (Document doc : docs)
				eds.add((EmailDocument) doc);
			EmailUtils.maskEmailDomain(eds, this.addressBook);
		}

		indexer.copyDirectoryWithDocFilter(out_dir, filter);
		log.info("Completed exporting indexes");
		// write out the archive file
		SimpleSessions.saveArchive(out_dir, name, this); // save .session file. note: no blobs saved, they don't need to change.
		log.info("Completed saving archive object");

		// save the blobs in a new blobstore
		if (!exportInPublicMode) {
			log.info("Starting to export blobs");
			Set<Blob> blobsToKeep = new LinkedHashSet<Blob>();
			for (Document d : allDocs)
				if (d instanceof EmailDocument)
					if (!Util.nullOrEmpty(((EmailDocument) d).attachments))
						blobsToKeep.addAll(((EmailDocument) d).attachments);
			String blobsDir = out_dir + File.separatorChar + BLOBS_SUBDIR;
			new File(blobsDir).mkdirs();
			((FileBlobStore) blobStore).createCopy(blobsDir, blobsToKeep);
			log.info("Completed exporting blobs");
		}

		// restore states
		allDocs = savedAllDocs;

		return out_dir;
	}

	public List<Document> docsWithThreadId(long threadID) {
		List<Document> result = new ArrayList<Document>();
		for (Document ed : allDocs) {
			if (((EmailDocument) ed).threadID == threadID)
				result.add(ed);
		}
		return result;
	}

	public String getStats()
	{
		// note: this is a legacy method that does not use the archivestats
		// object above
		StringBuilder sb = new StringBuilder(allDocs.size() + " original docs with " + ownerEmailAddrs.size() + " email addresses " + ownerNames.size()
				+ " names for owner ");
		if (addressBook != null)
			sb.append(addressBook.getStats() + "\n");
		sb.append(indexer.computeStats() + "\n" + indexer.getLinks().size() + " links");
		return sb.toString();
	}

	/**
	 * @args
	 *       ldoc - lucene doc corresponding to the content
	 *       s - content of the doc
	 *       Date
	 *       docId - Uniquedocid of the emaildocument
	 *       sensitive - if set, will highlight any sensitive info in the mails
	 *       that matches one of the regexs specified in presetregexps
	 *       highlighttermsUnstemmed - terms to highlight in the content (for ex
	 *       lexicons)
	 *       highlighttermsstemmed - entities to highlight, generally are names
	 *       that one doesn't wish to be stemmed.
	 *       entitiesWithId - authorisedauthorities, for annotation
	 *       showDebugInfo - enabler to show debug info
	 * @return html for the given terms, with terms highlighted by the
	 *         indexer.
	 *         if IA_links is set, points links to the Internet archive's
	 *         version
	 *         of the
	 *         page. docId is used to initialize a new view created by
	 *         clicking on
	 *         a
	 *         link within this message, date is used to create the link to
	 *         the IA
	 */
	public String annotate(org.apache.lucene.document.Document ldoc, String s, Date date, String docId, Boolean sensitive, Set<String> highlightTermsStemmed, Set<String> highlightTermsUnstemmed,
			Map<String, Entity> entitiesWithId, boolean IA_links, boolean showDebugInfo)
	{
		getAllDocs();
		try {
			Summarizer summarizer = new Summarizer(indexer);

			s = Highlighter.getHTMLAnnotatedDocumentContents(s, (IA_links ? date : null), docId, sensitive, highlightTermsStemmed, highlightTermsUnstemmed,
					entitiesWithId, null, summarizer.importantTermsCanonical /*
																			 * unstemmed
																			 * because
																			 * we
																			 * are
																			 * only
																			 * using
																			 * names
																			 */, showDebugInfo);

			//indexer
			//	.getHTMLAnnotatedDocumentContents(s, (IA_links ? date : null), docId, searchTerms, isRegexSearch, highlightTermsStemmed, highlightTermsUnstemmed, entitiesWithId);
		} catch (Exception e) {
			e.printStackTrace();
			log.warn("indexer failed to annotate doc contents " + Util.stackTrace(e));
		}

		return s;
	}

	public String annotate(String s, Date date, String docId, Boolean sensitive, Set<String> highlightTermsStemmed, Set<String> highlightTermsUnstemmed,
			Map<String, Entity> entitiesWithId, boolean IA_links, boolean showDebugInfo) {
		return annotate(null, s, date, docId, sensitive, highlightTermsStemmed, highlightTermsUnstemmed,
				entitiesWithId, IA_links, showDebugInfo);
	}

	// need to remove current dependency on indexer (only for debug).
	public Pair<StringBuilder, Boolean> getHTMLForContents(Document d, Date date, String docId, Boolean sensitive, Set<String> highlightTermsStemmed,
			Set<String> highlightTermsUnstemmed, Map<String, Map<String, Short>> authorisedEntities, boolean IA_links, boolean inFull, boolean showDebugInfo) throws Exception
	{
		String type = "person", otype = "organization", ptype = "location";
		//not using filtered entities here as it looks weird especially in the redaction mode not to
		// have a word not masked annotated. It is counter-intuitive.
        List<String> cpeople = indexer.getAllEntitiesInDoc(d, NER.EPER);
		List<String> corgs = indexer.getAllEntitiesInDoc(d, NER.EORG);
		List<String> cplaces = indexer.getAllEntitiesInDoc(d, NER.ELOC);
        Set<String> acrs = Tokenizer.getAcronyms(indexer.getContents(d, false));

		List<String> e = indexer.getAllEntitiesInDoc(d, type);
		List<String> orgs = indexer.getAllEntitiesInDoc(d, otype);
		List<String> places = indexer.getAllEntitiesInDoc(d, ptype);
		String contents = indexer.getContents(d, false);
		org.apache.lucene.document.Document ldoc = indexer.getDoc(d);
		if (ldoc == null)
			System.err.println("lucenedoc is null for: " + d.getUniqueId() + " but the content is " + (contents == null ? "null" : "not null"));

		List<String> entities = new ArrayList<String>();

		if(cpeople == null)
			cpeople = new ArrayList<String>();
		if(cplaces == null)
			cplaces = new ArrayList<String>();
		if(corgs == null)
			corgs = new ArrayList<String>();
        if(e == null)
			e = new ArrayList<String>();
		if(orgs == null)
			orgs = new ArrayList<String>();
		if(places == null)
			places = new ArrayList<String>();
		if(acrs == null)
			acrs = new HashSet<String>();

		entities.addAll(cpeople);
		entities.addAll(cplaces);
		entities.addAll(corgs);
		entities.addAll(e);
		entities.addAll(orgs);
		entities.addAll(places);
		entities.addAll(acrs);

		// Contains all entities and id if it is authorised else null
		Map<String, Entity> entitiesWithId = new HashMap<String, Entity>();
		for (String entity : entities) {
			Set<String> types = new HashSet<String>();
			if (cpeople.contains(entity))
				types.add("cp");
			if (cplaces.contains(entity))
				types.add("cl");
			if (corgs.contains(entity))
				types.add("co");
			if (e.contains(entity))
				types.add("person");
			if (orgs.contains(entity))
				types.add("org");
			if (places.contains(entity))
				types.add("place");
			if (acrs.contains(entity))
				types.add("acr");
			String ce = IndexUtils.canonicalizeEntity(entity);
			if (ce == null)
				continue;
			if (authorisedEntities != null && authorisedEntities.containsKey(ce)) {
				entitiesWithId.put(entity, new Entity(entity, authorisedEntities.get(ce), types));
			}
			else
				entitiesWithId.put(entity, new Entity(entity, null, types));
		}

        //dont want more button anymore
		boolean overflow = false;
//		if (!inFull && contents.length() > 4999) {
//			contents = Util.ellipsize(contents, 4999);
//			overflow = true;
//		}
		String htmlContents = annotate(ldoc, contents, date, docId, sensitive, highlightTermsStemmed, highlightTermsUnstemmed, entitiesWithId, IA_links, showDebugInfo);
		//also add NER offsets for debugging
//		htmlContents += "<br>Offsets: <br>";
//		List<Triple<String,Integer, Integer>> triples = edu.stanford.muse.ner.NER.getNamesOffsets(ldoc);
//		for(Triple<String,Integer,Integer> t: triples)
//			htmlContents += t.getFirst()+" <"+t.getSecond()+", "+t.getThird()+"><br>";

		if (ModeConfig.isPublicMode())
			htmlContents = Util.maskEmailDomain(htmlContents);

		StringBuilder sb = new StringBuilder();
		sb.append(htmlContents);
		return new Pair<StringBuilder, Boolean>(sb, overflow);
	}

	/* break up docs into clusters, based on existing docClusters */
	public List<MultiDoc> clustersForDocs(Collection<? extends Document> docs)
	{
		//TODO: whats the right thing to do when docClusters is null?
		if(docClusters == null){
			List<MultiDoc> new_mDocs = new ArrayList<MultiDoc>();
			new_mDocs.add(null);
			for (Document d : docs)
			{
				MultiDoc new_mDoc = new MultiDoc(-1,"dummy");
				new_mDoc.add(d);
				new_mDocs.set(0, new_mDoc);
			}
			return new_mDocs;
		}

		Map<Document, Integer> map = new LinkedHashMap<Document, Integer>();
		int i = 0;
		for (MultiDoc mdoc : docClusters)
		{
			for (Document d : mdoc.docs)
				map.put(d, i);
			i++;
		}

		List<MultiDoc> new_mDocs = new ArrayList<MultiDoc>();
		for (@SuppressWarnings("unused")
		MultiDoc md : docClusters)
			new_mDocs.add(null);

		for (Document d : docs)
		{
			int x = map.get(d);
			MultiDoc new_mDoc = new_mDocs.get(x);
			if (new_mDoc == null)
			{
				MultiDoc original = docClusters.get(x);
				new_mDoc = new MultiDoc(original.getUniqueId(), original.description);
				new_mDocs.set(x, new_mDoc);
			}
			new_mDoc.add(d);
		}

		List<MultiDoc> result = new ArrayList<MultiDoc>();
		for (MultiDoc md : new_mDocs)
			if (md != null)
				result.add(md);

		return result;
	}

	public String toString()
	{
		// be defensive here -- some of the fields may be null
		StringBuilder sb = new StringBuilder();
		if (allDocs != null)
			sb.append("Archive with #docs: " + allDocs.size() + " addressbook: " + addressBook + " " + getStats() + " ");
		else
			sb.append("Null docs");
		if (indexer != null)
		{
			if (indexer.stats != null)
				sb.append(Util.fieldsToString(indexer.stats, false));
			else
				sb.append("Null indexer-stats");
		} else
			sb.append("Null indexer");
		return sb.toString();
	}

	private Map<String, Integer> countNames()
	{
		Map<String, Integer> name_count = new LinkedHashMap<String, Integer>();
		for (Document d : getAllDocs()) {
			Set<String> names = indexer.getNames(d, Indexer.QueryType.FULL);
			// log.info("Names = " + Util.joinSort(names, "|"));
			for (String n : names) {
				n = n.trim();
				if (n.length() == 0)
					continue;
				if (name_count.containsKey(n))
					name_count.put(n, name_count.get(n) + 1);
				else
					name_count.put(n, 1);
			}
		}

		// for (Map.Entry<String, Integer> e : entries) {
		// log.info("NameCount:" + e.getKey() + "|" + e.getValue());
		// }
		return name_count;
	}

	// return top names whose frequency percentage is higher than
	// "threshold_pct", limited to maximum "n" names.
	public List<Map.Entry<String, Integer>> getTopNames(int threshold_pct, int n, boolean sort_by_names)
	{
		if (topNames == null) {
			// sort by count
			topNames = new ArrayList<Map.Entry<String, Integer>>(countNames().entrySet());
			Collections.sort(topNames, new Comparator<Map.Entry<String, Integer>>() {
				public int compare(Map.Entry<String, Integer> e1, Map.Entry<String, Integer> e2) {
					return e2.getValue().compareTo(e1.getValue());
				}
			});

			// rescale the count to be in the range of 0-100
			if (topNames.size() > 0) {
				int max_count = topNames.get(0).getValue();
				for (Map.Entry<String, Integer> e : topNames)
					e.setValue((int) (Math.pow(e.getValue().doubleValue() / max_count, 0.25) * 100));
			}
		}

		int count = 0;
		for (Map.Entry<String, Integer> e : topNames) {
			if (e.getValue() < threshold_pct || count == n)
				break;
			count++;
		}

		List<Map.Entry<String, Integer>> result = new ArrayList<Map.Entry<String, Integer>>(topNames.subList(0, count));
		if (sort_by_names) {
			// NOTE: this sort triggers java.lang.AbstractMethodError at
			// java.util.Arrays.mergeSort when placed in JSP.
			Collections.sort(result, new Comparator<Map.Entry<String, Integer>>() {
				public int compare(Map.Entry<String, Integer> e1, Map.Entry<String, Integer> e2) {
					return e1.getKey().compareTo(e2.getKey());
				}
			});
		}

		return result;
	}

	public void assignThreadIds() {
		Collection<Collection<EmailDocument>> threads = EmailUtils.threadEmails((Collection) allDocs);
		int thrId = 1; // note: valid thread ids must be > 1
		for (Collection<EmailDocument> thread : threads)
		{
			for (EmailDocument doc : thread)
				doc.threadID = thrId;
			thrId++;
		}
	}

	public void postDeserialized(String baseDir, boolean readOnly) throws CorruptIndexException, LockObtainFailedException, IOException
	{
		if (ModeConfig.isPublicMode())
			setGroupAssigner(null);

		if (indexer != null)
			log.info(indexer.computeStats());

		indexer.setBaseDir(baseDir);
		indexer.setupForRead();

        if (!readOnly)
			indexer.setupForWrite();
		// getTopNames();
       if (addressBook != null) {
			// addressBook.reassignContactIds();
			addressBook.organizeContacts(); // is this idempotent?
		}

		if (lexiconMap == null) {
			lexiconMap = createLexiconMap(baseDir);
		}

		// recompute... sometimes the processing metadata may be stale, because some messages have been redacted at export.
		processingMetadata.numPotentiallySensitiveMessages = numMatchesPresetQueries();
	}

	public void merge(Archive other) {
		for (Document doc : other.getAllDocs()) {
			if (!this.containsDoc(doc))
				this.addDoc(doc, other.getContents(doc, /* originalContentOnly */false));
		}

		addressBook.merge(other.addressBook);
		this.processingMetadata.merge(other.processingMetadata);
	}

	private static Map<String, Lexicon> createLexiconMap(String baseDir) throws FileNotFoundException, IOException
	{
		String lexDir = baseDir + File.separatorChar + LEXICONS_SUBDIR;
		Map<String, Lexicon> map = new LinkedHashMap<String, Lexicon>();
		File lexDirFile = new File(lexDir);
		if (!lexDirFile.exists()) {
			log.warn("'lexicons' directory is missing from archive");
		} else {
			for (File f : lexDirFile.listFiles(new Util.MyFilenameFilter(null, Lexicon.LEXICON_SUFFIX))) {
				String name = Lexicon.lexiconNameFromFilename(f.getName());
				if (!map.containsKey(name)) {
					map.put(name.toLowerCase(), new Lexicon(lexDir, name));
				}
			}
		}
		return map;
	}

	public Lexicon getLexicon(String lexName)
	{
		// lexicon map could be stale, re-read it
		try {
			lexiconMap = createLexiconMap(baseDir);
		} catch (Exception e) {
			Util.print_exception("Error trying to read list of lexicons", e, log);
		}
		return lexiconMap.get(lexName.toLowerCase());
	}

	public Set<String> getAvailableLexicons()
	{
		// lexicon map could be stale, re-read it
		try {
			lexiconMap = createLexiconMap(baseDir);
		} catch (Exception e) {
			Util.print_exception("Error trying to read list of lexicons", e, log);
		}
		if (lexiconMap == null)
			return new LinkedHashSet<String>();
		return Collections.unmodifiableSet(lexiconMap.keySet());
	}

	public int numMatchesPresetQueries() {
		return indexer.numMatchesPresetQueries();
	}

	public void addStats(FetchStats as) {
		allStats.add(as);
	}

	public Collection<String> getDataErrors()
	{
		Collection<String> result = new LinkedHashSet<String>();

		for (FetchStats as: allStats) {
			Collection<String> asErrors = as.dataErrors;
			if (asErrors != null)
				result.addAll(asErrors);
		}
		return result;
	}

	public static void main(String[] args) {
		try {
			String docId = "/Users/sidharthanandan/Downloads/palin.mbox-1020";
			String userDir = System.getProperty("user.home") + File.separator + "epadd-appraisal" + File.separator + "user";
			Archive archive = SimpleSessions.readArchiveIfPresent(userDir);
			Document doc = archive.indexer.docForId(docId);
			Pair<StringBuilder, Boolean> p = archive.getHTMLForContents(doc, null, docId, false, null, null, null, false, false, true);
			System.err.println("<link rel='stylesheet' href='epadd.css'>" + p.first);
			//System.err.println(li.getContents(li.docForId(docId), true) + "<br>");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
