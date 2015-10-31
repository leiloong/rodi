package com.fluidops.rdb2rdfbench.eval;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.antidot.semantic.rdf.model.impl.sesame.SesameDataSet;
import net.antidot.semantic.rdf.rdb2rdf.r2rml.core.R2RMLProcessor;
import net.antidot.sql.model.core.DriverType;

import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.UpdateExecutionException;
import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFHandlerException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.InferredAxiomGenerator;
import org.semanticweb.owlapi.util.InferredClassAssertionAxiomGenerator;
import org.semanticweb.owlapi.util.InferredEquivalentClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredEquivalentDataPropertiesAxiomGenerator;
import org.semanticweb.owlapi.util.InferredEquivalentObjectPropertyAxiomGenerator;
import org.semanticweb.owlapi.util.InferredInverseObjectPropertiesAxiomGenerator;
import org.semanticweb.owlapi.util.InferredOntologyGenerator;
import org.semanticweb.owlapi.util.InferredPropertyAssertionGenerator;
import org.semanticweb.owlapi.util.InferredSubClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubDataPropertyAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubObjectPropertyAxiomGenerator;

import com.fluidops.rdb2rdfbench.Config;
import com.fluidops.rdb2rdfbench.db.rdf.SesameAdapter;
import com.fluidops.rdb2rdfbench.db.rdf.SesameAdapter.RepoType;
import com.fluidops.rdb2rdfbench.db.rel.RdbmsUtil;
import com.fluidops.rdb2rdfbench.util.ReportWriter;
import com.fluidops.rdb2rdfbench.util.SetupUtil;

/**
 * Collection of static methods to run end-to end evaluations.
 * 
 * @author cp
 *
 */
public class Evaluator {

	public enum EvalStep {
		/**
		 * All evaluation steps at once.
		 */
		ALL,
		/**
		 * Execution of R2RML mappings (i.e., fill A-Box).
		 */
		R2RML_ONLY,
		/**
		 * Perform reasoning on A-Box.
		 */
		REASONING_ONLY,
		/**
		 * Run query tests (main evaluation step)
		 */
		QUERIES_ONLY
	}

	private static List<String> fetchR2RmlMappingFiles() {
		Config cfg = Config.getInstance();
		List<String> ret = new ArrayList<String>();

		File path = new File(cfg.getR2RmlPath());

		if (path.isDirectory()) {
			for (File file : path.listFiles())
				if (!file.isHidden())
					ret.add(file.getAbsolutePath());
		} else
			ret.add(cfg.getR2RmlPath()); // single file

		return ret;
	}

	private static void applyR2Rml(List<String> mappingFiles, String scenario)
			throws Exception {
		SesameAdapter eval = SesameAdapter.getInstance(RepoType.EVALUATION);

		// execute mappings (db2triples):
		Connection jdbc = RdbmsUtil.getConnection();

		try {
			for (String r2rmlFile : mappingFiles) {

				SesameDataSet ds;
				if (Config.getInstance().getSelectDbms().equals("mysql")) {
					jdbc.createStatement().execute("USE " + scenario);
					ds = R2RMLProcessor.convertDatabase(jdbc, r2rmlFile,
							"urn:base-uri-", DriverType.MysqlDriver);
				} else {
					jdbc.createStatement().execute("SET SEARCH_PATH TO " + scenario);
					ds = R2RMLProcessor.convertDatabase(jdbc, r2rmlFile,
							"urn:base-uri-", DriverType.PostgreSQL);
				}

				eval.add(ds.tuplePattern(null, null, null));

			}
		} catch (Exception e) {
			throw new RuntimeException("Error while executing R2RML mappings: "
					+ e);
		} finally {
			jdbc.close();
		}
	}

	private static QueryResultChecker evaluateQueryMapping(QueryPair query,
			SqlResultSet reference) throws RepositoryException,
			MalformedQueryException, QueryEvaluationException {

		// execute SPARQL query
		SesameAdapter ses = SesameAdapter.getInstance(RepoType.EVALUATION);
		SparqlResultSet result = ses.query(query);

		// evaluate results:
		QueryResultChecker eval = new QueryResultChecker(query.getName(),
				result, reference, query.getQueryMapping(),
				query.getCategories());

		System.out.println("Evaluated query '" + query.getName()
				+ "', precision = " + eval.getPrecision() + ", recall = "
				+ eval.getRecall());

		return eval;
	}

	private static SqlResultSet runSql(String scenario, QueryPair query)
			throws SQLException {
		Connection conn = RdbmsUtil.getConnection();

		if (Config.getInstance().getSelectDbms().equals("mysql")) {
			conn.createStatement().execute("USE " + scenario);
		} else {
			conn.createStatement().execute("SET SEARCH_PATH TO " + scenario);
		}

		PreparedStatement prep = conn.prepareStatement(query.getSqlQuery());
		ResultSet res = prep.executeQuery();
		SqlResultSet ret = new SqlResultSet(res, query.getName(),
				query.getSqlEntityIdColumns());
		res.close();
		prep.close();
		conn.close();
		return ret;
	}

	/**
	 * Prepares for evaluation: fetch and execute R2RML mappings, thus
	 * transforming relational data into triples and load them in the store for
	 * evaluation. May fail on {@link RuntimeException}s.
	 * 
	 * @param scenario
	 *            Scenario ID.
	 */
	public static void runR2Rml(String scenario) {
		Config cfg = Config.getInstance();

		System.out.println("Executing R2RML mappings to load data...");

		// preparing evaluation repository: loading data
		if (!cfg.getR2RmlPath().isEmpty())
			try {
				applyR2Rml(fetchR2RmlMappingFiles(), scenario);
			} catch (Exception e) {
				throw new RuntimeException(
						"Failed to execute R2RML mappings for evaluation: " + e);
			}
	}

	private static void addInferredAxioms(OWLOntologyManager mgr,
			OWLOntology o, OWLReasoner reasoner) {
		List<InferredAxiomGenerator<? extends OWLAxiom>> inferredAxioms = new ArrayList<InferredAxiomGenerator<? extends OWLAxiom>>();

		inferredAxioms.add(new InferredSubClassAxiomGenerator());
		inferredAxioms.add(new InferredEquivalentClassAxiomGenerator());

		inferredAxioms.add(new InferredClassAssertionAxiomGenerator());

		inferredAxioms.add(new InferredPropertyAssertionGenerator());

		// Not supported by konclude
		inferredAxioms
				.add(new InferredEquivalentDataPropertiesAxiomGenerator());
		inferredAxioms
				.add(new InferredEquivalentObjectPropertyAxiomGenerator());
		inferredAxioms.add(new InferredSubDataPropertyAxiomGenerator());
		inferredAxioms.add(new InferredSubObjectPropertyAxiomGenerator());

		inferredAxioms.add(new InferredInverseObjectPropertiesAxiomGenerator());

		InferredOntologyGenerator ontoGen = new InferredOntologyGenerator(
				reasoner, inferredAxioms);
		ontoGen.fillOntology(mgr, o);
	}

	private static void filterDatatypesInPropertyAssertions(
			OWLOntologyManager mgr, OWLOntology o) {

		// Avoid types in dataproperty assertions... (at some point property
		// assertions are created with "wrong" types)
		Set<OWLAxiom> toAdd = new HashSet<OWLAxiom>();
		Set<OWLAxiom> toDel = new HashSet<OWLAxiom>();

		for (OWLNamedIndividual ind : o.getIndividualsInSignature(true)) {

			for (OWLDataPropertyAssertionAxiom ax : o
					.getDataPropertyAssertionAxioms(ind)) {
				toAdd.add(mgr.getOWLDataFactory()
						.getOWLDataPropertyAssertionAxiom(ax.getProperty(),
								ind, ax.getObject()));

				toDel.add(ax);
			}
		}
		mgr.addAxioms(o, toAdd);
		mgr.removeAxioms(o, toDel);

	}

	private static void runFullReasoning(ReasonerManager.REASONER reasonerID)
			throws RepositoryException, MalformedQueryException,
			QueryEvaluationException, RDFHandlerException,
			OWLOntologyCreationException, OWLOntologyStorageException {
		System.out.println("Pushing data to reasoner...");

		SesameAdapter eval = SesameAdapter.getInstance(RepoType.EVALUATION);

		// OWLAPI
		OWLOntology o = eval.exportToOwl();
		OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();

		// Reasoner
		System.out.println("Initializing reasoner...");

		OWLReasoner reasoner = ReasonerManager.getReasoner(reasonerID, o);

		System.out.println("Inferring facts...");
		addInferredAxioms(OWLManager.createOWLOntologyManager(), o, reasoner);

		reasoner.dispose();

		// Avoid types in dataproperty assertions... (at some point property
		// assertions are created with "wrong" types)
		filterDatatypesInPropertyAssertions(mgr, o);

		// write back to database
		System.out.println("Writing back to database...");
		eval.loadFromOwl(o);
	}

	private static void runSimplifiedReasoning() throws RepositoryException,
			MalformedQueryException, QueryEvaluationException,
			UpdateExecutionException {

		System.out.println("Starting simplified reasoning...");

		SesameAdapter eval = SesameAdapter.getInstance(RepoType.EVALUATION);

		String prefixes = "prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
				+ "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
				+ "prefix owl: <http://www.w3.org/2002/07/owl#> ";

		// class hierarchies, up to five levels up:
		System.out.println("Adding types...");

		// equivalent classes
		System.out.println("Step 1/4...");
		String equivs = prefixes + "INSERT {" + "?inst a ?c2" + "} WHERE {"
				+ "?inst a ?c1 . ?c1 owl:equivalentClass ?c2 }";

		eval.plainSparqlUpdate(equivs);
		eval.plainSparqlUpdate(equivs); // equiv of equiv

		// hierarchies
		System.out.println("Step 2/4...");
		String superClassQueryA = prefixes + "INSERT {" + "?inst a ?c2"
				+ "} WHERE {" + "?inst a ?c1 . ?c1 rdfs:subClassOf ?bridge . "
				+ " {?bridge rdfs:subClassOf ?c2} "
				+ "UNION {?bridge rdfs:subClassOf ?b2 . "
				+ "?b2 rdfs:subClassOf ?b3 ." + "?b3 rdfs:subClassOf ?c2} "
				+ "}";

		eval.plainSparqlUpdate(superClassQueryA);

		System.out.println("Step 3/4...");
		String superClassQueryB = prefixes + "INSERT {" + "?inst a ?c2"
				+ "} WHERE {" + "?inst a ?c1 . ?c1 rdfs:subClassOf ?c2 }";

		eval.plainSparqlUpdate(superClassQueryB);

		// equivalent classes once more (for equivalents of super classes)
		System.out.println("Step 1/4...");

		eval.plainSparqlUpdate(equivs);
		eval.plainSparqlUpdate(equivs);

		// mirroring inverse object properties
		System.out.println("Adding inverse properties...");

		String inverseQuery = prefixes + "INSERT {?inst2 ?inv ?inst1} "
				+ "WHERE {?inst1 ?prop ?inst2 . ?inv owl:inverseOf ?prop}";

		eval.plainSparqlUpdate(inverseQuery);
	}

	/**
	 * Prepares for evaluation: runs Hermit on data in triple store to derive
	 * implicit facts. Assumes that R2RML mappings have previously been executed
	 * to load data, or mapped data has been previously imported from an
	 * external source.
	 * 
	 * @throws RepositoryException
	 * @throws OWLOntologyStorageException
	 * @throws OWLOntologyCreationException
	 * @throws RDFHandlerException
	 * @throws QueryEvaluationException
	 * @throws MalformedQueryException
	 * @throws UpdateExecutionException
	 * 
	 */
	public static void runReasoning() throws RepositoryException,
			OWLOntologyStorageException, MalformedQueryException,
			QueryEvaluationException, RDFHandlerException,
			OWLOntologyCreationException, UpdateExecutionException {

		Config cfg = Config.getInstance();

		if (cfg.getReasoning().equals("hermit")) {
			System.out.println("Reasoner set to 'hermit'.");
			runFullReasoning(ReasonerManager.REASONER.HERMIT);
		} else if (cfg.getReasoning().equals("factpp")) {
			System.out.println("Reasoner set to 'factpp'.");
			runFullReasoning(ReasonerManager.REASONER.FACTpp);
		} else if (cfg.getReasoning().equals("factpp_owllink")) {
			System.out.println("Reasoner set to 'factpp (owl link)'.");
			runFullReasoning(ReasonerManager.REASONER.FACTppOWLlink);
		} else if (cfg.getReasoning().equals("konclude")) {
			System.out.println("Reasoner set to 'konclude'.");
			runFullReasoning(ReasonerManager.REASONER.KONCLUDE);
		} else if (cfg.getReasoning().equals("pellet")) {
			System.out.println("Reasoner set to 'pellet'.");
			runFullReasoning(ReasonerManager.REASONER.PELLET);
		} else if (cfg.getReasoning().equals("simplified")) {
			System.out.println("Reasoner set to 'simplified'.");
			runSimplifiedReasoning();
		} else if (cfg.getReasoning().equals("none")) {
			System.out.println("Reasoning disabled.");
		} else {
			throw new RuntimeException("Configuration error: "
					+ "reasoner must be one of none, simplified, hermit.");
		}

	}

	/**
	 * Evaluates a scenario with all of its associated queries. Returns an
	 * evaluation report for each evaluated query. Evaluation must have been
	 * previously prepared by loading mapping results (see {@link #runR2Rml()})
	 * and completing its closure of inferrable facts, if applicable (see
	 * {@link #runReasoning()}).
	 * 
	 * @param scenario
	 *            ID of scenario to evaluate.
	 * @param debug
	 *            Set true to have detailed per-query reports to be stored
	 *            (includes full query results).
	 * @return List of {@link EvaluationReport}s.
	 */
	public static List<EvaluationReport> evaluate(String scenario, boolean debug)
			throws RepositoryException, IOException, InterruptedException {
		List<EvaluationReport> ret = new ArrayList<EvaluationReport>();

		System.out.println("Testing queries for scenario " + scenario + "...");

		// check results
		List<QueryPair> queries = QueryReader.getScenarioQueries(scenario);

		int disabledCnt = 0;

		for (QueryPair query : queries) {

			if (query.isDisabled()) {
				System.err.println("Warning: query \"" + query.getName()
						+ "\" has been disabled and won't be executed.");
				disabledCnt++;
				continue;
			}

			System.out.println("Running query " + query.getFileNameBase()
					+ " \"" + query.getName() + "\"...");

			SqlResultSet reference;
			try {
				reference = runSql(scenario, query);
			} catch (SQLException e) {
				throw new RuntimeException("Failed to execute SQL query '"
						+ query.sqlQuery + "': " + e);
			}

			QueryResultChecker qrc;

			try {
				qrc = evaluateQueryMapping(query, reference);
			} catch (RepositoryException | MalformedQueryException
					| QueryEvaluationException e) {
				throw new RuntimeException(
						"Failed to execute SPARQL query or error "
								+ "comparing query results: " + e);
			}

			ret.add(qrc.getReport());

			if (debug) {
				ReportWriter.debugReport(qrc);
			}
		}

		if (disabledCnt > 0)
			System.err.println("Warning: " + disabledCnt
					+ " queries were skipped.");

		// add aggregated reports
		System.out.println("Calculating aggregates...");
		ReportAggregator agg = new ReportAggregator(ret);
		ret.add(agg.averageAll());
		ret.addAll(agg.allAveragesByCategory());

		System.out.println("done.");
		return ret;
	}

	/**
	 * Starts the main evaluation cycle for specified scenarios.
	 * 
	 * @param scenarios
	 *            List of scenarios to run.
	 */
	public static void automaticWorkflow(String[] scenarios)
			throws RepositoryException, IOException, InterruptedException {

		Config cfg = Config.getInstance();

		for (String scenario : scenarios) {
			System.out.println("BENCHMARK: preparing scenario " + scenario);

			// setup
			SetupUtil.setupScenario(scenario);

			// sweep old R2RML
			File path = new File(cfg.getR2RmlPath());

			if (path.isDirectory()) {
				for (File file : path.listFiles())
					file.delete();
			} else
				path.delete(); // single file

			// signal READY
			File ready = new File(cfg.getReadyFile());
			FileWriter readyWriter = new FileWriter(ready);
			readyWriter.write(scenario);
			readyWriter.close();

			System.out.println("BENCHMARK: scenario ready: " + scenario);
			System.out.println("BENCHMARK: now waiting for "
					+ "R2RML to be submitted...");

			// wait for R2RML
			if (path.isDirectory()) {
				while (path.listFiles().length == 0)
					Thread.sleep(500);
			} else {
				while (!path.exists())
					Thread.sleep(500);
			}

			Thread.sleep(1000); // wait for a second

			System.out.println("BENCHMARK: R2RML received, "
					+ "starting evaluation.");

			// evaluate
			List<EvaluationReport> reps = evaluate(scenario, false);

			// write reports
			ReportWriter.materializeTextualReports(scenarios[0], "", reps);
			ReportWriter.materializeTabularReports(scenarios[0], "", reps);
		}

		throw new RuntimeException("automatic/batch mode not implemented, yet");
	}
}
