package org.zoodb.profiling.api.impl;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zoodb.jdo.TransactionImpl;
import org.zoodb.jdo.api.impl.DBStatistics;
import org.zoodb.profiling.api.IDataProvider;
import org.zoodb.profiling.api.IFieldManager;
import org.zoodb.profiling.api.IPathManager;
import org.zoodb.profiling.api.IProfilingManager;
import org.zoodb.profiling.suggestion.AbstractSuggestion;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

/**
 * @author tobiasg
 *
 */
public class ProfilingManager implements IProfilingManager {
	
	private Logger logger = LogManager.getLogger("allLogger");
	
	private static ProfilingManager singleton = null;
	
	/**
	 * Filename where profiling data will be stored
	 */
	private String pfn;
	
	private Date begin;
	private Date end;
	
	private IPathManager pathManager;
	private IFieldManager fieldManager;
	
	private Collection<AbstractSuggestion> suggestions;
	
	private static String currentTrxId;
	
	
	public static ProfilingManager getInstance() {
		if (singleton == null) {
			singleton = new ProfilingManager();
		}
		return singleton;
	}
	
	private ProfilingManager() {
		pathManager = new PathManagerTreeV2();
		fieldManager = new FieldManager();
		suggestions = new LinkedList<AbstractSuggestion>();
	}
	
	@Override
	public void save() {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyy-HHmm");
		pfn = "profiler_" + sdf.format(begin) + "-" + sdf.format(end) + ".xml";
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(pfn);
			
			XStream xstream = new XStream(new DomDriver("UTF-8"));
			
			for (AbstractSuggestion s : suggestions) {
				//System.out.println(xstream.toXML(s));
				xstream.toXML(s, fos);
			}
			fos.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public IPathManager getPathManager() {
		return pathManager;
	}

	@Override
	public IFieldManager getFieldManager() {
		return fieldManager;
	}

	@Override
	public void newTrxEvent(TransactionImpl trx) {
		logger.info("New Trx: " + trx.getUniqueTrxId());
		currentTrxId = trx.getUniqueTrxId();
	}
	
	public String getCurrentTrxId() {
		return currentTrxId;
	}

	@Override
	public IDataProvider getDataProvider() {
		// TODO dataprovider should be a singleton
		ProfilingDataProvider dp = new ProfilingDataProvider();
		dp.setFieldManager((FieldManager) fieldManager);
		return dp;
	}

	@Override
	public void finish() {
		end = new Date();
		//getPathManager().prettyPrintPaths();
        
        
        //getPathManager().aggregateObjectPaths();
        //getPathManager().prettyPrintClassPaths(true);
        
        
        //ProfilingManager.getInstance().getPathManager().optimizeListPaths();
	}

	@Override
	public void init() {
		DBStatistics.enable(true);
		begin = new Date();
	}

	@Override
	public void addSuggestion(AbstractSuggestion s) {
		suggestions.add(s);
	}

	@Override
	public void addSuggestions(Collection<? extends AbstractSuggestion> s) {
		suggestions.addAll(s);
	}

}