package com.mnsuk.converter;


import static com.ibm.dataexplorer.converter.LoggingConstants.PUBLIC_ENTRY;
import static com.ibm.dataexplorer.converter.LoggingConstants.PUBLIC_EXIT;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.ArrayFS;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.CASRuntimeException;
import org.apache.uima.cas.ConstraintFactory;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.FSTypeConstraint;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.TypeSystem;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.cas.text.AnnotationIndex;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.JFSIndexRepository;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.pear.tools.InstallationDescriptor;
import org.apache.uima.pear.tools.PackageBrowser;
import org.apache.uima.pear.tools.PackageInstaller;
import org.apache.uima.resource.Resource;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceManager;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.util.XMLInputSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import com.ibm.dataexplorer.converter.ByteArrayConverter;
import com.ibm.dataexplorer.converter.ConversionException;
import com.ibm.dataexplorer.converter.ConverterOptions;
import com.ibm.dataexplorer.converter.FatalConverterException;
import com.vivisimo.parser.input.ConverterInput;
import com.vivisimo.parser.input.InputFilter;
import com.vivisimo.parser.input.InputFilterFactory;
import com.vivisimo.parser.input.VXMLInputBuilder;

public class UimaAE implements ByteArrayConverter {

	private static final String SENTENCETYPE = "uima.tt.SentenceAnnotation";
	private static final String PARAGRAPHTYPE = "uima.tt.ParagraphAnnotation";
	private static final String LEMMATYPE = "uima.tt.Lemma";
	private static final String LEMMAKEY = "key";
	
	private static final Logger LOGGER = LoggerFactory.getLogger(UimaAE.class);
	private UimaAEConverterOptions opts;
	private boolean isAlive;
	private UimaEngine engine = null;

	public UimaAE(ConverterOptions options) throws FatalConverterException {
		LOGGER.trace("entry");
		this.opts = new UimaAEConverterOptions(options);
		engine = getAnalysisEngine();
		if (engine == null)
			throw new FatalConverterException("Error creating UIMA analysis engine.");
		isAlive = true;
		LOGGER.trace("exit");
	}

	/**
	 * @throws FatalConverterException
	 * 
	 * Get a UIMA analysis engine for the configured pear file. Update or use an existing 
	 * one depending on the timestamp, if it has already been installed, or install and 
	 * create a new one if it hasn't.
	 */
	private UimaEngine getAnalysisEngine() throws FatalConverterException {
		UimaEngine result = null;
		File pearSupportFolder = getPearSupportFolder();		
		if (!pearSupportFolder.isDirectory()) {
			String msg = pearSupportFolder != null ? "Installation directory " + getPearSupportFolder().toString() : "Pear support folder";
			msg += " does not exist.";
			LOGGER.error(msg);
			throw new FatalConverterException(msg); 
		}
		File pearRepo = new File(pearSupportFolder, "repo");		
		if (!pearRepo.isDirectory()) {
			String msg = "Pear repository directory " + pearRepo.toString() + " does not exist.";
			LOGGER.error(msg);
			throw new FatalConverterException(msg); 
		}
		File pearRun = new File(pearSupportFolder, "run");
		if (!pearRun.isDirectory()) {
			String msg = "Installed pear directory " + pearRun.toString() + " does not exist.";
			LOGGER.error(msg);
			throw new FatalConverterException(msg);  
		}
		if (!pearRun.canWrite()) {
			String msg = "Cannot write to the pear installation directory " + pearRun.toString();
			LOGGER.error(msg);
			throw new FatalConverterException(msg);  
		}
		File pear = new File(pearRepo, opts.pearFilenameStr);
		if (!pear.isFile()) {
			String msg = "Specified Pear file cannot be read: " + pear.toString();
			LOGGER.error(msg);
			throw new FatalConverterException(msg);
		}
		long pearModified = pear.lastModified();
		InstallationDescriptor id;
		try {
			JarFile jpear = new JarFile(pear);
			PackageBrowser pb = new PackageBrowser(jpear);
			id = pb.getInstallationDescriptor();
		} catch (Exception e) {
			throw new FatalConverterException("Error reading pear file: " + e.getMessage(), (Throwable) e);
		}
		String pearCompId = id.getMainComponentId();
		if (pearCompId == null) {
			Date now = new Date();
			pearCompId = pear.getName().replaceFirst("[.][^.]+$", "");
			String msg = "PEAR file contains no component ID, using filename as ID.";
			LOGGER.warn(msg);
		}
		LOGGER.info("PEAR file component ID is " + pearCompId);
		File installedPearLoc = new File(pearRun, pearCompId);
		PackageBrowser installedPear;
		try {
			if (installedPearLoc.isDirectory()) { // it's already installed
				long installModified = installedPearLoc.lastModified();
				
				installedPear = new PackageBrowser(installedPearLoc);
				id = installedPear.getInstallationDescriptor();
				if (id == null || !id.getMainComponentId().equals(pearCompId)) {
					throw new FatalConverterException("Specified Component ID does not match existing installed pear file");
				}
				if (installModified < pearModified) { // needs an update
					delete(installedPearLoc); 
					installedPear = PackageInstaller.installPackage(pearRun, pear, true);
					LOGGER.info("Updating pear file installation: " + opts.pearFilenameStr);
				} else {
					LOGGER.info("Pear file: " + opts.pearFilenameStr + " is already installed.");
				}
			} else {  // install it
				installedPear = PackageInstaller.installPackage(pearRun, pear, true);
				LOGGER.info("Installing pear file: " + opts.pearFilenameStr);
			}
		} catch (Exception e) {
			LOGGER.error("Pear install error: " + e.getMessage());
			throw new FatalConverterException("Pear install error: " + e.getMessage(), (Throwable) e);
		}
		try {
			ResourceManager rsMgr = UIMAFramework.newDefaultResourceManager();
			XMLInputSource in = new XMLInputSource(installedPear.getComponentPearDescPath());
			ResourceSpecifier rspec = UIMAFramework.getXMLParser().parseResourceSpecifier(in);
			// tuning CAS
			Properties perfProps = UIMAFramework.getDefaultPerformanceTuningProperties();
			perfProps.setProperty(UIMAFramework.CAS_INITIAL_HEAP_SIZE, "1000000");
			HashMap<String, Object> params = new HashMap<String, Object>();
			params.put(Resource.PARAM_PERFORMANCE_TUNING_SETTINGS, perfProps);
			AnalysisEngine ae = UIMAFramework.produceAnalysisEngine(rspec, rsMgr, params);
			result = new UimaEngine(ae);
		} catch (Exception e) {
			LOGGER.error("Error creating  analysis engine from pear: " + e.getMessage());
			throw new FatalConverterException("Error creating  analysis engine from pear: " + e.getMessage(), (Throwable) e);
		}
		return result;
	}

	/**
	 * @throws FatalConverterException
	 * 
	 * Return a File descriptor to the folder where pear support is installed in WexFC Engine.
	 */
	private File getPearSupportFolder() {
		URI pearSupportFile;
		// try the easy way first. Look for base class and resolve from there.
		try {
			final URL codeSourceLocation =
					ByteArrayConverter.class.getProtectionDomain().getCodeSource().getLocation();

			if (codeSourceLocation != null) {
				String path = codeSourceLocation.toString();
				if (path.startsWith("jar:")) {
					// remove "jar:" prefix and "!/" suffix
					final int index = path.indexOf("!/");
					path = path.substring(4, index);
				}
				if (System.getProperty("os.name").startsWith("Win") && path.matches("file:[A-Za-z]:.*")) {
					path = "file:/" + path.substring(5);
				}
				File pearsupport;
				try {
					pearSupportFile = new URL(path).toURI();
					Path p = Paths.get(pearSupportFile);
					pearsupport = p.getParent().getParent().getParent().resolve("pearsupport").toFile();
				} catch (Exception e) {
					LOGGER.error("Error determining pear support folder location. " + e.getMessage());
					throw new FatalConverterException("Error determining pear support folder location. " + e.getMessage());
				}
				return pearsupport;
			}
		}  catch (final SecurityException e) {
			// NB: Cannot access protection domain.
		}
		catch (final NullPointerException e) {
			// NB: Protection domain or code source is null.
		}
		// NB: The easy way failed, so we try the hard way. We ask for the class
		// itself as a resource, then strip the class's path from the URL string,
		// leaving the base path.

		// get the class's raw resource path
		final URL classResource = ByteArrayConverter.class.getResource(ByteArrayConverter.class.getSimpleName() + ".class");
		if (classResource == null) return null; // cannot find class resource

		final String url = classResource.toString();
		final String suffix = ByteArrayConverter.class.getCanonicalName().replace('.', '/') + ".class";
		if (!url.endsWith(suffix)) return null; // weird URL

		// strip the class's path from the URL string
		final String base = url.substring(0, url.length() - suffix.length());

		String path = base;

		// remove the "jar:" prefix and "!/" suffix, if present
		if (path.startsWith("jar:")) path = path.substring(4, path.length() - 2);

		try {
			pearSupportFile = new URL(path).toURI();
			Path p = Paths.get(pearSupportFile);
			File pearsupport = p.getParent().getParent().resolve("pearsupport").toFile();
			return pearsupport;
		}
		catch (Exception e) {
			LOGGER.error("Error determining pear support folder location. " + e.getMessage());
			throw new FatalConverterException("Error determining pear support folder location. " + e.getMessage());
		}
	}

	@Override
	public byte[] convert(byte[] data) throws ConversionException, FatalConverterException {
		LOGGER.trace(PUBLIC_ENTRY);
		checkIsAlive();

		try {
			String stringData = convertToString(data);
			Throwable throwable = null;
			try {
				VXMLInputBuilder inputBuilder = new VXMLInputBuilder(stringData);
				for (ConverterInput inputDocument : inputBuilder.documents()) {
					InputFilter filter = InputFilterFactory.createInputFilter(inputDocument, opts.contentList);
					String filteredContents = filter.filterInput(opts.excludeByDefault);
					if (filteredContents == null || filteredContents.isEmpty()) {
						continue;
					}
					engine.setDocumentText(filteredContents);

					for (String f : opts.contentTypes){

						String[] t = f.split(String.valueOf(TypeSystem.FEATURE_SEPARATOR));
						String contentName = (t.length >= 1 ) ? t[0] : null; 
						String typeName = (t.length >= 2 ) ? t[1] : null; 
						String featureName = (t.length == 3) ? t[2] : null;

						List<AnnotationFS> annos = engine.extractAnno(typeName);
						appendEntities(inputDocument.getDocumentElement(), annos, contentName, typeName, featureName);
					}
				} 
				return convertToBytes(inputBuilder.newOutputBuilder(opts.excludeByDefault).toString());

			} catch (Throwable inputBuilder) {
				throwable = inputBuilder;
				throw inputBuilder;
			}

		} catch (Exception e) {
			throw new ConversionException("Error calling UIMA Analysis Engine: " + e.getMessage(), (Throwable) e);
		} 
		finally {
			LOGGER.trace(PUBLIC_EXIT);
		}

	}
	
	private void appendEntities(Element documentElement, List<AnnotationFS> annos, String contentName, String typeName, String featureName) {
		for (AnnotationFS anno : annos) {
			Element newContent = documentElement.getOwnerDocument().createElement("content");
			if (featureName==null) {
				List<Feature> fts = anno.getType().getFeatures();
				for (Feature ft  : fts) {
					if (ft.getShortName().equals("ruleId") || ft.getShortName().equals("sofa") )
						continue;
					String str="";
					if (ft.getRange().isPrimitive()) {
						if (!(ft.getShortName().equals("begin") || ft.getShortName().equals("end")) || opts.annotationOffsets ) {
							str = anno.getFeatureValueAsString(ft);
						} else {
							continue;
						}
					} else if (ft.getRange().isArray()) { // try the covered text on the first element 
						FeatureStructure fs = ((ArrayFS) anno.getFeatureValue(ft)).get(0);
						if (fs != null) {
							if (fs.getType().getFeatureByBaseName("begin") != null) // it's an annotation
								str = ((AnnotationFS) fs).getCoveredText();
							else
								str = "";
						}		
					} else {
						FeatureStructure fs = anno.getFeatureValue(ft);
						String name = fs.getType().getName();
						if (name.equals(SENTENCETYPE) || name.equals(PARAGRAPHTYPE))
							str = ((AnnotationFS) fs).getCoveredText();
						else if ( name.equals(LEMMATYPE)) {
							Type lemmaFSType = fs.getType();
							Feature lemmaKey = lemmaFSType.getFeatureByBaseName(LEMMAKEY);
							if (lemmaKey != null) str = fs.getStringValue(lemmaKey);
						}
					}
					newContent.setAttribute(ft.getShortName(),str);
				}
				newContent.setTextContent(anno.getCoveredText());
				newContent.setAttribute("name", contentName);
			} else {
				String featureValue = extractPrimitiveFeatureAsString(anno, featureName);
				newContent.setTextContent(featureValue);
				newContent.setAttribute("name", contentName);
				//newContent.setAttribute("feature", featureName);
				newContent.setAttribute("coveredtext",anno.getCoveredText());
				if (opts.annotationOffsets) {
					String begin = extractPrimitiveFeatureAsString(anno, "begin");
					String end = extractPrimitiveFeatureAsString(anno, "end");
					newContent.setAttribute("begin", begin);
					newContent.setAttribute("end", end);
				}
			}
			documentElement.appendChild(newContent);
			LOGGER.info("Creating content element from annotation "+anno.getType().getName());
		}
	}
	
	public class UimaEngine {
		private AnalysisEngine ae = null;
		private String docText = null;
		
		public UimaEngine(AnalysisEngine ae) {
			this.ae=ae;
		}
		
		public void setDocumentText(String content) {
			this.docText=content;		
		}
		
		public List<AnnotationFS> extractAnno(String annoType) {
			if (docText == null || docText.isEmpty()) {
				LOGGER.error("No document text to analyse");
				return null;
			}
			CAS cas = null;
			try {
				cas = ae.newCAS();
				cas.setDocumentText(docText);
				cas.setDocumentLanguage("en");
				ae.process(cas);

			} catch (ResourceInitializationException e) {
				LOGGER.error("CAS processing error" + e);
			} catch (AnalysisEngineProcessException e) {
				LOGGER.error("CAS processing error" + e);
			}
			ArrayList<AnnotationFS> annos = extractAFSList(cas, annoType);	
			//cas.reset(); // ?
			return annos;
		}
		
		/**
		 * Extract a list of annotation feature structures for a given type name.
		 * <p>
		 *
		 * @param  jcas 
		 * @param  typeName Full type name to extract
		 * @return List of AnnotationFS
		 */	
		private final ArrayList<AnnotationFS> extractAFSList(CAS cas, String typeName) {
			ArrayList<AnnotationFS> annotations = new ArrayList<AnnotationFS>();
			try {
				JCas jcas = cas.getJCas();
				TypeSystem typeSystem = jcas.getTypeSystem();
				Type type = typeSystem.getType(typeName);

				if (type!=null) {

					JFSIndexRepository indexRepository = jcas.getJFSIndexRepository();
					AnnotationIndex<Annotation> index = indexRepository.getAnnotationIndex();

					ConstraintFactory cf = jcas.getConstraintFactory();
					FSTypeConstraint filter = cf.createTypeConstraint();

					filter.add(type);

					FSIterator<Annotation> list = jcas.createFilteredIterator(index.iterator(), filter);
					while (list.hasNext()) {
						AnnotationFS afs = (AnnotationFS)list.next();
						annotations.add(afs);
					}
				} else {
					LOGGER.warn("Type " + typeName + " not found in typesystem");
				}
			}
			catch (CASRuntimeException e) {
				LOGGER.warn("CAS processing error" + e.toString());
			} catch (CASException e) {
				LOGGER.error("CAS processing error" + e.toString());
			}
			return annotations;
		}
	}

	private byte[] convertToBytes(String data) throws UnsupportedEncodingException {
		return data == null ? null : data.getBytes("UTF-8");
	}

	private String convertToString(byte[] data) throws UnsupportedEncodingException {
		return data == null ? "" : new String(data, "UTF-8");
	}

	@Override
	public void terminate() {
		checkIsAlive();

		LOGGER.trace("Terminating");
		isAlive = false;

		// Nothing to terminate
	}

	private void checkIsAlive() {
		if (!isAlive()) {
			LOGGER.error("I've already been terminated");
			throw new IllegalStateException("The object has already been terminated");
		}
	}

	boolean isAlive() {
		return isAlive;
	}

	private static final String extractPrimitiveFeatureAsString(AnnotationFS afs, String feature){
		Feature ft = null;
		String ret = null;
		Type type = afs.getType();

		try {
			ft = type.getFeatureByBaseName(feature);
			if (ft == null) {
				LOGGER.warn("Failed to find feature for extract: " + feature);
				return ret;
			}

			if (ft.getRange().isPrimitive()) {
				ret = afs.getFeatureValueAsString(ft);
			} else if (ft.getRange().isArray()) { // try the covered text on the first element 
				FeatureStructure fs = ((ArrayFS) afs.getFeatureValue(ft)).get(0);
				if (fs != null) {
					if (fs.getType().getFeatureByBaseName("begin") != null) // it's an annotation
						ret = ((AnnotationFS) fs).getCoveredText();
				}		
			} else {
				FeatureStructure fs = afs.getFeatureValue(ft);
				String name = fs.getType().getName();
				if (name.equals(SENTENCETYPE) || name.equals(PARAGRAPHTYPE))
					ret = ((AnnotationFS) fs).getCoveredText();
				else if ( name.equals(LEMMATYPE)) {
					Type lemmaFSType = fs.getType();
					Feature lemmaKey = lemmaFSType.getFeatureByBaseName(LEMMAKEY);
					if (lemmaKey != null) ret = fs.getStringValue(lemmaKey);
				}
			}


		} catch (CASRuntimeException e) {
			LOGGER.debug("Failed to get feature value for feature: " + ft.getName() + " " + e.toString(),e);
		}
		return ret;
	}
	
	/**
	 * Delete a file or a directory and its children.
	 * @param file The directory to delete.
	 * @throws IOException Exception when problem occurs during deleting the directory.
	 */
	private static void delete(File file) throws IOException {
 
		for (File childFile : file.listFiles()) {
 
			if (childFile.isDirectory()) {
				delete(childFile);
			} else {
				if (!childFile.delete()) {
					throw new IOException();
				}
			}
		}
 
		if (!file.delete()) {
			throw new IOException();
		}
	}
}
