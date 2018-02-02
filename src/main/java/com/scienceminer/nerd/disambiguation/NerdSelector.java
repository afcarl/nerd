package com.scienceminer.nerd.disambiguation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.regex.*;

import com.scienceminer.nerd.utilities.NerdConfig;
import com.scienceminer.nerd.embeddings.SimilarityScorer;

import org.grobid.core.lang.Language;
import org.grobid.core.utilities.TextUtilities;
import org.grobid.trainer.LabelStat;
import org.grobid.core.analyzers.GrobidAnalyzer;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.UnicodeUtil;

import com.scienceminer.nerd.exceptions.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.scienceminer.nerd.kb.model.*;
import com.scienceminer.nerd.kb.LowerKnowledgeBase;
import com.scienceminer.nerd.features.*;
import com.scienceminer.nerd.training.*;
import com.scienceminer.nerd.mention.*;
import com.scienceminer.nerd.utilities.mediaWiki.MediaWikiParser;
import com.scienceminer.nerd.evaluation.*;

import smile.regression.*;

/**
 * A machine learning model for estimating if a candidate should be selected or not as the
 * entity realized by a mention. This model is applied after the NerdRanker and make a 
 * decision based on the ranked entities and the context. 
 */
public class NerdSelector extends NerdModel {

	private static final Logger LOGGER = LoggerFactory.getLogger(NerdSelector.class);

	// selected feature set for this particular selector
	private FeatureType featureType;

	// ranker model files
	private static String MODEL_PATH_LONG = "data/models/selector-long";

	private LowerKnowledgeBase wikipedia = null;

	public NerdSelector(LowerKnowledgeBase wikipedia) throws Exception {
		super();
		this.wikipedia = wikipedia;
		
		NerdConfig conf = wikipedia.getConfig();

		//model = MLModel.GRADIENT_TREE_BOOST;
		model = MLModel.RANDOM_FOREST;
		featureType = FeatureType.SIMPLE;
		
		GenericSelectionFeatureVector feature = getNewFeature();
		arffParser.setResponseIndex(feature.getNumFeatures()-1);
	}

	public GenericSelectionFeatureVector getNewFeature() {
		GenericSelectionFeatureVector feature = null;
		if (featureType == FeatureType.SIMPLE)
			feature = new SimpleSelectionFeatureVector();
		else if (featureType == FeatureType.BASELINE)
			feature = new BaselineSelectionFeatureVector();
		else if (featureType == FeatureType.NERD)
			feature = new NerdSelectionFeatureVector();
		return feature;
	}

	public double getProbability(double nerd_score, 
								double prob_anchor_string, 
								double prob_c,
								int nb_tokens, 
								double relatedness,
								boolean inContext,
								boolean isNe,
								double tf_idf, 
								double dice) throws Exception {
		if (forest == null) {
			// load model
			File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
			if (!modelFile.exists()) {
                LOGGER.debug("Invalid model file for nerd selector.");
			}
			String xml = FileUtils.readFileToString(modelFile, "UTF-8");


			if (model == MLModel.RANDOM_FOREST)
				forest = (RandomForest)xstream.fromXML(xml);
			else
				forest = (GradientTreeBoost)xstream.fromXML(xml);
			if (attributeDataset != null) 
				attributes = attributeDataset.attributes();
			else {
				StringBuilder arffBuilder = new StringBuilder();
				GenericSelectionFeatureVector feat = getNewFeature();
				arffBuilder.append(feat.getArffHeader()).append("\n");
				arffBuilder.append(feat.printVector());
				String arff = arffBuilder.toString();
				attributeDataset = arffParser.parse(IOUtils.toInputStream(arff, "UTF-8"));
				attributes = attributeDataset.attributes();
				attributeDataset = null;
			}
			LOGGER.info("Model for nerd selector loaded: " +
				MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model");
		}

		GenericSelectionFeatureVector feature = getNewFeature();
		feature.nerd_score = nerd_score;
		feature.prob_anchor_string = prob_anchor_string;
		feature.prob_c = prob_c;
		feature.nb_tokens = nb_tokens;
		feature.relatedness = relatedness;
		feature.inContext = inContext;
		feature.isNe = isNe;
		feature.tf_idf = tf_idf;
		feature.dice = dice;

		double[] features = feature.toVector(attributes);
		//smile.math.Math.setSeed(12345);
		final double score = forest.predict(features);

        LOGGER.debug("selector: " +
                "score: " + score + ", " +
                "ranker score: "+nerd_score+", " +
                "link prob: "+prob_anchor_string+", " +
                "ranker score: "+prob_c+", " +
                "words size: "+nb_tokens+", " +
                "relatedness: "+relatedness+", " +
                "candidate in context? : "+inContext+", " +
                "NE? : "+isNe+", " +
                "tf/idf : "+ tf_idf+", " +
                "dice : "+dice+", "
        );

        return score;
	}

	public void saveModel() throws Exception {
		LOGGER.info("saving model");
		// save the model with XStream
		String xml = xstream.toXML(forest);
		File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
		if (!modelFile.exists()) {
            LOGGER.debug("Invalid file for saving author filtering model.");
		}
		FileUtils.writeStringToFile(modelFile, xml, "UTF-8");
		LOGGER.info("Model saved under " + modelFile.getPath());
	}

	public void loadModel() throws IOException, Exception {
		LOGGER.info("loading model");
		// load model
		File modelFile = new File(MODEL_PATH_LONG+"-"+wikipedia.getConfig().getLangCode()+".model"); 
		if (!modelFile.exists()) {
        	LOGGER.debug("Model file for nerd selector does not exist.");
        	throw new NerdResourceException("Model file for nerd selector does not exist.");
		}
		String xml = FileUtils.readFileToString(modelFile, "UTF-8");
//		smile.math.Math.setSeed(12345);
		if (model == MLModel.RANDOM_FOREST)
			forest = (RandomForest)xstream.fromXML(xml);
		else
			forest = (GradientTreeBoost)xstream.fromXML(xml);
		LOGGER.debug("Model for nerd ranker loaded.");
	}

	public void trainModel() {
		if (attributeDataset == null) {
			LOGGER.debug("Training data for nerd selector has not been loaded or prepared");
			throw new NerdResourceException("Training data for nerd selector has not been loaded or prepared");
		}
		LOGGER.info("building model");
		double[][] x = attributeDataset.toArray(new double[attributeDataset.size()][]);
		double[] y = attributeDataset.toArray(new double[attributeDataset.size()]);
		
		long start = System.currentTimeMillis();
		//setting the seed
		smile.math.Math.setSeed(12345);

		if (model == MLModel.RANDOM_FOREST)
			forest = new RandomForest(attributeDataset.attributes(), x, y, 200);
		else {
			//nb trees: 500, maxNodes: 6, srinkage: 0.05, subsample: 0.7
			forest = new GradientTreeBoost(attributeDataset.attributes(), x, y, 
				GradientTreeBoost.Loss.LeastAbsoluteDeviation, 1000, 6, 0.05, 0.7);
		}
        LOGGER.info("NERD selector model created in " +
			(System.currentTimeMillis() - start) / (1000.00) + " seconds");
	}

	public void train(ArticleTrainingSample articles, File file) throws Exception {
		if (articles.size() == 0) {
			return;
		}
		StringBuilder arffBuilder = new StringBuilder();
		GenericSelectionFeatureVector feat = getNewFeature();
		arffBuilder.append(feat.getArffHeader()).append("\n");
		FileUtils.writeStringToFile(file, arffBuilder.toString(), StandardCharsets.UTF_8);
		int nbArticle = 0;
		positives = 1;
		negatives = 0;
		NerdRanker ranker = new NerdRanker(wikipedia);
		for (Article article : articles.getSample()) {
			LOGGER.info("Training on " + (nbArticle+1) + "  / " + articles.getSample().size());
			arffBuilder = new StringBuilder();
			if (article instanceof CorpusArticle)
				arffBuilder = trainCorpusArticle(article, arffBuilder, ranker);	
			else
				arffBuilder = trainWikipediaArticle(article, arffBuilder, ranker);	
			FileUtils.writeStringToFile(file, arffBuilder.toString(), StandardCharsets.UTF_8, true);
			nbArticle++;
		}
		//arffDataset = arffBuilder.toString();
		arffDataset = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
		//logger.info(arffDataset);
		attributeDataset = arffParser.parse(IOUtils.toInputStream(arffDataset, StandardCharsets.UTF_8));
		LOGGER.info("Training data saved under " + file.getPath());
	}

	private StringBuilder trainWikipediaArticle(Article article, 
									StringBuilder arffBuilder, 
									NerdRanker ranker) throws Exception {
		LOGGER.info(" - training " + article);
		List<NerdEntity> refs = new ArrayList<NerdEntity>();
		String lang = wikipedia.getConfig().getLangCode();
		String content = MediaWikiParser.getInstance().toTextWithInternalLinksArticlesOnly(article.getFullWikiText(), lang);
		content = content.replace("''", "");
		StringBuilder contentText = new StringBuilder(); 
		//logger.info(content);
		Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]"); 
		Matcher linkMatcher = linkPattern.matcher(content);

		// split references into ambiguous and unambiguous
		int head = 0;
		while (linkMatcher.find()) {			
			String linkText = content.substring(linkMatcher.start()+2, linkMatcher.end()-2);
			if (head != linkMatcher.start())
				contentText.append(content.substring(head, linkMatcher.start()));

			String labelText = linkText;
			String destText = linkText;

			int pos = linkText.lastIndexOf('|');
			if (pos>0) {
				destText = linkText.substring(0, pos);
				labelText = linkText.substring(pos+1);
			} else {
				// labelText and destText are the same, but we could have an anchor #
				int pos2 = linkText.indexOf('#');
				if (pos2 != -1) {
					destText = linkText.substring(0,pos2);
				} else {
					destText = linkText;
				}
				labelText = destText;
			}
			contentText.append(labelText);

			head = linkMatcher.end();
			
			Label label = new Label(wikipedia.getEnvironment(), labelText);
			Label.Sense[] senses = label.getSenses();
			Article dest = wikipedia.getArticleByTitle(destText);
			
			if ((dest != null) && (senses.length >= 0)) {
				NerdEntity ref = new NerdEntity();
				ref.setRawName(labelText);
				ref.setWikipediaExternalRef(dest.getId());
				ref.setOffsetStart(contentText.length()-labelText.length());
				ref.setOffsetEnd(contentText.length());
				refs.add(ref);
//logger.info(linkText + ", " + labelText + ", " + destText + " / " + ref.getOffsetStart() + " " + ref.getOffsetEnd());
			}
		}
		contentText.append(content.substring(head));
		String contentString = contentText.toString();
		contentString = UnicodeUtil.normaliseText(contentString);

//logger.info("Cleaned content: " + contentString);
		List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(contentString, new Language(lang, 1.0));

		// get candidates for this content
		NerdEngine nerdEngine = NerdEngine.getInstance();
		Relatedness relatedness = Relatedness.getInstance();

		// process the text
		ProcessText processText = ProcessText.getInstance();
		List<Mention> entities = new ArrayList<Mention>();
		Language language = new Language(lang, 1.0);
		if (lang.equals("en") || lang.equals("fr")) {
			entities = processText.processNER(tokens, language);
		}
//logger.info("number of NE found: " + entities.size());
		List<Mention> entities2 = processText.processWikipedia(tokens, language);
//logger.info("number of non-NE found: " + entities2.size());
		for(Mention entity : entities2) {
			// we add entities only if the mention is not already present
			if (!entities.contains(entity))
				entities.add(entity);
		}

		if ( (entities == null) || (entities.size() == 0) )
			return arffBuilder;

		// disambiguate and solve entity mentions
		List<NerdEntity> disambiguatedEntities = new ArrayList<NerdEntity>();
		for (Mention entity : entities) {
			NerdEntity nerdEntity = new NerdEntity(entity);
			disambiguatedEntities.add(nerdEntity);
		}
//logger.info("total entities to disambiguate: " + disambiguatedEntities.size());

		Map<NerdEntity, List<NerdCandidate>> candidates = 
			nerdEngine.generateCandidatesSimple(disambiguatedEntities, lang);
//logger.info("total entities with candidates: " + candidates.size());
		// set the expected concept to the NerdEntity
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();

			/*for (NerdCandidate cand : cands) {
				logger.info(cand.toString());
			}*/

			int start = entity.getOffsetStart();
			int end = entity.getOffsetEnd();
//logger.info("entity: " + start + " / " + end + " - " + contentString.substring(start, end));
			for(NerdEntity ref : refs) {
				int start_ref = ref.getOffsetStart();
				int end_ref = ref.getOffsetEnd();
				if ( (start_ref == start) && (end_ref == end) ) {
					entity.setWikipediaExternalRef(ref.getWikipediaExternalRef());
					break;
				} 
			}
		}

		// get context for this content
//logger.info("get context for this content");
		NerdContext context = null;
		try {
			 context = relatedness.getContext(candidates, null, lang, false);
		} catch(Exception e) {
			e.printStackTrace();
		}

		double quality = (double)context.getQuality();
		int nbInstance = 0;
		// second pass for producing the disambiguation observations
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();
			int expectedId = entity.getWikipediaExternalRef();
			int nbCandidate = 0;
			/*if (expectedId == -1) {
				continue;
			}*/
			/*if ((cands == null) || (cands.size() <= 1)) {
				// do not considerer unambiguous entities
				continue;
			}*/
			
			for(NerdCandidate candidate : cands) {
				try {
					nbCandidate++;
//logger.info(nbCandidate + " candidate / " + cands.size());
					Label.Sense sense = candidate.getWikiSense();
					if (sense == null)
						continue;

					double commonness = sense.getPriorProbability();
//logger.info("commonness: " + commonness);

					double related = relatedness.getRelatednessTo(candidate, context, lang);
//logger.info("relatedness: " + related);

					boolean bestCaseContext = true;
					// actual label used
					Label bestLabel = candidate.getLabel();
					if (!entity.getNormalisedName().equals(bestLabel.getText())) {
						bestCaseContext = false;
					}

					float embeddingsSimilarity = SimilarityScorer.getInstance().getLRScore(candidate, tokens, lang);

					String wikidataId = "Q0"; // undefined entity
					if (candidate.getWikidataId() != null)	
						wikidataId = candidate.getWikidataId();

					String wikidataP31Id = "Q0"; // undefined entity
					if (candidate.getWikidataP31Id() != null)
						wikidataP31Id = candidate.getWikidataP31Id();

					// nerd score
					double nerd_score = ranker.getProbability(commonness, related, quality, bestCaseContext, 
						embeddingsSimilarity, wikidataId, wikidataP31Id);

					boolean inContext = false;
					if (context.contains(candidate))
						inContext = true;

					boolean isNe = false;
					if (entity.getType() != null)
						isNe = true;

					GrobidAnalyzer analyzer = GrobidAnalyzer.getInstance();
					List<String> words = analyzer.tokenize(entity.getNormalisedName(), 
						new Language(wikipedia.getConfig().getLangCode(), 1.0));

					GenericSelectionFeatureVector feature = getNewFeature();
					feature.nerd_score = nerd_score;
					feature.prob_anchor_string = entity.getLinkProbability();
					feature.prob_c = commonness;
					feature.nb_tokens = words.size();
					feature.relatedness = related;
					feature.inContext = inContext;
					feature.isNe = isNe;
					feature.dice = ProcessText.getDICECoefficient(entity.getNormalisedName(), lang);

					int tf = TextUtilities.getOccCount(candidate.getLabel().getText(), contentString);
					double idf = ((double)wikipedia.getArticleCount()) / candidate.getLabel().getDocCount();
					feature.tf_idf = (double)tf * idf;

					feature.label = (expectedId == candidate.getWikipediaExternalRef()) ? 1.0 : 0.0;

					// addition of the example is constrained by the sampling ratio
					if ( ((feature.label == 0.0) && ((double)this.negatives / this.positives < sampling)) ||
						 ((feature.label == 1.0) && ((double)this.negatives / this.positives >= sampling)) ) {
						arffBuilder.append(feature.printVector()).append("\n");
						nbInstance++;
						if (feature.label == 0.0)
							this.negatives++;
						else
							this.positives++;
					}
		
					//logger.info("*"+candidate.getWikiSense().getTitle() + "* " +
					//		entity.toString());
					//logger.info("\t\t" + "nerd_score: " + nerd_score +
					//	", prob_anchor_string: " + feature.prob_anchor_string);

					/*if ( (feature.label == 1.0) && (nbCandidate > 1) )
						break;
					if ( (expectedId == -1) && (nbCandidate > 0) ) {
						break;
					}*/

					/*if (nbCandidate > 0)
						break;*/
				}
				catch(Exception e) {
					e.printStackTrace();
				}
			}
			Collections.sort(cands);
		}

		LOGGER.info("article contribution: " + nbInstance + " training instances");
		return arffBuilder;
	}

	private StringBuilder trainCorpusArticle(Article article, 
									StringBuilder arffBuilder, 
									NerdRanker ranker) throws Exception {

		return arffBuilder;
	}

	/**
	 * Evaluate the selector with a set of articles, given an existing ranker for preprocessing.
	 * Boolean parameter `full` indicates if only the selector is evaluated or if the full end-to-end
	 * process is evaluated with additional overlap pruning.
	 */
	public LabelStat evaluate(ArticleTrainingSample testSet, NerdRanker ranker, boolean full) throws Exception {	
		List<LabelStat> stats = new ArrayList<LabelStat>();
		int n = 0;
		for (Article article : testSet.getSample()) {
			LOGGER.info("Evaluating on article " + (n+1) + " / " + testSet.getSample().size());
			if (article instanceof CorpusArticle)
				stats.add(evaluateCorpusArticle(article, ranker, full));
			else	
				stats.add(evaluateWikipediaArticle(article, ranker, full));
				
			n++;
		}
		return EvaluationUtil.evaluate(testSet, stats);
	}

	private LabelStat evaluateWikipediaArticle(Article article, NerdRanker ranker, boolean full) throws Exception {
		LOGGER.info(" - evaluating " + article);
		Language lang = new Language(wikipedia.getConfig().getLangCode(), 1.0);
		String content = MediaWikiParser.getInstance().toTextWithInternalLinksArticlesOnly(article.getFullWikiText(), 
			lang.getLang());

		Pattern linkPattern = Pattern.compile("\\[\\[(.*?)\\]\\]"); 
		Matcher linkMatcher = linkPattern.matcher(content);

		Set<Integer> referenceDisamb = new HashSet<Integer>();
		Set<Integer> producedDisamb = new HashSet<Integer>();

		while (linkMatcher.find()) {			
			String linkText = content.substring(linkMatcher.start()+2, linkMatcher.end()-2);

			String labelText = linkText;
			String destText = linkText;

			int pos = linkText.lastIndexOf('|');
			if (pos>0) {
				destText = linkText.substring(0, pos);
				labelText = linkText.substring(pos+1);
			}

			destText = Character.toUpperCase(destText.charAt(0)) + destText.substring(1);
			Label label = new Label(wikipedia.getEnvironment(), labelText);
			Label.Sense[] senses = label.getSenses();
			Article dest = wikipedia.getArticleByTitle(destText);

			if ((senses.length > 0) && (dest != null) && !referenceDisamb.contains(dest.getId())) {
				referenceDisamb.add(dest.getId());
			}
		}

		ProcessText processText = ProcessText.getInstance();
		String text = MediaWikiParser.getInstance().toTextOnly(article.getFullWikiText(), lang.getLang());
		text = UnicodeUtil.normaliseText(text);
		List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(text, lang);

		List<Mention> nerEntities = null;
		if (lang.getLang().equals("en") || lang.getLang().equals("fr")) {
			nerEntities = processText.processNER(tokens, lang);
		}
		if (nerEntities == null)
			nerEntities = new ArrayList<Mention>();
		List<Mention> nerEntities2 = processText.processWikipedia(tokens, lang);
		for(Mention entity : nerEntities2) {
			// we add entities only if the mention is not already present
			if (!nerEntities.contains(entity)) {
				nerEntities.add(entity);
			}
		}

		List<NerdEntity> entities = new ArrayList<NerdEntity>();
		for (Mention entity : nerEntities) {
			NerdEntity theEntity = new NerdEntity(entity);
			entities.add(theEntity);
		}

		NerdEngine engine = NerdEngine.getInstance();
		//Language lang = new Language(wikipedia.getConfig().getLangCode(), 1.0);
		Map<NerdEntity, List<NerdCandidate>> candidates = 
			engine.generateCandidatesSimple(entities, wikipedia.getConfig().getLangCode());
		NerdContext context = engine.rank(candidates, wikipedia.getConfig().getLangCode(), null, false, tokens);

		engine.pruneWithSelector(candidates, 
			wikipedia.getConfig().getLangCode(), false, false, wikipedia.getConfig().getMinSelectorScore(), context, text);

		List<NerdEntity> result = new ArrayList<NerdEntity>();
		for (Map.Entry<NerdEntity, List<NerdCandidate>> entry : candidates.entrySet()) {
			List<NerdCandidate> cands = entry.getValue();
			NerdEntity entity = entry.getKey();
			if (full) {
				for(NerdCandidate candidate : cands) {
					NerdEntity nerdEntity = new NerdEntity(entity);
					nerdEntity.populateFromCandidate(candidate, wikipedia.getConfig().getLangCode());
					result.add(nerdEntity);
					break;
				}
			} else if (cands.size() > 0) {
				Collections.sort(cands);
				if (!producedDisamb.contains(cands.get(0).getWikipediaExternalRef()))
					producedDisamb.add(cands.get(0).getWikipediaExternalRef());
			}
			
		}
		
		if (full) {
			Collections.sort(result);
			result = engine.pruneOverlap(result, false);
			for(NerdEntity entit : result) {
				if (!producedDisamb.contains(entit.getWikipediaExternalRef()))
					producedDisamb.add(entit.getWikipediaExternalRef());
			}
		}

		LabelStat stats = new LabelStat();
		int nbCorrect = 0;
		for(Integer index : producedDisamb) {
			if (!referenceDisamb.contains(index)) {
				stats.incrementFalsePositive();
			} else
				nbCorrect++;
		}
		stats.setObserved(nbCorrect); // reminder: "observed" is true positive

		stats.setExpected(referenceDisamb.size());
		for(Integer index : referenceDisamb) {
			if (!producedDisamb.contains(index)) {
				stats.incrementFalseNegative();
			}
		}

		return stats;
	}

	private LabelStat evaluateCorpusArticle(Article article, NerdRanker ranker, boolean full) throws Exception {
		LabelStat stats = new LabelStat();

		return stats;
	}

}
