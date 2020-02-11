package com.cloudbees.hudson.plugins.folder.relocate.cli;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.security.AuthorizationStrategy;
import jenkins.model.Jenkins;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static hudson.cli.CLICommandInvoker.Matcher.failedWith;
import static hudson.cli.CLICommandInvoker.Matcher.succeeded;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class MoveCommandTest {

    private static final int STANDARD_CLI_ERROR_CODE = 2;
    private static final int PERMISSION_CLI_ERROR_CODE = 6;

    private static final String DST_FOLDER = "dstFolder";
    private static final String ORIGIN_FOLDER = "originFolder";
    private static final String CHILD_FOLDER = "childFolder";
    private static final String FREE_IN_ROOT_LEVEL = "freeRoot";
    private static final String FREE_IN_ORIGIN_FOLDER = "freeInOrigin";
    private static final String FREE_IN_CHILD_FOLDER = "freeInChild";

    private static final String ADMIN_USER = "admin";
    private static final String NO_ADMIN_USER = "no-admin";

    private Folder origin;
    private Folder child;
    private FreeStyleProject freeRoot;
    private FreeStyleProject freeInOrigin;
    private FreeStyleProject freeInChild;

    @Rule
    public JenkinsRule rule = new JenkinsRule();

    @Before
    public void setUp() throws IOException {
        origin = rule.jenkins.createProject(Folder.class, ORIGIN_FOLDER);
        child = origin.createProject(Folder.class, CHILD_FOLDER);

        freeRoot = rule.jenkins.createProject(FreeStyleProject.class, FREE_IN_ROOT_LEVEL);
        freeInOrigin = origin.createProject(FreeStyleProject.class, FREE_IN_ORIGIN_FOLDER);
        freeInChild = child.createProject(FreeStyleProject.class, FREE_IN_CHILD_FOLDER);
    }

    @Test
    public void failWithoutParameters() {
        CLICommandInvoker invoker = createInvoker(null, null);
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, failedWith(STANDARD_CLI_ERROR_CODE));

        invoker = createInvoker(DST_FOLDER, null);
        result = invoker.invoke();
        assertThat(result, failedWith(STANDARD_CLI_ERROR_CODE));

        final FreeStyleProject[] projects = {freeRoot};
        invoker = createInvoker(null, new FreeStyleProject[] {freeRoot});
        result = invoker.invoke();
        assertThat(result, failedWith(STANDARD_CLI_ERROR_CODE));
    }

    @Test
    public void testPermissions() throws IOException {
        setUpAuthorization();
        rule.jenkins.createProject(Folder.class, DST_FOLDER);

        final FreeStyleProject[] itemsToMove = {freeRoot, freeInOrigin, freeInChild};

        // move without login - Handled by Jenkins CLI
        CLICommandInvoker invoker = createInvoker(DST_FOLDER, itemsToMove);
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, failedWith(6));

        // move with permissions
        invoker = createInvoker(ADMIN_USER, DST_FOLDER, itemsToMove);
        result = invoker.invoke();
        assertThat(result, succeeded());

        // move without permission - Handled by Folder CLI
        invoker = createInvoker(NO_ADMIN_USER, DST_FOLDER, itemsToMove);
        result = invoker.invoke();
        assertThat(result, failedWith(MoveCommand.VALIDATIONS_ERROR_CODE));
    }

    @Test
    public void testCreateOption() {
        setUpAuthorization();

        final FreeStyleProject[] itemsToMove = {freeRoot, freeInOrigin, freeInChild};

        // -create without login
        CLICommandInvoker invoker = createInvoker(true, null, DST_FOLDER, itemsToMove);
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, failedWith(PERMISSION_CLI_ERROR_CODE));
        assertThat(result.stderr(), containsString("ERROR: anonymous is missing the Overall/Read permission"));

        // -create as admin. Single folder
        invoker = createInvoker(true, ADMIN_USER, DST_FOLDER, itemsToMove);
        result = invoker.invoke();
        assertThat(result, succeeded());
        TopLevelItem found = rule.jenkins.getItem(DST_FOLDER);
        assertNotNull(found);
        assertThat(found, instanceOf(Folder.class));

        // -create as admin. Tree
        invoker = createInvoker(true, ADMIN_USER, "toCreate0/toCreate1/toCreate2", itemsToMove);
        result = invoker.invoke();
        assertThat(result, succeeded());
        found = rule.jenkins.getItem("toCreate0");
        assertNotNull(found);
        assertThat(found, instanceOf(Folder.class));
        found = ((Folder)found).getItem("toCreate1");
        assertNotNull(found);
        assertThat(found, instanceOf(Folder.class));
        found = ((Folder)found).getItem("toCreate2");
        assertNotNull(found);
        assertThat(found, instanceOf(Folder.class));

        // -create as no-admin*/
        invoker = createInvoker(true, NO_ADMIN_USER, DST_FOLDER, itemsToMove);
        result = invoker.invoke();
        assertThat(result, failedWith(MoveCommand.ERROR_RETURN_CODE));
        assertThat(result.stdout(), containsString("[FAILURE]: Only administrator users can use the '-create' option"));
    }

    @Test
    public void testMoveJobs() throws Exception {
        // Check initial location
        assertNotNull(rule.jenkins.getItem(FREE_IN_ROOT_LEVEL));
        assertNull(rule.jenkins.getItem(FREE_IN_ORIGIN_FOLDER));
        Folder origin = (Folder) rule.jenkins.getItem(ORIGIN_FOLDER);
        assertNull(origin.getItem(FREE_IN_ROOT_LEVEL));
        assertNotNull(origin.getItem(FREE_IN_ORIGIN_FOLDER));

        // Executions
        rule.assertBuildStatusSuccess(freeRoot.scheduleBuild2(0));
        rule.assertBuildStatusSuccess(freeRoot.scheduleBuild2(0));
        assertNotNull(freeRoot.getBuildByNumber(1));
        assertNotNull(freeRoot.getBuildByNumber(2));

        CLICommandInvoker invoker = createInvoker(ORIGIN_FOLDER,new FreeStyleProject[] {freeInOrigin, freeRoot});
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, succeeded());
        assertThat(result.stdout(), containsString(String.format("The item is already in the '%s' folder. Skipping", ORIGIN_FOLDER)));
        assertThat(result.stdout(), containsString(String.format("%s: Successfully moved to '%s'", FREE_IN_ROOT_LEVEL, ORIGIN_FOLDER)));

        // Check it has been moved
        assertNull(rule.jenkins.getItem(FREE_IN_ROOT_LEVEL));
        assertNull(rule.jenkins.getItem(FREE_IN_ORIGIN_FOLDER));
        origin = (Folder) rule.jenkins.getItem(ORIGIN_FOLDER);
        FreeStyleProject moved = (FreeStyleProject) origin.getItem(FREE_IN_ROOT_LEVEL);
        assertNotNull(moved);
        assertNotNull(origin.getItem(FREE_IN_ORIGIN_FOLDER));

        // Executions should have been moved
        assertNotNull(moved.getBuildByNumber(1));
        assertNotNull(moved.getBuildByNumber(2));
        rule.assertBuildStatusSuccess(moved.scheduleBuild2(0));
        assertEquals(3, moved.getLastBuild().getNumber());
    }

    @Test
    public void testMoveFolder() throws Exception {
        Folder dst = rule.jenkins.createProject(Folder.class, DST_FOLDER);
        assertNotNull(rule.jenkins.getItem(DST_FOLDER));

        // Original Folder. Contains job + sub-folder with job
        Folder origin = (Folder) rule.jenkins.getItem(ORIGIN_FOLDER);
        assertNotNull(origin);
        assertNull(dst.getItem(ORIGIN_FOLDER));
        assertNotNull(origin.getItem(FREE_IN_ORIGIN_FOLDER));
        assertNull(dst.getItem(FREE_IN_ORIGIN_FOLDER));

        Folder subFolder = (Folder) origin.getItem(CHILD_FOLDER);
        assertNotNull(subFolder);
        assertNotNull(subFolder.getItem(FREE_IN_CHILD_FOLDER));

        // Executions
        rule.assertBuildStatusSuccess(freeInChild.scheduleBuild2(0));
        rule.assertBuildStatusSuccess(freeInChild.scheduleBuild2(0));
        assertNotNull(freeInChild.getBuildByNumber(1));
        assertNotNull(freeInChild.getBuildByNumber(2));

        CLICommandInvoker invoker = createInvoker(DST_FOLDER,new TopLevelItem[] {origin});
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, succeeded());
        assertThat(result.stdout(), containsString(String.format("%s: Successfully moved to '%s'", ORIGIN_FOLDER, DST_FOLDER)));

        // Check it has been moved
        origin = (Folder) rule.jenkins.getItem(ORIGIN_FOLDER);
        dst = (Folder) rule.jenkins.getItem(DST_FOLDER);
        assertNull(origin);
        assertNotNull(dst);
        assertNotNull(dst.getItem(ORIGIN_FOLDER));

        // now origin is in dst and keep its former structure
        origin = (Folder) dst.getItem(ORIGIN_FOLDER);
        assertNotNull(origin);
        assertNotNull(origin.getItem(FREE_IN_ORIGIN_FOLDER));
        subFolder = (Folder) origin.getItem(CHILD_FOLDER);
        assertNotNull(subFolder);
        FreeStyleProject moved = (FreeStyleProject) subFolder.getItem(FREE_IN_CHILD_FOLDER);
        assertNotNull(moved);

        // Executions should have been moved
        assertNotNull(moved.getBuildByNumber(1));
        assertNotNull(moved.getBuildByNumber(2));
        rule.assertBuildStatusSuccess(moved.scheduleBuild2(0));
        assertEquals(3, moved.getLastBuild().getNumber());
    }

    @Test
    public void testMoveToRoot() {
        // Check initial location
        assertNull(rule.jenkins.getItem(FREE_IN_ORIGIN_FOLDER));
        Folder origin = (Folder) rule.jenkins.getItem(ORIGIN_FOLDER);
        assertNotNull(origin.getItem(FREE_IN_ORIGIN_FOLDER));

        CLICommandInvoker invoker = createInvoker("jenkins",new FreeStyleProject[] {freeInOrigin});
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, succeeded());
        assertThat(result.stdout(), containsString(String.format("%s: Successfully moved to 'jenkins'", FREE_IN_ORIGIN_FOLDER, ORIGIN_FOLDER)));

        // Check it has been moved
        assertNotNull(rule.jenkins.getItem(FREE_IN_ORIGIN_FOLDER));
        origin = (Folder) rule.jenkins.getItem(ORIGIN_FOLDER);
        assertNull(origin.getItem(FREE_IN_ORIGIN_FOLDER));
    }

    private CLICommandInvoker createInvoker(String destinationFolder, TopLevelItem... itemsToMove) {
        return createInvoker(null, destinationFolder, itemsToMove);
    }

    private CLICommandInvoker createInvoker(String user, String destinationFolder, TopLevelItem... itemsToMove) {
        return createInvoker(false, user, destinationFolder, itemsToMove);
    }

    private CLICommandInvoker createInvoker(boolean create, String user, String destinationFolder, TopLevelItem... itemsToMove) {
        List<String> arguments = new ArrayList<>();

        if (StringUtils.isNotBlank(destinationFolder)) {
            arguments.add(destinationFolder);
        }

        if (ArrayUtils.isNotEmpty(itemsToMove)) {
            arguments.addAll(Arrays.stream(itemsToMove).map(TopLevelItem::getFullName).collect(Collectors.toList()));
        }

        if (create) {
            arguments.add("-create");
        }

        CLICommandInvoker invoker = new CLICommandInvoker(rule, "move");

        if (StringUtils.isNotBlank(user)) {
            invoker.asUser(user);
        }

        if (!arguments.isEmpty()) {
            invoker.withArgs(arguments.toArray(new String[arguments.size()]));
        }

        return invoker;
    }

    private void setUpAuthorization() {
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        AuthorizationStrategy strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN_USER)
                .grant(Jenkins.READ, Item.READ).everywhere().to(NO_ADMIN_USER);
        rule.jenkins.setAuthorizationStrategy(strategy);
    }
}