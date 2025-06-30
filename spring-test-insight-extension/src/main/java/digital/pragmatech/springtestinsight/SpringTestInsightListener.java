package digital.pragmatech.springtestinsight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextBootstrapper;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring TestExecutionListener that tracks test execution and context cache usage.
 * This listener runs with highest precedence to capture context loading before Spring's own listeners.
 */
public class SpringTestInsightListener extends AbstractTestExecutionListener {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringTestInsightListener.class);
    
    // Shared instances for tracking across all tests
    private static final TestExecutionTracker executionTracker = new TestExecutionTracker();
    private static final ContextCacheTracker contextCacheTracker = new ContextCacheTracker();
    private static final TestExecutionReporter reporter = new TestExecutionReporter();
    
    // Track current test class and method
    private final Map<TestContext, String> testClassNames = new ConcurrentHashMap<>();
    private final Map<TestContext, Instant> methodStartTimes = new ConcurrentHashMap<>();
    
    // Static flag to ensure report is generated only once
    private static volatile boolean reportGenerated = false;
    
    @Override
    public int getOrder() {
        // Run with highest precedence to capture context loading early
        return Ordered.HIGHEST_PRECEDENCE;
    }
    
    @Override
    public void beforeTestClass(TestContext testContext) throws Exception {
        Class<?> testClass = testContext.getTestClass();
        String className = testClass.getName();
        
        logger.debug("Starting Spring Test Insight for test class: {}", className);
        
        // Start tracking if this is the first test class
        if (executionTracker.getTotalTestClasses() == 0) {
            executionTracker.startTracking();
        }
        
        // Record test class start
        testClassNames.put(testContext, className);
        executionTracker.recordTestClassStart(className);
        
        // Extract and track context configuration
        try {
            TestContextBootstrapper bootstrapper = BootstrapUtils.resolveTestContextBootstrapper(testClass);
            MergedContextConfiguration mergedConfig = bootstrapper.buildMergedContextConfiguration();
            
            // The cache key is the hashCode of the MergedContextConfiguration
            int cacheKey = mergedConfig.hashCode();
            
            // Track the association between cache key and test class
            contextCacheTracker.recordTestClassForContext(cacheKey, className, mergedConfig);
            
            logger.info("Test class {} uses context cache key {}", className, cacheKey);
            
        } catch (Exception e) {
            logger.warn("Failed to extract context configuration for test class {}: {}", 
                className, e.getMessage());
        }
    }
    
    @Override
    public void prepareTestInstance(TestContext testContext) throws Exception {
        // After test instance is prepared, the context should be loaded
        String className = testClassNames.get(testContext);
        if (className != null) {
            try {
                Class<?> testClass = testContext.getTestClass();
                TestContextBootstrapper bootstrapper = BootstrapUtils.resolveTestContextBootstrapper(testClass);
                MergedContextConfiguration mergedConfig = bootstrapper.buildMergedContextConfiguration();
                int cacheKey = mergedConfig.hashCode();
                
                // Now check if this was a cache hit or miss
                // If the context was already tracked as created for another test, it's a hit
                Optional<ContextCacheTracker.ContextCacheEntry> entry = contextCacheTracker.getCacheEntry(cacheKey);
                if (entry.isPresent() && entry.get().isCreated()) {
                    contextCacheTracker.recordContextCacheHit(cacheKey);
                    logger.debug("Context cache hit for test class {}", className);
                } else {
                    contextCacheTracker.recordContextCreation(cacheKey);
                    logger.debug("New context created for test class {}", className);
                }
            } catch (Exception e) {
                logger.warn("Failed to track context loading for test class {}: {}", className, e.getMessage());
            }
        }
    }
    
    @Override
    public void afterTestClass(TestContext testContext) throws Exception {
        String className = testClassNames.get(testContext);
        if (className != null) {
            executionTracker.recordTestClassEnd(className);
            logger.debug("Completed Spring Test Insight for test class: {}", className);
        }
        
        // Clean up
        testClassNames.remove(testContext);
    }
    
    @Override
    public void beforeTestMethod(TestContext testContext) throws Exception {
        String className = testClassNames.get(testContext);
        String methodName = testContext.getTestMethod().getName();
        
        if (className != null) {
            executionTracker.recordTestMethodStart(className, methodName);
            methodStartTimes.put(testContext, Instant.now());
        }
    }
    
    @Override
    public void afterTestMethod(TestContext testContext) throws Exception {
        String className = testClassNames.get(testContext);
        String methodName = testContext.getTestMethod().getName();
        
        if (className != null) {
            // Determine test status
            TestStatus status = determineTestStatus(testContext);
            executionTracker.recordTestMethodEnd(className, methodName, status);
            
            // Clean up
            methodStartTimes.remove(testContext);
        }
    }
    
    @Override
    public void afterTestExecution(TestContext testContext) throws Exception {
        // Check if all tests are complete and generate report
        // This is a simplified approach - in production, you might want a more sophisticated
        // mechanism to determine when all tests are complete
        checkAndGenerateReport();
    }
    
    private TestStatus determineTestStatus(TestContext testContext) {
        if (testContext.getTestException() != null) {
            return TestStatus.FAILED;
        }
        return TestStatus.PASSED;
    }
    
    private synchronized void checkAndGenerateReport() {
        // In a real implementation, you would need a more sophisticated way
        // to determine when all tests are complete. This might involve:
        // - JVM shutdown hooks
        // - Integration with build tools
        // - Custom test suite completion detection
        
        // For now, we'll rely on the JUnit extension to trigger report generation
    }
    
    /**
     * Called by the JUnit extension to generate the final report.
     */
    public static void generateReport(String phase) {
        synchronized (SpringTestInsightListener.class) {
            if (!reportGenerated) {
                logger.info("Generating Spring Test Insight report for {} phase...", phase);
                executionTracker.stopTracking();
                
                // Get context cache statistics including our custom tracking
                SpringContextCacheAccessor.CacheStatistics springStats = 
                    SpringContextCacheStatistics.getCacheStatistics();
                
                // Generate report with both execution and context cache data
                reporter.generateReport(phase, executionTracker, springStats, contextCacheTracker);
                
                // Clear data
                contextCacheTracker.clear();
                reportGenerated = true;
            }
        }
    }
    
    /**
     * Reset the report generation flag (useful for testing or multi-phase builds).
     */
    public static void resetReportGeneration() {
        reportGenerated = false;
    }
    
    /**
     * Get the context cache tracker for external access.
     */
    public static ContextCacheTracker getContextCacheTracker() {
        return contextCacheTracker;
    }
}