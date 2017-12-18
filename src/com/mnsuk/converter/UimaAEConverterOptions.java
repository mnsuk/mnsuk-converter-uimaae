package com.mnsuk.converter;

import java.util.HashSet;
import java.util.List;

import com.ibm.dataexplorer.converter.ConverterOptions;
import com.ibm.dataexplorer.converter.FatalConverterException;

public class UimaAEConverterOptions {
	private static final String OPTION_PEAR_REPO_PATH = "pear-repo-path";
	private static final String OPTION_PEAR_FILENAME = "pear-filename";
	private static final String OPTION_EXCLUDE_BY_DEFAULT = "exclude-by-default";
	private static final String OPTION_CONTENT_LIST = "content-list";
	private static final String OPTION_ANNOTATION_OFFSETS = "enable-annotation-offsets";
	private static final String OPTION_CONTENT_TYPES = "type";
	 
	public String pearRepoPath;
	public String pearFilenameStr;
	public String uimaCompId;
	public boolean excludeByDefault;
	public boolean annotationOffsets;
	public List<String> contentTypes;
	public HashSet<String> contentList = new HashSet<String>();

	public UimaAEConverterOptions (ConverterOptions options) {
		this.pearRepoPath = options.getLastOptionValue(OPTION_PEAR_REPO_PATH);
		this.pearFilenameStr = options.getLastOptionValue(OPTION_PEAR_FILENAME);
		this.excludeByDefault = OPTION_EXCLUDE_BY_DEFAULT.equals(options.getLastOptionValue(OPTION_EXCLUDE_BY_DEFAULT));
		this.contentList.addAll(options.getOptionValues(OPTION_CONTENT_LIST));
		this.annotationOffsets = OPTION_ANNOTATION_OFFSETS.equals(options.getLastOptionValue(OPTION_ANNOTATION_OFFSETS));
		this.contentTypes = options.getOptionValues(OPTION_CONTENT_TYPES);
	}
	
	public void validateOptions() {
		Boolean stub = false;
		if (stub) { //stub
			throw new FatalConverterException("Required option missing");
		}
	}
}
