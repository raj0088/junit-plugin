package hudson.tasks.junit.pipeline;

import com.google.common.base.Predicate;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.TestResultTest;
import hudson.tasks.test.PipelineBlockWithTests;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.StageStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class JUnitResultsStepTest {
    @Rule
    public final JenkinsRule rule = new JenkinsRule();

    @ClassRule
    public final static BuildWatcher buildWatcher = new BuildWatcher();

    @Test
    public void configRoundTrip() throws Exception {
        SnippetizerTester st = new SnippetizerTester(rule);
        JUnitResultsStep step = new JUnitResultsStep("**/target/surefire-reports/TEST-*.xml");
        st.assertRoundTrip(step, "junit '**/target/surefire-reports/TEST-*.xml'");
        step.setAllowEmptyResults(true);
        st.assertRoundTrip(step, "junit allowEmptyResults: true, testResults: '**/target/surefire-reports/TEST-*.xml'");
        step.setHealthScaleFactor(2.0);
        st.assertRoundTrip(step, "junit allowEmptyResults: true, healthScaleFactor: 2.0, testResults: '**/target/surefire-reports/TEST-*.xml'");
        MockTestDataPublisher publisher = new MockTestDataPublisher("testing");
        step.setTestDataPublishers(Collections.<TestDataPublisher>singletonList(publisher));
        st.assertRoundTrip(step, "junit allowEmptyResults: true, healthScaleFactor: 2.0, testDataPublishers: [[$class: 'MockTestDataPublisher', name: 'testing']], testResults: '**/target/surefire-reports/TEST-*.xml'");
    }

    @Test
    public void emptyFails() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "emptyFails");
        j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                "  node {\n" +
                "    sh 'echo hi'\n" +
                "    junit('*.xml')\n" +
                "  }\n" +
                "}\n", true));

        WorkflowRun r = j.scheduleBuild2(0).waitForStart();
        rule.assertBuildStatus(Result.FAILURE, rule.waitForCompletion(r));
        rule.assertLogContains("ERROR: No test report files were found. Configuration error?", r);
    }

    @Test
    public void allowEmpty() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "allowEmpty");
        j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                "  node {\n" +
                "    sh 'echo hi'\n" +
                "    def results = junit(testResults: '*.xml', allowEmptyResults: true)\n" +
                "    assert results.totalCount == 0\n" +
                "  }\n" +
                "}\n", true));

        WorkflowRun r = rule.buildAndAssertSuccess(j);
        assertNull(r.getAction(TestResultAction.class));
        rule.assertLogContains("None of the test reports contained any result", r);
    }

    @Test
    public void singleStep() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "singleStep");
        j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                "  node {\n" +
                "    def results = junit(testResults: '*.xml')\n" + // node id 7
                "    assert results.totalCount == 6\n" +
                "  }\n" +
                "}\n", true));
        FilePath ws = rule.jenkins.getWorkspaceFor(j);
        FilePath testFile = ws.child("test-result.xml");
        testFile.copyFrom(TestResultTest.class.getResource("junit-report-1463.xml"));

        WorkflowRun r = rule.buildAndAssertSuccess(j);
        TestResultAction action = r.getAction(TestResultAction.class);
        assertNotNull(action);
        assertEquals(1, action.getResult().getSuites().size());
        assertEquals(6, action.getTotalCount());

        assertExpectedResults(r, 1, 6, "7");

        // Case result display names shouldn't include stage, since there's only one stage.
        for (CaseResult c : action.getPassedTests()) {
            assertEquals(c.getTransformedTestName(), c.getDisplayName());
        }
    }

    @Test
    public void twoSteps() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "twoSteps");
        j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                "  node {\n" +
                "    def first = junit(testResults: 'first-result.xml')\n" +    // node id 7
                "    def second = junit(testResults: 'second-result.xml')\n" +  // node id 8
                "    assert first.totalCount == 6\n" +
                "    assert second.totalCount == 1\n" +
                "  }\n" +
                "}\n", true));
        FilePath ws = rule.jenkins.getWorkspaceFor(j);
        FilePath testFile = ws.child("first-result.xml");
        testFile.copyFrom(TestResultTest.class.getResource("junit-report-1463.xml"));
        FilePath secondTestFile = ws.child("second-result.xml");
        secondTestFile.copyFrom(TestResultTest.class.getResource("junit-report-2874.xml"));

        WorkflowRun r = rule.buildAndAssertSuccess(j);
        TestResultAction action = r.getAction(TestResultAction.class);
        assertNotNull(action);
        assertEquals(2, action.getResult().getSuites().size());
        assertEquals(7, action.getTotalCount());

        // First call
        assertExpectedResults(r, 1, 6, "7");

        // Second call
        assertExpectedResults(r, 1, 1, "8");

        // Combined calls
        assertExpectedResults(r, 2, 7, "7", "8");

        // Case result display names shouldn't include stage, since there's only one stage.
        for (CaseResult c : action.getPassedTests()) {
            assertEquals(c.getTransformedTestName(), c.getDisplayName());
        }
    }

    @Test
    public void threeSteps() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "threeSteps");
        j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                "  node {\n" +
                "    def first = junit(testResults: 'first-result.xml')\n" +    // node id 7
                "    def second = junit(testResults: 'second-result.xml')\n" +  // node id 8
                "    def third = junit(testResults: 'third-result.xml')\n" +    // node id 9
                "    assert first.totalCount == 6\n" +
                "    assert second.totalCount == 1\n" +
                "  }\n" +
                "}\n", true));
        FilePath ws = rule.jenkins.getWorkspaceFor(j);
        FilePath testFile = ws.child("first-result.xml");
        testFile.copyFrom(TestResultTest.class.getResource("junit-report-1463.xml"));
        FilePath secondTestFile = ws.child("second-result.xml");
        secondTestFile.copyFrom(TestResultTest.class.getResource("junit-report-2874.xml"));
        FilePath thirdTestFile = ws.child("third-result.xml");
        thirdTestFile.copyFrom(TestResultTest.class.getResource("junit-report-nested-testsuites.xml"));

        WorkflowRun r = rule.assertBuildStatus(Result.UNSTABLE,
                rule.waitForCompletion(j.scheduleBuild2(0).waitForStart()));
        TestResultAction action = r.getAction(TestResultAction.class);
        assertNotNull(action);
        assertEquals(5, action.getResult().getSuites().size());
        assertEquals(10, action.getTotalCount());

        // First call
        assertExpectedResults(r, 1, 6, "7");

        // Second call
        assertExpectedResults(r, 1, 1, "8");

        // Third call
        assertExpectedResults(r, 3, 3, "9");

        // Combined first and second calls
        assertExpectedResults(r, 2, 7, "7", "8");

        // Combined first and third calls
        assertExpectedResults(r, 4, 9, "7", "9");

        // Case result display names shouldn't include stage, since there's only one stage.
        for (CaseResult c : action.getPassedTests()) {
            assertEquals(c.getTransformedTestName(), c.getDisplayName());
        }
    }

    @Test
    public void parallelInStage() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "parallelInStage");
        FilePath ws = rule.jenkins.getWorkspaceFor(j);
        FilePath testFile = ws.child("first-result.xml");
        testFile.copyFrom(TestResultTest.class.getResource("junit-report-1463.xml"));
        FilePath secondTestFile = ws.child("second-result.xml");
        secondTestFile.copyFrom(TestResultTest.class.getResource("junit-report-2874.xml"));
        FilePath thirdTestFile = ws.child("third-result.xml");
        thirdTestFile.copyFrom(TestResultTest.class.getResource("junit-report-nested-testsuites.xml"));

        j.setDefinition(new CpsFlowDefinition("stage('first') {\n" +
                "  node {\n" +
                "    parallel(a: { def first = junit(testResults: 'first-result.xml'); assert first.totalCount == 6 },\n" +
                "             b: { def second = junit(testResults: 'second-result.xml'); assert second.totalCount == 1 },\n" +
                "             c: { def third = junit(testResults: 'third-result.xml'); assert third.totalCount == 3 })\n" +
                "  }\n" +
                "}\n", true
        ));
        WorkflowRun r = rule.assertBuildStatus(Result.UNSTABLE,
                rule.waitForCompletion(j.scheduleBuild2(0).waitForStart()));
        TestResultAction action = r.getAction(TestResultAction.class);
        assertNotNull(action);
        assertEquals(5, action.getResult().getSuites().size());
        assertEquals(10, action.getTotalCount());

        assertBranchResults(r, 1, 6, "a", "first");
        assertBranchResults(r, 1, 1, "b", "first");
        assertBranchResults(r, 3, 3, "c", "first");
        assertStageResults(r, 5, 10, "first");
    }

    @Test
    public void testTrends() throws Exception {
        WorkflowJob j = rule.jenkins.createProject(WorkflowJob.class, "testTrends");
        j.setDefinition(new CpsFlowDefinition("node {\n" +
                "  stage('first') {\n" +
                "    def first = junit(testResults: \"junit-report-testTrends-first-${env.BUILD_NUMBER}.xml\")\n" +
                "  }\n" +
                "  stage('second') {\n" +
                "    def second = junit(testResults: \"junit-report-testTrends-second-${env.BUILD_NUMBER}.xml\")\n" +
                "  }\n" +
                "}\n", true));
        FilePath ws = rule.jenkins.getWorkspaceFor(j);
        for (int i = 1; i < 4; i++) {
            FilePath firstFile = ws.child("junit-report-testTrends-first-" + i + ".xml");
            firstFile.copyFrom(JUnitResultsStepTest.class.getResource("junit-report-testTrends-first-" + i + ".xml"));
            FilePath secondFile = ws.child("junit-report-testTrends-second-" + i + ".xml");
            secondFile.copyFrom(JUnitResultsStepTest.class.getResource("junit-report-testTrends-second-" + i + ".xml"));
        }

        WorkflowRun firstRun = rule.buildAndAssertSuccess(j);
        assertStageResults(firstRun, 1, 8, 0, "first");
        assertStageResults(firstRun, 1, 1, 0, "second");
        WorkflowRun secondRun = rule.assertBuildStatus(Result.UNSTABLE, rule.waitForCompletion(j.scheduleBuild2(0).waitForStart()));
        assertStageResults(secondRun, 1, 8, 3, "first");
        assertStageResults(secondRun, 1, 1, 0, "second");
        WorkflowRun thirdRun = rule.assertBuildStatus(Result.UNSTABLE, rule.waitForCompletion(j.scheduleBuild2(0).waitForStart()));
        assertStageResults(thirdRun, 1, 8, 3, "first");
        assertStageResults(thirdRun, 1, 1, 0, "second");
        TestResultAction thirdAction = thirdRun.getAction(TestResultAction.class);
        assertNotNull(thirdAction);

        for (CaseResult failed : thirdAction.getFailedTests()) {
            if (failed.getDisplayName() != null) {
                if (failed.getDisplayName().equals("first / org.twia.vendor.VendorManagerTest.testGetVendorFirmKeyForVendorRep") ||
                        failed.getDisplayName().equals("first / org.twia.vendor.VendorManagerTest.testCreateAdjustingFirm")) {
                    assertEquals(2, failed.getFailedSince());
                } else if (failed.getDisplayName().equals("first / org.twia.vendor.VendorManagerTest.testCreateVendorFirm")) {
                    assertEquals(3, failed.getFailedSince());
                }
            }
        }
    }


    private static Predicate<FlowNode> branchForName(final String name) {
        return new Predicate<FlowNode>() {
            @Override
            public boolean apply(@Nullable FlowNode input) {
                return input != null &&
                        input.getAction(LabelAction.class) != null &&
                        input.getAction(ThreadNameAction.class) != null &&
                        name.equals(input.getAction(ThreadNameAction.class).getThreadName());
            }
        };
    }

    private static Predicate<FlowNode> stageForName(final String name) {
        return new Predicate<FlowNode>() {
            @Override
            public boolean apply(@Nullable FlowNode input) {
                return input instanceof StepStartNode &&
                        ((StepStartNode) input).getDescriptor() instanceof StageStep.DescriptorImpl &&
                        input.getDisplayName().equals(name);
            }
        };
    }

    public static void assertBranchResults(WorkflowRun run, int suiteCount, int testCount, String branchName, String stageName) {
        assertBranchResults(run, suiteCount, testCount, -1, branchName, stageName);
    }

    public static void assertBranchResults(WorkflowRun run, int suiteCount, int testCount, int failCount, String branchName, String stageName) {
        FlowExecution execution = run.getExecution();
        DepthFirstScanner scanner = new DepthFirstScanner();
        FlowNode aBranch = scanner.findFirstMatch(execution, branchForName(branchName));
        assertNotNull(aBranch);
        TestResult branchResult = assertBlockResults(run, suiteCount, testCount, failCount, aBranch);
        for (CaseResult c : branchResult.getPassedTests()) {
            assertEquals(stageName + " / " + branchName + " / " + c.getTransformedTestName(), c.getDisplayName());
        }
    }

    public static void assertStageResults(WorkflowRun run, int suiteCount, int testCount, String stageName) {
        assertStageResults(run, suiteCount, testCount, -1, stageName);
    }

    public static void assertStageResults(WorkflowRun run, int suiteCount, int testCount, int failCount, String stageName) {
        FlowExecution execution = run.getExecution();
        DepthFirstScanner scanner = new DepthFirstScanner();
        FlowNode aStage = scanner.findFirstMatch(execution, stageForName(stageName));
        assertNotNull(aStage);
        assertBlockResults(run, suiteCount, testCount, failCount, aStage);
    }

    private static TestResult assertBlockResults(WorkflowRun run, int suiteCount, int testCount, int failCount, FlowNode blockNode) {
        assertNotNull(blockNode);

        TestResultAction action = run.getAction(TestResultAction.class);
        assertNotNull(action);

        TestResult aResult = action.getResult().getResultForPipelineBlock(blockNode.getId());
        assertNotNull(aResult);

        assertEquals(suiteCount, aResult.getSuites().size());
        assertEquals(testCount, aResult.getTotalCount());
        if (failCount > -1) {
            assertEquals(failCount, aResult.getFailCount());
        }

        PipelineBlockWithTests aBlock = action.getResult().getPipelineBlockWithTests(blockNode.getId());

        assertNotNull(aBlock);
        List<String> aTestNodes = new ArrayList<>(aBlock.nodesWithTests());
        TestResult aFromNodes = action.getResult().getResultByNodes(aTestNodes);
        assertNotNull(aFromNodes);
        assertEquals(aResult.getSuites().size(), aFromNodes.getSuites().size());
        assertEquals(aResult.getFailCount(), aFromNodes.getFailCount());
        assertEquals(aResult.getSkipCount(), aFromNodes.getSkipCount());
        assertEquals(aResult.getPassCount(), aFromNodes.getPassCount());

        return aResult;
    }

    private void assertExpectedResults(Run<?,?> run, int suiteCount, int testCount, String... nodeIds) throws Exception {
        TestResultAction action = run.getAction(TestResultAction.class);
        assertNotNull(action);

        TestResult result = action.getResult().getResultByNodes(Arrays.asList(nodeIds));
        assertNotNull(result);
        assertEquals(suiteCount, result.getSuites().size());
        assertEquals(testCount, result.getTotalCount());
    }

    public static class MockTestDataPublisher extends TestDataPublisher {
        private final String name;
        @DataBoundConstructor
        public MockTestDataPublisher(String name) {
            this.name = name;
        }
        public String getName() {
            return name;
        }
        @Override public TestResultAction.Data contributeTestData(Run<?,?> run, FilePath workspace, Launcher launcher, TaskListener listener, TestResult testResult) throws IOException, InterruptedException {
            return null;
        }

        // Needed to make this extension available to all tests for {@link #testDescribableRoundTrip()}
        @TestExtension
        public static class DescriptorImpl extends Descriptor<TestDataPublisher> {
            @Override public String getDisplayName() {
                return "MockTestDataPublisher";
            }
        }
    }
}