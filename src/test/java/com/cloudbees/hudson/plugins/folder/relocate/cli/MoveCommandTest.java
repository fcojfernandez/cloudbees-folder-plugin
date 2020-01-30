package com.cloudbees.hudson.plugins.folder.relocate.cli;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.cli.CLICommandInvoker;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.security.AuthorizationStrategy;
import jenkins.model.Jenkins;
import org.apache.commons.collections.CollectionUtils;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class MoveCommandTest {

    private static final int STANDARD_CLI_ERROR_CODE = 2;
    private static final int PERMISSION_CLI_ERROR_CODE = 6;

    private static final String DST_FOLDER = "dstFolder";
    private static final String ORG_FOLDER = "originFolder";
    private static final String CHILD_FOLDER = "childFolder";
    private static final String FREE_IN_ROOT_LEVEL = "freeRoot";
    private static final String FREE_IN_ORG_FOLDER = "freeInOrigin";
    private static final String FREE_IN_CHILD_FOLDER = "freeInChild";

    private static final String ADMIN_USER = "admin";
    private static final String NO_ADMIN_USER = "no-admin";

    private Folder dst;
    private Folder origin;
    private Folder child;
    private FreeStyleProject freeRoot;
    private FreeStyleProject freeInOrg;
    private FreeStyleProject freeInChild;

    @Rule
    public JenkinsRule rule = new JenkinsRule();

    @Before
    public void setUp() throws IOException {
        dst = rule.jenkins.createProject(Folder.class, DST_FOLDER);
        origin = rule.jenkins.createProject(Folder.class, ORG_FOLDER);
        child = origin.createProject(Folder.class, CHILD_FOLDER);

        freeRoot = rule.jenkins.createProject(FreeStyleProject.class, FREE_IN_ROOT_LEVEL);
        freeInOrg = origin.createProject(FreeStyleProject.class, FREE_IN_ORG_FOLDER);
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
        invoker = createInvoker(null, Arrays.asList(projects));
        result = invoker.invoke();
        assertThat(result, failedWith(STANDARD_CLI_ERROR_CODE));
    }

    @Test
    public void testPermissions() {
        setUpAuthorization();

        final FreeStyleProject[] projects = {freeRoot, freeInOrg, freeInChild};
        final List<FreeStyleProject> itemsToMove = Arrays.asList(projects);

        // move without login - Handled by Jenkins CLI
        CLICommandInvoker invoker = createInvoker(DST_FOLDER, itemsToMove, null);
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, failedWith(6));

        // move with permissions
        invoker = createInvoker(DST_FOLDER, itemsToMove, ADMIN_USER);
        result = invoker.invoke();
        assertThat(result, succeeded());

        // move without permission - Handled by Folder CLI
        invoker = createInvoker(DST_FOLDER, itemsToMove, NO_ADMIN_USER);
        result = invoker.invoke();
        assertThat(result, failedWith(MoveCommand.VALIDATIONS_ERROR_CODE));
    }

    @Test
    public void testCreateOption() {
        setUpAuthorization();

        final FreeStyleProject[] projects = {freeRoot, freeInOrg, freeInChild};
        final List<FreeStyleProject> itemsToMove = Arrays.asList(projects);

        // -create without login
        CLICommandInvoker invoker = createInvoker(DST_FOLDER, itemsToMove, null);
        CLICommandInvoker.Result result = invoker.invoke();
        assertThat(result, failedWith(PERMISSION_CLI_ERROR_CODE));
        assertThat(result.stderr(), containsString("ERROR: anonymous is missing the Overall/Read permission"));

        // -create as admin. Single folder
        invoker = createInvoker("toCreate", itemsToMove, ADMIN_USER, true);
        result = invoker.invoke();
        assertThat(result, succeeded());
        TopLevelItem found = rule.jenkins.getItem("toCreate");
        assertNotNull(found);
        assertThat(found, instanceOf(Folder.class));

        // -create as admin. Tree
        invoker = createInvoker("toCreate0/toCreate1/toCreate2", itemsToMove, ADMIN_USER, true);
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
        invoker = createInvoker(DST_FOLDER, itemsToMove, NO_ADMIN_USER, true);
        result = invoker.invoke();
        assertThat(result, failedWith(MoveCommand.ERROR_RETURN_CODE));
        assertThat(result.stdout(), containsString("[FAILURE]: Only administrator users can use the '-create' option"));
    }

    private CLICommandInvoker createInvoker(String destinationFolder, List<FreeStyleProject> itemsToMove) {
        return createInvoker(destinationFolder, itemsToMove, null);
    }

    private CLICommandInvoker createInvoker(String destinationFolder, List<FreeStyleProject> itemsToMove, String user) {
        return createInvoker(destinationFolder, itemsToMove, user, false);
    }

    private CLICommandInvoker createInvoker(String destinationFolder, List<FreeStyleProject> itemsToMove, String user, boolean create) {
        List<String> arguments = new ArrayList<>();

        if (StringUtils.isNotBlank(destinationFolder)) {
            arguments.add(destinationFolder);
        }

        if (CollectionUtils.isNotEmpty(itemsToMove)) {
            arguments.addAll(itemsToMove.stream().map(FreeStyleProject::getFullName).collect(Collectors.toList()));
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