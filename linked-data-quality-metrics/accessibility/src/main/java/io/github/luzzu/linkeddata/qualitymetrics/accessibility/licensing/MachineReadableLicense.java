package io.github.luzzu.linkeddata.qualitymetrics.accessibility.licensing;

import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.luzzu.linkeddata.qualitymetrics.commons.AbstractQualityMetric;
import io.github.luzzu.linkeddata.qualitymetrics.vocabulary.DQM;
import io.github.luzzu.operations.properties.EnvironmentProperties;
import io.github.luzzu.qualityproblems.ProblemCollection;
import io.github.luzzu.semantics.commons.ResourceCommons;
import io.github.luzzu.semantics.vocabularies.DAQ;


/**
 * @author Jeremy Debattista
 * 
 * Verifies whether consumers of the dataset are explicitly granted permission to re-use it, under defined 
 * conditions, by annotating the resource with a machine-readable indication (e.g. a VoID description) of the license.
 *  
 */
public class MachineReadableLicense extends AbstractQualityMetric<Boolean> {
	
	private final Resource METRIC_URI = DQM.MachineReadableLicenseMetric;
	
	private static Logger logger = LoggerFactory.getLogger(MachineReadableLicense.class);
	
	
	
	/**
	 * Allows to determine if a predicate states what is the licensing schema of a resource
	 */
	private LicensingModelClassifier licenseClassifier = new LicensingModelClassifier();
	
	
	private boolean hasValidMachineReadableLicense = false;
	
	
	/**
	 * Processes a single quad being part of the dataset. Firstly, tries to figure out the URI of the dataset whence the quads come. 
	 * If so, the URI is extracted from the corresponding subject and stored to be used in the calculation of the metric. Otherwise, verifies 
	 * whether the quad contains licensing information (by checking if the property is part of those known to be about licensing) and if so, stores 
	 * the URL of the subject in the map of resources confirmed to have licensing information
	 * @param quad Quad to be processed and examined to try to extract the dataset's URI
	 */
	public void compute(Quad quad) {
		logger.debug("Computing : {} ", quad.asTriple().toString());

		// Extract the predicate (property) of the statement, the described resource (subject) and the value set (object)
		Node subject = quad.getSubject();
		Node predicate = quad.getPredicate();
		Node object = quad.getObject();
		
		
		
		if ((subject.getURI().equals(EnvironmentProperties.getInstance().getDatasetPLD()))
			&& (licenseClassifier.isLicensingPredicate(predicate))) {
			
			if (object.isURI()) {
				if ((licenseClassifier.isCopyLeftLicenseURI(object)) || (licenseClassifier.isNotRecommendedCopyLeftLicenseURI(object))) {
					// We have a license and we have to check if it is machine readable
					try{
						Model licenseModel = RDFDataMgr.loadModel(object.getURI());
						if (licenseClassifier.containsMachineReadableLicense(licenseModel)) this.hasValidMachineReadableLicense = true;
						else {
							// add to problem report as DQMPROB.NotMachineReadableLicense
						}
					} catch (Exception e) {
						// add to problem report as DQMPROB.NotMachineReadableLicense
					}
					
					if (licenseClassifier.isNotRecommendedCopyLeftLicenseURI(object)) {
						// add to problem report as DQMPROB.NotRecommendedLicenceInDataset
					}
				}
			}
		}
		
	}
		
	@Override
	public Boolean metricValue() {
		return this.hasValidMachineReadableLicense;
	}


	
	public Resource getMetricURI() {
		return METRIC_URI;
	}

	
	@Override
	public boolean isEstimate() {
		return false;
	}

	@Override
	public Resource getAgentURI() {
		return 	DQM.LuzzuProvenanceAgent;
	}


	@Override
	public ProblemCollection<?> getProblemCollection() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Model getObservationActivity() {
		Model activity = ModelFactory.createDefaultModel();
		
		Resource mp = ResourceCommons.generateURI();
		activity.add(mp, RDF.type, DAQ.MetricProfile);
		
		return activity;
	}
	
}