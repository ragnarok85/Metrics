package io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability;

import java.util.ArrayList;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.luzzu.exceptions.MetricProcessingException;
import io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability.helper.Dereferencer;
import io.github.luzzu.linkeddata.qualitymetrics.accessibility.availability.helper.Tld;
import io.github.luzzu.linkeddata.qualitymetrics.commons.AbstractQualityMetric;
import io.github.luzzu.linkeddata.qualitymetrics.commons.HTTPRetriever;
import io.github.luzzu.linkeddata.qualitymetrics.commons.cache.LinkedDataMetricsCacheManager;
import io.github.luzzu.linkeddata.qualitymetrics.vocabulary.DQM;
import io.github.luzzu.linkeddata.qualitymetrics.vocabulary.DQMPROB;
import io.github.luzzu.operations.properties.EnvironmentProperties;
import io.github.luzzu.qualitymetrics.algorithms.ReservoirSampler;
import io.github.luzzu.qualitymetrics.commons.cache.CachedHTTPResource;
import io.github.luzzu.qualitymetrics.commons.datatypes.HTTPDereference.StatusCode;
import io.github.luzzu.qualityproblems.ProblemCollection;
import io.github.luzzu.qualityproblems.ProblemCollectionQuad;
import io.github.luzzu.semantics.commons.ResourceCommons;
import io.github.luzzu.semantics.vocabularies.DAQ;
import io.github.luzzu.semantics.vocabularies.QPRO;

/**
 * @author Jeremy Debatista
 * 
 * This metric calculates an estimation of the number of valid redirects (303) or 
 * hashed links according to LOD Principles. Makes use of statistical sampling 
 * techniques to remain scalable to datasets of big-data proportions
 * 
 * Based on: <a href="http://www.hyperthing.org/">Hyperthing - A linked data Validator</a>
 * 
 * @see <a href="http://dl.dropboxusercontent.com/u/4138729/paper/dereference_iswc2011.pdf">
 * Dereferencing Semantic Web URIs: What is 200 OK on the Semantic Web? - Yang et al.</a>
 * 
 */
public class EstimatedDereferenceabilityByTld extends AbstractQualityMetric<Double> {
	
	private final Resource METRIC_URI = DQM.DereferenceabilityMetric;

	final static Logger logger = LoggerFactory.getLogger(EstimatedDereferenceability.class);
	
	/**
	 * Constants controlling the maximum number of elements in the reservoir of Top-level Domains and 
	 * Fully Qualified URIs of each TLD, respectively
	 */
	public int MAX_TLDS = 500;
	public int MAX_FQURIS_PER_TLD = 100000;
	
	private Long totalURIs = 0l;

	/**
	 * Performs HTTP requests, used to try to fetch identified URIs
	 */
	private HTTPRetriever httpRetriever = new HTTPRetriever();
	
	/**
	 * Holds the set of dereferenceable top-level domains found among the subjects and objects of the triples,
	 * as a reservoir sampler, if its number of items grows beyond the limit (MAX_TLDS) items will be replaced 
	 * randomly upon forthcoming insertions. Moreover, the items will be indexed so that search operations are O(1)
	 */
	private ReservoirSampler<Tld> tldsReservoir = new ReservoirSampler<Tld>(MAX_TLDS, true);

	private LinkedDataMetricsCacheManager dcmgr = LinkedDataMetricsCacheManager.getInstance();

	private double metricValue = 0.0;
	private boolean metricCalculated = false;
	
	private long totalDerefUris = 0;
	private long totalNumberOfResources = 0;

	

	private ProblemCollection<Quad> problemCollection = new ProblemCollectionQuad(DQM.DereferenceabilityMetric);
	private boolean requireProblemReport = EnvironmentProperties.getInstance().requiresQualityProblemReport();

	private long totalNumberOfTriples = 0;
	
	/**
	 * Processes each triple obtained from the dataset to be assessed (instance declarations, that is, 
	 * triples with predicate rdf:type are ignored). Identifies URIs appearing in both, the subject 
	 * and object of the triple and adds them to the set of URIs to be evaluated for dereferenceability
	 * @param quad Triple (in quad format) to be evaluated
	 */
	public void compute(Quad quad) throws MetricProcessingException {
		logger.debug("Assessing {}", quad.asTriple().toString());

		
		// we are currently ignoring triples ?s a ?o
		if (!(quad.getPredicate().getURI().equals(RDF.type.getURI()))){ 
			totalNumberOfTriples++;
			String subject = quad.getSubject().toString();
			if (httpRetriever.isPossibleURL(subject)) {
				logger.trace("URI found on subject: {}", subject);
				addUriToDereference(subject);
				totalNumberOfResources++;
			}

			String object = quad.getObject().toString();
			if (httpRetriever.isPossibleURL(object)) {
				logger.trace("URI found on object: {}", object);
				addUriToDereference(object);
				totalNumberOfResources++;
			}
		}
	}

	/**
	 * Initiates the dereferencing process of some of the URIs identified in the dataset, chosen in accordance 
	 * with a statistical sampling method, in order to compute the estimated dereferenceability of the whole dataset 
	 * @return estimated dereferencibility, computed as aforementioned
	 */
	@Override
	public Double metricValue() {
		
		if(!this.metricCalculated) {
			List<String> lstUrisToDeref = new ArrayList<String>();			
			
			for(Tld tld : this.tldsReservoir.getItems()){
				lstUrisToDeref.addAll(tld.getfqUris().getItems());
			}
			this.totalURIs = (long) lstUrisToDeref.size();
			this.totalDerefUris = this.deReferenceUris(lstUrisToDeref);
			this.metricValue = (double)this.totalDerefUris / (double)lstUrisToDeref.size();
		}
		
		return this.metricValue;
	}
	
	public Resource getMetricURI() {
		return this.METRIC_URI;
	}

	
	/* ------------------------------------ Private Methods ------------------------------------------------ */
	
	/**
	 * Checks and properly processes an URI found as subject or object of a triple, adding it to the
	 * set of TLDs and fully-qualified URIs 
	 * @param uri URI to be processed
	 */
	private void addUriToDereference(String uri) {
		// Extract the top-level domain and look for it within the reservoir 
		String uriTLD = HTTPRetriever.extractTopLevelDomainURI(uri);
		Tld newTld = new Tld(uriTLD, MAX_FQURIS_PER_TLD);		
		Tld foundTld = this.tldsReservoir.findItem(newTld);
		
		if(foundTld == null) {
			logger.trace("New TLD found and recorded: {}...", uriTLD);
			// Add the new TLD to the reservoir
			this.tldsReservoir.add(newTld);
			// Add new fully qualified URI to those of the new TLD
			newTld.addFqUri(uri);
		} else {
			// The identified TLD was found, it already exists on the reservoir, just add the fqdn to it
			foundTld.addFqUri(uri);
		}
	}
	
	/**
	 * Tries to dereference all the URIs contained in the parameter, by retrieving them from the cache. URIs
	 * not found in the cache are added to the queue containing the URIs to be fetched by the async HTTP retrieval process
	 * @param uriSet Set of URIs to be dereferenced
	 * @return list with the results of the dereferenceability operations, for those URIs that were found in the cache 
	 */
	private long deReferenceUris(List<String> uriSet) {
		// Start the dereferenciation process, which will be run in parallel
		httpRetriever.addListOfResourceToQueue(uriSet);
		httpRetriever.start(true);
		
		List<String> lstToDerefUris = new ArrayList<String>(uriSet);
		long totalDerefUris = 0;
				
		// Dereference each and every one of the URIs contained in the specified set
		while(lstToDerefUris.size() > 0) {
			// Remove the URI at the head of the queue of URIs to be dereferenced                
			String headUri = lstToDerefUris.remove(0);
			
			// First, search for the URI in the cache
			CachedHTTPResource httpResource = (CachedHTTPResource)dcmgr.getFromCache(LinkedDataMetricsCacheManager.HTTP_RESOURCE_CACHE, headUri);
			
			if (httpResource == null || httpResource.getStatusLines() == null) {
				// URIs not found in the cache, is still to be fetched via HTTP, add it to the end of the list
				lstToDerefUris.add(headUri);
			} else {
				// URI found in the cache (which means that was fetched at some point), check if successfully dereferenced
				if (Dereferencer.hasValidDereferencability(httpResource)) {
					totalDerefUris++;
				}
				
				if (requireProblemReport) createProblemReport(httpResource);
				logger.trace("{} - {} - {}", headUri, httpResource.getStatusLines(), httpResource.getDereferencabilityStatusCode());
			}
		}
		
		return totalDerefUris;
	}
	
	private void createProblemReport(CachedHTTPResource httpResource){
		StatusCode sc = httpResource.getDereferencabilityStatusCode();
		
		switch (sc){
			case SC200 : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC200OK); break;
			case SC301 : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC301MovedPermanently); break;
			case SC302 : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC302Found); break;
			case SC307 : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC307TemporaryRedirectory); break;
			case SC3XX : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC3XXRedirection); break;
			case SC4XX : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC4XXClientError); break;
			case SC5XX : this.createProblemQuad(httpResource.getUri(), DQMPROB.SC5XXServerError); break;
			case SC303 : if (!httpResource.isContentParsable())  this.createProblemQuad(httpResource.getUri(), DQMPROB.SC303WithoutParsableContent); break;
 			default	   : break;
		}
	}
	
	
	private void createProblemQuad(String resource, Resource problem){
		Quad q = new Quad(null, ModelFactory.createDefaultModel().createResource(resource).asNode(), QPRO.exceptionDescription.asNode(), problem.asNode());
		this.problemCollection.addProblem(q);
	}
	
	@Override
	public boolean isEstimate() {
		return true;
	}

	@Override
	public Resource getAgentURI() {
		return 	DQM.LuzzuProvenanceAgent;
	}


	@Override
	public ProblemCollection<?> getProblemCollection() {
		return this.problemCollection;
	}

	@Override
	public Model getObservationActivity() {
		Model activity = ModelFactory.createDefaultModel();
		
		Resource mp = ResourceCommons.generateURI();
		activity.add(mp, RDF.type, DAQ.MetricProfile);
		
		//TODO: Add profiling information, including what estimation technique used. See Extensional Conciseness metric

		
		activity.add(mp, DAQ.totalDatasetTriplesAssessed, ResourceCommons.generateTypeLiteral((long)this.totalNumberOfTriples));
		activity.add(mp, DQM.totalNumberOfResourcesAssessed, ResourceCommons.generateTypeLiteral((long)this.totalNumberOfResources));
		activity.add(mp, DQM.totalValidDereferenceableURIs, ResourceCommons.generateTypeLiteral((int)this.totalDerefUris));
		activity.add(mp, DQM.totalNumberOfResources, ResourceCommons.generateTypeLiteral((long)this.totalURIs));
		activity.add(mp, DAQ.estimationTechniqueUsed, ModelFactory.createDefaultModel().createResource("https://dblp.uni-trier.de/rec/conf/esws/DebattistaL0A15"));

		
		Resource ep = ResourceCommons.generateURI();
		activity.add(mp, DAQ.estimationParameter, ep);
		activity.add(ep, RDF.type, DAQ.EstimationParameter);
		activity.add(ep, DAQ.estimationParameterValue, ResourceCommons.generateTypeLiteral(MAX_FQURIS_PER_TLD));
		activity.add(ep, DAQ.estimationParameterKey, ResourceCommons.generateTypeLiteral("k"));
		activity.add(ep, RDFS.comment, activity.createLiteral("The size of the reservior for each pay-level domain (pld).", "en"));

		Resource ep2 = ResourceCommons.generateURI();
		activity.add(mp, DAQ.estimationParameter, ep2);
		activity.add(ep2, RDF.type, DAQ.EstimationParameter);
		activity.add(ep2, DAQ.estimationParameterValue, ResourceCommons.generateTypeLiteral(MAX_TLDS));
		activity.add(ep2, DAQ.estimationParameterKey, ResourceCommons.generateTypeLiteral("plds-k"));
		activity.add(ep2, RDFS.comment, activity.createLiteral("The size of the global reservior holding the pay-level domains (pld).", "en"));

		return activity;
	}
}