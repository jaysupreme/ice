package edu.nyu.jet.ice.entityset;

import AceJet.Ace;
import AceJet.Gazetteer;
import Jet.Control;
import edu.nyu.jet.ice.models.IcePreprocessor;
import edu.nyu.jet.ice.uicomps.Ice;
import edu.nyu.jet.ice.utils.FileNameSchema;
import edu.nyu.jet.ice.utils.ProgressMonitorI;
import Jet.JetTest;
import Jet.Lex.Lexicon;
import Jet.Lex.Stemmer;
import Jet.Lisp.FeatureSet;
import Jet.Parser.SyntacticRelation;
import Jet.Parser.SyntacticRelationSet;
import Jet.Tipster.Annotation;
import Jet.Tipster.Document;
import Jet.Tipster.ExternalDocument;

import java.io.*;
import java.util.*;

import gnu.trove.TObjectIntHashMap;
import opennlp.model.DataIndexer;
import opennlp.model.Event;
import opennlp.model.FileEventStream;
import opennlp.model.OnePassDataIndexer;
import org.la4j.vector.sparse.CompressedVector;

/**
 * Index entity feature vectors for set expansion
 *
 * @author yhe
 * @version 1.0
 */
public class EntitySetIndexer {
    private static ProgressMonitorI defaultProgressMonitor = null;
    private static final String[] stopWordsArr = new String[]{
            "a", "an", "and", "are", "as", "at", "be", "but", "by",
            "for", "if", "in", "into", "is", "it",
            "no", "not", "of", "on", "or", "such",
            "that", "the", "their", "then", "there", "these",
            "they", "this", "to", "was", "will", "with"
    };
    private static final Set<String> stopWords = new HashSet<String>();
    {
        for (String word : stopWordsArr) {
            stopWords.add(word);
        }
    }
    private static Stemmer stemmer = Stemmer.getDefaultStemmer();

    public static void setDefaultProgressMonitor(ProgressMonitorI progressMonitor) {
        EntitySetIndexer.defaultProgressMonitor = progressMonitor;
    }

	private ProgressMonitorI progressMonitor;
	private Set<String> words = new HashSet<String>();

	public EntitySetIndexer() {}

	public ProgressMonitorI getProgressMonitor() {
		return progressMonitor;
	}

	public void setProgressMonitor(ProgressMonitorI progressMonitor) {
		this.progressMonitor = progressMonitor;
	}

	public static void main(String[] args) {
		EntitySetIndexer indexer = new EntitySetIndexer();
		indexer.setProgressMonitor(defaultProgressMonitor);
		indexer.run(args[0], args[1], Double.valueOf(args[2]), args[3], args[4], args[5], args[6], args[7]);
    }

	public void run(String termFile, String type, double cutoff, String propsFile, String docList, String inputDir, String inputSuffix, String outputFile) {
		try {
			String line;
			BufferedReader r = new BufferedReader(new FileReader(termFile));
			while ((line = r.readLine()) != null) {
				Entity entity = Entity.fromString(line);
				if (entity == null) continue;
				if (entity.getScore() > cutoff &&
						entity.getType().equals(type) &&
						entity.getText().trim().length() > 1) {
					words.add(entity.getText().replaceAll("\\s+", " ").trim());
				}
			}
			// Processing documents
			if (progressMonitor != null) {
				progressMonitor.setProgress(0);
				progressMonitor.setNote("Initializing Jet");
			}
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}
			JetTest.initializeFromConfig(propsFile);
			Lexicon.clear();
			for (String word : words) {
				Lexicon.addEntry(Gazetteer.splitAtWS(word),
						new FeatureSet(),
						"term");
			}

			String docName;
			int docCount = 0;
			//DictionaryBasedNamedEntityPostprocessor nePostprocessor =
			//        new DictionaryBasedNamedEntityPostprocessor(propsFile);
			BufferedReader docListReader = new BufferedReader(new FileReader (docList));
			List<Event> allEvents = new ArrayList<Event>();
			boolean isCanceled = false;
			while ((docName = docListReader.readLine()) != null) {
				docCount++;
				String inputFile = docName;
                if (!("*".equals(inputSuffix.trim()))) {
                    inputFile = inputFile + "." + inputSuffix;
                }
				System.out.println ("\nProcessing document " + docCount + ": " + inputFile);
				ExternalDocument doc = new ExternalDocument ("sgml", inputDir, inputFile);
				doc.setAllTags(true);
				doc.open();
				// process document
				Ace.monocase = Ace.allLowerCase(doc);
				Control.processDocument(doc, null, false, docCount);
				System.err.println("Jet control finished");

				SyntacticRelationSet syntacticRelationSet = IcePreprocessor.loadSyntacticRelationSet(
						FileNameSchema.getPreprocessCacheDir(Ice.selectedCorpusName),
						inputDir,
						inputFile);
				IcePreprocessor.loadPOS(doc,
						FileNameSchema.getPreprocessCacheDir(Ice.selectedCorpusName),
						inputDir,
						inputFile);

				List<Annotation> sentences = doc.annotationsOfType("sentence");
				if (sentences == null) continue;
				for (Annotation sentence : sentences) {
					allEvents.addAll(processSentence(sentence, doc, syntacticRelationSet, words));
				}
				if (progressMonitor != null) {
					if (progressMonitor.isCanceled()) {
						isCanceled = true;
						break;
					}
					progressMonitor.setProgress(docCount);
					progressMonitor.setNote(docCount + " files processed");
				}
			}
			// Indexing
			if (!isCanceled) {
				if (progressMonitor != null) {
					progressMonitor.setNote("Start indexing features... Unable to cancel at this stage.");
				}
				PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter("temp.events")));
				for (Event event : allEvents) {
					pw.println(FileEventStream.toLine(event).trim());
				}
				pw.close();
				DataIndexer indexer = new OnePassDataIndexer(new FileEventStream("temp.events"), 0);
				String[] contextLabels = indexer.getPredLabels();
				TObjectIntHashMap contextMap = new TObjectIntHashMap();
				for (int i = 0; i < contextLabels.length; i++) {
					String contextLabel = contextLabels[i];
					contextMap.put(contextLabel, i+1);          /* TObjectIntHashMap will return 0 for not found */
				}
				Map<String, org.la4j.vector.Vector> counter = new HashMap<String, org.la4j.vector.Vector>();
				for (Event e : allEvents) {
					String word = e.getOutcome();
					if (!counter.containsKey(word)) {
						counter.put(word, new CompressedVector(contextLabels.length));
					}
					org.la4j.vector.Vector v = counter.get(word);
					for (String context : e.getContext()) {
						int contextIdx = contextMap.get(context)-1;
						v.set(contextIdx, v.get(contextIdx) + 1);
					}
				}
                updateContextWithPMI(counter);
				// Write to index
				PrintWriter w = new PrintWriter(new FileWriter(outputFile));
				PrintWriter wInverse = new PrintWriter(new FileWriter(outputFile + ".info"));
				w.println(contextLabels.length + 1);
				for (String word : counter.keySet()) {
					writeVector(word, counter.get(word), w);
					writeVectorInfo(word, counter.get(word), contextLabels, wInverse);
				}
				w.close();
				wInverse.close();
				if (progressMonitor != null) {
					progressMonitor.setProgress(docCount + 5);
					progressMonitor.setNote("Start indexing features... done.");
				}
			}
		}
		catch (Exception e) {
			System.err.println("Jet.ExpandEntitySet.EntitySetIndexer countFile type cutoff " +
					"propsFile docList inputDir inputSuffix outputFile");
			e.printStackTrace();
		}
	}

    private static void updateContextWithPMI(Map<String, org.la4j.vector.Vector> counter) {
        if (counter.size() == 0) return;
        double denominator = 0;
        int featureLength    = 0;
        for (org.la4j.vector.Vector v : counter.values()) {
            denominator += v.sum();
            if (featureLength == 0) featureLength = v.length();
        }
        org.la4j.vector.Vector priors = new CompressedVector(featureLength);
        for (org.la4j.vector.Vector v : counter.values()) {
            priors = priors.add(v);
        }
        priors = priors.divide(denominator);
        for (org.la4j.vector.Vector v : counter.values()) {
            denominator = v.sum();
            for (int i = 0; i < v.length(); i++) {
                double val   = v.get(i);
                double prior = priors.get(i);
                if (val != 0 && prior != 0) {
                    v.set(i, Math.log(val/denominator/prior));
                }
            }
        }
    }

    private static void writeVector(String s, org.la4j.vector.Vector v, PrintWriter w) {
        w.print(s);
        for (int i = 0; i < v.length(); i++) {
            if (v.get(i) > 0.1) {
                w.print("\t" + i + ":" + String.format("%.8f", v.get(i)));
            }
        }
        w.println();
    }

    private static void writeVectorInfo(String s, org.la4j.vector.Vector v, String[] contextLabels, PrintWriter w) {
        w.print(s);
        for (int i = 0; i < v.length(); i++) {
            if (v.get(i) > 0.1) {
                w.print("\t" + contextLabels[i] + ":" + String.format("%.8f", v.get(i)));
            }
        }
        w.println();
    }

    private static List<Event> processSentence(Annotation sentence, Document doc, SyntacticRelationSet s, Set<String> wordSet) {
        List<Annotation> terms = doc.annotationsOfType("term", sentence.span());
        if (terms == null) return new ArrayList<Event>();
        List<Event> result = new ArrayList<Event>();
        for (int i = 0; i < terms.size(); i++) {
            String tokenString = doc.text(terms.get(i)).replaceAll("\\s+", " ").trim();
			Annotation termHead = IcePreprocessor.findTermHead(doc, terms.get(i), s);
			if (wordSet.contains(tokenString)) {
                List<String> contextList = new ArrayList<String>();
                SyntacticRelation syn = s.getRelationTo(termHead.start());
                if (syn != null) {
                    if (!stopWords.contains(syn.sourceWord.trim())) {
//                        contextList.add("wordFrom=" + stemmer.getStem(syn.sourceWord, "NULL"));
                        contextList.add(String.format("from_%s_%s", syn.type, stemmer.getStem(syn.sourceWord.trim(), "NULL")));
                    }
//                    contextList.add("wordFromPOS=" + syn.sourcePos);
//                    contextList.add("fromRelationName=" + syn.type);
                }
                List<Annotation>  posCands = doc.annotationsAt(termHead.start(), "tagger");
                if (posCands != null) {
                    String pos = (String)posCands.get(0).get("cat");
                    contextList.add("pos=" + pos);
                }
                SyntacticRelationSet fromSet = s.getRelationsFrom(termHead.start());
                if (fromSet != null) {
                    for (int j = 0; j < fromSet.size(); j++) {
                        SyntacticRelation r = fromSet.get(j);
                        if (!stopWords.contains(r.targetWord.trim())) {
//                            contextList.add("wordTo=" + stemmer.getStem(r.targetWord, "NULL"));
                            contextList.add(String.format("to_%s_%s", r.type, stemmer.getStem(r.targetWord.trim(), "NULL")));
                        }
//                        contextList.add("wordToPOS=" + r.targetPos);
//                        contextList.add("toRelationName=" + r.type);
                    }
                }
                Event event = new Event(tokenString,
                        contextList.toArray(new String[contextList.size()]));
				if (contextList.size() > 0) {
					result.add(event);
				}
            }
        }
        return result;
    }
}