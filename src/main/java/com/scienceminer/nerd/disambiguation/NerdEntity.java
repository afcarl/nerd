package com.scienceminer.nerd.disambiguation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.scienceminer.nerd.kb.*;
import com.scienceminer.nerd.kb.model.Article;
import com.scienceminer.nerd.kb.model.Page;
import org.grobid.core.data.Entity;
import org.grobid.core.data.Sense;
import org.grobid.core.layout.BoundingBox;
import org.grobid.core.lexicon.NERLexicon;
import org.grobid.core.utilities.OffsetPosition;
import org.grobid.core.utilities.TextUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This class represents disambiguated entity (the result), including conceptual and 
 * encyclopedic information, with the information necessary for a disambiguation. 
 * 
 *
 */
public class NerdEntity implements Comparable<NerdEntity> {
	private static final Logger LOGGER = LoggerFactory.getLogger(NerdEntity.class);
	
	// Orign of the entity definition
	public enum Origin {
		GROBID	("grobid"),
		USER	("user"),
		NERD	("nerd");
		
		private String name;

		private Origin(String name) {
          	this.name = name;
		}

		public String getName() {
			return name;
		}
	};

	// exact mention form of the entity, as appearing in the input
	private String rawName = null;

	// mention form of the entity, with soft normalisation: replacing \n per space, 
	// replacing sequence of space by a unique space
	// this is usually this surface form that should be used, in particular for
	// acronyms where this field capture the expended form
	private String normalisedRawName = null;
	
	// preferred/normalised name of the entity
    private String preferredTerm = null;
	
	// type of the entity (person, location, etc.)
	private NERLexicon.NER_Type type = null;
	
	// subtypes of the entity when available - the first one is the main one, the others secondary subtypes
	private List<String> subTypes = null;
	
	// relative offset positions in context, if defined
	private OffsetPosition offsets = null;
	
	// optional bounding box in the source document
	private List<BoundingBox> boundingBoxes = null;

	// probability of the entity in context, if defined
	private double prob = 1.0;
	
	// confidence score of the entity in context, if defined
	private double ner_conf = -1.0;
	
	// all the sense information related to the entity
	private Sense sense = null;

	// definitions of the related sense/concept/entity
	private List<Definition> definitions = null;
	
	// list of conceptual variants for the term
	private List<Variant> variants = null;
	
	// Wikipedia page ID
	private int wikipediaExternalRef = -1;
	
	// Wiktionary page ID
	private int wiktionaryExternalRef = -1;

	// Wikidata identifier
	private String wikidataId = null;

	// multilingual Wikipedia information
	private Map<String,String> wikipediaMultilingualRef = null;
	private Map<String,Integer> wikipediaMultilingualArticle = null;

	// domain information
	private List<String> domains = null;

	// list of freebase types for the term
	//private List<String> freebaseTypes = null;

	// method used to produce the disambiguated term 
	public static final int GROBID = 0;
	public static final int USER = 1;
	public static final int NERD = 2;
	// orign of the entity 
	private Origin origin = null;
	
	// list of wikipedia categories corresponding to the disambiguated term
	private List<com.scienceminer.nerd.kb.Category> categories = null;
	
	public boolean isSubTerm = false;
	public boolean isAcronym = false;
	
	// to carry statistics/priors
	// conditional probability of the concept given the string
	private double prob_c = 0.0;
	// conditional probability of the string given the concept (i.e. reverse prob_c)
	private double prob_i = 0.0;
	// frequency of usage of the string to refer to the concept in a reference corpus (usually wikipedia)
	// among all possible concepts it can realise
	private int freq = 0;
	// frequency of usage of the string to refer to the concept in a reference corpus (usually wikipedia)
	// among all the strings which can realise this particular concept
	private int freq_i = 0;
	// represent named entity disambiguation score in a NERD scenario
	@JsonProperty("nerd_score")
	private double nerdScore = 0.0;
	// represent named entity selection score in a NERD scenario
	@JsonProperty("nerd_selection_score")
	private double selectionScore = 0.0;
	// relatedness score of the term with the context
	private double relatednessScore = 0.0;
	// frequency that the raw string is used as a link anchor in Wikipedia
	private double linkProbability = 0.0;
	
	private String lang = null;

	private List<Statement> statements = null;

	public NerdEntity() {
		offsets = new OffsetPosition();
	}

	public NerdEntity(Entity entity) {
		rawName = entity.getRawName();
		if (entity.getNormalisedName() != null) 
			this.normalisedRawName = entity.getNormalisedName();
		else if (rawName != null) {
	        this.normalisedRawName = simpleStringNormalisation(rawName);
		}
		preferredTerm = entity.getNormalisedName();
		type = entity.getType();
		subTypes = entity.getSubTypes();
		offsets = new OffsetPosition();
		offsets.start = entity.getOffsetStart();
		offsets.end = entity.getOffsetEnd();
		prob = entity.getProb();
		ner_conf = entity.getConf();
		sense = entity.getSense();
		boundingBoxes = entity.getBoundingBoxes();
		isAcronym = entity.getIsAcronym();
		switch(entity.getOrigin()) {
			case GROBID : origin = Origin.GROBID;
						break;
			case USER : origin = Origin.USER;
						break;
			default: origin = Origin.NERD;
		}
	}
	
	public NerdEntity(NerdEntity entity) {
		rawName = entity.getRawName();
		normalisedRawName = entity.getNormalisedName();
		preferredTerm = entity.getPreferredTerm();
		type = entity.getType();
		subTypes = entity.getSubTypes();
		offsets = new OffsetPosition();
		offsets.start = entity.getOffsetStart();
		offsets.end = entity.getOffsetEnd();
		boundingBoxes = entity.getBoundingBoxes();
		prob = entity.getProb();
		prob_c = entity.getProb_c();
		prob_i = entity.getProb_i();
		linkProbability= entity.linkProbability;
		ner_conf = entity.getNer_conf();
		sense = entity.getSense();
		origin = entity.getOrigin();
		domains = entity.domains;
		isAcronym = entity.getIsAcronym();
		//freebaseTypes = entity.freebaseTypes;
		lang = entity.getLang();
	}
	
    public String getRawName() {
        return rawName;
    }
	
	public void setRawName(String raw) {
        this.rawName = raw;
        if (raw != null) {
	        this.normalisedRawName = simpleStringNormalisation(raw);
		}
    }

	/*public void setNormalisedRawName(String raw) {
        this.normalisedRawName = raw;
    }*/

    public String getNormalisedName() {
        return normalisedRawName;
    }

	public String getPreferredTerm() {
        return preferredTerm;
    }
	
	public void setPreferredTerm(String raw) {
        this.preferredTerm = raw;
    }

	public NERLexicon.NER_Type getType() {
		return type;
	}
	
	public void setType(NERLexicon.NER_Type theType) {
		type = theType;
	}
	
	public void setTypeFromString(String theType) {
		type = NERLexicon.NER_Type.valueOf(theType.toUpperCase());
	}
	
	public List<String> getSubTypes() {
		return subTypes;
	} 

	public void setSubTypes(List<String> theSubTypes) {
		subTypes = theSubTypes;
	}

	public void addSubType(String subType) {
		if (subTypes == null)
			subTypes = new ArrayList<String>();
		subTypes.add(subType);
	}
	
	public void setOffsetStart(int start) {
        offsets.start = start;
    }

    public int getOffsetStart() {
        return offsets.start;
    }

    public void setOffsetEnd(int end) {
        offsets.end = end;
    }

    public int getOffsetEnd() {
        return offsets.end;
    }
	
	public double getProb() {
		return this.prob;
	}
	
	public void setProb(double prob) {
		this.prob = prob;
	}
	
	public double getNer_conf() {
		return this.ner_conf;
	}
	
	public void setNer_conf(double conf) {
		this.ner_conf = conf;
	}
	
	public Sense getSense() {
		return sense;
	}
	
	public void setSense(Sense sense) {
		this.sense = sense;
	}
	
	public String getLang() {
		return lang;
	}
	
	public void setLang(String lang) {
		this.lang = lang;
	}
	
	public Origin getOrigin() {
		return origin;
	}
	
	public void setOrigin(Origin origin) {
		this.origin = origin;
	}

	public boolean getIsAcronym() {
		return this.isAcronym;
	}

	public void setIsAcronym(boolean acronym) {
		this.isAcronym = acronym;
	}

	public List<Statement> getStatements() {
		return this.statements;
	}

	public void setStatements(List<Statement> statements) {
		this.statements = statements;
	}

	/*public List<String> getFreebaseTypes() {
		return freebaseTypes;
	}
	
	public void setFreebaseTypes(List<String> types) {
		freebaseTypes = types;
	}
	
	public void addFreebaseType(String type) {
		if (freebaseTypes == null)
			freebaseTypes = new ArrayList<String>();
		if (!freebaseTypes.contains(type))
			freebaseTypes.add(type);
	}*/
	
	public List<com.scienceminer.nerd.kb.Category> getCategories() {
		return categories;
	}
	
	public void setCategories(List<com.scienceminer.nerd.kb.Category> categories) {
		categories = categories;
	}

	public void addCategory(com.scienceminer.nerd.kb.Category categ) {
		if (categories == null)
			categories = new ArrayList<com.scienceminer.nerd.kb.Category>();
		categories.add(categ);
	}
	
	public List<Variant> getVariants() {
		return variants;
	}
	
	public void setVariants(List<Variant> variants) {
		this.variants = variants;
	}
	
	public void addVariant(Variant variant) {
		if (variants == null) {
			variants = new ArrayList<Variant>();
		}
		if (!variants.contains(variant)) {
			variants.add(variant);
		}
	}
	
	public List<String> getDomains() {
		return domains;
	} 

	public void setDomains(List<String> domains) {
		this.domains = domains;
	}

	public void addDomain(String domain) {
		if (domains == null)
			domains = new ArrayList<String>();
		else {
			if (domains.contains(domain.toLowerCase())) {
				return;
			}
		}
		domains.add(domain);
	}

	public int getWikipediaExternalRef() {
		return wikipediaExternalRef;
	}

	public void setWikipediaExternalRef(int ref) {
        this.wikipediaExternalRef = ref;
    }

    public String getWikidataId() {
		return wikidataId;
	}

	public void setWikidataId(String ref) {
        this.wikidataId = ref;
    }

    public void setBoundingBoxes(List<BoundingBox> boundingBoxes) {
		this.boundingBoxes = boundingBoxes;
	}

	public List<BoundingBox> getBoundingBoxes() {
		return boundingBoxes;
	}

	public void addBoundingBoxes(BoundingBox boundingBox) {
		if (this.boundingBoxes == null)
			this.boundingBoxes = new ArrayList<BoundingBox>();
		this.boundingBoxes.add(boundingBox);
	}

	public Map<String,String> getWikipediaMultilingualRef() {
		return wikipediaMultilingualRef;
	}

	public void setWikipediaMultilingualRef(Map<String,String> translations, 
											List<String> targetLanguages,
											Map<String, LowerKnowledgeBase> wikipedias) {
		if ( (targetLanguages != null) && (targetLanguages.size() != 0) ) {	
			Map<String,String> subTranslations = new TreeMap<String,String>();
			Map<String,Integer> subArticleCorrespondance = new TreeMap<String,Integer>();
			for(String targetLanguage : targetLanguages) {
				String translation = translations.get(targetLanguage);
				if (translation != null) {
					int ind = translation.indexOf("#");
					if (ind != -1) {
						translation = translation.substring(0,ind);
					}
					translation = translation.replace("\\'", "'");
					subTranslations.put(targetLanguage, translation);
					if (wikipedias.get(targetLanguage) != null) {
						Article article = wikipedias.get(targetLanguage).getArticleByTitle(translation);
						if (article != null) {
							subArticleCorrespondance.put(targetLanguage, article.getId());
						}
						else {
							System.out.println(translation + ": Article for language " + targetLanguage + " is null");
						}
					}
					else {
						System.out.println("Wikipedia for language " + targetLanguage + " is null");
					}
				}
			}
			wikipediaMultilingualRef = subTranslations;
			wikipediaMultilingualArticle = subArticleCorrespondance;
		}
	}

	/**
	 *  If missing and possible, add to the current entity the corresponding FreeBase ID
	 */
	/*public void injectFreeBaseId(TreeMap<String,String> translations, 
								Map<String, Wikipedia> wikipedias,
								KBAccess kbAccess) {
		// if we already have the English wikipedia article id 
		int englishArticleId = -1;
		if ( (wikipediaMultilingualArticle != null) && (wikipediaMultilingualArticle.get("en") != null) ) {
			englishArticleId = wikipediaMultilingualArticle.get("en");
		}
		else if (translations != null) {
			// we need to get the English wikipedia article id from the translations
			String translation = translations.get("en");
			if (translation != null) {
				Article article = wikipedias.get("en").getArticleByTitle(translation);
				if (article != null) {
					englishArticleId = article.getId();
				}
			}
		}
		if (englishArticleId != -1) {
			String freebaseId = kbAccess.getFreeBaseId(englishArticleId);
			if (freebaseId != null) {
				freeBaseExternalRef = freebaseId;
			}
		}
	}*/

	public int getWiktionaryExternalRef() {
		return wiktionaryExternalRef;
	}

	public void setWiktionaryExternalRef(int ref) {
        this.wiktionaryExternalRef = ref;
    }

	/*public String getFreeBaseExternalRef() {
		return freeBaseExternalRef;
	}

	public void setFreeBaseExternalRef(String freebase) {
		freeBaseExternalRef = freebase;
	}*/

	public List<Definition> getDefinitions() {
		return definitions;
	}
	
	public void setDefinitions(List<Definition> desc) {
		definitions = desc;
	}
	
	public void addDefinition(Definition desc) {
		if (definitions == null)
		 	definitions = new ArrayList<Definition>();
		if (desc == null)
			return;
		definitions.add(desc);
	}
	
	public double getProb_c() {
		return prob_c;
	}
	
	public void setProb_c(double p) {
		prob_c = p;
	}
	
	public double getProb_i() {
		return prob_i;
	}
	
	public void setProb_i(double p) {
		prob_i = p;
	}
	
	public int getFreq() {
		return freq;
	}
	
	public void setFreq(int f) {
		freq = f;
	}
	
	public int getFreq_i() {
		return freq_i;
	}
	
	public void setFreq_i(int f) {
		freq_i = f;
	}
	
	public double getNerdScore() {
		return nerdScore;
	}
	
	public void setNerdScore(double n) {
		nerdScore = n;
	}
	
	public double getLinkProbability() {
		return linkProbability;
	}
	
	public void setLinkProbability(double prob) {
		linkProbability = prob;
	}
	
	public double getSelectionScore() {
		return selectionScore;
	}
	
	public void setSelectionScore(double n) {
		selectionScore = n;
	}
	
	public double getRelatednessScore() {
		return relatednessScore;
	}
	
	public void setRelatednessScore(double score) {
		relatednessScore = score;
	}
	
	public boolean getIsSubTerm() {
		return isSubTerm;
	}
	
	public void setIsSubTerm(boolean sub) {
		isSubTerm = sub;
	}
	
	@Override
	public boolean equals(Object object) {
		boolean result = false;
		if ( (object != null) && object instanceof NerdEntity) {
			int start = ((NerdEntity)object).getOffsetStart();
			int end = ((NerdEntity)object).getOffsetEnd();
			if ( (start == offsets.start) && (end == offsets.end) && (this.wikipediaExternalRef == ((NerdEntity)object).getWikipediaExternalRef()) ) {
				result = true;
			}
		}
		return result;
	}

	@Override
	public int compareTo(NerdEntity theEntity) {
		// if we have offsets
		int start = theEntity.getOffsetStart();
		int end = theEntity.getOffsetEnd();
		//if ( (start != -1) && (end != -1) ) {
			Double score = new Double(theEntity.getNerdScore());
			if ( (offsets.start == start) && (offsets.end == end) ) {
				Double thisScore = new Double(nerdScore);
				if ((score != 0.0) && (thisScore != 0.0) && (!score.equals(thisScore)))
					return thisScore.compareTo(score);
				else {
					thisScore = new Double(getProb_c());
					score = new Double(theEntity.getProb_c());
					return thisScore.compareTo(score);
				} 
			} else if (offsets.start != start) 
				return offsets.start - start;
			else {
				if (offsets.end != end)
					return offsets.end - end;
				else
					return rawName.length() - theEntity.getRawName().length();
			}
		/*} else {
			// we have coordinates
			List<BoundingBox> bb = theEntity.getBoundingBoxes();
			if ( (bb.size() > 0) && (boundingBoxes.size() > 0) ) {
				int thePage = bb.get(0).getPage();
				int page = boundingBoxes.get(0).getPage();
				if (page != thePage) 
					return page - thePage;
				else {
					// same page, we look at the coordinates of the top-left point 
					// (warning: this is occidental-biased)
					double theX = bb.get(0).getX();
					double theY = bb.get(0).getY();
					double x = boundingBoxes.get(0).getX();
					double y = boundingBoxes.get(0).getY();
					if (theY != y) {
						if (y > theY)
							return 1;
						else 
							return -1;
					} else {
						if (theX != x) {
							if (x > theX)
								return 1;
							else 
								return -1;
						} else 
							return rawName.length() - theEntity.getRawName().length();
					}
				}  
			} else {
				// normally never happen 
				return rawName.length() - theEntity.getRawName().length();
			}
		}*/
	}

	/**
     *  Copy the result of a disambiguation corresponding to a candidate in the current NERD entity
	 */
	public void populateFromCandidate(NerdCandidate candidate, String lang) {
		wikipediaExternalRef = candidate.getWikipediaExternalRef();
		wikidataId = candidate.getWikidataId();

		LowerKnowledgeBase wikipedia = UpperKnowledgeBase.getInstance().getWikipediaConf(lang);
		Page page = wikipedia.getPageById(wikipediaExternalRef);
		Definition definition = new Definition();
		try {
			definition.setDefinition(page.getFirstParagraphWikiText());
		} catch(Exception e) {
			LOGGER.debug("Error when getFirstParagraphWikiText for page id: "+ wikipediaExternalRef);
			//e.printStackTrace();
		}
		definition.setSource("wikipedia-" + lang);
		definition.setLang(lang);
		addDefinition(definition);

		domains = candidate.getDomains();

		prob_c = candidate.getProb_c();
		nerdScore = candidate.getNerdScore();
		selectionScore = candidate.getSelectionScore();
		//freeBaseExternalRef = candidate.getFreeBaseExternalRef();
		categories = candidate.getWikipediaCategories();
		statements = UpperKnowledgeBase.getInstance().getStatements(wikidataId); 

		preferredTerm = candidate.getPreferredTerm();
		this.lang = lang;
	}
	
	@Override
	public String toString() {
        StringBuffer buffer = new StringBuffer();
				
		if (normalisedRawName != null)
			buffer.append(normalisedRawName + "\t");
		if (definitions != null)
			buffer.append("[" + definitions.toString() + "]\t");	
		if (wiktionaryExternalRef != -1) {
			buffer.append(wiktionaryExternalRef + "\t");	
		} 
		if (wikipediaExternalRef != -1) {
			buffer.append(wikipediaExternalRef + "\t");	
		}
		if (wikidataId != null) {
			buffer.append(wikidataId + "\t");	
		}
		/*if (freeBaseExternalRef != null) {
			buffer.append(freeBaseExternalRef + "\t");	
		}*/
		
		if (domains != null) {
			buffer.append(domains.toString() + "\t");
		}
		
		if (variants != null) {
			for(Variant variant : variants)
				buffer.append(variant.toString() + "\t");
		}
		
		if (isSubTerm)
			buffer.append("isSubTerm\t");

		if (isAcronym)
			buffer.append("isAcronym\t");
		
		//if (nerdScore > 0.0) 
		{
			buffer.append(nerdScore + "(nerd)\t");
		}
		
		//if (selectionScore > 0.0) 
		{
			buffer.append(selectionScore + "(selection)\t");
		}
		
		{
			buffer.append(relatednessScore + "(relatedness)\t");
		}
		
		buffer.append(prob_c + "(prob_c)\t");
		buffer.append(freq + "(freq_c)\t");
		buffer.append(prob_i + "(prob_i)\t");
		buffer.append(freq_i + "(freq_i)\t");
		buffer.append(linkProbability + "(linkProbability)\t");
		
		/*if (freebaseTypes != null)
			buffer.append(freebaseTypes.toString()+"\t");*/
		
		return buffer.toString();	
	}
	
	/**
	 * Serialize in JSON the largest possible set of information, including
	 * KB data related to the disambiguated sense. 
	 */
	public String toJsonFull() {
		JsonStringEncoder encoder = JsonStringEncoder.getInstance();
		StringBuilder buffer = new StringBuilder();
		buffer.append("{ ");
		byte[] encodedRawName = encoder.quoteAsUTF8(normalisedRawName);
		String outputRawName = new String(encodedRawName); 
		buffer.append("\"rawName\" : \"" + outputRawName + "\"");
		if (preferredTerm != null) {
			byte[] encodedPreferredTerm = encoder.quoteAsUTF8(preferredTerm);
			String outputPreferredTerm  = new String(encodedPreferredTerm); 
			buffer.append(", \"preferredTerm\" : \"" + outputPreferredTerm + "\"");
		}
		if (type != null)
			buffer.append(", \"type\" : \"" + type.getName() + "\"");	
		
		if (subTypes != null) {
			buffer.append(", \"subtype\" : [ ");
			boolean begin = true;
			for(String subtype : subTypes) {
				if (begin) {
					buffer.append("\"" + subtype + "\"");
					begin = false;
				}
				else {
					buffer.append(", \"" + subtype + "\"");
				}
			}
			buffer.append(" ] \"");
		}
			
		if (getOffsetStart() != -1)	
			buffer.append(", \"offsetStart\" : " + getOffsetStart());
		if (getOffsetEnd() != -1)	
			buffer.append(", \"offsetEnd\" : " + getOffsetEnd());	
		
		if ( (boundingBoxes != null) && (boundingBoxes.size() > 0) ) {
			buffer.append(", \"pos\" : [");
			boolean start = true; 
			for(BoundingBox box : boundingBoxes) {
				if (start) {
					buffer.append("{").append(box.toJson()).append("}");
					start = false;
				} else {
					buffer.append(", {").append(box.toJson()).append("}");
				}
			}
			buffer.append("]");
		}

		/*buffer.append(", \"nerd_score\" : \"" + nerdScore + "\"");
		buffer.append(", \"nerd_selection_score\" : \"" + selectionScore + "\"");*/

		buffer.append(", \"nerd_score\":" + TextUtilities.formatFourDecimals(nerdScore));
		buffer.append(", \"nerd_selection_score\":" + TextUtilities.formatFourDecimals(selectionScore));

		/*if (ner_conf != -1.0)
			buffer.append(", \"ner_conf\" : \"" + ner_conf + "\"");*/
		//buffer.append(", \"prob\" : \"" + prob + "\"");
		
		sense = correctSense(sense);
		if (sense != null) {
			buffer.append(", \"sense\" : { "); 
			if (sense.getFineSense() != null) {
				buffer.append("\"fineSense\" : \"" + sense.getFineSense() + "\"");
				//buffer.append(", \"fineSenseConfidence\" : \"" + sense.getFineSenseConfidence() + "\"");
			}
		
			if (sense.getCoarseSense() != null) {
				if ( (sense.getFineSense() == null) ||
				     ( (sense.getFineSense() != null) && !sense.getCoarseSense().equals(sense.getFineSense())) ) {
					buffer.append(", \"coarseSense\" : \"" + sense.getCoarseSense() + "\"");
				}
			}
			buffer.append(" }");
		}

		if (wikipediaExternalRef != -1)
			buffer.append(", \"wikipediaExternalRef\":" + wikipediaExternalRef);

		if (wikidataId != null)
			buffer.append(", \"wikidataId\" : \"" + wikidataId + "\"");

		/*if (freeBaseExternalRef != null)
			buffer.append(", \"freeBaseExternalRef\" : \"" + freeBaseExternalRef + "\"" );*/

		if ( (definitions != null) && (definitions.size() > 0) ) {
			buffer.append(", \"definitions\" : [ ");
			for(Definition definition : definitions) {
				if ((definition.getDefinition() == null) || (definition.getDefinition().length() == 0) )
					continue;
				byte[] encoded = encoder.quoteAsUTF8(definition.getDefinition());
				String output = new String(encoded); 
				buffer.append("{ \"definition\" : \"" + output + "\", \"source\" : \"" + 
					definition.getSource() + "\", \"lang\" : \"" + definition.getLang() + "\" }");
			}
			buffer.append(" ] ");
		}

		if (domains != null) {
			buffer.append(", \"domains\" : [ ");
			boolean first = true;
			for(String domain : domains) {
				byte[] encoded = encoder.quoteAsUTF8(domain);
				String output = new String(encoded);
				if (first) {
					buffer.append("\"" + output + "\"");
					first = false;
				}
				else
					buffer.append(", \"" + output + "\"");
			}
			buffer.append(" ] ");
		}

		if (categories != null) {
			buffer.append(", \"categories\" : [ ");
			boolean first = true;
			for(com.scienceminer.nerd.kb.Category category : categories) {				
				byte[] encoded = encoder.quoteAsUTF8(category.getName());
				String output = new String(encoded);
				if (first) {
					first = false;
				}
				else
					buffer.append(", ");
				buffer.append("{\"source\" : \"wikipedia-"+lang+"\", \"category\" : \"" + output + "\", ");
				buffer.append("\"page_id\" : " + category.getWikiPageID() + "}");
			}
			buffer.append(" ] ");
		}
		
		if ( (wikipediaMultilingualRef != null) && (wikipediaMultilingualRef.size() != 0) ) {
			buffer.append(", \"multilingual\" : [ ");
			boolean first = true;
		    Iterator it = wikipediaMultilingualRef.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry pair = (Map.Entry)it.next();
				String l = (String)pair.getKey();
				String t = (String)pair.getValue();
				/*if (t != null)
					t = t.replace("#", " ");*/
				// with #, this is an anchor to the first element page, however the page_id seems then not available	
   				if (first) {
   					first = false;
   				}
   				else
   					buffer.append(", ");
				buffer.append("{\"lang\" : \"" + l + "\", \"term\" : \"" + t + "\"");
				if (wikipediaMultilingualArticle.get(l) != null)
					buffer.append(", \"page_id\" : " + wikipediaMultilingualArticle.get(l));
				buffer.append("}");
				it.remove(); // avoids a ConcurrentModificationException
	       }
			buffer.append(" ] ");
		}
		
		// statements
		if (statements != null) {
			buffer.append(", \"statements\": [");
			boolean start = true;
			for(Statement statement : statements) {
				if (start)
					start = false;
				else 
					buffer.append(", ");
				buffer.append(statement.toJson());
			}
			buffer.append("]");
		}

		buffer.append(" }");
		return buffer.toString();
	}

	/**
	 * Only serialize in JSON the minimal information which will need to be completed
	 * by an additional call to the KB concept information.
	 */
	public String toJsonCompact() {
		JsonStringEncoder encoder = JsonStringEncoder.getInstance();
		StringBuilder buffer = new StringBuilder();
		buffer.append("{ ");
		byte[] encodedRawName = encoder.quoteAsUTF8(normalisedRawName);
		String outputRawName = new String(encodedRawName); 
		buffer.append("\"rawName\" : \"" + outputRawName + "\"");
		/*if (preferredTerm != null) {
			byte[] encodedPreferredTerm = encoder.quoteAsUTF8(preferredTerm);
			String outputPreferredTerm  = new String(encodedPreferredTerm); 
			buffer.append(", \"preferredTerm\" : \"" + outputPreferredTerm + "\"");
		}*/
		if (type != null)
			buffer.append(", \"type\" : \"" + type.getName() + "\"");	
		
		if (subTypes != null) {
			buffer.append(", \"subtype\" : [ ");
			boolean begin = true;
			for(String subtype : subTypes) {
				if (begin) {
					buffer.append("\"" + subtype + "\"");
					begin = false;
				}
				else {
					buffer.append(", \"" + subtype + "\"");
				}
			}
			buffer.append(" ] \"");
		}

		if (getOffsetStart() != -1)	
			buffer.append(", \"offsetStart\" : " + getOffsetStart());
		if (getOffsetEnd() != -1)	
			buffer.append(", \"offsetEnd\" : " + getOffsetEnd());	

		if ( (boundingBoxes != null) && (boundingBoxes.size() > 0) ) {
			buffer.append(", \"pos\" : [");
			boolean start = true; 
			for(BoundingBox box : boundingBoxes) {
				if (start) {
					buffer.append("{").append(box.toJson()).append("}");
					start = false;
				} else {
					buffer.append(", {").append(box.toJson()).append("}");
				}
			}
			buffer.append("]");
		}

		buffer.append(", \"nerd_score\": " + TextUtilities.formatFourDecimals(nerdScore));
		buffer.append(", \"nerd_selection_score\": " + TextUtilities.formatFourDecimals(selectionScore));
		/*if (ner_conf != -1.0)
			buffer.append(", \"ner_conf\" : \"" + ner_conf + "\"");*/
		//buffer.append(", \"prob\" : \"" + prob + "\"");
		
		sense = correctSense(sense);
		if (sense != null) {
			buffer.append(", \"sense\" : { "); 
			if (sense.getFineSense() != null) {
				buffer.append("\"fineSense\" : \"" + sense.getFineSense() + "\"");
				//buffer.append(", \"fineSenseConfidence\" : \"" + sense.getFineSenseConfidence() + "\"");
			}
		
			if (sense.getCoarseSense() != null) {
				if ( (sense.getFineSense() == null) ||
				     ( (sense.getFineSense() != null) && !sense.getCoarseSense().equals(sense.getFineSense())) ) {
					buffer.append(", \"coarseSense\" : \"" + sense.getCoarseSense() + "\"");
				}
			}
			buffer.append(" }");
		}

		if (wikipediaExternalRef != -1)
			buffer.append(", \"wikipediaExternalRef\": " + wikipediaExternalRef);

		if (wikidataId != null)
			buffer.append(", \"wikidataId\" : \"" + wikidataId + "\"");

		/*if (freeBaseExternalRef != null)
			buffer.append(", \"freeBaseExternalRef\" : \"" + freeBaseExternalRef + "\"" );*/

		/*if ( (definitions != null) && (definitions.size() > 0) ) {
			buffer.append(", \"definitions\" : [ ");
			for(Definition definition : definitions) {
				if ((definition.getDefinition() == null) || (definition.getDefinition().length() == 0) )
					continue;
				byte[] encoded = encoder.quoteAsUTF8(definition.getDefinition());
				String output = new String(encoded); 
				buffer.append("{ \"definition\" : \"" + output + "\", \"source\" : \"" + 
					definition.getSource() + "\", \"lang\" : \"" + definition.getLang() + "\" }");
			}
			buffer.append(" ] ");
		}*/

		if (domains != null) {
			buffer.append(", \"domains\" : [ ");
			boolean first = true;
			for(String domain : domains) {
				byte[] encoded = encoder.quoteAsUTF8(domain);
				String output = new String(encoded);
				if (first) {
					buffer.append("\"" + output + "\"");
					first = false;
				}
				else
					buffer.append(", \"" + output + "\"");
			}
			buffer.append(" ] ");
		}

		/*if (categories != null) {
			buffer.append(", \"categories\" : [ ");
			boolean first = true;
			for(com.scienceminer.nerd.kb.Category category : categories) {				
				byte[] encoded = encoder.quoteAsUTF8(category.getName());
				String output = new String(encoded);
				if (first) {
					first = false;
				}
				else
					buffer.append(", ");
				buffer.append("{\"source\" : \"wikipedia-"+lang+"\", \"category\" : \"" + output + "\", ");
				buffer.append("\"page_id\" : " + category.getWikiPageID() + "}");
			}
			buffer.append(" ] ");
		}*/
		
		/*if ( (wikipediaMultilingualRef != null) && (wikipediaMultilingualRef.size() != 0) ) {
			buffer.append(", \"multilingual\" : [ ");
			boolean first = true;
		    Iterator it = wikipediaMultilingualRef.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry pair = (Map.Entry)it.next();
				String l = (String)pair.getKey();
				String t = (String)pair.getValue();
				//if (t != null)
				//	t = t.replace("#", " ");
				// with #, this is an anchor to the first element page, however the page_id seems then not available	
   				if (first) {
   					first = false;
   				}
   				else
   					buffer.append(", ");
				buffer.append("{\"lang\" : \"" + l + "\", \"term\" : \"" + t + "\"");
				if (wikipediaMultilingualArticle.get(l) != null)
					buffer.append(", \"page_id\" : " + wikipediaMultilingualArticle.get(l));
				buffer.append("}");
				it.remove(); // avoids a ConcurrentModificationException
	       }
			buffer.append(" ] ");
		}*/

		buffer.append(" }");
		return buffer.toString();
	}

	private Sense correctSense(Sense theSense) {
		if (theSense == null)
			return null;
		if (theSense.getFineSense() != null) {
			if (theSense.getFineSense().indexOf("contestant") != -1) 
				return null;
			if (theSense.getFineSense().indexOf("team") != -1) 
				return null;
		}
		return theSense;
	}

	private String simpleStringNormalisation(String str) {
		return str.replace("\n", " ").trim().replaceAll(" +", " ");
	}
}