/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.jdeps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import edu.illinois.starts.constants.StartsConstants;
import edu.illinois.starts.enums.DependencyFormat;
import edu.illinois.starts.helpers.RTSUtil;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.helpers.ZLCHelper;
import edu.illinois.starts.jdeps.BaseMojo.Result;
import edu.illinois.starts.maven.AgentLoader;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import edu.illinois.yasgl.DirectedGraph;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.surefire.booter.Classpath;

/**
 * Prepares for test runs by writing non-affected tests in the excludesFile.
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.TEST)
public class RunMojo extends DiffMojo implements StartsConstants {
    private static final String TARGET = "target";
    /**
     * Set this to "false" to prevent checksums from being persisted to disk. This
     * is useful for "dry runs" where one may want to see the non-affected tests that
     * STARTS writes to the Surefire excludesFile, without updating test dependencies.
     */
    @Parameter(property = "updateRunChecksums", defaultValue = TRUE)
    protected boolean updateRunChecksums;

    /**
     * Set this option to "true" to run all tests, not just the affected ones. This option is useful
     * in cases where one is interested to measure the time to run all tests, while at the
     * same time measuring the times for analyzing what tests to select and reporting the number of
     * tests it would select.
     * Note: Run with "-DstartsLogging=FINER" or "-DstartsLogging=FINEST" so that the "selected-tests"
     * file, which contains the list of tests that would be run if this option is set to false, will
     * be written to disk.
     */
    @Parameter(property = "retestAll", defaultValue = FALSE)
    protected boolean retestAll;

    /**
     * Set this to "true" to save nonAffectedTests to a file on disk. This improves the time for
     * updating test dependencies in offline mode by not running computeChangeData() twice.
     * Note: Running with "-DstartsLogging=FINEST" also saves nonAffectedTests to a file on disk.
     */
    @Parameter(property = "writeNonAffected", defaultValue = "false")
    protected boolean writeNonAffected;

    /**
     * Set this to "true" to save changedClasses to a file on disk.
     * Note: Running with "-DstartsLogging=FINEST" also saves changedClasses to a file on disk.
     */
    @Parameter(property = "writeChangedClasses", defaultValue = "false")
    protected boolean writeChangedClasses;

    protected Set<String> nonAffectedTests;
    protected Set<String> changedClasses;
    protected List<Pair> jarCheckSums = null;

    private Logger logger;

    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        logger = Logger.getGlobal();
        long start = System.currentTimeMillis();
        setIncludesExcludes();
        run();
        Set<String> allTests = new HashSet<>(getTestClasses(CHECK_IF_ALL_AFFECTED));
        if (writeNonAffected || logger.getLoggingLevel().intValue() <= Level.FINEST.intValue()) {
            Writer.writeToFile(nonAffectedTests, "non-affected-tests", getArtifactsDir());
        }
        if (allTests.equals(nonAffectedTests)) {
            logger.log(Level.INFO, STARS_RUN_STARS);
            logger.log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        long end = System.currentTimeMillis();
        System.setProperty(PROFILE_END_OF_RUN_MOJO, Long.toString(end));
        logger.log(Level.FINE, PROFILE_RUN_MOJO_TOTAL + Writer.millsToSeconds(end - start));
    }

    protected void run() throws MojoExecutionException {
        String cpString = Writer.pathToString(getSureFireClassPath().getClassPath());
        List<String> sfPathElements = getCleanClassPath(cpString);
        nonAffectedTests = new HashSet<>();
        HashSet<String> AffectedTests = new HashSet<>();
        
        Classpath sfClassPath = getSureFireClassPath();
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());
        List<String> allTests = getTestClasses("updateForNextRun");
        Set<String> affectedTests = new HashSet<>(allTests);
        System.out.println(affectedTests.size());
        
        DirectedGraph<String> graph = null;
        ClassLoader loader = createClassLoader(sfClassPath);
        //TODO: set this boolean to true only for static reflectionAnalyses with * (border, string, naive)?
        
        boolean computeUnreached = true;
        Result result = prepareForNextRun(sfPathString, sfClassPath, allTests, nonAffectedTests, computeUnreached);
        Map<String, Set<String>> testDeps = result.getTestDeps();
        graph = result.getGraph();
        Set<String> unreached = computeUnreached ? result.getUnreachedDeps() : new HashSet<String>();        
        
        ZLCHelper zlcHelper = new ZLCHelper();
        zlcHelper.updateZLCFile(testDeps, loader, getArtifactsDir(), unreached);
        
        save(getArtifactsDir(), affectedTests, allTests, sfPathString, graph);
        
        if (!isSameClassPath(sfPathElements) || !hasSameJarChecksum(sfPathElements)) {
            // Force retestAll because classpath changed since last run
            // don't compute changed and non-affected classes
        	
            
            try {
            	AffectedTests=check();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            System.out.println("affectedtests_num:"+AffectedTests.size());
            affectedTests.removeAll(AffectedTests);
            System.out.println(allTests);
//            allTests.removeAll(AffectedTests);
            List<String> excludePaths = Writer.fqnsToExcludePath(affectedTests);
            dynamicallyUpdateExcludes(excludePaths);
            Writer.writeClassPath(cpString, artifactsDir);
            Writer.writeJarChecksums(sfPathElements, artifactsDir, jarCheckSums);
        } else if (retestAll) {
            // Force retestAll but compute changes and affected tests
            setChangedAndNonaffected();
            dynamicallyUpdateExcludes(new ArrayList<String>());
        } else {
            setChangedAndNonaffected();
            List<String> excludePaths = Writer.fqnsToExcludePath(nonAffectedTests);
            dynamicallyUpdateExcludes(excludePaths);
        }
        long startUpdateTime = System.currentTimeMillis();
        if (updateRunChecksums) {
            updateForNextRun(nonAffectedTests);
        }
        long endUpdateTime = System.currentTimeMillis();
        logger.log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME
                + Writer.millsToSeconds(endUpdateTime - startUpdateTime));
    }

    private void dynamicallyUpdateExcludes(List<String> excludePaths) throws MojoExecutionException {
        if (AgentLoader.loadDynamicAgent()) {
            logger.log(Level.FINEST, "AGENT LOADED!!!");
            System.setProperty(STARTS_EXCLUDE_PROPERTY, Arrays.toString(excludePaths.toArray(new String[0])));
        } else {
            throw new MojoExecutionException("I COULD NOT ATTACH THE AGENT");
        }
    }

    protected void setChangedAndNonaffected() throws MojoExecutionException {
        nonAffectedTests = new HashSet<>();
        changedClasses = new HashSet<>();
        Pair<Set<String>, Set<String>> data = computeChangeData(writeChangedClasses);
        nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        changedClasses  = data == null ? new HashSet<String>() : data.getValue();
    }

    private boolean isSameClassPath(List<String> sfPathString) throws MojoExecutionException {
        if (sfPathString.isEmpty()) {
            return true;
        }
        String oldSfPathFileName = Paths.get(getArtifactsDir(), SF_CLASSPATH).toString();
        if (!new File(oldSfPathFileName).exists()) {
            return false;
        }
        try {
            List<String> oldClassPathLines = Files.readAllLines(Paths.get(oldSfPathFileName));
            if (oldClassPathLines.size() != 1) {
                throw new MojoExecutionException(SF_CLASSPATH + " is corrupt! Expected only 1 line.");
            }
            List<String> oldClassPathelements = getCleanClassPath(oldClassPathLines.get(0));
            // comparing lists and not sets in case order changes
            if (sfPathString.equals(oldClassPathelements)) {
                return true;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return false;
    }

    private boolean hasSameJarChecksum(List<String> cleanSfClassPath) throws MojoExecutionException {
        if (cleanSfClassPath.isEmpty()) {
            return true;
        }
        String oldChecksumPathFileName = Paths.get(getArtifactsDir(), JAR_CHECKSUMS).toString();
        if (!new File(oldChecksumPathFileName).exists()) {
            return false;
        }
        boolean noException = true;
        try {
            List<String> lines = Files.readAllLines(Paths.get(oldChecksumPathFileName));
            Map<String, String> checksumMap = new HashMap<>();
            for (String line : lines) {
                String[] elems = line.split(COMMA);
                checksumMap.put(elems[0], elems[1]);
            }
            jarCheckSums = new ArrayList<>();
            for (String path : cleanSfClassPath) {
                Pair<String, String> pair = Writer.getJarToChecksumMapping(path);
                jarCheckSums.add(pair);
                String oldCS = checksumMap.get(pair.getKey());
                noException &= pair.getValue().equals(oldCS);
            }
        } catch (IOException ioe) {
            noException = false;
            // reset to null because we don't know what/when exception happened
            jarCheckSums = null;
            ioe.printStackTrace();
        }
        return noException;
    }

    private List<String> getCleanClassPath(String cp) {
        List<String> cpPaths = new ArrayList<>();
        String[] paths = cp.split(File.pathSeparator);
        String classes = File.separator + TARGET +  File.separator + CLASSES;
        String testClasses = File.separator + TARGET + File.separator + TEST_CLASSES;
        for (int i = 0; i < paths.length; i++) {
            // TODO: should we also exclude SNAPSHOTS from same project?
            if (paths[i].contains(classes) || paths[i].contains(testClasses)) {
                continue;
            }
            cpPaths.add(paths[i]);
        }
        return cpPaths;
    }
    
    public  HashSet<String> check() throws MojoExecutionException, IOException{
    	String select_tests_path = Writer.pathToString(getSureFireClassPath().getClassPath());
    	HashSet<String> changed_tests = new HashSet<String>(); 
    	HashSet<String> diff_library = new HashSet<String>();  
    	
    	BufferedReader br = new BufferedReader(new FileReader("/Users/chenlingchao/PycharmProjects/information/RTS/RTS_results2.txt"));
        String line = null;
        while ((line = br.readLine()) != null) {
        	diff_library.add(line);
        }
        br.close();
        
        
        BufferedReader br2 = new BufferedReader(new FileReader("/Users/chenlingchao/test/retry4j/.starts/deps.zlc"));
        String line2 = null;
        while ((line2 = br2.readLine()) != null) {
        	String tmp=line2.split("!")[1]; 
        	tmp=tmp.split(" ")[0];
//        	System.out.println(tmp.substring(0,tmp.length()-6));
        	if (diff_library.contains(tmp.substring(0,tmp.length()-6))){
        		String tp=line2.split(" ")[2];
        		String tp_array[]=tp.split(",");
        		for(String test:tp_array){
        			changed_tests.add(test);
        		}
        	}       	
        }
        br2.close();
        System.out.println(changed_tests.size());
        return changed_tests;
    }
    
    
}
